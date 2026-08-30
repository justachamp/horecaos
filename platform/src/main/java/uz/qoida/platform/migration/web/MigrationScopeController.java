package uz.qoida.platform.migration.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.qoida.platform.iam.api.Capability;
import uz.qoida.platform.iam.api.CurrentActor;
import uz.qoida.platform.iam.api.ResourceScope.ScopeType;
import uz.qoida.platform.migration.application.MigrationCutoverDecisionStore.DecisionRow;
import uz.qoida.platform.migration.application.MigrationScopeService;
import uz.qoida.platform.migration.application.MigrationScopeStore.ScopeRow;
import uz.qoida.platform.migration.domain.ScopeState;
import uz.qoida.platform.web.api.AggregateVersion;
import uz.qoida.platform.web.authorization.RequiresCapability;
import uz.qoida.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Moving one capability scope, and deciding its cutover (ADR 0024).
 *
 * <p><strong>There is no endpoint that sets a scope's write mode.</strong> That
 * absence is the most important thing about this controller. A {@code PUT} of
 * {@code TARGET_ONLY} onto a scope would transfer ownership of a tenant's writes
 * with one command, with no reconciliation behind it, no recorded approver, and
 * no evidence — which is precisely what ADR 0024 means when it says free-form
 * manual database updates are not a cutover mechanism, and offering it over HTTP
 * rather than over psql does not make it one. Ownership moves through {@link
 * #cutOver}, which re-evaluates the gates, appends a signed decision, and changes
 * the modes in one transaction, or it does not move.
 *
 * <p>The tenant is a query parameter and not a path segment. Every service call
 * here carries it as a predicate — a scope decides who may write a tenant's
 * orders, so a lookup keyed on the scope identifier alone would be a cross-tenant
 * read of the row granting write authority — but it is not the resource's
 * address. Putting it in the path would invite a {@code TENANT}-scoped
 * declaration, and a tenant administrator who could reach these endpoints could
 * hand their own capability to a target nobody has finished filling. Every
 * declaration is therefore {@link ScopeType#PLATFORM}, which is the only scope a
 * path carrying no tenant, brand, or location identifier can support.
 */
@RestController
@RequestMapping("/api/v1/platform-admin/migration/scopes")
@Tag(name = "Migration scopes", description = "Capability ownership, its transitions, and cutover decisions")
public class MigrationScopeController {

    private final MigrationScopeService scopes;
    private final CurrentActor currentActor;

    public MigrationScopeController(MigrationScopeService scopes, CurrentActor currentActor) {
        this.scopes = scopes;
        this.currentActor = currentActor;
    }

    @GetMapping("/{scopeId}")
    @RequiresCapability(value = Capability.MIGRATION_READ, scope = ScopeType.PLATFORM)
    @Operation(summary = "Get a capability scope",
            description = "The ETag carries the scope version, which every transition below "
                    + "requires back as expectedVersion.")
    ResponseEntity<ScopeView> get(@PathVariable UUID scopeId, @RequestParam UUID tenantId) {
        ScopeRow scope = scopes.get(tenantId, scopeId);
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(scope.version()))
                .body(ScopeView.of(scope));
    }

    /**
     * Moves the scope along its ordinary path.
     *
     * <p>Ordinary means everything that is not a suspension, a resumption, or a
     * transfer of ownership. Each of those records something a plain state change
     * cannot — the state being left, the state to return to, the approver and the
     * evidence — and each has its own endpoint below. Asking for one of them here
     * is refused rather than half-performed.
     */
    @PostMapping("/{scopeId}/transitions")
    @RequiresCapability(value = Capability.MIGRATION_SCOPE_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(summary = "Advance a scope to its next state",
            description = "TARGET_OWNED is not reachable here. Taking target ownership goes through "
                    + "the cutover endpoint, which records who approved it and on what evidence "
                    + "before the write mode moves.")
    ResponseEntity<ScopeView> advance(
            @PathVariable UUID scopeId,
            @RequestParam UUID tenantId,
            @RequestHeader(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody AdvanceScopeRequest body) {

        ScopeRow scope = scopes.advance(tenantId, scopeId, new MigrationScopeService.AdvanceCommand(
                body.targetState(), body.expectedVersion(), body.reason(), idempotencyKey));
        return versioned(scope);
    }

    /**
     * Takes the writer back from the target.
     *
     * <p>Guarded by {@link Capability#MIGRATION_CUTOVER_APPROVE} rather than
     * {@code MIGRATION_SCOPE_MANAGE}, and separate from the transitions endpoint
     * for that reason alone. Requiring two people to give a capability to the
     * target and one to take it away would be an asymmetry with the dangerous half
     * on the cheap side: a rollback fences a capability that is currently serving
     * customers, and restoring it afterwards means a full re-validation.
     */
    @PostMapping("/{scopeId}/rollbacks")
    @RequiresCapability(value = Capability.MIGRATION_CUTOVER_APPROVE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(summary = "Reverse a cutover, returning the writer to legacy",
            description = "Reachable from TARGET_OWNED and CANARY. Not gated on reconciliation: a "
                    + "critical difference is the reason to roll back, so requiring a clear one "
                    + "would remove the escape exactly when it is needed.")
    ResponseEntity<ScopeView> rollBack(
            @PathVariable UUID scopeId,
            @RequestParam UUID tenantId,
            @RequestHeader(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody RollbackScopeRequest body) {

        ScopeRow scope = scopes.rollBack(tenantId, scopeId, new MigrationScopeService.RollbackCommand(
                body.expectedVersion(), body.reason(), idempotencyKey));
        return versioned(scope);
    }

    /**
     * Suspends the scope, recording the state it is leaving.
     *
     * <p>The modes are untouched, and that is deliberate: a paused
     * {@code TARGET_ONLY} scope whose write mode was reset would have handed the
     * capability back to legacy merely by being paused. Writes are fenced anyway,
     * because ownership is suspended on the state rather than on the mode.
     */
    @PostMapping("/{scopeId}/suspensions")
    @RequiresCapability(value = Capability.MIGRATION_SCOPE_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(summary = "Pause a scope, or block it on reconciliation",
            description = "PAUSED is a decision somebody made and BLOCKED_RECONCILIATION is one "
                    + "evidence forced. The operator resuming it needs to know which.")
    ResponseEntity<ScopeView> suspend(
            @PathVariable UUID scopeId,
            @RequestParam UUID tenantId,
            @RequestHeader(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody SuspendScopeRequest body) {

        ScopeRow scope = scopes.suspend(tenantId, scopeId, new MigrationScopeService.SuspendCommand(
                body.holdingState(), body.expectedVersion(), body.reason(), idempotencyKey));
        return versioned(scope);
    }

    /**
     * Returns a held scope to the state it left.
     *
     * <p>The request carries no destination and cannot be given one. The
     * destination is read from what the suspension recorded, so that pausing a
     * canary and resuming it target-owned is not a request anyone can make.
     */
    @PostMapping("/{scopeId}/resumptions")
    @RequiresCapability(value = Capability.MIGRATION_SCOPE_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(summary = "Resume a held scope into the state it was suspended from")
    ResponseEntity<ScopeView> resume(
            @PathVariable UUID scopeId,
            @RequestParam UUID tenantId,
            @RequestHeader(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody ResumeScopeRequest body) {

        ScopeRow scope = scopes.resume(tenantId, scopeId, new MigrationScopeService.ResumeCommand(
                body.expectedVersion(), body.reason(), idempotencyKey));
        return versioned(scope);
    }

    /**
     * Publishes how many in-scope sources are still undecided.
     *
     * <p>The count comes from the discovery tooling's inventory, which the control
     * plane deliberately holds no copy of. Publishing a zero is therefore a claim
     * a named operator makes and is on record as having made, and it is what the
     * cutover gate reads.
     */
    @PostMapping("/{scopeId}/coverage")
    @RequiresCapability(value = Capability.MIGRATION_SCOPE_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(summary = "Republish the scope's undecided-source count")
    ResponseEntity<ScopeView> republishCoverage(
            @PathVariable UUID scopeId,
            @RequestParam UUID tenantId,
            @Valid @RequestBody CoverageRequest body) {

        ScopeRow scope = scopes.republishCoverage(tenantId, scopeId, body.undecidedSources(),
                body.expectedVersion(), body.reason());
        return versioned(scope);
    }

    /**
     * Approves the transfer of ownership and performs it.
     *
     * <p>The approver is the authenticated caller and is never taken from the
     * body. Letting a request name its own approver would leave ADR 0027's four
     * eyes as a string comparison between two values one person supplied, and the
     * whole separation between {@code MIGRATION_SCOPE_MANAGE} and {@code
     * MIGRATION_CUTOVER_APPROVE} would be spent on a check that cannot fail.
     * {@code requestedBy} is supplied because it names the person who asked for
     * the window, which is a fact about an earlier event and not about this
     * request; the service refuses the pair when they are the same person.
     */
    @PostMapping("/{scopeId}/cutover")
    @RequiresCapability(value = Capability.MIGRATION_CUTOVER_APPROVE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(summary = "Approve a cutover and transfer ownership to the target",
            description = "The gates are re-evaluated in this transaction rather than trusted from "
                    + "whenever the scope reached CUTOVER_READY: a critical difference found "
                    + "overnight stops a window approved yesterday evening.")
    ResponseEntity<ScopeView> cutOver(
            @PathVariable UUID scopeId,
            @RequestParam UUID tenantId,
            @RequestHeader(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody CutoverDecisionRequest body) {

        ScopeRow scope = scopes.cutOver(tenantId, scopeId, body.toCommand(decider(), idempotencyKey));
        return versioned(scope);
    }

    /**
     * Records that a named person declined a proposed cutover.
     *
     * <p>A refusal is evidence and is kept as one. A decision table holding only
     * approvals would make "nobody ever asked" and "somebody said no twice" look
     * identical, and the second is the more interesting one at any review.
     */
    @PostMapping("/{scopeId}/cutover-refusals")
    @RequiresCapability(value = Capability.MIGRATION_CUTOVER_APPROVE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(summary = "Refuse a proposed cutover, leaving the scope where it is")
    CutoverDecisionView refuseCutover(
            @PathVariable UUID scopeId,
            @RequestParam UUID tenantId,
            @RequestHeader(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody CutoverDecisionRequest body) {

        DecisionRow decision =
                scopes.refuseCutover(tenantId, scopeId, body.toCommand(decider(), idempotencyKey));
        return CutoverDecisionView.of(decision);
    }

    private String decider() {
        return currentActor.get().subject();
    }

    private static ResponseEntity<ScopeView> versioned(ScopeRow scope) {
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(scope.version()))
                .body(ScopeView.of(scope));
    }

    /** @param expectedVersion the scope version the operator was looking at (ADR 0031) */
    record AdvanceScopeRequest(
            @NotNull ScopeState targetState,
            @Positive int expectedVersion,
            @NotBlank @Size(max = 1000) String reason) { }

    /** @param reason mandatory: a writer taken back from the target is an incident */
    record RollbackScopeRequest(
            @Positive int expectedVersion,
            @NotBlank @Size(max = 1000) String reason) { }

    /** @param holdingState PAUSED for a decision, BLOCKED_RECONCILIATION for evidence */
    record SuspendScopeRequest(
            @NotNull ScopeState holdingState,
            @Positive int expectedVersion,
            @NotBlank @Size(max = 1000) String reason) { }

    record ResumeScopeRequest(
            @Positive int expectedVersion,
            @NotBlank @Size(max = 1000) String reason) { }

    record CoverageRequest(
            @PositiveOrZero int undecidedSources,
            @Positive int expectedVersion,
            @NotBlank @Size(max = 1000) String reason) { }

    /**
     * @param evidence    the aggregate figures the decision rests on — watermarks,
     *                    counts, checksums, the reconciliation runs that cleared,
     *                    the observed soak window. Flat and bounded: values are
     *                    strings and nesting is unrepresentable, because the moment
     *                    a snapshot accepts a nested document it becomes where a
     *                    diagnosing engineer pastes the sample rows, and the control
     *                    plane acquires a copy of source data ADR 0029 has no record
     *                    of
     * @param requestedBy who asked for the window; never the authenticated caller,
     *                    who is recorded as the decider
     * @param requestedAt when the window was asked for, which is not when it was
     *                    granted; omit when both happened now
     */
    record CutoverDecisionRequest(
            @NotNull ScopeState targetState,
            @Positive int expectedVersion,
            @NotBlank @Size(max = 1000) String reason,
            @NotEmpty @Size(max = 32) Map<@NotBlank @Size(max = 64) String,
                    @NotBlank @Size(max = 512) String> evidence,
            @NotBlank @Size(max = 255) String requestedBy,
            @Schema(description = "The ADR 0027 maker-checker request this decision discharges")
            UUID approvalRequestId,
            Instant requestedAt) {

        MigrationScopeService.CutoverCommand toCommand(String decidedBy, String idempotencyKey) {
            return new MigrationScopeService.CutoverCommand(
                    targetState, expectedVersion, reason, new LinkedHashMap<String, Object>(evidence),
                    requestedBy, decidedBy, approvalRequestId, requestedAt, idempotencyKey);
        }
    }
}
