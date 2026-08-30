package uz.horecaos.platform.integration.provider;

import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.integration.api.provider.BindingRef;

/**
 * Reads {@code login} and {@code sender} out of ADR 0026 configuration.
 *
 * <p>Two rows merged in one direction: the installation's
 * {@code non_sensitive_config} first, the binding's {@code configuration_override}
 * on top. That is ADR 0030's precedence rule — narrower wins — and it is what
 * lets one partner account carry a different sender string per brand, which is
 * the shape a multi-brand tenant actually has.
 *
 * <p>Both statements are constrained on {@code tenant_id} as well as the binding
 * id. The binding id arrives from a {@link BindingRef} that was itself resolved
 * for a tenant, but an id is never proof of ownership on its own.
 *
 * <p>Never a credential. {@code secret_reference} is not selected here and no
 * record this class returns can hold one.
 */
@Repository
public class JdbcSmsAccountLookup implements SmsAccountLookup {

    /** The keys this provider's account is configured under. */
    static final String LOGIN_KEY = "login";

    static final String SENDER_KEY = "sender";

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcSmsAccountLookup(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<SmsAccount> forBinding(BindingRef binding) {
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
                .query((row, number) -> read(row.getString("installation_config"), row.getString("binding_config")))
                .optional();
    }

    private SmsAccount read(String installationJson, String bindingJson) {
        Map<String, String> merged = new java.util.LinkedHashMap<>();
        flatten(installationJson, merged);
        flatten(bindingJson, merged);
        return new SmsAccount(merged.get(LOGIN_KEY), merged.get(SENDER_KEY));
    }

    private void flatten(String json, Map<String, String> into) {
        if (json == null || json.isBlank()) {
            return;
        }
        Map<String, Object> parsed = objectMapper.readValue(json, JSON_OBJECT);
        parsed.forEach((key, value) -> {
            if (value != null) {
                into.put(key, String.valueOf(value));
            }
        });
    }
}
