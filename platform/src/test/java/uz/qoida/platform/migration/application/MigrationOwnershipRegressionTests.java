package uz.qoida.platform.migration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.qoida.platform.migration.api.MigrationCapability;
import uz.qoida.platform.migration.application.MigrationProgramService.OpenScopeCommand;
import uz.qoida.platform.migration.application.MigrationScopeService.AdvanceCommand;
import uz.qoida.platform.migration.application.MigrationScopeService.CutoverCommand;
import uz.qoida.platform.migration.application.MigrationScopeService.RollbackCommand;
import uz.qoida.platform.migration.application.MigrationScopeStore.ScopeRow;
import uz.qoida.platform.migration.application.QuarantineService.QuarantineCommand;
import uz.qoida.platform.migration.domain.MappingStatus;
import uz.qoida.platform.migration.domain.RunType;
import uz.qoida.platform.migration.domain.ScopeState;
import uz.qoida.platform.migration.infrastructure.persistence.JdbcEntityMappingStore.EntityMapping;

/**
 * The holes an adversarial review found, each one closed and pinned here.
 *
 * <p>Every case below was reachable by an ordinary operator action against code
 * that compiled, passed its own suite, and in two places carried a Javadoc
 * asserting the guarantee it did not have. They are grouped in one file because
 * they share a shape: each is a way for a capability to end up with two writers,
 * or with none, without anybody deciding that it should.
 */
class MigrationOwnershipRegressionTests extends MigrationControlPlaneFixture {

    private static final String REQUESTER = "maker";
    private static final String APPROVER = "checker";

    // ----------------------------------------------------- import after cutover

    /**
     * The importer feeds a follower. Once the target is the authority there is no
     * follower, and a catch-up run started against it — "one last sweep before we
     * freeze legacy" — replays legacy rows over target-owned facts.
     */
    @Test
    @DisplayName("no import run may be started against a scope that has already cut over")
    void aCutOverScopeAdmitsNoFurtherImportRun() {
        UUID scopeId = ownedScope();

        for (RunType writing : new RunType[] { RunType.BACKFILL, RunType.CATCH_UP,
                RunType.REMEDIATION }) {
            Throwable refused = catchThrowable(() -> startRun(scopeId, writing,
                    "after-cutover-" + writing));
            assertThat(refused)
                    .as("%s against a TARGET_OWNED scope", writing)
                    .isInstanceOf(MigrationPreconditionException.class);
        }
    }

    /**
     * Reconciliation still runs. Refusing to measure a cut-over scope would remove
     * the evidence the rollback window exists to gather.
     */
    @Test
    @DisplayName("reconciliation still runs against a cut-over scope")
    void reconciliationIsExemptFromTheImportRefusal() {
        UUID scopeId = ownedScope();

        assertThat(startRun(scopeId, RunType.RECONCILIATION, "measure-after-cutover")).isNotNull();
    }

    /**
     * ADR 0024's rollback opens by stopping new target commands and draining
     * workers. A backfill started underneath one is writing into the very scope
     * whose ownership is being handed back.
     */
    @Test
    @DisplayName("no import run may be started against a scope that is rolling back")
    void aRollingBackScopeAdmitsNoImportRun() {
        UUID scopeId = ownedScope();
        scopeService.rollBack(TENANT, scopeId,
                new RollbackCommand(scopeVersion(scopeId), "money did not reconcile", key()));

        assertThat(scope(scopeId).state()).isEqualTo(ScopeState.ROLLING_BACK);
        assertThat(catchThrowable(() -> startRun(scopeId, RunType.CATCH_UP, "during-rollback")))
                .isInstanceOf(MigrationPreconditionException.class);
    }

    /**
     * Guarding the start of a run is not enough. A catch-up is the ordinary state
     * of a scope on the way to cutover — ADR 0024's runbook step 3 is "final
     * incremental catch-up" — so a run is nearly always live at the moment
     * ownership moves, and the replicator is mid-page when the write mode flips.
     */
    @Test
    @DisplayName("a run that was live at cutover stops importing on its next page")
    void aRunningImportDoesNotSurviveTheCutover() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);
        advanceThrough(scopeId, ScopeState.MAPPING_APPROVED, ScopeState.BACKFILLING);
        startRun(scopeId, RunType.BACKFILL, "backfill-1");
        advanceThrough(scopeId, ScopeState.CATCHING_UP);

        UUID runId = startRun(scopeId, RunType.CATCH_UP, "the-final-sweep");
        var run = runService.get(TENANT, runId);

        // Mid-run, the page imports normally.
        assertThat(runService.runAsImport(run, () -> "page-1")).isEqualTo("page-1");

        advanceThrough(scopeId, ScopeState.SHADOW_READING, ScopeState.CANARY);
        scopeService.republishCoverage(TENANT, scopeId, 0, scopeVersion(scopeId), "all decided");
        scopeService.advance(TENANT, scopeId, new AdvanceCommand(ScopeState.CUTOVER_READY,
                scopeVersion(scopeId), "evidence is in", key()));
        scopeService.cutOver(TENANT, scopeId, new CutoverCommand(ScopeState.TARGET_OWNED,
                scopeVersion(scopeId), "the window opened", Map.of("watermark", "legacy:9000"),
                REQUESTER, APPROVER, null, null, key()));

        // The run row itself is untouched and still RUNNING — which is exactly why
        // checking it is not sufficient, and the scope has to be re-read.
        assertThat(runService.get(TENANT, runId).status().terminal()).isFalse();

        assertThat(catchThrowable(() -> runService.runAsImport(run, () -> "page-2")))
                .as("the next page finds the scope no longer admits imports")
                .isInstanceOf(MigrationPreconditionException.class);
    }

    // -------------------------------------------------- quarantine vs crosswalk

    /**
     * The schema states QUARANTINED and a null {@code target_id} as one fact, so
     * quarantining a migrated row would blank the crosswalk. The target entity
     * would survive with nothing pointing at it, and the next import — finding no
     * mapping for that legacy id — would create a second one. The duplicate is
     * silent, survives a reconciliation that counts keys, and shows up as money
     * counted twice.
     */
    @Test
    @DisplayName("an already-migrated row cannot be quarantined, and its crosswalk survives the attempt")
    void quarantiningAMigratedRowIsRefusedRatherThanOrphaningIt() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);
        advanceThrough(scopeId, ScopeState.MAPPING_APPROVED, ScopeState.BACKFILLING);
        UUID runId = startRun(scopeId, RunType.BACKFILL, "backfill-1");

        UUID targetId = UUID.randomUUID();
        tx(() -> mappingStore.upsert(new EntityMapping(UUID.randomUUID(), TENANT, scopeId,
                "ORDER", "legacy-4819", targetId, "v1", 1L, 1, MappingStatus.MAPPED, runId,
                clock.instant())));

        Throwable refused = catchThrowable(() -> tx(() -> quarantineService.quarantine(TENANT, runId,
                new QuarantineCommand("ORDER", "legacy-4819", "TENANT_KEY_UNRESOLVED", null))));

        assertThat(refused).isInstanceOf(MigrationConflictException.class);
        assertThat(refused).hasMessageContaining("already migrated");

        var mapping = mappingStore.find(TENANT, scopeId, "ORDER", "legacy-4819").orElseThrow();
        assertThat(mapping.status())
                .as("the crosswalk is untouched, so nothing is orphaned")
                .isEqualTo(MappingStatus.MAPPED);
        assertThat(mapping.targetId()).isEqualTo(targetId);
    }

    /** The ordinary case still works: an unmapped row quarantines. */
    @Test
    @DisplayName("a row that never migrated quarantines normally")
    void anUnmappedRowStillQuarantines() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);
        advanceThrough(scopeId, ScopeState.MAPPING_APPROVED, ScopeState.BACKFILLING);
        UUID runId = startRun(scopeId, RunType.BACKFILL, "backfill-1");

        var item = tx(() -> quarantineService.quarantine(TENANT, runId,
                new QuarantineCommand("ORDER", "legacy-9001", "TENANT_KEY_UNRESOLVED", null)));

        assertThat(item.legacyId()).isEqualTo("legacy-9001");
        assertThat(mappingStore.find(TENANT, scopeId, "ORDER", "legacy-9001").orElseThrow().status())
                .isEqualTo(MappingStatus.QUARANTINED);
    }

    // ------------------------------------------------------ narrowing a scope

    /**
     * Resolution stops at the most specific claim, so inserting a narrower scope
     * re-answers ownership for that subtree. Under a target-owned ancestor with
     * legacy already fenced, the narrowed branch gets an answer of "the target may
     * not write" while legacy cannot take the order either — and there is no
     * DELETE on the table to undo it with.
     */
    @Test
    @DisplayName("a narrower scope cannot be opened under an ancestor the target already owns")
    void narrowingUnderATargetOwnedScopeIsRefused() {
        UUID tenantWide = ownedScope();
        assertThat(scope(tenantWide).state()).isEqualTo(ScopeState.TARGET_OWNED);

        Throwable refused = catchThrowable(() -> programs.openScope(programId,
                new OpenScopeCommand(TENANT, BRAND, LOCATION, MigrationCapability.ORDERS,
                        "DELEVER", "QOIDA_ORDERING", "planning the next wave")));

        assertThat(refused).isInstanceOf(MigrationConflictException.class);
        assertThat(refused).hasMessageContaining("no writer at all");
    }

    /**
     * The ordinary case, and the reason the check is about the write mode rather
     * than about existence: planning a wave under a legacy-owned ancestor is how
     * every migration starts.
     */
    @Test
    @DisplayName("a narrower scope opens freely while the ancestor is still legacy-owned")
    void narrowingUnderALegacyOwnedScopeIsAllowed() {
        openTenantWideScope(MigrationCapability.ORDERS);

        ScopeRow narrowed = programs.openScope(programId,
                new OpenScopeCommand(TENANT, BRAND, LOCATION, MigrationCapability.ORDERS,
                        "DELEVER", "QOIDA_ORDERING", "the branch that drains last"));

        assertThat(narrowed.locationId()).isEqualTo(LOCATION);
        assertThat(narrowed.state()).isEqualTo(ScopeState.DISCOVERY);
    }

    // ------------------------------------------------- who may reverse a cutover

    /**
     * Two people to give a capability to the target and one to take it back would
     * put the more dangerous half on the cheaper side: a rollback fences something
     * that is currently serving customers, and restoring it means a full
     * re-validation.
     */
    @Test
    @DisplayName("entering a rollback is refused on the ordinary transition path")
    void rollbackIsNotAnOrdinaryTransition() {
        UUID scopeId = ownedScope();

        Throwable refused = catchThrowable(() -> scopeService.advance(TENANT, scopeId,
                new AdvanceCommand(ScopeState.ROLLING_BACK, scopeVersion(scopeId),
                        "taking it back", key())));

        assertThat(refused).isInstanceOf(MigrationPreconditionException.class);
        assertThat(refused).hasMessageContaining("rollBack");
        assertThat(scope(scopeId).state())
                .as("the refusal leaves the scope exactly where it was")
                .isEqualTo(ScopeState.TARGET_OWNED);
    }

    @Test
    @DisplayName("the approver's rollback path works, and does not move the modes")
    void theApproverMayRollBack() {
        UUID scopeId = ownedScope();
        var before = scope(scopeId).modes();

        ScopeRow rolling = scopeService.rollBack(TENANT, scopeId,
                new RollbackCommand(scopeVersion(scopeId), "provider duplication", key()));

        assertThat(rolling.state()).isEqualTo(ScopeState.ROLLING_BACK);
        assertThat(rolling.modes())
                .as("ADR 0024: rollback is an ownership operation, not an undo of the data")
                .isEqualTo(before);
    }

    // ------------------------------------------------ replaying the wrong key

    /**
     * Refusals and approvals share one idempotency key space. Reusing a refusal's
     * key on the cutover endpoint used to answer 200 with the scope and an ETag
     * while nothing transitioned — and an operator reading a successful cutover
     * goes and fences legacy by hand, leaving the capability with no writer.
     */
    @Test
    @DisplayName("a refusal's idempotency key cannot be replayed as an approval")
    void aRefusalCannotBeReadBackAsACutover() {
        UUID scopeId = cutoverReadyScope();
        String sharedKey = "orders-window-3";

        scopeService.refuseCutover(TENANT, scopeId, new CutoverCommand(ScopeState.TARGET_OWNED,
                scopeVersion(scopeId), "the fiscal check had not run",
                Map.of("watermark", "legacy:8100", "fiscalCheck", "not run"),
                REQUESTER, APPROVER, null, null, sharedKey));

        Throwable refused = catchThrowable(() -> scopeService.cutOver(TENANT, scopeId,
                new CutoverCommand(ScopeState.TARGET_OWNED, scopeVersion(scopeId),
                        "the window opened", Map.of("watermark", "legacy:9000"),
                        REQUESTER, APPROVER, null, null, sharedKey)));

        assertThat(refused).isInstanceOf(MigrationConflictException.class);
        assertThat(refused).hasMessageContaining("REFUSED");
        assertThat(scope(scopeId).state())
                .as("the scope stayed where the refusal left it")
                .isEqualTo(ScopeState.CUTOVER_READY);
    }

    // -------------------------------------------------- calling off a program

    /**
     * A plan called off before it began never started, and the schema used to
     * insist it must have. The operator got a 500 for a legitimate action.
     */
    @Test
    @DisplayName("a program that never started can still be called off")
    void aPlannedProgramCanBeAbandoned() {
        var planned = programs.create(new MigrationProgramService.CreateProgramCommand(
                "Rehearsal that never ran", "delever-staging", "qoida-staging", 1, "seeding"));

        var abandoned = programs.abandon(planned.id(), planned.version(), "the pilot moved");

        assertThat(abandoned.status()).isEqualTo(ProgramStatus.ABANDONED);
        assertThat(abandoned.startedAt())
                .as("no start time is invented for a migration that never ran")
                .isNull();
    }

    @Test
    @DisplayName("an abandoned program cannot be abandoned twice")
    void abandoningTwiceIsRefused() {
        var planned = programs.create(new MigrationProgramService.CreateProgramCommand(
                "Called off once", "delever-staging", "qoida-staging", 1, "seeding"));
        var abandoned = programs.abandon(planned.id(), planned.version(), "the pilot moved");

        assertThat(catchThrowable(() ->
                programs.abandon(planned.id(), abandoned.version(), "again")))
                .isInstanceOf(MigrationConflictException.class);
    }

    // -------------------------------------------------------------- fixtures

    /** A tenant-wide ORDERS scope that has cut over, with an approver on record. */
    private UUID ownedScope() {
        UUID scopeId = cutoverReadyScope();
        scopeService.cutOver(TENANT, scopeId, new CutoverCommand(ScopeState.TARGET_OWNED,
                scopeVersion(scopeId), "the window opened", Map.of("watermark", "legacy:9000"),
                REQUESTER, APPROVER, null, null, key()));
        return scopeId;
    }

    private UUID cutoverReadyScope() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);
        advanceThrough(scopeId, ScopeState.MAPPING_APPROVED, ScopeState.BACKFILLING);
        startRun(scopeId, RunType.BACKFILL, "backfill-1");
        advanceThrough(scopeId, ScopeState.CATCHING_UP, ScopeState.SHADOW_READING,
                ScopeState.CANARY);
        scopeService.republishCoverage(TENANT, scopeId, 0, scopeVersion(scopeId), "all decided");
        scopeService.advance(TENANT, scopeId, new AdvanceCommand(ScopeState.CUTOVER_READY,
                scopeVersion(scopeId), "evidence is in", key()));
        return scopeId;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
