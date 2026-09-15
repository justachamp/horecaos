package uz.horecaos.platform.reporting.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.reporting.application.ProductClassificationService;
import uz.horecaos.platform.reporting.domain.ClassificationRun;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * T14 (7.7a/7.7b), ADR 0134: the persisted ABC/XYZ classification run behind
 * the product report's ABC and XYZ tabs, and the AX/CZ matrix filter over
 * them.
 *
 * <p>Its own controller, sibling to {@link ReportingController} rather than a
 * method on it: that class's own doc states "everything here is a read at
 * TENANT scope", and starting a run is deliberately not one — see {@link
 * Capability#REPORTING_CLASSIFICATION_RUN}'s own doc for why it is not
 * folded into {@code reporting.read}.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/reporting/classification-runs")
@Tag(name = "Product classification", description = "Persisted ABC/XYZ runs behind 7.7a/7.7b")
public class ProductClassificationController {

    private final ProductClassificationService classification;
    private final CurrentActor currentActor;

    public ProductClassificationController(ProductClassificationService classification, CurrentActor currentActor) {
        this.classification = classification;
        this.currentActor = currentActor;
    }

    @PostMapping
    @RequiresCapability(value = Capability.REPORTING_CLASSIFICATION_RUN, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Compute and persist a new ABC/XYZ classification run",
            description = "Refused under a 28-day window (statistics.md S2.7): a Pareto over "
                    + "four days is an artefact of one large order, and the month preset is "
                    + "month-to-date, so the closest pill can silently be shorter than the "
                    + "floor -- this refuses rather than answering anyway. Always writes a new "
                    + "run, even over a window just computed: more orders may have closed "
                    + "since, and the run live when a class-C ruling was disputed is the one "
                    + "that defends it, not a cache that might now disagree.")
    public ResponseEntity<ClassificationRunResponse> run(
            @PathVariable UUID tenantId, @Valid @RequestBody ClassificationRunRequest body) {

        ClassificationRun run = classification.run(
                tenantId,
                body.from(),
                body.to(),
                orEmpty(body.locationIds()),
                currentActor.get().subject());
        return ResponseEntity.ok(ClassificationRunResponse.of(
                run, ReportingController.ProvenanceResponse.of(classification.provenanceFor(tenantId))));
    }

    @GetMapping("/latest")
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The most recently computed run over this exact window, if any",
            description = "A read: seeing what was last computed never needs "
                    + "reporting.classification.run, only reporting.read. Answers "
                    + "RESOURCE_NOT_FOUND when nobody has run a classification over this "
                    + "exact window and location set yet -- the console offers a button to "
                    + "start one rather than treating an empty run as a rendering choice.")
    public ResponseEntity<ClassificationRunResponse> latest(
            @PathVariable UUID tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<UUID> locationId) {

        var found = classification.latest(tenantId, from, to, orEmpty(locationId));
        if (found.isEmpty()) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND, "No classification run recorded for this window yet", Map.of());
        }
        return ResponseEntity.ok(ClassificationRunResponse.of(
                found.get(), ReportingController.ProvenanceResponse.of(classification.provenanceFor(tenantId))));
    }

    private static List<UUID> orEmpty(@Nullable List<UUID> value) {
        return value == null ? List.of() : value;
    }

    /** @param locationIds omitted or empty means every location the caller may read */
    public record ClassificationRunRequest(
            @NotNull LocalDate from,
            @NotNull LocalDate to,
            @Nullable List<UUID> locationIds) {}

    /** One computed run, mirroring {@link ClassificationRun}. */
    public record ClassificationRunResponse(
            UUID runId,
            LocalDate from,
            LocalDate to,
            List<UUID> locationIds,
            String metricCode,
            int abcThresholdABasisPoints,
            int abcThresholdBBasisPoints,
            int xyzThresholdXBasisPoints,
            int xyzThresholdYBasisPoints,
            int bucketDays,
            int bucketCount,
            Instant computedAt,
            List<ClassificationRowResponse> rows,
            ReportingController.ProvenanceResponse provenance) {

        static ClassificationRunResponse of(ClassificationRun run, ReportingController.ProvenanceResponse provenance) {
            return new ClassificationRunResponse(
                    run.id(),
                    run.from(),
                    run.to(),
                    run.locationIds(),
                    run.metricCode(),
                    run.thresholds().abcThresholdA(),
                    run.thresholds().abcThresholdB(),
                    run.thresholds().xyzThresholdX(),
                    run.thresholds().xyzThresholdY(),
                    run.bucketDays(),
                    run.bucketCount(),
                    run.computedAt(),
                    run.rows().stream().map(ClassificationRowResponse::of).toList(),
                    provenance);
        }
    }

    /** One product's classification, mirroring {@link ClassificationRun.Row}. */
    public record ClassificationRowResponse(
            UUID variantId,
            @Nullable UUID categoryId,
            String productName,
            long revenueGrossSom,
            int revenueShareBasisPoints,
            int cumulativeShareBasisPoints,
            String abcClass,
            int quantityTotal,
            double meanQuantityPerBucket,
            double stddevQuantityPerBucket,
            int coefficientOfVariationBasisPoints,
            String xyzClass) {

        static ClassificationRowResponse of(ClassificationRun.Row row) {
            return new ClassificationRowResponse(
                    row.variantId(),
                    row.categoryId(),
                    row.productName(),
                    row.revenueGrossSom(),
                    row.revenueShareBasisPoints(),
                    row.cumulativeShareBasisPoints(),
                    String.valueOf(row.abcClass()),
                    row.quantityTotal(),
                    row.meanQuantityPerBucket(),
                    row.stddevQuantityPerBucket(),
                    row.coefficientOfVariationBasisPoints(),
                    String.valueOf(row.xyzClass()));
        }
    }
}
