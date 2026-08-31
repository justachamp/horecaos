package uz.horecaos.platform.migration.web;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.protection.Classified;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.migration.application.MigrationRunStore.Counters;
import uz.horecaos.platform.migration.application.MigrationRunStore.RunRow;
import uz.horecaos.platform.migration.domain.RunStatus;
import uz.horecaos.platform.migration.domain.RunType;

/**
 * One migration run as the control-plane console sees it.
 *
 * <p>The watermarks are published as the opaque strings they are. They are
 * meaningful only to the migrator that wrote them — a primary key, a change
 * sequence, or a timestamp, depending on the source — and rendering one as an
 * instant would invite a console to sort by it and be wrong for every source
 * whose watermark is a key.
 *
 * <p>The run's checkpoint is not published, for the same reason the scope's is
 * not: it is a worker's private note about where inside a page it was, with no
 * fixed keys, and ADR 0031 admits no unbounded free-form map into a response
 * contract.
 *
 * @param counters totals for this run alone. A resumed run counts only the rows
 *                 it processes, so summing runs is how a reconciliation rule
 *                 reaches a scope's total and no run's figure is a running sum
 * @param checksum hex sha-256 of what a completed pass produced, and null until
 *                 it completes
 */
public record RunView(
        UUID id,
        UUID scopeId,
        RunType runType,
        RunStatus status,
        @Nullable String sourceWatermark,
        @Nullable String targetWatermark,
        int transformationVersion,
        RunCountersView counters,
        @Nullable String checksum,
        String startedBy,
        int version,
        Instant startedAt,
        @Nullable Instant finishedAt) {

    /**
     * The five dispositions a run counts.
     *
     * @param quarantined declared because the ADR 0029 name heuristic reads
     *                    "quaran<em>tin</em>ed" as a taxpayer identification
     *                    number. It is a count of rows a run could not place. The
     *                    heuristic is deliberately a substring match and
     *                    deliberately over-fires; a declaration is how a false
     *                    positive is answered, and it matters here because this
     *                    run view is returned by a platform-scoped endpoint that
     *                    has no tenant, and therefore no per-tenant key its
     *                    idempotency record could be encrypted under.
     */
    public record RunCountersView(
            long scanned,
            long created,
            long updated,
            long skipped,

            @Classified(value = DataClass.INTERNAL, reason = "a count of rows, not a tax number")
            long quarantined) {

        static RunCountersView of(Counters counters) {
            return new RunCountersView(
                    counters.scanned(),
                    counters.created(),
                    counters.updated(),
                    counters.skipped(),
                    counters.quarantined());
        }
    }

    static RunView of(RunRow row) {
        return new RunView(
                row.id(),
                row.scopeId(),
                row.runType(),
                row.status(),
                row.sourceWatermark(),
                row.targetWatermark(),
                row.transformationVersion(),
                RunCountersView.of(row.counters()),
                row.checksum(),
                row.startedBy(),
                row.version(),
                row.startedAt(),
                row.finishedAt());
    }
}
