package uz.horecaos.platform.pricing.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * How much a customer account has been discounted by coupon-gated
 * promotions, tenant-wide (ADR 0072, row 7.9a) — the abuse check a marketer
 * runs before granting another goodwill code.
 *
 * <p>{@code pricing.coupon_redemptions} has carried an index built for
 * exactly this query, {@code (tenant_id, customer_account_id)}, since V0093;
 * this port is its first caller. A per-account total cannot come from
 * {@code reporting} instead: {@code fact_order.customer_subject_hash} is a
 * one-way ADR 0029 hash, so a reporting fact cannot be joined back to a
 * customer account id. Pricing is the only module holding the plaintext
 * relationship, so pricing is the only module that can answer this.
 *
 * <p>Tenant-scoped rather than brand-scoped: a customer account can hold
 * redemptions against more than one brand under the same tenant when the
 * tenant's identity partition is {@code TENANT_SHARED}, and "how much has
 * this customer been discounted" means everywhere in the tenant, not one
 * brand's slice of it.
 *
 * <p>Called by two consumers named in row 7.9a: the customer detail pane
 * (the {@code P40} wave) and the marketing reports screen (this wave, row
 * 7.9a's own report).
 */
public interface CustomerDiscountHistoryPort {

    /**
     * Every reservation, redemption, and release this account has ever held,
     * newest first. Deliberately not collapsed into a single total here: a
     * multi-brand tenant can run more than one currency, so a caller sums
     * {@link Redemption.Status#REDEEMED} rows grouped by
     * {@link Redemption#currency()} rather than trust a single number that
     * could silently add two currencies together.
     */
    List<Redemption> history(UUID tenantId, UUID customerAccountId);

    /**
     * @param codeHint null only if the issuing coupon's own hint was never set —
     *                 V0093 leaves {@code code_hint} nullable
     * @param orderId  null until the reservation redeems; a released reservation never gets one at all
     */
    record Redemption(
            UUID redemptionId,
            UUID brandId,
            UUID couponId,
            @Nullable String codeHint,
            UUID promotionId,
            String promotionName,
            @Nullable UUID orderId,
            Status status,
            long amountMinor,
            String currency,
            Instant reservedAt,
            @Nullable Instant redeemedAt,
            @Nullable Instant releasedAt) {

        /** Mirrors {@code pricing.coupon_redemptions.status} (V0093). */
        public enum Status {
            RESERVED,
            REDEEMED,
            RELEASED
        }
    }
}
