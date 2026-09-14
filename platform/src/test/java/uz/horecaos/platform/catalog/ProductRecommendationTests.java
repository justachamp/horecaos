package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
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
import uz.horecaos.platform.catalog.application.CatalogAuthoringService.ProductCreated;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore.RecommendationRow;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Row 4.2h: cross-sell, new to the gap map and unbuilt at every layer before
 * this wave. Directional attach/detach/reorder, and IA 4.2's own filter —
 * active + in-menu + not-stopped — resolved at read time, tested explicitly
 * for the stopped case per the gap map's own instruction: "the filter is the
 * row".
 */
class ProductRecommendationTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final String LOCALE = "uz";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final FiscalClassification UNCLASSIFIED = FiscalClassification.unclassified();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCatalogStore store;
    private CatalogAuthoringService authoring;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the product recommendation tests");
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
        jdbc.sql("TRUNCATE TABLE catalog.product_recommendations, catalog.location_offerings, "
                        + "catalog.translations, catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenancy(TENANT, BRAND, LOCATION, "product-recommendation-tenant", "MAIN");

        store = new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        CommercialDefaults.Wired commercial = CommercialDefaults.wire(jdbc, Clock.systemUTC());
        authoring = new CatalogAuthoringService(
                store,
                new uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder(
                        jdbc, JsonMapper.builder().build()),
                commercial.entitlements(),
                commercial.usage(),
                Clock.systemUTC());
    }

    @Test
    @DisplayName("attaching a target variant makes it appear in the source product's recommendation list")
    void attachingAddsToTheList() {
        ProductCreated burger = createProduct("BURGER", "Burger");
        ProductCreated fries = createProduct("FRIES", "Fries");

        authoring.attachRecommendation(TENANT, BRAND, burger.productId(), fries.defaultVariantId(), 0);

        assertThat(authoring.listRecommendations(TENANT, BRAND, burger.productId(), LOCALE))
                .extracting(RecommendationRow::targetVariantId)
                .containsExactly(fries.defaultVariantId());
    }

    @Test
    @DisplayName("a recommendation is directional: attaching burger -> fries does not attach fries -> burger")
    void aRecommendationIsDirectionalNotSymmetric() {
        ProductCreated burger = createProduct("BURGER", "Burger");
        ProductCreated fries = createProduct("FRIES", "Fries");

        authoring.attachRecommendation(TENANT, BRAND, burger.productId(), fries.defaultVariantId(), 0);

        assertThat(authoring.listRecommendations(TENANT, BRAND, fries.productId(), LOCALE))
                .isEmpty();
    }

    @Test
    @DisplayName("attaching an already-attached target re-sorts it rather than creating a second row")
    void reattachingReordersRatherThanDuplicating() {
        ProductCreated burger = createProduct("BURGER", "Burger");
        ProductCreated fries = createProduct("FRIES", "Fries");
        authoring.attachRecommendation(TENANT, BRAND, burger.productId(), fries.defaultVariantId(), 0);

        authoring.attachRecommendation(TENANT, BRAND, burger.productId(), fries.defaultVariantId(), 5);

        List<RecommendationRow> rows = authoring.listRecommendations(TENANT, BRAND, burger.productId(), LOCALE);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().sortOrder()).isEqualTo(5);
    }

    @Test
    @DisplayName("detaching removes the target, and detaching again is a no-op rather than an error")
    void detachingIsIdempotent() {
        ProductCreated burger = createProduct("BURGER", "Burger");
        ProductCreated fries = createProduct("FRIES", "Fries");
        authoring.attachRecommendation(TENANT, BRAND, burger.productId(), fries.defaultVariantId(), 0);

        authoring.detachRecommendation(TENANT, BRAND, burger.productId(), fries.defaultVariantId());
        authoring.detachRecommendation(TENANT, BRAND, burger.productId(), fries.defaultVariantId());

        assertThat(authoring.listRecommendations(TENANT, BRAND, burger.productId(), LOCALE))
                .isEmpty();
    }

    @Test
    @DisplayName("a product cannot recommend one of its own variants")
    void aProductCannotRecommendItsOwnVariant() {
        ProductCreated burger = createProduct("BURGER", "Burger");

        assertThatThrownBy(() ->
                        authoring.attachRecommendation(TENANT, BRAND, burger.productId(), burger.defaultVariantId(), 0))
                .isInstanceOf(CatalogAuthoringService.SelfRecommendationException.class);
    }

    @Test
    @DisplayName("attaching a variant this brand does not have is refused")
    void attachingAnUnknownVariantIsRefused() {
        ProductCreated burger = createProduct("BURGER", "Burger");

        assertThatThrownBy(
                        () -> authoring.attachRecommendation(TENANT, BRAND, burger.productId(), UUID.randomUUID(), 0))
                .isInstanceOf(CatalogAuthoringService.UnknownCatalogEntityException.class);
    }

    // ----------------------------------------------- IA 4.2's filter: active + in-menu + not-stopped

    @Test
    @DisplayName("a target with no offering at this location at all is excluded — never added, not on this menu")
    void aTargetNeverOfferedHereIsExcludedFromTheResolvedList() {
        ProductCreated burger = createProduct("BURGER", "Burger");
        ProductCreated fries = createProduct("FRIES", "Fries");
        authoring.attachRecommendation(TENANT, BRAND, burger.productId(), fries.defaultVariantId(), 0);

        assertThat(authoring.resolvedRecommendations(TENANT, BRAND, burger.productId(), LOCATION, LOCALE))
                .isEmpty();
    }

    @Test
    @DisplayName("a target that is active, in-menu and not stopped appears in the resolved list")
    void anEligibleTargetAppearsInTheResolvedList() {
        ProductCreated burger = createProduct("BURGER", "Burger");
        ProductCreated fries = createProduct("FRIES", "Fries");
        authoring.attachRecommendation(TENANT, BRAND, burger.productId(), fries.defaultVariantId(), 0);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, fries.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        assertThat(authoring.resolvedRecommendations(TENANT, BRAND, burger.productId(), LOCATION, LOCALE))
                .extracting(RecommendationRow::targetVariantId)
                .containsExactly(fries.defaultVariantId());
    }

    @Test
    @DisplayName(
            "the filter is the row: a stopped target is excluded, and un-stopping it restores the recommendation on its own")
    void aStoppedTargetIsExcludedAndReappearsWhenUnStopped() {
        ProductCreated burger = createProduct("BURGER", "Burger");
        ProductCreated fries = createProduct("FRIES", "Fries");
        authoring.attachRecommendation(TENANT, BRAND, burger.productId(), fries.defaultVariantId(), 0);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, fries.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        assertThat(authoring.resolvedRecommendations(TENANT, BRAND, burger.productId(), LOCATION, LOCALE))
                .as("sanity check: eligible before being stopped")
                .isNotEmpty();

        // catalog.menus.status.UNAVAILABLE reads "Stopped" to an operator — the
        // exact same column this filter reads as "not-stopped".
        authoring.setOffering(
                TENANT, BRAND, LOCATION, fries.defaultVariantId(), OfferingStatus.UNAVAILABLE, List.of("DELIVERY"));
        assertThat(authoring.resolvedRecommendations(TENANT, BRAND, burger.productId(), LOCATION, LOCALE))
                .as("stopped: excluded even though the attachment itself was never touched")
                .isEmpty();
        // Nothing was pruned from the stored, unfiltered set — the attachment
        // survives the stop.
        assertThat(authoring.listRecommendations(TENANT, BRAND, burger.productId(), LOCALE))
                .hasSize(1);

        authoring.setOffering(
                TENANT, BRAND, LOCATION, fries.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        assertThat(authoring.resolvedRecommendations(TENANT, BRAND, burger.productId(), LOCATION, LOCALE))
                .as("un-stopped: the recommendation reappears on its own, resolved fresh rather than restored by hand")
                .extracting(RecommendationRow::targetVariantId)
                .containsExactly(fries.defaultVariantId());
    }

    @Test
    @DisplayName("an archived target is excluded even while it is still offered and not stopped")
    void anArchivedTargetIsExcluded() {
        ProductCreated burger = createProduct("BURGER", "Burger");
        ProductCreated fries = createProduct("FRIES", "Fries");
        authoring.attachRecommendation(TENANT, BRAND, burger.productId(), fries.defaultVariantId(), 0);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, fries.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        authoring.setProductStatus(TENANT, BRAND, fries.productId(), Status.ARCHIVED);

        assertThat(authoring.resolvedRecommendations(TENANT, BRAND, burger.productId(), LOCATION, LOCALE))
                .isEmpty();
    }

    private ProductCreated createProduct(String code, String name) {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy", LOCALE);
        return authoring.createProduct(
                TENANT, BRAND, catalogId, code, name, null, LOCALE, "SKU-" + code, "PIECE", UNCLASSIFIED, ACTOR);
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
                .param("slug", brandCode.toLowerCase(Locale.ROOT))
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
