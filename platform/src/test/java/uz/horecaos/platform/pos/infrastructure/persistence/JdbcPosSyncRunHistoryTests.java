package uz.horecaos.platform.pos.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosSyncStore.RunDetailRow;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosSyncStore.RunSummaryRow;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/**
 * The run-history list and detail reads (gap-map row 4.5a) — there was no
 * listing {@code GET} at all before this wave, only starting a run and
 * reading one run's own differences.
 */
class JdbcPosSyncRunHistoryTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac123001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac123002");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac123003");
    private static final UUID BINDING = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac123004");
    private static final UUID OTHER_BINDING = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac123005");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcPosSyncStore store;

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
        store = new JdbcPosSyncStore(
                jdbc, tools.jackson.databind.json.JsonMapper.builder().build());

        jdbc.sql("DELETE FROM integration.pos_sync_runs WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM integration.bindings WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM integration.installations WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.brands WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id = :t").param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'pos-run-history', 'Legal', 'POS run history', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'RUN_HISTORY_BRAND', 'run-history-brand', 'Run history brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :t, 'POS', 'clopos', 'clopos-open-api-v2', 'Pilot', 'ACTIVE')
                """).param("id", INSTALLATION).param("t", TENANT).update();
        for (UUID binding : List.of(BINDING, OTHER_BINDING)) {
            jdbc.sql("""
                    INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                    VALUES (:id, :t, :installationId, :brandId, 'ACTIVE')
                    """)
                    .param("id", binding)
                    .param("t", TENANT)
                    .param("installationId", INSTALLATION)
                    .param("brandId", BRAND)
                    .update();
        }
    }

    private UUID openRunAt(UUID bindingId, Instant startedAt) {
        return store.openRun(TENANT, bindingId, "MANUAL", true, "test-adapter-1", 1, startedAt);
    }

    @Test
    @DisplayName("run history lists newest first, scoped to the binding asked for")
    void listsNewestFirstScopedToBinding() {
        UUID older = openRunAt(BINDING, Instant.parse("2026-09-01T00:00:00Z"));
        UUID newer = openRunAt(BINDING, Instant.parse("2026-09-02T00:00:00Z"));
        openRunAt(OTHER_BINDING, Instant.parse("2026-09-03T00:00:00Z"));

        List<RunSummaryRow> page = store.listRuns(TENANT, BINDING, 50, null);

        assertThat(page).extracting(RunSummaryRow::id).containsExactly(newer, older);
    }

    @Test
    @DisplayName("a short page carries no cursor, and a full page's cursor resumes exactly where it stopped")
    void cursorResumesWithoutSkippingOrRepeating() {
        UUID first = openRunAt(BINDING, Instant.parse("2026-09-01T00:00:00Z"));
        UUID second = openRunAt(BINDING, Instant.parse("2026-09-02T00:00:00Z"));
        UUID third = openRunAt(BINDING, Instant.parse("2026-09-03T00:00:00Z"));

        List<RunSummaryRow> firstPage = store.listRuns(TENANT, BINDING, 2, null);
        assertThat(firstPage).extracting(RunSummaryRow::id).containsExactly(third, second);

        String cursor = JdbcPosSyncStore.cursorFor(firstPage.getLast());
        List<RunSummaryRow> secondPage = store.listRuns(TENANT, BINDING, 2, cursor);
        assertThat(secondPage).extracting(RunSummaryRow::id).containsExactly(first);
    }

    @Test
    @DisplayName("a malformed cursor is a client error, not a 500")
    void malformedCursorIsRefused() {
        assertThatThrownBy(() -> store.listRuns(TENANT, BINDING, 10, "not-a-cursor"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("run detail carries every stage timestamp and count, and 404s for a run this tenant never opened")
    void runDetailCarriesEveryField() {
        UUID runId = openRunAt(BINDING, Instant.parse("2026-09-05T00:00:00Z"));
        store.markStatus(TENANT, runId, "STAGED", "fetched_at", Instant.parse("2026-09-05T00:01:00Z"));
        store.markFailed(TENANT, runId, "PROVIDER_TIMEOUT", "Timed out reading the menu");

        RunDetailRow detail = store.findRunDetail(TENANT, runId).orElseThrow();

        assertThat(detail.id()).isEqualTo(runId);
        assertThat(detail.bindingId()).isEqualTo(BINDING);
        assertThat(detail.status()).isEqualTo("FAILED");
        assertThat(detail.adapterVersion()).isEqualTo("test-adapter-1");
        assertThat(detail.fieldPolicyVersion()).isEqualTo(1);
        assertThat(detail.fetchedAt()).isEqualTo(Instant.parse("2026-09-05T00:01:00Z"));
        assertThat(detail.lastErrorCode()).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(detail.lastError()).isEqualTo("Timed out reading the menu");

        assertThat(store.findRunDetail(TENANT, UUID.randomUUID())).isEmpty();
    }
}
