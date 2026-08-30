package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.courier.domain.AdjustmentOrigin;
import uz.horecaos.platform.courier.domain.LedgerEntryType;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.HandoverRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Cash at the end of a shift (ADR 0042).
 *
 * <p>Three figures, three different people's statements about the same bag: what
 * the platform expected, what the courier declared handing over, and what the
 * cashier confirmed receiving. Each gap is recorded as its own
 * {@code CASH_VARIANCE} entry with a reason code, and never absorbed into
 * another figure — a variance folded into an earnings line is how a courier
 * concludes he was paid less than he earned, and how a shortfall stops being
 * findable.
 *
 * <p>Cash custody is independent of engagement type. The money is the tenant's
 * from the moment the customer hands it over, whatever the carrier's engagement.
 */
@Service
public class CourierCashService {

    private final JdbcCourierShiftStore shifts;
    private final CourierLedgerService ledger;
    private final AuditRecorder audit;
    private final Clock clock;

    public CourierCashService(JdbcCourierShiftStore shifts, CourierLedgerService ledger,
            AuditRecorder audit, Clock clock) {
        this.shifts = shifts;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
    }

    /** The courier declares what they are handing over. */
    @Transactional
    public HandoverRow declare(UUID tenantId, UUID handoverId, UUID courierId,
            long declaredMinor, ActorRef actor) {
        HandoverRow handover = handover(tenantId, handoverId);
        if (!handover.courierId().equals(courierId)) {
            // Not-found is deliberate: a handover id must not become an oracle
            // through which one courier can enumerate another courier's cash.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                    "No such cash handover of yours");
        }
        if (!shifts.declare(tenantId, handoverId, declaredMinor, clock.instant())) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE,
                    "This handover is " + handover.status());
        }
        HandoverRow declared = handover(tenantId, handoverId);
        audit.record(AuditFact.of("courier.cash.declared", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier_cash_handover", handoverId)
                // This is an attestation, not a discretionary Operations
                // override. It has no free-text reason in the courier contract;
                // record that fixed business intent rather than inventing one
                // from the declared amount.
                .because("Courier declared cash handover")
                .changed(Map.of("declaredMinor", declaredMinor))
                .usingCapability("courier.shift.open")
                .correlatedBy("courier-cash")
                .occurredAt(clock.instant())
                .build());
        return declared;
    }

    /**
     * A branch cashier confirms what was actually received.
     *
     * <p>Writes the handover as a positive ledger entry — the courier's position
     * improves by exactly what he handed back — and, where the three figures
     * disagree, one explicit variance entry for the whole gap between what was
     * expected and what was confirmed.
     */
    @Transactional
    public HandoverRow confirm(UUID tenantId, UUID handoverId, long confirmedMinor,
            String reasonCode, ActorRef actor, String reason) {

        HandoverRow handover = handover(tenantId, handoverId);
        if (handover.declaredMinor() == null) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE,
                    "Nothing has been declared on this handover yet");
        }

        long variance = confirmedMinor - handover.expectedMinor();
        String status = variance == 0 ? "CONFIRMED" : "VARIANCE_RAISED";
        if (variance != 0 && (reasonCode == null || reasonCode.isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A cash variance carries a reason code; an unexplained shortfall is how "
                            + "the ledger stops being true");
        }
        if (!shifts.confirm(tenantId, handoverId, confirmedMinor, variance, status, reasonCode,
                actor.subject(), clock.instant())) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "This handover was confirmed by somebody else first");
        }

        ledger.append(new CourierLedgerService.NewEntry(tenantId, handover.courierId(),
                handover.locationId(), LedgerEntryType.CASH_HANDED_OVER, confirmedMinor,
                handover.currency(), "courier_cash_handover", handoverId, AdjustmentOrigin.SYSTEM,
                null, clock.instant(), "cash-handed-over:" + handoverId, null, null,
                actor.subject()));

        if (variance != 0) {
            ledger.append(new CourierLedgerService.NewEntry(tenantId, handover.courierId(),
                    handover.locationId(), LedgerEntryType.CASH_VARIANCE, -variance,
                    handover.currency(), "courier_cash_handover", handoverId,
                    AdjustmentOrigin.MANUAL, reasonCode, clock.instant(),
                    "cash-variance:" + handoverId, null, null, actor.subject()));
        }

        audit.record(AuditFact.of("courier.cash.confirmed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("courier_cash_handover", handoverId)
                .because(reason)
                .changed(Map.of("expectedMinor", handover.expectedMinor(),
                        "declaredMinor", handover.declaredMinor(),
                        "confirmedMinor", confirmedMinor,
                        "varianceMinor", variance,
                        "reasonCode", String.valueOf(reasonCode)))
                .usingCapability("courier.cash.confirm")
                .correlatedBy("courier-cash")
                .occurredAt(clock.instant())
                .build());

        return handover(tenantId, handoverId);
    }

    private HandoverRow handover(UUID tenantId, UUID handoverId) {
        return shifts.findHandover(tenantId, handoverId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND,
                        "No such cash handover: " + handoverId));
    }
}
