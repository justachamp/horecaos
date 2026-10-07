package uz.horecaos.platform.integration.camel.einvoicing;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One call from an e-invoicing adapter to an operator's API (ADR 0007, ADR 0096),
 * shaped like the marketplace and payment calls: the adapter names the endpoint, the
 * route decides circuit policy, and the credential exists only for the duration of one
 * attempt inside the gateway.
 *
 * <p>Unlike those calls this one belongs to a <em>platform</em> installation -- HorecaOS's
 * own account, with no tenant -- so it names the installation and the approved environment
 * its host must belong to ({@code integration.provider_environments.egress_allowlist}), and
 * carries the ADR 0028 reference the gateway resolves. The base URL is the adapter's choice
 * among the hosts that environment approves (an operator's API host and its separate login
 * host), and the gateway refuses any other.
 *
 * @param operation       a short stable label for metrics, from a small closed set -- it
 *                        becomes a metric tag
 * @param path            path and query string, already encoded by the adapter
 * @param request         the headers and body, computed from the resolved credential when the
 *                        operator authenticates in them; invoked once per attempt, and it must
 *                        not log, store or return what it is given
 * @param acceptedStatuses non-2xx statuses to be read as answers rather than faults, delivered
 *                        under {@code ProviderHttpClient.STATUS_KEY}: a lookup's {@code 404}
 */
public record EInvoicingApiCall(
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

    public EInvoicingApiCall {
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
     * What goes on the wire: the headers, and at most one of a JSON body or a form body.
     * {@code toString} is deliberately empty of both -- a request carries the account's password.
     */
    public record Request(
            Map<String, String> headers,
            @Nullable Map<String, Object> json,
            @Nullable Map<String, String> form) {

        public Request {
            headers = Map.copyOf(headers);
            if (json != null && form != null) {
                throw new IllegalArgumentException("A request has a JSON body or a form body, not both");
            }
        }

        public static Request of(Map<String, String> headers) {
            return new Request(headers, null, null);
        }

        public static Request json(Map<String, String> headers, Map<String, Object> body) {
            return new Request(headers, body, null);
        }

        public static Request form(Map<String, String> headers, Map<String, String> body) {
            return new Request(headers, null, body);
        }

        @Override
        public String toString() {
            return "Request[headers=" + headers.keySet() + "]";
        }
    }

    /** Deliberately omits the request function: this reaches log lines. */
    @Override
    public String toString() {
        return "EInvoicingApiCall[" + providerType + " " + operation + " " + method + "]";
    }
}
