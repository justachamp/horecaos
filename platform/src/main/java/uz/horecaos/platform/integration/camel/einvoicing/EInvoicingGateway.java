package uz.horecaos.platform.integration.camel.einvoicing;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;

/**
 * Turns one e-invoicing call into a credential and a request on the wire (ADR 0007,
 * ADR 0026, ADR 0028, ADR 0096).
 *
 * <p>The same three jobs the marketplace and delivery gateways do, with one difference that
 * is the point of ADR 0096: the account is HorecaOS's own, a platform installation, so there
 * is no tenant to resolve a binding for. What closes the request-forgery path instead is the
 * environment: the call names an approved {@code integration.provider_environments} row, and
 * its host must be one that row's egress allowlist names. A call to any other host is refused
 * before the secret is even resolved.
 *
 * <p>Every refusal below happens before anything is sent, and is reported as an outcome whose
 * code says so, so an adapter concludes "the operator holds nothing" rather than "unknown".
 */
@Service
public class EInvoicingGateway {

    private static final Logger log = LoggerFactory.getLogger(EInvoicingGateway.class);

    /** Long enough for an operator's gateway; short enough that a stuck one cannot hold a worker. */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "localhost", "[::1]", "::1");

    /** Refusals that provably happened before a byte was sent. */
    public static final Set<String> NOTHING_WAS_SENT = Set.of(
            "EGRESS_NOT_APPROVED",
            "SECRET_REFERENCE_INVALID",
            "SECRET_NOT_FOUND",
            "SECRET_UNAVAILABLE",
            "REQUEST_UNBUILDABLE",
            "METHOD_UNSUPPORTED");

    private final EgressAllowlist egress;
    private final SecretResolver secrets;
    private final ProviderHttpClient http;

    public EInvoicingGateway(EgressAllowlist egress, SecretResolver secrets, ProviderHttpClient http) {
        this.egress = egress;
        this.secrets = secrets;
        this.http = http;
    }

    public ProviderOutcome invoke(EInvoicingApiCall call) {
        if (!hostIsApproved(call)) {
            return ProviderOutcome.rejected(
                    "EGRESS_NOT_APPROVED", "That host is not approved for environment " + call.environmentCode());
        }

        SecretReference reference;
        try {
            reference = SecretReference.parse(call.secretReference());
        } catch (RuntimeException malformed) {
            return ProviderOutcome.rejected("SECRET_REFERENCE_INVALID", "The account's secret reference is malformed");
        }

        SecretValue credential;
        try {
            // Not disposed: the resolver caches and shares the instance.
            credential = secrets.resolve(reference);
        } catch (SecretResolver.SecretNotFoundException missing) {
            return ProviderOutcome.rejected(
                    "SECRET_NOT_FOUND", "No credential is stored behind the account's reference");
        } catch (RuntimeException unavailable) {
            // The class name only: whatever the secrets manager said may name a path.
            return ProviderOutcome.retryable(
                    "SECRET_UNAVAILABLE", unavailable.getClass().getSimpleName(), null);
        }

        ProviderOutcome outcome = send(call, credential);
        if (isAuthenticationFailure(outcome)) {
            // One read past the cache, as ADR 0028 prescribes: a rotated credential and a
            // revoked one arrive as the same 401, and only a fresh read separates them. A 401
            // is the operator refusing before it acts, so repeating the call is safe.
            log.warn(
                    "E-invoicing operator {} rejected the cached credential for installation {}; refreshing once",
                    call.providerType(),
                    call.installationId());
            try {
                outcome = send(call, secrets.resolveFresh(reference));
            } catch (RuntimeException unavailable) {
                return ProviderOutcome.retryable(
                        "SECRET_UNAVAILABLE", unavailable.getClass().getSimpleName(), null);
            }
        }
        return outcome;
    }

    private ProviderOutcome send(EInvoicingApiCall call, SecretValue credential) {
        EInvoicingApiCall.Request request;
        try {
            request = call.request().apply(credential.reveal());
        } catch (RuntimeException failure) {
            // Nothing has been sent. The message is deliberately dropped: the function that
            // threw was holding the credential when it did.
            return ProviderOutcome.rejected(
                    "REQUEST_UNBUILDABLE", failure.getClass().getSimpleName());
        }

        ProviderCall providerCall = new ProviderCall(
                call.baseUrl(),
                credential.reveal(),
                call.correlationId(),
                call.timeout() == null ? DEFAULT_TIMEOUT : call.timeout());

        Map<String, String> headers = request.headers();
        return switch (call.method()) {
            case "GET" ->
                http.getAccepting(providerCall, call.path(), headers, call.acceptedStatuses(), EInvoicingGateway::body);
            case "POST" ->
                request.form() != null
                        ? http.postForm(providerCall, call.path(), headers, request.form(), EInvoicingGateway::body)
                        : http.post(providerCall, call.path(), headers, request.json(), EInvoicingGateway::body);
            default -> ProviderOutcome.rejected("METHOD_UNSUPPORTED", call.method());
        };
    }

    private boolean hostIsApproved(EInvoicingApiCall call) {
        Optional<Set<String>> approved = egress.hostsOf(call.environmentCode());
        if (approved.isEmpty()) {
            return false;
        }
        try {
            URI uri = URI.create(call.baseUrl());
            String host = uri.getHost();
            if (host == null || !approved.get().contains(host.toLowerCase(Locale.ROOT))) {
                return false;
            }
            // TLS always, but for a loopback host, which only a test's fake operator is ever
            // approved as: an environment row is platform-owned, and none names one.
            return "https".equals(uri.getScheme()) || LOOPBACK.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /** The operator's body, passed through unread. Interpreting it is the adapter's. */
    private static ProviderOutcome body(Map<String, Object> parsed) {
        return ProviderOutcome.success(parsed, null);
    }

    private static boolean isAuthenticationFailure(ProviderOutcome outcome) {
        return outcome.status() == ProviderOutcome.Status.REJECTED
                && "PROVIDER_AUTHENTICATION".equals(outcome.errorCode());
    }
}
