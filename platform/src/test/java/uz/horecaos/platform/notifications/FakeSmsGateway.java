package uz.horecaos.platform.notifications;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * An SMS gateway that fails on demand (ADR 0007, ADR 0020).
 *
 * <p>The same shape as {@code ControlledFakeProvider} in the delivery suite, and
 * for the same reason: a real gateway's sandbox cannot be asked to time out, or to
 * accept a message and then lose the reply. Those are precisely the cases that
 * produce a second confirmation to a customer, and they are unreachable in a test
 * without something like this.
 *
 * <p>What this one adds is a status endpoint, because ADR 0020's uncertainty path
 * is a query rather than a repeat and cannot be exercised without one.
 *
 * <p>Scenario selection is by a header no production adapter sends, and this class
 * lives in test sources only. ADR 0007 forbids a scenario switch in production
 * provider code, and the safest way to honour that is for the switch not to ship.
 */
public final class FakeSmsGateway implements AutoCloseable {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final HttpServer server;
    private final List<String> sentTo = new CopyOnWriteArrayList<>();
    private final Map<String, String> accepted = new ConcurrentHashMap<>();
    private final AtomicInteger messagesSent = new AtomicInteger();
    private final AtomicReference<Scenario> scenario = new AtomicReference<>(Scenario.SUCCESS);

    private FakeSmsGateway(HttpServer server) {
        this.server = server;
    }

    public static FakeSmsGateway start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeSmsGateway gateway = new FakeSmsGateway(server);
        server.createContext("/provider/commands", gateway::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        return gateway;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void behaveAs(Scenario next) {
        scenario.set(next);
    }

    /** How many messages the gateway actually sent, as opposed to was asked to. */
    public int messagesSent() {
        return messagesSent.get();
    }

    /** The recipients, so a test can assert one message went to one number. */
    public List<String> sentTo() {
        return List.copyOf(sentTo);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        boolean isStatusQuery = "GET".equals(exchange.getRequestMethod());
        if (isStatusQuery) {
            handleStatusQuery(exchange);
            return;
        }

        String idempotencyKey = header(exchange, IDEMPOTENCY_HEADER);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        switch (scenario.get()) {
            case SUCCESS -> {
                String reference = acceptOnce(idempotencyKey, body);
                respond(exchange, 200,
                        """
                        {"status":"ACCEPTED","externalReference":"%s"}""".formatted(reference));
            }
            case RATE_LIMITED -> {
                exchange.getResponseHeaders().add("Retry-After", "7");
                respond(exchange, 429, """
                        {"error":"rate limited"}""");
            }
            case PERMANENT_REJECTION -> respond(exchange, 400, """
                    {"error":"invalid recipient"}""");
            case SERVER_ERROR -> respond(exchange, 500, """
                    {"error":"internal"}""");
            case ACCEPTED_THEN_TIMEOUT -> {
                // The dangerous one: the message goes out and the caller never
                // learns that it did. Recorded before the reply is dropped, so a
                // test can assert the reconcile found it rather than resent it.
                acceptOnce(idempotencyKey, body);
                exchange.close();
            }
        }
    }

    /**
     * Answers what happened to one key.
     *
     * <p>{@code NOT_FOUND} in the body rather than a 404, because that is the one
     * answer that licenses a second send, and it must not be confusable with the
     * gateway rejecting the query itself.
     */
    private void handleStatusQuery(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestURI().getPath()
                .substring("/provider/commands/".length());
        String reference = accepted.get(key);

        if (reference == null) {
            respond(exchange, 200, """
                    {"status":"NOT_FOUND"}""");
            return;
        }
        respond(exchange, 200, """
                {"status":"DELIVERED","externalReference":"%s"}""".formatted(reference));
    }

    /**
     * Honours the idempotency key.
     *
     * <p>A repeated key returns the first reference and sends nothing further,
     * which is exactly the behaviour a retry path has to be tested against.
     */
    private String acceptOnce(String idempotencyKey, String requestBody) {
        if (idempotencyKey == null) {
            return newMessage(requestBody);
        }
        return accepted.computeIfAbsent(idempotencyKey, key -> newMessage(requestBody));
    }

    private String newMessage(String requestBody) {
        sentTo.add(recipientOf(requestBody));
        return "sms-" + messagesSent.incrementAndGet();
    }

    /** Good enough for a fake: the test only needs to know which number was texted. */
    private static String recipientOf(String requestBody) {
        int start = requestBody.indexOf("\"to\":\"");
        if (start < 0) {
            return "unknown";
        }
        int from = start + "\"to\":\"".length();
        int end = requestBody.indexOf('"', from);
        return end < 0 ? "unknown" : requestBody.substring(from, end);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String header(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    /** The failures a real gateway's sandbox cannot be asked to produce. */
    public enum Scenario {
        SUCCESS,
        RATE_LIMITED,
        PERMANENT_REJECTION,
        SERVER_ERROR,
        ACCEPTED_THEN_TIMEOUT
    }
}
