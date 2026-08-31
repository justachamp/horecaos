package uz.horecaos.platform.ordering.api;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The settlement an order is discharged by (ADR 0046), asked for by ordering and
 * owned by payments.
 *
 * <p>ADR 0046 replaced ADR 0013's single payment intent with an ordered set of
 * tenders that sum to the order total, and everything downstream of an order's
 * money — the courier's cash figure, the points reversal, and every refund and
 * remedy {@code OrderRemedyService} records — reads that set. Nothing created it.
 * A settlement planned only by a test is a refund that fails for every order a
 * customer ever placed, which is the defect this port closes.
 *
 * <p>Two moments, because the platform learns two different things at them:
 *
 * <ol>
 *   <li>{@link #planSettlement} at checkout, where the tender composition is
 *       decided and where a plan that does not sum has to be refused before any
 *       provider is called;</li>
 *   <li>{@link #recordHandover} at completion, which is when a cash order's money
 *       actually arrives. A provider tender needs no equivalent here: its money
 *       is in before the restaurant is asked, so payments settles it on the
 *       confirmation it already listens for.</li>
 * </ol>
 *
 * <p>Only local rows are written by either. ADR 0019 keeps every provider call
 * out of the checkout transaction, and this port is inside it.
 */
public interface OrderSettlementPort {

    /**
     * Plans how this order will be discharged, and takes any balance hold.
     *
     * <p>Idempotent on the order: an order that already has a settlement gets its
     * existing one back rather than a second. The tender rows are written and the
     * points reserved inside the caller's transaction, so a checkout that goes on
     * to fail takes the hold back with it.
     *
     * @return the plan, or empty when this order names no method this build can
     *         tender against — the same conservative answer
     *         {@link PaymentIntentPort#createIntent} gives, and never a fabricated
     *         id
     */
    Optional<PlannedSettlement> planSettlement(SettlementRequest request);

    /**
     * What a settlement was planned as, and the one place the amount due is said.
     *
     * <p>{@code moneyDueMinor} exists because it was being derived twice. Checkout
     * created the payment intent for the order total and then asked for a
     * settlement that split the same total into a money tender of
     * {@code total - redeemed} and a balance tender of {@code redeemed}: on a
     * 94 000 order with 12 000 taken from points, Click was asked for 94 000 while
     * the settlement recorded an 82 000 money leg. The customer paid the full
     * price <em>and</em> spent their points, and the 12 000 surplus was on no
     * tender, so {@code OrderSettlementService.refund} — bounded by
     * {@code refundableMinor()} per tender — could not give it back either.
     *
     * <p>So the settlement is the authority and the intent is told. This figure is
     * the sum of the tenders that do not settle from a balance, read back from the
     * rows that were written rather than recomputed from the request, and it is
     * what {@link PaymentIntentPort#createIntent} must be called with. Deriving it
     * a second time anywhere is the defect returning.
     *
     * <p>Never zero and never negative: ADR 0046 requires at least one money tender
     * of at least one som, so an order fully covered by points is refused before a
     * plan exists.
     *
     * @param settlementId  the settlement, existing or newly planned
     * @param moneyDueMinor whole som a provider or a courier is actually to collect
     */
    record PlannedSettlement(UUID settlementId, long moneyDueMinor) {}

    /**
     * Records that the order was handed over, settling the tenders whose money
     * arrives at the door.
     *
     * <p>Cash is the case. A cash tender is not money when the order is confirmed;
     * it is money when a courier or a counter hands the food over, and settling it
     * any earlier would let an operator refund cash the tenant has never held.
     */
    void recordHandover(UUID tenantId, UUID orderId, String actor);

    /**
     * Records that the order ended without a handover, so nothing is left held.
     *
     * <p>The third moment, and the one that was missing. A cancelled, rejected,
     * expired or payment-failed order used to leave its settlement {@code PLANNED}
     * and its balance tender {@code RESERVED} for ever: the only thing that ever
     * unwound them was a loyalty sweep that could not tell a dead order from a
     * live one, and which was releasing live orders' holds to do it. With that
     * sweep taught to leave a live order alone, this is what gives an ended
     * order's points back — promptly, in the same transaction as the ending, and
     * for every terminal route rather than the ones somebody remembered.
     *
     * <p>Called for every terminal status except {@code COMPLETED}, which is
     * {@link #recordHandover}'s. Implementations must be inert for an order that
     * has no settlement, and for one whose settlement has already settled: an
     * order paid for and then cancelled needs a refund, which is a remedy and not
     * this.
     *
     * @param reasonCode why the order ended, recorded on the points that come
     *                   back. A code, never an operator's free text
     */
    void recordTerminalOutcome(UUID tenantId, UUID orderId, String reasonCode, String actor);

    /**
     * What checkout asks payments to plan a settlement from.
     *
     * @param customerAccountId       null for a guest checkout, which has no
     *                                balance to draw on and therefore no balance
     *                                tender
     * @param totalMinor              the order total in whole som — the figure the
     *                                tenders must sum to exactly, delivery fee, tax
     *                                and promotions included, because that is the
     *                                figure the order was placed at
     * @param redeemFromBalanceMinor  how much of the total the customer chose to
     *                                settle from points, or zero. Never the whole
     *                                total: an order with no money tender has no
     *                                fiscal path and nothing for a courier to
     *                                collect
     */
    record SettlementRequest(
            UUID tenantId,
            UUID brandId,
            UUID orderId,
            @Nullable UUID customerAccountId,
            String currency,
            long totalMinor,
            String paymentMethodCode,
            long redeemFromBalanceMinor,
            String idempotencyKey,
            @Nullable String actor) {}

    /**
     * Whether a real implementation is present.
     *
     * <p>Read by the checkout result so an assembly that can take orders and can
     * refund none of them says so on every order, rather than in a warning logged
     * once at startup that nobody sees again.
     */
    default boolean isWired() {
        return true;
    }

    /** The warning code a checkout carries while this port is unwired. */
    String NOT_WIRED_WARNING = "ORDER_SETTLEMENT_NOT_WIRED";
}
