package uz.horecaos.platform.ordering.api;

import java.util.UUID;

/**
 * Step 7 of ADR 0019's checkout transaction: "create a provider-neutral payment
 * intent when payment is required".
 *
 * <p>ADR 0013's payments module implements this. The stand-in that reports itself
 * unwired survives beside it, behind {@code @ConditionalOnMissingBean}, for a
 * deployment assembled without payments: the gap then still surfaces on every
 * checkout result and every order read rather than in a design document, which is
 * the house pattern for a known gap ({@code CatalogPricingConfiguration}).
 *
 * <p>Only local data is created here. ADR 0019 is explicit that no provider call
 * happens inside the checkout transaction: external initiation starts after
 * commit and carries its own idempotency and reconciliation from ADR 0013.
 */
public interface PaymentIntentPort {

    /**
     * Whether this order must be paid before the restaurant is asked to confirm.
     *
     * <p>Asked rather than assumed, because the answer depends on the payment
     * method the channel offers and on ADR 0013's capture timing, neither of
     * which ordering may decide on its own.
     */
    boolean paymentRequiredBeforeConfirmation(UUID tenantId, UUID orderId, String paymentMethodCode);

    /**
     * Whether a payment by this method could actually be taken at this location.
     *
     * <p>A precondition, asked among checkout's read-only validations and before
     * anything is written. A method whose merchant account does not resolve is a
     * refusal the customer meets at the basket, not an order that reaches
     * {@code PAYMENT_AUTHORIZING} with nothing able to move it out again.
     *
     * <p>Defaults to "no reason to refuse" so a build with no payments module
     * behaves exactly as it did: that build requires payment for nothing, so the
     * question is never reached on a path that matters.
     *
     * @param paymentMethodCode never called with null or blank — a checkout that
     *                          names no method is not asking for one
     */
    default boolean canAcceptPayment(UUID tenantId, UUID locationId, String paymentMethodCode) {
        return true;
    }

    /**
     * Creates the local, provider-neutral intent row an order refers to.
     *
     * @param amountMinor what is actually to be collected, which is the order's
     *                    money leg and not its total. On an order part-settled from
     *                    a balance the two differ, and the caller reads this figure
     *                    from {@link OrderSettlementPort.PlannedSettlement} rather
     *                    than deriving it: an intent for the order total on a
     *                    split-tender order charges the customer for the points
     *                    they also spent, and the surplus lands on no tender, so no
     *                    refund can reach it either
     * @return the intent id, or null when no payment is required
     */
    UUID createIntent(
            UUID tenantId,
            UUID orderId,
            long amountMinor,
            String currency,
            String paymentMethodCode,
            String idempotencyKey);

    /**
     * Whether a real implementation is present.
     *
     * <p>Read by the checkout result and by the order read model so the gap
     * appears on every report and every response, rather than only in a warning
     * logged once at startup that nobody sees again.
     */
    default boolean isWired() {
        return true;
    }

    /** The warning code a checkout carries while this port is unwired. */
    String NOT_WIRED_WARNING = "PAYMENT_INTENT_NOT_WIRED";
}
