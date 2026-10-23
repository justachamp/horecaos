package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;

/**
 * Where a tenant's settings and policies are stored, across the whole tenant in one read each (settings.md
 * §10.0: «Find a setting» says where a value is set).
 *
 * <p>The tenant is a predicate of every statement, and a {@code PLATFORM} row (which has no tenant) cannot
 * match it. Brand and branch names are joined in here, tenant-scoped, so the console prints «бренд «Rayhon»»
 * and not an id it would have to look up. No secret lives in {@code tenant.configuration_values} (ADR 0030),
 * and the typed value is returned as the column holds it: the caller decides how much of a long string to
 * show.
 */
@Repository
public class JdbcConfigurationPlacementStore {

    private final JdbcClient jdbc;

    public JdbcConfigurationPlacementStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One stored configuration value at a TENANT, BRAND or LOCATION scope. */
    public record StoredValue(
            String keyCode,
            ScopeType scopeType,
            @Nullable UUID brandId,
            @Nullable String brandName,
            @Nullable UUID locationId,
            @Nullable String locationName,
            String valueType,
            @Nullable Boolean booleanValue,
            @Nullable Long integerValue,
            @Nullable BigDecimal decimalValue,
            @Nullable String stringValue,
            boolean explicitNull,
            long version,
            @Nullable String setBy,
            @Nullable Instant updatedAt) {}

    /** The newest version of one policy at a TENANT, BRAND or LOCATION scope. */
    public record StoredPolicy(
            String keyCode,
            ScopeType scopeType,
            @Nullable UUID brandId,
            @Nullable String brandName,
            @Nullable UUID locationId,
            @Nullable String locationName,
            int version,
            @Nullable String principal,
            @Nullable Instant validFrom) {}

    public List<StoredValue> valuesOf(UUID tenantId) {
        return jdbc.sql("""
                        SELECT v.key_code, v.scope_type, v.brand_id, b.display_name AS brand_name,
                               v.location_id, l.display_name AS location_name,
                               v.value_type, v.boolean_value, v.integer_value, v.decimal_value, v.string_value,
                               v.is_explicit_null, v.version, v.set_by, v.updated_at
                          FROM tenant.configuration_values v
                          LEFT JOIN tenant.brands b ON b.tenant_id = v.tenant_id AND b.id = v.brand_id
                          LEFT JOIN tenant.locations l ON l.tenant_id = v.tenant_id AND l.id = v.location_id
                         WHERE v.tenant_id = :tenantId AND v.scope_type IN ('TENANT', 'BRAND', 'LOCATION')
                         ORDER BY v.key_code,
                                  CASE v.scope_type WHEN 'TENANT' THEN 1 WHEN 'BRAND' THEN 2 ELSE 3 END,
                                  b.display_name NULLS FIRST, l.display_name NULLS FIRST, v.id
                        """)
                .param("tenantId", tenantId)
                .query((row, number) -> new StoredValue(
                        row.getString("key_code"),
                        ScopeType.valueOf(row.getString("scope_type")),
                        row.getObject("brand_id", UUID.class),
                        row.getString("brand_name"),
                        row.getObject("location_id", UUID.class),
                        row.getString("location_name"),
                        row.getString("value_type"),
                        (Boolean) row.getObject("boolean_value"),
                        (Long) row.getObject("integer_value"),
                        row.getBigDecimal("decimal_value"),
                        row.getString("string_value"),
                        row.getBoolean("is_explicit_null"),
                        row.getLong("version"),
                        row.getString("set_by"),
                        instant(row.getObject("updated_at", java.time.OffsetDateTime.class))))
                .list();
    }

    public List<StoredPolicy> policiesOf(UUID tenantId, Collection<String> keyCodes) {
        if (keyCodes.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT DISTINCT ON (p.key_code, p.scope_type, p.brand_id, p.location_id)
                               p.key_code, p.scope_type, p.brand_id, b.display_name AS brand_name,
                               p.location_id, l.display_name AS location_name, p.version,
                               coalesce(p.approved_by, p.created_by) AS principal, p.valid_from
                          FROM tenant.policies p
                          LEFT JOIN tenant.brands b ON b.tenant_id = p.tenant_id AND b.id = p.brand_id
                          LEFT JOIN tenant.locations l ON l.tenant_id = p.tenant_id AND l.id = p.location_id
                         WHERE p.tenant_id = :tenantId AND p.key_code IN (:keyCodes)
                           AND p.scope_type IN ('TENANT', 'BRAND', 'LOCATION')
                         ORDER BY p.key_code, p.scope_type, p.brand_id, p.location_id, p.version DESC
                        """)
                .param("tenantId", tenantId)
                .param("keyCodes", keyCodes)
                .query((row, number) -> new StoredPolicy(
                        row.getString("key_code"),
                        ScopeType.valueOf(row.getString("scope_type")),
                        row.getObject("brand_id", UUID.class),
                        row.getString("brand_name"),
                        row.getObject("location_id", UUID.class),
                        row.getString("location_name"),
                        row.getInt("version"),
                        row.getString("principal"),
                        instant(row.getObject("valid_from", java.time.OffsetDateTime.class))))
                .list();
    }

    private static @Nullable Instant instant(java.time.@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
