package uz.horecaos.platform.ordering.application.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.fulfillment.application.DeliveryFeeResolver;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcCatalogPricingContext;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStepHandler.StepResult;
import uz.horecaos.platform.tenancy.application.ServiceabilityService;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcServiceabilityStore;

/**
 * ADR 0008: {@code ACTIVATION_SMOKE_TEST}, passing and failing on a fixture
 * shaped like production data. Wires the same real {@code pricing} and {@code
 * tenancy} components production does — a stand-in would prove the handler
 * calls a stand-in, which is not what this step exists to prove.
 */
class OrderingOnboardingStepHandlersTests {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String CHANNEL_CODE = "STOREFRONT";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private UUID tenantId;
    private UUID brandId;
    private UUID locationId;
    private UUID catalogId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for these tests");
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
        jdbc.sql("TRUNCATE TABLE pricing.quote_adjustments, pricing.quote_lines, pricing.quotes, "
                        + "pricing.prices, pricing.price_book_assignments, pricing.price_books, "
                        + "pricing.tax_profiles CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, "
                        + "catalog.location_offerings, catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.channel_fulfillment_modes, tenant.sales_channel_locations, "
                        + "tenant.sales_channels CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        tenantId = UUID.randomUUID();
        brandId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        insertTenant();
        insertBrand();
        insertLocation();
        catalogId = insertCatalog();
    }

    @Test
    void failsWhenTheTenantHasNoStorefrontChannel() {
        StepResult result = handler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("NO_CHANNEL");
    }

    @Test
    void failsWhenTheChannelHasNoFulfillmentModeEnabledAtTheLocation() {
        UUID channelId = insertChannel();
        bindChannelToLocation(channelId);

        StepResult result = handler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("NO_FULFILLMENT_MODE");
    }

    @Test
    void failsWhenNoItemIsAvailableAtTheLocation() {
        UUID channelId = insertChannel();
        bindChannelToLocation(channelId);
        enableFulfillmentMode(channelId, "PICKUP");

        StepResult result = handler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("NO_AVAILABLE_ITEM");
    }

    @Test
    void failsWhenTheItemHasNoPriceToQuote() {
        UUID channelId = insertChannel();
        bindChannelToLocation(channelId);
        enableFulfillmentMode(channelId, "PICKUP");
        UUID variantId = insertProductAndVariant("BURGER");
        insertPublication();
        insertLocationOffering(variantId);
        // Deliberately no price book, no price, no tax profile: the quote must refuse.

        StepResult result = handler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("QUOTE_REFUSED");
    }

    /**
     * Gap F, closed: pricing cleanly is not the same as being sellable.
     * Everything a checkout needs to quote is present and nothing has ever
     * listed the item as stock — the exact shape the proving run found live,
     * where {@code ACTIVATION_SMOKE_TEST} passed and the tenant's very first
     * real order refused {@code 409 ITEMS_UNAVAILABLE}/{@code
     * NOT_STOCKED_AT_LOCATION}.
     */
    @Test
    void failsWhenTheQuotableItemHasNoStockRecord() {
        UUID channelId = insertChannel();
        bindChannelToLocation(channelId);
        enableFulfillmentMode(channelId, "PICKUP");
        UUID variantId = insertProductAndVariant("BURGER");
        insertPublication();
        insertLocationOffering(variantId);
        seedPricing(variantId);
        // Deliberately no inventory.stock_items row: the item is priceable and
        // published, but nobody has ever told inventory it exists here.

        StepResult result = handler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("ITEM_NOT_AVAILABLE_TO_SELL");
        assertThat(result.detail())
                .as("an operator reading this must be told which item, not just that onboarding failed")
                .contains("SKU-BURGER")
                .contains("NOT_STOCKED_AT_LOCATION");
    }

    /** The other direction: sold out is refused the same way an absent stock row is. */
    @Test
    void failsWhenTheQuotableItemIsMarkedSoldOut() {
        UUID channelId = insertChannel();
        bindChannelToLocation(channelId);
        enableFulfillmentMode(channelId, "PICKUP");
        UUID variantId = insertProductAndVariant("BURGER");
        insertPublication();
        insertLocationOffering(variantId);
        seedPricing(variantId);
        UUID stockItemId =
                inventory().listVariantAtLocation(tenantId, brandId, locationId, variantId, TrackingMode.BINARY);
        inventory().setAvailability(tenantId, locationId, variantId, false, "onboarding-test-sold-out", null);
        assertThat(stockItemId).isNotNull();

        StepResult result = handler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.FAILED);
        assertThat(result.errorCode()).isEqualTo("ITEM_NOT_AVAILABLE_TO_SELL");
        assertThat(result.detail()).contains("SOLD_OUT");
    }

    @Test
    void passesWhenServiceabilityAQuoteAndInventoryAllResolve() {
        UUID channelId = insertChannel();
        bindChannelToLocation(channelId);
        enableFulfillmentMode(channelId, "PICKUP");
        UUID variantId = insertProductAndVariant("BURGER");
        insertPublication();
        insertLocationOffering(variantId);
        seedPricing(variantId);
        inventory().listVariantAtLocation(tenantId, brandId, locationId, variantId, TrackingMode.BINARY);

        StepResult result = handler().execute(context());

        assertThat(result.outcome()).isEqualTo(StepResult.Outcome.COMPLETED);
    }

    private InventoryService inventory() {
        return new InventoryService(new JdbcInventoryStore(jdbc), CLOCK);
    }

    private OrderingOnboardingStepHandlers.ActivationSmokeTest handler() {
        var channels = new JdbcSalesChannelStore(jdbc);
        var serviceability = new ServiceabilityService(new JdbcServiceabilityStore(jdbc), CLOCK);
        var deliveryFees = new DeliveryFeeResolver(
                new JdbcServiceZoneStore(jdbc),
                new JdbcDeliveryTariffStore(jdbc),
                new JdbcDeliveryFeeResolutionStore(jdbc, JsonMapper.builder().build()),
                (origin, destination, installationId) -> java.util.Optional.empty(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var pricing = new QuoteService(
                new JdbcPricingStore(jdbc, JsonMapper.builder().build()),
                new PricingEngine(),
                new JdbcCatalogPricingContext(jdbc, "uz"),
                channels,
                deliveryFees,
                CLOCK);
        return new OrderingOnboardingStepHandlers.ActivationSmokeTest(
                jdbc, channels, serviceability, pricing, inventory(), CLOCK);
    }

    private OnboardingStepHandler.StepContext context() {
        return new OnboardingStepHandler.StepContext(UUID.randomUUID(), tenantId, Map.of(), null, 1);
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "t-" + tenantId.toString().substring(0, 8))
                .update();
    }

    private void insertBrand() {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("slug", "b-" + brandId.toString().substring(0, 8))
                .update();
    }

    private void insertLocation() {
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', :slug, 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("slug", "l-" + locationId.toString().substring(0, 8))
                .update();
    }

    private UUID insertChannel() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, :code, 'WEB', :code, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", CHANNEL_CODE)
                .update();
        return id;
    }

    private void bindChannelToLocation(UUID channelId) {
        jdbc.sql("""
                INSERT INTO tenant.sales_channel_locations (tenant_id, channel_id, location_id, status)
                VALUES (:tenantId, :channelId, :locationId, 'ACTIVE')
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("locationId", locationId)
                .update();
    }

    private void enableFulfillmentMode(UUID channelId, String mode) {
        jdbc.sql("""
                INSERT INTO tenant.channel_fulfillment_modes (tenant_id, channel_id, fulfillment_mode, enabled)
                VALUES (:tenantId, :channelId, :mode, true)
                """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("mode", mode)
                .update();
    }

    private UUID insertCatalog() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();
        return id;
    }

    private UUID insertProductAndVariant(String code) {
        UUID productId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .update();
        UUID variantId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, status)
                VALUES (:id, :tenantId, :brandId, :productId, :sku, 'ACTIVE')
                """)
                .param("id", variantId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .param("sku", "SKU-" + code)
                .update();
        return variantId;
    }

    private void insertPublication() {
        jdbc.sql("""
                INSERT INTO catalog.publications
                    (id, tenant_id, brand_id, catalog_id, channel, status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, :channel, 'PUBLISHED', 'hash', now())
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalogId)
                .param("channel", CHANNEL_CODE)
                .update();
    }

    private void insertLocationOffering(UUID variantId) {
        jdbc.sql("""
                INSERT INTO catalog.location_offerings (id, tenant_id, brand_id, location_id, variant_id, status)
                VALUES (:id, :tenantId, :brandId, :locationId, :variantId, 'AVAILABLE')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("variantId", variantId)
                .update();
    }

    private void seedPricing(UUID variantId) {
        UUID priceBookId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO pricing.price_books (id, tenant_id, brand_id, name, currency, status, valid_from, priority)
                VALUES (:id, :tenantId, :brandId, 'BRAND_MENU', 'UZS', 'ACTIVE', :from, 0)
                """)
                .param("id", priceBookId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("from", java.time.OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                INSERT INTO pricing.price_book_assignments
                    (id, tenant_id, brand_id, price_book_id, scope_type, scope_id, valid_from, priority)
                VALUES (:id, :tenantId, :brandId, :priceBookId, 'BRAND', null, :from, 0)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("priceBookId", priceBookId)
                .param("from", java.time.OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                INSERT INTO pricing.prices
                    (id, tenant_id, brand_id, price_book_id, priceable_type, priceable_id, amount_minor, valid_from)
                VALUES (:id, :tenantId, :brandId, :priceBookId, 'VARIANT', :variantId, 50000, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("priceBookId", priceBookId)
                .param("variantId", variantId)
                .param("from", java.time.OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                INSERT INTO pricing.tax_profiles (id, tenant_id, brand_id, jurisdiction_code, mode,
                    rate_basis_points, valid_from)
                VALUES (:id, :tenantId, :brandId, 'UZ', 'INCLUSIVE', 1200, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("from", java.time.OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC))
                .update();
    }
}
