package uz.horecaos.platform.integration.camel.cardbilling;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One call from a card provider's adapter to the provider's API, on behalf of HorecaOS's own
 * merchant account (ADR 0007, ADR 0095).
 *
 * <p>Shaped like the e-invoicing call and for the same reason: the account is a <em>platform</em>
 * installation with no tenant, so the call names the installation and the approved environment
 * its host must belong to ({@code integration.provider_environments.egress_allowlist}), and
 * carries the ADR 0028 reference the gateway resolves. The adapter chooses the base URL among
 * the hosts that environment approves and the gateway refuses any other.
 *
 * @param operation        a short stable label for metrics, from a small closed set; it becomes
 *                         a metric tag
 * @param path             path, already encoded by the adapter
 * @param request          the headers and body, computed from the resolved credential when the
 *                         provider authenticates in them; invoked once per attempt, and it must
 *                         not log, store or return what it is given
 * @param acceptedStatuses non-2xx statuses to be read as answers rather than faults, delivered
 *                         under {@code ProviderHttpClient.STATUS_KEY}
 */
public record CardBillingApiCall(
        UUID installationId,
        String providerType,
        String environmentCode,
        String operation,
        String method,
        String baseUrl,
        String path,
        String secretReference,
        Function<String, Request> request,
        Set<Integer> acceptedStatuses,
        @Nullable String correlationId,
        @Nullable Duration timeout) {

    public CardBillingApiCall {
        Objects.requireNonNull(installationId, "An installation id is required");
        Objects.requireNonNull(providerType, "A provider type is required");
        Objects.requireNonNull(environmentCode, "An environment code is required");
        Objects.requireNonNull(operation, "An operation label is required");
        Objects.requireNonNull(method, "An HTTP method is required");
        Objects.requireNonNull(baseUrl, "A base URL is required");
        Objects.requireNonNull(path, "A path is required");
        Objects.requireNonNull(secretReference, "A secret reference is required");
        Objects.requireNonNull(request, "A request function is required");
        acceptedStatuses = Set.copyOf(acceptedStatuses);
    }

    /**
     * What goes on the wire: the headers and at most a JSON body. {@code toString} says neither,
     * because the headers carry a signature of the merchant's secret.
     */
    public record Request(
            Map<String, String> headers, @Nullable Map<String, Object> json) {

        public Request {
            headers = Map.copyOf(headers);
        }

        public static Request of(Map<String, String> headers) {
            return new Request(headers, null);
        }

        public static Request json(Map<String, String> headers, Map<String, Object> body) {
            return new Request(headers, body);
        }

        @Override
        public String toString() {
            return "Request[headers=" + headers.keySet() + "]";
        }
    }

    /** Deliberately omits the request function and the path: this reaches log lines. */
    @Override
    public String toString() {
        return "CardBillingApiCall[" + providerType + " " + operation + " " + method + "]";
    }
}
