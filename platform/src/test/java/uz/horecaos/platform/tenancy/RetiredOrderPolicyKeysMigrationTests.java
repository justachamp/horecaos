package uz.horecaos.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.tenancy.infrastructure.persistence.ConfigurationKeyStartupValidator;

/**
 * ADR 0177 decision 2: five configuration keys left the registry, and a stored value for one would
 * stop the platform booting ({@link ConfigurationKeyStartupValidator}, ADR 0030). This is the proof
 * that the migration which ships with that change removes them and nothing else.
 *
 * <p>The migration has already run when the database comes up, against an empty table. So this puts
 * the rows back -- the state of a developer's or a reviewer's database where somebody once saved one of
 * these fields -- shows the validator refusing it, runs the very file Flyway ran, and shows the
 * validator content and the live row untouched. What would still pass if the delete named the wrong
 * keys: nothing here, because the live row and the second validator pass both depend on which codes
 * it removed.
 */
class RetiredOrderPolicyKeysMigrationTests {

    private static final List<String> RETIRED = List.of(
            "ordering.business_day_start_hour",
            "ordering.average_order_minutes",
            "ordering.maximum_order_minutes",
            "ordering.vat_rate_percent",
            "ordering.routing_poll_interval_minutes");

    private static final String LIVE = "ordering.minimum_order_amount_minor";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private JdbcClient jdbc;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
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
        jdbc.sql("DELETE FROM tenant.configuration_values").update();
    }

    @Test
    @DisplayName(
            "a stored value for a retired key stops the platform booting, and the migration removes exactly those rows")
    void theMigrationRemovesTheRetiredRowsAndOnlyThose() throws IOException {
        for (String code : RETIRED) {
            insertPlatformValue(code, 6);
        }
        insertPlatformValue(LIVE, 50_000);
        var validator = new ConfigurationKeyStartupValidator(jdbc);

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .as("the alarm the migration exists to switch off")
                .isInstanceOf(ConfigurationKeys.UnknownConfigurationKeyException.class)
                .hasMessageContaining("ordering.business_day_start_hour")
                .hasMessageContaining("ordering.vat_rate_percent")
                .hasMessageContaining("ordering.routing_poll_interval_minutes");

        jdbc.sql(migrationSql()).update();

        assertThat(storedCodes()).containsExactly(LIVE);
        validator.run(new DefaultApplicationArguments());
    }

    @Test
    @DisplayName("the migration is a no-op on a database where nobody saved one of them")
    void theMigrationIsHarmlessWhereNothingWasSaved() throws IOException {
        insertPlatformValue(LIVE, 50_000);

        jdbc.sql(migrationSql()).update();

        assertThat(storedCodes()).containsExactly(LIVE);
    }

    private List<String> storedCodes() {
        return jdbc.sql("SELECT key_code FROM tenant.configuration_values ORDER BY key_code")
                .query(String.class)
                .list();
    }

    private void insertPlatformValue(String code, long value) {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values (id, key_code, scope_type, value_type, integer_value, set_by)
                VALUES (:id, :code, 'PLATFORM', 'INTEGER', :value, 'migration-test')
                """)
                .param("id", UUID.randomUUID())
                .param("code", code)
                .param("value", value)
                .update();
    }

    private static String migrationSql() throws IOException {
        var resource = new ClassPathResource(
                "db/migration/V0653__the_five_order_policy_scalars_nothing_read_leave_the_registry.sql");
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
