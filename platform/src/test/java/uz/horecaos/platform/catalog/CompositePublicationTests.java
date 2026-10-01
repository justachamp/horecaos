package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
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
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.catalog.api.VariantPricingLookup;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogPublicationService;
import uz.horecaos.platform.catalog.application.CatalogSnapshotLoader;
import uz.horecaos.platform.catalog.application.CatalogValidator;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService.AttachmentPolicy;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService.NewComboGroup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CompositeProducts.AttachmentOwnerType;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboComponent;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboGroup;
import uz.horecaos.platform.catalog.domain.CompositeProducts.Visibility;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.domain.PublicationStatus;
import uz.horecaos.platform.catalog.domain.ValidationFinding;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCompositeCatalogStore;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * ADR 0136's publication rules, through the real loader and the real publication
 * service: the data the validator reasons about is assembled from the tables an
 * author wrote, not handed in.
 *
 * <p>{@code CompositeProductRulesTests} proves each rule on a literal; this proves the
 * loader hands the rules the right literal, and that a refusal reaches a publication
 * report an operator reads.
 */
class CompositePublicationTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String LOCALE = "uz";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final FiscalClassification UNCLASSIFIED = FiscalClassification.unclassified();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCatalogStore store;
    private CatalogAuthoringService authoring;
    private CompositeProductAuthoringService composites;
    private CatalogPublicationService publication;
    private PricingStandIn pricing;
    private UUID catalogId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for composite publication tests");
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
                        + "catalog.location_offerings, catalog.media_relations, catalog.translations, "
                        + "catalog.combo_components, catalog.combo_groups, "
                        + "catalog.product_modifier_groups, catalog.variant_modifier_groups, "
                        + "catalog.category_products, catalog.catalog_products, catalog.modifier_options, "
                        + "catalog.modifier_groups, catalog.categories, catalog.fiscal_classifications, "
                        + "catalog.fees, catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE media.assets CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenancy();

        var mapper = JsonMapper.builder().build();
        store = new JdbcCatalogStore(jdbc, mapper);
        var audit = new JdbcAuditRecorder(jdbc, mapper);
        CommercialDefaults.Wired commercial = CommercialDefaults.wire(jdbc, Clock.systemUTC());
        authoring = new CatalogAuthoringService(
                store, audit, commercial.entitlements(), commercial.usage(), Clock.systemUTC());
        composites = new CompositeProductAuthoringService(
                new JdbcCompositeCatalogStore(jdbc), store, audit, Clock.systemUTC());

        pricing = new PricingStandIn();
        publication = new CatalogPublicationService(
                store,
                new CatalogValidator(),
                new CatalogSnapshotLoader(store, (tenantId, assets) -> true, pricing, LOCALE),
                new JdbcSalesChannelStore(jdbc),
                Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC));
        catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
    }

    // -------------------------------------------------------------------- combos

    @Test
    @DisplayName("a combo whose components are unpriced is refused, and the container is not asked for a price")
    void anUnpricedComboIsRefusedAtPublication() {
        var combo = comboOfTwo();

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(result.status()).isEqualTo(PublicationStatus.REJECTED);
        List<ValidationFinding> blockers = result.report().blockers();
        assertThat(blockers)
                .filteredOn(finding -> finding.code().equals("COMBO_COMPONENT_HAS_NO_ACTIVE_PRICE"))
                .extracting(ValidationFinding::entityId)
                .containsExactlyInAnyOrder(
                        combo.burgerPairing().id(), combo.colaPairing().id());
        assertThat(blockers).extracting(ValidationFinding::code).contains("COMBO_HAS_NO_PRICED_COMPONENTS");
        assertThat(blockers)
                .filteredOn(finding -> finding.code().equals("VARIANT_HAS_NO_ACTIVE_PRICE"))
                .extracting(ValidationFinding::entityId)
                .as("the container is never priced (ADR 0136); the dishes it offers are priced as variants here")
                .doesNotContain(combo.container());
    }

    @Test
    @DisplayName("pricing every component lets the combo publish, container product included")
    void aPricedComboPublishes() {
        var combo = comboOfTwo();
        pricing.priceComponents(combo.burgerPairing().id(), combo.colaPairing().id());

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(result.report().blockers()).isEmpty();
        assertThat(result.status()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(jdbc.sql("""
                                SELECT count(*) FROM catalog.publication_items
                                WHERE publication_id = :id AND entity_type = 'PRODUCT'
                                  AND entity_id = :productId
                                """)
                        .param("id", result.publicationId())
                        .param("productId", combo.containerProduct())
                        .query(Long.class)
                        .single())
                .as("the container is an ordinary published product: something to name, photograph and publish")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a minimum above the active components is caught by the loader's data, not only by a literal")
    void anUnsatisfiableComboGroupIsRefused() {
        var combo = comboOfTwo();
        pricing.priceComponents(combo.burgerPairing().id(), combo.colaPairing().id());
        composites.updateComboGroup(
                TENANT,
                BRAND,
                combo.group().id(),
                combo.group().version(),
                new CompositeProductAuthoringService.ComboGroupChanges(
                        3, 3, false, 0, uz.horecaos.platform.catalog.domain.CatalogEntities.Status.ACTIVE),
                "tester");

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(result.status()).isEqualTo(PublicationStatus.REJECTED);
        assertThat(result.report().blockers())
                .extracting(ValidationFinding::code)
                .contains("COMBO_GROUP_MINIMUM_UNSATISFIABLE");
    }

    @Test
    @DisplayName("a combo group with no name is refused like every other customer-facing entity")
    void aNamelessComboGroupIsRefused() {
        var combo = comboOfTwo();
        pricing.priceComponents(combo.burgerPairing().id(), combo.colaPairing().id());
        jdbc.sql("DELETE FROM catalog.translations WHERE entity_type = 'COMBO_GROUP'")
                .update();

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(result.report().blockers())
                .filteredOn(finding -> finding.code().equals("MISSING_TRANSLATION"))
                .extracting(ValidationFinding::entityType)
                .containsExactly(EntityType.COMBO_GROUP);
    }

    // ----------------------------------------------------------- hidden groups

    @Test
    @DisplayName(
            "a hidden group with two options is refused; archiving the spare lets it publish, unpublished as a choice")
    void aHiddenGroupMustBeUnambiguous() {
        var burger = authoring.createProduct(
                TENANT, BRAND, catalogId, "BURGER", "Burger", null, LOCALE, "SKU-B", "PIECE", UNCLASSIFIED, ACTOR);
        UUID box = authoring.createModifierGroup(TENANT, BRAND, "BOX", "Box", LOCALE, true, 1, 1, false);
        UUID small = authoring.addModifierOption(
                TENANT, BRAND, box, "SMALL", "Small", LOCALE, null, 1, 0, UNCLASSIFIED, ACTOR);
        UUID large = authoring.addModifierOption(
                TENANT, BRAND, box, "LARGE", "Large", LOCALE, null, 1, 1, UNCLASSIFIED, ACTOR);
        authoring.attachModifierGroup(TENANT, BRAND, burger.productId(), box, 0);
        composites.setAttachmentPolicy(
                TENANT,
                BRAND,
                AttachmentOwnerType.PRODUCT,
                burger.productId(),
                box,
                1,
                new AttachmentPolicy(Visibility.HIDDEN_AUTO_SELECT, null, null, null, null),
                "tester");

        var refused = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        assertThat(refused.status()).isEqualTo(PublicationStatus.REJECTED);
        assertThat(refused.report().blockers())
                .filteredOn(finding -> finding.code().equals("HIDDEN_MODIFIER_GROUP_AMBIGUOUS_DEFAULT"))
                .extracting(ValidationFinding::entityId)
                .containsExactly(box);

        jdbc.sql("UPDATE catalog.modifier_options SET status = 'ARCHIVED' WHERE id = :id")
                .param("id", large)
                .update();
        var accepted = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(accepted.status()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(jdbc.sql("""
                                SELECT immutable_content_json -> 'modifierGroupIds' ->> 0
                                FROM catalog.publication_items
                                WHERE publication_id = :id AND entity_type = 'PRODUCT' AND entity_id = :product
                                """)
                        .param("id", accepted.publicationId())
                        .param("product", burger.productId())
                        .query(String.class)
                        .optional())
                .as("the delivery box is the quote's charge to apply, never an option the storefront shows")
                .isEmpty();
        assertThat(small).isNotNull();
    }

    // ----------------------------------------------------------------- nesting

    @Test
    @DisplayName("a third level of nested variant-modifiers is refused at publication, on the option that causes it")
    void aThirdLevelIsRefusedAtPublication() {
        var burger = authoring.createProduct(
                TENANT, BRAND, catalogId, "BURGER", "Burger", null, LOCALE, "SKU-B", "PIECE", UNCLASSIFIED, ACTOR);
        var side = authoring.createProduct(
                TENANT, BRAND, catalogId, "SIDE", "Side", null, LOCALE, "SKU-S", "PIECE", UNCLASSIFIED, ACTOR);
        var dip = authoring.createProduct(
                TENANT, BRAND, catalogId, "DIP", "Dip", null, LOCALE, "SKU-D", "PIECE", UNCLASSIFIED, ACTOR);

        // burger -> "Add side" (links SIDE) ; SIDE carries DIPS ; "Dip" links DIP ; DIP carries SPICE.
        UUID sides = authoring.createModifierGroup(TENANT, BRAND, "SIDES", "Sides", LOCALE, false, 0, 1, false);
        authoring.addModifierOption(
                TENANT,
                BRAND,
                sides,
                "ADD-SIDE",
                "Add side",
                LOCALE,
                side.defaultVariantId(),
                1,
                0,
                UNCLASSIFIED,
                ACTOR);
        authoring.attachModifierGroup(TENANT, BRAND, burger.productId(), sides, 0);

        UUID dips = authoring.createModifierGroup(TENANT, BRAND, "DIPS", "Dips", LOCALE, false, 0, 1, false);
        UUID tooDeep = authoring.addModifierOption(
                TENANT, BRAND, dips, "DIP", "Dip", LOCALE, dip.defaultVariantId(), 1, 0, UNCLASSIFIED, ACTOR);
        composites.attachModifierGroupToVariant(TENANT, BRAND, side.defaultVariantId(), dips, 0, "tester");

        assertThat(publication
                        .publish(TENANT, BRAND, catalogId, "STOREFRONT", null)
                        .report()
                        .blockers())
                .extracting(ValidationFinding::code)
                .as("two levels -- the allowed depth")
                .doesNotContain("MODIFIER_NESTING_DEPTH_EXCEEDED");

        UUID spice = authoring.createModifierGroup(TENANT, BRAND, "SPICE", "Spice", LOCALE, false, 0, 1, false);
        authoring.addModifierOption(TENANT, BRAND, spice, "HOT", "Hot", LOCALE, null, 1, 0, UNCLASSIFIED, ACTOR);
        composites.attachModifierGroupToVariant(TENANT, BRAND, dip.defaultVariantId(), spice, 0, "tester");

        var refused = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(refused.status()).isEqualTo(PublicationStatus.REJECTED);
        assertThat(refused.report().blockers())
                .filteredOn(finding -> finding.code().equals("MODIFIER_NESTING_DEPTH_EXCEEDED"))
                .extracting(ValidationFinding::entityId)
                .containsExactly(tooDeep);
    }

    // ---------------------------------------------------------------- fixtures

    private record Combo(
            UUID container,
            UUID containerProduct,
            ComboGroup group,
            ComboComponent burgerPairing,
            ComboComponent colaPairing) {}

    private Combo comboOfTwo() {
        var lunch = authoring.createProduct(
                TENANT, BRAND, catalogId, "LUNCH", "Lunch", null, LOCALE, "SKU-L", "PIECE", UNCLASSIFIED, ACTOR);
        var burger = authoring.createProduct(
                TENANT, BRAND, catalogId, "BURGER", "Burger", null, LOCALE, "SKU-B", "PIECE", UNCLASSIFIED, ACTOR);
        var cola = authoring.createProduct(
                TENANT, BRAND, catalogId, "COLA", "Cola", null, LOCALE, "SKU-C", "PIECE", UNCLASSIFIED, ACTOR);
        pricing.priceVariantsOnly(burger.defaultVariantId(), cola.defaultVariantId());

        ComboGroup group = composites.createComboGroup(
                new NewComboGroup(TENANT, BRAND, lunch.defaultVariantId(), "MAIN", "Choose", LOCALE, 1, 2, false, 0),
                "tester");
        ComboComponent burgerPairing =
                composites.addComponent(TENANT, BRAND, group.id(), burger.defaultVariantId(), 1, 0, "tester");
        ComboComponent colaPairing =
                composites.addComponent(TENANT, BRAND, group.id(), cola.defaultVariantId(), 1, 1, "tester");
        return new Combo(lunch.defaultVariantId(), lunch.productId(), group, burgerPairing, colaPairing);
    }

    private void insertTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'composite-publication', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'STOREFRONT', 'ACTIVE')
                """).param("id", UUID.randomUUID()).param("tenantId", TENANT).update();
    }

    /**
     * What pricing would say, and no more: dishes are priced as variants, a combo's
     * container never is, and a component is priced only when a test says so.
     */
    private static final class PricingStandIn implements VariantPricingLookup {

        private final Set<UUID> variants = new HashSet<>();
        private final Set<UUID> components = new HashSet<>();
        private boolean everyVariant = true;

        void priceVariantsOnly(UUID... ids) {
            everyVariant = false;
            variants.addAll(List.of(ids));
        }

        void priceComponents(UUID... ids) {
            components.addAll(List.of(ids));
        }

        @Override
        public Set<UUID> pricedVariants(UUID tenantId, UUID brandId, Set<UUID> variantIds) {
            if (everyVariant) {
                return variantIds;
            }
            Set<UUID> priced = new HashSet<>(variantIds);
            priced.retainAll(variants);
            return priced;
        }

        @Override
        public Set<UUID> pricedComboComponents(UUID tenantId, UUID brandId, Set<UUID> componentIds) {
            Set<UUID> priced = new HashSet<>(componentIds);
            priced.retainAll(components);
            return priced;
        }
    }
}
