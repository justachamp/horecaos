package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
import uz.horecaos.platform.catalog.application.CatalogItemDisplayLookup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.inventory.api.ItemAvailabilityChanged;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;

/**
 * catalog.md §4.6, the read side: one location's sellable variants joined with
 * current availability — the read {@code PUT
 * .../inventory/variants/{variantId}/availability} has never had.
 *
 * <p>Runs against a real database, like {@link CatalogPublicationTests}: the
 * property under test is a join across the {@code catalog} and {@code
 * inventory} schemas, and a stubbed store would agree with itself about
 * whether the join and the tenant scope are actually correct.
 */
class CatalogAvailabilityReadModelTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();

    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();
    private static final UUID OTHER_LOCATION = UUID.randomUUID();

    private static final String LOCALE = "uz";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final FiscalClassification UNCLASSIFIED = FiscalClassification.unclassified();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCatalogStore store;
    private CatalogAuthoringService authoring;
    private InventoryService inventory;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the catalog availability read model tests");
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
        jdbc.sql("TRUNCATE TABLE catalog.location_offerings, catalog.translations, "
                        + "catalog.category_products, catalog.categories, catalog.catalog_products, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE inventory.reservation_lines, inventory.reservations, "
                        + "inventory.movements, inventory.positions, inventory.stock_items CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        insertTenancy(TENANT, BRAND, LOCATION, "read-model-tenant", "MAIN");
        insertTenancy(OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, "read-model-other-tenant", "OTHER-MAIN");

        store = new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        CommercialDefaults.Wired commercial = CommercialDefaults.wire(jdbc, Clock.systemUTC());
        authoring = new CatalogAuthoringService(
                store,
                new uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder(
                        jdbc, JsonMapper.builder().build()),
                commercial.entitlements(),
                commercial.usage(),
                Clock.systemUTC());
        inventory = new InventoryService(
                new JdbcInventoryStore(jdbc),
                event -> {},
                Clock.fixed(Instant.parse("2026-08-30T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a sellable variant with a category and current availability appears")
    void aSellableVariantAppearsWithItsAvailability() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        UUID hot = authoring.createCategory(TENANT, BRAND, catalogId, null, "HOT", "Issiq", LOCALE, 1);
        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.placeProductInCategory(TENANT, BRAND, hot, plov.productId(), 1);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, plov.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, plov.defaultVariantId(), TrackingMode.BINARY);

        var page = store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, null, null, 50);

        assertThat(page).singleElement().satisfies(row -> {
            assertThat(row.variantId()).isEqualTo(plov.defaultVariantId());
            assertThat(row.productName()).isEqualTo("Osh");
            assertThat(row.categoryName()).isEqualTo("Issiq");
            // A binary item starts available (InventoryService), so the row
            // reflects that without a separate write.
            assertThat(row.available()).isTrue();
            assertThat(row.trackingMode()).isEqualTo("BINARY");
        });
    }

    @Test
    @DisplayName("a variant toggled off shows unavailable in the listing")
    void aToggledOffVariantShowsUnavailable() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, plov.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, plov.defaultVariantId(), TrackingMode.BINARY);

        assertThat(store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, null, null, 50))
                .as("starts available")
                .singleElement()
                .satisfies(row -> assertThat(row.available()).isTrue());

        inventory.setAvailability(TENANT, LOCATION, plov.defaultVariantId(), false, "SOLD_OUT", ACTOR);

        assertThat(store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, null, null, 50))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.available()).as("stopped").isFalse();
                    assertThat(row.variantId()).isEqualTo(plov.defaultVariantId());
                });
    }

    @Test
    @DisplayName("a variant never toggled since listing has an UNKNOWN stop source")
    void aNeverToggledVariantHasAnUnknownStopSource() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, plov.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, plov.defaultVariantId(), TrackingMode.BINARY);

        assertThat(store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, null, null, 50))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.stopSource()).isEqualTo("UNKNOWN");
                    assertThat(row.stopReasonCode()).isNull();
                    assertThat(row.stopChangedAt()).isNull();
                });
    }

    @Test
    @DisplayName("gap map row 2.5b: a kitchen's own toggle is a MANUAL stop source")
    void aManualToggleIsAManualStopSource() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, plov.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, plov.defaultVariantId(), TrackingMode.BINARY);

        inventory.setAvailability(
                TENANT, LOCATION, plov.defaultVariantId(), false, "OPERATIONS_STOP_LIST_TOGGLE", ACTOR);

        assertThat(store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, null, null, 50))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.stopSource()).isEqualTo("MANUAL");
                    assertThat(row.stopReasonCode()).isEqualTo("OPERATIONS_STOP_LIST_TOGGLE");
                    assertThat(row.stopChangedAt()).isNotNull();
                });
    }

    @Test
    @DisplayName("gap map row 2.5b: PosAvailabilityPoll's own reason code is a POS stop source")
    void aPosPushIsAPosStopSource() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, plov.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, plov.defaultVariantId(), TrackingMode.BINARY);

        // PosAvailabilityPoll's own actor (a non-UUID subject, so
        // InventoryService#setAvailability records it as no actor at all —
        // see that class's own doc) and its own fixed reason code.
        inventory.setAvailability(TENANT, LOCATION, plov.defaultVariantId(), false, "POS_STOP_LIST", null);

        assertThat(store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, null, null, 50))
                .singleElement()
                .satisfies(row -> assertThat(row.stopSource()).isEqualTo("POS"));
    }

    @Test
    @DisplayName("gap map row 2.5: the stop list's own exact tab badges, over the whole catalog")
    void variantAvailabilityCountsAreExactAcrossTheWholeCatalog() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        var lagman = authoring.createProduct(
                TENANT, BRAND, catalogId, "LAGMAN", "Lagman", null, LOCALE, "SKU-LAGMAN", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, plov.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        authoring.setOffering(
                TENANT, BRAND, LOCATION, lagman.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, plov.defaultVariantId(), TrackingMode.BINARY);
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, lagman.defaultVariantId(), TrackingMode.BINARY);
        inventory.setAvailability(TENANT, LOCATION, lagman.defaultVariantId(), false, "SOLD_OUT", ACTOR);

        var counts = store.variantAvailabilityCounts(TENANT, BRAND, LOCATION, LOCALE, null);

        assertThat(counts.total()).isEqualTo(2);
        assertThat(counts.available()).isEqualTo(1);
        assertThat(counts.onStop()).isEqualTo(1);
    }

    @Test
    @DisplayName("the tab badges follow the same search box the row list does")
    void variantAvailabilityCountsFollowTheSearchTerm() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        var lagman = authoring.createProduct(
                TENANT, BRAND, catalogId, "LAGMAN", "Lagman", null, LOCALE, "SKU-LAGMAN", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, plov.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        authoring.setOffering(
                TENANT, BRAND, LOCATION, lagman.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, plov.defaultVariantId(), TrackingMode.BINARY);
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, lagman.defaultVariantId(), TrackingMode.BINARY);

        var counts = store.variantAvailabilityCounts(TENANT, BRAND, LOCATION, LOCALE, "Osh");

        assertThat(counts.total()).isEqualTo(1);
        assertThat(counts.available()).isEqualTo(1);
    }

    @Test
    @DisplayName("toggling a variant off publishes the ADR 0058 stop-list event with the stock item's own brand")
    void togglingOffPublishesAnAvailabilityChangedEvent() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, plov.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        List<Object> published = new ArrayList<>();
        InventoryService capturing = new InventoryService(
                new JdbcInventoryStore(jdbc),
                published::add,
                Clock.fixed(Instant.parse("2026-08-30T10:00:00Z"), ZoneOffset.UTC));
        capturing.listVariantAtLocation(TENANT, BRAND, LOCATION, plov.defaultVariantId(), TrackingMode.BINARY);
        published.clear(); // listVariantAtLocation itself publishes nothing; kept explicit rather than assumed.

        capturing.setAvailability(TENANT, LOCATION, plov.defaultVariantId(), false, "SOLD_OUT", ACTOR);

        List<ItemAvailabilityChanged> events = published.stream()
                .filter(ItemAvailabilityChanged.class::isInstance)
                .map(ItemAvailabilityChanged.class::cast)
                .toList();
        assertThat(events).hasSize(1);
        ItemAvailabilityChanged event = events.get(0);
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.brandId()).isEqualTo(BRAND);
        assertThat(event.locationId()).isEqualTo(LOCATION);
        assertThat(event.variantId()).isEqualTo(plov.defaultVariantId());
        assertThat(event.available()).isFalse();
        assertThat(event.reasonCode()).isEqualTo("SOLD_OUT");

        // The trigger's own name resolution, exercised against the real
        // catalog read model rather than assumed: the variant this event
        // names resolves back to the product's own translation.
        assertThat(new CatalogItemDisplayLookup(store).displayName(TENANT, plov.defaultVariantId()))
                .contains("Osh");
    }

    @Test
    @DisplayName("toggling a variant back on does not publish a second stop-list-shaped surprise")
    void togglingBackOnStillPublishesTheSymmetricEvent() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, plov.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        List<Object> published = new ArrayList<>();
        InventoryService capturing = new InventoryService(
                new JdbcInventoryStore(jdbc),
                published::add,
                Clock.fixed(Instant.parse("2026-08-30T10:00:00Z"), ZoneOffset.UTC));
        capturing.listVariantAtLocation(TENANT, BRAND, LOCATION, plov.defaultVariantId(), TrackingMode.BINARY);
        capturing.setAvailability(TENANT, LOCATION, plov.defaultVariantId(), false, "SOLD_OUT", ACTOR);
        published.clear();

        capturing.setAvailability(TENANT, LOCATION, plov.defaultVariantId(), true, "RESTOCKED", ACTOR);

        List<ItemAvailabilityChanged> events = published.stream()
                .filter(ItemAvailabilityChanged.class::isInstance)
                .map(ItemAvailabilityChanged.class::cast)
                .toList();
        assertThat(events).hasSize(1);
        // The event itself fires for both directions (InventoryEvent's own
        // Javadoc); it is InventoryOperationsAlertTrigger.onAvailabilityChanged
        // that narrows this to the off-transition alone, asserted directly
        // in InventoryOperationsAlertTriggerTests.
        assertThat(events.get(0).available()).isTrue();
    }

    @Test
    @DisplayName("a variant with no stock item at all is unavailable, not absent")
    void anUnlistedVariantIsUnavailable() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var salad = authoring.createProduct(
                TENANT, BRAND, catalogId, "SALAD", "Salat", null, LOCALE, "SKU-SALAD", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, salad.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        // Deliberately never listed with inventory: the ADR 0017 rule is that an
        // unlisted variant is unavailable, not simply unknown, mirroring
        // InventoryService.checkAvailability's own default.

        assertThat(store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, null, null, 50))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.available()).isFalse();
                    assertThat(row.trackingMode()).isNull();
                });
    }

    /**
     * catalog.md §4.5's own problem statement, fixed by this wave: before it, a
     * {@code HIDDEN} offering and a variant never offered here at all answered
     * this read identically — both absent. {@code AVAILABLE}-only join plus
     * filter meant an operator staring at the matrix could not tell "I hid
     * this on purpose" from "I forgot to add this at all". This is the test
     * that used to assert the bug ({@code anUnofferedVariantIsAbsent}, both
     * rows silently dropped) and now asserts the fix: both appear, and {@code
     * offeringStatus} is what tells them apart.
     */
    @Test
    @DisplayName("a HIDDEN variant and a never-added one both appear, distinguished by offeringStatus")
    void aHiddenVariantIsDistinctFromANeverAddedOne() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var hidden = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "HIDDEN",
                "Hidden dish",
                null,
                LOCALE,
                "SKU-HIDDEN",
                "PIECE",
                UNCLASSIFIED,
                ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, hidden.defaultVariantId(), OfferingStatus.HIDDEN, List.of("DELIVERY"));
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, hidden.defaultVariantId(), TrackingMode.BINARY);

        var neverOffered = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "NEVER",
                "Never offered",
                null,
                LOCALE,
                "SKU-NEVER",
                "PIECE",
                UNCLASSIFIED,
                ACTOR);

        var rows = store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, null, 50);

        assertThat(rows)
                .as("both the hidden offering and the never-added variant now appear")
                .hasSize(2);
        assertThat(rows)
                .filteredOn(row -> row.variantId().equals(hidden.defaultVariantId()))
                .singleElement()
                .satisfies(row -> assertThat(row.offeringStatus()).isEqualTo("HIDDEN"));
        assertThat(rows)
                .filteredOn(row -> row.variantId().equals(neverOffered.defaultVariantId()))
                .singleElement()
                .satisfies(row -> assertThat(row.offeringStatus())
                        .as("never having a location_offerings row here reads as null, not a status")
                        .isNull());
    }

    /**
     * Protects {@code StopListPortAdapter}'s Telegram 86 command, which calls
     * {@code offeringStatusFilter = "AVAILABLE"} explicitly and must keep
     * seeing exactly what it always saw — nothing hidden, nothing never
     * offered — now that the unfiltered read above includes both.
     */
    @Test
    @DisplayName("filtering to AVAILABLE reproduces the pre-widening rows exactly")
    void filteringToAvailableExcludesHiddenAndNeverAdded() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var visible = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "VISIBLE",
                "Visible dish",
                null,
                LOCALE,
                "SKU-VIS",
                "PIECE",
                UNCLASSIFIED,
                ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, visible.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        var hidden = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "HIDDEN",
                "Hidden dish",
                null,
                LOCALE,
                "SKU-HID",
                "PIECE",
                UNCLASSIFIED,
                ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, hidden.defaultVariantId(), OfferingStatus.HIDDEN, List.of("DELIVERY"));
        authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "NEVER",
                "Never offered",
                null,
                LOCALE,
                "SKU-NEV",
                "PIECE",
                UNCLASSIFIED,
                ACTOR);

        var rows = store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, null, 50, null, "AVAILABLE");

        assertThat(rows)
                .extracting(JdbcCatalogStore.VariantAvailabilityRow::variantId)
                .containsExactly(visible.defaultVariantId());
    }

    @Test
    @DisplayName("the NOT_ADDED filter finds only what is missing from this branch's menu")
    void notAddedFilterFindsOnlyWhatIsMissingFromTheMenu() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var offered = authoring.createProduct(
                TENANT, BRAND, catalogId, "OFFERED", "Offered", null, LOCALE, "SKU-OFF", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, offered.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        var missing = authoring.createProduct(
                TENANT, BRAND, catalogId, "MISSING", "Missing", null, LOCALE, "SKU-MIS", "PIECE", UNCLASSIFIED, ACTOR);

        var rows = store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, null, 50, null, "NOT_ADDED");

        assertThat(rows)
                .extracting(JdbcCatalogStore.VariantAvailabilityRow::variantId)
                .containsExactly(missing.defaultVariantId());
    }

    @Test
    @DisplayName("search matches the product name or the SKU, case-insensitively")
    void searchMatchesProductNameOrSku() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV-1", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, plov.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        var lagman = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "LAGMAN",
                "Lagman",
                null,
                LOCALE,
                "SKU-LAGMAN-1",
                "PIECE",
                UNCLASSIFIED,
                ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, lagman.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        assertThat(store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, null, 50, "osh", null))
                .as("matches the product name, case-insensitively")
                .extracting(JdbcCatalogStore.VariantAvailabilityRow::variantId)
                .containsExactly(plov.defaultVariantId());

        assertThat(store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, null, 50, "SKU-LAGMAN", null))
                .as("matches the SKU")
                .extracting(JdbcCatalogStore.VariantAvailabilityRow::variantId)
                .containsExactly(lagman.defaultVariantId());
    }

    @Test
    @DisplayName("fulfillmentModes are surfaced, empty for a never-added variant")
    void fulfillmentModesAreSurfaced() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT,
                BRAND,
                LOCATION,
                plov.defaultVariantId(),
                OfferingStatus.AVAILABLE,
                List.of("DELIVERY", "PICKUP"));
        var never = authoring.createProduct(
                TENANT, BRAND, catalogId, "NEVER", "Never", null, LOCALE, "SKU-NEVER", "PIECE", UNCLASSIFIED, ACTOR);

        var rows = store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, null, 50);

        assertThat(rows)
                .filteredOn(row -> row.variantId().equals(plov.defaultVariantId()))
                .singleElement()
                .satisfies(row -> assertThat(row.fulfillmentModes()).containsExactly("DELIVERY", "PICKUP"));
        assertThat(rows)
                .filteredOn(row -> row.variantId().equals(never.defaultVariantId()))
                .singleElement()
                .satisfies(row -> assertThat(row.fulfillmentModes()).isEmpty());
    }

    @Test
    @DisplayName("bulk-setting offering status updates every variant and leaves fulfillmentModes alone")
    void bulkSetOfferingStatusUpdatesEveryVariantAndLeavesFulfillmentModesAlone() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, plov.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("PICKUP"));
        var lagman = authoring.createProduct(
                TENANT, BRAND, catalogId, "LAGMAN", "Lagman", null, LOCALE, "SKU-LAGMAN", "PIECE", UNCLASSIFIED, ACTOR);
        // Never offered before the bulk call — the bulk gesture must be able
        // to add it to the menu, not merely toggle rows that already exist.

        int updated = authoring.bulkSetOfferingStatus(
                TENANT,
                BRAND,
                LOCATION,
                List.of(plov.defaultVariantId(), lagman.defaultVariantId()),
                OfferingStatus.UNAVAILABLE,
                ACTOR.toString());

        assertThat(updated).isEqualTo(2);
        var rows = store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, null, 50);
        assertThat(rows)
                .filteredOn(row -> row.variantId().equals(plov.defaultVariantId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.offeringStatus()).isEqualTo("UNAVAILABLE");
                    // Untouched by the bulk write — PICKUP survives, not the
                    // upsertOffering default this row never asked for.
                    assertThat(row.fulfillmentModes()).containsExactly("PICKUP");
                });
        assertThat(rows)
                .filteredOn(row -> row.variantId().equals(lagman.defaultVariantId()))
                .singleElement()
                .satisfies(row -> assertThat(row.offeringStatus()).isEqualTo("UNAVAILABLE"));
    }

    /**
     * The tenant boundary the whole endpoint exists inside. Both tenants offer
     * a variant, at their own location, and each read sees only its own —
     * exactly the property {@code tenant-isolation}'s negative-test rule asks
     * for.
     */
    @Test
    @DisplayName("another tenant's variants never appear")
    void anotherTenantsVariantsNeverAppear() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var ours = authoring.createProduct(
                TENANT, BRAND, catalogId, "OURS", "Ours", null, LOCALE, "SKU-OURS", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, ours.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, ours.defaultVariantId(), TrackingMode.BINARY);

        UUID otherCatalogId = authoring.createCatalog(OTHER_TENANT, OTHER_BRAND, "MAIN", "Their menu", LOCALE);
        var theirs = authoring.createProduct(
                OTHER_TENANT,
                OTHER_BRAND,
                otherCatalogId,
                "THEIRS",
                "Theirs",
                null,
                LOCALE,
                "SKU-THEIRS",
                "PIECE",
                UNCLASSIFIED,
                ACTOR);
        authoring.setOffering(
                OTHER_TENANT,
                OTHER_BRAND,
                OTHER_LOCATION,
                theirs.defaultVariantId(),
                OfferingStatus.AVAILABLE,
                List.of("DELIVERY"));
        inventory.listVariantAtLocation(
                OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, theirs.defaultVariantId(), TrackingMode.BINARY);

        var oursOnly = store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, null, null, 50);
        assertThat(oursOnly)
                .extracting(JdbcCatalogStore.VariantAvailabilityRow::variantId)
                .containsExactly(ours.defaultVariantId());

        var theirsOnly = store.variantsAtLocation(OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, LOCALE, null, null, 50);
        assertThat(theirsOnly)
                .extracting(JdbcCatalogStore.VariantAvailabilityRow::variantId)
                .containsExactly(theirs.defaultVariantId());
    }

    /**
     * The New order screen's item search (orders.md §5.5, wave P13): a
     * case-insensitive substring match on the product's own translated name,
     * so the picker can search a catalog of thousands rather than paging
     * through it.
     */
    @Test
    @DisplayName("query narrows the list by product name, case-insensitively")
    void queryNarrowsByProductName() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh palov", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        var somsa = authoring.createProduct(
                TENANT, BRAND, catalogId, "SOMSA", "Somsa", null, LOCALE, "SKU-SOMSA", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, plov.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        authoring.setOffering(
                TENANT, BRAND, LOCATION, somsa.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        assertThat(store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, "palov", null, 50))
                .as("a lower-case, partial query still matches the mixed-case stored name")
                .extracting(JdbcCatalogStore.VariantAvailabilityRow::variantId)
                .containsExactly(plov.defaultVariantId());

        assertThat(store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, "SOM", null, 50))
                .as("upper-case query, lower-case stored name")
                .extracting(JdbcCatalogStore.VariantAvailabilityRow::variantId)
                .containsExactly(somsa.defaultVariantId());

        assertThat(store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, "no such dish", null, 50))
                .as("a query matching nothing returns nothing, not everything")
                .isEmpty();

        assertThat(store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, null, null, 50))
                .as("no query is still the unfiltered page every other caller relies on")
                .hasSize(2);

        assertThat(store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, "   ", null, 50))
                .as("a blank query is the same as no query, never an everything-excluded empty pattern")
                .hasSize(2);
    }

    /**
     * The same property {@link #anotherTenantsVariantsNeverAppear} proves for
     * an unfiltered read, but for the searched read: a query is a narrower
     * WHERE clause over the same tenant- and location-scoped rows, never a
     * back door around the scope those other predicates enforce.
     */
    @Test
    @DisplayName("a matching query never crosses the location boundary")
    void queryStaysLocationScoped() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var ours = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "BURGER",
                "Cheeseburger",
                null,
                LOCALE,
                "SKU-BURGER",
                "PIECE",
                UNCLASSIFIED,
                ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, ours.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        UUID otherCatalogId = authoring.createCatalog(OTHER_TENANT, OTHER_BRAND, "MAIN", "Their menu", LOCALE);
        var theirs = authoring.createProduct(
                OTHER_TENANT,
                OTHER_BRAND,
                otherCatalogId,
                "BURGER2",
                "Cheeseburger deluxe",
                null,
                LOCALE,
                "SKU-BURGER2",
                "PIECE",
                UNCLASSIFIED,
                ACTOR);
        authoring.setOffering(
                OTHER_TENANT,
                OTHER_BRAND,
                OTHER_LOCATION,
                theirs.defaultVariantId(),
                OfferingStatus.AVAILABLE,
                List.of("DELIVERY"));

        assertThat(store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, "cheeseburger", null, 50))
                .as("the same query at the other tenant's location must not surface this tenant's match")
                .extracting(JdbcCatalogStore.VariantAvailabilityRow::variantId)
                .containsExactly(ours.defaultVariantId());

        assertThat(store.variantsAtLocation(
                        OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, LOCALE, "cheeseburger", null, 50))
                .extracting(JdbcCatalogStore.VariantAvailabilityRow::variantId)
                .containsExactly(theirs.defaultVariantId());
    }

    @Test
    @DisplayName("the page is keyset-paginated by variant id, oldest cursor first")
    void thePageIsKeysetPaginated() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        for (int i = 0; i < 3; i++) {
            var product = authoring.createProduct(
                    TENANT,
                    BRAND,
                    catalogId,
                    "P" + i,
                    "Product " + i,
                    null,
                    LOCALE,
                    "SKU-" + i,
                    "PIECE",
                    UNCLASSIFIED,
                    ACTOR);
            authoring.setOffering(
                    TENANT, BRAND, LOCATION, product.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        }

        var firstPage = store.variantsAtLocation(TENANT, BRAND, LOCATION, LOCALE, null, null, 2);
        assertThat(firstPage).hasSize(2);

        var secondPage = store.variantsAtLocation(
                TENANT,
                BRAND,
                LOCATION,
                LOCALE,
                null,
                firstPage.get(firstPage.size() - 1).variantId(),
                2);
        assertThat(secondPage).hasSize(1);

        assertThat(firstPage)
                .extracting(JdbcCatalogStore.VariantAvailabilityRow::variantId)
                .doesNotContainAnyElementsOf(secondPage.stream()
                        .map(JdbcCatalogStore.VariantAvailabilityRow::variantId)
                        .toList());
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
                .param("slug", brandCode.toLowerCase(java.util.Locale.ROOT))
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
