package uz.horecaos.platform.migration.application.importing;

import uz.horecaos.platform.migration.api.ExtractionSpec;

/**
 * Read-only, paged access to the legacy database (ADR 0024, step 3).
 *
 * <p>Two methods, and the split between them is the difference between a backfill
 * and a catch-up. A backfill asks "which rows have I not seen", ordered by a key
 * nothing renumbers; a catch-up asks "what changed since", ordered by a change
 * column. They are separate cursors on one entity type for that reason, and a
 * finished backfill leaves an exhausted key cursor beside a live watermark.
 *
 * <p>An implementation must never write. ADR 0024 requires read-only source
 * access, and the enforcement belongs at the database role rather than in this
 * interface's Javadoc — a migration user with no INSERT is what makes the
 * guarantee true when somebody adds a method here in two years.
 */
public interface LegacySourceReader {

    /**
     * The next page of rows after {@code afterKey}, in stable-key order.
     *
     * <p>Keyset, never an offset. The legacy database is serving traffic while
     * this runs, and an offset silently skips rows when the table shifts
     * underneath the reader — a row nobody then accounts for, which is exactly
     * the claim ADR 0024 exists to be able to make.
     *
     * @param afterKey exclusive lower bound, or null for the first page
     * @param limit    the page size; a short page means the source is exhausted
     */
    SourcePage readPage(ExtractionSpec spec, String afterKey, int limit);

    /**
     * The next page of rows changed at or after {@code watermark}.
     *
     * <p>Inclusive on the watermark and ordered by the change column and then the
     * stable key. Inclusive because the legacy change column has second
     * granularity at best and no ordering guarantee within a value, so an
     * exclusive bound drops every row that shares the last one's timestamp. The
     * cost is that the boundary rows are re-read on every catch-up, which is
     * exactly the case the crosswalk's upsert makes free.
     *
     * @throws IllegalArgumentException when the spec declares no watermark column
     */
    SourcePage readChanges(ExtractionSpec spec, String watermark, String afterKey, int limit);
}
