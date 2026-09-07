package uz.horecaos.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.catalog.api.MenuPriceLookup;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0036's {@code catalog.channel_offering_exclusions}: a table with its
 * constraints and, until V0175, no reader anywhere in the codebase — "a table
 * nothing reads is a rule nobody enforces." This exercises the reader added to
 * {@link JdbcCatalogStore} and its wiring into
 * {@link StorefrontCatalogQuery#menuFor}, the live storefront menu path that is
 * this repository's own answer to "is this offered on this channel".
 *
 * <p>Chosen home: {@code tenancy}, not {@code catalog}. The reader itself lives
 * in {@code catalog} because the table does (ADR 0036's own physical model:
 * "Per-channel item suppression sits in catalog, because it references a
 * variant under the composite brand key ADR 0016 enforces"), but this wave's
 * assignment is scoped to {@code tenancy}'s sales-channel classes and this
 * exclusion mechanism is a channel concept through and through — every row
 * names a {@code channel_id}, and the test that matters is whether a channel's
 * exclusion actually changes what that channel's menu shows.
 */
class ChannelOfferingExclusionsReaderTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();
    private static final UUID LOCATION_1 = UUID.randomUUID();
    private static final UUID LOCATION_2 = UUID.randomUUID();
    private static final UUID OTHER_LOCATION = UUID.randomUUID();
    private static final UUID CHANNEL = UUID.randomUUID();
    private static final UUID OTHER_CHANNEL = UUID.randomUUID();
    private static final String CHANNEL_CODE = "STOREFRONT";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCatalogStore catalogStore;
    private StorefrontCatalogQuery menu;

    private UUID variant1;
    private UUID variant2;
    private UUID variant3;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the exclusions reader test");
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
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, "
                        + "catalog.channel_offering_exclusions, catalog.location_offerings, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        ObjectMapper objectMapper = JsonMapper.builder().build();
        catalogStore = new JdbcCatalogStore(jdbc, objectMapper);
        MenuPriceLookup noPrices =
                (tenantId, brandId, locationId, channelCode, variantIds, optionIds) -> Optional.empty();
        menu = new StorefrontCatalogQuery(catalogStore, noPrices);

        seedTenancy();
        variant1 = seedProductAndVariant("BURGER");
        variant2 = seedProductAndVariant("PIZZA");
        variant3 = seedProductAndVariant("SALAD");
        for (UUID variantId : new UUID[] {variant1, variant2, variant3}) {
            offerAt(LOCATION_1, variantId);
            offerAt(LOCATION_2, variantId);
        }

        // Brand-wide: excludes variant1 on this channel at every one of the
        // brand's locations.
        excludeVariant(BRAND, CHANNEL, variant1, null, "SEASONAL");
        // Location-specific: excludes variant2 on this channel, but only at
        // LOCATION_1 -- LOCATION_2 must still offer it.
        excludeVariant(BRAND, CHANNEL, variant2, LOCATION_1, "OUT_OF_STOCK_HERE");
        // variant3 is never excluded anywhere; it is the negative control for
        // "not excluding what it should not".
    }

    // --------------------------------------------------------- the reader itself

    @Test
    @DisplayName("a brand-wide exclusion hides the variant at every location on that channel")
    void brandWideExclusionAppliesEverywhere() {
        assertThat(catalogStore.channelExcludedVariantIds(TENANT, BRAND, CHANNEL_CODE, LOCATION_1))
                .contains(variant1);
        assertThat(catalogStore.channelExcludedVariantIds(TENANT, BRAND, CHANNEL_CODE, LOCATION_2))
                .as("location_id IS NULL means brand-wide, not location_1-only")
                .contains(variant1);
    }

    @Test
    @DisplayName("a location-scoped exclusion hides the variant only at that location")
    void locationScopedExclusionIsScoped() {
        assertThat(catalogStore.channelExcludedVariantIds(TENANT, BRAND, CHANNEL_CODE, LOCATION_1))
                .contains(variant2);
        assertThat(catalogStore.channelExcludedVariantIds(TENANT, BRAND, CHANNEL_CODE, LOCATION_2))
                .as("the exclusion named LOCATION_1 only; LOCATION_2 must still offer variant2")
                .doesNotContain(variant2);
    }

    @Test
    @DisplayName("a variant with no exclusion row is never reported excluded")
    void unrelatedVariantIsNotExcluded() {
        assertThat(catalogStore.channelExcludedVariantIds(TENANT, BRAND, CHANNEL_CODE, LOCATION_1))
                .as("variant3 has no exclusion row anywhere")
                .doesNotContain(variant3);
        assertThat(catalogStore.channelExcludedVariantIds(TENANT, BRAND, CHANNEL_CODE, LOCATION_2))
                .doesNotContain(variant3);
    }

    @Test
    @DisplayName("a different channel's exclusions do not leak onto this one")
    void exclusionsDoNotCrossChannels() {
        UUID otherChannelInSameBrand = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'KIOSK', 'KIOSK', 'Kiosk', 'ACTIVE')
                """)
                .param("id", otherChannelInSameBrand)
                .param("tenantId", TENANT)
                .update();

        assertThat(catalogStore.channelExcludedVariantIds(TENANT, BRAND, "KIOSK", LOCATION_1))
                .as("STOREFRONT's exclusions must not apply to a channel that never excluded anything")
                .isEmpty();
    }

    @Test
    @DisplayName("another tenant's exclusions are never visible to this tenant's read")
    void tenantIsolation() {
        seedOtherTenant();
        UUID otherVariant = seedProductAndVariantFor(OTHER_TENANT, OTHER_BRAND, "OTHER_DISH");
        offerAtFor(OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, otherVariant);
        // Same channel CODE as this tenant's own -- the adversarial case for a
        // query that joined on code without also filtering by tenant_id.
        excludeVariantFor(OTHER_TENANT, OTHER_BRAND, OTHER_CHANNEL, otherVariant, null, "OTHER_TENANT_REASON");

        Set<UUID> mineAtLocation1 = catalogStore.channelExcludedVariantIds(TENANT, BRAND, CHANNEL_CODE, LOCATION_1);
        assertThat(mineAtLocation1)
                .as("the other tenant's excluded variant id must never appear here")
                .doesNotContain(otherVariant);
        assertThat(mineAtLocation1).containsExactlyInAnyOrder(variant1, variant2);

        Set<UUID> theirs =
                catalogStore.channelExcludedVariantIds(OTHER_TENANT, OTHER_BRAND, CHANNEL_CODE, OTHER_LOCATION);
        assertThat(theirs).containsExactly(otherVariant);
    }

    // ------------------------------------------------------ wired into the menu

    @Test
    @DisplayName("an excluded variant's product does not appear on the live channel menu")
    void excludedVariantIsAbsentFromTheStorefrontMenu() {
        publish();

        StorefrontCatalogQuery.StorefrontMenu locationOne =
                menu.menuFor(TENANT, BRAND, LOCATION_1, "en", CHANNEL_CODE).orElseThrow();

        assertThat(locationOne.products())
                .as("variant1 (brand-wide exclusion) and variant2 (excluded at LOCATION_1) are both absent")
                .extracting(StorefrontCatalogQuery.MenuProduct::code)
                .containsExactly("SALAD");

        StorefrontCatalogQuery.StorefrontMenu locationTwo =
                menu.menuFor(TENANT, BRAND, LOCATION_2, "en", CHANNEL_CODE).orElseThrow();

        assertThat(locationTwo.products())
                .as("variant2's exclusion named LOCATION_1 only, so LOCATION_2 still sells PIZZA; "
                        + "variant1's brand-wide exclusion still applies here")
                .extracting(StorefrontCatalogQuery.MenuProduct::code)
                .containsExactlyInAnyOrder("PIZZA", "SALAD");
    }

    // ---------------------------------------------------------------- fixtures

    private void seedTenancy() {
        insertTenant(TENANT, "exclusion-tenant");
        insertBrand(BRAND, TENANT, "MAIN", "main");
        insertLocation(LOCATION_1, TENANT, BRAND, "MAIN01", "main-01");
        insertLocation(LOCATION_2, TENANT, BRAND, "MAIN02", "main-02");
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, :code, 'WEB', 'Storefront', 'ACTIVE')
                """)
                .param("id", CHANNEL)
                .param("tenantId", TENANT)
                .param("code", CHANNEL_CODE)
                .update();
    }

    private void seedOtherTenant() {
        insertTenant(OTHER_TENANT, "exclusion-other-tenant");
        insertBrand(OTHER_BRAND, OTHER_TENANT, "MAIN", "main");
        insertLocation(OTHER_LOCATION, OTHER_TENANT, OTHER_BRAND, "MAIN01", "main-01");
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, :code, 'WEB', 'Storefront', 'ACTIVE')
                """)
                .param("id", OTHER_CHANNEL)
                .param("tenantId", OTHER_TENANT)
                .param("code", CHANNEL_CODE)
                .update();
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void insertBrand(UUID id, UUID tenantId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private void insertLocation(UUID id, UUID tenantId, UUID brandId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private UUID seedProductAndVariant(String code) {
        return seedProductAndVariantFor(TENANT, BRAND, code);
    }

    private UUID seedProductAndVariantFor(UUID tenantId, UUID brandId, String code) {
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
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, is_default, status)
                VALUES (:id, :tenantId, :brandId, :productId, true, 'ACTIVE')
                """)
                .param("id", variantId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .update();
        return variantId;
    }

    private void offerAt(UUID locationId, UUID variantId) {
        offerAtFor(TENANT, BRAND, locationId, variantId);
    }

    private void offerAtFor(UUID tenantId, UUID brandId, UUID locationId, UUID variantId) {
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

    private void excludeVariant(
            UUID brandId, UUID channelId, UUID variantId, @Nullable UUID locationId, String reasonCode) {
        excludeVariantFor(TENANT, brandId, channelId, variantId, locationId, reasonCode);
    }

    private void excludeVariantFor(
            UUID tenantId, UUID brandId, UUID channelId, UUID variantId, @Nullable UUID locationId, String reasonCode) {
        jdbc.sql("""
                INSERT INTO catalog.channel_offering_exclusions (
                    id, tenant_id, brand_id, location_id, variant_id, channel_id, reason_code)
                VALUES (:id, :tenantId, :brandId, :locationId, :variantId, :channelId, :reasonCode)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("variantId", variantId)
                .param("channelId", channelId)
                .param("reasonCode", reasonCode)
                .update();
    }

    /** A minimal live publication naming all three products, exactly as {@code menuFor} reads them. */
    private void publish() {
        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        UUID publicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, :channel, 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .param("channel", CHANNEL_CODE)
                .update();

        publishItem(publicationId, "BURGER", variant1);
        publishItem(publicationId, "PIZZA", variant2);
        publishItem(publicationId, "SALAD", variant3);
    }

    private void publishItem(UUID publicationId, String code, UUID variantId) {
        UUID productId = UUID.randomUUID();
        Map<String, Object> content = Map.of(
                "code",
                code,
                "names",
                Map.of("en", Map.of("name", code)),
                "variants",
                java.util.List.of(Map.of("variantId", variantId.toString(), "isDefault", true)));
        jdbc.sql("""
                INSERT INTO catalog.publication_items (
                    publication_id, tenant_id, brand_id, entity_type, entity_id, entity_version,
                    immutable_content_json)
                VALUES (:publicationId, :tenantId, :brandId, 'PRODUCT', :entityId, 1, :content::jsonb)
                """)
                .param("publicationId", publicationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("entityId", productId)
                .param("content", writeJson(content))
                .update();
    }

    private static String writeJson(Map<String, Object> content) {
        return JsonMapper.builder().build().writeValueAsString(content);
    }
}
