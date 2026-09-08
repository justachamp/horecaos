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
 * The ADR 0030 code-owned configuration registry and its resolution trace
 * (control-plane IA 2.7 and 8.5).
 *
 * <p>{@link ConfigurationKeys} has always been a real, code-owned registry —
 * every key a module declares, its default, and which scopes may override it
 * — and {@link ConfigurationResolver} has always been able to explain why any
 * one of them resolved the way it did. Neither was reachable over HTTP before
 * this: nothing served the whole key list, and nothing let an operator ask
 * "what does {@code ordering.cart_expiry_minutes} resolve to for this tenant,
 * and why" without a database client.
 *
 * <p><strong>Authoring now lives here too.</strong> Until 2026-09-08,
 * {@code tenant.configuration_values} had a resolver and no writer anywhere in
 * the platform — a value could only be set by hand in SQL, exactly the gap
 * {@code tenancy.api.PolicyAuthor}'s own Javadoc records for {@code
 * tenant.policies} before it existed. {@link #setValue} closes it, over
 * {@link ConfigurationValueAuthor}. Every read and write here is gated by
 * {@link Capability#PLATFORM_ADMIN} at platform scope, matching the read
 * endpoints below: this is a control-plane screen platform operators use on a
 * tenant's behalf, the same posture {@code PLATFORM_ADMIN}'s own Javadoc
 * describes ("issued by Keycloak... never granted through tenant
 * administration") — not tenant self-service, which would need its own
 * narrower capability and its own conversation about who holds it.
 *
 * <p>IA 8.5's "platform-level order/SLA/retention defaults that tenants
 * inherit" is answered by the same {@link #keys()} list this screen's own
 * 2.7 key picker uses, filtered to {@link ConfigurationKey#tenantVisible()}
 * entries: a platform default a tenant may never even see is not one it
 * inherits. There is no separate SLA/retention registry — {@code
 * ConfigurationKeys} is the whole of what exists.
 */
@RestController
@RequestMapping("/api/v1/control-plane/configuration")
@Tag(name = "Configuration", description = "The ADR 0030 code-owned configuration registry and its resolution trace")
public class ConfigurationController {

    private final ConfigurationResolver resolver;
    private final ConfigurationValueAuthor values;
    private final CurrentActor currentActor;

    public ConfigurationController(
            ConfigurationResolver resolver, ConfigurationValueAuthor values, CurrentActor currentActor) {
        this.resolver = resolver;
        this.values = values;
        this.currentActor = currentActor;
    }

    @GetMapping("/keys")
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Every configuration key this build declares",
            description = "Code-owned (ADR 0030): a key exists here or it does not exist at all. "
                    + "Static within a build, so there is nothing to paginate.")
    List<ConfigurationKeyResponse> keys() {
        return ConfigurationKeys.all().stream()
                .map(ConfigurationKeyResponse::of)
                .sorted((left, right) -> left.code().compareTo(right.code()))
                .toList();
    }

    @GetMapping("/keys/{code}/resolution")
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Resolve one key at one scope, and explain why",
            description = "Walks the ADR 0030 chain (platform -> tenant -> brand -> location) and "
                    + "reports which level supplied the value, or that the code default did. "
                    + "scopeType decides which of tenantId/brandId/locationId are required, on "
                    + "exactly the rule ResourceScope itself enforces.")
    ConfigurationResolutionResponse resolution(
            @PathVariable String code,
            @RequestParam ScopeType scopeType,
            @RequestParam(required = false) @Nullable UUID tenantId,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId) {

        ConfigurationKey<?> key = ConfigurationKeys.find(code)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No configuration key " + code));
        ResourceScope scope = scopeOf(scopeType, tenantId, brandId, locationId);
        return resolveAndExplain(key, scope);
    }

    @PostMapping("/keys/{code}/values")
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Set the value stored at exactly one key and scope",
            description = "Refused for an unregistered key, a scope the key does not declare "
                    + "settable, or a value whose shape does not match the key's declared type — "
                    + "never coerced. expectedVersion null means \"nothing is set here yet\"; "
                    + "otherwise it must be the version currentVersionAtScope on a prior "
                    + "GET .../resolution last reported, or the write is refused with "
                    + "STALE_VERSION.")
    ResponseEntity<ConfigurationValueResponse> setValue(
            @PathVariable String code, @Valid @RequestBody SetConfigurationValueRequest request) {

        ConfigurationKey<?> key = ConfigurationKeys.find(code)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No configuration key " + code));
        ResourceScope scope = scopeOf(request.scopeType(), request.tenantId(), request.brandId(), request.locationId());
        return ResponseEntity.ok(setErased(key, scope, request));
    }

    /** Captures the wildcard from {@link ConfigurationKeys#find} so the author's generic methods apply. */
    private <T> ConfigurationValueResponse setErased(
            ConfigurationKey<T> key, ResourceScope scope, SetConfigurationValueRequest request) {
        T typedValue = request.explicitNull() ? null : extractTypedValue(key, request);
        AuthoredConfigurationValue<T> authored = values.set(
                key,
                scope,
                typedValue,
                request.explicitNull(),
                request.expectedVersion(),
                ActorRef.user(currentActor.get().subject(), null),
                request.reason());
        return ConfigurationValueResponse.of(authored);
    }

    /**
     * Picks the one request field matching {@code key.valueType()} and refuses
     * everything else — a string sent for an integer key is a refusal, not a
     * cast.
     */
    private static <T> T extractTypedValue(ConfigurationKey<T> key, SetConfigurationValueRequest request) {
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

    private static void requireOnlyField(SetConfigurationValueRequest request, String expectedField) {
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
    private <T> ConfigurationResolutionResponse resolveAndExplain(ConfigurationKey<T> key, ResourceScope scope) {
        Resolved<T> resolved = resolver.resolve(key, scope);
        ResolutionTrace trace = resolver.explain(key, scope);
        Long currentVersionAtScope = values.currentVersion(key, scope).orElse(null);
        return ConfigurationResolutionResponse.of(key, resolved, trace, currentVersionAtScope);
    }

    private static ResourceScope scopeOf(
            ScopeType scopeType, @Nullable UUID tenantId, @Nullable UUID brandId, @Nullable UUID locationId) {
        try {
            return switch (scopeType) {
                case PLATFORM -> ResourceScope.platform();
                case TENANT -> ResourceScope.tenant(require(tenantId, "tenantId"));
                case BRAND -> ResourceScope.brand(require(tenantId, "tenantId"), require(brandId, "brandId"));
                case LOCATION ->
                    ResourceScope.location(
                            require(tenantId, "tenantId"),
                            require(brandId, "brandId"),
                            require(locationId, "locationId"));
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

    /** One declared key, as the code registered it. */
    public record ConfigurationKeyResponse(
            String code,
            String valueType,
            @Nullable Object defaultValue,
            List<ScopeType> settableScopes,
            String owningModule,
            boolean tenantVisible,
            boolean explicitNullTerminates,
            String description) {

        static ConfigurationKeyResponse of(ConfigurationKey<?> key) {
            return new ConfigurationKeyResponse(
                    key.code(),
                    key.valueType().getSimpleName(),
                    key.defaultValue(),
                    key.settableScopes().stream().sorted().toList(),
                    key.owningModule(),
                    key.tenantVisible(),
                    key.explicitNullTerminates(),
                    key.description());
        }
    }

    /** What a key resolved to at one scope, and the trace that explains it. */
    public record ConfigurationResolutionResponse(
            String keyCode,
            @Nullable Object value,
            boolean cameFromDefault,
            String source,
            @Nullable ScopeType winningScope,
            List<TraceLevel> inspectedLevels,
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
        static <T> ConfigurationResolutionResponse of(
                ConfigurationKey<T> key,
                Resolved<T> resolved,
                ResolutionTrace trace,
                @Nullable Long currentVersionAtScope) {
            return new ConfigurationResolutionResponse(
                    key.code(),
                    resolved.value(),
                    resolved.cameFromDefault(),
                    trace.source().name(),
                    trace.winningScope(),
                    trace.inspectedLevels().stream().map(TraceLevel::of).toList(),
                    trace.describe(),
                    currentVersionAtScope);
        }
    }

    public record TraceLevel(ScopeType scopeType, String outcome) {
        static TraceLevel of(ResolutionTrace.Level level) {
            return new TraceLevel(level.scopeType(), level.outcome().name());
        }
    }

    /**
     * Sets the value at one key and scope. Exactly one of {@code booleanValue}
     * / {@code integerValue} / {@code decimalValue} / {@code stringValue}
     * applies, chosen by the key's declared type — the other three, and a
     * value at all when {@code explicitNull} is set, are refusals.
     */
    public record SetConfigurationValueRequest(
            @NotNull ScopeType scopeType,
            @Nullable UUID tenantId,
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
    public record ConfigurationValueResponse(
            UUID id,
            String keyCode,
            ScopeType scopeType,
            @Nullable Object value,
            boolean explicitNull,
            long version) {

        static <T> ConfigurationValueResponse of(AuthoredConfigurationValue<T> authored) {
            return new ConfigurationValueResponse(
                    authored.id(),
                    authored.keyCode(),
                    authored.scopeType(),
                    authored.value(),
                    authored.explicitNull(),
                    authored.version());
        }
    }
}
