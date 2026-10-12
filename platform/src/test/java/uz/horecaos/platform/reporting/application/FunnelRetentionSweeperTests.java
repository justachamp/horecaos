package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAttributionClickSeries;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAttributionLinkStore;
import uz.horecaos.platform.reporting.api.ReportingConfigurationKeys;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcFunnelStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

/**
 * ADR 0167 Decision 7: the funnel, registration and link-click count rows are kept 400 days by business date,
 * and the job that deletes them starts report-only like every destructive retention job (ADR 0029).
 */
class FunnelRetentionSweeperTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-2000-7000-8000-0000000f6801");
    private static final UUID BRAND = UUID.fromString("018f6f4e-2000-7000-8000-0000000f6802");
    private static final UUID LINK = UUID.fromString("018f6f4e-2000-7000-8000-0000000f6803");

    /** "Today" for the sweep: 2027-10-10, so the 400-day cutoff is 2026-09-05. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-10-10T09:00:00Z"), ZoneOffset.UTC);

    private static final LocalDate CUTOFF = LocalDate.of(2026, 9, 5);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcFunnelStore funnel;
    private JdbcAttributionClickSeries clicks;
    private ConfigurationResolver configuration;

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

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE reporting.fact_funnel_day, reporting.fact_registration_day")
                .update();
        jdbc.sql("TRUNCATE TABLE marketing.attribution_links CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'funnel-retention', 'Legal', 'Retention', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO marketing.attribution_links
                    (id, tenant_id, brand_id, label, token, channel, destination_type, status, valid_from, created_by)
                VALUES (:id, :t, :b, 'Old link', 'oldLink001', 'WEB', 'INFLUENCER', 'ACTIVE',
                        now() - interval '900 days', :by)
                """)
                .param("id", LINK)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("by", UUID.randomUUID())
                .update();

        // One row just past the cutoff and one on it, in each of the three tables.
        for (LocalDate day : new LocalDate[] {CUTOFF.minusDays(1), CUTOFF}) {
            jdbc.sql("""
                    INSERT INTO reporting.fact_funnel_day
                        (tenant_id, brand_id, business_date, channel_code, basket_started, checkout_started,
                         ordered, boundary_version, computed_at)
                    VALUES (:t, :b, :d, 'WEB', 1, 1, 1, 1, now())
                    """).param("t", TENANT).param("b", BRAND).param("d", day).update();
            jdbc.sql("""
                    INSERT INTO reporting.fact_registration_day
                        (tenant_id, brand_id, business_date, origin, registered, boundary_version, computed_at)
                    VALUES (:t, :b, :d, 'SELF_SERVICE', 1, 1, now())
                    """).param("t", TENANT).param("b", BRAND).param("d", day).update();
            jdbc.sql("""
                    INSERT INTO marketing.attribution_link_clicks_day (tenant_id, link_id, day, clicks)
                    VALUES (:t, :l, :d, 5)
                    """).param("t", TENANT).param("l", LINK).param("d", day).update();
        }

        funnel = new JdbcFunnelStore(jdbc);
        clicks = new JdbcAttributionClickSeries(new JdbcAttributionLinkStore(jdbc));
        configuration = mock(ConfigurationResolver.class);
        when(configuration.value(eq(ReportingConfigurationKeys.FUNNEL_RETENTION_DAYS), any()))
                .thenReturn(400);
    }

    @Test
    @DisplayName("report-only, the default, counts what would be deleted and deletes nothing")
    void reportOnlyDeletesNothing() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        FunnelRetentionSweeper sweeper = new FunnelRetentionSweeper(
                funnel, clicks, configuration, CLOCK, meters, FunnelRetentionSweeper.Mode.REPORT_ONLY);

        long reported = sweeper.runOnce();

        assertThat(reported)
                .as("one past-cutoff row in each of the three tables")
                .isEqualTo(3);
        assertThat(rows("reporting.fact_funnel_day")).isEqualTo(2);
        assertThat(rows("reporting.fact_registration_day")).isEqualTo(2);
        assertThat(rows("marketing.attribution_link_clicks_day")).isEqualTo(2);
        assertThat(meters.get("horecaos.reporting.funnel.retention_due").gauge().value())
                .as("the gauge says how many rows are waiting on a person to enable the job")
                .isEqualTo(3.0);
    }

    @Test
    @DisplayName("enforcing deletes rows older than the cutoff by business date and keeps the one on it")
    void enforcingDeletesOnlyPastTheCutoff() {
        FunnelRetentionSweeper sweeper = new FunnelRetentionSweeper(
                funnel, clicks, configuration, CLOCK, new SimpleMeterRegistry(), FunnelRetentionSweeper.Mode.ENFORCE);

        long deleted = sweeper.runOnce();

        assertThat(deleted).isEqualTo(3);
        for (String table : new String[] {
            "reporting.fact_funnel_day", "reporting.fact_registration_day", "marketing.attribution_link_clicks_day"
        }) {
            assertThat(rows(table)).as(table).isEqualTo(1);
        }
        assertThat(jdbc.sql("SELECT min(business_date) FROM reporting.fact_funnel_day")
                        .query(LocalDate.class)
                        .single())
                .as("the row on the cutoff date is 400 days old, not older, so it stays")
                .isEqualTo(CUTOFF);
        assertThat(sweeper.runOnce()).as("nothing left to do").isZero();
    }

    @Test
    @DisplayName("a longer platform retention keeps the older rows")
    void theConfiguredPeriodGoverns() {
        when(configuration.value(eq(ReportingConfigurationKeys.FUNNEL_RETENTION_DAYS), any()))
                .thenReturn(800);
        FunnelRetentionSweeper sweeper = new FunnelRetentionSweeper(
                funnel, clicks, configuration, CLOCK, new SimpleMeterRegistry(), FunnelRetentionSweeper.Mode.ENFORCE);

        assertThat(sweeper.runOnce()).isZero();
        assertThat(rows("reporting.fact_funnel_day")).isEqualTo(2);
    }

    private long rows(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
