package uz.horecaos.platform.integration.provider.voice;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Resolving a VOICE installation for its webhook/event-socket adapter (ADR
 * 0064), mirroring {@code TelegramWebhookInstallationLookup}.
 *
 * <p>Numbering (per-brand DIDs vs. a shared line) is one of ADR 0064's own
 * unresolved open inputs, so this class makes the smallest honest choice
 * available today: it resolves the installation's first ACTIVE binding, by
 * priority, and treats that binding's brand/location as the one this
 * installation serves. A tenant that binds one VOICE installation to more than
 * one location gets every call attributed to whichever binding sorts first —
 * an installation-per-location topology, not per-DID routing — until the
 * owner's numbering decision gives this class a DID-to-location mapping to
 * resolve against instead.
 */
@Repository
public class VoiceInstallationLookup {

    private final JdbcClient jdbc;

    public VoiceInstallationLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<VoiceInstallation> find(UUID installationId) {
        return jdbc.sql("""
                SELECT i.id, i.tenant_id, i.provider_type, i.status, e.base_url,
                       i.secret_reference, i.webhook_secret_reference, i.non_sensitive_config,
                       b.id AS binding_id, b.brand_id, b.location_id
                FROM integration.installations i
                JOIN integration.provider_environments e ON e.code = i.environment_code
                LEFT JOIN LATERAL (
                    SELECT id, brand_id, location_id
                    FROM integration.bindings
                    WHERE bindings.installation_id = i.id AND bindings.status = 'ACTIVE'
                    ORDER BY priority ASC, created_at ASC
                    LIMIT 1
                ) b ON true
                WHERE i.id = :id AND i.provider_category = 'VOICE'
                """)
                .param("id", installationId)
                .query(VoiceInstallationLookup::toInstallation)
                .optional();
    }

    /** Every ACTIVE installation of one provider type — what an AMI connection supervisor iterates. */
    public List<VoiceInstallation> listActive(String providerType) {
        return jdbc.sql("""
                SELECT i.id, i.tenant_id, i.provider_type, i.status, e.base_url,
                       i.secret_reference, i.webhook_secret_reference, i.non_sensitive_config,
                       b.id AS binding_id, b.brand_id, b.location_id
                FROM integration.installations i
                JOIN integration.provider_environments e ON e.code = i.environment_code
                LEFT JOIN LATERAL (
                    SELECT id, brand_id, location_id
                    FROM integration.bindings
                    WHERE bindings.installation_id = i.id AND bindings.status = 'ACTIVE'
                    ORDER BY priority ASC, created_at ASC
                    LIMIT 1
                ) b ON true
                WHERE i.provider_category = 'VOICE' AND i.provider_type = :providerType AND i.status = 'ACTIVE'
                """)
                .param("providerType", providerType)
                .query(VoiceInstallationLookup::toInstallation)
                .list();
    }

    private static VoiceInstallation toInstallation(java.sql.ResultSet row, int num) throws java.sql.SQLException {
        return new VoiceInstallation(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("binding_id", UUID.class),
                row.getString("provider_type"),
                row.getString("status"),
                row.getString("base_url"),
                row.getString("secret_reference"),
                row.getString("webhook_secret_reference"),
                row.getString("non_sensitive_config"));
    }

    /**
     * A VOICE installation, resolved for its webhook or event-socket adapter.
     *
     * @param brandId    null when the installation has no ACTIVE binding yet —
     *                   a DRAFT installation nobody has scoped to a branch
     * @param locationId null under the same condition as {@code brandId}. An
     *                   adapter refuses to ingest for such an installation
     *                   rather than guess a location.
     * @param bindingId  null under the same condition as {@code brandId}.
     */
    public record VoiceInstallation(
            UUID installationId,
            UUID tenantId,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            @Nullable UUID bindingId,
            String providerType,
            String status,
            String baseUrl,
            @Nullable String secretReference,
            @Nullable String webhookSecretReference,
            String nonSensitiveConfigJson) {

        public boolean isScoped() {
            return brandId != null && locationId != null;
        }
    }
}
