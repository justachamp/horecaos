package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.AuthoredConfigurationValue;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.ConfigurationValueAuthor;
import uz.horecaos.platform.tenancy.api.ResolutionTrace;
import uz.horecaos.platform.tenancy.api.Resolved;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A tenant's own read, write and resolution trace over its ADR 0030
 * configuration — settings.md §1.1's scope bar and §1.2's {@code
 * q-inherited-field} (wave P31, gap map row {@code 10/X.1}).
 *
 * <p>{@link ConfigurationController}'s own Javadoc named this gap before it
 * existed: every method there is {@link Capability#PLATFORM_ADMIN} at {@code
 * PLATFORM} scope, "not tenant self-service, which would need its own
 * narrower capability and its own conversation about who holds it". {@link
 * Capability#TENANT_CONFIGURATION_READ} and {@link
 * Capability#TENANT_CONFIGURATION_WRITE} are that capability, and this
 * controller is deliberately its own — narrower in two ways a shared
 * implementation could not enforce implicitly:
 *
 * <ol>
 *   <li>Every key this controller names, in the list and in a direct lookup
 *       by code, is filtered to {@link ConfigurationKey#tenantVisible()}. A
 *       platform-only default (audit retention, the commercial enforcement
 *       ceiling) is not merely omitted from the index — asking for it by
 *       code answers {@link ErrorCode#RESOURCE_NOT_FOUND}, the same answer
 *       an unregistered code gets, so a tenant cannot distinguish "this key
 *       does not exist" from "this key is not yours to see".
 *   <li>{@code tenantId} is taken only from the path, never from the request
 *       body — unlike {@link ConfigurationController.SetConfigurationValueRequest},
 *       which lets a {@code PLATFORM_ADMIN} caller target any tenant by
 *       design. A caller here holds {@link Capability#TENANT_CONFIGURATION_WRITE}
 *       at exactly the tenant the path names (ADR 0025's scope check), and
 *       every {@link ResourceScope} this controller builds is pinned to that
 *       same tenant; a request body cannot smuggle a different one in.
 * </ol>
 *
 * <p>{@code scopeType=PLATFORM} is refused outright (as {@link
 * ErrorCode#VALIDATION_FAILED}): the scope bar offers TENANT, BRAND and
 * LOCATION — the levels a tenant may actually edit — and a platform default
 * is something this surface only ever inherits from, never writes.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/configuration")
@Tag(
        name = "Operations configuration",
        description = "A tenant's own read, write and resolution trace over its ADR 0030 configuration")
public class OperationsConfigurationController {

    private final ConfigurationResolver resolver;
    private final ConfigurationValueAuthor values;
    private final CurrentActor currentActor;

    public OperationsConfigurationController(
            ConfigurationResolver resolver, ConfigurationValueAuthor values, CurrentActor currentActor) {
        this.resolver = resolver;
        this.values = values;
        this.currentActor = currentActor;
    }

    @GetMapping("/keys")
    @RequiresCapability(Capability.TENANT_CONFIGURATION_READ)
    @Operation(
            summary = "Every configuration key this tenant may see",
            description = "ConfigurationKeys.all() filtered to tenantVisible() — the settings.md "
                    + "\"find a setting\" registry and the scope bar's own key picker. A platform-only "
                    + "default is absent here even though ConfigurationController's PLATFORM_ADMIN "
                    + "surface still lists it.")
    List<OperationsConfigurationKeyResponse> keys() {
        return ConfigurationKeys.all().stream()
                .filter(ConfigurationKey::tenantVisible)
                .map(OperationsConfigurationKeyResponse::of)
                .sorted((left, right) -> left.code().compareTo(right.code()))
                .toList();
    }

    @GetMapping("/keys/{code}/resolution")
    @RequiresCapability(Capability.TENANT_CONFIGURATION_READ)
    @Operation(
            summary = "Resolve one key at TENANT, BRAND or LOCATION scope, and explain why",
            description = "Walks the ADR 0030 chain and reports which level supplied the value, or "
                    + "that the code default did. tenantId always comes from the path; scopeType "
                    + "decides whether brandId/locationId are required, on the same rule ResourceScope "
                    + "itself enforces. Refused for a key that is not tenantVisible() and for "
                    + "scopeType=PLATFORM.")
    OperationsConfigurationResolutionResponse resolution(
            @PathVariable UUID tenantId,
            @PathVariable String code,
            @RequestParam ScopeType scopeType,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId) {

        ConfigurationKey<?> key = tenantVisibleKey(code);
        ResourceScope scope = scopeOf(tenantId, scopeType, brandId, locationId);
        return resolveAndExplain(key, scope);
    }

    @PostMapping("/keys/{code}/values")
    @RequiresCapability(value = Capability.TENANT_CONFIGURATION_WRITE, mutating = true)
    @Operation(
            summary = "Set the value stored at exactly one key and scope, for this tenant",
            description = "Same shape as ConfigurationController.setValue, minus tenantId in the "
                    + "body: the path is the only source of tenant scope here. Refused for an "
                    + "unregistered or non-tenantVisible key, a scope the key does not declare "
                    + "settable, scopeType=PLATFORM, or a value whose shape does not match the "
                    + "key's declared type. expectedVersion null means \"nothing is set here yet\"; "
                    + "otherwise it must be the version a prior GET .../resolution reported at "
                    + "exactly this scope, or the write is refused with STALE_VERSION.")
    ResponseEntity<OperationsConfigurationValueResponse> setValue(
            @PathVariable UUID tenantId,
            @PathVariable String code,
            @Valid @RequestBody OperationsSetConfigurationValueRequest request) {

        ConfigurationKey<?> key = tenantVisibleKey(code);
        ResourceScope scope = scopeOf(tenantId, request.scopeType(), request.brandId(), request.locationId());
        return ResponseEntity.ok(setErased(key, scope, request));
    }

    /** {@link ConfigurationKeys#find}, refusing a key this surface does not show at all. */
    private static ConfigurationKey<?> tenantVisibleKey(String code) {
        ConfigurationKey<?> key = ConfigurationKeys.find(code)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No configuration key " + code));
        if (!key.tenantVisible()) {
            // Deliberately the same error as "unregistered": a tenant must not be
            // able to tell a platform-only key apart from one that does not exist.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No configuration key " + code);
        }
        return key;
    }

    /** Captures the wildcard from {@link ConfigurationKeys#find} so the author's generic methods apply. */
    private <T> OperationsConfigurationValueResponse setErased(
            ConfigurationKey<T> key, ResourceScope scope, OperationsSetConfigurationValueRequest request) {
        T typedValue = request.explicitNull() ? null : extractTypedValue(key, request);
        AuthoredConfigurationValue<T> authored = values.set(
                key,
                scope,
                typedValue,
                request.explicitNull(),
                request.expectedVersion(),
                ActorRef.user(currentActor.get().subject(), null),
                request.reason());
        return OperationsConfigurationValueResponse.of(authored);
    }

    /**
     * Picks the one request field matching {@code key.valueType()} and refuses
     * everything else — a string sent for an integer key is a refusal, not a
     * cast. Mirrors {@link ConfigurationController}'s own extraction exactly.
     */
    private static <T> T extractTypedValue(ConfigurationKey<T> key, OperationsSetConfigurationValueRequest request) {
        Class<T> type = key.valueType();
        String expectedField;
        Object raw;
        if (type == Boolean.class) {
            expectedField = "booleanValue";
            raw = request.booleanValue();
        } else if (type == Integer.class || type == Long.class) {
            expectedField = "integerValue";
            raw = request.integerValue();
        } else if (type == BigDecimal.class) {
            expectedField = "decimalValue";
            raw = request.decimalValue();
        } else if (type == String.class) {
            expectedField = "stringValue";
            raw = request.stringValue();
        } else {
            throw new IllegalStateException("Unsupported configuration value type " + type);
        }
        requireOnlyField(request, expectedField);
        if (raw == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "%s is a %s key: set %s, or explicitNull"
                            .formatted(key.code(), type.getSimpleName(), expectedField));
        }
        if (type == Integer.class) {
            raw = Math.toIntExact((Long) raw);
        }
        return type.cast(raw);
    }

    private static void requireOnlyField(OperationsSetConfigurationValueRequest request, String expectedField) {
        List<String> extraneous = new ArrayList<>();
        if (!"booleanValue".equals(expectedField) && request.booleanValue() != null) {
            extraneous.add("booleanValue");
        }
        if (!"integerValue".equals(expectedField) && request.integerValue() != null) {
            extraneous.add("integerValue");
        }
        if (!"decimalValue".equals(expectedField) && request.decimalValue() != null) {
            extraneous.add("decimalValue");
        }
        if (!"stringValue".equals(expectedField) && request.stringValue() != null) {
            extraneous.add("stringValue");
        }
        if (!extraneous.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Only %s applies to this key's type; also set: %s".formatted(expectedField, extraneous));
        }
    }

    /** Captures the wildcard from {@link ConfigurationKey#find} so the resolver's generic methods apply. */
    private <T> OperationsConfigurationResolutionResponse resolveAndExplain(
            ConfigurationKey<T> key, ResourceScope scope) {
        Resolved<T> resolved = resolver.resolve(key, scope);
        ResolutionTrace trace = resolver.explain(key, scope);
        Long currentVersionAtScope = values.currentVersion(key, scope).orElse(null);
        return OperationsConfigurationResolutionResponse.of(key, resolved, trace, currentVersionAtScope);
    }

    /**
     * Builds a scope pinned to {@code tenantId} from the path — the request
     * never supplies its own tenant. {@code PLATFORM} is refused: nothing on
     * this surface ever sets a platform default.
     */
    private static ResourceScope scopeOf(
            UUID tenantId, ScopeType scopeType, @Nullable UUID brandId, @Nullable UUID locationId) {
        try {
            return switch (scopeType) {
                case PLATFORM ->
                    throw new IllegalArgumentException("scopeType PLATFORM is not settable from operations");
                case TENANT -> ResourceScope.tenant(tenantId);
                case BRAND -> ResourceScope.brand(tenantId, require(brandId, "brandId"));
                case LOCATION ->
                    ResourceScope.location(tenantId, require(brandId, "brandId"), require(locationId, "locationId"));
            };
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }
    }

    private static UUID require(@Nullable UUID value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required for this scopeType");
        }
        return value;
    }

    /** One declared, tenant-visible key, as the code registered it. */
    public record OperationsConfigurationKeyResponse(
            String code,
            String valueType,
            @Nullable Object defaultValue,
            List<ScopeType> settableScopes,
            String owningModule,
            boolean explicitNullTerminates,
            String description) {

        static OperationsConfigurationKeyResponse of(ConfigurationKey<?> key) {
            return new OperationsConfigurationKeyResponse(
                    key.code(),
                    key.valueType().getSimpleName(),
                    key.defaultValue(),
                    // PLATFORM is never a settable scope from this surface's own POST,
                    // but it is still true information about the key's declaration —
                    // shown so the scope bar can explain why a value it did not set
                    // still resolves to something.
                    key.settableScopes().stream().sorted().toList(),
                    key.owningModule(),
                    key.explicitNullTerminates(),
                    key.description());
        }
    }

    /** What a key resolved to at one scope, and the trace that explains it. */
    public record OperationsConfigurationResolutionResponse(
            String keyCode,
            @Nullable Object value,
            boolean cameFromDefault,
            String source,
            @Nullable ScopeType winningScope,
            List<OperationsTraceLevel> inspectedLevels,
            String describe,
            @Nullable Long currentVersionAtScope) {

        /**
         * @param currentVersionAtScope the version of the row stored at exactly
         *                              the requested scope, not the (possibly
         *                              more general) {@code winningScope} —
         *                              absent when nothing is set exactly here.
         *                              Read this before a {@code POST
         *                              .../values} at the same scope: {@code
         *                              null} means pass {@code expectedVersion:
         *                              null} to create, otherwise pass this
         *                              value back.
         */
        static <T> OperationsConfigurationResolutionResponse of(
                ConfigurationKey<T> key,
                Resolved<T> resolved,
                ResolutionTrace trace,
                @Nullable Long currentVersionAtScope) {
            return new OperationsConfigurationResolutionResponse(
                    key.code(),
                    resolved.value(),
                    resolved.cameFromDefault(),
                    trace.source().name(),
                    trace.winningScope(),
                    trace.inspectedLevels().stream()
                            .map(OperationsTraceLevel::of)
                            .toList(),
                    trace.describe(),
                    currentVersionAtScope);
        }
    }

    public record OperationsTraceLevel(ScopeType scopeType, String outcome) {
        static OperationsTraceLevel of(ResolutionTrace.Level level) {
            return new OperationsTraceLevel(level.scopeType(), level.outcome().name());
        }
    }

    /**
     * Sets the value at one key and scope, for the tenant the path names.
     * Exactly one of {@code booleanValue} / {@code integerValue} / {@code
     * decimalValue} / {@code stringValue} applies, chosen by the key's
     * declared type — the other three, and a value at all when {@code
     * explicitNull} is set, are refusals. Carries no {@code tenantId}: unlike
     * {@link ConfigurationController.SetConfigurationValueRequest}, this
     * surface never lets a caller name a tenant other than the one in the
     * path.
     */
    public record OperationsSetConfigurationValueRequest(
            @NotNull ScopeType scopeType,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            boolean explicitNull,
            @Nullable Boolean booleanValue,
            @Nullable Long integerValue,
            @Nullable BigDecimal decimalValue,
            @Nullable String stringValue,
            @Nullable Long expectedVersion,
            @NotBlank @Size(max = 1000) String reason) {}

    /** The row {@link #setValue} just wrote. */
    public record OperationsConfigurationValueResponse(
            UUID id,
            String keyCode,
            ScopeType scopeType,
            @Nullable Object value,
            boolean explicitNull,
            long version) {

        static <T> OperationsConfigurationValueResponse of(AuthoredConfigurationValue<T> authored) {
            return new OperationsConfigurationValueResponse(
                    authored.id(),
                    authored.keyCode(),
                    authored.scopeType(),
                    authored.value(),
                    authored.explicitNull(),
                    authored.version());
        }
    }
}
