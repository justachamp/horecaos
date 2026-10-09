package uz.horecaos.platform.marketing.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The anonymous click series of a brand's attribution links, for the acquisition-source chart
 * (ADR 0167 Decision 4, tier 1; row 7.6c).
 *
 * <p>Implemented by {@code marketing}, read by {@code reporting} (ADR 0023: a report reads a
 * module through its port and never its tables). A click is a count and not an event (ADR 0044):
 * the series is a number per link per day, and nothing in it can be joined to a person, a session
 * or a device. The link's label is the tenant's own name for it ("Instagram: Aziza"), which is why
 * it travels here and the token does not.
 *
 * <p>Also the retention seam. The series is kept for the same period as the funnel (Decision 7),
 * and the job that enforces that period lives in {@code reporting}; the delete is marketing's
 * because the table is.
 */
public interface AttributionClickSeries {

    /**
     * Clicks per link in {@code [from, to]} (inclusive), links with none omitted, for one brand or,
     * when {@code brandId} is null, for every brand of the tenant.
     */
    List<LinkClicks> clicksBetween(UUID tenantId, @Nullable UUID brandId, LocalDate from, LocalDate to);

    /** How many series rows are older than {@code cutoff} (exclusive); the report-only count. */
    long countOlderThan(LocalDate cutoff);

    /** Deletes series rows older than {@code cutoff} (exclusive) and returns how many it deleted. */
    int deleteOlderThan(LocalDate cutoff);

    record LinkClicks(UUID linkId, UUID brandId, String label, String channel, String status, long clicks) {}
}
