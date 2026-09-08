package uz.horecaos.platform.commercial.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.commercial.api.UsageMeter;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.BrandCreated;
import uz.horecaos.platform.tenancy.api.BrandId;
import uz.horecaos.platform.tenancy.api.LocationCreated;
import uz.horecaos.platform.tenancy.api.LocationId;
import uz.horecaos.platform.tenancy.api.TenantCreated;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * {@link TenancyUsageMeterTrigger} is the caller {@link UsageMeter} was missing
 * for {@code brands.max_count} and {@code locations.max_count} (ADR 0021): this
 * proves a real {@link BrandCreated}/{@link LocationCreated} fact, shaped the
 * way {@code TenantControlPlaneService} actually publishes it, turns into a
 * usage movement — and that a redelivery of the same fact does not turn into a
 * second one.
 */
class TenancyUsageMeterTriggerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-07T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private TenancyUsageMeterTrigger trigger;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this integration test");
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
        jdbc.sql("TRUNCATE TABLE commercial.usage_aggregates, commercial.usage_events")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'trigger-tenant', 'Trigger tenant', 'Trigger tenant', 'UZS',
                    'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();

        UsageMeter usage =
                CommercialDefaults.wire(jdbc, Clock.fixed(NOW, ZoneOffset.UTC)).usage();
        trigger = new TenancyUsageMeterTrigger(usage);
    }

    @Test
    @DisplayName("a brand created fact meters one against brands.max_count")
    void aBrandCreatedFactMetersOne() {
        UUID brandId = UUID.randomUUID();
        trigger.onTenancyEvent(brandCreated(brandId));

        assertThat(consumed("brands.max_count")).isEqualTo(1);
    }

    @Test
    @DisplayName("a location created fact meters one against locations.max_count")
    void aLocationCreatedFactMetersOne() {
        UUID locationId = UUID.randomUUID();
        trigger.onTenancyEvent(locationCreated(locationId));

        assertThat(consumed("locations.max_count")).isEqualTo(1);
    }

    @Test
    @DisplayName("a redelivered fact does not meter twice")
    void aRedeliveredFactDoesNotDoubleCount() {
        LocationCreated created = locationCreated(UUID.randomUUID());

        trigger.onTenancyEvent(created);
        trigger.onTenancyEvent(created);

        assertThat(consumed("locations.max_count"))
                .as("at-least-once delivery is the contract every listener works under")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an unrelated tenancy fact is ignored")
    void anUnrelatedFactIsIgnored() {
        trigger.onTenancyEvent(new TenantCreated(
                UUID.randomUUID(),
                new TenantId(TENANT),
                NOW,
                "trigger-tenant",
                "Trigger tenant",
                "Trigger tenant",
                "UZS",
                "Asia/Tashkent",
                "ACTIVE",
                "TENANT_SHARED"));

        assertThat(consumed("brands.max_count")).isZero();
        assertThat(consumed("locations.max_count")).isZero();
    }

    private BrandCreated brandCreated(UUID brandId) {
        return new BrandCreated(
                UUID.randomUUID(), new TenantId(TENANT), new BrandId(brandId), NOW, "MAIN", "main", "Main", "DRAFT");
    }

    private LocationCreated locationCreated(UUID locationId) {
        return new LocationCreated(
                UUID.randomUUID(),
                new TenantId(TENANT),
                new BrandId(BRAND),
                new LocationId(locationId),
                NOW,
                "L1",
                "l1",
                "Location One",
                "Asia/Tashkent",
                "DRAFT");
    }

    private long consumed(String entitlementKey) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(consumed_quantity), 0) FROM commercial.usage_aggregates
                 WHERE tenant_id = :tenantId AND entitlement_key = :key AND period_key = 'LIFETIME'
                """)
                .param("tenantId", TENANT)
                .param("key", entitlementKey)
                .query(Long.class)
                .single();
    }
}
