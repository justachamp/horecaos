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
import uz.horecaos.platform.support.TestDatabase;

/**
 * The runbook that tells tenants what their saved late-order threshold now means (ADR 0150) is only
 * worth keeping if its commands are real. This parses {@code
 * docs/runbooks/late-order-threshold-stored-values.md}, takes the SQL between each {@code <<'SQL'}
 * and its closing {@code SQL}, and runs it against a real PostgreSQL holding the three shapes that
 * matter: a company-wide number, a branch's own, and an explicit revert.
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
                .extracting(row -> row.get("tenant") + "|" + row.get("scope_type") + "|" + row.get("minutes"))
                .containsExactlyInAnyOrder(
                        "runbook-saved|TENANT|20", "runbook-saved|LOCATION|60", "runbook-reverted|TENANT|inherit")
                .doesNotContain("runbook-untouched|TENANT|null");
        assertThat(rows)
                .as("a tenant that never saved the number is not listed: the reader changes nothing for it")
                .noneMatch(row -> "runbook-untouched".equals(row.get("tenant")));
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
        assertThat(company.get("open_unpromised_orders")).isEqualTo(4L);
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

    private static Map<String, Object> rowAt(List<Map<String, Object>> rows, String scopeType) {
        return rows.stream()
                .filter(row -> scopeType.equals(row.get("scope_type")))
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
        OrderBoardFixtures.OrderSpec spec =
                order(seed).at(TENANT, BRAND, location).createdAt(createdAt).status(status);
        fixtures.insertOrder(unpromised ? spec.unpromised() : spec.promisedAt(createdAt.plusSeconds(2100)));
    }

    private void save(UUID tenant, String scopeType, @Nullable UUID brand, @Nullable UUID location, int minutes) {
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
