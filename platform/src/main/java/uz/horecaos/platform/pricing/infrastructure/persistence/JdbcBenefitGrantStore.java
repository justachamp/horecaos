package uz.horecaos.platform.pricing.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Per-recipient coded benefit grants (operations §6.2a, ADR 0018, ADR 0044) —
 * V0265's {@code pricing.benefit_grants}.
 *
 * <p>A grant is bound to exactly one {@code customer_account_id} at mint time
 * and is single-use: redemption is one conditional {@code UPDATE}, the same
 * shape {@link JdbcPromoCodeStore#incrementIfWithinLimit} uses to make "exactly
 * one winner" true under concurrency without a separate advisory lock — the
 * {@code WHERE} clause checks identity, status and window in the same atomic
 * statement that claims the grant, rather than reading first and racing a
 * second caller to the write.
 */
@Repository
public class JdbcBenefitGrantStore {

    private final JdbcClient jdbc;

    public JdbcBenefitGrantStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public void insert(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID customerAccountId,
            String benefitType,
            long value,
            @Nullable Long maximumDiscountMinor,
            @Nullable String currency,
            long minBasketMinor,
            String codeHash,
            String codeHint,
            String sourceType,
            @Nullable UUID sourceId,
            Instant validFrom,
            @Nullable Instant expiresAt,
            Instant now) {
        jdbc.sql("""
                INSERT INTO pricing.benefit_grants (
                    id, tenant_id, brand_id, customer_account_id, benefit_type, value,
                    maximum_discount_minor, currency, min_basket_minor, code_hash, code_hint,
                    status, source_type, source_id, valid_from, expires_at, version, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :customerId, :benefitType, :value,
                    :maxDiscount, :currency, :minBasket, :codeHash, :codeHint,
                    'ACTIVE', :sourceType, :sourceId, :validFrom, :expiresAt, 1, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("customerId", customerAccountId)
                .param("benefitType", benefitType)
                .param("value", value)
                .param("maxDiscount", maximumDiscountMinor)
                .param("currency", currency)
                .param("minBasket", minBasketMinor)
                .param("codeHash", codeHash)
                .param("codeHint", codeHint)
                .param("sourceType", sourceType)
                .param("sourceId", sourceId)
                .param("validFrom", utc(validFrom))
                .param("expiresAt", utc(expiresAt))
                .param("now", utc(now))
                .update();
    }

    /** What a presented code needs checked against, read fresh — never cached across calls. */
    public Optional<GrantRow> findByCodeHash(UUID tenantId, String codeHash) {
        return jdbc.sql("""
                SELECT id, brand_id, customer_account_id, benefit_type, value, maximum_discount_minor,
                       currency, min_basket_minor, status, valid_from, expires_at
                FROM pricing.benefit_grants
                WHERE tenant_id = :tenantId AND code_hash = :codeHash
                """)
                .param("tenantId", tenantId)
                .param("codeHash", codeHash)
                .query(this::mapGrantRow)
                .optional();
    }

    /** Scoped to one brand at the query itself, not filtered afterwards — a caller never even receives a sibling brand's row. */
    public List<GrantRow> listForCustomer(UUID tenantId, UUID brandId, UUID customerAccountId) {
        return jdbc.sql("""
                SELECT id, brand_id, customer_account_id, benefit_type, value, maximum_discount_minor,
                       currency, min_basket_minor, status, valid_from, expires_at
                FROM pricing.benefit_grants
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND customer_account_id = :customerId
                ORDER BY created_at DESC
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("customerId", customerAccountId)
                .query(this::mapGrantRow)
                .list();
    }

    private GrantRow mapGrantRow(ResultSet row, int number) throws SQLException {
        return new GrantRow(
                row.getObject("id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("customer_account_id", UUID.class),
                row.getString("benefit_type"),
                row.getLong("value"),
                row.getObject("maximum_discount_minor", Long.class),
                row.getString("currency"),
                row.getLong("min_basket_minor"),
                row.getString("status"),
                row.getObject("valid_from", OffsetDateTime.class).toInstant(),
                instant(row.getObject("expires_at", OffsetDateTime.class)));
    }

    /**
     * The only place a grant moves to {@code REDEEMED}. A conditional
     * {@code UPDATE}, not a read-then-write: the row lock it takes is what
     * serializes every other concurrent attempt against the same grant, and the
     * {@code customer_account_id} equality in the {@code WHERE} clause is what
     * makes this the redemption <em>check</em> and not only the write — a
     * caller presenting the right code for the wrong account claims nothing,
     * in the same statement that would otherwise have claimed it.
     *
     * @return true when the claim applied — this grant was, at this exact
     *         instant, bound to this customer, {@code ACTIVE}, and in its window
     */
    public boolean redeemIfActive(
            UUID tenantId, UUID grantId, UUID customerAccountId, UUID orderId, @Nullable UUID quoteId, Instant now) {
        return jdbc.sql("""
                UPDATE pricing.benefit_grants
                SET status = 'REDEEMED', redeemed_at = :now, order_id = :orderId, quote_id = :quoteId,
                    updated_at = :now
                WHERE id = :id AND tenant_id = :tenantId AND customer_account_id = :customerId
                  AND status = 'ACTIVE' AND valid_from <= :now AND (expires_at IS NULL OR expires_at > :now)
                """)
                        .param("id", grantId)
                        .param("tenantId", tenantId)
                        .param("customerId", customerAccountId)
                        .param("orderId", orderId)
                        .param("quoteId", quoteId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** One grant by id, within a tenant. */
    public Optional<GrantRow> findById(UUID tenantId, UUID grantId) {
        return jdbc.sql("""
                SELECT id, brand_id, customer_account_id, benefit_type, value, maximum_discount_minor,
                       currency, min_basket_minor, status, valid_from, expires_at
                FROM pricing.benefit_grants
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", grantId)
                .query(this::mapGrantRow)
                .optional();
    }

    /** The grant an order has spent, if any: the one an amendment must keep pricing under. */
    public Optional<GrantRow> findHeldByOrder(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT id, brand_id, customer_account_id, benefit_type, value, maximum_discount_minor,
                       currency, min_basket_minor, status, valid_from, expires_at
                FROM pricing.benefit_grants
                WHERE tenant_id = :tenantId AND order_id = :orderId AND status = 'REDEEMED'
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(this::mapGrantRow)
                .optional();
    }

    /** The grant a quote was priced with: read from the quote's own adjustments, never from the request. */
    public Optional<GrantOnQuote> grantAppliedToQuote(UUID tenantId, UUID quoteId) {
        return jdbc.sql("""
                SELECT a.source_id AS grant_id, -SUM(a.amount_minor) AS discount_minor, q.currency
                FROM pricing.quote_adjustments a
                JOIN pricing.quotes q ON q.id = a.quote_id AND q.tenant_id = a.tenant_id
                WHERE a.tenant_id = :tenantId AND a.quote_id = :quoteId
                  AND a.source_type = 'GRANT' AND a.source_id IS NOT NULL
                  AND a.adjustment_type IN ('ITEM_DISCOUNT', 'ORDER_DISCOUNT', 'DELIVERY_FEE_BENEFIT')
                GROUP BY a.source_id, q.currency
                """)
                .param("tenantId", tenantId)
                .param("quoteId", quoteId)
                .query((row, n) -> new GrantOnQuote(
                        row.getObject("grant_id", UUID.class),
                        row.getLong("discount_minor"),
                        row.getString("currency")))
                .optional();
    }

    /**
     * Claims a grant for the order a checkout is about to write: {@link #redeemIfActive}'s conditional
     * {@code UPDATE}, with the quote it was priced under and what it took off recorded beside the claim.
     *
     * @return true when the claim applied, at this exact instant, for this customer and this brand
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public boolean claimIfActive(
            UUID tenantId,
            UUID brandId,
            UUID grantId,
            UUID customerAccountId,
            UUID orderId,
            UUID quoteId,
            long discountMinor,
            String currency,
            Instant now) {
        return jdbc.sql("""
                UPDATE pricing.benefit_grants
                SET status = 'REDEEMED', redeemed_at = :now, order_id = :orderId, quote_id = :quoteId,
                    redeemed_discount_minor = :discount, redeemed_currency = :currency,
                    version = version + 1, updated_at = :now
                WHERE id = :id AND tenant_id = :tenantId AND brand_id = :brandId
                  AND customer_account_id = :customerId
                  AND status = 'ACTIVE' AND valid_from <= :now AND (expires_at IS NULL OR expires_at > :now)
                """)
                        .param("id", grantId)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("customerId", customerAccountId)
                        .param("orderId", orderId)
                        .param("quoteId", quoteId)
                        .param("discount", discountMinor)
                        .param("currency", currency)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** Whether this quote already holds this grant: a retried checkout finds its own claim and claims nothing twice. */
    public boolean isClaimedByQuote(UUID tenantId, UUID grantId, UUID quoteId) {
        return jdbc.sql("""
                        SELECT count(*) FROM pricing.benefit_grants
                        WHERE tenant_id = :tenantId AND id = :id AND quote_id = :quoteId AND status = 'REDEEMED'
                        """)
                        .param("tenantId", tenantId)
                        .param("id", grantId)
                        .param("quoteId", quoteId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /**
     * Gives a grant back when the checkout that claimed it did not complete: the quote it was claimed under
     * names it. Whatever the claim recorded is cleared, so the grant reads as if it had never been spent.
     */
    public boolean releaseByQuote(UUID tenantId, UUID quoteId, Instant now) {
        return jdbc.sql("""
                UPDATE pricing.benefit_grants
                SET status = 'ACTIVE', redeemed_at = NULL, order_id = NULL, quote_id = NULL,
                    redeemed_discount_minor = NULL, redeemed_currency = NULL,
                    version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND quote_id = :quoteId AND status = 'REDEEMED'
                """)
                        .param("tenantId", tenantId)
                        .param("quoteId", quoteId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** What the spent grant took off, moved in place when an amendment reprices the order that holds it. */
    public boolean restateDiscount(UUID tenantId, UUID orderId, long discountMinor, Instant now) {
        return jdbc.sql("""
                UPDATE pricing.benefit_grants
                SET redeemed_discount_minor = :discount, updated_at = :now
                WHERE tenant_id = :tenantId AND order_id = :orderId AND status = 'REDEEMED'
                """)
                        .param("tenantId", tenantId)
                        .param("orderId", orderId)
                        .param("discount", discountMinor)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** The grants spent in [from, to), the source of the GRANT rows of the day-close redemption fact. */
    public List<SpentGrantRow> spentBetween(UUID tenantId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT id, brand_id, source_type, order_id, customer_account_id,
                       COALESCE(redeemed_discount_minor, 0) AS discount_minor,
                       COALESCE(redeemed_currency, currency, 'UZS') AS currency, redeemed_at
                FROM pricing.benefit_grants
                WHERE tenant_id = :tenantId AND status = 'REDEEMED' AND order_id IS NOT NULL
                  AND redeemed_at >= :from AND redeemed_at < :to
                ORDER BY redeemed_at, id
                """)
                .param("tenantId", tenantId)
                .param("from", utc(from))
                .param("to", utc(to))
                .query((row, n) -> new SpentGrantRow(
                        row.getObject("id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getString("source_type"),
                        row.getObject("order_id", UUID.class),
                        row.getObject("customer_account_id", UUID.class),
                        row.getLong("discount_minor"),
                        row.getString("currency"),
                        row.getObject("redeemed_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    /** The grant a quote was priced with and what it took off, from the quote's own evidence. */
    public record GrantOnQuote(UUID grantId, long discountMinor, String currency) {}

    /** A spent grant, for the day-close fact. */
    public record SpentGrantRow(
            UUID grantId,
            UUID brandId,
            String sourceType,
            UUID orderId,
            UUID customerAccountId,
            long discountMinor,
            String currency,
            Instant redeemedAt) {}

    public record GrantRow(
            UUID grantId,
            UUID brandId,
            UUID customerAccountId,
            String benefitType,
            long value,
            @Nullable Long maximumDiscountMinor,
            @Nullable String currency,
            long minBasketMinor,
            String status,
            Instant validFrom,
            @Nullable Instant expiresAt) {}
}
