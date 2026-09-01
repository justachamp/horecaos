package uz.horecaos.platform.integration.provider.telegramgateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A local stand-in for {@code https://gateway.telegram.org} (ADR 0063), in the
 * {@code FakeSmsGateway}/{@code FakeTelegramBotApi} genre: a real socket, reached
 * through the real {@link TelegramGatewayClient}, so a test exercises the whole
 * path — token header, credential-refresh-on-401, and the delivery-policy seam
 * that decides Gateway-vs-SMS from what this fake answers.
 *
 * <p>Scenario selection is by explicit setters, and lives only in test sources —
 * no production adapter code has a scenario switch, per ADR 0007.
 */
public final class FakeTelegramGateway implements AutoCloseable {

    private final HttpServer server;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final List<String> phonesSentTo = new CopyOnWriteArrayList<>();
    private final List<String> authorizationHeadersReceived = new CopyOnWriteArrayList<>();
    private final AtomicInteger requestsSent = new AtomicInteger();
    private final AtomicReference<Scenario> scenario = new AtomicReference<>(Scenario.SUCCESS);
    private final Map<String, Integer> refusedPhoneSuffixes = new ConcurrentHashMap<>();
    private volatile double requestCost = 0.03;
    private volatile String expectedToken = "";

    private FakeTelegramGateway(HttpServer server) {
        this.server = server;
    }

    public static FakeTelegramGateway start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        FakeTelegramGateway fake = new FakeTelegramGateway(server);
        server.createContext("/sendVerificationMessage", fake::handleSend);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        return fake;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    public void behaveAs(Scenario next) {
        scenario.set(next);
    }

    /** The token this fake expects to see (a test's own knowledge of what the secret manager resolves to). */
    public void expectToken(String token) {
        this.expectedToken = token;
    }

    public void setRequestCostUsd(double cost) {
        this.requestCost = cost;
    }

    /** {@code sendVerificationMessage} answers a business refusal for a phone ending in this suffix. */
    public void refusePhonesEndingIn(String suffix) {
        refusedPhoneSuffixes.put(suffix, 400);
    }

    public List<String> phonesSentTo() {
        return List.copyOf(phonesSentTo);
    }

    public List<String> authorizationHeadersReceived() {
        return List.copyOf(authorizationHeadersReceived);
    }

    public int requestsSent() {
        return requestsSent.get();
    }

    private void handleSend(HttpExchange exchange) throws IOException {
        requestsSent.incrementAndGet();
        String authorization = header(exchange, "Authorization");
        authorizationHeadersReceived.add(authorization == null ? "" : authorization);

        byte[] raw = exchange.getRequestBody().readAllBytes();
        Map<String, Object> body =
                raw.length == 0 ? Map.of() : jsonMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        String phone = String.valueOf(body.get("phone_number"));
        phonesSentTo.add(phone);

        if (!expectedToken.isBlank() && !("Bearer " + expectedToken).equals(authorization)) {
            respond(exchange, 401, """
                    {"ok":false,"error":"Unauthorized"}""");
            return;
        }

        for (Map.Entry<String, Integer> refusal : refusedPhoneSuffixes.entrySet()) {
            if (phone.endsWith(refusal.getKey())) {
                respond(exchange, refusal.getValue(), """
                        {"ok":false,"error":"PHONE_NUMBER_INVALID"}""");
                return;
            }
        }

        // AtomicReference#get is nullable by type regardless of what has ever
        // been stored; this one is constructed with SUCCESS and every behaveAs
        // call requires a non-null Scenario, so it is never actually empty.
        switch (java.util.Objects.requireNonNull(scenario.get())) {
            case SUCCESS ->
                respond(exchange, 200, String.format(Locale.ROOT, """
                            {"ok":true,"result":{"request_id":"tg-req-%d","phone_number":"%s",\
                            "request_cost":%s,"remaining_balance":42.0}}""", requestsSent.get(), phone, requestCost));
            case NO_TELEGRAM_ACCOUNT -> respond(exchange, 400, """
                    {"ok":false,"error":"PHONE_NUMBER_INVALID"}""");
            case RATE_LIMITED -> {
                exchange.getResponseHeaders().add("Retry-After", "5");
                respond(exchange, 429, """
                        {"ok":false,"error":"Too Many Requests"}""");
            }
            case SERVER_ERROR -> respond(exchange, 500, """
                    {"ok":false,"error":"internal"}""");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static @Nullable String header(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    public enum Scenario {
        SUCCESS,
        NO_TELEGRAM_ACCOUNT,
        RATE_LIMITED,
        SERVER_ERROR
    }
}
