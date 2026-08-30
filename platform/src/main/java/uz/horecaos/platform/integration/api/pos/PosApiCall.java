package uz.horecaos.platform.integration.api.pos;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * One call from a POS adapter to a point-of-sale API (ADR 0007).
 *
 * <p>Shaped like {@code MerchantApiCall} rather than like {@code
 * DeliveryOperation}, and for the same reason: a courier partner has a small
 * closed set of operations a route can name, while POS vendors differ so
 * completely that naming their endpoints centrally would put one vendor's URL
 * scheme in shared code. The adapter names the endpoint; the route decides retry,
 * circuit, and reconciliation policy.
 *
 * <p>The field that matters is {@link #effect()}. Everything the route does with
 * a failure follows from it.
 *
 * @param path          path and query string. Already encoded by the adapter,
 *                      because query encoding is a vendor concern: at least one
 *                      POS binds parameters through PHP bracket notation, which
 *                      {@code java.net.URI} refuses to accept unencoded
 * @param authorization headers computed from the resolved credential. Invoked
 *                      once per attempt inside the gateway, so the credential
 *                      exists only for the duration of a call and never on this
 *                      record. It must not log, store, or return what it is given
 * @param body          the request body, also computed from the credential, or
 *                      null for a call that carries none. A function rather than
 *                      a map because not every provider authenticates in a
 *                      header: the implemented POS mints its session token from a
 *                      body containing the client secret, and a fixed map here
 *                      would have forced the adapter to hold that secret itself —
 *                      exactly what routing every call through the gateway
 *                      exists to prevent. Use {@link #fixedBody(Map)} for the
 *                      ordinary case, which ignores the credential
 * @param operation     a short stable label for metrics and route logs, from a
 *                      small closed set — it becomes a metric tag, and an
 *                      unbounded tag eventually takes the registry down
 */
public record PosApiCall(
        UUID tenantId,
        UUID installationId,
        String providerType,
        String operation,
        String method,
        String path,
        Function<String, Map<String, Object>> body,
        Effect effect,
        Function<String, Map<String, String>> authorization,
        String correlationId,
        Duration timeout) {

    public PosApiCall {
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(installationId, "An installation id is required");
        Objects.requireNonNull(providerType, "A provider type is required");
        Objects.requireNonNull(operation, "An operation label is required");
        Objects.requireNonNull(method, "An HTTP method is required");
        Objects.requireNonNull(path, "A path is required");
        Objects.requireNonNull(effect, "An effect classification is required");
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

    /**
     * What repeating this call would do at the provider.
     *
     * <p>Three values rather than a mutating flag. The middle one is why: a call
     * that sets a named field to a named value converges however many times it
     * runs, so treating it as unsafe would send a fiscal identifier write-back or
     * a status update to manual reconciliation for no reason at all. The third is
     * the dangerous one, and it exists as its own name so that nobody has to
     * remember which endpoints it applies to.
     */
    public enum Effect {

        /** Reads. Always safe to repeat; this is the reconciliation path. */
        READ,

        /**
         * Sets a specific value, or moves to a named terminal state. Idempotent
         * by construction whatever the provider guarantees, so a lost response
         * may simply be sent again.
         */
        IDEMPOTENT_WRITE,

        /**
         * Creates something, with no key to deduplicate on.
         *
         * <p>A lost response here is not a failure to retry. It is a request whose
         * outcome must be discovered before anything else happens, because
         * repeating it is a second order at a kitchen.
         */
        UNKEYED_CREATE
    }

    /**
     * Deliberately omits the body and the authorization function.
     *
     * <p>This record reaches exception messages and route log lines, and an order
     * export body carries the customer's name, telephone number and address —
     * which ADR 0029 keeps out of every one of them, and the generated record
     * {@code toString} would put there.
     */
    @Override
    public String toString() {
        return "PosApiCall[" + providerType + " " + operation + " " + method + " " + effect + "]";
    }
}
