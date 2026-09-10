package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Where each feature flag is set (ADR 0082): its platform row and every
 * tenant's own, with tenant names, for the rollout screen.
 *
 * <p>Reads the stored rows rather than resolving each tenant, because the
 * question is "who has been set to what", not "what does each tenant see" —
 * a tenant with no row follows the platform value and is not a row here.
 */
@Repository
public class JdbcFeatureFlagOverview {

    private final JdbcClient jdbc;

    public JdbcFeatureFlagOverview(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param value the stored value, or null for an explicit "follow the platform"
     */
    public record StoredSetting(
            String keyCode,
            String scopeType,
            @Nullable UUID tenantId,
            @Nullable String tenantName,
            @Nullable Boolean value,
            long version) {}

    public List<StoredSetting> settingsFor(List<String> keyCodes) {
        if (keyCodes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT v.key_code, v.scope_type, v.tenant_id, t.display_name,
                               CASE WHEN v.is_explicit_null THEN NULL ELSE v.boolean_value END AS value,
                               v.version
                          FROM tenant.configuration_values v
                          LEFT JOIN tenant.tenants t ON t.id = v.tenant_id
                         WHERE v.key_code IN (:codes) AND v.scope_type IN ('PLATFORM', 'TENANT')
                         ORDER BY v.key_code, v.scope_type, t.display_name
                        """)
                .param("codes", keyCodes)
                .query((row, number) -> new StoredSetting(
                        row.getString("key_code"),
                        row.getString("scope_type"),
                        row.getObject("tenant_id", UUID.class),
                        row.getString("display_name"),
                        row.getObject("value", Boolean.class),
                        row.getLong("version")))
                .list();
    }
}
