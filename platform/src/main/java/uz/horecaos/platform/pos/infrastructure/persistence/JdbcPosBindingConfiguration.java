package uz.horecaos.platform.pos.infrastructure.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.integration.api.provider.BindingRef;

/**
 * Resolves the configuration one POS call runs under (ADR 0026, ADR 0030).
 *
 * <p>Two rows, merged in one direction only: the installation's non-sensitive
 * configuration first, the binding's override on top. That is ADR 0030's
 * precedence rule — narrower wins — rather than a second one invented here, and
 * it is what lets a brand-wide integrator id sit beside a per-venue identifier
 * without either being repeated.
 *
 * <p>Never a credential. The installation's secret reference is resolved inside
 * the gateway at call time and does not pass through this class or any record it
 * returns.
 */
@Component
public class JdbcPosBindingConfiguration {

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcPosBindingConfiguration(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<Map<String, String>> resolve(BindingRef binding) {
        return jdbc.sql("""
                SELECT i.non_sensitive_config::text AS installation_config,
                       b.configuration_override::text AS binding_config
                  FROM integration.bindings b
                  JOIN integration.installations i
                    ON i.id = b.installation_id AND i.tenant_id = b.tenant_id
                 WHERE b.tenant_id = :tenantId AND b.id = :bindingId
                """)
                .param("tenantId", binding.tenantId())
                .param("bindingId", binding.bindingId())
                .query((row, number) -> merge(row.getString("installation_config"), row.getString("binding_config")))
                .optional();
    }

    /**
     * The binding's own scope and provider, by id.
     *
     * <p>Needed because {@code ProviderInstallationLookup} resolves a capability
     * at a <em>scope</em> — a brand and a location — and a caller that has only a
     * binding id has neither. Passing nulls to that lookup matches nothing at
     * all, silently, which is a worse failure than asking here.
     */
    public Optional<BindingRef> bindingRef(UUID tenantId, UUID bindingId) {
        return jdbc.sql("""
                SELECT b.id, b.installation_id, b.tenant_id, b.brand_id, b.location_id,
                       i.provider_category, i.provider_type
                  FROM integration.bindings b
                  JOIN integration.installations i
                    ON i.id = b.installation_id AND i.tenant_id = b.tenant_id
                 WHERE b.tenant_id = :tenantId AND b.id = :bindingId
                """)
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .query((row, number) -> new BindingRef(
                        row.getObject("id", UUID.class),
                        row.getObject("installation_id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        uz.horecaos.platform.integration.api.provider.ProviderCategory.valueOf(
                                row.getString("provider_category")),
                        row.getString("provider_type"),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class)))
                .optional();
    }

    /** The installation's configuration alone, for a discovery run with no binding. */
    public Optional<Map<String, String>> resolveInstallation(UUID tenantId, UUID installationId) {
        return jdbc.sql("""
                SELECT non_sensitive_config::text AS installation_config
                  FROM integration.installations
                 WHERE tenant_id = :tenantId AND id = :installationId
                """)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .query((row, number) -> merge(row.getString("installation_config"), null))
                .optional();
    }

    private Map<String, String> merge(String installationJson, String bindingJson) {
        Map<String, String> merged = new LinkedHashMap<>();
        flatten(installationJson, merged);
        flatten(bindingJson, merged);
        return Map.copyOf(merged);
    }

    private void flatten(String json, Map<String, String> into) {
        if (json == null || json.isBlank()) {
            return;
        }
        Map<String, Object> parsed = objectMapper.readValue(json, JSON_OBJECT);
        parsed.forEach((key, value) -> {
            if (value != null) {
                // Flattened to strings on purpose. Everything a POS adapter reads
                // from configuration is an identifier, a code, or a flag, and a
                // typed accessor per key would be a schema for values that arrive
                // as JSON from a control plane anyway.
                into.put(key, String.valueOf(value));
            }
        });
    }
}
