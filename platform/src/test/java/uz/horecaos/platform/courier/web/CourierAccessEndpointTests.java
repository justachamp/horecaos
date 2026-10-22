package uz.horecaos.platform.courier.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.courier.application.CourierAccessRelay;
import uz.horecaos.platform.courier.application.port.CourierAccessMessenger;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierAccessStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * A courier's first sign-in and a reset of it (ADR 0178, couriers.md §18), through the real HTTP stack, the
 * real relay and the real database, with the identity provider and the SMS gateway as in-memory fakes.
 *
 * <p>What a green run here proves is the platform's half: that a link is queued, texted once, spent once, voided
 * by a newer one, expires, survives a password the realm refuses, and that no phone number, token or password
 * reaches a table, an audit fact or a response. It proves nothing about a handset receiving a text; that is the
 * owner's external fact (docs/runbooks/external-connections/w3-delivery-maps.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CourierAccessEndpointTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID TENANT = UUID.fromString("018fd800-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fd800-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fd800-4000-7000-8000-0000000000c1");
    private static final UUID COURIER_TYPE = UUID.fromString("018fd800-4000-7000-8000-0000000000d1");
    private static final UUID COURIER = UUID.fromString("018fd800-4000-7000-8000-0000000000e1");
    private static final String PHONE = "+998901234567";
    private static final String MANAGER = "courier-access-manager";
    private static final String OWNER = "courier-access-owner";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the courier access endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
        registry.add("horecaos.courier.access.link-origin", () -> "https://ops.example.test");
    }

    @Autowired
    @SuppressWarnings("NullAway")
    private MockMvc mvc;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @Autowired
    @SuppressWarnings("NullAway")
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    @SuppressWarnings("NullAway")
    private Stubs.FakeStaffAccounts accounts;

    @Autowired
    @SuppressWarnings("NullAway")
    private Stubs.RecordingMessenger messenger;

    @Autowired
    @SuppressWarnings("NullAway")
    private CourierAccessRelay relay;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcCourierAccessStore store;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcCourierStore couriers;

    @Autowired
    @SuppressWarnings("NullAway")
    private AuditRecorder audit;

    private String subject = "";

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        accounts.reset();
        messenger.reset();

        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, keycloak_organization_id, version)
                VALUES (:id, 'courier-access', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 'org-access', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name, vehicle_class)
                VALUES (:id, :t, 'SCOOTER', 'Scooter', 'SCOOTER')
                """).param("id", COURIER_TYPE).param("t", TENANT).update();

        StaffAccounts.StaffAccount account = accounts.create("Malika", "Tosheva", PHONE, null);
        subject = account.subjectId();
        insertCourier(COURIER, subject, "C-1");
        seedEngagement(COURIER);

        roleRegistry.synchronize();
        grant(MANAGER, PlatformRole.TENANT_ADMIN);
        grant(OWNER, PlatformRole.TENANT_OWNER);
    }

    @Test
    @DisplayName("a manager's invite is queued, texted once by the relay, opened and spent by the courier, "
            + "and the courier's password is set at the identity provider")
    void theWholeFirstSignInFlow() throws Exception {
        MvcResult queued = invite(MANAGER, "INVITE", "ru");
        assertThat(queued.getResponse().getStatus()).isEqualTo(202);
        assertThat(json(queued).path("status").asText()).isEqualTo("QUEUED");
        assertThat(json(queued).path("smsConnected").asBoolean()).isTrue();
        assertThat(state(MANAGER).path("state").asText()).isEqualTo("QUEUED");
        assertThat(messenger.sent).as("the request itself sends nothing").isEmpty();

        assertThat(relay.runOnce()).isEqualTo(1);

        assertThat(messenger.sent).hasSize(1);
        CourierAccessMessenger.AccessMessage text = messenger.sent.getFirst();
        assertThat(text.destination()).isEqualTo(PHONE);
        assertThat(text.kind()).isEqualTo("INVITE");
        assertThat(text.locale()).isEqualTo("ru");
        assertThat(text.text())
                .contains("https://ops.example.test/courier-access#token=")
                .contains("приложение");
        String token = tokenOf(text);
        assertThat(state(MANAGER).path("state").asText()).isEqualTo("SENT");

        MvcResult inspected = inspect(token);
        assertThat(inspected.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(inspected).path("kind").asText()).isEqualTo("INVITE");
        assertThat(json(inspected).path("locale").asText()).isEqualTo("ru");

        MvcResult accepted = accept(token, "a-long-enough-password-1");
        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(accepted).path("sessionsEnded").asBoolean()).isTrue();
        assertThat(accounts.passwords).containsEntry(subject, "a-long-enough-password-1");
        assertThat(accounts.loggedOut).containsExactly(subject);
        assertThat(state(MANAGER).path("state").asText()).isEqualTo("ACCEPTED");

        MvcResult again = accept(token, "another-long-password-2");
        assertThat(again.getResponse().getStatus()).as("a spent link is spent").isEqualTo(404);
        assertThat(json(again).path("reason").asText()).isEqualTo("INVALID");
        assertThat(accounts.passwords).containsEntry(subject, "a-long-enough-password-1");
    }

    @Test
    @DisplayName("nothing that is stored or audited holds the phone number, the token or the password")
    void noNumberTokenOrPasswordIsKept() throws Exception {
        invite(MANAGER, "INVITE", "en");
        relay.runOnce();
        String token = tokenOf(messenger.sent.getFirst());
        accept(token, "a-long-enough-password-1");

        String rows = jdbc.sql("SELECT row_to_json(t)::text FROM fulfillment.courier_access_invitations t")
                .query(String.class)
                .list()
                .toString();
        String facts = jdbc.sql("SELECT change_document::text || coalesce(reason, '') FROM audit.audit_events")
                .query(String.class)
                .list()
                .toString();
        for (String secret : List.of(PHONE, token, "a-long-enough-password-1")) {
            assertThat(rows).doesNotContain(secret);
            assertThat(facts).doesNotContain(secret);
        }
        assertThat(jdbc.sql("SELECT token_hash FROM fulfillment.courier_access_invitations")
                        .query(String.class)
                        .list())
                .as("a spent link keeps no hash at all")
                .containsOnlyNulls();
        assertThat(jdbc.sql("SELECT action_code FROM audit.audit_events ORDER BY occurred_at")
                        .query(String.class)
                        .list())
                .as("the flow is on the record, and the record is the invitation, not the secrets")
                .contains("courier.access.invited", "courier.access.sent", "courier.access.accepted");
    }

    @Test
    @DisplayName("asking again voids the link already texted, and a second tap within a minute is told to wait")
    void aNewerInvitationVoidsTheOlderOne() throws Exception {
        invite(MANAGER, "INVITE", "en");
        relay.runOnce();
        String first = tokenOf(messenger.sent.getFirst());

        MvcResult tooSoon = invite(MANAGER, "RESET", "en");
        assertThat(tooSoon.getResponse().getStatus()).isEqualTo(429);
        assertThat(json(tooSoon).path("code").asText()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(inspect(first).getResponse().getStatus())
                .as("a refused request voided nothing")
                .isEqualTo(200);

        jdbc.sql("UPDATE fulfillment.courier_access_invitations SET requested_at = requested_at - interval '5 minutes'")
                .update();
        assertThat(invite(MANAGER, "RESET", "uz-Latn").getResponse().getStatus())
                .isEqualTo(202);
        assertThat(inspect(first).getResponse().getStatus()).isEqualTo(404);
        assertThat(json(inspect(first)).path("reason").asText()).isEqualTo("INVALID");

        relay.runOnce();
        assertThat(messenger.sent).hasSize(2);
        assertThat(messenger.sent.get(1).kind()).isEqualTo("RESET");
        assertThat(messenger.sent.get(1).text()).contains("Yangi parolni");
        String second = tokenOf(messenger.sent.get(1));
        assertThat(accept(second, "a-long-enough-password-1").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(jdbc.sql("SELECT status FROM fulfillment.courier_access_invitations ORDER BY requested_at")
                        .query(String.class)
                        .list())
                .containsExactly("VOIDED", "ACCEPTED");
    }

    @Test
    @DisplayName("a password the realm refuses leaves the link usable, and an expired link says so")
    void aRefusedPasswordKeepsTheLinkAndAnExpiredOneIsRefused() throws Exception {
        invite(MANAGER, "INVITE", "en");
        relay.runOnce();
        String token = tokenOf(messenger.sent.getFirst());

        accounts.rejectPasswordsWith = "length(12)";
        MvcResult refused = accept(token, "short");
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(refused).path("field").asText()).isEqualTo("password");
        assertThat(json(refused).path("policy").asText()).isEqualTo("length(12)");
        assertThat(state(MANAGER).path("state").asText())
                .as("the courier may type another")
                .isEqualTo("SENT");

        accounts.rejectPasswordsWith = null;
        jdbc.sql("UPDATE fulfillment.courier_access_invitations SET expires_at = now() - interval '1 minute'")
                .update();
        MvcResult expired = inspect(token);
        assertThat(expired.getResponse().getStatus()).isEqualTo(404);
        assertThat(json(expired).path("reason").asText()).isEqualTo("EXPIRED");
        assertThat(accept(token, "a-long-enough-password-1").getResponse().getStatus())
                .isEqualTo(404);
        assertThat(state(MANAGER).path("state").asText()).isEqualTo("EXPIRED");
        assertThat(accounts.passwords).isEmpty();
    }

    @Test
    @DisplayName("a gateway that cannot be reached is retried with the invitation still queued, then sends")
    void anUnavailableGatewayIsRetried() throws Exception {
        invite(MANAGER, "INVITE", "en");
        messenger.next = CourierAccessMessenger.Outcome.unavailable("GATEWAY_DOWN");

        assertThat(relay.runOnce()).isZero();

        Map<String, Object> row = jdbc.sql(
                        "SELECT status, attempts, last_error_code FROM fulfillment.courier_access_invitations")
                .query((rs, n) -> Map.<String, Object>of(
                        "status", rs.getString("status"),
                        "attempts", rs.getInt("attempts"),
                        "code", rs.getString("last_error_code")))
                .single();
        assertThat(row)
                .containsEntry("status", "QUEUED")
                .containsEntry("attempts", 1)
                .containsEntry("code", "GATEWAY_DOWN");
        assertThat(relay.runOnce())
                .as("not due yet: the backoff is a few minutes")
                .isZero();

        jdbc.sql("UPDATE fulfillment.courier_access_invitations SET next_attempt_at = now() - interval '1 second'")
                .update();
        messenger.next = CourierAccessMessenger.Outcome.accepted();
        assertThat(relay.runOnce()).isEqualTo(1);
        assertThat(state(MANAGER).path("state").asText()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("a gateway that refuses, or a courier with no number on file, fails the invitation with a stable code")
    void aRefusalOrAMissingNumberFailsWithACode() throws Exception {
        invite(MANAGER, "INVITE", "en");
        messenger.next = CourierAccessMessenger.Outcome.refused("SMS_PURPOSE_NOT_PERMITTED");
        relay.runOnce();
        JsonNode refused = state(MANAGER);
        assertThat(refused.path("state").asText()).isEqualTo("FAILED");
        assertThat(refused.path("lastErrorCode").asText()).isEqualTo("SMS_PURPOSE_NOT_PERMITTED");

        messenger.next = CourierAccessMessenger.Outcome.accepted();
        accounts.byId.put(subject, new StaffAccounts.StaffAccount(subject, null, false, false, "not-a-number"));
        assertThat(invite(MANAGER, "INVITE", "en").getResponse().getStatus()).isEqualTo(202);
        relay.runOnce();
        assertThat(state(MANAGER).path("lastErrorCode").asText()).isEqualTo("NO_PHONE_NUMBER");
        assertThat(messenger.sent).as("nothing was sent to a number nobody has").isEmpty();
    }

    @Test
    @DisplayName("with no SMS gateway connected the invitation waits and says so, using up no attempt")
    void withNoGatewayTheInvitationWaits() throws Exception {
        invite(MANAGER, "INVITE", "en");
        CourierAccessRelay unwired = new CourierAccessRelay(
                store,
                couriers,
                accounts,
                new DefaultListableBeanFactory().getBeanProvider(CourierAccessMessenger.class),
                audit,
                java.time.Clock.systemUTC(),
                "https://ops.example.test");

        assertThat(unwired.runOnce()).isZero();

        JsonNode state = state(MANAGER);
        assertThat(state.path("state").asText()).isEqualTo("QUEUED");
        assertThat(state.path("lastErrorCode").asText()).isEqualTo("SMS_NOT_CONNECTED");
        assertThat(jdbc.sql("SELECT attempts FROM fulfillment.courier_access_invitations")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("access is for a courier who works here: an archived one, an ended one and a stranger are refused, "
            + "and a manager's capability is required")
    void accessIsGuarded() throws Exception {
        assertThat(invite(OWNER, "INVITE", "en").getResponse().getStatus())
                .as("an owner reads couriers and does not manage engagements")
                .isEqualTo(403);
        assertThat(mvc.perform(get(accessPath(UUID.randomUUID())).with(tokenFor(MANAGER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("an unknown courier has no access to read")
                .isEqualTo(404);
        assertThat(mvc.perform(post(accessPath(UUID.randomUUID()) + "-invitations")
                                .with(tokenFor(MANAGER))
                                .header("Idempotency-Key", "stranger")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"kind\":\"INVITE\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);

        jdbc.sql("UPDATE fulfillment.courier_engagements SET status = 'ENDED', engaged_until = current_date")
                .update();
        assertThat(invite(MANAGER, "INVITE", "en").getResponse().getStatus()).isEqualTo(409);

        jdbc.sql("UPDATE fulfillment.courier_engagements SET status = 'ACTIVE', engaged_until = NULL")
                .update();
        jdbc.sql("UPDATE fulfillment.couriers SET status = 'ARCHIVED'").update();
        assertThat(invite(MANAGER, "INVITE", "en").getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("a link whose courier was archived after it was sent no longer sets a password")
    void aLinkOutlivesNoArchive() throws Exception {
        invite(MANAGER, "INVITE", "en");
        relay.runOnce();
        String token = tokenOf(messenger.sent.getFirst());
        jdbc.sql("UPDATE fulfillment.couriers SET status = 'ARCHIVED'").update();

        assertThat(accept(token, "a-long-enough-password-1").getResponse().getStatus())
                .isEqualTo(404);
        assertThat(accounts.passwords).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private static String accessPath(UUID courierId) {
        return "/api/v1/operations/tenants/" + TENANT + "/couriers/" + courierId + "/access";
    }

    private MvcResult invite(String caller, String kind, String locale) throws Exception {
        return mvc.perform(post(accessPath(COURIER) + "-invitations")
                        .with(tokenFor(caller))
                        .header("Idempotency-Key", "invite-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"" + kind + "\",\"locale\":\"" + locale + "\"}"))
                .andReturn();
    }

    private JsonNode state(String caller) throws Exception {
        return json(mvc.perform(get(accessPath(COURIER)).with(tokenFor(caller))).andReturn());
    }

    private MvcResult inspect(String token) throws Exception {
        return mvc.perform(post("/api/v1/operations/courier-access/inspect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\"}"))
                .andReturn();
    }

    private MvcResult accept(String token, String password) throws Exception {
        return mvc.perform(post("/api/v1/operations/courier-access/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    private static String tokenOf(CourierAccessMessenger.AccessMessage message) {
        String link = message.link();
        return link.substring(link.indexOf("#token=") + "#token=".length());
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void insertCourier(UUID id, String principalSubject, String reference) {
        jdbc.sql("""
                INSERT INTO fulfillment.couriers
                    (id, tenant_id, courier_type_id, principal_subject, display_reference, protected_full_name, status)
                VALUES (:id, :t, :type, :subject, :reference, 'ciphertext-not-exercised-here', 'ACTIVE')
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("type", COURIER_TYPE)
                .param("subject", principalSubject)
                .param("reference", reference)
                .update();
    }

    private void seedEngagement(UUID courierId) {
        Instant now = Instant.now();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_engagements (
                    id, tenant_id, courier_id, engagement_type, status, engaged_from,
                    protected_registration_ref, registration_valid_until,
                    registration_verified_at, registration_verified_by, verification_method,
                    reverification_due_on, warning_state, version, created_at, updated_at)
                VALUES (:id, :t, :c, 'SELF_EMPLOYED', 'ACTIVE', :from,
                    'ciphertext-not-exercised-here', :until, :now, 'test-fixture', 'MANUAL_ATTESTATION',
                    :due, 'VALID', 1, :now, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("c", courierId)
                .param("from", now.atOffset(ZoneOffset.UTC).toLocalDate().minusMonths(1))
                .param("until", now.atOffset(ZoneOffset.UTC).toLocalDate().plusYears(1))
                .param("due", now.atOffset(ZoneOffset.UTC).toLocalDate().plusMonths(6))
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void grant(String grantee, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'courier access endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((grantee + role.code() + TENANT).getBytes(StandardCharsets.UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", grantee)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String who) {
        return jwt().jwt(builder ->
                builder.subject(who).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Stubs {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }

        @Bean
        @Primary
        FakeStaffAccounts stubStaffAccounts() {
            return new FakeStaffAccounts();
        }

        @Bean
        RecordingMessenger recordingMessenger() {
            return new RecordingMessenger();
        }

        /** The SMS gateway as a list: what the relay handed it, and what it should answer next. */
        static class RecordingMessenger implements CourierAccessMessenger {

            final List<AccessMessage> sent = new ArrayList<>();
            volatile Outcome next = Outcome.accepted();

            void reset() {
                sent.clear();
                next = Outcome.accepted();
            }

            @Override
            public Outcome send(AccessMessage message) {
                Outcome outcome = next;
                if (outcome.status() == Outcome.Status.ACCEPTED) {
                    sent.add(message);
                }
                return outcome;
            }
        }

        /** The identity provider as a map: accounts, the passwords set on them, and who was signed out. */
        static class FakeStaffAccounts implements StaffAccounts {

            final Map<String, StaffAccount> byId = new HashMap<>();
            final Map<String, String> passwords = new HashMap<>();
            final List<String> loggedOut = new ArrayList<>();
            volatile @Nullable String rejectPasswordsWith;

            void reset() {
                byId.clear();
                passwords.clear();
                loggedOut.clear();
                rejectPasswordsWith = null;
            }

            @Override
            public Optional<StaffAccount> find(String subjectId) {
                return Optional.ofNullable(byId.get(subjectId));
            }

            @Override
            public StaffAccount create(String firstName, String lastName, String phone, @Nullable String email) {
                String subjectId = "courier-access-" + UUID.randomUUID();
                StaffAccount account = new StaffAccount(subjectId, email, false, false, phone);
                byId.put(subjectId, account);
                return account;
            }

            @Override
            public void setPassword(String subjectId, String password) {
                String policy = rejectPasswordsWith;
                if (policy != null) {
                    throw new PasswordRejectedException(policy);
                }
                passwords.put(subjectId, password);
            }

            @Override
            public void logoutEverywhere(String subjectId) {
                loggedOut.add(subjectId);
            }

            @Override
            public void completeSetup(String subjectId, String firstName, String lastName, String password) {
                throw new UnsupportedOperationException("not part of this fixture");
            }

            @Override
            public Optional<StaffAccount> findByLogin(String usernameOrEmail) {
                throw new UnsupportedOperationException("not part of this fixture");
            }

            @Override
            public Optional<String> findSubjectIdByLogin(String usernameOrEmail) {
                throw new UnsupportedOperationException("not part of this fixture");
            }
        }
    }
}
