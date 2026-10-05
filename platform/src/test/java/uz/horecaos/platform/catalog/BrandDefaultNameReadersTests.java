package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.catalog.api.SampleMenuPort;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogPublicationService;
import uz.horecaos.platform.catalog.application.CatalogSnapshotLoader;
import uz.horecaos.platform.catalog.application.CatalogValidator;
import uz.horecaos.platform.catalog.application.SampleMenuService;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.domain.ValidationFinding;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.ordering.application.OrderCatalogSnapshot;
import uz.horecaos.platform.ordering.infrastructure.catalog.JdbcOrderCatalogSnapshot;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcCatalogPricingContext;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcBrandLocaleLookup;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * Every server-side reader of a catalog name that ends up in front of somebody
 * resolves it the way the console's list screens do (row 10.12): in the brand's own
 * default language, then in the server's configured locale, then a code.
 *
 * <p>The list reads moved to the brand's default first, and the editors stopped forcing
 * the server's {@code uz} into their tab strip because of it. A reader left on
 * {@code uz} alone would then refuse to publish, or print a bare code on an order,
 * for a brand that names its menu in {@code ru} and never in {@code uz}.
 */
class BrandDefaultNameReadersTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String SERVER_LOCALE = "uz";
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
                "Docker is required for the brand-default name reader tests");
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
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE catalog.media_relations, catalog.fiscal_classifications, "
                        + "catalog.product_modifier_groups, catalog.modifier_options, catalog.modifier_groups, "
                        + "catalog.category_products, catalog.categories, catalog.catalog_products, "
                        + "catalog.translations, catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE media.assets CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenantAndBrand();

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
    @DisplayName("a quote line is described in the brand's default language, then the server's, then the code")
    void quoteDescriptionsFollowTheBrandDefault() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", SERVER_LOCALE);
        UUID both = variantOf(authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "PLOV",
                "Osh",
                null,
                SERVER_LOCALE,
                "SKU-PLOV",
                "PIECE",
                UNCLASSIFIED,
                ACTOR));
        authoring.translate(TENANT, BRAND, EntityType.PRODUCT, productOf(both), "ru", "Плов", null);
        UUID serverOnly = variantOf(authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "LAGMAN",
                "Lag'mon",
                null,
                SERVER_LOCALE,
                "SKU-LAGMAN",
                "PIECE",
                UNCLASSIFIED,
                ACTOR));
        UUID neither = variantOf(authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "NEITHER",
                "Only English",
                null,
                "en",
                "SKU-N",
                "PIECE",
                UNCLASSIFIED,
                ACTOR));
        brandDefault("ru");
        Set<UUID> variants = Set.of(both, serverOnly, neither);

        var brandAware = new JdbcCatalogPricingContext(jdbc, new JdbcBrandLocaleLookup(jdbc), SERVER_LOCALE);
        assertThat(brandAware.descriptions(TENANT, BRAND, variants))
                .containsEntry(both, "Плов")
                .containsEntry(serverOnly, "Lag'mon")
                .containsEntry(neither, "NEITHER");

        assertThat(new JdbcCatalogPricingContext(jdbc, SERVER_LOCALE).descriptions(TENANT, BRAND, variants))
                .as("control: the config-only context answers the server's locale for every brand")
                .containsEntry(both, "Osh");
    }

    @Test
    @DisplayName("the names an order snapshots follow the brand default, with the server's locale as the fallback")
    void orderSnapshotNamesFollowTheBrandDefault() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", SERVER_LOCALE);
        UUID variant = variantOf(authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "PLOV",
                "Osh",
                null,
                SERVER_LOCALE,
                "SKU-PLOV",
                "PIECE",
                UNCLASSIFIED,
                ACTOR));
        authoring.translate(TENANT, BRAND, EntityType.PRODUCT, productOf(variant), "ru", "Плов", null);
        UUID group = authoring.createModifierGroup(
                TENANT, BRAND, "EXTRAS", "Qo'shimchalar", SERVER_LOCALE, false, 0, 3, false);
        authoring.translate(TENANT, BRAND, EntityType.MODIFIER_GROUP, group, "ru", "Добавки", null);
        UUID ruOption = authoring.addModifierOption(
                TENANT, BRAND, group, "CHEESE", "Pishloq", SERVER_LOCALE, null, 1, 1, UNCLASSIFIED, ACTOR);
        authoring.translate(TENANT, BRAND, EntityType.MODIFIER_OPTION, ruOption, "ru", "Сыр", null);
        UUID importedOption = authoring.addModifierOption(
                TENANT, BRAND, group, "SAUCE", "Sous", SERVER_LOCALE, null, 1, 2, UNCLASSIFIED, ACTOR);
        brandDefault("ru");

        OrderCatalogSnapshot brandAware =
                new JdbcOrderCatalogSnapshot(jdbc, new JdbcBrandLocaleLookup(jdbc), SERVER_LOCALE);
        assertThat(Objects.requireNonNull(brandAware
                                .variants(TENANT, BRAND, Set.of(variant))
                                .get(variant))
                        .productName())
                .isEqualTo("Плов");
        Map<UUID, OrderCatalogSnapshot.ModifierDescriptor> options =
                brandAware.modifierOptions(TENANT, BRAND, Set.of(ruOption, importedOption));
        assertThat(Objects.requireNonNull(options.get(ruOption)).groupName()).isEqualTo("Добавки");
        assertThat(Objects.requireNonNull(options.get(ruOption)).optionName()).isEqualTo("Сыр");
        assertThat(Objects.requireNonNull(options.get(importedOption)).optionName())
                .as("named only in the server's locale: not the bare code")
                .isEqualTo("Sous");

        OrderCatalogSnapshot serverOnly = new JdbcOrderCatalogSnapshot(jdbc, SERVER_LOCALE);
        assertThat(Objects.requireNonNull(serverOnly
                                .modifierOptions(TENANT, BRAND, Set.of(ruOption))
                                .get(ruOption))
                        .optionName())
                .as("control: the config-only snapshot answers the server's locale for every brand")
                .isEqualTo("Pishloq");
    }

    @Test
    @DisplayName("publication accepts a menu named in the brand's default language and never in the server's")
    void publicationAcceptsANameInTheBrandDefault() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Основное меню", "ru");
        var product = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Плов", null, "ru", "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        assertThat(product.productId()).isNotNull();
        brandDefault("ru");

        CatalogSnapshotLoader brandAware = new CatalogSnapshotLoader(
                store, (tenantId, assets) -> true, (t, b, ids) -> ids, new JdbcBrandLocaleLookup(jdbc), SERVER_LOCALE);
        CatalogValidator.Snapshot snapshot = brandAware.load(TENANT, BRAND, catalogId);
        assertThat(snapshot.defaultLocale()).isEqualTo("ru");
        assertThat(snapshot.fallbackLocale()).isEqualTo(SERVER_LOCALE);
        assertThat(new CatalogValidator().validate(snapshot).blockers())
                .extracting(ValidationFinding::code)
                .doesNotContain("MISSING_TRANSLATION");

        CatalogSnapshotLoader serverOnly =
                new CatalogSnapshotLoader(store, (tenantId, assets) -> true, (t, b, ids) -> ids, SERVER_LOCALE);
        assertThat(new CatalogValidator()
                        .validate(serverOnly.load(TENANT, BRAND, catalogId))
                        .blockers())
                .as("control: the server's locale alone has no 'uz' name to accept")
                .extracting(ValidationFinding::code)
                .contains("MISSING_TRANSLATION");
    }

    @Test
    @DisplayName(
            "the onboarding sample menu is authored in the brand's default language, the server's for a brand with none")
    void sampleMenuIsAuthoredInTheBrandDefault() {
        brandDefault("ru");

        SampleMenuPort.SampleMenu menu =
                sampleMenu(new JdbcBrandLocaleLookup(jdbc)).installSample(TENANT, BRAND, List.of());

        assertThat(translationLocales(EntityType.CATALOG, menu.catalogId()))
                .as("the catalog's own name is the one row written in the authoring locale alone")
                .containsExactly("ru");
        assertThat(sampleMenu(new JdbcBrandLocaleLookup(jdbc))
                        .installSample(TENANT, BRAND, List.of())
                        .created())
                .as("a retry finds the same sample rather than authoring a second")
                .isFalse();
    }

    @Test
    @DisplayName("a sample menu for a brand with no default of its own keeps the server's locale")
    void sampleMenuForABrandWithNoDefaultUsesTheServerLocale() {
        SampleMenuPort.SampleMenu menu =
                sampleMenu(new JdbcBrandLocaleLookup(jdbc)).installSample(TENANT, BRAND, List.of());

        assertThat(translationLocales(EntityType.CATALOG, menu.catalogId())).containsExactly(SERVER_LOCALE);
    }

    @Test
    @DisplayName(
            "a brand default the sample has no wording in falls back to the server's locale, which publication accepts")
    void sampleMenuFallsBackWhenTheBrandDefaultIsBeyondTheSample() {
        // A locale beyond the platform triple: BrandProfile.KNOWN_LOCALES will not let the console
        // choose one yet, but nothing below it stops the row, and the sample must not write its
        // Uzbek text under a code that is not Uzbek.
        brandDefault("kaa");

        SampleMenuPort.SampleMenu menu =
                sampleMenu(new JdbcBrandLocaleLookup(jdbc)).installSample(TENANT, BRAND, List.of());

        assertThat(translationLocales(EntityType.CATALOG, menu.catalogId())).containsExactly(SERVER_LOCALE);
    }

    private static UUID variantOf(CatalogAuthoringService.ProductCreated created) {
        return created.defaultVariantId();
    }

    private UUID productOf(UUID variantId) {
        return jdbc.sql("SELECT product_id FROM catalog.variants WHERE id = :id")
                .param("id", variantId)
                .query(UUID.class)
                .single();
    }

    private SampleMenuService sampleMenu(JdbcBrandLocaleLookup brandLocales) {
        CatalogSnapshotLoader loader = new CatalogSnapshotLoader(
                store, (tenantId, assets) -> true, (t, b, ids) -> ids, brandLocales, SERVER_LOCALE);
        CatalogPublicationService publications = new CatalogPublicationService(
                store,
                new CatalogValidator(),
                loader,
                new JdbcSalesChannelStore(jdbc),
                Clock.systemUTC(),
                AuditTrail.discarding());
        return new SampleMenuService(store, authoring, publications, brandLocales, SERVER_LOCALE);
    }

    private List<String> translationLocales(EntityType type, UUID entityId) {
        return jdbc.sql("""
                SELECT locale FROM catalog.translations
                WHERE tenant_id = :tenantId AND entity_type = :type AND entity_id = :entityId
                ORDER BY locale
                """)
                .param("tenantId", TENANT)
                .param("type", type.name())
                .param("entityId", entityId)
                .query(String.class)
                .list();
    }

    private void brandDefault(String locale) {
        jdbc.sql("""
                INSERT INTO tenant.brand_locales (tenant_id, brand_id, locale, is_default)
                VALUES (:tenantId, :brandId, :locale, true)
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locale", locale)
                .update();
    }

    private void insertTenantAndBrand() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone,
                    status, version)
                VALUES (:id, 'name-readers', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", BRAND)
                .param("tenantId", TENANT)
                .param("code", "MAIN")
                .param("slug", "MAIN".toLowerCase(Locale.ROOT))
                .update();
    }
}
