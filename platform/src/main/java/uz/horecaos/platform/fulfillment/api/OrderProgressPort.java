package uz.horecaos.platform.fulfillment.api;

import java.util.UUID;

/**
 * How the kitchen proposes an order transition without being able to make one
 * (ADR 0041, ADR 0019).
 *
 * <p>ADR 0041 is emphatic that the ticket <em>proposes</em> order transitions
 * through the ADR 0019 command path exactly as POS and delivery do, and never
 * writes {@code ordering.orders}. This interface is that boundary in code: the
 * kitchen holds a reference to it and has no other way to reach an order.
 *
 * <p>ADR 0019's command path lives in {@code ordering.application}, which is
 * module-internal, so the implementation belongs to ordering and is
 * {@code OrderProgressAdapter}. Where that bean is absent — a slice test, or a
 * rollback of ADR 0041's rollout step 2 — {@code UnwiredOrderProgressPort}
 * stands in behind {@code @ConditionalOnMissingBean} and reports itself unwired
 * on every ticket read, which is the house pattern for a known gap
 * ({@code PaymentIntentPort}) and is ADR 0041's rollout step 1: "tickets created
 * and readable but no order proposals — one branch runs the screen beside
 * paper."
 */
public interface OrderProgressPort {

    /** The three order transitions the kitchen is ever entitled to propose. */
    enum OrderProgress {

        /** The first item started. {@code CONFIRMED -> PREPARING}. */
        PREPARING,

        /** Every non-cancelled item is ready. {@code PREPARING -> READY}. */
        READY,

        /**
         * A pickup order was handed over. {@code READY -> COMPLETED}.
         *
         * <p>Never proposed for a delivery order: ADR 0014 moves the shipment on
         * its own evidence, and a kitchen completing a delivery order would close
         * it while the food is still in a bag on a scooter.
         */
        COMPLETED
    }

    /** What happened to the proposal, from the kitchen's point of view. */
    enum ProposalOutcome {

        /** The order moved because of this proposal. */
        APPLIED,

        /**
         * The order was already where the proposal wanted it. Not an error: an
         * offline client replaying a queued advance, and two stations finishing in
         * the same second, both land here, and both are correct.
         */
        ALREADY_THERE,

        /**
         * ADR 0019 does not permit the transition from where the order actually
         * is — most often because an operator advanced it by hand first. The
         * kitchen records the fact and carries on; the ticket is not rolled back,
         * because the food is where the food is.
         */
        REFUSED,

        /** No implementation is present. Recorded, and visible on every read. */
        NOT_WIRED
    }

    /**
     * Asks ordering to make the transition this kitchen fact implies.
     *
     * <p>Never throws for a refusal. A cook is not a person who can interpret an
     * exception, and the alternative — failing the station advance because the
     * order would not move — would leave the kitchen unable to record that the
     * food is ready.
     *
     * @param idempotencyKey stable across retries of one kitchen fact, so an
     *                       offline client replaying twelve queued advances
     *                       produces twelve transitions rather than twenty-four
     */
    ProposalOutcome propose(UUID tenantId, UUID orderId, OrderProgress progress,
            String idempotencyKey, String reasonCode, String actorType, String actorId,
            String correlationId);

    /**
     * Whether a real implementation is present.
     *
     * <p>Read by the ticket read model so the gap appears on every board rather
     * than only in a warning logged once at startup that nobody sees again.
     */
    default boolean isWired() {
        return true;
    }

    /** The warning code a kitchen board carries while this port is unwired. */
    String NOT_WIRED_WARNING = "ORDER_PROGRESS_NOT_WIRED";
}
