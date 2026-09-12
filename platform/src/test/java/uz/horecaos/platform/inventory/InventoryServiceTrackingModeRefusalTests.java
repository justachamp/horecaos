package uz.horecaos.platform.inventory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
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
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.application.InventoryService.UnsupportedTrackingModeException;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Gap map row {@code 4.4d} (wave P46): {@code QUANTITY} tracking is refused
 * either way {@code catalog.use_stock_logic} is set — the platform does not
 * implement counted stock yet — but the refusal must read as a legible
 * operator sentence naming which of the two true things is going on, never
 * as a stack trace. Before this wave, {@link
 * InventoryService.UnsupportedTrackingModeException} was a bare {@code
 * RuntimeException} with one fixed message and no handler on at least one
 * reachable path ({@code checkAvailability}), which is a 500 the moment
 * either occurs.
 */
class InventoryServiceTrackingModeRefusalTests {

    private static final TenantRlsSession NO_OP_RLS = new TenantRlsSession() {
        @Override
        public void bindTenant(UUID tenantId) {}

        @Override
        public void bindPlatform() {}
    };

    private static TestDatabase.Handle db;

    private JdbcInventoryStore store;
    private Clock clock;

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
    void wireStore() {
        DataSource dataSource = db.dataSource();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE inventory.reservation_lines, inventory.reservations, "
                        + "inventory.movements, inventory.positions, inventory.stock_items CASCADE")
                .update();
        store = new JdbcInventoryStore(jdbc);
        clock = Clock.fixed(Instant.parse("2026-09-12T09:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    @DisplayName("catalog.use_stock_logic off: QUANTITY is refused, naming the flag that would need turning on")
    void refusedWithTheFlagOffNamesTheFlag() {
        InventoryService inventory =
                new InventoryService(store, event -> {}, clock, fact -> {}, NO_OP_RLS, new FakeConfigurationResolver());
        UUID tenantId = UUID.randomUUID();

        assertThatThrownBy(() -> inventory.listVariantAtLocation(
                        tenantId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TrackingMode.QUANTITY))
                .isInstanceOf(UnsupportedTrackingModeException.class)
                .hasMessageContaining("catalog.use_stock_logic")
                .hasMessageContaining("off")
                .hasMessageNotContaining("Exception")
                .hasMessageNotContaining("null");
    }

    @Test
    @DisplayName("catalog.use_stock_logic on: QUANTITY is still refused, but says the platform cannot honour it yet")
    void refusedWithTheFlagOnSaysEnforcementDoesNotExistYet() {
        InventoryService inventory = new InventoryService(
                store,
                event -> {},
                clock,
                fact -> {},
                NO_OP_RLS,
                new FakeConfigurationResolver(Map.of("catalog.use_stock_logic", true)));
        UUID tenantId = UUID.randomUUID();

        assertThatThrownBy(() -> inventory.listVariantAtLocation(
                        tenantId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), TrackingMode.QUANTITY))
                .isInstanceOf(UnsupportedTrackingModeException.class)
                .hasMessageContaining("catalog.use_stock_logic")
                .hasMessageContaining("turned on")
                .hasMessageContaining("not available");
    }

    /**
     * The gap that was truly latent before this wave: {@code
     * listVariantAtLocation} always refused {@code QUANTITY} before a stock
     * item could exist, so nothing exercised {@code checkAvailability}'s own
     * {@code QUANTITY} branch — until this test seeds one directly, the way a
     * future authoring path or a hand-edited row could. Before {@code
     * InventoryApiErrorHandler} existed, this exact call reached {@code
     * InventoryController.checkAvailability} with no local catch and no
     * global handler for a bare {@code RuntimeException}: a 500.
     */
    @Test
    @DisplayName("checkAvailability refuses a QUANTITY item just as legibly, not only listVariantAtLocation")
    void checkAvailabilityAlsoRefusesLegibly() {
        UUID tenantId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        seedTenantBrandLocationVariant(tenantId, brandId, locationId, productId, variantId);
        store.createStockItem(tenantId, brandId, locationId, variantId, TrackingMode.QUANTITY, clock.instant());

        InventoryService inventory =
                new InventoryService(store, event -> {}, clock, fact -> {}, NO_OP_RLS, new FakeConfigurationResolver());

        assertThatThrownBy(() -> inventory.checkAvailability(tenantId, locationId, Set.of(variantId)))
                .isInstanceOf(UnsupportedTrackingModeException.class)
                .hasMessageContaining("catalog.use_stock_logic");
    }

    /** The tenant/brand/location/product/variant chain {@code inventory.*}'s foreign keys require. */
    private void seedTenantBrandLocationVariant(
            UUID tenantId, UUID brandId, UUID locationId, UUID productId, UUID variantId) {
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        String suffix = tenantId.toString().substring(0, 8);

        jdbc.sql("""
                        INSERT INTO tenant.tenants (
                            id, slug, legal_name, display_name, default_currency, default_timezone, status)
                        VALUES (:id, :slug, 'Tracking mode test', 'Tracking mode test', 'UZS', 'Asia/Tashkent', 'ACTIVE')
                        """).param("id", tenantId).param("slug", "trk-" + suffix).update();

        jdbc.sql("""
                        INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                        VALUES (:id, :tenantId, 'BRAND', 'brand', 'Brand', 'ACTIVE')
                        """).param("id", brandId).param("tenantId", tenantId).update();

        jdbc.sql("""
                        INSERT INTO tenant.locations (
                            id, tenant_id, brand_id, code, slug, display_name, timezone, status)
                        VALUES (:id, :tenantId, :brandId, 'LOC', 'loc', 'Location', 'Asia/Tashkent', 'ACTIVE')
                        """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();

        jdbc.sql("""
                        INSERT INTO catalog.products (id, tenant_id, brand_id, code)
                        VALUES (:id, :tenantId, :brandId, 'SKU')
                        """)
                .param("id", productId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();

        jdbc.sql("""
                        INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id)
                        VALUES (:id, :tenantId, :brandId, :productId)
                        """)
                .param("id", variantId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .update();
    }
}
