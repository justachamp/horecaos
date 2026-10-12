package uz.horecaos.platform.iam.infrastructure.keycloak;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import uz.horecaos.platform.iam.api.accounts.StaffSessions;
import uz.horecaos.platform.iam.api.accounts.StaffSessions.LiveSession;
import uz.horecaos.platform.iam.api.accounts.StaffSessions.SessionsUnavailableException;
import uz.horecaos.platform.iam.infrastructure.keycloak.FakeKeycloakMfaRealm.User;

/**
 * The adapter's HTTP contract (ADR 0176, ADR 0007): the real {@link KeycloakStaffSessions} against
 * the recorded fake realm for the shapes Keycloak 26.7 was probed to return, and against a bare
 * server for the shapes it must survive and the fake does not produce.
 */
class KeycloakStaffSessionsTests {

    @SuppressWarnings("NullAway")
    private FakeKeycloakMfaRealm realm;

    @SuppressWarnings("NullAway")
    private HttpServer bare;

    @AfterEach
    void stop() {
        if (realm != null) {
            realm.close();
        }
        if (bare != null) {
            bare.stop(0);
        }
    }

    @Test
    @DisplayName("lists the account's offline sessions as an id and two times, and nothing else")
    void listsSessionsWithoutTheAddress() throws Exception {
        realm = FakeKeycloakMfaRealm.start(Clock.systemUTC());
        User cook = realm.addUser("998900000001", "pw", "cook@example.test");
        var adapters = realm.adapters();
        adapters.login().signIn("998900000001", "pw", null);
        adapters.login().signIn("998900000001", "pw", null);

        List<LiveSession> sessions = adapters.sessions().list(cook.id);

        assertThat(sessions).hasSize(2);
        assertThat(sessions)
                .extracting(LiveSession::id)
                .containsExactlyInAnyOrderElementsOf(realm.liveSessionIds(cook));
        assertThat(sessions.toString()).doesNotContain(FakeKeycloakMfaRealm.SESSION_ADDRESS);
        assertThat(sessions).allSatisfy(session -> {
            assertThat(session.startedAt()).isAfter(Instant.parse("2020-01-01T00:00:00Z"));
            assertThat(session.lastAccessAt()).isAfterOrEqualTo(session.startedAt());
        });
    }

    @Test
    @DisplayName("an account Keycloak does not know has no sessions; one with none has an empty list")
    void unknownAccountAndEmptyList() throws Exception {
        realm = FakeKeycloakMfaRealm.start(Clock.systemUTC());
        User cook = realm.addUser("998900000002", "pw", "cook2@example.test");
        StaffSessions sessions = realm.adapters().sessions();

        assertThat(sessions.list(cook.id)).isEmpty();
        assertThat(sessions.list(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    @DisplayName(
            "a client id Keycloak holds no client for is unavailable, never an empty list: the 404 for the client is not the 404 for the account")
    void aStaleClientIdIsNotAnEmptyList() throws Exception {
        realm = FakeKeycloakMfaRealm.start(Clock.systemUTC());
        User cook = realm.addUser("998900000009", "pw", "cook9@example.test");
        realm.adapters().login().signIn("998900000009", "pw", null);
        RestClient client = RestClient.builder().baseUrl(realm.baseUrl()).build();
        KeycloakStaffSessions stale =
                new KeycloakStaffSessions(client, "horecaos", UUID.randomUUID().toString());

        assertThatThrownBy(() -> stale.list(cook.id))
                .as(
                        "a confident empty list would tell a person who is signed in on a device that there is nothing to end")
                .isInstanceOf(SessionsUnavailableException.class)
                .hasMessageNotContaining(cook.id);
        assertThat(stale.list(UUID.randomUUID().toString()))
                .as("an account Keycloak does not know has none, whatever the client id")
                .isEmpty();
        assertThat(new KeycloakStaffSessions(client, "horecaos", FakeKeycloakMfaRealm.LOGIN_CLIENT_UUID).list(cook.id))
                .as("with the right client id the same account lists its session")
                .hasSize(1);
    }

    @Test
    @DisplayName("ending a session ends that one only; ending one that is gone answers false, not an error")
    void endsOneSession() throws Exception {
        realm = FakeKeycloakMfaRealm.start(Clock.systemUTC());
        User cook = realm.addUser("998900000003", "pw", "cook3@example.test");
        var adapters = realm.adapters();
        adapters.login().signIn("998900000003", "pw", null);
        adapters.login().signIn("998900000003", "pw", null);
        List<String> ids = realm.liveSessionIds(cook);

        assertThat(adapters.sessions().end(ids.get(0))).isTrue();
        assertThat(realm.liveSessionIds(cook)).containsExactly(ids.get(1));
        assertThat(adapters.sessions().end(ids.get(0))).as("already gone").isFalse();
    }

    @Test
    @DisplayName(
            "a Keycloak that answers 5xx is unavailable, for the list and for the end, and says nothing about the body")
    void keycloakDown() throws Exception {
        realm = FakeKeycloakMfaRealm.start(Clock.systemUTC());
        User cook = realm.addUser("998900000004", "pw", "cook4@example.test");
        var adapters = realm.adapters();
        adapters.login().signIn("998900000004", "pw", null);
        String id = realm.liveSessionIds(cook).get(0);
        realm.failSessionAdmin(true);

        assertThatThrownBy(() -> adapters.sessions().list(cook.id)).isInstanceOf(SessionsUnavailableException.class);
        assertThatThrownBy(() -> adapters.sessions().end(id))
                .isInstanceOf(SessionsUnavailableException.class)
                .hasMessageNotContaining(FakeKeycloakMfaRealm.SESSION_ADDRESS);
        assertThat(realm.liveSessionIds(cook)).as("nothing was ended").hasSize(1);
    }

    @Test
    @DisplayName("an unset sign-in client id makes listing unavailable and asks Keycloak nothing")
    void clientUuidNotConfigured() throws Exception {
        realm = FakeKeycloakMfaRealm.start(Clock.systemUTC());
        RestClient client = RestClient.builder().baseUrl(realm.baseUrl()).build();
        realm.clearCalls();

        assertThatThrownBy(() -> new KeycloakStaffSessions(client, "horecaos", "").list("any"))
                .isInstanceOf(SessionsUnavailableException.class);
        assertThatThrownBy(() -> new KeycloakStaffSessions(client, "horecaos", null).list("any"))
                .isInstanceOf(SessionsUnavailableException.class);
        assertThat(realm.calls()).isEmpty();
    }

    @Test
    @DisplayName("a malformed body, a missing field and a non-array answer do not escape as a raw exception")
    void malformedBodies() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("not json at all");
        List<String> paths = new CopyOnWriteArrayList<>();
        bare = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        bare.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            byte[] bytes = java.util.Objects.requireNonNull(body.get()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        bare.start();
        RestClient client = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + bare.getAddress().getPort())
                .build();
        KeycloakStaffSessions sessions = new KeycloakStaffSessions(client, "horecaos", "uuid-1");

        assertThatThrownBy(() -> sessions.list("u")).isInstanceOf(SessionsUnavailableException.class);

        body.set("{\"error\":\"nope\"}");
        assertThatThrownBy(() -> sessions.list("u")).isInstanceOf(SessionsUnavailableException.class);

        body.set("[{\"username\":\"no id\"},{\"id\":\"s-1\",\"start\":\"not a number\"}]");
        List<LiveSession> listed = sessions.list("u");
        assertThat(listed).extracting(LiveSession::id).containsExactly("s-1");
        assertThat(paths).anyMatch(path -> path.endsWith("/offline-sessions/uuid-1"));
        assertThat(paths).anyMatch(path -> path.endsWith("/users/u/sessions"));
    }

    @Test
    @DisplayName("an offline session and an online one with the same id are merged, offline first")
    void mergedById() throws Exception {
        bare = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        bare.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String json = path.contains("offline-sessions")
                    ? "[{\"id\":\"same\",\"start\":1000,\"lastAccess\":5000,\"ipAddress\":\"203.0.113.9\"}]"
                    : "[{\"id\":\"same\",\"start\":1000,\"lastAccess\":9000},{\"id\":\"online-only\",\"start\":2000,\"lastAccess\":2000}]";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        bare.start();
        RestClient client = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + bare.getAddress().getPort())
                .build();

        List<LiveSession> listed = new KeycloakStaffSessions(client, "horecaos", "uuid-1").list("u");

        assertThat(listed).extracting(LiveSession::id).containsExactly("same", "online-only");
        assertThat(listed.get(0).lastAccessAt()).isEqualTo(Instant.ofEpochMilli(5000));
        assertThat(listed.toString()).doesNotContain("203.0.113.9");
    }

    @Test
    @DisplayName("the port's session type has no field that could carry an address")
    void exposesNoAddress() {
        assertThat(StaffSessions.LiveSession.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("id", "startedAt", "lastAccessAt");
    }
}
