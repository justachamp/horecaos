package uz.horecaos.platform.integration.camel.delivery.yandex;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.delivery.DeliveryCapability;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;

/**
 * Yandex Delivery (Cargo B2B), integration API v2.
 *
 * <p>Verified against the published API on 2026-08-20. Two properties shape
 * everything here:
 *
 * <ul>
 *   <li><b>Create and accept are separate.</b> {@code /claims/create} produces a
 *       claim that is <em>not</em> a booking. Nothing is dispatched until
 *       {@code /claims/accept}. That is why this adapter declares
 *       {@link DeliveryCapability#RESERVE_SHIPMENT}: a hold can be taken while
 *       other partners are still being evaluated, and abandoned for free.</li>
 *   <li><b>Mutations carry a version.</b> Accept and cancel take the {@code version}
 *       last read. A stale version is rejected rather than applied, which is the
 *       protection against two operators acting on the same claim.</li>
 * </ul>
 *
 * <p>{@code request_id} is a documented idempotency key on create, so a create
 * timeout is retryable under the same key rather than uncertain — the opposite
 * of the Noor adapter, and the reason both cannot share one code path.
 */
@Component
public class YandexDeliveryAdapter implements DeliveryPartner {

    public static final String PROVIDER_TYPE = "yandex-delivery";

    private final ProviderHttpClient http;

    public YandexDeliveryAdapter(ProviderHttpClient http) {
        this.http = http;
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public Set<DeliveryCapability> capabilities() {
        // RESCHEDULE_SHIPMENT is absent: the API has no reschedule. Changing a
        // pickup time means cancelling and creating again, which may cost money,
        // so it is a decision for ordering to make explicitly rather than one to
        // hide behind a capability that pretends to be free.
        return Set.of(
                DeliveryCapability.QUOTE_DELIVERY,
                DeliveryCapability.RESERVE_SHIPMENT,
                DeliveryCapability.CONFIRM_SHIPMENT,
                DeliveryCapability.SCHEDULE_SHIPMENT,
                DeliveryCapability.QUERY_CANCELLATION_COST,
                DeliveryCapability.CANCEL_SHIPMENT,
                DeliveryCapability.QUERY_SHIPMENT,
                DeliveryCapability.TRACK_SHIPMENT);
    }

    @Override
    public ProviderOutcome quote(DeliveryRequest request, ProviderCall call) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "items",
                List.of(Map.of(
                        "quantity", 1, "size", Map.of("length", 0.3, "width", 0.3, "height", 0.3), "weight", 1.0)));
        body.put(
                "route_points",
                List.of(
                        Map.of(
                                "coordinates",
                                coordinates(
                                        request.pickup().longitude(),
                                        request.pickup().latitude())),
                        Map.of(
                                "coordinates",
                                coordinates(
                                        request.dropoff().longitude(),
                                        request.dropoff().latitude()))));
        body.put("requirements", Map.of("taxi_class", "express"));

        return http.post(call, "/check-price", headers(call), body, response -> {
            Map<String, Object> quote = new LinkedHashMap<>();
            quote.put("priceMinor", minorUnits(response.get("price")));
            quote.put("currency", request.currency());
            quote.put("etaMinutes", response.get("eta"));
            // No quote id and no TTL are returned, so this price is indicative
            // only. Callers must not treat it as redeemable at create time.
            quote.put("binding", false);
            return ProviderOutcome.success(quote, null);
        });
    }

    /**
     * Creates a claim. The result is a hold, not a booking — see the class note.
     * Callers that want a live courier must follow with
     * {@link #confirmShipment(String, ProviderCall)}.
     */
    @Override
    public ProviderOutcome createShipment(DeliveryRequest request, ProviderCall call) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", List.of(claimItem(request)));
        body.put("route_points", List.of(pickupPoint(request), dropoffPoint(request)));
        body.put("client_requirements", Map.of("taxi_class", "express"));
        body.put("optional_return", false);
        if (request.requestedPickupAt() != null) {
            // A scheduled pickup. Yandex reads this as "not before".
            body.put("due", request.requestedPickupAt().toString());
        }

        // request_id is Yandex's documented idempotency key, which is what makes
        // a repeat of this exact call safe rather than a second courier.
        String path = "/claims/create?request_id=" + call.idempotencyKey();

        return http.post(call, path, headers(call), body, response -> {
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("state", "RESERVED");
            normalized.put("providerStatus", response.get("status"));
            normalized.put("version", response.get("version"));
            normalized.put("live", false);
            return ProviderOutcome.success(normalized, String.valueOf(response.get("id")));
        });
    }

    @Override
    public ProviderOutcome confirmShipment(String externalReference, ProviderCall call) {
        // Accept needs the version last read, so the claim is re-read first
        // rather than assuming a version the caller may have cached. One extra
        // call is cheaper than an accept that silently applies to a claim that
        // changed underneath us.
        ProviderOutcome current = queryShipment(externalReference, call);
        if (current.status() != ProviderOutcome.Status.SUCCESS) {
            return current;
        }
        Object version = current.normalized().get("version");
        if (version == null) {
            return ProviderOutcome.uncertain(
                    "NO_VERSION", "Claim %s returned no version; cannot accept safely".formatted(externalReference));
        }

        return http.post(
                call,
                "/claims/accept?claim_id=" + externalReference,
                headers(call),
                Map.of("version", version),
                response -> {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    normalized.put("state", "CONFIRMED");
                    normalized.put("providerStatus", response.get("status"));
                    normalized.put("version", response.get("version"));
                    normalized.put("live", true);
                    return ProviderOutcome.success(normalized, externalReference);
                });
    }

    @Override
    public ProviderOutcome cancellationCost(String externalReference, ProviderCall call) {
        return http.post(
                call, "/claims/cancel-info?claim_id=" + externalReference, headers(call), Map.of(), response -> {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    String state = String.valueOf(response.get("cancel_state"));
                    normalized.put("cancelState", state);
                    normalized.put("free", "free".equals(state));
                    normalized.put("version", response.get("version"));
                    return ProviderOutcome.success(normalized, externalReference);
                });
    }

    @Override
    public ProviderOutcome cancelShipment(String externalReference, String reason, ProviderCall call) {
        // cancel_state and version both come from cancel-info. Cancelling with a
        // guessed state is how a "free" cancellation turns into a paid one.
        ProviderOutcome cost = cancellationCost(externalReference, call);
        if (cost.status() != ProviderOutcome.Status.SUCCESS) {
            return cost;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cancel_state", cost.normalized().get("cancelState"));
        body.put("version", cost.normalized().get("version"));

        return http.post(call, "/claims/cancel?claim_id=" + externalReference, headers(call), body, response -> {
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("state", "CANCELLED");
            normalized.put("providerStatus", response.get("status"));
            normalized.put(
                    "paidCancellation", !Boolean.TRUE.equals(cost.normalized().get("free")));
            normalized.put("reason", reason);
            return ProviderOutcome.success(normalized, externalReference);
        });
    }

    @Override
    public ProviderOutcome queryShipment(String externalReference, ProviderCall call) {
        return http.post(call, "/claims/info?claim_id=" + externalReference, headers(call), Map.of(), response -> {
            Map<String, Object> normalized = new LinkedHashMap<>();
            String providerStatus = String.valueOf(response.get("status"));
            normalized.put("state", YandexClaimStatus.toShipmentState(providerStatus));
            normalized.put("providerStatus", providerStatus);
            normalized.put("version", response.get("version"));
            normalized.put("live", YandexClaimStatus.isLive(providerStatus));
            return ProviderOutcome.success(normalized, externalReference);
        });
    }

    private Map<String, String> headers(ProviderCall call) {
        return Map.of("Authorization", "Bearer " + call.credential(), "Accept-Language", "ru");
    }

    private Map<String, Object> claimItem(DeliveryRequest request) {
        return Map.of(
                "title",
                "Order " + request.horecaosReference(),
                "quantity",
                1,
                "cost_value",
                String.valueOf(request.itemValueMinor() / 100.0),
                "cost_currency",
                request.currency(),
                "size",
                Map.of("length", 0.3, "width", 0.3, "height", 0.3),
                "weight",
                1.0);
    }

    private Map<String, Object> pickupPoint(DeliveryRequest request) {
        Pickup pickup = request.pickup();
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("point_id", 1);
        point.put("visit_order", 1);
        point.put("type", "source");
        point.put(
                "address",
                Map.of(
                        "fullname", pickup.address(),
                        "coordinates", coordinates(pickup.longitude(), pickup.latitude()),
                        "comment", pickup.comment() == null ? "" : pickup.comment()));
        point.put("contact", Map.of("name", pickup.contactName(), "phone", pickup.contactPhone()));
        return point;
    }

    private Map<String, Object> dropoffPoint(DeliveryRequest request) {
        Dropoff dropoff = request.dropoff();
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("fullname", dropoff.address());
        address.put("coordinates", coordinates(dropoff.longitude(), dropoff.latitude()));
        address.put("comment", dropoff.comment() == null ? "" : dropoff.comment());
        if (dropoff.entrance() != null) {
            address.put("porch", dropoff.entrance());
        }
        if (dropoff.floor() != null) {
            address.put("sfloor", dropoff.floor());
        }
        if (dropoff.apartment() != null) {
            address.put("sflat", dropoff.apartment());
        }

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("point_id", 2);
        point.put("visit_order", 2);
        point.put("type", "destination");
        point.put("address", address);
        point.put("contact", Map.of("name", dropoff.contactName(), "phone", dropoff.contactPhone()));
        // Yandex settles with the merchant, not the recipient, so no
        // cash-on-delivery block is sent. A prepaid basket needs no flag here —
        // unlike Noor, where omitting one charges the customer twice.
        return point;
    }

    /** Yandex orders coordinates longitude-first, which is the reverse of most maps. */
    private static List<Double> coordinates(double longitude, double latitude) {
        return List.of(longitude, latitude);
    }

    private static long minorUnits(Object price) {
        if (price == null) {
            return 0L;
        }
        return Math.round(Double.parseDouble(String.valueOf(price)) * 100);
    }
}
