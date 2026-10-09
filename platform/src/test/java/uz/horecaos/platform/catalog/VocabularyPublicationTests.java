package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
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
import uz.horecaos.platform.catalog.application.AutoAddSnapshotSource;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogPublicationService;
import uz.horecaos.platform.catalog.application.CatalogSnapshotLoader;
import uz.horecaos.platform.catalog.application.CatalogValidator;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuProduct;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.StorefrontMenu;
import uz.horecaos.platform.catalog.application.VocabularyService;
import uz.horecaos.platform.catalog.application.VocabularyService.EntryEdit;
import uz.horecaos.platform.catalog.application.VocabularyService.EntryView;
import uz.horecaos.platform.catalog.application.VocabularyService.NewEntry;
import uz.horecaos.platform.catalog.application.VocabularySnapshotSource;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.domain.PublicationStatus;
import uz.horecaos.platform.catalog.domain.ValidationFinding;
import uz.horecaos.platform.catalog.domain.Vocabularies.Kind;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcVocabularyStore;
import uz.horecaos.platform.media.api.MediaAvailability;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;
import uz.horecaos.platform.web.api.ApiException;

/**
 * ADR 0174 through the registries, publication and the storefront read, against a real database.
 *
 * <p>Three things matter. The variant's own name must reach a customer (it was authored and dropped at
 * publication). The registries must stay controlled and bounded, with their refusals stable. And a menu that
 * uses none of it must publish exactly the content it always has, because publication items are written once and
 * a brand that never authored a tag must not see its draft hash move.
 */
class VocabularyPublicationTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID SECOND_BRAND = UUID.randomUUID();
    private static final UUID OTHER_TENANT_BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID SECOND_LOCATION = UUID.randomUUID();
    private static final String LOCALE = "uz";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String SUBJECT = ACTOR.toString();
    private static final FiscalClassification UNCLASSIFIED = FiscalClassification.unclassified();
    private static final Map<String, String> SPICY = Map.of("ru", "Острое", "uz-Latn", "Achchiq", "en", "Spicy");
    private static final Map<String, String> VEGAN = Map.of("ru", "Веган", "uz-Latn", "Vegan", "en", "Vegan");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCatalogStore store;
    private CatalogAuthoringService authoring;
    private VocabularyService vocabulary;
    private CatalogPublicationService publication;
    private StorefrontCatalogQuery storefront;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for vocabulary tests");
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
        jdbc.sql("TRUNCATE TABLE catalog.product_tags, catalog.product_ingredients, catalog.tag_translations, "
                        + "catalog.ingredient_translations, catalog.tags, catalog.ingredients, "
                        + "catalog.publication_items, catalog.publications, catalog.location_offerings, "
                        + "catalog.translations, catalog.category_products, catalog.catalog_products, "
                        + "catalog.fiscal_classifications, catalog.variant_physical_attributes, catalog.fees, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
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
        JdbcVocabularyStore vocabularyStore = new JdbcVocabularyStore(jdbc);
        vocabulary = new VocabularyService(
                vocabularyStore, store, audit, Clock.systemUTC(), BrandLocaleLookup.platformFallback());
        MediaAvailability media = (tenantId, assets) -> true;
        publication = new CatalogPublicationService(
                store,
                new CatalogValidator(),
                new CatalogSnapshotLoader(
                        store,
                        media,
                        (tenantId, brandId, variantIds) -> variantIds,
                        BrandLocaleLookup.platformFallback(),
                        LOCALE,
                        AutoAddSnapshotSource.NONE,
                        new VocabularySnapshotSource.Stored(vocabularyStore)),
                new JdbcSalesChannelStore(jdbc),
                Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC),
                AuditTrail.discarding());
        storefront = new StorefrontCatalogQuery(
                store,
                (tenantId, brandId, locationId, channel, variantIds, optionIds) -> Optional.empty(),
                (tenantId, brandId, locationId, channel, variantIds) -> Map.of(),
                new JdbcMenuStore(jdbc),
                new uz.horecaos.platform.catalog.infrastructure.tenancy.JdbcCatalogTenantContext(jdbc),
                Clock.systemUTC(),
                new uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore(jdbc));
    }

    // ----------------------------------------------------------------------- the variant's name

    @Test
    @DisplayName("a variant's name travels into the publication and the storefront resolves it per language")
    void aVariantNameReachesTheCustomer() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var pizza = product(catalogId, "PIZZA");
        authoring.translate(
                TENANT,
                BRAND,
                uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType.VARIANT,
                pizza.defaultVariantId(),
                "uz",
                "Kichik",
                null);
        authoring.translate(
                TENANT,
                BRAND,
                uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType.VARIANT,
                pizza.defaultVariantId(),
                "ru",
                "Маленькая",
                null);
        UUID large = authoring.addVariant(
                TENANT, BRAND, pizza.productId(), "SKU-PIZZA-L", "PIECE", "Katta", LOCALE, 1, UNCLASSIFIED, ACTOR);
        offer(large);

        assertThat(publication
                        .publish(TENANT, BRAND, catalogId, "STOREFRONT", null)
                        .status())
                .isEqualTo(PublicationStatus.PUBLISHED);

        MenuProduct uzMenu = onlyProduct(menu("uz"));
        assertThat(uzMenu.variants())
                .extracting(variant -> variant.name())
                .containsExactlyInAnyOrder("Kichik", "Katta");
        assertThat(onlyProduct(menu("ru")).variants())
                .extracting(variant -> variant.name())
                .as("Russian where the variant has it, the brand's default wording where it does not")
                .containsExactlyInAnyOrder("Маленькая", "Katta");
    }

    @Test
    @DisplayName("a variant nobody named publishes no names at all and the storefront says null, not a code")
    void anUnnamedVariantStaysTheWayItWas() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        product(catalogId, "SODA");

        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(onlyProduct(menu("uz")).variants().getFirst().name()).isNull();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM catalog.publication_items
                         WHERE entity_type = 'PRODUCT'
                           AND (immutable_content_json::text LIKE '%"tagIds"%'
                             OR immutable_content_json::text LIKE '%"ingredientIds"%'
                             OR jsonb_exists(immutable_content_json -> 'variants' -> 0, 'names'))
                        """).query(Long.class).single())
                .as("a brand that authored none of it publishes exactly the content it always has")
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM catalog.publication_items WHERE entity_type IN ('TAG', 'INGREDIENT')")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("several variants with one unnamed is a warning, not a blocker; a lone variant needs no name")
    void anUnnamedSizeIsOnlyAWarning() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var pizza = product(catalogId, "PIZZA");
        UUID second = authoring.addVariant(
                TENANT, BRAND, pizza.productId(), "SKU-PIZZA-L", "PIECE", null, LOCALE, 1, UNCLASSIFIED, ACTOR);
        offer(second);
        product(catalogId, "SODA");

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(result.status()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(result.report().findings())
                .filteredOn(finding -> finding.code().equals("VARIANT_NAME_MISSING"))
                .hasSize(2)
                .allSatisfy(finding -> assertThat(finding.severity()).isEqualTo(ValidationFinding.Severity.WARNING))
                .extracting(ValidationFinding::entityId)
                .containsExactlyInAnyOrder(pizza.defaultVariantId(), second);
    }

    // ------------------------------------------------------------------- tags and ingredients

    @Test
    @DisplayName("a tag and an ingredient travel with the products that carry them, in order, and resolve per language")
    void tagsAndIngredientsReachTheStorefront() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = product(catalogId, "PLOV");
        EntryView spicy =
                vocabulary.create(Kind.TAG, TENANT, new NewEntry("spicy", "FLAME", true, true, 2, SPICY), SUBJECT);
        EntryView vegan =
                vocabulary.create(Kind.TAG, TENANT, new NewEntry("vegan", "LEAF", true, false, 1, VEGAN), SUBJECT);
        EntryView rice = vocabulary.create(
                Kind.INGREDIENT,
                TENANT,
                new NewEntry("rice", null, null, null, 0, Map.of("ru", "Рис", "uz-Latn", "Guruch", "en", "Rice")),
                SUBJECT);
        EntryView carrot = vocabulary.create(
                Kind.INGREDIENT,
                TENANT,
                new NewEntry(
                        "carrot", null, null, null, 1, Map.of("ru", "Морковь", "uz-Latn", "Sabzi", "en", "Carrot")),
                SUBJECT);
        vocabulary.setForProduct(
                Kind.TAG,
                TENANT,
                BRAND,
                plov.productId(),
                List.of(spicy.row().id(), vegan.row().id()),
                SUBJECT);
        vocabulary.setForProduct(
                Kind.INGREDIENT,
                TENANT,
                BRAND,
                plov.productId(),
                List.of(carrot.row().id(), rice.row().id()),
                SUBJECT);

        assertThat(publication
                        .publish(TENANT, BRAND, catalogId, "STOREFRONT", null)
                        .status())
                .isEqualTo(PublicationStatus.PUBLISHED);

        MenuProduct uz = onlyProduct(menu("uz"));
        assertThat(uz.tags())
                .as("the author's order on the product, not the registry's sort order")
                .extracting(tag -> tag.code())
                .containsExactly("spicy", "vegan");
        assertThat(uz.tags().getFirst().name()).isEqualTo("Achchiq");
        assertThat(uz.tags().getFirst().icon()).isEqualTo("FLAME");
        assertThat(uz.tags().getFirst().showAsBadge()).isTrue();
        assertThat(uz.tags().getFirst().showAsFilter()).isTrue();
        assertThat(uz.tags().get(1).showAsFilter()).isFalse();
        assertThat(uz.ingredients()).extracting(i -> i.name()).containsExactly("Sabzi", "Guruch");
        assertThat(onlyProduct(menu("en")).tags().getFirst().name()).isEqualTo("Spicy");
        assertThat(onlyProduct(menu("ru")).ingredients().getFirst().name()).isEqualTo("Морковь");
    }

    @Test
    @DisplayName("a brand's publication holds only the tags its own products use")
    void aBrandPublishesOnlyWhatItsProductsUse() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = product(catalogId, "PLOV");
        UUID secondCatalog = authoring.createCatalog(TENANT, SECOND_BRAND, "MAIN", "Second menu", LOCALE);
        var samsa = productOf(SECOND_BRAND, SECOND_LOCATION, secondCatalog, "SAMSA");
        EntryView spicy =
                vocabulary.create(Kind.TAG, TENANT, new NewEntry("spicy", null, true, false, 0, SPICY), SUBJECT);
        EntryView vegan =
                vocabulary.create(Kind.TAG, TENANT, new NewEntry("vegan", null, true, false, 1, VEGAN), SUBJECT);
        vocabulary.create(Kind.TAG, TENANT, new NewEntry("unused", null, true, false, 2, SPICY), SUBJECT);
        vocabulary.setForProduct(
                Kind.TAG, TENANT, BRAND, plov.productId(), List.of(spicy.row().id()), SUBJECT);
        vocabulary.setForProduct(
                Kind.TAG,
                TENANT,
                SECOND_BRAND,
                samsa.productId(),
                List.of(vegan.row().id()),
                SUBJECT);

        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        publication.publish(TENANT, SECOND_BRAND, secondCatalog, "STOREFRONT", null);

        assertThat(tagCodesPublishedFor(BRAND)).containsExactly("spicy");
        assertThat(tagCodesPublishedFor(SECOND_BRAND))
                .as("one tenant's other brands never see a tag they do not use, and neither does a tag nobody uses")
                .containsExactly("vegan");
    }

    @Test
    @DisplayName(
            "an archived tag leaves the next publication, keeps resolving on the product, and its code stays taken")
    void anArchivedTagLeavesThePublicationButNotTheProduct() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = product(catalogId, "PLOV");
        EntryView spicy =
                vocabulary.create(Kind.TAG, TENANT, new NewEntry("spicy", null, true, false, 0, SPICY), SUBJECT);
        vocabulary.setForProduct(
                Kind.TAG, TENANT, BRAND, plov.productId(), List.of(spicy.row().id()), SUBJECT);
        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        assertThat(onlyProduct(menu("uz")).tags()).hasSize(1);

        vocabulary.archive(Kind.TAG, TENANT, spicy.row().id(), 1, SUBJECT);
        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(onlyProduct(menu("uz")).tags())
                .as("the live menu no longer shows it")
                .isEmpty();
        List<EntryView> carried = vocabulary.forProduct(Kind.TAG, TENANT, BRAND, plov.productId());
        assertThat(carried)
                .as("the product still carries it, and says it is archived")
                .hasSize(1);
        assertThat(carried.getFirst().row().status()).isEqualTo("ARCHIVED");
        ApiException taken = (ApiException) catchThrowable(
                () -> vocabulary.create(Kind.TAG, TENANT, new NewEntry("spicy", null, true, false, 0, SPICY), SUBJECT));
        assertThat(taken.properties()).containsEntry("findingCode", "VOCABULARY_CODE_TAKEN");
    }

    @Test
    @DisplayName(
            "a tag or ingredient with no wording in the brand's default language blocks publication until it has one")
    void aNamelessEntryBlocksPublication() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = product(catalogId, "PLOV");
        // Named in the tenant's default language (ru) only: the catalogue's default here is uz.
        EntryView tag = vocabulary.create(
                Kind.TAG, TENANT, new NewEntry("spicy", null, true, false, 0, Map.of("ru", "Острое")), SUBJECT);
        EntryView ingredient = vocabulary.create(
                Kind.INGREDIENT, TENANT, new NewEntry("rice", null, null, null, 0, Map.of("ru", "Рис")), SUBJECT);
        vocabulary.setForProduct(
                Kind.TAG, TENANT, BRAND, plov.productId(), List.of(tag.row().id()), SUBJECT);
        vocabulary.setForProduct(
                Kind.INGREDIENT,
                TENANT,
                BRAND,
                plov.productId(),
                List.of(ingredient.row().id()),
                SUBJECT);

        var rejected = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(rejected.status()).isEqualTo(PublicationStatus.REJECTED);
        assertThat(rejected.report().blockers())
                .extracting(ValidationFinding::code)
                .contains("TAG_NAME_MISSING", "INGREDIENT_NAME_MISSING");

        vocabulary.update(
                Kind.TAG,
                TENANT,
                tag.row().id(),
                new EntryEdit(1, null, null, null, null, Map.of("uz-Latn", "Achchiq")),
                SUBJECT);
        vocabulary.update(
                Kind.INGREDIENT,
                TENANT,
                ingredient.row().id(),
                new EntryEdit(1, null, null, null, null, Map.of("uz-Latn", "Guruch")),
                SUBJECT);
        assertThat(publication
                        .publish(TENANT, BRAND, catalogId, "STOREFRONT", null)
                        .status())
                .isEqualTo(PublicationStatus.PUBLISHED);
    }

    // ------------------------------------------------------------------------- the registries

    @Test
    @DisplayName("a code is a lower case slug, unique per tenant, and a tag is a badge, a filter or both")
    void theRegistryRefusesWhatItShouldNot() {
        assertRefused(
                () -> vocabulary.create(Kind.TAG, TENANT, new NewEntry("Spicy", null, true, false, 0, SPICY), SUBJECT),
                "VOCABULARY_CODE_INVALID");
        assertRefused(
                () -> vocabulary.create(
                        Kind.TAG, TENANT, new NewEntry("hot dog", null, true, false, 0, SPICY), SUBJECT),
                "VOCABULARY_CODE_INVALID");
        assertRefused(
                () -> vocabulary.create(
                        Kind.TAG, TENANT, new NewEntry("x".repeat(33), null, true, false, 0, SPICY), SUBJECT),
                "VOCABULARY_CODE_INVALID");
        assertRefused(
                () -> vocabulary.create(
                        Kind.TAG, TENANT, new NewEntry("spicy", "SKULL", true, false, 0, SPICY), SUBJECT),
                "TAG_ICON_UNKNOWN");
        assertRefused(
                () -> vocabulary.create(
                        Kind.TAG, TENANT, new NewEntry("spicy", "FLAME", false, false, 0, SPICY), SUBJECT),
                "TAG_NOT_VISIBLE");
        assertRefused(
                () -> vocabulary.create(
                        Kind.TAG, TENANT, new NewEntry("spicy", "FLAME", null, null, 0, SPICY), SUBJECT),
                "TAG_NOT_VISIBLE");
        assertRefused(
                () -> vocabulary.create(
                        Kind.TAG, TENANT, new NewEntry("spicy", null, true, false, 0, Map.of("en", "Spicy")), SUBJECT),
                "VOCABULARY_DEFAULT_NAME_MISSING");
        assertThat(count("catalog.tags"))
                .as("nothing was stored by any refusal")
                .isZero();

        vocabulary.create(Kind.TAG, TENANT, new NewEntry("spicy", "FLAME", true, false, 0, SPICY), SUBJECT);
        assertRefused(
                () -> vocabulary.create(Kind.TAG, TENANT, new NewEntry("spicy", null, true, false, 0, SPICY), SUBJECT),
                "VOCABULARY_CODE_TAKEN");
        vocabulary.create(Kind.TAG, OTHER_TENANT, new NewEntry("spicy", null, true, false, 0, SPICY), SUBJECT);
        assertThat(count("catalog.tags"))
                .as("the same code is free for another tenant")
                .isEqualTo(2);
        assertThat(catchThrowable(() -> jdbc.sql("""
                                INSERT INTO catalog.tags (id, tenant_id, code, show_as_badge, show_as_filter)
                                VALUES (gen_random_uuid(), :t, 'dark', false, false)
                                """).param("t", TENANT).update()))
                .as("the database says the same as the service: a tag that is neither shown nor offered does nothing")
                .hasMessageContaining("ck_tag_visible");
    }

    @Test
    @DisplayName("a rename changes the wording, never the code, and an edit never deletes a locale")
    void aRenameKeepsTheCodeAndTheOtherLocales() {
        EntryView created =
                vocabulary.create(Kind.TAG, TENANT, new NewEntry("spicy", "FLAME", true, true, 0, SPICY), SUBJECT);

        EntryView edited = vocabulary.update(
                Kind.TAG,
                TENANT,
                created.row().id(),
                new EntryEdit(1, null, false, null, 5, Map.of("en", "Hot")),
                SUBJECT);

        assertThat(edited.row().code()).isEqualTo("spicy");
        assertThat(edited.row().version()).isEqualTo(2);
        assertThat(edited.row().showAsBadge()).isFalse();
        assertThat(edited.row().showAsFilter())
                .as("an unnamed switch keeps its value")
                .isTrue();
        assertThat(edited.row().iconKey()).isEqualTo("FLAME");
        assertThat(edited.names())
                .containsEntry("en", "Hot")
                .containsEntry("ru", "Острое")
                .containsEntry("uz-Latn", "Achchiq");
        ApiException stale = (ApiException) catchThrowable(() -> vocabulary.update(
                Kind.TAG, TENANT, created.row().id(), new EntryEdit(1, null, null, null, null, null), SUBJECT));
        assertThat(stale.getMessage()).contains("version");
        assertRefused(
                () -> vocabulary.update(
                        Kind.TAG,
                        TENANT,
                        created.row().id(),
                        new EntryEdit(2, null, false, false, null, null),
                        SUBJECT),
                "TAG_NOT_VISIBLE");
    }

    @Test
    @DisplayName("a tenant holds at most 200 tags, and a product at most twelve")
    void theBoundsAreEnforcedWithAStableCode() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = product(catalogId, "PLOV");
        List<UUID> ids = IntStream.range(0, 13)
                .mapToObj(n -> vocabulary
                        .create(Kind.TAG, TENANT, new NewEntry("tag-" + n, null, true, false, n, SPICY), SUBJECT)
                        .row()
                        .id())
                .toList();

        vocabulary.setForProduct(Kind.TAG, TENANT, BRAND, plov.productId(), ids.subList(0, 12), SUBJECT);
        assertRefused(
                () -> vocabulary.setForProduct(Kind.TAG, TENANT, BRAND, plov.productId(), ids, SUBJECT),
                "VOCABULARY_PRODUCT_LIMIT_REACHED");
        assertThat(vocabulary.forProduct(Kind.TAG, TENANT, BRAND, plov.productId()))
                .as("the refused write changed nothing")
                .hasSize(12);

        jdbc.sql("""
                INSERT INTO catalog.tags (id, tenant_id, code, show_as_badge, show_as_filter)
                SELECT gen_random_uuid(), :t, 'bulk-' || n, true, false FROM generate_series(1, 187) AS n
                """).param("t", TENANT).update();
        assertThat(count("catalog.tags WHERE tenant_id = '" + TENANT + "'")).isEqualTo(200);
        assertRefused(
                () -> vocabulary.create(
                        Kind.TAG, TENANT, new NewEntry("one-too-many", null, true, false, 0, SPICY), SUBJECT),
                "VOCABULARY_TENANT_LIMIT_REACHED");
    }

    @Test
    @DisplayName("a product carries at most forty ingredients")
    void anIngredientListIsBounded() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = product(catalogId, "PLOV");
        List<UUID> ids = IntStream.range(0, 41)
                .mapToObj(n -> vocabulary
                        .create(
                                Kind.INGREDIENT,
                                TENANT,
                                new NewEntry("ing-" + n, null, null, null, n, Map.of("ru", "Ингредиент " + n)),
                                SUBJECT)
                        .row()
                        .id())
                .toList();

        vocabulary.setForProduct(Kind.INGREDIENT, TENANT, BRAND, plov.productId(), ids.subList(0, 40), SUBJECT);
        assertRefused(
                () -> vocabulary.setForProduct(Kind.INGREDIENT, TENANT, BRAND, plov.productId(), ids, SUBJECT),
                "VOCABULARY_PRODUCT_LIMIT_REACHED");
    }

    @Test
    @DisplayName(
            "assigning replaces the set in the order given, refuses repeats and strangers, and keeps what is archived")
    void anAssignmentIsAnOrderedSetThatCannotLeakAcrossTenants() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = product(catalogId, "PLOV");
        UUID a = tag("a", 0);
        UUID b = tag("b", 1);
        UUID c = tag("c", 2);

        vocabulary.setForProduct(Kind.TAG, TENANT, BRAND, plov.productId(), List.of(b, a, c), SUBJECT);
        assertThat(ids(vocabulary.forProduct(Kind.TAG, TENANT, BRAND, plov.productId())))
                .containsExactly(b, a, c);
        vocabulary.setForProduct(Kind.TAG, TENANT, BRAND, plov.productId(), List.of(c, a), SUBJECT);
        assertThat(ids(vocabulary.forProduct(Kind.TAG, TENANT, BRAND, plov.productId())))
                .as("replaced, not merged")
                .containsExactly(c, a);

        assertRefused(
                () -> vocabulary.setForProduct(Kind.TAG, TENANT, BRAND, plov.productId(), List.of(a, a), SUBJECT),
                "VOCABULARY_ENTRY_DUPLICATED");
        UUID foreign = vocabulary
                .create(Kind.TAG, OTHER_TENANT, new NewEntry("theirs", null, true, false, 0, SPICY), SUBJECT)
                .row()
                .id();
        assertRefused(
                () -> vocabulary.setForProduct(Kind.TAG, TENANT, BRAND, plov.productId(), List.of(a, foreign), SUBJECT),
                "VOCABULARY_ENTRY_UNKNOWN");
        assertThat(catchThrowable(() -> jdbc.sql("""
                                INSERT INTO catalog.product_tags (id, tenant_id, brand_id, product_id, tag_id)
                                VALUES (gen_random_uuid(), :t, :b, :p, :tag)
                                """)
                        .param("t", TENANT)
                        .param("b", BRAND)
                        .param("p", plov.productId())
                        .param("tag", foreign)
                        .update()))
                .as(
                        "the composite foreign key refuses another tenant's tag even from a caller that skipped the service")
                .hasMessageContaining("fk_product_tag_tag");
        assertThat(catchThrowable(() -> jdbc.sql("""
                                INSERT INTO catalog.product_tags (id, tenant_id, brand_id, product_id, tag_id)
                                VALUES (gen_random_uuid(), :t, :b, :p, :tag)
                                """)
                        .param("t", TENANT)
                        .param("b", SECOND_BRAND)
                        .param("p", plov.productId())
                        .param("tag", b)
                        .update()))
                .as("and an assignment cannot name a product of another brand")
                .hasMessageContaining("fk_product_tag_product");
        assertThat(catchThrowable(() -> vocabulary.setForProduct(
                        Kind.TAG, TENANT, SECOND_BRAND, plov.productId(), List.of(a), SUBJECT)))
                .isInstanceOf(ApiException.class);

        vocabulary.archive(Kind.TAG, TENANT, a, 1, SUBJECT);
        vocabulary.setForProduct(Kind.TAG, TENANT, BRAND, plov.productId(), List.of(a), SUBJECT);
        assertThat(ids(vocabulary.forProduct(Kind.TAG, TENANT, BRAND, plov.productId())))
                .as("an archived tag the product already carries may stay while the author changes the rest")
                .containsExactly(a);
        UUID retired = tag("retired", 8);
        vocabulary.archive(Kind.TAG, TENANT, retired, 1, SUBJECT);
        assertRefused(
                () -> vocabulary.setForProduct(Kind.TAG, TENANT, BRAND, plov.productId(), List.of(a, retired), SUBJECT),
                "VOCABULARY_ENTRY_ARCHIVED");
    }

    @Test
    @DisplayName("every write is audited with ids, codes and counts, and never with the wording")
    void auditFactsCarryNoWording() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = product(catalogId, "PLOV");
        EntryView spicy =
                vocabulary.create(Kind.TAG, TENANT, new NewEntry("spicy", "FLAME", true, true, 0, SPICY), SUBJECT);
        vocabulary.update(
                Kind.TAG,
                TENANT,
                spicy.row().id(),
                new EntryEdit(1, null, null, null, 3, Map.of("en", "Hot")),
                SUBJECT);
        vocabulary.setForProduct(
                Kind.TAG, TENANT, BRAND, plov.productId(), List.of(spicy.row().id()), SUBJECT);
        vocabulary.archive(Kind.TAG, TENANT, spicy.row().id(), 2, SUBJECT);

        List<String> actions = jdbc.sql("SELECT action_code FROM audit.audit_events ORDER BY recorded_at, id")
                .query(String.class)
                .list();
        assertThat(actions)
                .contains(
                        "catalog.tag.created",
                        "catalog.tag.updated",
                        "catalog.product.tags-set",
                        "catalog.tag.archived");
        String documents = String.join(
                " ",
                jdbc.sql("SELECT change_document::text FROM audit.audit_events")
                        .query(String.class)
                        .list());
        assertThat(documents).contains("spicy").contains(spicy.row().id().toString());
        assertThat(documents).doesNotContain("Острое").doesNotContain("Achchiq").doesNotContain("Hot");
    }

    // ---------------------------------------------------------------------------- helpers

    private UUID tag(String code, int sortOrder) {
        return vocabulary
                .create(Kind.TAG, TENANT, new NewEntry(code, null, true, false, sortOrder, SPICY), SUBJECT)
                .row()
                .id();
    }

    private UUID b2() {
        return tag("fresh", 9);
    }

    private static List<UUID> ids(List<EntryView> views) {
        return views.stream().map(view -> view.row().id()).toList();
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String findingCode) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).properties()).containsEntry("findingCode", findingCode);
    }

    private CatalogAuthoringService.ProductCreated product(UUID catalogId, String code) {
        return productOf(BRAND, LOCATION, catalogId, code);
    }

    private CatalogAuthoringService.ProductCreated productOf(UUID brand, UUID location, UUID catalogId, String code) {
        var created = authoring.createProduct(
                TENANT, brand, catalogId, code, code, null, LOCALE, "SKU-" + code, "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT,
                brand,
                location,
                created.defaultVariantId(),
                OfferingStatus.AVAILABLE,
                List.of("DELIVERY", "PICKUP"));
        return created;
    }

    private void offer(UUID variantId) {
        authoring.setOffering(
                TENANT, BRAND, LOCATION, variantId, OfferingStatus.AVAILABLE, List.of("DELIVERY", "PICKUP"));
    }

    private StorefrontMenu menu(String locale) {
        return storefront.menuFor(TENANT, BRAND, LOCATION, locale, "STOREFRONT").orElseThrow();
    }

    private static MenuProduct onlyProduct(StorefrontMenu menu) {
        return menu.products().getFirst();
    }

    private List<String> tagCodesPublishedFor(UUID brand) {
        return jdbc.sql("""
                        SELECT i.immutable_content_json ->> 'code'
                          FROM catalog.publication_items i
                          JOIN catalog.publications p ON p.id = i.publication_id
                         WHERE i.entity_type = 'TAG' AND i.brand_id = :brand AND p.status = 'PUBLISHED'
                         ORDER BY 1
                        """).param("brand", brand).query(String.class).list();
    }

    private long count(String tableAndWhere) {
        return jdbc.sql("SELECT count(*) FROM " + tableAndWhere)
                .query(Long.class)
                .single();
    }

    private void insertTenancy() {
        insertTenant(TENANT, "vocab-tenant");
        insertTenant(OTHER_TENANT, "vocab-other-tenant");
        insertBrand(TENANT, BRAND, "MAIN", "main");
        insertBrand(TENANT, SECOND_BRAND, "SECOND", "second");
        insertBrand(OTHER_TENANT, OTHER_TENANT_BRAND, "MAIN", "other-main");
        insertLocation(LOCATION, BRAND, "MAIN01", "main-01");
        insertLocation(SECOND_LOCATION, SECOND_BRAND, "SECOND01", "second-01");
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'STOREFRONT', 'ACTIVE')
                """).param("id", UUID.randomUUID()).param("tenantId", TENANT).update();
    }

    private void insertLocation(UUID id, UUID brand, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.locations (
                    id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brand)
                .param("code", code)
                .param("slug", slug)
                .update();
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
