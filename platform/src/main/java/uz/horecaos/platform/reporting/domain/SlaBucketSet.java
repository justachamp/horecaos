package uz.horecaos.platform.reporting.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The platform-fixed elapsed-time buckets (ADR 0043).
 *
 * <p>Six half-open intervals in minutes: [0,30) [30,35) [35,40) [40,50) [50,60)
 * [60,infinity). They are exhaustive and non-overlapping, so the shares sum to
 * the whole. The competitor's documented branch buckets are «до 30, до 35, 30–40,
 * 40–50, 35–60, свыше 60» — two adjacent columns count the same order twice, and
 * the percentages cannot add up to anything.
 *
 * <p>Not a tenant setting, and that is the point. Raw {@code seconds_total} is
 * stored on every fact so a {@code v2} set can be cut retroactively over history,
 * which is precisely why the buckets themselves are fixed: a tenant-editable
 * bucket rewrites the meaning of every chart already drawn, including last
 * quarter's, and nothing anywhere records that it happened. Fixed buckets will
 * not fit a tenant whose promise is 45 minutes, and the answer to that is a
 * release rather than a setting.
 */
public final class SlaBucketSet {

    public static final int VERSION = 1;

    /**
     * One bucket.
     *
     * @param code             stable, so a stored aggregate row survives a label
     *                         change
     * @param fromMinutes      inclusive
     * @param toMinutesExclusive exclusive; null on the open-ended last bucket
     */
    public record Bucket(
            String code, int fromMinutes, @Nullable Integer toMinutesExclusive) {

        boolean contains(long seconds) {
            long minutes = seconds / 60;
            return minutes >= fromMinutes && (toMinutesExclusive == null || minutes < toMinutesExclusive);
        }
    }

    private static final List<Bucket> V1 = List.of(
            new Bucket("UNDER_30", 0, 30),
            new Bucket("M30_35", 30, 35),
            new Bucket("M35_40", 35, 40),
            new Bucket("M40_50", 40, 50),
            new Bucket("M50_60", 50, 60),
            new Bucket("OVER_60", 60, null));

    private SlaBucketSet() {}

    public static List<Bucket> buckets() {
        return V1;
    }

    /**
     * The bucket an elapsed time falls in.
     *
     * <p>Never empty: the set is exhaustive by construction, so a caller does not
     * have to invent a seventh bucket for the orders that fell through, which is
     * the shape that silently drops rows out of a distribution.
     */
    public static Bucket bucketFor(long elapsedSeconds) {
        if (elapsedSeconds < 0) {
            throw new IllegalArgumentException("An order cannot close before it opened: " + elapsedSeconds + "s");
        }
        return V1.stream()
                .filter(bucket -> bucket.contains(elapsedSeconds))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException("The bucket set is no longer exhaustive at " + elapsedSeconds + "s"));
    }
}
