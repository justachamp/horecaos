package uz.qoida.platform.integration.provider;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import uz.qoida.platform.integration.api.provider.ProviderCapabilityCatalog;
import uz.qoida.platform.integration.api.provider.ProviderCategory;
import uz.qoida.platform.integration.camel.notification.NotificationChannelAdapter;

/** ADR 0026 declaration backed by the notification adapters wired in this build. */
@Component
public class NotificationProviderCapabilityCatalog implements ProviderCapabilityCatalog {

    private final Map<String, Set<String>> capabilitiesByProvider;

    public NotificationProviderCapabilityCatalog(List<NotificationChannelAdapter> adapters) {
        capabilitiesByProvider = adapters.stream().collect(Collectors.groupingBy(
                NotificationChannelAdapter::providerType,
                Collectors.mapping(adapter -> capabilityFor(adapter.channel()), Collectors.toUnmodifiableSet())));
    }

    @Override
    public ProviderCategory category() {
        return ProviderCategory.NOTIFICATION;
    }

    @Override
    public Optional<Declaration> declarationFor(String providerType) {
        return Optional.ofNullable(capabilitiesByProvider.get(providerType)).map(capabilities ->
                new Declaration(capabilities, "notification/%s/v1".formatted(providerType)));
    }

    private static String capabilityFor(String channel) {
        return "SEND_" + channel.toUpperCase(Locale.ROOT).replace('-', '_');
    }
}
