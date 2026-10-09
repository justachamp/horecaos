package uz.horecaos.platform.marketing.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code marketing.contact_policy_overrides}: a tenant's tighter caps and wider quiet
 * hours (ADR 0112, V0495).
 *
 * <p>The tighten-only rule is the table's CHECK constraints as well as the service's,
 * so a row that reached this table another way is still refused by the database.
 */
@Repository
public class JdbcContactPolicyStore {

    private static final String COLUMNS = """
            tenant_id, brand_id, channel, campaign_purpose, period_kind, cap_count,
            quiet_hours_start, quiet_hours_end, stated_reason, updated_by, version, created_at, updated_at
            """;

    private final JdbcClient jdbc;

    public JdbcContactPolicyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes an override, creating it or replacing the one at the same key.
     *
     * @param expectedVersion the version the caller read, or null to create; an
     *                        update with a stale one changes nothing
     * @return the new version, or empty when {@code expectedVersion} was stale
     */
    public Optional<Integer> upsert(OverrideRow override, @Nullable Integer expectedVersion, Instant now) {
        if (expectedVersion == null) {
            int inserted = jdbc.sql("""
                    INSERT INTO marketing.contact_policy_overrides (
                        tenant_id, brand_id, channel, campaign_purpose, period_kind, cap_count,
                        quiet_hours_start, quiet_hours_end, stated_reason, updated_by, created_at, updated_at)
                    VALUES (:tenantId, :brandId, :channel, :purpose, :period, :cap,
                        :quietStart, :quietEnd, :reason, :by, :now, :now)
                    ON CONFLICT DO NOTHING
                    """)
                    .param("tenantId", override.tenantId())
                    .param("brandId", override.brandId())
                    .param("channel", override.channel())
                    .param("purpose", override.campaignPurpose())
                    .param("period", override.periodKind())
                    .param("cap", override.capCount())
                    .param("quietStart", override.quietHoursStart())
                    .param("quietEnd", override.quietHoursEnd())
                    .param("reason", override.statedReason())
                    .param("by", override.updatedBy())
                    .param("now", utc(now))
                    .update();
            return inserted == 1 ? Optional.of(1) : Optional.empty();
        }
        return jdbc.sql("""
                UPDATE marketing.contact_policy_overrides
                   SET cap_count = :cap, quiet_hours_start = :quietStart, quiet_hours_end = :quietEnd,
                       stated_reason = :reason, updated_by = :by, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND channel = :channel
                   AND campaign_purpose = :purpose AND period_kind = :period AND version = :expected
                RETURNING version
                """)
                .param("cap", override.capCount())
                .param("quietStart", override.quietHoursStart())
                .param("quietEnd", override.quietHoursEnd())
                .param("reason", override.statedReason())
                .param("by", override.updatedBy())
                .param("now", utc(now))
                .param("tenantId", override.tenantId())
                .param("brandId", override.brandId())
                .param("channel", override.channel())
                .param("purpose", override.campaignPurpose())
                .param("period", override.periodKind())
                .param("expected", expectedVersion)
                .query(Integer.class)
                .optional();
    }

    public Optional<OverrideRow> find(
            UUID tenantId, UUID brandId, String channel, String campaignPurpose, String periodKind) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM marketing.contact_policy_overrides
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND channel = :channel
                  AND campaign_purpose = :purpose AND period_kind = :period
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("channel", channel)
                .param("purpose", campaignPurpose)
                .param("period", periodKind)
                .query(JdbcContactPolicyStore::row)
                .optional();
    }

    public List<OverrideRow> listByBrand(UUID tenantId, UUID brandId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM marketing.contact_policy_overrides
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                ORDER BY channel, campaign_purpose, period_kind
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(JdbcContactPolicyStore::row)
                .list();
    }

    /** The overrides that bear on one send: this channel and this purpose, every period. */
    public List<OverrideRow> applicable(UUID tenantId, UUID brandId, String channel, String campaignPurpose) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM marketing.contact_policy_overrides
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND channel = :channel
                  AND campaign_purpose = :purpose
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("channel", channel)
                .param("purpose", campaignPurpose)
                .query(JdbcContactPolicyStore::row)
                .list();
    }

    public boolean delete(UUID tenantId, UUID brandId, String channel, String campaignPurpose, String periodKind) {
        return jdbc.sql("""
                DELETE FROM marketing.contact_policy_overrides
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND channel = :channel
                   AND campaign_purpose = :purpose AND period_kind = :period
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("channel", channel)
                        .param("purpose", campaignPurpose)
                        .param("period", periodKind)
                        .update()
                == 1;
    }

    private static OverrideRow row(ResultSet row, int number) throws SQLException {
        return new OverrideRow(
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getString("channel"),
                row.getString("campaign_purpose"),
                row.getString("period_kind"),
                row.getObject("cap_count", Integer.class),
                row.getObject("quiet_hours_start", LocalTime.class),
                row.getObject("quiet_hours_end", LocalTime.class),
                row.getString("stated_reason"),
                row.getObject("updated_by", UUID.class),
                row.getInt("version"),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record OverrideRow(
            UUID tenantId,
            UUID brandId,
            String channel,
            String campaignPurpose,
            String periodKind,
            @Nullable Integer capCount,
            @Nullable LocalTime quietHoursStart,
            @Nullable LocalTime quietHoursEnd,
            String statedReason,
            UUID updatedBy,
            int version,
            Instant updatedAt) {}
}
