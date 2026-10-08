package uz.horecaos.platform.integration.camel.sms;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * An smsgw.vas.uz fake that remembers exactly what was sent to it.
 *
 * <p>A real socket rather than a stubbed client, because the two things most
 * worth proving about this adapter are both about the bytes: that {@code weight}
 * is absent from the body, and that a lost answer produces one {@code /send} and
 * one {@code /search} rather than two sends. A stub would prove neither — it
 * would prove that the stub was called.
 *
 * <p>The document names no sandbox and every example in it uses what looks like a
 * live account, so ADR 0007's controlled-fake rule is the only way to test this
 * provider at all.
 *
 * <p>Test sources only, so no scripted answer can reach production.
 */
public final class RecordingSmsGateway implements AutoCloseable {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<Reply>> replies = new LinkedHashMap<>();
    private final Map<String, Long> stalls = new LinkedHashMap<>();
    private final java.util.Set<String> drops = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private RecordingSmsGateway(HttpServer server) {
        this.server = server;
    }

    public static RecordingSmsGateway start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getAllByName("127.0.0.1")[0], 0), 0);
        // A pool rather than the default serial dispatcher. The stall tests hold
        // one handler open past the caller's deadline on purpose, and on the
        // default executor that would also stall the /search that resolves it —
        // turning a test about reconciliation into a test about the fake.
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        RecordingSmsGateway fake = new RecordingSmsGateway(server);
        server.createContext("/", fake::handle);
        server.start();
        return fake;
    }

    /** Scripts one answer. Queued, so a path can answer differently on a second call. */
    public RecordingSmsGateway reply(String path, String json) {
        return reply(path, 200, json);
    }

    public RecordingSmsGateway reply(String path, int status, String json) {
        replies.computeIfAbsent(path, key -> new ArrayDeque<>()).add(new Reply(status, json));
        return this;
    }

    /**
     * Takes the request, then goes quiet past the caller's deadline.
     *
     * <p>The only way to produce the case this adapter exists for: the gateway has
     * the message and may already have sent it, and we will never learn which.
     */
    public RecordingSmsGateway stallAfterReceiving(String path, long millis) {
        stalls.put(path, millis);
        return this;
    }

    /**
     * Takes the request and closes the connection without a word.
     *
     * <p>The cheap way to produce the dangerous case: the gateway has the message
     * and the caller learns nothing, with no deadline to wait out. Reversible, so a
     * test can heal the gateway and watch the reconciliation find what it already
     * holds.
     */
    public RecordingSmsGateway dropConnectionAfterReceiving(String path) {
        drops.add(path);
        return this;
    }

    public RecordingSmsGateway stopDropping(String path) {
        drops.remove(path);
        return this;
    }

    /** Forgets every scripted answer for a path, so the next one starts fresh. */
    public RecordingSmsGateway clearReplies(String path) {
        replies.remove(path);
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public long callsTo(String path) {
        return calls.stream().filter(call -> call.path().equals(path)).count();
    }

    public Call callTo(String path) {
        return calls.stream()
                .filter(call -> call.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No call to " + path + "; saw "
                        + calls.stream().map(Call::path).toList()));
    }

    @Override
    public void close() {
        server.stop(0);
        if (server.getExecutor() instanceof java.util.concurrent.ExecutorService pool) {
            pool.shutdownNow();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] raw = exchange.getRequestBody().readAllBytes();
        Map<String, Object> body = raw.length == 0 ? Map.of() : JSON.readValue(raw, MAP);
        calls.add(new Call(exchange.getRequestMethod(), path, body));

        if (drops.contains(path)) {
            exchange.close();
            return;
        }

        Long stall = stalls.get(path);
        if (stall != null) {
            try {
                Thread.sleep(stall);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        Deque<Reply> queued = replies.get(path);
        Reply reply = queued == null || queued.isEmpty()
                ? new Reply(200, "{\"status\":{\"code\":0,\"description\":\"success\"},\"id\":\"1\"}")
                : (queued.size() == 1 ? queued.peek() : queued.poll());

        byte[] bytes = reply.json().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    public record Call(String method, String path, Map<String, Object> body) {}

    private record Reply(int status, String json) {}
}
