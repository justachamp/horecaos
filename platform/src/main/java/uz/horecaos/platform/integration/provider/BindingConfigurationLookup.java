package uz.horecaos.platform.integration.provider;

import java.util.Map;
import uz.horecaos.platform.integration.api.provider.BindingRef;

/**
 * The merged non-secret configuration of one ADR 0026 binding (ADR 0030).
 *
 * <p>The installation's {@code non_sensitive_config} with the binding's
 * {@code configuration_override} laid over it, narrower wins. Never a credential:
 * {@code secret_reference} is not selected by anything that implements this.
 * Tenant-scoped through the {@link BindingRef}, because a binding id alone is not
 * proof of ownership.
 */
public interface BindingConfigurationLookup {

    Map<String, String> configuration(BindingRef binding);

    /** For a caller with nothing to read, which is every test that binds no configuration. */
    BindingConfigurationLookup NONE = binding -> Map.of();
}
