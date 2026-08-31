package uz.horecaos.platform.pos.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.pos.application.PosOrderExportService;
import uz.horecaos.platform.pos.application.PosOrderExportService.OperatorDecision;
import uz.horecaos.platform.pos.domain.ExportCandidate;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosExportStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The queue of exports nothing could settle, and the decision that settles them
 * (ADR 0011, ADR 0031).
 *
 * <p>This endpoint exists because the point of sale it was written for has no
 * idempotency mechanism. An export whose response was lost may or may not have
 * printed a kitchen ticket, and the recovery read can only ever say "orders like
 * this exist at the till" — never "your order exists" — because two identical
 * baskets from one telephone number are a customer who ordered twice and a
 * double export, indistinguishably.
 *
 * <p>So the resolution is a person's, and this is where they make it. The screen
 * behind it shows the candidates with their match evidence and the honest
 * statement that the platform could not decide; ADR 0011 requires the raw
 * provider payload behind those candidates to be treated as protected
 * integration evidence, which is why this API returns match flags and identifiers
 * and not the payload.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/pos-exports")
@Tag(name = "POS order exports", description = "Exports to a point of sale, and the ones a person has to settle")
public class PosOrderExportController {

    private final PosOrderExportService exports;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final java.time.Clock clock;

    public PosOrderExportController(
            PosOrderExportService exports, AuditRecorder audit, CurrentActor currentActor, java.time.Clock clock) {
        this.exports = exports;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @GetMapping
    @RequiresCapability(Capability.POS_EXPORT_READ)
    @Operation(
            summary = "Exports waiting on a decision",
            description = "Exports whose outcome the platform could not establish. An empty list is "
                    + "the ordinary state; a growing one means a till or a network is unwell.")
    Page<ExportView> awaitingOperator(@PathVariable UUID tenantId, @RequestParam(defaultValue = "50") int limit) {

        List<ExportView> rows = exports.awaitingOperator(tenantId, Math.min(limit, 200)).stream()
                .map(PosOrderExportController::toView)
                .toList();
        return Page.last(rows);
    }

    @GetMapping("/{exportId}/candidates")
    @RequiresCapability(Capability.POS_EXPORT_READ)
    @Operation(
            summary = "What the recovery read found",
            description = "Provider orders that resemble this export. Resemblance is not identity: "
                    + "unless the provider echoed our own reference, none of these is proof.")
    Page<CandidateView> candidates(@PathVariable UUID tenantId, @PathVariable UUID exportId) {
        List<CandidateView> rows = exports.candidates(tenantId, exportId).stream()
                .map(PosOrderExportController::toView)
                .toList();
        return Page.last(rows);
    }

    @PostMapping("/{exportId}/discovery")
    @RequiresCapability(value = Capability.POS_EXPORT_RESOLVE, mutating = true)
    @Operation(
            summary = "Read the provider to find out what happened",
            description = "Side-effect free. It searches the provider and attaches what it found; "
                    + "it never re-sends the order.")
    ResponseEntity<Map<String, Object>> discover(@PathVariable UUID tenantId, @PathVariable UUID exportId) {

        var outcome = exports.discoverOutcome(tenantId, exportId);
        return ResponseEntity.ok(Map.of(
                "status", outcome.status().name(),
                "errorCode", outcome.errorCode() == null ? "" : outcome.errorCode(),
                "detail", outcome.detail() == null ? "" : outcome.detail()));
    }

    @PostMapping("/{exportId}/resolution")
    @RequiresCapability(value = Capability.POS_EXPORT_RESOLVE, mutating = true)
    @Operation(
            summary = "Settle an export a person had to decide",
            description = "LANDED requires the provider order it landed as, because everything "
                    + "downstream — the fiscal write-back, the reconciliation — keys on it. "
                    + "ABSENT permits one further export attempt. ABANDON does not.")
    ResponseEntity<Map<String, Object>> resolve(
            @PathVariable UUID tenantId, @PathVariable UUID exportId, @Valid @RequestBody ResolutionRequest request) {

        boolean changed;
        try {
            changed = exports.settleByOperator(
                    tenantId,
                    exportId,
                    request.decision(),
                    request.externalOrderId(),
                    request.reason(),
                    currentActor.get().subject());
        } catch (IllegalArgumentException incomplete) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, incomplete.getMessage());
        }

        if (changed) {
            // ADR 0027: the decision, its author, and the reason, in the same
            // transaction as the state change. An operator asserting that an order
            // reached a kitchen is a claim somebody may later have to defend
            // against a customer who never got their dinner.
            audit.record(AuditFact.of("pos.export_resolved", AuditClass.BUSINESS)
                    .by(ActorRef.user(currentActor.get().subject(), null))
                    .at(ResourceScope.tenant(tenantId))
                    .target("PosOrderExport", exportId)
                    .because(request.reason())
                    .changed(Map.of(
                            "decision",
                            request.decision().name(),
                            "externalOrderId",
                            request.externalOrderId() == null ? "" : request.externalOrderId()))
                    .usingCapability(Capability.POS_EXPORT_RESOLVE.code())
                    .correlatedBy(exportId.toString())
                    .occurredAt(clock.instant())
                    .build());
        }

        return ResponseEntity.ok(Map.of("changed", changed, "outcome", changed ? "resolved" : "no_change"));
    }

    private static ExportView toView(JdbcPosExportStore.ExportRow row) {
        return new ExportView(
                row.id(),
                row.orderId(),
                row.state().name(),
                row.attemptCount(),
                row.correlationReference(),
                row.externalOrderId(),
                row.externalVenueReference(),
                row.requestedAt().toString());
    }

    private static CandidateView toView(ExportCandidate candidate) {
        return new CandidateView(
                candidate.externalOrderId(),
                candidate.externalStatus(),
                candidate.externalCreatedAt() == null
                        ? null
                        : candidate.externalCreatedAt().toString(),
                candidate.correlationEchoed(),
                candidate.phoneMatches(),
                candidate.fingerprintMatches(),
                candidate.timeDeltaSeconds());
    }

    /**
     * A person's decision about one export nothing else could settle.
     *
     * @param externalOrderId required for {@link OperatorDecision#LANDED}, ignored
     *                        otherwise
     */
    public record ResolutionRequest(
            @NotNull OperatorDecision decision,
            // Required only for LANDED (enforced in PosOrderExportService, since
            // that is a cross-field rule the bean-validation annotations on this
            // record cannot express); absent on ABSENT/ABANDON requests.
            @Nullable @Size(max = 64) String externalOrderId,
            @NotBlank @Size(max = 1000) String reason) {}

    /** Carries no customer detail. The order id is the way to an authorized read. */
    public record ExportView(
            UUID exportId,
            UUID orderId,
            String state,
            int attemptCount,
            @Nullable String correlationReference,
            @Nullable String externalOrderId,
            String venue,
            String requestedAt) {}

    /**
     * One recovery-read candidate, as the API exposes it.
     *
     * @param correlationEchoed the only field here that decides anything. Where it
     *                          is true the candidate is our order by identity;
     *                          where it is false the rest is resemblance
     */
    public record CandidateView(
            String externalOrderId,
            @Nullable String externalStatus,
            @Nullable String externalCreatedAt,
            boolean correlationEchoed,
            boolean phoneMatches,
            boolean fingerprintMatches,
            @Nullable Integer timeDeltaSeconds) {}
}
