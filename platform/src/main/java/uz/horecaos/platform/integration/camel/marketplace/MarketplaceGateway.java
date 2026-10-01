package uz.horecaos.platform.integration.camel.marketplace;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceApiCall;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup.InstallationSnapshot;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;

/**
 * Turns an ADR 0026 installation into a base URL and a live credential, and puts one
 * marketplace availability call on the wire (ADR 0007, ADR 0028, ADR 0141).
 *
 * <p>The same three jobs {@code PosGateway} and {@code DeliveryGateway} do, with no
 * correction to the shared classification: an availability push sets one item to one value,
 * so a lost response really is safe to send again, and the reconciler's own rule (an
 * unknown outcome nulls what it believes and the next tick sends the current value) is what
 * makes a resend correct rather than merely permitted.
 *
 * <p>Every refusal below — the installation is missing, it is not {@code ACTIVE}, the
 * adapter cannot build its authorization — is an outcome that provably wrote nothing to the
 * partner, so the conclusion drawn from it is {@code NOT_APPLIED}. A suspended installation is
 * a deliberate stop, and calling anyway would be worse than useless.
 */
@Service
public class MarketplaceGateway {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceGateway.class);

    /** Long enough for an aggregator's gateway; short enough that a stuck partner cannot hold a worker. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private final ProviderInstallationLookup installations;
    private final SecretResolver secrets;
    private final ProviderHttpClient http;

    public MarketplaceGateway(
            ProviderInstallationLookup installations, SecretResolver secrets, ProviderHttpClient http) {
        this.installations = installations;
        this.secrets = secrets;
        this.http = http;
    }

    public ProviderOutcome invoke(MarketplaceApiCall call) {
        // No ADR 0024 import guard here, unlike PosGateway: a POS call prints a ticket in a
        // working kitchen, while this one reports the platform's CURRENT truth about a dish.
        // A stock baseline import that sets availability is the platform's truth changing, and
        // the partner being told so within one resync interval is the point of the
        // reconciler, not a side effect to suppress. Nothing an import does can make this
        // call say anything the resolver would not say to the storefront at the same instant.

        Optional<InstallationSnapshot> snapshot = installations.installation(call.tenantId(), call.installationId());
        if (snapshot.isEmpty()) {
            return ProviderOutcome.rejected(
                    "INSTALLATION_MISSING", "Installation " + call.installationId() + " is not available");
        }
        InstallationSnapshot installation = snapshot.get();
        if (!"ACTIVE".equals(installation.status())) {
            return ProviderOutcome.rejected(
                    "INSTALLATION_INACTIVE", "Installation " + call.installationId() + " is " + installation.status());
        }

        SecretReference reference = SecretReference.parse(installation.secretReference());
        // Not disposed: the resolver caches and shares the instance.
        SecretValue credential = secrets.resolve(reference);
        ProviderOutcome outcome = send(call, installation, credential);

        if (isAuthenticationFailure(outcome)) {
            // One read past the cache, as ADR 0028 prescribes: an expired cached token and a
            // revoked secret arrive as the same 401, and only a fresh read separates them.
            log.warn(
                    "Marketplace {} rejected the cached credential for installation {}; refreshing once",
                    call.providerType(),
                    call.installationId());
            outcome = send(call, installation, secrets.resolveFresh(reference));
        }
        return outcome;
    }

    private ProviderOutcome send(MarketplaceApiCall call, InstallationSnapshot installation, SecretValue credential) {
        Map<String, String> headers;
        Map<String, Object> payload;
        try {
            headers = call.authorization().apply(credential.reveal());
            payload = call.body() == null ? null : call.body().apply(credential.reveal());
        } catch (RuntimeException failure) {
            // Nothing has been sent. The exception's message is deliberately dropped: the
            // function that threw was holding the credential when it did.
            return ProviderOutcome.rejected(
                    "AUTHORIZATION_UNBUILDABLE", failure.getClass().getSimpleName());
        }

        ProviderCall providerCall = new ProviderCall(
                installation.baseUrl(),
                credential.reveal(),
                call.correlationId(),
                call.timeout() == null ? DEFAULT_TIMEOUT : call.timeout());

        return switch (call.method()) {
            case "PUT" -> http.put(providerCall, call.path(), headers, payload, MarketplaceGateway::body);
            case "POST" -> http.post(providerCall, call.path(), headers, payload, MarketplaceGateway::body);
            case "PATCH" -> http.patch(providerCall, call.path(), headers, payload, MarketplaceGateway::body);
            default -> ProviderOutcome.rejected("METHOD_UNSUPPORTED", call.method());
        };
    }

    /** The provider's body, passed through unread. Interpreting it is the adapter's. */
    private static ProviderOutcome body(Map<String, Object> parsed) {
        return ProviderOutcome.success(parsed, null);
    }

    private static boolean isAuthenticationFailure(ProviderOutcome outcome) {
        return outcome.status() == ProviderOutcome.Status.REJECTED
                && "PROVIDER_AUTHENTICATION".equals(outcome.errorCode());
    }
}
