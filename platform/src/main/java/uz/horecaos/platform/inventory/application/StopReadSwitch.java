package uz.horecaos.platform.inventory.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.inventory.api.InventoryConfigurationKeys;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.api.ResolutionTrace;
import uz.horecaos.platform.tenancy.api.Resolved;

/**
 * Whether stops are consulted at all (ADR 0141, rollback switch three: {@code
 * inventory.stops.read_enabled}).
 *
 * <p>The one place the switch is read, so the resolver, the stop list's overlay, the stop
 * service's creation check and the POS poll's two writers cannot disagree about it. On by default;
 * off is the decommission, which the configuration writer refuses until a materialisation run's
 * report was acknowledged ({@link StopReadSwitchGuard}). The key is settable at platform and
 * tenant scope, so a brand's answer is its tenant's; the brand parameter is the scope the
 * resolver is asked at, which keeps a later brand-level override a change to the key and to
 * nothing that reads it.
 */
@Component
public class StopReadSwitch {

    private final ConfigurationResolver configuration;

    public StopReadSwitch(ConfigurationResolver configuration) {
        this.configuration = configuration;
    }

    /** Whether the stops of {@code brandId} are consulted. */
    public boolean readsEnabled(UUID tenantId, UUID brandId) {
        Boolean enabled = configuration.value(
                InventoryConfigurationKeys.STOPS_READ_ENABLED, ResourceScope.brand(tenantId, brandId));
        return !Boolean.FALSE.equals(enabled);
    }

    /** Whether the tenant's stops are consulted, for a caller that has no brand in hand. */
    public boolean readsEnabled(UUID tenantId) {
        Boolean enabled =
                configuration.value(InventoryConfigurationKeys.STOPS_READ_ENABLED, ResourceScope.tenant(tenantId));
        return !Boolean.FALSE.equals(enabled);
    }

    /**
     * A switch with nothing configured, which is the key's own default and so on: a fixture built
     * before the decommission existed.
     */
    public static StopReadSwitch alwaysOn() {
        return new StopReadSwitch(new ConfigurationResolver() {
            @Override
            public <T> Resolved<T> resolve(ConfigurationKey<T> key, ResourceScope scope) {
                return new Resolved<>(
                        key.defaultValue(),
                        new ResolutionTrace(key.code(), ResolutionTrace.Source.CODE_DEFAULT, null, List.of()));
            }

            @Override
            public ResolutionTrace explain(ConfigurationKey<?> key, ResourceScope scope) {
                return resolve(key, scope).trace();
            }
        });
    }
}
