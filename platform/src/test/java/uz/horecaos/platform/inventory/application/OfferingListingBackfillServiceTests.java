package uz.horecaos.platform.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.Set;
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
import uz.horecaos.platform.catalog.application.UnlistedOfferingsPortAdapter;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.OfferingListingBackfillService.LocationBackfillResult;
import uz.horecaos.platform.inventory.application.OfferingListingBackfillService.VariantBackfillResult;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Gap map row 4.1's own two backfill directions, wired against real catalog
 * and inventory stores exactly as they run in production: {@link
 * UnlistedOfferingsPortAdapter} reading across the module boundary in SQL,
 * {@link StockListingPortAdapter} listing through {@code InventoryService}'s
 * real idempotent {@code ensureListed}.
 *
 * <p>Deliberately never calls {@code CatalogAuthoringService.setOffering} —
 * the one write path that also publishes {@code OfferingBecameAvailable} and
 * lists automatically (see {@code CatalogOfferingAutoListingIntegrationTests}).
 * Fixtures here go straight to {@code JdbcCatalogStore.upsertOffering}, the
 * pre-wave shape every pre-existing tenant's own offerings are already in, so
 * this suite proves the backfill catches exactly what the event cannot.
 */
class OfferingListingBackfillServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION_A = UUID.randomUUID();
    private static final UUID LOCATION_B = UUID.randomUUID();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCatalogStore catalogStore;
    private CatalogAuthoringService authoring;
    private OfferingListingBackfillService backfill;
    private InventoryService inventory;

    private UUID cachedCatalogId;
    private UUID variantA;
    private UUID variantB;

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
        jdbc.sql("TRUNCATE TABLE inventory.reservation_lines, inventory.reservations, "
                        + "inventory.movements, inventory.positions, inventory.stock_items CASCADE")
                .update();
        jdbc.sql(
                        "TRUNCATE TABLE catalog.location_offerings, catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.locations, tenant.tenants CASCADE").update();

        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'offering-backfill-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        insertLocation(LOCATION_A, "CHI", "chilonzor");
        insertLocation(LOCATION_B, "YUN", "yunusabad");

        catalogStore = new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        CommercialDefaults.Wired commercial = CommercialDefaults.wire(jdbc, Clock.systemUTC());
        authoring = new CatalogAuthoringService(
                catalogStore, fact -> {}, commercial.entitlements(), commercial.usage(), Clock.systemUTC());
        cachedCatalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", "ru");
        variantA = createVariant("BURGER-A", "SKU-A");
        variantB = createVariant("BURGER-B", "SKU-B");

        inventory = new InventoryService(new JdbcInventoryStore(jdbc), event -> {}, Clock.systemUTC());
        StockListingPortAdapter stockListing = new StockListingPortAdapter(inventory);
        UnlistedOfferingsPortAdapter unlisted = new UnlistedOfferingsPortAdapter(catalogStore);
        backfill = new OfferingListingBackfillService(unlisted, stockListing);
    }

    @Test
    @DisplayName("backfillLocation lists every AVAILABLE-offered, unlisted variant and is idempotent")
    void backfillLocationListsUnlistedVariantsAndIsIdempotent() {
        // Pre-wave-shaped offerings: written straight to the store, no event.
        catalogStore.upsertOffering(TENANT, BRAND, LOCATION_A, variantA, OfferingStatus.AVAILABLE, "PICKUP");
        catalogStore.upsertOffering(TENANT, BRAND, LOCATION_A, variantB, OfferingStatus.AVAILABLE, "PICKUP");
        // A HIDDEN offering must never be listed by the backfill.
        UUID hiddenVariant = createVariant("HIDDEN-ITEM", "SKU-HIDDEN");
        catalogStore.upsertOffering(TENANT, BRAND, LOCATION_A, hiddenVariant, OfferingStatus.HIDDEN, "PICKUP");

        assertThat(inventory
                        .checkAvailability(TENANT, LOCATION_A, Set.of(variantA, variantB))
                        .available())
                .as("nothing has listed either variant yet")
                .isFalse();

        LocationBackfillResult first = backfill.backfillLocation(TENANT, BRAND, LOCATION_A);
        assertThat(first.candidateCount()).isEqualTo(2);
        assertThat(first.listedCount()).isEqualTo(2);
        assertThat(first.mayHaveMore()).isFalse();

        assertThat(inventory
                        .checkAvailability(TENANT, LOCATION_A, Set.of(variantA, variantB))
                        .available())
                .as("both variants are now listed and orderable")
                .isTrue();
        assertThat(inventory
                        .checkAvailability(TENANT, LOCATION_A, Set.of(hiddenVariant))
                        .available())
                .as("a HIDDEN offering is never a backfill candidate")
                .isFalse();

        LocationBackfillResult second = backfill.backfillLocation(TENANT, BRAND, LOCATION_A);
        assertThat(second.candidateCount())
                .as("nothing is left unlisted, so the second call finds no candidates")
                .isZero();
        assertThat(second.listedCount()).isZero();
    }

    @Test
    @DisplayName("backfillLocation never overwrites a deliberately sold-out BINARY item")
    void backfillLocationNeverOverwritesADeliberateSoldOut() {
        catalogStore.upsertOffering(TENANT, BRAND, LOCATION_A, variantA, OfferingStatus.AVAILABLE, "PICKUP");
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION_A, variantA, TrackingMode.BINARY);
        inventory.setAvailability(TENANT, LOCATION_A, variantA, false, "OUT_OF_STOCK", null);

        LocationBackfillResult result = backfill.backfillLocation(TENANT, BRAND, LOCATION_A);

        assertThat(result.candidateCount())
                .as("an already-listed variant, sold out or not, is not an unlisted candidate at all")
                .isZero();
        assertThat(inventory
                        .checkAvailability(TENANT, LOCATION_A, Set.of(variantA))
                        .available())
                .isFalse();
    }

    @Test
    @DisplayName("backfillVariant lists every unlisted branch of one variant and is idempotent")
    void backfillVariantListsUnlistedBranchesAndIsIdempotent() {
        catalogStore.upsertOffering(TENANT, BRAND, LOCATION_A, variantA, OfferingStatus.AVAILABLE, "PICKUP");
        catalogStore.upsertOffering(TENANT, BRAND, LOCATION_B, variantA, OfferingStatus.AVAILABLE, "PICKUP");
        // variantB is only offered at LOCATION_B and must not be touched.
        catalogStore.upsertOffering(TENANT, BRAND, LOCATION_B, variantB, OfferingStatus.AVAILABLE, "PICKUP");

        assertThat(backfill.unlistedLocationsForVariant(TENANT, BRAND, variantA))
                .containsExactlyInAnyOrder(LOCATION_A, LOCATION_B);

        VariantBackfillResult first = backfill.backfillVariant(TENANT, BRAND, variantA);
        assertThat(first.candidateCount()).isEqualTo(2);
        assertThat(first.listedCount()).isEqualTo(2);

        assertThat(backfill.unlistedLocationsForVariant(TENANT, BRAND, variantA))
                .as("both branches are now listed")
                .isEmpty();
        assertThat(backfill.unlistedLocationsForVariant(TENANT, BRAND, variantB))
                .as("variantB's own unlisted branch is untouched by variantA's backfill")
                .containsExactly(LOCATION_B);

        VariantBackfillResult second = backfill.backfillVariant(TENANT, BRAND, variantA);
        assertThat(second.candidateCount()).isZero();
        assertThat(second.listedCount()).isZero();
    }

    private UUID createVariant(String productCode, String sku) {
        var product = authoring.createProduct(
                TENANT,
                BRAND,
                cachedCatalogId,
                productCode,
                productCode,
                null,
                "ru",
                sku,
                "PIECE",
                FiscalClassification.unclassified(),
                null);
        return product.defaultVariantId();
    }

    private void insertLocation(UUID locationId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, :slug, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", code)
                .param("slug", slug)
                .update();
    }
}
