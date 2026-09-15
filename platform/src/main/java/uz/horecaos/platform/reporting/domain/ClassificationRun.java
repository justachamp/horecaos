package uz.horecaos.platform.reporting.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * T14 (7.7a/7.7b), ADR 0134: one persisted ABC/XYZ run, exactly as recorded
 * in {@code reporting.classification_run} plus its {@code classification_result}
 * rows.
 */
public record ClassificationRun(
        UUID id,
        UUID tenantId,
        LocalDate from,
        LocalDate to,
        List<UUID> locationIds,
        String metricCode,
        ClassificationThresholds thresholds,
        int bucketDays,
        int bucketCount,
        String requestedBy,
        Instant computedAt,
        List<Row> rows) {

    public ClassificationRun {
        rows = List.copyOf(rows);
        locationIds = List.copyOf(locationIds);
    }

    /**
     * One product's classification under this run.
     *
     * @param categoryId nullable on the same footing {@code
     *                    VariantSalesRow.categoryId} already is — an
     *                    uncategorised variant classifies like any other
     */
    public record Row(
            UUID variantId,
            @Nullable UUID categoryId,
            String productName,
            long revenueGrossSom,
            int revenueShareBasisPoints,
            int cumulativeShareBasisPoints,
            char abcClass,
            int quantityTotal,
            double meanQuantityPerBucket,
            double stddevQuantityPerBucket,
            int coefficientOfVariationBasisPoints,
            char xyzClass) {}
}
