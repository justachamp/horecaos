package uz.horecaos.platform.iam.infrastructure.keycloak;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffSessions;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;

/**
 * A stateful stand-in for the parts of Keycloak 26.7 that ADR 0148 rests on, behind a real socket,
 * reached through the real {@link StaffDirectGrantClient}, {@link StaffPasswordCheckClient} and
 * {@link KeycloakStaffAccounts}: so a test exercises the adapters' actual HTTP, form encoding and
 * JSON, and the platform's real services above them, not a mock of one interface.
 *
 * <p><strong>What it models, and where each fact comes from.</strong> Every behaviour below was
 * observed on a throwaway Keycloak 26.7.0 by {@code infra/keycloak/spikes/mfa-lockout-probe.py}
 * (ADR 0148, open input one), and a model that is more forgiving than the real server would let a
 * test pass on a property the real thing does not have:
 * <ul>
 *   <li>the login client's direct grant has a conditional OTP step: an account with an OTP
 *       credential needs a valid {@code totp} and a missing code is refused exactly as a wrong
 *       password is ({@code invalid_grant}, "Invalid user credentials"); an account with none
 *       ignores the parameter;
 *   <li>a code is single use inside its thirty-second step, per credential;
 *   <li>the password-only client has no OTP step;
 *   <li>a wrong password, or a wrong code, is a failure in the brute-force detector, and
 *       <strong>any successful grant, by either client, clears the count</strong> -- the fact that
 *       makes Keycloak's own lockout useless against code guessing once a probe exists;
 *   <li>the eighth consecutive failure disables the account;
 *   <li>the admin API accepts an {@code otp} credential on {@code PUT /users/{id}}, lists it under
 *       {@code /credentials} and deletes it by id.
 * </ul>
 *
 * <p>Every call is recorded, with the code it carried, so a test can assert what Keycloak did and
 * did not see ("no request carrying that code reaches Keycloak").
 */
public final class FakeKeycloakMfaRealm implements AutoCloseable {

    public static final String LOGIN_CLIENT = "horecaos-staff-login";
    public static final String PROBE_CLIENT = "horecaos-staff-password-check";
    public static final String CLIENT_SECRET = "a-fixed-test-secret";

    /** The sign-in client's internal id: what ADR 0176's offline-session listing takes in place of its clientId. */
    public static final String LOGIN_CLIENT_UUID = "5d9f6a52-6a7e-4a58-9d44-0c1b6f1e0001";

    /**
     * The address the fake puts on every session representation, as the real Keycloak does. The
     * platform must never copy it out; a test scans every response and row for it.
     */
    public static final String SESSION_ADDRESS = "203.0.113.77";

    public static final int FAILURE_FACTOR = 8;

    /** One request Keycloak received. {@code totp} is the code the request carried, if any. */
    public record Call(
            String kind,
            @Nullable String client,
            @Nullable String username,
            @Nullable String totp) {}

    public static final class Credential {
        public final String id = UUID.randomUUID().toString();
        public final String label;
        final String secret;
        final long createdMillis;
        long lastUsedStep = Long.MIN_VALUE;

        Credential(String label, String secret, long createdMillis) {
            this.label = label;
            this.secret = secret;
            this.createdMillis = createdMillis;
        }
    }

    public static final class User {
        public final String id = UUID.randomUUID().toString();
        public final String username;
        public final @Nullable String email;
        String password;
        public final List<Credential> otp = new ArrayList<>();
        public final List<String> requiredActions = new ArrayList<>();
        public final List<String> realmRoles = new ArrayList<>();
        public int failures;
        public boolean disabled;
        /** Keycloak organization ids the account belongs to: the {@code organization} claim of its tokens. */
        public final List<String> organizationIds = new ArrayList<>();

        public final Map<String, Object> attributes = new LinkedHashMap<>();

        User(String username, String password, @Nullable String email) {
            this.username = username;
            this.password = password;
            this.email = email;
        }

        public String password() {
            return password;
        }

        public boolean hasPassword(String candidate) {
            return password.equals(candidate);
        }
    }

    /** One offline session: what the direct grant with {@code offline_access} creates. */
    public static final class Session {
        public final String id =
                "kc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        public final String userId;
        public final long startMillis;
        public long lastAccessMillis;

        Session(String userId, long startMillis) {
            this.userId = userId;
            this.startMillis = startMillis;
            this.lastAccessMillis = startMillis;
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<String, Session> offlineSessions = new LinkedHashMap<>();
    private volatile boolean sessionAdminFails;
    private volatile boolean logoutFails;
    private final HttpServer server;
    private final Clock clock;
    private final Map<String, User> users = new LinkedHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger sessions = new AtomicInteger();
    private final List<String> deletedCredentialTypes = new CopyOnWriteArrayList<>();
    private volatile boolean probeClientKnown = true;

    private FakeKeycloakMfaRealm(HttpServer server, Clock clock) {
        this.server = server;
        this.clock = clock;
    }

    public static FakeKeycloakMfaRealm start(Clock clock) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        FakeKeycloakMfaRealm realm = new FakeKeycloakMfaRealm(server, clock);
        server.createContext("/realms/horecaos/protocol/openid-connect/token", realm::token);
        server.createContext("/realms/horecaos/protocol/openid-connect/revoke", realm::revoke);
        server.createContext("/realms/horecaos/protocol/openid-connect/logout", realm::logout);
        server.createContext("/admin/realms/horecaos/users", realm::admin);
        server.createContext("/admin/realms/horecaos/sessions", realm::deleteSession);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        return realm;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------------ fixtures

    public synchronized User addUser(String username, String password, @Nullable String email) {
        User user = new User(username, password, email);
        users.put(username, user);
        return user;
    }

    /** Gives the account an authenticator directly, as if it had been enrolled long ago. */
    public synchronized Credential enrol(User user, String secret, String label) {
        Credential credential = new Credential(label, secret, clock.instant().toEpochMilli());
        user.otp.add(credential);
        return credential;
    }

    /** Keycloak's session endpoints (list, single delete) answer 503; the token endpoint is unaffected. */
    public void failSessionAdmin(boolean fails) {
        this.sessionAdminFails = fails;
    }

    /** {@code POST /users/{id}/logout} answers 503, so {@code logoutEverywhere} fails. */
    public void failLogout(boolean fails) {
        this.logoutFails = fails;
    }

    /** The ids of the offline sessions Keycloak holds for the account right now. */
    public synchronized List<String> liveSessionIds(User user) {
        return offlineSessions.values().stream()
                .filter(session -> session.userId.equals(user.id))
                .map(session -> session.id)
                .toList();
    }

    /** Ends a session behind the platform's back, as an idle timeout would. */
    public synchronized void expireSession(String sessionId) {
        offlineSessions.remove(sessionId);
    }

    /** Models a realm that never had the password-only client created. */
    public void withoutProbeClient() {
        probeClientKnown = false;
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<Call> calls(String kind, @Nullable String client) {
        return calls.stream()
                .filter(call -> call.kind().equals(kind) && (client == null || client.equals(call.client())))
                .toList();
    }

    /** The token requests to the login client that carried a code: what Keycloak was asked to verify. */
    public List<Call> codesSeenByLoginClient() {
        return calls.stream()
                .filter(call ->
                        "TOKEN".equals(call.kind()) && LOGIN_CLIENT.equals(call.client()) && call.totp() != null)
                .toList();
    }

    public int openSessions() {
        return sessions.get();
    }

    public List<String> deletedCredentialTypes() {
        return List.copyOf(deletedCredentialTypes);
    }

    public void clearCalls() {
        calls.clear();
    }

    // ------------------------------------------------------------------ adapters

    /** The three real adapters, pointed at this realm. */
    public Adapters adapters() {
        SecretResolver secrets = new SecretResolver() {
            @Override
            public SecretValue resolve(SecretReference reference) {
                return SecretValue.of(CLIENT_SECRET);
            }

            @Override
            public SecretValue resolveFresh(SecretReference reference) {
                return SecretValue.of(CLIENT_SECRET);
            }
        };
        RestClient client = RestClient.builder().baseUrl(baseUrl()).build();
        SecretReference login =
                new SecretReference("test", SecretCategory.IDENTITY_ADMIN, "keycloak", "staff-login-secret");
        SecretReference probe =
                new SecretReference("test", SecretCategory.IDENTITY_ADMIN, "keycloak", "staff-password-check-secret");
        return new Adapters(
                new StaffDirectGrantClient(client, "horecaos", LOGIN_CLIENT, login, secrets, clock),
                new StaffPasswordCheckClient(client, "horecaos", PROBE_CLIENT, probe, secrets),
                new KeycloakStaffAccounts(client, "horecaos", LOGIN_CLIENT),
                new KeycloakStaffSessions(client, "horecaos", LOGIN_CLIENT_UUID));
    }

    public record Adapters(
            StaffDirectGrantClient login,
            StaffPasswordCheckClient probe,
            StaffAccounts accounts,
            StaffSessions sessions) {}

    // ------------------------------------------------------------------ the token endpoint

    private synchronized void token(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        String client = form.get("client_id");
        String username = form.get("username");
        String totp = form.get("totp");
        calls.add(new Call("TOKEN", client, username, totp));

        boolean login = LOGIN_CLIENT.equals(client);
        boolean probe = PROBE_CLIENT.equals(client);
        if ((!login && !probe) || (probe && !probeClientKnown) || !CLIENT_SECRET.equals(form.get("client_secret"))) {
            respond(
                    exchange,
                    401,
                    json(Map.of(
                            "error",
                            "invalid_client",
                            "error_description",
                            "Invalid client or Invalid client credentials")));
            return;
        }
        if ("refresh_token".equals(form.get("grant_type"))) {
            refreshGrant(exchange, form, login);
            return;
        }
        if (!"password".equals(form.get("grant_type"))) {
            respond(exchange, 400, json(Map.of("error", "unsupported_grant_type")));
            return;
        }
        User user = users.get(username);
        if (user == null) {
            respond(exchange, 400, invalidGrant());
            return;
        }
        if (user.disabled) {
            respond(exchange, 400, invalidGrant());
            return;
        }
        if (!user.hasPassword(form.getOrDefault("password", ""))) {
            fail(user);
            respond(exchange, 400, invalidGrant());
            return;
        }
        if (login && !user.otp.isEmpty()) {
            Credential matched = totp == null ? null : matchingCredential(user, totp);
            if (matched == null) {
                fail(user);
                respond(exchange, 400, invalidGrant());
                return;
            }
            matched.lastUsedStep = currentStep();
        }
        if (!user.requiredActions.isEmpty()) {
            respond(
                    exchange,
                    400,
                    json(Map.of("error", "invalid_grant", "error_description", "Account is not fully set up")));
            return;
        }
        // Verified live: a successful grant, whichever client made it, clears the failure count.
        user.failures = 0;
        sessions.incrementAndGet();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", user.id);
        claims.put("realm_access", Map.of("roles", user.realmRoles));
        // Probed live: a direct grant with offline_access creates one offline session, and the
        // access token's sid is that session's id. The password-only client asks for none.
        String sid = null;
        if (login && String.valueOf(form.get("scope")).contains("offline_access")) {
            Session session = new Session(user.id, clock.instant().toEpochMilli());
            offlineSessions.put(session.id, session);
            sid = session.id;
        }
        if (sid != null) {
            claims.put("sid", sid);
        }
        addOrganizations(claims, user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", jwt(claims));
        body.put("refresh_token", sid == null ? "refresh-" + UUID.randomUUID() : refreshToken(user, sid));
        body.put("expires_in", probe ? 1 : 300);
        body.put("refresh_expires_in", 0);
        body.put("token_type", "Bearer");
        respond(exchange, 200, json(body));
    }

    private static void addOrganizations(Map<String, Object> claims, User user) {
        if (!user.organizationIds.isEmpty()) {
            Map<String, Object> organizations = new LinkedHashMap<>();
            int n = 0;
            for (String id : user.organizationIds) {
                organizations.put("org" + (++n), Map.of("id", id));
            }
            claims.put("organization", organizations);
        }
    }

    /** An offline refresh token is a JWT that names its session, as Keycloak's is. */
    private static String refreshToken(User user, String sid) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", user.id);
        claims.put("sid", sid);
        claims.put("typ", "Offline");
        claims.put("jti", UUID.randomUUID().toString());
        return jwt(claims);
    }

    private void refreshGrant(HttpExchange exchange, Map<String, String> form, boolean login) throws IOException {
        calls.add(new Call("REFRESH", form.get("client_id"), null, null));
        String sid = sessionIdOf(form.getOrDefault("refresh_token", ""));
        Session session = sid == null ? null : offlineSessions.get(sid);
        User user = session == null
                ? null
                : users.values().stream()
                        .filter(candidate -> candidate.id.equals(session.userId))
                        .findFirst()
                        .orElse(null);
        if (!login || session == null || user == null) {
            respond(
                    exchange,
                    400,
                    json(Map.of("error", "invalid_grant", "error_description", "Offline user session not found")));
            return;
        }
        session.lastAccessMillis = clock.instant().toEpochMilli();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", user.id);
        claims.put("sid", session.id);
        claims.put("realm_access", Map.of("roles", user.realmRoles));
        addOrganizations(claims, user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", jwt(claims));
        body.put("refresh_token", form.get("refresh_token"));
        body.put("expires_in", 300);
        body.put("refresh_expires_in", 0);
        body.put("token_type", "Bearer");
        respond(exchange, 200, json(body));
    }

    private static @Nullable String sessionIdOf(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            Map<?, ?> claims = JSON.readValue(
                    new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8), Map.class);
            return claims.get("sid") instanceof String sid ? sid : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** The {@code sid} of an access or refresh token this realm issued, for assertions. */
    public static @Nullable String sidOf(String token) {
        return sessionIdOf(token);
    }

    private synchronized void deleteSession(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String id = uri.getPath().substring("/admin/realms/horecaos/sessions/".length());
        calls.add(new Call("ADMIN " + exchange.getRequestMethod() + " /sessions/" + id, null, null, null));
        exchange.getRequestBody().readAllBytes();
        if (sessionAdminFails) {
            respond(exchange, 503, json(Map.of("error", "unavailable")));
            return;
        }
        boolean offline = "true".equals(queryOf(uri).get("isOffline"));
        if (!"DELETE".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "");
        } else if (offline && offlineSessions.remove(id) != null) {
            respond(exchange, 204, "");
        } else {
            respond(exchange, 404, json(Map.of("error", "Session not found")));
        }
    }

    private void fail(User user) {
        user.failures++;
        if (user.failures >= FAILURE_FACTOR) {
            user.disabled = true;
        }
    }

    private @Nullable Credential matchingCredential(User user, String code) {
        long step = currentStep();
        for (Credential credential : user.otp) {
            for (long candidate = step - 1; candidate <= step + 1; candidate++) {
                if (code.equals(Totp.code(credential.secret, candidate)) && candidate > credential.lastUsedStep) {
                    return credential;
                }
            }
        }
        return null;
    }

    private long currentStep() {
        return clock.instant().getEpochSecond() / 30;
    }

    private synchronized void revoke(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        calls.add(new Call("REVOKE", form.get("client_id"), null, null));
        sessions.updateAndGet(open -> Math.max(0, open - 1));
        String revoked = sessionIdOf(form.getOrDefault("token", ""));
        if (revoked != null) {
            offlineSessions.remove(revoked);
        }
        respond(exchange, 200, "");
    }

    private synchronized void logout(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        calls.add(new Call("LOGOUT", form.get("client_id"), null, null));
        sessions.updateAndGet(open -> Math.max(0, open - 1));
        respond(exchange, 204, "");
    }

    // ------------------------------------------------------------------ the admin API

    private synchronized void admin(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String method = exchange.getRequestMethod();
        String path = uri.getPath().substring("/admin/realms/horecaos/users".length());
        String[] parts = path.isEmpty() ? new String[0] : path.substring(1).split("/");
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(new Call("ADMIN " + method + " " + path, null, null, null));

        if (parts.length == 0 && "GET".equals(method)) {
            Map<String, String> query = queryOf(uri);
            List<Map<String, Object>> found = new ArrayList<>();
            for (User user : users.values()) {
                if (user.username.equals(query.get("username"))
                        || (user.email != null && user.email.equals(query.get("email")))) {
                    found.add(representation(user));
                }
            }
            respond(exchange, 200, json(found));
            return;
        }
        User user = userById(parts[0]);
        if (user == null) {
            respond(exchange, 404, json(Map.of("error", "User not found")));
            return;
        }
        if (parts.length == 1 && "GET".equals(method)) {
            respond(exchange, 200, json(representation(user)));
        } else if (parts.length == 1 && "PUT".equals(method)) {
            applyUserUpdate(user, body);
            respond(exchange, 204, "");
        } else if (parts.length == 2 && "credentials".equals(parts[1]) && "GET".equals(method)) {
            List<Map<String, Object>> list = new ArrayList<>();
            list.add(Map.of("id", "password-" + user.id, "type", "password", "createdDate", 1L));
            for (Credential credential : user.otp) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", credential.id);
                entry.put("type", "otp");
                entry.put("userLabel", credential.label);
                entry.put("createdDate", credential.createdMillis);
                list.add(entry);
            }
            respond(exchange, 200, json(list));
        } else if (parts.length == 3 && "credentials".equals(parts[1]) && "DELETE".equals(method)) {
            String id = parts[2];
            if (id.startsWith("password-")) {
                deletedCredentialTypes.add("password");
                user.password = "";
                respond(exchange, 204, "");
                return;
            }
            boolean removed = user.otp.removeIf(credential -> credential.id.equals(id));
            if (removed) {
                deletedCredentialTypes.add("otp");
            }
            respond(exchange, removed ? 204 : 404, "");
        } else if (parts.length == 2 && "logout".equals(parts[1]) && "POST".equals(method)) {
            if (logoutFails) {
                respond(exchange, 503, json(Map.of("error", "unavailable")));
                return;
            }
            // Probed live: the admin logout ends online sessions only. The offline ones this
            // platform creates survive it, and end when the consent is deleted below.
            sessions.set(0);
            respond(exchange, 204, "");
        } else if (parts.length == 3 && "consents".equals(parts[1]) && "DELETE".equals(method)) {
            boolean had = offlineSessions.values().removeIf(session -> session.userId.equals(user.id));
            respond(exchange, had ? 204 : 404, "");
        } else if (parts.length == 3 && "offline-sessions".equals(parts[1]) && "GET".equals(method)) {
            if (sessionAdminFails) {
                respond(exchange, 503, json(Map.of("error", "unavailable")));
                return;
            }
            // Keycloak 26.7: a client id it holds no client for is a 404 ("Client not found"), as an unknown
            // account is; it is not an empty list. The adapter must tell the two apart.
            if (!LOGIN_CLIENT_UUID.equals(parts[2])) {
                respond(exchange, 404, json(Map.of("error", "Client not found")));
                return;
            }
            List<Map<String, Object>> list = new ArrayList<>();
            if (LOGIN_CLIENT_UUID.equals(parts[2])) {
                for (Session session : offlineSessions.values()) {
                    if (session.userId.equals(user.id)) {
                        Map<String, Object> rep = new LinkedHashMap<>();
                        rep.put("id", session.id);
                        rep.put("username", user.username);
                        rep.put("userId", user.id);
                        rep.put("ipAddress", SESSION_ADDRESS);
                        rep.put("start", session.startMillis);
                        rep.put("lastAccess", session.lastAccessMillis);
                        rep.put("clients", Map.of(LOGIN_CLIENT_UUID, LOGIN_CLIENT));
                        list.add(rep);
                    }
                }
            }
            respond(exchange, 200, json(list));
        } else if (parts.length == 2 && "sessions".equals(parts[1]) && "GET".equals(method)) {
            if (sessionAdminFails) {
                respond(exchange, 503, json(Map.of("error", "unavailable")));
                return;
            }
            respond(exchange, 200, "[]");
        } else {
            respond(exchange, 404, json(Map.of("error", "not modelled: " + method + " " + path)));
        }
    }

    @SuppressWarnings("unchecked")
    private void applyUserUpdate(User user, String body) {
        Map<String, Object> rep = JSON.readValue(body, Map.class);
        if (rep.get("requiredActions") instanceof List<?> actions) {
            user.requiredActions.clear();
            actions.forEach(action -> user.requiredActions.add(String.valueOf(action)));
        }
        if (rep.get("credentials") instanceof List<?> credentials) {
            for (Object entry : credentials) {
                Map<String, Object> credential = (Map<String, Object>) entry;
                if ("otp".equals(credential.get("type"))) {
                    Map<String, Object> secretData =
                            JSON.readValue(String.valueOf(credential.get("secretData")), Map.class);
                    Map<String, Object> credentialData =
                            JSON.readValue(String.valueOf(credential.get("credentialData")), Map.class);
                    if (!"totp".equals(credentialData.get("subType"))
                            || !Integer.valueOf(6).equals(credentialData.get("digits"))
                            || !Integer.valueOf(30).equals(credentialData.get("period"))
                            || !"HmacSHA1".equals(credentialData.get("algorithm"))) {
                        throw new IllegalStateException("the platform registered an OTP policy the realm does not use");
                    }
                    user.otp.add(new Credential(
                            String.valueOf(credential.get("userLabel")),
                            String.valueOf(secretData.get("value")),
                            clock.instant().toEpochMilli()));
                }
            }
        }
    }

    private @Nullable User userById(String id) {
        return users.values().stream()
                .filter(user -> user.id.equals(id))
                .findFirst()
                .orElse(null);
    }

    private static Map<String, Object> representation(User user) {
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("id", user.id);
        rep.put("username", user.username);
        rep.put("enabled", !user.disabled);
        rep.put("emailVerified", true);
        rep.put("firstName", "Test");
        rep.put("lastName", "Person");
        if (user.email != null) {
            rep.put("email", user.email);
        }
        rep.put("attributes", user.attributes);
        rep.put("requiredActions", new ArrayList<>(user.requiredActions));
        return rep;
    }

    // ------------------------------------------------------------------ helpers

    private static String invalidGrant() {
        return json(Map.of("error", "invalid_grant", "error_description", "Invalid user credentials"));
    }

    private static String jwt(Map<String, Object> claims) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "."
                + encoder.encodeToString(json(claims).getBytes(StandardCharsets.UTF_8))
                + ".sig";
    }

    private static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            form.put(
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return form;
    }

    private static Map<String, String> queryOf(URI uri) {
        Map<String, String> query = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return query;
        }
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            query.put(
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return query;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    /** RFC 6238 with the realm's policy: six digits, thirty seconds, HMAC-SHA-1, key = the secret's UTF-8 bytes. */
    public static final class Totp {

        private Totp() {}

        public static String code(String secret, Instant at) {
            return code(secret, at.getEpochSecond() / 30);
        }

        static String code(String secret, long step) {
            try {
                Mac mac = Mac.getInstance("HmacSHA1");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
                byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
                int offset = hash[hash.length - 1] & 0xF;
                int binary = ((hash[offset] & 0x7F) << 24)
                        | ((hash[offset + 1] & 0xFF) << 16)
                        | ((hash[offset + 2] & 0xFF) << 8)
                        | (hash[offset + 3] & 0xFF);
                return "%06d".formatted(binary % 1_000_000);
            } catch (java.security.GeneralSecurityException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
