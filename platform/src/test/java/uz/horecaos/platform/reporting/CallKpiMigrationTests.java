package uz.horecaos.platform.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The schema half of ADR 0175: V0556 adds the five call-level columns to {@code
 * reporting.fact_call_hour}, a check that holds them together, a partial index for the conversion
 * lookup and the honest comment on {@code talk_duration_seconds}.
 *
 * <p>The "closed before the migration reads NULL" claim is proved over a row that already exists,
 * the only state in which it can be wrong: a table migrated from empty has no old row to leave
 * alone.
 */
class CallKpiMigrationTests {

    /**
     * A migration that exists and sits below V0556. Not "the one just before it": sibling waves hold the
     * numbers between (batch 20 reserves ranges), and what matters is only that V0556 has not run.
     */
    private static final MigrationVersion BEFORE = MigrationVersion.fromVersion("0516");

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 9, 5);

    @Test
    @DisplayName("a call-hour row written before the migration survives it with every KPI null")
    void anExistingRowReadsNotMeasured() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to migrate a database");
        try (TestDatabase.Handle db = TestDatabase.empty()) {
            DataSource dataSource = db.dataSource();
            Flyway.configure().dataSource(dataSource).target(BEFORE).load().migrate();
            JdbcClient jdbc = JdbcClient.create(dataSource);
            insertLegacyRow(jdbc, "alice");

            Flyway.configure().dataSource(dataSource).load().migrate();

            Map<String, Object> row = jdbc.sql("""
                    SELECT answered_call_count, ring_seconds_total, ring_sample_count, ring_seconds_max,
                           converted_count, talk_duration_seconds, answered_count
                    FROM reporting.fact_call_hour WHERE tenant_id = :t AND operator_principal_id = 'alice'
                    """).param("t", TENANT).query().singleRow();
            assertThat(row.get("answered_call_count")).isNull();
            assertThat(row.get("ring_seconds_total")).isNull();
            assertThat(row.get("ring_sample_count")).isNull();
            assertThat(row.get("ring_seconds_max")).isNull();
            assertThat(row.get("converted_count")).isNull();
            assertThat(row.get("talk_duration_seconds"))
                    .as("no stored number is rewritten")
                    .isEqualTo(90L);
            assertThat(row.get("answered_count")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the check accepts every consistent shape and refuses an inconsistent one")
    void theCheckHoldsTheFiveColumnsTogether() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to migrate a database");
        try (TestDatabase.Handle db = TestDatabase.migrated()) {
            JdbcClient jdbc = JdbcClient.create(db.dataSource());

            // Not measured at all: the pre-migration shape.
            insert(jdbc, "legacy", 0, null, null, null, null, null);
            // Measured and empty: a row the new close wrote for an hour with no answered call.
            insert(jdbc, "empty", 1, 0, 0L, 0, null, 0);
            // A sample, a maximum, and some conversions.
            insert(jdbc, "sampled", 2, 3, 40L, 2, 31, 2);
            // An answered call that was not a sample (no OFFERED row): answered but no maximum.
            insert(jdbc, "unsampled", 3, 1, 0L, 0, null, 1);

            assertThat(jdbc.sql("SELECT count(*) FROM reporting.fact_call_hour WHERE tenant_id = :t")
                            .param("t", TENANT)
                            .query(Integer.class)
                            .single())
                    .isEqualTo(4);

            // More samples than answered calls cannot be true.
            assertRefused(jdbc, "samples>answered", 4, 1, 5L, 2, 5, 0);
            // More conversions than answered calls cannot be true.
            assertRefused(jdbc, "converted>answered", 5, 1, 0L, 0, null, 2);
            // A sample with no maximum, and a maximum with no sample.
            assertRefused(jdbc, "sample-no-max", 6, 1, 5L, 1, null, 0);
            assertRefused(jdbc, "max-no-sample", 7, 1, 0L, 0, 5, 0);
            // Half measured: the columns are all-or-nothing.
            assertRefused(jdbc, "half-measured", 8, 1, null, 1, 5, 0);
            // Negative figures.
            assertRefused(jdbc, "negative-total", 9, 1, -1L, 1, 0, 0);
        }
    }

    @Test
    @DisplayName("the application role can write the new columns, the lookup index exists and the column is relabelled")
    void grantsIndexAndTheHonestComment() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to migrate a database");
        try (TestDatabase.Handle db = TestDatabase.migrated()) {
            JdbcClient jdbc = JdbcClient.create(db.dataSource());
            String role = TestDatabase.APPLICATION_ROLE;

            for (String column : List.of(
                    "answered_call_count",
                    "ring_seconds_total",
                    "ring_sample_count",
                    "ring_seconds_max",
                    "converted_count")) {
                for (String privilege : List.of("INSERT", "UPDATE", "SELECT")) {
                    assertThat(Boolean.TRUE.equals(
                                    jdbc.sql("SELECT has_column_privilege(:role, 'reporting.fact_call_hour', :c, :p)")
                                            .param("role", role)
                                            .param("c", column)
                                            .param("p", privilege)
                                            .query(Boolean.class)
                                            .single()))
                            .as("%s on %s (the V0149 table grant covers added columns)", privilege, column)
                            .isTrue();
                }
            }

            assertThat(jdbc.sql("""
                            SELECT indexdef FROM pg_indexes
                            WHERE schemaname = 'ordering' AND indexname = 'ix_orders_source_call'
                            """).query(String.class).single())
                    .contains("(tenant_id, source_call_id)")
                    .contains("WHERE (source_call_id IS NOT NULL)");

            String comment = jdbc.sql("""
                            SELECT col_description('reporting.fact_call_hour'::regclass, attnum)
                            FROM pg_attribute
                            WHERE attrelid = 'reporting.fact_call_hour'::regclass
                              AND attname = 'talk_duration_seconds'
                            """).query(String.class).single();
            assertThat(comment).contains("call length").contains("not talk time");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void insertLegacyRow(JdbcClient jdbc, String operator) {
        jdbc.sql("""
                INSERT INTO reporting.fact_call_hour
                    (tenant_id, business_date, hour_of_day, location_id, brand_id, operator_principal_id,
                     boundary_version, metric_calculation_version, offered_count, answered_count,
                     missed_count, transferred_count, talk_duration_seconds)
                VALUES (:t, :day, 9, :l, :b, :op, 1, 1, 1, 1, 0, 0, 90)
                """)
                .param("t", TENANT)
                .param("day", DAY)
                .param("l", LOCATION)
                .param("b", BRAND)
                .param("op", operator)
                .update();
    }

    private static void insert(
            JdbcClient jdbc,
            String operator,
            int hour,
            @Nullable Integer answeredCalls,
            @Nullable Long ringTotal,
            @Nullable Integer ringSamples,
            @Nullable Integer ringMax,
            @Nullable Integer converted) {
        jdbc.sql("""
                INSERT INTO reporting.fact_call_hour
                    (tenant_id, business_date, hour_of_day, location_id, brand_id, operator_principal_id,
                     boundary_version, metric_calculation_version, answered_call_count, ring_seconds_total,
                     ring_sample_count, ring_seconds_max, converted_count)
                VALUES (:t, :day, :hour, :l, :b, :op, 1, 1, :answered, :total, :samples, :max, :converted)
                """)
                .param("t", TENANT)
                .param("day", DAY)
                .param("hour", hour)
                .param("l", LOCATION)
                .param("b", BRAND)
                .param("op", operator)
                .param("answered", answeredCalls)
                .param("total", ringTotal)
                .param("samples", ringSamples)
                .param("max", ringMax)
                .param("converted", converted)
                .update();
    }

    private static void assertRefused(
            JdbcClient jdbc,
            String operator,
            int hour,
            @Nullable Integer answeredCalls,
            @Nullable Long ringTotal,
            @Nullable Integer ringSamples,
            @Nullable Integer ringMax,
            @Nullable Integer converted) {
        assertThatThrownBy(
                        () -> insert(jdbc, operator, hour, answeredCalls, ringTotal, ringSamples, ringMax, converted))
                .as(operator)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_fact_call_hour_kpi");
    }
}
