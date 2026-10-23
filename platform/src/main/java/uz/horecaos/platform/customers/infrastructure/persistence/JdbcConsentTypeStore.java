package uz.horecaos.platform.customers.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.tenancy.api.LocalizedLabels;

/**
 * `customer.consent_types` (V0289): the tenant-wide consent-purpose registry 5.2b's own doc names as missing entirely, and
 * `customer.consent_type_translations` (V0683), where a purpose's name is kept per locale.
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

    /** Every wording of every purpose of a tenant, by purpose and locale. */
    public Map<UUID, Map<String, String>> translationsForTenant(UUID tenantId) {
        Map<UUID, Map<String, String>> byType = new HashMap<>();
        jdbc.sql("""
                SELECT consent_type_id, locale, label
                  FROM customer.consent_type_translations
                 WHERE tenant_id = :tenantId
                 ORDER BY consent_type_id, locale
                """)
                .param("tenantId", tenantId)
                .query((row, number) -> {
                    byType.computeIfAbsent(row.getObject("consent_type_id", UUID.class), id -> new LinkedHashMap<>())
                            .put(row.getString("locale"), row.getString("label"));
                    return 0;
                })
                .list();
        return byType;
    }

    public boolean hasAny(UUID tenantId) {
        return jdbc.sql("SELECT count(*) FROM customer.consent_types WHERE tenant_id = :tenantId")
                        .param("tenantId", tenantId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** Whether the tenant has an active purpose with this code. */
    public boolean isRegistered(UUID tenantId, String code) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM customer.consent_types
                                        WHERE tenant_id = :tenantId AND code = :code AND active)
                        """)
                .param("tenantId", tenantId)
                .param("code", code)
                .query(Boolean.class)
                .single();
    }

    /**
     * Inserts the code-owned default catalogue, skipping any code already
     * present. Safe under a race: a losing insert on the {@code (tenant_id,
     * code)} unique constraint is discarded rather than failing the caller,
     * because two requests bootstrapping the same tenant at once should both
     * simply see the registry afterwards, not one of them get an error for
     * having asked first.
     *
     * <p>Each default is written in every language it is worded in, to the translations table (V0683), and for the platform
     * triple to the legacy columns as well for one release, so the previous build still finds what it reads.
     */
    public void seedDefaults(UUID tenantId, List<DefaultConsentType> defaults, String actorSubject, Instant now) {
        OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        for (DefaultConsentType candidate : defaults) {
            UUID id = uz.horecaos.platform.configuration.Ids.newId();
            int inserted = jdbc.sql("""
                    INSERT INTO customer.consent_types
                        (id, tenant_id, code, label_ru, label_uz, label_en, description,
                         channel_specific, policy_version, active, created_at, updated_at,
                         created_by, updated_by)
                    VALUES (:id, :tenantId, :code, :labelRu, :labelUz, :labelEn, :description,
                            :channelSpecific, '1', true, :now, :now, :actor, :actor)
                    ON CONFLICT (tenant_id, code) DO NOTHING
                    """)
                    .param("id", id)
                    .param("tenantId", tenantId)
                    .param("code", candidate.code())
                    .param("labelRu", candidate.labels().get(LocalizedLabels.RU))
                    .param("labelUz", candidate.labels().get(LocalizedLabels.UZ_LATN))
                    .param("labelEn", candidate.labels().get(LocalizedLabels.EN))
                    .param("description", candidate.description())
                    .param("channelSpecific", candidate.channelSpecific())
                    .param("now", at)
                    .param("actor", actorSubject)
                    .update();
            if (inserted == 0) {
                // The purpose is already there (a race, or a tenant seeded before): its wording is the tenant's now.
                continue;
            }
            candidate
                    .labels()
                    .forEach((locale, label) -> jdbc.sql("""
                            INSERT INTO customer.consent_type_translations
                                (tenant_id, consent_type_id, locale, label, created_at, updated_at)
                            VALUES (:tenantId, :id, :locale, :label, :now, :now)
                            ON CONFLICT (consent_type_id, locale) DO NOTHING
                            """)
                            .param("tenantId", tenantId)
                            .param("id", id)
                            .param("locale", locale)
                            .param("label", label)
                            .param("now", at)
                            .update());
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

    /** A code-owned default to seed for a tenant with no registry yet, worded per locale. */
    public record DefaultConsentType(
            String code, Map<String, String> labels, String description, boolean channelSpecific) {

        public DefaultConsentType {
            labels = Map.copyOf(labels);
        }
    }

    /**
     * One tenant-defined consent purpose. The three {@code label*} components are the legacy columns, nullable since V0683;
     * the wording lives in the translations table and is merged by {@code ConsentTypeService}.
     */
    public record ConsentTypeRow(
            UUID id,
            UUID tenantId,
            String code,
            @Nullable String labelRu,
            @Nullable String labelUz,
            @Nullable String labelEn,
            @Nullable String description,
            boolean channelSpecific,
            String policyVersion,
            boolean active,
            int version,
            Instant createdAt,
            Instant updatedAt) {}
}
