package uz.horecaos.platform.pricing.api;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The one place a promo-code redemption is ever recorded (ADR 0072), for
 * {@code CheckoutReservationStep}.
 *
 * <p>Mirrors {@code InventoryReservationPort} deliberately: keyed by the quote
 * a checkout is accepting rather than by a redemption id, so a retried
 * checkout and this step both name the same attempt without needing to
 * remember one; a plain verb pair, reserve and release, called inside the
 * same transaction as the inventory hold and the quote acceptance, each
 * compensated by the other if a later step in that transaction fails.
 *
 * <p>There is no separate "commit" the way inventory's hold is later turned
 * into a sale — a redemption written here is already final the moment the
 * enclosing checkout transaction commits, because nothing about it needs a
 * second confirmation once the quote it names has been accepted in the same
 * transaction.
 */
public interface PromoCodeRedemptionPort {

    /**
     * Finds whatever coupon-gated promotion actually applied to this quote —
     * from the quote's own {@code PROMOTION}-sourced adjustments, never from a
     * value carried on the request — and, if one exists, atomically checks its
     * limits and takes the redemption.
     *
     * <p>A quote with no applied coupon returns {@link Result#NO_CODE_APPLIED}:
     * there is nothing to reserve, and that is success, not a refusal.
     *
     * @param orderId the id the order is about to take, minted by the caller
     *                before this call — {@code pricing.coupon_redemptions}
     *                requires an order id on a {@code REDEEMED} row, the same
     *                one {@code LocationCapacityPort.claimCapacity} claims a
     *                kitchen slot under
     * @param customerAccountId null for a guest checkout
     */
    RedemptionResult reserveForQuote(
            UUID tenantId, UUID brandId, UUID quoteId, UUID orderId, @Nullable UUID customerAccountId, Instant now);

    /**
     * Gives a redemption back when a later step of the same checkout
     * transaction fails.
     *
     * @return false when there was nothing reserved for this quote to release —
     *         not an error, since {@link #reserveForQuote} is a no-op exactly
     *         as often as this is
     */
    boolean release(UUID tenantId, UUID quoteId);

    record RedemptionResult(Result result) {

        public boolean isRefused() {
            return result != Result.NO_CODE_APPLIED && result != Result.REDEEMED;
        }

        public enum Result {
            /** No coupon-gated promotion applied to this quote. Nothing to reserve. */
            NO_CODE_APPLIED,
            REDEEMED,
            /** The coupon's total limit was already reached by another checkout. */
            LIMIT_REACHED,
            /** This customer already holds every redemption this coupon allows them. */
            PER_CUSTOMER_LIMIT_REACHED,
            /** The coupon left its validity window or was retired since it was last priced. */
            CODE_NOT_ELIGIBLE
        }
    }
}
