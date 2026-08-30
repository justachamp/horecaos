package uz.horecaos.platform.integration.camel;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A provider that fails on demand (ADR 0007).
 *
 * <p>The reason this exists rather than a sandbox: a real provider's sandbox
 * cannot be asked to time out, reset a connection, or accept a request and then
 * lose the reply. Those are precisely the cases that break payments and courier
 * bookings, and they are unreachable in a test without something like this.
 *
 * <p>Scenario selection is by a header that no production adapter sends, and
 * this class lives in test sources only. ADR 0007 forbids a scenario switch in
 * production provider code, and the safest way to honour that is for the switch
 * not to ship at all.
 */
public final class ControlledFakeProvider implements AutoCloseable {

    /** Never sent by production code; the fake is the only thing that reads it. */
    public static final String SCENARIO_HEADER = "X-HorecaOS-Test-Scenario";
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final HttpServer server;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final Map<String, String> idempotentResponses = new ConcurrentHashMap<>();
    private final AtomicInteger sideEffects = new AtomicInteger();
    private volatile Scenario override;

    private ControlledFakeProvider(HttpServer server) {
        this.server = server;
    }

    public static ControlledFakeProvider start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ControlledFakeProvider provider = new ControlledFakeProvider(server);
        server.createContext("/provider/commands", provider::handle);
        server.createContext("/provider/status", provider::status);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        return provider;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * Makes every subsequent call take this scenario regardless of the header.
     *
     * <p>Needed because an event's payload is immutable — the inbox hashes it and
     * treats a changed payload as a contract violation — so a test that needs the
     * second attempt at one command to behave differently from the first cannot
     * say so in the command. Pass {@code null} to hand control back to the header.
     */
    public void forceScenario(Scenario scenario) {
        this.override = scenario;
    }

    /** How many times the provider actually did the thing, as opposed to was asked to. */
    public int sideEffectCount() {
        return sideEffects.get();
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /**
     * Reports whether a key was ever accepted, without accepting anything.
     *
     * <p>This is what reconciliation needs and what a retry must never be: it
     * answers "did you already act on this?" and creates no side effect either
     * way, so an uncertain outcome can be resolved rather than repeated.
     */
    private void status(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String key = query != null && query.startsWith("key=") ? query.substring(4) : null;
        String reference = key == null ? null : idempotentResponses.get(key);

        if (reference == null) {
            respond(exchange, 404, """
                    {"status":"NOT_FOUND"}""");
            return;
        }
        respond(exchange, 200, """
                {"status":"ACCEPTED","externalReference":"%s"}""".formatted(reference));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String scenario = override != null
                ? override.name()
                : header(exchange, SCENARIO_HEADER, Scenario.SUCCESS.name());
        String idempotencyKey = header(exchange, IDEMPOTENCY_HEADER, null);
        requests.add(new RecordedRequest(scenario, idempotencyKey));

        Scenario selected;
        try {
            selected = Scenario.valueOf(scenario);
        } catch (IllegalArgumentException unknown) {
            selected = Scenario.SUCCESS;
        }

        switch (selected) {
            case SUCCESS -> {
                // Honours the idempotency key: a repeated key returns the first
                // response and performs no second effect, which is what an
                // adapter's retry behaviour must be tested against.
                String reference = idempotencyKey == null
                        ? newReference()
                        : idempotentResponses.computeIfAbsent(idempotencyKey, key -> newReference());
                respond(exchange, 200, """
                        {"status":"ACCEPTED","externalReference":"%s"}""".formatted(reference));
            }
            case SLOW -> {
                sleep(2_000);
                respond(exchange, 200, """
                        {"status":"ACCEPTED","externalReference":"%s"}""".formatted(newReference()));
            }
            case RATE_LIMITED -> {
                exchange.getResponseHeaders().add("Retry-After", "7");
                respond(exchange, 429, """
                        {"error":"rate limited"}""");
            }
            case PERMANENT_REJECTION -> respond(exchange, 400, """
                    {"error":"invalid request"}""");
            case SERVER_ERROR -> respond(exchange, 500, """
                    {"error":"internal"}""");
            case CONNECTION_RESET -> {
                // Closes without a response, which is what a reset looks like to
                // a client that has already sent its request.
                exchange.close();
            }
            case ACCEPTED_THEN_TIMEOUT -> {
                // The dangerous one: the effect happens and the caller never
                // learns it did.
                String reference = idempotencyKey == null
                        ? newReference()
                        : idempotentResponses.computeIfAbsent(idempotencyKey, key -> newReference());
                sleep(3_000);
                exchange.close();
            }
        }
    }

    private String newReference() {
        sideEffects.incrementAndGet();
        return "ext-" + sideEffects.get();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String header(HttpExchange exchange, String name, String fallback) {
        List<String> values = exchange.getRequestHeaders().get(name);
        return values == null || values.isEmpty() ? fallback : values.getFirst();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** The failures a real sandbox cannot be asked to produce. */
    public enum Scenario {
        SUCCESS,
        SLOW,
        RATE_LIMITED,
        PERMANENT_REJECTION,
        SERVER_ERROR,
        CONNECTION_RESET,
        ACCEPTED_THEN_TIMEOUT
    }

    public record RecordedRequest(String scenario, String idempotencyKey) { }
}
