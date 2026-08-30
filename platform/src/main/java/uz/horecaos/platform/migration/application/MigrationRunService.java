package uz.horecaos.platform.migration.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.migration.api.ImportContext;
import uz.horecaos.platform.migration.application.MigrationRunStore.Counters;
import uz.horecaos.platform.migration.application.MigrationRunStore.RunRow;
import uz.horecaos.platform.migration.application.MigrationScopeStore.ScopeRow;
import uz.horecaos.platform.migration.domain.RunStatus;
import uz.horecaos.platform.migration.domain.RunType;
import uz.horecaos.platform.migration.domain.ScopeState;

/**
 * Starting, checkpointing, finishing and resuming migration runs (ADR 0024).
 *
 * <p>Everything here is shaped by one fact: a run is killed all the time. A
 * deploy, an out-of-memory, a network partition mid-page — and the migration has
 * to carry on from where it was, without re-importing what it already imported
 * and without counting anything twice. So a run reads a watermark on the way in,
 * advances it in bounded steps on the way through, and never restates a counter.
 *
 * <p>Two things make repetition safe, and they are different mechanisms for
 * different repetitions. A retried <em>start</em> joins the run it already
 * started, through {@code uq_run_idempotency}. A retried <em>checkpoint</em>
 * writes the same absolute totals it wrote the first time and leaves the row
 * where it was; adding a page's delta twice instead would inflate, by exactly the
 * retried page, every figure a reconciliation rule is about to compare against —
 * and a lost response on the last checkpoint of an eight-hour backfill is the
 * ordinary case, not the exotic one.
 */
@Service
public class MigrationRunService {

    /** Hex sha-256, as {@code ck_run_checksum} requires and every other digest here is. */
    private static final Pattern CHECKSUM = Pattern.compile("^[0-9a-f]{64}$");

    private static final Logger log = LoggerFactory.getLogger(MigrationRunService.class);

    private final MigrationRunStore runs;
    private final MigrationScopeStore scopes;
    private final MigrationAccessPolicy access;
    private final MigrationAudit audit;
    private final Clock clock;

    public MigrationRunService(MigrationRunStore runs, MigrationScopeStore scopes,
            MigrationAccessPolicy access, MigrationAudit audit, Clock clock) {
        this.runs = runs;
        this.scopes = scopes;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Opens a run over a scope.
     *
     * <p>An operator action, so it carries the capability check: starting a
     * backfill against a live tenant's source is a decision, and the run it opens
     * is what every subsequent import writes under. The per-page work that
     * follows is not — see {@link #checkpoint}.
     *
     * <p>A new run of a type that already has a live one is refused rather than
     * queued. {@code ux_run_active_per_scope} would refuse it anyway; catching it
     * here lets the answer name the run that is already going, which is what the
     * operator needs, instead of a constraint name.
     */
    @Transactional
    public RunRow start(UUID tenantId, UUID scopeId, StartRunCommand command) {
        Objects.requireNonNull(command, "A start run command is required");
        String actor = access.requireOperator();

        String key = requireText(command.idempotencyKey(), "An idempotency key is required (ADR 0031)");
        Optional<RunRow> replayed = runs.findByIdempotencyKey(tenantId, key);
        if (replayed.isPresent()) {
            RunRow run = replayed.get();
            if (!run.scopeId().equals(scopeId)) {
                throw new MigrationConflictException(
                        "That idempotency key already started a run over scope " + run.scopeId());
            }
            return run;
        }

        RunType runType = Objects.requireNonNull(command.runType(), "A run type is required");
        if (command.transformationVersion() <= 0) {
            throw new IllegalArgumentException(
                    "A run records the version of the transformation code it applied, so the rows it "
                            + "wrote can be traced to it");
        }

        ScopeRow scope = requireScope(tenantId, scopeId);
        requireScopeAdmits(scope, runType);

        Optional<RunRow> live = runs.findActive(tenantId, scopeId, runType);
        if (live.isPresent()) {
            throw new MigrationConflictException(
                    ("Run %s is already a live %s over this scope. Two of them would page the same "
                            + "source twice and race on the crosswalk.")
                            .formatted(live.get().id(), runType));
        }

        // Where the last interrupted run of this type got to. Inheriting the
        // watermark is what makes the migration survive a worker dying overnight:
        // the new run pages on from there rather than re-reading five years of
        // history to reach the same place.
        //
        // The counters are deliberately not inherited. Each run counts the rows it
        // processed, and a reconciliation rule sums the runs; copying the dead
        // run's totals into its successor would count its work twice in every sum.
        Optional<MigrationRunStore.Resumption> resumption =
                runs.findResumption(tenantId, scopeId, runType);

        Instant now = clock.instant();
        RunRow run = new RunRow(UUID.randomUUID(), tenantId, scopeId, runType, RunStatus.RUNNING,
                resumption.map(MigrationRunStore.Resumption::sourceWatermark).orElse(null),
                resumption.map(MigrationRunStore.Resumption::targetWatermark).orElse(null),
                resumption.map(MigrationRunStore.Resumption::checkpoint).orElse(Map.of()),
                command.transformationVersion(), Counters.NONE, null,
                requireText(command.startedBy(), "A run records who started it"), key, 1, now, null);

        runs.insert(run);
        audit.record("migration.run.started", ActorRef.user(actor, null),
                MigrationAudit.scopeOf(scope.tenantId(), scope.brandId(), scope.locationId()),
                "migration.run", run.id(), run.version(), command.reason(),
                Map.of("scopeId", scopeId,
                        "capability", scope.capability().name(),
                        "runType", runType.name(),
                        "transformationVersion", run.transformationVersion(),
                        "resumedFromWatermark", run.sourceWatermark() == null
                                ? "start" : run.sourceWatermark()),
                null);

        log.info("Opened {} run {} over scope {} from watermark {}", runType, run.id(), scopeId,
                run.sourceWatermark());
        return run;
    }

    /**
     * Records a page of work and advances the watermark.
     *
     * <p>No capability check, and no audit fact. A checkpoint is a worker
     * reporting progress on a run an operator already authorised; it cannot move a
     * scope, cannot change who writes anything, and happens thousands of times per
     * run. Auditing it would bury the transitions ADR 0027 is kept for under
     * telemetry, and requiring an operator's capability would mean the migration
     * only ran while somebody was logged in.
     *
     * <p>The counters are the run's running totals, so a retried page writes the
     * numbers that are already there and the row does not move. That is the whole
     * of the replay protection, and it is why the totals are the worker's to keep
     * rather than the control plane's to add up.
     */
    @Transactional
    public RunRow checkpoint(UUID tenantId, UUID runId, CheckpointCommand command) {
        Objects.requireNonNull(command, "A checkpoint command is required");

        RunRow run = requireRun(tenantId, runId);
        if (run.status().terminal()) {
            throw new MigrationConflictException(
                    ("Run %s finished at %s and is evidence, not state. A correction is a "
                            + "remediation run.").formatted(runId, run.finishedAt()));
        }

        boolean applied = runs.checkpoint(tenantId, runId, command.sourceWatermark(),
                command.targetWatermark(),
                command.checkpoint() == null ? Map.of() : command.checkpoint(),
                command.totals() == null ? Counters.NONE : command.totals());

        if (!applied) {
            // The only way the statement matches nothing is the run having been
            // settled between the read above and it, which a supervisor cancelling
            // a runaway catch-up does routinely. The worker is told to stop rather
            // than being left retrying against a frozen row.
            RunRow settled = requireRun(tenantId, runId);
            log.info("Checkpoint of run {} was refused; it is {}", runId, settled.status());
            throw new MigrationConflictException(
                    "Run %s ended %s while it was being checkpointed".formatted(runId, settled.status()));
        }
        return requireRun(tenantId, runId);
    }

    /**
     * Reads back where an interrupted run left off.
     *
     * <p>What a worker calls when it comes back to a run that is still {@code
     * RUNNING} because the process that owned it died without finishing it. It
     * gets the watermark to page on from and the counters as they stand, and the
     * second half matters as much as the first: the totals it goes on to write are
     * absolute, so a worker that seeded its tally from zero would send the run's
     * counters backwards and be refused by {@code trg_runs_no_regression} — loudly,
     * which is the right way to discover it.
     */
    @Transactional(readOnly = true)
    public RunRow resume(UUID tenantId, UUID runId) {
        RunRow run = requireRun(tenantId, runId);
        if (run.status().terminal()) {
            throw new MigrationConflictException(
                    "Run %s ended %s; start a new run rather than resuming a finished one"
                            .formatted(runId, run.status()));
        }
        return run;
    }

    /**
     * Closes the run.
     *
     * <p>After this the row freezes: {@code trg_runs_no_regression} refuses every
     * further update, because the counters and the checksum are what a
     * reconciliation is compared against and evidence that can be edited after the
     * comparison proves nothing.
     */
    @Transactional
    public RunRow finish(UUID tenantId, UUID runId, FinishRunCommand command) {
        Objects.requireNonNull(command, "A finish run command is required");
        String actor = access.requireOperator();

        RunStatus terminal = Objects.requireNonNull(command.status(), "A terminal status is required");
        if (!terminal.terminal()) {
            throw new IllegalArgumentException("%s does not end a run".formatted(terminal));
        }

        RunRow run = requireRun(tenantId, runId);
        if (run.status().terminal()) {
            if (run.status() == terminal) {
                return run;
            }
            throw new MigrationConflictException(
                    "Run %s already ended %s".formatted(runId, run.status()));
        }
        if (run.version() != command.expectedVersion()) {
            throw MigrationConflictException.staleVersion("run", command.expectedVersion(), run.version());
        }

        String checksum = command.checksum();
        if (checksum != null) {
            if (terminal != RunStatus.COMPLETED) {
                throw new IllegalArgumentException(
                        ("A checksum states what a finished pass produced; a run that ended %s did "
                                + "not produce one.").formatted(terminal));
            }
            if (!CHECKSUM.matcher(checksum).matches()) {
                throw new IllegalArgumentException(
                        "A run checksum is a lowercase hex sha-256, so the reconciliation suite "
                                + "compares like with like");
            }
        }

        Instant now = clock.instant();
        int version = runs.finish(tenantId, runId, terminal, checksum, command.expectedVersion(), now)
                .orElseThrow(() -> MigrationConflictException.staleVersion(
                        "run", command.expectedVersion(), run.version()));

        ScopeRow scope = requireScope(tenantId, run.scopeId());
        audit.record("migration.run.finished", ActorRef.user(actor, null),
                MigrationAudit.scopeOf(scope.tenantId(), scope.brandId(), scope.locationId()),
                "migration.run", runId, version, command.reason(),
                Map.of("scopeId", run.scopeId(),
                        "runType", run.runType().name(),
                        "status", terminal.name(),
                        "sourceWatermark", run.sourceWatermark() == null ? "" : run.sourceWatermark(),
                        "scanned", run.counters().scanned(),
                        "created", run.counters().created(),
                        "updated", run.counters().updated(),
                        "skipped", run.counters().skipped(),
                        "quarantined", run.counters().quarantined()),
                null);
        return requireRun(tenantId, runId);
    }

    /**
     * Runs a page of import work with ADR 0024's external-effect suppression on.
     *
     * <p>This is the call site {@link ImportContext} exists for. What it is
     * <em>meant</em> to suppress, per ADR 0024, is the outbox publish, the
     * notification, the payment capture, the courier booking, the POS export, the
     * benefit consumption and the inventory movement. Importing five years of
     * completed orders through the ordering domain would otherwise send five years
     * of confirmations to real phone numbers and re-book couriers for deliveries
     * that arrived in 2021.
     *
     * <p><strong>Those adapters now consult it</strong>, which they did not when
     * this method was first written: {@code isImporting()} had exactly one
     * occurrence in the main sources, its own declaration, and this Javadoc said
     * so rather than promising a suppression that did not happen. The consumers
     * are enumerated as {@link uz.horecaos.platform.migration.api.ExternalEffect} and
     * asserted by {@code MigrationImportSuppressionTests}, so an effect that loses
     * its consumer fails a test instead of reaching a customer.
     *
     * <p>Each effect is either skipped or refused, and the two are not
     * interchangeable. An effect is skipped where not producing it leaves the
     * caller a truthful answer — no outbox row, no notification intent, no POS
     * export, no usage movement. It is refused where skipping would mean inventing
     * the result: a payment intent, a courier booking, an outbound POS call, or a
     * stock reservation whose fabricated id the next call would commit against.
     *
     * <p>Two of those placements matter more than they look. The outbox is
     * suppressed at the append rather than in the relay, and the notification at
     * intent creation rather than at delivery, because both of those run on
     * scheduler threads where this binding does not exist — a row that reached
     * them would be indistinguishable from a real one.
     *
     * <p>What it does <strong>not</strong> suppress is everything that decides
     * whether the row is fit to store: validation, domain invariants, optimistic
     * concurrency, and {@code AuditRecorder}. An import that skipped those would be
     * the ad hoc target SQL ADR 0024 rejected — bad rows arriving looking exactly
     * like good ones. A row that cannot pass validation belongs in quarantine, and
     * {@link QuarantineService} is where it goes.
     *
     * <p>The binding is confined to the calling thread and does not follow work
     * handed to an executor. An import port that fans its page out to a pool would
     * find the effects escaping rather than being suppressed, which is the unsafe
     * direction, so the work must be performed on the thread this method gives it.
     */
    public <T> T runAsImport(RunRow run, Supplier<T> work) {
        Objects.requireNonNull(run, "A run is required");
        Objects.requireNonNull(work, "Import work is required");

        if (!run.runType().writesTarget()) {
            throw new IllegalArgumentException(
                    ("A %s run writes nothing to the target, so there are no effects to suppress. "
                            + "Running it under the import flag would silence a real caller's "
                            + "notification if the flag ever leaked.").formatted(run.runType()));
        }
        if (run.status().terminal()) {
            throw new MigrationConflictException(
                    "Run %s ended %s and cannot import".formatted(run.id(), run.status()));
        }
        // The scope is re-read on every page, not trusted from when the run
        // started. A catch-up is the ordinary state of a scope on the way to
        // cutover — ADR 0024's runbook step 3 is "final incremental catch-up" —
        // so a run is almost always live at the moment ownership moves. Checking
        // only at start leaves the replicator mid-page when the write mode flips,
        // and its next page imports legacy rows into a capability the target now
        // owns: the request handlers and the replicator writing the same rows,
        // which is the two-authority state this module exists to prevent.
        //
        // One extra read per page, against an indexed primary key, on a path that
        // is already doing transformation and inserts. That is the cheapest place
        // in the system to buy this.
        requireScopeAdmits(requireScope(run.tenantId(), run.scopeId()), run.runType());
        return ImportContext.runAsImport(work);
    }

    @Transactional(readOnly = true)
    public RunRow get(UUID tenantId, UUID runId) {
        access.requireOperator();
        return requireRun(tenantId, runId);
    }

    /**
     * Whether the scope is in a position for this kind of run.
     *
     * <p>The write mode is the real check, and it is asked through {@code
     * importMayWrite()} rather than by naming states: the importer feeds a
     * follower, so it may write only where a follower exists. A scope whose target
     * has nothing filling it has nothing for a backfill to write into, and one
     * that has already cut over has no follower left — replaying legacy rows over
     * target-owned facts is the two-authority state this module exists to prevent.
     *
     * <p>Held scopes are refused for a different reason: somebody decided the
     * scope should not be moving, and a migrator that kept writing through a pause
     * would make the pause meaningless.
     *
     * <p>{@link ScopeState#ROLLING_BACK} is refused by name rather than by
     * predicate, because it is not a holding state — it has its own exit and its
     * own work to do — and because its permitted modes still include {@code
     * following}, so the write-mode check alone would admit a run. ADR 0024's
     * rollback procedure opens by stopping new target commands and draining
     * workers; a backfill started underneath that is writing into the very scope
     * whose ownership is being handed back.
     *
     * <p>Reconciliation runs are exempt from all of it. They write nothing, and
     * refusing to measure a scope because it is paused would remove the evidence
     * needed to decide whether to un-pause it.
     */
    private static void requireScopeAdmits(ScopeRow scope, RunType runType) {
        if (!runType.writesTarget()) {
            return;
        }
        if (scope.state().holding() || scope.state().terminal()
                || scope.state() == ScopeState.ROLLING_BACK) {
            throw new MigrationPreconditionException(
                    MigrationPreconditionException.SCOPE_NOT_READY_FOR_RUN,
                    ("Scope %s is %s. A %s run would keep writing the target through a state that "
                            + "exists to stop it.").formatted(scope.id(), scope.state(), runType));
        }
        if (!scope.modes().writeMode().importMayWrite()) {
            throw new MigrationPreconditionException(
                    MigrationPreconditionException.SCOPE_NOT_READY_FOR_RUN,
                    ("Scope %s is %s with write mode %s, so nothing is maintaining a target copy "
                            + "yet. Move it to BACKFILLING before a %s run.")
                            .formatted(scope.id(), scope.state(), scope.modes().writeMode(), runType));
        }
    }

    private RunRow requireRun(UUID tenantId, UUID runId) {
        return runs.findById(tenantId, runId)
                .orElseThrow(() -> new MigrationResourceNotFoundException(
                        "No migration run %s for this tenant".formatted(runId)));
    }

    private ScopeRow requireScope(UUID tenantId, UUID scopeId) {
        return scopes.findById(tenantId, scopeId)
                .orElseThrow(() -> new MigrationResourceNotFoundException(
                        "No migration scope %s for this tenant".formatted(scopeId)));
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.strip();
    }

    /**
     * @param transformationVersion the version of the transformation code this run
     *                              applies; a changed mapping is a new
     *                              {@code REMEDIATION} run rather than a silent
     *                              mixing of two semantics in one entity family
     */
    public record StartRunCommand(
            RunType runType,
            int transformationVersion,
            String startedBy,
            String reason,
            String idempotencyKey) { }

    /**
     * @param sourceWatermark how far into the source the run has committed, which
     *                        is where a successor picks up if this one is killed
     * @param totals          the run's running totals, never a page's increments;
     *                        a retried checkpoint restates them and changes nothing
     */
    public record CheckpointCommand(
            String sourceWatermark,
            String targetWatermark,
            Map<String, Object> checkpoint,
            Counters totals) { }

    /** @param checksum hex sha-256 of what the pass produced, and only on a completed run */
    public record FinishRunCommand(RunStatus status, String checksum, int expectedVersion,
            String reason) { }
}
