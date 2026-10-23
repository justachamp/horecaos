package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierAccessStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierRow;
import uz.horecaos.platform.fulfillment.api.InternalFleetPort;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Correcting a courier, ending their engagement and archiving them (IA 3.3 «courier CRUD»,
 * couriers.md §5 «Archive … only when balance is zero and no open shift», ADR 0178).
 *
 * <p>Before this, the only thing that ended an engagement or archived a courier was the applicant
 * erasure sweep, so a courier who left stayed on the roster forever and a mistyped display reference
 * was permanent. Nothing here deletes: a courier's ledger, shifts and deliveries still name them.
 *
 * <p><strong>The guards are the point.</strong> A courier who is carrying a bag, or standing on an
 * open shift, is not ended out from under the dispatcher; one whose balance is not zero is not
 * archived, because an archived courier drops out of the screens where the balance is settled. Each
 * refusal is {@code 422 UNPROCESSABLE_STATE} with a stable {@code reason}, so the console says what
 * to do first instead of quoting a sentence.
 *
 * <p>A courier who leaves takes their sign-in link with them: ending the engagement and archiving both void the
 * invitation that is still live (queued, or texted and not yet spent) in the same transaction, which is what
 * V0657 means by «VOIDED: the courier left, before it was spent». Without it a link texted at nine still set a
 * password, and ended every session of the account, at ten.
 */
@Service
public class CourierLifecycleService {

    /** The stable reasons a lifecycle step is refused with. */
    public static final String REASON_OPEN_SHIFT = "COURIER_HAS_OPEN_SHIFT";

    public static final String REASON_ACTIVE_DELIVERIES = "COURIER_HAS_ACTIVE_DELIVERIES";
    public static final String REASON_NONZERO_BALANCE = "COURIER_BALANCE_NOT_ZERO";

    private final JdbcCourierStore couriers;
    private final JdbcCourierShiftStore shifts;
    private final JdbcCourierLedgerStore ledger;
    private final JdbcCourierAccessStore access;
    private final InternalFleetPort.ActiveAssignments activeAssignments;
    private final AuditRecorder audit;
    private final Clock clock;

    public CourierLifecycleService(
            JdbcCourierStore couriers,
            JdbcCourierShiftStore shifts,
            JdbcCourierLedgerStore ledger,
            JdbcCourierAccessStore access,
            InternalFleetPort.ActiveAssignments activeAssignments,
            AuditRecorder audit,
            Clock clock) {
        this.couriers = couriers;
        this.shifts = shifts;
        this.ledger = ledger;
        this.access = access;
        this.activeAssignments = activeAssignments;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Corrects the display reference and/or the type of an active courier.
     *
     * @throws ApiException {@code STALE_VERSION} when the courier changed since {@code expectedVersion};
     *     {@code RESOURCE_CONFLICT} when the reference belongs to another courier of the tenant or the
     *     courier is archived; {@code VALIDATION_FAILED} when nothing is to change or the type is not
     *     an active one of this tenant
     */
    @Transactional
    public CourierRow update(
            UUID tenantId,
            UUID courierId,
            int expectedVersion,
            @Nullable String displayReference,
            @Nullable UUID courierTypeId,
            ActorRef actor,
            String reason) {
        CourierRow before = require(tenantId, courierId);
        if (displayReference == null && courierTypeId == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Name the display reference or the courier type to change");
        }
        if (!"ACTIVE".equals(before.status())) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "An archived courier is a record and is not edited");
        }
        if (courierTypeId != null
                && couriers.findType(tenantId, courierTypeId)
                        .filter(type -> "ACTIVE".equals(type.status()))
                        .isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "That is not an active courier type of this tenant",
                    Map.of("field", "courierTypeId"));
        }
        Instant now = clock.instant();
        boolean applied;
        try {
            applied =
                    couriers.updateCourier(tenantId, courierId, expectedVersion, displayReference, courierTypeId, now);
        } catch (DuplicateKeyException taken) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Another courier of this tenant already has that display reference",
                    Map.of("field", "displayReference"));
        }
        if (!applied) {
            throw ApiException.staleVersion(expectedVersion, before.version());
        }

        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> became = new LinkedHashMap<>();
        if (displayReference != null && !displayReference.equals(before.displayReference())) {
            was.put("displayReference", before.displayReference());
            became.put("displayReference", displayReference);
        }
        if (courierTypeId != null && !courierTypeId.equals(before.courierTypeId())) {
            was.put("courierTypeId", before.courierTypeId().toString());
            became.put("courierTypeId", courierTypeId.toString());
        }
        audit.record(AuditFact.of("courier.updated", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier", courierId)
                .because(reason)
                .usingCapability("courier.engagement.manage")
                .changed(ChangeDocuments.diff(was, became))
                .correlatedBy(courierId.toString())
                .occurredAt(now)
                .build());
        return require(tenantId, courierId);
    }

    /**
     * Ends the courier's engagement: the date they stopped working for the tenant. Refused while they
     * stand on an open shift or carry a delivery.
     */
    @Transactional
    public void endEngagement(UUID tenantId, UUID courierId, ActorRef actor, String reason) {
        CourierRow courier = require(tenantId, courierId);
        Optional<JdbcCourierStore.EngagementRow> live = couriers.findLiveEngagement(tenantId, courierId);
        if (live.isEmpty()) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This courier has no engagement to end");
        }
        requireNothingInFlight(tenantId, courierId);

        Instant now = clock.instant();
        couriers.endEngagements(tenantId, courierId, LocalDate.ofInstant(now, ZoneOffset.UTC), now);
        int invitationsVoided = access.voidLive(tenantId, courierId, now);
        audit.record(AuditFact.of("courier.engagement.ended", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier_engagement", live.get().id())
                .because(reason)
                .usingCapability("courier.engagement.manage")
                .changed(ChangeDocuments.diff(
                        Map.of("status", live.get().status().name()),
                        Map.of(
                                "status",
                                "ENDED",
                                "courierId",
                                courier.id().toString(),
                                "accessInvitationsVoided",
                                invitationsVoided)))
                .correlatedBy(courierId.toString())
                .occurredAt(now)
                .build());
    }

    /**
     * Archives the courier, ending any engagement they still hold in the same step. Refused with a
     * balance that is not zero, an open shift, or a delivery in flight.
     */
    @Transactional
    public void archive(UUID tenantId, UUID courierId, ActorRef actor, String reason) {
        CourierRow courier = require(tenantId, courierId);
        if (!"ACTIVE".equals(courier.status())) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This courier is already archived");
        }
        requireNothingInFlight(tenantId, courierId);
        long balance = ledger.balanceMinor(tenantId, courierId);
        if (balance != 0L) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "The courier's balance is not zero; settle it before archiving",
                    Map.of("reason", REASON_NONZERO_BALANCE));
        }

        Instant now = clock.instant();
        int ended = couriers.endEngagements(tenantId, courierId, LocalDate.ofInstant(now, ZoneOffset.UTC), now);
        if (!couriers.archiveCourier(tenantId, courierId, now)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This courier is already archived");
        }
        int invitationsVoided = access.voidLive(tenantId, courierId, now);
        audit.record(AuditFact.of("courier.archived", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier", courierId)
                .because(reason)
                .usingCapability("courier.engagement.manage")
                .changed(ChangeDocuments.diff(
                        Map.of("status", "ACTIVE"),
                        Map.of(
                                "status",
                                "ARCHIVED",
                                "engagementsEnded",
                                ended,
                                "accessInvitationsVoided",
                                invitationsVoided)))
                .correlatedBy(courierId.toString())
                .occurredAt(now)
                .build());
    }

    private CourierRow require(UUID tenantId, UUID courierId) {
        return couriers.findCourier(tenantId, courierId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such courier: " + courierId));
    }

    private void requireNothingInFlight(UUID tenantId, UUID courierId) {
        if (shifts.findLiveShift(tenantId, courierId).isPresent()) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "The courier is on an open shift; close it first",
                    Map.of("reason", REASON_OPEN_SHIFT));
        }
        if (activeAssignments.byCourier(tenantId, List.of(courierId)).getOrDefault(courierId, 0) > 0) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "The courier is carrying a delivery; finish or reassign it first",
                    Map.of("reason", REASON_ACTIVE_DELIVERIES));
        }
    }
}
