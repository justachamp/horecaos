package uz.horecaos.platform.payments.infrastructure.click.fake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fake Click HTTP provider's own wire behaviour, including ADR 0007's
 * failure scenarios (ADR 0007, ADR 0013).
 *
 * <p>Deliberately not run through {@code PaymentGateway}/the Camel route — this is
 * a contract test of the fake itself, matching ADR 0007's own split between
 * {@code ControlledFakeProvider} (a fake, tested directly) and
 * {@code ProviderContractTests} (the same fake, exercised through a real route).
 * {@code ClickFakeProviderRoundTripTests} is this file's counterpart on the
 * production-code side: it proves the real Camel route, gateway, and secret
 * resolution reach this server; this file proves the server itself is honest about
 * what it claims to simulate.
 */
class FakeClickHttpProviderTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T09:00:00Z"), ZoneOffset.UTC);

    private FakeClickHttpProvider provider;
    private String baseUrl;
    private HttpClient client;

    @BeforeEach
    void start() {
        provider = new FakeClickHttpProvider(CLOCK, null);
        int port = provider.start(0);
        baseUrl = "http://localhost:" + port;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void stop() {
        provider.stop();
    }

    @Test
    @DisplayName("invoice/create answers error_code 0 with a synthetic invoice_id")
    void invoiceCreateSucceeds() throws Exception {
        String body = """
                {"service_id":"svc-1","amount":15000,"phone_number":"998901234567","merchant_trans_id":"mti-1"}""";
        HttpResponse<String> response = post("/invoice/create", body);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"error_code\":0").contains("invoice_id");
    }

    @Test
    @DisplayName("a payment registered by the fake customer-pay tool is found by status_by_mti and payment/status")
    void registeredPaymentIsFoundByStatusQueries() throws Exception {
        String paymentId = provider.registerCapturedPayment("svc-1", "mti-42", 15_000L);

        HttpResponse<String> byMti = get("/payment/status_by_mti/svc-1/mti-42/2026-08-24");
        assertThat(byMti.statusCode()).isEqualTo(200);
        assertThat(byMti.body()).contains("\"payment_id\":" + paymentId);

        HttpResponse<String> status = get("/payment/status/svc-1/" + paymentId);
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(status.body()).contains("\"payment_status\":2");
    }

    @Test
    @DisplayName("a service_id unrelated to a payment_id finds nothing, per binding")
    void statusByMtiIsScopedToItsOwnServiceId() throws Exception {
        provider.registerCapturedPayment("svc-owner", "mti-99", 1_000L);

        HttpResponse<String> wrongService = get("/payment/status_by_mti/svc-other/mti-99/2026-08-24");
        assertThat(wrongService.statusCode()).isEqualTo(200);
        assertThat(wrongService.body()).doesNotContain("payment_id");
    }

    @Test
    @DisplayName("submit_items registers an OFD receipt that ofd_data then reads back")
    void submitItemsThenReadsBack() throws Exception {
        String paymentId = provider.registerCapturedPayment("svc-1", "mti-fiscal", 15_000L);
        String body = "{\"service_id\":\"svc-1\",\"payment_id\":\"" + paymentId
                + "\",\"items\":[],\"received_ecash\":0,\"received_cash\":0,\"received_card\":1500000}";

        HttpResponse<String> submit = post("/payment/ofd_data/submit_items", body);
        assertThat(submit.statusCode()).isEqualTo(200);
        assertThat(submit.body()).contains("\"error_code\":0");

        HttpResponse<String> readBack = get("/payment/ofd_data/svc-1/" + paymentId);
        assertThat(readBack.statusCode()).isEqualTo(200);
        assertThat(readBack.body()).contains("https://ofd.soliq.uz/epi?t=");
    }

    @Test
    @DisplayName("the ofd_data read-back is empty before anything was submitted")
    void ofdDataIsEmptyBeforeSubmission() throws Exception {
        String paymentId = provider.registerCapturedPayment("svc-1", "mti-unsubmitted", 15_000L);

        HttpResponse<String> readBack = get("/payment/ofd_data/svc-1/" + paymentId);
        assertThat(readBack.statusCode()).isEqualTo(200);
        assertThat(readBack.body()).doesNotContain("qrCodeURL");
    }

    // -----------------------------------------------------------------------
    // ADR 0007's scenarios
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("SCENARIO_HTTP_500 answers 500 on every call naming it, with no production code aware of it")
    void http500Scenario() throws Exception {
        String body = "{\"service_id\":\"" + FakeClickHttpProvider.SCENARIO_HTTP_500 + "\",\"payment_id\":\"1\"}";
        HttpResponse<String> response = post("/payment/ofd_data/submit_items", body);

        assertThat(response.statusCode()).isEqualTo(500);
    }

    @Test
    @DisplayName("SCENARIO_ACCEPTED_THEN_LOST performs the side effect and then drops the connection unanswered")
    void acceptedThenLostScenario() {
        String serviceId = FakeClickHttpProvider.SCENARIO_ACCEPTED_THEN_LOST;
        String paymentId = provider.registerCapturedPayment(serviceId, "mti-lost", 15_000L);
        String body = "{\"service_id\":\"" + serviceId + "\",\"payment_id\":\"" + paymentId
                + "\",\"items\":[],\"received_ecash\":0,\"received_cash\":0,\"received_card\":1500000}";

        // The client experiences exactly what a lost reply looks like: the
        // provider acted (see the assertion below) and no answer ever arrived.
        assertThatThrownBy(() -> post("/payment/ofd_data/submit_items", body)).isInstanceOf(IOException.class);

        assertThat(provider.readOfdUrl(paymentId))
                .as("the side effect happened even though the response never arrived — an "
                        + "accepted-then-lost caller must resolve by reading, never by resending")
                .isNotBlank();
    }

    @Test
    @DisplayName("SCENARIO_TIMEOUT never answers within any caller's deadline")
    void timeoutScenario() {
        HttpClient shortTimeoutClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/payment/status/" + FakeClickHttpProvider.SCENARIO_TIMEOUT + "/1"))
                .timeout(Duration.ofMillis(300))
                .GET()
                .build();

        assertThatThrownBy(() -> shortTimeoutClient.send(request, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(HttpTimeoutException.class);
    }

    // ------------------------------------------------------------------- helpers

    private HttpResponse<String> post(String path, String jsonBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
