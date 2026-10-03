package uz.horecaos.platform.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
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
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.inventory.web.StorefrontInventoryController.StorefrontAvailabilityResponse;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * {@code StorefrontInventoryController} had zero coverage before this wave
 * and leaked the raw {@code remainingQuantity} unconditionally. This proves
 * the fix (gap map row 4.4c/4.4d's storefront half): the single-variant read
 * applies the exact same {@link InventoryService#LOW_STOCK_DISPLAY_THRESHOLD}
 * gate {@code InventoryMenuAvailabilityLookup} already applies to the
 * published menu's own batched read.
 */
class StorefrontInventoryControllerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final String CHANNEL = "WEB";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private InventoryService inventory;
    private StorefrontInventoryController controller;

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
        jdbc.sql("TRUNCATE TABLE catalog.variants, catalog.products CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'storefront-inventory-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        inventory = new InventoryService(new JdbcInventoryStore(jdbc), event -> {}, Clock.systemUTC());
        controller = new StorefrontInventoryController(inventory);
    }

    /** {@code inventory.stock_items} has a real FK to {@code catalog.variants} (V0019's own {@code fk_stock_item_variant}). */
    private UUID createVariant(String code) {
        UUID productId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", code)
                .update();
        UUID variantId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, is_default, status)
                VALUES (:id, :tenantId, :brandId, :productId, true, 'ACTIVE')
                """)
                .param("id", variantId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", productId)
                .update();
        return variantId;
    }

    @Test
    @DisplayName("at or below the threshold, the remaining count is shown")
    void lowRemainingQuantityIsShown() {
        UUID variantId = createVariant("LOW");
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, variantId, TrackingMode.QUANTITY);
        inventory.setOnHandQuantity(TENANT, LOCATION, variantId, BigDecimal.valueOf(3), "RECOUNT", "test-operator");

        StorefrontAvailabilityResponse response = Objects.requireNonNull(
                controller.availability(TENANT, LOCATION, variantId, CHANNEL).getBody());

        assertThat(response.available()).isTrue();
        assertThat(response.remainingQuantity()).isEqualByComparingTo(BigDecimal.valueOf(3));
    }

    @Test
    @DisplayName("exactly at the threshold, the remaining count is still shown")
    void remainingQuantityAtTheThresholdIsShown() {
        UUID variantId = createVariant("AT-THRESHOLD");
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, variantId, TrackingMode.QUANTITY);
        inventory.setOnHandQuantity(
                TENANT, LOCATION, variantId, InventoryService.LOW_STOCK_DISPLAY_THRESHOLD, "RECOUNT", "test-operator");

        StorefrontAvailabilityResponse response = Objects.requireNonNull(
                controller.availability(TENANT, LOCATION, variantId, CHANNEL).getBody());

        assertThat(response.remainingQuantity()).isEqualByComparingTo(InventoryService.LOW_STOCK_DISPLAY_THRESHOLD);
    }

    // ----------------------------------------- ADR 0141: the stops cover the channel the customer is on

    private StorefrontInventoryController controllerWithStopsAndChannels() {
        TenantRlsSession noRls = new TenantRlsSession() {
            @Override
            public void bindTenant(UUID tenantId) {}

            @Override
            public void bindPlatform() {}
        };
        InventoryService wired = new InventoryService(
                new JdbcInventoryStore(jdbc),
                event -> {},
                Clock.systemUTC(),
                fact -> {},
                noRls,
                new FakeConfigurationResolver(),
                (tenantId, at) -> at.atZone(ZoneOffset.UTC).toLocalDate(),
                new JdbcAvailabilityStopStore(jdbc),
                null,
                new JdbcSalesChannelStore(jdbc),
                null);
        inventory = wired;
        return new StorefrontInventoryController(wired);
    }

    private UUID insertChannel(String code, String systemType) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name)
                VALUES (:id, :t, :code, :type, :code)
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("code", code)
                .param("type", systemType)
                .update();
        return id;
    }

    private void insertChannelStop(UUID variantId, UUID channelId) {
        jdbc.sql("""
                INSERT INTO inventory.availability_stops (
                    id, tenant_id, brand_id, variant_id, scope_type, channel_id,
                    source, reason_code, created_by)
                VALUES (:id, :t, :b, :v, 'CHANNEL', :channel, 'OPERATOR', 'RECALL', 'operator-1')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("v", variantId)
                .param("channel", channelId)
                .update();
    }

    @Test
    @DisplayName(
            "a stop on the customer's channel makes the read say unavailable, and another channel still sells the dish")
    void aChannelStopCoversTheChannelTheCustomerIsOn() {
        StorefrontInventoryController wired = controllerWithStopsAndChannels();
        UUID web = insertChannel("WEB1", "WEB");
        insertChannel("APP1", "IOS");
        UUID variantId = createVariant("STOPPED-ON-WEB");
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, variantId, TrackingMode.BINARY);
        insertChannelStop(variantId, web);

        assertThat(Objects.requireNonNull(wired.availability(TENANT, LOCATION, variantId, "WEB1")
                                .getBody())
                        .available())
                .as(
                        "the menu hides the dish and the cart refuses it with ON_STOP, so this read must not say it is orderable")
                .isFalse();
        assertThat(Objects.requireNonNull(wired.availability(TENANT, LOCATION, variantId, "APP1")
                                .getBody())
                        .available())
                .as("the stop covers one channel only")
                .isTrue();
    }

    @Test
    @DisplayName("a bare system type that names no registered channel code still answers as before")
    void aBareSystemTypeStillAnswers() {
        StorefrontInventoryController wired = controllerWithStopsAndChannels();
        insertChannel("STOREFRONT", "WEB");
        UUID variantId = createVariant("PLAIN");
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, variantId, TrackingMode.BINARY);

        assertThat(Objects.requireNonNull(wired.availability(TENANT, LOCATION, variantId, "WEB")
                                .getBody())
                        .available())
                .isTrue();
    }

    @Test
    @DisplayName("above the threshold, the exact count is never leaked")
    void highRemainingQuantityIsHidden() {
        UUID variantId = createVariant("HIGH");
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, variantId, TrackingMode.QUANTITY);
        inventory.setOnHandQuantity(TENANT, LOCATION, variantId, BigDecimal.valueOf(500), "RECOUNT", "test-operator");

        StorefrontAvailabilityResponse response = Objects.requireNonNull(
                controller.availability(TENANT, LOCATION, variantId, CHANNEL).getBody());

        assertThat(response.available()).isTrue();
        assertThat(response.remainingQuantity())
                .as("500 is well above the shared low-stock threshold; the raw count must not leak")
                .isNull();
    }

    @Test
    @DisplayName("an unavailable variant never shows a count, high or low")
    void unavailableVariantNeverShowsAQuantity() {
        UUID variantId = createVariant("UNAVAILABLE");
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, variantId, TrackingMode.BINARY);
        inventory.setAvailability(TENANT, LOCATION, variantId, false, "OUT_OF_STOCK", null);

        StorefrontAvailabilityResponse response = Objects.requireNonNull(
                controller.availability(TENANT, LOCATION, variantId, CHANNEL).getBody());

        assertThat(response.available()).isFalse();
        assertThat(response.remainingQuantity()).isNull();
    }
}
