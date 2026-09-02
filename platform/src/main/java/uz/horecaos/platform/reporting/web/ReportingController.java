package uz.horecaos.platform.reporting.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import uz.horecaos.platform.reporting.application.ReportQuery;
import uz.horecaos.platform.reporting.application.ReportQueryService;
import uz.horecaos.platform.reporting.domain.Grain;
import uz.horecaos.platform.reporting.domain.MetricDefinition;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The tenant's reporting surface (ADR 0043).
 *
 * <p>Everything here is a read at {@code TENANT} scope, and every response
 * carries its provenance: the metric versions used, the business-day boundary and
 * timezone they were computed under, how far the close has got, which metrics are
 * still provisional, and whether any recut disagreed with a stored figure. ADR
 * 0023 does not allow a report that cannot state its freshness, and a tile that
 * cannot say how old it is teaches people to check every number by hand.
 *
 * <p>The typed query is a {@code GET} rather than the {@code POST /queries} the
 * ADR sketched. It is a pure read, and ADR 0031's build gate treats every POST as
 * effectful and requires an {@code Idempotency-Key} — a header a read has no use
 * for. Expressing the query as repeated parameters keeps the same contract that
 * matters: metric ids and dimension names, never SQL and never a fragment of one.
 * A body-carrying query endpoint needs an exemption in that gate, which belongs to
 * whoever owns it rather than being taken here.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/reporting")
@Tag(name = "Reporting", description = "Versioned metrics, day-grain reports, and their provenance")
public class ReportingController {

    private final ReportQueryService queries;

    public ReportingController(ReportQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/metrics")
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Every metric this build defines, with its definition and signature",
            description = "The metric dictionary. A definition whose source fact is not built "
                    + "says so, and a definition finance has not signed is marked provisional "
                    + "rather than presented as settled.")
    public ResponseEntity<List<MetricResponse>> metrics(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(
                queries.catalogue().stream().map(MetricResponse::of).toList());
    }

    @GetMapping("/queries")
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Named metrics over a date range, grouped by named dimensions",
            description = "Unknown metric ids are rejected rather than ignored: a silently "
                    + "dropped column renders as a quiet day. A money metric is refused unless "
                    + "the answer names the legal entity, because a combined total across two "
                    + "entities reconciles to neither tax filing (ADR 0038).")
    public ResponseEntity<QueryResponse> query(
            @PathVariable UUID tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam List<String> metric,
            @RequestParam(required = false) List<String> groupBy,
            @RequestParam(required = false) List<UUID> locationId,
            @RequestParam(required = false) List<String> channelCode) {

        ReportQuery query = new ReportQuery(
                tenantId, from, to, metric, dimensions(groupBy), orEmpty(locationId), orEmpty(channelCode));

        var result = queries.run(query);
        return ResponseEntity.ok(new QueryResponse(
                result.rows().stream().map(RowResponse::of).toList(), ProvenanceResponse.of(result.provenance())));
    }

    @GetMapping("/sla-buckets")
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The fixed elapsed-time distribution per branch",
            description = "sla_bucket_set.v1: six half-open intervals that are exhaustive and "
                    + "do not overlap, so the shares sum to the whole. Not a tenant setting — "
                    + "an editable bucket rewrites every chart already drawn.")
    public ResponseEntity<SlaResponse> slaBuckets(
            @PathVariable UUID tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<UUID> locationId) {

        var result = queries.slaBuckets(tenantId, from, to, orEmpty(locationId));
        return ResponseEntity.ok(new SlaResponse(
                result.buckets().stream()
                        .map(bucket -> new BucketResponse(
                                bucket.businessDate(),
                                bucket.scopeId(),
                                bucket.bucketCode(),
                                bucket.orderCount(),
                                bucket.shareBasisPoints()))
                        .toList(),
                ProvenanceResponse.of(result.provenance())));
    }

    @GetMapping("/preparation-time")
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Median seconds from confirmation to ready",
            description = "Its own endpoint because a median cannot be composed from per-slice "
                    + "medians. Null when nothing reached READY in the range, which is not a "
                    + "zero-second kitchen.")
    public ResponseEntity<MedianResponse> preparationTime(
            @PathVariable UUID tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<UUID> locationId) {

        var result = queries.preparationTime(tenantId, from, to, orEmpty(locationId));
        return ResponseEntity.ok(
                new MedianResponse(result.medianSeconds(), ProvenanceResponse.of(result.provenance())));
    }

    @GetMapping("/orders")
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Order-grain rows behind 7.2's per-stage, commercial-log, and late-order tables",
            description = "Not day-grain: one row per order, straight off reporting.fact_order. "
                    + "Carries no name, phone, operator, or courier — reporting has no PERSONAL "
                    + "field at all (ADR 0029). A bounded read, ordered by the axis the requested "
                    + "sort names, not a paginated feed; maybeMore on the response says whether it "
                    + "came back full.")
    public ResponseEntity<OrderListResponse> orders(
            @PathVariable UUID tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<UUID> locationId,
            @RequestParam(required = false) List<String> channelCode,
            @RequestParam(defaultValue = "DATE_DESC") String sort,
            @RequestParam(required = false) Integer limit) {

        var result = queries.orders(
                tenantId, from, to, orEmpty(locationId), orEmpty(channelCode), orderSort(sort), clampOrderLimit(limit));
        return ResponseEntity.ok(new OrderListResponse(
                result.rows().stream().map(OrderRowResponse::of).toList(),
                result.maybeMore(),
                ProvenanceResponse.of(result.provenance())));
    }

    @GetMapping("/order-outcomes")
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Every terminal status in range, split by cancellation reason",
            description = "One grouped read behind two surfaces: sum by terminalStatus for the "
                    + "overview funnel's drop-offs, or read the CANCELLED/REJECTED/EXPIRED/"
                    + "PAYMENT_FAILED rows for the cancellation panel's reason breakdown. What a "
                    + "cancellation cost — stock_disposition, liability_party — is not here: "
                    + "ADR 0039's order_outcomes does not exist yet, so fact_order carries null on "
                    + "every row for both.")
    public ResponseEntity<OutcomeListResponse> orderOutcomes(
            @PathVariable UUID tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<UUID> locationId,
            @RequestParam(required = false) List<String> channelCode) {

        var result = queries.orderOutcomes(tenantId, from, to, orEmpty(locationId), orEmpty(channelCode));
        return ResponseEntity.ok(new OutcomeListResponse(
                result.rows().stream().map(OutcomeRowResponse::of).toList(),
                ProvenanceResponse.of(result.provenance())));
    }

    private static JdbcReportingStore.OrderSort orderSort(String requested) {
        try {
            return JdbcReportingStore.OrderSort.valueOf(requested);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Unknown sort \"%s\"".formatted(requested), Map.of("sort", requested));
        }
    }

    private static final int ORDER_LIST_DEFAULT_LIMIT = 100;

    /** Wider than {@code Page.MAXIMUM_LIMIT}: this is a bounded read, not a page of a feed. */
    private static final int ORDER_LIST_MAX_LIMIT = 300;

    private static int clampOrderLimit(@Nullable Integer requested) {
        if (requested == null) {
            return ORDER_LIST_DEFAULT_LIMIT;
        }
        if (requested < 1) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit must be at least 1", Map.of());
        }
        return Math.min(requested, ORDER_LIST_MAX_LIMIT);
    }

    private static List<Grain.Dimension> dimensions(List<String> requested) {
        if (requested == null) {
            return List.of();
        }
        return requested.stream()
                .map(name -> {
                    try {
                        return Grain.Dimension.valueOf(name);
                    } catch (IllegalArgumentException unknown) {
                        // Rejected rather than dropped, for the same reason as an unknown
                        // metric: a silently ignored grouping returns a total where the
                        // caller asked for a breakdown, and nothing says so.
                        throw new ApiException(
                                ErrorCode.VALIDATION_FAILED,
                                "Unknown dimension \"%s\"".formatted(name),
                                Map.of("dimension", name));
                    }
                })
                .toList();
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    /**
     * One metric's definition, plus its signature state.
     *
     * @param sourceAvailable false when the metric is defined but its source fact
     *                        is not built. Surfaces render it unbuilt, never zero
     * @param provisional     finance has not signed this definition
     */
    public record MetricResponse(
            String metricCode,
            String name,
            int version,
            String grain,
            String sourceFact,
            boolean sourceAvailable,
            String aggregation,
            String unit,
            String currencyRule,
            String roundingRule,
            String definition,
            String includes,
            String excludes,
            String refundTreatment,
            @Nullable String openQuestion,
            @Nullable LocalDate effectiveFrom,
            boolean provisional,
            @Nullable String signedBy,
            @Nullable Instant signedAt) {

        static MetricResponse of(ReportQueryService.MetricView view) {
            MetricDefinition definition = view.definition();
            return new MetricResponse(
                    definition.id().code(),
                    definition.id().name(),
                    definition.id().version(),
                    definition.grain().name(),
                    definition.sourceFact(),
                    definition.sourceAvailable(),
                    definition.aggregation().name(),
                    definition.unit().name(),
                    definition.currencyRule().name(),
                    definition.roundingRule(),
                    definition.definition(),
                    definition.inclusion(),
                    definition.exclusion(),
                    definition.refundTreatment(),
                    definition.openQuestion(),
                    definition.effectiveFrom(),
                    view.provisional(),
                    view.signedBy(),
                    view.signedAt());
        }
    }

    /**
     * One report row: a slice's dimension values, plus the figures computed for it.
     *
     * @param values metric code to figure. Null means the slice had nothing to compute it from
     */
    public record RowResponse(
            LocalDate businessDate,
            @Nullable UUID locationId,
            @Nullable String channelCode,
            @Nullable String fulfilmentType,
            @Nullable UUID legalEntityId,
            Map<String, Long> values) {

        static RowResponse of(ReportQueryService.ReportRow row) {
            var slice = row.slice();
            return new RowResponse(
                    slice.businessDate(),
                    slice.locationId(),
                    slice.channelCode(),
                    slice.fulfilmentType(),
                    slice.legalEntityId(),
                    row.values());
        }
    }

    public record QueryResponse(List<RowResponse> rows, ProvenanceResponse provenance) {}

    public record BucketResponse(
            LocalDate businessDate, UUID locationId, String bucketCode, int orderCount, int shareBasisPoints) {}

    public record SlaResponse(List<BucketResponse> buckets, ProvenanceResponse provenance) {}

    public record MedianResponse(@Nullable Integer medianSeconds, ProvenanceResponse provenance) {}

    /** One order-grain row. See {@code JdbcReportingStore.OrderRow} for what each field means. */
    public record OrderRowResponse(
            UUID orderId,
            LocalDate businessDate,
            UUID locationId,
            @Nullable UUID legalEntityId,
            String channelCode,
            String fulfilmentType,
            String terminalStatus,
            long grossRevenueSom,
            long discountSom,
            long deliveryFeeSom,
            long taxSom,
            long netRevenueSom,
            int itemCount,
            Instant occurredAt,
            @Nullable Instant closedAt,
            @Nullable Integer secondsToConfirm,
            @Nullable Integer secondsToReady,
            @Nullable Integer secondsTotal,
            @Nullable Integer secondsLate,
            @Nullable String cancellationReasonCode) {

        static OrderRowResponse of(JdbcReportingStore.OrderRow row) {
            return new OrderRowResponse(
                    row.orderId(),
                    row.businessDate(),
                    row.locationId(),
                    row.legalEntityId(),
                    row.channelCode(),
                    row.fulfilmentType(),
                    row.terminalStatus(),
                    row.grossRevenueSom(),
                    row.discountSom(),
                    row.deliveryFeeSom(),
                    row.taxSom(),
                    row.netRevenueSom(),
                    row.itemCount(),
                    row.occurredAt(),
                    row.closedAt(),
                    row.secondsToConfirm(),
                    row.secondsToReady(),
                    row.secondsTotal(),
                    row.secondsLate(),
                    row.cancellationReasonCode());
        }
    }

    /**
     * @param maybeMore true when the bounded read came back full — see
     *                  {@code ReportQueryService.OrderListResult}
     */
    public record OrderListResponse(List<OrderRowResponse> rows, boolean maybeMore, ProvenanceResponse provenance) {}

    /** One (terminal status, cancellation reason) bucket. */
    public record OutcomeRowResponse(
            String terminalStatus, @Nullable String cancellationReasonCode, int count) {

        static OutcomeRowResponse of(JdbcReportingStore.OutcomeRow row) {
            return new OutcomeRowResponse(row.terminalStatus(), row.cancellationReasonCode(), row.count());
        }
    }

    public record OutcomeListResponse(List<OutcomeRowResponse> rows, ProvenanceResponse provenance) {}

    /**
     * What ADR 0023 requires a report to declare about itself.
     *
     * @param openDivergences recuts that disagreed with a stored figure and were
     *                        deliberately not applied. Non-zero means somebody has
     *                        to look, and the figures above are still the stored
     *                        ones
     */
    public record ProvenanceResponse(
            Instant asOf,
            @Nullable LocalDate closedThrough,
            @Nullable Instant lastCloseCompletedAt,
            String businessDayStart,
            String timezone,
            int boundaryVersion,
            List<String> metricVersions,
            List<String> provisionalMetrics,
            int openDivergences) {

        static ProvenanceResponse of(ReportQueryService.Provenance provenance) {
            return new ProvenanceResponse(
                    provenance.asOf(),
                    provenance.closedThrough(),
                    provenance.lastCloseCompletedAt(),
                    provenance.businessDayStart(),
                    provenance.timezone(),
                    provenance.boundaryVersion(),
                    provenance.metricVersions(),
                    provenance.provisionalMetricCodes(),
                    provenance.openDivergences());
        }
    }
}
