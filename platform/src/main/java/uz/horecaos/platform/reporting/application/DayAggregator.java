package uz.horecaos.platform.reporting.application;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.reporting.application.ReportingFacts.BranchDayAggregate;
import uz.horecaos.platform.reporting.application.ReportingFacts.BranchDayKey;
import uz.horecaos.platform.reporting.application.ReportingFacts.CallHourFact;
import uz.horecaos.platform.reporting.application.ReportingFacts.OrderFact;
import uz.horecaos.platform.reporting.application.ReportingFacts.RefundFact;
import uz.horecaos.platform.reporting.application.ReportingFacts.SlaBucketAggregate;
import uz.horecaos.platform.reporting.domain.SlaBucketSet;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.SourceCallEvent;

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
     * T11 / ADR 0125: the fixed SLA distribution per courier for one business
     * date — the {@code COURIER} scope {@code ck_agg_sla_scope_kind} (V0031)
     * has allowed since before this fact existed. Same six buckets, same
     * shares-in-basis-points math as {@link #slaBuckets}; the only
     * difference is the elapsed time bucketed ({@code transitSeconds}, a
     * courier's acceptance-to-delivery span) and the key it is bucketed by
     * (courier, not location).
     */
    public static List<SlaBucketAggregate> courierSlaBuckets(
            UUID tenantId, LocalDate businessDate, List<ReportingFacts.DeliveryFact> deliveries) {

        Map<UUID, Map<String, Integer>> counts = new LinkedHashMap<>();
        for (ReportingFacts.DeliveryFact delivery : deliveries) {
            counts.computeIfAbsent(delivery.courierId(), ignored -> new LinkedHashMap<>())
                    .merge(SlaBucketSet.bucketFor(delivery.transitSeconds()).code(), 1, Integer::sum);
        }

        List<SlaBucketAggregate> rows = new ArrayList<>();
        counts.forEach((courierId, byBucket) -> {
            int total = byBucket.values().stream().mapToInt(Integer::intValue).sum();
            List<Integer> ordered = SlaBucketSet.buckets().stream()
                    .map(bucket -> byBucket.getOrDefault(bucket.code(), 0))
                    .toList();
            List<Integer> shares = sharesInBasisPoints(ordered, total);

            for (int index = 0; index < SlaBucketSet.buckets().size(); index++) {
                if (ordered.get(index) == 0) {
                    continue;
                }
                rows.add(new SlaBucketAggregate(
                        tenantId,
                        businessDate,
                        "COURIER",
                        courierId,
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
        private long deliveryFeeSom;
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
                // Wave 8 w7-reports (7.2c): same COMPLETED_ONLY inclusion as
                // grossSom, of which this is already a component — never a
                // separate inclusion rule from the figure it is carved out of.
                deliveryFeeSom += order.deliveryFeeSom();
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
                    newCustomers.size(),
                    deliveryFeeSom);
        }
    }

    /**
     * The longest offered-to-answered interval still believed, when no ADR 0030 value was
     * resolved (a test, or a close wired without the resolver). Mirrors the declared default
     * of {@code voice.ring_time_ceiling_seconds}.
     */
    public static final int DEFAULT_RING_TIME_CEILING_SECONDS = 1800;

    /**
     * ADR 0064: one business date's call activity, bucketed by branch, hour,
     * and operator (the sentinel {@code "(unassigned)"} standing in for a call
     * this build could not attribute to a specific person — see {@link
     * CallHourFact}'s own doc).
     *
     * <p>Talk duration is summed only from ENDED events that already carry a
     * computed {@code durationSeconds} — see {@code VoiceEventIngestionService}
     * for where that number comes from.
     */
    public static List<CallHourFact> callHourFacts(
            UUID tenantId,
            LocalDate businessDate,
            ZoneId zone,
            List<SourceCallEvent> events,
            int boundaryVersion,
            int calculationVersion) {
        return callHourFacts(
                tenantId,
                businessDate,
                zone,
                events,
                boundaryVersion,
                calculationVersion,
                DEFAULT_RING_TIME_CEILING_SECONDS);
    }

    /**
     * ADR 0175: the same facts plus the five call-level columns. The existing counts accumulate on
     * the event's own key exactly as before; the new values accumulate on the key {@code (brand,
     * location, hour of the first answer, resolved operator)}, so a key that exists only for them is
     * a row of zero event counts, which V0149's check allows.
     *
     * <p>Every row this writes carries non-null values for the call KPIs (zero where nothing was
     * measured) and a non-null maximum exactly when it holds a ring sample; a row read back from a
     * day closed before the migration carries {@code null} and is "not measured".
     *
     * @param ringCeilingSeconds the longest offered-to-answered interval still believed
     *     ({@code voice.ring_time_ceiling_seconds}); a longer pair is two calls joined by a reused
     *     provider id, still an answered call but not a ring sample
     */
    public static List<CallHourFact> callHourFacts(
            UUID tenantId,
            LocalDate businessDate,
            ZoneId zone,
            List<SourceCallEvent> events,
            int boundaryVersion,
            int calculationVersion,
            int ringCeilingSeconds) {

        Map<CallHourKey, CallAccumulator> byKey = new LinkedHashMap<>();
        for (SourceCallEvent event : events) {
            int hour = ZonedDateTime.ofInstant(event.occurredAt(), zone).getHour();
            CallHourKey key = new CallHourKey(
                    event.brandId(), event.locationId(), hour, operatorOrSentinel(event.operatorPrincipalId()));
            byKey.computeIfAbsent(key, ignored -> new CallAccumulator()).add(event);

            if ("ANSWERED".equals(event.eventType()) && event.firstAnswerOfCall()) {
                CallHourKey answeredKey = new CallHourKey(
                        event.brandId(), event.locationId(), hour, operatorOrSentinel(event.resolvedOperator()));
                byKey.computeIfAbsent(answeredKey, ignored -> new CallAccumulator())
                        .addFirstAnswer(event, ringCeilingSeconds);
            }
        }

        List<CallHourFact> rows = new ArrayList<>(byKey.size());
        byKey.forEach((key, accumulated) ->
                rows.add(accumulated.toRow(tenantId, businessDate, key, boundaryVersion, calculationVersion)));

        rows.sort(Comparator.comparing((CallHourFact row) -> row.locationId().toString())
                .thenComparingInt(CallHourFact::hourOfDay)
                .thenComparing(CallHourFact::operatorPrincipalId));
        return rows;
    }

    private static String operatorOrSentinel(@Nullable String operatorPrincipalId) {
        return operatorPrincipalId == null || operatorPrincipalId.isBlank() ? "(unassigned)" : operatorPrincipalId;
    }

    private record CallHourKey(UUID brandId, UUID locationId, int hour, String operatorPrincipalId) {}

    private static final class CallAccumulator {
        private int offered;
        private int answered;
        private int missed;
        private int transferred;
        private long talkDurationSeconds;

        // ADR 0175: the call-level columns, accumulated only from a call's first answer.
        private int answeredCalls;
        private long ringSecondsTotal;
        private int ringSamples;
        private int ringSecondsMax;
        private int converted;

        void add(SourceCallEvent event) {
            switch (event.eventType()) {
                case "OFFERED" -> offered++;
                case "ANSWERED" -> answered++;
                case "MISSED" -> missed++;
                case "TRANSFERRED" -> transferred++;
                case "ENDED" -> {
                    if (event.durationSeconds() != null) {
                        talkDurationSeconds += event.durationSeconds();
                    }
                }
                default -> {
                    /* an event type this aggregator does not yet know; ignored rather than failing the close */
                }
            }
        }

        /**
         * One call's first answer: it is an answered call, it is a ring sample only when an {@code
         * OFFERED} exists and the interval is between zero and the ceiling (a lost event shows up as
         * lower coverage, never as a wrong average), and it is a conversion when an order was placed
         * from the call.
         */
        void addFirstAnswer(SourceCallEvent answer, int ringCeilingSeconds) {
            answeredCalls++;
            Instant offeredAt = answer.offeredAt();
            if (offeredAt != null) {
                long interval = Duration.between(offeredAt, answer.occurredAt()).toSeconds();
                if (interval >= 0 && interval <= ringCeilingSeconds) {
                    ringSamples++;
                    ringSecondsTotal += interval;
                    ringSecondsMax = Math.max(ringSecondsMax, (int) interval);
                }
            }
            if (answer.hasOrder()) {
                converted++;
            }
        }

        CallHourFact toRow(
                UUID tenantId, LocalDate businessDate, CallHourKey key, int boundaryVersion, int calculationVersion) {
            return new CallHourFact(
                    tenantId,
                    key.brandId(),
                    key.locationId(),
                    businessDate,
                    key.hour(),
                    key.operatorPrincipalId(),
                    boundaryVersion,
                    calculationVersion,
                    offered,
                    answered,
                    missed,
                    transferred,
                    talkDurationSeconds,
                    answeredCalls,
                    ringSecondsTotal,
                    ringSamples,
                    ringSamples > 0 ? ringSecondsMax : null,
                    converted);
        }
    }
}
