package uz.horecaos.platform.notifications.application;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.notifications.api.ReceiptEnablement;

/**
 * {@link ReceiptEnablement} read from deployment configuration (ADR 0146
 * Decisions 4 and 5).
 *
 * <p>{@code horecaos.sms.receipts.enabled-provider-types}, a comma-separated list,
 * empty by default. Empty is the shipped state for every real gateway: VAS has no
 * receipt source until one real callback has been captured against a controlled
 * account, and the provider's own document shows the callback's {@code key}
 * arriving empty. Turning a type on is therefore a deployment decision taken after
 * that capture, not a tenant setting and not a code change.
 */
@Component
public class ConfiguredReceiptEnablement implements ReceiptEnablement {

    private final Set<String> enabled;

    public ConfiguredReceiptEnablement(
            @Value("${horecaos.sms.receipts.enabled-provider-types:}") String enabledProviderTypes) {
        this.enabled = Arrays.stream(enabledProviderTypes.split(","))
                .map(String::trim)
                .filter(type -> !type.isEmpty())
                .map(type -> type.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<String> enabledProviderTypes() {
        return enabled;
    }

    @Override
    public boolean isEnabled(String providerType) {
        return providerType != null && enabled.contains(providerType.toUpperCase(Locale.ROOT));
    }
}
