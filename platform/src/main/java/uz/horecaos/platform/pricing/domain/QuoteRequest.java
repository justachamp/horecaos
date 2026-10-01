package uz.horecaos.platform.pricing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.fulfillment.api.PricingAuthority;
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
 * @param frame ADR 0140: the promotion inputs a caller fixes rather than lets pricing
 *                          read from the clock -- the cart's payment method and
 *                          fulfilment mode, or, for an amendment, what the order
 *                          was placed under. Null prices as the cart path always
 *                          did
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
        @Nullable Frame frame) {

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

    /** Every call site that predates the promotion frame (ADR 0140). */
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
                null,
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
     * The promotion inputs a caller fixes (ADR 0140).
     *
     * <p>On the cart path the caller is {@code CartService}: the payment method and
     * the fulfilment mode come from the cart and the instant is null, which means
     * the clock. On the amendment path ({@code inheritFromQuoteId} set) pricing
     * starts from the {@code promotionInputs} recorded on that quote, the quote
     * behind the order's current revision, and overrides only what the amendment
     * itself changes: the basket, the delivery point and, when {@code
     * paymentMethodCode} is set, the payment method. The clock is never an
     * override. {@code placedAt} is the fallback service instant for an order
     * priced before calculation version 3, which recorded none.
     */
    public record Frame(
            @Nullable Instant serviceInstant,
            @Nullable String paymentMethodCode,
            @Nullable String fulfillmentMode,
            @Nullable UUID inheritFromQuoteId,
            @Nullable Instant placedAt) {

        public boolean isAmendment() {
            return inheritFromQuoteId != null;
        }
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
     */
    public record Line(String lineId, UUID variantId, int quantity, List<UUID> modifierOptionIds) {

        public Line {
            Objects.requireNonNull(lineId, "A line id is required");
            Objects.requireNonNull(variantId, "A variant id is required");
            if (quantity <= 0) {
                throw new IllegalArgumentException("A quote line needs a positive quantity");
            }
            modifierOptionIds = modifierOptionIds == null ? List.of() : List.copyOf(modifierOptionIds);
        }
    }
}
