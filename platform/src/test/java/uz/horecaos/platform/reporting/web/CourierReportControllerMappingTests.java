package uz.horecaos.platform.reporting.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;

/**
 * T11 (7.4c, ADR 0125): {@code UNBILLED} is derived at the HTTP mapping
 * layer, not written anywhere — this is the one place {@code
 * courier.domain.MatchStatus.UNBILLED} is actually produced, a pure unit
 * test rather than a database round trip because the derivation is a plain
 * one-line ternary the query layer never sees (its own row carries a null
 * {@code matchStatus}, correctly, since no invoice line exists).
 */
class CourierReportControllerMappingTests {

    @Test
    void aRowWithNoInvoiceLineMapsToUnbilledRatherThanNull() {
        var row = new JdbcReportingStore.ExternalDeliveryCostRow(
                UUID.randomUUID(),
                "ORD-1",
                45_000L,
                "UZS",
                5_000L,
                UUID.randomUUID(),
                "NOOR",
                20_000L,
                null,
                null,
                null,
                null);

        var response = CourierReportController.ExternalDeliveryCostRowResponse.of(row);

        assertThat(response.reconciliationStatus()).isEqualTo("UNBILLED");
        assertThat(response.reconcileActionAvailable()).isFalse();
    }

    @Test
    void aRowWithAnInvoiceLineCarriesItsOwnStatusThrough() {
        var row = new JdbcReportingStore.ExternalDeliveryCostRow(
                UUID.randomUUID(),
                "ORD-2",
                45_000L,
                "UZS",
                5_000L,
                UUID.randomUUID(),
                "NOOR",
                20_000L,
                UUID.randomUUID(),
                25_000L,
                "VARIANCE",
                5_000L);

        var response = CourierReportController.ExternalDeliveryCostRowResponse.of(row);

        assertThat(response.reconciliationStatus()).isEqualTo("VARIANCE");
        assertThat(response.varianceMinor()).isEqualTo(5_000L);
    }

    /**
     * 2026-09-14 review: a VARIANCE row's money discrepancy must be disposed
     * of only through {@code resolveVariance}'s accept/dispute choice, never
     * silently written off by this report's reconcile button — before this
     * fix the mapping unconditionally offered the action for any row with an
     * invoice line, VARIANCE included.
     */
    @Test
    void aVarianceRowNeverOffersReconcile() {
        var row = new JdbcReportingStore.ExternalDeliveryCostRow(
                UUID.randomUUID(),
                "ORD-2",
                45_000L,
                "UZS",
                5_000L,
                UUID.randomUUID(),
                "NOOR",
                20_000L,
                UUID.randomUUID(),
                25_000L,
                "VARIANCE",
                5_000L);

        assertThat(CourierReportController.ExternalDeliveryCostRowResponse.of(row)
                        .reconcileActionAvailable())
                .isFalse();
    }

    /** A MATCHED row has nothing left to reconcile, so the action is withdrawn once matching lands. */
    @Test
    void aMatchedRowNeverOffersReconcile() {
        var row = new JdbcReportingStore.ExternalDeliveryCostRow(
                UUID.randomUUID(),
                "ORD-3",
                45_000L,
                "UZS",
                5_000L,
                UUID.randomUUID(),
                "NOOR",
                20_000L,
                UUID.randomUUID(),
                20_000L,
                "MATCHED",
                null);

        var response = CourierReportController.ExternalDeliveryCostRowResponse.of(row);

        assertThat(response.reconciliationStatus()).isEqualTo("MATCHED");
        assertThat(response.reconcileActionAvailable()).isFalse();
    }
}
