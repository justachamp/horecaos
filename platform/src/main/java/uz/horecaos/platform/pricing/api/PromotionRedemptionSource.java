package uz.horecaos.platform.pricing.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The redemptions pricing recorded in a window, for the day-close fact (ADR 0140,
 * report 7.9).
 *
 * <p>Implemented by {@code pricing}, read by {@code reporting} -- never a report
 * reading {@code pricing} tables (ADR 0023). Its sources are the promotion ledger
 * (automatic), {@code coupon_redemptions} (coupon) and the one-off benefit grants a customer spent (ADR 0140).
 * A coupon's code word never crosses this port: the record carries the coupon id.
 * The customer is an account id here because reporting hashes it into the ADR 0029
 * pseudonym before it reaches a fact.
 */
public interface PromotionRedemptionSource {

    List<Redemption> redeemedBetween(UUID tenantId, Instant fromInclusive, Instant toExclusive);

    /**
     * @param promotionId null for a {@link Kind#GRANT}: a grant has no promotion behind it
     * @param grantId     set exactly for a {@link Kind#GRANT}; never the code word
     */
    record Redemption(
            UUID redemptionId,
            UUID brandId,
            @Nullable UUID promotionId,
            String promotionCode,
            int definitionVersion,
            Kind kind,
            @Nullable UUID couponId,
            UUID orderId,
            @Nullable UUID customerAccountId,
            long discountMinor,
            long markupMinor,
            String currency,
            Instant redeemedAt,
            @Nullable UUID grantId) {

        /** A redemption of a promotion or a coupon: every row that predates one-off grants. */
        public Redemption(
                UUID redemptionId,
                UUID brandId,
                UUID promotionId,
                String promotionCode,
                int definitionVersion,
                Kind kind,
                @Nullable UUID couponId,
                UUID orderId,
                @Nullable UUID customerAccountId,
                long discountMinor,
                long markupMinor,
                String currency,
                Instant redeemedAt) {
            this(
                    redemptionId,
                    brandId,
                    promotionId,
                    promotionCode,
                    definitionVersion,
                    kind,
                    couponId,
                    orderId,
                    customerAccountId,
                    discountMinor,
                    markupMinor,
                    currency,
                    redeemedAt,
                    null);
        }

        public enum Kind {
            AUTOMATIC,
            COUPON,
            GRANT
        }
    }
}
