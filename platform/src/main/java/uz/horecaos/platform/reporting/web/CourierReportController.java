package uz.horecaos.platform.reporting.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * `/statistics/couriers` (T11, ADR 0125): `7.4` the courier leaderboard,
 * `7.4a` its SLA bucket distribution, `7.4b` the delivery-sum-by-tariff
 * audit, and `7.4c` the per-order external-delivery cost report.
 *
 * <p>A separate class from {@link ReportingController} rather than four more
 * methods on it (already 700+ lines and shared by every reporting wave) —
 * the same reasoning {@code DayCloseTenderFactTests} states for staying out
 * of the shared test file it could have joined.
 *
 * <p>No response here carries a courier's name. Every row carries {@code
 * courierId} alone (ADR 0029/ADR 0042); a caller resolves display through
 * P19's reveal, never through a report.
 *
 * <p>The per-line reconcile action `7.4c` names lives beside the invoice
 * lines it mutates — {@code OperationsCourierController}'s {@code
 * partner-delivery-invoices} family — not here: this controller only reads.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/reporting/couriers")
@Tag(name = "Courier reporting", description = "Leaderboard, SLA buckets, tariff audit and external-delivery cost")
public class CourierReportController {

    private final ReportQueryService queries;

    public CourierReportController(ReportQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/leaderboard")
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "7.4: courier leaderboard — distance, transit hours, deliveries, on-time %",
            description = "One row per courier across the range, straight off reporting.fact_delivery "
                    + "(ADR 0125). courierId only; resolve display through the couriers endpoint (P19), "
                    + "never from this report.")
    public ResponseEntity<LeaderboardResponse> leaderboard(
            @PathVariable UUID tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        var result = queries.courierLeaderboard(tenantId, from, to);
        return ResponseEntity.ok(new LeaderboardResponse(
                result.rows().stream().map(LeaderboardRowResponse::of).toList(),
                ReportingController.ProvenanceResponse.of(result.provenance())));
    }

    @GetMapping("/sla-buckets")
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "7.4a: the fixed SLA distribution, per courier",
            description = "The COURIER scope of agg_sla_bucket_day (ck_agg_sla_scope_kind has allowed it "
                    + "since V0031; this is the first producer and reader). Same sla_bucket_set.v1 six "
                    + "buckets as the branch distribution, bucketed on a courier's own acceptance-to-"
                    + "delivery span rather than the order's full elapsed time.")
    public ResponseEntity<CourierSlaResponse> slaBuckets(
            @PathVariable UUID tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        var result = queries.courierSlaBuckets(tenantId, from, to);
        return ResponseEntity.ok(new CourierSlaResponse(
                result.buckets().stream()
                        .map(bucket -> new CourierBucketResponse(
                                bucket.businessDate(),
                                bucket.scopeId(),
                                bucket.bucketCode(),
                                bucket.orderCount(),
                                bucket.shareBasisPoints()))
                        .toList(),
                ReportingController.ProvenanceResponse.of(result.provenance())));
    }

    @GetMapping("/tariff-audit")
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "7.4b: delivery-sum-by-tariff audit",
            description = "fulfillment.delivery_fee_resolutions (ADR 0037) joined through "
                    + "quote -> order -> shipment for the one fact none of the three tables has alone: "
                    + "which courier actually worked each resolution. Grouped by (tariff, courier); "
                    + "'today' means whatever the range covers, not a business-day close.")
    public ResponseEntity<TariffAuditResponse> tariffAudit(
            @PathVariable UUID tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<UUID> locationId) {

        var result = queries.tariffAudit(tenantId, from, to, orEmpty(locationId));
        return ResponseEntity.ok(new TariffAuditResponse(
                result.rows().stream().map(TariffAuditRowResponse::of).toList(),
                ReportingController.ProvenanceResponse.of(result.provenance())));
    }

    @GetMapping("/external-delivery-cost")
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "7.4c: per-order external-delivery cost",
            description = "Order amount vs charged delivery vs provider billed vs variance vs "
                    + "reconciliation status, for every PARTNER-sourced delivered shipment in range. "
                    + "reconciliationStatus is UNBILLED (never PENDING) for a shipment with no invoice "
                    + "line at all -- 'HorecaOS has a shipment the partner never billed', excluded from "
                    + "varianceMinor's own total the same way it is excluded from every sum here: a "
                    + "null variance never contributes. The invoice-scoped half of this reconciliation "
                    + "renders in Finance 8.4; this is the per-order cut.")
    public ResponseEntity<ExternalDeliveryCostResponse> externalDeliveryCost(
            @PathVariable UUID tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<UUID> locationId) {

        var result = queries.externalDeliveryCost(tenantId, from, to, orEmpty(locationId));
        long totalVarianceMinor = result.rows().stream()
                .filter(row -> row.varianceMinor() != null)
                .mapToLong(row -> row.varianceMinor())
                .sum();
        return ResponseEntity.ok(new ExternalDeliveryCostResponse(
                result.rows().stream().map(ExternalDeliveryCostRowResponse::of).toList(),
                totalVarianceMinor,
                ReportingController.ProvenanceResponse.of(result.provenance())));
    }

    private static List<UUID> orEmpty(@Nullable List<UUID> values) {
        return values == null ? List.of() : values;
    }

    // ----------------------------------------------------------------- 7.4

    public record LeaderboardRowResponse(
            UUID courierId,
            int deliveryCount,
            int minDistanceMeters,
            int maxDistanceMeters,
            double avgDistanceMeters,
            long totalDistanceMeters,
            double avgTransitHours,
            long totalTransitSeconds,
            @Nullable Double onTimeShare) {

        static LeaderboardRowResponse of(JdbcReportingStore.CourierLeaderboardRow row) {
            return new LeaderboardRowResponse(
                    row.courierId(),
                    row.deliveryCount(),
                    row.minDistanceMeters(),
                    row.maxDistanceMeters(),
                    row.avgDistanceMeters(),
                    row.totalDistanceMeters(),
                    row.avgTransitSeconds() / 3600.0,
                    row.totalTransitSeconds(),
                    // Null rather than zero when no delivery in range recorded a
                    // promise: a courier with an unmeasured on-time rate must
                    // never read as a courier who missed every delivery.
                    row.onTimeCount() + row.lateCount() == 0
                            ? null
                            : (double) row.onTimeCount() / (row.onTimeCount() + row.lateCount()));
        }
    }

    public record LeaderboardResponse(
            List<LeaderboardRowResponse> rows, ReportingController.ProvenanceResponse provenance) {}

    // ---------------------------------------------------------------- 7.4a

    public record CourierBucketResponse(
            LocalDate businessDate, UUID courierId, String bucketCode, int orderCount, int shareBasisPoints) {}

    public record CourierSlaResponse(
            List<CourierBucketResponse> buckets, ReportingController.ProvenanceResponse provenance) {}

    // ---------------------------------------------------------------- 7.4b

    public record TariffAuditRowResponse(
            UUID tariffId,
            int tariffVersion,
            @Nullable UUID zoneId,
            @Nullable Integer bandSequence,
            @Nullable UUID courierId,
            int resolutionCount,
            long totalFinalFeeMinor,
            String currency) {

        static TariffAuditRowResponse of(JdbcReportingStore.TariffAuditRow row) {
            return new TariffAuditRowResponse(
                    row.tariffId(),
                    row.tariffVersion(),
                    row.zoneId(),
                    row.bandSequence(),
                    row.courierId(),
                    row.resolutionCount(),
                    row.totalFinalFeeMinor(),
                    row.currency());
        }
    }

    public record TariffAuditResponse(
            List<TariffAuditRowResponse> rows, ReportingController.ProvenanceResponse provenance) {}

    // ---------------------------------------------------------------- 7.4c

    public record ExternalDeliveryCostRowResponse(
            UUID orderId,
            String publicOrderNumber,
            long orderTotalMinor,
            String currency,
            long chargedDeliveryMinor,
            UUID shipmentId,
            @Nullable String providerType,
            @Nullable Long providerEstimatedMinor,
            @Nullable Long providerBilledMinor,
            @Nullable Long varianceMinor,
            String reconciliationStatus,
            boolean reconcileActionAvailable) {

        static ExternalDeliveryCostRowResponse of(JdbcReportingStore.ExternalDeliveryCostRow row) {
            // UNBILLED is never written anywhere -- it is exactly the read-time
            // case where no invoice line exists at all for a delivered partner
            // shipment (courier.domain.MatchStatus's own doc: "HorecaOS has a
            // shipment the partner never billed"). Every other status is the
            // line's own, straight through.
            String status = row.matchStatus() == null ? "UNBILLED" : row.matchStatus();
            return new ExternalDeliveryCostRowResponse(
                    row.orderId(),
                    row.publicOrderNumber(),
                    row.orderTotalMinor(),
                    row.currency(),
                    row.chargedDeliveryMinor(),
                    row.shipmentId(),
                    row.providerType(),
                    row.providerEstimatedMinor(),
                    row.providerBilledMinor(),
                    row.varianceMinor(),
                    status,
                    // 2026-09-14 review: never for VARIANCE or MATCHED. A
                    // VARIANCE row's money discrepancy is disposed of only
                    // through resolveVariance's accept/dispute choice — never
                    // silently written off by this button — and a MATCHED
                    // row has nothing left to reconcile. UNMATCHED_LINE can
                    // never actually reach this row (its invoice line carries
                    // no shipment_id to join on), but is named here rather
                    // than collapsed into "anything but VARIANCE/MATCHED" so
                    // the condition still reads as an allowlist, not a
                    // denylist someone has to keep in sync by hand.
                    row.invoiceLineId() != null && ("UNMATCHED_LINE".equals(status) || "UNBILLED".equals(status)));
        }
    }

    /** @param totalVarianceMinor every row's variance summed, UNBILLED rows (null variance) never contributing. */
    public record ExternalDeliveryCostResponse(
            List<ExternalDeliveryCostRowResponse> rows,
            long totalVarianceMinor,
            ReportingController.ProvenanceResponse provenance) {}
}
