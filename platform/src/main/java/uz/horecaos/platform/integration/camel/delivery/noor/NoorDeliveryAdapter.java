package uz.horecaos.platform.integration.camel.delivery.noor;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.delivery.DeliveryCapability;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;

/**
 * Noor Delivery, API v1.
 *
 * <p>Verified against the published collection on 2026-08-20. Three properties
 * separate this adapter from {@code YandexDeliveryAdapter}, and each one is a way
 * to lose money if ignored:
 *
 * <ul>
 *   <li><b>Create is immediately live.</b> There is no hold. {@code POST /orders}
 *       dispatches a courier, so this adapter does not declare
 *       {@link DeliveryCapability#RESERVE_SHIPMENT} and must never be called
 *       speculatively while other partners are still being compared.</li>
 *   <li><b>{@code product_paid} decides who pays.</b> Omitted or false, the courier
 *       collects the basket total from the recipient. On a basket HorecaOS has
 *       already charged, that is the customer paying twice. See
 *       {@link #productPaid(DeliveryRequest)}.</li>
 *   <li><b>Idempotency is unverified.</b> {@code vendor_order_id} looks like a
 *       dedupe key but the documentation does not say so, and we have not been
 *       able to confirm it with the partner. Until someone does, a create whose
 *       response never arrives is {@code UNCERTAIN}, not retryable — see
 *       {@link #createShipment}.</li>
 * </ul>
 */
@Component
public class NoorDeliveryAdapter implements DeliveryPartner {

    public static final String PROVIDER_TYPE = "noor-delivery";

    /** Noor rejects offset-bearing timestamps; everything is UTC, seconds precision. */
    private static final DateTimeFormatter NOOR_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final ProviderHttpClient http;

    public NoorDeliveryAdapter(ProviderHttpClient http) {
        this.http = http;
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public Set<DeliveryCapability> capabilities() {
        // No RESERVE_SHIPMENT or CONFIRM_SHIPMENT: there is no hold to take.
        // No QUERY_CANCELLATION_COST: the API will not say what a cancellation
        // costs, and a guessed "free" is the wrong kind of answer.
        // No RESCHEDULE_SHIPMENT: changing the time means cancel and re-create.
        return Set.of(
                DeliveryCapability.QUOTE_DELIVERY,
                DeliveryCapability.CREATE_ON_DEMAND_SHIPMENT,
                DeliveryCapability.SCHEDULE_SHIPMENT,
                DeliveryCapability.CANCEL_SHIPMENT,
                DeliveryCapability.QUERY_SHIPMENT,
                DeliveryCapability.VERIFY_DELIVERY_WEBHOOK);
    }

    @Override
    public ProviderOutcome quote(DeliveryRequest request, ProviderCall call) {
        return http.post(call, "/api/v1/orders/eval", headers(call), evalBody(request), response -> {
            Map<String, Object> quote = new LinkedHashMap<>();
            quote.put("priceMinor", minorUnits(response.get("price")));
            quote.put("currency", "UZS");
            quote.put("etaMinutes", response.get("eta"));
            // Noor returns no quote id and no expiry, so this price cannot be
            // redeemed later. It is a comparison input, nothing more.
            quote.put("binding", false);
            return ProviderOutcome.success(quote, null);
        });
    }

    /**
     * Creates a live delivery. There is no hold on this partner: when this call
     * succeeds a courier has been dispatched.
     *
     * <p>A failure whose request reached Noor comes back {@code UNCERTAIN}, and
     * the caller must reconcile with {@link #queryShipment} before doing anything
     * else. Retrying under {@code vendor_order_id} would be correct only if that
     * field deduplicates, which is exactly what we have not confirmed; if it does
     * not, the retry is a second courier and a second delivery fee.
     */
    @Override
    public ProviderOutcome createShipment(DeliveryRequest request, ProviderCall call) {
        return http.post(call, "/api/v1/orders", headers(call), createBody(request), response -> {
            Map<String, Object> normalized = new LinkedHashMap<>();
            String stage = String.valueOf(response.get("stage"));
            normalized.put("state", NoorStage.toShipmentState(stage));
            normalized.put("providerStatus", stage);
            // No hold exists on this partner, so a created shipment is live.
            normalized.put("live", true);
            return ProviderOutcome.success(normalized, String.valueOf(response.get("id")));
        });
    }

    /**
     * Not supported: Noor has no hold to promote. Returning {@code REJECTED}
     * rather than silently succeeding keeps a caller that assumed a two-phase
     * partner from believing it confirmed something.
     */
    @Override
    public ProviderOutcome confirmShipment(String externalReference, ProviderCall call) {
        return ProviderOutcome.rejected(
                "CAPABILITY_UNSUPPORTED", "Noor creates live deliveries; there is no reservation to confirm");
    }

    @Override
    public ProviderOutcome cancellationCost(String externalReference, ProviderCall call) {
        // Noor exposes no cancellation-cost endpoint. UNCERTAIN is the honest
        // answer: it tells the caller the cost is unknown, where a zero would
        // tell it the cancellation is free.
        return ProviderOutcome.uncertain(
                "CAPABILITY_UNSUPPORTED", "Noor does not publish cancellation cost before cancelling");
    }

    @Override
    public ProviderOutcome cancelShipment(String externalReference, String reason, ProviderCall call) {
        return http.patch(
                call,
                "/api/v1/orders/" + externalReference + "/cancel",
                headers(call),
                Map.of("reason", reason == null ? "" : reason),
                response -> {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    normalized.put("state", "CANCELLED");
                    normalized.put("providerStatus", response.getOrDefault("stage", "CANCELED"));
                    normalized.put("reason", reason);
                    return ProviderOutcome.success(normalized, externalReference);
                });
    }

    @Override
    public ProviderOutcome queryShipment(String externalReference, ProviderCall call) {
        return http.get(call, "/api/v1/orders/" + externalReference, headers(call), response -> {
            Map<String, Object> normalized = new LinkedHashMap<>();
            String stage = String.valueOf(response.get("stage"));
            normalized.put("state", NoorStage.toShipmentState(stage));
            normalized.put("providerStatus", stage);
            normalized.put("live", NoorStage.isLive(stage));
            return ProviderOutcome.success(normalized, externalReference);
        });
    }

    private Map<String, String> headers(ProviderCall call) {
        // Noor authenticates with a long-lived static token rather than a bearer
        // grant, which is why ADR 0028 rotation matters more here than on Yandex.
        return Map.of("X-Auth", call.credential());
    }

    private Map<String, Object> evalBody(DeliveryRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "addresses",
                List.of(
                        Map.of(
                                "lat",
                                request.pickup().latitude(),
                                "lon",
                                request.pickup().longitude()),
                        Map.of(
                                "lat",
                                request.dropoff().latitude(),
                                "lon",
                                request.dropoff().longitude())));
        return body;
    }

    private Map<String, Object> createBody(DeliveryRequest request) {
        Map<String, Object> delivery = new LinkedHashMap<>();
        if (request.requestedPickupAt() == null) {
            delivery.put("type", "EXPRESS");
        } else {
            delivery.put("type", "DELAYED");
            delivery.put("time", NOOR_TIME.format(request.requestedPickupAt()));
        }
        delivery.put("product_paid", productPaid(request));
        delivery.put("product_price", request.itemValueMinor() / 100.0);

        Map<String, Object> body = new LinkedHashMap<>();
        // The order reference, not the command id. This is the value an operator
        // searches Noor's dashboard for after an uncertain create, so the route
        // runbook names it explicitly; the two must not drift apart.
        body.put("vendor_order_id", request.horecaosReference());
        // Noor accepts exactly one pickup point per order. A multi-pickup basket
        // has to become several orders upstream; it cannot be flattened here.
        // Built with a mutable map rather than Map.of(): a pickup contact name or
        // phone is optional on the domain request, and Map.of() throws on a null
        // value rather than accepting one.
        Map<String, Object> pickup = new LinkedHashMap<>();
        pickup.put("lat", request.pickup().latitude());
        pickup.put("lon", request.pickup().longitude());
        pickup.put("address", request.pickup().address());
        pickup.put(
                "contact_name",
                request.pickup().contactName() == null ? "" : request.pickup().contactName());
        pickup.put(
                "contact_phone",
                request.pickup().contactPhone() == null ? "" : request.pickup().contactPhone());
        pickup.put(
                "comment",
                request.pickup().comment() == null ? "" : request.pickup().comment());
        body.put("pickup", pickup);
        body.put("dropoff", dropoff(request));
        body.put("delivery", delivery);
        return body;
    }

    /**
     * True when HorecaOS has already taken the money.
     *
     * <p>Noor reads this as "do not collect from the recipient". Sending false on
     * a prepaid basket makes the courier ask the customer for the full amount at
     * the door — money we already have. There is no derived or defaulted value
     * here on purpose: it comes straight from the payment state on the request.
     */
    private boolean productPaid(DeliveryRequest request) {
        return request.prepaid();
    }

    private Map<String, Object> dropoff(DeliveryRequest request) {
        Dropoff target = request.dropoff();
        Map<String, Object> dropoff = new LinkedHashMap<>();
        dropoff.put("lat", target.latitude());
        dropoff.put("lon", target.longitude());
        dropoff.put("address", target.address());
        dropoff.put("contact_name", target.contactName());
        dropoff.put("contact_phone", target.contactPhone());
        dropoff.put("comment", target.comment() == null ? "" : target.comment());
        if (target.entrance() != null) {
            dropoff.put("entrance", target.entrance());
        }
        if (target.floor() != null) {
            dropoff.put("floor", target.floor());
        }
        if (target.apartment() != null) {
            dropoff.put("apartment", target.apartment());
        }
        return dropoff;
    }

    private static long minorUnits(@Nullable Object price) {
        if (price == null) {
            return 0L;
        }
        return Math.round(Double.parseDouble(String.valueOf(price)) * 100);
    }
}
