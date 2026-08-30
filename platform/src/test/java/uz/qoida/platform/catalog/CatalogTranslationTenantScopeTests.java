package uz.qoida.platform.catalog;

import javax.sql.DataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;

import tools.jackson.databind.json.JsonMapper;

import uz.qoida.platform.catalog.application.CatalogAuthoringService;
import uz.qoida.platform.catalog.domain.CatalogEntities.EntityType;
import uz.qoida.platform.catalog.domain.FiscalClassification;
import uz.qoida.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.qoida.platform.support.TestDatabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cross-tenant write no foreign key could have caught.
 *
 * <p>Every other tenant-blind reference on this platform is a foreign key with
 * the tenant column missing, and {@code pg_constraint} can be asked for all of
 * them at once — that is what V0077 did. {@code catalog.translations} is the
 * shape that sweep cannot see: {@code entity_id} is polymorphic across six
 * catalog tables, so it carries no foreign key at all, and there is no constraint
 * anywhere to inspect.
 *
 * <p>What the absence bought was worse than a dangling pointer. The primary key
 * was {@code (entity_type, entity_id, locale)} with {@code tenant_id NOT NULL}
 * sitting outside it, and the write is an upsert. A tenant passing another
 * tenant's product id collided on a key that named no tenant, took the
 * {@code DO UPDATE} branch, and replaced the name and description that tenant's
 * customers were reading — leaving the victim's {@code tenant_id} on the row, so
 * nothing afterwards looked wrong.
 *
 * <p>Two halves, and each is tested here on its own, because each fails
 * differently and only one of them can be expressed as a key.
 */
class CatalogTranslationTenantScopeTests {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID BRAND_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID BRAND_B = UUID.randomUUID();
    private static final String LOCALE = "uz";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final FiscalClassification UNCLASSIFIED = FiscalClassification.unclassified();

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private JdbcCatalogStore store;
    private CatalogAuthoringService authoring;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for catalog translation scope tests");
        db = TestDatabase.migrated();
        jdbcUrl = db.jdbcUrl();
        username = db.username();
        password = db.password();
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
        jdbc.sql("TRUNCATE TABLE catalog.translations, catalog.variants, catalog.products, "
                + "catalog.catalogs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        insertTenant(TENANT_A, "translation-tenant-a");
        insertBrand(BRAND_A, TENANT_A, "A");
        insertTenant(TENANT_B, "translation-tenant-b");
        insertBrand(BRAND_B, TENANT_B, "B");

        store = new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        authoring = new CatalogAuthoringService(store);
    }

    /**
     * The rewrite, refused.
     *
     * <p>This is the whole defect in one call. Tenant B translates tenant A's
     * product id, and before V0077 the row that changed was tenant A's. The
     * assertion that matters is the last one: A's text is still A's.
     */
    @Test
    @DisplayName("one tenant cannot overwrite another tenant's translation through the upsert")
    void aCrossTenantUpsertDoesNotRewriteTheOtherTenantsText() {
        UUID catalogA = authoring.createCatalog(TENANT_A, BRAND_A, "MAIN", "Asosiy menyu", LOCALE);
        UUID productA = authoring.createProduct(TENANT_A, BRAND_A, catalogA, "PLOV",
                "Osh", "Qo'y go'shti bilan", LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR)
                .productId();

        assertThatThrownBy(() -> authoring.translate(TENANT_B, BRAND_B, EntityType.PRODUCT,
                productA, LOCALE, "Arzon osh", "Boshqa ijarachining matni"))
                .isInstanceOf(CatalogAuthoringService.UnknownCatalogEntityException.class);

        assertThat(translation(TENANT_A, productA))
                .containsEntry("name", "Osh")
                .containsEntry("description", "Qo'y go'shti bilan");
    }

    /**
     * The service check, on its own.
     *
     * <p>The key alone would let this write succeed as a new row of B's, and the
     * fact that it succeeded would tell B the id is real. The refusal has to be
     * indistinguishable from the refusal for a uuid that exists nowhere, or the
     * endpoint is an existence oracle for catalog ids — the second consequence
     * V0069 named, in a module where the ids are menu items rather than scanned
     * tax documents.
     */
    @Test
    @DisplayName("a translation against an unknown id and against another tenant's id are refused alike")
    void notYoursAndDoesNotExistGiveTheSameAnswer() {
        UUID catalogA = authoring.createCatalog(TENANT_A, BRAND_A, "MAIN", "Asosiy menyu", LOCALE);
        UUID productA = authoring.createProduct(TENANT_A, BRAND_A, catalogA, "PLOV",
                "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR).productId();
        UUID nowhere = UUID.randomUUID();

        Throwable foreign = catchTranslate(productA);
        Throwable absent = catchTranslate(nowhere);

        assertThat(foreign).isInstanceOf(CatalogAuthoringService.UnknownCatalogEntityException.class);
        assertThat(absent).isInstanceOf(CatalogAuthoringService.UnknownCatalogEntityException.class);
        assertThat(foreign.getMessage().replace(productA.toString(), "ID"))
                .as("the two refusals must not differ in anything but the id the caller already sent")
                .isEqualTo(absent.getMessage().replace(nowhere.toString(), "ID"));

        assertThat(jdbc.sql("SELECT count(*) FROM catalog.translations WHERE tenant_id = :t")
                .param("t", TENANT_B).query(Integer.class).single())
                .as("neither refusal may leave a row behind")
                .isZero();
    }

    /**
     * The key, on its own.
     *
     * <p>Written against the store rather than the service so it exercises the
     * schema and not the guard above it — this is what holds when the resolved
     * path is bypassed by a repair script or a second write path. Two tenants may
     * now hold a translation for the same uuid, which is the uniqueness the old
     * key was silently enforcing across tenants, and each row keeps its own text.
     */
    @Test
    @DisplayName("the key admits the same entity id in two tenants and keeps the rows apart")
    void theWidenedKeyKeepsTwoTenantsRowsApart() {
        UUID sharedId = UUID.randomUUID();

        store.upsertTranslation(TENANT_A, BRAND_A, EntityType.PRODUCT, sharedId, LOCALE,
                "Osh", "A ning matni");
        store.upsertTranslation(TENANT_B, BRAND_B, EntityType.PRODUCT, sharedId, LOCALE,
                "Arzon osh", "B ning matni");

        assertThat(translation(TENANT_A, sharedId)).containsEntry("name", "Osh");
        assertThat(translation(TENANT_B, sharedId)).containsEntry("name", "Arzon osh");
        assertThat(jdbc.sql("SELECT count(*) FROM catalog.translations WHERE entity_id = :id")
                .param("id", sharedId).query(Integer.class).single())
                .isEqualTo(2);
    }

    /**
     * The key is the primary key, not an extra index beside the old one.
     *
     * <p>Asserted against the catalog because the upsert's {@code ON CONFLICT}
     * target has to name exactly these columns: leave the old key in place as well
     * and the conflict target could still be written the old way, which would put
     * the defect back with a passing test above it.
     */
    @Test
    @DisplayName("the primary key of catalog.translations names the tenant")
    void thePrimaryKeyNamesTheTenant() {
        List<String> keys = jdbc.sql("""
                SELECT pg_get_constraintdef(con.oid)
                  FROM pg_constraint con
                 WHERE con.conrelid = 'catalog.translations'::regclass
                   AND con.contype IN ('p', 'u')
                """).query(String.class).list();

        assertThat(keys).singleElement().asString()
                .isEqualTo("PRIMARY KEY (tenant_id, entity_type, entity_id, locale)");
    }

    private Throwable catchTranslate(UUID entityId) {
        try {
            authoring.translate(TENANT_B, BRAND_B, EntityType.PRODUCT, entityId, LOCALE,
                    "Arzon osh", null);
            return null;
        } catch (Throwable thrown) {
            return thrown;
        }
    }

    private Map<String, Object> translation(UUID tenantId, UUID entityId) {
        return jdbc.sql("""
                SELECT name, description FROM catalog.translations
                 WHERE tenant_id = :tenantId AND entity_type = 'PRODUCT'
                   AND entity_id = :entityId AND locale = :locale
                """)
                .param("tenantId", tenantId).param("entityId", entityId).param("locale", LOCALE)
                .query().singleRow();
    }

    private void insertTenant(UUID tenantId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone,
                    status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
    }

    private void insertBrand(UUID brandId, UUID tenantId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, lower(:code), 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("tenantId", tenantId).param("code", code).update();
    }
}
