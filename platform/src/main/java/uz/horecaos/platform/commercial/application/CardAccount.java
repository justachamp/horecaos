package uz.horecaos.platform.commercial.application;

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
 */
public record CardAccount(
        UUID installationId,
        String providerType,
        @Nullable String environmentCode,
        @Nullable String secretReference,
        Map<String, Object> configuration) {

    public CardAccount {
        configuration = Map.copyOf(configuration);
    }

    @Override
    public String toString() {
        return "CardAccount[installation=%s type=%s]".formatted(installationId, providerType);
    }
}
