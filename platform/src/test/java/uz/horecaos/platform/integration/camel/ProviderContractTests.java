package uz.horecaos.platform.integration.camel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier;

/**
 * The ADR 0007 contract suite every provider adapter must satisfy.
 *
 * <p>Run against the controlled fake, because a real sandbox cannot be asked to
 * time out mid-request or accept a command and lose the reply — and those are
 * exactly the cases that duplicate a payment or book two couriers.
 */
class ProviderContractTests {

    private ControlledFakeProvider provider;
    private ProviderExceptionClassifier classifier;

    @BeforeEach
    void setUp() throws IOException {
        provider = ControlledFakeProvider.start();
        classifier = new ProviderExceptionClassifier();
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    void aSuccessfulCallIsNormalisedToSuccess() throws Exception {
        var response = call(ControlledFakeProvider.Scenario.SUCCESS, "key-1", Duration.ofSeconds(5));

        assertThat(classifier
                        .classify(response.statusCode(), response.body(), null)
                        .status())
                .isEqualTo(ProviderOutcome.Status.SUCCESS);
        assertThat(provider.sideEffectCount()).isEqualTo(1);
    }

    @Test
    void aRepeatedIdempotencyKeyProducesOneSideEffect() throws Exception {
        call(ControlledFakeProvider.Scenario.SUCCESS, "key-1", Duration.ofSeconds(5));
        call(ControlledFakeProvider.Scenario.SUCCESS, "key-1", Duration.ofSeconds(5));
        call(ControlledFakeProvider.Scenario.SUCCESS, "key-1", Duration.ofSeconds(5));

        assertThat(provider.sideEffectCount())
                .as("a retry under the same key must not book a second courier or take a second payment")
                .isEqualTo(1);
    }

    @Test
    void aDifferentKeyIsADifferentOperation() throws Exception {
        call(ControlledFakeProvider.Scenario.SUCCESS, "key-1", Duration.ofSeconds(5));
        call(ControlledFakeProvider.Scenario.SUCCESS, "key-2", Duration.ofSeconds(5));

        assertThat(provider.sideEffectCount()).isEqualTo(2);
    }

    @Test
    void aRateLimitIsRetryableAndCarriesItsDelay() throws Exception {
        var response = call(ControlledFakeProvider.Scenario.RATE_LIMITED, "key-1", Duration.ofSeconds(5));
        Duration retryAfter = response.headers()
                .firstValue("Retry-After")
                .map(value -> Duration.ofSeconds(Long.parseLong(value)))
                .orElse(null);

        var outcome = classifier.classify(response.statusCode(), response.body(), retryAfter);

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.RETRYABLE);
        assertThat(outcome.retryDelay()).contains(Duration.ofSeconds(7));
    }

    @Test
    void aPermanentRejectionIsNeverRetriedAsInfrastructure() throws Exception {
        var response = call(ControlledFakeProvider.Scenario.PERMANENT_REJECTION, "key-1", Duration.ofSeconds(5));

        var outcome = classifier.classify(response.statusCode(), response.body(), null);

        assertThat(outcome.status())
                .as("retrying a 400 produces the same 400 forever while looking like an outage")
                .isEqualTo(ProviderOutcome.Status.REJECTED);
        assertThat(outcome.mayRetryDirectly()).isFalse();
    }

    @Test
    void aServerErrorIsRetryable() throws Exception {
        var response = call(ControlledFakeProvider.Scenario.SERVER_ERROR, "key-1", Duration.ofSeconds(5));

        assertThat(classifier
                        .classify(response.statusCode(), response.body(), null)
                        .mayRetryDirectly())
                .isTrue();
    }

    @Test
    void anAuthenticationFailureIsNotRetriedOnATimer() {
        var outcome = classifier.classify(401, "{}", null);

        assertThat(outcome.status())
                .as("retrying on a timer would hide a credential rotation that never happened")
                .isEqualTo(ProviderOutcome.Status.REJECTED);
    }

    @Test
    void aReadTimeoutAfterSendingIsUncertainNotRetryable() {
        var outcome = classifier.classify(new SocketTimeoutException("read timed out"), true);

        assertThat(outcome.status())
                .as("the provider may already have acted; retrying blindly is how duplicates happen")
                .isEqualTo(ProviderOutcome.Status.UNCERTAIN);
        assertThat(outcome.requiresReconciliation()).isTrue();
        assertThat(outcome.mayRetryDirectly()).isFalse();
    }

    @Test
    void aConnectTimeoutBeforeSendingIsSafeToRetry() {
        var outcome = classifier.classify(new SocketTimeoutException("connect timed out"), false);

        assertThat(outcome.status())
                .as("nothing left this process, so nothing could have been acted on")
                .isEqualTo(ProviderOutcome.Status.RETRYABLE);
    }

    @Test
    void aConnectionResetAfterSendingIsUncertain() {
        assertThat(classifier
                        .classify(new IOException("connection reset"), true)
                        .requiresReconciliation())
                .isTrue();
    }

    @Test
    void anAcceptedThenTimedOutCommandLeavesARealSideEffect() {
        // The scenario the whole classification exists for: the provider did the
        // work and the caller never found out.
        Throwable timeout = null;
        try {
            call(ControlledFakeProvider.Scenario.ACCEPTED_THEN_TIMEOUT, "key-1", Duration.ofMillis(400));
        } catch (Exception expected) {
            timeout = expected;
        }

        assertThat(timeout).isNotNull();
        assertThat(classifier.classify(timeout, true).requiresReconciliation())
                .as("this is why UNCERTAIN cannot be collapsed into RETRYABLE")
                .isTrue();
    }

    @Test
    void aSlowResponseStillSucceedsWithinAGenerousTimeout() throws Exception {
        var response = call(ControlledFakeProvider.Scenario.SLOW, "key-1", Duration.ofSeconds(6));

        assertThat(classifier
                        .classify(response.statusCode(), response.body(), null)
                        .status())
                .isEqualTo(ProviderOutcome.Status.SUCCESS);
    }

    @Test
    void everyRequestCarriesItsIdempotencyKeyToTheProvider() throws Exception {
        call(ControlledFakeProvider.Scenario.SUCCESS, "key-42", Duration.ofSeconds(5));

        assertThat(provider.requests())
                .extracting(ControlledFakeProvider.RecordedRequest::idempotencyKey)
                .containsExactly("key-42");
    }

    private HttpResponse<String> call(ControlledFakeProvider.Scenario scenario, String idempotencyKey, Duration timeout)
            throws Exception {

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(provider.baseUrl() + "/provider/commands"))
                .header(ControlledFakeProvider.SCENARIO_HEADER, scenario.name())
                .header(ControlledFakeProvider.IDEMPOTENCY_HEADER, idempotencyKey)
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString("{\"command\":\"test\"}"))
                .build();

        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
