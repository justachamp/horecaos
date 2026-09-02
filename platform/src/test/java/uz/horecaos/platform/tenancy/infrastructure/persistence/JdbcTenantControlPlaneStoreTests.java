package uz.horecaos.platform.tenancy.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.BrandId;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.LocationId;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.domain.Brand;
import uz.horecaos.platform.tenancy.domain.CoordinateSource;
import uz.horecaos.platform.tenancy.domain.CustomerIdentityMode;
import uz.horecaos.platform.tenancy.domain.CustomerIdentityPolicy;
import uz.horecaos.platform.tenancy.domain.Location;
import uz.horecaos.platform.tenancy.domain.LocationPlace;
import uz.horecaos.platform.tenancy.domain.Slug;
import uz.horecaos.platform.tenancy.domain.Tenant;

class JdbcTenantControlPlaneStoreTests {

    /**
     * The instant every "what governs this tenant now" question in this class is
     * asked at. Later than every policy row the fixtures write, so the answer is
     * about the policy history and not about which side of the wall clock the
     * suite happened to run on.
     */
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcTenantControlPlaneStore store;

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
        store = new JdbcTenantControlPlaneStore(jdbc);
    }

    @Test
    void persistsAndReadsTheTenantBrandLocationHierarchy() {
        Tenant tenant = tenant("018f6f4e-899d-7b1c-a8cf-0242ac120200", "tenant-a");
        store.insertTenant(tenant);
        store.insertCustomerIdentityPolicy(CustomerIdentityPolicy.initial(
                UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120201"),
                tenant.id(),
                CustomerIdentityMode.TENANT_SHARED,
                Instant.parse("2026-08-19T00:00:00Z")));

        tenant.linkKeycloakOrganization("keycloak-organization-a");
        store.linkKeycloakOrganization(tenant);

        Brand brand = Brand.draft(
                new BrandId(UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120202")),
                tenant.id(),
                "BRAND_A",
                new Slug("brand-a"),
                "Brand A");
        store.insertBrand(brand);

        Location location = Location.draft(
                new LocationId(UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120203")),
                tenant.id(),
                brand.id(),
                "LOCATION_A",
                new Slug("location-a"),
                "Location A",
                ZoneId.of("Asia/Tashkent"));
        store.insertLocation(location);

        assertThat(store.findTenant(tenant.id()))
                .get()
                .extracting(saved -> saved.keycloakOrganizationId().orElseThrow())
                .isEqualTo("keycloak-organization-a");
        assertThat(store.findTenantBySlug(tenant.slug()))
                .as("idempotent provisioning tooling looks a tenant up by its fixed slug, not a "
                        + "freshly-generated id it does not have yet")
                .get()
                .extracting(Tenant::id)
                .isEqualTo(tenant.id());
        assertThat(store.findTenantBySlug(new Slug("no-such-tenant"))).isEmpty();
        assertThat(store.findCurrentCustomerIdentityMode(tenant.id(), NOW))
                .contains(CustomerIdentityMode.TENANT_SHARED);
        assertThat(store.findBrands(tenant.id()))
                .singleElement()
                .extracting(Brand::id)
                .isEqualTo(brand.id());
        assertThat(store.findLocations(brand))
                .singleElement()
                .satisfies(saved -> assertThat(saved.brandId()).isEqualTo(brand.id()));
    }

    /**
     * The identity mode lived in two places and only one of them was written:
     * the versioned policy the control plane inserts, and a denormalised
     * {@code tenant.tenants.customer_identity_policy} column that customer
     * identity resolution read. A tenant that asked for BRAND_ISOLATED was
     * partitioned TENANT_SHARED and nothing said so.
     *
     * <p>V0060 made the database mirror the versioned table into that column;
     * V0072 dropped it, because "which row is current" moves with the clock and a
     * trigger only fires on a write, so no stored copy can survive a cutover
     * scheduled for a date nobody writes on. The versioned table is now the only
     * place the mode is, and the store reads it through
     * {@code tenant.current_customer_identity_policy}.
     */
    @Test
    void aGovernedModeChangeMovesTheAnswerToTheNewVersion() {
        Tenant tenant = tenant("018f6f4e-899d-7b1c-a8cf-0242ac120280", "tenant-identity-mirror");
        store.insertTenant(tenant);

        // Nothing configured: no row, and the caller applies its own default
        // rather than being handed one the operator never chose.
        assertThat(store.findCurrentCustomerIdentityMode(tenant.id(), NOW)).isEmpty();

        store.insertCustomerIdentityPolicy(CustomerIdentityPolicy.initial(
                UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120281"),
                tenant.id(),
                CustomerIdentityMode.BRAND_ISOLATED,
                Instant.parse("2026-08-19T00:00:00Z")));

        assertThat(store.findCurrentCustomerIdentityMode(tenant.id(), NOW))
                .contains(CustomerIdentityMode.BRAND_ISOLATED);

        // A governed mode change supersedes the current row and inserts the next
        // version, dated for the instant of the change itself. The answer follows
        // it rather than keeping the mode the tenant used to have.
        jdbc.sql("""
                UPDATE tenant.customer_identity_policies
                SET superseded_at = timestamptz '2026-08-20T00:00:00Z'
                WHERE tenant_id = :tenantId AND version = 1
                """).param("tenantId", tenant.id().value()).update();
        store.insertCustomerIdentityPolicy(supersededBy(
                UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120282"),
                tenant.id(),
                CustomerIdentityMode.TENANT_SHARED,
                Instant.parse("2026-08-20T00:00:00Z")));

        assertThat(store.findCurrentCustomerIdentityMode(tenant.id(), NOW))
                .contains(CustomerIdentityMode.TENANT_SHARED);
        // And the tenant row has no opinion of its own to contradict it.
        assertThat(tenantColumns()).doesNotContain("customer_identity_policy");
    }

    /**
     * A governed mode change dated for a future cutover does not govern yet.
     *
     * <p>"Current" used to mean {@code superseded_at IS NULL} and nothing else,
     * so the instant a next-version row was inserted it became the answer —
     * whatever date the operator had set it to take effect. That is the same
     * silent re-partitioning V0060 refused to let a deployment do, arriving
     * instead through a row scheduled for next month.
     */
    @Test
    void aFutureDatedPolicyDoesNotGovernUntilItTakesEffect() {
        Tenant tenant = tenant("018f6f4e-899d-7b1c-a8cf-0242ac120290", "tenant-identity-future");
        store.insertTenant(tenant);
        store.insertCustomerIdentityPolicy(CustomerIdentityPolicy.initial(
                UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120291"),
                tenant.id(),
                CustomerIdentityMode.TENANT_SHARED,
                Instant.parse("2026-08-19T00:00:00Z")));

        Instant cutover = Instant.parse("2026-09-01T00:00:00Z");
        jdbc.sql("""
                UPDATE tenant.customer_identity_policies
                SET superseded_at = :cutover
                WHERE tenant_id = :tenantId AND version = 1
                """)
                .param("cutover", java.time.OffsetDateTime.ofInstant(cutover, java.time.ZoneOffset.UTC))
                .param("tenantId", tenant.id().value())
                .update();
        store.insertCustomerIdentityPolicy(supersededBy(
                UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120292"),
                tenant.id(),
                CustomerIdentityMode.BRAND_ISOLATED,
                cutover));

        // Before the cutover the tenant is still shared, even though the row that
        // will replace it already exists.
        assertThat(store.findCurrentCustomerIdentityMode(tenant.id(), NOW))
                .contains(CustomerIdentityMode.TENANT_SHARED);
        // At the cutover instant itself the new row governs and the old one does
        // not: supersede() closes one and opens the next at the same instant, so
        // an inclusive test on both sides would make them both current.
        assertThat(store.findCurrentCustomerIdentityMode(tenant.id(), cutover))
                .contains(CustomerIdentityMode.BRAND_ISOLATED);
    }

    private CustomerIdentityPolicy supersededBy(
            UUID nextId, TenantId tenantId, CustomerIdentityMode nextMode, Instant changedAt) {
        CustomerIdentityMode previous = nextMode == CustomerIdentityMode.TENANT_SHARED
                ? CustomerIdentityMode.BRAND_ISOLATED
                : CustomerIdentityMode.TENANT_SHARED;
        return CustomerIdentityPolicy.initial(UUID.randomUUID(), tenantId, previous, changedAt)
                .supersede(nextId, nextMode, changedAt, false, false);
    }

    private java.util.List<String> tenantColumns() {
        return jdbc.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'tenant' AND table_name = 'tenants'
                """).query(String.class).list();
    }

    @Test
    void aBranchIsRegisteredWithoutAPointAndPinnedAfterwards() {
        Tenant tenant = tenant("018f6f4e-899d-7b1c-a8cf-0242ac120260", "tenant-place");
        store.insertTenant(tenant);
        Brand brand = Brand.draft(
                new BrandId(UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120261")),
                tenant.id(),
                "BRAND_P",
                new Slug("brand-p"),
                "Brand P");
        store.insertBrand(brand);

        Location location = Location.draft(
                new LocationId(UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120262")),
                tenant.id(),
                brand.id(),
                "LOCATION_P",
                new Slug("location-p"),
                "Chilonzor filiali",
                ZoneId.of("Asia/Tashkent"));
        store.insertLocation(location);

        // Registered from a spreadsheet. Nobody has stood outside it yet, and the
        // gap is stated rather than implied by a scatter of nulls.
        assertThat(store.findLocations(brand)).singleElement().satisfies(saved -> {
            assertThat(saved.place().coordinateSource()).isEqualTo(CoordinateSource.NOT_GEOCODED);
            assertThat(saved.place().isLocatable()).isFalse();
        });

        location.describePlace(new LocationPlace(
                "Chilonzor ko'chasi 9-kvartal, 42-uy",
                "Chilonzor",
                "Toshkent",
                "Metro Chilonzor yonida",
                "+998712000000",
                new GeoPoint(41.275300, 69.204400),
                CoordinateSource.MERCHANT_PIN));
        store.updateLocationPlace(location);

        assertThat(store.findLocations(brand)).singleElement().satisfies(saved -> {
            LocationPlace place = saved.place();
            assertThat(place.addressLine()).isEqualTo("Chilonzor ko'chasi 9-kvartal, 42-uy");
            assertThat(place.district()).isEqualTo("Chilonzor");
            assertThat(place.landmark()).isEqualTo("Metro Chilonzor yonida");
            assertThat(place.contactPhone()).isEqualTo("+998712000000");
            assertThat(place.coordinateSource()).isEqualTo(CoordinateSource.MERCHANT_PIN);
            assertThat(place.point()).hasValueSatisfying(point -> {
                assertThat(point.latitude()).isEqualTo(41.275300);
                assertThat(point.longitude()).isEqualTo(69.204400);
            });
        });
    }

    /**
     * Half a coordinate is unroutable, and a latitude on its own points at the
     * equator — where a distance check would treat it as a real place. The domain
     * refuses the pair, and so does the schema, because these columns are also
     * reachable by a migration and by hand.
     */
    @Test
    void databaseRejectsHalfACoordinateAndASourceThatDisagreesWithIt() {
        Tenant tenant = tenant("018f6f4e-899d-7b1c-a8cf-0242ac120270", "tenant-coords");
        store.insertTenant(tenant);
        Brand brand = Brand.draft(
                new BrandId(UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120271")),
                tenant.id(),
                "BRAND_C",
                new Slug("brand-c"),
                "Brand C");
        store.insertBrand(brand);
        UUID locationId = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120272");
        store.insertLocation(Location.draft(
                new LocationId(locationId),
                tenant.id(),
                brand.id(),
                "LOCATION_C",
                new Slug("location-c"),
                "Location C",
                ZoneId.of("Asia/Tashkent")));

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE tenant.locations SET latitude = 41.3, coordinate_source = 'MERCHANT_PIN'
                WHERE id = :id
                """).param("id", locationId).update())
                .hasMessageContaining("ck_locations_coordinates");

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE tenant.locations SET coordinate_source = 'GEOCODER' WHERE id = :id
                """).param("id", locationId).update())
                .hasMessageContaining("ck_locations_coordinate_source_agrees");

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE tenant.locations SET contact_phone = '71 200 00 00' WHERE id = :id
                """).param("id", locationId).update())
                .hasMessageContaining("ck_locations_contact_phone");
    }

    @Test
    void databaseRejectsALocationWhoseTenantAndBrandAncestryDoNotMatch() {
        Tenant firstTenant = tenant("018f6f4e-899d-7b1c-a8cf-0242ac120210", "tenant-a");
        Tenant secondTenant = tenant("018f6f4e-899d-7b1c-a8cf-0242ac120211", "tenant-b");
        store.insertTenant(firstTenant);
        store.insertTenant(secondTenant);
        Brand brand = Brand.draft(
                new BrandId(UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120212")),
                firstTenant.id(),
                "BRAND_A",
                new Slug("brand-a"),
                "Brand A");
        store.insertBrand(brand);

        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO tenant.locations (
                            id, tenant_id, brand_id, code, slug, display_name, timezone, status
                        ) VALUES (
                            :id, :tenantId, :brandId, 'WRONG_SCOPE', 'wrong-scope',
                            'Wrong Scope', 'Asia/Tashkent', 'DRAFT'
                        )
                        """)
                        .param("id", UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120213"))
                        .param("tenantId", secondTenant.id().value())
                        .param("brandId", brand.id().value())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsBrandAndLocationActivation() {
        Tenant tenant = tenant("018f6f4e-899d-7b1c-a8cf-0242ac120290", "tenant-activation");
        store.insertTenant(tenant);

        Brand brand = Brand.draft(
                new BrandId(UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120291")),
                tenant.id(),
                "BRAND_ACT",
                new Slug("brand-act"),
                "Brand Activation");
        store.insertBrand(brand);

        Location location = Location.draft(
                new LocationId(UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120292")),
                tenant.id(),
                brand.id(),
                "LOC_ACT",
                new Slug("loc-act"),
                "Location Activation",
                ZoneId.of("Asia/Tashkent"));
        store.insertLocation(location);

        assertThat(store.findBrand(tenant.id(), brand.id()))
                .get()
                .extracting(Brand::status)
                .isEqualTo(uz.horecaos.platform.tenancy.domain.OperatingUnitStatus.DRAFT);
        assertThat(store.findLocations(brand))
                .singleElement()
                .extracting(Location::status)
                .isEqualTo(uz.horecaos.platform.tenancy.domain.OperatingUnitStatus.DRAFT);

        brand.activate();
        store.updateBrandStatus(brand);
        location.activate();
        store.updateLocationStatus(location);

        assertThat(store.findBrand(tenant.id(), brand.id()))
                .as("pickup-location discovery and other reads require ACTIVE explicitly, so the "
                        + "write must actually reach the row, not just the in-memory aggregate")
                .get()
                .extracting(Brand::status)
                .isEqualTo(uz.horecaos.platform.tenancy.domain.OperatingUnitStatus.ACTIVE);
        assertThat(store.findLocations(brand))
                .singleElement()
                .extracting(Location::status)
                .isEqualTo(uz.horecaos.platform.tenancy.domain.OperatingUnitStatus.ACTIVE);
    }

    @Test
    void listsTenantsInAStableKeysetOrderAndPaginates() {
        Tenant first = tenant("018f6f4e-899d-7b1c-a8cf-0242ac120210", "list-a");
        Tenant second = tenant("018f6f4e-899d-7b1c-a8cf-0242ac120211", "list-b");
        Tenant third = tenant("018f6f4e-899d-7b1c-a8cf-0242ac120212", "list-c");
        store.insertTenant(first);
        store.insertTenant(second);
        store.insertTenant(third);

        var firstPage = store.listTenants(null, 2);
        assertThat(firstPage)
                .as("id is a random UUID, not a time-ordered one, so this is a stable page order — "
                        + "the sort itself, not the insert order")
                .hasSize(2);

        var secondPage = store.listTenants(firstPage.getLast().id(), 2);
        assertThat(secondPage).hasSize(1);
        assertThat(java.util.stream.Stream.concat(firstPage.stream(), secondPage.stream())
                        .map(row -> row.id().value()))
                .as("the two pages together cover every tenant exactly once")
                .containsExactlyInAnyOrder(
                        first.id().value(), second.id().value(), third.id().value());

        var row = secondPage.getFirst();
        assertThat(row.legalName()).isEqualTo("Tenant LLC");
        assertThat(row.defaultCurrency()).isEqualTo("UZS");
        assertThat(row.status()).isEqualTo(uz.horecaos.platform.tenancy.domain.TenantStatus.PROVISIONING);
        assertThat(row.createdAt()).isNotNull();
    }

    private static Tenant tenant(String id, String slug) {
        return Tenant.provision(
                new TenantId(UUID.fromString(id)),
                new Slug(slug),
                "Tenant LLC",
                "Tenant",
                Currency.getInstance("UZS"),
                ZoneId.of("Asia/Tashkent"));
    }
}
