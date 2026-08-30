package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.ResolutionTrace;
import uz.horecaos.platform.tenancy.api.Resolved;
import uz.horecaos.platform.tenancy.domain.configuration.ScopeResolution;
import uz.horecaos.platform.tenancy.domain.configuration.ScopedValue;

/**
 * SQL adapter for ADR 0030 configuration resolution.
 *
 * <p>This class only fetches the candidate rows for a scope chain. Precedence
 * itself lives in {@link ScopeResolution} so it stays exhaustively testable
 * without a database.
 */
@Repository
public class JdbcConfigurationResolver implements ConfigurationResolver {

    private static final String SELECT_CHAIN = """
            SELECT scope_type, value_type, boolean_value, integer_value,
                   decimal_value, string_value, is_explicit_null
              FROM tenant.configuration_values
             WHERE key_code = :keyCode
               AND (
                    (scope_type = 'PLATFORM')
                 OR (scope_type = 'TENANT' AND tenant_id = :tenantId)
                 OR (scope_type = 'BRAND' AND tenant_id = :tenantId AND brand_id = :brandId)
                 OR (scope_type = 'LOCATION' AND tenant_id = :tenantId
                     AND brand_id = :brandId AND location_id = :locationId)
               )
            """;

    private final JdbcClient jdbc;

    public JdbcConfigurationResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public <T> Resolved<T> resolve(ConfigurationKey<T> key, ResourceScope scope) {
        return ScopeResolution.resolve(key, scope, storedValues(key, scope));
    }

    @Override
    public ResolutionTrace explain(ConfigurationKey<?> key, ResourceScope scope) {
        return resolveErased(key, scope).trace();
    }

    private <T> Resolved<T> resolveErased(ConfigurationKey<T> key, ResourceScope scope) {
        return resolve(key, scope);
    }

    private Map<ScopeType, ScopedValue> storedValues(ConfigurationKey<?> key, ResourceScope scope) {
        List<Row> rows = jdbc.sql(SELECT_CHAIN)
                .param("keyCode", key.code())
                .param("tenantId", scope.tenantId())
                .param("brandId", scope.brandId())
                .param("locationId", scope.locationId())
                .query((resultSet, rowNumber) -> new Row(
                        ScopeType.valueOf(resultSet.getString("scope_type")),
                        resultSet.getString("value_type"),
                        (Boolean) resultSet.getObject("boolean_value"),
                        (Long) resultSet.getObject("integer_value"),
                        resultSet.getBigDecimal("decimal_value"),
                        resultSet.getString("string_value"),
                        resultSet.getBoolean("is_explicit_null")))
                .list();

        Map<ScopeType, ScopedValue> values = new EnumMap<>(ScopeType.class);
        for (Row row : rows) {
            values.put(
                    row.scopeType(),
                    row.explicitNull()
                            ? ScopedValue.explicitNull(row.scopeType())
                            : ScopedValue.of(row.scopeType(), row.typedValue(key)));
        }
        return values;
    }

    private record Row(
            ScopeType scopeType,
            String valueType,
            Boolean booleanValue,
            Long integerValue,
            java.math.BigDecimal decimalValue,
            String stringValue,
            boolean explicitNull) {

        Object typedValue(ConfigurationKey<?> key) {
            Object raw =
                    switch (valueType) {
                        case "BOOLEAN" -> booleanValue;
                        case "INTEGER" -> integerValue;
                        case "DECIMAL" -> decimalValue;
                        case "STRING" -> stringValue;
                        default ->
                            throw new IllegalStateException(
                                    "Unsupported stored value type %s for %s".formatted(valueType, key.code()));
                    };
            // A stored INTEGER is a bigint; narrow it only when the key asks for one,
            // so a value that no longer fits fails loudly instead of wrapping.
            if (raw instanceof Long value && key.valueType() == Integer.class) {
                return Math.toIntExact(value);
            }
            return raw;
        }
    }

    /** Convenience for callers holding raw identifiers rather than typed ones. */
    public static ResourceScope scopeOf(UUID tenantId, UUID brandId, UUID locationId) {
        if (tenantId == null) {
            return ResourceScope.platform();
        }
        if (brandId == null) {
            return new ResourceScope(ScopeType.TENANT, tenantId, null, null);
        }
        if (locationId == null) {
            return new ResourceScope(ScopeType.BRAND, tenantId, brandId, null);
        }
        return new ResourceScope(ScopeType.LOCATION, tenantId, brandId, locationId);
    }
}
