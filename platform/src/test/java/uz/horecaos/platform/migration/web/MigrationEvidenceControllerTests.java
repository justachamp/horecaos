package uz.horecaos.platform.migration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.migration.domain.MappingStatus;
import uz.horecaos.platform.migration.domain.ReconciliationSeverity;
import uz.horecaos.platform.migration.domain.ReconciliationStatus;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcEntityMappingStore;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcEntityMappingStore.EntityMappingRow;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcReconciliationStore;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcReconciliationStore.ReconciliationMeasure;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcReconciliationStore.ReconciliationResultRow;
import uz.horecaos.platform.web.api.Page;

class MigrationEvidenceControllerTests {

    private final JdbcEntityMappingStore entityMappings = mock(JdbcEntityMappingStore.class);
    private final JdbcReconciliationStore reconciliation = mock(JdbcReconciliationStore.class);
    private final MigrationEvidenceController controller =
            new MigrationEvidenceController(entityMappings, reconciliation);

    @Test
    void entityMappingsIsASinglePageCappedAtTheRequestedLimit() {
        UUID tenantId = UUID.randomUUID();
        UUID scopeId = UUID.randomUUID();
        EntityMappingRow row = new EntityMappingRow(
                UUID.randomUUID(),
                tenantId,
                scopeId,
                "PRODUCT",
                "legacy-42",
                UUID.randomUUID(),
                "v1",
                3L,
                1,
                MappingStatus.MAPPED,
                null,
                UUID.randomUUID(),
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"));
        when(entityMappings.listForScope(tenantId, scopeId, "PRODUCT", null, 25))
                .thenReturn(List.of(row));

        Page<MigrationEvidenceController.EntityMappingResponse> page =
                controller.entityMappings(scopeId, tenantId, "PRODUCT", 25);

        assertThat(page.nextCursor())
                .as("a diagnostics screen, not a browsable archive")
                .isNull();
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.legacyId()).isEqualTo("legacy-42");
            assertThat(item.status()).isEqualTo(MappingStatus.MAPPED);
            assertThat(item.targetId()).isEqualTo(row.targetId());
        });
    }

    @Test
    void entityMappingsDefaultsTheLimitWhenTheCallerOmitsIt() {
        UUID tenantId = UUID.randomUUID();
        UUID scopeId = UUID.randomUUID();
        when(entityMappings.listForScope(tenantId, scopeId, "PRODUCT", null, Page.DEFAULT_LIMIT))
                .thenReturn(List.of());

        controller.entityMappings(scopeId, tenantId, "PRODUCT", null);

        assertThat(Page.limitOrDefault(null)).isEqualTo(Page.DEFAULT_LIMIT);
    }

    @Test
    void reconciliationResultsCarriesTheMeasureAndSettlementStateThrough() {
        UUID tenantId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ReconciliationResultRow row = new ReconciliationResultRow(
                UUID.randomUUID(),
                tenantId,
                runId,
                UUID.randomUUID(),
                "order-count-per-status",
                1,
                "CONFIRMED",
                ReconciliationSeverity.CRITICAL,
                ReconciliationMeasure.count(BigInteger.valueOf(100), BigInteger.valueOf(97)),
                BigInteger.valueOf(3),
                null,
                ReconciliationStatus.OPEN,
                null,
                null,
                null,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-01T00:00:00Z"));
        when(reconciliation.listForRun(tenantId, runId, null, 50)).thenReturn(List.of(row));

        Page<MigrationEvidenceController.ReconciliationResultResponse> page =
                controller.reconciliationResults(runId, tenantId, null);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.ruleCode()).isEqualTo("order-count-per-status");
            assertThat(item.severity()).isEqualTo(ReconciliationSeverity.CRITICAL);
            assertThat(item.expected()).isEqualTo(BigInteger.valueOf(100));
            assertThat(item.actual()).isEqualTo(BigInteger.valueOf(97));
            assertThat(item.difference()).isEqualTo(BigInteger.valueOf(3));
            assertThat(item.status()).isEqualTo(ReconciliationStatus.OPEN);
        });
    }
}
