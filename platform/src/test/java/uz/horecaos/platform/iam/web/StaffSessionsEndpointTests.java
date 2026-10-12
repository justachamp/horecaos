package uz.horecaos.platform.iam.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffSessions;
import uz.horecaos.platform.iam.application.staff.StaffMemberService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.iam.infrastructure.keycloak.FakeKeycloakMfaRealm;
import uz.horecaos.platform.iam.infrastructure.keycloak.FakeKeycloakMfaRealm.User;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffDirectGrantClient;
import uz.horecaos.platform.iam.infrastructure.keycloak.StaffPasswordCheckClient;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0176 over the real HTTP stack, with a stateful fake of Keycloak behind the real adapters:
 * the sign-in recorded per tenant, the sessions card's list, «Выйти на всех устройствах» and
 * ending one session, and the fallbacks when Keycloak cannot be asked.
 *
 * <p>Each sign-in comes from a different source address (the per-address budget of ADR 0033
 * would otherwise answer 429 first), and the address and the {@code User-Agent} are distinctive
 * strings so the canary test can prove neither is kept anywhere.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaffSessionsEndpointTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PASSWORD = "correct horse battery";

    private static final String CHROME_ON_WINDOWS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36";
    private static final String SAFARI_ON_IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Version/17.5 Mobile/15E148 Safari/604.1";

    private static final UUID TENANT = UUID.fromString("018fb100-6000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb100-6000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fb100-6000-7000-8000-0000000000c1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fb100-6000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018fb100-6000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOCATION = UUID.fromString("018fb100-6000-7000-8000-0000000000c2");
    private static final String ORG = "org-sessions-a";
    private static final String OTHER_ORG = "org-sessions-b";
    private static final String STRANGER_ORG = "org-sessions-c";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @SuppressWarnings("NullAway")
    private static FakeKeycloakMfaRealm realm;

    private static final AtomicInteger counter = new AtomicInteger();

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

    @Autowired
    @SuppressWarnings("NullAway")
    private MeterRegistry meters;

    @BeforeEach
    void reset() {
        realm.failSessionAdmin(false);
        realm.failLogout(false);
        realm.clearCalls();
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        seedTenancy(TENANT, BRAND, LOCATION, "sessions-a", ORG);
        seedTenancy(OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, "sessions-b", OTHER_ORG);
    }

    // ============================================================================ recording

    @Test
    @DisplayName("a sign-in writes one row for each tenant the token names that keeps a record of the person")
    void aSignInIsRecordedPerTenant() throws Exception {
        User cook = person("cook");
        cook.organizationIds.addAll(List.of(ORG, OTHER_ORG, STRANGER_ORG));
        member(TENANT, cook);
        member(OTHER_TENANT, cook);

        MvcResult signedIn = signIn(cook.username, CHROME_ON_WINDOWS, "198.51.100.1");

        assertThat(signedIn.getResponse().getStatus()).isEqualTo(201);
        String sid =
                FakeKeycloakMfaRealm.sidOf(json(signedIn).get("accessToken").asText());
        assertThat(sid).isNotBlank();
        List<Map<String, Object>> rows = jdbc.sql("""
                        SELECT tenant_id, keycloak_session_id, browser_family, os_family, device_form, ended_at
                          FROM iam.staff_sign_ins ORDER BY tenant_id
                        """).query().listOfRows();
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(row -> row.get("tenant_id")).containsExactlyInAnyOrder(TENANT, OTHER_TENANT);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("keycloak_session_id")).isEqualTo(sid);
            assertThat(row.get("browser_family")).isEqualTo("CHROME");
            assertThat(row.get("os_family")).isEqualTo("WINDOWS");
            assertThat(row.get("device_form")).isEqualTo("DESKTOP");
            assertThat(row.get("ended_at")).isNull();
        });
    }

    @Test
    @DisplayName("a token that names no organization is signed in and not recorded")
    void aTokenWithNoOrganizationIsNotRecorded() throws Exception {
        User admin = person("platform");
        member(TENANT, admin);

        MvcResult signedIn = signIn(admin.username, CHROME_ON_WINDOWS, "198.51.100.2");

        assertThat(signedIn.getResponse().getStatus()).isEqualTo(201);
        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("a ledger that cannot write leaves the sign-in a 201 with tokens, and counts the failure")
    void recordingNeverFailsASignIn() throws Exception {
        User cook = person("cook");
        cook.organizationIds.add(ORG);
        member(TENANT, cook);
        double before = failedRecordings();
        jdbc.sql("ALTER TABLE iam.staff_sign_ins RENAME TO staff_sign_ins_broken")
                .update();
        MvcResult signedIn;
        try {
            signedIn = signIn(cook.username, CHROME_ON_WINDOWS, "198.51.100.3");
        } finally {
            jdbc.sql("ALTER TABLE iam.staff_sign_ins_broken RENAME TO staff_sign_ins")
                    .update();
        }

        assertThat(signedIn.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(signedIn).get("accessToken").asText()).isNotBlank();
        assertThat(failedRecordings()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("neither the User-Agent nor the address is kept in a row, a fact, a response or the log")
    void theCanary() throws Exception {
        String agent = "CanaryAgent/9.9 (X11; Linux x86_64) Chrome/126.0 CANARY-UA-7f3a91";
        String address = "198.51.100.201";
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
        List<String> seen = new ArrayList<>();
        try {
            User cook = person("cook");
            cook.organizationIds.add(ORG);
            member(TENANT, cook);
            grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);

            MvcResult signedIn = signIn(cook.username, agent, address);
            seen.add(signedIn.getResponse().getContentAsString(UTF_8));
            String sid =
                    FakeKeycloakMfaRealm.sidOf(json(signedIn).get("accessToken").asText());
            MvcResult list = get(base(TENANT), cook.id, sid);
            seen.add(list.getResponse().getContentAsString(UTF_8));
            seen.add(post(base(TENANT) + "/terminations", terminate("ALL", "JUST_IN_CASE"), cook.id, sid)
                    .getResponse()
                    .getContentAsString(UTF_8));
            seen.add(jdbc.sql("SELECT coalesce(string_agg(row_to_json(t)::text, ' '), '') FROM iam.staff_sign_ins t")
                    .query(String.class)
                    .single());
            seen.add(jdbc.sql("SELECT coalesce(string_agg(row_to_json(t)::text, ' '), '') FROM audit.audit_events t")
                    .query(String.class)
                    .single());
        } finally {
            root.detachAppender(logs);
        }
        List<ILoggingEvent> events;
        synchronized (logs) {
            events = new ArrayList<>(logs.list);
        }
        events.forEach(event -> seen.add(event.getFormattedMessage()));

        String everything = String.join("\n", seen);
        assertThat(everything)
                .doesNotContain("CANARY-UA-7f3a91", "CanaryAgent", address, FakeKeycloakMfaRealm.SESSION_ADDRESS);
        // The proof can fail: the session really was recorded and really was listed.
        assertThat(seen.get(1)).contains("deviceLabel").contains("CHROME");
    }

    // ================================================================================ the list

    @Test
    @DisplayName("the list names each device, marks the one this token is, and carries no address")
    void theListLabelsAndMarksTheCurrentSession() throws Exception {
        User cook = person("cook");
        cook.organizationIds.add(ORG);
        member(TENANT, cook);
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        String laptop = sidOf(signIn(cook.username, CHROME_ON_WINDOWS, "198.51.100.11"));
        String phone = sidOf(signIn(cook.username, SAFARI_ON_IPHONE, "198.51.100.12"));

        MvcResult result = get(base(TENANT), cook.id, phone);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(result);
        assertThat(body.get("keycloak").asText()).isEqualTo("AVAILABLE");
        JsonNode items = body.get("items");
        assertThat(items).hasSize(2);
        JsonNode current = byId(items, phone);
        assertThat(current.get("current").asBoolean()).isTrue();
        assertThat(current.get("state").asText()).isEqualTo("ACTIVE");
        assertThat(current.get("deviceLabel").get("browser").asText()).isEqualTo("SAFARI");
        assertThat(current.get("deviceLabel").get("os").asText()).isEqualTo("IOS");
        assertThat(current.get("deviceLabel").get("form").asText()).isEqualTo("PHONE");
        JsonNode other = byId(items, laptop);
        assertThat(other.get("current").asBoolean()).isFalse();
        assertThat(other.get("deviceLabel").get("browser").asText()).isEqualTo("CHROME");
        assertThat(result.getResponse().getContentAsString(UTF_8)).doesNotContain(FakeKeycloakMfaRealm.SESSION_ADDRESS);
    }

    @Test
    @DisplayName(
            "a session with no platform row is an unknown device with Keycloak's times; no row is marked current without a sid")
    void aSessionWithNoRowIsAnUnknownDevice() throws Exception {
        User cook = person("cook");
        cook.organizationIds.add(ORG);
        member(TENANT, cook);
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        String sid = sidOf(signIn(cook.username, CHROME_ON_WINDOWS, "198.51.100.13"));
        jdbc.sql("DELETE FROM iam.staff_sign_ins").update();

        JsonNode body = json(get(base(TENANT), cook.id, null));

        JsonNode item = byId(body.get("items"), sid);
        assertThat(item.get("deviceLabel").isNull()).isTrue();
        assertThat(item.get("state").asText()).isEqualTo("ACTIVE");
        assertThat(item.get("current").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("a row the platform believes open and Keycloak does not list reads as ended")
    void whatKeycloakNoLongerListsReadsAsEnded() throws Exception {
        User cook = person("cook");
        cook.organizationIds.add(ORG);
        member(TENANT, cook);
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        String kept = sidOf(signIn(cook.username, CHROME_ON_WINDOWS, "198.51.100.14"));
        String gone = sidOf(signIn(cook.username, SAFARI_ON_IPHONE, "198.51.100.15"));
        realm.expireSession(gone);

        JsonNode items = json(get(base(TENANT), cook.id, kept)).get("items");

        assertThat(byId(items, kept).get("state").asText()).isEqualTo("ACTIVE");
        assertThat(byId(items, gone).get("state").asText()).isEqualTo("ENDED");
        assertThat(rowFor(gone).get("ended_at"))
                .as("the platform's row is not rewritten by a read")
                .isNull();
    }

    @Test
    @DisplayName("with Keycloak unreachable the list is the platform's own record and says so")
    void theListFallsBackToThePlatformsRows() throws Exception {
        User cook = person("cook");
        cook.organizationIds.add(ORG);
        member(TENANT, cook);
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        String sid = sidOf(signIn(cook.username, CHROME_ON_WINDOWS, "198.51.100.16"));
        realm.failSessionAdmin(true);

        JsonNode body = json(get(base(TENANT), cook.id, sid));

        assertThat(body.get("keycloak").asText()).isEqualTo("UNAVAILABLE");
        assertThat(body.get("items")).hasSize(1);
        assertThat(body.get("items").get(0).get("state").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("items").get(0).get("current").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a member of the other tenant reads only their own rows, and a caller with no grant is refused")
    void theListIsTheCallersOwn() throws Exception {
        User cook = person("cook");
        User other = person("other");
        cook.organizationIds.add(ORG);
        other.organizationIds.add(OTHER_ORG);
        member(TENANT, cook);
        member(OTHER_TENANT, other);
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        grant(other.id, PlatformRole.LOCATION_STAFF, "LOCATION", OTHER_LOCATION, OTHER_TENANT);
        String cooks = sidOf(signIn(cook.username, CHROME_ON_WINDOWS, "198.51.100.17"));
        sidOf(signIn(other.username, SAFARI_ON_IPHONE, "198.51.100.18"));

        JsonNode seenByCook = json(get(base(TENANT), cook.id, cooks)).get("items");
        MvcResult crossTenant = get(base(OTHER_TENANT), cook.id, cooks);
        User stranger = person("nogrant");
        member(TENANT, stranger);
        MvcResult noGrant = get(base(TENANT), stranger.id, null);

        assertThat(seenByCook).hasSize(1);
        assertThat(seenByCook.get(0).get("id").asText()).isEqualTo(cooks);
        assertThat(crossTenant.getResponse().getStatus())
                .as("no grant in the other tenant")
                .isEqualTo(403);
        assertThat(noGrant.getResponse().getStatus()).isEqualTo(403);
    }

    // =================================================================== «Выйти на всех устройствах»

    @Test
    @DisplayName(
            "signing out everywhere ends every session at Keycloak, marks this tenant's rows and writes one fact with a count and no device")
    void signOutEverywhere() throws Exception {
        User cook = person("cook");
        cook.organizationIds.addAll(List.of(ORG, OTHER_ORG));
        member(TENANT, cook);
        member(OTHER_TENANT, cook);
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        MvcResult first = signIn(cook.username, CHROME_ON_WINDOWS, "198.51.100.21");
        MvcResult second = signIn(cook.username, SAFARI_ON_IPHONE, "198.51.100.22");
        String sid = sidOf(first);

        MvcResult ended = post(base(TENANT) + "/terminations", terminate("ALL", "LOST_DEVICE"), cook.id, sid);

        assertThat(ended.getResponse().getStatus()).isEqualTo(204);
        assertThat(realm.liveSessionIds(cook)).isEmpty();
        assertThat(refresh(json(second).get("refreshToken").asText())
                        .getResponse()
                        .getStatus())
                .as("the other device's refresh token is dead")
                .isEqualTo(401);
        assertThat(jdbc.sql("""
                                SELECT count(*) FROM iam.staff_sign_ins
                                 WHERE tenant_id = :t AND end_reason = 'SIGNED_OUT_EVERYWHERE' AND ended_at IS NOT NULL
                                """).param("t", TENANT).query(Integer.class).single())
                .isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM iam.staff_sign_ins WHERE tenant_id = :t AND ended_at IS NULL")
                        .param("t", OTHER_TENANT)
                        .query(Integer.class)
                        .single())
                .as("another tenant's rows are not touched by an act in this one")
                .isEqualTo(2);
        assertThat(auditCount("iam.staff_session.terminated_all")).isEqualTo(1);
        String fact = jdbc.sql("""
                        SELECT string_agg(change_document::text || ' ' || coalesce(reason, ''), ' ')
                          FROM audit.audit_events WHERE action_code = 'iam.staff_session.terminated_all'
                        """).query(String.class).single();
        assertThat(fact).contains("activeSessions").contains("LOST_DEVICE").doesNotContain("CHROME", "SAFARI", "IOS");
        assertThat(json(get(base(TENANT), cook.id, sid)).get("items"))
                .as("after the act Keycloak lists nothing live")
                .allSatisfy(item -> assertThat(item.get("state").asText()).isEqualTo("ENDED"));
    }

    @Test
    @DisplayName("the act is idempotent under a repeated key and refuses a request with none")
    void theActIsIdempotent() throws Exception {
        User cook = person("cook");
        cook.organizationIds.add(ORG);
        member(TENANT, cook);
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        String sid = sidOf(signIn(cook.username, CHROME_ON_WINDOWS, "198.51.100.23"));
        realm.clearCalls();
        String key = UUID.randomUUID().toString();

        MvcResult one =
                postWithKey(base(TENANT) + "/terminations", terminate("ALL", "JUST_IN_CASE"), cook.id, sid, key);
        MvcResult two =
                postWithKey(base(TENANT) + "/terminations", terminate("ALL", "JUST_IN_CASE"), cook.id, sid, key);
        MvcResult none = mvc.perform(MockMvcRequestBuilders.post(base(TENANT) + "/terminations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(terminate("ALL", "JUST_IN_CASE"))
                        .with(tokenFor(cook.id, sid)))
                .andReturn();

        assertThat(one.getResponse().getStatus()).isEqualTo(204);
        assertThat(two.getResponse().getStatus()).isEqualTo(204);
        assertThat(realm.calls().stream()
                        .filter(call -> call.kind().endsWith("/logout"))
                        .count())
                .as("Keycloak was asked to log out once")
                .isEqualTo(1);
        assertThat(auditCount("iam.staff_session.terminated_all")).isEqualTo(1);
        assertThat(none.getResponse().getStatus()).isEqualTo(400);
        assertThat(code(none)).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    @DisplayName(
            "with Keycloak unreachable nothing is ended, nothing is marked, nothing is audited, and the answer says so")
    void keycloakDownEndsNothing() throws Exception {
        User cook = person("cook");
        cook.organizationIds.add(ORG);
        member(TENANT, cook);
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        String sid = sidOf(signIn(cook.username, CHROME_ON_WINDOWS, "198.51.100.24"));
        realm.failLogout(true);

        MvcResult result = post(base(TENANT) + "/terminations", terminate("ALL", "LOST_DEVICE"), cook.id, sid);

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(code(result)).isEqualTo("IDENTITY_PROVIDER_UNAVAILABLE");
        assertThat(realm.liveSessionIds(cook)).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM iam.staff_sign_ins WHERE ended_at IS NOT NULL")
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(auditCount("iam.staff_session.terminated_all")).isZero();
    }

    @Test
    @DisplayName("an audit failure rolls the rows back with it: the rows stay open when the fact cannot be written")
    void theRowsAndTheFactSucceedOrFailTogether() throws Exception {
        User cook = person("cook");
        cook.organizationIds.add(ORG);
        member(TENANT, cook);
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        String sid = sidOf(signIn(cook.username, CHROME_ON_WINDOWS, "198.51.100.25"));
        jdbc.sql("ALTER TABLE audit.audit_events RENAME TO audit_events_broken").update();
        Throwable failure;
        try {
            failure = org.assertj.core.api.Assertions.catchThrowable(
                    () -> post(base(TENANT) + "/terminations", terminate("ALL", "LOST_DEVICE"), cook.id, sid));
        } finally {
            jdbc.sql("ALTER TABLE audit.audit_events_broken RENAME TO audit_events")
                    .update();
        }

        assertThat(failure).as("the act fails when its fact cannot be written").isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM iam.staff_sign_ins WHERE ended_at IS NULL")
                        .query(Integer.class)
                        .single())
                .as("the row update was rolled back with the failed fact")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the sixth termination in an hour is refused with a retry interval")
    void terminationsAreRateLimited() throws Exception {
        User cook = person("cook");
        cook.organizationIds.add(ORG);
        member(TENANT, cook);
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        String sid = sidOf(signIn(cook.username, CHROME_ON_WINDOWS, "198.51.100.26"));
        List<Integer> statuses = new ArrayList<>();
        MvcResult last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            last = post(base(TENANT) + "/terminations", terminate("ALL", "JUST_IN_CASE"), cook.id, sid);
            statuses.add(last.getResponse().getStatus());
        }

        assertThat(statuses).containsExactly(204, 204, 204, 204, 204, 429);
        assertThat(code(last)).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(json(last).get("retryAfterSeconds").asInt()).isPositive();
    }

    @Test
    @DisplayName("a scope that does not match the route, or a reason outside the closed list, is refused")
    void theBodyIsClosed() throws Exception {
        User cook = person("cook");
        cook.organizationIds.add(ORG);
        member(TENANT, cook);
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        String sid = sidOf(signIn(cook.username, CHROME_ON_WINDOWS, "198.51.100.27"));

        MvcResult wrongScope = post(base(TENANT) + "/terminations", terminate("ONE", "LOST_DEVICE"), cook.id, sid);
        MvcResult typedReason =
                post(base(TENANT) + "/terminations", terminate("ALL", "because my boss is annoying"), cook.id, sid);
        MvcResult missingReason = post(base(TENANT) + "/terminations", "{\"scope\":\"ALL\"}", cook.id, sid);

        assertThat(wrongScope.getResponse().getStatus()).isEqualTo(400);
        assertThat(typedReason.getResponse().getStatus()).isEqualTo(400);
        assertThat(missingReason.getResponse().getStatus()).isEqualTo(400);
        assertThat(realm.liveSessionIds(cook)).hasSize(1);
    }

    // ======================================================================== ending one session

    @Test
    @DisplayName("one session can be ended; the other device keeps working; the fact says one fewer")
    void endOneSession() throws Exception {
        User cook = person("cook");
        cook.organizationIds.add(ORG);
        member(TENANT, cook);
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        MvcResult laptop = signIn(cook.username, CHROME_ON_WINDOWS, "198.51.100.31");
        MvcResult phone = signIn(cook.username, SAFARI_ON_IPHONE, "198.51.100.32");
        String laptopSid = sidOf(laptop);

        MvcResult ended = post(
                base(TENANT) + "/" + laptopSid + "/terminations",
                terminate("ONE", "SHARED_DEVICE"),
                cook.id,
                sidOf(phone));

        assertThat(ended.getResponse().getStatus()).isEqualTo(204);
        assertThat(refresh(json(laptop).get("refreshToken").asText())
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(refresh(json(phone).get("refreshToken").asText())
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(rowFor(laptopSid).get("end_reason")).isEqualTo("ENDED_ONE");
        assertThat(rowFor(sidOf(phone)).get("ended_at")).isNull();
        String fact = jdbc.sql("""
                        SELECT string_agg(change_document::text, ' ')
                          FROM audit.audit_events WHERE action_code = 'iam.staff_session.terminated'
                        """).query(String.class).single();
        assertThat(fact).contains("activeSessions");
        assertThat(auditCount("iam.staff_session.terminated")).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "another person's session id, and an id nobody holds, answer as unknown and Keycloak is never asked to delete")
    void aSessionThatIsNotTheCallersIsUnknown() throws Exception {
        User cook = person("cook");
        User colleague = person("colleague");
        cook.organizationIds.add(ORG);
        colleague.organizationIds.add(ORG);
        member(TENANT, cook);
        member(TENANT, colleague);
        grant(cook.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        grant(colleague.id, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
        String cooksSid = sidOf(signIn(cook.username, CHROME_ON_WINDOWS, "198.51.100.33"));
        String colleaguesSid = sidOf(signIn(colleague.username, CHROME_ON_WINDOWS, "198.51.100.34"));
        realm.clearCalls();

        MvcResult theirs = post(
                base(TENANT) + "/" + colleaguesSid + "/terminations",
                terminate("ONE", "LOST_DEVICE"),
                cook.id,
                cooksSid);
        MvcResult nobodys = post(
                base(TENANT) + "/kc-doesnotexist/terminations", terminate("ONE", "LOST_DEVICE"), cook.id, cooksSid);

        assertThat(theirs.getResponse().getStatus()).isEqualTo(404);
        assertThat(nobodys.getResponse().getStatus()).isEqualTo(404);
        assertThat(realm.calls().stream().filter(call -> call.kind().contains("DELETE /sessions")))
                .as("ownership is decided before Keycloak is asked to delete anything")
                .isEmpty();
        assertThat(realm.liveSessionIds(colleague)).containsExactly(colleaguesSid);
    }

    // ================================================================================== sign-out

    @Test
    @DisplayName("signing out of this device marks its row SIGNED_OUT and leaves the others open")
    void signingOutOfThisDevice() throws Exception {
        User cook = person("cook");
        cook.organizationIds.add(ORG);
        member(TENANT, cook);
        MvcResult laptop = signIn(cook.username, CHROME_ON_WINDOWS, "198.51.100.41");
        MvcResult phone = signIn(cook.username, SAFARI_ON_IPHONE, "198.51.100.42");

        MvcResult out = mvc.perform(MockMvcRequestBuilders.delete("/api/v1/operations/auth/sessions/current")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}"
                                .formatted(json(laptop).get("refreshToken").asText())))
                .andReturn();

        assertThat(out.getResponse().getStatus()).isEqualTo(204);
        assertThat(rowFor(sidOf(laptop)).get("end_reason")).isEqualTo("SIGNED_OUT");
        assertThat(rowFor(sidOf(phone)).get("ended_at")).isNull();
    }

    @Test
    @DisplayName("a refresh moves last_seen_at on the row of its own session")
    void aRefreshIsSeen() throws Exception {
        User cook = person("cook");
        cook.organizationIds.add(ORG);
        member(TENANT, cook);
        MvcResult laptop = signIn(cook.username, CHROME_ON_WINDOWS, "198.51.100.43");
        String sid = sidOf(laptop);
        // Age the row past the touch interval, then refresh: the conditional write moves it.
        jdbc.sql("UPDATE iam.staff_sign_ins SET last_seen_at = last_seen_at - interval '2 hours', "
                        + "signed_in_at = signed_in_at - interval '2 hours'")
                .update();
        Instant stale = lastSeen(sid);

        assertThat(refresh(json(laptop).get("refreshToken").asText())
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        Instant touched = lastSeen(sid);
        assertThat(touched).isAfter(stale.plus(Duration.ofHours(1)));
    }

    // ====================================================================================== helpers

    private static String base(UUID tenantId) {
        return "/api/v1/operations/tenants/" + tenantId + "/staff/me/sessions";
    }

    private static String terminate(String scope, String reason) {
        return "{\"scope\":\"%s\",\"reasonCode\":\"%s\"}".formatted(scope, reason);
    }

    private User person(String label) {
        int n = counter.incrementAndGet();
        return realm.addUser("99891%07d".formatted(n), PASSWORD, label + n + "@example.test");
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
                        'ACTIVE', 'test-fixture', 'staff sessions endpoint test', :validFrom)
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

    private void seedTenancy(UUID tenantId, UUID brandId, UUID locationId, String slug, String organizationId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version, keycloak_organization_id)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0, :org)
                """)
                .param("id", tenantId)
                .param("slug", slug)
                .param("org", organizationId)
                .update();
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

    private MvcResult signIn(String username, String userAgent, String address) throws Exception {
        String body = "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, PASSWORD);
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/operations/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", userAgent)
                        .content(body)
                        .with(request -> {
                            request.setRemoteAddr(address);
                            return request;
                        }))
                .andReturn();
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/operations/auth/sessions/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)))
                .andReturn();
    }

    private MvcResult get(String path, String subject, @Nullable String sid) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).with(tokenFor(subject, sid)))
                .andReturn();
    }

    private MvcResult post(String path, String json, String subject, @Nullable String sid) throws Exception {
        return postWithKey(path, json, subject, sid, UUID.randomUUID().toString());
    }

    private MvcResult postWithKey(String path, String json, String subject, @Nullable String sid, String key)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .header("Idempotency-Key", key)
                        .with(tokenFor(subject, sid)))
                .andReturn();
    }

    private static RequestPostProcessor tokenFor(String subject, @Nullable String sid) {
        return jwt().jwt(builder -> {
            builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of())));
            if (sid != null) {
                builder.claim("sid", sid);
            }
        });
    }

    private static String sidOf(MvcResult signedIn) throws Exception {
        assertThat(signedIn.getResponse().getStatus()).as("sign-in").isEqualTo(201);
        return java.util.Objects.requireNonNull(
                FakeKeycloakMfaRealm.sidOf(json(signedIn).get("accessToken").asText()));
    }

    private static JsonNode json(@Nullable MvcResult result) throws Exception {
        return JSON.readTree(
                java.util.Objects.requireNonNull(result).getResponse().getContentAsString(UTF_8));
    }

    private static String code(@Nullable MvcResult result) throws Exception {
        return json(result).get("code").asText();
    }

    private static JsonNode byId(JsonNode items, String id) {
        for (JsonNode item : items) {
            if (id.equals(item.get("id").asText())) {
                return item;
            }
        }
        throw new AssertionError("no item " + id + " in " + items);
    }

    private Map<String, Object> rowFor(String sid) {
        return jdbc.sql("SELECT * FROM iam.staff_sign_ins WHERE keycloak_session_id = :sid")
                .param("sid", sid)
                .query()
                .singleRow();
    }

    private Instant lastSeen(String sid) {
        return jdbc.sql("SELECT last_seen_at FROM iam.staff_sign_ins WHERE keycloak_session_id = :sid")
                .param("sid", sid)
                .query((rs, n) ->
                        rs.getObject(1, java.time.OffsetDateTime.class).toInstant())
                .single();
    }

    private int rowCount() {
        return jdbc.sql("SELECT count(*) FROM iam.staff_sign_ins")
                .query(Integer.class)
                .single();
    }

    private int auditCount(String action) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = :a")
                .param("a", action)
                .query(Integer.class)
                .single();
    }

    private double failedRecordings() {
        var counter = meters.find("horecaos.iam.staff_session.ledger")
                .tag("operation", "sign_in")
                .tag("outcome", "failed")
                .counter();
        return counter == null ? 0 : counter.count();
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
        StaffSessions fakeStaffSessions() {
            return realm.adapters().sessions();
        }
    }
}
