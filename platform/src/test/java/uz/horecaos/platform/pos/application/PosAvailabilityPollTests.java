package uz.horecaos.platform.pos.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.application.StockAvailabilityPortAdapter;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.pos.FakePosAdapter;
import uz.horecaos.platform.pos.domain.CatalogSnapshot;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosBindingConfiguration;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosLiveAvailabilityStore;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0012's stop-list poller, end to end: a provider reading reaches a
 * customer-visible fact in {@code inventory}, and a provider failure never
 * looks like a menu emptying out.
 *
 * <p>Wires the real {@link JdbcPosLiveAvailabilityStore}, {@link
 * PosAvailabilityPollService}, and {@link InventoryService} against a real
 * database, and {@link FakePosAdapter} in place of an actual Clopos connection
 * — the same substitution {@code PosCatalogSyncService}'s own tests use for the
 * daily run.
 */
class PosAvailabilityPollTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-7b00-7000-8000-0000000e0001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-7b00-7000-8000-0000000e0002");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-7b00-7000-8000-0000000e0003");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-7b00-7000-8000-0000000e0004");
    private static final UUID BINDING = UUID.fromString("018f6f4e-7b00-7000-8000-0000000e0005");
    private static final String LOCALE = "en";
    private static final UUID CREATE_ACTOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-09T10:00:00Z");
    private static final String EXTERNAL_PRODUCT_ID = "ext-plov";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private InventoryService inventory;
    private FakePosAdapter fakeAdapter;
    private PosAvailabilityPoll poll;
    private UUID variantId;

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

        // TRUNCATE CASCADE, not scoped DELETEs: this suite's database is its own
        // private clone (see TestDatabase's own doc), so there is no other
        // class's data to disturb, and CASCADE means a table this suite forgot
        // to name explicitly still gets cleared instead of throwing an FK error
        // on the next test's insert.
        jdbc.sql("TRUNCATE TABLE integration.pos_live_availability, integration.provider_entity_mappings, "
                        + "integration.binding_capabilities, integration.bindings, integration.installations CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE inventory.reservation_lines, inventory.reservations, "
                        + "inventory.movements, inventory.positions, inventory.stock_items CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.location_offerings, catalog.translations, "
                        + "catalog.category_products, catalog.categories, catalog.catalog_products, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'pos-availability-poll', 'Legal', 'POS availability poll', 'UZS',
                        'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'POLL_BRAND', 'poll-brand', 'Poll brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :t, :brandId, 'MAIN', 'main', 'Main location', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("t", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :t, 'POS', 'fake-pos', 'clopos-open-api-v2', 'Pilot', 'ACTIVE')
                """).param("id", INSTALLATION).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, location_id, status)
                VALUES (:id, :t, :installationId, :brandId, :locationId, 'ACTIVE')
                """)
                .param("id", BINDING)
                .param("t", TENANT)
                .param("installationId", INSTALLATION)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .update();
        jdbc.sql("""
                INSERT INTO integration.binding_capabilities (binding_id, tenant_id, capability_code, enabled)
                VALUES (:bindingId, :t, 'AVAILABILITY_READ', true)
                """).param("bindingId", BINDING).param("t", TENANT).update();

        JdbcCatalogStore catalogStore =
                new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        CommercialDefaults.Wired commercial = CommercialDefaults.wire(jdbc, Clock.systemUTC());
        CatalogAuthoringService authoring = new CatalogAuthoringService(
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
        StockAvailabilityPortAdapter stockAvailability = new StockAvailabilityPortAdapter(inventory);

        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "PLOV",
                "Plov",
                null,
                LOCALE,
                "SKU-PLOV",
                "PIECE",
                FiscalClassification.unclassified(),
                CREATE_ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, plov.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DINE_IN"));
        variantId = plov.defaultVariantId();
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, variantId, TrackingMode.BINARY);

        jdbc.sql("""
                INSERT INTO integration.provider_entity_mappings
                    (id, tenant_id, installation_id, binding_id, entity_type, horecaos_entity_id,
                     external_entity_id, status, mapping_source)
                VALUES (:id, :t, :installationId, :bindingId, 'VARIANT_PARENT', :productId,
                        :externalId, 'ACTIVE', 'DISCOVERED')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("installationId", INSTALLATION)
                .param("bindingId", BINDING)
                .param("productId", plov.productId())
                .param("externalId", EXTERNAL_PRODUCT_ID)
                .update();

        JdbcPosLiveAvailabilityStore store = new JdbcPosLiveAvailabilityStore(jdbc);
        JdbcPosBindingConfiguration configuration =
                new JdbcPosBindingConfiguration(jdbc, JsonMapper.builder().build());
        fakeAdapter = new FakePosAdapter();
        PosAdapterRegistry adapters = new PosAdapterRegistry(List.of(fakeAdapter));
        PosAvailabilityPollService pollService = new PosAvailabilityPollService(store);

        poll = new PosAvailabilityPoll(
                store,
                configuration,
                adapters,
                pollService,
                stockAvailability,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("a stop-list reading reaches the customer: 86'd, then un-86'd when the provider drops the constraint")
    void aStopListReadingReachesInventoryInBothDirections() {
        assertThat(binaryAvailable()).as("sanity: the variant starts available").isTrue();

        fakeAdapter.scriptAvailability(
                List.of(new CatalogSnapshot.Availability(EXTERNAL_PRODUCT_ID, BigDecimal.ZERO, NOW, Map.of())));
        poll.pollDueBindings();

        assertThat(binaryAvailable())
                .as("a stop-list limit of zero must reach the same inventory fact the storefront reads")
                .isFalse();

        // Absence means unconstrained: the provider no longer names this
        // product on the stop list at all.
        fakeAdapter.scriptAvailability(List.of());
        poll.pollDueBindings();

        assertThat(binaryAvailable())
                .as("dropping off the stop list must flip the variant back to available, not leave it 86'd forever")
                .isTrue();
    }

    @Test
    @DisplayName("a failed poll leaves the last known reading exactly where it was -- stale, never withdrawn")
    void aFailedPollLeavesTheLastReadingInPlace() {
        fakeAdapter.scriptAvailability(
                List.of(new CatalogSnapshot.Availability(EXTERNAL_PRODUCT_ID, BigDecimal.ZERO, NOW, Map.of())));
        poll.pollDueBindings();
        assertThat(binaryAvailable()).isFalse();

        fakeAdapter.failNextAvailabilityReadWith(
                ProviderOutcome.retryable("TIMEOUT", "read timed out", Duration.ofSeconds(5)));
        poll.pollDueBindings();

        assertThat(binaryAvailable())
                .as("a provider timeout must never look like the menu emptying out -- the product stays 86'd, "
                        + "the last thing anybody actually observed, rather than silently becoming sellable again")
                .isFalse();
        assertThat(jdbc.sql("""
                        SELECT stock_limit FROM integration.pos_live_availability
                         WHERE tenant_id = :t AND binding_id = :b AND external_entity_id = :id
                        """)
                        .param("t", TENANT)
                        .param("b", BINDING)
                        .param("id", EXTERNAL_PRODUCT_ID)
                        .query(BigDecimal.class)
                        .single())
                .as("the stored reading itself is untouched by the failed poll")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    private boolean binaryAvailable() {
        return jdbc.sql("""
                        SELECT p.binary_available
                          FROM inventory.stock_items s
                          JOIN inventory.positions p ON p.stock_item_id = s.id AND p.tenant_id = s.tenant_id
                         WHERE s.tenant_id = :t AND s.location_id = :locationId AND s.variant_id = :variantId
                        """)
                .param("t", TENANT)
                .param("locationId", LOCATION)
                .param("variantId", variantId)
                .query(Boolean.class)
                .single();
    }
}
