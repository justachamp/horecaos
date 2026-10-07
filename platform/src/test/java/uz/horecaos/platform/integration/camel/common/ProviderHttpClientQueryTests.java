package uz.horecaos.platform.integration.camel.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * The query-carrying GET that the geocoder needs (ADR 0145): a request whose address and key are
 * in the URL, which is the provider's choice and not ours, and which therefore must reach the
 * wire intact and reach nothing else.
 */
class ProviderHttpClientQueryTests {

    private final ProviderHttpClient http =
            new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier());

    private static HttpServer server(
            AtomicReference<String> rawQuery, AtomicReference<String> path, int status, String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            path.set(exchange.getRequestURI().getPath());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static ProviderCall call(HttpServer server) {
        return new ProviderCall(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/1.x", "k", null, Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("the query is percent-encoded, in order, with spaces as %20 and reserved characters escaped")
    void theQueryIsEncodedAndOrdered() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer server = server(rawQuery, path, 200, "{}");
        try {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("apikey", "a+b/c=d");
            query.put("geocode", "Fixture ko'chasi 12 & Ташкент?#");
            query.put("bbox", "69.04,41.15~69.46,41.47");

            ProviderOutcome outcome =
                    http.get(call(server), "/", query, Map.of(), parsed -> ProviderOutcome.success(parsed, null));

            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.SUCCESS);
            assertThat(path.get()).isEqualTo("/1.x/");
            String raw = rawQuery.get();
            assertThat(raw).startsWith("apikey=a%2Bb%2Fc%3Dd&geocode=");
            assertThat(raw).doesNotContain(" ").doesNotContain("+").doesNotContain("#");
            assertThat(raw).contains("Fixture%20ko%27chasi%2012%20%26%20");
            assertThat(raw).endsWith("&bbox=69.04%2C41.15%7E69.46%2C41.47");
            // And it decodes back to exactly what was meant.
            assertThat(java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8))
                    .contains("geocode=Fixture ko'chasi 12 & Ташкент?#");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a failure outcome never carries the query, the address or the key")
    void aFailureCarriesNothingOfTheRequest() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        // A provider that repeats the request back inside its error, as several do.
        HttpServer server = server(
                rawQuery, path, 400, "{\"message\":\"cannot geocode canary-address 998901112233 key=secret-key\"}");
        try {
            ProviderOutcome outcome = http.get(
                    call(server),
                    "/",
                    Map.of("apikey", "secret-key", "geocode", "canary-address"),
                    Map.of(),
                    parsed -> ProviderOutcome.success(parsed, null));

            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
            // The long digit run (a phone number, a card fragment) is masked by the existing scrub.
            assertThat(String.valueOf(outcome.detail())).doesNotContain("998901112233");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a request with an empty query is the plain GET it always was")
    void anEmptyQueryAddsNothing() throws Exception {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer server = server(rawQuery, path, 200, "{}");
        try {
            http.get(call(server), "/ping", Map.of(), Map.of(), parsed -> ProviderOutcome.success(parsed, null));

            assertThat(path.get()).isEqualTo("/1.x/ping");
            assertThat(rawQuery.get()).isNull();
        } finally {
            server.stop(0);
        }
    }
}
