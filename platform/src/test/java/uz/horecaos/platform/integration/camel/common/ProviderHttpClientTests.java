package uz.horecaos.platform.integration.camel.common;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * What happens after a provider has answered (ADR 0007).
 *
 * <p>The scenarios here are the ones a sandbox cannot be asked for and the
 * contract suite's fake does not cover: a provider that sends 200 and then stops
 * writing, and one that sends 200 and never stops. Both are read-phase failures,
 * and both used to be unbounded — the request deadline covered only the headers,
 * so the read that followed ran forever on whichever thread called in, which for
 * a checkout is the Tomcat request thread.
 */
class ProviderHttpClientTests {

    private HttpServer server;
    private CountDownLatch release;
    private ProviderHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        release = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));
        client = new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier());
    }

    @AfterEach
    void tearDown() {
        // Released before the server stops, so a handler parked mid-body is not
        // left holding the executor open for the rest of the suite.
        release.countDown();
        server.stop(0);
    }

    @Test
    @DisplayName("a provider that answers 200 and then stalls is bounded by the request deadline")
    void aStalledBodyDoesNotPinTheCallingThread() {
        server.createContext("/stall", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            // Chunked, so the client is told a body is coming and then is not
            // given it. This is the shape that a streaming body handler cannot
            // put a deadline on.
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("{\"stat".getBytes(StandardCharsets.UTF_8));
            out.flush();
            await(release);
            exchange.close();
        });
        server.start();

        ProviderOutcome outcome = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> client.get(call(Duration.ofSeconds(1)), "/stall", Map.of(),
                        body -> ProviderOutcome.success(body, null)));

        // Uncertain rather than retryable: the request was on the wire and the
        // provider answered, so it may well have acted on it.
        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
    }

    @Test
    @DisplayName("a body larger than the cap is uncertain rather than buffered whole")
    void anEndlessBodyIsCappedRatherThanParsed() {
        String padding = "x".repeat(256 * 1024);
        server.createContext("/flood", exchange -> {
            byte[] bytes = ("{\"status\":\"ACCEPTED\",\"note\":\"" + padding + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        ProviderOutcome outcome = client.get(call(Duration.ofSeconds(5)), "/flood", Map.of(),
                body -> ProviderOutcome.success(body, null));

        // Well-formed JSON, and still refused. A quarter of a megabyte is not a
        // provider reporting an invoice, and parsing whatever fitted under the
        // cap would be answering a question nobody asked.
        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
        assertThat(outcome.errorCode()).isEqualTo("RESPONSE_UNREADABLE");
    }

    @Test
    @DisplayName("an ordinary answer still parses")
    void aNormalBodyIsUnaffected() {
        server.createContext("/ok", exchange -> {
            byte[] bytes = "{\"externalReference\":\"ext-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        ProviderOutcome outcome = client.get(call(Duration.ofSeconds(5)), "/ok", Map.of(),
                body -> ProviderOutcome.success(body, String.valueOf(body.get("externalReference"))));

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.SUCCESS);
        assertThat(outcome.externalReference()).isEqualTo("ext-1");
    }

    @Test
    @DisplayName("a rejection that echoes the request does not carry the customer into the outcome")
    void anEchoedRequestIsNotPersistedAsDetail() {
        String body = """
                {"code":"address_not_serviceable",
                 "message":"No courier for +998901231076",
                 "request":{"dropoff":{"address":"Tashkent, Amir Temur 12, apt 34",
                                       "contact_name":"Dilnoza K.",
                                       "contact_phone":"+998901231076"}},
                 "errors":[{"field":"dropoff.address","value":"Tashkent, Amir Temur 12, apt 34"}]}
                """;
        answer("/rejected", 422, body);

        ProviderOutcome outcome = client.get(call(Duration.ofSeconds(5)), "/rejected", Map.of(),
                success -> ProviderOutcome.success(success, null));

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
        assertThat(outcome.detail())
                .as("this detail is persisted on an onboarding step and served from a sync-run "
                        + "endpoint, both outside envelope encryption")
                .doesNotContain("Amir Temur", "Dilnoza", "998901231076")
                .as("and an operator still has to be able to tell what the provider refused")
                .contains("address_not_serviceable");
    }

    @Test
    @DisplayName("a provider's own error vocabulary survives, because downstream classifiers read it")
    void aRecognisedErrorFieldIsKept() {
        answer("/auth", 401, "{\"success\":false,\"error\":\"Client is disabled\"}");

        ProviderOutcome outcome = client.get(call(Duration.ofSeconds(5)), "/auth", Map.of(),
                success -> ProviderOutcome.success(success, null));

        assertThat(outcome.detail())
                .as("CloposEnvelope reads exactly this string to tell a restaurant's own switch "
                        + "from a broken integrator registration")
                .contains("Client is disabled");
    }

    @Test
    @DisplayName("an unrecognised body reports its field names, never its values")
    void anUnknownShapeReportsItsSchema() {
        answer("/odd", 500, "{\"fault\":\"upstream\",\"customer_phone\":\"+998901231076\"}");

        ProviderOutcome outcome = client.get(call(Duration.ofSeconds(5)), "/odd", Map.of(),
                success -> ProviderOutcome.success(success, null));

        assertThat(outcome.detail())
                .as("field names are the provider's schema and tell an operator what to add to "
                        + "the allowlist; the values beside them are not ours to keep")
                .contains("fault", "customer_phone")
                .doesNotContain("998901231076", "upstream");
    }

    @Test
    @DisplayName("a non-JSON error body is reduced to its size")
    void anHtmlErrorPageIsNotCarriedAround() {
        answer("/gateway", 502, "<html><body>Bad gateway for order QO-1 at Amir Temur 12</body></html>");

        ProviderOutcome outcome = client.get(call(Duration.ofSeconds(5)), "/gateway", Map.of(),
                success -> ProviderOutcome.success(success, null));

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.RETRYABLE);
        assertThat(outcome.detail())
                .doesNotContain("Amir Temur")
                .contains("not a JSON object");
    }

    private void answer(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    private ProviderCall call(Duration timeout) {
        return new ProviderCall("http://127.0.0.1:" + server.getAddress().getPort(),
                "test-credential-placeholder", "key-1", timeout);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
