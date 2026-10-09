package uz.horecaos.platform.customers.api;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Where a page of a guest's history ends (ADR 0111 §2): the last entry shown, by <em>when and which</em>.
 *
 * <p>A card pages backwards through entries from several modules merged by time. A cursor that is an
 * instant alone ("older than this") silently drops every entry that shares the last one's instant and
 * did not fit on the page: SMS retries stamped in one batch, a campaign's receipts written together, two
 * calls logged for the same minute. The entry's own id breaks the tie, so the order is total and a page
 * boundary can fall between two entries of one instant without losing either: the next page is exactly
 * the entries that sort <em>after</em> this one in {@link #newestFirst()}.
 *
 * <p>Every {@link CustomerHistorySource} must answer in that order -- {@code ORDER BY <instant> DESC,
 * <id> DESC} -- and must use {@link #admits} (or the SQL row comparison it mirrors) to decide what is
 * older. Ids compare as unsigned 128-bit numbers, which is how PostgreSQL orders {@code uuid}; {@link
 * UUID#compareTo} compares the halves as signed longs and would order half of all ids differently from
 * the database.
 *
 * @param occurredAt  the last entry's instant
 * @param referenceId the last entry's id
 */
public record HistoryCursor(Instant occurredAt, UUID referenceId) {

    private static final UUID LOWEST = new UUID(0L, 0L);

    public HistoryCursor {
        Objects.requireNonNull(occurredAt, "An instant is required");
        Objects.requireNonNull(referenceId, "An id is required");
    }

    /**
     * Everything strictly older than an instant, ties included: what a caller that only has the
     * instant (an older client, a bare {@code before}) asks for.
     */
    public static HistoryCursor olderThan(Instant instant) {
        return new HistoryCursor(instant, LOWEST);
    }

    /** The cursor that continues after {@code last}, the final entry of a page. */
    public static HistoryCursor after(CustomerHistoryEntry last) {
        return new HistoryCursor(last.occurredAt(), last.referenceId() == null ? LOWEST : last.referenceId());
    }

    /** Whether an entry stamped {@code instant} with {@code id} lies after this cursor, in newest-first order. */
    public boolean admits(Instant instant, @Nullable UUID id) {
        int byInstant = instant.compareTo(occurredAt);
        if (byInstant != 0) {
            return byInstant < 0;
        }
        return compareIds(id == null ? LOWEST : id, referenceId) < 0;
    }

    /** Newest first, and within one instant by id descending: the order every source sorts by. */
    public static Comparator<CustomerHistoryEntry> newestFirst() {
        return Comparator.comparing(CustomerHistoryEntry::occurredAt)
                .thenComparing(
                        (CustomerHistoryEntry entry) -> entry.referenceId() == null ? LOWEST : entry.referenceId(),
                        HistoryCursor::compareIds)
                .reversed();
    }

    /** Unsigned, as PostgreSQL orders {@code uuid}. */
    public static int compareIds(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
}
