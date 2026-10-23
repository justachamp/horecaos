package uz.horecaos.platform.loyalty.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.customers.api.ConsentDirectory;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerPhoneLookup;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.loyalty.api.PosLoyaltyBalancePort;
import uz.horecaos.platform.loyalty.application.LoyaltyAdjustmentService;
import uz.horecaos.platform.loyalty.application.LoyaltyAdjustmentService.AdjustmentCommand;
import uz.horecaos.platform.loyalty.application.PosBalanceSyncScheduler;
import uz.horecaos.platform.loyalty.application.PosBalanceSyncService;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcPosBalanceSyncStore;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;
import uz.horecaos.platform.pos.FakePosAdapter;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.application.port.ConfigurationValueCache;

/**
 * ADR 0171, row 6.3b, the parts batch 20 left to configuration keys: a tenant stages a binding's sync mode and says
 * whether its till keeps a balance (both over HTTP, with the console's real JSON), a platform movement reaches the till
 * within a minute whatever the pull interval, a difference that needs a person reaches the brand's chats, and the consent
 * gate counsel may one day ask for is built dark and flips with a key.
 *
 * <p>The till is {@link FakePosAdapter}'s wallet; the clock the scheduler reads is this test's own, so every interval below
 * is a duration that is advanced and not an instant that is hoped for.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoyaltyPosSyncStagingTests {

    private static final UUID TENANT = UUID.fromString("018fd187-4000-7000-8000-0000000a7201");
    private static final UUID OTHER_TENANT = UUID.fromString("018fd187-4000-7000-8000-0000000a7202");
    private static final UUID BRAND = UUID.fromString("018fd187-4000-7000-8000-0000000a7203");
    private static final UUID OTHER_BRAND = UUID.fromString("018fd187-4000-7000-8000-0000000a7204");

    private static final UUID FAKE_INSTALLATION = UUID.fromString("018fd187-4000-7000-8000-0000000a7211");
    private static final UUID FAKE_BINDING = UUID.fromString("018fd187-4000-7000-8000-0000000a7212");
    private static final UUID CLOPOS_INSTALLATION = UUID.fromString("018fd187-4000-7000-8000-0000000a7213");
    private static final UUID CLOPOS_BINDING = UUID.fromString("018fd187-4000-7000-8000-0000000a7214");

    private static final UUID AZIZA = UUID.fromString("018fd187-4000-7000-8000-0000000a7221");
    private static final String PHONE_AZIZA = "+998 90 111 22 33";

    /** 10:00 in Tashkent: past the hour after which the day's reconciliation is due. */
    private static final Instant START = Instant.parse("2026-10-14T05:00:00Z");

    private static final String FINANCE = "staging-finance";
    private static final String SUPPORT = "staging-support";
    private static final String OTHER_FINANCE = "staging-other-tenant-finance";

    private static final String BASE =
            "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/loyalty/pos-sync";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        registry.add("horecaos.loyalty.pos-sync.enabled", () -> "false");
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
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
    private FakePosAdapter till;

    @Autowired
    @SuppressWarnings("NullAway")
    private MovableClock clock;

    @Autowired
    @SuppressWarnings("NullAway")
    private StubPhones phones;

    @Autowired
    @SuppressWarnings("NullAway")
    private StubConsents consents;

    @Autowired
    @SuppressWarnings("NullAway")
    private RecordingAlerts alerts;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcLoyaltyStore loyalty;

    @Autowired
    @SuppressWarnings("NullAway")
    private LoyaltyAdjustmentService adjustments;

    @Autowired
    @SuppressWarnings("NullAway")
    private PosLoyaltyBalancePort port;

    @Autowired
    @SuppressWarnings("NullAway")
    private PosBalanceSyncService sync;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcPosBalanceSyncStore syncStore;

    @Autowired
    @SuppressWarnings("NullAway")
    private ConfigurationResolver configuration;

    @Autowired
    @SuppressWarnings("NullAway")
    private ConfigurationValueCache configurationCache;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.provider_entity_mappings").update();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        jdbc.sql("TRUNCATE TABLE loyalty.pos_sync_run_findings, loyalty.pos_sync_match_progress, "
                        + "loyalty.pos_sync_discrepancies, loyalty.pos_sync_movements, "
                        + "loyalty.pos_balance_sync_state, loyalty.pos_sync_runs")
                .update();
        jdbc.sql("DELETE FROM integration.binding_capabilities").update();
        jdbc.sql("DELETE FROM integration.bindings").update();
        jdbc.sql("DELETE FROM integration.installations").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        till.resetWallet();
        clock.set(START);
        phones.clear();
        consents.clear();
        alerts.clear();
        for (String key : List.of("loyalty.pos.till_keeps_balance", "loyalty.pos_sync.require_consent")) {
            configurationCache.evict(key, uz.horecaos.platform.iam.api.ResourceScope.brand(TENANT, BRAND));
        }

        seedTenant(TENANT, BRAND, "staging-a");
        seedTenant(OTHER_TENANT, OTHER_BRAND, "staging-b");
        installation(CLOPOS_INSTALLATION, "clopos");
        binding(CLOPOS_BINDING, CLOPOS_INSTALLATION, "ACTIVE");
        installation(FAKE_INSTALLATION, FakePosAdapter.PROVIDER_TYPE);
        binding(FAKE_BINDING, FAKE_INSTALLATION, "ACTIVE");
        enableCapability(FAKE_BINDING);

        grant(FINANCE, PlatformRole.TENANT_FINANCE, TENANT);
        grant(SUPPORT, PlatformRole.SUPPORT_AGENT, TENANT);
        grant(OTHER_FINANCE, PlatformRole.TENANT_FINANCE, OTHER_TENANT);
    }

    // ------------------------------------------------------------ staging the rollout

    @Test
    @DisplayName(
            "the rollout is staged from the console: report-only, then pull, then push, one stage up at a time, any stage down at once")
    void aTenantStagesTheRolloutOneStageUpAtATime() throws Exception {
        long version = bindingVersion();
        assertThat(bindingField("mode")).isEqualTo("REPORT_ONLY");

        MvcResult skipped = putMode(FAKE_BINDING, "PULL_AND_PUSH", version, FINANCE);
        assertThat(skipped.getResponse().getStatus()).isEqualTo(422);
        assertThat(skipped.getResponse().getContentAsString(UTF_8))
                .contains("UNPROCESSABLE_STATE")
                .contains("REPORT_ONLY")
                .contains("PULL_AND_PUSH");
        assertThat(bindingField("mode")).as("a refused step moved nothing").isEqualTo("REPORT_ONLY");
        assertThat(bindingVersion()).isEqualTo(version);

        MvcResult pull = putMode(FAKE_BINDING, "PULL", version, FINANCE);
        assertThat(pull.getResponse().getStatus())
                .as(pull.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(JsonPath.<String>read(pull.getResponse().getContentAsString(UTF_8), "$.mode"))
                .isEqualTo("PULL");
        assertThat(JsonPath.<Integer>read(pull.getResponse().getContentAsString(UTF_8), "$.version"))
                .isEqualTo((int) version + 1);
        assertThat(pull.getResponse().getHeader("ETag")).isEqualTo("W/\"" + (version + 1) + "\"");
        assertThat(bindingField("mode")).isEqualTo("PULL");
        assertThat(configurationOverride(FAKE_BINDING))
                .as("written where the reader reads it: the binding's own configuration")
                .contains("\"loyalty.sync.mode\": \"PULL\"");

        MvcResult push = putMode(FAKE_BINDING, "PULL_AND_PUSH", version + 1, FINANCE);
        assertThat(push.getResponse().getStatus()).isEqualTo(200);
        assertThat(bindingField("mode")).isEqualTo("PULL_AND_PUSH");

        MvcResult down = putMode(FAKE_BINDING, "REPORT_ONLY", version + 2, FINANCE);
        assertThat(down.getResponse().getStatus())
                .as("two stages down in one step: stopping needs no waiting")
                .isEqualTo(200);
        assertThat(bindingField("mode")).isEqualTo("REPORT_ONLY");

        assertThat(jdbc.sql("""
                        SELECT change_document::text FROM audit.audit_events
                         WHERE action_code = 'loyalty.pos_sync.mode.changed' ORDER BY occurred_at
                        """).query(String.class).list())
                .hasSize(3)
                .anyMatch(document -> document.contains("PULL_AND_PUSH") && document.contains("REPORT_ONLY"));
    }

    @Test
    @DisplayName("a change sent against an old binding version is STALE_VERSION and the mode does not move")
    void aStaleVersionIsRefused() throws Exception {
        long version = bindingVersion();
        assertThat(putMode(FAKE_BINDING, "PULL", version, FINANCE).getResponse().getStatus())
                .isEqualTo(200);

        MvcResult stale = putMode(FAKE_BINDING, "PULL_AND_PUSH", version, FINANCE);

        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString(UTF_8)).contains("STALE_VERSION");
        assertThat(bindingField("mode")).isEqualTo("PULL");
    }

    @Test
    @DisplayName(
            "a mode above report-only needs a binding that can sync: an unassessed vendor and a suspended binding are refused")
    void aModeNeedsABindingThatCanSync() throws Exception {
        MvcResult unassessed = putMode(CLOPOS_BINDING, "PULL", bindingVersion(CLOPOS_BINDING), FINANCE);
        assertThat(unassessed.getResponse().getStatus()).isEqualTo(422);
        assertThat(unassessed.getResponse().getContentAsString(UTF_8)).contains("UNASSESSED");

        jdbc.sql("UPDATE integration.bindings SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", FAKE_BINDING)
                .update();
        MvcResult suspended = putMode(FAKE_BINDING, "PULL", bindingVersion(), FINANCE);
        assertThat(suspended.getResponse().getStatus()).isEqualTo(422);
        assertThat(bindingField("mode")).isEqualTo("REPORT_ONLY");
    }

    @Test
    @DisplayName("staging needs loyalty.sync.manage in this tenant, an If-Match and an Idempotency-Key")
    void stagingIsGuarded() throws Exception {
        long version = bindingVersion();

        assertThat(putMode(FAKE_BINDING, "PULL", version, SUPPORT).getResponse().getStatus())
                .as("support reads, it does not stage")
                .isEqualTo(403);
        assertThat(putMode(FAKE_BINDING, "PULL", version, OTHER_FINANCE)
                        .getResponse()
                        .getStatus())
                .as("another tenant's finance has no grant here")
                .isEqualTo(403);
        MvcResult noMatch = mvc.perform(MockMvcRequestBuilders.put(BASE + "/bindings/" + FAKE_BINDING + "/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"PULL\",\"reason\":\"pilot\"}")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(tokenFor(FINANCE)))
                .andReturn();
        assertThat(noMatch.getResponse().getStatus()).as("no If-Match").isGreaterThanOrEqualTo(400);
        MvcResult noKey = mvc.perform(MockMvcRequestBuilders.put(BASE + "/bindings/" + FAKE_BINDING + "/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"PULL\",\"reason\":\"pilot\"}")
                        .header("If-Match", "W/\"" + version + "\"")
                        .with(tokenFor(FINANCE)))
                .andReturn();
        assertThat(noKey.getResponse().getStatus()).as("no Idempotency-Key").isGreaterThanOrEqualTo(400);
        MvcResult unknownMode = mvc.perform(modeRequest(FAKE_BINDING, "TURBO", version, FINANCE))
                .andReturn();
        assertThat(unknownMode.getResponse().getStatus()).isEqualTo(400);
        MvcResult noReason = mvc.perform(MockMvcRequestBuilders.put(BASE + "/bindings/" + FAKE_BINDING + "/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"PULL\"}")
                        .header("If-Match", "W/\"" + version + "\"")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(tokenFor(FINANCE)))
                .andReturn();
        assertThat(noReason.getResponse().getStatus()).isEqualTo(400);

        assertThat(bindingField("mode")).as("none of the six changed it").isEqualTo("REPORT_ONLY");
        assertThat(bindingVersion()).isEqualTo(version);
    }

    @Test
    @DisplayName(
            "what the console writes is what the scheduler reads: a staged binding pulls and pushes on its next tick")
    void theStagedModeDrivesTheScheduler() throws Exception {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000);
        PosBalanceSyncScheduler scheduler = scheduler(clock);

        assertThat(scheduler.tickOne(tenantBinding()))
                .as("report-only: at most the day's reconciliation (05:00 UTC is 10:00 in Tashkent)")
                .isEqualTo(1);
        assertThat(count("loyalty.pos_sync_runs WHERE kind IN ('PULL', 'PUSH')"))
                .isZero();

        long version = bindingVersion();
        assertThat(putMode(FAKE_BINDING, "PULL", version, FINANCE).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(putMode(FAKE_BINDING, "PULL_AND_PUSH", version + 1, FINANCE)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);

        assertThat(scheduler.tickOne(tenantBinding())).isEqualTo(2);
        assertThat(count("loyalty.pos_sync_runs WHERE kind = 'PULL'")).isEqualTo(1);
        assertThat(count("loyalty.pos_sync_runs WHERE kind = 'PUSH'")).isEqualTo(1);
    }

    // ------------------------------------------------------------ the till keeps its own balance

    @Test
    @DisplayName(
            "the brand says its till keeps a balance of its own, against the version of its own row, and the overview follows")
    void theBrandSaysItsTillKeepsABalance() throws Exception {
        String before = overview();
        assertThat(JsonPath.<Boolean>read(before, "$.tillKeepsBalance")).isFalse();
        assertThat(JsonPath.<Integer>read(before, "$.tillKeepsBalanceVersion")).isZero();

        MvcResult said = putTillKeepsBalance("{\"value\":true,\"reason\":\"iiko loyalty is on\"}", 0, FINANCE);
        assertThat(said.getResponse().getStatus())
                .as(said.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        String body = said.getResponse().getContentAsString(UTF_8);
        assertThat(JsonPath.<Boolean>read(body, "$.tillKeepsBalance")).isTrue();
        assertThat(JsonPath.<Integer>read(body, "$.tillKeepsBalanceVersion")).isEqualTo(1);
        assertThat(JsonPath.<Boolean>read(overview(), "$.tillKeepsBalance")).isTrue();

        MvcResult stale = putTillKeepsBalance("{\"value\":false,\"reason\":\"changed my mind\"}", 0, FINANCE);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString(UTF_8)).contains("STALE_VERSION");
        assertThat(JsonPath.<Boolean>read(overview(), "$.tillKeepsBalance")).isTrue();

        MvcResult unsaid = putTillKeepsBalance("{\"value\":false,\"reason\":\"retired the till loyalty\"}", 1, FINANCE);
        assertThat(unsaid.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(overview(), "$.tillKeepsBalance")).isFalse();
        assertThat(JsonPath.<Integer>read(overview(), "$.tillKeepsBalanceVersion"))
                .isEqualTo(2);
    }

    @Test
    @DisplayName(
            "the statement is the standing notice's switch: with the only binding suspended, saying it turns the notice on")
    void theStatementTurnsTheStandingNoticeOn() throws Exception {
        jdbc.sql("UPDATE integration.bindings SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", FAKE_BINDING)
                .update();
        assertThat(JsonPath.<Boolean>read(overview(), "$.standingNotice")).isFalse();

        putTillKeepsBalance("{\"value\":true,\"reason\":\"the till keeps points\"}", 0, FINANCE);

        assertThat(JsonPath.<Boolean>read(overview(), "$.standingNotice")).isTrue();
    }

    @Test
    @DisplayName(
            "the statement needs the capability and a value: the console's JSON without one is a 400 that names it")
    void theStatementIsGuarded() throws Exception {
        assertThat(putTillKeepsBalance("{\"value\":true,\"reason\":\"x\"}", 0, SUPPORT)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(putTillKeepsBalance("{\"value\":true,\"reason\":\"x\"}", 0, OTHER_FINANCE)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        MvcResult missing = putTillKeepsBalance("{\"reason\":\"x\"}", 0, FINANCE);
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(missing.getResponse().getContentAsString(UTF_8)).contains("VALIDATION_FAILED");
        assertThat(count("tenant.configuration_values WHERE key_code = 'loyalty.pos.till_keeps_balance'"))
                .isZero();
    }

    // ------------------------------------------------------------ push convergence

    @Test
    @DisplayName(
            "a platform movement reaches the till within a minute, on the push sweep's own clock, long before the five-minute pull")
    void aPlatformMovementReachesTheTillWithinAMinute() {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000);
        mode("PULL_AND_PUSH");
        PosBalanceSyncScheduler scheduler = scheduler(clock);
        scheduler.tickOne(tenantBinding());
        assertThat(till.tillBalance("T-AZIZA"))
                .as("matched, equal, nothing to send yet")
                .isEqualTo(40_000);

        // A redemption's opposite: the platform credits the customer 5 000, as an order's accrual would.
        credit(AZIZA, 5_000, "accrual-1");
        clock.advance(Duration.ofSeconds(10));
        assertThat(scheduler.pushOne(tenantBinding()))
                .as("ten seconds after the last pass: inside the push interval")
                .isZero();
        assertThat(till.tillBalance("T-AZIZA")).isEqualTo(40_000);

        clock.advance(Duration.ofSeconds(21));
        assertThat(scheduler.pushOne(tenantBinding()))
                .as("thirty-one seconds after: the sweep sends what moved")
                .isEqualTo(1);

        assertThat(till.tillBalance("T-AZIZA"))
                .as("the till shows the platform's number, 31 seconds after it moved")
                .isEqualTo(45_000);
        assertThat(count("loyalty.pos_sync_runs WHERE kind = 'PULL'"))
                .as("and no pull was needed or due: its interval is five minutes")
                .isEqualTo(1);
        assertThat(Duration.between(START.plusSeconds(0), clock.instant()).getSeconds())
                .as("31 seconds of the test's clock since the platform moved it, under the minute the record promises")
                .isLessThan(60);
    }

    @Test
    @DisplayName(
            "an idle binding writes no push run however often the sweep asks, and a busy one writes one per interval")
    void anIdleBindingWritesNoRuns() {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000);
        mode("PULL_AND_PUSH");
        PosBalanceSyncScheduler scheduler = scheduler(clock);
        scheduler.tickOne(tenantBinding());
        long pushesAfterTheFirstTick = count("loyalty.pos_sync_runs WHERE kind = 'PUSH'");

        for (int i = 0; i < 12; i++) {
            clock.advance(Duration.ofSeconds(10));
            assertThat(scheduler.pushOne(tenantBinding())).isZero();
        }
        assertThat(count("loyalty.pos_sync_runs WHERE kind = 'PUSH'"))
                .as("two minutes of sweeps with nothing moved wrote nothing")
                .isEqualTo(pushesAfterTheFirstTick);

        credit(AZIZA, 1_000, "accrual-2");
        clock.advance(Duration.ofSeconds(10));
        scheduler.pushOne(tenantBinding());
        credit(AZIZA, 1_000, "accrual-3");
        scheduler.pushOne(tenantBinding());
        assertThat(count("loyalty.pos_sync_runs WHERE kind = 'PUSH'"))
                .as("the second movement waits for the interval: one run, carrying the latest number")
                .isEqualTo(pushesAfterTheFirstTick + 1);
        clock.advance(Duration.ofSeconds(31));
        scheduler.pushOne(tenantBinding());
        assertThat(till.tillBalance("T-AZIZA")).isEqualTo(42_000);
    }

    @Test
    @DisplayName(
            "a till that is down is asked at the pull interval and not every thirty seconds, and is sent the number once it is back")
    void aTillThatIsDownIsNotHammered() {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000);
        mode("PULL_AND_PUSH");
        PosBalanceSyncScheduler scheduler = scheduler(clock);
        scheduler.tickOne(tenantBinding());

        till.tillDown(true);
        credit(AZIZA, 5_000, "accrual-4");
        clock.advance(Duration.ofSeconds(31));
        assertThat(scheduler.pushOne(tenantBinding()))
                .as("tried once, and it failed")
                .isEqualTo(1);
        int writesWhenItFailed = till.balanceWriteCount();

        clock.advance(Duration.ofSeconds(40));
        assertThat(scheduler.pushOne(tenantBinding()))
                .as("not again for the next 300 seconds")
                .isZero();
        assertThat(till.balanceWriteCount()).isEqualTo(writesWhenItFailed);

        till.tillDown(false);
        clock.advance(Duration.ofSeconds(260));
        assertThat(scheduler.pushOne(tenantBinding())).isEqualTo(1);
        assertThat(till.tillBalance("T-AZIZA")).isEqualTo(45_000);
    }

    // ------------------------------------------------------------ a difference reaches the chats

    @Test
    @DisplayName("a real difference reaches the operations alerts by ids and kind alone, and an ordinary one does not")
    void aDifferenceReachesTheAlerts() throws Exception {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 22_000).tillCustomer("T-STRANGER", "+998 94 444 55 66", 9_000);

        MvcResult run = post(BASE + "/runs", FINANCE, "{\"bindingId\":\"%s\"}".formatted(FAKE_BINDING));
        assertThat(run.getResponse().getStatus()).isEqualTo(200);

        assertThat(alerts.calls()).hasSize(1);
        RecordedAlert alert = alerts.calls().getFirst();
        assertThat(alert.eventClass()).isEqualTo("LOYALTY_SYNC_DISCREPANCY");
        assertThat(alert.tenantId()).isEqualTo(TENANT);
        assertThat(alert.brandId()).isEqualTo(BRAND);
        assertThat(alert.variables()).containsOnlyKeys("bindingId", "discrepancyId", "discrepancyKind");
        assertThat(alert.variables()).containsEntry("discrepancyKind", "TILL_LOWER");
        assertThat(alert.variables().toString())
                .as("no phone, no name, no balance")
                .doesNotContain("998")
                .doesNotContain("22000")
                .doesNotContain("40000");
        assertThat(alert.idempotencyKeyBase()).startsWith("LOYALTY_SYNC_DISCREPANCY:PosSyncBinding:" + FAKE_BINDING);

        MvcResult again = post(BASE + "/runs", FINANCE, "{\"bindingId\":\"%s\"}".formatted(FAKE_BINDING));
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        assertThat(alerts.calls())
                .as("the difference is still the same open one: a second pass opens nothing and so tells no one")
                .hasSize(1);
    }

    // ------------------------------------------------------------ the consent gate, built dark

    @Test
    @DisplayName(
            "by default no consent is asked for: counsel's accepted answer, so a till customer is matched without one")
    void byDefaultNoConsentIsAsked() throws Exception {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000);

        MvcResult run = post(BASE + "/runs", FINANCE, "{\"bindingId\":\"%s\"}".formatted(FAKE_BINDING));

        assertThat(JsonPath.<Integer>read(run.getResponse().getContentAsString(UTF_8), "$.matched"))
                .isEqualTo(1);
        assertThat(count("loyalty.pos_sync_discrepancies WHERE kind = 'NOT_CONSENTED'"))
                .isZero();
        assertThat(consents.asked())
                .as("the directory is not even asked while the key is off")
                .isZero();
    }

    @Test
    @DisplayName(
            "flipping the key makes absence count as withheld: the customer is reported NOT_CONSENTED and not paired, until they consent")
    void theGateIsAConfigurationFlip() throws Exception {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000);
        requireConsent(true);

        MvcResult first = post(BASE + "/runs", FINANCE, "{\"bindingId\":\"%s\"}".formatted(FAKE_BINDING));

        String body = first.getResponse().getContentAsString(UTF_8);
        assertThat(JsonPath.<Integer>read(body, "$.matched"))
                .as("not matched without consent")
                .isZero();
        assertThat(count("loyalty.pos_balance_sync_state")).isZero();
        assertThat(count("integration.provider_entity_mappings")).isZero();
        assertThat(count("loyalty.pos_sync_discrepancies WHERE kind = 'NOT_CONSENTED' AND status = 'OPEN'"))
                .isEqualTo(1);
        assertThat(consents.lastPurpose()).isEqualTo("loyalty.pos_sync");
        assertThat(alerts.calls()).extracting(RecordedAlert::eventClass).containsExactly("LOYALTY_SYNC_DISCREPANCY");
        assertThat(alerts.calls().getFirst().variables()).containsEntry("discrepancyKind", "NOT_CONSENTED");

        consents.grant(AZIZA);
        MvcResult second = post(BASE + "/runs", FINANCE, "{\"bindingId\":\"%s\"}".formatted(FAKE_BINDING));

        assertThat(JsonPath.<Integer>read(second.getResponse().getContentAsString(UTF_8), "$.matched"))
                .isEqualTo(1);
        assertThat(count("loyalty.pos_balance_sync_state")).isEqualTo(1);
        assertThat(count("loyalty.pos_sync_discrepancies WHERE kind = 'NOT_CONSENTED' AND status = 'OPEN'"))
                .as("the difference ended with the condition")
                .isZero();
    }

    @Test
    @DisplayName(
            "a consent that was withheld is not consent, and nothing about the customer is sent to the till either way")
    void withheldIsNotConsent() throws Exception {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000);
        requireConsent(true);
        consents.withhold(AZIZA);

        MvcResult run = post(BASE + "/runs", FINANCE, "{\"bindingId\":\"%s\"}".formatted(FAKE_BINDING));

        assertThat(JsonPath.<Integer>read(run.getResponse().getContentAsString(UTF_8), "$.matched"))
                .isZero();
        assertThat(count("loyalty.pos_sync_discrepancies WHERE kind = 'NOT_CONSENTED'"))
                .isEqualTo(1);
        assertThat(till.balanceWriteCount()).isZero();
    }

    @Test
    @DisplayName(
            "flipping the key after customers were paired stops their sync: nothing is pulled or pushed for a customer without consent, and it resumes when they consent")
    void flippingTheKeyStopsWhoWasAlreadyPaired() {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000);
        mode("PULL_AND_PUSH");
        sync.run(TENANT, BRAND, FAKE_BINDING, PosBalanceSyncService.RunKind.RECONCILE, "test");
        assertThat(count("loyalty.pos_balance_sync_state WHERE status = 'MATCHED'"))
                .as("paired while the key was off")
                .isEqualTo(1);
        int writes = till.balanceWriteCount();

        requireConsent(true);
        credit(AZIZA, 5_000, "accrual-after-the-flip");
        till.tillEarn("T-AZIZA", 3_000);
        alerts.clear();
        sync.run(TENANT, BRAND, FAKE_BINDING, PosBalanceSyncService.RunKind.RECONCILE, "test");

        assertThat(till.balanceWriteCount())
                .as("nothing about the customer is sent to the till once consent is required")
                .isEqualTo(writes);
        assertThat(till.tillBalance("T-AZIZA"))
                .as("the till is as the till had it")
                .isEqualTo(43_000);
        assertThat(count("loyalty.pos_sync_movements"))
                .as("and nothing the till did is imported")
                .isZero();
        assertThat(platformBalance(AZIZA)).isEqualTo(45_000);
        assertThat(stateStatus()).as("the pairing is suspended, not deleted").isEqualTo("SUSPENDED");
        assertThat(count("loyalty.pos_sync_discrepancies WHERE kind = 'NOT_CONSENTED' AND status = 'OPEN'"))
                .as("and the report says why")
                .isEqualTo(1);
        assertThat(alerts.calls()).extracting(RecordedAlert::eventClass).containsExactly("LOYALTY_SYNC_DISCREPANCY");
        assertThat(count("audit.audit_events WHERE action_code = 'loyalty.pos_sync.consent_suspended'"))
                .isEqualTo(1);

        sync.run(TENANT, BRAND, FAKE_BINDING, PosBalanceSyncService.RunKind.RECONCILE, "test");
        assertThat(count("loyalty.pos_sync_discrepancies WHERE kind = 'NOT_CONSENTED' AND status = 'OPEN'"))
                .as("a second pass tells no one again")
                .isEqualTo(1);
        assertThat(till.balanceWriteCount()).isEqualTo(writes);

        consents.grant(AZIZA);
        sync.run(TENANT, BRAND, FAKE_BINDING, PosBalanceSyncService.RunKind.RECONCILE, "test");

        assertThat(stateStatus()).as("consent given: the pairing is back").isEqualTo("MATCHED");
        assertThat(count("loyalty.pos_sync_discrepancies WHERE kind = 'NOT_CONSENTED' AND status = 'OPEN'"))
                .isZero();
        assertThat(count("loyalty.pos_sync_movements"))
                .as("what the till did meanwhile is imported now")
                .isEqualTo(1);
        assertThat(platformBalance(AZIZA)).isEqualTo(48_000);
        assertThat(till.tillBalance("T-AZIZA"))
                .as("and the platform's number reaches the till")
                .isEqualTo(48_000);
    }

    @Test
    @DisplayName("a customer who withdraws consent after being paired stops being synced on the next pass")
    void aWithdrawalStopsAPairedCustomer() {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000);
        mode("PULL_AND_PUSH");
        requireConsent(true);
        consents.grant(AZIZA);
        sync.run(TENANT, BRAND, FAKE_BINDING, PosBalanceSyncService.RunKind.RECONCILE, "test");
        assertThat(stateStatus()).isEqualTo("MATCHED");
        int writes = till.balanceWriteCount();

        consents.withhold(AZIZA);
        credit(AZIZA, 2_000, "accrual-after-the-withdrawal");
        sync.run(TENANT, BRAND, FAKE_BINDING, PosBalanceSyncService.RunKind.PUSH, "test");

        assertThat(till.balanceWriteCount())
                .as("a push pass sends nothing for them")
                .isEqualTo(writes);
        assertThat(stateStatus()).isEqualTo("SUSPENDED");
        assertThat(count("loyalty.pos_sync_discrepancies WHERE kind = 'NOT_CONSENTED' AND status = 'OPEN'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "a pairing a person suspended is not brought back by the consent gate: only one the gate suspended resumes")
    void aPersonsSuspensionIsNotUndoneByConsent() {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000);
        mode("PULL_AND_PUSH");
        sync.run(TENANT, BRAND, FAKE_BINDING, PosBalanceSyncService.RunKind.RECONCILE, "test");
        jdbc.sql("UPDATE loyalty.pos_balance_sync_state SET status = 'SUSPENDED'")
                .update();
        requireConsent(true);
        consents.grant(AZIZA);
        int writes = till.balanceWriteCount();
        credit(AZIZA, 2_000, "accrual-while-suspended");

        sync.run(TENANT, BRAND, FAKE_BINDING, PosBalanceSyncService.RunKind.RECONCILE, "test");

        assertThat(stateStatus()).isEqualTo("SUSPENDED");
        assertThat(till.balanceWriteCount()).isEqualTo(writes);
    }

    @Test
    @DisplayName(
            "a finance user cannot push the platform's number to a till for a customer who has not consented, whichever way the difference is settled")
    void aResolutionDoesNotTouchTheTillWithoutConsent() {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000);
        mode("PULL");
        sync.run(TENANT, BRAND, FAKE_BINDING, PosBalanceSyncService.RunKind.RECONCILE, "test");
        till.corruptTillBalance("T-AZIZA", 41_000);
        sync.run(TENANT, BRAND, FAKE_BINDING, PosBalanceSyncService.RunKind.RECONCILE, "test");
        List<JdbcPosBalanceSyncStore.DiscrepancyRow> open = syncStore.discrepancies(TENANT, BRAND, "OPEN", 10);
        assertThat(open).hasSize(1);
        requireConsent(true);
        int writes = till.balanceWriteCount();

        assertThatThrownBy(() -> sync.resolve(
                        TENANT,
                        BRAND,
                        open.getFirst().id(),
                        PosBalanceSyncService.Resolution.ACCEPT_PLATFORM,
                        "put it right",
                        open.getFirst().version(),
                        FINANCE))
                .isInstanceOf(uz.horecaos.platform.web.api.ApiException.class);

        assertThat(till.balanceWriteCount()).isEqualTo(writes);
        assertThat(syncStore.discrepancies(TENANT, BRAND, "OPEN", 10)).hasSize(1);
    }

    // ------------------------------------------------------------------------ helpers

    private PosBalanceSyncScheduler scheduler(Clock clock) {
        return new PosBalanceSyncScheduler(port, sync, syncStore, configuration, clock);
    }

    private PosLoyaltyBalancePort.TenantBinding tenantBinding() {
        return new PosLoyaltyBalancePort.TenantBinding(
                TENANT, BRAND, java.util.Objects.requireNonNull(port.binding(TENANT, FAKE_BINDING)));
    }

    private long clockSecondsSinceMovement(MovableClock clock) {
        return Duration.between(Instant.parse("2026-10-14T05:00:00Z"), clock.instant())
                        .getSeconds()
                - 0;
    }

    private String overview() throws Exception {
        MvcResult result = mvc.perform(MockMvcRequestBuilders.get(BASE).with(tokenFor(FINANCE)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString(UTF_8);
    }

    private Object bindingField(String field) throws Exception {
        List<Object> found = JsonPath.read(overview(), "$.bindings[?(@.bindingId=='" + FAKE_BINDING + "')]." + field);
        return found.getFirst();
    }

    private long bindingVersion() {
        return bindingVersion(FAKE_BINDING);
    }

    private long bindingVersion(UUID bindingId) {
        return jdbc.sql("SELECT version FROM integration.bindings WHERE id = :id")
                .param("id", bindingId)
                .query(Long.class)
                .single();
    }

    private String configurationOverride(UUID bindingId) {
        return jdbc.sql("SELECT configuration_override::text FROM integration.bindings WHERE id = :id")
                .param("id", bindingId)
                .query(String.class)
                .single();
    }

    private MockHttpServletRequestBuilder modeRequest(UUID bindingId, String mode, long version, String as) {
        return MockMvcRequestBuilders.put(BASE + "/bindings/" + bindingId + "/mode")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"%s\",\"reason\":\"pilot rollout\"}".formatted(mode))
                .header("If-Match", "W/\"" + version + "\"")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(tokenFor(as));
    }

    private MvcResult putMode(UUID bindingId, String mode, long version, String as) throws Exception {
        return mvc.perform(modeRequest(bindingId, mode, version, as)).andReturn();
    }

    private MvcResult putTillKeepsBalance(String json, long version, String as) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put(BASE + "/till-keeps-balance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .header("If-Match", "W/\"" + version + "\"")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(tokenFor(as)))
                .andReturn();
    }

    private MvcResult post(String path, String subject, String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(tokenFor(subject)))
                .andReturn();
    }

    private void mode(String mode) {
        jdbc.sql(
                        "UPDATE integration.bindings SET configuration_override = jsonb_build_object('loyalty.sync.mode', CAST(:m AS text)) WHERE id = :id")
                .param("m", mode)
                .param("id", FAKE_BINDING)
                .update();
    }

    private void requireConsent(boolean value) {
        jdbc.sql("DELETE FROM tenant.configuration_values WHERE key_code = 'loyalty.pos_sync.require_consent'")
                .update();
        jdbc.sql("""
                INSERT INTO tenant.configuration_values (id, key_code, scope_type, tenant_id, brand_id, value_type,
                    boolean_value, set_by)
                VALUES (:id, 'loyalty.pos_sync.require_consent', 'BRAND', :t, :b, 'BOOLEAN', :v, 'test')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("v", value)
                .update();
        configurationCache.evict(
                "loyalty.pos_sync.require_consent", uz.horecaos.platform.iam.api.ResourceScope.brand(TENANT, BRAND));
    }

    private void credit(UUID customer, long amount, String key) {
        adjustments.adjust(new AdjustmentCommand(
                TENANT,
                BRAND,
                customer,
                amount,
                "UZS",
                "GOODWILL",
                "fixture",
                ActorRef.user("fixture", null),
                key,
                ""));
    }

    private String stateStatus() {
        return jdbc.sql("SELECT status FROM loyalty.pos_balance_sync_state")
                .query(String.class)
                .single();
    }

    private long platformBalance(UUID customer) {
        return jdbc.sql("SELECT balance_minor FROM loyalty.accounts WHERE customer_account_id = :c")
                .param("c", customer)
                .query(Long.class)
                .single();
    }

    private long count(String tableAndPredicate) {
        Long count = jdbc.sql("SELECT count(*) FROM " + tableAndPredicate)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    private void pointsAccount(UUID customer, String phone, long balanceMinor) {
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name, identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1)
                ON CONFLICT DO NOTHING
                """).param("id", customer).param("t", TENANT).update();
        phones.register(phone, customer);
        loyalty.openAccount(UUID.randomUUID(), TENANT, BRAND, customer, "UZS", clock.instant());
        if (balanceMinor > 0) {
            adjustments.adjust(new AdjustmentCommand(
                    TENANT,
                    BRAND,
                    customer,
                    balanceMinor,
                    "UZS",
                    "OPENING",
                    "fixture",
                    ActorRef.user("fixture", null),
                    "open-" + customer,
                    ""));
        }
    }

    private void seedTenant(UUID tenantId, UUID brandId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", brandId).param("t", tenantId).update();
    }

    private void installation(UUID id, String providerType) {
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :t, 'POS', :providerType, 'clopos-open-api-v2', 'Till', 'ACTIVE')
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("providerType", providerType)
                .update();
    }

    private void binding(UUID id, UUID installation, String status) {
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :t, :i, :b, :status)
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("i", installation)
                .param("b", BRAND)
                .param("status", status)
                .update();
    }

    private void enableCapability(UUID bindingId) {
        jdbc.sql("""
                INSERT INTO integration.binding_capabilities (binding_id, tenant_id, capability_code, enabled)
                VALUES (:b, :t, 'LOYALTY_BALANCE_SYNC', true)
                """).param("b", bindingId).param("t", TENANT).update();
    }

    private void grant(String subject, PlatformRole role, UUID tenant) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'loyalty pos sync staging test', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenant)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                // Long ago, so the grant is in force whatever instant this test's clock stands at.
                .param("validFrom", Instant.parse("2026-01-01T00:00:00Z").atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    /** A clock the test moves, so every interval is advanced and never waited for. */
    static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    record RecordedAlert(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            String eventClass,
            String idempotencyKeyBase,
            Map<String, String> variables) {}

    /** The operations alert port, recording what it is asked and sending nothing. */
    static final class RecordingAlerts implements OperationsAlertPort {

        private final List<RecordedAlert> calls = new ArrayList<>();

        List<RecordedAlert> calls() {
            return List.copyOf(calls);
        }

        void clear() {
            calls.clear();
        }

        @Override
        public void fanOut(
                UUID tenantId,
                UUID brandId,
                @Nullable UUID locationId,
                String eventClass,
                String templateKey,
                String subjectType,
                UUID subjectId,
                @Nullable UUID triggerEventId,
                String idempotencyKeyBase,
                Map<String, String> triggerVariables,
                Duration expiry) {
            calls.add(new RecordedAlert(
                    tenantId, brandId, locationId, eventClass, idempotencyKeyBase, Map.copyOf(triggerVariables)));
        }
    }

    /** What customers have decided about the sync's purpose: nothing, until a test says. */
    static final class StubConsents implements ConsentDirectory {

        private final Map<UUID, Boolean> decisions = new HashMap<>();
        private int asked;
        private @Nullable String lastPurpose;

        void grant(UUID customer) {
            decisions.put(customer, true);
        }

        void withhold(UUID customer) {
            decisions.put(customer, false);
        }

        void clear() {
            decisions.clear();
            asked = 0;
            lastPurpose = null;
        }

        int asked() {
            return asked;
        }

        @Nullable
        String lastPurpose() {
            return lastPurpose;
        }

        @Override
        public Optional<ConsentState> consentFor(
                UUID tenantId, UUID accountId, @Nullable UUID brandId, String purpose, @Nullable String channel) {
            asked++;
            lastPurpose = purpose;
            Boolean decision = decisions.get(accountId);
            return decision == null
                    ? Optional.empty()
                    : Optional.of(new ConsentState(decision, "1", Instant.parse("2026-10-14T00:00:00Z")));
        }
    }

    /** A phone book the sync reads through the customers port. */
    static final class StubPhones implements CustomerPhoneLookup {

        private final Map<String, List<CustomerAccountRef>> byPhone = new HashMap<>();

        void register(String phone, UUID customer) {
            byPhone.computeIfAbsent(phone, key -> new ArrayList<>()).add(new CustomerAccountRef(customer, TENANT));
        }

        void clear() {
            byPhone.clear();
        }

        @Override
        public List<CustomerAccountRef> findByPhone(UUID tenantId, String rawPhoneNumber) {
            return tenantId.equals(TENANT) ? byPhone.getOrDefault(rawPhoneNumber, List.of()) : List.of();
        }

        @Override
        public Optional<CardProfile> cardProfile(UUID tenantId, UUID accountId) {
            return Optional.empty();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }

        @Bean
        FakePosAdapter fakePosAdapter() {
            return new FakePosAdapter();
        }

        /** The application's clock, which the test moves: a run's start and the scheduler's interval read the same one. */
        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock(START);
        }

        @Bean
        @Primary
        StubPhones stubPhones() {
            return new StubPhones();
        }

        @Bean
        @Primary
        StubConsents stubConsents() {
            return new StubConsents();
        }

        @Bean
        @Primary
        RecordingAlerts recordingAlerts() {
            return new RecordingAlerts();
        }
    }
}
