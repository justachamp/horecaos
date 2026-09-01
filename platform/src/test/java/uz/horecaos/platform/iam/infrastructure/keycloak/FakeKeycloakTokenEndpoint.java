package uz.horecaos.platform.iam.infrastructure.keycloak;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

/**
 * A local stand-in for Keycloak's {@code /protocol/openid-connect/token} and
 * {@code /revoke} endpoints (ADR 0062), in the {@code FakeTelegramBotApi}/{@code
 * FakeClickHttpProvider} genre: a real socket, reached through the real {@link
 * StaffDirectGrantClient}, so a test exercises the adapter's actual HTTP and
 * form-encoding, not a mock of one interface.
 *
 * <p>Scenario selection is entirely by the {@code username}/{@code
 * refresh_token} value submitted, mirroring the four shapes verified live
 * against the dev realm on 2026-09-01 (see the platform repository's ADR 0062
 * implementation notes): a plain success, {@code invalid_grant}/"Invalid user
 * credentials" for a wrong password or an unknown username alike, {@code
 * invalid_grant}/"Account is not fully set up" for a required action, and a
 * 500 for an upstream failure.
 */
final class FakeKeycloakTokenEndpoint implements AutoCloseable {

    static final String HAPPY_USERNAME = "cashier@bukhara.local";
    static final String HAPPY_PASSWORD = "correct horse";
    static final String ACTION_REQUIRED_USERNAME = "needs-account-action@bukhara.local";
    static final String SERVER_ERROR_USERNAME = "trips-a-500@bukhara.local";
    static final String LIVE_REFRESH_TOKEN = "a-live-refresh-token";
    static final String STALE_REFRESH_TOKEN = "a-stale-refresh-token";
    static final String SERVER_ERROR_REFRESH_TOKEN = "trips-a-500-refresh-token";

    private final HttpServer server;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final AtomicInteger revocationCount = new AtomicInteger();

    private FakeKeycloakTokenEndpoint(HttpServer server) {
        this.server = server;
    }

    static FakeKeycloakTokenEndpoint start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        FakeKeycloakTokenEndpoint fake = new FakeKeycloakTokenEndpoint(server);
        server.createContext("/realms/horecaos/protocol/openid-connect/token", fake::handleToken);
        server.createContext("/realms/horecaos/protocol/openid-connect/revoke", fake::handleRevoke);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        return fake;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int revocationCount() {
        return revocationCount.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        String grantType = form.get("grant_type");
        String subject = "refresh_token".equals(grantType) ? form.get("refresh_token") : form.get("username");

        if (SERVER_ERROR_USERNAME.equals(subject) || SERVER_ERROR_REFRESH_TOKEN.equals(subject)) {
            respond(exchange, 500, jsonMapper.writeValueAsString(Map.of("error", "server_error")));
            return;
        }
        if (ACTION_REQUIRED_USERNAME.equals(subject)) {
            respond(
                    exchange,
                    400,
                    jsonMapper.writeValueAsString(
                            Map.of("error", "invalid_grant", "error_description", "Account is not fully set up")));
            return;
        }
        boolean happyPasswordGrant = "password".equals(grantType)
                && HAPPY_USERNAME.equals(form.get("username"))
                && HAPPY_PASSWORD.equals(form.get("password"));
        if (happyPasswordGrant || LIVE_REFRESH_TOKEN.equals(subject)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("access_token", "fake-access-token");
            body.put("refresh_token", "fake-refresh-token");
            body.put("expires_in", 300);
            // 0, not a positive duration: verified live against the dev realm
            // that an offline_access-scoped refresh token answers this way
            // rather than with a real expiry. See TokenOutcome.Issued's doc.
            body.put("refresh_expires_in", 0);
            body.put("token_type", "Bearer");
            respond(exchange, 200, jsonMapper.writeValueAsString(body));
            return;
        }

        // Everything else -- a wrong password, an unknown username, a stale or
        // revoked refresh token -- answers the one uniform refusal. Real
        // Keycloak varies error_description across these (see the live-proof
        // note above); this fake collapses them on purpose, because
        // StaffDirectGrantClient must classify every one of them the same way
        // and a fake that varied the wording would let a test pass by
        // accident on a wording match that is not actually load-bearing.
        respond(
                exchange,
                400,
                jsonMapper.writeValueAsString(
                        Map.of("error", "invalid_grant", "error_description", "Invalid user credentials")));
    }

    private void handleRevoke(HttpExchange exchange) throws IOException {
        revocationCount.incrementAndGet();
        Map<String, String> form = readForm(exchange);
        if (SERVER_ERROR_REFRESH_TOKEN.equals(form.get("token"))) {
            respond(exchange, 500, "");
            return;
        }
        // RFC 7009: 200 whether or not the token was ever valid.
        respond(exchange, 200, "");
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            form.put(key, value);
        }
        return form;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
