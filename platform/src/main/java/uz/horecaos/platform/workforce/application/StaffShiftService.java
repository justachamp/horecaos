package uz.horecaos.platform.workforce.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.workforce.api.AttendanceBusinessDay;
import uz.horecaos.platform.workforce.application.StaffPeople.Person;
import uz.horecaos.platform.workforce.domain.ReasonCode;
import uz.horecaos.platform.workforce.domain.ShiftArithmetic;
import uz.horecaos.platform.workforce.domain.ShiftSource;
import uz.horecaos.platform.workforce.domain.ShiftStatus;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffRosterStore;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffRosterStore.EntryRow;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore.ShiftRow;

/**
 * The timesheet (ADR 0156 Decisions 4 to 9): opening, closing, correcting, voiding and approving a
 * staff shift, by the person at a branch or by a manager for someone at a branch they manage.
 *
 * <p><strong>A timesheet and never a permission.</strong> Nothing here, and no caller of it, refuses an
 * order action, a kitchen action or a sign-in because no shift is open; a clock-in needs no published
 * roster entry, so the Friday with no rota still has hours.
 *
 * <p><strong>Time is the server's for a person and the request's for a manager.</strong> A self clock
 * event takes its time from the clock and never from the request. A manager's entry names its time,
 * and when that time is in the past beyond a short grace it carries a reason from the closed {@link
 * ReasonCode} list. Time is corrected, never overwritten: every correction is a conditional write on
 * the shift's version with an ADR 0027 fact holding the old and new times, and a shift older than
 * {@code workforce.shift.retroactive_edit_days} can be changed only by a holder of {@code
 * staff.shift.approve}.
 *
 * <p><strong>Variance is judged at close and approved by a person who looked.</strong> At close the
 * shift is matched to its roster entry (through the id it was opened against, never by searching
 * again), {@code variance_seconds} is paid minus planned (null when there is no plan), the resolved
 * threshold is snapshotted onto the shift, and over it the shift waits in {@code AWAITING_APPROVAL},
 * out of the payroll fact, until a holder of {@code staff.shift.approve} approves it with a reason,
 * one shift at a time. Nothing is closed with invented hours: a shift left open is flagged, never
 * closed by a job.
 */
@Service
public class StaffShiftService {

    /** How far in the past a manager's entry may name a time before it needs a reason. */
    static final Duration PAST_GRACE = Duration.ofMinutes(5);

    /** How far ahead of the clock a recorded time may be: a clock-skew allowance, not a way to pre-record hours. */
    static final Duration FUTURE_GRACE = Duration.ofMinutes(5);

    private final JdbcStaffShiftStore shifts;
    private final JdbcStaffRosterStore roster;
    private final StaffPeople people;
    private final AttendanceSettings settings;
    private final AttendanceBusinessDay businessDays;
    private final AuthorizationService authorization;
    private final WorkforceAudit audit;
    private final Clock clock;

    public StaffShiftService(
            JdbcStaffShiftStore shifts,
            JdbcStaffRosterStore roster,
            StaffPeople people,
            AttendanceSettings settings,
            AttendanceBusinessDay businessDays,
            AuthorizationService authorization,
            WorkforceAudit audit,
            Clock clock) {
        this.shifts = shifts;
        this.roster = roster;
        this.people = people;
        this.settings = settings;
        this.businessDays = businessDays;
        this.authorization = authorization;
        this.audit = audit;
        this.clock = clock;
    }

    /** A shift as a screen shows it; {@code person} carries a name only when the caller may see names. */
    public record ShiftView(ShiftRow shift, @Nullable Person person) {}

    // ============================================================================ a manager's acts

    /** A manager records that someone started, at a branch they manage. */
    @Transactional
    public ShiftView openFor(
            Branch branch,
            UUID memberId,
            Instant startedAt,
            @Nullable ReasonCode reason,
            String actor,
            String correlationId) {
        settings.requireEnabled(branch.tenantId());
        Person person = people.eligibleAt(branch, memberId, false)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such staff member."));
        Instant now = clock.instant();
        requireNotInTheFuture(startedAt, now);
        requireReasonWhenPast(startedAt, now, reason);
        requireWithinEditWindow(branch, startedAt, actor);
        ShiftRow opened = open(branch, memberId, startedAt, ShiftSource.CONSOLE_MANAGER, actor, actor, reason, now);
        audit.recordCreated(
                "workforce.shift.opened",
                branch,
                actor,
                "workforce_staff_shift",
                opened.id(),
                reason == null ? "A manager recorded a shift start" : reason.name(),
                openedDocument(person, opened),
                "staff.shift.manage",
                correlationId,
                now);
        return new ShiftView(opened, person);
    }

    /** A manager records that someone finished. */
    @Transactional
    public ShiftView closeFor(
            Branch branch,
            UUID shiftId,
            int expectedVersion,
            Instant endedAt,
            @Nullable Integer breakSeconds,
            @Nullable ReasonCode reason,
            String actor,
            String correlationId) {
        settings.requireEnabled(branch.tenantId());
        ShiftRow before = find(branch, shiftId);
        if (before.status() != ShiftStatus.OPEN) {
            throw notOpen(before);
        }
        Instant now = clock.instant();
        requireNotInTheFuture(endedAt, now);
        requireReasonWhenPast(endedAt, now, reason);
        requireWithinEditWindow(branch, before.startedAt(), actor);
        ShiftRow next = settle(branch, before, before.startedAt(), endedAt, breakSeconds, ShiftSource.CONSOLE_MANAGER);
        next = withEdit(next, actor, reason);
        save(next, expectedVersion, before);
        recordClosed(
                branch,
                actor,
                before,
                next,
                reason == null ? "A manager recorded a shift end" : reason.name(),
                correlationId,
                now);
        return new ShiftView(reload(next), person(branch, next));
    }

    /** A manager changes a shift's times or break. Needs a reason; the old and new times are the fact. */
    @Transactional
    public ShiftView correct(
            Branch branch,
            UUID shiftId,
            int expectedVersion,
            @Nullable Instant startedAt,
            @Nullable Instant endedAt,
            @Nullable Integer breakSeconds,
            ReasonCode reason,
            String actor,
            String correlationId) {
        settings.requireEnabled(branch.tenantId());
        ShiftRow before = find(branch, shiftId);
        if (before.status() == ShiftStatus.VOIDED) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "A voided shift cannot be changed",
                    Map.of("status", before.status().name()));
        }
        if (startedAt == null && endedAt == null && breakSeconds == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Name at least one of the start, the end or the break", Map.of());
        }
        Instant now = clock.instant();
        Instant newStart = startedAt == null ? before.startedAt() : startedAt;
        @Nullable Instant newEnd = endedAt == null ? before.endedAt() : endedAt;
        requireNotInTheFuture(newStart, now);
        if (newEnd != null) {
            requireNotInTheFuture(newEnd, now);
        }
        requireWithinEditWindow(branch, before.startedAt(), actor);
        requireWithinEditWindow(branch, newStart, actor);
        if (before.status() == ShiftStatus.OPEN && newEnd == null && breakSeconds != null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A break is recorded when the shift ends",
                    Map.of("field", "breakSeconds"));
        }
        ShiftRow next = newEnd == null
                ? withStart(before, newStart)
                : settle(
                        branch,
                        before,
                        newStart,
                        newEnd,
                        breakSeconds,
                        before.closeSource() == null ? ShiftSource.CONSOLE_MANAGER : before.closeSource());
        next = withEdit(next, actor, reason);
        // The hours changed, so any earlier approval of the old hours no longer stands.
        next = withoutApproval(next);
        save(next, expectedVersion, before);
        audit.recordChange(
                "workforce.shift.corrected",
                branch,
                actor,
                "workforce_staff_shift",
                shiftId,
                reason.name(),
                timesDocument(person(branch, before), before),
                timesDocument(person(branch, next), next),
                "staff.shift.manage",
                correlationId,
                now);
        return new ShiftView(reload(next), person(branch, next));
    }

    /** A mistaken shift is voided with a reason and never deleted. */
    @Transactional
    public ShiftView voidShift(
            Branch branch,
            UUID shiftId,
            @Nullable Integer expectedVersion,
            ReasonCode reason,
            String actor,
            String correlationId) {
        settings.requireEnabled(branch.tenantId());
        ShiftRow before = find(branch, shiftId);
        if (before.status() == ShiftStatus.VOIDED) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "The shift is already voided",
                    Map.of("status", before.status().name()));
        }
        requireWithinEditWindow(branch, before.startedAt(), actor);
        Instant now = clock.instant();
        ShiftRow next = new ShiftRow(
                before.id(),
                before.tenantId(),
                before.brandId(),
                before.locationId(),
                before.memberId(),
                before.rosterEntryId(),
                ShiftStatus.VOIDED,
                before.startedAt(),
                before.endedAt(),
                before.openSource(),
                before.closeSource(),
                before.breakSeconds(),
                before.workedSeconds(),
                before.paidSeconds(),
                before.plannedSeconds(),
                before.varianceSeconds(),
                before.varianceThresholdMinutes(),
                before.businessDate(),
                before.editReasonCode(),
                actor,
                before.approvedBy(),
                before.approvedAt(),
                before.approvalReasonCode(),
                actor,
                now,
                reason.name(),
                before.version(),
                before.createdBy(),
                before.createdAt(),
                before.updatedAt());
        save(next, expectedVersion == null ? before.version() : expectedVersion, before);
        if (before.rosterEntryId() != null) {
            // The only shift against a consumed entry was a mistake: the entry is a plan again.
            roster.reopenConsumed(branch.tenantId(), before.rosterEntryId());
        }
        Map<String, Object> beforeDoc = new LinkedHashMap<>();
        beforeDoc.put("status", before.status().name());
        Map<String, Object> afterDoc = new LinkedHashMap<>();
        afterDoc.put("status", ShiftStatus.VOIDED.name());
        afterDoc.put("staffRef", reference(branch, before));
        audit.recordChange(
                "workforce.shift.voided",
                branch,
                actor,
                "workforce_staff_shift",
                shiftId,
                reason.name(),
                beforeDoc,
                afterDoc,
                "staff.shift.manage",
                correlationId,
                now);
        return new ShiftView(reload(next), person(branch, next));
    }

    /**
     * Approves one shift whose variance was over the threshold, with a reason. Never a bulk act: its
     * guarantee is that a person looked at this shift. The approver may be the person who edited the
     * hours unless the tenant sets {@code workforce.shift.approver_must_differ}; the fact records both.
     */
    @Transactional
    public ShiftView approve(
            Branch branch, UUID shiftId, int expectedVersion, ReasonCode reason, String actor, String correlationId) {
        settings.requireEnabled(branch.tenantId());
        ShiftRow before = find(branch, shiftId);
        if (before.status() != ShiftStatus.AWAITING_APPROVAL) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Only a shift waiting for approval can be approved",
                    Map.of("status", before.status().name()));
        }
        if (settings.approverMustDiffer(branch.tenantId()) && actor.equals(before.lastEditedBy())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "A different person must approve hours than edited them",
                    Map.of("reason", "APPROVER_MUST_DIFFER"));
        }
        Instant now = clock.instant();
        ShiftRow next = new ShiftRow(
                before.id(),
                before.tenantId(),
                before.brandId(),
                before.locationId(),
                before.memberId(),
                before.rosterEntryId(),
                ShiftStatus.CLOSED,
                before.startedAt(),
                before.endedAt(),
                before.openSource(),
                before.closeSource(),
                before.breakSeconds(),
                before.workedSeconds(),
                before.paidSeconds(),
                before.plannedSeconds(),
                before.varianceSeconds(),
                before.varianceThresholdMinutes(),
                before.businessDate(),
                before.editReasonCode(),
                before.lastEditedBy(),
                actor,
                now,
                reason.name(),
                null,
                null,
                null,
                before.version(),
                before.createdBy(),
                before.createdAt(),
                before.updatedAt());
        save(next, expectedVersion, before);
        Map<String, Object> beforeDoc = new LinkedHashMap<>();
        beforeDoc.put("status", ShiftStatus.AWAITING_APPROVAL.name());
        Map<String, Object> afterDoc = new LinkedHashMap<>();
        afterDoc.put("status", ShiftStatus.CLOSED.name());
        afterDoc.put("staffRef", reference(branch, before));
        afterDoc.put("varianceSeconds", before.varianceSeconds());
        afterDoc.put("paidSeconds", before.paidSeconds());
        // Both people: the approver is the fact's actor; the last editor is named by reference only.
        afterDoc.put("lastEditedBySelf", actor.equals(before.lastEditedBy()));
        audit.recordChange(
                "workforce.shift.approved",
                branch,
                actor,
                "workforce_staff_shift",
                shiftId,
                reason.name(),
                beforeDoc,
                afterDoc,
                "staff.shift.approve",
                correlationId,
                now);
        return new ShiftView(reload(next), person(branch, next));
    }

    // ============================================================================== a person's own

    /** The caller starts their own shift at a branch where they hold a job. The time is the server's. */
    @Transactional
    public ShiftView clockIn(UUID tenantId, String subject, UUID locationId, String correlationId) {
        settings.requireEnabled(tenantId);
        Branch branch = branchOrAbsent(tenantId, locationId);
        UUID memberId = people.memberIdOf(tenantId, subject).orElseThrow(StaffShiftService::noSuchBranch);
        if (!authorization.has(subject, Capability.STAFF_SHIFT_CLOCK, branch.scope())) {
            throw noSuchBranch();
        }
        Person person = people.eligibleAt(branch, memberId, false).orElseThrow(StaffShiftService::noSuchBranch);
        Instant now = clock.instant();
        ShiftRow opened = open(branch, memberId, now, ShiftSource.CONSOLE_SELF, subject, null, null, now);
        audit.recordCreated(
                "workforce.shift.opened",
                branch,
                subject,
                "workforce_staff_shift",
                opened.id(),
                "The person clocked in",
                openedDocument(person, opened),
                "staff.shift.clock",
                correlationId,
                now);
        return new ShiftView(opened, person);
    }

    /** The caller ends their own open shift, wherever it is. The time is the server's. */
    @Transactional
    public ShiftView clockOut(UUID tenantId, String subject, String correlationId) {
        settings.requireEnabled(tenantId);
        UUID memberId = people.memberIdOf(tenantId, subject).orElseThrow(StaffShiftService::noSuchBranch);
        ShiftRow before = shifts.findOpenFor(tenantId, memberId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        "There is no open shift to end",
                        Map.of("reason", "NO_OPEN_SHIFT")));
        Branch branch = new Branch(tenantId, before.brandId(), before.locationId());
        Instant now = clock.instant();
        Instant endedAt =
                now.isAfter(before.startedAt()) ? now : before.startedAt().plusSeconds(1);
        if (!ShiftArithmetic.isSaneSpan(before.startedAt(), endedAt)) {
            // A shift left open past the schema's sanity bound is a manager's to resolve: nothing closes
            // it with invented hours, and a person cannot end it with a time they did not work.
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "This shift has been open too long to end here. Ask a manager to correct it.",
                    Map.of("reason", "SHIFT_TOO_LONG"));
        }
        ShiftRow next = settle(branch, before, before.startedAt(), endedAt, null, ShiftSource.CONSOLE_SELF);
        save(next, before.version(), before);
        recordClosed(branch, subject, before, next, "The person clocked out", correlationId, now);
        return new ShiftView(reload(next), person(branch, next));
    }

    // =================================================================================== internals

    private ShiftRow open(
            Branch branch,
            UUID memberId,
            Instant startedAt,
            ShiftSource source,
            String createdBy,
            @Nullable String editedBy,
            @Nullable ReasonCode reason,
            Instant now) {
        UUID tenantId = branch.tenantId();
        shifts.findOpenFor(tenantId, memberId).ifPresent(open -> {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This person already has an open shift.",
                    Map.of("reason", "SHIFT_ALREADY_OPEN", "shiftId", open.id().toString()));
        });
        // Matched to the nearest published entry of the same person and branch within the window, and
        // the entry becomes CONSUMED in the same transaction (ADR 0156, Lifecycle and arithmetic). An
        // entry the sweeper already marked MISSED still counts: hours recorded after the fact meet it.
        @Nullable
        UUID entryId = roster.nearestUncovered(
                        tenantId, branch.locationId(), memberId, startedAt, settings.matchWindowMinutes(tenantId))
                .filter(entry -> roster.markConsumed(tenantId, entry.id()) == 1)
                .map(EntryRow::id)
                .orElse(null);
        ShiftRow shift = new ShiftRow(
                Ids.newId(),
                tenantId,
                branch.brandId(),
                branch.locationId(),
                memberId,
                entryId,
                ShiftStatus.OPEN,
                startedAt,
                null,
                source,
                null,
                0,
                null,
                null,
                null,
                null,
                null,
                businessDays.businessDateOf(tenantId, startedAt),
                reason == null ? null : reason.name(),
                editedBy,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                createdBy,
                now,
                now);
        try {
            shifts.insert(shift);
        } catch (DataIntegrityViolationException failure) {
            throw overlap();
        }
        return shift;
    }

    /**
     * The shift with its hours computed: worked, paid, planned (from the entry it was opened against),
     * variance (null when there is none), the threshold resolved now and the status it earns.
     */
    private ShiftRow settle(
            Branch branch,
            ShiftRow shift,
            Instant startedAt,
            Instant endedAt,
            @Nullable Integer breakSeconds,
            ShiftSource closeSource) {
        if (!ShiftArithmetic.isSaneSpan(startedAt, endedAt)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A shift ends after it starts and lasts at most " + ShiftArithmetic.MAXIMUM_SPAN.toHours()
                            + " hours",
                    Map.of("field", "endedAt"));
        }
        long worked = ShiftArithmetic.workedSeconds(startedAt, endedAt);
        @Nullable
        EntryRow entry = shift.rosterEntryId() == null
                ? null
                : roster.findById(branch.tenantId(), shift.rosterEntryId()).orElse(null);
        long breakResolved;
        if (breakSeconds != null) {
            breakResolved = breakSeconds;
        } else if (shift.endedAt() != null) {
            breakResolved = shift.breakSeconds();
        } else if (entry != null) {
            // The break comes from the matched entry unless a manager sets it; it cannot exceed the shift.
            breakResolved = Math.min(entry.plannedBreakMinutes() * 60L, worked);
        } else {
            breakResolved = 0;
        }
        if (breakResolved < 0 || breakResolved > worked) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A break is between zero and the shift's own length",
                    Map.of("field", "breakSeconds"));
        }
        long paid = ShiftArithmetic.paidSeconds(worked, breakResolved);
        Long planned = entry == null
                ? null
                : ShiftArithmetic.plannedSeconds(entry.plannedStart(), entry.plannedEnd(), entry.plannedBreakMinutes());
        Long variance = ShiftArithmetic.varianceSeconds(paid, planned);
        int threshold = settings.varianceThresholdMinutes(branch);
        ShiftStatus status =
                ShiftArithmetic.needsApproval(variance, threshold) ? ShiftStatus.AWAITING_APPROVAL : ShiftStatus.CLOSED;
        return new ShiftRow(
                shift.id(),
                shift.tenantId(),
                shift.brandId(),
                shift.locationId(),
                shift.memberId(),
                shift.rosterEntryId(),
                status,
                startedAt,
                endedAt,
                shift.openSource(),
                closeSource,
                (int) breakResolved,
                worked,
                paid,
                planned,
                variance,
                threshold,
                businessDays.businessDateOf(shift.tenantId(), startedAt),
                shift.editReasonCode(),
                shift.lastEditedBy(),
                shift.approvedBy(),
                shift.approvedAt(),
                shift.approvalReasonCode(),
                shift.voidedBy(),
                shift.voidedAt(),
                shift.voidReasonCode(),
                shift.version(),
                shift.createdBy(),
                shift.createdAt(),
                shift.updatedAt());
    }

    private ShiftRow withStart(ShiftRow shift, Instant startedAt) {
        return new ShiftRow(
                shift.id(),
                shift.tenantId(),
                shift.brandId(),
                shift.locationId(),
                shift.memberId(),
                shift.rosterEntryId(),
                shift.status(),
                startedAt,
                shift.endedAt(),
                shift.openSource(),
                shift.closeSource(),
                shift.breakSeconds(),
                shift.workedSeconds(),
                shift.paidSeconds(),
                shift.plannedSeconds(),
                shift.varianceSeconds(),
                shift.varianceThresholdMinutes(),
                businessDays.businessDateOf(shift.tenantId(), startedAt),
                shift.editReasonCode(),
                shift.lastEditedBy(),
                shift.approvedBy(),
                shift.approvedAt(),
                shift.approvalReasonCode(),
                shift.voidedBy(),
                shift.voidedAt(),
                shift.voidReasonCode(),
                shift.version(),
                shift.createdBy(),
                shift.createdAt(),
                shift.updatedAt());
    }

    private static ShiftRow withEdit(ShiftRow shift, String editor, @Nullable ReasonCode reason) {
        return new ShiftRow(
                shift.id(),
                shift.tenantId(),
                shift.brandId(),
                shift.locationId(),
                shift.memberId(),
                shift.rosterEntryId(),
                shift.status(),
                shift.startedAt(),
                shift.endedAt(),
                shift.openSource(),
                shift.closeSource(),
                shift.breakSeconds(),
                shift.workedSeconds(),
                shift.paidSeconds(),
                shift.plannedSeconds(),
                shift.varianceSeconds(),
                shift.varianceThresholdMinutes(),
                shift.businessDate(),
                reason == null ? shift.editReasonCode() : reason.name(),
                editor,
                shift.approvedBy(),
                shift.approvedAt(),
                shift.approvalReasonCode(),
                shift.voidedBy(),
                shift.voidedAt(),
                shift.voidReasonCode(),
                shift.version(),
                shift.createdBy(),
                shift.createdAt(),
                shift.updatedAt());
    }

    private static ShiftRow withoutApproval(ShiftRow shift) {
        return new ShiftRow(
                shift.id(),
                shift.tenantId(),
                shift.brandId(),
                shift.locationId(),
                shift.memberId(),
                shift.rosterEntryId(),
                shift.status(),
                shift.startedAt(),
                shift.endedAt(),
                shift.openSource(),
                shift.closeSource(),
                shift.breakSeconds(),
                shift.workedSeconds(),
                shift.paidSeconds(),
                shift.plannedSeconds(),
                shift.varianceSeconds(),
                shift.varianceThresholdMinutes(),
                shift.businessDate(),
                shift.editReasonCode(),
                shift.lastEditedBy(),
                null,
                null,
                null,
                shift.voidedBy(),
                shift.voidedAt(),
                shift.voidReasonCode(),
                shift.version(),
                shift.createdBy(),
                shift.createdAt(),
                shift.updatedAt());
    }

    private void save(ShiftRow next, int expectedVersion, ShiftRow before) {
        int changed;
        try {
            changed = shifts.update(next, expectedVersion);
        } catch (DataIntegrityViolationException failure) {
            throw overlap();
        }
        if (changed == 0) {
            throw ApiException.staleVersion(expectedVersion, before.version());
        }
    }

    private ShiftRow reload(ShiftRow written) {
        return shifts.find(written.tenantId(), written.id()).orElse(written);
    }

    private void recordClosed(
            Branch branch,
            String actor,
            ShiftRow before,
            ShiftRow next,
            String because,
            String correlationId,
            Instant now) {
        Map<String, Object> beforeDoc = new LinkedHashMap<>();
        beforeDoc.put("status", before.status().name());
        Map<String, Object> afterDoc = new LinkedHashMap<>();
        afterDoc.put("status", next.status().name());
        afterDoc.put("staffRef", reference(branch, next));
        afterDoc.put("endedAt", String.valueOf(next.endedAt()));
        afterDoc.put("breakSeconds", next.breakSeconds());
        afterDoc.put("workedSeconds", next.workedSeconds());
        afterDoc.put("paidSeconds", next.paidSeconds());
        afterDoc.put("varianceSeconds", next.varianceSeconds());
        audit.recordChange(
                "workforce.shift.closed",
                branch,
                actor,
                "workforce_staff_shift",
                next.id(),
                because,
                beforeDoc,
                afterDoc,
                next.closeSource() == ShiftSource.CONSOLE_SELF ? "staff.shift.clock" : "staff.shift.manage",
                correlationId,
                now);
    }

    private static Map<String, Object> openedDocument(Person person, ShiftRow shift) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("staffRef", person.displayReference());
        document.put("source", shift.openSource().name());
        document.put("startedAt", shift.startedAt().toString());
        document.put("businessDate", shift.businessDate().toString());
        document.put("hadRosterEntry", shift.rosterEntryId() != null);
        return document;
    }

    private Map<String, Object> timesDocument(@Nullable Person person, ShiftRow shift) {
        Map<String, Object> document = new LinkedHashMap<>();
        if (person != null) {
            document.put("staffRef", person.displayReference());
        }
        document.put("startedAt", shift.startedAt().toString());
        document.put("endedAt", String.valueOf(shift.endedAt()));
        document.put("breakSeconds", shift.breakSeconds());
        document.put("paidSeconds", shift.paidSeconds());
        document.put("varianceSeconds", shift.varianceSeconds());
        document.put("status", shift.status().name());
        return document;
    }

    private @Nullable Person person(Branch branch, ShiftRow shift) {
        return people.peopleOf(branch.tenantId(), List.of(shift.memberId()), false)
                .get(shift.memberId());
    }

    private String reference(Branch branch, ShiftRow shift) {
        Person person = person(branch, shift);
        return person == null ? "unknown" : person.displayReference();
    }

    private ShiftRow find(Branch branch, UUID shiftId) {
        return shifts.findAtLocation(branch.tenantId(), branch.locationId(), shiftId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such shift."));
    }

    private Branch branchOrAbsent(UUID tenantId, UUID locationId) {
        UUID brandId = shifts.brandOf(tenantId, locationId).orElseThrow(StaffShiftService::noSuchBranch);
        return new Branch(tenantId, brandId, locationId);
    }

    private void requireNotInTheFuture(Instant time, Instant now) {
        if (time.isAfter(now.plus(FUTURE_GRACE))) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Hours cannot be recorded for a time that has not come",
                    Map.of("field", "time"));
        }
    }

    private void requireReasonWhenPast(Instant time, Instant now, @Nullable ReasonCode reason) {
        if (time.isBefore(now.minus(PAST_GRACE)) && reason == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A time in the past needs a reason from the list",
                    Map.of("field", "reasonCode"));
        }
    }

    private void requireWithinEditWindow(Branch branch, Instant time, String actor) {
        Instant cutoff = clock.instant().minus(Duration.ofDays(settings.retroactiveEditDays(branch.tenantId())));
        if (time.isBefore(cutoff) && !authorization.has(actor, Capability.STAFF_SHIFT_APPROVE, branch.scope())) {
            throw new AuthorizationService.AccessDeniedException(Capability.STAFF_SHIFT_APPROVE, branch.scope());
        }
    }

    private static ApiException notOpen(ShiftRow shift) {
        return new ApiException(
                ErrorCode.UNPROCESSABLE_STATE,
                "Only an open shift can be closed",
                Map.of("status", shift.status().name()));
    }

    private static ApiException overlap() {
        return new ApiException(
                ErrorCode.RESOURCE_CONFLICT,
                "This person already has a shift overlapping that time.",
                Map.of("reason", "SHIFT_OVERLAP"));
    }

    private static ApiException noSuchBranch() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such branch.");
    }

    /** The statuses a payroll fact may carry, for callers that need to branch on a shift's settlement. */
    public static Optional<ShiftStatus> parse(String status) {
        try {
            return Optional.of(ShiftStatus.valueOf(status));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
