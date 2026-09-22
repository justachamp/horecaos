package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService.ProductCreated;
import uz.horecaos.platform.catalog.application.CommentPresetService;
import uz.horecaos.platform.catalog.application.CommentPresetService.NewPreset;
import uz.horecaos.platform.catalog.application.CommentPresetService.PresetEdit;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore.PresetRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore.ProductPresetRow;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Row 2.1b: preset product comments. A tenant-wide coded
 * kitchen-instruction vocabulary (never brand-scoped, see
 * {@code V0378__catalog_comment_presets.sql}), attachable to a product, and
 * readable back as the coded value a KDS and a POS export would round-trip.
 *
 * <p>Built ahead of gap map row 4.7's neighbouring, still-ADR-0016-blocked
 * vocabularies (attributes, tags, ingredients) — deliberately narrower, see
 * {@link CommentPresetService}'s own doc for why that is safe.
 */
class CommentPresetTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();

    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();

    private static final String LOCALE = "uz";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String ACTOR_SUBJECT = "comment-preset-test";
    private static final FiscalClassification UNCLASSIFIED = FiscalClassification.unclassified();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCatalogStore catalogStore;
    private CatalogAuthoringService authoring;
    private JdbcCommentPresetStore presetStore;
    private CommentPresetService presets;
    private UUID catalogId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the comment preset tests");
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
        jdbc.sql("TRUNCATE TABLE catalog.product_comment_presets, catalog.comment_presets, "
                        + "catalog.translations, catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenancy(TENANT, BRAND, "comment-preset-tenant", "MAIN");
        insertTenancy(OTHER_TENANT, OTHER_BRAND, "comment-preset-other-tenant", "OTHER-MAIN");

        catalogStore = new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        CommercialDefaults.Wired commercial = CommercialDefaults.wire(jdbc, Clock.systemUTC());
        authoring = new CatalogAuthoringService(
                catalogStore,
                new uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder(
                        jdbc, JsonMapper.builder().build()),
                commercial.entitlements(),
                commercial.usage(),
                Clock.systemUTC());
        presetStore = new JdbcCommentPresetStore(jdbc);
        presets = new CommentPresetService(
                presetStore,
                catalogStore,
                new uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder(
                        jdbc, JsonMapper.builder().build()),
                Clock.systemUTC());
        catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy", LOCALE);
    }

    // ------------------------------------------------------------------ vocabulary

    @Test
    @DisplayName("a created preset appears in the tenant's list")
    void createdPresetAppearsInTheList() {
        presets.create(
                TENANT, newPreset("NO_ONION", "Без лука", "Piyozsiz", "No onion", "MOD_NO_ONION"), ACTOR_SUBJECT);

        assertThat(presets.list(TENANT)).extracting(PresetRow::code).containsExactly("NO_ONION");
    }

    @Test
    @DisplayName("two presets sharing a code for one tenant is refused")
    void aDuplicateCodeIsRefused() {
        presets.create(TENANT, newPreset("NO_ONION", "Без лука", "Piyozsiz", "No onion", null), ACTOR_SUBJECT);

        Throwable failure = catchThrowable(() -> presets.create(
                TENANT, newPreset("NO_ONION", "Дубликат", "Dublikat", "Duplicate", null), ACTOR_SUBJECT));

        assertThat(failure).isInstanceOf(ApiException.class);
        assertThat(((ApiException) failure).errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
    }

    @Test
    @DisplayName("a POS modifier mapping is optional — a preset with none still creates and lists fine")
    void posModifierCodeIsOptional() {
        PresetRow created = presets.create(
                TENANT,
                newPreset("WELL_DONE", "Хорошо прожарить", "Yaxshi qovurish", "Well done", null),
                ACTOR_SUBJECT);

        assertThat(created.posModifierCode()).isNull();
        assertThat(presets.list(TENANT).getFirst().posModifierCode()).isNull();
    }

    @Test
    @DisplayName("updating a preset's labels, POS mapping and status persists, at a new version")
    void updatingAPresetPersists() {
        PresetRow created =
                presets.create(TENANT, newPreset("NO_ONION", "Без лука", "Piyozsiz", "No onion", null), ACTOR_SUBJECT);

        PresetRow updated = presets.update(
                TENANT,
                created.id(),
                new PresetEdit("Без лука!", "Piyozsiz!", "No onion!", "MOD_NO_ONION", 3, "ARCHIVED", created.version()),
                ACTOR_SUBJECT);

        assertThat(updated.labelRu()).isEqualTo("Без лука!");
        assertThat(updated.posModifierCode()).isEqualTo("MOD_NO_ONION");
        assertThat(updated.status()).isEqualTo("ARCHIVED");
        assertThat(updated.version()).isEqualTo(2);
        assertThat(presets.list(TENANT).getFirst().status()).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName(
            "editing a preset at the wrong version is refused rather than silently overwriting a concurrent change")
    void updatingAtTheWrongVersionIsRefused() {
        PresetRow created =
                presets.create(TENANT, newPreset("NO_ONION", "Без лука", "Piyozsiz", "No onion", null), ACTOR_SUBJECT);

        Throwable failure = catchThrowable(() -> presets.update(
                TENANT,
                created.id(),
                new PresetEdit("x", "x", "x", null, 0, "ACTIVE", created.version() + 1),
                ACTOR_SUBJECT));

        assertThat(failure).isInstanceOf(ApiException.class);
        assertThat(((ApiException) failure).errorCode()).isEqualTo(ErrorCode.STALE_VERSION);
    }

    @Test
    @DisplayName("another tenant's presets never appear in this tenant's list")
    void anotherTenantsPresetsNeverAppear() {
        presets.create(TENANT, newPreset("NO_ONION", "Без лука", "Piyozsiz", "No onion", null), ACTOR_SUBJECT);
        presets.create(OTHER_TENANT, newPreset("NO_ONION", "Другой", "Boshqa", "Other", null), ACTOR_SUBJECT);

        assertThat(presets.list(TENANT)).hasSize(1);
        assertThat(presets.list(OTHER_TENANT)).hasSize(1);
    }

    // ---------------------------------------------------------------- attachment

    @Test
    @DisplayName("attaching a preset makes it appear on the product's list")
    void attachingAddsToTheProductsList() {
        ProductCreated burger = createProduct("BURGER", "Burger");
        PresetRow noOnion = presets.create(
                TENANT, newPreset("NO_ONION", "Без лука", "Piyozsiz", "No onion", "MOD_NO_ONION"), ACTOR_SUBJECT);

        presets.attachToProduct(TENANT, BRAND, burger.productId(), noOnion.id(), 0, ACTOR_SUBJECT);

        assertThat(presets.listForProduct(TENANT, BRAND, burger.productId()))
                .extracting(ProductPresetRow::code)
                .containsExactly("NO_ONION");
    }

    @Test
    @DisplayName("attaching an already-attached preset re-sorts it rather than creating a second row")
    void reattachingReordersRatherThanDuplicating() {
        ProductCreated burger = createProduct("BURGER", "Burger");
        PresetRow noOnion =
                presets.create(TENANT, newPreset("NO_ONION", "Без лука", "Piyozsiz", "No onion", null), ACTOR_SUBJECT);
        presets.attachToProduct(TENANT, BRAND, burger.productId(), noOnion.id(), 0, ACTOR_SUBJECT);

        presets.attachToProduct(TENANT, BRAND, burger.productId(), noOnion.id(), 9, ACTOR_SUBJECT);

        List<ProductPresetRow> rows = presets.listForProduct(TENANT, BRAND, burger.productId());
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().sortOrder()).isEqualTo(9);
    }

    @Test
    @DisplayName("detaching removes the preset from the product, and detaching again is a no-op rather than an error")
    void detachingIsIdempotent() {
        ProductCreated burger = createProduct("BURGER", "Burger");
        PresetRow noOnion =
                presets.create(TENANT, newPreset("NO_ONION", "Без лука", "Piyozsiz", "No onion", null), ACTOR_SUBJECT);
        presets.attachToProduct(TENANT, BRAND, burger.productId(), noOnion.id(), 0, ACTOR_SUBJECT);

        presets.detachFromProduct(TENANT, BRAND, burger.productId(), noOnion.id(), ACTOR_SUBJECT);
        presets.detachFromProduct(TENANT, BRAND, burger.productId(), noOnion.id(), ACTOR_SUBJECT);

        assertThat(presets.listForProduct(TENANT, BRAND, burger.productId())).isEmpty();
    }

    @Test
    @DisplayName("attaching a preset to a product this brand does not have is refused")
    void attachingToAnUnknownProductIsRefused() {
        PresetRow noOnion =
                presets.create(TENANT, newPreset("NO_ONION", "Без лука", "Piyozsiz", "No onion", null), ACTOR_SUBJECT);

        assertThatThrownBy(
                        () -> presets.attachToProduct(TENANT, BRAND, UUID.randomUUID(), noOnion.id(), 0, ACTOR_SUBJECT))
                .isInstanceOf(CommentPresetService.UnknownProductException.class);
    }

    @Test
    @DisplayName("attaching a preset this tenant does not have is refused")
    void attachingAnUnknownPresetIsRefused() {
        ProductCreated burger = createProduct("BURGER", "Burger");

        assertThatThrownBy(() ->
                        presets.attachToProduct(TENANT, BRAND, burger.productId(), UUID.randomUUID(), 0, ACTOR_SUBJECT))
                .isInstanceOf(CommentPresetService.UnknownPresetException.class);
    }

    @Test
    @DisplayName("another tenant's preset cannot be attached to this tenant's product, even by id")
    void anotherTenantsPresetCannotBeAttached() {
        ProductCreated burger = createProduct("BURGER", "Burger");
        PresetRow otherTenantsPreset =
                presets.create(OTHER_TENANT, newPreset("NO_ONION", "Другой", "Boshqa", "Other", null), ACTOR_SUBJECT);

        assertThatThrownBy(() -> presets.attachToProduct(
                        TENANT, BRAND, burger.productId(), otherTenantsPreset.id(), 0, ACTOR_SUBJECT))
                .isInstanceOf(CommentPresetService.UnknownPresetException.class);
    }

    @Test
    @DisplayName("attaching and detaching a preset each record an audit fact; a no-op detach records nothing")
    void attachAndDetachAreAudited() {
        ProductCreated burger = createProduct("BURGER", "Burger");
        PresetRow noOnion =
                presets.create(TENANT, newPreset("NO_ONION", "Без лука", "Piyozsiz", "No onion", null), ACTOR_SUBJECT);
        List<AuditFact> audited = new ArrayList<>();
        CommentPresetService capturing =
                new CommentPresetService(presetStore, catalogStore, audited::add, Clock.systemUTC());

        capturing.attachToProduct(TENANT, BRAND, burger.productId(), noOnion.id(), 0, ACTOR_SUBJECT);
        assertThat(audited).extracting(AuditFact::actionCode).contains("catalog.comment-preset.attached");

        audited.clear();
        capturing.detachFromProduct(TENANT, BRAND, burger.productId(), noOnion.id(), ACTOR_SUBJECT);
        assertThat(audited).extracting(AuditFact::actionCode).contains("catalog.comment-preset.detached");

        audited.clear();
        capturing.detachFromProduct(TENANT, BRAND, burger.productId(), noOnion.id(), ACTOR_SUBJECT);
        assertThat(audited).isEmpty();
    }

    // ------------------------------------------------------------------------ fixture

    private static NewPreset newPreset(
            String code, String labelRu, String labelUz, String labelEn, @Nullable String posModifierCode) {
        return new NewPreset(code, labelRu, labelUz, labelEn, posModifierCode, 0);
    }

    private ProductCreated createProduct(String code, String name) {
        return authoring.createProduct(
                TENANT, BRAND, catalogId, code, name, null, LOCALE, "SKU-" + code, "PIECE", UNCLASSIFIED, ACTOR);
    }

    private void insertTenancy(UUID tenantId, UUID brandId, String tenantSlug, String brandCode) {
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
    }
}
