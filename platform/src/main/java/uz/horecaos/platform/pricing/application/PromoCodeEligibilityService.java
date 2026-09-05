package uz.horecaos.platform.pricing.application;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.pricing.api.PromoCodeQueryPort;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore.CouponEligibilityRow;

/**
 * The read-only half of ADR 0072's promo-code validation: "is this code alive
 * and not exhausted", answered fresh from the database every time.
 *
 * <p>Called from two places that must never trust each other's earlier
 * answer — {@code CartService.applyPromoCode} across the {@link PromoCodeQueryPort}
 * seam, and {@code QuoteService.quote()} directly, module-internally, at
 * every price. Neither call is cached, and neither reserves anything: this
 * class never writes {@code consumed_count}. That happens only in
 * {@code PromoCodeRedemptionService}, at checkout.
 *
 * <p>Deliberately does not check the minimum basket, channel, or location a
 * coupon's promotion may require — those are {@code Promotion.Condition}s
 * {@code PromotionEvaluator} already re-evaluates on every price. Checking
 * them here too would be a second copy of the same logic that could one day
 * disagree with the first.
 *
 * <p>A guest cart's per-customer cap is never checked at all —
 * {@code coupon_customer_usage} has no identity to count a guest against, and
 * V0093's own design (see {@code JdbcPromoCodeStore}'s class doc) treats that
 * as the cap simply not applying, not as a refusal.
 */
@Service
public class PromoCodeEligibilityService implements PromoCodeQueryPort {

    private final JdbcPromoCodeStore store;

    public PromoCodeEligibilityService(JdbcPromoCodeStore store) {
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public Eligibility check(UUID tenantId, UUID brandId, String code, @Nullable UUID customerAccountId, Instant now) {
        String normalized = JdbcPromoCodeStore.normalize(code);
        return store.findCouponByCode(tenantId, brandId, normalized)
                .map(row -> evaluate(tenantId, row, customerAccountId, now))
                .orElse(Eligibility.refused(Eligibility.Reason.CODE_NOT_FOUND));
    }

    private Eligibility evaluate(
            UUID tenantId, CouponEligibilityRow row, @Nullable UUID customerAccountId, Instant now) {
        if (!"ACTIVE".equals(row.status())) {
            return Eligibility.refused(Eligibility.Reason.CODE_NOT_ACTIVE);
        }
        if (now.isBefore(row.validFrom())) {
            return Eligibility.refused(Eligibility.Reason.CODE_NOT_YET_ACTIVE);
        }
        if (row.validUntil() != null && !now.isBefore(row.validUntil())) {
            return Eligibility.refused(Eligibility.Reason.CODE_EXPIRED);
        }
        if (!row.hasCapacity()) {
            return Eligibility.refused(Eligibility.Reason.REDEMPTION_LIMIT_REACHED);
        }
        if (customerAccountId != null) {
            int used = store.customerUsage(tenantId, row.couponId(), customerAccountId);
            if (used >= row.maximumPerCustomer()) {
                return Eligibility.refused(Eligibility.Reason.PER_CUSTOMER_LIMIT_REACHED);
            }
        }
        return Eligibility.ok(row.promotionId());
    }
}
