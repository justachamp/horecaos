package uz.horecaos.platform.integration.camel.einvoicing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * An e-invoicing operator's HTTP API as a fake that remembers exactly what was sent to it.
 *
 * <p>The adapters' unit tests script the answers the gateway would hand them; this one puts the
 * whole stack on a real socket -- adapter, route, gateway, {@code ProviderHttpClient} -- so the
 * things only HTTP shows are shown: that a form body is form-encoded, that a lookup's 404 reaches
 * the adapter as an answer, and that a request that was written and never answered is made
 * exactly once.
 *
 * <p>Test sources only, so no scenario switch can reach production (ADR 0007).
 */
public final class FakeOperatorServer implements AutoCloseable {

    private final HttpServer server;
    private final List<Received> requests = new CopyOnWriteArrayList<>();
    private final Map<String, List<Reply>> replies = new LinkedHashMap<>();
    private final Map<String, Long> stalls = new LinkedHashMap<>();

    private FakeOperatorServer(HttpServer server) {
        this.server = server;
    }

    public static FakeOperatorServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], 0), 0);
        FakeOperatorServer fake = new FakeOperatorServer(server);
        server.createContext("/", fake::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        return fake;
    }

    /** Scripts the next answer to {@code METHOD path}; the last one scripted repeats. */
    public synchronized FakeOperatorServer reply(String method, String path, int status, String json) {
        replies.computeIfAbsent(method + " " + path, key -> new ArrayList<>()).add(new Reply(status, json));
        return this;
    }

    /** Takes the request, then goes quiet past the caller's deadline: the answer is lost, the effect may not be. */
    public synchronized FakeOperatorServer stall(String method, String path, long millis) {
        stalls.put(method + " " + path, millis);
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Received> requests() {
        return List.copyOf(requests);
    }

    public long count(String method, String path) {
        return requests.stream()
                .filter(received ->
                        received.method().equals(method) && received.path().equals(path))
                .count();
    }

    public Received last(String method, String path) {
        return requests.stream()
                .filter(received ->
                        received.method().equals(method) && received.path().equals(path))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("No " + method + " " + path + "; saw "
                        + requests.stream()
                                .map(r -> r.method() + " " + r.path())
                                .toList()));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String key = exchange.getRequestMethod() + " " + path;
        byte[] raw = exchange.getRequestBody().readAllBytes();
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name.toLowerCase(java.util.Locale.ROOT), values.getFirst());
            }
        });
        requests.add(new Received(
                exchange.getRequestMethod(),
                path,
                exchange.getRequestURI().getRawQuery(),
                headers,
                new String(raw, StandardCharsets.UTF_8)));

        Long stall;
        Reply reply;
        synchronized (this) {
            stall = stalls.get(key);
            List<Reply> scripted = replies.get(key);
            reply = scripted == null || scripted.isEmpty()
                    ? new Reply(404, "{\"error\":\"no such route\"}")
                    : scripted.size() > 1 ? scripted.remove(0) : scripted.getFirst();
        }
        if (stall != null) {
            try {
                Thread.sleep(stall);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = reply.json().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public record Received(String method, String path, String query, Map<String, String> headers, String body) {}

    private record Reply(int status, String json) {}
}
