package uz.horecaos.platform.ordering.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a customer could spend of their points on an order of a given size, asked for by ordering and
 * answered by loyalty (ADR 0046, ADR 0185).
 *
 * <p>ADR 0046 decides where a redemption is taken: inside the checkout transaction, never after. What that
 * left open is how anybody learns what they may redeem before they check out. {@code PointsRedemptionPort.quote}
 * answered it and nothing outside loyalty ever called it, so a storefront could not show a maximum, a refusal
 * or the spendable amount, and an operator on the phone could not offer points at all. This is the seam that
 * closes it, in the direction the modules already face: ordering declares the question and loyalty answers it,
 * as payments answers {@link OrderSettlementPort}, so ordering still imports nothing from loyalty.
 *
 * <p><strong>Read-only, and it holds nothing.</strong> A customer browsing two tabs must not lock their own
 * balance out of the checkout they actually finish; the hold is taken by the settlement at checkout.
 * Consequently an offer is a snapshot: another tab can spend the same points between the offer and the
 * checkout, and the checkout's conditional debit is the gate.
 */
public interface RedemptionOfferPort {

    /**
     * @param orderTotalMinor   the figure the order would be placed at, delivery fee, tax and promotions included
     * @param deliveryFeeMinor  the part of it that is the delivery fee, which a brand's policy may exclude from
     *                          what points can pay for
     * @param channelCode       the order's channel code, checked against the policy's allowed channels
     */
    Offer offer(
            UUID tenantId,
            UUID brandId,
            UUID customerAccountId,
            long orderTotalMinor,
            long deliveryFeeMinor,
            String currency,
            String channelCode);

    /** Whether a real implementation is present. */
    default boolean isWired() {
        return true;
    }

    /** Why an offer is empty when no loyalty module is present. */
    String NOT_WIRED_REFUSAL = "REDEMPTION_NOT_WIRED";

    /**
     * @param accountId      null when the customer holds no points account at this brand
     * @param availableMinor what the account can spend today, in whole som: the earn delay is already out of it
     * @param maximumMinor   the largest redemption this order permits, after the policy share, the minimum order
     *                       and the invariant that some money must change hands
     * @param refusal        a code for why the maximum is zero ({@code NO_ACCOUNT}, {@code REDEMPTION_NOT_ENABLED},
     *                       {@code CHANNEL_NOT_ELIGIBLE}, {@code ACCOUNT_NOT_ACTIVE}, {@code ORDER_NOT_ELIGIBLE},
     *                       {@code INSUFFICIENT_BALANCE}, {@code REDEMPTION_BLOCKED_BY_PROMOTION}), or null
     */
    record Offer(
            @Nullable UUID accountId,
            long availableMinor,
            long maximumMinor,
            String currency,
            @Nullable String refusal) {

        public boolean redeemable() {
            return maximumMinor > 0;
        }

        /** The same offer with nothing redeemable, for a reason this offer's maker does not own. */
        public Offer refusedFor(String reason) {
            return new Offer(accountId, availableMinor, 0L, currency, reason);
        }
    }
}
