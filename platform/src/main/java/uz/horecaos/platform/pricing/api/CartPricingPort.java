package uz.horecaos.platform.pricing.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.fulfillment.api.PricingAuthority;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * Pricing a cart, for the module that owns the cart (ADR 0018, ADR 0019).
 *
 * <p>Ordering owns the cart and therefore has to be the thing that asks for it to
 * be priced; it must not own the arithmetic. This port is that seam. Everything
 * the total depends on goes in, and a quote with its context hash comes back —
 * ordering never sees a price book, a tax profile, or the engine.
 */
public interface CartPricingPort {

    /**
     * Prices a cart and stores the quote with its evidence.
     *
     * <p>An idempotency key returns the existing quote rather than a second one,
     * so a customer cannot end up holding two quotes and two reservations for one
     * basket.
     *
     * @throws PricingRefusedException when the cart cannot be priced at all — an
     *         item with no active price, no live menu, no price book. A refusal
     *         rather than a zero: a cart silently priced at nothing is a free meal
     */
    QuoteSnapshot priceCart(PricingCommand command);

    /**
     * Everything a cart's total depends on, handed from ordering to pricing.
     *
     * @param customerAccountId null for a guest cart, which has no account to
     *                          price loyalty or account-scoped terms against
     * @param channelCode the ADR 0036 channel, which decides both the menu that is
     *                    priced and the price plane that prices it
     * @param presentedCouponCode ADR 0072: the cart's own applied promo code
     *                    ({@code ordering.carts.applied_coupon_code}), or null.
     *                    Re-resolved by pricing on every call — ordering never
     *                    learns whether it is eligible, only what the resulting
     *                    quote's total and adjustments say
     * @param carriedRedemptionOrderId ADR 0072, for repricing an order that already
     *                    exists (an amendment): the order whose live promo-code
     *                    redemption the price must carry, or null for a cart.
     *                    Read by pricing from what that order's checkout
     *                    recorded, never from the cart -- the cart's applied code
     *                    can change or vanish after checkout, the redemption
     *                    cannot. The redemption already holds its slot, so pricing
     *                    does not re-check the coupon's caps or window for it;
     *                    the promotion's own conditions are still evaluated on
     *                    the new basket
     * @param frame       ADR 0140: the promotion inputs ordering fixes rather than
     *                    lets pricing read from the clock, or null. On the cart path
     *                    the cart's payment method and fulfilment mode; on an
     *                    amendment, what the order was placed under (see {@link
     *                    PricingCommand.PromotionFrame})
     * @param delivery    ADR 0037: where the order is going, and who prices the
     *                    delivery leg, or null for a cart being collected. A
     *                    coordinate rather than an address — pricing has no use
     *                    for the text and must not depend on ordering's own
     *                    destination type, so this is pricing's own shape
     *                    ({@link GeoPoint} plus {@link PricingAuthority}, the
     *                    same pair {@code QuoteRequest.Delivery} carries)
     */
    record PricingCommand(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            @Nullable UUID customerAccountId,
            String channelCode,
            List<Item> items,
            String idempotencyKey,
            @Nullable String presentedCouponCode,
            @Nullable Delivery delivery,
            @Nullable UUID carriedRedemptionOrderId,
            @Nullable PromotionFrame frame) {

        public PricingCommand {
            Objects.requireNonNull(tenantId, "A tenant id is required");
            Objects.requireNonNull(brandId, "A brand id is required");
            Objects.requireNonNull(locationId, "A location id is required");
            items = List.copyOf(Objects.requireNonNull(items, "Items are required"));
            if (items.isEmpty()) {
                throw new IllegalArgumentException("A cart with no items has nothing to price");
            }
        }

        /** Every call site that predates the promotion frame (ADR 0140). */
        public PricingCommand(
                UUID tenantId,
                UUID brandId,
                UUID locationId,
                @Nullable UUID customerAccountId,
                String channelCode,
                List<Item> items,
                String idempotencyKey,
                @Nullable String presentedCouponCode,
                @Nullable Delivery delivery,
                @Nullable UUID carriedRedemptionOrderId) {
            this(
                    tenantId,
                    brandId,
                    locationId,
                    customerAccountId,
                    channelCode,
                    items,
                    idempotencyKey,
                    presentedCouponCode,
                    delivery,
                    carriedRedemptionOrderId,
                    null);
        }

        /** Every call site that predates a repricing carrying an order's own redemption. */
        public PricingCommand(
                UUID tenantId,
                UUID brandId,
                UUID locationId,
                @Nullable UUID customerAccountId,
                String channelCode,
                List<Item> items,
                String idempotencyKey,
                @Nullable String presentedCouponCode,
                @Nullable Delivery delivery) {
            this(
                    tenantId,
                    brandId,
                    locationId,
                    customerAccountId,
                    channelCode,
                    items,
                    idempotencyKey,
                    presentedCouponCode,
                    delivery,
                    null,
                    null);
        }

        /** Every call site that predates ADR 0072's promo code and ADR 0037's destination. */
        public PricingCommand(
                UUID tenantId,
                UUID brandId,
                UUID locationId,
                @Nullable UUID customerAccountId,
                String channelCode,
                List<Item> items,
                String idempotencyKey) {
            this(
                    tenantId,
                    brandId,
                    locationId,
                    customerAccountId,
                    channelCode,
                    items,
                    idempotencyKey,
                    null,
                    null,
                    null,
                    null);
        }

        /** Every call site that predates ADR 0037's destination. */
        public PricingCommand(
                UUID tenantId,
                UUID brandId,
                UUID locationId,
                @Nullable UUID customerAccountId,
                String channelCode,
                List<Item> items,
                String idempotencyKey,
                @Nullable String presentedCouponCode) {
            this(
                    tenantId,
                    brandId,
                    locationId,
                    customerAccountId,
                    channelCode,
                    items,
                    idempotencyKey,
                    presentedCouponCode,
                    null,
                    null,
                    null);
        }

        /**
         * The promotion inputs ordering fixes (ADR 0140).
         *
         * <p>On the cart path: {@code paymentMethodCode} and {@code fulfillmentMode}
         * from the cart, the rest null (the instant is the clock). On the amendment
         * path: {@code inheritFromQuoteId} is the quote behind the order's current
         * revision, whose recorded {@code promotionInputs} pricing starts from;
         * {@code paymentMethodCode} is non-null only when the amendment changes the
         * method; {@code fulfillmentMode} and {@code placedAt} come from the order and
         * serve an order priced before calculation version 3, which recorded none.
         * The clock is never an override: the service instant stays the one the order
         * was placed under, so a promotion that priced the order at 12:30 still holds
         * when a line is added at 15:05.
         */
        public record PromotionFrame(
                @Nullable Instant serviceInstant,
                @Nullable String paymentMethodCode,
                @Nullable String fulfillmentMode,
                @Nullable UUID inheritFromQuoteId,
                @Nullable Instant placedAt) {}

        /**
         * Where a delivery cart is going, and who prices it (ADR 0037).
         *
         * @param pricingAuthority a null constructor argument defaults to {@link
         *                         PricingAuthority#HORECAOS}, matching {@code
         *                         QuoteRequest.Delivery}'s own default
         */
        public record Delivery(GeoPoint destination, PricingAuthority pricingAuthority) {

            public Delivery {
                Objects.requireNonNull(destination, "A delivery needs a destination point");
                pricingAuthority = pricingAuthority == null ? PricingAuthority.HORECAOS : pricingAuthority;
            }
        }

        /**
         * One line of the cart being priced.
         *
         * @param lineKey stable within the cart, so a re-quote can be compared line
         *                by line rather than by position
         */
        public record Item(String lineKey, UUID variantId, int quantity, List<UUID> modifierOptionIds) {

            public Item {
                modifierOptionIds = modifierOptionIds == null ? List.of() : List.copyOf(modifierOptionIds);
            }
        }
    }

    /**
     * A cart that cannot be priced, with a stable code and the thing that caused
     * it.
     *
     * <p>Carries the offending id so a storefront can say which item, rather than
     * failing opaquely and leaving the customer to remove things one at a time.
     */
    class PricingRefusedException extends RuntimeException {

        private final String code;
        private final UUID subjectId;

        public PricingRefusedException(String code, UUID subjectId, String message) {
            super(message);
            this.code = code;
            this.subjectId = subjectId;
        }

        public String code() {
            return code;
        }

        public UUID subjectId() {
            return subjectId;
        }
    }
}
