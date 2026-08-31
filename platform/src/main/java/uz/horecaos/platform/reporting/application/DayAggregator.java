package uz.horecaos.platform.reporting.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import uz.horecaos.platform.reporting.application.ReportingFacts.BranchDayAggregate;
import uz.horecaos.platform.reporting.application.ReportingFacts.BranchDayKey;
import uz.horecaos.platform.reporting.application.ReportingFacts.OrderFact;
import uz.horecaos.platform.reporting.application.ReportingFacts.RefundFact;
import uz.horecaos.platform.reporting.application.ReportingFacts.SlaBucketAggregate;
import uz.horecaos.platform.reporting.domain.SlaBucketSet;

/**
 * Turns a day's facts into the day's aggregates (ADR 0043).
 *
 * <p>A pure function of its inputs, with no database and no clock, for one
 * reason: the close writes the result and the settle recut compares against it,
 * and if those two ran different arithmetic every recut would raise a divergence
 * caused by the reporting code rather than by the data. Sharing one function is
 * what makes "recomputing a closed business day from the same facts reproduces
 * byte-identical aggregates" a property rather than a hope.
 */
public final class DayAggregator {

    private DayAggregator() {}

    /**
     * The branch-day rows for one business date.
     *
     * <p>Refunds are folded in by their own date, so a refund issued today lands
     * on today's row even though the order it reverses closed three days ago.
     * Orders and refunds are grouped on the same key, which is why the refund
     * fact carries the order's channel and fulfilment type: without them a day's
     * refunds would belong to no branch row at all.
     */
    public static List<BranchDayAggregate> branchDay(
            LocalDate businessDate,
            List<OrderFact> orders,
            List<RefundFact> refunds,
            int boundaryVersion,
            int calculationVersion) {

        Map<BranchDayKey, Accumulator> byKey = new LinkedHashMap<>();

        for (OrderFact order : orders) {
            byKey.computeIfAbsent(keyOf(order), ignored -> new Accumulator()).add(order);
        }
        for (RefundFact refund : refunds) {
            byKey.computeIfAbsent(keyOf(refund), ignored -> new Accumulator()).add(refund);
        }

        List<BranchDayAggregate> rows = new ArrayList<>(byKey.size());
        byKey.forEach((key, accumulated) -> rows.add(accumulated.toRow(key, boundaryVersion, calculationVersion)));

        // Ordered so two runs over the same facts produce the same sequence of
        // rows. A stable order is what makes a diff between a close and a recut
        // readable by a human rather than a set comparison.
        rows.sort(Comparator.comparing(
                        (BranchDayAggregate row) -> row.key().locationId().toString())
                .thenComparing(row -> row.key().channelCode())
                .thenComparing(row -> row.key().fulfilmentType())
                .thenComparing(row -> String.valueOf(row.key().legalEntityId())));
        return rows;
    }

    /**
     * The fixed SLA distribution per location for one business date.
     *
     * <p>Only orders that closed appear: an order still open has no elapsed time,
     * and counting it in the fastest bucket is the wrong answer twice over.
     */
    public static List<SlaBucketAggregate> slaBuckets(UUID tenantId, LocalDate businessDate, List<OrderFact> orders) {

        Map<UUID, Map<String, Integer>> counts = new LinkedHashMap<>();
        for (OrderFact order : orders) {
            if (order.secondsTotal() == null) {
                continue;
            }
            counts.computeIfAbsent(order.locationId(), ignored -> new LinkedHashMap<>())
                    .merge(SlaBucketSet.bucketFor(order.secondsTotal()).code(), 1, Integer::sum);
        }

        List<SlaBucketAggregate> rows = new ArrayList<>();
        counts.forEach((locationId, byBucket) -> {
            int total = byBucket.values().stream().mapToInt(Integer::intValue).sum();
            List<Integer> ordered = SlaBucketSet.buckets().stream()
                    .map(bucket -> byBucket.getOrDefault(bucket.code(), 0))
                    .toList();
            List<Integer> shares = sharesInBasisPoints(ordered, total);

            for (int index = 0; index < SlaBucketSet.buckets().size(); index++) {
                if (ordered.get(index) == 0) {
                    // An empty bucket is not stored. A zero row per bucket per
                    // branch per day triples the table for no information, and a
                    // missing bucket already reads as zero on every surface.
                    continue;
                }
                rows.add(new SlaBucketAggregate(
                        tenantId,
                        businessDate,
                        "LOCATION",
                        locationId,
                        SlaBucketSet.VERSION,
                        SlaBucketSet.buckets().get(index).code(),
                        ordered.get(index),
                        shares.get(index)));
            }
        });
        return rows;
    }

    /**
     * Shares that actually sum to the whole.
     *
     * <p>Largest remainder, not plain truncation. Six truncated shares can leave
     * five basis points unallocated, and a distribution chart whose columns
     * visibly fail to add up costs more trust than the rounding error is worth.
     */
    private static List<Integer> sharesInBasisPoints(List<Integer> counts, int total) {
        if (total == 0) {
            return counts.stream().map(ignored -> 0).toList();
        }
        int[] shares = new int[counts.size()];
        long[] remainders = new long[counts.size()];
        int allocated = 0;

        for (int index = 0; index < counts.size(); index++) {
            long scaled = (long) counts.get(index) * 10_000L;
            shares[index] = (int) (scaled / total);
            remainders[index] = scaled % total;
            allocated += shares[index];
        }
        List<Integer> order = new ArrayList<>();
        for (int index = 0; index < counts.size(); index++) {
            order.add(index);
        }
        order.sort(
                Comparator.comparingLong((Integer index) -> remainders[index]).reversed());

        for (int position = 0; allocated < 10_000; position++, allocated++) {
            shares[order.get(position % order.size())]++;
        }
        List<Integer> result = new ArrayList<>(shares.length);
        for (int share : shares) {
            result.add(share);
        }
        return result;
    }

    private static BranchDayKey keyOf(OrderFact order) {
        return new BranchDayKey(
                order.tenantId(),
                order.businessDate(),
                order.locationId(),
                order.legalEntityId(),
                order.channelCode(),
                order.fulfilmentType());
    }

    private static BranchDayKey keyOf(RefundFact refund) {
        return new BranchDayKey(
                refund.tenantId(),
                refund.businessDate(),
                refund.locationId(),
                refund.legalEntityId(),
                refund.channelCode(),
                refund.fulfilmentType());
    }

    /** Mutable only inside this call; nothing here escapes. */
    private static final class Accumulator {

        private int orderCount;
        private int cancelledCount;
        private long grossSom;
        private long discountSom;
        private long netSom;
        private long refundedSom;
        private long closedSecondsSum;
        private int closedOrders;
        private int promisedCount;
        private int lateCount;
        private final Set<String> customers = new HashSet<>();
        private final Set<String> newCustomers = new HashSet<>();

        void add(OrderFact order) {
            if (order.completed()) {
                orderCount++;
                grossSom += order.grossRevenueSom();
                discountSom += order.discountSom();
                netSom += order.netRevenueSom();
            }
            if (order.cancelled()) {
                cancelledCount++;
            }
            if (order.secondsTotal() != null) {
                closedSecondsSum += order.secondsTotal();
                closedOrders++;
            }
            if (order.secondsLate() != null) {
                promisedCount++;
                if (order.late()) {
                    lateCount++;
                }
            }
            if (order.customerSubjectHash() != null) {
                customers.add(order.customerSubjectHash());
                if (Boolean.TRUE.equals(order.isFirstOrder())) {
                    newCustomers.add(order.customerSubjectHash());
                }
            }
        }

        void add(RefundFact refund) {
            refundedSom += refund.refundedSom();
        }

        BranchDayAggregate toRow(BranchDayKey key, int boundaryVersion, int calculationVersion) {
            Integer average =
                    closedOrders == 0 ? null : Math.toIntExact(Math.round((double) closedSecondsSum / closedOrders));
            return new BranchDayAggregate(
                    key,
                    boundaryVersion,
                    calculationVersion,
                    orderCount,
                    cancelledCount,
                    grossSom,
                    discountSom,
                    netSom,
                    refundedSom,
                    average,
                    promisedCount,
                    lateCount,
                    customers.size(),
                    newCustomers.size());
        }
    }
}

