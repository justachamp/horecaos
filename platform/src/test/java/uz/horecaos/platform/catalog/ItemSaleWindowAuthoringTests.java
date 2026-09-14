package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
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
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.domain.ItemSaleSchedule.Window;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.tenancy.JdbcCatalogTenantContext;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Row 4.2g's binding table and its resolution — V0020 withdrew {@code
 * location_offerings.sales_schedule_id} and nothing ever replaced it, so a
 * breakfast-only item has been stopped and un-stopped by hand every day. Runs
 * against a real database, wired with the real {@link JdbcCatalogTenantContext}
 * rather than a stub: the property this suite exists to prove is that
 * resolution actually crosses into {@code tenant.locations.timezone}, and a
 * stubbed zone would agree with itself about that.
 */
class ItemSaleWindowAuthoringTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final String LOCALE = "uz";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final FiscalClassification UNCLASSIFIED = FiscalClassification.unclassified();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCatalogStore store;
    private CatalogAuthoringService authoring;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the item sale window tests");
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
        jdbc.sql("TRUNCATE TABLE catalog.item_sale_windows, catalog.translations, catalog.variants, "
                        + "catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenancy(TENANT, BRAND, LOCATION, "item-sale-window-tenant", "MAIN");

        store = new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        CommercialDefaults.Wired commercial = CommercialDefaults.wire(jdbc, Clock.systemUTC());
        authoring = new CatalogAuthoringService(
                store,
                new uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder(
                        jdbc, JsonMapper.builder().build()),
                commercial.entitlements(),
                commercial.usage(),
                Clock.systemUTC(),
                new JdbcCatalogTenantContext(jdbc));
    }

    @Test
    @DisplayName("a variant with no windows saved is always on sale — the unchanged default")
    void noWindowsMeansUnrestricted() {
        UUID variantId = createVariant();

        assertThat(authoring.isOnSaleNow(TENANT, LOCATION, variantId, Instant.parse("2026-09-14T02:00:00Z")))
                .isTrue();
    }

    @Test
    @DisplayName("saving a weekly window set replaces it wholesale and the read returns exactly what was saved")
    void windowsRoundTripThroughReplace() {
        UUID variantId = createVariant();
        List<Window> windows = List.of(
                new Window(1, LocalTime.of(6, 0), LocalTime.of(11, 0)),
                new Window(2, LocalTime.of(6, 0), LocalTime.of(11, 0)));

        authoring.replaceItemSaleWindows(TENANT, BRAND, LOCATION, variantId, windows);

        assertThat(authoring.itemSaleWindows(TENANT, LOCATION, variantId)).containsExactlyInAnyOrderElementsOf(windows);
    }

    @Test
    @DisplayName("a second save replaces the first set rather than appending to it")
    void aSecondSaveReplacesRatherThanAppends() {
        UUID variantId = createVariant();
        authoring.replaceItemSaleWindows(
                TENANT, BRAND, LOCATION, variantId, List.of(new Window(1, LocalTime.of(6, 0), LocalTime.of(11, 0))));

        authoring.replaceItemSaleWindows(
                TENANT, BRAND, LOCATION, variantId, List.of(new Window(3, LocalTime.of(18, 0), LocalTime.of(22, 0))));

        assertThat(authoring.itemSaleWindows(TENANT, LOCATION, variantId))
                .containsExactly(new Window(3, LocalTime.of(18, 0), LocalTime.of(22, 0)));
    }

    @Test
    @DisplayName("saving a schedule for a variant this brand does not have is refused")
    void savingForAnUnknownVariantIsRefused() {
        assertThatThrownBy(() -> authoring.replaceItemSaleWindows(
                        TENANT,
                        BRAND,
                        LOCATION,
                        UUID.randomUUID(),
                        List.of(new Window(1, LocalTime.of(6, 0), LocalTime.of(11, 0)))))
                .isInstanceOf(CatalogAuthoringService.UnknownCatalogEntityException.class);
    }

    @Test
    @DisplayName("resolution converts through the location's own timezone, not UTC")
    void resolutionUsesTheLocationsOwnTimezone() {
        // Asia/Tashkent is UTC+5 (no daylight saving). A breakfast window of
        // 06:00-11:00 local time covers 01:00-06:00 UTC — an instant this test
        // deliberately reads as inside the window in local time and outside it
        // if the resolver ever regressed to comparing against UTC directly.
        UUID variantId = createVariant();
        authoring.replaceItemSaleWindows(
                TENANT, BRAND, LOCATION, variantId, List.of(new Window(1, LocalTime.of(6, 0), LocalTime.of(11, 0))));

        // 2026-09-14T02:00:00Z is Monday 07:00 in Asia/Tashkent — inside the
        // window in local time, but 02:00 falls outside 06:00-11:00 if read as
        // a naive UTC clock instead.
        assertThat(authoring.isOnSaleNow(TENANT, LOCATION, variantId, Instant.parse("2026-09-14T02:00:00Z")))
                .isTrue();

        // 2026-09-14T01:00:00Z is Monday 06:00 in Asia/Tashkent — exactly the
        // opening instant, still on sale (half-open at the close, not the open).
        assertThat(authoring.isOnSaleNow(TENANT, LOCATION, variantId, Instant.parse("2026-09-14T01:00:00Z")))
                .isTrue();

        // 2026-09-14T07:00:00Z is Monday 12:00 in Asia/Tashkent — past the
        // window's local close, even though 07:00 alone would still read as
        // "before 11:00" under a naive UTC comparison.
        assertThat(authoring.isOnSaleNow(TENANT, LOCATION, variantId, Instant.parse("2026-09-14T07:00:00Z")))
                .isFalse();
    }

    private UUID createVariant() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy", LOCALE);
        var product = authoring.createProduct(
                TENANT, BRAND, catalogId, "OMLET", "Omlet", null, LOCALE, "SKU-OMLET", "PIECE", UNCLASSIFIED, ACTOR);
        return product.defaultVariantId();
    }

    private void insertTenancy(UUID tenantId, UUID brandId, UUID locationId, String tenantSlug, String brandCode) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone,
                    status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", tenantSlug).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("code", brandCode)
                .param("slug", brandCode.toLowerCase(Locale.ROOT))
                .update();

        jdbc.sql("""
                INSERT INTO tenant.locations (
                    id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', :slug, 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("slug", "main-01-" + locationId)
                .update();
    }
}
