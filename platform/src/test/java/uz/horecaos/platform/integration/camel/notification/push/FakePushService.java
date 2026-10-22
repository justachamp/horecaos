package uz.horecaos.platform.integration.camel.notification.push;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;

/**
 * A local stand-in for a browser vendor's push service (RFC 8030), in the {@code FakeTelegramBotApi}
 * genre: a real socket, reached through the real adapter, so a test exercises the encryption, the VAPID
 * header and the status handling rather than a mock of an interface.
 *
 * <p>It records every request it is sent, and answers each path as told: a status, a {@code Retry-After},
 * a redirect, or no answer at all (the connection closed without a response), which is how a push service
 * that went away mid-request looks to a sender.
 */
public final class FakePushService implements AutoCloseable {

    /** One request the service received. */
    public record Received(String path, Map<String, String> headers, byte[] body) {

        public @Nullable String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private static final Base64.Decoder URL = Base64.getUrlDecoder();

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Map<String, Scenario> scenarios = new ConcurrentHashMap<>();

    private record Scenario(
            int status,
            @Nullable String retryAfter,
            @Nullable String location,
            boolean hangUp) {}

    private FakePushService(HttpServer server) {
        this.server = server;
    }

    public static FakePushService start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakePushService fake = new FakePushService(server);
        server.createContext("/", fake::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        return fake;
    }

    /** The base address, e.g. {@code http://127.0.0.1:41233}. */
    public String origin() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A subscription URL on this service. */
    public String endpoint(String path) {
        return origin() + path;
    }

    public void respond(String path, int status) {
        scenarios.put(path, new Scenario(status, null, null, false));
    }

    public void respondRetryAfter(String path, int status, String retryAfter) {
        scenarios.put(path, new Scenario(status, retryAfter, null, false));
    }

    public void redirect(String path, String location) {
        scenarios.put(path, new Scenario(302, null, location, false));
    }

    /** Takes the request and closes the connection without answering. */
    public void hangUp(String path) {
        scenarios.put(path, new Scenario(0, null, null, true));
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public List<Received> receivedAt(String path) {
        return received.stream().filter(request -> request.path().equals(path)).toList();
    }

    /**
     * Whether the request's {@code Authorization} is a VAPID header whose token is signed by the given
     * key and names this service as its audience: what a real push service checks before accepting.
     */
    public boolean vapidVerifies(Received request, ECPublicKey publicKey, String expectedSubject) {
        try {
            String authorization = request.header("authorization");
            if (authorization == null || !authorization.startsWith("vapid t=")) {
                return false;
            }
            String token = authorization.substring("vapid t=".length(), authorization.indexOf(", k="));
            String[] parts = token.split("\\.");
            String claims = new String(URL.decode(parts[1]), StandardCharsets.UTF_8);
            if (!claims.contains("\"aud\":\"" + origin() + "\"")
                    || !claims.contains("\"sub\":\"" + expectedSubject + "\"")) {
                return false;
            }
            byte[] raw = URL.decode(parts[2]);
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(publicKey);
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return signature.verify(der(raw));
        } catch (Exception malformed) {
            return false;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        byte[] body = exchange.getRequestBody().readAllBytes();
        Map<String, String> headers = new ConcurrentHashMap<>();
        exchange.getRequestHeaders()
                .forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
        received.add(new Received(path, headers, body));

        Scenario scenario = scenarios.getOrDefault(path, new Scenario(201, null, null, false));
        if (scenario.hangUp()) {
            exchange.close();
            return;
        }
        if (scenario.retryAfter() != null) {
            exchange.getResponseHeaders().add("Retry-After", scenario.retryAfter());
        }
        if (scenario.location() != null) {
            exchange.getResponseHeaders().add("Location", scenario.location());
        }
        exchange.sendResponseHeaders(scenario.status(), -1);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** {@code R || S} as the DER the JCA verifies. Written here so the production conversion is not reused. */
    private static byte[] der(byte[] raw) {
        byte[] r = new java.math.BigInteger(1, java.util.Arrays.copyOfRange(raw, 0, 32)).toByteArray();
        byte[] s = new java.math.BigInteger(1, java.util.Arrays.copyOfRange(raw, 32, 64)).toByteArray();
        byte[] der = new byte[6 + r.length + s.length];
        der[0] = 0x30;
        der[1] = (byte) (4 + r.length + s.length);
        der[2] = 0x02;
        der[3] = (byte) r.length;
        System.arraycopy(r, 0, der, 4, r.length);
        der[4 + r.length] = 0x02;
        der[5 + r.length] = (byte) s.length;
        System.arraycopy(s, 0, der, 6 + r.length, s.length);
        return der;
    }
}
