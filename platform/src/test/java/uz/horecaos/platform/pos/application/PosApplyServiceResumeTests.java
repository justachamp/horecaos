package uz.horecaos.platform.pos.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import uz.horecaos.platform.integration.api.pos.PosSyncRequestedPayload;
import uz.horecaos.platform.integration.api.pos.PosSyncRequester;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosApplyStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * A run interrupted mid-apply resumes rather than double-importing (ADR 0012).
 *
 * <p>This is the property {@code PosApplyService}'s own class doc names and the
 * one thing every other test of it takes for granted: an item already {@code
 * APPLIED} is never touched again, and a resume finishes exactly the items that
 * did not get to run — never re-runs the ones that did, and never leaves a
 * settled run open a second time.
 *
 * <p>The interruption is simulated at the only place it can actually leave
 * evidence: the database. A process dying mid-{@code execute} leaves some apply
 * items {@code APPLIED} and others still {@code PLANNED}, with the run itself
 * still {@code APPLYING} — exactly the row shapes seeded below — because {@link
 * PosApplyService#execute} commits each item on its own rather than inside one
 * transaction spanning the whole run (see that method's own doc for why). A
 * fresh {@link PosApplyService} instance, standing in for the next process that
 * picks the run back up, is what calls {@link PosApplyService#resume}.
 */
class PosApplyServiceResumeTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-7200-7000-8000-0000000b0001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-7200-7000-8000-0000000b0002");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-7200-7000-8000-0000000b0003");
    private static final UUID BINDING = UUID.fromString("018f6f4e-7200-7000-8000-0000000b0004");
    private static final UUID RUN = UUID.fromString("018f6f4e-7200-7000-8000-0000000b0005");
    private static final UUID MAPPED_PRODUCT = UUID.fromString("018f6f4e-7200-7000-8000-0000000b0006");
    private static final UUID MAPPING = UUID.fromString("018f6f4e-7200-7000-8000-0000000b0007");
    private static final UUID ALREADY_APPLIED_ITEM = UUID.fromString("018f6f4e-7200-7000-8000-0000000b0008");
    private static final UUID STILL_PLANNED_ITEM = UUID.fromString("018f6f4e-7200-7000-8000-0000000b0009");
    private static final UUID DIFFERENCE = UUID.fromString("018f6f4e-7200-7000-8000-0000000b000a");

    private static final Instant EARLIER = Instant.parse("2026-08-24T04:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-24T05:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private PosApplyService applyService;

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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);

        jdbc.sql("DELETE FROM integration.pos_sync_apply_items WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM integration.pos_sync_differences WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM integration.pos_sync_runs WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();
        jdbc.sql("DELETE FROM integration.provider_entity_mappings WHERE tenant_id = :t")
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
                    (id, slug, legal_name, display_name, default_currency, default_timezone,
                     status, version)
                VALUES (:id, 'pos-apply-resume', 'Legal', 'POS apply resume', 'UZS',
                        'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'POS_BRAND', 'pos-brand', 'POS brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code,
                     display_name, status)
                VALUES (:id, :t, 'POS', 'clopos', 'clopos-open-api-v2', 'Pilot', 'ACTIVE')
                """).param("id", INSTALLATION).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :t, :installationId, :brandId, 'ACTIVE')
                """)
                .param("id", BINDING)
                .param("t", TENANT)
                .param("installationId", INSTALLATION)
                .param("brandId", BRAND)
                .update();

        // The run is already APPLYING -- exactly the state PosApplyService.apply
        // leaves behind partway through a large run, and the only state
        // PosApplyService.resume's APPLYING branch acts on.
        jdbc.sql("""
                INSERT INTO integration.pos_sync_runs
                    (id, tenant_id, binding_id, trigger_type, status, dry_run,
                     adapter_version, field_policy_version, started_at)
                VALUES (:id, :t, :binding, 'MANUAL', 'APPLYING', false, 'test-adapter', 1, :startedAt)
                """)
                .param("id", RUN)
                .param("t", TENANT)
                .param("binding", BINDING)
                .param("startedAt", EARLIER.atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO integration.provider_entity_mappings
                    (id, tenant_id, installation_id, binding_id, entity_type, horecaos_entity_id,
                     external_entity_id, status, mapping_source, version)
                VALUES (:id, :t, :installation, :binding, 'PRODUCT', :product, 'ext-41', 'ACTIVE',
                        'DISCOVERED', 1)
                """)
                .param("id", MAPPING)
                .param("t", TENANT)
                .param("installation", INSTALLATION)
                .param("binding", BINDING)
                .param("product", MAPPED_PRODUCT)
                .update();

        jdbc.sql("""
                INSERT INTO integration.pos_sync_differences
                    (id, tenant_id, run_id, entity_type, external_entity_id, horecaos_entity_id,
                     category, authority, severity, recommended_action)
                VALUES (:id, :t, :run, 'PRODUCT', 'ext-41', :product, 'REMOVAL_SIGNAL', 'MAPPING',
                        'WARNING', 'AUTO_APPLY')
                """)
                .param("id", DIFFERENCE)
                .param("t", TENANT)
                .param("run", RUN)
                .param("product", MAPPED_PRODUCT)
                .update();

        // Already applied by a process that got this far before dying. No
        // difference_id: the fk permits null, and nothing about "already
        // applied" is exercised again by resume, so it needs no working
        // reference -- see executeOne, which only ever reads store.plannedApplyItems.
        jdbc.sql("""
                INSERT INTO integration.pos_sync_apply_items
                    (id, tenant_id, run_id, difference_id, idempotency_key, action, target_type,
                     target_id, status, applied_at)
                VALUES (:id, :t, :run, NULL, 'already-applied', 'RETIRE_MAPPING', 'MAPPING',
                        :product, 'APPLIED', :appliedAt)
                """)
                .param("id", ALREADY_APPLIED_ITEM)
                .param("t", TENANT)
                .param("run", RUN)
                .param("product", MAPPED_PRODUCT)
                .param("appliedAt", EARLIER.atOffset(ZoneOffset.UTC))
                .update();

        // Never got to run before the process died.
        jdbc.sql("""
                INSERT INTO integration.pos_sync_apply_items
                    (id, tenant_id, run_id, difference_id, idempotency_key, action, target_type,
                     target_id, expected_target_version, status)
                VALUES (:id, :t, :run, :difference, 'still-planned', 'RETIRE_MAPPING', 'MAPPING',
                        :product, 1, 'PLANNED')
                """)
                .param("id", STILL_PLANNED_ITEM)
                .param("t", TENANT)
                .param("run", RUN)
                .param("difference", DIFFERENCE)
                .param("product", MAPPED_PRODUCT)
                .update();

        applyService = new PosApplyService(
                new JdbcPosApplyStore(jdbc), new RefusingRequester(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("resume finishes only the items that never ran, and never re-touches the one that already did")
    void resumeExecutesOnlyWhatIsStillPlanned() {
        var outcome = applyService.resume(TENANT, RUN).orElseThrow();

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.requestedRunId())
                .as("an APPLYING run resumes in place; it never asks for a fresh PosSyncRequested run")
                .isNull();
        assertThat(outcome.applyProgress()).isNotNull();

        assertThat(status(STILL_PLANNED_ITEM)).isEqualTo("APPLIED");
        assertThat(mappingStatus()).isEqualTo("RETIRED");
        assertThat(mappingVersion())
                .as("the mapping is retired exactly once, not once per resume call")
                .isEqualTo(2L);

        // The item that was already APPLIED before resume ran is byte-for-byte
        // untouched: same status, same applied_at. A double-import would either
        // change this timestamp (re-executed) or, if the action were not
        // naturally idempotent, produce a visibly wrong second effect.
        assertThat(status(ALREADY_APPLIED_ITEM)).isEqualTo("APPLIED");
        assertThat(appliedAt(ALREADY_APPLIED_ITEM)).isEqualTo(EARLIER.atOffset(ZoneOffset.UTC));

        assertThat(runStatus())
                .as("every item is now terminal, so the run itself completes")
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("resuming an already-completed run refuses rather than re-running anything")
    void resumingAnAlreadyCompletedRunRefuses() {
        var firstResume = applyService.resume(TENANT, RUN).orElseThrow();
        assertThat(firstResume.accepted()).isTrue();
        assertThat(runStatus()).isEqualTo("COMPLETED");

        var second = applyService.resume(TENANT, RUN).orElseThrow();

        assertThat(second.accepted()).isFalse();
        assertThat(status(STILL_PLANNED_ITEM)).isEqualTo("APPLIED");
        assertThat(appliedAt(ALREADY_APPLIED_ITEM)).isEqualTo(EARLIER.atOffset(ZoneOffset.UTC));
    }

    private String status(UUID itemId) {
        return jdbc.sql("SELECT status FROM integration.pos_sync_apply_items WHERE id = :id")
                .param("id", itemId)
                .query(String.class)
                .single();
    }

    private java.time.OffsetDateTime appliedAt(UUID itemId) {
        return jdbc.sql("SELECT applied_at FROM integration.pos_sync_apply_items WHERE id = :id")
                .param("id", itemId)
                .query(java.time.OffsetDateTime.class)
                .single();
    }

    private String mappingStatus() {
        return jdbc.sql("SELECT status FROM integration.provider_entity_mappings WHERE id = :id")
                .param("id", MAPPING)
                .query(String.class)
                .single();
    }

    private long mappingVersion() {
        return jdbc.sql("SELECT version FROM integration.provider_entity_mappings WHERE id = :id")
                .param("id", MAPPING)
                .query(Long.class)
                .single();
    }

    private String runStatus() {
        return jdbc.sql("SELECT status FROM integration.pos_sync_runs WHERE id = :id")
                .param("id", RUN)
                .query(String.class)
                .single();
    }

    /** Not exercised by an APPLYING resume; throws if that ever changes silently. */
    private static final class RefusingRequester implements PosSyncRequester {
        @Override
        public void requestSync(PosSyncRequestedPayload command, String correlationId) {
            throw new AssertionError("resuming an APPLYING run must never ask for a fresh sync");
        }
    }
}
