package uz.horecaos.platform.migration.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalRequestOwnership;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.migration.application.MigrationCutoverDecisionStore.Decision;
import uz.horecaos.platform.migration.application.MigrationCutoverDecisionStore.DecisionRow;
import uz.horecaos.platform.migration.application.MigrationScopeStore.ScopeRow;
import uz.horecaos.platform.migration.domain.OwnershipModes;
import uz.horecaos.platform.migration.domain.ScopeState;
import uz.horecaos.platform.migration.domain.ScopeStateMachine;
import uz.horecaos.platform.migration.domain.WriteMode;

/**
 * The transition engine: the only way a migration scope moves (ADR 0024).
 *
 * <p>{@link ScopeStateMachine} answers whether a move exists. This class answers
 * whether it is allowed now, and the difference between the two questions is
 * where ADR 0024's central prohibition lives. The ADR says a platform-admin UI
 * may not skip required reconciliation or invent a target owner; the transition
 * table cannot say that, because whether reconciliation cleared is a fact about
 * rows written long after the table was compiled. So the edges exist, the gates
 * are here, and there is no second path to the same UPDATE.
 *
 * <p>Four refusals carry the weight, and each one is a refusal rather than a
 * warning because the alternative in every case is a capability quietly changing
 * hands on evidence that does not exist.
 *
 * <ol>
 * <li>A scope may not reach {@code CUTOVER_READY} while an unresolved CRITICAL
 * reconciliation difference stands against it. Every advancing transition
 * carries this check, matching what the results table itself claims — an open
 * critical row blocks every transition of its scope — and the escapes
 * (suspending, rolling back, withdrawing an approval) are deliberately outside
 * it, because a blocked scope must still be able to move away from trouble.</li>
 * <li>A scope may not reach {@code CUTOVER_READY} while any in-scope source is
 * still undecided in the coverage register. See {@link #COVERAGE_UNDECIDED}.</li>
 * <li>Entering {@code TARGET_ONLY} requires an approved cutover decision,
 * inserted before the modes change and in the same transaction. {@link
 * #advance} refuses the move outright and sends the caller to {@link
 * #cutOver}, so there is no ordering in which the ownership transfer commits
 * and the evidence does not.</li>
 * <li>Returning from a holding state goes back to the state the scope actually
 * left, read from the checkpoint written when it was suspended. See {@link
 * #RESUME_STATE}.</li>
 * </ol>
 */
@Service
public class MigrationScopeService {

    /**
     * The checkpoint key holding the state a held scope must return to.
     *
     * <p>Written when the scope is suspended, read when it resumes, removed when
     * it does. The state machine deliberately gives {@code PAUSED} and {@code
     * BLOCKED_RECONCILIATION} no outgoing edges precisely so that this is a stored
     * fact about one scope rather than a property of the states: allowing {@code
     * PAUSED -> anything} would let an operator pause a canary and resume it
     * target-owned, which is a cutover performed by pressing pause.
     *
     * <p>It lives in the checkpoint because V0024 gives the scope no column of its
     * own for it, and the checkpoint is already the gate evidence carried between
     * transitions. Its absence on a held scope is not treated as a default: {@link
     * #resume} refuses, because a resume that guesses is a resume that can
     * silently promote a scope.
     */
    public static final String RESUME_STATE = "resumeState";

    /**
     * The checkpoint key holding how many in-scope sources are still undecided.
     *
     * <p>ADR 0024's coverage register — every source table classified MIGRATE,
     * TRANSFORM, DROP or DECIDE — is a planning artefact under {@code
     * docs/domains/legacy-mapping.md} and in the discovery tooling's own
     * inventory. It is not in this database and cannot be read at runtime, so the
     * count of sources still sitting at DECIDE is published onto the scope by
     * {@link #republishCoverage} at the end of each discovery or mapping pass and
     * read back here.
     *
     * <p>Absence means unknown, and unknown blocks. A scope whose coverage has
     * never been published has not proved that every source was decided; it has
     * only failed to say. Defaulting the missing case to zero would make the gate
     * pass for exactly the scopes nobody has looked at.
     */
    public static final String COVERAGE_UNDECIDED = "undecidedSources";

    /** How many blocking rules a refusal names before it stops listing them. */
    private static final int REFUSAL_DETAIL = 5;

    private static final Logger log = LoggerFactory.getLogger(MigrationScopeService.class);

    private final MigrationScopeStore scopes;
    private final MigrationReconciliationStore reconciliation;
    private final MigrationCutoverDecisionStore decisions;
    private final MigrationQuarantineStore quarantine;
    private final MigrationAccessPolicy access;
    private final ApprovalRequestOwnership approvals;
    private final MigrationAudit audit;
    private final Clock clock;

    public MigrationScopeService(MigrationScopeStore scopes,
            MigrationReconciliationStore reconciliation, MigrationCutoverDecisionStore decisions,
            MigrationQuarantineStore quarantine, MigrationAccessPolicy access,
            ApprovalRequestOwnership approvals, MigrationAudit audit, Clock clock) {
        this.scopes = scopes;
        this.reconciliation = reconciliation;
        this.decisions = decisions;
        this.quarantine = quarantine;
        this.access = access;
        this.approvals = approvals;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ScopeRow get(UUID tenantId, UUID scopeId) {
        access.requireOperator();
        return requireScope(tenantId, scopeId);
    }

    /**
     * Moves a scope along its ordinary path.
     *
     * <p>Everything except the three moves that need more than a state change:
     * suspending needs the state being left recorded, resuming needs it read back,
     * and taking ownership needs a decision appended first. Each of those has its
     * own method and this one refuses them, so there is no shape of {@code
     * advance} call that performs half of one.
     */
    @Transactional
    public ScopeRow advance(UUID tenantId, UUID scopeId, AdvanceCommand command) {
        Objects.requireNonNull(command, "An advance command is required");
        String actor = access.requireOperator();

        ScopeRow scope = requireScope(tenantId, scopeId);
        ScopeState from = scope.state();
        ScopeState to = Objects.requireNonNull(command.targetState(), "A target state is required");
        requireVersion(scope, command.expectedVersion());

        if (from.holding()) {
            throw wrongEntryPoint(scope, to,
                    "A held scope leaves through resume, which returns it to the state it left. "
                            + "Advancing from %s would let a pause become a promotion.".formatted(from));
        }
        if (to.holding()) {
            throw wrongEntryPoint(scope, to,
                    "Suspending a scope goes through suspend, which records the state it is "
                            + "leaving. Without that record it could not be resumed to anywhere.");
        }
        if (to == ScopeState.ROLLING_BACK) {
            // The mirror of the cutover refusal below. Entering a rollback reverses
            // the ownership transfer, and ADR 0027's maker-checker means the person
            // who runs a migration is not the person who may move its writer — in
            // either direction. Left on this endpoint it is served by
            // MIGRATION_SCOPE_MANAGE, so one maker could fence a live tenant's
            // ordering and leave it fenced for the length of a full re-validation:
            // ROLLING_BACK -> CATCHING_UP -> SHADOW_READING -> CANARY ->
            // CUTOVER_READY, then a second approver at cutOver.
            throw wrongEntryPoint(scope, to,
                    "Reversing a cutover goes through rollBack, which the approver capability "
                            + "guards. Taking a writer back is the same decision as giving it, and "
                            + "it is not the maker's to make.");
        }
        ScopeStateMachine.require(from, to);

        OwnershipModes modes = modesEntering(scope, to);
        if (entersTargetOwnership(scope.modes(), modes)) {
            // Rule 3. The decision has to be appended before the modes change and
            // in the same transaction, and only cutOver does that. Refusing here
            // rather than checking for an existing decision keeps one path to
            // TARGET_ONLY instead of two that must agree.
            throw wrongEntryPoint(scope, to,
                    "Taking target ownership goes through cutOver, which records the approver and "
                            + "the evidence before the write mode moves, never after.");
        }

        if (advancing(from, to)) {
            requireReconciliationClear(scope, to, actor);
        }
        if (to == ScopeState.CUTOVER_READY) {
            requireCoverageDecided(scope, actor);
        }
        if (to == ScopeState.RETIRED) {
            requireQuarantineSettled(scope, actor);
        }

        return apply(scope, to, modes, scope.checkpoint(), command.reason(), actor,
                "migration.scope.advanced", Map.of(), null);
    }

    /**
     * Suspends a scope, recording where it must come back to.
     *
     * <p>The modes are kept exactly as they were, which is the whole point of the
     * holding states carrying every running mode pair rather than one: a paused
     * {@code TARGET_ONLY} scope that had its write mode reset would have handed
     * the capability back to legacy by being paused. The ownership answer stays
     * fenced regardless, because {@code CapabilityOwnership} suspends writes on
     * the state and not on the mode.
     */
    @Transactional
    public ScopeRow suspend(UUID tenantId, UUID scopeId, SuspendCommand command) {
        Objects.requireNonNull(command, "A suspend command is required");
        String actor = access.requireOperator();

        ScopeRow scope = requireScope(tenantId, scopeId);
        ScopeState to = Objects.requireNonNull(command.holdingState(), "A holding state is required");
        requireVersion(scope, command.expectedVersion());

        if (!to.holding()) {
            throw new IllegalArgumentException(
                    "%s is not a holding state; use advance for an ordinary move".formatted(to));
        }
        ScopeStateMachine.require(scope.state(), to);

        Map<String, Object> checkpoint = mutableCheckpoint(scope);
        checkpoint.put(RESUME_STATE, scope.state().name());

        return apply(scope, to, scope.modes(), checkpoint, command.reason(), actor,
                "migration.scope.suspended", Map.of(RESUME_STATE, scope.state().name()), null);
    }

    /**
     * Returns a held scope to the state it left.
     *
     * <p>The caller does not choose the destination and cannot. It is read from
     * the checkpoint the suspension wrote, and a scope that has no such record is
     * refused rather than sent somewhere plausible — the plausible answer for a
     * scope whose last observed activity was a canary is {@code CANARY}, and being
     * wrong about that once is a capability whose writer nobody can name.
     */
    @Transactional
    public ScopeRow resume(UUID tenantId, UUID scopeId, ResumeCommand command) {
        Objects.requireNonNull(command, "A resume command is required");
        String actor = access.requireOperator();

        ScopeRow scope = requireScope(tenantId, scopeId);
        requireVersion(scope, command.expectedVersion());
        if (!scope.state().holding()) {
            throw wrongEntryPoint(scope, scope.state(),
                    "Scope %s is %s, which is not a held state".formatted(scopeId, scope.state()));
        }

        ScopeState to = recordedResumeState(scope, actor);
        ScopeStateMachine.requireResume(scope.state(), to);

        // The scope kept its modes while it was held, so the state it returns to
        // must be one those modes belong in. When it is not, the row drifted while
        // the scope was suspended and the honest move is to refuse: guessing which
        // of the state and the modes is the true one is guessing at whether a
        // capability is legacy-owned or target-owned.
        if (!to.permits(scope.modes())) {
            throw refuse(scope, MigrationPreconditionException.INCOHERENT_OWNERSHIP_MODES, actor,
                    ("Scope %s is held with write mode %s and read mode %s, which %s does not "
                            + "permit. Its stored modes and its recorded resume state disagree and "
                            + "one of them is wrong.").formatted(scopeId, scope.modes().writeMode(),
                            scope.modes().readMode(), to));
        }

        if (scope.state() == ScopeState.BLOCKED_RECONCILIATION) {
            // A reconciliation block is not a pause somebody may simply lift. It
            // was entered because evidence forced it, and resuming while that
            // evidence still stands would return the scope to a path it is not
            // entitled to be on.
            requireReconciliationClear(scope, to, actor);
        }

        Map<String, Object> checkpoint = mutableCheckpoint(scope);
        checkpoint.remove(RESUME_STATE);

        return apply(scope, to, scope.modes(), checkpoint, command.reason(), actor,
                "migration.scope.resumed", Map.of("resumedFrom", scope.state().name()), null);
    }

    /**
     * Reverses a cutover: the transition that takes the writer back.
     *
     * <p>Split from {@link #advance} so it can be guarded by the approver
     * capability rather than the maker's. The asymmetry would be indefensible —
     * two people to hand a capability to the target, one to take it away — and the
     * taking-away is the more dangerous half in practice, because it fences a
     * capability that is currently serving customers.
     *
     * <p>Deliberately not gated on reconciliation. A critical difference found
     * mid-canary is the reason to roll back, so requiring a clear reconciliation
     * first would make the escape unavailable exactly when it is needed. ADR 0024
     * lists money and state discrepancy among the rollback criteria.
     *
     * <p>The modes are not moved here. ADR 0024 is explicit that rollback is an
     * ownership and traffic operation carried out with reconciliation, not an undo
     * of the data movement, and {@code ROLLING_BACK} permits all three mode pairs
     * precisely so the operation is representable while it is half done. Writes
     * are fenced meanwhile by the state, not by the mode.
     */
    @Transactional
    public ScopeRow rollBack(UUID tenantId, UUID scopeId, RollbackCommand command) {
        Objects.requireNonNull(command, "A rollback command is required");
        String actor = access.requireOperator();

        ScopeRow scope = requireScope(tenantId, scopeId);
        requireVersion(scope, command.expectedVersion());
        ScopeStateMachine.require(scope.state(), ScopeState.ROLLING_BACK);

        String reason = requireText(command.reason(),
                "A rollback records why the writer is being taken back");

        return apply(scope, ScopeState.ROLLING_BACK, scope.modes(), scope.checkpoint(), reason,
                actor, "migration.scope.rollback.started",
                Map.of("rolledBackFrom", scope.state().name(),
                        "writeMode", scope.modes().writeMode().name()),
                null);
    }

    /**
     * @param reason not optional. A capability being taken back from the target is
     *               an incident, and the reason is the first thing the review asks
     *               for.
     */
    public record RollbackCommand(int expectedVersion, String reason, String idempotencyKey) { }

    /**
     * Transfers ownership: the one transition that changes who writes a tenant's
     * data.
     *
     * <p>The order of the three steps is the rule, not an implementation detail.
     * The gates are re-evaluated here, in this transaction, rather than trusted
     * from whenever the scope reached {@code CUTOVER_READY} — a critical
     * difference found overnight must stop a window that was approved yesterday
     * evening. The decision is appended next, carrying the approver, the evidence
     * and the scope version it was taken against. Only then does the write mode
     * move. A crash anywhere in the sequence rolls back the whole of it, so there
     * is no state in which the target owns writes and nobody signed for them.
     *
     * <p>{@code ux_cutover_approved_per_version} settles two approvers racing on
     * one version: one insert succeeds, the other's transaction fails, and the
     * loser is told the scope moved rather than being allowed to believe they
     * moved it.
     */
    @Transactional
    public ScopeRow cutOver(UUID tenantId, UUID scopeId, CutoverCommand command) {
        Objects.requireNonNull(command, "A cutover command is required");
        String actor = access.requireOperator();

        Optional<DecisionRow> replayed = decisions.findByIdempotencyKey(tenantId,
                requireKey(command.idempotencyKey()));
        if (replayed.isPresent()) {
            // ADR 0031. The decision is append-only and its key is unique per
            // tenant, so a retried approval reports the scope as the first attempt
            // left it instead of moving it a second time.
            DecisionRow decision = replayed.get();
            if (!decision.scopeId().equals(scopeId)) {
                throw new MigrationConflictException(
                        "That idempotency key already decided scope " + decision.scopeId());
            }
            // A refusal and an approval share one idempotency key space, so the
            // stored decision has to be checked for what it actually was. Without
            // this, reusing a refusal's key on the cutover endpoint returns 200
            // with the scope and an ETag while nothing transitioned and no
            // decision was appended — and the operator, reading a successful
            // cutover, goes and fences legacy by hand. That leaves the capability
            // with no writer at all, which is the one outcome this module exists
            // to make unreachable.
            if (decision.decision() != Decision.APPROVED
                    || decision.toState() != command.targetState()) {
                throw new MigrationConflictException(
                        ("That idempotency key already recorded a %s decision for %s -> %s on this "
                                + "scope. A replay may only report back the decision it made; use "
                                + "a new key for a new one.")
                                .formatted(decision.decision(), decision.fromState(),
                                        decision.toState()));
            }
            return requireScope(tenantId, scopeId);
        }

        ScopeRow scope = requireScope(tenantId, scopeId);
        ScopeState from = scope.state();
        ScopeState to = Objects.requireNonNull(command.targetState(), "A target state is required");
        requireVersion(scope, command.expectedVersion());
        ScopeStateMachine.require(from, to);

        OwnershipModes modes = modesEntering(scope, to);
        if (!entersTargetOwnership(scope.modes(), modes)) {
            throw wrongEntryPoint(scope, to,
                    ("%s -> %s does not take target ownership, so it is an ordinary transition and "
                            + "belongs in advance. Recording a cutover decision for it would put "
                            + "rows nobody decided in the approval evidence.").formatted(from, to));
        }

        String requestedBy = requireText(command.requestedBy(), "A requester is required");
        String decidedBy = requireText(command.decidedBy(), "An approver is required");
        if (decidedBy.equals(requestedBy)) {
            throw refuse(scope, MigrationPreconditionException.SELF_APPROVAL, actor,
                    "The person who asked to move a capability's writer may not be the person who "
                            + "agreed (ADR 0027)");
        }

        requireReconciliationClear(scope, to, actor);
        requireCoverageDecided(scope, actor);
        Boolean approvalIsPlatform = resolveCitedApproval(scope, command, actor);

        Instant now = clock.instant();
        DecisionRow decision = new DecisionRow(UUID.randomUUID(), tenantId, scopeId, from, to,
                scope.version(), Decision.APPROVED, requireText(command.reason(), "A reason is required"),
                sanitizedEvidence(command.evidenceSnapshot()), requestedBy, decidedBy,
                command.approvalRequestId(), approvalIsPlatform, command.idempotencyKey(),
                command.requestedAt() == null ? now : command.requestedAt(), now);
        decisions.insert(decision);

        ScopeRow moved = apply(scope, to, modes, scope.checkpoint(), command.reason(), actor,
                "migration.scope.cutover", Map.of(
                        "decisionId", decision.id(),
                        "requestedBy", requestedBy,
                        "decidedBy", decidedBy,
                        "writeMode", modes.writeMode().name(),
                        "readMode", modes.readMode().name()),
                command.approvalRequestId());

        log.info("Scope {} moved {} -> {}: {} now owns writes for {}", scopeId, from, to,
                scope.targetOwner(), scope.capability());
        return moved;
    }

    /**
     * Records that a named person declined a proposed cutover.
     *
     * <p>The scope does not move, and the refusal is still evidence. A decision
     * table that held only approvals would make "nobody ever asked" and "somebody
     * said no twice" look identical, and the second is the more interesting one at
     * any review. Refusals are outside {@code ux_cutover_approved_per_version} on
     * purpose: a refusal does not advance the version, and a scope may be refused
     * several times before it is approved once.
     */
    @Transactional
    public DecisionRow refuseCutover(UUID tenantId, UUID scopeId, CutoverCommand command) {
        Objects.requireNonNull(command, "A cutover command is required");
        String actor = access.requireOperator();

        Optional<DecisionRow> replayed = decisions.findByIdempotencyKey(tenantId,
                requireKey(command.idempotencyKey()));
        if (replayed.isPresent()) {
            return replayed.get();
        }

        ScopeRow scope = requireScope(tenantId, scopeId);
        requireVersion(scope, command.expectedVersion());
        String requestedBy = requireText(command.requestedBy(), "A requester is required");
        String decidedBy = requireText(command.decidedBy(), "A decider is required");
        if (decidedBy.equals(requestedBy)) {
            throw refuse(scope, MigrationPreconditionException.SELF_APPROVAL, actor,
                    "The person who asked to move a capability's writer may not be the person who "
                            + "decided (ADR 0027)");
        }

        Boolean approvalIsPlatform = resolveCitedApproval(scope, command, actor);

        Instant now = clock.instant();
        DecisionRow decision = new DecisionRow(UUID.randomUUID(), tenantId, scopeId, scope.state(),
                Objects.requireNonNull(command.targetState(), "A target state is required"),
                scope.version(), Decision.REFUSED,
                requireText(command.reason(), "A reason is required"),
                sanitizedEvidence(command.evidenceSnapshot()), requestedBy, decidedBy,
                command.approvalRequestId(), approvalIsPlatform, command.idempotencyKey(),
                command.requestedAt() == null ? now : command.requestedAt(), now);
        decisions.insert(decision);

        audit.record("migration.scope.cutover-refused", ActorRef.user(actor, null),
                MigrationAudit.scopeOf(scope.tenantId(), scope.brandId(), scope.locationId()),
                "migration.scope", scopeId, scope.version(), command.reason(),
                Map.of("decisionId", decision.id(), "decidedBy", decidedBy,
                        "proposedState", command.targetState().name()),
                command.approvalRequestId());
        return decision;
    }

    /**
     * Publishes how many in-scope sources are still undecided in the coverage
     * register.
     *
     * <p>The count comes from outside this database. ADR 0024's register — every
     * source table classified MIGRATE, TRANSFORM, DROP or DECIDE — lives in the
     * approved mapping document and the discovery tooling's inventory, and the
     * control plane holds no copy of it: a copy would drift, and the version of it
     * that mattered would be whichever one the gate happened to read. So the
     * discovery pass publishes its own answer onto the scope at the end of each
     * pass, and {@link #COVERAGE_UNDECIDED} is where the cutover gate reads it.
     *
     * <p>Publishing a zero is a claim, made by a named operator, recorded with a
     * reason, and audited. That is the point: somebody has to say that every
     * source was decided, and be on record as having said it.
     */
    @Transactional
    public ScopeRow republishCoverage(UUID tenantId, UUID scopeId, int undecidedSources,
            int expectedVersion, String reason) {

        String actor = access.requireOperator();
        if (undecidedSources < 0) {
            throw new IllegalArgumentException("An undecided-source count cannot be negative");
        }
        ScopeRow scope = requireScope(tenantId, scopeId);
        requireVersion(scope, expectedVersion);

        Map<String, Object> checkpoint = mutableCheckpoint(scope);
        Object previous = checkpoint.put(COVERAGE_UNDECIDED, undecidedSources);

        int version = scopes.updateCheckpoint(tenantId, scopeId, checkpoint, expectedVersion,
                        clock.instant())
                .orElseThrow(() -> MigrationConflictException.staleVersion(
                        "scope", expectedVersion, scope.version()));

        audit.record("migration.scope.coverage-published", ActorRef.user(actor, null),
                MigrationAudit.scopeOf(scope.tenantId(), scope.brandId(), scope.locationId()),
                "migration.scope", scopeId, version, reason,
                Map.of("undecidedSources", undecidedSources,
                        "previousUndecidedSources", previous == null ? "unknown" : previous),
                null);

        return new ScopeRow(scope.id(), scope.programId(), scope.tenantId(), scope.brandId(),
                scope.locationId(), scope.capability(), scope.sourceOwner(), scope.targetOwner(),
                scope.modes(), scope.state(), scope.stateEnteredAt(), Map.copyOf(checkpoint), version);
    }

    // ------------------------------------------------------------------ gates

    /** Rule 1, and the results table's own claim that an open critical row blocks every move. */
    private void requireReconciliationClear(ScopeRow scope, ScopeState to, String actor) {
        if (!reconciliation.hasOpenCritical(scope.tenantId(), scope.id())) {
            return;
        }
        String blocking = reconciliation
                .openCriticalResults(scope.tenantId(), scope.id(), REFUSAL_DETAIL).stream()
                .map(MigrationReconciliationStore.BlockingResult::describe)
                .collect(Collectors.joining("; "));
        throw refuse(scope, MigrationPreconditionException.OPEN_CRITICAL_RECONCILIATION, actor,
                ("Scope %s cannot move to %s: unresolved CRITICAL reconciliation differences stand "
                        + "against it (%s). Resolve them, or approve them against their rule's "
                        + "tolerance; there is no override.").formatted(scope.id(), to, blocking));
    }

    /** Rule 2. Unknown blocks as firmly as non-zero. */
    private void requireCoverageDecided(ScopeRow scope, String actor) {
        Object published = scope.checkpoint().get(COVERAGE_UNDECIDED);
        if (published == null) {
            throw refuse(scope, MigrationPreconditionException.UNDECIDED_SOURCES, actor,
                    ("Scope %s has never published its source coverage. \"Not yet counted\" is not "
                            + "\"nothing left to decide\", and the scope cannot become cutover-ready "
                            + "on the difference.").formatted(scope.id()));
        }
        if (!(published instanceof Number count)) {
            throw refuse(scope, MigrationPreconditionException.UNDECIDED_SOURCES, actor,
                    ("Scope %s carries an unreadable undecided-source count (%s), so the coverage "
                            + "gate cannot be evaluated.").formatted(scope.id(), published));
        }
        if (count.longValue() > 0) {
            throw refuse(scope, MigrationPreconditionException.UNDECIDED_SOURCES, actor,
                    ("Scope %s still has %s in-scope source(s) classified DECIDE in the coverage "
                            + "register. Every source is migrated, transformed, dropped or decided "
                            + "before a capability changes hands.").formatted(scope.id(), count));
        }
    }

    /**
     * Retirement revokes access to the source, and an open quarantine item can
     * never be settled afterwards: the legacy row it points at stops being
     * reachable by anyone. The backlog is settled first or the item becomes a
     * permanent record of a row nobody can ever account for.
     */
    private void requireQuarantineSettled(ScopeRow scope, String actor) {
        int open = quarantine.openCount(scope.tenantId(), scope.id());
        if (open > 0) {
            throw refuse(scope, MigrationPreconditionException.OPEN_QUARANTINE, actor,
                    ("Scope %s has %d open quarantine item(s). Retiring revokes access to the "
                            + "source, and an item settled after that is settled without being able "
                            + "to look at the row.").formatted(scope.id(), open));
        }
    }

    // ------------------------------------------------------------- mechanics

    /**
     * The modes the scope will carry in the target state.
     *
     * <p>Derived, never supplied by the caller. A state and its modes are one fact
     * recorded twice, and letting an operator name the modes independently is how
     * a scope ends up claiming {@code DISCOVERY} while the target owns its writes
     * — a row that has drifted from what production is actually doing, discovered
     * only when nobody can say which system accepted the last order.
     *
     * <p>Modes the target state also permits are kept as they are, so a transition
     * that is not about routing does not move routing. Otherwise the target state
     * must permit exactly one pair, which every state on the ordinary path does.
     */
    private OwnershipModes modesEntering(ScopeRow scope, ScopeState to) {
        Set<OwnershipModes> permitted = to.permittedModes();
        if (permitted.contains(scope.modes())) {
            return scope.modes();
        }
        if (permitted.size() == 1) {
            return permitted.iterator().next();
        }
        throw new MigrationPreconditionException(
                MigrationPreconditionException.INCOHERENT_OWNERSHIP_MODES,
                ("Scope %s carries write mode %s and read mode %s, which %s does not permit, and %s "
                        + "permits several pairs so the correct one cannot be derived.")
                        .formatted(scope.id(), scope.modes().writeMode(), scope.modes().readMode(),
                                to, to));
    }

    /**
     * Whether this move is the moment the target becomes the authority.
     *
     * <p>Asked of the modes rather than of the states, so resuming a paused
     * {@code TARGET_OWNED} scope does not read as a fresh cutover — it was already
     * {@code TARGET_ONLY} and kept its modes while it was held, and demanding a
     * second decision to un-pause it would mean a second approver for every
     * operational hiccup.
     */
    private static boolean entersTargetOwnership(OwnershipModes current, OwnershipModes next) {
        return next.writeMode() == WriteMode.TARGET_ONLY
                && current.writeMode() != WriteMode.TARGET_ONLY;
    }

    /**
     * Whether the move goes forward, as opposed to stepping away from trouble.
     *
     * <p>Only forward moves are gated on reconciliation. The escapes — suspending,
     * entering or completing a rollback, withdrawing a cutover approval, reopening
     * a soaking cutover so it can be reversed — must stay available precisely when
     * a critical difference exists, since a difference found mid-canary is the
     * reason to take one of them.
     */
    private static boolean advancing(ScopeState from, ScopeState to) {
        if (to.holding() || to == ScopeState.ROLLING_BACK || from == ScopeState.ROLLING_BACK) {
            return false;
        }
        if (from == ScopeState.CUTOVER_READY && to == ScopeState.CANARY) {
            return false;
        }
        return !(from == ScopeState.ROLLBACK_WINDOW && to == ScopeState.TARGET_OWNED);
    }

    private ScopeRow apply(ScopeRow scope, ScopeState to, OwnershipModes modes,
            Map<String, Object> checkpoint, String reason, String actor, String actionCode,
            Map<String, Object> extraChanges, UUID approvalRequestId) {

        Instant now = clock.instant();
        int version = scopes.transition(scope.tenantId(), scope.id(), scope.state(), to, modes,
                        checkpoint, scope.version(), now)
                .orElseThrow(() -> {
                    // Somebody moved the scope between the read and this statement.
                    // Their move stands, and the loser is told what actually
                    // happened rather than being invited to force theirs on top.
                    ScopeRow settled = requireScope(scope.tenantId(), scope.id());
                    log.info("Transition of scope {} to {} lost the race; it is {} at version {}",
                            scope.id(), to, settled.state(), settled.version());
                    return MigrationConflictException.staleVersion("scope", scope.version(),
                            settled.version());
                });

        Map<String, Object> changes = new LinkedHashMap<>(extraChanges);
        changes.put("fromState", scope.state().name());
        changes.put("toState", to.name());
        changes.put("capability", scope.capability().name());
        changes.put("targetOwner", scope.targetOwner());
        audit.record(actionCode, ActorRef.user(actor, null),
                MigrationAudit.scopeOf(scope.tenantId(), scope.brandId(), scope.locationId()),
                "migration.scope", scope.id(), version, reason, changes, approvalRequestId);

        return new ScopeRow(scope.id(), scope.programId(), scope.tenantId(), scope.brandId(),
                scope.locationId(), scope.capability(), scope.sourceOwner(), scope.targetOwner(),
                modes, to, now, Map.copyOf(checkpoint), version);
    }

    private ScopeState recordedResumeState(ScopeRow scope, String actor) {
        Object recorded = scope.checkpoint().get(RESUME_STATE);
        if (recorded == null) {
            throw refuse(scope, MigrationPreconditionException.RESUME_STATE_UNKNOWN, actor,
                    ("Scope %s is %s but carries no record of the state it left, so there is nowhere "
                            + "to resume it to. Anything chosen here could promote it.")
                            .formatted(scope.id(), scope.state()));
        }
        try {
            return ScopeState.valueOf(recorded.toString());
        } catch (IllegalArgumentException unreadable) {
            throw refuse(scope, MigrationPreconditionException.RESUME_STATE_UNKNOWN, actor,
                    "Scope %s records \"%s\" as the state it left, which is not a scope state"
                            .formatted(scope.id(), recorded));
        }
    }

    /**
     * Records the refused attempt, then produces the failure.
     *
     * <p>The audit is written in its own transaction, because this one is about to
     * roll back. An operator reaching for {@code CUTOVER_READY} past an open
     * critical difference is exactly what ADR 0024 exists to make visible, and it
     * would be visible nowhere if the record died with the attempt.
     */
    /**
     * Resolves the approval request a cutover command cites, in the scope's own
     * tenant, and refuses anything else.
     *
     * <p>This is the fifth refusal, and it belongs beside the other four. {@code
     * audit.approval_requests.tenant_id} is nullable because a PLATFORM-scope
     * approval belongs to no tenant, so the id a caller submits does not say whose
     * request it is; before this, {@code cutOver} wrote whatever id it was given
     * and the only check was that a row with that id existed <em>somewhere on the
     * platform</em>. An operator could therefore cite another tenant's approval
     * request as the authorisation for moving their own tenant's writer, and
     * {@code migration.cutover_decisions} — append-only precisely so it can be
     * trusted at a review — would record it as signed for.
     *
     * <p>The two answers a tenant may have are the platform's request and its own,
     * which is what {@link ApprovalRequestOwnership} returns and what V0088's
     * {@code fk_cutover_approval_request} accepts. Absent means refuse, and the
     * message deliberately does not distinguish "no such request" from "another
     * tenant's": a distinguishable refusal turns this endpoint into an existence
     * oracle for approval request ids across the platform, which is the second
     * half of the defect V0069 named.
     *
     * @return whether the cited request is the platform's, or null when none is
     *         cited — a transition policy does not gate discharges no request
     */
    private Boolean resolveCitedApproval(ScopeRow scope, CutoverCommand command, String actor) {
        UUID approvalRequestId = command.approvalRequestId();
        if (approvalRequestId == null) {
            return null;
        }
        return approvals.resolve(approvalRequestId, scope.tenantId())
                .map(owner -> owner == ApprovalRequestOwnership.Owner.PLATFORM)
                .orElseThrow(() -> refuse(scope,
                        MigrationPreconditionException.APPROVAL_NOT_CITABLE, actor,
                        "The cited approval request is not one this tenant may cite. A cutover "
                                + "decision may discharge a PLATFORM-scope approval or one of its "
                                + "own tenant's, and nothing else (ADR 0027)"));
    }

    private MigrationPreconditionException refuse(ScopeRow scope, String reasonCode, String actor,
            String message) {

        audit.recordRefusal("migration.scope.transition-refused", ActorRef.user(actor, null),
                MigrationAudit.scopeOf(scope.tenantId(), scope.brandId(), scope.locationId()),
                "migration.scope", scope.id(), scope.version(), reasonCode,
                Map.of("state", scope.state().name(), "capability", scope.capability().name(),
                        "refusal", reasonCode));
        log.warn("Refused a transition of scope {} in {}: {}", scope.id(), scope.state(), message);
        return new MigrationPreconditionException(reasonCode, message);
    }

    private static MigrationPreconditionException wrongEntryPoint(ScopeRow scope, ScopeState to,
            String message) {
        return new MigrationPreconditionException(MigrationPreconditionException.WRONG_ENTRY_POINT,
                "%s -> %s on scope %s: %s".formatted(scope.state(), to, scope.id(), message));
    }

    private ScopeRow requireScope(UUID tenantId, UUID scopeId) {
        return scopes.findById(tenantId, scopeId)
                .orElseThrow(() -> new MigrationResourceNotFoundException(
                        "No migration scope %s for this tenant".formatted(scopeId)));
    }

    private static void requireVersion(ScopeRow scope, int expectedVersion) {
        if (scope.version() != expectedVersion) {
            throw MigrationConflictException.staleVersion("scope", expectedVersion, scope.version());
        }
    }

    private static Map<String, Object> mutableCheckpoint(ScopeRow scope) {
        return scope.checkpoint() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(scope.checkpoint());
    }

    /**
     * The evidence snapshot, checked for being aggregates and references.
     *
     * <p>Nested structures are refused. The snapshot is watermarks, counts,
     * checksums and the reconciliation run ids that cleared; the moment it accepts
     * a nested document it becomes the place a diagnosing engineer pastes the
     * sample rows, and the control plane acquires an unclassified copy of source
     * data that ADR 0029 has no record of.
     */
    private static Map<String, Object> sanitizedEvidence(Map<String, Object> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            throw new IllegalArgumentException(
                    "A cutover decision records the figures it rested on; an empty snapshot is a "
                            + "signature on nothing");
        }
        List<String> nested = evidence.entrySet().stream()
                .filter(entry -> entry.getValue() instanceof Map || entry.getValue() instanceof Iterable)
                .map(Map.Entry::getKey)
                .toList();
        if (!nested.isEmpty()) {
            throw new MigrationPreconditionException(
                    MigrationPreconditionException.EVIDENCE_NOT_A_REFERENCE,
                    ("Evidence entries %s are nested documents. A snapshot carries totals and "
                            + "references, never rows (ADR 0029).").formatted(nested));
        }
        return Map.copyOf(evidence);
    }

    private static String requireKey(String idempotencyKey) {
        return requireText(idempotencyKey, "An idempotency key is required (ADR 0031)");
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    /** @param expectedVersion the scope version the operator was looking at (ADR 0031) */
    public record AdvanceCommand(ScopeState targetState, int expectedVersion, String reason,
            String idempotencyKey) { }

    /** @param holdingState {@code PAUSED} for a decision, {@code BLOCKED_RECONCILIATION} for evidence */
    public record SuspendCommand(ScopeState holdingState, int expectedVersion, String reason,
            String idempotencyKey) { }

    /** Carries no destination: the destination is the state the suspension recorded. */
    public record ResumeCommand(int expectedVersion, String reason, String idempotencyKey) { }

    /**
     * @param evidenceSnapshot the aggregate figures the decision rests on:
     *                         watermarks, counts, checksums, the reconciliation
     *                         runs that cleared, the observed soak window
     * @param requestedBy      who asked for the window
     * @param decidedBy        who agreed to it, and never the same person
     * @param approvalRequestId the ADR 0027 maker-checker request this discharges,
     *                          where policy required one
     * @param requestedAt      when the window was asked for, which is not when it
     *                          was granted; null means both happened now
     */
    public record CutoverCommand(
            ScopeState targetState,
            int expectedVersion,
            String reason,
            Map<String, Object> evidenceSnapshot,
            String requestedBy,
            String decidedBy,
            UUID approvalRequestId,
            Instant requestedAt,
            String idempotencyKey) { }
}
