package uz.horecaos.platform.catalog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Rows 4.4c/4.4d (storefront half), end to end through the real Spring
 * context: the published menu HTTP read ({@code StorefrontCatalogController})
 * and the storefront's own per-variant read ({@code
 * StorefrontInventoryController}) had zero HTTP-level coverage before this —
 * every existing assertion about either exercised the application layer
 * directly ({@code StorefrontCatalogQueryTests}) and never proved the real
 * {@code InventoryMenuAvailabilityLookup} bean Spring actually wires in is
 * the one {@code StorefrontCatalogQuery} gets, nor that the JSON a browser
 * receives actually carries {@code orderable}/{@code remainingQuantity}.
 *
 * <p>Unauthenticated by design (both controllers' own class docs), so no JWT
 * is needed here, unlike {@code CatalogAuthoringControllerEndpointTests}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorefrontCatalogControllerEndpointTests {

    // A fresh id per test (not static), assigned in setUp() below — never a
    // constant shared class-wide. ADR 0033 caches a resolved
    // catalog.use_stock_logic under tenant.configuration for up to its
    // registry TTL; a tenant id reused across test methods (as a static
    // field would) risks one test's resolution — often the compiled-in
    // "false" default, resolved before that test's own row exists — being
    // served back to a later test that just wrote "true" for the same
    // tenant, no eviction in between. A fresh tenant id per test can never
    // collide with a stale cache entry from another one.
    private UUID tenant;
    private UUID brand;
    private UUID location;
    private UUID catalogId;

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the storefront catalog/inventory endpoint tests");
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
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
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private InventoryService inventory;

    @BeforeEach
    void setUp() {
        tenant = UUID.randomUUID();
        brand = UUID.randomUUID();
        location = UUID.randomUUID();
        catalogId = UUID.randomUUID();

        jdbc.sql("TRUNCATE TABLE inventory.reservation_lines, inventory.reservations, "
                        + "inventory.movements, inventory.positions, inventory.stock_items, "
                        + "inventory.channel_stop_thresholds CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, "
                        + "catalog.location_offerings, catalog.variants, catalog.products, "
                        + "catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'storefront-inventory', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenant).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Main brand', 'ACTIVE', 0)
                """).param("id", brand).param("tenantId", tenant).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'main', 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", location)
                .param("tenantId", tenant)
                .param("brandId", brand)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE')
                """).param("id", UUID.randomUUID()).param("tenantId", tenant).update();

        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", tenant)
                .param("brandId", brand)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash', now())
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenant)
                .param("brandId", brand)
                .param("catalogId", catalogId)
                .update();
    }

    // -------------------------------------------------------- /menu (rows 4.4c/4.4d)

    @Test
    void aBinaryItemTheKitchenHasStoppedIsShownButNotOrderableOnTheMenu() throws Exception {
        UUID variantId = publishOneProduct("BURGER");
        inventory.listVariantAtLocation(tenant, brand, location, variantId, TrackingMode.BINARY);
        inventory.setAvailability(tenant, location, variantId, false, "SOLD_OUT", null);

        mvc.perform(menuGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].variants[0].orderable").value(false))
                .andExpect(
                        jsonPath("$.products[0].variants[0].remainingQuantity").doesNotExist());
    }

    @Test
    void aQuantityItemAtZeroIsNotOrderable() throws Exception {
        UUID variantId = publishOneProduct("BURGER");
        turnOnStockLogic();
        inventory.listVariantAtLocation(tenant, brand, location, variantId, TrackingMode.QUANTITY);
        inventory.setOnHandQuantity(tenant, location, variantId, BigDecimal.ZERO, "test", "tester");

        mvc.perform(menuGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].variants[0].orderable").value(false));
    }

    @Test
    void aQuantityItemWellBelowTheDisplayThresholdShowsTheRemainingCount() throws Exception {
        UUID variantId = publishOneProduct("BURGER");
        turnOnStockLogic();
        inventory.listVariantAtLocation(tenant, brand, location, variantId, TrackingMode.QUANTITY);
        inventory.setOnHandQuantity(tenant, location, variantId, BigDecimal.valueOf(2), "test", "tester");

        mvc.perform(menuGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].variants[0].orderable").value(true))
                .andExpect(
                        jsonPath("$.products[0].variants[0].remainingQuantity").value(comparesEqualTo(2.0)));
    }

    @Test
    void aQuantityItemWellAboveTheDisplayThresholdNeverLeaksTheCount() throws Exception {
        UUID variantId = publishOneProduct("BURGER");
        turnOnStockLogic();
        inventory.listVariantAtLocation(tenant, brand, location, variantId, TrackingMode.QUANTITY);
        inventory.setOnHandQuantity(tenant, location, variantId, BigDecimal.valueOf(500), "test", "tester");

        mvc.perform(menuGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].variants[0].orderable").value(true))
                .andExpect(
                        jsonPath("$.products[0].variants[0].remainingQuantity").doesNotExist());
    }

    @Test
    void anAvailableItemIsUnaffectedByTheNewInventoryRead() throws Exception {
        UUID variantId = publishOneProduct("BURGER");
        inventory.listVariantAtLocation(tenant, brand, location, variantId, TrackingMode.BINARY);

        mvc.perform(menuGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].variants[0].orderable").value(true));
    }

    /**
     * ADR 0033: before this wave the response's {@code ETag} was the
     * publication id alone, and {@code Cache-Control} was {@code
     * max-age=30, public} — a shared cache could replay a stale body for up
     * to 30 seconds without asking the origin anything, and even a
     * revalidating client would have gotten a matching, wrongly-cached
     * {@code ETag} back, because a stop changes nothing about the
     * publication. Both would have made a stop invisible for up to half a
     * minute. This proves the fix: the same publication, read again after a
     * stop, now carries a different {@code ETag}, and the cache directive no
     * longer permits a stale replay at all.
     */
    @Test
    void aStopChangesTheETagEvenThoughThePublicationDidNot() throws Exception {
        UUID variantId = publishOneProduct("BURGER");
        inventory.listVariantAtLocation(tenant, brand, location, variantId, TrackingMode.BINARY);

        String etagBeforeStop = mvc.perform(menuGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].variants[0].orderable").value(true))
                .andReturn()
                .getResponse()
                .getHeader("ETag");

        inventory.setAvailability(tenant, location, variantId, false, "SOLD_OUT", null);

        String etagAfterStop = mvc.perform(menuGet())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0].variants[0].orderable").value(false))
                .andReturn()
                .getResponse()
                .getHeader("ETag");

        assertThat(etagBeforeStop).isNotNull();
        assertThat(etagAfterStop).isNotNull().isNotEqualTo(etagBeforeStop);
    }

    @Test
    void theMenuNeverAdvertisesAMaxAgeThatWouldLetAStopGoUnseen() throws Exception {
        publishOneProduct("BURGER");

        String cacheControl = mvc.perform(menuGet())
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader("Cache-Control");

        assertThat(cacheControl).isNotNull().contains("no-cache").doesNotContain("max-age");
    }

    // ------------------------------------- /variants/{id}/availability (row 4.4c)

    @Test
    void theSingleVariantAvailabilityEndpointReportsAvailableAndRemaining() throws Exception {
        UUID variantId = publishOneProduct("BURGER");
        turnOnStockLogic();
        inventory.listVariantAtLocation(tenant, brand, location, variantId, TrackingMode.QUANTITY);
        inventory.setOnHandQuantity(tenant, location, variantId, BigDecimal.valueOf(3), "test", "tester");

        mvc.perform(get("/api/v1/storefront/tenants/%s/locations/%s/variants/%s/availability"
                                .formatted(tenant, location, variantId))
                        .queryParam("channel", "WEB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.remainingQuantity").value(comparesEqualTo(3.0)));
    }

    @Test
    void theSingleVariantAvailabilityEndpointReportsUnavailableForASoldOutItem() throws Exception {
        UUID variantId = publishOneProduct("BURGER");
        inventory.listVariantAtLocation(tenant, brand, location, variantId, TrackingMode.BINARY);
        inventory.setAvailability(tenant, location, variantId, false, "SOLD_OUT", null);

        mvc.perform(get("/api/v1/storefront/tenants/%s/locations/%s/variants/%s/availability"
                                .formatted(tenant, location, variantId))
                        .queryParam("channel", "WEB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.remainingQuantity").doesNotExist());
    }

    // --------------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder menuGet() {
        return get("/api/v1/storefront/tenants/%s/brands/%s/locations/%s/menu".formatted(tenant, brand, location))
                .queryParam("channel", "STOREFRONT");
    }

    /** One product, one variant, offered and published — the smallest real menu. */
    private UUID publishOneProduct(String code) {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", tenant)
                .param("brandId", brand)
                .param("code", code)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, status)
                VALUES (:id, :tenantId, :brandId, :productId, :sku, 'ACTIVE')
                """)
                .param("id", variantId)
                .param("tenantId", tenant)
                .param("brandId", brand)
                .param("productId", productId)
                .param("sku", "SKU-" + code)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.location_offerings (id, tenant_id, brand_id, location_id, variant_id, status)
                VALUES (:id, :tenantId, :brandId, :locationId, :variantId, 'AVAILABLE')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenant)
                .param("brandId", brand)
                .param("locationId", location)
                .param("variantId", variantId)
                .update();
        UUID publicationId = jdbc.sql(
                        "SELECT id FROM catalog.publications WHERE tenant_id = :tenantId AND channel = 'STOREFRONT'")
                .param("tenantId", tenant)
                .query(UUID.class)
                .single();
        jdbc.sql("""
                INSERT INTO catalog.publication_items (publication_id, tenant_id, brand_id,
                    entity_type, entity_id, entity_version, immutable_content_json)
                VALUES (:publicationId, :tenantId, :brandId, 'PRODUCT', :entityId, 1, CAST(:content AS jsonb))
                """)
                .param("publicationId", publicationId)
                .param("tenantId", tenant)
                .param("brandId", brand)
                .param("entityId", productId)
                .param("content", """
                        {"code": "%s", "variants": [{"variantId": "%s"}]}
                        """.formatted(code, variantId))
                .update();
        return variantId;
    }

    private void turnOnStockLogic() {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values
                    (id, key_code, scope_type, tenant_id, value_type, boolean_value, set_by)
                VALUES (:id, 'catalog.use_stock_logic', 'TENANT', :tenantId, 'BOOLEAN', true, 'test')
                """).param("id", UUID.randomUUID()).param("tenantId", tenant).update();
    }
}
