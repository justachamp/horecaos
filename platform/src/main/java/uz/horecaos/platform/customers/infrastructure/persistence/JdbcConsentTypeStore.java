package uz.horecaos.platform.customers.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * `customer.consent_types` (V0289): the tenant-wide consent-purpose registry
 * 5.2b's own doc names as missing entirely.
 */
@Repository
public class JdbcConsentTypeStore {

    private final JdbcClient jdbc;

    public JdbcConsentTypeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every purpose this tenant has defined, active ones first, then by code. */
    public List<ConsentTypeRow> list(UUID tenantId) {
        return jdbc.sql("""
                SELECT id, tenant_id, code, label_ru, label_uz, label_en, description,
                       channel_specific, policy_version, active, version, created_at, updated_at
                  FROM customer.consent_types
                 WHERE tenant_id = :tenantId
                 ORDER BY active DESC, code
                """)
                .param("tenantId", tenantId)
                .query(JdbcConsentTypeStore::toRow)
                .list();
    }

    public boolean hasAny(UUID tenantId) {
        return jdbc.sql("SELECT count(*) FROM customer.consent_types WHERE tenant_id = :tenantId")
                        .param("tenantId", tenantId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /**
     * Inserts the code-owned default catalogue, skipping any code already
     * present. Safe under a race: a losing insert on the {@code (tenant_id,
     * code)} unique constraint is discarded rather than failing the caller,
     * because two requests bootstrapping the same tenant at once should both
     * simply see the registry afterwards, not one of them get an error for
     * having asked first.
     */
    public void seedDefaults(UUID tenantId, List<DefaultConsentType> defaults, String actorSubject, Instant now) {
        OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        for (DefaultConsentType candidate : defaults) {
            jdbc.sql("""
                    INSERT INTO customer.consent_types
                        (id, tenant_id, code, label_ru, label_uz, label_en, description,
                         channel_specific, policy_version, active, created_at, updated_at,
                         created_by, updated_by)
                    VALUES (:id, :tenantId, :code, :labelRu, :labelUz, :labelEn, :description,
                            :channelSpecific, '1', true, :now, :now, :actor, :actor)
                    ON CONFLICT (tenant_id, code) DO NOTHING
                    """)
                    .param("id", uz.horecaos.platform.configuration.Ids.newId())
                    .param("tenantId", tenantId)
                    .param("code", candidate.code())
                    .param("labelRu", candidate.labelRu())
                    .param("labelUz", candidate.labelUz())
                    .param("labelEn", candidate.labelEn())
                    .param("description", candidate.description())
                    .param("channelSpecific", candidate.channelSpecific())
                    .param("now", at)
                    .param("actor", actorSubject)
                    .update();
        }
    }

    private static ConsentTypeRow toRow(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new ConsentTypeRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getString("code"),
                row.getString("label_ru"),
                row.getString("label_uz"),
                row.getString("label_en"),
                row.getString("description"),
                row.getBoolean("channel_specific"),
                row.getString("policy_version"),
                row.getBoolean("active"),
                row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    /** A code-owned default to seed for a tenant with no registry yet. */
    public record DefaultConsentType(
            String code, String labelRu, String labelUz, String labelEn, String description, boolean channelSpecific) {}

    /** One tenant-defined consent purpose. */
    public record ConsentTypeRow(
            UUID id,
            UUID tenantId,
            String code,
            String labelRu,
            String labelUz,
            String labelEn,
            @Nullable String description,
            boolean channelSpecific,
            String policyVersion,
            boolean active,
            int version,
            Instant createdAt,
            Instant updatedAt) {}
}
