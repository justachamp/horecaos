package uz.horecaos.platform.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryBulkAvailabilityService;
import uz.horecaos.platform.inventory.application.InventoryBulkAvailabilityService.ItemOutcome;
import uz.horecaos.platform.inventory.application.InventoryBulkAvailabilityService.ItemStatus;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@link InventoryBulkAvailabilityService} — gap map row 2.5. Proves the
 * ADR 0039-modelled contract the stop list's batch endpoint promises: every
 * item applied independently and reported with its own outcome, one
 * unstocked variant among many never failing its siblings, and the 200-item
 * cap.
 */
class InventoryBulkAvailabilityServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final String LOCALE = "en";
    private static final UUID CREATE_ACTOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-14T09:00:00Z");
    private static final String ACTOR = UUID.randomUUID().toString();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private InventoryService inventory;
    private InventoryBulkAvailabilityService bulk;
    private CatalogAuthoringService authoring;
    private UUID catalogId;

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
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.location_offerings, catalog.translations, "
                        + "catalog.category_products, catalog.categories, catalog.catalog_products, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE inventory.reservation_lines, inventory.reservations, "
                        + "inventory.movements, inventory.positions, inventory.stock_items CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenancy();

        JdbcCatalogStore catalogStore =
                new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        CommercialDefaults.Wired commercial = CommercialDefaults.wire(jdbc, Clock.systemUTC());
        authoring = new CatalogAuthoringService(
                catalogStore,
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                commercial.entitlements(),
                commercial.usage(),
                Clock.systemUTC());

        JdbcInventoryStore inventoryStore = new JdbcInventoryStore(jdbc);
        inventory = new InventoryService(
                inventoryStore,
                event -> {},
                Clock.fixed(NOW, ZoneOffset.UTC),
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()));
        bulk = new InventoryBulkAvailabilityService(inventory);

        catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
    }

    @Test
    void everyVariantIsAppliedIndependently() {
        UUID a = stockedVariant("PLOV");
        UUID b = stockedVariant("LAGMAN");

        List<ItemOutcome> outcomes = bulk.apply(TENANT, LOCATION, List.of(a, b), false, "STOP_LIST_BULK", ACTOR);

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).allSatisfy(outcome -> {
            assertThat(outcome.status()).isEqualTo(ItemStatus.APPLIED);
            assertThat(outcome.changed()).isTrue();
        });
    }

    @Test
    void oneUnstockedVariantFailsWithoutStoppingItsSiblings() {
        UUID stocked = stockedVariant("OSH");
        UUID neverListed = UUID.randomUUID(); // no stock item at this location

        List<ItemOutcome> outcomes =
                bulk.apply(TENANT, LOCATION, List.of(stocked, neverListed), false, "STOP_LIST_BULK", ACTOR);

        assertThat(outcomes).hasSize(2);
        ItemOutcome stockedOutcome = outcomes.stream()
                .filter(o -> o.variantId().equals(stocked))
                .findFirst()
                .orElseThrow();
        ItemOutcome failedOutcome = outcomes.stream()
                .filter(o -> o.variantId().equals(neverListed))
                .findFirst()
                .orElseThrow();

        assertThat(stockedOutcome.status()).isEqualTo(ItemStatus.APPLIED);
        assertThat(failedOutcome.status()).isEqualTo(ItemStatus.FAILED);
        assertThat(failedOutcome.problemCode()).isEqualTo("VARIANT_NOT_STOCKED");
    }

    @Test
    void aVariantAlreadyInTheTargetStateAppliesAsANoOp() {
        UUID variant = stockedVariant("SHURPA");
        // A freshly stocked BINARY item starts available
        // (JdbcInventoryStore#createStockItem's own doc: "starting unavailable
        // would silently hide every newly listed dish"), so the first bulk
        // stop genuinely flips it and the second is the no-op under test.
        bulk.apply(TENANT, LOCATION, List.of(variant), false, "STOP_LIST_BULK", ACTOR);

        List<ItemOutcome> outcomes = bulk.apply(TENANT, LOCATION, List.of(variant), false, "STOP_LIST_BULK", ACTOR);

        assertThat(outcomes).singleElement().satisfies(outcome -> {
            assertThat(outcome.status()).isEqualTo(ItemStatus.APPLIED);
            assertThat(outcome.changed()).isFalse();
        });
    }

    @Test
    void aRequestOverTheCapIsRefused() {
        List<UUID> tooMany = Stream.generate(UUID::randomUUID)
                .limit(InventoryBulkAvailabilityService.MAX_ITEMS + 1)
                .toList();

        assertThatThrownBy(() -> bulk.apply(TENANT, LOCATION, tooMany, false, "STOP_LIST_BULK", ACTOR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactlyTheCapIsAccepted() {
        List<UUID> variants = new ArrayList<>();
        for (int i = 0; i < InventoryBulkAvailabilityService.MAX_ITEMS; i++) {
            variants.add(stockedVariant("ITEM" + i));
        }

        List<ItemOutcome> outcomes = bulk.apply(TENANT, LOCATION, variants, false, "STOP_LIST_BULK", ACTOR);

        assertThat(outcomes).hasSize(InventoryBulkAvailabilityService.MAX_ITEMS);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.status()).isEqualTo(ItemStatus.APPLIED));
    }

    private UUID stockedVariant(String code) {
        var product = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                code,
                code,
                null,
                LOCALE,
                "SKU-" + code,
                "PIECE",
                FiscalClassification.unclassified(),
                CREATE_ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, product.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DINE_IN"));
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, product.defaultVariantId(), TrackingMode.BINARY);
        return product.defaultVariantId();
    }

    private void insertTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'inventory-bulk-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'inventory-bulk-brand', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'inventory-bulk-location', 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }
}
