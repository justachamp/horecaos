package uz.horecaos.platform.loyalty.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import uz.horecaos.platform.loyalty.api.PosLoyaltyBalancePort.SyncBinding;
import uz.horecaos.platform.loyalty.application.PosBalanceSyncService;
import uz.horecaos.platform.loyalty.application.PosBalanceSyncService.BindingView;
import uz.horecaos.platform.loyalty.application.PosBalanceSyncService.Overview;
import uz.horecaos.platform.loyalty.application.PosBalanceSyncService.Resolution;
import uz.horecaos.platform.loyalty.application.PosBalanceSyncService.ResolutionResult;
import uz.horecaos.platform.loyalty.application.PosBalanceSyncService.RunKind;
import uz.horecaos.platform.loyalty.application.PosBalanceSyncService.RunReport;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcPosBalanceSyncStore.DiscrepancyRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcPosBalanceSyncStore.RunRow;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The reconciliation report between the platform's loyalty ledger and a till's bonus balance, and the people who settle
 * its differences (ADR 0171, gap-map row 6.3b).
 *
 * <p>Reads declare {@code loyalty.read}. The two mutations -- resolve a difference, ask for a reconciliation now --
 * declare {@code loyalty.sync.manage} at {@code BRAND} scope, because a resolution can credit points and a brand's
 * outstanding points are its legal entity's liability. A resolution takes an {@code If-Match} on the discrepancy's version
 * and an {@code Idempotency-Key} (ADR 0031), and a credit above the manual-adjustment threshold answers {@code PENDING}
 * until a second person signs (ADR 0027).
 *
 * <p>No response here carries a phone, a name or the till's own customer record: a discrepancy names a points account
 * and the two balances. A vendor that cannot sync appears with its capability state ({@code UNASSESSED},
 * {@code UNSUPPORTED}) so the operator is told the till's balance is out of reach instead of being left to assume it is
 * mirrored.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/loyalty/pos-sync")
@Tag(name = "Loyalty POS sync", description = "Keeping a till's bonus balance a mirror of the ledger (ADR 0171)")
public class LoyaltyPosSyncController {

    private static final int MAX_LIST = 200;

    private final PosBalanceSyncService sync;
    private final CurrentActor currentActor;

    public LoyaltyPosSyncController(PosBalanceSyncService sync, CurrentActor currentActor) {
        this.sync = sync;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.LOYALTY_READ, scope = ScopeType.BRAND)
    @Operation(
            operationId = "loyaltyPosSyncOverview",
            summary = "Per point-of-sale binding: can it sync loyalty balances, and how is it doing",
            description = "Every POS binding of the brand with the state of LOYALTY_BALANCE_SYNC at its vendor "
                    + "(SUPPORTED, PARTIAL, UNSUPPORTED or UNASSESSED), whether it is enabled, its mode "
                    + "(REPORT_ONLY until somebody stages it up), how many accounts are matched, how many differences "
                    + "are open and the last pass. standingNotice is true when the tenant says this brand's till keeps "
                    + "a balance of its own and no binding carries the capability: the two can differ.")
    public ResponseEntity<PosSyncOverviewResponse> overview(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(PosSyncOverviewResponse.of(sync.overview(tenantId, brandId)));
    }

    @GetMapping("/runs")
    @RequiresCapability(value = Capability.LOYALTY_READ, scope = ScopeType.BRAND)
    @Operation(
            operationId = "loyaltyPosSyncRuns",
            summary = "Reconciliation reports, newest first",
            description =
                    "Push, pull and reconcile passes over the brand's bindings with the counts each named: compared, "
                            + "matched, till higher, till lower, till-only, platform-only, ambiguous, imported, held, unverifiable.")
    public ResponseEntity<List<PosSyncRunResponse>> runs(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate to,
            @RequestParam(required = false) @Nullable Integer limit) {

        LocalDate end = to == null ? LocalDate.now(ZoneOffset.UTC) : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        if (end.isBefore(start)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The range ends before it starts");
        }
        Instant fromInstant = start.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return ResponseEntity.ok(sync.runs(tenantId, brandId, fromInstant, toInstant, clamp(limit)).stream()
                .map(PosSyncRunResponse::of)
                .toList());
    }

    @GetMapping("/runs/{runId}")
    @RequiresCapability(value = Capability.LOYALTY_READ, scope = ScopeType.BRAND)
    @Operation(
            operationId = "loyaltyPosSyncRun",
            summary = "One reconciliation report with the differences it found",
            description = "Each difference carries the two balances and the delta; none carries a name or a phone.")
    public ResponseEntity<PosSyncRunReportResponse> run(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID runId) {
        return ResponseEntity.ok(PosSyncRunReportResponse.of(sync.report(tenantId, brandId, runId)));
    }

    @GetMapping("/discrepancies")
    @RequiresCapability(value = Capability.LOYALTY_READ, scope = ScopeType.BRAND)
    @Operation(
            operationId = "loyaltyPosSyncDiscrepancies",
            summary = "Differences between the ledger and a till, open first by default")
    public ResponseEntity<List<PosSyncDiscrepancyResponse>> discrepancies(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable Integer limit) {

        String normalised =
                status == null || status.isBlank() ? null : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalised != null && !normalised.equals("OPEN") && !normalised.equals("RESOLVED")) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "status must be OPEN or RESOLVED");
        }
        return ResponseEntity.ok(sync.discrepancies(tenantId, brandId, normalised, clamp(limit)).stream()
                .map(PosSyncDiscrepancyResponse::of)
                .toList());
    }

    @PostMapping("/discrepancies/{discrepancyId}/resolutions")
    @RequiresCapability(value = Capability.LOYALTY_SYNC_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            operationId = "loyaltyPosSyncResolve",
            summary = "Settle one difference",
            description =
                    "ACCEPT_TILL applies the till's number as an adjustment (above the manual-adjustment threshold it "
                            + "needs a second person's signature and answers approval PENDING, leaving the difference open); "
                            + "ACCEPT_PLATFORM pushes the platform's number again; ABSORB records that the brand carries it; "
                            + "SUSPEND stops syncing that customer. Requires If-Match with the discrepancy's version.")
    public ResponseEntity<PosSyncResolutionResponse> resolve(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID discrepancyId,
            @Valid @RequestBody PosSyncResolveRequest body,
            HttpServletRequest request) {

        long expected = AggregateVersion.requireIfMatch(request);
        ResolutionResult result = sync.resolve(
                tenantId, brandId, discrepancyId, body.resolution(), body.reason(), expected, actorSubject());
        return ResponseEntity.ok(PosSyncResolutionResponse.of(result));
    }

    @PostMapping("/runs")
    @RequiresCapability(value = Capability.LOYALTY_SYNC_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            operationId = "loyaltyPosSyncReconcile",
            summary = "Reconcile one binding now",
            description =
                    "Matches new till customers, imports and pushes as the binding's mode allows, and compares every "
                            + "matched account with a fresh read of the till's balance. Answers with the report row. A binding "
                            + "that cannot sync (an unassessed vendor, a capability not enabled, a suspended binding) answers 422.")
    public ResponseEntity<PosSyncRunResponse> reconcileNow(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody PosSyncReconcileRequest body) {
        return ResponseEntity.ok(PosSyncRunResponse.of(
                sync.run(tenantId, brandId, body.bindingId(), RunKind.RECONCILE, actorSubject())));
    }

    private String actorSubject() {
        return currentActor.get().subject();
    }

    private static int clamp(@Nullable Integer limit) {
        return limit == null ? 100 : Math.max(1, Math.min(limit, MAX_LIST));
    }

    // ---------------------------------------------------------------- requests

    public record PosSyncResolveRequest(
            @NotNull Resolution resolution,
            @NotBlank @Size(max = 500) String reason) {}

    public record PosSyncReconcileRequest(@NotNull UUID bindingId) {}

    // --------------------------------------------------------------- responses

    public record PosSyncRunSummary(
            UUID runId,
            String kind,
            String status,
            Instant startedAt,
            @Nullable Instant finishedAt) {

        static @Nullable PosSyncRunSummary of(@Nullable RunRow run) {
            return run == null
                    ? null
                    : new PosSyncRunSummary(run.id(), run.kind(), run.status(), run.startedAt(), run.finishedAt());
        }
    }

    public record PosSyncBindingResponse(
            UUID bindingId,
            UUID installationId,
            String providerType,
            @Nullable UUID locationId,
            String bindingStatus,
            String capabilityState,
            boolean enabled,
            boolean syncing,
            String mode,
            Map<String, Object> limits,
            long matchedAccounts,
            long openDiscrepancies,
            @Nullable PosSyncRunSummary lastRun) {

        static PosSyncBindingResponse of(BindingView view) {
            SyncBinding binding = view.binding();
            return new PosSyncBindingResponse(
                    binding.bindingId(),
                    binding.installationId(),
                    binding.providerType(),
                    binding.locationId(),
                    binding.bindingStatus(),
                    binding.state().name(),
                    binding.enabled(),
                    binding.syncing(),
                    view.mode().name(),
                    binding.limits(),
                    view.matchedAccounts(),
                    view.openDiscrepancies(),
                    PosSyncRunSummary.of(view.lastRun()));
        }
    }

    public record PosSyncOverviewResponse(
            boolean tillKeepsBalance,
            boolean standingNotice,
            long openDiscrepancies,
            List<PosSyncBindingResponse> bindings) {

        static PosSyncOverviewResponse of(Overview overview) {
            return new PosSyncOverviewResponse(
                    overview.tillKeepsBalance(),
                    overview.standingNotice(),
                    overview.openDiscrepancies(),
                    overview.bindings().stream().map(PosSyncBindingResponse::of).toList());
        }
    }

    public record PosSyncRunResponse(
            UUID runId,
            UUID bindingId,
            String kind,
            String status,
            @Nullable String failureCode,
            Instant startedAt,
            @Nullable Instant finishedAt,
            int compared,
            int matched,
            int tillHigher,
            int tillLower,
            int tillOnly,
            int platformOnly,
            int ambiguous,
            int imported,
            int held,
            int unverifiable,
            int pushed) {

        static PosSyncRunResponse of(RunRow run) {
            return new PosSyncRunResponse(
                    run.id(),
                    run.bindingId(),
                    run.kind(),
                    run.status(),
                    run.failureCode(),
                    run.startedAt(),
                    run.finishedAt(),
                    run.compared(),
                    run.matched(),
                    run.tillHigher(),
                    run.tillLower(),
                    run.tillOnly(),
                    run.platformOnly(),
                    run.ambiguous(),
                    run.imported(),
                    run.held(),
                    run.unverifiable(),
                    run.pushed());
        }
    }

    /**
     * One difference. {@code accountId} is the points account (an opaque id), {@code externalCustomerId} the till's own
     * customer id, classified INTERNAL like every external id in the mapping table. Neither is a name or a phone.
     */
    public record PosSyncDiscrepancyResponse(
            UUID discrepancyId,
            UUID bindingId,
            @Nullable UUID accountId,
            @Nullable String externalCustomerId,
            String kind,
            @Nullable Long platformBalanceMinor,
            @Nullable Long tillBalanceMinor,
            @Nullable Long deltaMinor,
            String status,
            @Nullable String resolutionCode,
            @Nullable String resolutionReason,
            @Nullable String resolvedBy,
            @Nullable Instant resolvedAt,
            Instant observedAt,
            int version) {

        static PosSyncDiscrepancyResponse of(DiscrepancyRow row) {
            return new PosSyncDiscrepancyResponse(
                    row.id(),
                    row.bindingId(),
                    row.accountId(),
                    row.externalCustomerId(),
                    row.kind(),
                    row.platformBalanceMinor(),
                    row.tillBalanceMinor(),
                    row.deltaMinor(),
                    row.status(),
                    row.resolutionCode(),
                    row.resolutionReason(),
                    row.resolvedBy(),
                    row.resolvedAt(),
                    row.observedAt(),
                    row.version());
        }
    }

    public record PosSyncRunReportResponse(PosSyncRunResponse run, List<PosSyncDiscrepancyResponse> discrepancies) {

        static PosSyncRunReportResponse of(RunReport report) {
            return new PosSyncRunReportResponse(
                    PosSyncRunResponse.of(report.run()),
                    report.discrepancies().stream()
                            .map(PosSyncDiscrepancyResponse::of)
                            .toList());
        }
    }

    /**
     * @param applied  whether the difference is now resolved; false when a second signature is awaited
     * @param approval NOT_REQUIRED, PENDING, APPROVED or DECLINED (ADR 0027)
     */
    public record PosSyncResolutionResponse(
            PosSyncDiscrepancyResponse discrepancy,
            boolean applied,
            String approval,
            @Nullable UUID approvalRequestId) {

        static PosSyncResolutionResponse of(ResolutionResult result) {
            return new PosSyncResolutionResponse(
                    PosSyncDiscrepancyResponse.of(result.discrepancy()),
                    result.applied(),
                    result.approval(),
                    result.approvalRequestId());
        }
    }
}
