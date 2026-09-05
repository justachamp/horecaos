package uz.horecaos.platform.pricing.api;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Whether a promo code can be applied right now (ADR 0072), for the module
 * that owns the cart.
 *
 * <p>Deliberately read-only and deliberately shallow: it answers "is this code
 * alive and not exhausted", never "would this basket actually earn a
 * discount". The second question is a {@code pricing.domain.Promotion}
 * condition — minimum basket, channel, location — that
 * {@code PromotionEvaluator} already re-evaluates on every price, so
 * duplicating it here would be a second copy of eligibility logic that could
 * one day disagree with the first about what "eligible" means. A code that
 * passes this check but whose conditions are not met simply produces a quote
 * with no discount; the storefront notices the total does not move.
 *
 * <p>Called independently by the cart-apply endpoint and, module-internally,
 * by {@code QuoteService} at every price — neither call trusts the other's
 * earlier answer, per ADR 0072's validation discipline. Neither call reserves
 * anything: {@link PromoCodeRedemptionPort} is the only place a redemption is
 * ever recorded.
 */
public interface PromoCodeQueryPort {

    /**
     * @param code the raw, customer-typed code. Normalized (trimmed, upper-cased)
     *             before lookup, so a customer typing lower case is not refused
     *             for it
     * @param customerAccountId null for a guest cart. A guest cart's per-customer
     *                          cap simply does not apply — see
     *                          {@link Eligibility.Reason#PER_CUSTOMER_LIMIT_REACHED}
     */
    Eligibility check(UUID tenantId, UUID brandId, String code, @Nullable UUID customerAccountId, Instant now);

    /**
     * @param promotionId set only when {@code reason} is {@link Reason#OK}, naming
     *                     the promotion {@code QuoteService} should present as
     *                     redeemed by this code
     */
    record Eligibility(Reason reason, @Nullable UUID promotionId) {

        public boolean isEligible() {
            return reason == Reason.OK;
        }

        public static Eligibility ok(UUID promotionId) {
            return new Eligibility(Reason.OK, promotionId);
        }

        public static Eligibility refused(Reason reason) {
            if (reason == Reason.OK) {
                throw new IllegalArgumentException("OK is not a refusal reason");
            }
            return new Eligibility(reason, null);
        }

        public enum Reason {
            OK,
            CODE_NOT_FOUND,
            CODE_NOT_ACTIVE,
            CODE_NOT_YET_ACTIVE,
            CODE_EXPIRED,
            REDEMPTION_LIMIT_REACHED,
            /**
             * Never returned for a guest cart. {@code coupon_customer_usage}'s own
             * per-customer cap "simply does not apply" to a caller with no
             * account (V0093's own comment on {@code coupon_redemptions.customer_account_id})
             * — inventing an identity for a guest would make two strangers share
             * a limit, which is worse than not enforcing one at all.
             */
            PER_CUSTOMER_LIMIT_REACHED
        }
    }
}
