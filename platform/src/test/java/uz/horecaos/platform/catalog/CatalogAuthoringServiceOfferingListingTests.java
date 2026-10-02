package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import uz.horecaos.platform.catalog.api.ChannelAssortmentChanged;
import uz.horecaos.platform.catalog.api.OfferingBecameAvailable;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@link OfferingBecameAvailable} is published exactly where gap-map row
 * 4.1's job description names: both {@code setOffering} overloads, {@code
 * offerIfAbsent} only on an actual create, and {@code bulkSetOfferingStatus}
 * once per variant — always for {@code AVAILABLE}, never for {@code
 * UNAVAILABLE}/{@code HIDDEN}. The consumer side ({@code
 * inventory.application.CatalogOfferingListingTrigger} turning the event into
 * a listed stock item over a real {@code AFTER_COMMIT} transaction boundary)
 * is proved separately by {@code
 * uz.horecaos.platform.catalog.application.CatalogOfferingAutoListingIntegrationTests},
 * which needs a real Spring context; this suite stays a fast, direct-construction
 * unit test the same shape as {@code CatalogAuthoringServiceP21Tests}, with a
 * capturing fake in place of Spring's own event publisher.
 */
class CatalogAuthoringServiceOfferingListingTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCatalogStore store;
    private CatalogAuthoringService authoring;
    private final List<OfferingBecameAvailable> published = new ArrayList<>();
    private final List<ChannelAssortmentChanged> assortment = new ArrayList<>();

    private UUID variantA;
    private UUID variantB;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the catalog tests");
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
        jdbc.sql(
                        "TRUNCATE TABLE catalog.channel_offering_exclusions, catalog.location_offerings, catalog.variants, catalog.products CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.locations, tenant.tenants CASCADE").update();

        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'offering-listing-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
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

        store = new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        variantA = createVariant("BURGER-A", "SKU-A");
        variantB = createVariant("BURGER-B", "SKU-B");

        CommercialDefaults.Wired commercial = CommercialDefaults.wire(jdbc, Clock.systemUTC());
        published.clear();
        assortment.clear();
        authoring = new CatalogAuthoringService(
                store,
                fact -> {},
                commercial.entitlements(),
                commercial.usage(),
                Clock.systemUTC(),
                (tenantId, locationId) -> Optional.empty(),
                event -> {
                    if (event instanceof OfferingBecameAvailable offering) {
                        published.add(offering);
                    }
                    if (event instanceof ChannelAssortmentChanged changed) {
                        assortment.add(changed);
                    }
                });
    }

    @Test
    @DisplayName("setOffering(AVAILABLE) publishes OfferingBecameAvailable")
    void setOfferingAvailablePublishes() {
        authoring.setOffering(TENANT, BRAND, LOCATION, variantA, OfferingStatus.AVAILABLE, List.of("PICKUP"));

        assertThat(published).hasSize(1);
        OfferingBecameAvailable event = published.get(0);
        assertThat(event.tenantId()).isEqualTo(TENANT);
        assertThat(event.brandId()).isEqualTo(BRAND);
        assertThat(event.locationId()).isEqualTo(LOCATION);
        assertThat(event.variantId()).isEqualTo(variantA);
    }

    @Test
    @DisplayName("setOffering(UNAVAILABLE)/(HIDDEN) never publish")
    void setOfferingNotAvailableNeverPublishes() {
        authoring.setOffering(TENANT, BRAND, LOCATION, variantA, OfferingStatus.UNAVAILABLE, List.of("PICKUP"));
        authoring.setOffering(TENANT, BRAND, LOCATION, variantB, OfferingStatus.HIDDEN, List.of("PICKUP"));

        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("the audited setOffering(actorSubject) overload also publishes")
    void auditedSetOfferingAlsoPublishes() {
        authoring.setOffering(
                TENANT, BRAND, LOCATION, variantA, OfferingStatus.AVAILABLE, List.of("PICKUP"), "operator-1");

        assertThat(published).hasSize(1);
    }

    @Test
    @DisplayName("offerIfAbsent publishes only on an actual create")
    void offerIfAbsentPublishesOnlyOnCreate() {
        boolean created =
                authoring.offerIfAbsent(TENANT, BRAND, LOCATION, variantA, OfferingStatus.AVAILABLE, List.of("PICKUP"));
        assertThat(created).isTrue();
        assertThat(published).hasSize(1);

        published.clear();
        boolean createdAgain =
                authoring.offerIfAbsent(TENANT, BRAND, LOCATION, variantA, OfferingStatus.AVAILABLE, List.of("PICKUP"));
        assertThat(createdAgain)
                .as("offerIfAbsent's own create-only contract leaves an existing row alone")
                .isFalse();
        assertThat(published).as("no publish for a call that changed nothing").isEmpty();
    }

    @Test
    @DisplayName("bulkSetOfferingStatus publishes once per variant moved to AVAILABLE")
    void bulkSetOfferingStatusPublishesPerVariant() {
        int changed = authoring.bulkSetOfferingStatus(
                TENANT, BRAND, LOCATION, List.of(variantA, variantB), OfferingStatus.AVAILABLE, "operator-1");

        assertThat(changed).isEqualTo(2);
        assertThat(published)
                .extracting(OfferingBecameAvailable::variantId)
                .containsExactlyInAnyOrder(variantA, variantB);
    }

    @Test
    @DisplayName("bulkSetOfferingStatus(UNAVAILABLE) publishes nothing")
    void bulkSetOfferingStatusUnavailablePublishesNothing() {
        authoring.bulkSetOfferingStatus(
                TENANT, BRAND, LOCATION, List.of(variantA, variantB), OfferingStatus.UNAVAILABLE, "operator-1");

        assertThat(published).isEmpty();
    }

    // ------------------------------------------------- ADR 0141: the marketplace reconciler's dirty marker

    @Test
    @DisplayName("every offering write tells the reconciler the branch's assortment moved, whatever the status")
    void offeringWritesPublishAnAssortmentMarker() {
        authoring.setOffering(TENANT, BRAND, LOCATION, variantA, OfferingStatus.UNAVAILABLE, List.of("PICKUP"));

        assertThat(published).as("UNAVAILABLE lists nothing").isEmpty();
        assertThat(assortment)
                .as("but it takes a dish off a marketplace, which is the reconciler's business")
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.tenantId()).isEqualTo(TENANT);
                    assertThat(event.brandId()).isEqualTo(BRAND);
                    assertThat(event.locationId()).isEqualTo(LOCATION);
                });
    }

    @Test
    @DisplayName("a bulk offering change publishes one marker, not one per variant")
    void bulkOfferingChangePublishesOneMarker() {
        authoring.bulkSetOfferingStatus(
                TENANT, BRAND, LOCATION, List.of(variantA, variantB), OfferingStatus.UNAVAILABLE, "operator-1");

        assertThat(assortment).hasSize(1);
    }

    @Test
    @DisplayName(
            "a channel exclusion added or removed publishes a marker, and a call that changed nothing publishes none")
    void channelExclusionsPublishAMarkerOnlyWhenTheyChangeSomething() {
        UUID channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name)
                VALUES (:id, :tenantId, 'EATS', 'AGGREGATOR', 'Eats')
                """).param("id", channelId).param("tenantId", TENANT).update();

        authoring.setChannelOffering(TENANT, BRAND, channelId, variantA, null, false, "NOT_ON_PARTNER", "operator-1");
        assertThat(assortment)
                .singleElement()
                .satisfies(event -> assertThat(event.locationId()).isNull());

        assortment.clear();
        authoring.setChannelOffering(TENANT, BRAND, channelId, variantA, null, false, "NOT_ON_PARTNER", "operator-1");
        assertThat(assortment).as("already excluded: nothing moved").isEmpty();

        authoring.setChannelOffering(TENANT, BRAND, channelId, variantA, null, true, null, "operator-1");
        assertThat(assortment).hasSize(1);
    }

    private UUID createVariant(String productCode, String sku) {
        UUID productId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", productCode)
                .update();
        UUID variantId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, is_default, status)
                VALUES (:id, :tenantId, :brandId, :productId, :sku, true, 'ACTIVE')
                """)
                .param("id", variantId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", productId)
                .param("sku", sku)
                .update();
        return variantId;
    }
}
