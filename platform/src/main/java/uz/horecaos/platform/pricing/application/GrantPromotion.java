package uz.horecaos.platform.pricing.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService.DiscountShape;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcBenefitGrantStore.GrantRow;

/**
 * A coded benefit grant, as the one value the promotion evaluator already understands (ADR 0140, ADR 0072).
 *
 * <p>A grant is not a promotion: it has no definition history, no lifecycle and no stacking group of an author's
 * choosing, and it is spent once by one named customer. But the evaluator has exactly the rule it needs, a
 * coupon-gated, exclusive order or delivery discount, and ADR 0072's treatment of a typed code is the treatment a
 * grant gets: it never combines with an automatic offer, and the customer receives the larger of the two. So the
 * grant is translated, per quote, into a transient {@link Promotion} whose id is the grant's own id. Nothing is
 * stored for it, and the engine records what it did under the source type {@code GRANT}, never {@code PROMOTION},
 * because there is no promotion row for that id to point at.
 *
 * <p>The three shapes are the three ADR 0072 gives a promo code, and map to the same actions {@code
 * PromoCodeAuthoringService} writes for a coupon.
 */
final class GrantPromotion {

    /** The handle on the adjustments a grant produces: a description code, and never the code word. */
    static final String CODE = "BENEFIT_GRANT";

    private GrantPromotion() {}

    /**
     * @param tenantId the tenant the grant was found under
     * @param quoteCurrency the currency the cart is priced in: a free-delivery grant carries none of its own
     * @param past true for the grant an order already holds, which keeps applying after its window closes
     *     because the window decided whether the order could spend it and it did
     */
    static Promotion of(UUID tenantId, GrantRow grant, String quoteCurrency, boolean past) {
        DiscountShape shape = DiscountShape.valueOf(grant.benefitType());
        Promotion.Scope scope = shape == DiscountShape.FREE_DELIVERY ? Promotion.Scope.DELIVERY : Promotion.Scope.ORDER;
        List<Promotion.Condition> conditions = grant.minBasketMinor() > 0
                ? List.of(new Promotion.Condition(
                        1,
                        Promotion.Condition.Type.SUBTOTAL_AT_LEAST,
                        new Promotion.Operands(Map.of("amountMinor", grant.minBasketMinor()))))
                : List.of();
        Promotion.Action action =
                switch (shape) {
                    case PERCENTAGE_OFF_ORDER ->
                        new Promotion.Action(
                                1,
                                Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT,
                                new Promotion.Operands(Map.of("basisPoints", grant.value())));
                    case FIXED_AMOUNT_OFF_ORDER ->
                        new Promotion.Action(
                                1,
                                Promotion.Action.Type.ORDER_FIXED_DISCOUNT,
                                new Promotion.Operands(Map.of("amountMinor", grant.value())));
                    case FREE_DELIVERY ->
                        new Promotion.Action(1, Promotion.Action.Type.FREE_DELIVERY, Promotion.Operands.empty());
                };
        Promotion promotion = new Promotion(
                grant.grantId(),
                tenantId,
                grant.brandId(),
                CODE,
                scope,
                // A group of its own, as a promo code has: exclusive already means nothing combines with it.
                "grant-" + grant.grantId(),
                true,
                0,
                true,
                grant.maximumDiscountMinor(),
                currencyOf(grant, quoteCurrency),
                grant.validFrom(),
                grant.expiresAt(),
                1,
                conditions,
                List.of(action));
        return past ? promotion.heldPastItsWindow() : promotion;
    }

    private static String currencyOf(GrantRow grant, String quoteCurrency) {
        @Nullable String own = grant.currency();
        return own != null ? own : quoteCurrency;
    }
}
