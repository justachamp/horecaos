package uz.horecaos.platform.integration.camel.geo;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * A Yandex-shaped fake that remembers exactly what was sent to it (ADR 0007's controlled-fake
 * rule, ADR 0145's testing section).
 *
 * <p>A real socket rather than a stubbed client, because the things worth proving about the
 * adapter are about the bytes: which parameters are sent, that the region's box goes as a
 * <em>bias</em> ({@code rspn=0}), and what happens when the answer is lost, late or the wrong
 * shape. One server stands in for both hosts; the geocoder is mounted under {@code /1.x} and
 * suggest under {@code /v1}, as the approved environment rows have them.
 *
 * <p>Answers are scripted per path and queued; a path with nothing scripted answers {@code 404},
 * which a test would notice, rather than a plausible default that could hide a wrong URL.
 * Replies are recorded fixtures from {@code src/test/resources/geo/yandex}. Test sources only, so
 * no scripted answer can reach production.
 */
final class RecordingGeoProvider implements AutoCloseable {

    static final String GEOCODER_PATH = "/1.x/";
    static final String SUGGEST_PATH = "/v1/suggest";

    private final HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Deque<Reply>> replies = new LinkedHashMap<>();
    private final Map<String, Long> stalls = new LinkedHashMap<>();

    private RecordingGeoProvider(HttpServer server) {
        this.server = server;
    }

    static RecordingGeoProvider start() throws IOException {
        keepTheServersOwnLogOutOfTheApplicationLog();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        // A pool, not the serial default: a stalled handler must not also stall the next call.
        server.setExecutor(Executors.newCachedThreadPool());
        RecordingGeoProvider fake = new RecordingGeoProvider(server);
        server.createContext("/", fake::handle);
        server.start();
        return fake;
    }

    /**
     * The JDK server writes one DEBUG line per request it receives and one per reply it sends
     * ({@code Exchange request line: GET /1.x/?apikey=...&geocode=...}, then the same path with
     * the status) through {@code System.Logger}, which in a JVM that has started a Spring context
     * is bridged into logback. Those lines are the <em>provider's</em> access log: this class
     * stands in for Yandex, and what Yandex receives necessarily includes the key and the typed
     * address, because that is the request. They are not output of the code under test, and
     * {@code GeocodePortContractTests#noAddressOrKeyReachesALog} -- which captures the whole JVM
     * at {@code ALL} to prove that code logs neither -- failed on them in CI (2026-10-09) while
     * passing on a machine where nothing had bridged the JDK's logging yet.
     *
     * <p>Pinned on the logback logger, not on the JUL one: the bridge's level propagator resets
     * every JUL level when it starts, and so would a pin made there. Re-applied on every start
     * because a logback reconfiguration resets levels too. The server's WARN and above still
     * arrive, so a fixture that genuinely breaks is still heard.
     */
    private static void keepTheServersOwnLogOutOfTheApplicationLog() {
        if (LoggerFactory.getLogger("com.sun.net.httpserver") instanceof Logger jdkServerLog) {
            jdkServerLog.setLevel(Level.WARN);
        }
    }

    /** Scripts one answer from a fixture file. Queued, so a path can answer differently next time. */
    RecordingGeoProvider replyFixture(String path, String fixture) {
        return reply(path, 200, fixture(fixture));
    }

    RecordingGeoProvider reply(String path, int status, String body) {
        replies.computeIfAbsent(path, key -> new ArrayDeque<>()).add(new Reply(status, body));
        return this;
    }

    /** Takes the request, then goes quiet past the caller's deadline: the accepted-then-lost case. */
    RecordingGeoProvider stallAfterReceiving(String path, long millis) {
        stalls.put(path, millis);
        return this;
    }

    String geocoderBase() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/1.x";
    }

    String suggestBase() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    long callsTo(String path) {
        return calls.stream().filter(call -> call.path().equals(path)).count();
    }

    Call lastCallTo(String path) {
        return calls.stream()
                .filter(call -> call.path().equals(path))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("No call to " + path + "; saw "
                        + calls.stream().map(Call::path).toList()));
    }

    GeoEndpoints endpoints() {
        return code -> switch (code) {
            case "yandex_geocoder_production" -> java.util.Optional.of(geocoderBase());
            case "yandex_suggest_production" -> java.util.Optional.of(suggestBase());
            default -> java.util.Optional.empty();
        };
    }

    static String fixture(String name) {
        try (InputStream in = RecordingGeoProvider.class.getResourceAsStream("/geo/yandex/" + name)) {
            if (in == null) {
                throw new AssertionError("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("Could not read fixture " + name, failure);
        }
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
        calls.add(new Call(
                exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery()));
        exchange.getRequestBody().readAllBytes();

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
                ? new Reply(404, "{\"message\":\"nothing scripted for this path\"}")
                : (queued.size() == 1 ? queued.peek() : queued.poll());

        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** @param rawQuery the query exactly as it arrived, still percent-encoded */
    record Call(String method, String path, @Nullable String rawQuery) {

        /** One decoded parameter, so a test asserts on meaning and not on encoding. */
        @Nullable
        String param(String name) {
            if (rawQuery == null) {
                return null;
            }
            for (String pair : rawQuery.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(name)) {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            }
            return null;
        }
    }

    private record Reply(int status, String body) {}
}
