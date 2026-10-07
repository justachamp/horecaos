package uz.horecaos.platform.iam.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.mail.StaffEmail;
import uz.horecaos.platform.iam.api.mail.StaffEmailSender;
import uz.horecaos.platform.iam.application.staff.StaffMemberService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.iam.infrastructure.keycloak.FakeKeycloakMfaRealm;
import uz.horecaos.platform.iam.infrastructure.keycloak.FakeKeycloakMfaRealm.Totp;
import uz.horecaos.platform.iam.infrastructure.keycloak.FakeKeycloakMfaRealm.User;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffDirectGrantClient;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffPasswordCheckClient;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0148 over the real HTTP stack, with a stateful fake of Keycloak behind the real adapters:
 * the sign-in second step, enrolment from a ticket and from a session, the person's own
 * authenticator list and removal, the administrator's read and reset, the platform account's
 * reset behind a second signature, and a tenant opening the setting.
 *
 * <p>Sign-in requests come from a different source address every time on purpose: the per-address
 * budget of ADR 0033 would otherwise answer 429 before the account's own code budget could, and
 * the point of that budget is that a rotating address does not refill it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaffMfaEndpointTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "correct horse battery";

    private static final UUID TENANT = UUID.fromString("018fb100-5000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb100-5000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fb100-5000-7000-8000-0000000000c1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fb100-5000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018fb100-5000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOCATION = UUID.fromString("018fb100-5000-7000-8000-0000000000c2");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @SuppressWarnings("NullAway")
    private static FakeKeycloakMfaRealm realm;

    private static final AtomicInteger counter = new AtomicInteger();
    private static final List<StaffEmail> EMAILS = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this endpoint test");
    }

    @AfterAll
    static void stopKeycloak() {
        if (realm != null) {
            realm.close();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        db = TestDatabase.migrated();
        realm = FakeKeycloakMfaRealm.start(Clock.systemUTC());
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
        registry.add("horecaos.secrets.data_encryption.platform.mfa-enrolment-sealing-key", () -> "a-test-sealing-key");
        // The platform rule at its last phase: an account holding a platform-scope grant needs a factor.
        registry.add("horecaos.iam.mfa.enforcement", () -> "REQUIRED");
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
    private StaffMemberService members;

    @Autowired
    @SuppressWarnings("NullAway")
    private TransactionTemplate tx;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("DELETE FROM audit.approval_requests").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        // TRUNCATE ... CASCADE empties every table that references tenants, and the platform-scope
        // policy V0500 seeds is one of its rows: put it back, exactly as the migration wrote it.
        jdbc.sql("""
                INSERT INTO audit.approval_policies (
                    id, tenant_id, action_code, scope_type, threshold_json, required_approver_capability,
                    valid_from, version, approved_by)
                VALUES (
                    '0192d1a0-0000-7000-8000-000000000500', NULL, 'iam.staff.mfa.reset', 'PLATFORM',
                    '{"description": "Removing a platform account''s second factor, every time"}'::jsonb,
                    'iam.staff.mfa.reset',
                    '2026-10-07T00:00:00Z', 1, 'migration V0500')
                ON CONFLICT DO NOTHING
                """).update();
        EMAILS.clear();
        seedTenancy(TENANT, BRAND, LOCATION, "mfa-staff-a");
        seedTenancy(OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, "mfa-staff-b");
    }

    // ================================================================ the sign-in second step

    @Test
    @DisplayName(
            "an enrolled account is asked for its code, refused a wrong one, and signed in by a right one; the answers are the ADR's")
    void theSecondStepOverHttp() throws Exception {
        User cook = person("cook");
        member(TENANT, cook);
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        String secret = enrolDirectly(cook);

        MvcResult asked = signIn(cook.username, PASSWORD, null);
        MvcResult wrong = signIn(cook.username, PASSWORD, "000000");
        MvcResult right = signIn(cook.username, PASSWORD, Totp.code(secret, Instant.now()));

        assertThat(asked.getResponse().getStatus()).isEqualTo(401);
        assertThat(code(asked)).isEqualTo("MFA_REQUIRED");
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(code(wrong)).isEqualTo("MFA_CODE_INVALID");
        assertThat(right.getResponse().getStatus()).isEqualTo(201);
        JsonNode session = json(right);
        assertThat(session.get("accessToken").asText()).isNotBlank();
        assertThat(session.get("mfaEnrolmentOffered").asBoolean()).isFalse();
    }

    @Test
    @DisplayName(
            "a wrong password and an unknown name answer byte for byte alike, with or without a code, and the login client is never asked")
    void theUniformFailureOverHttp() throws Exception {
        User cook = person("cook");
        enrolDirectly(cook);
        realm.clearCalls();

        List<String> bodies = new ArrayList<>();
        for (MvcResult result : List.of(
                signIn(cook.username, "not the password", null),
                signIn(cook.username, "not the password", "123456"),
                signIn("998900000000", "whatever it is", null),
                signIn("998900000000", "whatever it is", "123456"))) {
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            JsonNode body = json(result);
            assertThat(body.get("code").asText()).isEqualTo("UNAUTHENTICATED");
            bodies.add(body.get("detail").asText() + "|" + body.get("title").asText());
        }

        assertThat(bodies).containsOnly("Invalid credentials.|Authentication required");
        assertThat(realm.codesSeenByLoginClient()).isEmpty();
        assertThat(realm.calls("TOKEN", FakeKeycloakMfaRealm.LOGIN_CLIENT)).isEmpty();
    }

    @Test
    @DisplayName(
            "the sixth code in the hour is a 429 with retryAfterSeconds, from rotating addresses, and Keycloak sees five")
    void theCodeBudgetOverHttp() throws Exception {
        User cook = person("cook");
        String secret = enrolDirectly(cook);
        realm.clearCalls();

        List<Integer> statuses = new ArrayList<>();
        MvcResult last = null;
        for (int attempt = 1; attempt <= 8; attempt++) {
            last = signIn(cook.username, PASSWORD, "%06d".formatted(100_000 + attempt));
            statuses.add(last.getResponse().getStatus());
        }

        assertThat(statuses).containsExactly(401, 401, 401, 401, 401, 429, 429, 429);
        assertThat(code(last)).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(json(last).get("retryAfterSeconds").asLong()).isPositive();
        assertThat(realm.codesSeenByLoginClient()).hasSize(5);
        // The right code is refused too, while the budget is spent.
        MvcResult right = signIn(cook.username, PASSWORD, Totp.code(secret, Instant.now()));
        assertThat(right.getResponse().getStatus()).isEqualTo(429);
        assertThat(realm.codesSeenByLoginClient()).hasSize(5);
    }

    // ================================================================ enforcement and enrolment

    @Test
    @DisplayName(
            "a platform administrator with no factor is told to enrol, scans, confirms the first code from the ticket and arrives signed in")
    void aPlatformAdministratorEnrolsFromATicket() throws Exception {
        User admin = person("admin");
        grant(admin.id, PlatformRole.PLATFORM_ADMIN, "PLATFORM", null, null);

        MvcResult refused = signIn(admin.username, PASSWORD, null);

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(refused)).isEqualTo("MFA_ENROLMENT_REQUIRED");
        String ticket = json(refused).get("enrolmentTicket").asText();
        assertThat(json(refused).get("expiresAt").asText()).isNotBlank();
        assertThat(realm.calls("REVOKE", FakeKeycloakMfaRealm.LOGIN_CLIENT)).isNotEmpty();

        // No bearer: the ticket is the caller.
        MvcResult begun = post(
                "/api/v1/control-plane/auth/mfa/enrolments",
                "{\"password\":\"" + PASSWORD + "\",\"enrolmentTicket\":\"" + ticket + "\"}",
                null);
        assertThat(begun.getResponse().getStatus()).isEqualTo(200);
        JsonNode enrolment = json(begun);
        assertThat(enrolment.get("otpauthUri").asText()).startsWith("otpauth://totp/HorecaOS");
        assertThat(admin.otp)
                .as("nothing exists until the first code is confirmed")
                .isEmpty();
        String secret = secretOf(enrolment.get("secret").asText());

        MvcResult confirmed = post(
                "/api/v1/control-plane/auth/mfa/enrolments/confirm",
                "{\"sealedSecret\":\"%s\",\"code\":\"%s\",\"password\":\"%s\",\"label\":\"my phone\",\"enrolmentTicket\":\"%s\"}"
                        .formatted(
                                enrolment.get("sealedSecret").asText(),
                                Totp.code(secret, Instant.now()),
                                PASSWORD,
                                ticket),
                null);
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(confirmed).get("accessToken").asText()).isNotBlank();
        assertThat(admin.otp).hasSize(1);
        assertThat(EMAILS).hasSize(1);
        assertThat(auditCount("iam.staff.mfa.enrolled")).isEqualTo(1);

        // Signed out, and back in with the password and the next code.
        MvcResult back =
                signIn(admin.username, PASSWORD, Totp.code(secret, Instant.now().plusSeconds(30)));
        assertThat(back.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName(
            "the enrolment endpoints take a session or a ticket and nothing else, and the list and removal take a session only")
    void whoMayCallWhat() throws Exception {
        String body = "{\"password\":\"" + PASSWORD + "\"}";

        assertThat(post("/api/v1/operations/auth/mfa/enrolments", body, null)
                        .getResponse()
                        .getStatus())
                .as("neither a session nor a ticket")
                .isEqualTo(401);
        MvcResult garbage = post(
                "/api/v1/operations/auth/mfa/enrolments",
                "{\"password\":\"" + PASSWORD + "\",\"enrolmentTicket\":\"not-a-ticket\"}",
                null);
        assertThat(garbage.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(garbage)).isEqualTo("INVALID_REQUEST");
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/api/v1/operations/auth/mfa/authenticators"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(mvc.perform(MockMvcRequestBuilders.delete("/api/v1/operations/auth/mfa/authenticators/some-id")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"x\",\"code\":\"123456\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("a ticket from one console's sign-in is not an enrolment for any other account")
    void aTicketNamesOneAccount() throws Exception {
        User admin = person("admin");
        grant(admin.id, PlatformRole.PLATFORM_ADMIN, "PLATFORM", null, null);
        User other = realm.addUser("99891%07d".formatted(counter.incrementAndGet()), "another long passphrase", null);
        String ticket = json(signIn(admin.username, PASSWORD, null))
                .get("enrolmentTicket")
                .asText();

        // The ticket's account is the admin; the password offered is the other person's.
        MvcResult mismatched = post(
                "/api/v1/operations/auth/mfa/enrolments",
                "{\"password\":\"" + other.password() + "\",\"enrolmentTicket\":\"" + ticket + "\"}",
                null);

        assertThat(mismatched.getResponse().getStatus()).isEqualTo(422);
        assertThat(code(mismatched)).isEqualTo("CURRENT_PASSWORD_INVALID");
    }

    @Test
    @DisplayName("a signed-in person lists, adds a second device and removes one with their password and a code")
    void ownAuthenticators() throws Exception {
        User owner = person("owner");
        member(TENANT, owner);
        grant(owner.id, PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);
        String first = enrolDirectly(owner);

        MvcResult listed = get("/api/v1/operations/auth/mfa/authenticators", owner.id);
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(listed).get("enrolled").asBoolean()).isTrue();
        assertThat(json(listed).get("authenticators")).hasSize(1);
        assertThat(json(listed).get("maximum").asInt()).isEqualTo(2);

        MvcResult begun =
                post("/api/v1/operations/auth/mfa/enrolments", "{\"password\":\"" + PASSWORD + "\"}", owner.id);
        assertThat(begun.getResponse().getStatus()).isEqualTo(200);
        String second = secretOf(json(begun).get("secret").asText());
        MvcResult confirmed = post(
                "/api/v1/operations/auth/mfa/enrolments/confirm",
                "{\"sealedSecret\":\"%s\",\"code\":\"%s\",\"password\":\"%s\",\"label\":\"tablet\"}"
                        .formatted(
                                json(begun).get("sealedSecret").asText(), Totp.code(second, Instant.now()), PASSWORD),
                owner.id);
        assertThat(confirmed.getResponse().getStatus())
                .as("from a session: no content")
                .isEqualTo(204);
        assertThat(owner.otp).hasSize(2);
        assertThat(auditCount("iam.staff.mfa.authenticator_added")).isEqualTo(1);

        String tabletId = owner.otp.get(1).id;
        MvcResult removed = delete(
                "/api/v1/operations/auth/mfa/authenticators/" + tabletId,
                "{\"password\":\"%s\",\"code\":\"%s\"}".formatted(PASSWORD, Totp.code(first, Instant.now())),
                owner.id);
        assertThat(removed.getResponse().getStatus()).isEqualTo(204);
        assertThat(owner.otp).hasSize(1);
        assertThat(auditCount("iam.staff.mfa.authenticator_removed")).isEqualTo(1);

        MvcResult lastOne = delete(
                "/api/v1/operations/auth/mfa/authenticators/" + owner.otp.get(0).id,
                "{\"password\":\"%s\",\"code\":\"%s\"}"
                        .formatted(PASSWORD, Totp.code(first, Instant.now().plusSeconds(30))),
                owner.id);
        assertThat(lastOne.getResponse().getStatus()).isEqualTo(422);
        assertThat(owner.otp).hasSize(1);
    }

    @Test
    @DisplayName(
            "a bad body is refused the way the console's real JSON is: a missing password is a 400 and a code that is not six digits is a 400")
    void malformedBodies() throws Exception {
        User owner = person("owner");
        grant(owner.id, PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);

        assertThat(post("/api/v1/operations/auth/mfa/enrolments", "{}", owner.id)
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(post(
                                "/api/v1/operations/auth/mfa/enrolments/confirm",
                                "{\"sealedSecret\":\"x\",\"code\":\"12a456\",\"password\":\"p\"}",
                                owner.id)
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
    }

    // ================================================================ the administrator

    @Test
    @DisplayName("an owner reads whether a person holds a second factor; finance and another tenant's owner cannot")
    void theAdministratorsRead() throws Exception {
        User owner = person("owner");
        grant(owner.id, PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);
        User cook = person("cook");
        member(TENANT, cook);
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        User finance = person("finance");
        grant(finance.id, PlatformRole.TENANT_FINANCE, "TENANT", TENANT, TENANT);
        User stranger = person("stranger");
        grant(stranger.id, PlatformRole.TENANT_OWNER, "TENANT", OTHER_TENANT, OTHER_TENANT);
        enrolDirectly(cook);

        MvcResult read = get(mfaPath(cook), owner.id);
        MvcResult summary = get("/api/v1/operations/tenants/" + TENANT + "/staff/mfa-summary", owner.id);

        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(read).get("enrolled").asBoolean()).isTrue();
        assertThat(json(read).get("authenticators").get(0).has("id")).isTrue();
        assertThat(read.getResponse().getContentAsString(UTF_8)).doesNotContain("secret");
        assertThat(json(summary).get("members")).hasSize(1);
        assertThat(json(summary).get("members").get(0).get("enrolled").asBoolean())
                .isTrue();
        assertThat(get(mfaPath(cook), finance.id).getResponse().getStatus()).isEqualTo(403);
        assertThat(get(mfaPath(cook), stranger.id).getResponse().getStatus()).isEqualTo(403);
        assertThat(get(mfaPath(cook), cook.id).getResponse().getStatus())
                .as("not even the person, by this route")
                .isEqualTo(403);
    }

    @Test
    @DisplayName(
            "an administrator resets a person's second factor: authenticators removed, sessions ended, audited with the reason, emailed; and the guards hold")
    void aTenantReset() throws Exception {
        User owner = person("owner");
        member(TENANT, owner);
        grant(owner.id, PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);
        User admin = person("admin");
        member(TENANT, admin);
        grant(admin.id, PlatformRole.TENANT_ADMIN, "TENANT", TENANT, TENANT);
        User cook = person("cook");
        member(TENANT, cook);
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        enrolDirectly(cook);
        enrolDirectly(owner);
        User platform = person("platform");
        member(TENANT, platform);
        grant(platform.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        grant(platform.id, PlatformRole.PLATFORM_SUPPORT, "PLATFORM", null, null);

        String resetCook = mfaPath(cook) + "/resets";
        MvcResult stale = reset(resetCook, admin.id, "{\"reason\":\"Lost phone\"}", versionOf(cook) + 5);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(cook.otp).hasSize(1);

        MvcResult done =
                reset(resetCook, admin.id, "{\"reason\":\"Lost phone, confirmed in person\"}", versionOf(cook));
        assertThat(done.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(done).get("authenticatorsRemoved").asInt()).isEqualTo(1);
        assertThat(json(done).get("sessionsEnded").asBoolean()).isTrue();
        assertThat(cook.otp).isEmpty();
        assertThat(auditCount("iam.staff.mfa.reset")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT string_agg(change_document::text, ' ') FROM audit.audit_events")
                        .query(String.class)
                        .single())
                .doesNotContain("Aziza's", "otp", "secret");

        assertThat(reset(mfaPath(admin) + "/resets", admin.id, "{\"reason\":\"me\"}", versionOf(admin))
                        .getResponse()
                        .getStatus())
                .as("nobody resets their own")
                .isEqualTo(422);
        MvcResult ownerByAdmin = reset(mfaPath(owner) + "/resets", admin.id, "{\"reason\":\"x\"}", versionOf(owner));
        assertThat(ownerByAdmin.getResponse().getStatus())
                .as("an owner's reset is platform support's, inside a support session")
                .isEqualTo(403);
        assertThat(owner.otp).hasSize(1);
        MvcResult platformByAdmin =
                reset(mfaPath(platform) + "/resets", admin.id, "{\"reason\":\"x\"}", versionOf(platform));
        assertThat(platformByAdmin.getResponse().getStatus())
                .as("a platform account is reset on the control plane, with a second signature")
                .isEqualTo(422);
    }

    @Test
    @DisplayName(
            "only an owner or an administrator holds the reset; finance, a branch manager and the person themselves are refused")
    void whoMayReset() throws Exception {
        User cook = person("cook");
        member(TENANT, cook);
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        enrolDirectly(cook);
        for (PlatformRole refused : new PlatformRole[] {PlatformRole.TENANT_FINANCE, PlatformRole.BRAND_MANAGER}) {
            User person = person(refused.code());
            grant(
                    person.id,
                    refused,
                    refused == PlatformRole.BRAND_MANAGER ? "BRAND" : "TENANT",
                    refused == PlatformRole.BRAND_MANAGER ? BRAND : TENANT,
                    TENANT);
            MvcResult result = reset(mfaPath(cook) + "/resets", person.id, "{\"reason\":\"x\"}", 1);
            assertThat(result.getResponse().getStatus()).as(refused.name()).isEqualTo(403);
            assertThat(code(result)).isEqualTo("INSUFFICIENT_CAPABILITY");
        }
        assertThat(cook.otp).hasSize(1);
    }

    // ================================================================ a platform account's reset

    @Test
    @DisplayName(
            "a platform account's reset waits for a second administrator, and runs only when the first repeats it after approval")
    void aPlatformReset() throws Exception {
        User first = person("admin-one");
        grant(first.id, PlatformRole.PLATFORM_ADMIN, "PLATFORM", null, null);
        User second = person("admin-two");
        grant(second.id, PlatformRole.PLATFORM_ADMIN, "PLATFORM", null, null);
        User target = person("admin-three");
        grant(target.id, PlatformRole.PLATFORM_ADMIN, "PLATFORM", null, null);
        enrolDirectly(target);
        String path = "/api/v1/control-plane/staff/" + target.id + "/mfa";

        MvcResult read = get(path, first.id);
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(read).get("requirement").asText()).isEqualTo("REQUIRED");

        MvcResult waiting = post(path + "/resets", "{\"reason\":\"Phone lost on a flight\"}", first.id);
        assertThat(waiting.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(waiting).get("outcome").asText()).isEqualTo("AWAITING_APPROVAL");
        String requestId = json(waiting).get("approvalRequestId").asText();
        assertThat(target.otp).as("one signature removes nothing").hasSize(1);
        assertThat(auditCount("iam.staff.mfa.reset")).isZero();

        MvcResult selfApproval = post(
                "/api/v1/control-plane/approval-requests/" + requestId + "/decision",
                "{\"decision\":\"APPROVE\",\"reason\":\"me too\"}",
                first.id);
        assertThat(selfApproval.getResponse().getStatus()).isEqualTo(403);
        assertThat(target.otp).hasSize(1);

        MvcResult approved = post(
                "/api/v1/control-plane/approval-requests/" + requestId + "/decision",
                "{\"decision\":\"APPROVE\",\"reason\":\"Confirmed by phone with the on-call lead\"}",
                second.id);
        assertThat(approved.getResponse().getStatus()).isEqualTo(200);
        assertThat(target.otp).as("approving is not resetting").hasSize(1);

        MvcResult done = post(path + "/resets", "{\"reason\":\"Phone lost on a flight\"}", first.id);
        assertThat(json(done).get("outcome").asText()).isEqualTo("RESET");
        assertThat(json(done).get("authenticatorsRemoved").asInt()).isEqualTo(1);
        assertThat(target.otp).isEmpty();
        assertThat(auditCount("iam.staff.mfa.reset")).isEqualTo(1);

        MvcResult again = post(path + "/resets", "{\"reason\":\"Phone lost on a flight\"}", first.id);
        assertThat(json(again).get("outcome").asText())
                .as("the approval was spent")
                .isEqualTo("AWAITING_APPROVAL");
    }

    @Test
    @DisplayName(
            "the platform route answers not-found for an account with no platform grant, and refuses a reset of one's own")
    void thePlatformRouteIsNarrow() throws Exception {
        User first = person("admin-one");
        grant(first.id, PlatformRole.PLATFORM_ADMIN, "PLATFORM", null, null);
        User cook = person("cook");
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        User owner = person("owner");
        grant(owner.id, PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);

        assertThat(get("/api/v1/control-plane/staff/" + cook.id + "/mfa", first.id)
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(post("/api/v1/control-plane/staff/" + cook.id + "/mfa/resets", "{\"reason\":\"x\"}", first.id)
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(post("/api/v1/control-plane/staff/" + first.id + "/mfa/resets", "{\"reason\":\"x\"}", first.id)
                        .getResponse()
                        .getStatus())
                .isEqualTo(422);
        assertThat(get("/api/v1/control-plane/staff/" + first.id + "/mfa", owner.id)
                        .getResponse()
                        .getStatus())
                .as("a tenant owner holds no platform capability")
                .isEqualTo(403);
    }

    // ================================================================ the tenant's setting

    @Test
    @DisplayName(
            "a tenant opens the setting and its finance user is asked to enrol while a cook is not; a mistyped value is refused")
    void theTenantSetting() throws Exception {
        User owner = person("owner");
        member(TENANT, owner);
        grant(owner.id, PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);
        User finance = person("finance");
        member(TENANT, finance);
        grant(finance.id, PlatformRole.TENANT_FINANCE, "TENANT", TENANT, TENANT);
        User cook = person("cook");
        member(TENANT, cook);
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        String values = "/api/v1/operations/tenants/" + TENANT + "/configuration/keys/iam.staff_mfa_requirement/values";

        assertThat(signIn(finance.username, PASSWORD, null).getResponse().getStatus())
                .as("off by default: nobody is asked")
                .isEqualTo(201);

        MvcResult mistyped = post(values, setting("SOMETIMES"), owner.id);
        assertThat(mistyped.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(mistyped)).isEqualTo("VALIDATION_FAILED");
        MvcResult opened = post(values, setting("SENSITIVE_ROLES"), owner.id);
        assertThat(opened.getResponse().getStatus()).isEqualTo(200);

        MvcResult financeAsked = signIn(finance.username, PASSWORD, null);
        assertThat(financeAsked.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(financeAsked)).isEqualTo("MFA_ENROLMENT_REQUIRED");
        assertThat(signIn(cook.username, PASSWORD, null).getResponse().getStatus())
                .isEqualTo(201);

        long version = json(opened).get("version").asLong();
        MvcResult everyone = post(
                values,
                setting("ALL_STAFF").replace("\"expectedVersion\":null", "\"expectedVersion\":" + version),
                owner.id);
        assertThat(everyone.getResponse().getStatus()).isEqualTo(200);
        assertThat(code(signIn(cook.username, PASSWORD, null))).isEqualTo("MFA_ENROLMENT_REQUIRED");
    }

    // ================================================================ fixtures

    private static String setting(String mode) {
        return "{\"scopeType\":\"TENANT\",\"explicitNull\":false,\"stringValue\":\"%s\",\"expectedVersion\":null,\"reason\":\"test\"}"
                .formatted(mode);
    }

    private User person(String label) {
        int n = counter.incrementAndGet();
        return realm.addUser("99890%07d".formatted(n), PASSWORD, label + n + "@example.test");
    }

    /** An authenticator enrolled long ago; the secret is Keycloak's own twenty-character form. */
    private String enrolDirectly(User user) {
        String secret = "Zk3fT9qLw2XvB7%06d".formatted(counter.incrementAndGet());
        realm.enrol(user, secret, "phone");
        return secret;
    }

    private static String secretOf(String base32) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : base32.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private String mfaPath(User person) {
        return "/api/v1/operations/tenants/" + TENANT + "/staff/members/" + memberId(person) + "/mfa";
    }

    private UUID memberId(User person) {
        return jdbc.sql("SELECT id FROM iam.staff_members WHERE tenant_id = :t AND principal_subject = :s")
                .param("t", TENANT)
                .param("s", person.id)
                .query(UUID.class)
                .single();
    }

    private int versionOf(User person) {
        return jdbc.sql("SELECT version FROM iam.staff_members WHERE tenant_id = :t AND principal_subject = :s")
                .param("t", TENANT)
                .param("s", person.id)
                .query(Integer.class)
                .single();
    }

    private int auditCount(String action) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = :a")
                .param("a", action)
                .query(Integer.class)
                .single();
    }

    private static int addressCounter = 0;

    private MvcResult signIn(String username, String password, @Nullable String otp) throws Exception {
        String body = otp == null
                ? "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)
                : "{\"username\":\"%s\",\"password\":\"%s\",\"otp\":\"%s\"}".formatted(username, password, otp);
        int n = ++addressCounter;
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/operations/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(request -> {
                            request.setRemoteAddr("10.%d.%d.%d".formatted((n >> 16) & 255, (n >> 8) & 255, n & 255));
                            return request;
                        }))
                .andReturn();
    }

    private MvcResult get(String path, String subject) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).with(tokenFor(subject)))
                .andReturn();
    }

    private MvcResult post(String path, String json, @Nullable String subject) throws Exception {
        var request = MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (subject != null) {
            request.with(tokenFor(subject));
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult delete(String path, String json, String subject) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.delete(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(tokenFor(subject)))
                .andReturn();
    }

    private MvcResult reset(String path, String subject, String json, int ifMatch) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.request(HttpMethod.POST, path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .header("If-Match", "W/\"" + ifMatch + "\"")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(tokenFor(subject)))
                .andReturn();
    }

    private static JsonNode json(@Nullable MvcResult result) throws Exception {
        return JSON.readTree(
                java.util.Objects.requireNonNull(result).getResponse().getContentAsString(UTF_8));
    }

    private static String code(@Nullable MvcResult result) throws Exception {
        return json(result).get("code").asText();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    private void member(UUID tenantId, User person) {
        tx.executeWithoutResult(status -> {
            members.registerInvited(tenantId, person.id, "Test", "Person", null, "fixture-inviter", "corr-fixture");
            members.activate(tenantId, person.id, "Test", "Person", "corr-fixture");
        });
    }

    private void grant(
            String subject, PlatformRole role, String scopeType, @Nullable UUID scopeId, @Nullable UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'staff mfa endpoint test', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void seedTenancy(UUID tenantId, UUID brandId, UUID locationId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", brandId).param("t", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'L1', 'l1', 'L1', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("t", tenantId)
                .param("b", brandId)
                .update();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeKeycloak {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }

        @Bean
        @Primary
        StaffDirectGrantClient fakeLoginClient() {
            return realm.adapters().login();
        }

        @Bean
        @Primary
        StaffPasswordCheckClient fakePasswordCheckClient() {
            return realm.adapters().probe();
        }

        @Bean
        @Primary
        StaffAccounts fakeStaffAccounts() {
            return realm.adapters().accounts();
        }

        @Bean
        @Primary
        StaffEmailSender recordingMail() {
            return new StaffEmailSender() {
                @Override
                public Delivery send(StaffEmail email) {
                    EMAILS.add(email);
                    return Delivery.SENT;
                }

                @Override
                public boolean configured() {
                    return true;
                }
            };
        }
    }
}
