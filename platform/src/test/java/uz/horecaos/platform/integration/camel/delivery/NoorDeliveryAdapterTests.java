package uz.horecaos.platform.integration.camel.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
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
import uz.horecaos.platform.integration.camel.delivery.noor.NoorDeliveryAdapter;

/**
 * What Noor actually receives (ADR 0014).
 *
 * <p>These assert on the wire, not on the return value, because every Noor
 * mistake that costs money produces a 200 response.
 */
class NoorDeliveryAdapterTests {

    private RecordingPartnerServer partner;
    private NoorDeliveryAdapter adapter;

    @BeforeEach
    void startPartner() throws Exception {
        partner = RecordingPartnerServer.start();
        adapter = new NoorDeliveryAdapter(
                new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier()));
    }

    @AfterEach
    void stopPartner() {
        partner.close();
    }

    @Test
    @DisplayName("a prepaid basket sends product_paid=true so the courier does not charge again")
    void prepaidBasketMarksProductPaid() {
        partner.reply("/api/v1/orders", 200, """
                {"id":"noor-1","stage":"New"}""");

        adapter.createShipment(request(true, null), call());

        // The financial invariant from ADR 0014. False here means the courier
        // collects the basket total at the door from a customer who has already
        // paid HorecaOS — the single most expensive mistake this adapter can make.
        assertThat(partner.callTo("/api/v1/orders").field("delivery", "product_paid"))
                .isEqualTo(true);
    }

    @Test
    @DisplayName("an unpaid basket sends product_paid=false so the courier collects")
    void unpaidBasketLeavesCollectionToTheCourier() {
        partner.reply("/api/v1/orders", 200, """
                {"id":"noor-2","stage":"New"}""");

        adapter.createShipment(request(false, null), call());

        assertThat(partner.callTo("/api/v1/orders").field("delivery", "product_paid"))
                .isEqualTo(false);
    }

    @Test
    @DisplayName("an immediate delivery is EXPRESS with no time, a future one is DELAYED with UTC")
    void deliveryTypeFollowsTheRequestedPickup() {
        partner.reply("/api/v1/orders", 200, """
                {"id":"noor-3","stage":"New"}""");

        adapter.createShipment(request(true, null), call());
        assertThat(partner.lastCall().field("delivery", "type")).isEqualTo("EXPRESS");
        assertThat(partner.lastCall().field("delivery", "time")).isNull();

        adapter.createShipment(request(true, Instant.parse("2026-09-01T14:30:00Z")), call());
        assertThat(partner.lastCall().field("delivery", "type")).isEqualTo("DELAYED");
        // Noor is UTC everywhere and rejects offsets; an offset here would be a
        // silent five-hour error in Tashkent.
        assertThat(partner.lastCall().field("delivery", "time")).isEqualTo("2026-09-01 14:30:00");
    }

    @Test
    @DisplayName("the static token travels in X-Auth and never in a query string")
    void authenticatesWithTheStaticTokenHeader() {
        partner.reply("/api/v1/orders", 200, """
                {"id":"noor-4","stage":"New"}""");

        adapter.createShipment(request(true, null), call());

        assertThat(partner.lastCall().headers()).containsEntry("X-auth", "secret-token");
        assertThat(partner.lastCall().query()).isNull();
    }

    @Test
    @DisplayName("a read timeout on create is uncertain, never retryable")
    void createTimeoutIsUncertain() {
        partner.stallAfterReceiving("/api/v1/orders", 2_000);

        ProviderOutcome outcome = adapter.createShipment(
                request(true, null),
                new ProviderCall(partner.baseUrl(), "secret-token", "cmd-1", Duration.ofMillis(300)));

        // The premise, asserted rather than assumed: Noor really did receive the
        // create. Without this the test would still pass on a connect failure,
        // which is a different outcome with the opposite safe answer.
        assertThat(partner.calls()).hasSize(1);

        // vendor_order_id is not documented as an idempotency key, so a retry
        // here may book a second courier. UNCERTAIN forces a query instead.
        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
        assertThat(outcome.mayRetryDirectly()).isFalse();
        assertThat(outcome.requiresReconciliation()).isTrue();
    }

    @Test
    @DisplayName("a connect failure is retryable, because nothing reached the provider")
    void connectFailureIsRetryable() throws Exception {
        String deadUrl;
        try (RecordingPartnerServer dead = RecordingPartnerServer.start()) {
            deadUrl = dead.baseUrl();
        }

        ProviderOutcome outcome = adapter.createShipment(
                request(true, null), new ProviderCall(deadUrl, "secret-token", "cmd-2", Duration.ofSeconds(2)));

        // The mirror of the test above, and the reason the two must be told
        // apart: this one is safe to send again, that one is not.
        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.RETRYABLE);
        assertThat(outcome.requiresReconciliation()).isFalse();
    }

    @Test
    @DisplayName("a provider-side timeout on create is uncertain, not retryable")
    void providerTimeoutStatusOnCreateIsUncertain() {
        // 408 means the request reached Noor and Noor gave up processing it, so
        // it may well have dispatched a courier first. The HTTP client used to
        // call this retryable while the shared classifier called it uncertain;
        // the wired-in path was the unsafe one.
        partner.reply("/api/v1/orders", 408, "{\"error\":\"request timeout\"}");

        ProviderOutcome outcome = adapter.createShipment(request(true, null), call());

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
        assertThat(outcome.mayRetryDirectly()).isFalse();
    }

    @Test
    @DisplayName("a 2xx body that cannot be read is uncertain, because the courier is already sent")
    void unreadableSuccessResponseIsUncertain() {
        // Noor answers 200 with something that is not the expected object — a
        // proxy's HTML error page, a truncated body, an array. Jackson 3 throws
        // a RuntimeException rather than an IOException, so this used to fall
        // through to a blanket RETRYABLE and invite a second create.
        partner.reply("/api/v1/orders", 200, "[\"unexpected\"]");

        ProviderOutcome outcome = adapter.createShipment(request(true, null), call());

        assertThat(partner.calls()).hasSize(1);
        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
        assertThat(outcome.errorCode()).isEqualTo("RESPONSE_UNREADABLE");
    }

    @Test
    @DisplayName("a 5xx stays retryable, which is the deliberate exception")
    void serverErrorRemainsRetryable() {
        // Unlike 408, a 5xx is the provider stating it did not process the
        // request. Treating it as uncertain would route every transient blip to
        // manual reconciliation.
        partner.reply("/api/v1/orders", 503, "{\"error\":\"unavailable\"}");

        assertThat(adapter.createShipment(request(true, null), call()).status())
                .isEqualTo(ProviderOutcome.Status.RETRYABLE);
    }

    @Test
    @DisplayName("a created order is reported live, because Noor has no hold")
    void createdOrderIsLive() {
        partner.reply("/api/v1/orders", 200, """
                {"id":"noor-5","stage":"New"}""");

        ProviderOutcome outcome = adapter.createShipment(request(true, null), call());

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.SUCCESS);
        assertThat(outcome.externalReference()).isEqualTo("noor-5");
        assertThat(outcome.normalized()).containsEntry("live", true);
    }

    @Test
    @DisplayName("Noor declares no reservation and refuses to pretend it confirmed one")
    void hasNoReservationPhase() {
        assertThat(adapter.capabilities())
                .doesNotContain(
                        DeliveryCapability.RESERVE_SHIPMENT,
                        DeliveryCapability.CONFIRM_SHIPMENT,
                        DeliveryCapability.RESCHEDULE_SHIPMENT,
                        DeliveryCapability.QUERY_CANCELLATION_COST);

        assertThat(adapter.confirmShipment("noor-1", call()).status()).isEqualTo(ProviderOutcome.Status.REJECTED);
    }

    @Test
    @DisplayName("an unknown cancellation cost is uncertain rather than assumed free")
    void cancellationCostIsUnknownNotFree() {
        ProviderOutcome outcome = adapter.cancellationCost("noor-1", call());

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
    }

    @Test
    @DisplayName("an unmapped stage becomes UNKNOWN instead of a guess")
    void unmappedStageDoesNotBecomeDelivered() {
        partner.reply("/api/v1/orders/noor-9", 200, """
                {"id":"noor-9","stage":"SomeStageWeHaveNotSeen"}""");

        ProviderOutcome outcome = adapter.queryShipment("noor-9", call());

        assertThat(outcome.normalized()).containsEntry("state", "UNKNOWN");
        assertThat(outcome.normalized()).containsEntry("live", false);
    }

    @Test
    @DisplayName("a business rejection stage maps to FAILED, not to a transport retry")
    void performerNotFoundIsABusinessFailure() {
        partner.reply("/api/v1/orders/noor-10", 200, """
                {"id":"noor-10","stage":"PerformerNotFound"}""");

        ProviderOutcome outcome = adapter.queryShipment("noor-10", call());

        assertThat(outcome.normalized()).containsEntry("state", "FAILED");
    }

    private ProviderCall call() {
        return new ProviderCall(partner.baseUrl(), "secret-token", "cmd-1", Duration.ofSeconds(5));
    }

    private static DeliveryRequest request(boolean prepaid, @Nullable Instant pickupAt) {
        return new DeliveryRequest(
                "QO-1001",
                new Pickup(41.3111, 69.2797, "Amir Temur 1", "Kitchen", "+998901112233", null),
                new Dropoff(41.2995, 69.2401, "Navoi 5", "Customer", "+998907654321", null, "2", "4", "12"),
                pickupAt,
                prepaid,
                150_000_00L,
                "UZS",
                Map.of());
    }
}
