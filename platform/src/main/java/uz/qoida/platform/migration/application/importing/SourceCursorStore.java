package uz.qoida.platform.migration.application.importing;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Where extraction of one entity type in one scope got to
 * ({@code migration.source_cursors}, ADR 0024).
 *
 * <p>Separate from the run's checkpoint, and the distinction is what makes a
 * restart correct. A run is one execution; the position in the source outlives
 * every execution. A cursor kept on the run would leave a killed backfill's
 * successor either re-reading from the beginning or guessing which earlier run to
 * inherit a checkpoint from.
 *
 * <p>{@link #advance} is called inside the same transaction as the target writes
 * of the page it covers. The control plane and the target are two schemas of one
 * PostgreSQL, so ADR 0024's "checkpoint only after a target commit" is available
 * in its strongest form.
 */
public interface SourceCursorStore {

    Optional<Cursor> find(UUID tenantId, UUID scopeId, String entityType);

    /**
     * Creates the cursor for an entity type nobody has read yet.
     *
     * @return false when a concurrent starter won, in which case the caller reads
     *         the existing cursor rather than treating it as an error
     */
    boolean open(Cursor cursor, Instant now);

    /**
     * Moves the position forward, conditionally on the version read with it.
     *
     * <p>Optimistic rather than a lock, and the version is what stops a page
     * retried after a lost commit from advancing the cursor twice. It also stops
     * the case that matters more: two migrators paging one entity type against
     * different bounds, each believing it covered the gap the other left.
     *
     * @return false when the version was stale, which means somebody else moved
     *         this cursor and this page's work must be rolled back rather than
     *         merged
     */
    boolean advance(UUID tenantId, UUID scopeId, String entityType, Advance advance,
            int expectedVersion, Instant now);

    /**
     * @param lastStableKey exclusive lower bound for the next page, or null when
     *                      nothing has been read
     * @param watermark     the change position a catch-up resumes from, paired
     *                      with its column: the schema states pair completeness as
     *                      an equality, so one without the other does not exist
     * @param exhausted     the source had no rows past the key. Recorded rather
     *                      than re-derived, because "the last page was empty" and
     *                      "nobody has read this yet" are both an absent next key
     */
    record Cursor(
            UUID id,
            UUID tenantId,
            UUID scopeId,
            String entityType,
            String stableKeyColumn,
            String lastStableKey,
            String watermark,
            String watermarkColumn,
            UUID advancedByRunId,
            int transformationVersion,
            long pagesCommitted,
            long rowsCommitted,
            boolean exhausted,
            int version) { }

    /**
     * One page's worth of movement.
     *
     * <p>{@code rowsCommitted} and {@code pagesCommitted} are absolute totals and
     * not deltas, for the reason {@code MigrationRunStore.Counters} gives: the two
     * readings are indistinguishable at a call site, and writing the same total
     * twice is safe where adding the same delta twice overstates the run by
     * exactly the page that was retried.
     */
    record Advance(
            String lastStableKey,
            String watermark,
            UUID advancedByRunId,
            int transformationVersion,
            long pagesCommitted,
            long rowsCommitted,
            boolean exhausted) { }
}
