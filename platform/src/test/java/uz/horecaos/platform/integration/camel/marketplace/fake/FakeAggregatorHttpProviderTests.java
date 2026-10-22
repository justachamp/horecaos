package uz.horecaos.platform.integration.camel.marketplace.fake;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.marketplace.AvailabilityPush;
import uz.horecaos.platform.integration.api.marketplace.MarketplaceApiCall;
import uz.horecaos.platform.integration.api.marketplace.PushConclusion;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;

/**
 * The local fake aggregator (ADR 0007, ADR 0141) is only worth having if the platform's REAL transport
 * reaches it and classifies what it answers the way it would classify a real partner's. So these drive
 * {@link FakeAggregatorAvailabilityAdapter}'s call through the production {@link ProviderHttpClient} and
 * ask {@link PushConclusion} what the platform may then believe, rather than only asking the fake what it
 * received.
 */
class FakeAggregatorHttpProviderTests {

    private static final String SECRET = "a-test-secret-not-a-real-credential";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneOffset.UTC);

    private final FakeAggregatorAvailabilityAdapter adapter = new FakeAggregatorAvailabilityAdapter();
    private final ProviderHttpClient http =
            new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier());
    private FakeAggregatorHttpProvider fake;
    private int port;

    @BeforeEach
    void start() {
        fake = new FakeAggregatorHttpProvider(CLOCK, SECRET);
        port = fake.start();
    }

    @AfterEach
    void stop() {
        fake.stop();
    }

    @Test
    @DisplayName("a push through the real transport is held by the fake and the platform may believe it")
    void aPushIsHeldAndConfirmed() {
        PushConclusion conclusion = push("item-plov", true, 4, SECRET);

        assertThat(conclusion).isEqualTo(PushConclusion.CONFIRMED);
        assertThat(fake.itemState("item-plov"))
                .isEqualTo(new FakeAggregatorHttpProvider.ItemState(true, 4, CLOCK.instant()));
    }

    @Test
    @DisplayName("an older sequence is acknowledged and changes nothing, so a late duplicate cannot undo a newer value")
    void anOlderSequenceCannotUndoANewerOne() {
        push("item-plov", true, 1, SECRET);
        push("item-plov", false, 3, SECRET);
        PushConclusion late = push("item-plov", true, 2, SECRET);

        assertThat(late).as("the partner answered 200").isEqualTo(PushConclusion.CONFIRMED);
        assertThat(Objects.requireNonNull(fake.itemState("item-plov")).available())
                .as("a resend of the stale value must not make a stopped dish orderable again")
                .isFalse();
        assertThat(Objects.requireNonNull(fake.itemState("item-plov")).sequence())
                .isEqualTo(3);
        assertThat(fake.pushes()).hasSize(3);
    }

    @Test
    @DisplayName("an item the partner does not know is read as unmapped, not as a failure to retry")
    void anUnknownItemIsReadAsUnmapped() {
        assertThat(push(FakeAggregatorHttpProvider.SCENARIO_UNKNOWN_ITEM, false, 1, SECRET))
                .isEqualTo(PushConclusion.REJECTED_UNMAPPED);
    }

    @Test
    @DisplayName("an HTTP 500 leaves the platform not knowing what the partner holds")
    void aServerErrorIsUnknown() {
        assertThat(push(FakeAggregatorHttpProvider.SCENARIO_HTTP_500, false, 1, SECRET))
                .isEqualTo(PushConclusion.UNKNOWN);
    }

    @Test
    @DisplayName("a reply lost after the partner acted is unknown, though the partner did change")
    void aLostReplyIsUnknownEvenThoughThePartnerActed() {
        assertThat(push(FakeAggregatorHttpProvider.SCENARIO_ACCEPTED_THEN_LOST, false, 1, SECRET))
                .isEqualTo(PushConclusion.UNKNOWN);

        assertThat(fake.itemState(FakeAggregatorHttpProvider.SCENARIO_ACCEPTED_THEN_LOST))
                .as("this is what makes the case worth rehearsing: believing it failed would be wrong")
                .isNotNull();
    }

    @Test
    @DisplayName("a wrong credential is refused and provably wrote nothing")
    void aWrongCredentialWroteNothing() {
        assertThat(push("item-plov", false, 1, "not-the-secret")).isEqualTo(PushConclusion.NOT_APPLIED);
        assertThat(fake.itemState("item-plov")).isNull();
        assertThat(fake.pushes()).isEmpty();
    }

    @Test
    @DisplayName("an outage answers 503 to every call until it is ended, then the same push lands")
    void anOutageIsOverWhenItIsEnded() throws Exception {
        fake.setOutage(true);
        assertThat(push("item-plov", false, 1, SECRET)).isEqualTo(PushConclusion.UNKNOWN);
        assertThat(fake.pushes()).isEmpty();

        HttpResponse<String> ended = control("POST", "/_fake/outage?on=false");
        assertThat(ended.statusCode()).isEqualTo(200);
        assertThat(fake.outage()).isFalse();

        assertThat(push("item-plov", false, 2, SECRET)).isEqualTo(PushConclusion.CONFIRMED);
    }

    @Test
    @DisplayName("the developer's window shows what the fake holds, without a credential")
    void theControlWindowShowsTheState() throws Exception {
        push("item-somsa", false, 7, SECRET);

        HttpResponse<String> state = control("GET", "/_fake/state");

        assertThat(state.statusCode()).isEqualTo(200);
        assertThat(state.body())
                .contains("item-somsa")
                .contains("\"sequence\":7")
                .contains("\"outage\":false");
    }

    @Test
    @DisplayName("the adapter encodes an item id into the path and sends the state, not a delta")
    void theAdapterEncodesTheItemId() {
        MarketplaceApiCall call = adapter.availabilityCall(pushOf("item with space/slash", true, 9));

        assertThat(call.path()).isEqualTo("/v1/items/item%20with%20space%2Fslash/availability");
        assertThat(call.method()).isEqualTo("PUT");
        assertThat(Objects.requireNonNull(call.body()).apply(SECRET))
                .containsEntry("available", true)
                .containsEntry("sequence", 9L);
        assertThat(call.authorization().apply(SECRET)).containsEntry("Authorization", "Bearer " + SECRET);
        assertThat(call.toString())
                .as("a log line never carries the credential")
                .doesNotContain(SECRET);
    }

    // ------------------------------------------------------------------ helpers

    private PushConclusion push(String itemId, boolean available, long sequence, String credential) {
        MarketplaceApiCall call = adapter.availabilityCall(pushOf(itemId, available, sequence));
        ProviderCall providerCall =
                new ProviderCall("http://localhost:" + port, credential, call.correlationId(), Duration.ofSeconds(5));
        ProviderOutcome transport = http.put(
                providerCall,
                call.path(),
                call.authorization().apply(credential),
                Objects.requireNonNull(call.body()).apply(credential),
                parsed -> ProviderOutcome.success(parsed, null));
        return PushConclusion.of(adapter.interpret(transport));
    }

    private static AvailabilityPush pushOf(String itemId, boolean available, long sequence) {
        return new AvailabilityPush(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                FakeAggregatorAvailabilityAdapter.PROVIDER_TYPE,
                itemId,
                available,
                sequence,
                "corr-1");
    }

    private HttpResponse<String> control(String method, String path) throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                                .method(method, HttpRequest.BodyPublishers.noBody())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }
}
