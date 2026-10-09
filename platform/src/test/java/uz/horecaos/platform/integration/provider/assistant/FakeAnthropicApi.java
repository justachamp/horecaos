package uz.horecaos.platform.integration.provider.assistant;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A local stand-in for the Anthropic Messages API, in the {@code
 * FakeTelegramBotApi}/{@code FakeSmsGateway} genre (ADR 0007): a real socket
 * reached through the real {@code ProviderHttpClient}, so a test exercises the
 * adapter, the shared HTTP client and the ADR 0007 failure classifier together
 * rather than a mock of one of them.
 *
 * <p>Scenario selection is explicit and queued -- the next response -- and lives
 * only in test sources; no production adapter has a scenario switch. It records
 * every request's headers and parsed body so a test can assert the wire shape.
 */
final class FakeAnthropicApi implements AutoCloseable {

    record Received(Map<String, String> headers, Map<String, Object> body, String rawBody) {}

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final HttpServer server;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<Received> requests = new CopyOnWriteArrayList<>();
    private final Deque<Scripted> script = new ArrayDeque<>();
    private volatile @Nullable String acceptedKey;

    private record Scripted(int status, String body) {}

    private FakeAnthropicApi(HttpServer server) {
        this.server = server;
    }

    static FakeAnthropicApi start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeAnthropicApi fake = new FakeAnthropicApi(server);
        server.createContext("/", fake::handle);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        return fake;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    List<Received> requests() {
        return List.copyOf(requests);
    }

    Received last() {
        return requests.getLast();
    }

    /** Only this key authenticates; every other answers 401, which is how a rotated key looks to the adapter. */
    FakeAnthropicApi acceptOnlyKey(String key) {
        this.acceptedKey = key;
        return this;
    }

    /** The next response: the model replied with this structured document. */
    FakeAnthropicApi replyWith(
            String reply, List<String> citations, boolean refusal, long inputTokens, long outputTokens) {
        Map<String, Object> document = new java.util.LinkedHashMap<>();
        document.put("reply", reply);
        document.put("citations", citations);
        document.put("refusal", refusal);
        document.put("refusal_code", refusal ? "NO_ANSWER" : "");
        return respond(200, message("end_turn", mapper.writeValueAsString(document), inputTokens, outputTokens));
    }

    FakeAnthropicApi respond(int status, String body) {
        script.add(new Scripted(status, body));
        return this;
    }

    /** A 2xx whose text is not the JSON document the schema asks for. */
    FakeAnthropicApi replyWithText(String text) {
        return respond(200, message("end_turn", text, 10, 5));
    }

    FakeAnthropicApi stopReason(String stopReason) {
        return respond(200, message(stopReason, "{}", 10, 5));
    }

    private String message(String stopReason, String text, long inputTokens, long outputTokens) {
        Map<String, Object> message = new java.util.LinkedHashMap<>();
        message.put("id", "msg_fake");
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", "claude-sonnet-5-5");
        message.put("stop_reason", stopReason);
        message.put("content", List.of(Map.of("type", "text", "text", text)));
        message.put("usage", Map.of("input_tokens", inputTokens, "output_tokens", outputTokens));
        return mapper.writeValueAsString(message);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        String rawBody = new String(raw, StandardCharsets.UTF_8);
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.getFirst()));
        Map<String, Object> body = rawBody.isBlank() ? Map.of() : mapper.readValue(rawBody, MAP);
        requests.add(new Received(headers, body, rawBody));

        Scripted next;
        String required = acceptedKey;
        if (required != null && !required.equals(headers.get("x-api-key"))) {
            next = new Scripted(
                    401,
                    "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");
        } else {
            next = script.isEmpty()
                    ? new Scripted(
                            500,
                            "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"nothing scripted\"}}")
                    : script.poll();
        }
        byte[] response = next.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(next.status(), response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
