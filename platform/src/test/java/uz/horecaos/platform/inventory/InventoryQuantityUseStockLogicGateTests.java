package uz.horecaos.platform.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Gap map rows {@code 4.4c}/{@code 4.4d}: {@code catalog.use_stock_logic}'s
 * real meaning for a {@code QUANTITY}-tracked item.
 *
 * <p>Before this wave, {@code QUANTITY} was refused outright by {@code
 * InventoryService.UnsupportedTrackingModeException} regardless of the flag —
 * this suite replaces that refusal test, since the refusal itself is gone. A
 * variant may now be listed {@code QUANTITY} either way (an operator
 * reconciling counts before flipping the tenant-wide switch, ADR 0017's own
 * rollout phase); what the flag now actually gates is enforcement: off, a
 * {@code QUANTITY} item behaves exactly like {@code UNTRACKED} — sellable
 * with nothing on hand at all — and on, it is enforced for real.
 */
class InventoryQuantityUseStockLogicGateTests {

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
    @DisplayName("a variant may be listed QUANTITY regardless of catalog.use_stock_logic")
    void listingQuantitySucceedsEitherWayTheFlagIsSet() {
        UUID tenantId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        seedTenantBrandLocationVariant(tenantId, brandId, locationId, variantId, "off");

        InventoryService inventory =
                new InventoryService(store, event -> {}, clock, fact -> {}, NO_OP_RLS, new FakeConfigurationResolver());

        UUID stockItemId =
                inventory.listVariantAtLocation(tenantId, brandId, locationId, variantId, TrackingMode.QUANTITY);

        assertThat(stockItemId).as("no exception, a real stock item is created").isNotNull();
    }

    @Test
    @DisplayName("flag off: a QUANTITY item with nothing on hand is still available, like UNTRACKED")
    void flagOffBehavesLikeUntracked() {
        UUID tenantId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        seedTenantBrandLocationVariant(tenantId, brandId, locationId, variantId, "off");
        store.createStockItem(tenantId, brandId, locationId, variantId, TrackingMode.QUANTITY, clock.instant());
        // on_hand_quantity defaults to zero (V0019) -- were the flag on, this
        // would refuse as SOLD_OUT.

        InventoryService inventory = new InventoryService(
                store,
                event -> {},
                clock,
                fact -> {},
                NO_OP_RLS,
                new FakeConfigurationResolver(Map.of("catalog.use_stock_logic", false)));

        AvailabilityDecision decision = inventory.checkAvailability(tenantId, locationId, Set.of(variantId));

        assertThat(decision.available())
                .as("catalog.use_stock_logic off: QUANTITY quantities are ignored, UNTRACKED behaviour")
                .isTrue();
    }

    @Test
    @DisplayName("flag on: a QUANTITY item with nothing on hand refuses, SOLD_OUT")
    void flagOnEnforcesForReal() {
        UUID tenantId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        seedTenantBrandLocationVariant(tenantId, brandId, locationId, variantId, "on");
        store.createStockItem(tenantId, brandId, locationId, variantId, TrackingMode.QUANTITY, clock.instant());

        InventoryService inventory = new InventoryService(
                store,
                event -> {},
                clock,
                fact -> {},
                NO_OP_RLS,
                new FakeConfigurationResolver(Map.of("catalog.use_stock_logic", true)));

        AvailabilityDecision decision = inventory.checkAvailability(tenantId, locationId, Set.of(variantId));

        assertThat(decision.available())
                .as("catalog.use_stock_logic on: QUANTITY is enforced for real")
                .isFalse();
        assertThat(decision.unavailableItems())
                .extracting(AvailabilityDecision.Unavailable::reason)
                .containsExactly("SOLD_OUT");
    }

    @Test
    @DisplayName("flag on: setting on-hand makes a QUANTITY item sellable")
    void flagOnAndOnHandSetMakesItSellable() {
        UUID tenantId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        seedTenantBrandLocationVariant(tenantId, brandId, locationId, variantId, "on");

        InventoryService inventory = new InventoryService(
                store,
                event -> {},
                clock,
                fact -> {},
                NO_OP_RLS,
                new FakeConfigurationResolver(Map.of("catalog.use_stock_logic", true)));
        inventory.listVariantAtLocation(tenantId, brandId, locationId, variantId, TrackingMode.QUANTITY);

        boolean changed = inventory.setOnHandQuantity(
                tenantId, locationId, variantId, BigDecimal.valueOf(5), "DELIVERY_RECEIVED", "operator-1");

        assertThat(changed).isTrue();
        AvailabilityDecision decision = inventory.checkAvailability(tenantId, locationId, Set.of(variantId));
        assertThat(decision.available()).isTrue();
    }

    /** The tenant/brand/location/product/variant chain {@code inventory.*}'s foreign keys require. */
    private void seedTenantBrandLocationVariant(
            UUID tenantId, UUID brandId, UUID locationId, UUID variantId, String slugSuffix) {
        JdbcClient jdbc = JdbcClient.create(db.dataSource());
        UUID productId = UUID.randomUUID();
        String suffix = tenantId.toString().substring(0, 8);

        jdbc.sql("""
                        INSERT INTO tenant.tenants (
                            id, slug, legal_name, display_name, default_currency, default_timezone, status)
                        VALUES (:id, :slug, 'Use-stock-logic test', 'Use-stock-logic test', 'UZS', 'Asia/Tashkent', 'ACTIVE')
                        """)
                .param("id", tenantId)
                .param("slug", "usl-" + slugSuffix + "-" + suffix)
                .update();

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
