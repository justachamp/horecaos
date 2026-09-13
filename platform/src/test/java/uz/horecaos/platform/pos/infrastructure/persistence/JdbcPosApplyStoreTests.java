package uz.horecaos.platform.pos.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import uz.horecaos.platform.pos.domain.ApplyPlanner;
import uz.horecaos.platform.pos.domain.SyncDifference;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosApplyStore.ApplyItemRow;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The per-item apply outcome read (gap-map row 4.5a): {@code
 * integration.pos_sync_apply_items} had a writer and no test at all before
 * this — {@code applyItems} is exercised only indirectly, through {@code
 * PosApplyService}, in the rest of the suite.
 */
class JdbcPosApplyStoreTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac124001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac124002");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac124003");
    private static final UUID BINDING = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac124004");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcPosApplyStore store;
    private UUID runId;

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
        store = new JdbcPosApplyStore(jdbc);

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
                VALUES (:id, 'pos-apply-store', 'Legal', 'POS apply store', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'APPLY_STORE_BRAND', 'apply-store-brand', 'Apply store brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
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

        runId = Ids.newId();
        jdbc.sql("""
                INSERT INTO integration.pos_sync_runs
                    (id, tenant_id, binding_id, trigger_type, status, dry_run,
                     adapter_version, field_policy_version, started_at)
                VALUES (:id, :t, :bindingId, 'MANUAL', 'REVIEW_REQUIRED', false, 'test-adapter-1', 1, :now)
                """)
                .param("id", runId)
                .param("t", TENANT)
                .param("bindingId", BINDING)
                .param("now", OffsetDateTime.ofInstant(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC))
                .update();
    }

    private UUID insertDifference(SyncDifference.EntityType entityType, String externalId) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO integration.pos_sync_differences
                    (id, tenant_id, run_id, entity_type, external_entity_id,
                     category, authority, severity, recommended_action)
                VALUES (:id, :t, :runId, :entityType, :externalId,
                        'AUTHORIZED_CHANGE', 'PROVIDER', 'INFO', 'AUTO_APPLY')
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("runId", runId)
                .param("entityType", entityType.name())
                .param("externalId", externalId)
                .update();
        return id;
    }

    @Test
    @DisplayName("applyItems reports each item's actual outcome: applied, failed with a reason, and still planned")
    void applyItemsReportsPerItemOutcomes() {
        UUID appliedDifference = insertDifference(SyncDifference.EntityType.VARIANT, "ext-applied");
        UUID failedDifference = insertDifference(SyncDifference.EntityType.VARIANT, "ext-failed");
        UUID plannedDifference = insertDifference(SyncDifference.EntityType.VARIANT, "ext-planned");
        UUID mappedTarget = Ids.newId();

        store.planApplyItems(
                TENANT,
                BINDING,
                runId,
                List.of(
                        new ApplyPlanner.PlannedItem(
                                "key-applied",
                                appliedDifference,
                                ApplyPlanner.Action.UPDATE_MAPPING,
                                ApplyPlanner.TargetType.MAPPING,
                                SyncDifference.EntityType.VARIANT,
                                mappedTarget),
                        new ApplyPlanner.PlannedItem(
                                "key-failed",
                                failedDifference,
                                ApplyPlanner.Action.UPDATE_MAPPING,
                                ApplyPlanner.TargetType.MAPPING,
                                SyncDifference.EntityType.VARIANT,
                                mappedTarget),
                        new ApplyPlanner.PlannedItem(
                                "key-planned",
                                plannedDifference,
                                ApplyPlanner.Action.UPDATE_MAPPING,
                                ApplyPlanner.TargetType.MAPPING,
                                SyncDifference.EntityType.VARIANT,
                                mappedTarget)));

        List<ApplyItemRow> planned = store.applyItems(TENANT, runId);
        assertThat(planned)
                .hasSize(3)
                .allSatisfy(item -> assertThat(item.status()).isEqualTo("PLANNED"));

        UUID appliedItemId = planned.stream()
                .filter(item -> "key-applied".equals(item.idempotencyKey()))
                .findFirst()
                .orElseThrow()
                .id();
        UUID failedItemId = planned.stream()
                .filter(item -> "key-failed".equals(item.idempotencyKey()))
                .findFirst()
                .orElseThrow()
                .id();

        store.markApplyItemApplied(TENANT, appliedItemId, Instant.parse("2026-09-05T00:05:00Z"));
        store.markApplyItemFailed(TENANT, failedItemId, "The mapping's version moved since this item was planned");

        List<ApplyItemRow> after = store.applyItems(TENANT, runId);
        assertThat(after).hasSize(3);

        ApplyItemRow applied = after.stream()
                .filter(item -> item.id().equals(appliedItemId))
                .findFirst()
                .orElseThrow();
        assertThat(applied.status()).isEqualTo("APPLIED");
        assertThat(applied.appliedAt()).isEqualTo(Instant.parse("2026-09-05T00:05:00Z"));
        assertThat(applied.failureReason()).isNull();

        ApplyItemRow failed = after.stream()
                .filter(item -> item.id().equals(failedItemId))
                .findFirst()
                .orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureReason()).isEqualTo("The mapping's version moved since this item was planned");

        ApplyItemRow stillPlanned = after.stream()
                .filter(item -> "key-planned".equals(item.idempotencyKey()))
                .findFirst()
                .orElseThrow();
        assertThat(stillPlanned.status()).isEqualTo("PLANNED");

        // Prove the assertion can fail: swap the reason a live bug would swap.
        assertThat(applied.failureReason()).isNotEqualTo(failed.failureReason());
    }

    @Test
    @DisplayName("plannedApplyItems returns only PLANNED, filtering out what applyItems reports as done")
    void plannedApplyItemsFiltersToPlannedOnly() {
        UUID difference = insertDifference(SyncDifference.EntityType.VARIANT, "ext-a");
        UUID mappedTarget = Ids.newId();
        store.planApplyItems(
                TENANT,
                BINDING,
                runId,
                List.of(new ApplyPlanner.PlannedItem(
                        "key-a",
                        difference,
                        ApplyPlanner.Action.UPDATE_MAPPING,
                        ApplyPlanner.TargetType.MAPPING,
                        SyncDifference.EntityType.VARIANT,
                        mappedTarget)));
        UUID itemId = store.applyItems(TENANT, runId).getFirst().id();

        assertThat(store.plannedApplyItems(TENANT, runId)).hasSize(1);

        store.markApplyItemSkipped(TENANT, itemId, "no longer applicable");

        assertThat(store.plannedApplyItems(TENANT, runId)).isEmpty();
    }
}
