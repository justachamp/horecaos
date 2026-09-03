package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
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
 * and why" without a database client. This is that read, and nothing else —
 * authoring a scoped override is a tenant-administration act with its own
 * capability story, not exposed here.
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

    public ConfigurationController(ConfigurationResolver resolver) {
        this.resolver = resolver;
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

    /** Captures the wildcard from {@link ConfigurationKey#find} so the resolver's generic methods apply. */
    private <T> ConfigurationResolutionResponse resolveAndExplain(ConfigurationKey<T> key, ResourceScope scope) {
        Resolved<T> resolved = resolver.resolve(key, scope);
        ResolutionTrace trace = resolver.explain(key, scope);
        return ConfigurationResolutionResponse.of(key, resolved, trace);
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
            String describe) {

        static <T> ConfigurationResolutionResponse of(
                ConfigurationKey<T> key, Resolved<T> resolved, ResolutionTrace trace) {
            return new ConfigurationResolutionResponse(
                    key.code(),
                    resolved.value(),
                    resolved.cameFromDefault(),
                    trace.source().name(),
                    trace.winningScope(),
                    trace.inspectedLevels().stream().map(TraceLevel::of).toList(),
                    trace.describe());
        }
    }

    public record TraceLevel(ScopeType scopeType, String outcome) {
        static TraceLevel of(ResolutionTrace.Level level) {
            return new TraceLevel(level.scopeType(), level.outcome().name());
        }
    }
}
