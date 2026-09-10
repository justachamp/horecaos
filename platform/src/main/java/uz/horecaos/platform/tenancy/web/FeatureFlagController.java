package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcFeatureFlagOverview;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcFeatureFlagOverview.StoredSetting;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Feature flags (ADR 0082): the rollout view, and what one tenant sees.
 *
 * <p>A flag is a boolean configuration key in the {@code feature.} namespace,
 * so turning one on or off is the ordinary configuration write — {@code POST
 * /control-plane/configuration/keys/{code}/values} at platform or tenant
 * scope, with a reason and the expected version, audited like any other
 * setting. Clearing a tenant's override is the same write with {@code
 * explicitNull}: the tenant then follows the platform value again. This class
 * adds only the two reads a rollout needs.
 */
@RestController
@Tag(name = "Feature flags", description = "ADR 0082: staged rollout as boolean configuration keys")
public class FeatureFlagController {

    private final ConfigurationResolver resolver;
    private final JdbcFeatureFlagOverview overview;

    public FeatureFlagController(ConfigurationResolver resolver, JdbcFeatureFlagOverview overview) {
        this.resolver = resolver;
        this.overview = overview;
    }

    @GetMapping("/api/v1/control-plane/feature-flags")
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Every feature flag and where it is on",
            description = "Each flag with its default, the platform value if one is set, and every "
                    + "tenant set apart from the platform. A tenant not listed follows the platform "
                    + "value.")
    List<FeatureFlagView> flags() {
        List<ConfigurationKey<Boolean>> flags = ConfigurationKeys.featureFlags();
        List<StoredSetting> settings =
                overview.settingsFor(flags.stream().map(ConfigurationKey::code).toList());
        return flags.stream()
                .map(flag -> FeatureFlagView.of(
                        flag,
                        settings.stream()
                                .filter(setting -> setting.keyCode().equals(flag.code()))
                                .toList()))
                .toList();
    }

    @GetMapping("/api/v1/operations/tenants/{tenantId}/feature-flags")
    @RequiresCapability(value = Capability.TENANT_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Which feature flags are on for this tenant",
            description = "Resolved: the tenant's own setting, else the platform value, else off.")
    Map<String, Boolean> forTenant(@PathVariable UUID tenantId) {
        Map<String, Boolean> resolved = new LinkedHashMap<>();
        for (ConfigurationKey<Boolean> flag : ConfigurationKeys.featureFlags()) {
            Boolean value =
                    resolver.resolve(flag, ResourceScope.tenant(tenantId)).value();
            resolved.put(flag.code(), Boolean.TRUE.equals(value));
        }
        return resolved;
    }

    /**
     * One flag as the rollout screen shows it.
     *
     * @param platformValue null when nothing is set at the platform, so the default applies
     * @param platformVersion the platform row's version, needed to change it; null when absent
     */
    public record FeatureFlagView(
            String code,
            String description,
            boolean defaultValue,
            @Nullable Boolean platformValue,
            @Nullable Long platformVersion,
            List<TenantSetting> tenants) {

        static FeatureFlagView of(ConfigurationKey<Boolean> flag, List<StoredSetting> settings) {
            StoredSetting platform = settings.stream()
                    .filter(setting -> setting.scopeType().equals("PLATFORM"))
                    .findFirst()
                    .orElse(null);
            return new FeatureFlagView(
                    flag.code(),
                    flag.description(),
                    Boolean.TRUE.equals(flag.defaultValue()),
                    platform == null ? null : platform.value(),
                    platform == null ? null : platform.version(),
                    settings.stream()
                            .filter(setting -> setting.scopeType().equals("TENANT"))
                            .map(TenantSetting::of)
                            .toList());
        }
    }

    /**
     * @param value null when the tenant has been handed back to the platform value
     */
    public record TenantSetting(
            UUID tenantId,
            @Nullable String tenantName,
            @Nullable Boolean value,
            long version) {

        static TenantSetting of(StoredSetting setting) {
            return new TenantSetting(
                    java.util.Objects.requireNonNull(setting.tenantId()),
                    setting.tenantName(),
                    setting.value(),
                    setting.version());
        }
    }
}
