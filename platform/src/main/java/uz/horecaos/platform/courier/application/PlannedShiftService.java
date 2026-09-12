package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.courier.domain.PlannedShiftStatus;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.ShiftRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.EngagementRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcPlannedShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcPlannedShiftStore.PlannedShiftRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Planning a courier's shift ahead of time, and comparing the plan against
 * what {@link CourierShiftService} recorded actually happened (ADR 0042, IA
 * 3.5, operations gap map row {@code 3.5}).
 *
 * <p>A planned entry never opens a shift and never feeds pay — {@link
 * CourierShiftService}'s own Javadoc states the reason this class does not
 * repeat: paying from a roster pays a courier who did not show up, and the
 * person who wrote the roster is usually the person who would approve the
 * pay. What this class produces is a report, not an instruction: a manager
 * reads it to see who was expected and did not show, or who worked without
 * ever being rostered. Nothing here can switch {@code CourierDispatchGate}'s
 * enforcement into a mode that requires a published, accepted entry —
 * {@code ENFORCED_WITH_ROSTER} is not a value {@link
 * uz.horecaos.platform.courier.domain.ShiftEnforcement} has, and the policy
 * write that would introduce it belongs to a later wave.
 */
@Service
public class PlannedShiftService {

    private final JdbcPlannedShiftStore roster;
    private final JdbcCourierStore couriers;
    private final JdbcCourierShiftStore shifts;
    private final AuditRecorder audit;
    private final Clock clock;

    public PlannedShiftService(
            JdbcPlannedShiftStore roster,
            JdbcCourierStore couriers,
            JdbcCourierShiftStore shifts,
            AuditRecorder audit,
            Clock clock) {
        this.roster = roster;
        this.couriers = couriers;
        this.shifts = shifts;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Drafts a planned shift. Refused when the courier has no live engagement
     * to plan one for — the same precondition {@code CourierShiftService.open}
     * checks before a courier may open a real one, so a roster never plans
     * hours for someone whose engagement has already ended.
     */
    @Transactional
    public PlannedShiftRow draft(NewPlannedShift command) {
        if (!command.plannedEnd().isAfter(command.plannedStart())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A planned shift must end after it starts");
        }
        EngagementRow engagement = couriers.findLiveEngagement(command.tenantId(), command.courierId())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE, "This courier has no live engagement to plan a shift for"));

        UUID id = Ids.newId();
        PlannedShiftRow entry = new PlannedShiftRow(
                id,
                command.tenantId(),
                command.brandId(),
                command.locationId(),
                command.courierId(),
                engagement.id(),
                PlannedShiftStatus.DRAFT,
                command.plannedStart(),
                command.plannedEnd(),
                command.createdBy(),
                null,
                null,
                null,
                1);
        roster.insert(entry);

        audit.record(fact(
                "courier.roster-entry.drafted",
                command.actor(),
                command.tenantId(),
                command.brandId(),
                command.locationId(),
                id,
                command.reason(),
                Map.of(
                        "courierId", command.courierId().toString(),
                        "plannedStart", command.plannedStart().toString(),
                        "plannedEnd", command.plannedEnd().toString())));

        return entry;
    }

    /** Publishes a draft, making it visible as an offer. Nothing consumes that visibility yet — see the class doc. */
    @Transactional
    public void publish(UUID tenantId, UUID id, ActorRef actor, UUID publishedBy, String reason) {
        PlannedShiftRow entry = roster.find(tenantId, id)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such planned shift: " + id));
        if (!roster.publish(tenantId, id, publishedBy, clock.instant())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "This planned shift is %s and cannot be published".formatted(entry.status()));
        }
        audit.record(fact(
                "courier.roster-entry.published",
                actor,
                tenantId,
                entry.brandId(),
                entry.locationId(),
                id,
                reason,
                Map.of("courierId", entry.courierId().toString())));
    }

    /** Cancels a DRAFT or PUBLISHED entry outright. Refused once a courier has answered or the window has passed. */
    @Transactional
    public void cancel(UUID tenantId, UUID id, ActorRef actor, String reason) {
        PlannedShiftRow entry = roster.find(tenantId, id)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such planned shift: " + id));
        if (!roster.cancel(tenantId, id, clock.instant())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "This planned shift is %s and cannot be cancelled".formatted(entry.status()));
        }
        audit.record(fact(
                "courier.roster-entry.cancelled",
                actor,
                tenantId,
                entry.brandId(),
                entry.locationId(),
                id,
                reason,
                Map.of("courierId", entry.courierId().toString())));
    }

    /** The branch's planned shifts, optionally windowed to a period — IA 3.5's roster grid. */
    @Transactional(readOnly = true)
    public List<PlannedShiftRow> atLocation(
            UUID tenantId, UUID brandId, UUID locationId, @Nullable Instant from, @Nullable Instant to, int limit) {
        return roster.atLocation(tenantId, brandId, locationId, from, to, limit);
    }

    /**
     * Planned entries at this branch, each matched against whichever actual
     * shift of the same courier overlaps its window — IA 3.5's
     * planned-versus-actual comparison.
     *
     * <p>The match is computed here, not stored: an interval overlap between
     * two independently-written tables is the honest form of "did the roster
     * hold", and stamping a verdict back onto either row would let a later
     * shift edit silently disagree with a comparison nobody re-ran.
     */
    @Transactional(readOnly = true)
    public List<RosterComparison> comparisonAt(
            UUID tenantId, UUID brandId, UUID locationId, Instant from, Instant to, int limit) {

        List<PlannedShiftRow> entries = roster.atLocation(tenantId, brandId, locationId, from, to, limit);
        List<ShiftRow> actual = shifts.atLocation(tenantId, brandId, locationId, from, to, Math.max(limit, 500));
        Instant now = clock.instant();

        List<RosterComparison> comparisons = new ArrayList<>();
        for (PlannedShiftRow entry : entries) {
            Optional<ShiftRow> matched = actual.stream()
                    .filter(shift -> shift.courierId().equals(entry.courierId()))
                    .filter(shift -> overlaps(entry, shift))
                    .findFirst();
            String coverage =
                    matched.isPresent() ? "COVERED" : now.isBefore(entry.plannedEnd()) ? "PENDING" : "UNCOVERED";
            comparisons.add(new RosterComparison(entry, matched.orElse(null), coverage));
        }
        return comparisons;
    }

    /** Whether a shift's live window ({@code openedAt} through {@code closedAt}, or still open) overlaps the plan. */
    private static boolean overlaps(PlannedShiftRow entry, ShiftRow shift) {
        Instant shiftEnd = shift.closedAt() == null ? Instant.MAX : shift.closedAt();
        return !shift.openedAt().isAfter(entry.plannedEnd()) && !shiftEnd.isBefore(entry.plannedStart());
    }

    private AuditFact fact(
            String action,
            ActorRef actor,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID entryId,
            String reason,
            Map<String, Object> changes) {

        return AuditFact.of(action, AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("courier_roster_entry", entryId)
                .because(reason)
                .changed(changes)
                .correlatedBy("courier-roster-entry")
                .occurredAt(clock.instant())
                .build();
    }

    /** A new planned shift to draft. */
    public record NewPlannedShift(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID courierId,
            Instant plannedStart,
            Instant plannedEnd,
            UUID createdBy,
            ActorRef actor,
            String reason) {}

    /** @param coverage {@code COVERED}, {@code PENDING} (window not yet elapsed) or {@code UNCOVERED} */
    public record RosterComparison(
            PlannedShiftRow entry, @Nullable ShiftRow matchedShift, String coverage) {}
}
