package uz.horecaos.platform.integration.camel.pos;

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
import uz.horecaos.platform.integration.api.pos.PosApiCall;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup.InstallationSnapshot;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.migration.api.ExternalEffect;
import uz.horecaos.platform.migration.api.ImportSuppression;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;

/**
 * Turns an ADR 0026 installation into a base URL and a live credential, and puts
 * one POS call on the wire (ADR 0007, ADR 0028).
 *
 * <p>The same three jobs {@code DeliveryGateway} and {@code PaymentGateway} do,
 * with one correction that only a POS needs.
 *
 * <p><strong>A lost response on an unkeyed create is uncertain, whatever the
 * transport says.</strong> The shared classifier calls a 5xx retryable, because
 * every courier partner documents a 5xx as a failed request. No POS in this build
 * documents that, and the one that is implemented documents the opposite: its own
 * error guidance says that for non-idempotent requests an integrator must "check
 * the server state first to avoid duplicates". Its upstream gateway gives up
 * after eight seconds, which is short for an order export, so this is a weekly
 * event rather than an exotic one. Handing that back as retryable would be a
 * licence to print a second kitchen ticket.
 *
 * <p>Reads and value-setting writes keep the shared classification. Repeating a
 * status query cannot cook anything, and re-sending a fiscal identifier
 * write-back sets the same field to the same value.
 */
@Service
public class PosGateway {

    private static final Logger log = LoggerFactory.getLogger(PosGateway.class);

    /**
     * Comfortably above the eight seconds the implemented POS's gateway allows
     * its own upstream.
     *
     * <p>The margin is the point. If we time out first we learn nothing at all;
     * if the provider times out first we at least get its {@code 504}, which
     * tells us its gateway gave up on its upstream — still uncertain, but
     * uncertain with a shape, and distinguishable from a network that never
     * carried the request.
     */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(25);

    private final ProviderInstallationLookup installations;
    private final SecretResolver secrets;
    private final ProviderHttpClient http;

    public PosGateway(ProviderInstallationLookup installations, SecretResolver secrets,
            ProviderHttpClient http) {
        this.installations = installations;
        this.secrets = secrets;
        this.http = http;
    }

    public ProviderOutcome invoke(PosApiCall call) {
        // ADR 0024. Every rejection below is an outcome the export state machine
        // understands and retries or escalates; an import must get none of them,
        // because "the installation is missing" and "we deliberately did not send
        // a 2021 order to a live till" are different facts and only one of them is
        // true. A POS create is also the effect with the loudest physical
        // consequence in this list: it prints a ticket in a working kitchen.
        ImportSuppression.refuse(ExternalEffect.POS_PROVIDER_CALL,
                "%s on POS installation %s".formatted(call.operation(), call.installationId()));

        Optional<InstallationSnapshot> snapshot =
                installations.installation(call.tenantId(), call.installationId());
        if (snapshot.isEmpty()) {
            return ProviderOutcome.rejected("INSTALLATION_MISSING",
                    "Installation " + call.installationId() + " is not available");
        }
        InstallationSnapshot installation = snapshot.get();
        if (!"ACTIVE".equals(installation.status())) {
            // A suspended installation is a deliberate stop. On this provider it
            // is also the only containment control available, because the vendor
            // cannot rotate a client secret without a support ticket — so
            // "suspended" may well mean "we believe this credential is
            // compromised", and calling anyway would be worse than useless.
            return ProviderOutcome.rejected("INSTALLATION_INACTIVE",
                    "Installation " + call.installationId() + " is " + installation.status());
        }

        SecretReference reference = SecretReference.parse(installation.secretReference());
        // Not disposed: the resolver caches and shares the instance, and clearing
        // it here would blank the credential for every other caller.
        SecretValue credential = secrets.resolve(reference);
        ProviderOutcome outcome = send(call, installation, credential);

        if (isAuthenticationFailure(outcome)) {
            // One read past the cache, as ADR 0028 prescribes. On this provider
            // the distinction matters unusually much: an expired session token
            // and a revoked client secret arrive as the same 401, and only a
            // fresh read separates "our cached token aged out" from "the
            // restaurant switched the integration off".
            log.warn("POS {} rejected the cached credential for installation {}; refreshing once",
                    call.providerType(), call.installationId());
            outcome = send(call, installation, secrets.resolveFresh(reference));
        }

        return classifyForPos(call, outcome);
    }

    private ProviderOutcome send(PosApiCall call, InstallationSnapshot installation,
            SecretValue credential) {

        Map<String, String> headers;
        Map<String, Object> payload;
        try {
            headers = call.authorization().apply(credential.reveal());
            payload = call.body() == null ? null : call.body().apply(credential.reveal());
        } catch (RuntimeException failure) {
            // Nothing has been sent, so this cannot be uncertain. It is a
            // programming error in the adapter, and saying so beats a timeout.
            // The exception's message is deliberately dropped: the function that
            // threw was holding the credential when it did.
            return ProviderOutcome.rejected("AUTHORIZATION_UNBUILDABLE",
                    failure.getClass().getSimpleName());
        }

        ProviderCall providerCall = new ProviderCall(installation.baseUrl(), credential.reveal(),
                call.correlationId(), call.timeout() == null ? DEFAULT_TIMEOUT : call.timeout());

        return switch (call.method()) {
            case "GET" -> http.get(providerCall, call.path(), headers, PosGateway::body);
            case "POST" -> http.post(providerCall, call.path(), headers, payload, PosGateway::body);
            case "PUT" -> http.put(providerCall, call.path(), headers, payload, PosGateway::body);
            case "PATCH" -> http.patch(providerCall, call.path(), headers, payload, PosGateway::body);
            default -> ProviderOutcome.rejected("METHOD_UNSUPPORTED", call.method());
        };
    }

    /**
     * The POS-specific correction to the shared transport classification.
     *
     * <p>Only {@link PosApiCall.Effect#UNKEYED_CREATE} is corrected. Reads and
     * idempotent writes keep the shared answer, because repeating either of them
     * converges; an unkeyed create does not converge on anything.
     */
    static ProviderOutcome classifyForPos(PosApiCall call, ProviderOutcome outcome) {
        if (call.effect() != PosApiCall.Effect.UNKEYED_CREATE) {
            return outcome;
        }
        if (outcome.status() != ProviderOutcome.Status.RETRYABLE) {
            return outcome;
        }
        // CIRCUIT_OPEN stays retryable: the breaker refused before anything left
        // this process, so the provider provably did not act and there is nothing
        // to discover.
        if ("CIRCUIT_OPEN".equals(outcome.errorCode())) {
            return outcome;
        }
        return ProviderOutcome.uncertain(outcome.errorCode(),
                "A create with no idempotency key failed after the request was built. "
                        + "Resolve by reading the provider, never by sending it again");
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
