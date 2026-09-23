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

    /** T12 (7.5a): when {@code operator_principal_id} started being written. */
    private static final LocalDate T12_OPERATOR_ATTRIBUTION = LocalDate.of(2026, 9, 13);

    /** P39 (7.1c/7.3b): when {@code reporting.fact_order_tender} started being written. */
    private static final LocalDate P39_TENDER_FACT = LocalDate.of(2026, 9, 14);

    /** P27 (7.1): when this build started registering the elapsed-time metrics below — the source column (
     * {@code fact_order.seconds_total}) is older, but no definition named it until this wave. */
    private static final LocalDate P27_FULFILMENT_TIME = LocalDate.of(2026, 9, 14);

    /**
     * T13 (7.6/7.6a): when this build started registering the customer-grain
     * metrics below. The source columns — {@code fact_order.is_first_order},
     * {@code fact_order.customer_subject_hash}, {@code
     * agg_branch_day.distinct_customers}/{@code new_customers} — are older
     * (present since {@link #PILOT}), but no definition named them until
     * this wave.
     */
    private static final LocalDate T13_CUSTOMER_ANALYTICS = LocalDate.of(2026, 9, 15);

    /**
     * Wave 8 w7-reports (7.2c, V0383): when {@code agg_branch_day} started
     * carrying its own {@code delivery_fee_som} sum. {@code
     * fact_order.delivery_fee_som} is older (V0031), but nothing rolled it up
     * to the grain {@code /queries} reads until this migration, so a row
     * closed before this date reports zero here regardless of what it
     * actually charged — see {@code delivery_fee.v1}'s own {@code
     * openQuestion}.
     */
    private static final LocalDate W7_DELIVERY_FEE = LocalDate.of(2026, 9, 22);

    /** Wave 9 w4-reports-distance-crm (7.1, V0387): when {@code fact_order.delivery_distance_meters} started being written. */
    private static final LocalDate W9_DELIVERY_DISTANCE = LocalDate.of(2026, 9, 23);

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
            // Wave P27 (7.1): the overview's pickup/delivery elapsed-time tiles —
            // a registry-and-endpoint gap, not a data gap. fact_order.seconds_total
            // and fulfilment_type have been written since V0031; nothing named
            // "how long a delivery order actually took, door to door" until now.
            // Answered by GET .../reporting/fulfilment-time, on the same footing
            // as prep_time.median.v1 above: a median cannot be composed from
            // per-slice medians, so this is its own endpoint rather than
            // /queries.
            new MetricDefinition(
                    new MetricId("delivery_time.median", 1),
                    Grain.DAY_LOCATION,
                    "reporting.fact_order.seconds_total, fulfilment_type = 'DELIVERY'",
                    true,
                    Aggregation.MEDIAN,
                    "CLOSED_DELIVERY_ORDERS",
                    CurrencyRule.NONE,
                    "Seconds",
                    MetricUnit.SECONDS,
                    "Median seconds from order creation to close, over delivery orders only.",
                    "Delivery orders with a closed_at.",
                    "Pickup and dine-in orders; delivery orders still open.",
                    "Not applicable.",
                    "Door-to-door, not courier transit time: seconds_total starts at order "
                            + "creation, before confirmation, dispatch or handoff, so this is not "
                            + "yet the courier-leg-only figure 7.4's efficiency report (T11) would "
                            + "want.",
                    P27_FULFILMENT_TIME),
            new MetricDefinition(
                    new MetricId("pickup_time.median", 1),
                    Grain.DAY_LOCATION,
                    "reporting.fact_order.seconds_total, fulfilment_type = 'PICKUP'",
                    true,
                    Aggregation.MEDIAN,
                    "CLOSED_PICKUP_ORDERS",
                    CurrencyRule.NONE,
                    "Seconds",
                    MetricUnit.SECONDS,
                    "Median seconds from order creation to close, over pickup orders only.",
                    "Pickup orders with a closed_at.",
                    "Delivery and dine-in orders; pickup orders still open.",
                    "Not applicable.",
                    null,
                    P27_FULFILMENT_TIME),
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
                    null),
            // Wave P26: the Customers grid header counters (frontend information
            // architecture §5.1) used to be a second, unregistered code path
            // computed at UTC midnight — CustomerListQueryService#counts's own
            // former doc named this as a known simplification. Registering them
            // here is what row 5.1a asks for: "the same metric layer as the
            // dashboard". Provisional (no effectiveFrom) until finance signs
            // them, exactly like every other metric here before its PILOT date.
            new MetricDefinition(
                    new MetricId("customers.total", 1),
                    Grain.DAY,
                    "customer.customer_accounts",
                    true,
                    Aggregation.COUNT,
                    "ALL_NON_MERGED",
                    CurrencyRule.NONE,
                    "Integer",
                    MetricUnit.COUNT,
                    "Count of this tenant's customer accounts whose status is not MERGED.",
                    "ACTIVE, SUSPENDED, CLOSED and ANONYMIZED accounts.",
                    "MERGED accounts — a merge redirects one account onto another, and the "
                            + "merged-away row is never a second customer.",
                    "Not applicable.",
                    null,
                    null),
            new MetricDefinition(
                    new MetricId("customers.registered_today", 1),
                    Grain.DAY,
                    "customer.customer_accounts.created_at",
                    true,
                    Aggregation.COUNT,
                    "CREATED_ON_BUSINESS_DATE",
                    CurrencyRule.NONE,
                    "Integer",
                    MetricUnit.COUNT,
                    "Count of customer accounts created inside the tenant's own business day "
                            + "(BusinessDayBoundary), not the UTC calendar day.",
                    "Accounts created inside [businessDayStart, businessDayEnd) in the "
                            + "tenant's own zone and boundary.",
                    "Accounts created on any other business date.",
                    "Not applicable.",
                    "Before this version, the same figure was computed against UTC midnight, "
                            + "which excluded a Tashkent row dated \"today\" between 00:00 and "
                            + "05:00 local time. See MetricDefinitionDriftException's own "
                            + "reasoning for why that is a new version rather than a silent fix.",
                    null),
            new MetricDefinition(
                    new MetricId("customers.ordered_today", 1),
                    Grain.DAY,
                    "ordering.orders, via CustomerOrderActivityPort",
                    true,
                    Aggregation.COUNT_DISTINCT,
                    "AT_LEAST_ONE_ORDER_ON_BUSINESS_DATE",
                    CurrencyRule.NONE,
                    "Integer",
                    MetricUnit.COUNT,
                    "Distinct customer accounts with at least one order inside the tenant's own " + "business day.",
                    "Accounts naming an order placed inside [businessDayStart, businessDayEnd).",
                    "Guest orders, which carry no customer account to count.",
                    "Not applicable.",
                    "Read live from ordering rather than from reporting.agg_branch_day's own "
                            + "distinct_customers, so it reflects orders placed since the last "
                            + "close job ran rather than only what has already been aggregated.",
                    null),
            // Wave T12 (7.5a): the operator leaderboard's per-operator basket-size cut.
            new MetricDefinition(
                    new MetricId("receipt_depth", 1),
                    Grain.DAY_LOCATION_OPERATOR,
                    "reporting.fact_order.item_count over orders.count.v1, grouped by operator",
                    true,
                    Aggregation.RATIO,
                    "COMPLETED_ONLY",
                    CurrencyRule.NONE,
                    "One decimal place; the whole figure is items divided by orders, not rounded " + "to an integer",
                    MetricUnit.COUNT,
                    "Average item count per completed order, per operator: item_count.v1 (from "
                            + "orders.count.v1's own inclusion rule) over orders.count.v1, at the "
                            + "operator grain rather than the branch grain every other count metric "
                            + "here uses. What 7.2's per-order table already renders per row, "
                            + "aggregated — a manager reading it asks whether upsell coaching moved "
                            + "the basket, not what one receipt looked like.",
                    "Orders whose terminal status is COMPLETED.",
                    "Cancelled, rejected, and expired orders — the same exclusion " + "orders.count.v1 states.",
                    "Not applicable: a refund does not change what was ordered.",
                    "Answered by GET .../reporting/operator-leaderboard's avgItemsPerOrder, not "
                            + "by /queries: the registry's one-value-per-slice contract does not "
                            + "express a per-operator breakdown, the same reason order- and "
                            + "variant-grain reads get their own endpoint (ADR 0043).",
                    T12_OPERATOR_ATTRIBUTION),
            // P39 (7.1c/7.3b): the payment-mix card and its per-branch split, over
            // reporting.fact_order_tender (ADR 0043/0115). Payment is a grain, not
            // a column — this sums the tender fact, never revenue.*.v1's own
            // fact_order, which is exactly the doubling ADR 0043's physical model
            // warns against.
            new MetricDefinition(
                    new MetricId("payment_mix.amount", 1),
                    Grain.DAY_LOCATION_LEGAL_ENTITY_PAYMENT_METHOD,
                    "reporting.fact_order_tender.amount_som",
                    true,
                    Aggregation.DISTRIBUTION,
                    "SETTLED_OR_REVERSED_TENDERS",
                    CurrencyRule.UZS_SOM,
                    "Whole som; no sub-unit exists and nothing divides by a hundred",
                    MetricUnit.MONEY_SOM,
                    "Sum of the net amount tendered per payment method — a tender's planned "
                            + "amount less any refund already recorded against it (V0048) — over "
                            + "tenders that reached SETTLED or REVERSED, on the order's own "
                            + "business date. The one figure a restaurant uses for cash-collection "
                            + "control: what share of takings came in as cash, card, or online.",
                    "Tenders whose status is SETTLED or REVERSED, on orders of any terminal "
                            + "status — cash collected against an order later cancelled was still "
                            + "collected and still has to be reconciled.",
                    "Tenders that never moved money — PLANNED, RESERVED, RELEASED, FAILED — "
                            + "which would overstate takings with an amount nobody actually paid "
                            + "or received.",
                    "A partial refund reduces the tender's own row in place, because amount_som "
                            + "is already net; a full refund reduces it to zero and the tender's "
                            + "own status reads REVERSED. Never a second row, unlike "
                            + "revenue.net.v1's refund grain (fact_refund) — this figure answers "
                            + "\"what is currently in the till, by method\", not a ledger of every "
                            + "movement against it.",
                    "Counted at the amount the tenant's own registry recorded as tendered. A "
                            + "provider-settled method (CLICK, Payme, ...) is not reconciled here "
                            + "against what the provider actually remits after its own commission "
                            + "or fee — that is a payments-module concern this figure does not "
                            + "answer.",
                    P39_TENDER_FACT),
            // Wave T06 (7.3): the branch leaderboard's «В норме %» column needs a
            // denominator. orders.late.v1 already counts the numerator (closed,
            // promised, late); promised_count has been written to
            // reporting.agg_branch_day since V0031, but nothing named it as a
            // registry id until now — the same "registry-and-endpoint gap over
            // already-written data" P27_FULFILMENT_TIME's own comment describes.
            // No effectiveFrom, same as orders.late.v1 itself: the two are read
            // together and neither states a date the other does not.
            new MetricDefinition(
                    new MetricId("orders.promised", 1),
                    Grain.DAY_LOCATION,
                    "reporting.agg_branch_day.promised_count",
                    true,
                    Aggregation.COUNT,
                    "PROMISED_AND_CLOSED",
                    CurrencyRule.NONE,
                    "Integer",
                    MetricUnit.COUNT,
                    "Count of closed orders that carried a promise to the customer — the "
                            + "denominator orders.late.v1 needs to become an on-time percentage: "
                            + "on-time % = 100 × (orders.promised.v1 − orders.late.v1) / "
                            + "orders.promised.v1.",
                    "Orders that carried a promise and reached a terminal status — the same "
                            + "inclusion rule orders.late.v1 states.",
                    "Orders with no promise, which are a third state and never counted toward "
                            + "either the numerator or the denominator of an on-time share.",
                    "Not applicable.",
                    null,
                    null),
            // Wave T06 (7.3a): statistics.md §2.3's own «Медиана» column beside
            // the SLA time buckets. sla_bucket_set.v1 already sources
            // seconds_total; this is the same population's median, a
            // registry-and-endpoint gap over already-written data rather than a
            // new fact — the identical move P27_FULFILMENT_TIME's own comment
            // describes for delivery_time.median.v1/pickup_time.median.v1.
            // Distinct from prep_time.median.v1: that one reads
            // seconds_to_ready (confirmation to ready) and excludes orders
            // cancelled before production; this reads seconds_total (creation
            // to close) over every fulfilment type, exactly what the six
            // buckets beside it summarise.
            new MetricDefinition(
                    new MetricId("handover_time.median", 1),
                    Grain.DAY_LOCATION,
                    "reporting.fact_order.seconds_total",
                    true,
                    Aggregation.MEDIAN,
                    "CLOSED_ORDERS",
                    CurrencyRule.NONE,
                    "Seconds",
                    MetricUnit.SECONDS,
                    "Median seconds from order creation to close, over every fulfilment type — "
                            + "the same population sla_bucket_set.v1 buckets, summarised as one "
                            + "figure per branch.",
                    "Every order with a closed_at.",
                    "Orders still open at the close of the business day.",
                    "Not applicable.",
                    null,
                    null),
            // ---------------------------------------------------------------
            // T13 (7.6/7.6a/7.6b): customer analytics. The grain the row's own
            // evidence understated — reporting.fact_order already carries
            // is_first_order and customer_subject_hash, and agg_branch_day
            // already carries distinct_customers and new_customers — so
            // new-versus-returning and a repeat-purchase distribution are
            // derivable without any dim_customer table. Six of the seven are
            // answered by GET .../reporting/customer-kpis rather than
            // /queries: a distinct-customer count cannot be correctly summed
            // across the channel/fulfilment-type rows the typed pipeline
            // rolls agg_branch_day up from (a customer ordering on two
            // channels the same day would double count), the same reason
            // payment_mix.amount.v1 and receipt_depth.v1 above get their own
            // endpoint rather than /queries.
            new MetricDefinition(
                    new MetricId("customers.new", 1),
                    Grain.DAY_LOCATION,
                    "reporting.fact_order.customer_subject_hash, where is_first_order = true",
                    true,
                    Aggregation.COUNT_DISTINCT,
                    "FIRST_ORDER_IN_RANGE",
                    CurrencyRule.NONE,
                    "Integer",
                    MetricUnit.COUNT,
                    "Count of distinct customers whose first-ever order (is_first_order = true, "
                            + "computed against the customer's full order history, not just this "
                            + "range) fell inside the requested range and location.",
                    "Every order with a customer_subject_hash and is_first_order = true, "
                            + "regardless of terminal status — a cancelled first order still means "
                            + "the person is new.",
                    "Guest orders with no customer account, which carry no customer_subject_hash " + "at all.",
                    "Not applicable: a refund does not undo that an order was a customer's first.",
                    "Answered by GET .../reporting/customer-kpis, not by /queries — see this "
                            + "block's own note above.",
                    T13_CUSTOMER_ANALYTICS),
            new MetricDefinition(
                    new MetricId("customers.distinct", 1),
                    Grain.DAY_LOCATION,
                    "reporting.fact_order.customer_subject_hash",
                    true,
                    Aggregation.COUNT_DISTINCT,
                    "ANY_ORDER_IN_RANGE",
                    CurrencyRule.NONE,
                    "Integer",
                    MetricUnit.COUNT,
                    "Count of distinct customers who placed at least one order in the requested "
                            + "range and location, any terminal status.",
                    "Every order with a customer_subject_hash, regardless of terminal status.",
                    "Guest orders with no customer account.",
                    "Not applicable.",
                    "Answered by GET .../reporting/customer-kpis, not by /queries — see this "
                            + "block's own note above.",
                    T13_CUSTOMER_ANALYTICS),
            new MetricDefinition(
                    new MetricId("customers.repeat_share", 1),
                    Grain.DAY_LOCATION,
                    "customers.distinct.v1 less customers.new.v1, over customers.distinct.v1",
                    true,
                    Aggregation.RATIO,
                    "SAME_AS_CUSTOMERS_DISTINCT",
                    CurrencyRule.NONE,
                    "Basis points, so six truncated shares still sum to the whole",
                    MetricUnit.BASIS_POINTS,
                    "Share of this range's distinct customers who were NOT ordering for the "
                            + "first time — the returning share, in basis points of "
                            + "customers.distinct.v1.",
                    "Same inclusion as customers.distinct.v1 and customers.new.v1.",
                    "Same exclusion as customers.distinct.v1 and customers.new.v1.",
                    "Not applicable.",
                    "Null rather than zero when customers.distinct.v1 is zero — a period with no "
                            + "customers has no repeat share, and zero would read as \"nobody came "
                            + "back\" rather than \"nobody came at all\". Answered by GET "
                            + ".../reporting/customer-kpis, not by /queries.",
                    T13_CUSTOMER_ANALYTICS),
            new MetricDefinition(
                    new MetricId("customers.order_frequency", 1),
                    Grain.DAY_LOCATION,
                    "orders.count.v1 over customers.distinct.v1",
                    true,
                    Aggregation.RATIO,
                    "COMPLETED_ONLY",
                    CurrencyRule.NONE,
                    "Two decimal places; the whole figure is orders divided by customers, not "
                            + "rounded to an integer",
                    MetricUnit.COUNT,
                    "Average number of COMPLETED orders per distinct customer in the requested "
                            + "range and location. A period figure, never annualised.",
                    "COMPLETED orders with a customer_subject_hash, over the same customers "
                            + "customers.distinct.v1 counts.",
                    "Guest orders (no customer_subject_hash) contribute to neither the numerator "
                            + "nor the denominator.",
                    "Not applicable: a refunded order was still placed.",
                    "Null rather than zero when customers.distinct.v1 is zero. Answered by GET "
                            + ".../reporting/customer-kpis, not by /queries.",
                    T13_CUSTOMER_ANALYTICS),
            new MetricDefinition(
                    new MetricId("customers.value", 1),
                    Grain.DAY_LOCATION_LEGAL_ENTITY,
                    "revenue.net.v1 over customers.distinct.v1",
                    true,
                    Aggregation.RATIO,
                    "COMPLETED_ONLY",
                    CurrencyRule.UZS_SOM,
                    "Whole som; no sub-unit exists and nothing divides by a hundred",
                    MetricUnit.MONEY_SOM,
                    "Net revenue over the requested range divided by the count of distinct "
                            + "customers who ordered in it — what one customer was worth this "
                            + "period, never a projected lifetime figure (that is customers.ltv.v1, "
                            + "not built).",
                    "COMPLETED orders with a customer_subject_hash.",
                    "Guest orders, which have no customer to divide revenue across.",
                    "A refund reduces revenue.net.v1 on its own business date, so this figure "
                            + "moves with it the same way revenue.net.v1 does.",
                    "Null rather than zero when customers.distinct.v1 is zero. Answered by GET "
                            + ".../reporting/customer-kpis, not by /queries — money still refuses a "
                            + "combined figure across legal entities (ADR 0038).",
                    T13_CUSTOMER_ANALYTICS),
            new MetricDefinition(
                    new MetricId("customers.basket_depth", 1),
                    Grain.DAY_LOCATION,
                    "reporting.fact_order.item_count over orders.count.v1",
                    true,
                    Aggregation.RATIO,
                    "COMPLETED_ONLY",
                    CurrencyRule.NONE,
                    "One decimal place; the whole figure is items divided by orders, not rounded " + "to an integer",
                    MetricUnit.COUNT,
                    "Average item count per COMPLETED order in the requested range and location — "
                            + "the same arithmetic receipt_depth.v1 already publishes at the "
                            + "operator grain, here at the branch grain instead.",
                    "Orders whose terminal status is COMPLETED.",
                    "Cancelled, rejected, and expired orders — the same exclusion " + "orders.count.v1 states.",
                    "Not applicable: a refund does not change what was ordered.",
                    "Answered by GET .../reporting/customer-kpis, not by /queries — the same move "
                            + "receipt_depth.v1 already makes for a shape agg_branch_day does not "
                            + "carry.",
                    T13_CUSTOMER_ANALYTICS),
            new MetricDefinition(
                    new MetricId("customers.ltv", 1),
                    Grain.DAY_LOCATION_LEGAL_ENTITY,
                    "reporting.fact_order, grouped by customer_subject_hash across ALL history",
                    false,
                    Aggregation.RATIO,
                    "COMPLETED_ONLY",
                    CurrencyRule.UZS_SOM,
                    "Whole som; no sub-unit exists and nothing divides by a hundred",
                    MetricUnit.MONEY_SOM,
                    "Average lifetime net revenue per customer: for each customer active in the "
                            + "requested range, their total net revenue across every order they "
                            + "have ever placed, averaged across those customers.",
                    "Would be every COMPLETED order a customer has ever placed, not only those in "
                            + "the requested range.",
                    "Guest orders, which have no customer to accrue a lifetime to.",
                    "Would follow revenue.net.v1's own treatment.",
                    "Not built. No dim_customer or agg_customer_lifetime table exists — "
                            + "ADR 0043's implementation checklist deliberately does not build one "
                            + "— and computing this from reporting.fact_order directly would mean "
                            + "grouping by customer_subject_hash across every partition since the "
                            + "tenant's first order, unbounded by the range a caller asked for. "
                            + "customers.value.v1 above is the period figure this build actually "
                            + "publishes; every surface naming this metric must render it unbuilt "
                            + "rather than a number that quietly stops at the query range.",
                    null),
            // Money, so LEGAL_ENTITY is named (ADR 0038). Answered by the typed
            // /queries pipeline unlike every other metric in this block: it is
            // one value per (date, location, legal entity, customer type)
            // slice, sourced from fact_order directly rather than
            // agg_branch_day — see ReportQueryService#run's customer-type
            // branch.
            new MetricDefinition(
                    new MetricId("revenue.new_vs_returning", 1),
                    Grain.DAY_LOCATION_LEGAL_ENTITY_CUSTOMER_TYPE,
                    "reporting.fact_order.gross_revenue_som, grouped by is_first_order",
                    true,
                    Aggregation.SUM,
                    "COMPLETED_ONLY",
                    CurrencyRule.UZS_SOM,
                    "Whole som; no sub-unit exists and nothing divides by a hundred",
                    MetricUnit.MONEY_SOM,
                    "Gross revenue of COMPLETED orders, split by whether the order was the "
                            + "customer's first (is_first_order). Gross, matching revenue.gross.v1, "
                            + "not net: see refundTreatment below for why.",
                    "COMPLETED orders with a non-null is_first_order.",
                    "Guest orders (no customer_subject_hash, so is_first_order is null) and every "
                            + "non-completing terminal status.",
                    "Not applicable: reporting.fact_refund carries no customer_subject_hash or "
                            + "is_first_order, so a refund cannot be attributed to a customer type "
                            + "and this figure is never reduced by one the way revenue.net.v1 is.",
                    null,
                    T13_CUSTOMER_ANALYTICS),
            // Wave 8 w7-reports (7.2c): the daily summary («Сводка») needs both
            // the delivery-fee-inclusive total (already revenue.gross.v1 — see
            // that metric's own definition) and the fee-exclusive one, matching
            // Delever's report 1 (statistics.md §2.2). This is the fee's own
            // total, not a second gross figure — a report computes the
            // fee-exclusive column as revenue.gross.v1 minus this, and must
            // never add the two.
            new MetricDefinition(
                    new MetricId("delivery_fee", 1),
                    Grain.DAY_LOCATION_LEGAL_ENTITY,
                    "reporting.agg_branch_day.delivery_fee_som (V0383), rolled up from "
                            + "reporting.fact_order.delivery_fee_som",
                    true,
                    Aggregation.SUM,
                    "COMPLETED_ONLY",
                    CurrencyRule.UZS_SOM,
                    "Whole som; no sub-unit exists and nothing divides by a hundred",
                    MetricUnit.MONEY_SOM,
                    "Sum of the delivery fee charged on COMPLETED orders, on the order's "
                            + "business date. Already included in revenue.gross.v1's own total "
                            + "(ADR 0019: total = subtotal + tax + fee - discount, and gross is "
                            + "total plus the discount back) — this exists so a report can show "
                            + "the fee-exclusive figure too, by subtraction, without a second read.",
                    "Orders whose terminal status is COMPLETED.",
                    "Cancelled, rejected, expired, and payment-failed orders. A DINE_IN or "
                            + "PICKUP order with no delivery leg contributes zero, not null — it "
                            + "genuinely charged no delivery fee.",
                    "A refund reduces revenue.net.v1 and never this figure, the same treatment "
                            + "revenue.gross.v1 gets and for the same reason.",
                    "reporting.agg_branch_day.delivery_fee_som did not exist before V0383 "
                            + "(2026-09-22); an aggregate row closed before that migration reads "
                            + "zero here, not its real delivery-fee total, because a closed "
                            + "aggregate is never silently recomputed. A range that crosses that "
                            + "date understates the fee-exclusive figure derived from this metric "
                            + "for its earlier days.",
                    W7_DELIVERY_FEE),
            // Wave 9 w4-reports-distance-crm (7.1): the overview's distance
            // KPI tile. fact_order.delivery_distance_meters (V0387) is the
            // resolved delivery leg's distance (ADR 0037), snapshotted at
            // close time from fulfillment.delivery_plans — a registry entry
            // over a column this same migration introduces, on the same
            // footing delivery_time.median.v1's own comment already gives:
            // an average cannot be composed from agg_branch_day's per-slice
            // rows, so this is its own endpoint (GET .../reporting/delivery-distance)
            // rather than /queries.
            new MetricDefinition(
                    new MetricId("delivery_distance.average", 1),
                    Grain.DAY_LOCATION,
                    "reporting.fact_order.delivery_distance_meters, fulfilment_type = 'DELIVERY'",
                    true,
                    Aggregation.AVERAGE,
                    "CLOSED_DELIVERY_ORDERS_WITH_A_RESOLVED_DISTANCE",
                    CurrencyRule.NONE,
                    "Whole metres, rounded to the nearest",
                    MetricUnit.METERS,
                    "Mean resolved delivery distance across delivery orders closed in the "
                            + "requested range and location — the distance ADR 0037's delivery-fee "
                            + "tariff priced the leg on, a great-circle radius or a routed distance "
                            + "depending on the tariff's own distance mode, never the courier's "
                            + "travelled GPS track.",
                    "Delivery orders whose plan resolved a distance.",
                    "Pickup and dine-in orders, which have no delivery leg; a delivery order "
                            + "whose plan never resolved a distance, or that closed before V0387.",
                    "Not applicable: a refund does not change how far the order travelled.",
                    "Null rather than zero when nothing in range resolved a distance — a zero "
                            + "would read as a delivery next door. A row closed before V0387 "
                            + "(2026-09-23) and never recut is simply absent from this average, the "
                            + "same backfill gap delivery_fee.v1 discloses for agg_branch_day.",
                    W9_DELIVERY_DISTANCE)));

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
