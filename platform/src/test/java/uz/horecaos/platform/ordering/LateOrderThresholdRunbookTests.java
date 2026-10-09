package uz.horecaos.platform.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.horecaos.platform.ordering.OrderBoardFixtures.order;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.application.OrderLatenessPolicyService;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationResolver;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcPolicyResolver;

/**
 * The runbook that tells tenants what their saved late-order threshold now means (ADR 0150) is only
 * worth keeping if its commands are real. This parses {@code
 * docs/runbooks/late-order-threshold-stored-values.md}, takes the SQL between each {@code <<'SQL'}
 * and its closing {@code SQL}, and runs it against a real PostgreSQL holding the shapes that
 * matter: a company-wide number, a branch's own, an explicit revert, a platform-wide row, and an
 * authored {@code ordering.lateness} document at every scope, whose own per-mode fallback wins over
 * the saved number. The last test holds the report to what the reader ({@code
 * OrderLatenessPolicyService}) actually resolves, so the runbook cannot promise a change the boards
 * will not make.
 */
class LateOrderThresholdRunbookTests {

    private static final Path RUNBOOK = Path.of("docs/runbooks/late-order-threshold-stored-values.md");

    private static final UUID TENANT = UUID.fromString("018fc150-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fc150-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION_ONE = UUID.fromString("018fc150-4000-7000-8000-0000000000c1");
    private static final UUID LOCATION_TWO = UUID.fromString("018fc150-4000-7000-8000-0000000000c2");
    private static final UUID REVERTER = UUID.fromString("018fc150-4000-7000-8000-0000000000a2");
    private static final UUID REVERTER_BRAND = UUID.fromString("018fc150-4000-7000-8000-0000000000b2");
    private static final UUID REVERTER_LOCATION = UUID.fromString("018fc150-4000-7000-8000-0000000000c3");
    private static final UUID UNTOUCHED = UUID.fromString("018fc150-4000-7000-8000-0000000000a3");
    private static final UUID UNTOUCHED_BRAND = UUID.fromString("018fc150-4000-7000-8000-0000000000b3");
    private static final UUID UNTOUCHED_LOCATION = UUID.fromString("018fc150-4000-7000-8000-0000000000c4");

    private static final String KEY = "ordering.late_order_threshold_minutes";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private OrderBoardFixtures fixtures;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(db.dataSource());
        fixtures = new OrderBoardFixtures(jdbc);
        jdbc.sql("DELETE FROM tenant.policy_current WHERE key_code = 'ordering.lateness'")
                .update();
        jdbc.sql("DELETE FROM tenant.policies WHERE key_code = 'ordering.lateness'")
                .update();
        fixtures.clean();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();

        fixtures.tenant(TENANT, "runbook-saved", BRAND, LOCATION_ONE);
        fixtures.location(TENANT, BRAND, LOCATION_TWO, "TWO", "Second");
        fixtures.tenant(REVERTER, "runbook-reverted", REVERTER_BRAND, REVERTER_LOCATION);
        fixtures.tenant(UNTOUCHED, "runbook-untouched", UNTOUCHED_BRAND, UNTOUCHED_LOCATION);
    }

    @Test
    @DisplayName(
            "step 1 lists every tenant that ever saved the number, an explicit revert as 'inherit', and nobody else")
    void stepOneFindsTheTenantsWithAnExplicitValue() {
        save(TENANT, "TENANT", null, null, 20);
        save(TENANT, "LOCATION", BRAND, LOCATION_ONE, 60);
        saveRevert(REVERTER, "TENANT");

        List<Map<String, Object>> rows = jdbc.sql(sqlOf(1)).query().listOfRows();

        assertThat(rows)
                .extracting(row -> row.get("source") + "|" + row.get("tenant") + "|" + row.get("scope_type") + "|"
                        + row.get("value"))
                .containsExactlyInAnyOrder(
                        "SCALAR|runbook-saved|TENANT|20 min",
                        "SCALAR|runbook-saved|LOCATION|60 min",
                        "SCALAR|runbook-reverted|TENANT|inherit");
        assertThat(rows)
                .as("a tenant that never saved the number is not listed: the reader changes nothing for it")
                .noneMatch(row -> "runbook-untouched".equals(row.get("tenant")));
    }

    @Test
    @DisplayName("step 1 also lists the lateness documents authored at brand and branch scope, and a platform-wide row")
    void stepOneListsTheDocumentsAndThePlatformRowsToo() {
        save(TENANT, "TENANT", null, null, 20);
        savePlatform(30);
        saveDocument(TENANT, "BRAND", BRAND, null, 1800, null, 1200);
        saveDocument(TENANT, "LOCATION", BRAND, LOCATION_TWO, null, null, null);

        List<Map<String, Object>> rows = jdbc.sql(sqlOf(1)).query().listOfRows();

        assertThat(rows)
                .extracting(row -> row.get("source") + "|" + row.get("tenant") + "|" + row.get("scope_type") + "|"
                        + row.get("value"))
                .containsExactlyInAnyOrder(
                        "SCALAR|(every tenant)|PLATFORM|30 min",
                        "SCALAR|runbook-saved|TENANT|20 min",
                        "DOCUMENT|runbook-saved|BRAND|delivery=1800 pickup=blank dine_in=1200",
                        "DOCUMENT|runbook-saved|LOCATION|delivery=blank pickup=blank dine_in=blank");
        assertThat(rows)
                .filteredOn(row -> "DOCUMENT".equals(row.get("source")) && "LOCATION".equals(row.get("scope_type")))
                .extracting(row -> row.get("location_id"))
                .as("the scope's own identifiers are printed, so the operator can tell which branch")
                .containsExactly(LOCATION_TWO);
    }

    @Test
    @DisplayName("step 1 prints nothing when nobody ever saved the number")
    void stepOneIsEmptyWhenNobodySaved() {
        assertThat(jdbc.sql(sqlOf(1)).query().listOfRows()).isEmpty();
    }

    @Test
    @DisplayName("step 2 counts the open unpromised orders a saved number would flip, and leaves out what it must")
    void stepTwoCountsWhatWouldChangeColour() {
        save(TENANT, "TENANT", null, null, 20);
        save(TENANT, "LOCATION", BRAND, LOCATION_ONE, 60);
        saveRevert(REVERTER, "TENANT");
        Instant now = Instant.now();
        // Second branch (governed by the company-wide 20): thirty minutes old turns late, ten does not.
        insert("RB-1", LOCATION_TWO, now.minus(Duration.ofMinutes(30)), "RECEIVED", true);
        insert("RB-2", LOCATION_TWO, now.minus(Duration.ofMinutes(10)), "RECEIVED", true);
        insert("RB-3", LOCATION_TWO, now.minus(Duration.ofMinutes(50)), "PREPARING", true);
        // A finished order and an order with a promise are never counted.
        insert("RB-4", LOCATION_TWO, now.minus(Duration.ofMinutes(30)), "COMPLETED", true);
        insert("RB-5", LOCATION_TWO, now.minus(Duration.ofMinutes(30)), "RECEIVED", false);
        // First branch has its own sixty: fifty minutes old is late today and would stop being late.
        insert("RB-6", LOCATION_ONE, now.minus(Duration.ofMinutes(50)), "RECEIVED", true);

        List<Map<String, Object>> rows = jdbc.sql(sqlOf(2)).query().listOfRows();

        assertThat(rows).hasSize(2);
        Map<String, Object> company = rowAt(rows, "TENANT");
        assertThat(company.get("minutes")).isEqualTo(20L);
        assertThat(company.get("open_unpromised_orders"))
                .as("the orders the company-wide number governs: the first branch's own number is not counted here")
                .isEqualTo(3L);
        assertThat(company.get("held_back_by_a_document")).isEqualTo(0L);
        assertThat(company.get("would_turn_late_now"))
                .as("only the thirty-minute-old order: the fifty-minute ones are late at forty-five already")
                .isEqualTo(1L);
        assertThat(company.get("would_stop_being_late_now")).isEqualTo(0L);

        Map<String, Object> branch = rowAt(rows, "LOCATION");
        assertThat(branch.get("minutes")).isEqualTo(60L);
        assertThat(branch.get("open_unpromised_orders")).isEqualTo(1L);
        assertThat(branch.get("would_turn_late_now")).isEqualTo(0L);
        assertThat(branch.get("would_stop_being_late_now")).isEqualTo(1L);
    }

    @Test
    @DisplayName("step 2 does not promise a change where the branch's authored lateness document sets its own "
            + "number for that mode: the saved number is held back there and counted so")
    void stepTwoSeesTheDocumentThatShieldsAnOrder() {
        save(TENANT, "TENANT", null, null, 20);
        // The first branch authored its own thresholds: sixty minutes for delivery, nothing for the other modes.
        saveDocument(TENANT, "LOCATION", BRAND, LOCATION_ONE, 3600, null, null);
        Instant now = Instant.now();
        insert("RD-1", LOCATION_TWO, now.minus(Duration.ofMinutes(30)), "RECEIVED", true, "DELIVERY");
        insert("RD-2", LOCATION_ONE, now.minus(Duration.ofMinutes(30)), "RECEIVED", true, "DELIVERY");
        insert("RD-3", LOCATION_ONE, now.minus(Duration.ofMinutes(30)), "RECEIVED", true, "PICKUP");

        List<Map<String, Object>> rows = jdbc.sql(sqlOf(2)).query().listOfRows();

        assertThat(rows).hasSize(1);
        Map<String, Object> company = rowAt(rows, "TENANT");
        assertThat(company.get("open_unpromised_orders")).isEqualTo(3L);
        assertThat(company.get("held_back_by_a_document"))
                .as("the first branch's delivery order is held to its document's sixty minutes, whatever is saved")
                .isEqualTo(1L);
        assertThat(company.get("would_turn_late_now"))
                .as("the second branch's delivery order and the first branch's pickup order (its document is "
                        + "blank for pickup) turn late at twenty; the shielded one does not")
                .isEqualTo(2L);
        assertThat(company.get("would_stop_being_late_now")).isEqualTo(0L);
    }

    @Test
    @DisplayName("step 2 reaches a tenant that never saved anything when a platform-wide row governs it, "
            + "and a document at the company's own scope shields the whole company")
    void stepTwoCoversThePlatformRowAndACompanyWideDocument() {
        savePlatform(30);
        save(TENANT, "BRAND", BRAND, null, 20);
        saveDocument(TENANT, "TENANT", null, null, 2700, 2700, 2700);
        Instant now = Instant.now();
        // The tenant that never saved a thing: thirty-five minutes old is on time today and late at thirty.
        UUID untouched = UNTOUCHED_LOCATION;
        fixtures.insertOrder(order("RP-1")
                .at(UNTOUCHED, UNTOUCHED_BRAND, untouched)
                .createdAt(now.minus(Duration.ofMinutes(35)))
                .status("RECEIVED")
                .unpromised());
        // The company with a brand number of twenty but a company-wide document of forty-five everywhere:
        // the document wins, so the twenty changes nothing for this thirty-minute-old order.
        insert("RP-2", LOCATION_ONE, now.minus(Duration.ofMinutes(30)), "RECEIVED", true, "DELIVERY");

        List<Map<String, Object>> rows = jdbc.sql(sqlOf(2)).query().listOfRows();

        assertThat(rows).hasSize(2);
        Map<String, Object> platform = rowAt(rows, "PLATFORM");
        assertThat(platform.get("tenant")).isEqualTo("runbook-untouched");
        assertThat(platform.get("minutes")).isEqualTo(30L);
        assertThat(platform.get("would_turn_late_now")).isEqualTo(1L);
        Map<String, Object> brand = rowAt(rows, "BRAND");
        assertThat(brand.get("tenant")).isEqualTo("runbook-saved");
        assertThat(brand.get("held_back_by_a_document")).isEqualTo(1L);
        assertThat(brand.get("would_turn_late_now"))
                .as("held to the company document's forty-five minutes before and after")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("step 2 says what the reader does: for a spread of branches, modes and ages across scalar and "
            + "document scopes, its flips are exactly the orders whose resolved fallback the saved numbers move")
    void stepTwoAgreesWithTheReaderForEveryShape() {
        // Saved numbers at the platform, company and branch scope...
        Runnable saveTheNumbers = () -> {
            savePlatform(30);
            save(TENANT, "TENANT", null, null, 20);
            save(TENANT, "LOCATION", BRAND, LOCATION_ONE, 60);
        };
        saveTheNumbers.run();
        // ...and documents beside them: one for the brand, one for the second branch, one for the other company.
        saveDocument(TENANT, "BRAND", BRAND, null, 1800, null, 900);
        saveDocument(TENANT, "LOCATION", BRAND, LOCATION_TWO, null, 1500, null);
        saveDocument(UNTOUCHED, "TENANT", null, null, 2400, null, null);

        Instant now = Instant.now();
        // Ages chosen a minute clear of every boundary any of the numbers above can draw (15, 20, 25, 30, 40,
        // 45 and 60 minutes), so a few seconds between inserting and querying cannot move an order across.
        int[] ages = {17, 22, 27, 33, 42, 47, 58, 65};
        record Placed(UUID tenant, UUID brand, UUID location, FulfillmentMode mode, int ageMinutes) {}
        List<Placed> placed = new ArrayList<>();
        int seed = 0;
        for (UUID[] place : new UUID[][] {
            {TENANT, BRAND, LOCATION_ONE},
            {TENANT, BRAND, LOCATION_TWO},
            {UNTOUCHED, UNTOUCHED_BRAND, UNTOUCHED_LOCATION}
        }) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                for (int age : ages) {
                    fixtures.insertOrder(order("RM-" + seed++)
                            .at(place[0], place[1], place[2])
                            .createdAt(now.minus(Duration.ofMinutes(age)))
                            .status("RECEIVED")
                            .mode(mode.name())
                            .unpromised());
                    placed.add(new Placed(place[0], place[1], place[2], mode, age));
                }
            }
        }

        // What the reader resolves with the saved numbers in place, and then without them: the difference
        // is exactly what ADR 0150's release changes.
        OrderLatenessPolicyService reader = new OrderLatenessPolicyService(
                new JdbcPolicyResolver(jdbc, new ObjectMapper()), new JdbcConfigurationResolver(jdbc));
        Map<String, Integer> after = new java.util.HashMap<>();
        for (Placed order : placed) {
            after.put(
                    key(order.location(), order.mode()),
                    fallbackOf(reader, order.tenant(), order.brand(), order.location(), order.mode()));
        }
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        Map<String, Integer> before = new java.util.HashMap<>();
        for (Placed order : placed) {
            before.put(
                    key(order.location(), order.mode()),
                    fallbackOf(reader, order.tenant(), order.brand(), order.location(), order.mode()));
        }
        // The report reads the saved numbers, so put them back for it.
        saveTheNumbers.run();

        long expectedTurnLate = 0;
        long expectedStopLate = 0;
        for (Placed order : placed) {
            int age = order.ageMinutes() * 60;
            int was = Objects.requireNonNull(before.get(key(order.location(), order.mode())));
            int willBe = Objects.requireNonNull(after.get(key(order.location(), order.mode())));
            if (age > willBe && age <= was) {
                expectedTurnLate++;
            }
            if (age > was && age <= willBe) {
                expectedStopLate++;
            }
        }

        List<Map<String, Object>> rows = jdbc.sql(sqlOf(2)).query().listOfRows();

        assertThat(rows.stream()
                        .mapToLong(row -> (Long) row.get("would_turn_late_now"))
                        .sum())
                .as("orders the release starts calling late, by the reader's own resolution")
                .isEqualTo(expectedTurnLate);
        assertThat(rows.stream()
                        .mapToLong(row -> (Long) row.get("would_stop_being_late_now"))
                        .sum())
                .as("orders the release stops calling late, by the reader's own resolution")
                .isEqualTo(expectedStopLate);
        assertThat(expectedTurnLate + expectedStopLate)
                .as("the matrix is wide enough to move orders in both directions, or the sums above prove little")
                .isGreaterThan(10L)
                .isNotEqualTo(0L);
        assertThat(expectedStopLate).isGreaterThan(0L);
        assertThat(rows.stream()
                        .mapToLong(row -> (Long) row.get("open_unpromised_orders"))
                        .sum())
                .as("every order is governed by exactly one saved number: none counted twice, none dropped")
                .isEqualTo(placed.size());
    }

    @Test
    @DisplayName("the runbook names only tests that exist and points at the console's own screen")
    void theRunbookIsHonestAboutItself() throws IOException {
        String text = Files.readString(RUNBOOK);

        assertThat(text).contains("LateOrderThresholdRunbookTests");
        assertThat(Files.exists(
                        Path.of("src/test/java/uz/horecaos/platform/ordering/LateOrderThresholdRunbookTests.java")))
                .isTrue();
        assertThat(text)
                .contains("Settings → Order policy → Timing and SLA")
                .contains("ADR 0150")
                .contains("Is there a way back?");
    }

    // ---------------------------------------------------------------- helpers

    private static String key(UUID location, FulfillmentMode mode) {
        return location + "|" + mode;
    }

    private static int fallbackOf(
            OrderLatenessPolicyService reader, UUID tenant, UUID brand, UUID location, FulfillmentMode mode) {
        return reader.resolveAt(ResourceScope.location(tenant, brand, location))
                .policy()
                .forMode(mode)
                .noPromiseFallbackSeconds();
    }

    private static Map<String, Object> rowAt(List<Map<String, Object>> rows, String scopeType) {
        return rows.stream()
                .filter(row -> scopeType.equals(row.get("number_sits_at")))
                .findFirst()
                .orElseThrow();
    }

    /** The SQL of the nth {@code <<'SQL'} block in the runbook, exactly as an operator would paste it. */
    private static String sqlOf(int nth) {
        try {
            Matcher matcher =
                    Pattern.compile("<<'SQL'\n(.*?)\nSQL\n", Pattern.DOTALL).matcher(Files.readString(RUNBOOK));
            List<String> blocks = new ArrayList<>();
            while (matcher.find()) {
                blocks.add(matcher.group(1).trim());
            }
            assertThat(blocks).as("the runbook's SQL blocks").hasSize(2);
            return blocks.get(nth - 1).replaceAll(";\\s*$", "");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RUNBOOK, e);
        }
    }

    private void insert(String seed, UUID location, Instant createdAt, String status, boolean unpromised) {
        insert(seed, location, createdAt, status, unpromised, "DELIVERY");
    }

    private void insert(String seed, UUID location, Instant createdAt, String status, boolean unpromised, String mode) {
        OrderBoardFixtures.OrderSpec spec = order(seed)
                .at(TENANT, BRAND, location)
                .createdAt(createdAt)
                .status(status)
                .mode(mode);
        fixtures.insertOrder(unpromised ? spec.unpromised() : spec.promisedAt(createdAt.plusSeconds(2100)));
    }

    private void savePlatform(int minutes) {
        save(null, "PLATFORM", null, null, minutes);
    }

    /** An authored {@code ordering.lateness} document, active at its scope; null is a mode left blank. */
    private void saveDocument(
            UUID tenant,
            String scopeType,
            @Nullable UUID brand,
            @Nullable UUID location,
            @Nullable Integer delivery,
            @Nullable Integer pickup,
            @Nullable Integer dineIn) {
        UUID policyId = UUID.randomUUID();
        String document =
                "{\"delivery\":" + mode(delivery) + ",\"pickup\":" + mode(pickup) + ",\"dineIn\":" + mode(dineIn) + "}";
        jdbc.sql("""
                INSERT INTO tenant.policies (
                    id, key_code, scope_type, tenant_id, brand_id, location_id, version, status,
                    document, document_hash, valid_from, created_by)
                VALUES (:id, 'ordering.lateness', :scope, :tenant, :brand, :location, 1, 'ACTIVE',
                        CAST(:document AS jsonb), :hash, now(), 'runbook-test')
                """)
                .param("id", policyId)
                .param("scope", scopeType)
                .param("tenant", tenant)
                .param("brand", brand)
                .param("location", location)
                .param("document", document)
                .param("hash", "0".repeat(64))
                .update();
        jdbc.sql("""
                INSERT INTO tenant.policy_current (
                    key_code, scope_type, tenant_id, brand_id, location_id, policy_id, policy_version, activated_by)
                VALUES ('ordering.lateness', :scope, :tenant, :brand, :location, :id, 1, 'runbook-test')
                """)
                .param("id", policyId)
                .param("scope", scopeType)
                .param("tenant", tenant)
                .param("brand", brand)
                .param("location", location)
                .update();
    }

    private static String mode(@Nullable Integer fallbackSeconds) {
        return "{\"atRiskBeforeSeconds\":null,\"lateAfterSeconds\":0,\"noPromiseFallbackSeconds\":"
                + (fallbackSeconds == null ? "null" : fallbackSeconds) + "}";
    }

    private void save(
            @Nullable UUID tenant, String scopeType, @Nullable UUID brand, @Nullable UUID location, int minutes) {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values (
                    id, key_code, scope_type, tenant_id, brand_id, location_id, value_type, integer_value,
                    is_explicit_null, set_by, reason, version)
                VALUES (:id, :key, :scope, :tenant, :brand, :location, 'INTEGER', :minutes, false,
                        'runbook-test', 'saved while it did nothing', 1)
                """)
                .param("id", UUID.randomUUID())
                .param("key", KEY)
                .param("scope", scopeType)
                .param("tenant", tenant)
                .param("brand", brand)
                .param("location", location)
                .param("minutes", minutes)
                .update();
    }

    private void saveRevert(UUID tenant, String scopeType) {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values (
                    id, key_code, scope_type, tenant_id, value_type, is_explicit_null, set_by, reason, version)
                VALUES (:id, :key, :scope, :tenant, 'INTEGER', true, 'runbook-test', 'put back', 2)
                """)
                .param("id", UUID.randomUUID())
                .param("key", KEY)
                .param("scope", scopeType)
                .param("tenant", tenant)
                .update();
    }
}
