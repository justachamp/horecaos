package uz.horecaos.platform.loyalty.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
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
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerPhoneLookup;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.loyalty.application.LoyaltyAdjustmentService;
import uz.horecaos.platform.loyalty.application.LoyaltyAdjustmentService.AdjustmentCommand;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.pos.FakePosAdapter;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0171 (row 6.3b): keeping a till's bonus balance a mirror of the platform's loyalty ledger, over HTTP, against a real
 * database and a till whose failures can be commanded.
 *
 * <p>The till is {@link FakePosAdapter}'s in-memory wallet, registered as the adapter of a {@code fake-pos} installation
 * whose ceiling row (V0555) says {@code LOYALTY_BALANCE_SYNC} is SUPPORTED; the second installation is {@code clopos},
 * which has no row for the capability and so is {@code UNASSESSED}. Every ledger assertion checks both the entries and
 * the lots, because {@code balance == SUM(lots)} held through every loyalty money bug this repository has had while
 * {@code balance - SUM(entries)} was the one that broke.
 *
 * <p>The people are stand-ins with a distinctive phone each. The phone reaches the sync only through the customers port,
 * and these tests assert it is nowhere in a response, a stored row or a log line.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoyaltyPosSyncHttpTests {

    private static final UUID TENANT = UUID.fromString("018fc100-4000-7000-8000-0000000a7101");
    private static final UUID OTHER_TENANT = UUID.fromString("018fc100-4000-7000-8000-0000000a7102");
    private static final UUID BRAND = UUID.fromString("018fc100-4000-7000-8000-0000000a7103");
    private static final UUID OTHER_BRAND = UUID.fromString("018fc100-4000-7000-8000-0000000a7104");

    private static final UUID FAKE_INSTALLATION = UUID.fromString("018fc100-4000-7000-8000-0000000a7111");
    private static final UUID FAKE_BINDING = UUID.fromString("018fc100-4000-7000-8000-0000000a7112");
    private static final UUID CLOPOS_INSTALLATION = UUID.fromString("018fc100-4000-7000-8000-0000000a7113");
    private static final UUID CLOPOS_BINDING = UUID.fromString("018fc100-4000-7000-8000-0000000a7114");

    private static final UUID AZIZA = UUID.fromString("018fc100-4000-7000-8000-0000000a7121");
    private static final UUID DILYA = UUID.fromString("018fc100-4000-7000-8000-0000000a7122");
    private static final UUID FARRUKH = UUID.fromString("018fc100-4000-7000-8000-0000000a7123");
    private static final UUID NO_POINTS = UUID.fromString("018fc100-4000-7000-8000-0000000a7124");
    private static final UUID TWIN_ONE = UUID.fromString("018fc100-4000-7000-8000-0000000a7125");
    private static final UUID TWIN_TWO = UUID.fromString("018fc100-4000-7000-8000-0000000a7126");

    private static final String PHONE_AZIZA = "+998 90 111 22 33";
    private static final String PHONE_DILYA = "+998 91 222 33 44";
    private static final String PHONE_FARRUKH = "+998 93 333 44 55";
    private static final String PHONE_STRANGER = "+998 94 444 55 66";
    private static final String PHONE_NO_POINTS = "+998 95 555 66 77";
    private static final String PHONE_SHARED = "+998 97 777 88 99";

    private static final String FINANCE = "pos-sync-finance";
    private static final String SECOND_SIGNATURE = "pos-sync-second-signature";
    private static final String SUPPORT = "pos-sync-support";
    private static final String OTHER_FINANCE = "pos-sync-other-tenant-finance";

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
        // The scheduler is exercised by its own tick method; nothing here waits on a timer.
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
    private StubPhones phones;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcLoyaltyStore loyalty;

    @Autowired
    @SuppressWarnings("NullAway")
    private LoyaltyAdjustmentService adjustments;

    @Autowired
    @SuppressWarnings("NullAway")
    private ApprovalService approvals;

    @Autowired
    @SuppressWarnings("NullAway")
    private uz.horecaos.platform.loyalty.api.PosLoyaltyBalancePort port;

    @Autowired
    @SuppressWarnings("NullAway")
    private uz.horecaos.platform.loyalty.application.PosBalanceSyncService sync;

    @Autowired
    @SuppressWarnings("NullAway")
    private uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcPosBalanceSyncStore syncStore;

    @Autowired
    @SuppressWarnings("NullAway")
    private uz.horecaos.platform.tenancy.api.ConfigurationResolver configuration;

    @Autowired
    @SuppressWarnings("NullAway")
    private uz.horecaos.platform.loyalty.application.AdjustmentReasonPolicyService reasonCodes;

    @Autowired
    @SuppressWarnings("NullAway")
    private io.micrometer.core.instrument.MeterRegistry meters;

    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.provider_entity_mappings").update();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        jdbc.sql("TRUNCATE TABLE loyalty.pos_sync_discrepancies, loyalty.pos_sync_movements, "
                        + "loyalty.pos_balance_sync_state, loyalty.pos_sync_runs")
                .update();
        jdbc.sql("DELETE FROM integration.binding_capabilities").update();
        jdbc.sql("DELETE FROM integration.bindings").update();
        jdbc.sql("DELETE FROM integration.installations").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        till.resetWallet();
        phones.clear();

        seedTenant(TENANT, BRAND, "pos-sync-a");
        seedTenant(OTHER_TENANT, OTHER_BRAND, "pos-sync-b");
        installation(CLOPOS_INSTALLATION, "clopos");
        binding(CLOPOS_BINDING, CLOPOS_INSTALLATION, "ACTIVE");
        installation(FAKE_INSTALLATION, FakePosAdapter.PROVIDER_TYPE);
        binding(FAKE_BINDING, FAKE_INSTALLATION, "ACTIVE");
        enableCapability(FAKE_BINDING);

        grant(FINANCE, PlatformRole.TENANT_FINANCE, TENANT);
        grant(SECOND_SIGNATURE, PlatformRole.TENANT_ADMIN, TENANT);
        grant(SUPPORT, PlatformRole.SUPPORT_AGENT, TENANT);
        grant(OTHER_FINANCE, PlatformRole.TENANT_FINANCE, OTHER_TENANT);

        logCapture = new ListAppender<>();
        logCapture.start();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(logCapture);
    }

    @AfterEach
    void detachLogCapture() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(logCapture);
    }

    /** The platform document is not tenant data, so the reset above does not reach it: put the rollout's start back. */
    @AfterEach
    void putTheOperatorReasonCodeListBackToReportOnly() {
        if (reasonCodes.currentVersion() > 0) {
            reasonCodes.author(
                    uz.horecaos.platform.loyalty.domain.AdjustmentReasonPolicy.platformDefault(),
                    reasonCodes.currentVersion(),
                    ActorRef.user("platform-admin", "Platform"),
                    "test: back to report-only");
        }
    }

    // ------------------------------------------------------------------ the vendor is told apart

    @Test
    @DisplayName(
            "a vendor nobody has assessed is UNASSESSED, cannot be given the capability, and cannot be asked to reconcile")
    void anUnassessedVendorIsReportedAsSuchAndRefused() throws Exception {
        MvcResult overview = get(BASE, FINANCE);
        assertThat(overview.getResponse().getStatus()).isEqualTo(200);
        String body = overview.getResponse().getContentAsString(UTF_8);

        assertThat(state(body, CLOPOS_BINDING, "capabilityState")).isEqualTo("UNASSESSED");
        assertThat(state(body, CLOPOS_BINDING, "enabled")).isEqualTo(false);
        assertThat(state(body, CLOPOS_BINDING, "syncing")).isEqualTo(false);
        assertThat(state(body, FAKE_BINDING, "capabilityState")).isEqualTo("SUPPORTED");
        assertThat(state(body, FAKE_BINDING, "syncing")).isEqualTo(true);

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO integration.binding_capabilities (binding_id, tenant_id, capability_code, enabled)
                        VALUES (:b, :t, 'LOYALTY_BALANCE_SYNC', true)
                        """)
                        .param("b", CLOPOS_BINDING)
                        .param("t", TENANT)
                        .update())
                .as("the database, not a screen, refuses a capability nobody has assessed")
                .hasMessageContaining("has no assessed support");

        MvcResult refused = post(BASE + "/runs", FINANCE, "{\"bindingId\":\"%s\"}".formatted(CLOPOS_BINDING));
        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString(UTF_8)).contains("UNASSESSED");
    }

    @Test
    @DisplayName(
            "the standing notice appears for a till that keeps a balance with no capable binding, and not otherwise")
    void theStandingNoticeIsTheTruthAboutAnUnmirroredTill() throws Exception {
        assertThat(JsonPath.<Boolean>read(overview(), "$.standingNotice"))
                .as("the tenant has not said the till keeps a balance")
                .isFalse();

        tillKeepsBalance(true);
        assertThat(JsonPath.<Boolean>read(overview(), "$.tillKeepsBalance")).isTrue();
        assertThat(JsonPath.<Boolean>read(overview(), "$.standingNotice"))
                .as("the till keeps a balance, but a binding carries the capability and syncs")
                .isFalse();

        jdbc.sql("UPDATE integration.bindings SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", FAKE_BINDING)
                .update();
        assertThat(JsonPath.<Boolean>read(overview(), "$.standingNotice"))
                .as("nothing mirrors the till now, so the two can differ and the page says so")
                .isTrue();

        tillKeepsBalance(false);
        assertThat(JsonPath.<Boolean>read(overview(), "$.standingNotice")).isFalse();
    }

    // ------------------------------------------------------------------ matching

    @Test
    @DisplayName(
            "matching pairs a till customer with the one points account its phone resolves to, records it as a discovered"
                    + " mapping, and never creates an account from a till record or guesses between two")
    void matchingIsExactAndNeverGuesses() throws Exception {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        pointsAccount(TWIN_ONE, PHONE_SHARED, 5_000);
        pointsAccount(TWIN_TWO, PHONE_SHARED, 6_000);
        customerWithoutPoints(NO_POINTS, PHONE_NO_POINTS);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000)
                .tillCustomer("T-STRANGER", PHONE_STRANGER, 9_000)
                .tillCustomer("T-NOPOINTS", PHONE_NO_POINTS, 1_000)
                .tillCustomer("T-TWINS", PHONE_SHARED, 7_000);
        long accountsBefore = count("loyalty.accounts");
        long customersBefore = count("customer.customer_accounts");

        MvcResult run = reconcile(FAKE_BINDING);

        assertThat(run.getResponse().getStatus()).isEqualTo(200);
        String body = run.getResponse().getContentAsString(UTF_8);
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("SUCCEEDED");
        assertThat(JsonPath.<Integer>read(body, "$.compared")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(body, "$.matched")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(body, "$.tillOnly"))
                .as("the stranger and the customer with no points account here")
                .isEqualTo(2);
        assertThat(JsonPath.<Integer>read(body, "$.ambiguous"))
                .as("one phone, two accounts: reported, never guessed (ADR 0015)")
                .isEqualTo(1);

        assertThat(jdbc.sql("""
                        SELECT mapping_source FROM integration.provider_entity_mappings
                         WHERE entity_type = 'LOYALTY_CUSTOMER' AND external_entity_id = 'T-AZIZA'
                           AND horecaos_entity_id = :account
                        """)
                        .param("account", accountOf(AZIZA))
                        .query(String.class)
                        .single())
                .isEqualTo("DISCOVERED");
        assertThat(count("integration.provider_entity_mappings")).isEqualTo(1);
        assertThat(count("loyalty.accounts"))
                .as("no account is made from a till record")
                .isEqualTo(accountsBefore);
        assertThat(count("customer.customer_accounts")).isEqualTo(customersBefore);
        assertNoPhoneAnywhere(body);
    }

    // ------------------------------------------------------------------ the report

    @Test
    @DisplayName(
            "a reconciliation names every difference with both balances and the delta, changes neither side, and the"
                    + " binding starts in REPORT_ONLY so a first run applies and pushes nothing")
    void reconcileReportsAndChangesNothing() throws Exception {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        pointsAccount(DILYA, PHONE_DILYA, 10_000);
        pointsAccount(FARRUKH, PHONE_FARRUKH, 30_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000)
                .tillCustomer("T-DILYA", PHONE_DILYA, 22_000)
                .tillCustomer("T-FARRUKH", PHONE_FARRUKH, 5_000);
        till.tillEarn("T-DILYA", 3_000); // a movement the report-only pass must not import: 22 000 -> 25 000
        long ledgerBefore = ledgerSum();

        MvcResult run = reconcile(FAKE_BINDING);

        String body = run.getResponse().getContentAsString(UTF_8);
        assertThat(JsonPath.<Integer>read(body, "$.compared")).isEqualTo(3);
        assertThat(JsonPath.<Integer>read(body, "$.tillHigher")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(body, "$.tillLower")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(body, "$.imported")).isZero();
        assertThat(JsonPath.<Integer>read(body, "$.pushed")).isZero();
        UUID runId = UUID.fromString(JsonPath.read(body, "$.runId"));

        MvcResult detail = get(BASE + "/runs/" + runId, FINANCE);
        String report = detail.getResponse().getContentAsString(UTF_8);
        assertThat(discrepancy(report, "TILL_HIGHER"))
                .containsEntry("platformBalanceMinor", 10_000)
                .containsEntry("tillBalanceMinor", 25_000)
                .containsEntry("deltaMinor", 15_000)
                .containsEntry("status", "OPEN");
        assertThat(discrepancy(report, "TILL_LOWER"))
                .containsEntry("platformBalanceMinor", 30_000)
                .containsEntry("tillBalanceMinor", 5_000)
                .containsEntry("deltaMinor", -25_000);
        assertNoPhoneAnywhere(report);

        assertThat(ledgerSum()).as("a difference is never silently corrected").isEqualTo(ledgerBefore);
        assertThat(till.tillBalance("T-DILYA")).isEqualTo(25_000);
        assertThat(till.tillBalance("T-FARRUKH")).isEqualTo(5_000);
        assertThat(till.balanceWriteCount()).isZero();
        assertInvariants(AZIZA, DILYA, FARRUKH);

        MvcResult listed = get(BASE + "/discrepancies?status=OPEN", FINANCE);
        assertThat(JsonPath.<List<String>>read(listed.getResponse().getContentAsString(UTF_8), "$[*].kind"))
                .containsExactlyInAnyOrder("TILL_HIGHER", "TILL_LOWER");
    }

    // ------------------------------------------------------------------ pull

    @Test
    @DisplayName(
            "what happened at the till is imported as an order-free adjustment by a system actor, once however often the"
                    + " feed repeats it, with a lot that expires like any other and a ledger that still adds up")
    void pullImportsTillMovementsAsFactsExactlyOnce() throws Exception {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000);
        mode("PULL");
        reconcile(FAKE_BINDING); // matches the customer; nothing has happened at the till yet
        till.tillEarn("T-AZIZA", 5_000);
        till.tillSpend("T-AZIZA", 2_000);
        till.duplicateNextMovementPage();

        MvcResult run = reconcile(FAKE_BINDING);

        String body = run.getResponse().getContentAsString(UTF_8);
        assertThat(JsonPath.<Integer>read(body, "$.imported")).isEqualTo(2);
        assertThat(balanceOf(AZIZA)).isEqualTo(43_000);
        List<Map<String, Object>> entries =
                jdbc.sql("""
                SELECT reason_code, actor, amount_minor, idempotency_key, order_id
                  FROM loyalty.entries WHERE account_id = :a AND reason_code LIKE 'POS_SYNC_%'
                 ORDER BY occurred_at, amount_minor
                """).param("a", accountOf(AZIZA)).query().listOfRows();
        assertThat(entries).hasSize(2);
        assertThat(entries)
                .extracting(row -> row.get("reason_code"))
                .containsExactlyInAnyOrder("POS_SYNC_EARN", "POS_SYNC_SPEND");
        assertThat(entries).allSatisfy(row -> {
            assertThat(String.valueOf(row.get("actor"))).isEqualTo("system:pos-sync:" + FAKE_BINDING);
            assertThat(row.get("order_id"))
                    .as("order-free, the only movement the ledger admits without an order")
                    .isNull();
            assertThat(String.valueOf(row.get("idempotency_key"))).startsWith("POSSYNC:" + FAKE_BINDING + ":");
        });

        reconcile(FAKE_BINDING);
        reconcile(FAKE_BINDING);
        assertThat(balanceOf(AZIZA))
                .as("a replayed page imports each movement once")
                .isEqualTo(43_000);
        assertThat(count("loyalty.pos_sync_movements")).isEqualTo(2);

        Integer lifetimeDays =
                jdbc.sql("""
                        SELECT round(extract(epoch FROM (expires_at - earns_at)) / 86400)::int FROM loyalty.lots
                         WHERE account_id = :a AND source_entry_id IN (
                             SELECT id FROM loyalty.entries WHERE reason_code = 'POS_SYNC_EARN')
                        """).param("a", accountOf(AZIZA)).query(Integer.class).single();
        assertThat(lifetimeDays)
                .as("an imported credit expires on the platform's schedule, 180 days, like every credited adjustment")
                .isEqualTo(180);
        assertInvariants(AZIZA);
    }

    @Test
    @DisplayName(
            "a spend at the till larger than the balance applies what the balance covers, absorbs the rest, never takes the"
                    + " balance negative, and leaves the shortfall on the discrepancy list")
    void aTillSpendOverTheBalanceIsAbsorbed() throws Exception {
        pointsAccount(DILYA, PHONE_DILYA, 10_000);
        till.tillCustomer("T-DILYA", PHONE_DILYA, 10_000);
        mode("PULL");
        reconcile(FAKE_BINDING);
        till.tillSpend("T-DILYA", 10_000);
        // The customer spent the same points on an order the till had not yet heard about.
        adjustments.adjust(new AdjustmentCommand(
                TENANT,
                BRAND,
                DILYA,
                -6_000,
                "UZS",
                "GOODWILL",
                "raced the till",
                ActorRef.user("fixture", null),
                "race-1",
                ""));

        MvcResult run = reconcile(FAKE_BINDING);

        assertThat(balanceOf(DILYA)).isZero();
        assertThat(jdbc.sql("SELECT applied_minor || '/' || absorbed_minor FROM loyalty.pos_sync_movements")
                        .query(String.class)
                        .single())
                .as("the balance covered 4 000 of the 10 000 spent; the brand carries 6 000")
                .isEqualTo("4000/6000");
        String discrepancies =
                get(BASE + "/discrepancies?status=OPEN", FINANCE).getResponse().getContentAsString(UTF_8);
        assertThat(discrepancy(discrepancies, "SPEND_EXCEEDS_BALANCE")).containsEntry("deltaMinor", 6_000);
        assertThat(JsonPath.<Integer>read(run.getResponse().getContentAsString(UTF_8), "$.imported"))
                .isEqualTo(1);
        assertInvariants(DILYA);
    }

    @Test
    @DisplayName(
            "a credit above the cap is held and credits nothing; a person accepts it and it is credited once, never twice")
    void aCreditAboveTheCapIsHeldUntilAPersonResolvesIt() throws Exception {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000);
        brandPolicy("loyalty.pos_sync.max_auto_credit_minor", 8_000);
        mode("PULL");
        reconcile(FAKE_BINDING);
        till.tillEarn("T-AZIZA", 9_000);

        MvcResult run = reconcile(FAKE_BINDING);

        assertThat(JsonPath.<Integer>read(run.getResponse().getContentAsString(UTF_8), "$.held"))
                .isEqualTo(1);
        assertThat(balanceOf(AZIZA)).as("held credits nothing").isEqualTo(40_000);
        Map<String, Object> held = discrepancy(
                get(BASE + "/discrepancies?status=OPEN", FINANCE).getResponse().getContentAsString(UTF_8),
                "CREDIT_HELD");
        UUID id = UUID.fromString(String.valueOf(held.get("discrepancyId")));

        MvcResult accepted =
                resolve(id, intOf(held.get("version")), "ACCEPT_TILL", "Aziza earned this on a big table", FINANCE);

        assertThat(accepted.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<Boolean>read(accepted.getResponse().getContentAsString(UTF_8), "$.applied"))
                .isTrue();
        assertThat(balanceOf(AZIZA)).isEqualTo(49_000);
        MvcResult again = resolve(id, intOf(held.get("version")) + 1, "ACCEPT_TILL", "again", FINANCE);
        assertThat(again.getResponse().getStatus()).as("resolved once").isEqualTo(409);
        assertThat(balanceOf(AZIZA)).isEqualTo(49_000);
        reconcile(FAKE_BINDING);
        assertThat(balanceOf(AZIZA))
                .as("the held movement is not imported a second time")
                .isEqualTo(49_000);
        assertInvariants(AZIZA);
    }

    @Test
    @DisplayName("the binding-wide cap holds the credit that would take one binding over its day's total")
    void theBindingCapHoldsTheOverflowingCredit() throws Exception {
        pointsAccount(AZIZA, PHONE_AZIZA, 1_000);
        pointsAccount(DILYA, PHONE_DILYA, 1_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 1_000).tillCustomer("T-DILYA", PHONE_DILYA, 1_000);
        brandPolicy("loyalty.pos_sync.max_binding_credit_minor_per_day", 12_000);
        mode("PULL");
        reconcile(FAKE_BINDING);
        till.tillEarn("T-AZIZA", 7_000);
        till.tillEarn("T-DILYA", 7_000);

        MvcResult run = reconcile(FAKE_BINDING);

        String body = run.getResponse().getContentAsString(UTF_8);
        assertThat(JsonPath.<Integer>read(body, "$.imported")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(body, "$.held")).isEqualTo(1);
        assertThat(balanceOf(AZIZA) + balanceOf(DILYA))
                .as("one credited, one held")
                .isEqualTo(2_000 + 7_000);
    }

    @Test
    @DisplayName(
            "a credit above the manual-adjustment threshold waits for a second person's signature, and is credited once it is signed")
    void aLargeResolutionNeedsASecondSignature() throws Exception {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000);
        brandPolicy("loyalty.pos_sync.max_auto_credit_minor", 8_000);
        manualAdjustmentNeedsASecondSignature();
        mode("PULL");
        reconcile(FAKE_BINDING);
        till.tillEarn("T-AZIZA", 150_000);
        reconcile(FAKE_BINDING);
        Map<String, Object> held = discrepancy(
                get(BASE + "/discrepancies?status=OPEN", FINANCE).getResponse().getContentAsString(UTF_8),
                "CREDIT_HELD");
        UUID id = UUID.fromString(String.valueOf(held.get("discrepancyId")));

        MvcResult first = resolve(id, intOf(held.get("version")), "ACCEPT_TILL", "A wedding party", FINANCE);

        String body = first.getResponse().getContentAsString(UTF_8);
        assertThat(JsonPath.<Boolean>read(body, "$.applied")).isFalse();
        assertThat(JsonPath.<String>read(body, "$.approval")).isEqualTo("PENDING");
        assertThat(balanceOf(AZIZA))
                .as("nothing is credited before the second signature")
                .isEqualTo(40_000);
        assertThat(JsonPath.<String>read(body, "$.discrepancy.status")).isEqualTo("OPEN");

        UUID request = UUID.fromString(JsonPath.read(body, "$.approvalRequestId"));
        approvals.decide(
                request, ApprovalService.Decision.APPROVE, ActorRef.user(SECOND_SIGNATURE, null), "seen the booking");
        MvcResult second = resolve(id, intOf(held.get("version")), "ACCEPT_TILL", "A wedding party", FINANCE);

        assertThat(JsonPath.<Boolean>read(second.getResponse().getContentAsString(UTF_8), "$.applied"))
                .isTrue();
        assertThat(balanceOf(AZIZA)).isEqualTo(190_000);
        assertThat(jdbc.sql(
                                "SELECT approval_id FROM loyalty.entries WHERE account_id = :a AND reason_code = 'POS_SYNC_EARN'")
                        .param("a", accountOf(AZIZA))
                        .query(UUID.class)
                        .single())
                .as("the entry carries the approval that allowed it")
                .isEqualTo(request);
        assertInvariants(AZIZA);
    }

    // ------------------------------------------------------------------ resolutions

    @Test
    @DisplayName(
            "accepting the till's number applies the difference as a correction adjustment attributed to the person who decided")
    void acceptTillAppliesTheDifference() throws Exception {
        pointsAccount(DILYA, PHONE_DILYA, 10_000);
        till.tillCustomer("T-DILYA", PHONE_DILYA, 25_000);
        reconcile(FAKE_BINDING);
        Map<String, Object> difference = onlyDiscrepancy();

        MvcResult resolved = resolve(
                idOf(difference), intOf(difference.get("version")), "ACCEPT_TILL", "Counted at the till", FINANCE);

        assertThat(resolved.getResponse().getStatus()).isEqualTo(200);
        assertThat(balanceOf(DILYA)).isEqualTo(25_000);
        assertThat(jdbc.sql(
                                "SELECT actor || '|' || reason_code FROM loyalty.entries WHERE reason_code = 'POS_SYNC_CORRECTION'")
                        .query(String.class)
                        .single())
                .isEqualTo(FINANCE + "|POS_SYNC_CORRECTION");
        assertThat(jdbc.sql("SELECT resolution_code || '|' || resolved_by FROM loyalty.pos_sync_discrepancies")
                        .query(String.class)
                        .single())
                .isEqualTo("ACCEPT_TILL|" + FINANCE);
        assertInvariants(DILYA);
    }

    @Test
    @DisplayName(
            "accepting the till's number is not an operator-typed reason: with the ADR 0165 list enforced a held credit and a till-higher difference both still resolve, and the unlisted-code counter does not move")
    void acceptTillNeverPassesThroughTheOperatorReasonCodeList() throws Exception {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        pointsAccount(DILYA, PHONE_DILYA, 10_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000).tillCustomer("T-DILYA", PHONE_DILYA, 25_000);
        brandPolicy("loyalty.pos_sync.max_auto_credit_minor", 8_000);
        mode("PULL");
        reconcile(FAKE_BINDING);
        till.tillEarn("T-AZIZA", 9_000);
        reconcile(FAKE_BINDING);
        String open =
                get(BASE + "/discrepancies?status=OPEN", FINANCE).getResponse().getContentAsString(UTF_8);
        Map<String, Object> held = discrepancy(open, "CREDIT_HELD");
        Map<String, Object> higher = discrepancyOfTillCustomer(open, "TILL_HIGHER", "T-DILYA");
        double offListBefore = unlistedReasonCodes("REPORT_ONLY") + unlistedReasonCodes("ENFORCE");

        MvcResult reportOnly =
                resolve(idOf(higher), intOf(higher.get("version")), "ACCEPT_TILL", "Counted at the till", FINANCE);

        assertThat(reportOnly.getResponse().getStatus()).isEqualTo(200);
        assertThat(balanceOf(DILYA)).isEqualTo(25_000);
        assertThat(unlistedReasonCodes("REPORT_ONLY") + unlistedReasonCodes("ENFORCE"))
                .as("the sync's own reason code is not an off-list label an operator typed")
                .isEqualTo(offListBefore);

        enforceTheOperatorReasonCodeList();
        double enforcedBefore = unlistedReasonCodes("ENFORCE");

        MvcResult enforced =
                resolve(idOf(held), intOf(held.get("version")), "ACCEPT_TILL", "Aziza earned this", FINANCE);

        assertThat(enforced.getResponse().getStatus())
                .as(enforced.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(balanceOf(AZIZA)).isEqualTo(49_000);
        assertThat(unlistedReasonCodes("ENFORCE"))
                .as("an enforcing list refused nothing the sync wrote")
                .isEqualTo(enforcedBefore);
        assertInvariants(AZIZA, DILYA);
    }

    @Test
    @DisplayName(
            "a person cannot reach the sync's reason codes by typing them: enforcing the list still refuses POS_SYNC_EARN on an operator adjustment")
    void anOperatorStillCannotTypeASyncReasonCode() {
        pointsAccount(AZIZA, PHONE_AZIZA, 1_000);
        enforceTheOperatorReasonCodeList();

        assertThatThrownBy(() -> adjustments.adjust(new AdjustmentCommand(
                        TENANT,
                        BRAND,
                        AZIZA,
                        500,
                        "UZS",
                        uz.horecaos.platform.loyalty.application.PosBalanceSyncReasons.EARN,
                        "typed by a person",
                        ActorRef.user(FINANCE, null),
                        "typed-" + UUID.randomUUID(),
                        "")))
                .isInstanceOf(uz.horecaos.platform.web.api.ApiException.class);
        assertThat(balanceOf(AZIZA)).isEqualTo(1_000);

        // and the sync's own path does not become a way round the list for a code a person chose
        assertThatThrownBy(() -> adjustments.adjustFromTillSync(new AdjustmentCommand(
                        TENANT,
                        BRAND,
                        AZIZA,
                        500,
                        "UZS",
                        "GOODWILL",
                        "not the sync's",
                        ActorRef.user(FINANCE, null),
                        "goodwill-" + UUID.randomUUID(),
                        "")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(balanceOf(AZIZA)).isEqualTo(1_000);
    }

    @Test
    @DisplayName("accepting the platform's number pushes it to the till and leaves the ledger alone")
    void acceptPlatformPushesTheLedgerNumber() throws Exception {
        pointsAccount(DILYA, PHONE_DILYA, 10_000);
        till.tillCustomer("T-DILYA", PHONE_DILYA, 25_000);
        reconcile(FAKE_BINDING);
        Map<String, Object> difference = onlyDiscrepancy();

        MvcResult resolved = resolve(
                idOf(difference), intOf(difference.get("version")), "ACCEPT_PLATFORM", "The till was wrong", FINANCE);

        assertThat(resolved.getResponse().getStatus()).isEqualTo(200);
        assertThat(till.tillBalance("T-DILYA")).isEqualTo(10_000);
        assertThat(balanceOf(DILYA)).isEqualTo(10_000);
        assertThat(count("loyalty.entries WHERE reason_code = 'POS_SYNC_CORRECTION'"))
                .isZero();
    }

    @Test
    @DisplayName(
            "a resolution needs If-Match, loses to a newer version, is refused to a reader and answers 404 across tenants")
    void resolutionsAreGuarded() throws Exception {
        pointsAccount(DILYA, PHONE_DILYA, 10_000);
        till.tillCustomer("T-DILYA", PHONE_DILYA, 25_000);
        reconcile(FAKE_BINDING);
        Map<String, Object> difference = onlyDiscrepancy();
        UUID id = idOf(difference);
        int version = intOf(difference.get("version"));
        String path = BASE + "/discrepancies/" + id + "/resolutions";
        String body = "{\"resolution\":\"ABSORB\",\"reason\":\"seen\"}";

        MvcResult noMatch = mvc.perform(MockMvcRequestBuilders.post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(tokenFor(FINANCE)))
                .andReturn();
        assertThat(noMatch.getResponse().getStatus()).as("no If-Match").isEqualTo(400);

        assertThat(resolve(id, version + 5, "ABSORB", "seen", FINANCE)
                        .getResponse()
                        .getStatus())
                .as("a stale version")
                .isEqualTo(409);
        assertThat(resolve(id, version, "ABSORB", "seen", SUPPORT).getResponse().getStatus())
                .as("a reader holds loyalty.read and not loyalty.sync.manage")
                .isEqualTo(403);
        assertThat(count("loyalty.pos_sync_discrepancies WHERE status = 'RESOLVED'"))
                .isZero();

        MvcResult foreign = mvc.perform(MockMvcRequestBuilders.post("/api/v1/operations/tenants/" + OTHER_TENANT
                                + "/brands/" + OTHER_BRAND + "/loyalty/pos-sync/discrepancies/" + id + "/resolutions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "W/\"" + version + "\"")
                        .with(tokenFor(OTHER_FINANCE)))
                .andReturn();
        assertThat(foreign.getResponse().getStatus())
                .as("another tenant's discrepancy is no oracle")
                .isEqualTo(404);
        assertThat(get(BASE, OTHER_FINANCE).getResponse().getStatus())
                .as("and another tenant's finance cannot read this brand's report")
                .isEqualTo(403);
        assertThat(resolve(id, version, "ABSORB", "seen", FINANCE).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("a resolution that does not fit the kind of difference is refused, with what would fit")
    void aResolutionMustFitTheDifference() throws Exception {
        till.tillCustomer("T-STRANGER", PHONE_STRANGER, 9_000);
        reconcile(FAKE_BINDING);
        Map<String, Object> difference = onlyDiscrepancy();
        assertThat(difference).containsEntry("kind", "TILL_ONLY");

        MvcResult refused = resolve(
                idOf(difference), intOf(difference.get("version")), "ACCEPT_TILL", "no account to credit", FINANCE);

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(refused.getResponse().getContentAsString(UTF_8)).contains("ABSORB");
        assertThat(resolve(idOf(difference), intOf(difference.get("version")), "ABSORB", "not our customer", FINANCE)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ push

    @Test
    @DisplayName(
            "the platform's number is pushed to the till once per version, a lost reply is settled by reading and not by sending"
                    + " again, and a spend at the terminal during a push converges")
    void pushConvergesAndNeverDoubleSends() throws Exception {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000);
        mode("PULL_AND_PUSH");
        reconcile(FAKE_BINDING); // first pass: matches and pushes the current version
        int firstWrites = till.balanceWriteCount();
        assertThat(firstWrites).isEqualTo(1);
        reconcile(FAKE_BINDING);
        assertThat(till.balanceWriteCount())
                .as("nothing moved, so nothing is pushed again")
                .isEqualTo(firstWrites);

        // The platform moves (an order redeemed points elsewhere); the reply to the push is lost after the till applied
        // it.
        adjustments.adjust(new AdjustmentCommand(
                TENANT,
                BRAND,
                AZIZA,
                -5_000,
                "UZS",
                "GOODWILL",
                "redeemed",
                ActorRef.user("fixture", null),
                "move-1",
                ""));
        till.loseNextBalanceWriteReply();
        MvcResult run = reconcile(FAKE_BINDING);

        assertThat(JsonPath.<Integer>read(run.getResponse().getContentAsString(UTF_8), "$.pushed"))
                .isEqualTo(1);
        assertThat(till.balanceWriteCount())
                .as("one send, and the read settled the doubt")
                .isEqualTo(firstWrites + 1);
        assertThat(till.tillBalance("T-AZIZA")).isEqualTo(35_000);
        assertThat(jdbc.sql("SELECT last_pushed_version FROM loyalty.pos_balance_sync_state")
                        .query(Integer.class)
                        .single())
                .isEqualTo(jdbc.sql("SELECT version FROM loyalty.accounts WHERE id = :a")
                        .param("a", accountOf(AZIZA))
                        .query(Integer.class)
                        .single());

        // A spend at the terminal lands while the next push is in flight.
        adjustments.adjust(new AdjustmentCommand(
                TENANT,
                BRAND,
                AZIZA,
                1_000,
                "UZS",
                "GOODWILL",
                "top-up",
                ActorRef.user("fixture", null),
                "move-2",
                ""));
        till.duringNextBalanceWrite(() -> till.tillSpend("T-AZIZA", 4_000));
        reconcile(FAKE_BINDING);
        reconcile(FAKE_BINDING);
        reconcile(FAKE_BINDING);

        assertThat(balanceOf(AZIZA)).as("the spend was imported as a fact").isEqualTo(36_000 - 4_000);
        assertThat(till.tillBalance("T-AZIZA"))
                .as("and the two agree once it has been")
                .isEqualTo(balanceOf(AZIZA));
        assertThat(count("loyalty.pos_sync_discrepancies WHERE status = 'OPEN'"))
                .isZero();
        assertInvariants(AZIZA);
    }

    @Test
    @DisplayName("a suspended binding imports nothing and cannot be reconciled")
    void aSuspendedBindingDoesNothing() throws Exception {
        jdbc.sql("UPDATE integration.bindings SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", FAKE_BINDING)
                .update();

        MvcResult refused = reconcile(FAKE_BINDING);

        assertThat(refused.getResponse().getStatus()).isEqualTo(422);
        assertThat(count("loyalty.pos_sync_runs")).isZero();
    }

    @Test
    @DisplayName("a feed or a customer list that cannot be read is reported as unverifiable and fails nothing else")
    void anUnreadableTillIsNotAFailureOfThePass() throws Exception {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000);
        mode("PULL");
        reconcile(FAKE_BINDING);
        till.movementFeedUnreadable(true);

        MvcResult run = reconcile(FAKE_BINDING);

        assertThat(run.getResponse().getStatus()).isEqualTo(200);
        assertThat(JsonPath.<String>read(run.getResponse().getContentAsString(UTF_8), "$.status"))
                .isEqualTo("SUCCEEDED");
        assertThat(JsonPath.<Integer>read(run.getResponse().getContentAsString(UTF_8), "$.unverifiable"))
                .isPositive();
    }

    @Test
    @DisplayName("a currency other than the account's is refused rather than compared")
    void aBalanceInAnotherCurrencyIsUnverifiable() throws Exception {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000).balanceInCurrency("USD");

        MvcResult run = reconcile(FAKE_BINDING);

        String body = run.getResponse().getContentAsString(UTF_8);
        assertThat(JsonPath.<Integer>read(body, "$.unverifiable")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(body, "$.tillHigher") + JsonPath.<Integer>read(body, "$.tillLower"))
                .isZero();
    }

    @Test
    @DisplayName("a customer's phone reaches no response, no stored row and no log line")
    void thePhoneStaysInsideTheMatch() throws Exception {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000).tillCustomer("T-STRANGER", PHONE_STRANGER, 9_000);
        mode("PULL_AND_PUSH");

        MvcResult run = reconcile(FAKE_BINDING);
        get(BASE, FINANCE);
        get(BASE + "/discrepancies", FINANCE);

        assertNoPhoneAnywhere(run.getResponse().getContentAsString(UTF_8));
        for (ILoggingEvent event : logCapture.list) {
            assertThat(event.getFormattedMessage()).doesNotContain("111 22 33").doesNotContain("444 55 66");
            assertThat(String.valueOf(event.getThrowableProxy())).doesNotContain("111 22 33");
        }
        for (String table : List.of(
                "loyalty.pos_balance_sync_state",
                "loyalty.pos_sync_movements",
                "loyalty.pos_sync_discrepancies",
                "loyalty.pos_sync_runs",
                "integration.provider_entity_mappings")) {
            assertThat(jdbc.sql("SELECT coalesce(string_agg(t::text, ' '), '') FROM " + table + " t")
                            .query(String.class)
                            .single())
                    .as(table)
                    .doesNotContain("111 22 33")
                    .doesNotContain("998901112233")
                    .doesNotContain("444 55 66");
        }
    }

    @Test
    @DisplayName(
            "the shortfall a brand absorbs is a line on the liability report, beside the outstanding points and never in them")
    void theAbsorbedShortfallIsOnTheLiabilityReport() throws Exception {
        pointsAccount(DILYA, PHONE_DILYA, 10_000);
        till.tillCustomer("T-DILYA", PHONE_DILYA, 10_000);
        mode("PULL");
        reconcile(FAKE_BINDING);
        till.tillSpend("T-DILYA", 10_000);
        adjustments.adjust(new AdjustmentCommand(
                TENANT,
                BRAND,
                DILYA,
                -6_000,
                "UZS",
                "GOODWILL",
                "raced the till",
                ActorRef.user("fixture", null),
                "race-2",
                ""));
        reconcile(FAKE_BINDING);

        MvcResult liability = get("/api/v1/operations/tenants/" + TENANT + "/reports/loyalty-liability", FINANCE);

        assertThat(liability.getResponse().getStatus()).isEqualTo(200);
        String body = liability.getResponse().getContentAsString(UTF_8);
        assertThat(JsonPath.<Integer>read(body, "$[0].tillAbsorbed.amountMinor"))
                .as("6 000 points were spent at the terminal that the ledger could not cover")
                .isEqualTo(6_000);
        assertThat(JsonPath.<Integer>read(body, "$[0].outstanding.amountMinor")).isZero();
    }

    @Test
    @DisplayName(
            "the scheduler pulls and pushes a binding when its interval has passed and not before, and reconciles once a local day")
    void theSchedulerHonoursTheIntervalAndTheDay() {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000);
        mode("PULL_AND_PUSH");
        var scheduler = new uz.horecaos.platform.loyalty.application.PosBalanceSyncScheduler(
                port, sync, syncStore, configuration, java.time.Clock.systemUTC());
        var tenantBinding = new uz.horecaos.platform.loyalty.api.PosLoyaltyBalancePort.TenantBinding(
                TENANT, BRAND, java.util.Objects.requireNonNull(port.binding(TENANT, FAKE_BINDING)));
        int localHour =
                Instant.now().atZone(java.time.ZoneId.of("Asia/Tashkent")).getHour();

        int first = scheduler.tickOne(tenantBinding);
        int second = scheduler.tickOne(tenantBinding);

        assertThat(first)
                .as("a pull and a push, and the day's reconciliation once it is past 03:00 where the tenant is")
                .isEqualTo(localHour >= 3 ? 3 : 2);
        assertThat(second)
                .as("nothing is due again inside the interval or the day")
                .isZero();
        assertThat(till.tillBalance("T-AZIZA")).isEqualTo(40_000);
        assertThat(count("loyalty.pos_sync_runs WHERE kind = 'PULL'")).isEqualTo(1);
        assertThat(count("loyalty.pos_sync_runs WHERE kind = 'PUSH'")).isEqualTo(1);
    }

    @Test
    @DisplayName("a binding in REPORT_ONLY is only reconciled by the scheduler: it applies nothing and pushes nothing")
    void theSchedulerLeavesAReportOnlyBindingToReconcile() {
        pointsAccount(AZIZA, PHONE_AZIZA, 40_000);
        till.tillCustomer("T-AZIZA", PHONE_AZIZA, 40_000);
        var scheduler = new uz.horecaos.platform.loyalty.application.PosBalanceSyncScheduler(
                port, sync, syncStore, configuration, java.time.Clock.systemUTC());
        var tenantBinding = new uz.horecaos.platform.loyalty.api.PosLoyaltyBalancePort.TenantBinding(
                TENANT, BRAND, java.util.Objects.requireNonNull(port.binding(TENANT, FAKE_BINDING)));
        int localHour =
                Instant.now().atZone(java.time.ZoneId.of("Asia/Tashkent")).getHour();

        int started = scheduler.tickOne(tenantBinding);

        assertThat(started).isEqualTo(localHour >= 3 ? 1 : 0);
        assertThat(count("loyalty.pos_sync_runs WHERE kind IN ('PULL', 'PUSH')"))
                .isZero();
        assertThat(till.balanceWriteCount()).isZero();
    }

    @Test
    @DisplayName(
            "the cross-tenant discovery lists only active, capable, enabled bindings: never the unassessed vendor, never a suspended one")
    void onlyCapableEnabledActiveBindingsAreWalked() {
        var found = port.syncingBindings();

        assertThat(found).extracting(t -> t.binding().bindingId()).containsExactly(FAKE_BINDING);

        jdbc.sql("UPDATE integration.bindings SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", FAKE_BINDING)
                .update();
        assertThat(port.syncingBindings()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private void enforceTheOperatorReasonCodeList() {
        reasonCodes.author(
                new uz.horecaos.platform.loyalty.domain.AdjustmentReasonPolicy(
                        uz.horecaos.platform.loyalty.domain.AdjustmentReasonPolicy.Mode.ENFORCE,
                        uz.horecaos.platform.loyalty.domain.AdjustmentReasonPolicy.DEFAULT_CODES),
                reasonCodes.currentVersion(),
                ActorRef.user("platform-admin", "Platform"),
                "test: the next step of ADR 0165");
    }

    private double unlistedReasonCodes(String mode) {
        var counter = meters.find(
                        uz.horecaos.platform.loyalty.application.AdjustmentReasonPolicyService.UNLISTED_COUNTER_NAME)
                .tag("mode", mode)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private String overview() throws Exception {
        MvcResult result = get(BASE, FINANCE);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString(UTF_8);
    }

    private static String binding(UUID bindingId) {
        return "$.bindings[?(@.bindingId=='" + bindingId + "')]";
    }

    private static Object state(String body, UUID bindingId, String field) {
        List<Object> found = JsonPath.read(body, binding(bindingId) + "." + field);
        return found.getFirst();
    }

    private MvcResult reconcile(UUID bindingId) throws Exception {
        return post(BASE + "/runs", FINANCE, "{\"bindingId\":\"%s\"}".formatted(bindingId));
    }

    private MvcResult resolve(UUID discrepancyId, int version, String resolution, String reason, String subject)
            throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(BASE + "/discrepancies/" + discrepancyId + "/resolutions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"%s\",\"reason\":\"%s\"}".formatted(resolution, reason))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("If-Match", "W/\"" + version + "\"")
                        .with(tokenFor(subject)))
                .andReturn();
    }

    private Map<String, Object> onlyDiscrepancy() throws Exception {
        List<Map<String, Object>> all = JsonPath.read(
                get(BASE + "/discrepancies?status=OPEN", FINANCE).getResponse().getContentAsString(UTF_8), "$");
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    private Map<String, Object> discrepancy(String body, String kind) {
        List<Map<String, Object>> found = JsonPath.read(body, "$..discrepancies[?(@.kind=='" + kind + "')]");
        if (found.isEmpty()) {
            found = JsonPath.read(body, "$[?(@.kind=='" + kind + "')]");
        }
        assertThat(found).as("a %s discrepancy in %s", kind, body).hasSize(1);
        return found.getFirst();
    }

    /** The one difference of this kind for one till customer, when several customers share the kind. */
    private Map<String, Object> discrepancyOfTillCustomer(String body, String kind, String externalCustomerId) {
        List<Map<String, Object>> found = JsonPath.read(
                body, "$[?(@.kind=='" + kind + "' && @.externalCustomerId=='" + externalCustomerId + "')]");
        assertThat(found)
                .as("a %s discrepancy for %s in %s", kind, externalCustomerId, body)
                .hasSize(1);
        return found.getFirst();
    }

    private static UUID idOf(Map<String, Object> discrepancy) {
        return UUID.fromString(String.valueOf(discrepancy.get("discrepancyId")));
    }

    private static int intOf(@Nullable Object value) {
        return ((Number) java.util.Objects.requireNonNull(value)).intValue();
    }

    private void assertNoPhoneAnywhere(String body) {
        assertThat(body)
                .doesNotContain("111 22 33")
                .doesNotContain("222 33 44")
                .doesNotContain("333 44 55")
                .doesNotContain("444 55 66")
                .doesNotContain("555 66 77")
                .doesNotContain("777 88 99")
                .doesNotContain("998901112233");
    }

    /** {@code balance == SUM(entries)} and {@code == SUM(lots.remaining)}: both, because the audit found the second holds while the first breaks. */
    private void assertInvariants(UUID... customers) {
        for (UUID customer : customers) {
            UUID account = accountOf(customer);
            long balance = balanceOf(customer);
            Long entries = jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM loyalty.entries WHERE account_id = :a")
                    .param("a", account)
                    .query(Long.class)
                    .single();
            Long lots = jdbc.sql(
                            "SELECT coalesce(sum(remaining_minor), 0) FROM loyalty.lots WHERE account_id = :a AND status IN ('ACTIVE','PENDING')")
                    .param("a", account)
                    .query(Long.class)
                    .single();
            assertThat(entries)
                    .as("balance minus SUM(entries) for %s", customer)
                    .isEqualTo(balance);
            assertThat(lots)
                    .as("balance minus SUM(lots.remaining) for %s", customer)
                    .isEqualTo(balance);
            assertThat(balance).isGreaterThanOrEqualTo(0);
        }
    }

    private long ledgerSum() {
        Long sum = jdbc.sql("SELECT coalesce(sum(amount_minor), 0) FROM loyalty.entries")
                .query(Long.class)
                .single();
        return sum == null ? 0 : sum;
    }

    private long balanceOf(UUID customer) {
        Long balance = jdbc.sql(
                        "SELECT balance_minor FROM loyalty.accounts WHERE tenant_id = :t AND customer_account_id = :c")
                .param("t", TENANT)
                .param("c", customer)
                .query(Long.class)
                .single();
        return balance == null ? 0 : balance;
    }

    private UUID accountOf(UUID customer) {
        return jdbc.sql("SELECT id FROM loyalty.accounts WHERE tenant_id = :t AND customer_account_id = :c")
                .param("t", TENANT)
                .param("c", customer)
                .query(UUID.class)
                .single();
    }

    private long count(String tableAndPredicate) {
        Long count = jdbc.sql("SELECT count(*) FROM " + tableAndPredicate)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    private void mode(String mode) {
        jdbc.sql(
                        "UPDATE integration.bindings SET configuration_override = jsonb_build_object('loyalty.sync.mode', CAST(:m AS text)) WHERE id = :id")
                .param("m", mode)
                .param("id", FAKE_BINDING)
                .update();
    }

    private void tillKeepsBalance(boolean value) {
        jdbc.sql("DELETE FROM tenant.configuration_values WHERE key_code = 'loyalty.pos.till_keeps_balance'")
                .update();
        jdbc.sql("""
                INSERT INTO tenant.configuration_values (id, key_code, scope_type, tenant_id, brand_id, value_type,
                    boolean_value, set_by)
                VALUES (:id, 'loyalty.pos.till_keeps_balance', 'BRAND', :t, :b, 'BOOLEAN', :v, 'test')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("v", value)
                .update();
    }

    /**
     * The tenant's ADR 0027 policy for {@code loyalty.balance.adjust}. Without one the action is allowed without
     * approval (its missing-policy mode), so the second signature is a tenant decision this test has to make.
     */
    private void manualAdjustmentNeedsASecondSignature() {
        jdbc.sql("""
                INSERT INTO audit.approval_policies
                    (id, tenant_id, action_code, scope_type, threshold_json,
                     required_approver_capability, valid_from, version, approved_by)
                VALUES (:id, :tenantId, 'loyalty.balance.adjust', 'TENANT',
                        CAST('{"description":"second signature above the threshold"}' AS jsonb),
                        :approver, :validFrom, 1, 'platform-admin')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("approver", uz.horecaos.platform.iam.api.Capability.LOYALTY_ADJUST.code())
                .param("validFrom", Instant.now().minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void brandPolicy(String key, long value) {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values (id, key_code, scope_type, tenant_id, brand_id, value_type,
                    integer_value, set_by)
                VALUES (:id, :key, 'BRAND', :t, :b, 'INTEGER', :v, 'test')
                """)
                .param("id", UUID.randomUUID())
                .param("key", key)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("v", value)
                .update();
    }

    private void pointsAccount(UUID customer, String phone, long balanceMinor) {
        customerAccount(customer);
        phones.register(phone, customer);
        loyalty.openAccount(UUID.randomUUID(), TENANT, BRAND, customer, "UZS", Instant.now());
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

    private void customerWithoutPoints(UUID customer, String phone) {
        customerAccount(customer);
        phones.register(phone, customer);
    }

    private void customerAccount(UUID customer) {
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name, identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1)
                ON CONFLICT DO NOTHING
                """).param("id", customer).param("t", TENANT).update();
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
                        'ACTIVE', 'test-fixture', 'loyalty pos sync test', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenant)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private MvcResult get(String path, String subject) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).with(tokenFor(subject)))
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

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    /** A phone book the sync reads through the customers port: the real lookup hashes under keys this test has no use for. */
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
        public java.util.Optional<CardProfile> cardProfile(UUID tenantId, UUID accountId) {
            return java.util.Optional.empty();
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

        @Bean
        @Primary
        StubPhones stubPhones() {
            return new StubPhones();
        }
    }
}
