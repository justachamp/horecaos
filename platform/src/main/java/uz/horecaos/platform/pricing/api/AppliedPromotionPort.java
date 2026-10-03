package uz.horecaos.platform.pricing.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The promotions behind a priced quote, in words a storefront may show a customer
 * (ADR 0140), for the module that owns the cart and the order.
 *
 * <p>Read-only, and read from the quote's own evidence (its adjustments and the
 * verdict recorded when it was priced), never by pricing the basket again: what the
 * customer is shown has to be what they will be charged, and a second evaluation
 * could disagree with the first. An order reads the quote behind its current
 * revision, so an amended order shows what it is now priced with.
 */
public interface AppliedPromotionPort {

    /**
     * @param quoteId             a quote of this tenant. A quote that does not exist
     *                            (pruned, or another tenant's) answers {@link
     *                            AppliedPromotions#none()}, the same answer an order
     *                            priced outside the engine gets
     * @param presentedCouponCode the code applied to the cart, or null for an order
     *                            or a cart with none. Used only to say what became of
     *                            it; it is never echoed back
     */
    AppliedPromotions describe(UUID tenantId, UUID quoteId, @Nullable String presentedCouponCode);
}
