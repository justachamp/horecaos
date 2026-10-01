package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogPublicationService;
import uz.horecaos.platform.catalog.application.CatalogSnapshotLoader;
import uz.horecaos.platform.catalog.application.CatalogValidator;
import uz.horecaos.platform.catalog.application.PhysicalAttributesAuthoringService;
import uz.horecaos.platform.catalog.application.PhysicalAttributesAuthoringService.StalePhysicalAttributesException;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuVariant;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.domain.FiscalClassification.MarkingScheme;
import uz.horecaos.platform.catalog.domain.PhysicalAttributes;
import uz.horecaos.platform.catalog.domain.PublicationStatus;
import uz.horecaos.platform.catalog.domain.ValidationFinding;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcPhysicalAttributesStore;
import uz.horecaos.platform.media.api.MediaAvailability;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * ADR 0137's attributes through authoring, publication and the storefront read.
 *
 * <p>What matters is the publication boundary: the published copy is what a quote
 * is priced against and what a customer is shown, so a draft edit after publishing
 * must not reach it, and a good that is both marked and weighed must not reach it
 * at all.
 */
class PhysicalAttributesPublicationTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_TENANT_BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final String LOCALE = "uz";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String ACTOR_SUBJECT = ACTOR.toString();

    private static final FiscalClassification UNCLASSIFIED = FiscalClassification.unclassified();
    private static final FiscalClassification MARKED = new FiscalClassification(
            "10202001001000000",
            "1512315",
            1,
            "Bottle, dona",
            null,
            true,
            MarkingScheme.DATA_MATRIX,
            false,
            null,
            null);

    private static final PhysicalAttributes CAKE = new PhysicalAttributes(
            1500,
            null,
            true,
            100,
            1200,
            true,
            new BigDecimal("0.5"),
            new BigDecimal("350.5"),
            new BigDecimal("4.5"),
            new BigDecimal("18"),
            new BigDecimal("52.25"));

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCatalogStore store;
    private CatalogAuthoringService authoring;
    private PhysicalAttributesAuthoringService physical;
    private CatalogPublicationService publication;
    private StorefrontCatalogQuery storefront;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for catalog publication tests");
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
                        + "catalog.location_offerings, catalog.translations, "
                        + "catalog.category_products, catalog.catalog_products, "
                        + "catalog.fiscal_classifications, catalog.variant_physical_attributes, "
                        + "catalog.fees, catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenancy();

        JsonMapper mapper = JsonMapper.builder().build();
        store = new JdbcCatalogStore(jdbc, mapper);
        JdbcAuditRecorder audit = new JdbcAuditRecorder(jdbc, mapper);
        CommercialDefaults.Wired commercial = CommercialDefaults.wire(jdbc, Clock.systemUTC());
        authoring = new CatalogAuthoringService(
                store, audit, commercial.entitlements(), commercial.usage(), Clock.systemUTC());
        physical = new PhysicalAttributesAuthoringService(
                store, new JdbcPhysicalAttributesStore(jdbc), audit, Clock.systemUTC());

        MediaAvailability media = (tenantId, assets) -> true;
        publication = new CatalogPublicationService(
                store,
                new CatalogValidator(),
                new CatalogSnapshotLoader(store, media, (tenantId, brandId, variantIds) -> variantIds, LOCALE),
                new JdbcSalesChannelStore(jdbc),
                Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC));
        storefront = new StorefrontCatalogQuery(
                store,
                (tenantId, brandId, locationId, channel, variantIds, optionIds) -> Optional.empty(),
                (tenantId, brandId, locationId, channel, variantIds) -> Map.of(),
                new JdbcMenuStore(jdbc),
                new uz.horecaos.platform.catalog.infrastructure.tenancy.JdbcCatalogTenantContext(jdbc),
                Clock.systemUTC(),
                new uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore(jdbc));
    }

    @Test
    @DisplayName("the attributes a variant carries are published, and the storefront reads them off the snapshot")
    void publishedPhysicalAttributesReachTheStorefront() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var cake = product(catalogId, "CAKE", UNCLASSIFIED);
        physical.replace(TENANT, BRAND, cake.defaultVariantId(), CAKE, 0, ACTOR_SUBJECT);

        assertThat(publication
                        .publish(TENANT, BRAND, catalogId, "STOREFRONT", null)
                        .status())
                .isEqualTo(PublicationStatus.PUBLISHED);

        var facts = Objects.requireNonNull(onlyVariant().physical());
        assertThat(facts.catchweight()).isTrue();
        assertThat(facts.catchweightQuantumGrams()).isEqualTo(100);
        assertThat(facts.catchweightNominalGrams()).isEqualTo(1200);
        assertThat(facts.netWeightGrams()).isEqualTo(1500);
        assertThat(facts.splittable()).isTrue();
        assertThat(facts.portionSize()).isEqualByComparingTo("0.5");
        var nutrition = Objects.requireNonNull(facts.nutrition());
        assertThat(nutrition.caloriesKcalPer100()).isEqualByComparingTo("350.5");
        assertThat(nutrition.proteinGramsPer100()).isEqualByComparingTo("4.5");
        assertThat(nutrition.fatGramsPer100()).isEqualByComparingTo("18");
        assertThat(nutrition.carbohydratesGramsPer100()).isEqualByComparingTo("52.25");
    }

    @Test
    @DisplayName("a variant with no attributes publishes no physical block at all")
    void aPlainVariantCarriesNoBlock() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        product(catalogId, "SODA", UNCLASSIFIED);

        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(onlyVariant().physical())
                .as("no row means a fixed unit sold whole, and nothing is published about it")
                .isNull();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM catalog.publication_items
                        WHERE immutable_content_json::text LIKE '%"physical"%'
                        """).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("editing the draft after publishing changes nothing a customer or a quote reads")
    void aDraftEditDoesNotReachTheLiveMenu() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var cake = product(catalogId, "CAKE", UNCLASSIFIED);
        physical.replace(TENANT, BRAND, cake.defaultVariantId(), CAKE, 0, ACTOR_SUBJECT);
        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        // The author now turns catchweight off and clears the nutrition on the draft.
        physical.replace(
                TENANT,
                BRAND,
                cake.defaultVariantId(),
                new PhysicalAttributes(1500, null, false, null, null, false, null, null, null, null, null),
                1,
                ACTOR_SUBJECT);

        var facts = Objects.requireNonNull(onlyVariant().physical());
        assertThat(facts.catchweight())
                .as("the published copy still says catchweight: the draft edit is not live until the next publish")
                .isTrue();
        assertThat(facts.nutrition()).isNotNull();
    }

    @Test
    @DisplayName("a marked good that is also catchweight cannot be published")
    void aMarkedCatchweightGoodIsRefusedAtPublication() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var bottle = product(catalogId, "BOTTLE", MARKED);
        physical.replace(
                TENANT,
                BRAND,
                bottle.defaultVariantId(),
                new PhysicalAttributes(1500, null, true, 100, null, false, null, null, null, null, null),
                0,
                ACTOR_SUBJECT);

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(result.status()).isEqualTo(PublicationStatus.REJECTED);
        assertThat(result.report().blockers())
                .extracting(ValidationFinding::code)
                .contains("PHYSICAL_ATTRIBUTES_CONFLICT_WITH_MARKING");
        assertThat(storefront.menuFor(TENANT, BRAND, LOCATION, LOCALE, "STOREFRONT"))
                .as("a rejected publication leaves nothing live")
                .isEmpty();
    }

    @Test
    @DisplayName("the same good publishes once its catchweight is removed")
    void removingTheConflictLetsItPublish() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var bottle = product(catalogId, "BOTTLE", MARKED);
        physical.replace(
                TENANT,
                BRAND,
                bottle.defaultVariantId(),
                new PhysicalAttributes(1500, null, true, 100, null, false, null, null, null, null, null),
                0,
                ACTOR_SUBJECT);
        assertThat(publication
                        .publish(TENANT, BRAND, catalogId, "STOREFRONT", null)
                        .status())
                .isEqualTo(PublicationStatus.REJECTED);

        physical.replace(
                TENANT,
                BRAND,
                bottle.defaultVariantId(),
                new PhysicalAttributes(330, null, false, null, null, false, null, null, null, null, null),
                1,
                ACTOR_SUBJECT);

        assertThat(publication
                        .publish(TENANT, BRAND, catalogId, "STOREFRONT", null)
                        .status())
                .isEqualTo(PublicationStatus.PUBLISHED);
    }

    @Test
    @DisplayName("the first write expects version 0, a later one the version it read, and a stale one is refused")
    void writesAreVersioned() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        UUID variantId = product(catalogId, "CAKE", UNCLASSIFIED).defaultVariantId();

        assertThat(physical.read(TENANT, BRAND, variantId).version())
                .as("no row reads as version 0")
                .isZero();
        assertThat(physical.read(TENANT, BRAND, variantId).attributes()).isNull();

        assertThat(catchThrowable(() -> physical.replace(TENANT, BRAND, variantId, CAKE, 3, ACTOR_SUBJECT)))
                .as("a first write quoting a version it never read")
                .isInstanceOf(StalePhysicalAttributesException.class);

        assertThat(physical.replace(TENANT, BRAND, variantId, CAKE, 0, ACTOR_SUBJECT)
                        .version())
                .isEqualTo(1);
        assertThat(catchThrowable(() -> physical.replace(TENANT, BRAND, variantId, CAKE, 0, ACTOR_SUBJECT)))
                .as("a second author who also read version 0")
                .isInstanceOfSatisfying(StalePhysicalAttributesException.class, stale -> {
                    assertThat(stale.expected()).isZero();
                    assertThat(stale.actual()).isEqualTo(1);
                });
        assertThat(physical.replace(TENANT, BRAND, variantId, CAKE, 1, ACTOR_SUBJECT)
                        .version())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("an empty set clears the row, and clearing a variant that has none writes nothing")
    void anEmptySetClearsTheRow() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        UUID variantId = product(catalogId, "CAKE", UNCLASSIFIED).defaultVariantId();
        physical.replace(TENANT, BRAND, variantId, CAKE, 0, ACTOR_SUBJECT);

        var cleared = physical.replace(
                TENANT,
                BRAND,
                variantId,
                new PhysicalAttributes(null, null, false, null, null, false, null, null, null, null, null),
                1,
                ACTOR_SUBJECT);

        assertThat(cleared.attributes()).isNull();
        assertThat(cleared.version()).isZero();
        assertThat(count("catalog.variant_physical_attributes")).isZero();

        long auditBefore = count("audit.audit_events WHERE action_code = 'catalog.variantPhysicalAttributes.set'");
        physical.replace(TENANT, BRAND, variantId, null, 0, ACTOR_SUBJECT);
        assertThat(count("audit.audit_events WHERE action_code = 'catalog.variantPhysicalAttributes.set'"))
                .as("clearing nothing leaves no fact")
                .isEqualTo(auditBefore);
    }

    @Test
    @DisplayName("each write is audited with a per-field diff of what changed")
    void aWriteIsAuditedAsADiff() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        UUID variantId = product(catalogId, "CAKE", UNCLASSIFIED).defaultVariantId();
        physical.replace(TENANT, BRAND, variantId, CAKE, 0, ACTOR_SUBJECT);
        physical.replace(
                TENANT,
                BRAND,
                variantId,
                new PhysicalAttributes(
                        1500, null, true, 100, 1200, true, new BigDecimal("0.5"), null, null, null, null),
                1,
                ACTOR_SUBJECT);

        List<String> documents = jdbc.sql("""
                        SELECT change_document::text FROM audit.audit_events
                        WHERE action_code = 'catalog.variantPhysicalAttributes.set'
                        ORDER BY recorded_at, id
                        """).query(String.class).list();

        assertThat(documents).hasSize(2);
        assertThat(documents.get(0))
                .as("the first write: nothing before")
                .contains("\"catchweightQuantumGrams\"")
                .contains("350.5");
        assertThat(documents.get(1))
                .as("КБЖУ and catchweight figures are dish data, never redacted as if they were personal")
                .doesNotContain("[redacted]")
                .contains("caloriesKcalPer100")
                .contains("350.5");
    }

    @Test
    @DisplayName("another brand's variant is not found, and writes nothing")
    void anotherBrandsVariantIsOutOfScope() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        UUID variantId = product(catalogId, "CAKE", UNCLASSIFIED).defaultVariantId();

        assertThat(catchThrowable(() -> physical.replace(TENANT, OTHER_BRAND_OF_TENANT, variantId, CAKE, 0, "x")))
                .isInstanceOf(CatalogAuthoringService.UnknownCatalogEntityException.class);
        assertThat(catchThrowable(() -> physical.read(OTHER_TENANT, OTHER_TENANT_BRAND, variantId)))
                .as("a variant id of one tenant read through another tenant's brand")
                .isInstanceOf(CatalogAuthoringService.UnknownCatalogEntityException.class);
        assertThat(count("catalog.variant_physical_attributes")).isZero();
    }

    @Test
    @DisplayName("the database refuses a row that contradicts the invariants, whatever the caller was")
    void theDatabaseBacksTheDomainUp() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        UUID variantId = product(catalogId, "CAKE", UNCLASSIFIED).defaultVariantId();

        assertThat(catchThrowable(() -> rawInsert(variantId, "net_weight_grams, net_volume_millilitres", "100, 100")))
                .hasMessageContaining("ck_physical_weight_xor_volume");
        assertThat(catchThrowable(() -> rawInsert(variantId, "net_weight_grams, is_catchweight", "100, true")))
                .hasMessageContaining("ck_catchweight_needs_quantum");
        assertThat(catchThrowable(() -> rawInsert(variantId, "is_catchweight, catchweight_quantum_grams", "true, 100")))
                .hasMessageContaining("ck_catchweight_needs_weight");
        assertThat(catchThrowable(() -> rawInsert(variantId, "portion_size", "0.5")))
                .hasMessageContaining("ck_physical_portion_needs_splittable");
        assertThat(catchThrowable(() -> rawInsert(variantId, "net_weight_grams", "0")))
                .hasMessageContaining("ck_physical_positive_measures");
    }

    // ------------------------------------------------------------------ helpers

    private static final UUID OTHER_BRAND_OF_TENANT = UUID.randomUUID();

    private CatalogAuthoringService.ProductCreated product(
            UUID catalogId, String code, FiscalClassification classification) {
        var created = authoring.createProduct(
                TENANT, BRAND, catalogId, code, code, null, LOCALE, "SKU-" + code, "PIECE", classification, ACTOR);
        authoring.setOffering(
                TENANT,
                BRAND,
                LOCATION,
                created.defaultVariantId(),
                OfferingStatus.AVAILABLE,
                List.of("DELIVERY", "PICKUP"));
        return created;
    }

    private MenuVariant onlyVariant() {
        var menu = storefront
                .menuFor(TENANT, BRAND, LOCATION, LOCALE, "STOREFRONT")
                .orElseThrow();
        return menu.products().getFirst().variants().getFirst();
    }

    private void rawInsert(UUID variantId, String columns, String values) {
        jdbc.sql("INSERT INTO catalog.variant_physical_attributes (variant_id, tenant_id, brand_id, " + columns
                        + ") VALUES (:variantId, :tenantId, :brandId, " + values + ")")
                .param("variantId", variantId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }

    private long count(String tableAndWhere) {
        return jdbc.sql("SELECT count(*) FROM " + tableAndWhere)
                .query(Long.class)
                .single();
    }

    private void insertTenancy() {
        insertTenant(TENANT, "physical-tenant");
        insertTenant(OTHER_TENANT, "physical-other-tenant");
        insertBrand(TENANT, BRAND, "MAIN", "main");
        insertBrand(TENANT, OTHER_BRAND_OF_TENANT, "OTHER", "other");
        insertBrand(OTHER_TENANT, OTHER_TENANT_BRAND, "MAIN", "other-main");

        jdbc.sql("""
                INSERT INTO tenant.locations (
                    id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'main-01', 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'STOREFRONT', 'ACTIVE')
                """).param("id", UUID.randomUUID()).param("tenantId", TENANT).update();
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void insertBrand(UUID tenantId, UUID brandId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("code", code)
                .param("slug", slug)
                .update();
    }
}
