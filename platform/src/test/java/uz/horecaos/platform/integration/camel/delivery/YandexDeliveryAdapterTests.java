package uz.horecaos.platform.integration.camel.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.integration.api.delivery.DeliveryCapability;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.DeliveryRequest;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.Dropoff;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.Pickup;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.integration.camel.delivery.yandex.YandexDeliveryAdapter;

/**
 * What Yandex actually receives, and what a claim means when it comes back
 * (ADR 0014).
 */
class YandexDeliveryAdapterTests {

    private RecordingPartnerServer partner;
    private YandexDeliveryAdapter adapter;

    @BeforeEach
    void startPartner() throws Exception {
        partner = RecordingPartnerServer.start();
        adapter = new YandexDeliveryAdapter(
                new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier()));
    }

    @AfterEach
    void stopPartner() {
        partner.close();
    }

    @Test
    @DisplayName("a created claim is a hold, not a live booking")
    void createdClaimIsNotYetLive() {
        partner.reply("/claims/create", 200, """
                {"id":"claim-1","status":"new","version":1}""");

        ProviderOutcome outcome = adapter.createShipment(request(null), call());

        // The property that lets sourcing hold Yandex while still comparing
        // partners. Reading this as a booking would mean paying for both.
        assertThat(outcome.normalized()).containsEntry("live", false);
        assertThat(outcome.normalized()).containsEntry("state", "RESERVED");
        assertThat(outcome.externalReference()).isEqualTo("claim-1");
    }

    @Test
    @DisplayName("create carries request_id as its idempotency key")
    void createSendsTheIdempotencyKey() {
        partner.reply("/claims/create", 200, """
                {"id":"claim-2","status":"new","version":1}""");

        adapter.createShipment(request(null), call());

        // Documented as an idempotency key, which is what makes a Yandex create
        // safe to repeat where a Noor create is not.
        assertThat(partner.callTo("/claims/create").query()).isEqualTo("request_id=cmd-1");
        assertThat(partner.lastCall().headers()).containsEntry("Authorization", "Bearer yandex-token");
    }

    @Test
    @DisplayName("coordinates go out longitude first, the order Yandex expects")
    void coordinatesAreLongitudeFirst() {
        partner.reply("/claims/create", 200, """
                {"id":"claim-3","status":"new","version":1}""");

        adapter.createShipment(request(null), call());

        Object first = partner.callTo("/claims/create").list("route_points").getFirst();
        @SuppressWarnings("unchecked")
        Map<String, Object> address = (Map<String, Object>) ((Map<String, Object>) first).get("address");

        // Reversed, this delivers to a point in the Indian Ocean rather than
        // failing, which is why it is asserted rather than trusted.
        assertThat(address.get("coordinates")).isEqualTo(List.of(69.2797, 41.3111));
    }

    @Test
    @DisplayName("a scheduled pickup sends due; an immediate one omits it")
    void scheduledPickupSendsDue() {
        partner.reply("/claims/create", 200, """
                {"id":"claim-4","status":"new","version":1}""");

        adapter.createShipment(request(null), call());
        assertThat(partner.lastCall().body()).doesNotContainKey("due");

        adapter.createShipment(request(Instant.parse("2026-09-01T14:30:00Z")), call());
        assertThat(partner.lastCall().body()).containsEntry("due", "2026-09-01T14:30:00Z");
    }

    @Test
    @DisplayName("accept re-reads the claim and sends the version it just read")
    void acceptUsesAFreshlyReadVersion() {
        partner.reply("/claims/info", 200, """
                {"id":"claim-5","status":"ready_for_approval","version":7}""");
        partner.reply("/claims/accept", 200, """
                {"id":"claim-5","status":"accepted","version":8}""");

        ProviderOutcome outcome = adapter.confirmShipment("claim-5", call());

        // Version 7 comes from the info call made moments earlier, not from
        // whatever the caller happened to be holding. A stale version would be
        // rejected, and a cached one is stale by definition.
        assertThat(partner.callTo("/claims/accept").field("version")).isEqualTo(7);
        assertThat(outcome.normalized()).containsEntry("live", true);
        assertThat(outcome.normalized()).containsEntry("state", "CONFIRMED");
    }

    @Test
    @DisplayName("cancel asks what it costs first and sends back that exact state")
    void cancelUsesCancelInfoState() {
        partner.reply("/claims/cancel-info", 200, """
                {"cancel_state":"paid","version":3}""");
        partner.reply("/claims/cancel", 200, """
                {"id":"claim-6","status":"cancelled"}""");

        ProviderOutcome outcome = adapter.cancelShipment("claim-6", "customer changed mind", call());

        assertThat(partner.callTo("/claims/cancel").field("cancel_state")).isEqualTo("paid");
        assertThat(partner.callTo("/claims/cancel").field("version")).isEqualTo(3);
        // Surfaced so the merchant can be told a fee was incurred rather than
        // discovering it on an invoice.
        assertThat(outcome.normalized()).containsEntry("paidCancellation", true);
    }

    @Test
    @DisplayName("a free cancellation is reported as free")
    void freeCancellationIsReportedAsFree() {
        partner.reply("/claims/cancel-info", 200, """
                {"cancel_state":"free","version":2}""");

        ProviderOutcome outcome = adapter.cancellationCost("claim-7", call());

        assertThat(outcome.normalized()).containsEntry("free", true);
    }

    @Test
    @DisplayName("a stale version is a conflict, and a conflict is uncertain")
    void staleVersionIsUncertain() {
        partner.reply("/claims/info", 200, """
                {"id":"claim-8","status":"ready_for_approval","version":4}""");
        partner.reply("/claims/accept", 409, """
                {"code":"inconsistent_state"}""");

        ProviderOutcome outcome = adapter.confirmShipment("claim-8", call());

        // The claim moved underneath us; whether it moved to accepted is exactly
        // what we do not know. Reconcile rather than accept again.
        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
    }

    @Test
    @DisplayName("performer_not_found is still an open claim, not a failure")
    void performerNotFoundIsStillOpen() {
        partner.reply("/claims/info", 200, """
                {"id":"claim-9","status":"performer_not_found","version":5}""");

        ProviderOutcome outcome = adapter.queryShipment("claim-9", call());

        // Yandex keeps searching. Reading the name as terminal would strand the
        // order while a courier was still being found.
        assertThat(outcome.normalized()).containsEntry("state", "CONFIRMED");
    }

    @Test
    @DisplayName("an unrecognised status becomes UNKNOWN rather than a guess")
    void unknownStatusIsNotGuessed() {
        partner.reply("/claims/info", 200, """
                {"id":"claim-10","status":"some_new_status","version":6}""");

        ProviderOutcome outcome = adapter.queryShipment("claim-10", call());

        assertThat(outcome.normalized()).containsEntry("state", "UNKNOWN");
    }

    @Test
    @DisplayName("Yandex declares a reservation phase and no reschedule")
    void declaresTheCapabilitiesItActuallyHas() {
        assertThat(adapter.capabilities())
                .contains(
                        DeliveryCapability.RESERVE_SHIPMENT,
                        DeliveryCapability.CONFIRM_SHIPMENT,
                        DeliveryCapability.QUERY_CANCELLATION_COST)
                .doesNotContain(DeliveryCapability.RESCHEDULE_SHIPMENT);
    }

    @Test
    @DisplayName("a quote is marked non-binding, because Yandex returns no redeemable id")
    void quoteIsNotBinding() {
        partner.reply("/check-price", 200, """
                {"price":"35000.0","eta":25}""");

        ProviderOutcome outcome = adapter.quote(request(null), call());

        assertThat(outcome.normalized()).containsEntry("binding", false);
        assertThat(outcome.normalized()).containsEntry("priceMinor", 3_500_000L);
        assertThat(outcome.externalReference()).isNull();
    }

    private ProviderCall call() {
        return new ProviderCall(partner.baseUrl(), "yandex-token", "cmd-1", Duration.ofSeconds(5));
    }

    private static DeliveryRequest request(Instant pickupAt) {
        return new DeliveryRequest(
                "QO-2002",
                new Pickup(41.3111, 69.2797, "Amir Temur 1", "Kitchen", "+998901112233", null),
                new Dropoff(41.2995, 69.2401, "Navoi 5", "Customer", "+998907654321", null, "2", "4", "12"),
                pickupAt,
                true,
                150_000_00L,
                "UZS",
                Map.of());
    }
}
