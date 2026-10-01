package uz.horecaos.platform.pricing.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.fulfillment.api.PricingAuthority;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * What a customer wants priced (ADR 0018).
 *
 * <p>Every field here is an input to the context hash, which is what lets
 * checkout prove the cart it is accepting is the cart that was priced.
 *
 * @param customerAccountId null for a guest cart, which has no account to price
 *                          loyalty or account-scoped terms against
 * @param delivery null for a cart being collected rather than delivered
 * @param presentedCouponCode ADR 0072: the raw, customer-typed promo code
 *                          applied to the cart, or null when none is. Resolved
 *                          fresh against {@code pricing.coupon_codes} by
 *                          {@code QuoteService} on every call — never trusted
 *                          from an earlier answer — and, when still eligible,
 *                          folded into {@code presentedCouponPromotionIds},
 *                          which is already part of the context hash
 * @param carriedRedemptionOrderId ADR 0072, for repricing an order that already
 *                          exists (an amendment): the order whose live coupon
 *                          redemption this price must carry. That redemption
 *                          already holds its slot, so it is not re-checked
 *                          against the coupon's caps or window the way a
 *                          {@code presentedCouponCode} is -- the promotion it
 *                          was taken for is presented as-is and only its own
 *                          conditions are evaluated on the new basket. Null
 *                          for every cart price
 * @param fulfillmentMode ADR 0136: how the order leaves the location, which decides
 *                          which {@code HIDDEN_AUTO_SELECT} modifier groups the
 *                          server applies. Null means what a request has always
 *                          meant: {@code DELIVERY} when a destination is carried,
 *                          {@code PICKUP} otherwise -- see {@link
 *                          #effectiveFulfillmentMode()}. Dine-in has to be said,
 *                          because nothing else in the request tells it from pickup
 */
public record QuoteRequest(
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        @Nullable UUID customerAccountId,
        String channel,
        List<Line> lines,
        @Nullable String idempotencyKey,
        @Nullable Delivery delivery,
        @Nullable String presentedCouponCode,
        @Nullable UUID carriedRedemptionOrderId,
        @Nullable FulfillmentMode fulfillmentMode) {

    public QuoteRequest {
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(brandId, "A brand id is required");
        Objects.requireNonNull(locationId, "A location id is required");
        Objects.requireNonNull(lines, "Quote lines are required");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A quote needs at least one line");
        }
        channel = channel == null ? "STOREFRONT" : channel;
        lines = List.copyOf(lines);
    }

    /** Every call site that predates ADR 0136's fulfilment mode, which is to say all but the cart's. */
    public QuoteRequest(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            @Nullable UUID customerAccountId,
            String channel,
            List<Line> lines,
            @Nullable String idempotencyKey,
            @Nullable Delivery delivery,
            @Nullable String presentedCouponCode,
            @Nullable UUID carriedRedemptionOrderId) {
        this(
                tenantId,
                brandId,
                locationId,
                customerAccountId,
                channel,
                lines,
                idempotencyKey,
                delivery,
                presentedCouponCode,
                carriedRedemptionOrderId,
                null);
    }

    /**
     * The fulfilment mode the order is priced as (ADR 0136).
     *
     * <p>Stated when the caller knows it, derived when it does not: a request that
     * carries a delivery destination is a delivery, and every other request has
     * always been priced as a collection. The derivation is what keeps a request
     * that predates the field meaning exactly what it meant.
     */
    public FulfillmentMode effectiveFulfillmentMode() {
        if (fulfillmentMode != null) {
            return fulfillmentMode;
        }
        return delivery != null ? FulfillmentMode.DELIVERY : FulfillmentMode.PICKUP;
    }

    /** Every call site that predates a repricing carrying an order's own redemption. */
    public QuoteRequest(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            @Nullable UUID customerAccountId,
            String channel,
            List<Line> lines,
            @Nullable String idempotencyKey,
            @Nullable Delivery delivery,
            @Nullable String presentedCouponCode) {
        this(
                tenantId,
                brandId,
                locationId,
                customerAccountId,
                channel,
                lines,
                idempotencyKey,
                delivery,
                presentedCouponCode,
                null);
    }

    /** A cart being collected, with no promo code, and every call site that predates ADR 0037/0072. */
    public QuoteRequest(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            @Nullable UUID customerAccountId,
            String channel,
            List<Line> lines,
            @Nullable String idempotencyKey) {
        this(tenantId, brandId, locationId, customerAccountId, channel, lines, idempotencyKey, null, null);
    }

    /** Every call site that predates ADR 0072's promo code. */
    public QuoteRequest(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            @Nullable UUID customerAccountId,
            String channel,
            List<Line> lines,
            @Nullable String idempotencyKey,
            @Nullable Delivery delivery) {
        this(tenantId, brandId, locationId, customerAccountId, channel, lines, idempotencyKey, delivery, null);
    }

    /**
     * Where the order is going, when it is going anywhere (ADR 0037).
     *
     * <p>A coordinate and not an address. Pricing has no use for the text and ADR
     * 0029 keeps it inside envelope encryption; what the fee resolver needs is a
     * point to test containment with and a point to measure from.
     *
     * @param pricingAuthority whether HorecaOS prices this order at all. Carried on
     *                         the request rather than looked up here, because ADR
     *                         0037 puts the gate on the order and having pricing
     *                         decide it a second time is how two enforcement points
     *                         start disagreeing
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
     * @param lineId stable within the cart, so a re-quote can be compared line by
     *               line rather than by position
     * @param modifierOptionIds priced individually and added to the line
     * @param comboPicks ADR 0136: what the customer chose inside a combo. Non-empty
     *               exactly when {@code variantId} is a combo's container, which is
     *               never priced or sold on its own: the line becomes one ordinary
     *               quote line per picked component, each at its own price
     * @param nestedModifiers ADR 0136: second-level selections, each naming the
     *               first-level option whose linked variant offers it. One level
     *               and no more -- a nested option cannot itself be a parent
     */
    public record Line(
            String lineId,
            UUID variantId,
            int quantity,
            List<UUID> modifierOptionIds,
            List<ComboPick> comboPicks,
            List<NestedModifier> nestedModifiers) {

        public Line {
            Objects.requireNonNull(lineId, "A line id is required");
            Objects.requireNonNull(variantId, "A variant id is required");
            if (quantity <= 0) {
                throw new IllegalArgumentException("A quote line needs a positive quantity");
            }
            modifierOptionIds = modifierOptionIds == null ? List.of() : List.copyOf(modifierOptionIds);
            comboPicks = comboPicks == null ? List.of() : List.copyOf(comboPicks);
            nestedModifiers = nestedModifiers == null ? List.of() : List.copyOf(nestedModifiers);
            // Each component line is this id, a tilde and a position, and a quote line's
            // id is 64 characters. Refused here, where the line is built, rather than as a
            // database error half way through writing the quote.
            if (!comboPicks.isEmpty() && lineId.length() > MAX_COMBO_LINE_ID_LENGTH) {
                throw new IllegalArgumentException(
                        "A combo line's id may be at most %d characters".formatted(MAX_COMBO_LINE_ID_LENGTH));
            }
        }

        /** 64 for {@code quote_lines.line_id}, less {@code ~} and a three-digit position. */
        public static final int MAX_COMBO_LINE_ID_LENGTH = 60;

        /** An ordinary line, with no combo and no nested selection: every line before ADR 0136. */
        public Line(String lineId, UUID variantId, int quantity, List<UUID> modifierOptionIds) {
            this(lineId, variantId, quantity, modifierOptionIds, List.of(), List.of());
        }
    }

    /**
     * One pick inside a combo (ADR 0136).
     *
     * @param componentId the {@code catalog.combo_components} id -- the pairing of a
     *                    group with a variant, which is also what a price is keyed to
     * @param quantity    how many times the customer picked it; more than one needs a
     *                    group that allows the same component repeatedly
     */
    public record ComboPick(UUID componentId, int quantity) {

        public ComboPick {
            Objects.requireNonNull(componentId, "A combo pick names a component");
            if (quantity <= 0) {
                throw new IllegalArgumentException("A combo pick needs a positive quantity");
            }
        }
    }

    /**
     * A second-level modifier selection (ADR 0136).
     *
     * @param parentOptionId the first-level option the customer chose, whose linked
     *                       variant carries the group {@code optionId} belongs to
     * @param optionId       the option chosen from that linked variant's group
     */
    public record NestedModifier(UUID parentOptionId, UUID optionId) {

        public NestedModifier {
            Objects.requireNonNull(parentOptionId, "A nested selection names its parent option");
            Objects.requireNonNull(optionId, "A nested selection names an option");
        }
    }
}
