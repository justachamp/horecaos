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

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

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
