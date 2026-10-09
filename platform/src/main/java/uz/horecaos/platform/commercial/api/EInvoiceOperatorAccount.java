package uz.horecaos.platform.commercial.api;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * HorecaOS's own account with one operator, as an adapter needs it (ADR 0096, ADR 0026).
 *
 * <p>The base URL comes from the approved provider environment the platform
 * installation names, never from anything typed; the secret reference is an ADR 0028
 * reference the adapter's gateway resolves at call time. There is no credential here
 * and nothing that could become one.
 *
 * @param environmentCode the approved {@code integration.provider_environments} row
 * @param baseUrl         that environment's base URL, no trailing slash
 * @param secretReference an ADR 0028 reference to the operator login, resolved at call time
 * @param config          the installation's public settings: the seller's taxpayer number
 *                        (the login the operator knows HorecaOS by) and the login language
 */
public record EInvoiceOperatorAccount(
        UUID installationId,
        String providerType,
        String environmentCode,
        String baseUrl,
        String secretReference,
        Map<String, String> config) {

    public EInvoiceOperatorAccount {
        Objects.requireNonNull(installationId, "An installation id is required");
        Objects.requireNonNull(providerType, "A provider type is required");
        Objects.requireNonNull(environmentCode, "An environment code is required");
        Objects.requireNonNull(baseUrl, "A base URL is required");
        Objects.requireNonNull(secretReference, "A secret reference is required");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        config = Map.copyOf(config);
    }

    /** Names no reference text: a reference is not a secret, but a log line that prints one invites printing the next. */
    @Override
    public String toString() {
        return "EInvoiceOperatorAccount[installation=%s, provider=%s, environment=%s]"
                .formatted(installationId, providerType, environmentCode);
    }
}
