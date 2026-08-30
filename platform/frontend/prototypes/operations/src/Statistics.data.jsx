/* Fixtures for the reports section (operations spec §7.1–7.10).
 *
 * Authored backwards from the states the screen has to survive, not forwards
 * from a happy path. The awkward cases are here on purpose:
 *
 *   · a branch force-closed mid-service, so its part-day figures are not
 *     comparable and its aggregator channels must not raise false staleness
 *   · a tenant trading as two legal entities, so no money view may print a
 *     combined total (ADR 0038)
 *   · an order 41 minutes over its promise whose courier went off shift while
 *     still holding it
 *   · a dish sold out with the reason still in a raw, unvalidated varchar
 *   · a disabled user who exported the customer base last week and whose audit
 *     row must outlive the account
 *   · a dish name long enough to wrap beside one two syllables long
 *
 * Every number carries the id of the metric that produced it. A figure whose
 * source cannot be shown is a figure nobody trusts, and ADR 0043 makes the
 * registry the thing that stops the overview and the export disagreeing.
 *
 * Money is whole som as integers. Times are ISO strings, Asia/Tashkent.
 */

export const REPORT_NOW = "2026-08-21T19:34:00";

/* ADR 0023 forbids shipping a report that cannot state its freshness. */
export const FRESHNESS = {
  dataAsOf: "2026-08-21T19:34:00",
  closedThrough: "2026-08-20",
  /* 21.08 is inside its 24h settle window, so today's figures may still move. */
  settlingDay: "21.08",
  /* A recut that disagreed with the stored total. ADR 0043 alerts, never
   * overwrites — the manager may already have acted on the earlier number. */
  divergence: { day: "19.08", metric: "revenue.gross.v1", ticket: "SUP-2291" },
};

/* ── the tenant ────────────────────────────────────────────────────────────*/

export const TENANT = {
  name: "Osh Markazi",
  businessDayStart: "00:00",
  timezone: "Asia/Tashkent",
  /* Three branches, so the branch axis is real. One is force-closed. */
  locations: [
    { id: "loc-chi", name: "Chilonzor filiali", street: "Chilonzor 9-kvartal, 42-uy", entityId: "le-ooo", state: "OPEN" },
    { id: "loc-yun", name: "Yunusobod filiali", street: "Amir Temur ko'chasi 108", entityId: "le-yatt", state: "OPEN" },
    { id: "loc-ser", name: "Sergeli filiali", street: "Bunyodkor shoh ko'chasi 214", entityId: "le-ooo", state: "FORCE_CLOSED", closedAt: "2026-08-21T16:20:00", closedReason: "Elektr uzilishi — muzlatkich ishlamadi" },
  ],
  /* ADR 0038. A tenant-level revenue total across two of these reconciles to
   * neither tax filing, which is why the money tiles refuse to print one. */
  legalEntities: [
    { id: "le-ooo", name: "OOO «Osh Markazi Servis»", inn: "302847561" },
    { id: "le-yatt", name: "YaTT Ergashev J.Q.", inn: "451093827" },
  ],
  channels: [
    { id: "ch-tg", name: "Telegram bot", systemType: "OWN", ordersInPeriod: 71 },
    { id: "ch-web", name: "Sayt", systemType: "OWN", ordersInPeriod: 44 },
    { id: "ch-phone", name: "Telefon", systemType: "OWN", ordersInPeriod: 38 },
    { id: "ch-hall", name: "Zalda", systemType: "OWN", ordersInPeriod: 19 },
    { id: "ch-ye", name: "Yandex Eats", systemType: "AGGREGATOR", ordersInPeriod: 29 },
    { id: "ch-uz", name: "Uzum Tezkor", systemType: "AGGREGATOR", ordersInPeriod: 17 },
    { id: "ch-ex", name: "Express24", systemType: "AGGREGATOR", ordersInPeriod: 6 },
    /* Archived, but it carried orders inside the period, so it stays visible. */
    { id: "ch-ig", name: "Instagram Direct", systemType: "OWN", archived: true, ordersInPeriod: 3 },
    /* Archived with nothing in the period — hidden by the same rule. */
    { id: "ch-kiosk", name: "Kiosk", systemType: "OWN", archived: true, ordersInPeriod: 0 },
  ],
};

/* ── the metric registry (ADR 0043 §reporting.metric_definitions) ───────────
 * The `?` beside every number opens one of these. `signed` false renders the
 * number with an amber left rule: version 1 semantics ship provisional until
 * finance signs them, and saying so is the whole answer to Delever shipping LTV
 * with no stated definition at all.
 */

export const METRICS = {
  "revenue.gross.v1": {
    name: "Revenue",
    definition: "Sum of order totals including the delivery fee, on the order's business date.",
    includes: "COMPLETED only",
    excludes: "Cancelled, rejected, expired, payment-failed",
    refunds: "Reduce revenue.net on the refund's own date, never this figure",
    grain: "Business day × branch × channel × legal entity",
    source: "fact_order.gross_revenue_som — not built (ADR 0043). Read today from ordering.orders.total_minor.",
    rounding: "UZS, whole som, rounded down",
    signed: false,
    effectiveFrom: "2026-07-01",
  },
  "orders.count.v1": {
    name: "Orders",
    definition: "Count of orders whose terminal status is COMPLETED.",
    includes: "COMPLETED",
    excludes: "Every other terminal status, counted separately by orders.cancelled.v1",
    refunds: "A refunded order stays in this count",
    grain: "Business day × branch × channel",
    source: "ordering.orders — built. Terminal statuses are the code-owned twelve from ck_order_status.",
    rounding: "Integer",
    signed: true,
    effectiveFrom: "2026-07-01",
  },
  "average_check.v1": {
    name: "Average check",
    definition: "Gross revenue ÷ count of completed orders.",
    includes: "COMPLETED only",
    excludes: "Cancelled, rejected, expired",
    refunds: "Reduce revenue.net, not the average check",
    grain: "Business day × branch × legal entity",
    source: "Derived per registry from revenue.gross.v1 and orders.count.v1",
    rounding: "UZS, whole som, rounded down",
    signed: false,
    effectiveFrom: "2026-07-01",
  },
  "orders.cancelled.v1": {
    name: "Cancellations",
    definition: "Count and share of orders ending CANCELLED, REJECTED, EXPIRED or PAYMENT_FAILED, over all orders received.",
    includes: "All four non-completing terminal statuses, split by stage reached",
    excludes: "Orders still open at the close of the business day",
    refunds: "Not applicable",
    grain: "Business day × branch × reason code",
    source: "ordering.orders.status — built. Cost of a cancellation (stock_disposition, liability_party) needs ADR 0039 and is not available.",
    rounding: "Integer; share to one decimal",
    signed: false,
    effectiveFrom: "2026-07-01",
  },
  "orders.late.v1": {
    name: "Late orders",
    definition: "Orders whose elapsed time exceeded the time promised to the customer.",
    includes: "COMPLETED and CANCELLED-after-promise orders",
    excludes: "Pre-orders scheduled for a later slot",
    refunds: "Not applicable",
    grain: "Business day × branch × fulfilment mode",
    source: "ordering.orders.promised_at, with promise_basis, promise_prep_minutes and promise_travel_minutes beside it (V0023). Stored at checkout and never recomputed, so a later edit to tenant.preparation_bands cannot move a promise that was already made. Lateness is derived — promised_at < now() on a non-terminal order — and is deliberately not a column.",
    rounding: "Minutes, rounded down",
    signed: false,
    open: "Travel is not in the promise. promise_travel_minutes is null on every delivery order taken before ADR 0037, and null means not modelled rather than zero — which is what the eventual backfill will select on. Until then a delivery promise covers the kitchen only, so delivery lateness here is understated. Note the promise is stamped at checkout, not at confirmation: an order that waits eleven minutes for the restaurant to accept has spent eleven minutes of what the customer was told, and restarting the clock on acceptance would hide exactly that delay.",
    effectiveFrom: null,
  },
  "prep_time.median.v1": {
    name: "Preparation time",
    definition: "Median seconds from PREPARING to READY.",
    includes: "Orders that reached READY",
    excludes: "Cancelled before production",
    refunds: "Not applicable",
    grain: "Business day × branch",
    source: "ordering.order_state_history — built, but an approximation. True fire-to-pass is kitchen.tickets.started_at/ready_at and needs ADR 0041; a ticket sitting on the pass reads here as cooking time.",
    rounding: "Seconds, median on the face, mean below",
    signed: false,
    effectiveFrom: "2026-07-01",
  },
  "sla_bucket_set.v1": {
    name: "Time buckets",
    definition: "Six half-open intervals over elapsed order seconds: [0,30) [30,35) [35,40) [40,50) [50,60) [60,∞).",
    includes: "Every order with a closed_at",
    excludes: "Orders still open",
    refunds: "Not applicable",
    grain: "Business day × branch × scope",
    source: "Cut live from ordering.orders created_at→closed_at. Raw elapsed seconds are stored on the fact so a v2 set can re-cut history rather than reinterpret it. The delivery-leg cut needs ADR 0042; agg_sla_bucket_day needs ADR 0043.",
    rounding: "Integer counts; shares to one decimal, summing to 100,0",
    signed: true,
    effectiveFrom: "2026-07-01",
  },
  "channel_mix.count.v1": {
    name: "Channel mix",
    definition: "Share of completed orders by the channel snapshotted on the order.",
    includes: "COMPLETED only",
    excludes: "Cancelled, rejected, expired",
    refunds: "Not applicable",
    grain: "Business day × channel",
    source: "ordering.orders.channel_code_snapshot — built. The snapshot is why renaming a channel does not rewrite last month's chart.",
    rounding: "Shares to one decimal",
    signed: true,
    effectiveFrom: "2026-07-01",
  },
};

/* ── band A: five tiles ────────────────────────────────────────────────────
 * The delta is against the same weekday last week, never yesterday. Friday
 * against Saturday is noise wearing a trend's clothes.
 */

const spark = (a) => a; /* 24 cells, one per hour of the operating day */

export const TILES = [
  {
    id: "revenue",
    metric: "revenue.gross.v1",
    label: "Revenue",
    /* No combined figure: two legal entities (ADR 0038). */
    money: true,
    byEntity: [
      { entityId: "le-ooo", value: 11_940_000 },
      { entityId: "le-yatt", value: 6_700_000 },
    ],
    deltaPct: 4.2,
    sparkline: spark([0,0,0,0,0,0,0,0,0,0,180,420,760,1180,940,520,380,610,980,1420,0,0,0,0]),
    linkTo: "Daily operating table",
  },
  {
    id: "orders",
    metric: "orders.count.v1",
    label: "Orders",
    value: 214,
    deltaPct: 6.4,
    sparkline: spark([0,0,0,0,0,0,0,0,0,0,4,9,14,19,11,6,5,8,13,16,0,0,0,0]),
    linkTo: "Stage durations",
  },
  {
    id: "check",
    metric: "average_check.v1",
    label: "Average check",
    money: true,
    byEntity: [
      { entityId: "le-ooo", value: 88_444 },
      { entityId: "le-yatt", value: 92_500 },
    ],
    deltaPct: -1.8,
    sparkline: spark([0,0,0,0,0,0,0,0,0,0,74,81,86,92,88,79,84,90,94,97,0,0,0,0]),
    linkTo: "Stage durations",
  },
  {
    id: "cancelled",
    metric: "orders.cancelled.v1",
    label: "Cancellations",
    value: 16,
    secondary: "6,5% · most often: mahsulot tugadi",
    deltaPct: 18.5,
    deltaWorseWhenUp: true,
    sparkline: spark([0,0,0,0,0,0,0,0,0,0,1,0,2,3,1,0,1,2,3,3,0,0,0,0]),
    linkTo: "Cancellation reasons",
  },
  {
    id: "late",
    metric: "orders.late.v1",
    label: "Late",
    value: 9,
    secondary: "median +14 min · worst +41 min",
    /* No delta: comparing an undefined baseline against itself last Friday is
     * two wrong numbers, not a trend. */
    deltaPct: null,
    sparkline: spark([0,0,0,0,0,0,0,0,0,0,0,1,2,1,0,0,1,1,2,1,0,0,0,0]),
    linkTo: "Late orders",
  },
];

/* ── band B: timing against target ─────────────────────────────────────────*/

export const GAUGES = [
  {
    id: "prep",
    label: "Preparation",
    metric: "prep_time.median.v1",
    medianMinutes: 26,
    meanMinutes: 31,
    targetMinutes: 20,
    withinPct: 68,
    /* target is built (V0020 preparation_bands); the actual is derivable but
     * approximate, which the caption has to say rather than imply. */
    note: "PREPARING→READY, an approximation of fire-to-pass. True kitchen timings need ADR 0041.",
  },
  {
    id: "delivery",
    label: "Delivery",
    metric: null,
    unbuilt: "ADR 0042 — courier shifts, assignments and delivery timings do not exist yet.",
  },
  {
    id: "pickup",
    label: "Pickup",
    metric: null,
    unbuilt: "ADR 0041 — ready→collected is not recorded.",
  },
];

/* Six half-open intervals. They are exhaustive and do not overlap, so the
 * shares sum. Delever's documented branch buckets are «до 30, до 35, 30–40,
 * 40–50, 35–60, свыше 60» — two adjacent columns count the same order twice and
 * the percentages cannot add up to anything. */
export const SLA_BUCKETS = [
  { id: "b1", label: "under 30 min", from: 0, to: 30, count: 61 },
  { id: "b2", label: "30–35", from: 30, to: 35, count: 48 },
  { id: "b3", label: "35–40", from: 35, to: 40, count: 39 },
  { id: "b4", label: "40–50", from: 40, to: 50, count: 34 },
  { id: "b5", label: "50–60", from: 50, to: 60, count: 21 },
  { id: "b6", label: "over 60", from: 60, to: null, count: 11 },
];

export const SLA_MEDIAN_MINUTES = 34;

/* ── band C: mix ───────────────────────────────────────────────────────────*/

export const CHANNEL_MIX = [
  { channelId: "ch-tg", count: 71, revenue: 6_142_000 },
  { channelId: "ch-web", count: 44, revenue: 4_018_000 },
  { channelId: "ch-phone", count: 38, revenue: 3_764_000 },
  { channelId: "ch-ye", count: 29, revenue: 2_410_000 },
  { channelId: "ch-hall", count: 19, revenue: 1_206_000 },
  { channelId: "ch-uz", count: 17, revenue: 1_388_000 },
  { channelId: "ch-ex", count: 6, revenue: 502_000 },
  { channelId: "ch-ig", count: 3, revenue: 210_000 },
];

export const FULFILMENT_MIX = [
  { id: "DELIVERY", label: "Delivery", count: 138 },
  { id: "PICKUP", label: "Pickup", count: 57 },
  { id: "DINE_IN", label: "Dine-in", count: 19 },
];

/* ── band D: the funnel ────────────────────────────────────────────────────
 * Statuses are the code-owned twelve from ordering.orders.ck_order_status. This
 * is never a tenant vocabulary — a tenant that renames PREPARING has renamed a
 * column in a chart drawn last quarter.
 */

export const FUNNEL = [
  { id: "RECEIVED", label: "Received", count: 247 },
  { id: "CONFIRMED", label: "Confirmed", count: 230 },
  { id: "PREPARING", label: "Preparing", count: 221 },
  { id: "READY", label: "Ready", count: 218 },
  { id: "FULFILLING", label: "Fulfilling", count: 218 },
  { id: "COMPLETED", label: "Completed", count: 214 },
];

export const DROPOFFS = [
  { id: "REJECTED", label: "Rejected", count: 8, afterStage: "RECEIVED" },
  { id: "EXPIRED", label: "Expired", count: 4, afterStage: "RECEIVED" },
  { id: "PAYMENT_FAILED", label: "Payment failed", count: 5, afterStage: "RECEIVED" },
  { id: "CANCELLED", label: "Cancelled", count: 16, afterStage: "CONFIRMED" },
];

/* The reason codes are printed exactly as stored. ordering.order_state_history
 * .reason_code is a varchar(64), unvalidated and unlocalised — the casing drift
 * below is not a fixture typo, it is what an unconstrained free-text key looks
 * like after four months of three operators. ADR 0039 replaces it with
 * order_outcome_reasons plus stock_disposition and liability_party, which is
 * what turns this panel from a count into a cost. */
export const CANCELLATION_REASONS = [
  { code: "ITEM_UNAVAILABLE", label: "Mahsulot tugadi", detail: "Lo'la kabob — qiyma tugadi", count: 6, stage: "CONFIRMED" },
  { code: "no_courier", label: "Kuryer topilmadi", detail: "Yunusobod, 19:05–19:40", count: 5, stage: "READY" },
  { code: "customer_cancel", label: "Mijoz bekor qildi", detail: null, count: 3, stage: "PREPARING" },
  { code: "WRONG_ADDR", label: "Manzil noto'g'ri", detail: "Yetkazishda aniqlandi", count: 2, stage: "FULFILLING" },
];

/* ── the branch leaderboard (7.1 band D / 7.3 table A, top rows) ───────────*/

export const BRANCH_ROWS = [
  {
    id: "loc-yun", orders: 96, revenue: 6_700_000, check: 92_500,
    prepMedian: 22, cancelPct: 4.2, withinPct: 74,
  },
  {
    id: "loc-chi", orders: 89, revenue: 7_980_000, check: 89_663,
    prepMedian: 26, cancelPct: 6.8, withinPct: 66,
  },
  {
    id: "loc-ser", orders: 29, revenue: 3_960_000, check: 136_551,
    prepMedian: 31, cancelPct: 13.8, withinPct: 41,
    severity: "warning",
    reason: "Force-closed 16:20 — a part day, not comparable with the rows above",
  },
];

/* ── 7.2 «Этапы» / «Опоздания»: one fact table, two readings ────────────────
 * The late tab is a filter and a sort over these rows rather than a second
 * fixture, because two lists of the same orders that disagree is precisely the
 * failure the metric registry exists to prevent.
 *
 * Durations are seconds. `courierSec` is null throughout and prints «—»: ADR
 * 0042 does not exist, and a zero there would read as an instant delivery.
 */

export const ORDER_FACTS = [
  {
    id: "QO-4763", locationId: "loc-yun", channelId: "ch-ye", fulfilment: "DELIVERY",
    createdAt: "2026-08-21T18:04:00", closedAt: "2026-08-21T19:16:00",
    acceptSec: 214, branchSec: 386, prepSec: 2_640, courierSec: null, totalSec: 4_320,
    promiseMinutes: 30, lateMinutes: 41, status: "COMPLETED",
    total: 318_000, itemsSummary: "Chayonli osh (katta, qazi bilan) ×2, Qo'y sho'rva ×2, Tandir non ×4",
    customer: "Gulnora Abdurahmonova-Yo'ldosheva", phone: "+998901882043",
    operator: "Nigora Sultonova", courier: "Shoxrux Qodirov",
    reasonCode: null,
    kitchen: [
      { label: "Received", at: "2026-08-21T18:04:00" },
      { label: "Confirmed", at: "2026-08-21T18:07:34" },
      { label: "Fired", at: null, unbuilt: "kitchen.tickets.started_at — ADR 0041" },
      { label: "Ready", at: "2026-08-21T18:58:00" },
    ],
    logistics: [
      { label: "Courier assigned", at: "2026-08-21T18:41:00", actor: "Shoxrux Qodirov" },
      { label: "Courier went off shift", at: "2026-08-21T18:52:00", severity: "incident", actor: "Shoxrux Qodirov — order still held" },
      { label: "Reassigned", at: "2026-08-21T19:02:00", actor: "Ravshan Umarov" },
      { label: "Picked up", at: null, unbuilt: "fulfillment.courier_assignment — ADR 0042" },
      { label: "Delivered", at: "2026-08-21T19:16:00" },
    ],
  },
  {
    id: "QO-4771", locationId: "loc-chi", channelId: "ch-tg", fulfilment: "DELIVERY",
    createdAt: "2026-08-21T18:22:00", closedAt: "2026-08-21T19:24:00",
    acceptSec: 96, branchSec: 240, prepSec: 2_280, courierSec: null, totalSec: 3_720,
    promiseMinutes: 35, lateMinutes: 27, status: "COMPLETED",
    total: 171_000, itemsSummary: "Toshkent oshi ×2, Achichuk ×1, Ko'k choy ×2",
    customer: "Dilnoza Rahimova", phone: "+998901234567",
    operator: "Telegram bot", operatorMachine: true, courier: "Alisher Karimov",
    reasonCode: null,
    kitchen: [
      { label: "Received", at: "2026-08-21T18:22:00" },
      { label: "Confirmed", at: "2026-08-21T18:23:36" },
      { label: "Fired", at: null, unbuilt: "kitchen.tickets.started_at — ADR 0041" },
      { label: "Ready", at: "2026-08-21T19:05:36" },
    ],
    logistics: [
      { label: "Courier assigned", at: "2026-08-21T19:06:00", actor: "Alisher Karimov" },
      { label: "Picked up", at: null, unbuilt: "fulfillment.courier_assignment — ADR 0042" },
      { label: "Delivered", at: "2026-08-21T19:24:00" },
    ],
  },
  {
    id: "QO-4744", locationId: "loc-ser", channelId: "ch-phone", fulfilment: "DELIVERY",
    createdAt: "2026-08-21T15:41:00", closedAt: "2026-08-21T16:58:00",
    acceptSec: 412, branchSec: 604, prepSec: 2_940, courierSec: null, totalSec: 4_620,
    promiseMinutes: 40, lateMinutes: 37, status: "COMPLETED",
    total: 486_000, itemsSummary: "Qo'y kabob ×8, Tandir non ×8, Achichuk ×4, Ayron ×6",
    customer: "Jamshid Ergashev", phone: "+998908846205",
    operator: "Bekzod Tursunov", courier: "Otabek Nazarov",
    reasonCode: null,
    kitchen: [
      { label: "Received", at: "2026-08-21T15:41:00" },
      { label: "Confirmed", at: "2026-08-21T15:47:52" },
      { label: "Fired", at: null, unbuilt: "kitchen.tickets.started_at — ADR 0041" },
      { label: "Ready", at: "2026-08-21T16:47:00" },
    ],
    logistics: [
      { label: "Courier assigned", at: "2026-08-21T16:20:00", actor: "Otabek Nazarov" },
      { label: "Picked up", at: null, unbuilt: "fulfillment.courier_assignment — ADR 0042" },
      { label: "Delivered", at: "2026-08-21T16:58:00" },
    ],
  },
  {
    id: "QO-4802", locationId: "loc-chi", channelId: "ch-web", fulfilment: "PICKUP",
    createdAt: "2026-08-21T18:40:00", closedAt: "2026-08-21T19:22:00",
    acceptSec: 64, branchSec: 152, prepSec: 1_080, courierSec: null, totalSec: 2_520,
    promiseMinutes: 25, lateMinutes: 17, status: "COMPLETED",
    total: 56_000, itemsSummary: "Tandir somsa ×4, Ko'k choy ×1",
    customer: "Malika Yusupova", phone: "+998912057714",
    operator: "Sayt", operatorMachine: true, courier: null,
    reasonCode: null,
    kitchen: [
      { label: "Received", at: "2026-08-21T18:40:00" },
      { label: "Confirmed", at: "2026-08-21T18:41:04" },
      { label: "Fired", at: null, unbuilt: "kitchen.tickets.started_at — ADR 0041" },
      { label: "Ready", at: "2026-08-21T19:01:36" },
    ],
    logistics: [
      { label: "Waiting on the pass", at: "2026-08-21T19:01:36" },
      { label: "Collected", at: "2026-08-21T19:22:00", note: "ready→collected is not recorded — ADR 0041" },
    ],
  },
  {
    id: "QO-4788", locationId: "loc-yun", channelId: "ch-uz", fulfilment: "DELIVERY",
    createdAt: "2026-08-21T18:31:00", closedAt: "2026-08-21T19:12:00",
    acceptSec: 41, branchSec: 128, prepSec: 1_500, courierSec: null, totalSec: 2_460,
    promiseMinutes: 35, lateMinutes: 6, status: "COMPLETED",
    total: 142_000, itemsSummary: "Mastava ×2, Tandir non ×2, Ayron ×2",
    customer: "Sardor Yo'ldoshev", phone: "+998934421890",
    operator: "Uzum Tezkor", operatorMachine: true, courier: "Ravshan Umarov",
    reasonCode: null,
    kitchen: [
      { label: "Received", at: "2026-08-21T18:31:00" },
      { label: "Confirmed", at: "2026-08-21T18:31:41" },
      { label: "Fired", at: null, unbuilt: "kitchen.tickets.started_at — ADR 0041" },
      { label: "Ready", at: "2026-08-21T18:58:49" },
    ],
    logistics: [
      { label: "Courier assigned", at: "2026-08-21T18:59:00", actor: "Ravshan Umarov" },
      { label: "Picked up", at: null, unbuilt: "fulfillment.courier_assignment — ADR 0042" },
      { label: "Delivered", at: "2026-08-21T19:12:00" },
    ],
  },
  {
    id: "QO-4795", locationId: "loc-chi", channelId: "ch-tg", fulfilment: "DELIVERY",
    createdAt: "2026-08-21T18:35:00", closedAt: "2026-08-21T19:09:00",
    acceptSec: 52, branchSec: 96, prepSec: 1_140, courierSec: null, totalSec: 2_040,
    promiseMinutes: 35, lateMinutes: null, status: "COMPLETED",
    total: 98_000, itemsSummary: "Shavla ×2, Ko'k choy ×1",
    customer: "Ozoda Karimova", phone: "+998935512078",
    operator: "Telegram bot", operatorMachine: true, courier: "Alisher Karimov",
    reasonCode: null,
    kitchen: [
      { label: "Received", at: "2026-08-21T18:35:00" },
      { label: "Confirmed", at: "2026-08-21T18:35:52" },
      { label: "Fired", at: null, unbuilt: "kitchen.tickets.started_at — ADR 0041" },
      { label: "Ready", at: "2026-08-21T18:56:28" },
    ],
    logistics: [
      { label: "Courier assigned", at: "2026-08-21T18:57:00", actor: "Alisher Karimov" },
      { label: "Picked up", at: null, unbuilt: "fulfillment.courier_assignment — ADR 0042" },
      { label: "Delivered", at: "2026-08-21T19:09:00" },
    ],
  },
  {
    id: "QO-4757", locationId: "loc-yun", channelId: "ch-hall", fulfilment: "DINE_IN",
    createdAt: "2026-08-21T17:52:00", closedAt: "2026-08-21T18:31:00",
    acceptSec: 18, branchSec: 44, prepSec: 1_320, courierSec: null, totalSec: 2_340,
    promiseMinutes: 30, lateMinutes: null, status: "COMPLETED",
    total: 214_000, itemsSummary: "Toshkent oshi ×3, Achichuk ×2, Qora choy ×3",
    customer: null, phone: null,
    operator: "Zilola Xolmatova", courier: null,
    reasonCode: null,
    kitchen: [
      { label: "Received", at: "2026-08-21T17:52:00" },
      { label: "Confirmed", at: "2026-08-21T17:52:18" },
      { label: "Fired", at: null, unbuilt: "kitchen.tickets.started_at — ADR 0041" },
      { label: "Ready", at: "2026-08-21T18:14:44" },
    ],
    logistics: [
      { label: "Served in hall", at: "2026-08-21T18:31:00" },
    ],
  },
  {
    id: "QO-4739", locationId: "loc-ser", channelId: "ch-ye", fulfilment: "DELIVERY",
    createdAt: "2026-08-21T15:12:00", closedAt: "2026-08-21T15:58:00",
    acceptSec: 88, branchSec: 176, prepSec: 1_560, courierSec: null, totalSec: 2_760,
    promiseMinutes: 40, lateMinutes: null, status: "COMPLETED",
    total: 127_000, itemsSummary: "Tovuq kabob ×2, Tandir non ×2",
    customer: "Nodira Toshpo'latova", phone: "+998977712095",
    operator: "Yandex Eats", operatorMachine: true, courier: "Yandex kuryeri",
    reasonCode: null,
    kitchen: [
      { label: "Received", at: "2026-08-21T15:12:00" },
      { label: "Confirmed", at: "2026-08-21T15:13:28" },
      { label: "Fired", at: null, unbuilt: "kitchen.tickets.started_at — ADR 0041" },
      { label: "Ready", at: "2026-08-21T15:42:24" },
    ],
    logistics: [
      { label: "Handed to aggregator", at: "2026-08-21T15:44:00" },
      { label: "Delivered", at: null, unbuilt: "Yandex does not report the drop — ADR 0040" },
    ],
  },
  {
    id: "QO-4808", locationId: "loc-chi", channelId: "ch-phone", fulfilment: "DELIVERY",
    createdAt: "2026-08-21T18:52:00", closedAt: "2026-08-21T19:11:00",
    acceptSec: 132, branchSec: 208, prepSec: null, courierSec: null, totalSec: 1_140,
    promiseMinutes: 35, lateMinutes: null, status: "CANCELLED",
    total: 91_000, itemsSummary: "Lo'la kabob ×2",
    customer: "Sardor Yo'ldoshev", phone: "+998934421890",
    operator: "Nigora Sultonova", courier: null,
    reasonCode: "ITEM_UNAVAILABLE", reasonText: "Lo'la kabob — qiyma tugadi",
    kitchen: [
      { label: "Received", at: "2026-08-21T18:52:00" },
      { label: "Confirmed", at: "2026-08-21T18:54:12" },
      { label: "Cancelled", at: "2026-08-21T19:11:00", severity: "incident", actor: "Nigora Sultonova · ITEM_UNAVAILABLE" },
    ],
    logistics: [
      { label: "No courier assigned", at: null, note: "Cancelled before dispatch" },
    ],
  },
  {
    id: "QO-4812", locationId: "loc-yun", channelId: "ch-ig", fulfilment: "PICKUP",
    createdAt: "2026-08-21T19:02:00", closedAt: "2026-08-21T19:29:00",
    acceptSec: 306, branchSec: 214, prepSec: 900, courierSec: null, totalSec: 1_620,
    promiseMinutes: 25, lateMinutes: null, status: "COMPLETED",
    total: 42_000, itemsSummary: "Varaqi somsa ×3",
    customer: "Aziza Nurmatova", phone: "+998909903318",
    operator: "Zilola Xolmatova", courier: null,
    reasonCode: null,
    kitchen: [
      { label: "Received", at: "2026-08-21T19:02:00" },
      { label: "Confirmed", at: "2026-08-21T19:07:06" },
      { label: "Fired", at: null, unbuilt: "kitchen.tickets.started_at — ADR 0041" },
      { label: "Ready", at: "2026-08-21T19:25:40" },
    ],
    logistics: [
      { label: "Waiting on the pass", at: "2026-08-21T19:25:40" },
      { label: "Collected", at: "2026-08-21T19:29:00", note: "ready→collected is not recorded — ADR 0041" },
    ],
  },
];

/* The summary line above the late table. Stated, not computed on the face, so
 * the screen and the export cannot drift. */
export const LATE_SUMMARY = { count: 9, medianMinutes: 14, worstMinutes: 41, total: 214 };

/* ── 7.2 «Агрегаторы» ──────────────────────────────────────────────────────
 * The liveness matrix is a max(created_at) group by (location, channel) over a
 * small set — cheap on PostgreSQL today, and the one genuinely operational
 * thing on an otherwise historical screen. A dead channel loses money in
 * silence and is invisible everywhere else in the console.
 *
 * Thresholds are suppressed for a closed branch: an amber cell for a branch
 * that shut at 16:20 trains people to ignore amber cells.
 */

export const AGGREGATOR_CHANNELS = ["ch-ye", "ch-uz", "ch-ex"];

export const LIVENESS = [
  { locationId: "loc-chi", cells: { "ch-ye": "2026-08-21T19:26:00", "ch-uz": "2026-08-21T15:02:00", "ch-ex": "2026-08-21T06:10:00" } },
  { locationId: "loc-yun", cells: { "ch-ye": "2026-08-21T19:31:00", "ch-uz": "2026-08-21T18:55:00", "ch-ex": null } },
  { locationId: "loc-ser", cells: { "ch-ye": "2026-08-21T16:14:00", "ch-uz": "2026-08-21T15:48:00", "ch-ex": "2026-08-21T11:20:00" } },
];

export const AGGREGATOR_ORDERS = [
  { id: "QO-4739", channelId: "ch-ye", externalId: "YE-8841203", locationId: "loc-ser", total: 127_000, commission: null, status: "COMPLETED", at: "2026-08-21T15:12:00" },
  { id: "QO-4763", channelId: "ch-ye", externalId: "YE-8841577", locationId: "loc-yun", total: 318_000, commission: null, status: "COMPLETED", at: "2026-08-21T18:04:00" },
  { id: "QO-4788", channelId: "ch-uz", externalId: "UZ-771204", locationId: "loc-yun", total: 142_000, commission: null, status: "COMPLETED", at: "2026-08-21T18:31:00" },
  { id: "QO-4742", channelId: "ch-uz", externalId: "UZ-771188", locationId: "loc-chi", total: 88_000, commission: null, status: "REJECTED", at: "2026-08-21T15:02:00", reasonCode: "branch_busy" },
  { id: "QO-4701", channelId: "ch-ex", externalId: "EX-44107", locationId: "loc-chi", total: 64_000, commission: null, status: "COMPLETED", at: "2026-08-21T06:10:00" },
  { id: "QO-4698", channelId: "ch-ex", externalId: "EX-44092", locationId: "loc-ser", total: 156_000, commission: null, status: "COMPLETED", at: "2026-08-21T11:20:00" },
];

/* ── the export centre (§2.11) ─────────────────────────────────────────────
 * An asynchronous job with a lifecycle needs a place to live; a toast that has
 * scrolled away is not that place. Every row is also an ADR 0027 audit fact,
 * which is why the disabled account's row is still here — «who took the
 * customer base last Tuesday» has to stay answerable after the account is gone.
 */

export const EXPORT_QUOTA = { usedRows: 12_400, capRows: 50_000 };

export const EXPORTS = [
  {
    id: "exp-8841", report: "Order log", filters: "01.08–21.08 · Chilonzor, Yunusobod · delivery",
    columns: 12, pii: true, rows: 12_400, requestedBy: "Nigora Sultonova",
    createdAt: "2026-08-21T18:52:00", expiresAt: "2026-08-21T21:30:00", status: "READY",
  },
  {
    id: "exp-8840", report: "Stage durations", filters: "21.08 · all branches · all channels",
    columns: 8, pii: false, rows: null, requestedBy: "Bekzod Tursunov",
    createdAt: "2026-08-21T19:31:00", expiresAt: null, status: "RUNNING",
  },
  {
    id: "exp-8839", report: "Daily operating table", filters: "01.07–31.07 · all branches",
    columns: 14, pii: false, rows: null, requestedBy: "Bekzod Tursunov",
    createdAt: "2026-08-21T19:33:00", expiresAt: null, status: "QUEUED",
  },
  {
    id: "exp-8838", report: "Product sales", filters: "01.08–21.08 · Sergeli · pickup",
    columns: 9, pii: false, rows: null, requestedBy: "Zilola Xolmatova",
    createdAt: "2026-08-21T19:33:00", expiresAt: null, status: "QUEUED",
  },
  {
    id: "exp-8802", report: "Customer base", filters: "01.01–12.08 · all branches",
    columns: 11, pii: true, rows: 48_900, requestedBy: "Nodira Abdullayeva",
    requesterDisabled: "Account disabled 12.08",
    createdAt: "2026-08-12T11:04:00", expiresAt: "2026-08-12T13:04:00", status: "EXPIRED",
  },
  {
    id: "exp-8791", report: "Aggregator log", filters: "01.08–10.08 · all branches · aggregators",
    columns: 8, pii: false, rows: null, requestedBy: "Nigora Sultonova",
    createdAt: "2026-08-10T09:22:00", expiresAt: null, status: "FAILED",
    correlationId: "01J9M2X4QK7ZC3PD",
  },
];

/* ── what this prototype did not build, and why ────────────────────────────
 * Rendered on the screen rather than left as a comment. A missing report has to
 * be legibly unbuilt, not apparently broken, and the honest list is also the
 * backlog.
 */

export const NOT_PROTOTYPED = [
  { view: "7.3 Branch & SLA", why: "The per-branch bucket matrix needs reporting.agg_sla_bucket_day; a live self-join over order_state_history is quadratic. The tenant-wide distribution is on the overview instead.", adr: "ADR 0043" },
  { view: "7.4 Courier reports", why: "Entirely blocked. Shifts, assignments, distances and the external-delivery reconciliation do not exist as data. The reconciliation table is the one report that reliably finds money and it is worth waiting for.", adr: "ADR 0042" },
  { view: "7.5 Staff reports", why: "order_state_history.actor_id is a varchar, not a principal, so an operator leaderboard cannot join to a staff dimension. Machine principals are marked in the stage table meanwhile.", adr: "ADR 0039" },
  { view: "7.6 Customer analytics", why: "Cohorts, LTV and repeat share all need is_first_order precomputed. The behavioural funnel needs analytics.events and is the one thing in this spec that cannot be backfilled — it should start emitting before any screen reads it.", adr: "ADR 0043" },
  { view: "7.7 Product analytics", why: "«Продажи» is cheap today from order_lines; ABC and XYZ need cumulative Pareto and standard deviation over a month of lines. Monthly, deliberately three clicks away.", adr: "ADR 0043" },
  { view: "7.8 Forecast · 7.9 Marketing · 7.10 Geography", why: "Monthly or seasonal surfaces. Promotion redemptions have no owning ADR at all; the nearest is 0044.", adr: "ADR 0043 · 0044" },
  { view: "7.2 «Сводка»", why: "The branch × channel pivot is a per-day per-branch per-channel matrix — exactly the shape that becomes a checkout-latency incident when run against OLTP tables indexed for point writes.", adr: "ADR 0043" },
  { view: "2.12 Metric dictionary", why: "Its per-number half is built — every figure here opens its definition. The standalone searchable list is three clicks deep and settles arguments rather than running a service.", adr: "ADR 0043" },
];
