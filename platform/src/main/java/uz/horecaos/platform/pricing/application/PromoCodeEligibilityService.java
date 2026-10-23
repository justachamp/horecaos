package uz.horecaos.platform.pricing.application;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final @Nullable BenefitGrantEligibility grants;

    /** A service for a caller that predates one-off grants: a code that is not a shared one is not found. */
    public PromoCodeEligibilityService(JdbcPromoCodeStore store) {
        this(store, null);
    }

    @Autowired
    public PromoCodeEligibilityService(JdbcPromoCodeStore store, @Nullable BenefitGrantEligibility grants) {
        this.store = store;
        this.grants = grants;
    }

    /**
     * A shared code first, and when the code word is not one, the customer's own one-off grant (ADR 0140): the
     * customer types one field and does not know which kind of code they were sent.
     */
    @Override
    @Transactional(readOnly = true)
    public Eligibility check(UUID tenantId, UUID brandId, String code, @Nullable UUID customerAccountId, Instant now) {
        String normalized = JdbcPromoCodeStore.normalize(code);
        return store.findCouponByCode(tenantId, brandId, normalized)
                .map(row -> evaluate(tenantId, row, customerAccountId, now))
                .orElseGet(() -> grantOrNotFound(tenantId, brandId, normalized, customerAccountId, now));
    }

    private Eligibility grantOrNotFound(
            UUID tenantId, UUID brandId, String code, @Nullable UUID customerAccountId, Instant now) {
        if (grants == null) {
            return Eligibility.refused(Eligibility.Reason.CODE_NOT_FOUND);
        }
        BenefitGrantEligibility.Verdict verdict = grants.check(tenantId, brandId, code, customerAccountId, now);
        if (verdict.isEligible()) {
            return Eligibility.okGrant(
                    java.util.Objects.requireNonNull(verdict.grant()).grantId());
        }
        return Eligibility.refused(
                switch (java.util.Objects.requireNonNull(verdict.reason())) {
                    // Somebody else's grant is answered as a code that is not there: the code is a bearer secret.
                    case CODE_NOT_FOUND, WRONG_CUSTOMER -> Eligibility.Reason.CODE_NOT_FOUND;
                    case REQUIRES_ACCOUNT -> Eligibility.Reason.CODE_REQUIRES_ACCOUNT;
                    case NOT_YET_ACTIVE -> Eligibility.Reason.CODE_NOT_YET_ACTIVE;
                    case EXPIRED -> Eligibility.Reason.CODE_EXPIRED;
                    case ALREADY_USED -> Eligibility.Reason.CODE_ALREADY_USED;
                });
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
