/* Fixtures and queue policy for the order board and the order detail.
 *
 * Two things live here and nothing else does:
 *
 * 1. **The data**, authored backwards from the states the screen must show. The
 *    awkward cases are deliberate, not accidental: an order forty minutes late
 *    with its courier off shift, a POS export that failed forty minutes after
 *    creation, an aggregator order whose partner total does not reconcile, a
 *    branch force-closed mid-service, a rejected order carrying a *losing*
 *    approval decision from an operator whose account has since been disabled.
 *
 * 2. **The policy the server would own.** Severity, the queue comparator and the
 *    per-order action list are computed here rather than in the screen, because
 *    the specification is explicit (orders.md §4.2) that availability is
 *    server-supplied and the client renders exactly what it is handed. Keeping
 *    them out of Orders.jsx keeps that boundary visible in the file layout.
 *
 * Money is whole som as integers. `ck_order_total_reconciles` is
 * total = subtotal + tax + fee − discount; ADR 0018 prices are VAT-inclusive, so
 * for this tenant tax_minor is 0 and the VAT inside the total is shown derived.
 * One order breaks the identity on purpose — see #0136.
 */

import { NOW } from "./data";

export const NOW_MS = new Date(NOW).getTime();
export const BUSINESS_DATE = "21.08";

/* ── time helpers ──────────────────────────────────────────────────────────
 * dt() and day() come from ./components and are used for absolute stamps. What
 * a queue needs on top of that is relative: how long ago, how long until.
 */

export const minsTo = (iso) => Math.round((new Date(iso).getTime() - NOW_MS) / 60000);
export const secsTo = (iso) => Math.round((new Date(iso).getTime() - NOW_MS) / 1000);

/** "12 min" · "1 h 04 min". Durations are read, not compared, so they get words. */
export function dur(minutes) {
  const m = Math.abs(Math.round(minutes));
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, "0")} min`;
}

/** "01:12" — a countdown an operator reads while the customer waits. */
export function countdown(iso) {
  const s = Math.max(0, secsTo(iso));
  return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

/* ── status vocabulary ─────────────────────────────────────────────────────
 * ordering.orders.status, ck_order_status. Code-owned; no tenant reorders or
 * extends it. The ru/uz columns are what an operator actually says out loud and
 * are carried so the console can be switched without re-deriving them.
 */

export const STATUS = {
  RECEIVED:            { label: "Received",   ru: "Принят",           uz: "Qabul qilindi", tone: "info" },
  PAYMENT_AUTHORIZING: { label: "Paying",     ru: "Оплата",           uz: "To'lov",        tone: "neutral" },
  AWAITING_APPROVAL:   { label: "To confirm", ru: "На подтверждении", uz: "Tasdiqlashda",  tone: "pending" },
  PAYMENT_FAILED:      { label: "Payment failed", ru: "Оплата не прошла", uz: "To'lov o'tmadi", tone: "failed" },
  CONFIRMED:           { label: "Confirmed",  ru: "Подтверждён",      uz: "Tasdiqlangan",  tone: "info" },
  REJECTED:            { label: "Rejected",   ru: "Отклонён",         uz: "Rad etilgan",   tone: "failed" },
  EXPIRED:             { label: "Expired",    ru: "Просрочен",        uz: "Muddati o'tgan", tone: "neutral" },
  PREPARING:           { label: "Preparing",  ru: "Готовится",        uz: "Tayyorlanmoqda", tone: "pending" },
  READY:               { label: "Ready",      ru: "Готов",            uz: "Tayyor",        tone: "healthy" },
  FULFILLING:          { label: "Delivering", ru: "В доставке",       uz: "Yetkazilmoqda", tone: "info" },
  COMPLETED:           { label: "Completed",  ru: "Завершён",         uz: "Yakunlandi",    tone: "neutral" },
  CANCELLED:           { label: "Cancelled",  ru: "Отменён",          uz: "Bekor qilingan", tone: "failed" },
};

export const TERMINAL = ["COMPLETED", "CANCELLED", "REJECTED", "EXPIRED"];
export const isTerminal = (o) => TERMINAL.includes(o.status);

export const MODE = {
  DELIVERY: "Delivery",
  PICKUP:   "Pickup",
  DINE_IN:  "Dine-in",
};

export const PAYMENT_PROJECTION = {
  PAID:         { label: "Paid",     tone: "healthy" },
  PENDING:      { label: "Pending",  tone: "pending" },
  FAILED:       { label: "Failed",   tone: "failed" },
  REFUNDED:     { label: "Refunded", tone: "neutral" },
  NOT_REQUIRED: { label: "—",        tone: "neutral" },
};

/* ── the tenant ────────────────────────────────────────────────────────────*/

export const LOCATIONS = [
  { id: "l-yun", name: "Yunusobod", address: "Amir Temur shoh ko'chasi 108", pos: ["print", "export"], inScope: true },
  { id: "l-chi", name: "Chilonzor", address: "Bunyodkor shoh ko'chasi 12", pos: ["export"], inScope: true },
  { id: "l-ser", name: "Sergeli",   address: "Yangi Sergeli 4-kvartal", pos: ["print", "export"], inScope: true,
    forceClosed: { until: "2026-08-21T21:00:00", reason: "Elektr uzilishi — generator ishlamadi" } },
  { id: "l-mir", name: "Mirobod",   address: "Shahrisabz ko'chasi 41", pos: [], inScope: false },
];

export const CHANNELS = [
  { id: "ch-tg",   name: "Telegram bot",  code: "TG-BOT",  systemType: "OWN_BOT" },
  { id: "ch-web",  name: "Sayt",          code: "WEB",     systemType: "OWN_WEB" },
  { id: "ch-app",  name: "Mobil ilova",   code: "APP-IOS", systemType: "OWN_APP" },
  { id: "ch-call", name: "Operator",      code: "CALL",    systemType: "OPERATOR" },
  { id: "ch-wlt",  name: "Wolt",          code: "WOLT",    systemType: "MARKETPLACE" },
  { id: "ch-yan",  name: "Yandex Eats",   code: "YE",      systemType: "MARKETPLACE" },
];

/* Couriers, ADR 0014 / 0042 — not built. Shoxrux is the case the picker exists
 * for: assigned to a live order and off shift since 19:10. */
export const COURIERS = [
  { id: "cr-1", name: "Alisher Karimov",  vehicle: "Mototsikl", load: 1, onShift: true,  km: 2.4 },
  { id: "cr-2", name: "Ravshan Umarov",   vehicle: "Mototsikl", load: 2, onShift: true,  km: 5.1 },
  { id: "cr-3", name: "Otabek Nazarov",   vehicle: "Velosiped", load: 0, onShift: true,  km: 1.2 },
  { id: "cr-4", name: "Shoxrux Qodirov",  vehicle: "Avtomobil", load: 1, onShift: false, km: 7.8,
    offShiftSince: "2026-08-21T19:10:00" },
];

/* Actors. Bekzod's account was disabled after he left; his decisions stay in the
 * history, which is the point of writing attribution once and never rewriting. */
export const ACTORS = {
  "ac-1": { name: "Shohruh Karimov", role: "Operator", disabled: false },
  "ac-2": { name: "Nigora Sattorova", role: "Manager", disabled: false },
  "ac-3": { name: "Bekzod To'xtayev", role: "Operator", disabled: true },
  "ac-sys": { name: "Tizim", role: "System principal", disabled: false },
};

/* ADR 0030 document `ordering.lateness`, per fulfilment mode. Delever ships the
 * threshold and also lets a tenant pick the highlight colour; we take the
 * threshold and refuse the colour. */
export const LATENESS = {
  atRiskBeforeSeconds: 300,
  lateAfterSeconds: 0,
  noPromiseFallbackSeconds: 2700,
};

/* ordering.order_outcome_reasons, kind = CANCELLATION. ADR 0039, not built.
 * The disposition, the liability and the refund live on the reason an admin
 * configured once — never on a checkbox the operator ticks under pressure. */
export const CANCEL_REASONS = [
  { id: "r-1", category: "Customer", name: "Mijoz fikridan qaytdi",
    customerText: "Buyurtmangiz so'rovingizga ko'ra bekor qilindi.",
    disposition: "RETURN_TO_STOCK", liability: "CUSTOMER", refund: "FULL" },
  { id: "r-2", category: "Customer", name: "Telefonga javob bermadi",
    customerText: "Buyurtmani tasdiqlash uchun siz bilan bog'lana olmadik.",
    disposition: "RETURN_TO_STOCK", liability: "VENUE", refund: "FULL" },
  { id: "r-3", category: "Venue", name: "Mahsulot tugadi",
    customerText: "Afsuski, tanlangan taom hozir mavjud emas.",
    disposition: "RELEASE_RESERVE", liability: "VENUE", refund: "FULL" },
  { id: "r-4", category: "Venue", name: "Oshxona ulgurmadi",
    customerText: "Buyurtmani va'da qilingan vaqtda tayyorlay olmadik.",
    disposition: "WRITE_OFF", liability: "VENUE", refund: "FULL" },
  { id: "r-5", category: "Delivery", name: "Kuryer kechikdi",
    customerText: "Yetkazib berish kechikkani uchun buyurtma bekor qilindi.",
    disposition: "WRITE_OFF", liability: "COURIER_SERVICE", refund: "FULL" },
  { id: "r-6", category: "Delivery", name: "Manzilga yetib bo'lmadi",
    customerText: "Ko'rsatilgan manzilga yetib bora olmadik.",
    disposition: "WRITE_OFF", liability: "CUSTOMER", refund: "NONE" },
];

export const DISPOSITION_TEXT = {
  RETURN_TO_STOCK: "Return to stock",
  RELEASE_RESERVE: "Release the reservation",
  WRITE_OFF: "Write off",
  NO_EFFECT: "No stock movement",
};
export const LIABILITY_TEXT = {
  VENUE: "At the venue's cost",
  CUSTOMER: "At the customer's cost",
  COURIER_SERVICE: "At the delivery service's cost",
  PLATFORM: "At the platform's cost",
};
export const REFUND_TEXT = { FULL: "Full refund", NONE: "No refund", DISCRETION: "Refund at discretion" };

/* ── the orders ────────────────────────────────────────────────────────────
 * Nineteen, at 19:34 on the 21st. Twelve are active. Nine are in Attention, and
 * the reason each one is there is different — that is what the tab is for.
 */

const c = (name, phoneTail, opts = {}) => ({
  name, phoneMasked: `+998 ${opts.pfx || "90"} ••• •• ${phoneTail}`,
  ordersCount: opts.n ?? 1, lastOrderAt: opts.last || null,
  guest: !!opts.guest, contactAllowed: opts.contactAllowed !== false,
  anonymizedAt: opts.anonymizedAt || null, origin: opts.origin || "SELF_SERVICE",
});

const L = (n, name, qty, unit, opts = {}) => ({
  n, name, qty, unitMinor: unit,
  baseMinor: opts.base ?? unit * qty, finalMinor: opts.final ?? unit * qty,
  variant: opts.variant || null, sku: opts.sku || null,
  mods: opts.mods || [], note: !!opts.note, soldOut: !!opts.soldOut,
  discountReason: opts.discountReason || null,
});

export const ORDERS = [
  {
    id: "o-0142", number: "0142", version: 7, locationId: "l-yun", channelId: "ch-tg",
    status: "PREPARING", mode: "DELIVERY", createdAt: "2026-08-21T18:44:00",
    promisedAt: "2026-08-21T19:22:00", paymentProjection: "PAID", paymentMethod: "Click",
    customer: c("Dilnoza Rahimova", "67", { pfx: "90", n: 27, last: "2026-08-18T20:12:00" }),
    address: { street: "Amir Temur shoh ko'chasi", house: "108", flat: "24", entrance: "2", floor: "4",
      landmark: "Metro chiqishi yonida", lat: 41.3111, lon: 69.2797, coordinateSource: "ROOFTOP" },
    hasCustomerNote: true,
    lines: [
      L(1, "Toshkent oshi", 2, 63000, { variant: "To'liq", sku: "OSH-TSH-F", mods: [{ group: "Porsiya", option: "To'liq" }, { group: "Qo'shimcha", option: "Qazi", price: 18000 }] }),
      L(2, "Achichuk", 1, 14000, { sku: "SAL-ACH", note: true }),
      L(3, "Ko'k choy", 2, 8000, { sku: "ICH-KCH" }),
    ],
    money: { subtotal: 156000, discount: 0, fee: 15000, deliveryFee: 15000, providerCost: 21000, tax: 0, total: 171000, cashTendered: null },
    courierId: null,
    processes: [
      { key: "ORDER_INVENTORY", name: "Stock reservation", status: "COMPLETED", attempts: 1 },
      { key: "POS_ORDER_EXPORT", name: "Export to POS", status: "COMPLETED", attempts: 1 },
      { key: "ORDER_NOTIFICATION", name: "Customer notification", status: "COMPLETED", attempts: 1 },
    ],
    fiscal: { status: "PENDING", attempts: 0 },
    createdBy: "ac-sys", acceptedBy: "ac-1", acceptedAt: "2026-08-21T18:46:00", revision: 1,
    history: [
      { seq: 1, at: "2026-08-21T18:44:00", from: null, to: "RECEIVED", trigger: "CHECKOUT", actor: "ac-sys" },
      { seq: 2, at: "2026-08-21T18:45:00", from: "RECEIVED", to: "AWAITING_APPROVAL", trigger: "SYSTEM", actor: "ac-sys" },
      { seq: 3, at: "2026-08-21T18:46:00", from: "AWAITING_APPROVAL", to: "CONFIRMED", trigger: "APPROVAL_DECISION", actor: "ac-1" },
      { seq: 4, at: "2026-08-21T18:47:00", from: "CONFIRMED", to: "PREPARING", trigger: "OPERATIONS_ACTION", actor: "ac-1" },
    ],
  },
  {
    id: "o-0139", number: "0139", version: 11, locationId: "l-yun", channelId: "ch-web",
    status: "PREPARING", mode: "DELIVERY", createdAt: "2026-08-21T18:31:00",
    promisedAt: "2026-08-21T19:40:00", paymentProjection: "PAID", paymentMethod: "Payme",
    customer: c("Jamshid Ergashev", "05", { pfx: "90", n: 63, last: "2026-08-21T12:20:00" }),
    address: { street: "Yunusobod 19-kvartal", house: "7", flat: "12", entrance: "1", floor: "5",
      landmark: null, lat: 41.3641, lon: 69.2894, coordinateSource: "ROOFTOP" },
    lines: [
      L(1, "Qo'y kabob", 4, 42000, { sku: "KAB-QOY" }),
      L(2, "Tandir non", 4, 6000, { sku: "NON-TAN" }),
    ],
    money: { subtotal: 192000, discount: 0, fee: 15000, deliveryFee: 15000, providerCost: 19000, tax: 0, total: 207000, cashTendered: null },
    courierId: "cr-2",
    processes: [
      { key: "ORDER_INVENTORY", name: "Stock reservation", status: "COMPLETED", attempts: 1 },
      { key: "POS_ORDER_EXPORT", name: "Export to POS", status: "MANUAL_ACTION_REQUIRED", attempts: 4,
        nextAttemptAt: null, caption: "POS: product KAB-QOY is not mapped",
        error: "PRODUCT_NOT_MAPPED: KAB-QOY has no POS article",
        fix: "Open the catalog mapping table with KAB-QOY selected" },
      { key: "ORDER_NOTIFICATION", name: "Customer notification", status: "COMPLETED", attempts: 1 },
    ],
    fiscal: { status: "PENDING", attempts: 0 },
    createdBy: "ac-sys", acceptedBy: "ac-2", acceptedAt: "2026-08-21T18:33:00", revision: 2,
  },
  {
    id: "o-0151", number: "0151", version: 2, locationId: "l-chi", channelId: "ch-app",
    status: "AWAITING_APPROVAL", mode: "DELIVERY", createdAt: "2026-08-21T19:29:00",
    approvalDeadlineAt: "2026-08-21T19:35:12", promisedAt: "2026-08-21T20:20:00",
    paymentProjection: "PAID", paymentMethod: "Click",
    customer: c("Sardor Yo'ldoshev", "90", { pfx: "93", n: 4, last: "2026-08-11T13:40:00" }),
    address: { street: "Chilonzor 9-kvartal", house: "42", flat: "9", entrance: "3", floor: "2",
      landmark: "Maktab ro'parasida", lat: 41.2755, lon: 69.2043, coordinateSource: "INTERPOLATED" },
    lines: [
      L(1, "Chayonli osh", 1, 67000, { variant: "Katta", sku: "OSH-CHY-L", mods: [{ group: "Porsiya", option: "Katta", price: 15000 }] }),
      L(2, "Qo'y sho'rva", 2, 34000, { sku: "SHO-QOY" }),
      L(3, "Tandir somsa", 6, 12000, { sku: "NON-SOM" }),
      L(4, "Ayron", 3, 10000, { sku: "ICH-AYR" }),
    ],
    money: { subtotal: 237000, discount: 20000, fee: 15000, deliveryFee: 15000, providerCost: 17000, tax: 0, total: 232000, cashTendered: null },
    discounts: [{ code: "Promo OSH20", sourceType: "PROMOTION", sourceId: "pr-osh20", sourceVersion: 3, amount: 20000 }],
    courierId: null,
    processes: [
      { key: "RESTAURANT_APPROVAL", name: "Venue approval", status: "WAITING", attempts: 1, nextAttemptAt: "2026-08-21T19:35:12" },
      { key: "ORDER_INVENTORY", name: "Stock reservation", status: "COMPLETED", attempts: 1 },
    ],
    fiscal: { status: "PENDING", attempts: 0 },
    createdBy: "ac-sys", revision: 1,
  },
  {
    /* The counterexample to #0151: also awaiting approval, also in Attention,
     * and not urgent. Without one of these an operator learns that the tab
     * means "panic" rather than "look". */
    id: "o-0152", number: "0152", version: 1, locationId: "l-yun", channelId: "ch-tg",
    status: "AWAITING_APPROVAL", mode: "PICKUP", createdAt: "2026-08-21T19:30:00",
    approvalDeadlineAt: "2026-08-21T19:40:00", promisedAt: "2026-08-21T20:05:00",
    paymentProjection: "PAID", paymentMethod: "Payme",
    customer: c("Bahodir Ochilov", "72", { pfx: "99", n: 5, last: "2026-08-13T11:05:00" }),
    address: null,
    lines: [L(1, "Tandir somsa", 4, 12000, { sku: "NON-SOM" }), L(2, "Ko'k choy", 1, 8000, { sku: "ICH-KCH" })],
    money: { subtotal: 56000, discount: 0, fee: 0, deliveryFee: 0, providerCost: 0, tax: 0, total: 56000, cashTendered: null },
    courierId: null,
    processes: [
      { key: "RESTAURANT_APPROVAL", name: "Venue approval", status: "WAITING", attempts: 1, nextAttemptAt: "2026-08-21T19:40:00" },
      { key: "ORDER_INVENTORY", name: "Stock reservation", status: "COMPLETED", attempts: 1 },
    ],
    fiscal: { status: "PENDING", attempts: 0 },
    createdBy: "ac-sys", revision: 1,
  },
  {
    id: "o-0147", number: "0147", version: 4, locationId: "l-chi", channelId: "ch-web",
    status: "PAYMENT_FAILED", mode: "DELIVERY", createdAt: "2026-08-21T19:06:00",
    promisedAt: null, paymentProjection: "FAILED", paymentMethod: "Click",
    customer: c("Malika Yusupova", "14", { pfx: "91", n: 1, last: "2026-07-02T18:05:00" }),
    address: { street: "Katta Qo'rg'on ko'chasi", house: "3", flat: null, entrance: null, floor: null,
      landmark: "Ko'k darvoza, burchakdagi uy", lat: null, lon: null, coordinateSource: "NOT_GEOCODED" },
    lines: [L(1, "Shavla", 2, 38000, { variant: "To'liq", sku: "OSH-SHV" })],
    money: { subtotal: 76000, discount: 0, fee: 15000, deliveryFee: 15000, providerCost: 15000, tax: 0, total: 91000, cashTendered: null },
    courierId: null,
    processes: [
      { key: "ORDER_PAYMENT", name: "Payment", status: "FAILED_RETRYABLE", attempts: 2,
        nextAttemptAt: null, caption: "Click declined the payment — insufficient funds",
        error: "PROVIDER_DECLINED: insufficient funds (Click)" },
      { key: "ORDER_INVENTORY", name: "Stock reservation", status: "COMPENSATED", attempts: 1 },
    ],
    fiscal: null,
    createdBy: "ac-sys", revision: 1,
  },
  {
    id: "o-0138", number: "0138", version: 14, locationId: "l-yun", channelId: "ch-tg",
    status: "FULFILLING", mode: "DELIVERY", createdAt: "2026-08-21T18:12:00",
    promisedAt: "2026-08-21T18:54:00", paymentProjection: "PENDING", paymentMethod: "Naqd",
    customer: c("Zulfiya Abdullayeva", "31", { pfx: "94", n: 8, last: "2026-08-09T19:22:00" }),
    address: { street: "Bodomzor yo'li", house: "26", flat: "77", entrance: "4", floor: "9",
      landmark: "Bodomzor metro", lat: 41.3502, lon: 69.2881, coordinateSource: "ROOFTOP" },
    lines: [
      L(1, "Toshkent oshi", 3, 45000, { sku: "OSH-TSH" }),
      L(2, "Achichuk", 2, 14000, { sku: "SAL-ACH" }),
      L(3, "Coca-Cola 0.5", 2, 12000, { sku: "ICH-CC5" }),
    ],
    money: { subtotal: 187000, discount: 0, fee: 18000, deliveryFee: 18000, providerCost: 26000, tax: 0, total: 205000, cashTendered: 250000 },
    courierId: "cr-4",
    processes: [
      { key: "ORDER_INVENTORY", name: "Stock reservation", status: "COMPLETED", attempts: 1 },
      { key: "POS_ORDER_EXPORT", name: "Export to POS", status: "COMPLETED", attempts: 1 },
      { key: "ORDER_FULFILLMENT", name: "Delivery", status: "WAITING", attempts: 1 },
    ],
    fiscal: { status: "PENDING", attempts: 0 },
    createdBy: "ac-sys", acceptedBy: "ac-1", acceptedAt: "2026-08-21T18:14:00", revision: 1,
    history: [
      { seq: 1, at: "2026-08-21T18:12:00", from: null, to: "RECEIVED", trigger: "CHECKOUT", actor: "ac-sys" },
      { seq: 2, at: "2026-08-21T18:14:00", from: "RECEIVED", to: "CONFIRMED", trigger: "APPROVAL_DECISION", actor: "ac-1" },
      { seq: 3, at: "2026-08-21T18:15:00", from: "CONFIRMED", to: "PREPARING", trigger: "OPERATIONS_ACTION", actor: "ac-1" },
      { seq: 5, at: "2026-08-21T18:41:00", from: "PREPARING", to: "READY", trigger: "OPERATIONS_ACTION", actor: "ac-2" },
      { seq: 6, at: "2026-08-21T18:49:00", from: "READY", to: "FULFILLING", trigger: "OPERATIONS_ACTION", actor: "ac-2" },
    ],
  },
  {
    id: "o-0144", number: "0144", version: 6, locationId: "l-chi", channelId: "ch-call",
    status: "READY", mode: "DELIVERY", createdAt: "2026-08-21T18:58:00",
    promisedAt: "2026-08-21T19:38:00", paymentProjection: "PENDING", paymentMethod: "Naqd",
    customer: c("Rustam Sobirov", "42", { pfx: "97", n: 12, last: "2026-08-15T20:01:00" }),
    address: { street: "Furqat ko'chasi", house: "14", flat: "3", entrance: "1", floor: "1",
      landmark: null, lat: 41.2891, lon: 69.2192, coordinateSource: "ROOFTOP" },
    lines: [
      L(1, "Qo'y kabob", 2, 48000, { sku: "KAB-QOY", base: 96000, final: 84000, discountReason: "Happy hour −12 000" }),
      L(2, "Tandir non", 2, 6000, { sku: "NON-TAN" }),
    ],
    money: { subtotal: 108000, discount: 12000, fee: 15000, deliveryFee: 15000, providerCost: 15000, tax: 0, total: 111000, cashTendered: 150000 },
    discounts: [{ code: "Happy hour 18:00–19:00", sourceType: "PROMOTION", sourceId: "pr-hh", sourceVersion: 1, amount: 12000 }],
    courierId: null,
    processes: [
      { key: "ORDER_INVENTORY", name: "Stock reservation", status: "COMPLETED", attempts: 1 },
      { key: "POS_ORDER_EXPORT", name: "Export to POS", status: "COMPLETED", attempts: 1 },
    ],
    fiscal: { status: "PENDING", attempts: 0 },
    createdBy: "ac-1", acceptedBy: "ac-1", acceptedAt: "2026-08-21T18:59:00", revision: 1,
  },
  {
    id: "o-0145", number: "0145", version: 5, locationId: "l-yun", channelId: "ch-tg",
    status: "READY", mode: "PICKUP", createdAt: "2026-08-21T19:02:00",
    promisedAt: "2026-08-21T19:45:00", paymentProjection: "PAID", paymentMethod: "Payme",
    customer: c("Kamola Tursunova", "58", { pfx: "99", n: 3, last: "2026-08-02T14:11:00" }),
    address: null,
    lines: [L(1, "Mastava", 2, 28000, { sku: "SHO-MAS" }), L(2, "Tandir non", 3, 6000, { sku: "NON-TAN" })],
    money: { subtotal: 74000, discount: 0, fee: 0, deliveryFee: 0, providerCost: 0, tax: 0, total: 74000, cashTendered: null },
    courierId: null,
    processes: [{ key: "ORDER_INVENTORY", name: "Stock reservation", status: "COMPLETED", attempts: 1 }],
    fiscal: { status: "ISSUED", attempts: 1, receiptId: "FR-2026-0821-0145", sign: "3117 4402 9981", inn: "302847119" },
    createdBy: "ac-sys", acceptedBy: "ac-sys", acceptedAt: "2026-08-21T19:03:00", revision: 1,
  },
  {
    id: "o-0148", number: "0148", version: 3, locationId: "l-yun", channelId: "ch-web",
    status: "CONFIRMED", mode: "DELIVERY", createdAt: "2026-08-21T19:14:00",
    promisedAt: "2026-08-21T20:05:00", paymentProjection: "PAID", paymentMethod: "Uzum Bank",
    customer: c("Nodira Ismoilova", "76", { pfx: "90", n: 19, last: "2026-08-19T13:02:00" }),
    address: { street: "Shahrisabz ko'chasi", house: "41", flat: "18", entrance: "2", floor: "6",
      landmark: null, lat: 41.3095, lon: 69.2831, coordinateSource: "ROOFTOP" },
    lines: [
      L(1, "Qo'y sho'rva", 1, 34000, { sku: "SHO-QOY" }),
      L(2, "Varaqi somsa", 2, 14000, { sku: "NON-VSM" }),
      L(3, "Qora choy", 2, 8000, { sku: "ICH-QCH" }),
    ],
    money: { subtotal: 78000, discount: 0, fee: 15000, deliveryFee: 15000, providerCost: 15000, tax: 0, total: 93000, cashTendered: null },
    courierId: null,
    processes: [
      { key: "ORDER_INVENTORY", name: "Stock reservation", status: "COMPLETED", attempts: 1 },
      { key: "ORDER_NOTIFICATION", name: "Customer notification", status: "FAILED_RETRYABLE", attempts: 3,
        nextAttemptAt: "2026-08-21T19:36:00", caption: "Customer notification failed — the bot is blocked",
        error: "TELEGRAM_BLOCKED: customer blocked the bot" },
    ],
    fiscal: { status: "PENDING", attempts: 0 },
    createdBy: "ac-sys", acceptedBy: "ac-2", acceptedAt: "2026-08-21T19:15:00", revision: 1,
  },
  {
    id: "o-0141", number: "0141", version: 8, locationId: "l-ser", channelId: "ch-call",
    status: "PREPARING", mode: "DELIVERY", createdAt: "2026-08-21T18:52:00",
    promisedAt: "2026-08-21T19:52:00", paymentProjection: "PENDING", paymentMethod: "Naqd",
    customer: c("Umida Xolmatova", "63", { pfx: "93", n: 6, last: "2026-08-14T19:44:00" }),
    address: { street: "Yangi Sergeli 4-kvartal", house: "18", flat: "44", entrance: "5", floor: "3",
      landmark: "Do'kon orqasida", lat: 41.2211, lon: 69.2201, coordinateSource: "ROOFTOP" },
    callbackRequested: true, callbackRequestedAt: "2026-08-21T19:12:00",
    lines: [
      L(1, "Sazan salat", 1, 26000, { sku: "SAL-SAZ" }),
      L(2, "Qo'y kabob", 2, 42000, { sku: "KAB-QOY" }),
      L(3, "Tandir non", 2, 6000, { sku: "NON-TAN" }),
    ],
    money: { subtotal: 122000, discount: 0, fee: 15000, deliveryFee: 15000, providerCost: 15000, tax: 0, total: 137000, cashTendered: 200000 },
    courierId: "cr-1",
    processes: [
      { key: "ORDER_INVENTORY", name: "Stock reservation", status: "COMPLETED", attempts: 1 },
      { key: "POS_ORDER_EXPORT", name: "Export to POS", status: "COMPLETED", attempts: 1 },
    ],
    fiscal: { status: "PENDING", attempts: 0 },
    createdBy: "ac-1", acceptedBy: "ac-1", acceptedAt: "2026-08-21T18:53:00", revision: 1,
  },
  {
    id: "o-0136", number: "0136", version: 9, locationId: "l-chi", channelId: "ch-wlt",
    status: "FULFILLING", mode: "DELIVERY", createdAt: "2026-08-21T18:04:00",
    promisedAt: "2026-08-21T19:50:00", paymentProjection: "PAID", paymentMethod: "Wolt (partner)",
    customer: c("Abdurahmonov Shohjahon Mirzayevich", "27", { pfx: "90", n: 2, last: "2026-08-06T20:33:00" }),
    address: { street: "Mukimiy ko'chasi", house: "9A", flat: null, entrance: null, floor: null,
      landmark: "Wolt kuryeri olib ketadi", lat: 41.2833, lon: 69.2276, coordinateSource: "ROOFTOP" },
    externalRefs: [
      { provider: "Wolt", type: "PARTNER_DISPLAY_CODE", value: "WLT-88213" },
      { provider: "Wolt", type: "DELIVERY_CLAIM_ID", value: "clm-9f21e4" },
    ],
    pricingAuthority: "EXTERNAL", arithmeticVerified: false,
    lines: [
      L(1, "Toshkent oshi qazi va bedana tuxumi bilan", 2, 45000, { sku: "OSH-TSH-QZ" }),
      L(2, "Chayonli osh", 1, 52000, { sku: "OSH-CHY" }),
      L(3, "Coca-Cola 0.5", 3, 12000, { sku: "ICH-CC5" }),
    ],
    money: { subtotal: 178000, discount: 0, fee: 0, deliveryFee: 0, providerCost: 0, tax: 0, total: 174000, cashTendered: null },
    courierId: null,
    processes: [
      { key: "ORDER_INVENTORY", name: "Stock reservation", status: "COMPLETED", attempts: 1 },
      { key: "POS_ORDER_EXPORT", name: "Export to POS", status: "COMPLETED", attempts: 1 },
    ],
    fiscal: { status: "REQUESTED", attempts: 1 },
    createdBy: "ac-sys", acceptedBy: "ac-sys", acceptedAt: "2026-08-21T18:05:00", revision: 1,
  },
  {
    id: "o-0153", number: "0153", version: 1, locationId: "l-chi", channelId: "ch-call",
    status: "RECEIVED", mode: "DINE_IN", createdAt: "2026-08-21T19:33:00",
    promisedAt: "2026-08-21T20:00:00", paymentProjection: "NOT_REQUIRED", paymentMethod: null,
    customer: c("Farrux Sultonov", "88", { pfx: "88", n: 1, last: null }),
    address: null,
    lines: [L(1, "Tovuq kabob", 2, 32000, { sku: "KAB-TOV" }), L(2, "Achichuk", 1, 14000, { sku: "SAL-ACH" })],
    money: { subtotal: 78000, discount: 0, fee: 3000, deliveryFee: 0, providerCost: 0, tax: 0, total: 81000, cashTendered: null },
    fees: [{ code: "Xizmat haqi", amount: 3000 }],
    courierId: null,
    processes: [{ key: "ORDER_INVENTORY", name: "Stock reservation", status: "WAITING", attempts: 1 }],
    fiscal: { status: "PENDING", attempts: 0 },
    createdBy: "ac-1", revision: 1,
  },
  {
    id: "o-0154", number: "0154", version: 1, locationId: "l-yun", channelId: "ch-app",
    status: "PAYMENT_AUTHORIZING", mode: "DELIVERY", createdAt: "2026-08-21T19:32:00",
    promisedAt: "2026-08-21T20:18:00", paymentProjection: "PENDING", paymentMethod: "Click",
    customer: c("Aziza Nurmatova", "19", { pfx: "91", n: 2, last: "2026-07-28T12:10:00" }),
    address: { street: "Cho'ponota ko'chasi", house: "5", flat: "31", entrance: "2", floor: "7",
      landmark: null, lat: 41.3712, lon: 69.2955, coordinateSource: "ROOFTOP" },
    lines: [L(1, "Toshkent oshi", 1, 45000, { sku: "OSH-TSH" }), L(2, "Ayron", 1, 10000, { sku: "ICH-AYR" })],
    money: { subtotal: 55000, discount: 0, fee: 15000, deliveryFee: 15000, providerCost: 15000, tax: 0, total: 70000, cashTendered: null },
    courierId: null,
    processes: [{ key: "ORDER_PAYMENT", name: "Payment", status: "WAITING", attempts: 1, nextAttemptAt: "2026-08-21T19:36:00" }],
    fiscal: { status: "PENDING", attempts: 0 },
    createdBy: "ac-sys", revision: 1,
  },
  {
    id: "o-0132", number: "0132", version: 18, locationId: "l-yun", channelId: "ch-web",
    status: "COMPLETED", mode: "DELIVERY", createdAt: "2026-08-21T17:02:00",
    promisedAt: "2026-08-21T17:45:00", completedAt: "2026-08-21T18:07:00",
    paymentProjection: "PAID", paymentMethod: "Payme",
    customer: c("Oybek Rasulov", "34", { pfx: "90", n: 41, last: "2026-08-21T17:02:00" }),
    address: { street: "Amir Temur shoh ko'chasi", house: "60", flat: "102", entrance: "1", floor: "10",
      landmark: null, lat: 41.3168, lon: 69.2809, coordinateSource: "ROOFTOP" },
    lines: [
      L(1, "Qo'y kabob", 6, 42000, { sku: "KAB-QOY" }),
      L(2, "Tandir non", 6, 6000, { sku: "NON-TAN" }),
      L(3, "Ko'k choy", 4, 8000, { sku: "ICH-KCH" }),
    ],
    money: { subtotal: 320000, discount: 0, fee: 15000, deliveryFee: 15000, providerCost: 22000, tax: 0, total: 335000, cashTendered: null },
    courierId: "cr-2",
    processes: [
      { key: "ORDER_INVENTORY", name: "Stock reservation", status: "COMPLETED", attempts: 1 },
      { key: "POS_ORDER_EXPORT", name: "Export to POS", status: "COMPLETED", attempts: 1 },
    ],
    fiscal: { status: "BLOCKED", attempts: 3, blockedReason: "CLASSIFICATION_MISSING",
      blockedShort: "Fiscal blocked — no classification code",
      blockedText: "Qo'y kabob has no fiscal classification code", inn: "302847119", failureCode: "FISCAL_CLASS_MISSING" },
    createdBy: "ac-sys", acceptedBy: "ac-2", acceptedAt: "2026-08-21T17:04:00", revision: 1,
  },
  {
    id: "o-0129", number: "0129", version: 9, locationId: "l-chi", channelId: "ch-tg",
    status: "COMPLETED", mode: "PICKUP", createdAt: "2026-08-21T16:40:00",
    promisedAt: "2026-08-21T17:10:00", completedAt: "2026-08-21T17:08:00",
    paymentProjection: "PAID", paymentMethod: "Naqd",
    customer: c("Shahzod Yusupov", "51", { pfx: "94", n: 7, last: "2026-08-21T16:40:00" }),
    address: null,
    lines: [L(1, "Mastava", 1, 28000, { sku: "SHO-MAS" }), L(2, "Tandir somsa", 2, 12000, { sku: "NON-SOM" })],
    money: { subtotal: 52000, discount: 0, fee: 0, deliveryFee: 0, providerCost: 0, tax: 0, total: 52000, cashTendered: 100000 },
    courierId: null,
    processes: [{ key: "ORDER_INVENTORY", name: "Stock reservation", status: "COMPLETED", attempts: 1 }],
    fiscal: { status: "ISSUED", attempts: 1, receiptId: "FR-2026-0821-0129", sign: "8842 1109 3376", inn: "302847119" },
    createdBy: "ac-sys", acceptedBy: "ac-1", acceptedAt: "2026-08-21T16:41:00", revision: 1,
  },
  {
    id: "o-0126", number: "0126", version: 6, locationId: "l-chi", channelId: "ch-call",
    status: "CANCELLED", mode: "DELIVERY", createdAt: "2026-08-21T16:18:00",
    promisedAt: null, paymentProjection: "REFUNDED", paymentMethod: "Click",
    customer: c("Sardor Yo'ldoshev", "90", { pfx: "93", n: 4, last: "2026-08-11T13:40:00" }),
    address: { street: "Chilonzor 9-kvartal", house: "42", flat: "9", entrance: "3", floor: "2",
      landmark: "Maktab ro'parasida", lat: 41.2755, lon: 69.2043, coordinateSource: "INTERPOLATED" },
    lines: [L(1, "Lo'la kabob", 2, 38000, { sku: "KAB-LOL", soldOut: true })],
    money: { subtotal: 76000, discount: 0, fee: 15000, deliveryFee: 15000, providerCost: 0, tax: 0, total: 91000, cashTendered: null },
    courierId: null,
    outcome: { reasonId: "r-3", note: "Qiyma tugadi, ertaga keladi", at: "2026-08-21T16:26:00", by: "ac-1" },
    processes: [{ key: "ORDER_INVENTORY", name: "Stock reservation", status: "COMPENSATED", attempts: 1 }],
    fiscal: { status: "NOT_REQUIRED", attempts: 0 },
    createdBy: "ac-1", revision: 1,
  },
  {
    id: "o-0121", number: "0121", version: 3, locationId: "l-yun", channelId: "ch-tg",
    status: "EXPIRED", mode: "PICKUP", createdAt: "2026-08-21T15:44:00",
    approvalDeadlineAt: "2026-08-21T15:54:00", promisedAt: null,
    paymentProjection: "NOT_REQUIRED", paymentMethod: null,
    customer: c("Mehmon", "—", { guest: true, n: 1 }),
    address: null,
    lines: [L(1, "Tandir somsa", 3, 12000, { sku: "NON-SOM" })],
    money: { subtotal: 36000, discount: 0, fee: 0, deliveryFee: 0, providerCost: 0, tax: 0, total: 36000, cashTendered: null },
    courierId: null,
    processes: [{ key: "RESTAURANT_APPROVAL", name: "Venue approval", status: "COMPENSATED", attempts: 1 }],
    fiscal: { status: "NOT_REQUIRED", attempts: 0 },
    createdBy: "ac-sys", revision: 1,
  },
  {
    id: "o-0118", number: "0118", version: 4, locationId: "l-chi", channelId: "ch-yan",
    status: "REJECTED", mode: "DELIVERY", createdAt: "2026-08-21T15:11:00",
    promisedAt: null, paymentProjection: "REFUNDED", paymentMethod: "Yandex (partner)",
    customer: c("Gulnora Sharipova", "07", { pfx: "97", n: 3, last: "2026-08-01T18:20:00", contactAllowed: false }),
    address: { street: "Nukus ko'chasi", house: "22", flat: "5", entrance: "1", floor: "2",
      landmark: null, lat: 41.2947, lon: 69.2521, coordinateSource: "ROOFTOP" },
    externalRefs: [{ provider: "Yandex Eats", type: "PARTNER_ORDER_ID", value: "YE-2291-04" }],
    lines: [L(1, "Chayonli osh", 1, 52000, { sku: "OSH-CHY" })],
    money: { subtotal: 52000, discount: 0, fee: 15000, deliveryFee: 15000, providerCost: 0, tax: 0, total: 67000, cashTendered: null },
    courierId: null,
    processes: [{ key: "RESTAURANT_APPROVAL", name: "Venue approval", status: "COMPLETED", attempts: 1 }],
    fiscal: { status: "NOT_REQUIRED", attempts: 0 },
    createdBy: "ac-sys", revision: 1,
    history: [
      { seq: 1, at: "2026-08-21T15:11:00", from: null, to: "RECEIVED", trigger: "CHECKOUT", actor: "ac-sys" },
      { seq: 2, at: "2026-08-21T15:12:00", from: "RECEIVED", to: "AWAITING_APPROVAL", trigger: "SYSTEM", actor: "ac-sys" },
      { seq: 3, at: "2026-08-21T15:16:00", from: "AWAITING_APPROVAL", to: "REJECTED", trigger: "APPROVAL_DECISION", actor: "ac-1", reasonCode: "NO_CAPACITY" },
    ],
    decisions: [
      { at: "2026-08-21T15:16:00", actor: "ac-1", action: "REJECT", effective: true, reasonCode: "NO_CAPACITY" },
      { at: "2026-08-21T15:16:00", actor: "ac-3", action: "APPROVE", effective: false,
        note: "not applied — the order was already rejected" },
    ],
  },
  {
    id: "o-0117", number: "0117", version: 8, locationId: "l-yun", channelId: "ch-web",
    status: "COMPLETED", mode: "DELIVERY", createdAt: "2026-06-02T13:20:00",
    promisedAt: "2026-06-02T14:00:00", completedAt: "2026-06-02T13:56:00",
    paymentProjection: "PAID", paymentMethod: "Click",
    customer: c("—", "—", { anonymizedAt: "2026-08-02T03:00:00", n: 0 }),
    address: null,
    lines: [L(1, "Toshkent oshi", 1, 45000, { sku: "OSH-TSH" }), L(2, "Achichuk", 1, 14000, { sku: "SAL-ACH" })],
    money: { subtotal: 59000, discount: 0, fee: 15000, deliveryFee: 15000, providerCost: 17000, tax: 0, total: 74000, cashTendered: null },
    courierId: null,
    processes: [{ key: "ORDER_INVENTORY", name: "Stock reservation", status: "COMPLETED", attempts: 1 }],
    fiscal: { status: "ISSUED", attempts: 1, receiptId: "FR-2026-0602-0117", sign: "1120 6634 8890", inn: "302847119" },
    createdBy: "ac-sys", acceptedBy: "ac-sys", acceptedAt: "2026-06-02T13:21:00", revision: 1,
  },
];

/* ── severity ──────────────────────────────────────────────────────────────
 * Derived per render from the promise and the clock. Nothing writes it: a stored
 * lateness flag is wrong five seconds after it is written.
 *
 * The spec's level table names three tinted levels — BLOCKED, LATE, AT_RISK —
 * and the comparator ranks seven cases. The extra ranks (approval deadline,
 * payment failure, callback) order the queue and earn a caption, but only the
 * three named levels tint a row: PAYMENT_FAILED already carries a red status
 * badge, and tinting it as well would say the same thing twice.
 */

export const LEVEL = {
  BLOCKED: { tint: "var(--q-error-tint)", rule: "var(--q-error)", text: "var(--q-error-text)" },
  LATE:    { tint: "var(--q-error-tint)", rule: "var(--q-error)", text: "var(--q-error-text)" },
  AT_RISK: { tint: "var(--q-warning-tint)", rule: "var(--q-warning)", text: "var(--q-warning-text)" },
  NORMAL:  { tint: "transparent", rule: "transparent", text: "var(--q-ink-subtle)" },
};

export function severityOf(o) {
  const terminal = isTerminal(o);
  const blocked = (o.processes || []).find((p) => p.status === "MANUAL_ACTION_REQUIRED");
  const courier = COURIERS.find((x) => x.id === o.courierId);

  /* The caption is the point: a bare red row teaches nothing. It is one short
   * line of real text, so `caption` is the operator sentence and `error` stays
   * the full machine string for the integrations panel. */
  if (blocked) return { rank: 0, level: "BLOCKED", caption: blocked.caption || blocked.error };
  if (o.fiscal && (o.fiscal.status === "BLOCKED" || o.fiscal.status === "FAILED")) {
    return { rank: 0, level: "BLOCKED", caption: o.fiscal.blockedShort || `Fiscal ${o.fiscal.status.toLowerCase()}` };
  }

  /* Terminal orders are never flagged for lateness, whatever their history: a
   * completed order that ran late is a report row, not a queue row. */
  if (!terminal && o.promisedAt) {
    const late = -minsTo(o.promisedAt);
    if (late > LATENESS.lateAfterSeconds / 60) {
      const extra = !o.courierId && o.mode === "DELIVERY" ? " · no courier assigned"
        : courier && !courier.onShift ? ` · ${courier.name} off shift` : "";
      return { rank: 1, level: "LATE", caption: `Late by ${dur(late)}${extra}` };
    }
  }
  if (o.status === "AWAITING_APPROVAL" && o.approvalDeadlineAt && secsTo(o.approvalDeadlineAt) < 120) {
    return { rank: 2, level: "AT_RISK", caption: `Confirm within ${countdown(o.approvalDeadlineAt)}` };
  }
  if (o.status === "PAYMENT_FAILED") {
    const p = (o.processes || []).find((x) => x.key === "ORDER_PAYMENT");
    return { rank: 3, level: "NORMAL", caption: (p && (p.caption || p.error)) || "Payment declined" };
  }
  if (!terminal && o.promisedAt && secsTo(o.promisedAt) < LATENESS.atRiskBeforeSeconds) {
    const extra = !o.courierId && o.mode === "DELIVERY" ? " · no courier assigned" : "";
    return { rank: 4, level: "AT_RISK", caption: `Promise in ${dur(minsTo(o.promisedAt))}${extra}` };
  }
  if (o.callbackRequested) {
    return { rank: 5, level: "NORMAL", caption: "Callback requested — nobody has phoned yet" };
  }
  const retry = (o.processes || []).find((p) => p.status === "FAILED_RETRYABLE");
  if (retry) {
    return { rank: 5, level: "NORMAL", caption: retry.caption || `${retry.name} failed, retry ${retry.attempts}` };
  }
  return { rank: 6, level: "NORMAL", caption: null };
}

/** Attention is a saved view, not a partition: its rows also appear in their own
 *  status tab, deliberately. If it is empty the shift is fine. */
export function inAttention(o) {
  if (o.status === "AWAITING_APPROVAL" || o.status === "PAYMENT_FAILED") return true;
  if ((o.processes || []).some((p) => ["MANUAL_ACTION_REQUIRED", "FAILED_RETRYABLE"].includes(p.status))) return true;
  if (o.callbackRequested) return true;
  if (o.fiscal && ["BLOCKED", "FAILED"].includes(o.fiscal.status)) return true;
  const s = severityOf(o);
  return s.level === "LATE";
}

export const TABS = [
  { id: "attention", label: "Attention", member: inAttention, queue: true },
  { id: "new", label: "New", member: (o) => ["RECEIVED", "PAYMENT_AUTHORIZING", "AWAITING_APPROVAL"].includes(o.status), queue: true },
  { id: "preparing", label: "Preparing", member: (o) => ["CONFIRMED", "PREPARING", "READY"].includes(o.status), queue: true },
  { id: "delivering", label: "Delivering", member: (o) => o.status === "FULFILLING", queue: true },
  { id: "completed", label: "Completed", member: (o) => o.status === "COMPLETED", queue: false },
  { id: "cancelled", label: "Cancelled", member: (o) => ["CANCELLED", "REJECTED", "EXPIRED"].includes(o.status), queue: false },
  { id: "all", label: "All", member: () => true, queue: false },
];

/** Severity first, then promise ascending (nulls last), then oldest first — the
 *  person who waited longest. Log tabs get newest first instead. */
export function sortRows(rows, isQueue) {
  const t = (iso) => (iso ? new Date(iso).getTime() : null);
  return [...rows].sort((a, b) => {
    if (!isQueue) return t(b.createdAt) - t(a.createdAt);
    const sa = severityOf(a).rank, sb = severityOf(b).rank;
    if (sa !== sb) return sa - sb;
    const pa = t(a.promisedAt), pb = t(b.promisedAt);
    if (pa !== pb) { if (pa === null) return 1; if (pb === null) return -1; return pa - pb; }
    return t(a.createdAt) - t(b.createdAt);
  });
}

/* ── actions ───────────────────────────────────────────────────────────────
 * Stands in for the detail response's `actions[]` array (§4.2). The client never
 * computes availability; it renders what this hands it. Unavailable is omitted.
 * The one exception is a *temporarily* blocked action, which is rendered
 * disabled with the transient reason attached.
 *
 * At most two inline affordances per row, chosen by state. Everything else in
 * the overflow, in the fixed order of §2.9.
 */

const A = (id, label, extra = {}) => ({ id, label, ...extra });

export function actionsFor(o) {
  const loc = LOCATIONS.find((l) => l.id === o.locationId);
  const terminal = isTerminal(o);
  const posBlocked = (o.processes || []).some(
    (p) => p.key === "POS_ORDER_EXPORT" && ["MANUAL_ACTION_REQUIRED", "FAILED_RETRYABLE"].includes(p.status));
  const out = [];

  /* Inline — the single most likely next action for this row's state. */
  if (o.status === "AWAITING_APPROVAL") {
    out.push(A("approve", "Accept", { inline: true, primary: true }));
    out.push(A("reject", "Reject", { inline: true, confirm: true }));
  } else if (o.status === "PAYMENT_FAILED") {
    out.push(A("invoice", "Re-issue invoice", { inline: true, primary: true, adr: "ADR 0013" }));
  } else if (o.status === "CONFIRMED") {
    out.push(A("advance:PREPARING", "To kitchen", { inline: true, primary: true }));
  } else if (o.status === "READY" && o.mode === "DELIVERY" && !o.courierId) {
    out.push(A("courier", "Assign courier", { inline: true, primary: true, adr: "ADR 0014" }));
  } else if (o.status === "READY" && o.mode !== "DELIVERY") {
    out.push(A("complete", "Handed over", { inline: true, primary: true }));
  } else if (o.status === "FULFILLING") {
    out.push(A("complete", "Delivered", { inline: true, primary: true }));
  } else if (posBlocked) {
    out.push(A("resolve", "Resolve", { inline: true, primary: true }));
  } else if (o.status === "PREPARING") {
    /* §2.9 lists no inline affordance for PREPARING; the matrix makes "Ready"
     * the only forward move, so it is the detail's primary and an overflow
     * entry — not a third button competing on the row. */
    out.push(A("advance:READY", "Mark ready", { primary: true }));
  } else if (o.status === "READY" && o.mode === "DELIVERY" && o.courierId) {
    out.push(A("advance:FULFILLING", "Out for delivery", { primary: true }));
  } else if (o.fiscal && ["FAILED", "BLOCKED"].includes(o.fiscal.status)) {
    /* A blocked fiscal document is what put this order at the top of Attention,
     * so the affordance that clears it belongs on the row. Without this a
     * terminal order can rank first and offer nothing but an overflow menu. */
    out.push(A("fiscalize", "Fiscalize", { inline: true, primary: true, confirm: true, adr: "ADR 0038" }));
  }

  /* Overflow, in the order of §2.9. */
  out.push(A("open", "Open"), A("copy", "Copy number"));
  if (!o.customer.guest && o.customer.contactAllowed && !o.customer.anonymizedAt) {
    out.push(A("call", "Call customer", { pii: true }));
  }
  if (o.mode === "DELIVERY" && !terminal && ["CONFIRMED", "PREPARING", "READY", "FULFILLING"].includes(o.status)) {
    out.push(A("courier", o.courierId ? "Reassign courier" : "Assign courier", { adr: "ADR 0014" }));
    if (o.courierId) out.push(A("detach", "Remove courier", { confirm: true, adr: "ADR 0014" }));
    if (o.status !== "FULFILLING") out.push(A("dispatch", "Call delivery service", { adr: "ADR 0014" }));
  }
  if (["RECEIVED", "AWAITING_APPROVAL", "CONFIRMED", "PREPARING", "READY"].includes(o.status)) {
    out.push(A("amend", "Change order", {
      submenu: true, adr: "ADR 0039",
      disabled: posBlocked,
      reason: posBlocked ? "Change unavailable: waiting for POS acknowledgement" : null,
    }));
  }
  if (!terminal) out.push(A("kitchen-note", "Note to kitchen", { adr: "ADR 0039" }));
  if (loc && loc.pos.includes("print") && o.status !== "CANCELLED") {
    out.push(A("print", "Print to POS", { adr: "ADR 0011" }));
  }
  if (posBlocked) out.push(A("resend", "Resend to POS", { adr: "ADR 0012" }));
  if (o.fiscal && ["FAILED", "BLOCKED"].includes(o.fiscal.status) && !out.some((a) => a.id === "fiscalize")) {
    out.push(A("fiscalize", "Fiscalize", { confirm: true, adr: "ADR 0038" }));
  }
  if (o.status === "COMPLETED") out.push(A("refund", "Refund", { confirm: true, adr: "ADR 0013" }));
  if (!terminal) {
    if (["RECEIVED", "PAYMENT_AUTHORIZING", "AWAITING_APPROVAL", "PAYMENT_FAILED"].includes(o.status)) {
      out.push(A("cancel", "Cancel order", { confirm: true, danger: true }));
    } else {
      /* CONFIRMED and later: OrderStateService refuses it today. The action
       * disappears and the detail says why in words — never a greyed button. */
      out.push(A("cancel-blocked", "Cancel order", { hidden: true }));
    }
  }
  return out;
}

/** A bulk action is offered only when it is valid for every selected row. */
export const BULK = [
  { id: "courier", label: "Assign courier",
    valid: (o) => o.mode === "DELIVERY" && !isTerminal(o), noun: "not delivery orders, or already closed" },
  { id: "cancel", label: "Cancel",
    valid: (o) => ["RECEIVED", "PAYMENT_AUTHORIZING", "AWAITING_APPROVAL", "PAYMENT_FAILED"].includes(o.status),
    noun: "cannot be cancelled at their current status" },
  { id: "print", label: "Print to POS",
    valid: (o) => (LOCATIONS.find((l) => l.id === o.locationId)?.pos || []).includes("print"),
    noun: "are at a branch whose POS declares no print capability" },
  { id: "fiscalize", label: "Fiscalize",
    valid: (o) => !!o.fiscal && ["FAILED", "BLOCKED"].includes(o.fiscal.status),
    noun: "have no failed or blocked fiscal document" },
  { id: "export", label: "Export CSV", valid: () => true, noun: "" },
];

/** N independent commands under one bulk_operation_id, each in its own
 *  transaction — so the result is a per-item outcome list, never one verdict. */
export function bulkOutcome(action, rows) {
  return rows.map((o, i) => {
    const fail = action === "courier" && o.courierId === "cr-4";
    const fail2 = action === "print" && i === 1;
    return {
      orderId: o.id, number: o.number,
      ok: !(fail || fail2),
      problem: fail ? "COURIER_OFF_SHIFT" : fail2 ? "POS_TERMINAL_OFFLINE" : null,
    };
  });
}

/* ── things the backend does not have ──────────────────────────────────────
 * Rendered on screen wherever a panel would otherwise invent data.
 */
export const GAPS = {
  promise: "ADR 0014",
  courier: "ADR 0014",
  shipment: "ADR 0014",
  zone: "ADR 0037",
  fiscal: "ADR 0038",
  payment: "ADR 0013",
  revisions: "ADR 0039",
  attribution: "ADR 0039",
  external: "ADR 0040",
  kitchenLane: "ADR 0041",
  shift: "ADR 0042",
  stream: "ADR 0045",
};
