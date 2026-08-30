package uz.horecaos.platform.migration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import uz.horecaos.platform.migration.api.MigrationCapability;
import uz.horecaos.platform.migration.application.MigrationCutoverDecisionStore.Decision;
import uz.horecaos.platform.migration.application.MigrationCutoverDecisionStore.DecisionRow;
import uz.horecaos.platform.migration.application.MigrationScopeService.AdvanceCommand;
import uz.horecaos.platform.migration.application.MigrationScopeService.CutoverCommand;
import uz.horecaos.platform.migration.application.MigrationScopeService.ResumeCommand;
import uz.horecaos.platform.migration.application.MigrationScopeService.RollbackCommand;
import uz.horecaos.platform.migration.application.MigrationScopeService.SuspendCommand;
import uz.horecaos.platform.migration.application.MigrationScopeStore.ScopeRow;
import uz.horecaos.platform.migration.domain.ReconciliationSeverity;
import uz.horecaos.platform.migration.domain.RunType;
import uz.horecaos.platform.migration.domain.ScopeState;
import uz.horecaos.platform.migration.domain.WriteMode;

/**
 * The transition engine's four refusals, against the real schema (ADR 0024).
 *
 * <p>{@code ScopeStateMachine} answers whether a move exists;
 * {@code MigrationScopeService} answers whether it is allowed now. Everything
 * here is the second question, because it is the one whose answer depends on
 * rows written long after the transition table was compiled — and it is where
 * ADR 0024's prohibition on a platform-admin UI skipping reconciliation or
 * inventing a target owner actually lives.
 */
class MigrationCutoverGateTests extends MigrationControlPlaneFixture {

    private static final String REQUESTER = "release-manager";
    private static final String APPROVER = "head-of-engineering";

    // ============================================ 4. reconciliation blocks the gate

    /**
     * Guarantee 4. An unresolved CRITICAL difference blocks the move, and settling
     * it unblocks — the second half matters as much as the first, because a gate
     * that never opened would pass the first half on its own.
     */
    @Test
    @DisplayName("an open CRITICAL reconciliation result blocks CUTOVER_READY, and resolving it unblocks")
    void anUnresolvedCriticalDifferenceBlocksTheCutoverGate() {
        UUID scopeId = scopeReadyForCanary();
        UUID reconRun = startRun(scopeId, RunType.RECONCILIATION, "recon-1");
        UUID difference = recordDifference(scopeId, reconRun, "ORDER_TOTALS", ReconciliationSeverity.CRITICAL);

        Throwable blocked = catchThrowable(() -> advance(scopeId, ScopeState.CUTOVER_READY));

        assertThat(blocked).isInstanceOf(MigrationPreconditionException.class);
        assertThat(((MigrationPreconditionException) blocked).reasonCode())
                .isEqualTo(MigrationPreconditionException.OPEN_CRITICAL_RECONCILIATION);
        assertThat(blocked.getMessage())
                .as("an operator told only that reconciliation is outstanding goes to the "
                        + "results table to guess, and a guess is a dashboard summary")
                .contains("ORDER_TOTALS v3", "there is no override");
        assertThat(scope(scopeId).state()).as("the scope did not move").isEqualTo(ScopeState.CANARY);

        // The refusal is itself a fact. An operator reaching past an open critical
        // difference is exactly what ADR 0024 exists to make visible.
        assertThat(countRows(
                        "audit.audit_events",
                        "action_code = 'migration.scope.transition-refused' AND outcome = 'REJECTED'",
                        Map.of()))
                .isEqualTo(1);

        // Corrected rather than accepted.
        assertThat(reconciliationStore.resolve(TENANT, difference, clock.instant()))
                .isTrue();

        ScopeRow moved = advance(scopeId, ScopeState.CUTOVER_READY);
        assertThat(moved.state()).isEqualTo(ScopeState.CUTOVER_READY);
    }

    /**
     * ADR 0024 defines both zero-tolerance and approved-tolerance rules. An
     * accepted difference that still blocked would leave operators with no way
     * forward except editing the evidence.
     */
    @Test
    @DisplayName("an approved CRITICAL clears the gate, and a WARNING never blocked it")
    void approvalClearsTheGateAndOnlyCriticalEverBlocks() {
        UUID scopeId = scopeReadyForCanary();
        UUID reconRun = startRun(scopeId, RunType.RECONCILIATION, "recon-1");

        recordDifference(scopeId, reconRun, "ROUNDING_DRIFT", ReconciliationSeverity.WARNING);
        recordDifference(scopeId, reconRun, "NOTE_TRUNCATION", ReconciliationSeverity.INFO);
        assertThat(advance(scopeId, ScopeState.CUTOVER_READY).state())
                .as("a warning is a finding, not a fence")
                .isEqualTo(ScopeState.CUTOVER_READY);

        // Withdraw the approval so the scope is back where a critical can catch it.
        advance(scopeId, ScopeState.CANARY);
        UUID critical = recordDifference(scopeId, reconRun, "ORDER_TOTALS", ReconciliationSeverity.CRITICAL);
        assertThat(catchThrowable(() -> advance(scopeId, ScopeState.CUTOVER_READY)))
                .isInstanceOf(MigrationPreconditionException.class);

        assertThat(reconciliationStore.approve(TENANT, critical, APPROVER, clock.instant()))
                .isTrue();
        assertThat(advance(scopeId, ScopeState.CUTOVER_READY).state())
                .as("\"we agreed to live with it\" is an answer, and a different one from " + "\"we corrected it\"")
                .isEqualTo(ScopeState.CUTOVER_READY);
    }

    /**
     * The escapes stay open precisely when a critical difference exists, because a
     * difference found mid-canary is the reason to take one of them.
     */
    @Test
    @DisplayName("a blocked scope can still step away from trouble")
    void aBlockingDifferenceDoesNotTrapTheScope() {
        UUID blocked = scopeReadyForCanary();
        recordDifference(
                blocked,
                startRun(blocked, RunType.RECONCILIATION, "recon-1"),
                "ORDER_TOTALS",
                ReconciliationSeverity.CRITICAL);

        // Suspending is available: a difference found mid-canary is the reason to
        // stop, so the gate must not be what stops the stopping.
        scopeService.suspend(
                TENANT,
                blocked,
                new SuspendCommand(
                        ScopeState.BLOCKED_RECONCILIATION, scopeVersion(blocked), "the evidence forced it", "block-1"));
        assertThat(scope(blocked).state()).isEqualTo(ScopeState.BLOCKED_RECONCILIATION);

        // And so is rolling back, on a second scope in the same position.
        UUID reversing = openScope(MigrationCapability.CATALOG, null, null);
        advanceThrough(
                reversing,
                ScopeState.MAPPING_APPROVED,
                ScopeState.BACKFILLING,
                ScopeState.CATCHING_UP,
                ScopeState.SHADOW_READING,
                ScopeState.CANARY);
        recordDifference(
                reversing,
                startRun(reversing, RunType.RECONCILIATION, "recon-2"),
                "ORDER_TOTALS",
                ReconciliationSeverity.CRITICAL);

        // Through rollBack, which the approver capability guards: taking a writer
        // back is the same decision as giving it. The gate is still not consulted,
        // which is the point of this assertion — a critical difference is the
        // reason to reverse, so it must not also be what prevents reversing.
        assertThat(scopeService
                        .rollBack(
                                TENANT,
                                reversing,
                                new RollbackCommand(
                                        scopeVersion(reversing),
                                        "the totals did not reconcile",
                                        UUID.randomUUID().toString()))
                        .state())
                .isEqualTo(ScopeState.ROLLING_BACK);
        assertThat(advance(reversing, ScopeState.CATCHING_UP).state())
                .as("a rollback lands where the world it leaves behind actually is, and "
                        + "completing it is not an advance the gate may refuse")
                .isEqualTo(ScopeState.CATCHING_UP);
    }

    /**
     * The gates are additional to the transition table, never a substitute for it.
     * A move the machine does not have is refused before any gate is consulted.
     */
    @Test
    @DisplayName("no gate can be satisfied into a transition the machine does not have")
    void theGatesDoNotReplaceTheTransitionTable() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);
        scopeService.republishCoverage(TENANT, scopeId, 0, scopeVersion(scopeId), "all decided");

        assertThat(catchThrowable(() -> advance(scopeId, ScopeState.TARGET_OWNED)))
                .isInstanceOf(uz.horecaos.platform.migration.domain.ScopeStateMachine.IllegalTransitionException.class);
        assertThat(catchThrowable(() -> advance(scopeId, ScopeState.CUTOVER_READY)))
                .isInstanceOf(uz.horecaos.platform.migration.domain.ScopeStateMachine.IllegalTransitionException.class);
        assertThat(catchThrowable(
                        () -> cutOver(scopeId, REQUESTER, APPROVER, Map.of("watermark", "legacy:1"), "cutover-1")))
                .as("cutOver is the only path to TARGET_ONLY, not a way around the table")
                .isInstanceOf(uz.horecaos.platform.migration.domain.ScopeStateMachine.IllegalTransitionException.class);

        assertThat(scope(scopeId).state()).isEqualTo(ScopeState.DISCOVERY);
        assertThat(countRows("migration.cutover_decisions", "scope_id = :scopeId", Map.of("scopeId", scopeId)))
                .isZero();
    }

    /**
     * The second gate, and the reason absence is not defaulted to zero: a scope
     * whose coverage has never been published has not proved that every source was
     * decided, it has only failed to say.
     */
    @Test
    @DisplayName("a scope that never published its source coverage cannot become cutover-ready")
    void unknownCoverageBlocksAsFirmlyAsOutstandingCoverage() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);
        advanceThrough(
                scopeId,
                ScopeState.MAPPING_APPROVED,
                ScopeState.BACKFILLING,
                ScopeState.CATCHING_UP,
                ScopeState.SHADOW_READING,
                ScopeState.CANARY);

        Throwable neverCounted = catchThrowable(() -> advance(scopeId, ScopeState.CUTOVER_READY));
        assertThat(((MigrationPreconditionException) neverCounted).reasonCode())
                .isEqualTo(MigrationPreconditionException.UNDECIDED_SOURCES);
        assertThat(neverCounted.getMessage()).contains("\"Not yet counted\" is not \"nothing left to decide\"");

        // Publishing a non-zero count is not a pass either.
        scopeService.republishCoverage(TENANT, scopeId, 3, scopeVersion(scopeId), "three left");
        assertThat(catchThrowable(() -> advance(scopeId, ScopeState.CUTOVER_READY)))
                .isInstanceOf(MigrationPreconditionException.class)
                .hasMessageContaining("classified DECIDE");

        // Publishing a zero is a claim a named operator made and is on record for.
        scopeService.republishCoverage(TENANT, scopeId, 0, scopeVersion(scopeId), "all decided");
        assertThat(advance(scopeId, ScopeState.CUTOVER_READY).state()).isEqualTo(ScopeState.CUTOVER_READY);
        assertThat(countRows("audit.audit_events", "action_code = 'migration.scope.coverage-published'", Map.of()))
                .isEqualTo(2);
    }

    // ================================== 5. no ownership without an approved decision

    /**
     * Guarantee 5. There is one path to {@code TARGET_ONLY} and it appends the
     * decision before the modes change, in the same transaction. {@code advance}
     * refuses the move outright rather than checking for an existing decision,
     * which keeps one path instead of two that must agree.
     */
    @Test
    @DisplayName("reaching TARGET_ONLY through advance is refused, and records nothing")
    void ownershipCannotTransferWithoutAnApprovedDecision() {
        UUID scopeId = cutoverReadyScope();

        Throwable wrongDoor = catchThrowable(() -> advance(scopeId, ScopeState.TARGET_OWNED));

        assertThat(((MigrationPreconditionException) wrongDoor).reasonCode())
                .isEqualTo(MigrationPreconditionException.WRONG_ENTRY_POINT);
        assertThat(wrongDoor.getMessage())
                .contains("records the approver and the evidence before the write mode moves");
        assertThat(scope(scopeId).state()).isEqualTo(ScopeState.CUTOVER_READY);
        assertThat(scope(scopeId).modes().writeMode()).isEqualTo(WriteMode.LEGACY_WITH_TARGET_SHADOW);
        assertThat(countRows("migration.cutover_decisions", "scope_id = :scopeId", Map.of("scopeId", scopeId)))
                .isZero();
        assertThat(ownership
                        .ownershipOf(TENANT, MigrationCapability.ORDERS, null, null)
                        .targetMayWrite())
                .isFalse();
    }

    @Test
    @DisplayName("a cutover names two different people, and rests on figures rather than on rows")
    void theDecisionCarriesItsEvidenceAndItsFourEyes() {
        UUID scopeId = cutoverReadyScope();

        Throwable alone = catchThrowable(() ->
                cutOver(scopeId, REQUESTER, REQUESTER, Map.of("finalSourceWatermark", "legacy:9000"), "cutover-1"));
        assertThat(((MigrationPreconditionException) alone).reasonCode())
                .isEqualTo(MigrationPreconditionException.SELF_APPROVAL);

        assertThat(catchThrowable(() -> cutOver(scopeId, REQUESTER, APPROVER, Map.of(), "cutover-2")))
                .as("an empty snapshot is a signature on nothing")
                .isInstanceOf(IllegalArgumentException.class);

        Throwable nested = catchThrowable(() -> cutOver(
                scopeId,
                REQUESTER,
                APPROVER,
                Map.of("sampleRows", java.util.List.of("order 1", "order 2")),
                "cutover-3"));
        assertThat(((MigrationPreconditionException) nested).reasonCode())
                .as("the moment the snapshot accepts a nested document it becomes the place "
                        + "sample rows are pasted (ADR 0029)")
                .isEqualTo(MigrationPreconditionException.EVIDENCE_NOT_A_REFERENCE);

        assertThat(countRows("migration.cutover_decisions", "scope_id = :scopeId", Map.of("scopeId", scopeId)))
                .isZero();
        assertThat(scope(scopeId).state()).isEqualTo(ScopeState.CUTOVER_READY);
    }

    @Test
    @DisplayName("an approved cutover moves the writer and leaves the evidence that moved it")
    void anApprovedCutoverTransfersOwnership() {
        UUID scopeId = cutoverReadyScope();
        int approvedAgainst = scopeVersion(scopeId);

        ScopeRow owned = cutOver(
                scopeId,
                REQUESTER,
                APPROVER,
                Map.of("finalSourceWatermark", "legacy:9000", "reconciledOrders", 41_233, "checksum", "a".repeat(64)),
                "cutover-1");

        assertThat(owned.state()).isEqualTo(ScopeState.TARGET_OWNED);
        assertThat(owned.modes().writeMode()).isEqualTo(WriteMode.TARGET_ONLY);
        assertThat(ownership
                        .ownershipOf(TENANT, MigrationCapability.ORDERS, null, null)
                        .targetMayWrite())
                .isTrue();

        DecisionRow decision =
                decisionStore.findApproved(TENANT, scopeId, approvedAgainst).orElseThrow();
        assertThat(decision.decision()).isEqualTo(Decision.APPROVED);
        assertThat(decision.requestedBy()).isEqualTo(REQUESTER);
        assertThat(decision.decidedBy()).isEqualTo(APPROVER);
        assertThat(decision.fromState()).isEqualTo(ScopeState.CUTOVER_READY);
        assertThat(decision.toState()).isEqualTo(ScopeState.TARGET_OWNED);
        assertThat(decision.scopeVersion())
                .as("the version says which revision of the scope the approver was looking at")
                .isEqualTo(approvedAgainst);

        // ADR 0031: a retried approval reports the scope as the first attempt left
        // it rather than moving one that has already moved on.
        ScopeRow replayed =
                cutOver(scopeId, REQUESTER, APPROVER, Map.of("finalSourceWatermark", "legacy:9000"), "cutover-1");
        assertThat(replayed.version()).isEqualTo(owned.version());
        assertThat(countRows("migration.cutover_decisions", "scope_id = :scopeId", Map.of("scopeId", scopeId)))
                .isEqualTo(1);
    }

    /**
     * Two approvers racing on one scope version: one insert succeeds and the other
     * fails, so the loser is told the scope moved rather than being allowed to
     * believe they moved it.
     */
    @Test
    @DisplayName("a second approval against one scope version is unrepresentable")
    void onlyOneApprovalPerScopeVersion() {
        UUID scopeId = cutoverReadyScope();
        int version = scopeVersion(scopeId);
        cutOver(scopeId, REQUESTER, APPROVER, Map.of("watermark", "legacy:9000"), "cutover-1");

        assertThat(catchThrowable(() -> jdbc.sql("""
                INSERT INTO migration.cutover_decisions (id, tenant_id, scope_id, from_state,
                    to_state, scope_version, decision, reason, evidence_snapshot, requested_by,
                    decided_by, idempotency_key, requested_at)
                VALUES (:id, :tenantId, :scopeId, 'CUTOVER_READY', 'TARGET_OWNED', :version,
                    'APPROVED', 'racing', '{}'::jsonb, 'someone-else', 'another-approver',
                    'cutover-racer', now())
                """)
                        .param("id", UUID.randomUUID())
                        .param("tenantId", TENANT)
                        .param("scopeId", scopeId)
                        .param("version", version)
                        .update()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_cutover_approved_per_version");
    }

    /**
     * A refusal is as much a fact as an approval. A decision table holding only
     * approvals would make "nobody ever asked" and "somebody said no twice" look
     * identical, and the second is the more interesting one at any review.
     */
    @Test
    @DisplayName("a refused cutover is recorded and the scope does not move")
    void aRefusalIsEvidenceToo() {
        UUID scopeId = cutoverReadyScope();

        DecisionRow refusal = scopeService.refuseCutover(
                TENANT,
                scopeId,
                new CutoverCommand(
                        ScopeState.TARGET_OWNED,
                        scopeVersion(scopeId),
                        "the canary error rate doubled",
                        Map.of("canaryErrorRate", "0.031"),
                        REQUESTER,
                        APPROVER,
                        null,
                        null,
                        "refusal-1"));

        assertThat(refusal.decision()).isEqualTo(Decision.REFUSED);
        assertThat(refusal.decidedBy()).isEqualTo(APPROVER);
        assertThat(scope(scopeId).state()).isEqualTo(ScopeState.CUTOVER_READY);

        // Refusals sit outside the approved-per-version index on purpose: a scope
        // may be refused several times before it is approved once.
        scopeService.refuseCutover(
                TENANT,
                scopeId,
                new CutoverCommand(
                        ScopeState.TARGET_OWNED,
                        scopeVersion(scopeId),
                        "still not ready",
                        Map.of("canaryErrorRate", "0.028"),
                        REQUESTER,
                        APPROVER,
                        null,
                        null,
                        "refusal-2"));
        assertThat(countRows(
                        "migration.cutover_decisions",
                        "scope_id = :scopeId AND decision = 'REFUSED'",
                        Map.of("scopeId", scopeId)))
                .isEqualTo(2);
    }

    /**
     * A record that can be edited afterwards is worth nothing at the review where
     * it matters, so the application role holds INSERT and SELECT and nothing
     * else. Asserted as the role rather than as the superuser this suite otherwise
     * connects as, because the grant is the enforcement.
     */
    @Test
    @DisplayName("the application role cannot edit or delete a cutover decision")
    void cutoverEvidenceIsAppendOnlyForTheApplication() throws SQLException {
        UUID scopeId = cutoverReadyScope();
        cutOver(scopeId, REQUESTER, APPROVER, Map.of("watermark", "legacy:9000"), "cutover-1");

        try (Connection connection = DriverManager.getConnection(jdbcUrl(), username(), password());
                Statement statement = connection.createStatement()) {

            statement.execute("SET ROLE horecaos_application");

            assertThat(catchThrowable(() -> statement.executeUpdate(
                            "UPDATE migration.cutover_decisions SET reason = 'a better story'")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");

            assertThat(catchThrowable(() -> statement.executeUpdate("DELETE FROM migration.cutover_decisions")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");

            // The premise: the role can read the evidence and append to it, so the
            // two refusals above are about the verbs and not about the table.
            assertThat(statement
                            .executeQuery("SELECT count(*) FROM migration.cutover_decisions")
                            .next())
                    .isTrue();
        }
    }

    // ================================================ 7. a pause is not a promotion

    /**
     * Guarantee 7. The destination is read from the checkpoint the suspension
     * wrote, not chosen by the caller and not guessed by the machine.
     */
    @Test
    @DisplayName("a paused scope returns to the state it left")
    void resumeReturnsAScopeToWhereItCameFrom() {
        UUID scopeId = scopeReadyForCanary();

        ScopeRow paused = scopeService.suspend(
                TENANT,
                scopeId,
                new SuspendCommand(ScopeState.PAUSED, scopeVersion(scopeId), "an operator stopped it", "pause-1"));

        assertThat(paused.state()).isEqualTo(ScopeState.PAUSED);
        assertThat(paused.checkpoint()).containsEntry(MigrationScopeService.RESUME_STATE, "CANARY");
        assertThat(paused.modes())
                .as("a paused scope keeps the routing it had, or the pause would itself be a move")
                .isEqualTo(scope(scopeId).modes());

        // The move an operator would reach for, and the reason PAUSED has no
        // outgoing edges: pause a canary, resume it target-owned.
        Throwable promotion = catchThrowable(() -> advance(scopeId, ScopeState.TARGET_OWNED));
        assertThat(((MigrationPreconditionException) promotion).reasonCode())
                .isEqualTo(MigrationPreconditionException.WRONG_ENTRY_POINT);
        assertThat(promotion.getMessage()).contains("a pause become a promotion");

        ScopeRow resumed = scopeService.resume(
                TENANT, scopeId, new ResumeCommand(scopeVersion(scopeId), "the incident closed", "resume-1"));

        assertThat(resumed.state()).isEqualTo(ScopeState.CANARY);
        assertThat(resumed.checkpoint())
                .as("the marker is removed, or the second suspension would read the first " + "one's answer")
                .doesNotContainKey(MigrationScopeService.RESUME_STATE);
    }

    /**
     * The single failure this module exists to make impossible: a pause that
     * quietly hands a capability back to legacy.
     */
    @Test
    @DisplayName("pausing a target-owned scope does not hand the capability back to legacy")
    void aPauseDoesNotUndoACutover() {
        UUID scopeId = cutoverReadyScope();
        cutOver(scopeId, REQUESTER, APPROVER, Map.of("watermark", "legacy:9000"), "cutover-1");

        scopeService.suspend(
                TENANT,
                scopeId,
                new SuspendCommand(ScopeState.PAUSED, scopeVersion(scopeId), "an incident", "pause-1"));

        assertThat(scope(scopeId).modes().writeMode())
                .as("the stored routing survives, which is what makes the pause reversible")
                .isEqualTo(WriteMode.TARGET_ONLY);
        var held = ownership.ownershipOf(TENANT, MigrationCapability.ORDERS, null, null);
        assertThat(held.targetMayWrite()).isFalse();
        assertThat(held.legacyMayWrite())
                .as("legacy was fenced at cutover, so nobody writes until an operator resolves it")
                .isFalse();

        ScopeRow resumed = scopeService.resume(
                TENANT, scopeId, new ResumeCommand(scopeVersion(scopeId), "the incident closed", "resume-1"));

        assertThat(resumed.state()).isEqualTo(ScopeState.TARGET_OWNED);
        assertThat(ownership
                        .ownershipOf(TENANT, MigrationCapability.ORDERS, null, null)
                        .targetMayWrite())
                .isTrue();
        assertThat(countRows("migration.cutover_decisions", "scope_id = :scopeId", Map.of("scopeId", scopeId)))
                .as("un-pausing is not a fresh cutover, and demanding a second approver for "
                        + "every operational hiccup would make the first one ceremonial")
                .isEqualTo(1);
    }

    /**
     * The plausible answer for a scope whose last observed activity was a canary
     * is {@code CANARY}, and being wrong about that once is a capability whose
     * writer nobody can name.
     */
    @Test
    @DisplayName("a held scope with no recorded return state is refused rather than sent somewhere plausible")
    void aResumeNeverGuesses() {
        UUID scopeId = scopeReadyForCanary();
        scopeService.suspend(
                TENANT,
                scopeId,
                new SuspendCommand(ScopeState.PAUSED, scopeVersion(scopeId), "an operator stopped it", "pause-1"));

        jdbc.sql("UPDATE migration.scopes SET checkpoint = '{}'::jsonb WHERE id = :id")
                .param("id", scopeId)
                .update();

        Throwable nowhere = catchThrowable(() ->
                scopeService.resume(TENANT, scopeId, new ResumeCommand(scopeVersion(scopeId), "resuming", "resume-1")));
        assertThat(((MigrationPreconditionException) nowhere).reasonCode())
                .isEqualTo(MigrationPreconditionException.RESUME_STATE_UNKNOWN);
        assertThat(nowhere.getMessage()).contains("Anything chosen here could promote it");

        // A value that is not a scope state is the same refusal, not a fallback.
        jdbc.sql("""
                UPDATE migration.scopes SET checkpoint = '{"resumeState": "CANRY"}'::jsonb
                WHERE id = :id
                """).param("id", scopeId).update();
        assertThat(catchThrowable(() -> scopeService.resume(
                        TENANT, scopeId, new ResumeCommand(scopeVersion(scopeId), "resuming", "resume-2"))))
                .isInstanceOf(MigrationPreconditionException.class)
                .hasMessageContaining("which is not a scope state");

        assertThat(scope(scopeId).state()).isEqualTo(ScopeState.PAUSED);
    }

    /**
     * A reconciliation block is not a pause somebody may simply lift. It was
     * entered because evidence forced it, and resuming while that evidence stands
     * would return the scope to a path it is not entitled to be on.
     */
    @Test
    @DisplayName("resuming a reconciliation block requires the evidence to be settled first")
    void aBlockedScopeResumesOnlyOnceTheEvidenceIsSettled() {
        UUID scopeId = scopeReadyForCanary();
        UUID reconRun = startRun(scopeId, RunType.RECONCILIATION, "recon-1");

        scopeService.suspend(
                TENANT,
                scopeId,
                new SuspendCommand(
                        ScopeState.BLOCKED_RECONCILIATION, scopeVersion(scopeId), "evidence forced it", "block-1"));
        UUID difference = recordDifference(scopeId, reconRun, "ORDER_TOTALS", ReconciliationSeverity.CRITICAL);

        Throwable stillBlocked = catchThrowable(() -> scopeService.resume(
                TENANT, scopeId, new ResumeCommand(scopeVersion(scopeId), "let it run", "resume-1")));
        assertThat(((MigrationPreconditionException) stillBlocked).reasonCode())
                .isEqualTo(MigrationPreconditionException.OPEN_CRITICAL_RECONCILIATION);

        assertThat(reconciliationStore.resolve(TENANT, difference, clock.instant()))
                .isTrue();
        assertThat(scopeService
                        .resume(TENANT, scopeId, new ResumeCommand(scopeVersion(scopeId), "settled", "resume-2"))
                        .state())
                .isEqualTo(ScopeState.CANARY);
    }

    /**
     * Retirement revokes access to the source, so an item settled afterwards is
     * settled without anyone being able to look at the row it points at.
     */
    @Test
    @DisplayName("an open quarantine backlog blocks retirement")
    void retirementWaitsForTheQuarantineBacklog() {
        UUID scopeId = cutoverReadyScope();
        UUID backfill = runIdOf(scopeId);
        var item = quarantineService.quarantine(
                TENANT,
                backfill,
                new QuarantineService.QuarantineCommand("ORDER", "delever-77", "TENANT_NOT_PROVABLE", null));

        cutOver(scopeId, REQUESTER, APPROVER, Map.of("watermark", "legacy:9000"), "cutover-1");
        advanceThrough(scopeId, ScopeState.ROLLBACK_WINDOW, ScopeState.LEGACY_READ_ONLY);

        Throwable withBacklog = catchThrowable(() -> advance(scopeId, ScopeState.RETIRED));
        assertThat(((MigrationPreconditionException) withBacklog).reasonCode())
                .isEqualTo(MigrationPreconditionException.OPEN_QUARANTINE);

        quarantineService.resolve(
                TENANT,
                item.id(),
                new QuarantineService.ResolveCommand(
                        "NOT_MIGRATABLE", "the source row has no tenant and never had one"));

        assertThat(advance(scopeId, ScopeState.RETIRED).state()).isEqualTo(ScopeState.RETIRED);
    }

    // ================================== 5. the approval a cutover is allowed to cite

    /**
     * The fifth refusal. {@code audit.approval_requests.tenant_id} is nullable
     * because a PLATFORM-scope approval belongs to no tenant, so until V0088 the
     * only check on {@code approvalRequestId} was that a row with that id existed
     * <em>somewhere on the platform</em> — and an operator could hand this endpoint
     * another tenant's approval request and have the append-only decision table
     * record it as the authorisation for moving their own tenant's writer.
     *
     * <p>The refusal deliberately reads the same as the one for an id that names
     * nothing: a distinguishable answer would make this endpoint an existence
     * oracle for approval request ids across the whole platform.
     */
    @Test
    @DisplayName("a cutover cannot be authorised by another tenant's approval request")
    void aCutoverCannotCiteAnotherTenantsApproval() {
        UUID scopeId = cutoverReadyScope();
        UUID foreignApproval = approvalRequestFor(OTHER_TENANT);

        Throwable refused = catchThrowable(() -> cutOverCiting(scopeId, foreignApproval));
        assertThat(((MigrationPreconditionException) refused).reasonCode())
                .isEqualTo(MigrationPreconditionException.APPROVAL_NOT_CITABLE);

        assertThat(countRows("migration.cutover_decisions", "scope_id = :scopeId", Map.of("scopeId", scopeId)))
                .as("nothing is appended to the evidence table by a refused citation")
                .isZero();
        assertThat(scope(scopeId).state()).as("and the writer does not move").isEqualTo(ScopeState.CUTOVER_READY);
    }

    /**
     * The other half, and it is not decoration: a rule that closed the hole by
     * refusing every approval would pass the test above and break ADR 0027's
     * platform-scope approvals — which is the case a platform-wide cutover
     * actually uses.
     */
    @Test
    @DisplayName("a cutover may be authorised by a PLATFORM-scope approval or by its own tenant's")
    void aCutoverMayCiteThePlatformsApprovalOrItsOwn() {
        UUID platformScoped = cutoverReadyScope();
        UUID platformApproval = approvalRequestFor(null);

        ScopeRow moved = cutOverCiting(platformScoped, platformApproval);
        assertThat(moved.state()).isEqualTo(ScopeState.TARGET_OWNED);
        assertThat(decisionStore
                        .findByIdempotencyKey(TENANT, "cutover-citing")
                        .orElseThrow()
                        .approvalRequestIsPlatform())
                .as("the decision records which of the two owners it cited, and V0088's key "
                        + "is what makes the record true")
                .isTrue();

        UUID ownScoped = cutoverReadyScopeFor(MigrationCapability.PAYMENTS);
        UUID ownApproval = approvalRequestFor(TENANT);
        ScopeRow movedOnItsOwn = scopeService.cutOver(
                TENANT,
                ownScoped,
                new CutoverCommand(
                        ScopeState.TARGET_OWNED,
                        scopeVersion(ownScoped),
                        "the window opened",
                        Map.of("watermark", "legacy:9000"),
                        REQUESTER,
                        APPROVER,
                        ownApproval,
                        null,
                        "cutover-own-approval"));
        assertThat(movedOnItsOwn.state()).isEqualTo(ScopeState.TARGET_OWNED);
        assertThat(decisionStore
                        .findByIdempotencyKey(TENANT, "cutover-own-approval")
                        .orElseThrow()
                        .approvalRequestIsPlatform())
                .isFalse();
    }

    // ------------------------------------------------------------- shorthands

    /** An ADR 0027 request owned by {@code tenantId}, or by the platform when null. */
    private UUID approvalRequestFor(UUID tenantId) {
        UUID policyId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO audit.approval_policies (
                    id, tenant_id, action_code, scope_type, threshold_json,
                    required_approver_capability, valid_from, version, approved_by)
                VALUES (:id, :tenantId, :actionCode, :scopeType, '{}'::jsonb,
                    'migration.cutover.approve', :from, 1, 'fixture')
                """)
                .param("id", policyId)
                .param("tenantId", tenantId)
                .param("actionCode", "migration.cutover." + policyId)
                .param("scopeType", tenantId == null ? "PLATFORM" : "TENANT")
                .param("from", clock.instant().minusSeconds(3600).atOffset(java.time.ZoneOffset.UTC))
                .update();

        UUID requestId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO audit.approval_requests (
                    id, tenant_id, action_code, parameters_hash, scope_type, scope_id,
                    policy_id, policy_is_platform, policy_version, threshold_description,
                    status, requested_by, reason, expires_at)
                VALUES (:id, :tenantId, :actionCode, :hash, :scopeType, :scopeId,
                    :policyId, :policyIsPlatform, 1, 'probe', 'PENDING', :requestedBy,
                    'the cutover window', :expiresAt)
                """)
                .param("id", requestId)
                .param("tenantId", tenantId)
                .param("actionCode", "migration.cutover." + requestId)
                .param("hash", requestId.toString().replace("-", "").repeat(2).substring(0, 64))
                .param("scopeType", tenantId == null ? "PLATFORM" : "TENANT")
                .param("scopeId", tenantId)
                .param("policyId", policyId)
                .param("policyIsPlatform", tenantId == null)
                .param("requestedBy", REQUESTER)
                .param("expiresAt", clock.instant().plusSeconds(86_400).atOffset(java.time.ZoneOffset.UTC))
                .update();
        return requestId;
    }

    private ScopeRow cutOverCiting(UUID scopeId, UUID approvalRequestId) {
        return scopeService.cutOver(
                TENANT,
                scopeId,
                new CutoverCommand(
                        ScopeState.TARGET_OWNED,
                        scopeVersion(scopeId),
                        "the window opened",
                        Map.of("watermark", "legacy:9000"),
                        REQUESTER,
                        APPROVER,
                        approvalRequestId,
                        null,
                        "cutover-citing"));
    }

    private ScopeRow advance(UUID scopeId, ScopeState to) {
        return scopeService.advance(
                TENANT,
                scopeId,
                new AdvanceCommand(
                        to,
                        scopeVersion(scopeId),
                        "moving the scope",
                        UUID.randomUUID().toString()));
    }

    private ScopeRow cutOver(
            UUID scopeId, String requestedBy, String decidedBy, Map<String, Object> evidence, String idempotencyKey) {

        return scopeService.cutOver(
                TENANT,
                scopeId,
                new CutoverCommand(
                        ScopeState.TARGET_OWNED,
                        scopeVersion(scopeId),
                        "the window opened",
                        evidence,
                        requestedBy,
                        decidedBy,
                        null,
                        null,
                        idempotencyKey));
    }

    /**
     * A scope at {@code CUTOVER_READY} with a finished backfill behind it, which is
     * the position every cutover assertion starts from.
     */
    private UUID cutoverReadyScope() {
        return cutoverReadyScopeFor(MigrationCapability.ORDERS);
    }

    private UUID cutoverReadyScopeFor(MigrationCapability capability) {
        UUID scopeId = openTenantWideScope(capability);
        advanceThrough(scopeId, ScopeState.MAPPING_APPROVED, ScopeState.BACKFILLING);
        // Keyed on the capability, not a literal: two scopes in one tenant share
        // the idempotency key space, so a fixed "backfill-1" makes the second
        // caller replay the first one's run instead of starting its own.
        startRun(
                scopeId,
                RunType.BACKFILL,
                capability == MigrationCapability.ORDERS
                        ? "backfill-1"
                        : "backfill-" + capability.name().toLowerCase(java.util.Locale.ROOT));
        advanceThrough(scopeId, ScopeState.CATCHING_UP, ScopeState.SHADOW_READING, ScopeState.CANARY);
        scopeService.republishCoverage(TENANT, scopeId, 0, scopeVersion(scopeId), "all decided");
        advance(scopeId, ScopeState.CUTOVER_READY);
        return scopeId;
    }

    private UUID runIdOf(UUID scopeId) {
        return runStore.findActive(TENANT, scopeId, RunType.BACKFILL)
                .orElseThrow()
                .id();
    }
}
