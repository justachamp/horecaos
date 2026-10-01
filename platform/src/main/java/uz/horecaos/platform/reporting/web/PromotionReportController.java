package uz.horecaos.platform.reporting.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.reporting.application.ReportQueryService;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * `/reporting/promotions`: report 7.9, promotion spend after the fact (ADR 0140,
 * ADR 0043).
 *
 * <p>Both endpoints read {@code reporting.fact_promotion_redemption} and {@code
 * reporting.fact_order} alone -- never a {@code pricing} table (ADR 0023) -- so
 * they lag by up to a business day, in exchange for one definition of a redemption.
 * The customer is the ADR 0029 pseudonym the fact holds: the report shows the
 * pseudonym and that is the honest limit, because an account id in a reporting fact
 * is exactly what ADR 0029 forbids.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/reporting/promotions")
@Tag(
        name = "Promotion reporting",
        description = "Promotion redemptions, discount given, average check with and without")
public class PromotionReportController {

    private final ReportQueryService queries;

    public PromotionReportController(ReportQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/summary")
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.TENANT)
    @Operation(
            summary =
                    "7.9: promotion summary - redemptions, unique customers, discount given, average check with and without",
            description = "One row per promotion over the closed business days in the range. Cancelled, "
                    + "rejected, expired and payment-failed orders are excluded here and stay in the log. "
                    + "The average check with and without is a comparison over the same brand and period, "
                    + "not a causal uplift: customers who use a promotion differ from those who do not.")
    public ResponseEntity<SummaryResponse> summary(
            @PathVariable UUID tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Nullable UUID brandId) {
        var result = queries.promotionSummary(tenantId, from, to, brandId);
        return ResponseEntity.ok(new SummaryResponse(
                result.rows().stream().map(SummaryRowResponse::of).toList(),
                ReportingController.ProvenanceResponse.of(result.provenance())));
    }

    @GetMapping("/redemptions")
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "7.9: the redemption log - one row per (order, promotion), customer as a pseudonym",
            description = "Newest first, at most 500 rows. Includes orders that were later cancelled, with "
                    + "their order status, so a count that excludes them can be reproduced.")
    public ResponseEntity<RedemptionsResponse> redemptions(
            @PathVariable UUID tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Nullable UUID promotionId,
            @RequestParam(defaultValue = "200") int limit) {
        var result = queries.promotionRedemptions(tenantId, from, to, promotionId, limit);
        return ResponseEntity.ok(new RedemptionsResponse(
                result.rows().stream().map(RedemptionRowResponse::of).toList(),
                ReportingController.ProvenanceResponse.of(result.provenance())));
    }

    /**
     * @param averageCheckWithSom the mean gross revenue of completed orders that carried the promotion
     * @param averageCheckWithoutSom the same for the brand's completed orders in the period that did not; a comparison
     */
    public record SummaryRowResponse(
            UUID brandId,
            UUID promotionId,
            String promotionCode,
            String sourceKind,
            long redemptions,
            long uniqueCustomers,
            long discountSom,
            long markupSom,
            long revenueWithSom,
            @Nullable Long averageCheckWithSom,
            @Nullable Long averageCheckWithoutSom) {

        static SummaryRowResponse of(JdbcReportingStore.PromotionSummaryRow row) {
            return new SummaryRowResponse(
                    row.brandId(),
                    row.promotionId(),
                    row.promotionCode(),
                    row.sourceKind(),
                    row.redemptions(),
                    row.uniqueCustomers(),
                    row.discountMinor(),
                    row.markupMinor(),
                    row.revenueWith(),
                    row.averageCheckWith(),
                    row.averageCheckWithout());
        }
    }

    public record SummaryResponse(List<SummaryRowResponse> rows, ReportingController.ProvenanceResponse provenance) {}

    /**
     * @param customerSubject the ADR 0029 pseudonym, never an account id
     * @param orderStatus the order's terminal status in {@code fact_order}, null while it has none
     */
    public record RedemptionRowResponse(
            UUID redemptionId,
            LocalDate businessDate,
            UUID brandId,
            UUID promotionId,
            String promotionCode,
            int definitionVersion,
            String sourceKind,
            UUID orderId,
            @Nullable String customerSubject,
            long discountSom,
            long markupSom,
            String currency,
            Instant redeemedAt,
            @Nullable String orderStatus) {

        static RedemptionRowResponse of(JdbcReportingStore.PromotionRedemptionRow row) {
            return new RedemptionRowResponse(
                    row.redemptionId(),
                    row.businessDate(),
                    row.brandId(),
                    row.promotionId(),
                    row.promotionCode(),
                    row.definitionVersion(),
                    row.sourceKind(),
                    row.orderId(),
                    row.customerSubjectHash(),
                    row.discountMinor(),
                    row.markupMinor(),
                    row.currency(),
                    row.redeemedAt(),
                    row.orderStatus());
        }
    }

    public record RedemptionsResponse(
            List<RedemptionRowResponse> rows, ReportingController.ProvenanceResponse provenance) {}
}
