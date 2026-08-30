package uz.qoida.platform.integration.camel.delivery;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A courier partner fake that remembers exactly what was sent to it.
 *
 * <p>{@code ControlledFakeProvider} proves how the classifier behaves when a
 * provider misbehaves. This one answers the other half: whether the adapter put
 * the right fields on the wire. That distinction matters because the expensive
 * Noor and Yandex mistakes — a missing {@code product_paid}, a stale
 * {@code version}, coordinates in the wrong order — all produce a perfectly
 * successful HTTP call and a wrong real-world outcome.
 *
 * <p>Test sources only, so no scenario switch can reach production (ADR 0007).
 */
final class RecordingPartnerServer implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Reply> replies = new LinkedHashMap<>();
    private final Map<String, Long> delays = new LinkedHashMap<>();

    private RecordingPartnerServer(HttpServer server) {
        this.server = server;
    }

    static RecordingPartnerServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        RecordingPartnerServer fake = new RecordingPartnerServer(server);
        server.createContext("/", fake::handle);
        server.start();
        return fake;
    }

    /** Scripts a response. Keyed by path only; the query string is recorded, not matched. */
    RecordingPartnerServer reply(String path, int status, String json) {
        replies.put(path, new Reply(status, json));
        return this;
    }

    /**
     * Accepts the request, then goes quiet past the caller's timeout.
     *
     * <p>This is the only way to produce the case that matters: the provider has
     * the request and may act on it, and the caller will never learn whether it
     * did. A short client timeout alone cannot produce it — that fails at
     * connect, which is a different and safely retryable thing.
     */
    RecordingPartnerServer stallAfterReceiving(String path, long millis) {
        delays.put(path, millis);
        return this;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    Call lastCall() {
        return calls.getLast();
    }

    Call callTo(String path) {
        return calls.stream()
                .filter(call -> call.path().equals(path))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError(
                        "No call to " + path + "; saw " + calls.stream().map(Call::path).toList()));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        byte[] raw = exchange.getRequestBody().readAllBytes();

        Map<String, Object> body = raw.length == 0 ? Map.of() : JSON.readValue(raw, MAP);
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.getFirst());
            }
        });

        calls.add(new Call(exchange.getRequestMethod(), path, query, headers, body));

        Long delay = delays.get(path);
        if (delay != null) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        Reply reply = replies.getOrDefault(path, new Reply(200, "{}"));
        byte[] bytes = reply.json().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    record Call(String method, String path, String query, Map<String, String> headers,
            Map<String, Object> body) {

        /** Reads a nested field, e.g. {@code field("delivery", "product_paid")}. */
        @SuppressWarnings("unchecked")
        Object field(String... path) {
            Object current = body;
            for (String segment : path) {
                if (!(current instanceof Map<?, ?> map)) {
                    return null;
                }
                current = ((Map<String, Object>) map).get(segment);
            }
            return current;
        }

        @SuppressWarnings("unchecked")
        List<Object> list(String key) {
            Object value = body.get(key);
            return value instanceof List<?> items ? new ArrayList<>((List<Object>) items) : List.of();
        }
    }

    private record Reply(int status, String json) { }
}
