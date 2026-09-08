package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.AuthoredConfigurationValue;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationValueAuthor;
import uz.horecaos.platform.tenancy.application.port.ConfigurationValueCache;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * SQL adapter for ADR 0030 configuration-value authoring — the writer {@code
 * tenant.configuration_values} never had (see {@code ConfigurationValueAuthor}'s
 * own Javadoc). {@link JdbcConfigurationResolver} answers "what does this
 * resolve to"; this answers "here is what this scope should hold now".
 *
 * <p><strong>Optimistic locking under a mutable row, not append-only
 * versioning.</strong> Unlike {@link JdbcPolicyAuthor}, which never touches an
 * already-published version and only moves a pointer, this class updates the
 * one row {@code (key_code, scope)} identifies, in place, guarded by {@code
 * version}: {@code expectedVersion == null} means "no row exists here yet",
 * refused with {@code STALE_VERSION} if one does; a non-null {@code
 * expectedVersion} must match the stored row's current version exactly,
 * refused with {@code STALE_VERSION} otherwise (whether because the row moved
 * on or never existed). That asymmetry with {@code JdbcPolicyAuthor} is ADR
 * 0030's own: "only policies are snapshotted onto business facts" — a setting
 * has no pinned-history reader to protect.
 */
@Repository
public class JdbcConfigurationValueAuthor implements ConfigurationValueAuthor {

    private final JdbcClient jdbc;
    private final AuditRecorder audit;
    private final Clock clock;
    private final ConfigurationValueCache cache;

    public JdbcConfigurationValueAuthor(
            JdbcClient jdbc, AuditRecorder audit, Clock clock, ConfigurationValueCache cache) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.cache = cache;
    }

    @Override
    @Transactional
    public <T> AuthoredConfigurationValue<T> set(
            ConfigurationKey<T> key,
            ResourceScope scope,
            @Nullable T value,
            boolean explicitNull,
            @Nullable Long expectedVersion,
            ActorRef setBy,
            String reason) {

        Objects.requireNonNull(key, "A configuration key is required");
        Objects.requireNonNull(scope, "A scope is required");
        Objects.requireNonNull(setBy, "An actor is required");
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Setting a configuration value requires a reason");
        }
        if (!key.isSettableAt(scope.type())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "%s cannot be set at %s scope; it is settable at %s"
                            .formatted(key.code(), scope.type(), key.settableScopes()));
        }
        if (explicitNull) {
            if (value != null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "An explicit null cannot carry a value");
            }
        } else {
            Objects.requireNonNull(value, "A value is required unless explicitNull is set");
            if (!key.valueType().isInstance(value)) {
                // A programming error, not a caller mistake: the HTTP boundary
                // (ConfigurationController) rejects a mismatched request field
                // before this is ever reached, the same division of labour
                // JdbcPolicyAuthor's own documentType check describes.
                throw new IllegalArgumentException("%s expects a %s value, got %s"
                        .formatted(key.code(), key.valueType().getSimpleName(), value.getClass()));
            }
        }

        StoredColumns columns = StoredColumns.of(key, explicitNull ? null : value);
        Optional<Row> existing = findRow(key.code(), scope);
        Instant now = clock.instant();
        UUID id;
        long newVersion;

        if (expectedVersion == null) {
            if (existing.isPresent()) {
                throw new ApiException(
                        ErrorCode.STALE_VERSION,
                        "%s already has a value at %s scope; re-read its current version and retry"
                                .formatted(key.code(), scope.type()));
            }
            id = Ids.newId();
            try {
                insert(id, key.code(), scope, columns, explicitNull, setBy.subject(), reason, now);
            } catch (DuplicateKeyException concurrentWriter) {
                // uq_configuration_value_{platform,tenant,brand,location}. Two
                // operators setting this scope for the first time at once would
                // otherwise silently let the second overwrite the first's row
                // through a "create" call that never expected one to exist.
                throw new ApiException(
                        ErrorCode.STALE_VERSION,
                        "Another operator set %s at %s scope concurrently; re-read its current version and retry"
                                .formatted(key.code(), scope.type()));
            } catch (DataIntegrityViolationException violation) {
                throw explain(violation);
            }
            newVersion = 0;
        } else {
            boolean updated;
            try {
                updated =
                        update(key.code(), scope, columns, explicitNull, setBy.subject(), reason, expectedVersion, now);
            } catch (DataIntegrityViolationException violation) {
                throw explain(violation);
            }
            if (!updated) {
                throw new ApiException(
                        ErrorCode.STALE_VERSION,
                        "%s at %s scope has moved on from version %d"
                                .formatted(key.code(), scope.type(), expectedVersion));
            }
            newVersion = expectedVersion + 1;
            // existing.isEmpty() here would mean the pre-read raced with a
            // concurrent first insert at this exact scope and lost, yet the
            // update above still matched it by version — re-read rather than
            // assume, so the audit target is never guessed at.
            id = existing.map(Row::id)
                    .orElseGet(() -> findRow(key.code(), scope)
                            .map(Row::id)
                            .orElseThrow(() -> new IllegalStateException(
                                    "%s at %s scope matched version %d on update but is now missing"
                                            .formatted(key.code(), scope.type(), expectedVersion))));
        }

        // Right after the write, not before: an eviction that fires and is then
        // rolled back with its transaction is merely a wasted cache miss, but
        // one that fires first would let a concurrent reader repopulate the
        // cache with the value this call is about to replace.
        cache.evict(key.code(), scope);

        // AuditFact.changeDocument() runs the map through Map.copyOf, which
        // rejects a null value at the top level — so "the field was previously
        // unset" (a legitimate, distinct fact from "the field was zero") has to
        // live inside a non-null container, the same way audit.domain.ChangeDocuments
        // nests before/after under one submap rather than two nullable top-level
        // entries. That class lives in audit's internal domain package, not its
        // api, so this builds the same shape by hand rather than importing it.
        Map<String, Object> valueChange = new LinkedHashMap<>();
        valueChange.put("before", existing.map(row -> row.typedValue(key)).orElse(null));
        valueChange.put("after", explicitNull ? null : value);

        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("keyCode", key.code());
        changed.put("scopeType", scope.type().name());
        changed.put("version", newVersion);
        changed.put("explicitNull", explicitNull);
        changed.put("value", valueChange);

        audit.record(AuditFact.of("tenant.configuration_value.set", AuditClass.BUSINESS)
                .by(setBy)
                .at(scope)
                .target("ConfigurationValue", id)
                .because(reason)
                .changed(changed)
                .correlatedBy(id.toString())
                .occurredAt(now)
                .build());

        return new AuthoredConfigurationValue<>(id, key.code(), scope.type(), value, explicitNull, newVersion);
    }

    @Override
    public Optional<Long> currentVersion(ConfigurationKey<?> key, ResourceScope scope) {
        return findRow(key.code(), scope).map(Row::version);
    }

    private Optional<Row> findRow(String keyCode, ResourceScope scope) {
        return jdbc.sql("""
                SELECT id, version, value_type, boolean_value, integer_value, decimal_value,
                       string_value, is_explicit_null
                  FROM tenant.configuration_values
                 WHERE key_code = :keyCode AND scope_type = :scopeType
                   AND tenant_id IS NOT DISTINCT FROM :tenantId
                   AND brand_id IS NOT DISTINCT FROM :brandId
                   AND location_id IS NOT DISTINCT FROM :locationId
                """)
                .param("keyCode", keyCode)
                .param("scopeType", scope.type().name())
                .param("tenantId", scope.tenantId())
                .param("brandId", scope.brandId())
                .param("locationId", scope.locationId())
                .query((resultSet, rowNumber) -> new Row(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getLong("version"),
                        resultSet.getString("value_type"),
                        (Boolean) resultSet.getObject("boolean_value"),
                        (Long) resultSet.getObject("integer_value"),
                        resultSet.getBigDecimal("decimal_value"),
                        resultSet.getString("string_value"),
                        resultSet.getBoolean("is_explicit_null")))
                .optional();
    }

    private void insert(
            UUID id,
            String keyCode,
            ResourceScope scope,
            StoredColumns columns,
            boolean explicitNull,
            String setBy,
            String reason,
            Instant now) {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values
                    (id, key_code, scope_type, tenant_id, brand_id, location_id, value_type,
                     boolean_value, integer_value, decimal_value, string_value, is_explicit_null,
                     set_by, reason, version, created_at, updated_at)
                VALUES
                    (:id, :keyCode, :scopeType, :tenantId, :brandId, :locationId, :valueType,
                     :booleanValue, :integerValue, :decimalValue, :stringValue, :isExplicitNull,
                     :setBy, :reason, 0, :now, :now)
                """)
                .param("id", id)
                .param("keyCode", keyCode)
                .param("scopeType", scope.type().name())
                .param("tenantId", scope.tenantId())
                .param("brandId", scope.brandId())
                .param("locationId", scope.locationId())
                .param("valueType", columns.valueType())
                .param("booleanValue", columns.booleanValue())
                .param("integerValue", columns.integerValue())
                .param("decimalValue", columns.decimalValue())
                .param("stringValue", columns.stringValue())
                .param("isExplicitNull", explicitNull)
                .param("setBy", setBy)
                .param("reason", reason)
                .param("now", at(now))
                .update();
    }

    private boolean update(
            String keyCode,
            ResourceScope scope,
            StoredColumns columns,
            boolean explicitNull,
            String setBy,
            String reason,
            long expectedVersion,
            Instant now) {
        return jdbc.sql("""
                UPDATE tenant.configuration_values
                   SET value_type = :valueType,
                       boolean_value = :booleanValue,
                       integer_value = :integerValue,
                       decimal_value = :decimalValue,
                       string_value = :stringValue,
                       is_explicit_null = :isExplicitNull,
                       set_by = :setBy,
                       reason = :reason,
                       version = version + 1,
                       updated_at = :now
                 WHERE key_code = :keyCode AND scope_type = :scopeType
                   AND tenant_id IS NOT DISTINCT FROM :tenantId
                   AND brand_id IS NOT DISTINCT FROM :brandId
                   AND location_id IS NOT DISTINCT FROM :locationId
                   AND version = :expectedVersion
                """)
                        .param("keyCode", keyCode)
                        .param("scopeType", scope.type().name())
                        .param("tenantId", scope.tenantId())
                        .param("brandId", scope.brandId())
                        .param("locationId", scope.locationId())
                        .param("valueType", columns.valueType())
                        .param("booleanValue", columns.booleanValue())
                        .param("integerValue", columns.integerValue())
                        .param("decimalValue", columns.decimalValue())
                        .param("stringValue", columns.stringValue())
                        .param("isExplicitNull", explicitNull)
                        .param("setBy", setBy)
                        .param("reason", reason)
                        .param("expectedVersion", expectedVersion)
                        .param("now", at(now))
                        .update()
                == 1;
    }

    /** Translates the constraints into the sentence each one is protecting. */
    private static ApiException explain(DataIntegrityViolationException violation) {
        String message = String.valueOf(violation.getMostSpecificCause().getMessage());
        if (message.contains("fk_configuration_value_tenant")) {
            return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such tenant");
        }
        if (message.contains("fk_configuration_value_brand")) {
            return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "That brand does not belong to this tenant");
        }
        if (message.contains("fk_configuration_value_location")) {
            return new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND, "That location does not belong to this tenant and brand");
        }
        return new ApiException(ErrorCode.RESOURCE_CONFLICT, "The requested value conflicts with an existing resource");
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** Maps a typed value onto the four nullable columns the row's {@code value_type} selects between. */
    private record StoredColumns(
            String valueType,
            @Nullable Boolean booleanValue,
            @Nullable Long integerValue,
            @Nullable BigDecimal decimalValue,
            @Nullable String stringValue) {

        static <T> StoredColumns of(ConfigurationKey<T> key, @Nullable T value) {
            Class<T> type = key.valueType();
            if (type == Boolean.class) {
                return new StoredColumns("BOOLEAN", (Boolean) value, null, null, null);
            }
            if (type == Integer.class) {
                return new StoredColumns(
                        "INTEGER", null, value == null ? null : Long.valueOf((Integer) value), null, null);
            }
            if (type == Long.class) {
                return new StoredColumns("INTEGER", null, (Long) value, null, null);
            }
            if (type == BigDecimal.class) {
                return new StoredColumns("DECIMAL", null, null, (BigDecimal) value, null);
            }
            if (type == String.class) {
                return new StoredColumns("STRING", null, null, null, (String) value);
            }
            throw new IllegalStateException(
                    "Unsupported configuration value type %s for %s".formatted(type, key.code()));
        }
    }

    private record Row(
            UUID id,
            long version,
            String valueType,
            @Nullable Boolean booleanValue,
            @Nullable Long integerValue,
            @Nullable BigDecimal decimalValue,
            @Nullable String stringValue,
            boolean explicitNull) {

        /** Mirrors {@code JdbcConfigurationResolver.Row.typedValue}: narrows an INTEGER only for an Integer key. */
        @Nullable
        Object typedValue(ConfigurationKey<?> key) {
            if (explicitNull) {
                return null;
            }
            Object raw =
                    switch (valueType) {
                        case "BOOLEAN" -> booleanValue;
                        case "INTEGER" -> integerValue;
                        case "DECIMAL" -> decimalValue;
                        case "STRING" -> stringValue;
                        default -> throw new IllegalStateException("Unsupported stored value type " + valueType);
                    };
            if (raw instanceof Long value && key.valueType() == Integer.class) {
                return Math.toIntExact(value);
            }
            return raw;
        }
    }
}
