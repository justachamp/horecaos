package uz.horecaos.platform.reporting.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uz.horecaos.platform.reporting.domain.MetricDefinition.Aggregation;
import uz.horecaos.platform.reporting.domain.MetricDefinition.CurrencyRule;
import uz.horecaos.platform.reporting.domain.MetricDefinition.MetricUnit;

/**
 * The only definition of a number in HorecaOS (ADR 0043).
 *
 * <p>Every tile, report column, export, and API response names a metric id from
 * this list and composes no aggregate of its own, so {@code average_check.v1}
 * means one thing on every screen and in every month. Adding a chart therefore
 * requires a release. That is the intended trade and it will feel slow the first
 * time a manager asks for a cut nobody anticipated.
 *
 * <p>These ship as version 1 and finance signs them before any tenant-visible
 * surface treats them as final. Until a signature is recorded in
 * {@code reporting.metric_definitions} the API marks the metric provisional and
 * the console renders it behind an amber rule — which is the whole answer to a
 * competitor shipping lifetime value with no stated definition at all.
 *
 * <p>Two metrics here are deliberately declared with an unbuilt source. Declaring
 * them is not aspiration: a surface that names an unbuilt metric gets an explicit
 * "not built" rather than a zero, and a zero is the answer that gets believed.
 */
public final class MetricRegistry {

    /**
     * The version stamped onto every fact this build writes.
     *
     * <p>Bumped when the close job's arithmetic changes, so a recut can tell a
     * disagreement it caused from one the data caused. Without it, changing the
     * job silently produces divergence alerts against every stored day and buries
     * the real drift underneath them.
     */
    public static final int CALCULATION_VERSION = 1;

    private static final LocalDate PILOT = LocalDate.of(2026, 7, 1);

    private static final Map<String, MetricDefinition> BY_CODE = index(List.of(
            new MetricDefinition(
                    new MetricId("revenue.gross", 1),
                    Grain.DAY_LOCATION_LEGAL_ENTITY,
                    "reporting.fact_order.gross_revenue_som",
                    true,
                    Aggregation.SUM,
                    "COMPLETED_ONLY",
                    CurrencyRule.UZS_SOM,
                    "Whole som; no sub-unit exists and nothing divides by a hundred",
                    MetricUnit.MONEY_SOM,
                    "Sum of order value before discount, including the delivery fee and tax, on "
                            + "the order's business date. This is deliberately not what the "
                            + "restaurant took: the amount paid is revenue.net.v1. ADR 0043 "
                            + "defines net as gross minus discount minus refunds, which only "
                            + "holds if gross is the pre-discount figure, and a promotion-heavy "
                            + "day therefore shows a gross figure above its takings.",
                    "Orders whose terminal status is COMPLETED.",
                    "Cancelled, rejected, expired, and payment-failed orders.",
                    "A refund reduces revenue.net.v1 on the refund's own business date and never "
                            + "this figure, so a closed day does not change after it closed.",
                    null,
                    PILOT),
            new MetricDefinition(
                    new MetricId("revenue.net", 1),
                    Grain.DAY_LOCATION_LEGAL_ENTITY,
                    "reporting.fact_order.net_revenue_som less reporting.fact_refund on this " + "date",
                    true,
                    Aggregation.SUM,
                    "COMPLETED_ONLY",
                    CurrencyRule.UZS_SOM,
                    "Whole som; no sub-unit exists and nothing divides by a hundred",
                    MetricUnit.MONEY_SOM,
                    "Gross minus discount minus refunds, with each refund counted on the business "
                            + "date it was issued rather than the date of the order it refunds.",
                    "Orders whose terminal status is COMPLETED, plus refunds issued on this date "
                            + "against orders of any date.",
                    "Cancelled, rejected, expired, and payment-failed orders. Aggregator "
                            + "commission is NOT subtracted.",
                    "A refund lands on its own date. This is what stops yesterday's closed report "
                            + "silently changing when a refund arrives today.",
                    "Aggregator commission is unknown until ADR 0040 supplies it, and null is not "
                            + "zero, so this figure overstates what the restaurant kept on an "
                            + "aggregator order. A revenue.net_of_commission.v2 arrives with the "
                            + "commission fact rather than being faked from this one.",
                    PILOT),
            new MetricDefinition(
                    new MetricId("average_check", 1),
                    Grain.DAY_LOCATION_LEGAL_ENTITY,
                    "revenue.gross.v1 over orders.count.v1",
                    true,
                    Aggregation.RATIO,
                    "COMPLETED_ONLY",
                    CurrencyRule.UZS_SOM,
                    "Whole som, truncated toward zero",
                    MetricUnit.MONEY_SOM,
                    "Gross revenue divided by the count of completed orders, over the same "
                            + "filter and the same date attribution as both. Gross is the "
                            + "pre-discount figure, so this is the average basket before "
                            + "promotions rather than the average amount collected.",
                    "Orders whose terminal status is COMPLETED.",
                    "Cancelled, rejected, and expired orders.",
                    "Refunds reduce revenue.net.v1 and leave the average check alone, because the "
                            + "check is what was ordered.",
                    null,
                    PILOT),
            new MetricDefinition(
                    new MetricId("orders.count", 1),
                    Grain.DAY_LOCATION_CHANNEL,
                    "reporting.fact_order, terminal_status = COMPLETED",
                    true,
                    Aggregation.COUNT,
                    "COMPLETED_ONLY",
                    CurrencyRule.NONE,
                    "Integer",
                    MetricUnit.COUNT,
                    "Count of orders whose terminal status is COMPLETED.",
                    "COMPLETED orders.",
                    "Every other terminal status. Cancellations are orders.cancelled.v1 and are "
                            + "never a subtraction inside this metric, because a funnel whose "
                            + "stages do not sum to the total is unreadable.",
                    "A refunded order stays in this count. It was cooked and handed over.",
                    null,
                    PILOT),
            new MetricDefinition(
                    new MetricId("orders.cancelled", 1),
                    Grain.DAY_LOCATION,
                    "reporting.fact_order, terminal_status in CANCELLED, REJECTED, EXPIRED, " + "PAYMENT_FAILED",
                    true,
                    Aggregation.COUNT,
                    "NON_COMPLETING_TERMINAL",
                    CurrencyRule.NONE,
                    "Integer",
                    MetricUnit.COUNT,
                    "Count of orders ending CANCELLED, REJECTED, EXPIRED, or PAYMENT_FAILED.",
                    "All four non-completing terminal statuses.",
                    "Orders still open at the close of the business day.",
                    "Not applicable.",
                    "What a cancellation cost is not available. stock_disposition and "
                            + "liability_party need ADR 0039, and until they exist a reservation "
                            + "released before production counts the same as four cooked dishes "
                            + "binned at the pass.",
                    PILOT),
            new MetricDefinition(
                    new MetricId("orders.late", 1),
                    Grain.DAY_LOCATION,
                    "reporting.fact_order.seconds_late, derived from the ADR 0036 promise stored " + "at checkout",
                    true,
                    Aggregation.COUNT,
                    "PROMISED_AND_CLOSED",
                    CurrencyRule.NONE,
                    "Integer count; minutes rounded down where minutes are shown",
                    MetricUnit.COUNT,
                    "Count of closed orders handed over after the time promised to the customer.",
                    "Orders that carried a promise and reached a terminal status.",
                    "Orders with no promise, which are a third state and not on-time orders.",
                    "Not applicable.",
                    "Travel is not in the promise. promise_travel_minutes is null on every "
                            + "delivery order taken before ADR 0037, and null means not modelled "
                            + "rather than zero, so delivery lateness is understated. The promise "
                            + "is also stamped at checkout rather than at confirmation: an order "
                            + "that waits eleven minutes for the restaurant to accept has spent "
                            + "eleven minutes of what the customer was told, and restarting the "
                            + "clock on acceptance would hide exactly that delay.",
                    null),
            new MetricDefinition(
                    new MetricId("prep_time.median", 1),
                    Grain.DAY_LOCATION,
                    "reporting.fact_order.seconds_to_ready",
                    true,
                    Aggregation.MEDIAN,
                    "REACHED_READY",
                    CurrencyRule.NONE,
                    "Seconds",
                    MetricUnit.SECONDS,
                    "Median seconds from confirmation to the order being ready.",
                    "Orders that reached READY.",
                    "Orders cancelled before production.",
                    "Not applicable.",
                    "An approximation of fire-to-pass. True kitchen timings are "
                            + "kitchen.tickets.started_at and ready_at and need ADR 0041; a "
                            + "ticket sitting on the pass reads here as cooking time.",
                    PILOT),
            new MetricDefinition(
                    new MetricId("sla_bucket_set", 1),
                    Grain.DAY_LOCATION,
                    "reporting.agg_sla_bucket_day",
                    true,
                    Aggregation.DISTRIBUTION,
                    "CLOSED_ORDERS",
                    CurrencyRule.NONE,
                    "Integer counts; shares in basis points summing to 10000",
                    MetricUnit.BASIS_POINTS,
                    "Six half-open intervals over elapsed order seconds: [0,30) [30,35) [35,40) "
                            + "[40,50) [50,60) [60,infinity), in minutes.",
                    "Every order with a closed_at.",
                    "Orders still open at the close of the business day.",
                    "Not applicable.",
                    "The buckets are platform-fixed, not a tenant setting. Raw elapsed seconds "
                            + "are stored on the fact so a v2 set can re-cut history rather than "
                            + "reinterpret it, which is exactly why they are not editable: a "
                            + "tenant-edited bucket rewrites every chart already drawn and "
                            + "nothing records that it happened.",
                    PILOT),
            new MetricDefinition(
                    new MetricId("channel_mix.count", 1),
                    Grain.DAY_LOCATION_CHANNEL,
                    "reporting.fact_order.channel_code",
                    true,
                    Aggregation.COUNT,
                    "COMPLETED_ONLY",
                    CurrencyRule.NONE,
                    "Integer",
                    MetricUnit.COUNT,
                    "Completed orders by the channel snapshotted on the order.",
                    "COMPLETED orders.",
                    "Cancelled, rejected, and expired orders.",
                    "Not applicable.",
                    "The channel code is a snapshot, which is why renaming a channel does not "
                            + "rewrite last month's chart.",
                    PILOT),
            new MetricDefinition(
                    new MetricId("delivery_cost_variance", 1),
                    Grain.DAY_LOCATION_LEGAL_ENTITY,
                    "reporting.fact_delivery",
                    false,
                    Aggregation.SUM,
                    "EXTERNAL_DELIVERY_BILLED",
                    CurrencyRule.UZS_SOM,
                    "Whole som; no sub-unit exists and nothing divides by a hundred",
                    MetricUnit.MONEY_SOM,
                    "Provider billed amount minus the fee charged to the customer, on external " + "deliveries.",
                    "External deliveries whose provider invoice has been matched.",
                    "UNBILLED deliveries, which are counted separately rather than read as a " + "zero-variance match.",
                    "Not applicable.",
                    "Not built. fact_delivery needs ADR 0042: courier shifts, assignments, and "
                            + "the external-delivery reconciliation do not exist as data. Every "
                            + "surface naming this metric must render it unbuilt rather than "
                            + "zero.",
                    null)));

    private MetricRegistry() {}

    public static Optional<MetricDefinition> find(String code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }

    /**
     * Resolves a code or refuses it.
     *
     * <p>ADR 0043: an unknown id is rejected and never ignored. The moment one is
     * quietly dropped, a report renders with a column missing and reads as a quiet
     * day rather than a bug.
     */
    public static MetricDefinition require(String code) {
        return find(code).orElseThrow(() -> new UnknownMetricException(code));
    }

    /** Every metric, in declaration order, which is the order surfaces list them. */
    public static Collection<MetricDefinition> all() {
        return BY_CODE.values();
    }

    private static Map<String, MetricDefinition> index(List<MetricDefinition> definitions) {
        Map<String, MetricDefinition> byCode = new LinkedHashMap<>();
        for (MetricDefinition definition : definitions) {
            if (byCode.put(definition.id().code(), definition) != null) {
                throw new IllegalStateException(
                        "Two definitions claim " + definition.id().code());
            }
        }
        // Unmodifiable rather than Map.copyOf, which does not preserve order: the
        // declaration order is the order the metric dictionary lists them in.
        return Collections.unmodifiableMap(byCode);
    }

    /** A surface named a metric this build does not define. */
    public static final class UnknownMetricException extends IllegalArgumentException {

        private final String code;

        UnknownMetricException(String code) {
            super("Unknown metric \"%s\". Every number names a registry id (ADR 0043).".formatted(code));
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
