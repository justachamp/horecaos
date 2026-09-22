package uz.horecaos.platform.courier.application;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.courier.domain.DistanceSource;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.ShiftRow;
import uz.horecaos.platform.fulfillment.api.DeliveryCompletionPort;
import uz.horecaos.platform.fulfillment.api.DeliveryCompletionPort.InternalDelivery;
import uz.horecaos.platform.ordering.api.OrderCompleted;
import uz.horecaos.platform.ordering.api.OrderingEvent;
import uz.horecaos.platform.payments.api.CashDueLookupPort;
import uz.horecaos.platform.web.api.ApiException;

/**
 * The production caller {@code CourierAccrualService.recordDelivery} was
 * missing (ADR 0042, ADR 0125): {@code courier_assignment_earnings} and the
 * {@code CASH_COLLECTED} ledger entry were never written on a real tenant,
 * which is also why Finance's cash worklist reads permanently empty.
 *
 * <p><strong>Order completion, not a courier "delivered" tap, is where
 * "delivered" is known today.</strong> No courier-facing surface exists yet
 * to capture a real pickup or delivery event, so this listens for the one
 * fact the console already produces: an operator (or an automated rule)
 * moving a delivery order from {@code FULFILLING} to {@code COMPLETED}. See
 * ADR 0125 for why this is the deliberate, documented interim path rather
 * than a shortcut, and for the {@code kitchen_handover_at}/{@code
 * geoUnverified} gaps it leaves open until a real capture exists.
 *
 * <p><strong>{@link TransactionPhase#AFTER_COMMIT}, unlike {@code
 * loyalty.OrderCompletionAccrualTrigger}.</strong> {@code recordDelivery} is
 * {@code @Transactional} and can throw for an entirely ordinary
 * configuration gap — no active rate card yet for this branch and courier
 * type, plausible on a tenant still mid-onboarding. Joining it into the same
 * transaction as the order's own completion would mean that gap silently
 * breaks the unrelated ability to complete <em>any</em> delivery order at
 * that branch: Spring marks a joined transaction rollback-only the instant
 * an {@code @Transactional} method inside it throws, and catching the
 * exception here cannot undo that marking. Running after commit makes the
 * order's completion durable regardless of whether this succeeds, and the
 * failure is caught and logged rather than left to surface as a 500 on an
 * operator who already got their "order completed" response.
 *
 * <p>Idempotent by inheritance from both halves it drives:
 * {@link DeliveryCompletionPort#closeInternalShipment} is a compare-and-set
 * that answers the same facts on a replay without re-writing, and {@code
 * recordDelivery} itself refuses a second accrual on the assignment
 * attempt's unique constraint. A replayed {@code OrderCompleted} — an
 * ordinary at-least-once event, like every other — costs one extra pair of
 * reads and nothing else.
 */
@Component
public class DeliveryAccrualOrderCompletionTrigger {

    private static final Logger log = LoggerFactory.getLogger(DeliveryAccrualOrderCompletionTrigger.class);

    /**
     * ADR 0042's on-time policy has one shape today; this is the version
     * every accrual through this path is computed under, the same literal
     * {@code CourierCompensationTests} itself uses. A future policy revision
     * that changes what "on time" means bumps this constant alongside it.
     */
    private static final int ON_TIME_POLICY_VERSION = 1;

    private final DeliveryCompletionPort delivery;
    private final CourierAccrualService accrual;
    private final CourierShiftService shifts;
    private final CashDueLookupPort cashDue;

    public DeliveryAccrualOrderCompletionTrigger(
            DeliveryCompletionPort delivery,
            CourierAccrualService accrual,
            CourierShiftService shifts,
            CashDueLookupPort cashDue) {
        this.delivery = delivery;
        this.accrual = accrual;
        this.shifts = shifts;
        this.cashDue = cashDue;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderingEvent(OrderingEvent event) {
        if (event instanceof OrderCompleted completed) {
            accrueFor(completed);
        }
    }

    private void accrueFor(OrderCompleted completed) {
        UUID tenantId = completed.tenantId().value();

        Optional<InternalDelivery> found;
        try {
            found = delivery.closeInternalShipment(tenantId, completed.orderId(), completed.completedAt());
        } catch (RuntimeException failure) {
            // fulfillment's own read/CAS failed for a reason this trigger did
            // not anticipate. Never propagate: the order is already durably
            // completed and nothing about that should be in question because
            // its courier bookkeeping could not be reasoned about.
            log.warn(
                    "Could not resolve a delivery to close for completed order {} (tenant {}): {}",
                    completed.orderId(),
                    tenantId,
                    failure.getMessage());
            return;
        }
        if (found.isEmpty()) {
            // Pickup/dine-in order, a partner-sourced shipment, or nothing
            // live to close -- every one of those is "nothing for an internal
            // courier to accrue", not a fault.
            return;
        }
        InternalDelivery internal = found.get();

        // The settlement's actual money leg, not the order total: a
        // split-tender order settles part of itself from the customer's
        // loyalty balance, and the courier must only be told to collect what
        // is actually still owed in cash (CourierAccrualService's own
        // DeliveredAssignment.cashToCollectMinor javadoc: "the order total
        // less anything already captured and less any loyalty amount").
        // completed.totalMinor() alone ignores that split entirely, so it is
        // kept only as the fallback for a settlement read this trigger did
        // not anticipate -- never propagated, for the same reason
        // closeInternalShipment's own failure above is not: the order is
        // already durably completed.
        long cashToCollectMinor = 0L;
        if (!internal.prepaid()) {
            try {
                cashToCollectMinor = cashDue.cashDueMinor(tenantId, completed.orderId());
            } catch (RuntimeException failure) {
                log.warn(
                        "Could not read the cash due for completed order {} (tenant {}); falling back to the "
                                + "order total: {}",
                        completed.orderId(),
                        tenantId,
                        failure.getMessage());
                cashToCollectMinor = completed.totalMinor();
            }
        }

        Optional<ShiftRow> shift = shifts.liveShiftOf(tenantId, internal.courierId());

        try {
            accrual.recordDelivery(new CourierAccrualService.DeliveredAssignment(
                    tenantId,
                    internal.brandId(),
                    internal.locationId(),
                    internal.courierId(),
                    shift.map(ShiftRow::id).orElse(null),
                    internal.shipmentId(),
                    internal.assignmentAttemptId(),
                    internal.distanceMeters(),
                    mapDistanceSource(internal.distanceSourceName()),
                    internal.acceptedAt(),
                    completed.completedAt(),
                    internal.promisedDeliveryEnd(),
                    // null: let CourierAccrualService resolve the tenant's own
                    // grace policy rather than this trigger inventing one.
                    null,
                    ON_TIME_POLICY_VERSION,
                    // No real pickup capture exists yet -- see ADR 0125's
                    // negative consequences. Left null rather than guessed:
                    // OnTimeEvaluator treats a null handover as "no excuse
                    // recorded" and falls through to LATE, which is the
                    // honest answer when the platform does not actually know
                    // when the kitchen handed the bag over.
                    null,
                    internal.pickupWindowEnd(),
                    Math.max(0L, cashToCollectMinor),
                    // No geo-confirmation capture exists yet either. true
                    // rather than false: claiming a verification that never
                    // ran would be a worse answer than stating the gap, and
                    // AdjustmentRuleEvaluator's geo-unverified rate is meant
                    // to surface exactly this until it does.
                    true,
                    null,
                    null));
        } catch (ApiException failure) {
            // Almost always ErrorCode.UNPROCESSABLE_STATE: no active rate
            // card for this branch and courier type yet. A real
            // configuration gap worth a log line, and never a reason the
            // order this already completed should look any different.
            log.warn(
                    "Could not accrue delivery {} for courier {} (tenant {}): {}",
                    internal.shipmentId(),
                    internal.courierId(),
                    tenantId,
                    failure.getMessage());
        }
    }

    /**
     * {@code fulfillment}'s {@code distance_source} vocabulary (ADR 0037:
     * {@code RADIUS}, {@code ROAD}, {@code RADIUS_FALLBACK}) has no shared
     * enum with {@code courier}'s own (ADR 0042: {@code ROUTING}, {@code
     * HAVERSINE_FACTORED}, {@code MANUAL}) — the two modules price
     * different things from the same word. Neither {@link
     * uz.horecaos.platform.courier.domain.AccrualCalculator} nor any payout
     * figure reads {@code distanceSource}; it is evidence only, so this
     * mapping only has to be honest about what kind of number the plan's
     * distance is, not exact.
     */
    private static DistanceSource mapDistanceSource(@Nullable String fulfillmentDistanceSource) {
        if (fulfillmentDistanceSource == null) {
            return DistanceSource.MANUAL;
        }
        return switch (fulfillmentDistanceSource) {
            case "ROAD" -> DistanceSource.ROUTING;
            case "RADIUS", "RADIUS_FALLBACK" -> DistanceSource.HAVERSINE_FACTORED;
            default -> DistanceSource.MANUAL;
        };
    }
}
