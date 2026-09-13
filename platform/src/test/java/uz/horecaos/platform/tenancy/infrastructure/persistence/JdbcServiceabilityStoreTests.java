package uz.horecaos.platform.tenancy.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.BrandId;
import uz.horecaos.platform.tenancy.api.LocationId;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.domain.Brand;
import uz.horecaos.platform.tenancy.domain.Location;
import uz.horecaos.platform.tenancy.domain.Slug;
import uz.horecaos.platform.tenancy.domain.Tenant;
import uz.horecaos.platform.tenancy.domain.channel.ServiceMode;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcServiceabilityStore.ServiceState;

/**
 * The batch read {@code serviceStatesForBrand} adds (Settings 10.2a): before
 * this wave the branch list read {@link JdbcServiceabilityStore#serviceState}
 * one location at a time, the N+1 {@code locations-page.ts}'s own comment
 * named. One join must answer for every location of a brand, including one
 * that has never had its state changed.
 */
class JdbcServiceabilityStoreTests {

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcServiceabilityStore store;
    private JdbcTenantControlPlaneStore tenancyStore;

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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        store = new JdbcServiceabilityStore(jdbc);
        tenancyStore = new JdbcTenantControlPlaneStore(jdbc);
    }

    @Test
    void batchesEveryLocationsOwnStateInOneRead() {
        TenantId tenantId = new TenantId(UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120600"));
        tenancyStore.insertTenant(Tenant.provision(
                tenantId,
                new Slug("tenant-states"),
                "Tenant LLC",
                "Tenant",
                Currency.getInstance("UZS"),
                ZoneId.of("Asia/Tashkent")));
        BrandId brandId = new BrandId(UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120601"));
        Brand brand = Brand.draft(brandId, tenantId, "BRAND_S", new Slug("brand-s"), "Brand S");
        tenancyStore.insertBrand(brand);

        LocationId untouched = new LocationId(UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120602"));
        tenancyStore.insertLocation(Location.draft(
                untouched,
                tenantId,
                brandId,
                "LOC_UNTOUCHED",
                new Slug("loc-untouched"),
                "Untouched",
                ZoneId.of("Asia/Tashkent")));

        LocationId closed = new LocationId(UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120603"));
        tenancyStore.insertLocation(Location.draft(
                closed, tenantId, brandId, "LOC_CLOSED", new Slug("loc-closed"), "Closed", ZoneId.of("Asia/Tashkent")));
        store.upsertServiceState(
                tenantId.value(),
                brandId.value(),
                closed.value(),
                ServiceMode.FORCE_CLOSED,
                "fryer_broken",
                null,
                null,
                null,
                Instant.parse("2026-09-13T08:00:00Z"));

        Map<UUID, ServiceState> states = store.serviceStatesForBrand(tenantId.value(), brandId.value());

        assertThat(states).hasSize(2);
        assertThat(states.get(untouched.value()))
                .as("a location that has never had a manual override still answers FOLLOW_SCHEDULE, "
                        + "not a missing entry")
                .isEqualTo(ServiceState.followingSchedule());
        ServiceState closedState = java.util.Objects.requireNonNull(states.get(closed.value()));
        assertThat(closedState.mode()).isEqualTo(ServiceMode.FORCE_CLOSED);
        assertThat(closedState.reasonCode()).isEqualTo("fryer_broken");
    }
}
