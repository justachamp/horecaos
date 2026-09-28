package uz.horecaos.platform.reporting.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalAction;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcBusinessCalendarStore;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcBusinessCalendarStore.TenantHoliday;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * 10.10b: the settings screen over a tenant's own trading calendar — its
 * weekend, its own holidays, and the business-day boundary ADR 0043 built
 * with no production caller.
 *
 * <p>{@link BusinessDayService#setBoundary} is deliberately mechanism, not
 * policy: its own Javadoc leaves the ADR 0027 approval to the caller. This is
 * that caller, and it gates a boundary change the same way {@code
 * TenantProfileService.changeCountry} gates a residency change — first call
 * raises the request and answers "waiting", a second answers "changed" and
 * spends the signature — except the missing-policy default is permissive
 * (see {@link ApprovalAction#REPORTING_BUSINESS_DAY_BOUNDARY_CHANGE}'s own
 * Javadoc for why this and country-change differ).
 */
@Service
public class BusinessCalendarService {

    private final JdbcBusinessCalendarStore calendars;
    private final BusinessDayService businessDays;
    private final ApprovalService approvals;
    private final AuditRecorder audit;
    private final Clock clock;

    public BusinessCalendarService(
            JdbcBusinessCalendarStore calendars,
            BusinessDayService businessDays,
            ApprovalService approvals,
            AuditRecorder audit,
            Clock clock) {
        this.calendars = calendars;
        this.businessDays = businessDays;
        this.approvals = approvals;
        this.audit = audit;
        this.clock = clock;
    }

    /** Everything the settings screen shows before anyone opens an editor. */
    public Calendar calendar(UUID tenantId) {
        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        Optional<LocalDate> recutCompletedThrough = businessDays.recutCompletedThrough(tenantId);
        return new Calendar(
                boundary.zone().getId(),
                boundary.start(),
                boundary.version(),
                recutCompletedThrough.orElse(null),
                calendars.weekendDays(tenantId),
                calendars.holidays(tenantId));
    }

    /**
     * Moves the boundary, once a second person has signed it (or immediately,
     * while no tenant policy asks for one — see the class Javadoc).
     *
     * <p>Marks a recut outstanding ({@code recutCompletedThrough = null})
     * rather than performing one: recutting every stored business date is
     * {@code DayCloseService}'s job, run at its own pace against production
     * volumes, not something a settings-screen request can do inline.
     */
    @Transactional
    public BoundaryChange changeBoundary(UUID tenantId, LocalTime newStart, ActorRef actor, String reason) {
        BusinessDayBoundary current = businessDays.boundaryFor(tenantId);
        if (current.start().equals(newStart)) {
            return new BoundaryChange(BoundaryChange.UNCHANGED, null);
        }

        ApprovalOutcome approval = approvals.requireApproval(new ApprovalRequestCommand(
                ApprovalAction.REPORTING_BUSINESS_DAY_BOUNDARY_CHANGE.code(),
                hash(tenantId + ":" + newStart),
                ResourceScope.tenant(tenantId),
                actor,
                reason,
                ApprovalRequestCommand.DEFAULT_VALIDITY));
        if (approval instanceof ApprovalOutcome.Declined declined) {
            return new BoundaryChange(BoundaryChange.DECLINED, declined.requestId());
        }
        if (!approval.mayProceed()) {
            UUID requestId = approval instanceof ApprovalOutcome.Pending pending ? pending.requestId() : null;
            return new BoundaryChange(BoundaryChange.AWAITING_APPROVAL, requestId);
        }
        approval.consume();

        LocalDate effectiveFrom = current.dateOf(clock.instant()).plusDays(1);
        businessDays.setBoundary(
                tenantId,
                new BusinessDayBoundary(current.zone(), newStart, current.version() + 1),
                effectiveFrom,
                null);

        audit.record(AuditFact.of("reporting.business-day-boundary.changed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("BusinessDayBoundary", tenantId)
                .because(reason)
                // Staff 9.3a: "start" genuinely moves from current.start() to
                // newStart; effectiveFrom is unchanged identifying context (the
                // date the new start takes hold), carried on both sides.
                .changed(ChangeDocuments.diff(
                        boundaryDiffMap(current.start(), effectiveFrom), boundaryDiffMap(newStart, effectiveFrom)))
                .usingCapability(Capability.TENANT_CONFIGURATION_WRITE.code())
                .correlatedBy(tenantId.toString())
                .occurredAt(clock.instant())
                .build());
        UUID requestId = approval instanceof ApprovalOutcome.Approved approved ? approved.requestId() : null;
        return new BoundaryChange(BoundaryChange.CHANGED, requestId);
    }

    @Transactional
    public void setWeekendDays(UUID tenantId, List<Integer> weekendDays, ActorRef actor, String reason) {
        for (Integer day : weekendDays) {
            if (day == null || day < 1 || day > 7) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "A weekday is 1 (Monday) through 7 (Sunday)");
            }
        }
        // Staff 9.3a: read before the write so "weekendDays" has a real prior
        // value to diff against, not just the new list restated as "after".
        List<Integer> before = calendars.weekendDays(tenantId);
        calendars.setWeekendDays(tenantId, List.copyOf(weekendDays));
        audit.record(AuditFact.of("reporting.business-calendar.weekend-set", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("BusinessCalendar", tenantId)
                .because(reason)
                .changed(ChangeDocuments.change("weekendDays", before.toString(), weekendDays.toString()))
                .usingCapability(Capability.TENANT_CONFIGURATION_WRITE.code())
                .correlatedBy(tenantId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    @Transactional
    public UUID addHoliday(
            UUID tenantId,
            String name,
            @Nullable Integer month,
            @Nullable Integer day,
            @Nullable LocalDate date,
            ActorRef actor,
            String reason) {
        boolean recurring = month != null && day != null && date == null;
        boolean dated = date != null && month == null && day == null;
        if (!recurring && !dated) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A holiday is a month and day every year, or one date");
        }
        if (month != null && day != null) {
            try {
                MonthDay.of(month, day);
            } catch (DateTimeException invalid) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "There is no such day in the year");
            }
        }
        UUID id = Ids.newId();
        try {
            calendars.insertHoliday(tenantId, new TenantHoliday(id, name, month, day, date), subject(actor));
        } catch (DuplicateKeyException already) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "That day is already on this tenant's calendar");
        }
        Instant now = clock.instant();
        audit.record(AuditFact.of("reporting.business-calendar.holiday-added", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("TenantHoliday", id)
                .because(reason)
                // Staff 9.3a: a newly added holiday has no prior state to diff
                // against.
                .changed(ChangeDocuments.created(Map.of("name", name)))
                .usingCapability(Capability.TENANT_CONFIGURATION_WRITE.code())
                .correlatedBy(id.toString())
                .occurredAt(now)
                .build());
        return id;
    }

    @Transactional
    public void removeHoliday(UUID tenantId, UUID holidayId, ActorRef actor, String reason) {
        TenantHoliday holiday = calendars
                .findHoliday(tenantId, holidayId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such holiday"));
        calendars.deleteHoliday(tenantId, holidayId);
        audit.record(AuditFact.of("reporting.business-calendar.holiday-removed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("TenantHoliday", holidayId)
                .because(reason)
                // Staff 9.3a: "name" genuinely moves from the removed holiday's
                // name to nothing -- the row is gone.
                .changed(ChangeDocuments.change("name", holiday.name(), null))
                .usingCapability(Capability.TENANT_CONFIGURATION_WRITE.code())
                .correlatedBy(holidayId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    /** A {@code {start, effectiveFrom}} snapshot for {@link #changeBoundary}'s diff. */
    private static Map<String, Object> boundaryDiffMap(LocalTime start, LocalDate effectiveFrom) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("start", start.toString());
        map.put("effectiveFrom", effectiveFrom.toString());
        return map;
    }

    private static String subject(ActorRef actor) {
        return actor.subject() == null ? "" : actor.subject();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unreachable) {
            throw new IllegalStateException("SHA-256 is required", unreachable);
        }
    }

    /** What a business-day boundary change did: changed, waiting, declined, or a no-op. */
    public record BoundaryChange(String status, @Nullable UUID approvalRequestId) {
        public static final String CHANGED = "CHANGED";
        public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";
        public static final String DECLINED = "DECLINED";
        public static final String UNCHANGED = "UNCHANGED";
    }

    /**
     * @param recutCompletedThrough null when no boundary change is outstanding
     */
    public record Calendar(
            String timezone,
            LocalTime businessDayStart,
            int boundaryVersion,
            @Nullable LocalDate recutCompletedThrough,
            List<Integer> weekendDays,
            List<TenantHoliday> holidays) {}
}
