package uz.horecaos.platform.tenancy.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * ADR 0029's guard on provisional retention (ADR 0183, row {@code 7.6c}), against the real resolver and a real database so that
 * «confirmed» means what it means in production: a value written at platform scope.
 *
 * <p>Two keys are used. The real {@code reporting.funnel_retention_days} proves the flag is on the key the record names and
 * that the check reports and never refuses it. A synthetic key marked as governing personal data proves the refusal, because
 * no real key is that yet and a guard that has never been seen to refuse proves nothing.
 */
class ProvisionalRetentionStartupCheckTests {

    private static final ConfigurationKey<Integer> PERSONAL_DATA_RETENTION = ConfigurationKey.of(
                    "testing.provisional_personal_data_days", Integer.class)
            .defaultValue(730)
            .ownedBy("testing")
            .settableAt(ScopeType.PLATFORM)
            .provisionalRetention(true, "legal counsel (a test)")
            .build();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcConfigurationResolver resolver;

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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values CASCADE").update();
        resolver = new JdbcConfigurationResolver(jdbc);
    }

    private ProvisionalRetentionStartupCheck check(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new ProvisionalRetentionStartupCheck(
                resolver,
                environment,
                new SimpleMeterRegistry(),
                List.of(ConfigurationKeys.REPORTING_FUNNEL_RETENTION_DAYS, PERSONAL_DATA_RETENTION));
    }

    private ProvisionalRetentionStartupCheck realRegistry(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new ProvisionalRetentionStartupCheck(resolver, environment, new SimpleMeterRegistry());
    }

    private void confirmAtPlatform(ConfigurationKey<Integer> key, int days) {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values (id, key_code, scope_type, value_type, integer_value, set_by)
                VALUES (:id, :code, 'PLATFORM', 'INTEGER', :days, 'a-test')
                """)
                .param("id", UUID.randomUUID())
                .param("code", key.code())
                .param("days", days)
                .update();
    }

    @Test
    @DisplayName(
            "the funnel's 400 days is flagged provisional, awaiting finance and counsel, and governs no personal data")
    void theFunnelRetentionIsFlagged() {
        ConfigurationKey.ProvisionalRetention flag = java.util.Objects.requireNonNull(
                ConfigurationKeys.REPORTING_FUNNEL_RETENTION_DAYS.provisional(),
                "the funnel key is flagged provisional");

        assertThat(flag.governsPersonalData())
                .as("the rows hold counts and no person")
                .isFalse();
        assertThat(flag.awaitingConfirmationFrom()).contains("counsel");
    }

    @Test
    @DisplayName("on a production profile it reports the funnel's retention and still starts: counts are not a person")
    void itReportsButDoesNotRefuseAKeyThatHoldsNoPersonalData() {
        ProvisionalRetentionStartupCheck guard = realRegistry("production");

        assertThat(guard.findings())
                .extracting(ProvisionalRetentionStartupCheck.Finding::keyCode)
                .contains("reporting.funnel_retention_days");
        assertThatCode(() -> guard.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("on a production profile it refuses to start over a provisional period that governs personal data")
    void itRefusesAProvisionalPeriodThatGovernsPersonalData() {
        assertThatThrownBy(() -> check("production").run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("testing.provisional_personal_data_days")
                .hasMessageContaining("ADR 0029")
                .as("the funnel's key is reported, not named as a reason to refuse")
                .satisfies(
                        failure -> assertThat(failure.getMessage()).doesNotContain("reporting.funnel_retention_days"));
    }

    @Test
    @DisplayName("on a local profile it does nothing, whatever is provisional")
    void itIsSilentLocally() {
        assertThatCode(() -> check("local").run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
        assertThatCode(() -> check().run(new DefaultApplicationArguments())).doesNotThrowAnyException();
        assertThatCode(() -> check("test").run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
            "writing the value at platform scope is the confirmation: the key stops being provisional and the start goes through")
    void confirmingAtPlatformScopeClearsIt() {
        confirmAtPlatform(PERSONAL_DATA_RETENTION, 730);

        ProvisionalRetentionStartupCheck guard = check("production");

        assertThat(guard.findings())
                .extracting(ProvisionalRetentionStartupCheck.Finding::keyCode)
                .containsExactly("reporting.funnel_retention_days");
        assertThatCode(() -> guard.run(new DefaultApplicationArguments())).doesNotThrowAnyException();

        confirmAtPlatform(ConfigurationKeys.REPORTING_FUNNEL_RETENTION_DAYS, 400);
        assertThat(guard.findings()).isEmpty();
    }
}
