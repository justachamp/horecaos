package uz.horecaos.platform.catalog.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@link JdbcCatalogImportStore}'s brand scoping (row 4.5b) — {@link #run}
 * and {@link #rows} must answer for the caller's own brand only. A tenant
 * that runs several brands (ADR 0016) can have staff whose {@code
 * CATALOG_READ} grant covers one brand and not another; a runId from the
 * brand they cannot see must read exactly like a runId that does not exist.
 */
class JdbcCatalogImportStoreTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac126001");
    private static final UUID BRAND_A = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac126002");
    private static final UUID BRAND_B = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac126003");
    private static final UUID CATALOG_A = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac126004");
    private static final UUID CATALOG_B = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac126005");

    private static final Instant NOW = Instant.parse("2026-09-22T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCatalogImportStore store;
    private UUID runForBrandB;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
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
        store = new JdbcCatalogImportStore(jdbc);

        jdbc.sql("DELETE FROM catalog.import_run_rows WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM catalog.import_runs WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM catalog.catalogs WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.brands WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id = :t").param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'catalog-import-store', 'Legal', 'Catalog import store', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        insertBrand(BRAND_A, "BRAND_A", "brand-a");
        insertBrand(BRAND_B, "BRAND_B", "brand-b");
        insertCatalog(CATALOG_A, BRAND_A, "CATALOG_A");
        insertCatalog(CATALOG_B, BRAND_B, "CATALOG_B");

        runForBrandB = Ids.newId();
        store.insertQueuedRun(
                runForBrandB, TENANT, BRAND_B, CATALOG_B, false, "brand-b.csv", "content", "operator-b", 1, NOW);
        store.insertRow(TENANT, runForBrandB, 1, "CREATED", UUID.randomUUID(), UUID.randomUUID(), null, NOW);
    }

    @Test
    @DisplayName("run() answers for the run's own brand")
    void runAnswersForItsOwnBrand() {
        assertThat(store.run(TENANT, BRAND_B, runForBrandB)).isPresent();
    }

    @Test
    @DisplayName("run() refuses a sibling brand's runId under the same tenant -- exactly as if it did not exist")
    void runRefusesASiblingBrandsRunId() {
        assertThat(store.run(TENANT, BRAND_A, runForBrandB))
                .as("brand A's own CATALOG_READ grant must not surface brand B's import run")
                .isEmpty();
    }

    @Test
    @DisplayName("rows() answers for the run's own brand")
    void rowsAnswersForItsOwnBrand() {
        List<JdbcCatalogImportStore.ImportRowView> rows = store.rows(TENANT, BRAND_B, runForBrandB, 500, 0);

        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("rows() refuses a sibling brand's runId under the same tenant -- exactly as if it did not exist")
    void rowsRefusesASiblingBrandsRunId() {
        List<JdbcCatalogImportStore.ImportRowView> rows = store.rows(TENANT, BRAND_A, runForBrandB, 500, 0);

        assertThat(rows)
                .as("brand A's own CATALOG_READ grant must not surface brand B's per-row import report")
                .isEmpty();
    }

    private void insertBrand(UUID brandId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, :code, :slug, :code, 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("t", TENANT)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private void insertCatalog(UUID catalogId, UUID brandId, String code) {
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status, version)
                VALUES (:id, :t, :brandId, :code, :code, 'ACTIVE', 1)
                """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("brandId", brandId)
                .param("code", code)
                .update();
    }
}
