package uz.horecaos.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0167, "Testing": no table of the customer funnel can be joined to a person, a session or a device.
 *
 * <p>The absence is the legal posture of the whole record -- counts held on a basis nobody has had to
 * confirm because they name nobody -- so it is asserted over the columns the database actually has, read
 * back from {@code information_schema}, and not trusted to a code review of three {@code CREATE TABLE}
 * statements. A later migration that adds {@code customer_account_id} to any of them fails here, which is
 * the point: that column is ADR 0167's tier 2, specified and not built until counsel answers.
 */
class BehaviouralFunnelStructureTests {

    private static final List<String> FORBIDDEN_SUBSTRINGS =
            List.of("account", "customer", "subject", "session", "guest", "hash", "device", "cookie", "address");

    /** Matched as a whole word, because "ip" is inside "description" and "recipient". */
    private static final List<String> FORBIDDEN_WORDS = List.of("ip", "ua", "agent", "phone", "email", "name");

    private static final List<String[]> TABLES = List.of(
            new String[] {"reporting", "fact_funnel_day"},
            new String[] {"reporting", "fact_registration_day"},
            new String[] {"marketing", "attribution_link_clicks_day"});

    private static TestDatabase.Handle db;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for PostgreSQL tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName("none of the three funnel tables has a column that names or could identify a person")
    void noColumnCouldNameAPerson() {
        JdbcClient jdbc = JdbcClient.create(db.dataSource());

        for (String[] table : TABLES) {
            List<String> columns = jdbc.sql("""
                    SELECT column_name FROM information_schema.columns
                     WHERE table_schema = :schema AND table_name = :table
                     ORDER BY ordinal_position
                    """)
                    .param("schema", table[0])
                    .param("table", table[1])
                    .query(String.class)
                    .list();

            assertThat(columns)
                    .as("%s.%s must exist for this check to mean anything", table[0], table[1])
                    .isNotEmpty();
            for (String column : columns) {
                String lower = column.toLowerCase(Locale.ROOT);
                assertThat(FORBIDDEN_SUBSTRINGS)
                        .as("%s.%s.%s", table[0], table[1], column)
                        .noneMatch(lower::contains);
                assertThat(List.of(lower.split("_")))
                        .as("%s.%s.%s", table[0], table[1], column)
                        .doesNotContainAnyElementsOf(FORBIDDEN_WORDS);
            }
        }
    }

    @Test
    @DisplayName("the check can fail: a column that names a person is caught by the same rule")
    void theCheckCanFail() {
        for (String forbidden :
                List.of("customer_account_id", "guest_reference_hash", "session_id", "client_ip", "device_id")) {
            String lower = forbidden.toLowerCase(Locale.ROOT);
            boolean caught = FORBIDDEN_SUBSTRINGS.stream().anyMatch(lower::contains)
                    || List.of(lower.split("_")).stream().anyMatch(FORBIDDEN_WORDS::contains);
            assertThat(caught).as(forbidden).isTrue();
        }
    }

    @Test
    @DisplayName(
            "the application role can write the funnel tables, and the reporting read role reads the reporting ones")
    void grantsAreInPlace() {
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        for (String[] table : TABLES) {
            String qualified = table[0] + "." + table[1];
            for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                Boolean granted = jdbc.sql("SELECT has_table_privilege('horecaos_application', :table, :privilege)")
                        .param("table", qualified)
                        .param("privilege", privilege)
                        .query(Boolean.class)
                        .single();
                assertThat(granted)
                        .as("%s on %s for horecaos_application", privilege, qualified)
                        .isTrue();
            }
        }
        for (String reporting : List.of("reporting.fact_funnel_day", "reporting.fact_registration_day")) {
            Boolean granted = jdbc.sql("SELECT has_table_privilege('horecaos_reporting_read', :table, 'SELECT')")
                    .param("table", reporting)
                    .query(Boolean.class)
                    .single();
            assertThat(granted)
                    .as("SELECT on %s for horecaos_reporting_read", reporting)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the reporting read role reads the funnel tables and nothing of ordering or customer (ADR 0023)")
    void theReportingRoleDoesNotReachThePeopleTheCountsCameFrom() {
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        for (String sourceTable : List.of(
                "ordering.carts",
                "ordering.cart_lines",
                "ordering.checkout_attempts",
                "customer.brand_profiles",
                "customer.customer_accounts")) {
            Boolean granted = jdbc.sql("SELECT has_table_privilege('horecaos_reporting_read', :table, 'SELECT')")
                    .param("table", sourceTable)
                    .query(Boolean.class)
                    .single();
            assertThat(granted)
                    .as(
                            "horecaos_reporting_read must not read %s: the close job reads it, the reports do not",
                            sourceTable)
                    .isFalse();
        }
    }
}
