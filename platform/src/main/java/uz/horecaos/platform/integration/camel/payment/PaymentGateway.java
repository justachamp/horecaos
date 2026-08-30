package uz.horecaos.platform.integration.camel.payment;

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
import uz.horecaos.platform.integration.api.payment.MerchantApiCall;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup.InstallationSnapshot;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.migration.api.ExternalEffect;
import uz.horecaos.platform.migration.api.ImportSuppression;

/**
 * Turns an ADR 0026 installation into a base URL and a live credential, and puts
 * one payment call on the wire (ADR 0007, ADR 0028).
 *
 * <p>The same three jobs {@code DeliveryGateway} does, with one rule added that
 * only payments needs.
 *
 * <p><strong>A lost response on a mutating call is uncertain, whatever the
 * transport says.</strong> The shared classifier calls a 5xx retryable, because
 * every courier partner documents a 5xx as a failed request and treating it
 * otherwise would send every blip to a human. No payment provider documents that.
 * Click's own notes say a 500 or 502 on {@code card_token/payment},
 * {@code invoice/create} or {@code payment/reversal} may well have moved money,
 * and Click's merchant API carries no idempotency key on any call — so here a
 * retryable classification on a mutating call is upgraded to
 * {@link ProviderOutcome.Status#UNCERTAIN} rather than handed back as a licence to
 * send the charge again.
 */
@Service
public class PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(PaymentGateway.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private final ProviderInstallationLookup installations;
    private final SecretResolver secrets;
    private final ProviderHttpClient http;

    public PaymentGateway(ProviderInstallationLookup installations, SecretResolver secrets, ProviderHttpClient http) {
        this.installations = installations;
        this.secrets = secrets;
        this.http = http;
    }

    public ProviderOutcome invoke(MerchantApiCall call) {
        // ADR 0024, and the last line of defence for the money path. The
        // suppression that should have fired is in PaymentIntentService and
        // PaymentAttemptService; reaching an acquirer during an import means both
        // were bypassed, and a normalised rejection here would hide that.
        ImportSuppression.refuse(
                ExternalEffect.PAYMENT_COLLECTION,
                "%s on payment installation %s".formatted(call.operation(), call.installationId()));

        Optional<InstallationSnapshot> snapshot = installations.installation(call.tenantId(), call.installationId());
        if (snapshot.isEmpty()) {
            return ProviderOutcome.rejected(
                    "INSTALLATION_MISSING", "Installation " + call.installationId() + " is not available");
        }
        InstallationSnapshot installation = snapshot.get();
        if (!"ACTIVE".equals(installation.status())) {
            // A suspended installation is a deliberate stop, often mid-rotation.
            // Calling anyway earns a 401 and hides the reason.
            return ProviderOutcome.rejected(
                    "INSTALLATION_INACTIVE", "Installation " + call.installationId() + " is " + installation.status());
        }

        SecretReference reference = SecretReference.parse(installation.secretReference());
        // Not disposed: the resolver caches and shares the instance, and clearing
        // it here would blank the credential for every other caller.
        SecretValue credential = secrets.resolve(reference);
        ProviderOutcome outcome = send(call, installation, credential);

        if (isAuthenticationFailure(outcome)) {
            // One read past the cache, exactly as ADR 0028 prescribes: a key
            // rotated after we cached it looks identical to a revoked one, and
            // only a fresh read tells them apart. Once, never in a loop.
            log.warn(
                    "Provider {} rejected the cached credential for installation {}; refreshing once",
                    call.providerType(),
                    call.installationId());
            outcome = send(call, installation, secrets.resolveFresh(reference));
        }

        return classifyForPayments(call, outcome);
    }

    private ProviderOutcome send(MerchantApiCall call, InstallationSnapshot installation, SecretValue credential) {
        Map<String, String> headers;
        try {
            headers = call.authorization().apply(credential.reveal());
        } catch (RuntimeException failure) {
            // Nothing has been sent, so this cannot be uncertain. It is a
            // programming error in the adapter's signature construction, and
            // saying so beats a timeout six weeks later.
            return ProviderOutcome.rejected(
                    "AUTHORIZATION_UNBUILDABLE", failure.getClass().getSimpleName());
        }

        ProviderCall providerCall = new ProviderCall(
                installation.baseUrl(),
                credential.reveal(),
                call.correlationId(),
                call.timeout() == null ? DEFAULT_TIMEOUT : call.timeout());

        return switch (call.method()) {
            case "GET" -> http.get(providerCall, call.path(), headers, PaymentGateway::body);
            case "POST" -> http.post(providerCall, call.path(), headers, call.body(), PaymentGateway::body);
            case "PUT" -> http.put(providerCall, call.path(), headers, call.body(), PaymentGateway::body);
            case "DELETE" -> http.delete(providerCall, call.path(), headers, PaymentGateway::body);
            default -> ProviderOutcome.rejected("METHOD_UNSUPPORTED", call.method());
        };
    }

    /**
     * The payments-specific correction to the shared transport classification.
     *
     * <p>Reads keep the shared answer, because repeating a status query cannot
     * charge anyone. Mutating calls do not: on a provider with no idempotency key,
     * "safe to retry" is a claim nobody has made.
     */
    private static ProviderOutcome classifyForPayments(MerchantApiCall call, ProviderOutcome outcome) {
        if (!call.mutating() || outcome.status() != ProviderOutcome.Status.RETRYABLE) {
            return outcome;
        }
        // CIRCUIT_OPEN is the one retryable that stays retryable on a mutating
        // call: the breaker refused before anything left this process, so the
        // provider provably did not act and there is nothing to reconcile.
        if ("CIRCUIT_OPEN".equals(outcome.errorCode())) {
            return outcome;
        }
        return ProviderOutcome.uncertain(
                outcome.errorCode(),
                "A mutating payment call failed after the request was built; "
                        + "resolve by querying rather than by sending it again");
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
