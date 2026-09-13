package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
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
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;

/**
 * catalog.md §4.3 — categories were write-once before this wave: no {@code
 * PUT} changed {@code parentCategoryId}, {@code sort_order}, {@code status}
 * or {@code code}, and {@link JdbcCatalogStore} carried only {@code INSERT
 * INTO catalog.categories}. This class is the read-through-a-real-database
 * evidence for the {@code updateCategory}/{@code archiveCategory} pair this
 * wave adds, including the cycle refusal {@code CatalogValidator} could
 * previously only report after the fact, at publication.
 */
class CategoryAuthoringTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
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
                "Docker is required for the category authoring tests");
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
        jdbc.sql("TRUNCATE TABLE catalog.translations, catalog.categories, "
                        + "catalog.catalog_products, catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone,
                    status, version)
                VALUES (:id, 'category-authoring-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main-brand', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

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
    @DisplayName("update reparents, renames the code of, and re-sorts an existing category")
    void updateReparentsRenamesTheCodeOfAndResortsACategory() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        UUID hot = authoring.createCategory(TENANT, BRAND, catalogId, null, "HOT", "Issiq", LOCALE, 0);
        UUID cold = authoring.createCategory(TENANT, BRAND, catalogId, null, "COLD", "Sovuq", LOCALE, 1);

        authoring.updateCategory(TENANT, BRAND, catalogId, cold, hot, "COLD-RENAMED", 5);

        var updated = store.categoriesInCatalog(TENANT, BRAND, catalogId).stream()
                .filter(category -> category.id().equals(cold))
                .findFirst()
                .orElseThrow();
        assertThat(updated.parentCategoryId()).isEqualTo(hot);
        assertThat(updated.code()).isEqualTo("COLD-RENAMED");
        assertThat(updated.sortOrder()).isEqualTo(5);
        assertThat(updated.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("update refuses a reparent that would make the category its own grandparent")
    void updateRefusesALongerCycle() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        UUID grandparent = authoring.createCategory(TENANT, BRAND, catalogId, null, "A", "A", LOCALE, 0);
        UUID parent = authoring.createCategory(TENANT, BRAND, catalogId, grandparent, "B", "B", LOCALE, 0);

        // Reparenting the grandparent under its own child's child would close
        // the loop A -> B -> A. The database's own ck_category_not_self_parent
        // only refuses the one-step case; this is the longer cycle only this
        // wave's application-level walk catches before the write happens.
        assertThatThrownBy(() -> authoring.updateCategory(TENANT, BRAND, catalogId, grandparent, parent, "A", 0))
                .isInstanceOf(CatalogAuthoringService.CategoryTreeCycleException.class);

        // Refused, not partially applied: the row is exactly as it was.
        var unchanged = store.categoriesInCatalog(TENANT, BRAND, catalogId).stream()
                .filter(category -> category.id().equals(grandparent))
                .findFirst()
                .orElseThrow();
        assertThat(unchanged.parentCategoryId()).isNull();
        assertThat(unchanged.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("update refuses a category naming itself as its own parent")
    void updateRefusesSelfParent() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        UUID category = authoring.createCategory(TENANT, BRAND, catalogId, null, "A", "A", LOCALE, 0);

        assertThatThrownBy(() -> authoring.updateCategory(TENANT, BRAND, catalogId, category, category, "A", 0))
                .isInstanceOf(CatalogAuthoringService.CategoryTreeCycleException.class);
    }

    @Test
    @DisplayName("update on an unknown category throws rather than silently doing nothing")
    void updateOnUnknownCategoryThrows() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);

        assertThatThrownBy(() -> authoring.updateCategory(TENANT, BRAND, catalogId, UUID.randomUUID(), null, "X", 0))
                .isInstanceOf(CatalogAuthoringService.UnknownCatalogEntityException.class);
    }

    @Test
    @DisplayName("archive sets the category to ARCHIVED without touching its products")
    void archiveArchivesACategory() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        UUID category = authoring.createCategory(TENANT, BRAND, catalogId, null, "A", "A", LOCALE, 0);
        var product = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.placeProductInCategory(TENANT, BRAND, category, product.productId(), 0);

        authoring.archiveCategory(TENANT, BRAND, catalogId, category);

        var archived = store.categoriesInCatalog(TENANT, BRAND, catalogId).stream()
                .filter(c -> c.id().equals(category))
                .findFirst()
                .orElseThrow();
        assertThat(archived.status()).isEqualTo(Status.ARCHIVED);
        // Never a hard delete: the product placed in it keeps its row.
        assertThat(store.productIdsByCategory(TENANT, BRAND, catalogId).get(category))
                .containsExactly(product.productId());
    }

    @Test
    @DisplayName("archive on an unknown category throws rather than silently doing nothing")
    void archiveOnUnknownCategoryThrows() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);

        assertThatThrownBy(() -> authoring.archiveCategory(TENANT, BRAND, catalogId, UUID.randomUUID()))
                .isInstanceOf(CatalogAuthoringService.UnknownCatalogEntityException.class);
    }
}
