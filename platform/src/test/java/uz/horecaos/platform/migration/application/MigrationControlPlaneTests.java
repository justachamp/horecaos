package uz.horecaos.platform.migration.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import uz.horecaos.platform.migration.api.CapabilityOwnership;
import uz.horecaos.platform.migration.api.ImportContext;
import uz.horecaos.platform.migration.api.MigrationCapability;
import uz.horecaos.platform.migration.api.TargetWritesFencedException;
import uz.horecaos.platform.migration.application.MigrationProgramService.OpenScopeCommand;
import uz.horecaos.platform.migration.application.MigrationRunStore.Counters;
import uz.horecaos.platform.migration.application.MigrationRunStore.RunRow;
import uz.horecaos.platform.migration.domain.MappingStatus;
import uz.horecaos.platform.migration.domain.ReadMode;
import uz.horecaos.platform.migration.domain.RunStatus;
import uz.horecaos.platform.migration.domain.RunType;
import uz.horecaos.platform.migration.domain.ScopeState;
import uz.horecaos.platform.migration.domain.WriteMode;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcEntityMappingStore.EntityMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The guarantees the control plane makes about ownership, restartability and
 * quarantine, against the real schema (ADR 0024).
 *
 * <p>The single-writer claims are asserted against the database rather than
 * against the service, deliberately. ADR 0024 says two rows claiming one
 * capability must be <em>unrepresentable</em> rather than rejected by whichever
 * service happened to be asked, so the overlap tests insert by hand, past every
 * service, and assert PostgreSQL refuses.
 */
class MigrationControlPlaneTests extends MigrationControlPlaneFixture {

    // ================================================ 1. one claim, one writer

    /**
     * Guarantee 1, and the case a naive {@code UNIQUE (tenant_id, capability,
     * brand_id, location_id)} would miss entirely: NULL is not equal to NULL in a
     * unique index, so two identical tenant-wide claims would both insert and the
     * resolver would pick whichever the planner returned first.
     */
    @Test
    @DisplayName("two tenant-wide scopes cannot claim one capability, even though both narrowings are NULL")
    void twoTenantWideClaimsAreUnrepresentable() {
        insertScopeRow(UUID.randomUUID(), null, null, MigrationCapability.ORDERS,
                ScopeState.DISCOVERY, WriteMode.LEGACY_ONLY, ReadMode.LEGACY);

        Throwable second = catchThrowable(() -> insertScopeRow(UUID.randomUUID(), null, null,
                MigrationCapability.ORDERS, ScopeState.DISCOVERY, WriteMode.LEGACY_ONLY,
                ReadMode.LEGACY));

        assertThat(second)
                .as("two rows answering one ownership question is two writers chosen at random")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(second.getMessage()).contains("ux_scope_claim_tenant_wide");

        // And the premise: the resolver really would have had two rows to choose
        // between, because it probes on exactly this key.
        assertThat(countRows("migration.scopes",
                "tenant_id = :tenantId AND capability = 'ORDERS' "
                        + "AND brand_id IS NULL AND location_id IS NULL",
                Map.of("tenantId", TENANT)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("two brand-wide scopes and two branch scopes are refused the same way")
    void theOtherTwoSpecificitiesAreAlsoSingleClaim() {
        insertScopeRow(UUID.randomUUID(), BRAND, null, MigrationCapability.ORDERS,
                ScopeState.DISCOVERY, WriteMode.LEGACY_ONLY, ReadMode.LEGACY);
        assertThat(catchThrowable(() -> insertScopeRow(UUID.randomUUID(), BRAND, null,
                MigrationCapability.ORDERS, ScopeState.DISCOVERY, WriteMode.LEGACY_ONLY,
                ReadMode.LEGACY)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_scope_claim_brand_wide");

        insertScopeRow(UUID.randomUUID(), BRAND, LOCATION, MigrationCapability.ORDERS,
                ScopeState.DISCOVERY, WriteMode.LEGACY_ONLY, ReadMode.LEGACY);
        assertThat(catchThrowable(() -> insertScopeRow(UUID.randomUUID(), BRAND, LOCATION,
                MigrationCapability.ORDERS, ScopeState.DISCOVERY, WriteMode.LEGACY_ONLY,
                ReadMode.LEGACY)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_scope_claim_location");
    }

    /**
     * The three levels are meant to coexist — that is how a canary at one branch
     * is expressed at all. Without this the previous two tests would also pass
     * against a schema that refused every second scope.
     */
    @Test
    @DisplayName("the three specificities coexist: a branch may cut over while its brand has not")
    void claimsAtDifferentSpecificitiesAreNotAConflict() {
        insertScopeRow(UUID.randomUUID(), null, null, MigrationCapability.ORDERS,
                ScopeState.DISCOVERY, WriteMode.LEGACY_ONLY, ReadMode.LEGACY);
        insertScopeRow(UUID.randomUUID(), BRAND, null, MigrationCapability.ORDERS,
                ScopeState.DISCOVERY, WriteMode.LEGACY_ONLY, ReadMode.LEGACY);
        insertScopeRow(UUID.randomUUID(), BRAND, LOCATION, MigrationCapability.ORDERS,
                ScopeState.DISCOVERY, WriteMode.LEGACY_ONLY, ReadMode.LEGACY);
        insertScopeRow(UUID.randomUUID(), BRAND, OTHER_LOCATION, MigrationCapability.ORDERS,
                ScopeState.DISCOVERY, WriteMode.LEGACY_ONLY, ReadMode.LEGACY);
        insertScopeRow(UUID.randomUUID(), SECOND_BRAND, null, MigrationCapability.ORDERS,
                ScopeState.DISCOVERY, WriteMode.LEGACY_ONLY, ReadMode.LEGACY);

        // A different capability is a different claim at every level.
        insertScopeRow(UUID.randomUUID(), null, null, MigrationCapability.CATALOG,
                ScopeState.DISCOVERY, WriteMode.LEGACY_ONLY, ReadMode.LEGACY);

        assertThat(countRows("migration.scopes", "tenant_id = :tenantId",
                Map.of("tenantId", TENANT)))
                .isEqualTo(6);
    }

    /**
     * The claim is on the capability, not on the paperwork. Two programs each
     * holding a tenant-wide ORDERS scope would be two writers however the
     * programs are described, which is why {@code program_id} is deliberately
     * absent from all three claim keys.
     */
    @Test
    @DisplayName("a second program cannot claim a capability the first one already holds")
    void theClaimIgnoresWhichProgramMadeIt() {
        openTenantWideScope(MigrationCapability.ORDERS);

        UUID rehearsal = programs.create(new MigrationProgramService.CreateProgramCommand(
                "Delever rehearsal", "delever-staging", "horecaos-staging", 3, "a second program")).id();

        // Through the service, the second program is told who already holds it.
        Throwable refused = catchThrowable(() -> programs.openScope(rehearsal,
                new OpenScopeCommand(TENANT, null, null, MigrationCapability.ORDERS,
                        "DELEVER", "HORECAOS_ORDERING", "claiming it again")));
        assertThat(refused)
                .isInstanceOf(MigrationConflictException.class)
                .hasMessageContaining("two writers");

        // And past the service, the database refuses it too.
        assertThat(catchThrowable(() -> insertScopeRow(rehearsal, UUID.randomUUID(), null, null,
                MigrationCapability.ORDERS, ScopeState.DISCOVERY, WriteMode.LEGACY_ONLY,
                ReadMode.LEGACY)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_scope_claim_tenant_wide");
    }

    /**
     * No claim index is filtered on state, and this is why: a resolver forced to
     * choose between a retired row and a live row at one specificity is choosing
     * between two writers. Re-migrating a capability reuses the row it has.
     */
    @Test
    @DisplayName("a retired scope keeps its claim")
    void retirementDoesNotReleaseTheClaim() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);
        jdbc.sql("UPDATE migration.scopes SET state = 'RETIRED' WHERE id = :id")
                .param("id", scopeId).update();

        assertThat(catchThrowable(() -> insertScopeRow(UUID.randomUUID(), null, null,
                MigrationCapability.ORDERS, ScopeState.DISCOVERY, WriteMode.LEGACY_ONLY,
                ReadMode.LEGACY)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_scope_claim_tenant_wide");
    }

    /**
     * A location always belongs to a brand. Narrowing to a branch without naming
     * its brand would switch off the composite foreign key and admit another
     * brand's branch — a row that inserts cleanly and then fences the wrong
     * people's writes.
     */
    @Test
    @DisplayName("a scope cannot narrow to another tenant's brand, or to a branch with no brand")
    void narrowingCannotCrossAnOwnershipBoundary() {
        assertThat(catchThrowable(() -> insertScopeRow(UUID.randomUUID(), FOREIGN_BRAND, null,
                MigrationCapability.ORDERS, ScopeState.DISCOVERY, WriteMode.LEGACY_ONLY,
                ReadMode.LEGACY)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_scope_brand");

        assertThat(catchThrowable(() -> insertScopeRow(UUID.randomUUID(), null, LOCATION,
                MigrationCapability.ORDERS, ScopeState.DISCOVERY, WriteMode.LEGACY_ONLY,
                ReadMode.LEGACY)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_scope_narrowing");

        assertThat(catchThrowable(() -> insertScopeRow(UUID.randomUUID(), SECOND_BRAND, LOCATION,
                MigrationCapability.ORDERS, ScopeState.DISCOVERY, WriteMode.LEGACY_ONLY,
                ReadMode.LEGACY)))
                .as("LOCATION belongs to BRAND, so claiming it under SECOND_BRAND is another "
                        + "brand's branch")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_scope_location");
    }

    // ==================================================== 2. the gate fails closed

    /**
     * Guarantee 2. Every unknown answers "the target may not write", and the one
     * case that answers otherwise is asserted alongside them so the test cannot
     * pass by fencing everything.
     */
    @Test
    @DisplayName("every unknown fences the target write, and only real ownership does not")
    void theGateFailsClosedOnEverythingUnknown() {
        // No scope row at all. The migration has never reached this capability.
        CapabilityOwnership unmanaged = ownership.ownershipOf(TENANT,
                MigrationCapability.PAYMENTS, null, null);
        assertThat(unmanaged.scopeId()).isNull();
        assertThat(unmanaged.targetMayWrite()).isFalse();
        assertThat(catchThrowable(() -> ownership.requireTargetMayWrite(TENANT,
                MigrationCapability.PAYMENTS, null, null)))
                .isInstanceOf(TargetWritesFencedException.class);

        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);

        // The premise: a scope that really has taken ownership answers yes.
        forceModes(scopeId, ScopeState.TARGET_OWNED, WriteMode.TARGET_ONLY, ReadMode.TARGET);
        assertThat(ownership.ownershipOf(TENANT, MigrationCapability.ORDERS, null, null)
                .targetMayWrite())
                .as("a capability that has genuinely changed hands must be writable, "
                        + "or this suite would pass against a gate that refuses everything")
                .isTrue();

        // Paused, and blocked, keep their stored modes and are fenced on the state.
        for (ScopeState suspended : List.of(ScopeState.PAUSED, ScopeState.BLOCKED_RECONCILIATION)) {
            forceModes(scopeId, suspended, WriteMode.TARGET_ONLY, ReadMode.TARGET);
            CapabilityOwnership held = ownership.ownershipOf(TENANT, MigrationCapability.ORDERS,
                    null, null);
            assertThat(held.writeMode())
                    .as("%s keeps the routing it had, so it can be resumed to it", suspended)
                    .isEqualTo(WriteMode.TARGET_ONLY);
            assertThat(held.targetMayWrite())
                    .as("%s is somebody having decided the scope should not write", suspended)
                    .isFalse();
            assertThat(catchThrowable(() -> ownership.requireTargetMayWrite(TENANT,
                    MigrationCapability.ORDERS, null, null)))
                    .isInstanceOf(TargetWritesFencedException.class)
                    .hasMessageContaining(suspended.name());
        }

        // Mid-rollback, neither side may write: ADR 0024 restores legacy routing
        // only once one writer is proven.
        forceModes(scopeId, ScopeState.ROLLING_BACK, WriteMode.TARGET_ONLY, ReadMode.TARGET);
        CapabilityOwnership rollingBack = ownership.ownershipOf(TENANT,
                MigrationCapability.ORDERS, null, null);
        assertThat(rollingBack.targetMayWrite()).isFalse();
        assertThat(rollingBack.legacyMayWrite()).isFalse();

        // Guarantee 3, through the port: a shadow is not the authority.
        forceModes(scopeId, ScopeState.CANARY, WriteMode.LEGACY_WITH_TARGET_SHADOW,
                ReadMode.CANARY_TARGET);
        assertThat(catchThrowable(() -> ownership.requireTargetMayWrite(TENANT,
                MigrationCapability.ORDERS, null, null)))
                .as("LEGACY_WITH_TARGET_SHADOW permits the replicator to write a copy, "
                        + "never a request handler to write as though it owned the fact")
                .isInstanceOf(TargetWritesFencedException.class)
                .hasMessageContaining("LEGACY_WITH_TARGET_SHADOW");
    }

    /**
     * A row whose state does not permit its stored modes has drifted — from a
     * hand-edited UPDATE, or a restore that put half of a cutover back. Its write
     * mode is not evidence of anything, so the answer is rebuilt as legacy-owned
     * rather than read off it.
     */
    @Test
    @DisplayName("a scope whose state and modes disagree is fenced, and still names its row")
    void aDriftedRowIsNotTrusted() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);

        // DISCOVERY permits only LEGACY_ONLY/LEGACY. The pair below satisfies the
        // one mode rule the schema keeps, so it is reachable by an UPDATE.
        forceModes(scopeId, ScopeState.DISCOVERY, WriteMode.TARGET_ONLY, ReadMode.TARGET);

        CapabilityOwnership drifted = ownership.ownershipOf(TENANT, MigrationCapability.ORDERS,
                null, null);

        assertThat(drifted.targetMayWrite()).isFalse();
        assertThat(drifted.writeMode())
                .as("the stored write mode of an incoherent row is not read off it")
                .isEqualTo(WriteMode.LEGACY_ONLY);
        assertThat(drifted.scopeId())
                .as("the operator is sent to the row that needs fixing, not told no scope exists")
                .isEqualTo(scopeId);
    }

    /**
     * The schema keeps one of the three coherence rules the mode pair enforces
     * ({@code ck_scope_target_reads_need_target_writes}), so the other two are
     * reachable by an UPDATE against the database. Such a row fails on read, in
     * the record's own constructor, and the failure propagates out of the gate
     * rather than being turned into an answer — which aborts the caller's write,
     * the fail-closed direction.
     *
     * <p>Pinned here with the exception type it actually throws. A caller
     * catching only {@code TargetWritesFencedException} would not catch this one;
     * that is reported alongside this suite rather than asserted away.
     */
    @Test
    @DisplayName("a row carrying a mode pair the domain refuses fails the read rather than answering")
    void anIncoherentModePairAbortsTheGatedWrite() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);

        // Reading a target that LEGACY_ONLY leaves unfilled. The schema admits it
        // because its one mode rule is only about ReadMode.TARGET.
        forceModes(scopeId, ScopeState.SHADOW_READING, WriteMode.LEGACY_ONLY,
                ReadMode.SHADOW_COMPARE);

        assertThat(catchThrowable(() -> ownership.requireTargetMayWrite(TENANT,
                MigrationCapability.ORDERS, null, null)))
                .as("a store failure propagates and aborts the write it was asked to authorise; "
                        + "reporting \"no scope covers this\" would tell every module it owned "
                        + "every capability")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leaves unfilled");

        // The other reachable shape: legacy reads after the target took the writes.
        forceModes(scopeId, ScopeState.TARGET_OWNED, WriteMode.TARGET_ONLY, ReadMode.LEGACY);
        assertThat(catchThrowable(() -> ownership.ownershipOf(TENANT, MigrationCapability.ORDERS,
                null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serves stale data");
    }

    /**
     * Most specific wins, and resolution stops at the first level that claims the
     * capability. That stop is the mechanism, not an optimisation: it is how one
     * branch cuts over while the rest of its brand stays on legacy.
     */
    @Test
    @DisplayName("a branch that has cut over is target-owned while its brand is not")
    void resolutionStopsAtTheMostSpecificClaim() {
        UUID brandScope = openScope(MigrationCapability.ORDERS, BRAND, null);
        UUID branchScope = openScope(MigrationCapability.ORDERS, BRAND, LOCATION);
        forceModes(branchScope, ScopeState.TARGET_OWNED, WriteMode.TARGET_ONLY, ReadMode.TARGET);

        assertThat(ownership.ownershipOf(TENANT, MigrationCapability.ORDERS, BRAND, LOCATION)
                .targetMayWrite())
                .isTrue();
        assertThat(ownership.ownershipOf(TENANT, MigrationCapability.ORDERS, BRAND, OTHER_LOCATION)
                .scopeId())
                .as("a branch with no claim of its own falls back to its brand")
                .isEqualTo(brandScope);
        assertThat(ownership.ownershipOf(TENANT, MigrationCapability.ORDERS, BRAND, OTHER_LOCATION)
                .targetMayWrite())
                .isFalse();
        assertThat(ownership.ownershipOf(TENANT, MigrationCapability.ORDERS, BRAND, null).scopeId())
                .isEqualTo(brandScope);

        // And another tenant is not covered by this tenant's claim at all.
        assertThat(ownership.ownershipOf(OTHER_TENANT, MigrationCapability.ORDERS, BRAND, LOCATION)
                .scopeId())
                .isNull();
    }

    // ====================================================== 6. restartable runs

    /**
     * Guarantee 6. A worker is killed mid-page all the time, and the migration has
     * to carry on from where it was without re-importing what it imported and
     * without counting anything twice.
     */
    @Test
    @DisplayName("a killed run resumes from its watermark and its counters do not double-count")
    void aRunIsRestartable() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);
        advanceThrough(scopeId, ScopeState.MAPPING_APPROVED, ScopeState.BACKFILLING);

        UUID firstRun = startRun(scopeId, RunType.BACKFILL, "backfill-attempt-1");
        checkpoint(firstRun, "legacy:500", new Counters(500, 500, 0, 0, 0));
        checkpoint(firstRun, "legacy:1000", new Counters(1000, 990, 10, 0, 0));

        // The worker dies here. What it reads back on the way in is the watermark
        // and the totals as they stand.
        RunRow resumed = runService.resume(TENANT, firstRun);
        assertThat(resumed.sourceWatermark()).isEqualTo("legacy:1000");
        assertThat(resumed.counters()).isEqualTo(new Counters(1000, 990, 10, 0, 0));

        // The lost response on the last checkpoint: it is sent again, and because
        // the totals are absolute the row does not move.
        checkpoint(firstRun, "legacy:1000", new Counters(1000, 990, 10, 0, 0));
        assertThat(runStore.findById(TENANT, firstRun).orElseThrow().counters())
                .as("a retried checkpoint restates what is already there")
                .isEqualTo(new Counters(1000, 990, 10, 0, 0));

        // It pages on from the watermark rather than from the beginning.
        checkpoint(firstRun, "legacy:1500", new Counters(1500, 1480, 20, 0, 0));

        // And the opposite mistake fails loudly: a worker that seeded its tally
        // from zero would understate exactly the rows it re-imported.
        Throwable rewound = catchThrowable(() ->
                checkpoint(firstRun, "legacy:1600", new Counters(100, 100, 0, 0, 0)));
        assertThat(rewound.getMessage())
                .contains("Run counters only advance");
        assertThat(runStore.findById(TENANT, firstRun).orElseThrow().counters().scanned())
                .isEqualTo(1500);

        runService.finish(TENANT, firstRun, new MigrationRunService.FinishRunCommand(
                RunStatus.FAILED, null, runVersion(firstRun), "the worker was killed"));

        // The clock moves between the two settlements. findResumption orders on
        // finished_at and breaks a tie on the row id, which is a random UUID, so
        // two runs of one type settling inside the same timestamp would resolve to
        // whichever id sorted higher. See the note returned with this suite.
        clock.advance(java.time.Duration.ofMinutes(5));

        // A successor inherits the watermark, which is how the migration survives a
        // process dying at three in the morning, and does not inherit the counters,
        // which would count the first pass twice in every reconciliation sum.
        UUID successor = startRun(scopeId, RunType.BACKFILL, "backfill-attempt-2");
        RunRow second = runStore.findById(TENANT, successor).orElseThrow();
        assertThat(second.sourceWatermark()).isEqualTo("legacy:1500");
        assertThat(second.counters()).isEqualTo(Counters.NONE);

        // A retried start joins the run it already started rather than opening a
        // second backfill that would double every counter.
        assertThat(startRun(scopeId, RunType.BACKFILL, "backfill-attempt-2")).isEqualTo(successor);
        assertThat(countRows("migration.runs", "scope_id = :scopeId AND run_type = 'BACKFILL'",
                Map.of("scopeId", scopeId)))
                .isEqualTo(2);

        // A completed run is not a place to resume from: a successor would restart
        // a finished backfill at its end and scan nothing.
        checkpoint(successor, "legacy:9000", new Counters(7500, 7400, 100, 0, 0));
        runService.finish(TENANT, successor, new MigrationRunService.FinishRunCommand(
                RunStatus.COMPLETED, "a".repeat(64), runVersion(successor), "done"));
        assertThat(runStore.findResumption(TENANT, scopeId, RunType.BACKFILL)).isEmpty();

        // And a finished run is evidence, not state.
        assertThat(catchThrowable(() ->
                checkpoint(successor, "legacy:9500", new Counters(8000, 7900, 100, 0, 0))))
                .isInstanceOf(MigrationConflictException.class)
                .hasMessageContaining("evidence, not state");
    }

    @Test
    @DisplayName("two live runs of one type over one scope are unrepresentable")
    void oneLiveRunOfEachTypePerScope() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);
        advanceThrough(scopeId, ScopeState.MAPPING_APPROVED, ScopeState.BACKFILLING);
        startRun(scopeId, RunType.BACKFILL, "backfill-1");

        assertThat(catchThrowable(() -> startRun(scopeId, RunType.BACKFILL, "backfill-2")))
                .as("two backfills would page the same source twice and race on the crosswalk")
                .isInstanceOf(MigrationConflictException.class)
                .hasMessageContaining("already a live BACKFILL");

        // A reconciliation alongside a catch-up is fine, and deliberately still
        // allowed: measuring a scope is not writing to it.
        assertThat(startRun(scopeId, RunType.RECONCILIATION, "recon-1")).isNotNull();
    }

    /**
     * The second half of guarantee 6. The crosswalk's upsert key is what makes a
     * restarted backfill idempotent: the second import of a legacy row finds its
     * own mapping instead of minting a second target entity.
     */
    @Test
    @DisplayName("re-running the same mapping upsert leaves exactly one crosswalk row")
    void theCrosswalkIsIdempotentUnderARerun() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);
        advanceThrough(scopeId, ScopeState.MAPPING_APPROVED, ScopeState.BACKFILLING);
        UUID runId = startRun(scopeId, RunType.BACKFILL, "backfill-1");
        UUID targetOrder = UUID.randomUUID();

        UUID first = mappingStore.upsert(new EntityMapping(UUID.randomUUID(), TENANT, scopeId,
                        "ORDER", "delever-88121", targetOrder, "rev-7", 1L, 1,
                        MappingStatus.MAPPED, runId, clock.instant()))
                .orElseThrow();

        // The page is replayed after the worker was killed inside it. A different
        // mapping id is offered, as a fresh attempt would offer.
        UUID second = mappingStore.upsert(new EntityMapping(UUID.randomUUID(), TENANT, scopeId,
                        "ORDER", "delever-88121", targetOrder, "rev-7", 1L, 1,
                        MappingStatus.MAPPED, runId, clock.instant()))
                .orElseThrow();

        assertThat(second).isEqualTo(first);
        assertThat(countRows("migration.entity_mappings",
                "scope_id = :scopeId AND entity_type = 'ORDER' AND legacy_id = 'delever-88121'",
                Map.of("scopeId", scopeId)))
                .as("a second row here is a duplicated customer order in the target")
                .isEqualTo(1);
        assertThat(mappingStore.find(TENANT, scopeId, "ORDER", "delever-88121").orElseThrow()
                .targetId())
                .as("and it still resolves to the entity the first pass created")
                .isEqualTo(targetOrder);
    }

    // ======================================================== 8. quarantine

    /**
     * Guarantee 8, structurally. There is no payload column and no parameter that
     * would carry one, so a store implementing the port has nothing to write such
     * a column from. Asserted against the whole column list rather than against a
     * name, so a column added later fails here whatever it is called.
     */
    @Test
    @DisplayName("quarantine has no column and no parameter that could hold a source row")
    void quarantineCannotCarryAPayload() {
        List<String> columns = jdbc.sql("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'migration' AND table_name = 'quarantine_items'
                ORDER BY column_name
                """).query(String.class).list();

        assertThat(columns).containsExactly(
                "created_at", "entity_type", "id", "legacy_id", "reason_code",
                "resolution_code", "resolved_at", "resolved_by",
                "run_id", "sanitized_evidence_reference", "status", "tenant_id", "updated_at");

        List<String> commandFields = java.util.Arrays.stream(
                        QuarantineService.QuarantineCommand.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(commandFields)
                .as("a broken legacy row is not less personal than a valid one (ADR 0029)")
                .containsExactly("entityType", "legacyId", "reasonCode",
                        "sanitizedEvidenceReference");
    }

    @Test
    @DisplayName("the evidence reference has to be a reference, and a legacy id has to be an id")
    void theRemainingFieldsRefuseToBecomeThePayload() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);
        advanceThrough(scopeId, ScopeState.MAPPING_APPROVED, ScopeState.BACKFILLING);
        UUID runId = startRun(scopeId, RunType.BACKFILL, "backfill-1");

        // The most likely way source data reaches this schema: a diagnosing
        // engineer in good faith, because the pointer was inconvenient.
        Throwable pasted = catchThrowable(() -> quarantineService.quarantine(TENANT, runId,
                new QuarantineService.QuarantineCommand("ORDER", "delever-1", "TENANT_UNPROVABLE",
                        "{\"phone\": \"+998901112233\", \"total\": 45000}")));
        assertThat(pasted)
                .isInstanceOf(MigrationPreconditionException.class)
                .hasMessageContaining("no lawful basis to hold a copy");
        assertThat(((MigrationPreconditionException) pasted).reasonCode())
                .isEqualTo(MigrationPreconditionException.EVIDENCE_NOT_A_REFERENCE);

        assertThat(catchThrowable(() -> quarantineService.quarantine(TENANT, runId,
                new QuarantineService.QuarantineCommand("ORDER", "x".repeat(256),
                        "TENANT_UNPROVABLE", null))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("it is the row");

        assertThat(catchThrowable(() -> quarantineService.quarantine(TENANT, runId,
                new QuarantineService.QuarantineCommand("ORDER", "delever-1",
                        "the customer phone number was three digits", null))))
                .as("a reason code is from the approved vocabulary, not free text")
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(countRows("migration.quarantine_items", "run_id = :runId", Map.of("runId", runId)))
                .isZero();
    }

    /**
     * Guarantee 8's second half. ADR 0024 is explicit that a row without provable
     * tenant ownership is quarantined rather than assigned, and the enforcement is
     * structural: the service takes no target tenant and no target scope, so there
     * is no argument through which a plausible owner could be supplied.
     */
    @Test
    @DisplayName("a quarantined row takes its tenant and scope from the run, never from the caller")
    void quarantineNeverAssignsADefaultTenant() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);
        advanceThrough(scopeId, ScopeState.MAPPING_APPROVED, ScopeState.BACKFILLING);
        UUID runId = startRun(scopeId, RunType.BACKFILL, "backfill-1");

        var item = quarantineService.quarantine(TENANT, runId,
                new QuarantineService.QuarantineCommand("ORDER", "delever-88122",
                        "TENANT_NOT_PROVABLE", "evidence:quarantine/2026-08-22/88122"));

        assertThat(item.tenantId()).isEqualTo(TENANT);
        assertThat(item.open()).isTrue();

        // The crosswalk entry exists so "seen and consciously not migrated" is
        // distinguishable from "never seen", and it carries the run's scope.
        var mapping = mappingStore.find(TENANT, scopeId, "ORDER", "delever-88122").orElseThrow();
        assertThat(mapping.status()).isEqualTo(MappingStatus.QUARANTINED);
        assertThat(mapping.targetId())
                .as("a quarantined mapping that named a target would assert the row was migrated")
                .isNull();
        assertThat(runStore.findById(TENANT, runId).orElseThrow().counters().quarantined())
                .isEqualTo(1);

        // Filing the same row again is the retried page. It finds its own item and
        // does not count the backlog twice.
        var refiled = quarantineService.quarantine(TENANT, runId,
                new QuarantineService.QuarantineCommand("ORDER", "delever-88122",
                        "TENANT_NOT_PROVABLE", "evidence:quarantine/2026-08-22/88122"));
        assertThat(refiled.id()).isEqualTo(item.id());
        assertThat(runStore.findById(TENANT, runId).orElseThrow().counters().quarantined())
                .as("a doubled count reports a backlog that does not exist, and the backlog "
                        + "gates retirement")
                .isEqualTo(1);

        // Another tenant cannot file against this run: the run is not theirs, so
        // there is no scope for the row to be assigned to.
        assertThat(catchThrowable(() -> quarantineService.quarantine(OTHER_TENANT, runId,
                new QuarantineService.QuarantineCommand("ORDER", "delever-99999",
                        "TENANT_NOT_PROVABLE", null))))
                .isInstanceOf(MigrationResourceNotFoundException.class);
        assertThat(countRows("migration.quarantine_items", "tenant_id = :tenantId",
                Map.of("tenantId", OTHER_TENANT)))
                .isZero();

        // And past the service, the composite foreign key refuses it as well.
        assertThat(catchThrowable(() -> jdbc.sql("""
                INSERT INTO migration.quarantine_items (id, tenant_id, run_id, entity_type,
                    legacy_id, reason_code, status)
                VALUES (:id, :tenantId, :runId, 'ORDER', 'delever-99999', 'TENANT_NOT_PROVABLE',
                    'OPEN')
                """).param("id", UUID.randomUUID()).param("tenantId", OTHER_TENANT)
                .param("runId", runId).update()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_quarantine_run");
    }

    @Test
    @DisplayName("the database refuses a quarantined crosswalk row that names a target")
    void aQuarantinedMappingHasNoTarget() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);
        advanceThrough(scopeId, ScopeState.MAPPING_APPROVED, ScopeState.BACKFILLING);
        UUID runId = startRun(scopeId, RunType.BACKFILL, "backfill-1");

        assertThat(catchThrowable(() -> jdbc.sql("""
                INSERT INTO migration.entity_mappings (id, tenant_id, scope_id, entity_type,
                    legacy_id, target_id, transformation_version, mapping_status, run_id)
                VALUES (:id, :tenantId, :scopeId, 'ORDER', 'delever-1', :targetId, 1,
                    'QUARANTINED', :runId)
                """).param("id", UUID.randomUUID()).param("tenantId", TENANT)
                .param("scopeId", scopeId).param("targetId", UUID.randomUUID())
                .param("runId", runId).update()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_entity_mapping_target");
    }

    // ================================================== 9. the import context

    /**
     * Guarantee 9. The flag suppresses external effects, and the three things it
     * must never touch are asserted while it is set: the row is still validated,
     * the fact is still audited, and tenant ancestry is still enforced.
     */
    @Test
    @DisplayName("an import still validates, still audits, and still enforces tenant ancestry")
    void theImportFlagSuppressesExternalEffectsAndNothingElse() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);
        advanceThrough(scopeId, ScopeState.MAPPING_APPROVED, ScopeState.BACKFILLING);
        UUID runId = startRun(scopeId, RunType.BACKFILL, "backfill-1");
        RunRow run = runStore.findById(TENANT, runId).orElseThrow();

        runService.runAsImport(run, () -> {
            assertThat(ImportContext.isImporting())
                    .as("this is the call site the flag exists for")
                    .isTrue();

            // Validation is not suppressed. A row that cannot pass it belongs in
            // quarantine, not in the target: an import that skipped validation is
            // the ad hoc target SQL ADR 0024 rejected.
            assertThat(catchThrowable(() -> quarantineService.quarantine(TENANT, runId,
                    new QuarantineService.QuarantineCommand("order", "delever-1", "BAD_ROW", null))))
                    .as("an entity type is an upper-case code whether or not this is an import")
                    .isInstanceOf(IllegalArgumentException.class);

            // Tenant ancestry is not suppressed either.
            assertThat(catchThrowable(() -> programs.openScope(programId,
                    new OpenScopeCommand(TENANT, FOREIGN_BRAND, null, MigrationCapability.CATALOG,
                            "DELEVER", "HORECAOS_CATALOG", "importing"))))
                    .as("a scope narrowed to another tenant's brand fences the wrong people")
                    .isInstanceOf(DataIntegrityViolationException.class);

            quarantineService.quarantine(TENANT, runId, new QuarantineService.QuarantineCommand(
                    "ORDER", "delever-31", "TENANT_NOT_PROVABLE", null));
            return null;
        });

        assertThat(ImportContext.isImporting()).isFalse();

        // And the audit fact is there, attributed to the migrator rather than to
        // whoever pressed start: an investigation that could not tell the two apart
        // would blame an operator for every row of a five-year backfill.
        Map<String, Object> filed = jdbc.sql("""
                SELECT actor_type, actor_subject, reason FROM audit.audit_events
                WHERE action_code = 'migration.quarantine.filed'
                """).query().singleRow();
        assertThat(filed.get("actor_type")).isEqualTo("MIGRATION");
        assertThat(filed.get("actor_subject")).isEqualTo("run:" + runId);
        assertThat(filed.get("reason")).isEqualTo("TENANT_NOT_PROVABLE");
    }

    @Test
    @DisplayName("the import flag is refused where there is nothing to suppress")
    void theImportFlagIsNotHandedOutFreely() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);
        advanceThrough(scopeId, ScopeState.MAPPING_APPROVED, ScopeState.BACKFILLING);

        UUID reconciliation = startRun(scopeId, RunType.RECONCILIATION, "recon-1");
        RunRow measuring = runStore.findById(TENANT, reconciliation).orElseThrow();
        assertThat(catchThrowable(() -> runService.runAsImport(measuring, () -> "x")))
                .as("running a reconciliation under the flag would silence a real caller's "
                        + "notification if the flag ever leaked")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no effects to suppress");

        UUID backfill = startRun(scopeId, RunType.BACKFILL, "backfill-1");
        runService.finish(TENANT, backfill, new MigrationRunService.FinishRunCommand(
                RunStatus.COMPLETED, null, runVersion(backfill), "done"));
        RunRow finished = runStore.findById(TENANT, backfill).orElseThrow();
        assertThat(catchThrowable(() -> runService.runAsImport(finished, () -> "x")))
                .isInstanceOf(MigrationConflictException.class)
                .hasMessageContaining("cannot import");
    }

    /**
     * A backfill needs a target that something is filling, and a held scope is one
     * somebody has decided should not be moving. A migrator that kept writing
     * through a pause would make the pause meaningless.
     */
    @Test
    @DisplayName("a run that writes the target is refused while the scope is not filling one")
    void aScopeMustAdmitTheRunBeforeItStarts() {
        UUID scopeId = openTenantWideScope(MigrationCapability.ORDERS);

        Throwable tooEarly = catchThrowable(() -> startRun(scopeId, RunType.BACKFILL, "early"));
        assertThat(tooEarly).isInstanceOf(MigrationPreconditionException.class);
        assertThat(((MigrationPreconditionException) tooEarly).reasonCode())
                .isEqualTo(MigrationPreconditionException.SCOPE_NOT_READY_FOR_RUN);

        advanceThrough(scopeId, ScopeState.MAPPING_APPROVED, ScopeState.BACKFILLING);
        scopeService.suspend(TENANT, scopeId, new MigrationScopeService.SuspendCommand(
                ScopeState.PAUSED, scopeVersion(scopeId), "an operator stopped it", "pause-1"));

        assertThat(catchThrowable(() -> startRun(scopeId, RunType.BACKFILL, "during-a-pause")))
                .isInstanceOf(MigrationPreconditionException.class)
                .hasMessageContaining("a state that exists to stop it");

        // Reconciliation is exempt: refusing to measure a paused scope would remove
        // the evidence needed to decide whether to un-pause it.
        assertThat(startRun(scopeId, RunType.RECONCILIATION, "recon-during-a-pause")).isNotNull();
    }

    // ------------------------------------------------------------- shorthands

    private void checkpoint(UUID runId, String watermark, Counters totals) {
        runService.checkpoint(TENANT, runId, new MigrationRunService.CheckpointCommand(
                watermark, "target:" + watermark, Map.of("page", watermark), totals));
    }

    private int runVersion(UUID runId) {
        return runStore.findById(TENANT, runId).orElseThrow().version();
    }

    /**
     * Rewrites a scope's state and modes past every gate, which is how a drifted
     * row arrives in production too: a hand-edited UPDATE, or a restore that put
     * half of a cutover back.
     */
    private void forceModes(UUID scopeId, ScopeState state, WriteMode writeMode, ReadMode readMode) {
        jdbc.sql("""
                UPDATE migration.scopes
                SET state = :state, write_mode = :writeMode, read_mode = :readMode
                WHERE id = :id
                """)
                .param("state", state.name()).param("writeMode", writeMode.name())
                .param("readMode", readMode.name()).param("id", scopeId)
                .update();
    }

    private void insertScopeRow(UUID id, UUID brandId, UUID locationId,
            MigrationCapability capability, ScopeState state, WriteMode writeMode,
            ReadMode readMode) {
        insertScopeRow(programId, id, brandId, locationId, capability, state, writeMode, readMode);
    }

    /** A raw insert, past every service, because guarantee 1 is the database's. */
    private void insertScopeRow(UUID program, UUID id, UUID brandId, UUID locationId,
            MigrationCapability capability, ScopeState state, WriteMode writeMode,
            ReadMode readMode) {

        Map<String, Object> narrowing = new HashMap<>();
        narrowing.put("brandId", brandId);
        narrowing.put("locationId", locationId);

        jdbc.sql("""
                INSERT INTO migration.scopes (id, program_id, tenant_id, brand_id, location_id,
                    capability, source_owner, target_owner, write_mode, read_mode, state)
                VALUES (:id, :programId, :tenantId, :brandId, :locationId, :capability,
                    'DELEVER', 'HORECAOS_ORDERING', :writeMode, :readMode, :state)
                """)
                .param("id", id).param("programId", program).param("tenantId", TENANT)
                .params(narrowing)
                .param("capability", capability.name())
                .param("writeMode", writeMode.name()).param("readMode", readMode.name())
                .param("state", state.name())
                .update();
    }
}
