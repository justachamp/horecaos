package uz.horecaos.platform.integration.provider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import uz.horecaos.platform.integration.api.delivery.DeliveryPartner;
import uz.horecaos.platform.integration.api.provider.ProviderCapabilityCatalog;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;

/** ADR 0026 declaration backed by the delivery adapters wired in this build. */
@Component
public class DeliveryProviderCapabilityCatalog implements ProviderCapabilityCatalog {

    private final Map<String, DeliveryPartner> partners;

    public DeliveryProviderCapabilityCatalog(List<DeliveryPartner> partners) {
        this.partners = partners.stream().collect(Collectors.toUnmodifiableMap(
                DeliveryPartner::providerType, partner -> partner));
    }

    @Override
    public ProviderCategory category() {
        return ProviderCategory.DELIVERY;
    }

    @Override
    public Optional<Declaration> declarationFor(String providerType) {
        return Optional.ofNullable(partners.get(providerType)).map(partner -> new Declaration(
                partner.capabilities().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()),
                "delivery/%s/v1".formatted(partner.providerType())));
    }
}
