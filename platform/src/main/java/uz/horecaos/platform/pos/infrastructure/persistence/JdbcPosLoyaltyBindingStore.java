package uz.horecaos.platform.pos.infrastructure.persistence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads a brand's point-of-sale bindings together with what the vendor ceiling says about
 * {@code LOYALTY_BALANCE_SYNC} (ADR 0171).
 *
 * <p>The ceiling is read from {@code integration.pos_provider_capabilities} and its absence is a result,
 * not an error: a vendor with no row is {@code UNASSESSED}, which is what Clopos is today and what the
 * database already enforces by refusing to enable the capability on it. This class only makes that state
 * visible; it never decides it.
 */
@Repository
public class JdbcPosLoyaltyBindingStore {

    static final String CAPABILITY = "LOYALTY_BALANCE_SYNC";

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcPosLoyaltyBindingStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Every POS binding of one brand, oldest first. */
    public List<Row> bindingsOfBrand(UUID tenantId, UUID brandId) {
        return jdbc.sql(SELECT + " WHERE b.tenant_id = :tenantId AND b.brand_id = :brandId"
                        + " AND i.provider_category = 'POS' ORDER BY b.created_at, b.id")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((row, number) -> map(row))
                .list();
    }

    /** Every active POS binding with the capability enabled, across tenants (a scheduler's discovery, never a read for a caller). */
    public List<TenantRow> enabledBindings() {
        return jdbc.sql(SELECT_WITH_TENANT + """
                 WHERE i.provider_category = 'POS' AND b.status = 'ACTIVE' AND b.brand_id IS NOT NULL
                   AND EXISTS (SELECT 1 FROM integration.binding_capabilities bc
                                WHERE bc.tenant_id = b.tenant_id AND bc.binding_id = b.id
                                  AND bc.capability_code = 'LOYALTY_BALANCE_SYNC' AND bc.enabled)
                 ORDER BY b.tenant_id, b.created_at, b.id
                """)
                .query((row, number) -> new TenantRow(
                        row.getObject("tenant_id", UUID.class), row.getObject("brand_id", UUID.class), map(row)))
                .list();
    }

    public Optional<Row> find(UUID tenantId, UUID bindingId) {
        return jdbc.sql(SELECT + " WHERE b.tenant_id = :tenantId AND b.id = :bindingId"
                        + " AND i.provider_category = 'POS'")
                .param("tenantId", tenantId)
                .param("bindingId", bindingId)
                .query((row, number) -> map(row))
                .optional();
    }

    private static final String SELECT_WITH_TENANT = """
            SELECT b.id AS binding_id, b.tenant_id, b.brand_id, b.installation_id, i.provider_type, b.location_id,
                   b.status AS binding_status,
                   c.support_level, c.limits::text AS limits,
                   EXISTS (SELECT 1 FROM integration.binding_capabilities bc
                            WHERE bc.tenant_id = b.tenant_id AND bc.binding_id = b.id
                              AND bc.capability_code = 'LOYALTY_BALANCE_SYNC' AND bc.enabled) AS enabled
              FROM integration.bindings b
              JOIN integration.installations i
                ON i.id = b.installation_id AND i.tenant_id = b.tenant_id
              LEFT JOIN integration.pos_provider_capabilities c
                ON c.provider_type = i.provider_type AND c.capability_code = 'LOYALTY_BALANCE_SYNC'
            """;

    private static final String SELECT = """
            SELECT b.id AS binding_id, b.installation_id, i.provider_type, b.location_id,
                   b.status AS binding_status,
                   c.support_level, c.limits::text AS limits,
                   EXISTS (SELECT 1 FROM integration.binding_capabilities bc
                            WHERE bc.tenant_id = b.tenant_id AND bc.binding_id = b.id
                              AND bc.capability_code = 'LOYALTY_BALANCE_SYNC' AND bc.enabled) AS enabled
              FROM integration.bindings b
              JOIN integration.installations i
                ON i.id = b.installation_id AND i.tenant_id = b.tenant_id
              LEFT JOIN integration.pos_provider_capabilities c
                ON c.provider_type = i.provider_type AND c.capability_code = 'LOYALTY_BALANCE_SYNC'
            """;

    private Row map(java.sql.ResultSet row) throws java.sql.SQLException {
        String limits = row.getString("limits");
        Map<String, Object> parsed = limits == null || limits.isBlank()
                ? Collections.emptyMap()
                : new LinkedHashMap<>(objectMapper.readValue(limits, JSON_OBJECT));
        return new Row(
                row.getObject("binding_id", UUID.class),
                row.getObject("installation_id", UUID.class),
                row.getString("provider_type"),
                row.getObject("location_id", UUID.class),
                row.getString("binding_status"),
                row.getString("support_level"),
                row.getBoolean("enabled"),
                parsed);
    }

    public record TenantRow(UUID tenantId, UUID brandId, Row row) {}

    /** @param supportLevel the ceiling row's level, or null when the vendor has no row (unassessed) */
    public record Row(
            UUID bindingId,
            UUID installationId,
            String providerType,
            @Nullable UUID locationId,
            String bindingStatus,
            @Nullable String supportLevel,
            boolean enabled,
            Map<String, Object> limits) {}
}
