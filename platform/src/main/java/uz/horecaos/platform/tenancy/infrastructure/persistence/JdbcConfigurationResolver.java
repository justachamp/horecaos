package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.ResolutionTrace;
import uz.horecaos.platform.tenancy.api.Resolved;
import uz.horecaos.platform.tenancy.application.port.ConfigurationValueCache;
import uz.horecaos.platform.tenancy.domain.configuration.ScopeResolution;
import uz.horecaos.platform.tenancy.domain.configuration.ScopedConfigurationRow;

/**
 * SQL adapter for ADR 0030 configuration resolution.
 *
 * <p>This class only fetches the candidate rows for a scope chain. Precedence
 * itself lives in {@link ScopeResolution} so it stays exhaustively testable
 * without a database.
 *
 * <p>{@link #resolve} is cached under ADR 0033's {@code tenant.configuration}
 * and implements {@link ConfigurationValueCache} so {@code
 * JdbcConfigurationValueAuthor} can evict the exact scope it just wrote, the
 * same shape {@link JdbcPolicyResolver} uses for {@code tenant.policy_current}.
 * Without that eviction call, a value an operator just set would keep
 * resolving to whatever was cached before it for up to the registry's
 * sixty-second TTL — the backstop ADR 0033's Decision describes for a missed
 * invalidation, not the mechanism itself.
 */
@Repository
public class JdbcConfigurationResolver implements ConfigurationResolver, ConfigurationValueCache {

    private static final String SELECT_CHAIN = """
            SELECT scope_type, value_type, boolean_value, integer_value,
                   decimal_value, string_value, is_explicit_null,
                   version, set_by, updated_at
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

    /** ADR 0033's cache for "what does this key resolve to here". */
    public static final String CACHE_NAME = "tenant.configuration";

    static final String KEY_SEPARATOR = "|";

    private final JdbcClient jdbc;
    private final @Nullable ConfigurationValueCacheEvictor evictor;

    /** A fixture built by hand: nothing is cached, so there is nothing to evict. */
    public JdbcConfigurationResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.evictor = null;
    }

    @Autowired
    public JdbcConfigurationResolver(JdbcClient jdbc, ObjectProvider<CacheManager> caches) {
        this.jdbc = jdbc;
        CacheManager manager = caches.getIfAvailable();
        this.evictor = manager == null ? null : new ConfigurationValueCacheEvictor(manager);
    }

    /**
     * The key {@link #resolve} caches under: the setting, then the requesting scope as {@code
     * TYPE:tenant:brand:location} (an absent id is the text {@code null}). One definition for the
     * annotation below and for {@link ConfigurationValueCacheEvictor}, which has to recognise the keys
     * of every scope a write reaches.
     */
    public static String cacheKey(String keyCode, ResourceScope scope) {
        return keyCode
                + KEY_SEPARATOR
                + scope.type()
                + ':'
                + scope.tenantId()
                + ':'
                + scope.brandId()
                + ':'
                + scope.locationId();
    }

    @Override
    @Cacheable(
            cacheNames = CACHE_NAME,
            key = "T(uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationResolver)"
                    + ".cacheKey(#key.code(), #scope)")
    public <T> Resolved<T> resolve(ConfigurationKey<T> key, ResourceScope scope) {
        return ScopeResolution.resolve(key, scope, storedValues(key, scope));
    }

    @Override
    public ResolutionTrace explain(ConfigurationKey<?> key, ResourceScope scope) {
        return resolveErased(key, scope).trace();
    }

    /**
     * Called by {@code JdbcConfigurationValueAuthor} right after it writes the
     * row, so the value just set resolves on the very next call instead of
     * waiting out the registry's TTL -- at the scope written and at every
     * brand and location beneath it, whose own cached resolutions held the
     * value just replaced (see {@link ConfigurationValueCacheEvictor}).
     */
    @Override
    public void evict(String keyCode, ResourceScope scope) {
        if (evictor != null) {
            evictor.evict(keyCode, scope);
        }
    }

    private <T> Resolved<T> resolveErased(ConfigurationKey<T> key, ResourceScope scope) {
        return resolve(key, scope);
    }

    private Map<ScopeType, ScopedConfigurationRow> storedValues(ConfigurationKey<?> key, ResourceScope scope) {
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
                        resultSet.getBoolean("is_explicit_null"),
                        new ResolutionTrace.Provenance(
                                resultSet.getLong("version"),
                                resultSet.getString("set_by"),
                                resultSet
                                        .getObject("updated_at", OffsetDateTime.class)
                                        .toInstant())))
                .list();

        Map<ScopeType, ScopedConfigurationRow> values = new EnumMap<>(ScopeType.class);
        for (Row row : rows) {
            values.put(
                    row.scopeType(),
                    (row.explicitNull()
                                    ? ScopedConfigurationRow.explicitNull(row.scopeType())
                                    : ScopedConfigurationRow.of(row.scopeType(), row.typedValue(key)))
                            .withProvenance(row.provenance()));
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
            boolean explicitNull,
            ResolutionTrace.Provenance provenance) {

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
            return ResourceScope.tenant(tenantId);
        }
        if (locationId == null) {
            return ResourceScope.brand(tenantId, brandId);
        }
        return ResourceScope.location(tenantId, brandId, locationId);
    }
}
