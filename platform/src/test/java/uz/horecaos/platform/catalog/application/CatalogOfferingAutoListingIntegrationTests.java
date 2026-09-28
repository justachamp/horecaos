package uz.horecaos.platform.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.inventory.api.ReservationResult;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The producer-to-consumer proof the wave's own job description asks for
 * directly: {@code CatalogAuthoringService#setOffering} lists the variant
 * (the {@link uz.horecaos.platform.catalog.api.OfferingBecameAvailable}
 * event, consumed by {@code inventory.application.CatalogOfferingListingTrigger})
 * and a later checkout succeeds (gap-map row 4.1's pilot-critical follow-up).
 *
 * <p>A real {@code @SpringBootTest} context on purpose, not a hand-wired
 * fixture: only a real container proves the
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} wiring actually
 * fires — nothing here simulates that phase, {@code setOffering}'s own
 * {@code @Transactional} commits for real and Spring's own (synchronous,
 * unconfigured) event multicaster runs the listener before {@code
 * setOffering} returns to this test.
 */
@SpringBootTest
class CatalogOfferingAutoListingIntegrationTests {

    private static final UUID TENANT = UUID.fromString("018f9f20-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f20-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9f20-4000-7000-8000-0000000000c1");
    private static final UUID PRODUCT = UUID.fromString("018f9f20-4000-7000-8000-0000000000d1");
    private static final UUID VARIANT = UUID.fromString("018f9f20-4000-7000-8000-0000000000d2");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this integration test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    @Autowired
    private CatalogAuthoringService authoring;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE inventory.reservation_lines, inventory.reservations, "
                        + "inventory.movements, inventory.positions, inventory.stock_items CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.location_offerings CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.variants, catalog.products CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertFixtures();
    }

    @Test
    void settingAnOfferingAvailableListsTheVariantAndACheckoutSucceeds() {
        authoring.setOffering(TENANT, BRAND, LOCATION, VARIANT, OfferingStatus.AVAILABLE, List.of("PICKUP"));

        AvailabilityDecision decision = inventory.checkAvailability(TENANT, LOCATION, Set.of(VARIANT));
        assertThat(decision.available())
                .as("the event-driven listener should have listed the variant BINARY before setOffering returned")
                .isTrue();

        ReservationResult checkout = inventory.reserveForQuote(
                TENANT, BRAND, LOCATION, UUID.randomUUID(), Instant.now().plusSeconds(600), Map.of(VARIANT, 1));
        assertThat(checkout.isHeld())
                .as("a later checkout must succeed now that the variant is listed")
                .isTrue();
    }

    @Test
    void settingAnOfferingUnavailableNeverListsTheVariant() {
        authoring.setOffering(TENANT, BRAND, LOCATION, VARIANT, OfferingStatus.UNAVAILABLE, List.of("PICKUP"));

        AvailabilityDecision decision = inventory.checkAvailability(TENANT, LOCATION, Set.of(VARIANT));
        assertThat(decision.available()).isFalse();
        assertThat(decision.unavailableItems())
                .as(
                        "nothing about UNAVAILABLE should create a stock item — the variant reads NOT_STOCKED, not sold out")
                .anySatisfy(item -> assertThat(item.reason()).isEqualTo("NOT_STOCKED_AT_LOCATION"));
    }

    @Test
    void aDeliberatelySoldOutBinaryItemStaysSoldOutThroughRelisting() {
        authoring.setOffering(TENANT, BRAND, LOCATION, VARIANT, OfferingStatus.AVAILABLE, List.of("PICKUP"));
        assertThat(inventory
                        .checkAvailability(TENANT, LOCATION, Set.of(VARIANT))
                        .available())
                .isTrue();

        inventory.setAvailability(TENANT, LOCATION, VARIANT, false, "OUT_OF_STOCK", null);
        assertThat(inventory
                        .checkAvailability(TENANT, LOCATION, Set.of(VARIANT))
                        .available())
                .isFalse();

        // Re-asserting the same offering (a re-save, a re-run of an importer)
        // must not silently put the 86'd dish back on.
        authoring.setOffering(TENANT, BRAND, LOCATION, VARIANT, OfferingStatus.AVAILABLE, List.of("PICKUP"));

        assertThat(inventory
                        .checkAvailability(TENANT, LOCATION, Set.of(VARIANT))
                        .available())
                .as("StockListingPort#ensureListed's own idempotency must not overwrite a deliberate sold-out")
                .isFalse();
    }

    private void insertFixtures() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'catalog-offering-auto-listing', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, 'BURGER', 'ACTIVE')
                """)
                .param("id", PRODUCT)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, is_default, status)
                VALUES (:id, :tenantId, :brandId, :productId, true, 'ACTIVE')
                """)
                .param("id", VARIANT)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", PRODUCT)
                .update();
    }
}
