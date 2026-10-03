package uz.horecaos.platform.ordering.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

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
     * Whether the money for this method is taken before the order reaches the pass: a provider
     * tender, which has to clear before the restaurant is asked to confirm, as against cash, which
     * is collected at the door.
     *
     * <p>Asked of the method alone, so a basket can be judged before it has an order. It matters
     * to a basket whose total is not final at checkout -- a line sold by weight (ADR 0137) is
     * priced at its nominal weight and corrected at the scale, and a total a provider has
     * already taken cannot follow the correction. An unknown code answers false, as
     * {@link #paymentRequiredBeforeConfirmation} does.
     *
     * <p>Defaults to false so a build with no payments module behaves exactly as it did: that
     * build takes no money before anything, so there is nothing for the question to guard.
     */
    default boolean takesMoneyBeforeHandover(UUID tenantId, String paymentMethodCode) {
        return false;
    }

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
    @Nullable
    UUID createIntent(
            UUID tenantId,
            UUID orderId,
            long amountMinor,
            String currency,
            String paymentMethodCode,
            String idempotencyKey);

    /**
     * Which of these orders have a payment an operator can hand a checkout surface
     * for right now — the read behind the order board's «Выставить счёт»
     * ({@code ISSUE_INVOICE}).
     *
     * <p>Asked of the intent and its attempts, not of the order's {@code
     * payment_status_projection}: the projection stays {@code PENDING} through an
     * attempt that aged out or one whose outcome is unknown, so it cannot tell an
     * order the re-presentation endpoint will serve from one it answers {@code
     * NO_PAYMENT_INTENT} or {@code PAYMENT_IN_DOUBT}. An order absent from the
     * result has no payment to present, or none that may be shown again.
     *
     * <p>A port rather than a join for the reason {@code ActiveCourierAssignmentsPort}
     * gives: the board's own filter predicates may leave the {@code ordering}
     * schema (ADR 0102), a second field on every row goes through the module that
     * owns the fact. Defaults to "none" so a build with no payments module offers
     * no invoice, which is also the truth there: nothing can be presented.
     */
    default Set<UUID> ordersWithPresentablePayment(UUID tenantId, Collection<UUID> orderIds) {
        return Set.of();
    }

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
