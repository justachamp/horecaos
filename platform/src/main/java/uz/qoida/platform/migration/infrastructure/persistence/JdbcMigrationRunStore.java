package uz.qoida.platform.migration.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.ObjectMapper;

import uz.qoida.platform.migration.application.MigrationRunStore;
import uz.qoida.platform.migration.domain.RunStatus;
import uz.qoida.platform.migration.domain.RunType;

import static uz.qoida.platform.migration.infrastructure.persistence.MigrationColumns.documentJson;
import static uz.qoida.platform.migration.infrastructure.persistence.MigrationColumns.documentOrEmpty;
import static uz.qoida.platform.migration.infrastructure.persistence.MigrationColumns.instantOrNull;
import static uz.qoida.platform.migration.infrastructure.persistence.MigrationColumns.utc;

/**
 * Migration run persistence (ADR 0024).
 *
 * <p>Runs are restartable, and that shapes every statement here. A killed worker
 * reads its watermarks and checkpoint back to learn where it got to, and
 * {@link #checkpoint} moves the watermark and the counters in one statement, so a
 * process killed between them cannot exist. A checkpoint claiming a watermark the
 * counters do not support is a run that reconciles against arithmetic nobody
 * performed.
 *
 * <p>Every write also names {@code status = 'RUNNING'} in the {@code WHERE}. The
 * schema freezes finished runs with a trigger that raises, so without that
 * predicate a duplicate finish would surface as a driver exception rather than as
 * "you lost, somebody else settled this run".
 */
@Repository
public class JdbcMigrationRunStore implements MigrationRunStore {

    private static final String SELECT_RUN = """
            SELECT id, tenant_id, scope_id, run_type, status, source_watermark, target_watermark,
                   checkpoint, transformation_version, scanned_count, created_count, updated_count,
                   skipped_count, quarantined_count, checksum, started_by, idempotency_key,
                   version, started_at, finished_at
            FROM migration.runs""";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcMigrationRunStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<RunRow> findById(UUID tenantId, UUID runId) {
        return jdbc.sql(SELECT_RUN + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId).param("id", runId)
                .query(this::mapRun)
                .optional();
    }

    @Override
    public Optional<RunRow> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return jdbc.sql(SELECT_RUN + " WHERE tenant_id = :tenantId AND idempotency_key = :key")
                .param("tenantId", tenantId).param("key", idempotencyKey)
                .query(this::mapRun)
                .optional();
    }

    /** The live run of one type over one scope, which the schema allows at most one of. */
    @Override
    public Optional<RunRow> findActive(UUID tenantId, UUID scopeId, RunType runType) {
        return jdbc.sql(SELECT_RUN + """
                 WHERE tenant_id = :tenantId AND scope_id = :scopeId
                   AND run_type = :runType AND status = 'RUNNING'
                """)
                .param("tenantId", tenantId).param("scopeId", scopeId)
                .param("runType", runType.name())
                .query(this::mapRun)
                .optional();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The most recent finished run of this type on this scope is selected
     * first and its status is filtered afterwards, not the other way round. Asking
     * directly for the most recent {@code FAILED} run would happily skip over a
     * {@code COMPLETED} one that came after it, and resume a finished backfill
     * from the watermark of the attempt that failed before it — re-importing
     * everything between the two.
     */
    @Override
    public Optional<Resumption> findResumption(UUID tenantId, UUID scopeId, RunType runType) {
        return jdbc.sql("""
                SELECT status, source_watermark, target_watermark, checkpoint
                FROM migration.runs
                WHERE tenant_id = :tenantId AND scope_id = :scopeId
                  AND run_type = :runType AND status <> 'RUNNING'
                ORDER BY finished_at DESC, id DESC
                LIMIT 1
                """)
                .param("tenantId", tenantId).param("scopeId", scopeId)
                .param("runType", runType.name())
                .query((row, number) -> new LastFinishedRun(
                        RunStatus.valueOf(row.getString("status")),
                        new Resumption(
                                row.getString("source_watermark"),
                                row.getString("target_watermark"),
                                documentOrEmpty(objectMapper, row, "checkpoint"))))
                .optional()
                .filter(last -> last.status() != RunStatus.COMPLETED)
                .map(LastFinishedRun::resumption);
    }

    /**
     * The last run of a type to stop, and where it stopped.
     *
     * <p>The status travels with the watermarks so the "was it completed" decision
     * happens in Java over the one row the query already found, rather than in a
     * {@code WHERE} clause that would have looked past it.
     */
    private record LastFinishedRun(RunStatus status, Resumption resumption) { }

    /**
     * {@inheritDoc}
     *
     * <p>Two uniqueness rules do the work that a service check could not.
     * {@code uq_run_idempotency} makes a retried "start backfill" collide with the
     * run it already started rather than doubling every counter; the caller
     * catches the collision and reads the existing run back through
     * {@link #findByIdempotencyKey}. {@code ux_run_active_per_scope} makes a
     * second live run of one type over one scope impossible, so two workers cannot
     * page the same source concurrently and race on the crosswalk.
     */
    @Override
    public void insert(RunRow run) {
        jdbc.sql("""
                INSERT INTO migration.runs (
                    id, tenant_id, scope_id, run_type, status, source_watermark, target_watermark,
                    checkpoint, transformation_version, scanned_count, created_count, updated_count,
                    skipped_count, quarantined_count, checksum, started_by, idempotency_key,
                    version, started_at, finished_at)
                VALUES (
                    :id, :tenantId, :scopeId, :runType, :status, :sourceWatermark, :targetWatermark,
                    CAST(:checkpoint AS jsonb), :transformationVersion, :scanned, :created, :updated,
                    :skipped, :quarantined, :checksum, :startedBy, :idempotencyKey,
                    :version, :startedAt, :finishedAt)
                """)
                .param("id", run.id()).param("tenantId", run.tenantId())
                .param("scopeId", run.scopeId()).param("runType", run.runType().name())
                .param("status", run.status().name())
                .params(watermarks(run.sourceWatermark(), run.targetWatermark()))
                .param("checkpoint", documentJson(objectMapper, run.checkpoint()))
                .param("transformationVersion", run.transformationVersion())
                .params(countersOf(run.counters()))
                .param("checksum", run.checksum())
                .param("startedBy", run.startedBy())
                .param("idempotencyKey", run.idempotencyKey())
                .param("version", run.version())
                .param("startedAt", utc(run.startedAt()))
                .param("finishedAt", utc(run.finishedAt()))
                .update();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The watermark, the in-page checkpoint and all five counters in one
     * statement. Split across two, a worker killed in between would leave a
     * watermark asserting that a page had been processed and counters proving it
     * had not, and the reconciliation that follows would be arithmetic about a run
     * that never happened.
     *
     * <p>Not conditional on a version, unlike every other write in this package.
     * A checkpoint is a worker restating totals it owns, thousands of times per
     * run, and the quarantine counter is incremented underneath it by the control
     * plane — so an optimistic check here would fail on the ordinary interleaving
     * rather than on a conflict. What it is conditional on is the run still
     * running, which is the only thing a checkpoint must not do to a settled run.
     */
    @Override
    public boolean checkpoint(UUID tenantId, UUID runId, String sourceWatermark,
            String targetWatermark, Map<String, Object> checkpoint, Counters totals) {
        return jdbc.sql("""
                UPDATE migration.runs
                SET source_watermark = :sourceWatermark,
                    target_watermark = :targetWatermark,
                    checkpoint = CAST(:checkpoint AS jsonb),
                    scanned_count = :scanned,
                    created_count = :created,
                    updated_count = :updated,
                    skipped_count = :skipped,
                    quarantined_count = :quarantined,
                    version = version + 1
                WHERE tenant_id = :tenantId AND id = :id AND status = 'RUNNING'
                """)
                .param("tenantId", tenantId).param("id", runId)
                .params(watermarks(sourceWatermark, targetWatermark))
                .param("checkpoint", documentJson(objectMapper, checkpoint))
                .params(countersOf(totals))
                .update() == 1;
    }

    /**
     * {@inheritDoc}
     *
     * <p>An increment and not a total, deliberately, and the one statement in this
     * store that reads the column it writes. It interleaves with the worker's
     * absolute checkpoints, and an increment is the form that stays correct when
     * it does.
     */
    @Override
    public boolean addQuarantined(UUID tenantId, UUID runId, long delta) {
        return jdbc.sql("""
                UPDATE migration.runs
                SET quarantined_count = quarantined_count + :delta,
                    version = version + 1
                WHERE tenant_id = :tenantId AND id = :id AND status = 'RUNNING'
                """)
                .param("tenantId", tenantId).param("id", runId).param("delta", delta)
                .update() == 1;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Writes the status, the checksum and the finish time and nothing else: the
     * watermarks and totals are already whatever the worker's last checkpoint left
     * there. Restating them here would let a settling caller that had not been
     * checkpointing overwrite the run's totals with its own idea of them, and
     * {@code trg_runs_no_regression} would then reject the settle rather than the
     * bad number.
     *
     * <p>Conditional on the version as well as on the status, because this one is
     * an operator's decision rather than a worker's report, and two of them
     * arriving at once must resolve to one winner.
     */
    @Override
    public Optional<Integer> finish(UUID tenantId, UUID runId, RunStatus terminal, String checksum,
            int expectedVersion, Instant finishedAt) {
        return jdbc.sql("""
                UPDATE migration.runs
                SET status = :status,
                    checksum = :checksum,
                    version = version + 1,
                    finished_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND status = 'RUNNING'
                  AND version = :expectedVersion
                RETURNING version
                """)
                .param("tenantId", tenantId).param("id", runId)
                .param("status", terminal.name())
                .param("checksum", checksum)
                .param("expectedVersion", expectedVersion)
                .param("now", utc(finishedAt))
                .query(Integer.class)
                .optional();
    }

    /**
     * A watermark is opaque and may legitimately be absent — a backfill that has
     * not yet read a page has none — so a HashMap rather than {@code Map.of}.
     */
    private static Map<String, Object> watermarks(String source, String target) {
        Map<String, Object> params = new HashMap<>();
        params.put("sourceWatermark", source);
        params.put("targetWatermark", target);
        return params;
    }

    /**
     * Binds the five counters by name.
     *
     * <p>Bound as one group rather than five adjacent {@code param} calls, because
     * five longs in a row is exactly the shape that transposes without anyone
     * noticing until a reconciliation compares the wrong two numbers.
     */
    private static Map<String, Object> countersOf(Counters counters) {
        return Map.of(
                "scanned", counters.scanned(),
                "created", counters.created(),
                "updated", counters.updated(),
                "skipped", counters.skipped(),
                "quarantined", counters.quarantined());
    }

    private RunRow mapRun(ResultSet row, int number) throws SQLException {
        return new RunRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("scope_id", UUID.class),
                RunType.valueOf(row.getString("run_type")),
                RunStatus.valueOf(row.getString("status")),
                row.getString("source_watermark"),
                row.getString("target_watermark"),
                documentOrEmpty(objectMapper, row, "checkpoint"),
                row.getInt("transformation_version"),
                new Counters(
                        row.getLong("scanned_count"),
                        row.getLong("created_count"),
                        row.getLong("updated_count"),
                        row.getLong("skipped_count"),
                        row.getLong("quarantined_count")),
                row.getString("checksum"),
                row.getString("started_by"),
                row.getString("idempotency_key"),
                row.getInt("version"),
                instantOrNull(row, "started_at"),
                instantOrNull(row, "finished_at"));
    }
}
