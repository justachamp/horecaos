package uz.horecaos.platform.pricing.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.pricing.api.AppliedPromotionPort;
import uz.horecaos.platform.pricing.api.AppliedPromotions;
import uz.horecaos.platform.pricing.api.AppliedPromotions.Applied;
import uz.horecaos.platform.pricing.api.AppliedPromotions.CouponOutcome;
import uz.horecaos.platform.pricing.api.AppliedPromotions.Effect;
import uz.horecaos.platform.pricing.api.AppliedPromotions.Source;
import uz.horecaos.platform.pricing.api.PromoCodeQueryPort;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore;

/**
 * Turns a priced quote's adjustments into what a customer may be told (ADR 0140).
 *
 * <p>Reads the evidence the quote already holds. It prices nothing, so what a
 * storefront shows cannot differ from what checkout charges, and it reads the
 * adjustments the engine wrote rather than the promotions as they stand today, so
 * a promotion suspended after the order was placed still explains the order.
 *
 * <p>Whether a typed code applied is read from the verdict recorded when the quote
 * was priced. Where none was recorded (a quote priced before the verdict existed)
 * the answer falls back to the adjustments: the code's promotion either appears
 * among them or it does not, and "the offers are better" is only claimed when some
 * other promotion did apply. That fallback cannot tell a lost comparison from a
 * condition that did not hold, so it is the conservative reading and is wrong only
 * in the direction of saying a better offer exists when one does.
 */
@Service
public class AppliedPromotionService implements AppliedPromotionPort {

    private static final String PROMOTION_SOURCE = "PROMOTION";

    private final JdbcPricingStore pricing;
    private final JdbcPromoCodeStore promoCodes;
    private final PromoCodeQueryPort eligibility;
    private final Clock clock;

    public AppliedPromotionService(
            JdbcPricingStore pricing, JdbcPromoCodeStore promoCodes, PromoCodeQueryPort eligibility, Clock clock) {
        this.pricing = pricing;
        this.promoCodes = promoCodes;
        this.eligibility = eligibility;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public AppliedPromotions describe(UUID tenantId, UUID quoteId, @Nullable String presentedCouponCode) {
        QuoteSnapshot quote = pricing.findQuoteSnapshot(tenantId, quoteId).orElse(null);
        if (quote == null) {
            return AppliedPromotions.none();
        }
        List<QuoteSnapshot.Adjustment> promotionRows = quote.adjustments().stream()
                .filter(adjustment -> PROMOTION_SOURCE.equals(adjustment.sourceType()) && adjustment.sourceId() != null)
                .toList();
        Set<UUID> appliedIds = new LinkedHashSet<>();
        for (QuoteSnapshot.Adjustment adjustment : promotionRows) {
            appliedIds.add(adjustment.sourceId());
        }
        Set<UUID> gated = promoCodes.couponGatedPromotionIds(tenantId, appliedIds);

        // One entry per (source, effect): "two offers applied" is a fact about the
        // rule set, not about the basket, and a customer reads the sum.
        Map<Effect, long[]> automatic = new EnumMap<>(Effect.class);
        Map<Effect, long[]> coded = new EnumMap<>(Effect.class);
        for (QuoteSnapshot.Adjustment adjustment : promotionRows) {
            Effect effect = effectOf(adjustment.adjustmentType());
            if (effect == null) {
                continue;
            }
            Map<Effect, long[]> bucket = gated.contains(adjustment.sourceId()) ? coded : automatic;
            bucket.computeIfAbsent(effect, key -> new long[1])[0] += Math.abs(adjustment.amountMinor());
        }
        List<Applied> applied = new ArrayList<>();
        automatic.forEach((effect, sum) -> applied.add(new Applied(Source.AUTOMATIC, effect, sum[0])));
        coded.forEach((effect, sum) -> applied.add(new Applied(Source.PROMO_CODE, effect, sum[0])));
        applied.removeIf(entry -> entry.amountMinor() <= 0);
        applied.sort(Comparator.comparing(Applied::effect).thenComparing(Applied::source));

        CouponOutcome outcome = presentedCouponCode == null || presentedCouponCode.isBlank()
                ? null
                : outcomeOf(quote, presentedCouponCode, appliedIds, gated);
        List<AppliedPromotions.GiftOffer> giftOffers = pricing.findGiftOffers(tenantId, quoteId).stream()
                .map(row -> new AppliedPromotions.GiftOffer(
                        row.promotionId(), row.variantId(), row.quantity(), row.inCart(), row.toAdd()))
                .toList();
        return new AppliedPromotions(applied, outcome, giftOffers);
    }

    private CouponOutcome outcomeOf(QuoteSnapshot quote, String code, Set<UUID> appliedIds, Set<UUID> gated) {
        var verdict =
                eligibility.check(quote.tenantId(), quote.brandId(), code, quote.customerAccountId(), clock.instant());
        if (!verdict.isEligible()) {
            return CouponOutcome.NOT_VALID;
        }
        UUID promotionId = verdict.promotionId();
        if (promotionId != null && appliedIds.contains(promotionId)) {
            return CouponOutcome.APPLIED;
        }
        String recorded = promotionId == null
                ? null
                : pricing.findCouponVerdicts(quote.tenantId(), quote.quoteId()).get(promotionId);
        if (recorded != null) {
            // The engine's own words. A coupon promotion that lost a comparison, or
            // was set aside by an exclusive one, lost to the offers; anything else
            // (a condition, a window, a limit, nothing to take off) is its own
            // reason and not a contest.
            return "LOST_TO".equals(recorded) || "SUPPRESSED_BY_EXCLUSIVE".equals(recorded)
                    ? CouponOutcome.OFFERS_ARE_BETTER
                    : CouponOutcome.NOT_APPLICABLE;
        }
        boolean otherOfferApplied = appliedIds.stream().anyMatch(id -> !gated.contains(id));
        return otherOfferApplied ? CouponOutcome.OFFERS_ARE_BETTER : CouponOutcome.NOT_APPLICABLE;
    }

    private static @Nullable Effect effectOf(String adjustmentType) {
        return switch (adjustmentType) {
            case "ITEM_DISCOUNT", "ORDER_DISCOUNT" -> Effect.DISCOUNT;
            case "DELIVERY_FEE_BENEFIT" -> Effect.DELIVERY_DISCOUNT;
            case "ITEM_MARKUP" -> Effect.SURCHARGE;
            default -> null;
        };
    }
}
