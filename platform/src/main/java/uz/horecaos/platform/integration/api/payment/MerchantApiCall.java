package uz.horecaos.platform.integration.api.payment;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One call from a payment adapter to a provider's merchant API (ADR 0007).
 *
 * <p>Provider-neutral on purpose, and thinner than {@code DeliveryOperation} for a
 * reason: a courier partner has a small closed set of operations that the route
 * can name, while a payment provider's operation set differs so completely
 * between providers that naming them centrally would mean the route holding an
 * enumeration of Click's endpoints. The adapter names the endpoint; the route
 * decides retry, circuit, and reconciliation policy.
 *
 * @param operation     a short stable label for metrics and route logs. Must come
 *                      from a small closed set — it becomes a metric tag, and an
 *                      unbounded tag eventually takes the registry down
 * @param body          the JSON request body, or null for a call that carries none.
 *                      Never logged: a payment body is personal data under ADR 0029
 * @param mutating      whether the provider may have changed something by the time
 *                      the response was lost. This is the single most consequential
 *                      field here. Neither Click's nor Payme's merchant API offers
 *                      an idempotency key on a call that moves money, so a mutating
 *                      call whose response never arrived is
 *                      {@code UNCERTAIN} and must be resolved by a query — never by
 *                      sending it again, which is a second charge on a card
 * @param authorization the headers that authenticate this call, computed from the
 *                      resolved credential. Invoked once per attempt, inside the
 *                      gateway, so the credential exists only for the duration of
 *                      the call and never on this record. It must not log, store,
 *                      or return the value it is given
 */
public record MerchantApiCall(
        UUID tenantId,
        UUID installationId,
        String providerType,
        String operation,
        String method,
        String path,
        @Nullable Map<String, Object> body,
        boolean mutating,
        Function<String, Map<String, String>> authorization,
        @Nullable String correlationId,
        Duration timeout) {

    public MerchantApiCall {
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(installationId, "An installation id is required");
        Objects.requireNonNull(providerType, "A provider type is required");
        Objects.requireNonNull(operation, "An operation label is required");
        Objects.requireNonNull(method, "An HTTP method is required");
        Objects.requireNonNull(path, "A path is required");
        Objects.requireNonNull(authorization, "An authorization function is required");
        body = body == null ? null : Map.copyOf(body);
    }

    /**
     * Deliberately omits the body and the authorization function.
     *
     * <p>A call record reaches exception messages and route log lines, and its body
     * carries the amount, the merchant transaction id, and on some providers a
     * phone number. ADR 0029 keeps all of that out of a log, and the generated
     * record {@code toString} would put it there.
     */
    @Override
    public String toString() {
        return "MerchantApiCall[" + providerType + " " + operation + " " + method + " " + path + "]";
    }
}
