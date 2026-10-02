package uz.horecaos.platform.integration.api.marketplace;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One call from a marketplace adapter to an aggregator's API (ADR 0007), shaped like {@code
 * PosApiCall}: the adapter names the endpoint, the route decides circuit policy, and the
 * credential exists only for the duration of one attempt inside the gateway.
 *
 * <p>An availability push is a value-setting write, safe to repeat — that is the whole reason
 * the reconciler may resend, and why this record has no effect classification to choose from.
 *
 * @param path          path and query string, already encoded by the adapter
 * @param authorization headers computed from the resolved credential, invoked once per attempt;
 *                      it must not log, store or return what it is given
 * @param body          the request body, computed from the credential when the provider
 *                      authenticates in the body, or null for a call that carries none
 * @param operation     a short stable label for metrics, from a small closed set — it becomes a
 *                      metric tag
 */
public record MarketplaceApiCall(
        UUID tenantId,
        UUID bindingId,
        UUID installationId,
        String providerType,
        String operation,
        String method,
        String path,
        @Nullable Function<String, Map<String, Object>> body,
        Function<String, Map<String, String>> authorization,
        String correlationId,
        @Nullable Duration timeout) {

    public MarketplaceApiCall {
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(bindingId, "A binding id is required");
        Objects.requireNonNull(installationId, "An installation id is required");
        Objects.requireNonNull(providerType, "A provider type is required");
        Objects.requireNonNull(operation, "An operation label is required");
        Objects.requireNonNull(method, "An HTTP method is required");
        Objects.requireNonNull(path, "A path is required");
        Objects.requireNonNull(authorization, "An authorization function is required");
    }

    /** A body that does not depend on the credential, which is nearly all of them. */
    public static Function<String, Map<String, Object>> fixedBody(Map<String, Object> body) {
        Map<String, Object> copy = Map.copyOf(body);
        return credential -> copy;
    }

    /** Headers that do not depend on the resolved credential. */
    public static Function<String, Map<String, String>> fixedHeaders(Map<String, String> headers) {
        Map<String, String> copy = Map.copyOf(headers);
        return credential -> copy;
    }

    /** Deliberately omits the body and the authorization function: this reaches log lines. */
    @Override
    public String toString() {
        return "MarketplaceApiCall[" + providerType + " " + operation + " " + method + "]";
    }
}
