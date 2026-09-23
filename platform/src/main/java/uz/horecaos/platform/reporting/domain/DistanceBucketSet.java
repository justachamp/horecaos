package uz.horecaos.platform.reporting.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The platform-fixed delivery-distance buckets (row 7.10b, wave 10 w5-reports-exports).
 *
 * <p>Six half-open intervals in meters: [0,1000) [1000,2000) [2000,3000) [3000,5000)
 * [5000,8000) [8000,infinity). Exhaustive and non-overlapping, on the same footing
 * {@link SlaBucketSet}'s own doc argues for the elapsed-time buckets: not a tenant
 * setting, because an editable boundary rewrites the meaning of every chart already
 * drawn, including last quarter's, with nothing anywhere recording that it happened.
 *
 * <p>Unlike {@link SlaBucketSet}, this bucket count is computed live over {@code
 * reporting.fact_delivery.distance_meters} on every read (see {@code
 * JdbcReportingStore#readDistanceBuckets}) rather than through a nightly {@code
 * DayAggregator} pass into its own {@code agg_*_day} table — row 7.10b's own brief
 * asks for a read "over reporting.fact_delivery", and the geography page's histogram
 * section already reads a fixed, single window (30 days, one branch) cheap enough
 * that a live GROUP BY needs no pre-aggregation the way a whole-tenant day-grain
 * report does.
 */
public final class DistanceBucketSet {

    public static final int VERSION = 1;

    /**
     * One bucket.
     *
     * @param code            stable — this is what a chart's x-axis and any stored
     *                        reference to a bucket keys on, never the label
     * @param fromMeters      inclusive
     * @param toMetersExclusive exclusive; null on the open-ended last bucket
     */
    public record Bucket(
            String code, int fromMeters, @Nullable Integer toMetersExclusive) {}

    private static final List<Bucket> V1 = List.of(
            new Bucket("UNDER_1KM", 0, 1_000),
            new Bucket("KM1_2", 1_000, 2_000),
            new Bucket("KM2_3", 2_000, 3_000),
            new Bucket("KM3_5", 3_000, 5_000),
            new Bucket("KM5_8", 5_000, 8_000),
            new Bucket("OVER_8KM", 8_000, null));

    private DistanceBucketSet() {}

    public static List<Bucket> buckets() {
        return V1;
    }

    /** The bucket codes in their fixed display order — for zero-filling a read that found no deliveries in some bucket. */
    public static List<String> codes() {
        return V1.stream().map(Bucket::code).toList();
    }
}
