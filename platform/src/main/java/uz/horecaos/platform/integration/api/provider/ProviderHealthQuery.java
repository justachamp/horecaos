package uz.horecaos.platform.integration.api.provider;

/** Platform-wide provider installation health, exposed to another module (ADR 0026, ADR 0058). */
public interface ProviderHealthQuery {

    ProviderHealth providerHealth();
}
