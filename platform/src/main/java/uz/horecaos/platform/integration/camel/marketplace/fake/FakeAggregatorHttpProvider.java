package uz.horecaos.platform.integration.camel.marketplace.fake;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A local stand-in for an aggregator's availability API (ADR 0007, ADR 0141), so a stop can be walked
 * from the console to a "partner" and the stale alert and the Phase 3 dry run exercised without a real
 * aggregator.
 *
 * <p><strong>What it is, honestly.</strong> It answers the one shape {@link
 * FakeAggregatorAvailabilityAdapter} sends, {@code PUT /v1/items/{id}/availability}, on a real socket,
 * at the URL an {@code integration.provider_environments.base_url} row points {@link
 * uz.horecaos.platform.integration.camel.common.ProviderHttpClient} at. A stop lifted in the console
 * therefore runs the real reconciler, the real Camel route, the real gateway and the real ADR 0028
 * secret resolution, and ends on this class's socket instead of a vendor's. What it does not exercise
 * is any real aggregator: there is no real wire format here, because no aggregator's availability API is
 * known to this build (ADR 0141 open input 1, {@code docs/runbooks/external-connections/
 * w5-catalog-stops-fiscal.md}). The path, the body and the answers are this class's own invention and
 * are the one constant to replace when a real adapter arrives.
 *
 * <p><strong>Deliberately framework-free.</strong> No Spring annotation appears on this class so a test
 * can {@code new} one directly; {@link FakeAggregatorConfiguration} is the {@code local}-profile-only glue
 * that starts one with {@code make run}.
 *
 * <p><strong>Scenarios carry no switch in production code</strong> (ADR 0007's rule): a scenario is
 * selected by the item id a request names, which is ordinary {@code MENU_ITEM} mapping data a fixture
 * controls, plus one whole-partner switch ({@link #setOutage}) for "the partner is down", the situation
 * the stale alert and the dry run exist for.
 *
 * <p>State is in memory and per-instance, and nothing here is tenant-scoped because nothing a fake
 * aggregator holds is anybody's data: an item id, a boolean and a sequence number.
 */
public final class FakeAggregatorHttpProvider {

    private static final Logger log = LoggerFactory.getLogger(FakeAggregatorHttpProvider.class);

    /** Every scenario item id starts with this, so a fixture reads as one at a glance. */
    public static final String SCENARIO_PREFIX = "fake-scenario-";

    /** Never answers within any caller's deadline; the connection is eventually dropped unanswered. */
    public static final String SCENARIO_TIMEOUT = SCENARIO_PREFIX + "timeout";

    /** Answers HTTP 500, on every call. */
    public static final String SCENARIO_HTTP_500 = SCENARIO_PREFIX + "http-500";

    /**
     * Answers 404 with {@code {"error": "item_not_found"}}, which the adapter reads as "this partner does not
     * know the item". A JSON {@code error} field because that is the one shape the platform's HTTP client keeps
     * of a failure body (it never passes a body through whole, ADR 0029).
     */
    public static final String SCENARIO_UNKNOWN_ITEM = SCENARIO_PREFIX + "unknown-item";

    /**
     * Records the change and then drops the connection before any response is written: the partner
     * acted and the answer never arrived, which the platform must read as unknown, never as a licence to
     * believe the partner is in sync.
     */
    public static final String SCENARIO_ACCEPTED_THEN_LOST = SCENARIO_PREFIX + "accepted-then-lost";

    /** The path prefix of the aggregator's own API. */
    public static final String ITEMS_PATH = "/v1/items/";

    private static final String CONTROL_PATH = "/_fake/";

    /** Comfortably longer than every deadline the platform imposes on a marketplace call (15 seconds). */
    private static final Duration TIMEOUT_SCENARIO_DELAY = Duration.ofSeconds(45);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock;
    private final @Nullable String expectedSecret;

    private final Map<String, ItemState> items = new ConcurrentHashMap<>();
    private final List<Push> pushes = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean outage = new AtomicBoolean();

    private @Nullable HttpServer server;

    /** What the fake currently holds for one item, as a console-side tester would read it off the portal. */
    public record ItemState(boolean available, long sequence, Instant receivedAt) {}

    /** One accepted {@code PUT}, in arrival order. */
    public record Push(String itemId, boolean available, long sequence, Instant receivedAt) {}

    public FakeAggregatorHttpProvider() {
        this(Clock.systemUTC(), null);
    }

    /**
     * @param expectedSecret the bearer token every request is checked against. {@code null} skips the
     *                       check, for a caller that only needs the wire shape and not the credential path
     */
    public FakeAggregatorHttpProvider(Clock clock, @Nullable String expectedSecret) {
        this.clock = clock;
        this.expectedSecret = expectedSecret;
    }

    /** Starts on an ephemeral port and returns it. */
    public synchronized int start() {
        return start(0);
    }

    public synchronized int start(int port) {
        if (server != null) {
            throw new IllegalStateException(
                    "Already started on port " + server.getAddress().getPort());
        }
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not bind the fake aggregator", failure);
        }
        // Daemon threads: a scenario that never answers must never be the reason a test JVM, or
        // `make run`, fails to exit.
        server.setExecutor(Executors.newCachedThreadPool(daemonThreadFactory()));
        server.createContext("/", this::dispatch);
        server.start();
        log.info(
                "Fake aggregator listening on http://localhost:{}",
                server.getAddress().getPort());
        return server.getAddress().getPort();
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public synchronized int port() {
        if (server == null) {
            throw new IllegalStateException("Not started");
        }
        return server.getAddress().getPort();
    }

    /** While true every call of the aggregator's own API answers 503: "the partner is down". */
    public void setOutage(boolean down) {
        outage.set(down);
    }

    public boolean outage() {
        return outage.get();
    }

    /** What the fake holds for {@code itemId}, or null when no push ever named it. */
    public @Nullable ItemState itemState(String itemId) {
        return items.get(itemId);
    }

    /** Every accepted push, oldest first. */
    public List<Push> pushes() {
        synchronized (pushes) {
            return List.copyOf(pushes);
        }
    }

    // ------------------------------------------------------------- dispatch

    private void dispatch(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (RuntimeException unexpected) {
            log.warn(
                    "Fake aggregator failed to answer {} {}",
                    exchange.getRequestMethod(),
                    exchange.getRequestURI(),
                    unexpected);
            respondJson(exchange, 500, Map.of("error", "fake aggregator fault"));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        if (path.startsWith(CONTROL_PATH)) {
            control(exchange, method, path);
            return;
        }
        if (!authorized(exchange)) {
            respondJson(exchange, 401, Map.of("error", "invalid bearer token"));
            return;
        }
        if (outage.get()) {
            respondJson(exchange, 503, Map.of("error", "the fake aggregator is in an outage"));
            return;
        }
        if (path.startsWith(ITEMS_PATH) && path.endsWith("/availability")) {
            String itemId = decode(path.substring(ITEMS_PATH.length(), path.length() - "/availability".length()));
            if ("PUT".equals(method)) {
                setAvailability(exchange, itemId);
                return;
            }
            if ("GET".equals(method)) {
                readAvailability(exchange, itemId);
                return;
            }
        }
        respondJson(exchange, 404, Map.of("error", "no such endpoint on the fake aggregator"));
    }

    // ------------------------------------------------------------- endpoints

    private void setAvailability(HttpExchange exchange, String itemId) throws IOException {
        Map<String, Object> body = readJson(exchange);

        if (itemId.equals(SCENARIO_HTTP_500)) {
            respondJson(exchange, 500, Map.of("error", "scenario http-500"));
            return;
        }
        if (itemId.equals(SCENARIO_UNKNOWN_ITEM)) {
            respondJson(exchange, 404, Map.of("error", "item_not_found"));
            return;
        }
        if (itemId.equals(SCENARIO_TIMEOUT)) {
            sleepPastEveryDeadline();
            dropConnection(exchange);
            return;
        }
        if (!(body.get("available") instanceof Boolean available) || !(body.get("sequence") instanceof Number seq)) {
            respondJson(exchange, 400, Map.of("error", "available (boolean) and sequence (number) are required"));
            return;
        }

        Instant now = clock.instant();
        long sequence = seq.longValue();
        // A state-set keyed (item, sequence): a stale sequence is acknowledged and changes nothing, which
        // is what makes a resend harmless and a late duplicate unable to undo a newer value.
        items.merge(
                itemId,
                new ItemState(available, sequence, now),
                (held, incoming) -> incoming.sequence() >= held.sequence() ? incoming : held);
        pushes.add(new Push(itemId, available, sequence, now));

        if (itemId.equals(SCENARIO_ACCEPTED_THEN_LOST)) {
            dropConnection(exchange);
            return;
        }
        ItemState held = Objects.requireNonNull(items.get(itemId), "merged above");
        respondJson(
                exchange,
                200,
                orderedMap("itemId", itemId, "available", held.available(), "sequence", held.sequence()));
    }

    private void readAvailability(HttpExchange exchange, String itemId) throws IOException {
        ItemState held = items.get(itemId);
        if (held == null) {
            respondJson(exchange, 404, Map.of("error", "item_not_found"));
            return;
        }
        respondJson(
                exchange,
                200,
                orderedMap("itemId", itemId, "available", held.available(), "sequence", held.sequence()));
    }

    /**
     * The developer's window onto the fake: {@code GET /_fake/state} reads what it holds, {@code POST
     * /_fake/outage?on=true|false} starts and ends "the partner is down". Not authenticated, because it
     * is not the aggregator's API and exists only on a laptop.
     */
    private void control(HttpExchange exchange, String method, String path) throws IOException {
        if ("GET".equals(method) && path.equals(CONTROL_PATH + "state")) {
            Map<String, Object> held = new LinkedHashMap<>();
            new java.util.TreeMap<>(items)
                    .forEach((id, state) -> held.put(
                            id,
                            orderedMap(
                                    "available",
                                    state.available(),
                                    "sequence",
                                    state.sequence(),
                                    "receivedAt",
                                    state.receivedAt().toString())));
            respondJson(exchange, 200, orderedMap("outage", outage.get(), "pushes", pushes.size(), "items", held));
            return;
        }
        if ("POST".equals(method) && path.equals(CONTROL_PATH + "outage")) {
            String query = exchange.getRequestURI().getQuery();
            boolean on = query == null || !query.contains("on=false");
            outage.set(on);
            respondJson(exchange, 200, Map.of("outage", on));
            return;
        }
        respondJson(exchange, 404, Map.of("error", "no such control endpoint"));
    }

    // ------------------------------------------------------------- wire helpers

    private boolean authorized(HttpExchange exchange) {
        if (expectedSecret == null) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        return header != null && header.equals("Bearer " + expectedSecret);
    }

    private Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return Map.of();
        }
        try {
            return json.readValue(bytes, MAP_TYPE);
        } catch (RuntimeException unreadable) {
            return Map.of();
        }
    }

    private void respondJson(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void sleepPastEveryDeadline() {
        try {
            Thread.sleep(TIMEOUT_SCENARIO_DELAY);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** No response is written; the client experiences exactly what a lost reply looks like. */
    private void dropConnection(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        exchange.close();
    }

    private static String decode(String segment) {
        return URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "fake-aggregator");
            thread.setDaemon(true);
            return thread;
        };
    }
}
