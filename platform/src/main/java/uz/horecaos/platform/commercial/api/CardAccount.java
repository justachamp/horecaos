package uz.horecaos.platform.commercial.api;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What an adapter is handed besides the call itself: which of HorecaOS's own
 * merchant accounts it is acting for (ADR 0095, ADR 0026).
 *
 * <p>A secret <em>reference</em>, never a value (ADR 0028): the adapter resolves
 * it at call time, so rotating the credential never changes this record. {@code
 * toString} says which installation and nothing else.
 *
 * <p>{@code baseUrl} is the base URL of the approved {@code integration.provider_environments} row
 * {@code environmentCode} names, never anything typed (ADR 0026).
 *
 * <p>{@code externalAccountReference} is the provider's own public identifier for the
 * merchant account (for Click, its {@code service_id}): non-sensitive by ADR 0026's
 * definition and the one value a browser is told so it can tokenise a card.
 */
public record CardAccount(
        UUID installationId,
        String providerType,
        @Nullable String environmentCode,
        @Nullable String baseUrl,
        @Nullable String secretReference,
        @Nullable String externalAccountReference,
        Map<String, Object> configuration) {

    public CardAccount {
        configuration = Map.copyOf(configuration);
        if (baseUrl != null && baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    /** An account with no endpoint and no external account reference: the in-process test double has neither. */
    public CardAccount(
            UUID installationId,
            String providerType,
            @Nullable String environmentCode,
            @Nullable String secretReference,
            Map<String, Object> configuration) {
        this(installationId, providerType, environmentCode, null, secretReference, null, configuration);
    }

    @Override
    public String toString() {
        return "CardAccount[installation=%s type=%s]".formatted(installationId, providerType);
    }
}
