package uz.horecaos.platform.pricing.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.fulfillment.api.PricingAuthority;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.web.api.Quantities;

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
     * Checks one cart line's composite selection without pricing it (ADR 0136).
     *
     * <p>The cart is what the customer is looking at, and a combo with a group left
     * unanswered or a nested choice that its option does not offer is a basket nobody
     * can be charged for: discovering that when the cart is priced is a step too late
     * for the screen that could have said which group needs another pick. This runs
     * the very selection rules {@link #priceCart} runs, over the very same facts, so
     * the two cannot disagree about what a valid selection is.
     *
     * <p>A line that is not composite is accepted as it is, and the answer says so.
     *
     * @return the variants this line puts on the order: the picked components for a
     *         combo, whose container is never sold, and the line's own variant
     *         otherwise. The cart checks stock and sale windows on these
     * @throws PricingRefusedException with the rule's own stable code
     *         ({@code COMBO_GROUP_MINIMUM_NOT_MET}, {@code
     *         MODIFIER_NESTING_DEPTH_EXCEEDED}, ...) when the selection is not allowed
     */
    SelectionCheck checkSelection(UUID tenantId, UUID brandId, PricingCommand.Item item);

    /**
     * What a valid selection puts on the order.
     *
     * @param combo true when the line is a combo, whose container has no stock, no
     *              price and no line of its own
     * @param soldVariantIds the variants stock and sale windows are checked on
     */
    record SelectionCheck(boolean combo, java.util.Set<UUID> soldVariantIds) {

        public SelectionCheck {
            soldVariantIds = java.util.Set.copyOf(soldVariantIds);
        }
    }

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
     * @param delivery    ADR 0037: where the order is going, and who prices the
     *                    delivery leg, or null for a cart being collected. A
     *                    coordinate rather than an address — pricing has no use
     *                    for the text and must not depend on ordering's own
     *                    destination type, so this is pricing's own shape
     *                    ({@link GeoPoint} plus {@link PricingAuthority}, the
     *                    same pair {@code QuoteRequest.Delivery} carries)
     * @param fulfillmentMode ADR 0136: how the order leaves the location. It decides
     *                    which hidden auto-selected modifier groups pricing applies, so
     *                    a dine-in cart has to say so -- nothing else distinguishes it
     *                    from a collection. Null keeps the meaning a command has always
     *                    had: delivery when a destination is carried, pickup otherwise
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
            @Nullable FulfillmentMode fulfillmentMode) {

        public PricingCommand {
            Objects.requireNonNull(tenantId, "A tenant id is required");
            Objects.requireNonNull(brandId, "A brand id is required");
            Objects.requireNonNull(locationId, "A location id is required");
            items = List.copyOf(Objects.requireNonNull(items, "Items are required"));
            if (items.isEmpty()) {
                throw new IllegalArgumentException("A cart with no items has nothing to price");
            }
        }

        /** Every call site that predates ADR 0136's fulfilment mode. */
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
                    null);
        }

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
         * @param quantity a decimal since ADR 0137; whole for a variant that is not
         *                 sold by the portion
         * @param comboPicks ADR 0136: what the customer chose inside a combo. Non-empty
         *                exactly when {@code variantId} is a combo's container, which
         *                is never priced or sold on its own
         * @param nestedModifiers ADR 0136: second-level selections, each naming the
         *                first-level option whose linked variant offers it
         * @param actualWeightGrams ADR 0137: the weighed total of this line in grams, once
         *                 captured at pick or handover, or null while the line is still
         *                 priced provisionally. Refused for a variant that is not sold by weight
         */
        public record Item(
                String lineKey,
                UUID variantId,
                BigDecimal quantity,
                List<UUID> modifierOptionIds,
                List<ComboPick> comboPicks,
                List<NestedModifier> nestedModifiers,
                @Nullable Integer actualWeightGrams) {

            public Item {
                quantity = Quantities.normalise(quantity);
                modifierOptionIds = modifierOptionIds == null ? List.of() : List.copyOf(modifierOptionIds);
                comboPicks = comboPicks == null ? List.of() : List.copyOf(comboPicks);
                nestedModifiers = nestedModifiers == null ? List.of() : List.copyOf(nestedModifiers);
            }

            /** A line not yet weighed, with no combo and no nested selection. */
            public Item(String lineKey, UUID variantId, BigDecimal quantity, List<UUID> modifierOptionIds) {
                this(lineKey, variantId, quantity, modifierOptionIds, List.of(), List.of(), null);
            }

            /** A line with a captured weight (ADR 0137) and no combo or nested selection. */
            public Item(
                    String lineKey,
                    UUID variantId,
                    BigDecimal quantity,
                    List<UUID> modifierOptionIds,
                    @Nullable Integer actualWeightGrams) {
                this(lineKey, variantId, quantity, modifierOptionIds, List.of(), List.of(), actualWeightGrams);
            }

            /** A line with a combo and nested selections (ADR 0136), not yet weighed. */
            public Item(
                    String lineKey,
                    UUID variantId,
                    BigDecimal quantity,
                    List<UUID> modifierOptionIds,
                    List<ComboPick> comboPicks,
                    List<NestedModifier> nestedModifiers) {
                this(lineKey, variantId, quantity, modifierOptionIds, comboPicks, nestedModifiers, null);
            }

            /** A whole number of units, which is every line there was before ADR 0137. */
            public Item(String lineKey, UUID variantId, int quantity, List<UUID> modifierOptionIds) {
                this(lineKey, variantId, BigDecimal.valueOf(quantity), modifierOptionIds, List.of(), List.of(), null);
            }

            /** A whole number of units with a combo and nested selections. */
            public Item(
                    String lineKey,
                    UUID variantId,
                    int quantity,
                    List<UUID> modifierOptionIds,
                    List<ComboPick> comboPicks,
                    List<NestedModifier> nestedModifiers) {
                this(
                        lineKey,
                        variantId,
                        BigDecimal.valueOf(quantity),
                        modifierOptionIds,
                        comboPicks,
                        nestedModifiers,
                        null);
            }
        }

        /** One pick inside a combo: the combo component chosen, and how many times. */
        public record ComboPick(UUID componentId, int quantity) {}

        /** A second-level modifier selection, naming the first-level option that offers it. */
        public record NestedModifier(UUID parentOptionId, UUID optionId) {}
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
