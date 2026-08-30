package uz.qoida.platform.migration.application;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import uz.qoida.platform.migration.domain.RunStatus;
import uz.qoida.platform.migration.domain.RunType;

/**
 * Reads and writes {@code migration.runs}.
 *
 * <p>The shape of this port is the restartability requirement. A worker is
 * killed mid-page all the time — a deploy, an OOM, a network partition — and
 * what it reads back on the way in is a watermark, what it writes on the way
 * through is a bounded advance of that watermark, and what it must never do is
 * restate a counter.
 */
public interface MigrationRunStore {

    Optional<RunRow> findById(UUID tenantId, UUID runId);

    /**
     * The run this key already started, per {@code uq_run_idempotency}.
     *
     * <p>A retried "start backfill" must join the run it already started. Two
     * backfills over one scope double every counter, and the reconciliation that
     * follows would then be arithmetic about a run that never happened.
     */
    Optional<RunRow> findByIdempotencyKey(UUID tenantId, String idempotencyKey);

    /** The live run of this type on this scope, per {@code ux_run_active_per_scope}. */
    Optional<RunRow> findActive(UUID tenantId, UUID scopeId, RunType runType);

    /**
     * Where a new run of this type should pick up from.
     *
     * <p>The watermarks and checkpoint of the most recent run of this type on
     * this scope that ended {@code FAILED} or {@code CANCELLED}, and empty when
     * the most recent ended {@code COMPLETED}. The distinction is the whole
     * contract: inheriting from a completed run would restart a finished backfill
     * at its end and scan nothing, and inheriting from a failed one is exactly
     * how the migration survives a worker dying at three in the morning.
     *
     * <p>Counters are deliberately not part of this record. A resumed run counts
     * only the rows it processes; copying the dead run's totals forward would
     * count the first pass twice in every sum a reconciliation rule takes.
     */
    Optional<Resumption> findResumption(UUID tenantId, UUID scopeId, RunType runType);

    void insert(RunRow run);

    /**
     * Advances the watermark and restates the run's totals.
     *
     * <p>{@code totals} are absolute, not increments, and that is what makes a
     * checkpoint safe to send twice: writing the same totals a second time leaves
     * the same row, where adding the same page's delta twice would overstate the
     * run by exactly the page that was retried — and a lost response on the last
     * checkpoint of a long backfill is the ordinary case, not the exotic one. The
     * opposite mistake is caught a layer down: {@code trg_runs_no_regression}
     * refuses any counter that moves backwards, so a resumed worker that reset its
     * tally to zero fails loudly instead of quietly understating the rows it
     * re-imported.
     *
     * <p>Which means the worker carries its own running tally, seeded from the
     * counters it read back when it resumed. {@link MigrationRunService#resume}
     * exists to hand it exactly that.
     *
     * @return false when the run was no longer {@code RUNNING}, which is the only
     *         way this statement matches nothing
     */
    boolean checkpoint(UUID tenantId, UUID runId, String sourceWatermark, String targetWatermark,
            Map<String, Object> checkpoint, Counters totals);

    /**
     * Advances the quarantined counter alone, as {@code quarantined_count =
     * quarantined_count + :delta}.
     *
     * <p>The one counter the control plane moves itself, and the one that cannot
     * be left to the worker's tally. A row is quarantined the moment it fails, not
     * at the end of the page, and the backlog it contributes to is what gates both
     * cutover and retirement — so a crash between the filing and the next
     * checkpoint must not lose it. Stated as an increment rather than a total
     * because it interleaves with the worker's absolute writes, and an increment
     * is the form that stays correct when it does.
     */
    boolean addQuarantined(UUID tenantId, UUID runId, long delta);

    /**
     * Finishes the run, conditionally on it still being {@code RUNNING} at
     * {@code expectedVersion}.
     *
     * <p>After this the row is frozen by {@code trg_runs_no_regression} and is
     * evidence rather than state: a later correction is a remediation run, not an
     * edit.
     *
     * @return the new version, or empty when it had already finished
     */
    Optional<Integer> finish(UUID tenantId, UUID runId, RunStatus terminal, String checksum,
            int expectedVersion, Instant finishedAt);

    /**
     * One restartable execution of one migrator over one scope.
     *
     * @param sourceWatermark opaque to the control plane and meaningful only to
     *                        the migrator that wrote it: a key, a change
     *                        sequence, or a timestamp, never ordered here
     * @param checksum        set when the run completes, hex sha-256, so the
     *                        reconciliation suite compares like with like
     */
    record RunRow(
            UUID id,
            UUID tenantId,
            UUID scopeId,
            RunType runType,
            RunStatus status,
            String sourceWatermark,
            String targetWatermark,
            Map<String, Object> checkpoint,
            int transformationVersion,
            Counters counters,
            String checksum,
            String startedBy,
            String idempotencyKey,
            int version,
            Instant startedAt,
            Instant finishedAt) { }

    /**
     * The five dispositions a run counts, always as running totals.
     *
     * <p>Never increments, anywhere. The two readings of these five numbers are
     * indistinguishable at a call site, and passing a page's delta where the run's
     * total was meant reads perfectly and understates every figure a
     * reconciliation later compares against — so there is one meaning and no
     * second type to confuse it with.
     *
     * <p>They deliberately do not have to sum: whether a quarantined row also
     * counted as scanned, and whether the migrator advances the scan before or
     * after processing a page, are the migrator's decisions. The relation between
     * them is a reconciliation rule, evaluated over a finished run and disagreed
     * with in evidence, rather than an invariant that aborts a checkpoint at three
     * in the morning.
     */
    record Counters(long scanned, long created, long updated, long skipped, long quarantined) {

        public static final Counters NONE = new Counters(0, 0, 0, 0, 0);
    }

    /** Where an interrupted run of this type left off. */
    record Resumption(String sourceWatermark, String targetWatermark,
            Map<String, Object> checkpoint) { }
}
