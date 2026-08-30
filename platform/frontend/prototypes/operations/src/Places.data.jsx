/* Fixtures for Settings → Brands and locations.
 *
 * Authored backwards from the states the screen must render, per the spec's
 * severity ladder (§1.5, §1.7). Every band on that ladder has at least one
 * branch sitting on it, plus the awkward cases nobody designs for on purpose:
 * a branch name long enough to break a table beside a five-letter one, a branch
 * whose fryer died on Thursday and nobody reopened it, a branch trading with
 * zero channels bound, a DRAFT that cannot be activated, an archived branch
 * that must never be deleted, and a second timezone so the column earns itself.
 *
 * Two things live here rather than in the screen because they are derivation of
 * this data, not presentation of it: the opening-window expansion (which has to
 * honour `closes_at <= opens_at` meaning "next day") and the serviceability
 * resolver, whose evaluation order is the whole content of the explainer panel.
 *
 * Money is whole so'm. Times are ISO or `HH:mm` wall clock in the *location's*
 * timezone. Uzbek and Russian throughout; codes and identifiers in mono at the
 * render site.
 */

import { NOW } from "./data";

/* NOW is Friday 21.08.2026, 19:34 — mid-evening service, ISO day-of-week 5. */
export const NOW_ISO = NOW;
export const TODAY_DATE = "2026-08-21";
export const TOMORROW_DATE = "2026-08-22";
export const TODAY_DOW = 5;
export const NOW_MIN = 19 * 60 + 34;

export const DOW_LABEL = ["", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
export const MODES = ["DELIVERY", "PICKUP", "DINE_IN"];
export const MODE_LABEL = { DELIVERY: "Delivery", PICKUP: "Pickup", DINE_IN: "Dine-in" };
export const MODE_SHORT = { DELIVERY: "Dost", PICKUP: "Samo", DINE_IN: "Zal" };

/* ── reason vocabulary ─────────────────────────────────────────────────────
 * `location_service_state.reason_code` is free varchar(48) with no registry
 * (spec §16). Until IA 10.10 reference data exists the vocabulary is owned
 * here, in code, which is the only place it can be owned honestly.
 */
export const CLOSE_REASONS = [
  { code: "EQUIPMENT_FAILURE", label: "Equipment failure", ru: "Сломалось оборудование" },
  { code: "NO_STAFF", label: "No staff", ru: "Нет персонала" },
  { code: "POWER_OUTAGE", label: "Power cut", ru: "Нет электричества" },
  { code: "OVERLOADED", label: "Kitchen overloaded", ru: "Перегрузка кухни" },
  { code: "OUT_OF_STOCK", label: "Out of stock", ru: "Нет продуктов" },
  { code: "WEATHER", label: "Weather", ru: "Погода" },
  { code: "RENOVATION", label: "Renovation", ru: "Ремонт" },
  { code: "PRIVATE_EVENT", label: "Private event", ru: "Закрытое мероприятие" },
  { code: "OTHER", label: "Other", ru: "Другое" },
];

export const OPEN_REASONS = [
  { code: "EXTENDED_HOURS", label: "Extended hours", ru: "Продлённые часы" },
  { code: "SPECIAL_EVENT", label: "Event", ru: "Мероприятие" },
  { code: "CATCH_UP", label: "Catching up the queue", ru: "Догоняем очередь" },
  { code: "OTHER", label: "Other", ru: "Другое" },
];

export const reasonLabel = (code) =>
  (CLOSE_REASONS.concat(OPEN_REASONS).find((r) => r.code === code) || {}).label || code || "—";

/* ── brands ────────────────────────────────────────────────────────────────*/

export const BRANDS = [
  { id: "b-osh", code: "OSH", slug: "osh-markazi", name: "Osh Markazi", status: "ACTIVE", updatedAt: "2026-07-30T11:04:00", legacyCompanyId: "4471" },
  { id: "b-non", code: "NONCHOY", slug: "non-va-choy", name: "Non va Choy", status: "ACTIVE", updatedAt: "2026-08-14T09:22:00", legacyCompanyId: "4519" },
];

export const brandOf = (id) => BRANDS.find((b) => b.id === id) || {};

/* ── sales channels (ADR 0036, built — migration V0020) ────────────────────*/

export const CHANNELS = [
  { id: "ch-tg", code: "TELEGRAM_BOT", name: "Telegram-bot", systemType: "TELEGRAM", status: "ACTIVE", modes: ["DELIVERY", "PICKUP"], payments: ["CLICK", "PAYME", "CASH"], externallyPriced: false, pricePlane: "TELEGRAM_BOT" },
  { id: "ch-web", code: "WEB", name: "Veb-sayt", systemType: "WEB", status: "ACTIVE", modes: ["DELIVERY", "PICKUP"], payments: ["CLICK", "PAYME", "CASH"], externallyPriced: false, pricePlane: "WEB" },
  { id: "ch-app", code: "MOBILE_IOS", name: "Mobil ilova", systemType: "IOS", status: "ACTIVE", modes: ["DELIVERY"], payments: ["CLICK", "PAYME"], externallyPriced: false, pricePlane: "WEB" },
  { id: "ch-call", code: "CALL_CENTRE", name: "Qo'ng'iroq markazi", systemType: "CALL_CENTRE", status: "ACTIVE", modes: ["DELIVERY", "PICKUP", "DINE_IN"], payments: ["CASH", "CLICK"], externallyPriced: false, pricePlane: "WEB" },
  { id: "ch-exp", code: "EXPRESS24", name: "Express24", systemType: "AGGREGATOR", status: "ACTIVE", modes: ["DELIVERY"], payments: ["AGGREGATOR_SETTLED"], externallyPriced: true, pricePlane: "EXPRESS24" },
  { id: "ch-qr", code: "QR_TABLE", name: "QR-menyu (zal)", systemType: "QR_TABLE", status: "INACTIVE", modes: ["DINE_IN"], payments: ["CLICK", "CASH"], externallyPriced: false, pricePlane: "WEB" },
  { id: "ch-kiosk", code: "KIOSK", name: "Kiosk (Chorsu)", systemType: "KIOSK", status: "ARCHIVED", modes: ["PICKUP"], payments: ["CASH"], externallyPriced: false, pricePlane: "WEB" },
];

export const channelOf = (id) => CHANNELS.find((c) => c.id === id) || {};

/* ── schedules (tenant.service_schedules + rules + exceptions) ─────────────
 * Brand-scoped and reusable: the whole point of ADR 0036's shape is that one
 * Ramadan timetable is one object, not thirty forms.
 * `closes <= opens` means the window ends the following day.
 */

export const SCHEDULES = [
  {
    id: "s-kunlik", brandId: "b-osh", name: "Kunlik 10:00–23:00", acceptsScheduled: true,
    updatedAt: "2026-06-11T15:40:00",
    rules: [
      { day: 1, opens: "10:00", closes: "23:00" }, { day: 2, opens: "10:00", closes: "23:00" },
      { day: 3, opens: "10:00", closes: "23:00" }, { day: 4, opens: "10:00", closes: "23:00" },
      { day: 5, opens: "10:00", closes: "02:00" }, { day: 6, opens: "10:00", closes: "02:00" },
      { day: 7, opens: "10:00", closes: "23:00" },
    ],
    exceptions: [
      { id: "ex-2", date: "2026-09-01", label: "Mustaqillik kuni", reason: "Bayram — qisqartirilgan smena", closedAllDay: false, opens: "12:00", closes: "23:00", createdBy: "Nodira Xolmatova", createdAt: "2026-08-04T10:12:00" },
      { id: "ex-4", date: "2026-03-21", label: "Navro'z", reason: "Boshqaruvchi qarori, buyruq №11", closedAllDay: true, opens: null, closes: null, createdBy: "Nodira Xolmatova", createdAt: "2026-03-02T08:30:00" },
    ],
  },
  {
    id: "s-samovar", brandId: "b-osh", name: "Samovar 10:00–21:00", acceptsScheduled: false,
    updatedAt: "2026-05-02T12:00:00",
    rules: [1, 2, 3, 4, 5, 6, 7].map((day) => ({ day, opens: "10:00", closes: "21:00" })),
    exceptions: [],
  },
  {
    id: "s-zal", brandId: "b-osh", name: "Zal 11:00–23:00", acceptsScheduled: true,
    updatedAt: "2026-05-02T12:04:00",
    rules: [1, 2, 3, 4, 5, 6, 7].map((day) => ({ day, opens: "11:00", closes: "23:00" })),
    exceptions: [],
  },
  {
    /* A mall branch follows the mall, not the brand — and the mall shuts for a
     * sanitary day. One schedule, one exception, one branch affected. */
    id: "s-poytaxt", brandId: "b-osh", name: "«Poytaxt» SM 10:00–22:00", acceptsScheduled: true,
    updatedAt: "2026-08-19T17:31:00",
    rules: [1, 2, 3, 4, 5, 6, 7].map((day) => ({ day, opens: "10:00", closes: "22:00" })),
    exceptions: [
      { id: "ex-1", date: "2026-08-21", label: "Sanitariya kuni", reason: "Savdo markazi qarori, xat №2261", closedAllDay: true, opens: null, closes: null, createdBy: "Sanjar Tursunov", createdAt: "2026-08-19T17:30:00" },
    ],
  },
  {
    id: "s-kechki", brandId: "b-non", name: "Kechki 10:00–23:00", acceptsScheduled: true,
    updatedAt: "2026-08-17T08:10:00",
    rules: [1, 2, 3, 4, 5, 6, 7].map((day) => ({ day, opens: "10:00", closes: "23:00" })),
    exceptions: [],
  },
  {
    id: "s-nonushta", brandId: "b-non", name: "Nonushta 08:00–16:00", acceptsScheduled: false,
    updatedAt: "2026-08-14T09:20:00",
    rules: [1, 2, 3, 4, 5, 6, 7].map((day) => ({ day, opens: "08:00", closes: "16:00" })),
    exceptions: [
      { id: "ex-3", date: "2026-08-22", label: "Ta'mirlash", reason: "Pech almashtiriladi", closedAllDay: true, opens: null, closes: null, createdBy: "Kamola Ergasheva", createdAt: "2026-08-20T14:02:00" },
    ],
  },
  {
    /* Bound to nothing. The library rots without a way to see this. */
    id: "s-ramazon", brandId: "b-osh", name: "Ramazon 2026", acceptsScheduled: true,
    updatedAt: "2026-02-10T09:00:00",
    rules: [1, 2, 3, 4, 5, 6, 7].map((day) => ({ day, opens: "18:30", closes: "03:00" })),
    exceptions: [],
  },
];

export const scheduleOf = (id) => SCHEDULES.find((s) => s.id === id) || null;

/* ── preparation bands (tenant.preparation_bands) ──────────────────────────
 * `ends_at > starts_at` is a check constraint: bands never wrap past midnight,
 * so the 22:00–01:00 case is two rows and the editor has to say so.
 */

const BANDS_DEFAULT = [
  { id: "pb-1", mode: null, day: null, from: "10:00", to: "17:00", minutes: 25, priority: 10 },
  { id: "pb-2", mode: null, day: null, from: "17:00", to: "22:00", minutes: 40, priority: 10 },
];

const BANDS_CHILONZOR = [
  { id: "pb-c1", mode: null, day: null, from: "10:00", to: "17:00", minutes: 25, priority: 10 },
  { id: "pb-c2", mode: "DELIVERY", day: 5, from: "18:00", to: "23:00", minutes: 55, priority: 30, note: "Juma kechki navbat" },
  { id: "pb-c3", mode: null, day: null, from: "17:00", to: "22:00", minutes: 40, priority: 10 },
  /* The after-midnight case, split into two rows as the constraint requires. */
  { id: "pb-c4", mode: null, day: null, from: "22:00", to: "23:59", minutes: 30, priority: 10, splitOf: "22:00–01:00" },
  { id: "pb-c5", mode: null, day: null, from: "00:00", to: "01:00", minutes: 30, priority: 10, splitOf: "22:00–01:00" },
];

/* ── locations ─────────────────────────────────────────────────────────────
 * `phone`, `address`, `landmark`, `lat`, `lon` are absent from the schema
 * (spec §16, blocking). They are carried here as `null` on purpose so the
 * screen can render the field and mark it pending rather than pretend the
 * concept does not exist.
 */

export const LOCATIONS = [
  {
    id: "l-chz", brandId: "b-osh", code: "CHZ", slug: "chilonzor", name: "Chilonzor",
    timezone: "Asia/Tashkent", status: "ACTIVE", updatedAt: "2026-08-21T19:14:00", legacyVendorId: "8812",
    serviceState: {
      mode: "FORCE_CLOSED", reasonCode: "EQUIPMENT_FAILURE", note: "Tandir yorildi, usta ertaga keladi",
      effectiveUntil: null, changedBy: "Sanjar Tursunov", changedAt: "2026-08-21T19:14:00",
      maxConcurrentOrders: 12,
    },
    bindings: { DELIVERY: "s-kunlik", PICKUP: "s-kunlik", DINE_IN: "s-zal" },
    channels: [
      { channelId: "ch-tg", status: "ACTIVE" }, { channelId: "ch-web", status: "ACTIVE" },
      { channelId: "ch-app", status: "ACTIVE" }, { channelId: "ch-call", status: "ACTIVE" },
      { channelId: "ch-exp", status: "INACTIVE" },
    ],
    liveMenuFor: ["ch-tg", "ch-web", "ch-app", "ch-call"],
    holds: 3, inFlight: 7, lateInFlight: 2, worstLateMinutes: 41,
    offerings: { available: 128, soldOut: [{ name: "Lo'la kabob", reason: "Qiyma tugadi" }, { name: "Sazan salat", reason: "Baliq yetkazilmadi" }] },
    prepBands: BANDS_CHILONZOR,
    todayOrders: 87, todayRevenueMinor: 7_412_000,
  },
  {
    id: "l-yun", brandId: "b-osh", code: "YUN", slug: "yunusobod", name: "Yunusobod",
    timezone: "Asia/Tashkent", status: "ACTIVE", updatedAt: "2026-08-21T18:02:00", legacyVendorId: "8813",
    serviceState: { mode: "FOLLOW_SCHEDULE", reasonCode: null, note: null, effectiveUntil: null, changedBy: "Sanjar Tursunov", changedAt: "2026-08-19T09:00:00", maxConcurrentOrders: 12 },
    bindings: { DELIVERY: "s-kunlik", PICKUP: "s-kunlik", DINE_IN: null },
    channels: [
      { channelId: "ch-tg", status: "ACTIVE" }, { channelId: "ch-web", status: "ACTIVE" },
      { channelId: "ch-app", status: "ACTIVE" }, { channelId: "ch-exp", status: "ACTIVE" },
    ],
    liveMenuFor: ["ch-tg", "ch-web", "ch-app", "ch-exp"],
    holds: 12, inFlight: 12, lateInFlight: 3, worstLateMinutes: 22,
    offerings: { available: 131, soldOut: [] },
    todayOrders: 104, todayRevenueMinor: 9_188_000,
  },
  {
    id: "l-sgl", brandId: "b-osh", code: "SGL", slug: "sergeli", name: "Sergeli",
    timezone: "Asia/Tashkent", status: "ACTIVE", updatedAt: "2026-08-21T17:48:00", legacyVendorId: "8814",
    serviceState: {
      mode: "FORCE_CLOSED", reasonCode: "NO_STAFF", note: "Ikki oshpaz kasal", effectiveUntil: "2026-08-21T21:30:00",
      changedBy: "Dilshod Rasulov", changedAt: "2026-08-21T17:48:00", maxConcurrentOrders: 8,
    },
    bindings: { DELIVERY: "s-kunlik", PICKUP: "s-samovar", DINE_IN: null },
    channels: [{ channelId: "ch-tg", status: "ACTIVE" }, { channelId: "ch-web", status: "ACTIVE" }],
    liveMenuFor: ["ch-tg", "ch-web"],
    holds: 0, inFlight: 2, lateInFlight: 0, worstLateMinutes: 0,
    offerings: { available: 96, soldOut: [] },
    todayOrders: 41, todayRevenueMinor: 3_120_000,
  },
  {
    /* The long name, deliberately: a mall branch carries its mall in its name
     * and no table designed against "Chorsu" survives it. */
    id: "l-mrb", brandId: "b-osh", code: "MRB", slug: "mirobod-poytaxt",
    name: "Mirobod — Amir Temur shoh ko'chasi 108, «Poytaxt» savdo markazi, 3-qavat",
    timezone: "Asia/Tashkent", status: "ACTIVE", updatedAt: "2026-08-19T17:31:00", legacyVendorId: "8815",
    serviceState: { mode: "FOLLOW_SCHEDULE", reasonCode: null, note: null, effectiveUntil: null, changedBy: "Nodira Xolmatova", changedAt: "2026-08-01T10:00:00", maxConcurrentOrders: 10 },
    bindings: { DELIVERY: "s-poytaxt", PICKUP: "s-poytaxt", DINE_IN: "s-poytaxt" },
    channels: [
      { channelId: "ch-tg", status: "ACTIVE" }, { channelId: "ch-web", status: "ACTIVE" },
      { channelId: "ch-call", status: "ACTIVE" }, { channelId: "ch-qr", status: "ACTIVE" },
    ],
    liveMenuFor: ["ch-tg", "ch-web", "ch-call"],
    holds: 0, inFlight: 0, lateInFlight: 0, worstLateMinutes: 0,
    offerings: { available: 88, soldOut: [] },
    todayOrders: 0, todayRevenueMinor: 0,
  },
  {
    id: "l-chs", brandId: "b-non", code: "CHS", slug: "chorsu", name: "Chorsu",
    timezone: "Asia/Tashkent", status: "ACTIVE", updatedAt: "2026-08-20T14:02:00", legacyVendorId: "8816",
    serviceState: { mode: "FOLLOW_SCHEDULE", reasonCode: null, note: null, effectiveUntil: null, changedBy: "Kamola Ergasheva", changedAt: "2026-08-14T09:20:00", maxConcurrentOrders: null },
    bindings: { DELIVERY: null, PICKUP: "s-nonushta", DINE_IN: "s-nonushta" },
    channels: [{ channelId: "ch-tg", status: "ACTIVE" }, { channelId: "ch-call", status: "INACTIVE" }],
    liveMenuFor: ["ch-tg"],
    holds: 0, inFlight: 0, lateInFlight: 0, worstLateMinutes: 0,
    offerings: { available: 24, soldOut: [{ name: "Varaqi somsa", reason: "Kunlik miqdor tugadi" }] },
    todayOrders: 62, todayRevenueMinor: 1_040_000,
  },
  {
    id: "l-olm", brandId: "b-osh", code: "OLM", slug: "olmazor", name: "Olmazor",
    timezone: "Asia/Tashkent", status: "ACTIVE", updatedAt: "2026-08-21T18:55:00", legacyVendorId: "8817",
    serviceState: {
      mode: "FORCE_OPEN", reasonCode: "EXTENDED_HOURS", note: "To'y buyurtmasi, kechgacha ishlaymiz",
      effectiveUntil: "2026-08-22T02:00:00", changedBy: "Dilshod Rasulov", changedAt: "2026-08-21T18:55:00",
      maxConcurrentOrders: 10,
    },
    bindings: { DELIVERY: "s-kunlik", PICKUP: "s-samovar", DINE_IN: "s-zal" },
    channels: [{ channelId: "ch-tg", status: "ACTIVE" }, { channelId: "ch-web", status: "ACTIVE" }, { channelId: "ch-call", status: "ACTIVE" }],
    liveMenuFor: ["ch-tg", "ch-web", "ch-call"],
    holds: 4, inFlight: 4, lateInFlight: 0, worstLateMinutes: 0,
    offerings: { available: 118, soldOut: [] },
    todayOrders: 73, todayRevenueMinor: 6_004_000,
  },
  {
    id: "l-yks", brandId: "b-osh", code: "YKS", slug: "yakkasaroy", name: "Yakkasaroy",
    timezone: "Asia/Tashkent", status: "ACTIVE", updatedAt: "2026-08-18T11:20:00", legacyVendorId: "8818",
    serviceState: { mode: "FOLLOW_SCHEDULE", reasonCode: null, note: null, effectiveUntil: null, changedBy: "Nodira Xolmatova", changedAt: "2026-07-30T11:04:00", maxConcurrentOrders: 10 },
    /* Pickup shuts two hours before the hall. A fixed pair of schedules on the
     * branch cannot express this; three bindings can. */
    bindings: { DELIVERY: "s-kunlik", PICKUP: "s-samovar", DINE_IN: "s-zal" },
    channels: [
      { channelId: "ch-tg", status: "ACTIVE" }, { channelId: "ch-web", status: "ACTIVE" },
      { channelId: "ch-app", status: "ACTIVE" }, { channelId: "ch-call", status: "ACTIVE" },
      { channelId: "ch-exp", status: "INACTIVE", entitlementWithdrawn: true },
    ],
    liveMenuFor: ["ch-tg", "ch-web", "ch-app", "ch-call"],
    holds: 5, inFlight: 5, lateInFlight: 1, worstLateMinutes: 12,
    offerings: { available: 134, soldOut: [] },
    todayOrders: 68, todayRevenueMinor: 5_431_000,
  },
  {
    id: "l-mst", brandId: "b-non", code: "MST", slug: "mustaqillik", name: "Mustaqillik",
    timezone: "Asia/Tashkent", status: "ACTIVE", updatedAt: "2026-08-17T08:10:00", legacyVendorId: "8819",
    serviceState: { mode: "FOLLOW_SCHEDULE", reasonCode: null, note: null, effectiveUntil: null, changedBy: "Kamola Ergasheva", changedAt: "2026-08-17T08:10:00", maxConcurrentOrders: 6 },
    bindings: { DELIVERY: "s-kechki", PICKUP: "s-kechki", DINE_IN: null },
    channels: [{ channelId: "ch-tg", status: "ACTIVE" }, { channelId: "ch-web", status: "ACTIVE" }],
    /* Sells on Telegram; catalog has never published a live menu for it. */
    liveMenuFor: ["ch-web"],
    holds: 1, inFlight: 1, lateInFlight: 0, worstLateMinutes: 0,
    offerings: { available: 31, soldOut: [] },
    todayOrders: 55, todayRevenueMinor: 1_722_000,
  },
  {
    id: "l-smq", brandId: "b-non", code: "SMQ", slug: "samarqand-registon", name: "Samarqand — Registon",
    timezone: "Asia/Samarkand", status: "ACTIVE", updatedAt: "2026-08-16T13:45:00", legacyVendorId: "8820",
    serviceState: { mode: "FOLLOW_SCHEDULE", reasonCode: null, note: null, effectiveUntil: null, changedBy: "Kamola Ergasheva", changedAt: "2026-08-16T13:45:00", maxConcurrentOrders: 6 },
    bindings: { DELIVERY: "s-nonushta", PICKUP: "s-nonushta", DINE_IN: null },
    /* Bound to nothing. The branch is ACTIVE, staffed, and cannot sell. */
    channels: [],
    liveMenuFor: [],
    holds: 0, inFlight: 0, lateInFlight: 0, worstLateMinutes: 0,
    offerings: { available: 28, soldOut: [] },
    todayOrders: 0, todayRevenueMinor: 0,
  },
  {
    id: "l-bkt", brandId: "b-osh", code: "BKT", slug: "bektemir", name: "Bektemir",
    timezone: "Asia/Tashkent", status: "DRAFT", updatedAt: "2026-08-20T16:30:00", legacyVendorId: null,
    serviceState: { mode: "FOLLOW_SCHEDULE", reasonCode: null, note: null, effectiveUntil: null, changedBy: null, changedAt: null, maxConcurrentOrders: null },
    bindings: { DELIVERY: null, PICKUP: null, DINE_IN: null },
    channels: [],
    liveMenuFor: [],
    holds: 0, inFlight: 0, lateInFlight: 0, worstLateMinutes: 0,
    offerings: { available: 0, soldOut: [] },
    prepBands: [],
    todayOrders: 0, todayRevenueMinor: 0,
  },
  {
    /* Archived, never deleted: every order taken here references it forever. */
    id: "l-quy", brandId: "b-osh", code: "QUY", slug: "quyluq", name: "Quyluq",
    timezone: "Asia/Tashkent", status: "ARCHIVED", updatedAt: "2026-04-02T10:00:00", legacyVendorId: "8790",
    serviceState: { mode: "FOLLOW_SCHEDULE", reasonCode: null, note: null, effectiveUntil: null, changedBy: "Nodira Xolmatova", changedAt: "2026-04-02T10:00:00", maxConcurrentOrders: null },
    bindings: { DELIVERY: null, PICKUP: null, DINE_IN: null },
    channels: [], liveMenuFor: [],
    holds: 0, inFlight: 0, lateInFlight: 0, worstLateMinutes: 0,
    offerings: { available: 0, soldOut: [] },
    todayOrders: 0, todayRevenueMinor: 0,
  },
];

export const bandsFor = (loc) => loc.prepBands || BANDS_DEFAULT;
export const boundModes = (loc) => MODES.filter((m) => loc.bindings[m]);
export const activeChannels = (loc) => loc.channels.filter((c) => c.status === "ACTIVE");

/* ── opening windows ───────────────────────────────────────────────────────
 * Correctness, not polish: `closes <= opens` ends the window the following day,
 * and several rules may cover one day. Both are in the migration comment and
 * both are invisible until a branch reads as shut all evening.
 */

export const toMin = (hhmm) => Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3, 5));
export const fromMin = (m) => `${String(Math.floor(m / 60) % 24).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;

/** Rules → per-ISO-day segments in [0,1440), splitting the ones that wrap. */
export function expandWindows(rules) {
  const byDay = { 1: [], 2: [], 3: [], 4: [], 5: [], 6: [], 7: [] };
  rules.forEach((r) => {
    const o = toMin(r.opens);
    const c = toMin(r.closes);
    if (c > o) {
      byDay[r.day].push({ from: o, to: c, day: r.day, wraps: false });
    } else {
      byDay[r.day].push({ from: o, to: 1440, day: r.day, wraps: true, label: `${r.opens}–${r.closes}` });
      const next = (r.day % 7) + 1;
      byDay[next].push({ from: 0, to: c === 0 ? 1440 : c, day: next, spillFrom: r.day, label: `${r.opens}–${r.closes}` });
    }
  });
  Object.values(byDay).forEach((a) => a.sort((x, y) => x.from - y.from));
  return byDay;
}

/** The exception governing a schedule on a date, or null. */
export const exceptionOn = (schedule, date) =>
  (schedule && schedule.exceptions.find((e) => e.date === date)) || null;

/** Today's rendered windows for one bound mode, exception applied. */
export function windowsToday(loc, mode) {
  const s = scheduleOf(loc.bindings[mode]);
  if (!s) return null;
  const ex = exceptionOn(s, TODAY_DATE);
  if (ex && ex.closedAllDay) return { closed: true, exception: ex, windows: [] };
  if (ex) return { closed: false, exception: ex, windows: [{ from: toMin(ex.opens), to: toMin(ex.closes) }] };
  return { closed: false, exception: null, windows: expandWindows(s.rules)[TODAY_DOW] };
}

/** A window that wraps past midnight reads `18:00–02:00 +1`, never `18:00–00:00`. */
export const windowLabel = (x) =>
  x.wraps ? `${fromMin(x.from)}–${x.label.split("–")[1]} +1` : `${fromMin(x.from)}–${fromMin(x.to % 1440)}`;

export const windowsLabel = (w) => {
  if (!w) return "not served";
  if (w.closed || !w.windows.length) return "closed";
  return w.windows.map(windowLabel).join(", ");
};

const insideWindows = (w) => !!w && !w.closed && w.windows.some((x) => NOW_MIN >= x.from && NOW_MIN < x.to);

/** When the branch shuts, reading from the window that contains now. */
export function closingNow(loc) {
  for (const m of boundModes(loc)) {
    const w = windowsToday(loc, m);
    if (!w || w.closed) continue;
    const x = w.windows.find((y) => NOW_MIN >= y.from && NOW_MIN < y.to);
    if (x) return x.wraps ? x.label.split("–")[1] : fromMin(x.to % 1440);
  }
  return null;
}

/** The next opening minute today, or null when the branch is done for the day. */
function nextOpenToday(loc) {
  let best = null;
  boundModes(loc).forEach((m) => {
    const w = windowsToday(loc, m);
    if (!w || w.closed) return;
    w.windows.forEach((x) => { if (x.from > NOW_MIN && (best === null || x.from < best)) best = x.from; });
  });
  return best;
}

/* ── severity ──────────────────────────────────────────────────────────────
 * The spec's ladder (§1.5) names seven bands. Two blocking configuration
 * faults have to sit somewhere on it and the spec does not rank them, so they
 * are ranked here and the ranking is stated: a branch with no active channel
 * cannot sell at all, which is as bad as being at capacity (rank 3); a branch
 * with no live menu on a channel it trades on is a partial outage (rank 7).
 * Anything that cannot trade by design — draft, suspended, archived — sorts
 * below everything that could be trading and is not.
 */

export const STATE = {
  FORCE_CLOSED_OPEN_ENDED: { rank: 1, tone: "rose", pill: "failed", badge: "Closed manually", attention: true },
  AT_CAPACITY: { rank: 2, tone: "rose", pill: "failed", badge: "At capacity", attention: true },
  NO_CHANNEL: { rank: 3, tone: "rose", pill: "failed", badge: "Cannot sell", attention: true },
  FORCE_CLOSED_TIMED: { rank: 4, tone: "amber", pill: "pending", badge: "Closed manually", attention: false },
  CLOSED_BY_EXCEPTION: { rank: 5, tone: "amber", pill: "pending", badge: "Closed today", attention: true },
  OUTSIDE_HOURS: { rank: 6, tone: "none", pill: "neutral", badge: "Outside hours", attention: false },
  NO_LIVE_MENU: { rank: 7, tone: "amber", pill: "pending", badge: "No live menu", attention: true },
  FORCE_OPEN: { rank: 8, tone: "sky", pill: "neutral", badge: "Open manually", attention: false },
  TRADING: { rank: 9, tone: "none", pill: "active", badge: "Trading", attention: false },
  DRAFT: { rank: 10, tone: "grey", pill: "neutral", badge: "Draft", attention: false },
  SUSPENDED: { rank: 11, tone: "grey", pill: "neutral", badge: "Suspended", attention: false },
  ARCHIVED: { rank: 12, tone: "grey", pill: "neutral", badge: "Archived", attention: false },
};

const hhmm = (iso) => {
  const d = new Date(iso);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
};

/**
 * The worst state across the branch's bound modes, with the one caption that
 * explains it. Severity precedence is strict — only the top state speaks.
 */
export function resolveState(loc) {
  const ss = loc.serviceState;
  const mk = (key, caption, extra) => ({ key, caption, ...STATE[key], ...extra });

  if (loc.status === "ARCHIVED") return mk("ARCHIVED", "Archived — kept because orders reference it");
  if (loc.status === "SUSPENDED") return mk("SUSPENDED", "Suspended — accepts nothing on any channel");
  if (loc.status === "DRAFT") return mk("DRAFT", "Draft — not trading");

  if (ss.mode === "FORCE_CLOSED" && !ss.effectiveUntil) {
    return mk("FORCE_CLOSED_OPEN_ENDED",
      `Closed: ${reasonLabel(ss.reasonCode).toLowerCase()} · since ${hhmm(ss.changedAt)} · until cancelled`);
  }
  if (ss.maxConcurrentOrders && loc.holds >= ss.maxConcurrentOrders) {
    return mk("AT_CAPACITY", `Queue full: ${loc.holds} / ${ss.maxConcurrentOrders}`);
  }
  if (activeChannels(loc).length === 0) {
    return mk("NO_CHANNEL", "No active sales channel — nothing can reach this branch");
  }
  if (ss.mode === "FORCE_CLOSED") {
    return mk("FORCE_CLOSED_TIMED",
      `Closed: ${reasonLabel(ss.reasonCode).toLowerCase()} · until ${hhmm(ss.effectiveUntil)}`);
  }

  const modes = boundModes(loc);
  const exWindow = modes.map((m) => windowsToday(loc, m)).find((w) => w && w.closed && w.exception);
  if (exWindow) return mk("CLOSED_BY_EXCEPTION", `${exWindow.exception.label} — closed all day`, { exception: exWindow.exception });

  if (ss.mode === "FORCE_OPEN") {
    return mk("FORCE_OPEN",
      `Open manually: ${reasonLabel(ss.reasonCode).toLowerCase()} · until ${ss.effectiveUntil ? hhmm(ss.effectiveUntil) : "cancelled"}`);
  }

  const openNow = modes.some((m) => insideWindows(windowsToday(loc, m)));
  if (!openNow) {
    const next = nextOpenToday(loc);
    return mk("OUTSIDE_HOURS", next === null ? "Closed for today — opens tomorrow" : `Opens at ${fromMin(next)}`,
      { nextAvailableAt: next === null ? null : fromMin(next) });
  }

  const dark = activeChannels(loc).filter((c) => !loc.liveMenuFor.includes(c.channelId));
  if (dark.length) {
    return mk("NO_LIVE_MENU", `No published menu for «${channelOf(dark[0].channelId).name}»`, { darkChannel: dark[0].channelId });
  }

  return mk("TRADING", `Open until ${closingNow(loc) || "close"}`);
}

/* ── serviceability explainer (§13) ────────────────────────────────────────
 * Eight rules in evaluation order. The resolver short-circuits, so everything
 * after the first failure is "not evaluated" — rendering it green is the lie
 * that sends someone to the opening-hours screen for an hour.
 */

export function serviceabilityTrace(loc, channelId, mode) {
  const ch = channelOf(channelId);
  const binding = loc.channels.find((c) => c.channelId === channelId);
  const ss = loc.serviceState;
  const w = windowsToday(loc, mode);
  const rules = [];
  let failed = null;

  const add = (n, label, ok, detail, reason, fix) => {
    if (failed) { rules.push({ n, label, state: "skipped", detail: "not evaluated" }); return; }
    rules.push({ n, label, state: ok ? "pass" : "fail", detail, reason, fix });
    if (!ok) failed = reason;
  };

  add(1, "Channel is active and bound to this branch",
    ch.status === "ACTIVE" && binding && binding.status === "ACTIVE",
    !binding ? "Channel is not bound here at all"
      : binding.status !== "ACTIVE" ? "Bound but paused here"
      : ch.status !== "ACTIVE" ? `Channel is ${ch.status.toLowerCase()} tenant-wide` : "Bound and active",
    "CHANNEL_NOT_ENABLED", "Connect the channel");

  add(3, `Channel supports «${MODE_LABEL[mode]}»`,
    ch.modes && ch.modes.includes(mode) && !!loc.bindings[mode],
    !ch.modes || !ch.modes.includes(mode) ? "The channel does not carry this mode"
      : !loc.bindings[mode] ? "The branch has no binding for this mode"
      : `Channel carries it and the branch is bound via «${scheduleOf(loc.bindings[mode]).name}»`,
    "FULFILMENT_MODE_UNAVAILABLE", "Start serving this mode");

  add(4, "Not closed manually", ss.mode !== "FORCE_CLOSED",
    ss.mode === "FORCE_CLOSED"
      ? `${reasonLabel(ss.reasonCode)} · since ${hhmm(ss.changedAt)} · ${ss.effectiveUntil ? `until ${hhmm(ss.effectiveUntil)}` : "until cancelled"}`
      : ss.mode === "FORCE_OPEN" ? "Open manually — overrides the schedule" : "Following the schedule",
    "MANUALLY_CLOSED", "Reopen the branch");

  add(5, "No dated exception today", !(w && w.closed),
    w && w.closed ? `${w.exception.label} — closed all day` : "No exception on today's date",
    "CLOSED_BY_EXCEPTION", "Remove the exception");

  add(6, "Inside opening hours", ss.mode === "FORCE_OPEN" || insideWindows(w),
    w ? `Today: ${windowsLabel(w)}` : "No schedule bound for this mode",
    "OUTSIDE_SERVICE_HOURS", "Change the schedule");

  add(7, "Live menu published for this channel", loc.liveMenuFor.includes(channelId),
    loc.liveMenuFor.includes(channelId) ? "Publication is live" : "Catalog has no live publication for this channel",
    "NO_LIVE_MENU", "Publish the menu");

  add(8, "Below the concurrent-order limit",
    !ss.maxConcurrentOrders || loc.holds < ss.maxConcurrentOrders,
    ss.maxConcurrentOrders ? `${loc.holds} of ${ss.maxConcurrentOrders} slots held` : "No limit set",
    "AT_CAPACITY", "Raise the limit");

  const st = resolveState(loc);
  return {
    rules,
    available: !failed,
    reason: failed,
    nextAvailableAt: failed === "MANUALLY_CLOSED" && !ss.effectiveUntil ? null : st.nextAvailableAt || null,
    acceptsScheduledOrders: !!(scheduleOf(loc.bindings[mode]) || {}).acceptsScheduled,
    preparationMinutes: preparationNow(loc, mode),
    computedAt: NOW_ISO,
  };
}

/** The band covering now: highest priority, then narrowest. Null is a real
 *  answer — the storefront then quotes no time at all. */
export function preparationNow(loc, mode) {
  const covering = bandsFor(loc).filter(
    (b) => (!b.mode || b.mode === mode) && (!b.day || b.day === TODAY_DOW) &&
      NOW_MIN >= toMin(b.from) && NOW_MIN < toMin(b.to),
  );
  if (!covering.length) return null;
  covering.sort((a, b) => b.priority - a.priority || (toMin(a.to) - toMin(a.from)) - (toMin(b.to) - toMin(b.from)));
  return covering[0].minutes;
}

/** Uncovered minutes inside today's opening windows — a customer-facing hole. */
export function uncoveredWindows(loc) {
  const w = windowsToday(loc, boundModes(loc)[0]);
  if (!w || w.closed) return [];
  const gaps = [];
  w.windows.forEach((win) => {
    let cursor = win.from;
    const bands = bandsFor(loc)
      .filter((b) => !b.day || b.day === TODAY_DOW)
      .map((b) => ({ from: toMin(b.from), to: toMin(b.to) }))
      .sort((a, b) => a.from - b.from);
    bands.forEach((b) => {
      if (b.from > cursor && b.from < win.to) gaps.push({ from: cursor, to: Math.min(b.from, win.to) });
      cursor = Math.max(cursor, Math.min(b.to, win.to));
    });
    if (cursor < win.to) gaps.push({ from: cursor, to: win.to });
  });
  return gaps.filter((g) => g.to - g.from >= 15);
}

/* ── activation preconditions (§2.4) ───────────────────────────────────────*/

export function activationBlockers(loc) {
  const out = [];
  if (!boundModes(loc).length) out.push({ tab: "hours", text: "No schedule bound to any fulfilment mode" });
  if (!activeChannels(loc).length) out.push({ tab: "channels", text: "No active sales channel" });
  if (!loc.offerings.available) out.push({ tab: "profile", text: "No available offerings in the catalog" });
  out.push({ tab: "fiscal", text: "No fiscal assignment — will block activation once ADR 0038 lands", pending: true });
  return out;
}

/* ── what the backend does not have (§16) ──────────────────────────────────
 * Rendered on screen rather than omitted, so the gap is a decision someone can
 * see rather than a field that quietly never existed.
 */

export const PENDING_FIELDS = {
  phone: { label: "Phone", note: "No column on tenant.locations — no owning ADR (nearest 0002)" },
  address: { label: "Address", note: "No column on tenant.locations — no owning ADR" },
  landmark: { label: "Landmark", note: "First-class field in this market — no owning ADR" },
  point: { label: "Map point", note: "No latitude/longitude column — blocks ADR 0037 radius distance" },
  region: { label: "Region", note: "ADR 0037 fulfillment.regions — accepted, not started" },
  nameLocales: { label: "Name (ru / uz / en)", note: "catalog.translations admits catalog entities only — no owning ADR" },
  cover: { label: "Cover image", note: "media.assets exists; the role binding does not — ADR 0010" },
  tags: { label: "Tags", note: "IA 10.10 reference data — no owning ADR" },
  sortOrder: { label: "Storefront order", note: "IA 6.8 — no owning ADR" },
};

export const FISCAL_FIELDS = [
  "Legal entity — code, legal name, short name",
  "TIN — unique per tenant, length and checksum validated",
  "VAT registration and certificate reference",
  "Tax profile (ADR 0018)",
  "Effective from / effective until — a dated range, not a field",
  "Approved by and approval reference (ADR 0027)",
];

/* ── local presentation primitives ─────────────────────────────────────────
 * Small pieces the section needs and the shared component set does not carry:
 * the severity palette, a banded callout, a pending-field marker, a load bar
 * and the mode pills. They live beside the fixtures rather than inside the
 * screen so the screen file stays about the four views it renders.
 */
import { canvas, hairline, ink, inkMuted, inkSubtle, surface1, blue } from "./components";

/* ── severity presentation ─────────────────────────────────────────────────
 * Severity is never colour alone: a tint, a 3px left rule, and a caption that
 * says why. Normal rows carry a transparent rule so the alignment never jumps
 * when a branch goes bad.
 *
 * One deliberate departure from the spec's palette: it asks for a sky tint on
 * force-open, and platform blue is reserved here for the primary action, links,
 * focus and selection. Force-open therefore reads as ink-on-surface — still
 * three channels, still distinct from amber and from grey.
 */
export const TONE_STYLE = {
  rose:  { tint: "var(--q-error-tint)",   rule: "var(--q-error)",     text: "var(--q-error-text)" },
  amber: { tint: "var(--q-warning-tint)", rule: "var(--q-warning)",   text: "var(--q-warning-text)" },
  sky:   { tint: surface1,                rule: ink,                  text: inkMuted },
  grey:  { tint: "transparent",           rule: "var(--q-surface-2)", text: inkSubtle },
  none:  { tint: "transparent",           rule: "transparent",        text: inkMuted },
};

/* The pill tone is per state, not per severity tone: "outside hours" and
 * "trading" share a tone because neither is a fault, but only one of them is
 * green — a branch that is shut for the night must not read as healthy. */
export const pillOf = (st) => st.pill || "neutral";

export const mono = { fontFamily: "var(--q-font-mono)" };

/* ── small local pieces ────────────────────────────────────────────────────*/

export function Block({ title, description, right, children, first }) {
  return (
    <section style={{ marginTop: first ? 0 : 32 }}>
      <div style={{ display: "flex", alignItems: "flex-end", gap: 16, marginBottom: 12 }}>
        <div style={{ minWidth: 0 }}>
          <h2 className="q-subhead" style={{ margin: 0, color: ink }}>{title}</h2>
          {description ? (
            <p className="q-body-sm" style={{ margin: "2px 0 0", color: inkMuted }}>{description}</p>
          ) : null}
        </div>
        {right ? <div style={{ marginLeft: "auto", flexShrink: 0 }}>{right}</div> : null}
      </div>
      {children}
    </section>
  );
}

/** A banded callout. Same three channels as a row: tint, 3px rule, a reason. */
export function Callout({ tone = "amber", title, children, right }) {
  const t = TONE_STYLE[tone];
  return (
    <div
      style={{
        display: "flex", gap: 16, alignItems: "flex-start",
        background: t.tint === "transparent" ? surface1 : t.tint,
        border: `1px solid ${hairline}`,
        borderLeft: `3px solid ${t.rule === "transparent" ? hairline : t.rule}`,
        padding: "12px 16px",
      }}
    >
      <div style={{ minWidth: 0 }}>
        <div className="q-emphasis" style={{ color: t.text }}>{title}</div>
        {children ? <div className="q-body-sm" style={{ color: inkMuted, marginTop: 4 }}>{children}</div> : null}
      </div>
      {right ? <div style={{ marginLeft: "auto", flexShrink: 0 }}>{right}</div> : null}
    </div>
  );
}

/** A field the schema cannot store yet. Shown, not omitted — an absent field is
 *  invisible, and an invisible gap never gets an ADR written for it. */
export function PendingField({ spec }) {
  return (
    <div style={{ minWidth: 0 }}>
      <div className="q-caption" style={{ color: inkSubtle, marginBottom: 4 }}>{spec.label}</div>
      <div
        className="q-body-sm"
        style={{ color: inkSubtle, background: surface1, border: `1px dashed ${hairline}`, padding: "6px 8px" }}
      >
        Pending schema
      </div>
      <div className="q-caption" style={{ color: inkSubtle, marginTop: 4 }}>{spec.note}</div>
    </div>
  );
}

/** Load as a number and a bar. `n / ∞` is a real answer, not a missing one. */
export function LoadBar({ held, max, stale }) {
  const full = max && held >= max;
  return (
    <div>
      <div className="q-body-sm q-tnum" style={{ color: full ? "var(--q-error-text)" : ink }}>
        {held} / {max || "∞"}
      </div>
      <div style={{ display: "flex", gap: 2, marginTop: 4 }}>
        {Array.from({ length: max || 6 }).map((_, i) => (
          <span
            key={i}
            style={{
              width: 6, height: 4,
              background: stale ? "var(--q-surface-2)"
                : i < held ? (full ? "var(--q-error)" : ink)
                : max ? "var(--q-surface-2)" : "transparent",
              border: max && i >= held ? `1px dashed ${hairline}` : "none",
            }}
          />
        ))}
      </div>
    </div>
  );
}

export function ModePills({ loc }) {
  return (
    <div style={{ display: "flex", gap: 4 }}>
      {MODES.map((m) => {
        const on = !!loc.bindings[m];
        return (
          <span
            key={m}
            className="q-caption"
            title={on ? `${MODE_LABEL[m]}: ${scheduleOf(loc.bindings[m]).name}` : `${MODE_LABEL[m]} not served here`}
            style={{
              padding: "1px 6px", border: `1px solid ${on ? ink : hairline}`,
              color: on ? ink : inkSubtle, background: canvas, whiteSpace: "nowrap",
            }}
          >
            {MODE_SHORT[m]}
          </span>
        );
      })}
    </div>
  );
}

/** A count that answers "which ones?" in one click, per the spec's rule that a
 *  number in a table always raises the question. */
export function CountLink({ n, onClick, warn, title }) {
  return (
    <button
      type="button"
      onClick={(e) => { e.stopPropagation(); onClick(); }}
      className="q-body-sm q-tnum"
      title={title}
      style={{
        background: "transparent", border: "none", padding: 0, cursor: "pointer",
        color: blue, display: "inline-flex", alignItems: "center", gap: 6,
      }}
    >
      {n}
      {warn ? <span style={{ width: 6, height: 6, borderRadius: "50%", background: "var(--q-warning)" }} /> : null}
    </button>
  );
}
