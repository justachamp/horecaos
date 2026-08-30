package uz.horecaos.platform.payments.application;

import java.util.UUID;

/**
 * Told when a provider's money has actually landed against an order (ADR 0046,
 * ADR 0013).
 *
 * <p>The seam that was missing, and the reason a captured payment could become
 * unrefundable. Before this, the only thing that ever settled a
 * {@code BEFORE_CONFIRMATION} tender was
 * {@code OrderConfirmedSettlementTrigger}, listening for {@code OrderConfirmed}.
 * That covers the happy path and exactly nothing else: an order cancelled or
 * expired while its payment was still live never confirms again, so a Payme
 * redirect completed inside its twelve-hour window captured real money against a
 * settlement that had already been failed — and {@code OrderSettlementService
 * .refund} then refused, correctly, because no tender was {@code SETTLED}.
 *
 * <p>So the settlement is told by the capture rather than by the confirmation.
 * Money arriving is the fact, and it is a fact whatever the order's status is:
 * the platform does not get to decide it did not happen because the kitchen had
 * already given up.
 *
 * <p>Declared in {@code payments.application} and implemented in
 * {@code payments.settlement} for the reason {@code DeliveryFeeBasisPort} is
 * declared where it is used: the attempt lifecycle states what it needs to
 * announce, and the settlement decides what that means for tenders. An
 * assembly with no settlement wired at all gets the no-op below and behaves
 * exactly as it did.
 */
public interface CapturedMoneyPort {

    /**
     * Records that a provider capture landed on this order.
     *
     * <p>Must be idempotent and must be inert for an order with no settlement, for
     * one whose tenders have already settled, and for one whose money arrives at
     * handover rather than from a provider. It is called from inside the
     * transaction that records the capture, so it may only write local rows.
     *
     * @param actor what the settlement records as having settled the tender. Never
     *              a person: this is the platform's own rule firing, and ADR 0029
     *              keeps a customer out of it
     */
    void recordCapture(UUID tenantId, UUID orderId, String actor);

    /** The assembly with nothing to tell. */
    CapturedMoneyPort NONE = (tenantId, orderId, actor) -> {};
}
