package uz.horecaos.platform.reporting.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * `/reporting/reviews`: reviews and service recovery after the fact (ADR 0159, ADR 0043).
 *
 * <p>Both endpoints read {@code reporting.fact_review}, {@code fact_review_tag} and {@code
 * fact_recovery_case} alone -- never a {@code reviews} table (ADR 0023) -- so they lag by up to a
 * business day, in exchange for one definition of a review count and of an average. The customer is
 * not in any of it, not even as a pseudonym; no comment, note or tag wording is either, and there is
 * no courier dimension finer than the tag category (ADR 0159, open input 8).
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/reporting/reviews")
@Tag(name = "Review reporting", description = "Ratings, tags and service-recovery performance")
public class ReviewReportController {

    private final ReportQueryService queries;

    public ReviewReportController(ReportQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/summary")
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.TENANT)
    @Operation(
            summary =
                    "reviews.count, reviews.average_rating, reviews.tagged: reviews per location and the tags they carried",
            description = "One row per location over the closed business days in the range. Every review "
                    + "counts, whatever became of its comment: a rating is not words. The average is the sum "
                    + "of ratings over the count, half up to one decimal, and is null when the range holds "
                    + "no review. Tags are counted by category and sentiment, never by wording.")
    public ResponseEntity<SummaryResponse> summary(
            @PathVariable UUID tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Nullable UUID brandId) {
        var result = queries.reviewSummary(tenantId, from, to, brandId);
        return ResponseEntity.ok(new SummaryResponse(
                result.rows().stream().map(SummaryRowResponse::of).toList(),
                result.tags().stream().map(TagRowResponse::of).toList(),
                ReportingController.ProvenanceResponse.of(result.provenance())));
    }

    @GetMapping("/recovery")
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "recovery.*: service-recovery cases per location - opened, closed by outcome, times, breach rate",
            description = "A case counts as opened on its opening business day and closed on its closing "
                    + "day, so the two counts and the two durations are over different sets. Means are whole "
                    + "seconds rounded to the nearest; the 90th percentile is continuous over the same set. "
                    + "The breach rate is in basis points. Remedy money is returned per currency as it was "
                    + "attached and is not a registered metric (recovery.remedy_money is declared unbuilt: "
                    + "ADR 0038 needs a legal entity the compensation link does not carry).")
    public ResponseEntity<RecoveryResponse> recovery(
            @PathVariable UUID tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) @Nullable UUID brandId) {
        var result = queries.recoverySummary(tenantId, from, to, brandId);
        return ResponseEntity.ok(new RecoveryResponse(
                result.rows().stream().map(RecoveryRowResponse::of).toList(),
                result.outcomes().stream().map(OutcomeRowResponse::of).toList(),
                result.remedies().stream().map(RemedyRowResponse::of).toList(),
                ReportingController.ProvenanceResponse.of(result.provenance())));
    }

    /**
     * @param ratingSum   with {@code reviews}, what a total over locations is computed from: the sum and
     *                    the count are summed, then divided, never the mean of means
     * @param average     one decimal, half up; null when the location received no review
     * @param distribution the count at one, two, three, four and five stars
     */
    public record SummaryRowResponse(
            UUID brandId,
            UUID locationId,
            long reviews,
            long ratingSum,
            @Nullable BigDecimal average,
            long withComment,
            List<Long> distribution) {

        static SummaryRowResponse of(JdbcReportingStore.ReviewSummaryRow row) {
            return new SummaryRowResponse(
                    row.brandId(),
                    row.locationId(),
                    row.reviews(),
                    row.ratingSum(),
                    row.reviews() == 0
                            ? null
                            : BigDecimal.valueOf(row.ratingSum())
                                    .divide(BigDecimal.valueOf(row.reviews()), 1, RoundingMode.HALF_UP),
                    row.withComment(),
                    row.distribution());
        }
    }

    /**
     * @param category  food, service, courier, delivery time or other: a category and never a person
     * @param sentiment positive or negative
     */
    public record TagRowResponse(UUID brandId, UUID locationId, String category, String sentiment, long tags) {

        static TagRowResponse of(JdbcReportingStore.ReviewTagRow row) {
            return new TagRowResponse(row.brandId(), row.locationId(), row.category(), row.sentiment(), row.tags());
        }
    }

    public record SummaryResponse(
            List<SummaryRowResponse> rows,
            List<TagRowResponse> tags,
            ReportingController.ProvenanceResponse provenance) {}

    /**
     * @param breachRateBasisPoints cases opened in the range whose first-response or resolution clock was
     *                              marked breached, over cases opened, in basis points; null when none opened
     */
    public record RecoveryRowResponse(
            UUID brandId,
            UUID locationId,
            long opened,
            long closed,
            @Nullable Long firstResponseMeanSeconds,
            @Nullable Long firstResponseP90Seconds,
            @Nullable Long closeMeanSeconds,
            @Nullable Long closeP90Seconds,
            long breached,
            @Nullable Long breachRateBasisPoints,
            long compensations) {

        static RecoveryRowResponse of(JdbcReportingStore.RecoverySummaryRow row) {
            return new RecoveryRowResponse(
                    row.brandId(),
                    row.locationId(),
                    row.opened(),
                    row.closed(),
                    row.firstResponseMeanSeconds(),
                    row.firstResponseP90Seconds(),
                    row.closeMeanSeconds(),
                    row.closeP90Seconds(),
                    row.breached(),
                    row.opened() == 0
                            ? null
                            : BigDecimal.valueOf(row.breached())
                                    .multiply(BigDecimal.valueOf(10_000))
                                    .divide(BigDecimal.valueOf(row.opened()), 0, RoundingMode.HALF_UP)
                                    .longValueExact(),
                    row.compensations());
        }
    }

    public record OutcomeRowResponse(UUID brandId, UUID locationId, String outcomeCode, long cases) {

        static OutcomeRowResponse of(JdbcReportingStore.RecoveryOutcomeRow row) {
            return new OutcomeRowResponse(row.brandId(), row.locationId(), row.outcomeCode(), row.cases());
        }
    }

    /** Minor units of {@code currency}, as attached to the cases opened in the range. */
    public record RemedyRowResponse(UUID brandId, UUID locationId, String currency, long amountMinor) {

        static RemedyRowResponse of(JdbcReportingStore.RecoveryRemedyRow row) {
            return new RemedyRowResponse(row.brandId(), row.locationId(), row.currency(), row.amountMinor());
        }
    }

    public record RecoveryResponse(
            List<RecoveryRowResponse> rows,
            List<OutcomeRowResponse> outcomes,
            List<RemedyRowResponse> remedies,
            ReportingController.ProvenanceResponse provenance) {}
}
