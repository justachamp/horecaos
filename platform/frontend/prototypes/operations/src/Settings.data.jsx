/* Fixtures for Settings — the configuration section of the operations console.
 *
 * Authored backwards from the states the screen has to show, per the spec's own
 * instruction that a settings screen rendering a flat form is wrong even when
 * every field in it is correct. So the awkward cases are here on purpose:
 *
 *   · a branch force-closed with no expiry since Tuesday (the fryer-broke case
 *     ADR 0036's migration comment names), sitting beside a branch closed with
 *     a proper expiry and one force-opened on an exception day
 *   · a very long branch name beside "Sergeli"
 *   · a value whose last author is a person whose account has since been
 *     disabled — the resolution trace still has to name them
 *   · a key deliberately cleared at brand level (`is_explicit_null`), which is
 *     a different fact from "never set here"
 *   · a channel that is active and sells nothing, because its payment matrix is
 *     empty and the model reads an empty matrix as "sells nothing"
 *   · matrix cells that cannot be enabled at all, because no fiscal terminal at
 *     the location can discharge the receipt obligation for cash
 *   · a policy sitting in approval, requested by the person who would otherwise
 *     approve it
 *
 * Money is whole som as integers. Times are ISO strings. Content is Uzbek and
 * Russian as an Uzbek restaurant chain actually writes it; the console chrome is
 * English, matching the rest of this prototype.
 */

export const SETTINGS_NOW = "2026-08-21T19:34:00";

/* ── the ancestry ADR 0030 resolves down ──────────────────────────────────
 * PLATFORM → TENANT → BRAND → LOCATION. Everything on every screen in this
 * section is positioned somewhere on this ladder, and the screen says where.
 */

export const TENANT = {
  id: "tn-1",
  legalName: "«OSH MARKAZI» MCHJ",
  currency: "UZS",
  timezone: "Asia/Tashkent",
  defaultLocale: "uz",
};

export const BRANDS = [
  { id: "br-osh", code: "OSH_MARKAZI", name: "Osh Markazi", status: "ACTIVE", locationCount: 9 },
  { id: "br-rayhon", code: "RAYHON", name: "Rayhon", status: "ACTIVE", locationCount: 3 },
];

/* ── schedules ─────────────────────────────────────────────────────────────
 * Named reusable timetables bound per fulfilment mode (ADR 0036), not a pair of
 * fixed "venue hours" / "delivery hours" fields. «Tungi» carries the overnight
 * window the migration comment warns about: closes_at <= opens_at means the
 * window ends the next day and the grid must draw it crossing midnight.
 */

export const SCHEDULES = [
  {
    id: "sc-standard", name: "Standart", usedByCount: 11, acceptsScheduledOrders: true,
    windows: { mon: ["09:00–23:00"], tue: ["09:00–23:00"], wed: ["09:00–23:00"], thu: ["09:00–23:00"], fri: ["09:00–23:30"], sat: ["09:00–23:30"], sun: ["10:00–22:00"] },
  },
  {
    id: "sc-hall", name: "Zal", usedByCount: 6, acceptsScheduledOrders: false,
    windows: { mon: ["11:00–22:00"], tue: ["11:00–22:00"], wed: ["11:00–22:00"], thu: ["11:00–22:00"], fri: ["11:00–23:00"], sat: ["11:00–23:00"], sun: ["11:00–21:00"] },
  },
  {
    id: "sc-night", name: "Tungi yetkazib berish", usedByCount: 2, acceptsScheduledOrders: true,
    overnight: true,
    windows: { mon: ["20:00–03:00"], tue: ["20:00–03:00"], wed: ["20:00–03:00"], thu: ["20:00–03:00"], fri: ["20:00–04:00"], sat: ["20:00–04:00"], sun: ["20:00–02:00"] },
  },
  {
    id: "sc-lunch", name: "Faqat tushlik", usedByCount: 1, acceptsScheduledOrders: false,
    windows: { mon: ["11:30–15:00"], tue: ["11:30–15:00"], wed: ["11:30–15:00"], thu: ["11:30–15:00"], fri: ["11:30–15:00"], sat: [], sun: [] },
  },
];

/* Reference list, sorted by an admin-controlled display_order rather than by
 * anything computed — muscle memory beats freshness at 20:30 on a Friday. */
export const CLOSE_REASONS = [
  { code: "EQUIPMENT", order: 1, internal: "Uskuna ishlamayapti", customer: "Filial vaqtincha buyurtma qabul qilmayapti" },
  { code: "NO_STAFF", order: 2, internal: "Xodim yetishmayapti", customer: "Filial vaqtincha buyurtma qabul qilmayapti" },
  { code: "OVERLOADED", order: 3, internal: "Oshxona to'lib ketdi", customer: "Hozir buyurtmalar juda ko'p, birozdan so'ng urinib ko'ring" },
  { code: "SUPPLY", order: 4, internal: "Mahsulot tugadi", customer: "Filial vaqtincha buyurtma qabul qilmayapti" },
  { code: "UTILITIES", order: 5, internal: "Svet/gaz yo'q", customer: "Filial vaqtincha buyurtma qabul qilmayapti" },
  { code: "OTHER", order: 6, internal: "Boshqa sabab", customer: "Filial vaqtincha buyurtma qabul qilmayapti" },
];

/* ── locations ─────────────────────────────────────────────────────────────
 * `mode` is the override; `scheduleOpen` is what the timetable says right now.
 * The two together produce the "Сейчас" column, and lateness-style overlays are
 * never collapsed into the status: a branch is ACTIVE and force-closed, which
 * are two different columns.
 */

export const LOCATIONS = [
  {
    id: "loc-bunyodkor", brandId: "br-osh",
    name: "Bunyodkor shoh ko'chasi 12 — «Chilonzor Savdo Markazi» ichidagi filial",
    code: "CHZ_SM", status: "ACTIVE", timezone: "Asia/Tashkent",
    address: "Bunyodkor shoh ko'chasi 12, 2-qavat, Chilonzor tumani",
    mode: "FORCE_CLOSED", scheduleOpen: true,
    reasonCode: "EQUIPMENT", note: "Fritür buzildi, ehtiyot qismi kutilyapti",
    effectiveUntil: null, overrideSince: "2026-08-18T14:05:00", overrideBy: "Nodira Ismoilova",
    bindings: [{ mode: "DELIVERY", scheduleId: "sc-standard" }, { mode: "PICKUP", scheduleId: "sc-standard" }, { mode: "DINE_IN", scheduleId: "sc-hall" }],
    prepMinutes: 22, maxConcurrent: 20, holds: 0,
    channelIds: ["ch-web", "ch-telegram", "ch-qr", "ch-call"],
    legalEntity: null,
  },
  {
    id: "loc-yunusobod", brandId: "br-osh",
    name: "Yunusobod 19-kvartal", code: "YUN", status: "ACTIVE", timezone: "Asia/Tashkent",
    address: "Yunusobod 19-kvartal, 7-uy",
    mode: "FORCE_CLOSED", scheduleOpen: true,
    reasonCode: "OVERLOADED", note: "Oshxona to'lib ketdi, 21:30 da ochiladi",
    effectiveUntil: "2026-08-21T21:30:00", overrideSince: "2026-08-21T19:12:00", overrideBy: "Jasur Toshmatov",
    bindings: [{ mode: "DELIVERY", scheduleId: null }, { mode: "PICKUP", scheduleId: "sc-standard" }],
    prepMinutes: 18, maxConcurrent: 15, holds: 15,
    channelIds: ["ch-web", "ch-telegram", "ch-call", "ch-yandex"],
    legalEntity: null,
  },
  {
    id: "loc-sergeli", brandId: "br-osh",
    name: "Sergeli", code: "SRG", status: "ACTIVE", timezone: "Asia/Tashkent",
    address: "Sergeli 4-mavze, 11-uy",
    mode: "FOLLOW_SCHEDULE", scheduleOpen: false,
    reasonCode: null, note: "Bayram kuni — 21.08 dam olish", effectiveUntil: null,
    overrideSince: null, overrideBy: null, exceptionToday: "Mustaqillik arafasi — yopiq",
    bindings: [{ mode: "DELIVERY", scheduleId: "sc-standard" }, { mode: "PICKUP", scheduleId: "sc-standard" }],
    prepMinutes: 16, maxConcurrent: null, holds: 0,
    channelIds: [],
    legalEntity: null,
  },
  {
    id: "loc-chilonzor", brandId: "br-osh",
    name: "Chilonzor 9-kvartal", code: "CHZ", status: "ACTIVE", timezone: "Asia/Tashkent",
    address: "Chilonzor 9-kvartal, 42-uy",
    mode: "FORCE_OPEN", scheduleOpen: false,
    reasonCode: "OTHER", note: "To'y buyurtmasi — kechqurun ochiq ishlaymiz",
    effectiveUntil: "2026-08-22T02:00:00", overrideSince: "2026-08-21T18:00:00", overrideBy: "Alisher Karimov",
    bindings: [{ mode: "DELIVERY", scheduleId: "sc-night" }, { mode: "PICKUP", scheduleId: "sc-standard" }, { mode: "DINE_IN", scheduleId: "sc-hall" }],
    prepMinutes: 15, maxConcurrent: 20, holds: 7,
    channelIds: ["ch-web", "ch-telegram", "ch-qr", "ch-call", "ch-kiosk", "ch-yandex"],
    legalEntity: null,
  },
  {
    id: "loc-mirzo", brandId: "br-osh",
    name: "Mirzo Ulug'bek — Buyuk Ipak Yo'li", code: "MRZ", status: "ACTIVE", timezone: "Asia/Tashkent",
    address: "Buyuk Ipak Yo'li ko'chasi 145",
    mode: "FOLLOW_SCHEDULE", scheduleOpen: true,
    reasonCode: null, note: null, effectiveUntil: null, overrideSince: null, overrideBy: null,
    bindings: [{ mode: "DELIVERY", scheduleId: "sc-standard" }, { mode: "PICKUP", scheduleId: "sc-standard" }, { mode: "DINE_IN", scheduleId: "sc-hall" }],
    prepMinutes: 17, maxConcurrent: 25, holds: 11,
    channelIds: ["ch-web", "ch-telegram", "ch-qr", "ch-call", "ch-yandex", "ch-ios"],
    legalEntity: null,
  },
  {
    id: "loc-yakkasaroy", brandId: "br-osh",
    name: "Yakkasaroy — Shota Rustaveli", code: "YKS", status: "ACTIVE", timezone: "Asia/Tashkent",
    address: "Shota Rustaveli ko'chasi 38",
    mode: "FOLLOW_SCHEDULE", scheduleOpen: true,
    reasonCode: null, note: null, effectiveUntil: null, overrideSince: null, overrideBy: null,
    bindings: [{ mode: "DELIVERY", scheduleId: "sc-standard" }, { mode: "PICKUP", scheduleId: "sc-standard" }],
    prepMinutes: 19, maxConcurrent: 15, holds: 4,
    channelIds: ["ch-web", "ch-telegram", "ch-call"],
    legalEntity: null,
  },
  {
    id: "loc-olmazor", brandId: "br-osh",
    name: "Olmazor — Nukus ko'chasi", code: "OLM", status: "ACTIVE", timezone: "Asia/Tashkent",
    address: "Nukus ko'chasi 3",
    mode: "FOLLOW_SCHEDULE", scheduleOpen: true,
    reasonCode: null, note: null, effectiveUntil: null, overrideSince: null, overrideBy: null,
    bindings: [{ mode: "DELIVERY", scheduleId: "sc-standard" }, { mode: "PICKUP", scheduleId: "sc-standard" }],
    prepMinutes: 16, maxConcurrent: null, holds: 2,
    channelIds: ["ch-web", "ch-telegram", "ch-call"],
    legalEntity: null,
  },
  {
    id: "loc-mirobod", brandId: "br-osh",
    name: "Mirobod — Amir Temur ko'chasi", code: "MRB", status: "SUSPENDED", timezone: "Asia/Tashkent",
    address: "Amir Temur ko'chasi 108",
    mode: "FOLLOW_SCHEDULE", scheduleOpen: false,
    reasonCode: null, note: "Ijara shartnomasi qayta ko'rib chiqilyapti", effectiveUntil: null,
    overrideSince: null, overrideBy: null,
    bindings: [{ mode: "PICKUP", scheduleId: "sc-lunch" }],
    prepMinutes: 20, maxConcurrent: null, holds: 0,
    channelIds: ["ch-call"],
    legalEntity: null,
  },
  {
    id: "loc-almaty", brandId: "br-osh",
    name: "Almaty — Dostyq", code: "ALA", status: "DRAFT", timezone: "Asia/Almaty",
    address: "Достык 240, Алматы",
    mode: "FOLLOW_SCHEDULE", scheduleOpen: false,
    reasonCode: null, note: "Ochilish 09.09 rejalashtirilgan", effectiveUntil: null,
    overrideSince: null, overrideBy: null,
    bindings: [],
    prepMinutes: 20, maxConcurrent: null, holds: 0,
    channelIds: [],
    legalEntity: null,
  },
  {
    id: "loc-rh-shayx", brandId: "br-rayhon",
    name: "Rayhon — Shayxontohur", code: "RH_SHX", status: "ACTIVE", timezone: "Asia/Tashkent",
    address: "Zarqaynar ko'chasi 21",
    mode: "FOLLOW_SCHEDULE", scheduleOpen: true,
    reasonCode: null, note: null, effectiveUntil: null, overrideSince: null, overrideBy: null,
    bindings: [{ mode: "DELIVERY", scheduleId: "sc-standard" }, { mode: "DINE_IN", scheduleId: "sc-hall" }],
    prepMinutes: 24, maxConcurrent: 12, holds: 6,
    channelIds: ["ch-web", "ch-telegram", "ch-qr"],
    legalEntity: null,
  },
  {
    id: "loc-rh-uchtepa", brandId: "br-rayhon",
    name: "Rayhon — Uchtepa", code: "RH_UCH", status: "ACTIVE", timezone: "Asia/Tashkent",
    address: "Chertanak ko'chasi 9",
    mode: "FOLLOW_SCHEDULE", scheduleOpen: true,
    reasonCode: null, note: null, effectiveUntil: null, overrideSince: null, overrideBy: null,
    bindings: [{ mode: "DELIVERY", scheduleId: "sc-standard" }, { mode: "PICKUP", scheduleId: "sc-standard" }],
    prepMinutes: 21, maxConcurrent: 10, holds: 3,
    channelIds: ["ch-web", "ch-telegram"],
    legalEntity: null,
  },
  {
    id: "loc-rh-bektemir", brandId: "br-rayhon",
    name: "Rayhon — Bektemir", code: "RH_BKT", status: "ACTIVE", timezone: "Asia/Tashkent",
    address: "Sohil ko'chasi 4",
    mode: "FOLLOW_SCHEDULE", scheduleOpen: true,
    reasonCode: null, note: null, effectiveUntil: null, overrideSince: null, overrideBy: null,
    bindings: [{ mode: "DELIVERY", scheduleId: "sc-standard" }, { mode: "PICKUP", scheduleId: "sc-standard" }],
    prepMinutes: 23, maxConcurrent: null, holds: 1,
    channelIds: ["ch-web", "ch-telegram"],
    legalEntity: null,
  },
];

/* ── sales channels ────────────────────────────────────────────────────────
 * Tenant-owned by design (ADR 0036 keys the child tables on (tenant_id, id)),
 * which is why the scope bar reads TENANT on this screen and the brand picker
 * has no effect on it. `Зал` is deliberately absent from the type list: dine-in
 * is a fulfilment mode, never a channel.
 */

export const CHANNEL_TYPES = [
  "WEB", "IOS", "ANDROID", "TELEGRAM", "KIOSK", "QR_TABLE", "CALL_CENTRE", "AGGREGATOR", "POS",
];

export const SALES_CHANNELS = [
  { id: "ch-web", code: "WEB", name: "Sayt — oshmarkazi.uz", type: "WEB", status: "ACTIVE", pricePlane: null, externallyPriced: false, guestOrders: true, installationId: null },
  { id: "ch-telegram", code: "TG_BOT", name: "Telegram bot @oshmarkazi_bot", type: "TELEGRAM", status: "ACTIVE", pricePlane: null, externallyPriced: false, guestOrders: false, installationId: "in-telegram" },
  { id: "ch-ios", code: "IOS_APP", name: "iOS ilova", type: "IOS", status: "ACTIVE", pricePlane: "ch-web", externallyPriced: false, guestOrders: false, installationId: null },
  { id: "ch-android", code: "ANDROID_APP", name: "Android ilova", type: "ANDROID", status: "ACTIVE", pricePlane: "ch-web", externallyPriced: false, guestOrders: false, installationId: null },
  { id: "ch-kiosk", code: "KIOSK_CHZ", name: "Kiosk — Chilonzor 9-kvartal", type: "KIOSK", status: "ACTIVE", pricePlane: null, externallyPriced: false, guestOrders: true, installationId: null },
  { id: "ch-qr", code: "QR_TABLE", name: "QR stol menyusi", type: "QR_TABLE", status: "ACTIVE", pricePlane: null, externallyPriced: false, guestOrders: true, installationId: "in-rkeeper" },
  { id: "ch-call", code: "CALL_CENTRE", name: "Call-markaz", type: "CALL_CENTRE", status: "ACTIVE", pricePlane: null, externallyPriced: false, guestOrders: true, installationId: null },
  { id: "ch-yandex", code: "YANDEX_EATS", name: "Yandex Eats", type: "AGGREGATOR", status: "ACTIVE", pricePlane: null, externallyPriced: true, guestOrders: true, installationId: "in-yandex" },
  { id: "ch-express", code: "EXPRESS24", name: "Express24", type: "AGGREGATOR", status: "INACTIVE", pricePlane: null, externallyPriced: true, guestOrders: true, installationId: "in-express" },
  { id: "ch-pos-old", code: "RKEEPER_HALL", name: "R-Keeper zal (eski)", type: "POS", status: "ARCHIVED", pricePlane: null, externallyPriced: false, guestOrders: false, installationId: "in-rkeeper" },
];

/* Today `tenant.channel_payment_methods.payment_method_code` is a bare
 * varchar(32) with a format check and no foreign key — the migration's own
 * comment says so and names ADR 0038 as the owner. So this column set is the
 * code-owned provisional one, and the screen has to say that out loud. */
export const PAYMENT_METHOD_COLUMNS = [
  { code: "CASH", label: "Naqd" },
  { code: "CLICK", label: "Click" },
  { code: "PAYME", label: "Payme" },
];

export const FULFILMENT_MODES = [
  { code: "DELIVERY", label: "Yetkazib berish" },
  { code: "PICKUP", label: "Olib ketish" },
  { code: "DINE_IN", label: "Zalda" },
];

/* Cell values: ON · OFF · BLOCKED. BLOCKED is hatched and not clickable —
 * a method whose fiscal responsibility cannot be discharged at the locations
 * this channel serves is not a warning, it is a serviceability precondition. */
export const CHANNEL_PAYMENTS = {
  "ch-web": { CASH: "ON", CLICK: "ON", PAYME: "ON" },
  "ch-telegram": { CASH: "ON", CLICK: "ON", PAYME: "ON" },
  "ch-ios": { CASH: "OFF", CLICK: "OFF", PAYME: "OFF" },
  "ch-android": { CASH: "OFF", CLICK: "ON", PAYME: "OFF" },
  "ch-kiosk": { CASH: "BLOCKED", CLICK: "ON", PAYME: "ON" },
  "ch-qr": { CASH: "BLOCKED", CLICK: "ON", PAYME: "ON" },
  "ch-call": { CASH: "ON", CLICK: "ON", PAYME: "OFF" },
  "ch-yandex": { CASH: "ON", CLICK: "OFF", PAYME: "OFF" },
  "ch-express": { CASH: "OFF", CLICK: "OFF", PAYME: "OFF" },
  "ch-pos-old": { CASH: "ON", CLICK: "OFF", PAYME: "OFF" },
};

export const CHANNEL_MODES = {
  "ch-web": { DELIVERY: "ON", PICKUP: "ON", DINE_IN: "OFF" },
  "ch-telegram": { DELIVERY: "ON", PICKUP: "ON", DINE_IN: "OFF" },
  "ch-ios": { DELIVERY: "ON", PICKUP: "ON", DINE_IN: "OFF" },
  "ch-android": { DELIVERY: "ON", PICKUP: "OFF", DINE_IN: "OFF" },
  "ch-kiosk": { DELIVERY: "BLOCKED", PICKUP: "OFF", DINE_IN: "OFF" },
  "ch-qr": { DELIVERY: "BLOCKED", PICKUP: "OFF", DINE_IN: "ON" },
  "ch-call": { DELIVERY: "ON", PICKUP: "ON", DINE_IN: "OFF" },
  "ch-yandex": { DELIVERY: "ON", PICKUP: "OFF", DINE_IN: "OFF" },
  "ch-express": { DELIVERY: "OFF", PICKUP: "OFF", DINE_IN: "OFF" },
  "ch-pos-old": { DELIVERY: "OFF", PICKUP: "OFF", DINE_IN: "ON" },
};

/** Why a cell is hatched, in one sentence, per the spec's hover requirement. */
export const CELL_BLOCKED_REASON = {
  "ch-kiosk:CASH": "Kioskda faol fiskal terminal yo'q — naqd pul cheki berilmaydi (ADR 0038)",
  "ch-qr:CASH": "Zalda naqd chek POS orqali beriladi, ammo R-Keeper ulanishi tasdiqlanmagan",
  "ch-kiosk:DELIVERY": "Kiosk faqat bitta filialda turadi va yetkazib berish zonasiga bog'lanmagan",
  "ch-qr:DELIVERY": "QR stol — zaldagi kanal, yetkazib berish rejimi ma'noga ega emas (ADR 0036)",
};

/* ── integrations, only as far as Settings home and channels need them ─────*/

export const INSTALLATIONS = [
  { id: "in-rkeeper", name: "R-Keeper", category: "POS", environment: "prod", connection: "FAILED", checkedAt: "2026-08-21T19:22:00", evidence: "HTTP 503 от api.rkeeper.uz — 14 попыток подряд" },
  { id: "in-click", name: "Click Merchant", category: "PAYMENT", environment: "prod", connection: "UNVERIFIED", checkedAt: null, evidence: "Учётные данные заданы, связь ни разу не проверялась" },
  { id: "in-payme", name: "Payme", category: "PAYMENT", environment: "prod", connection: "SUCCEEDED", checkedAt: "2026-08-21T19:30:00", evidence: null, secretAgeDays: 412 },
  { id: "in-telegram", name: "Telegram Bot API", category: "NOTIFICATION", environment: "prod", connection: "SUCCEEDED", checkedAt: "2026-08-21T19:31:00", evidence: null },
  { id: "in-yandex", name: "Yandex Eats", category: "OTHER", environment: "prod", connection: "SUCCEEDED", checkedAt: "2026-08-21T19:28:00", evidence: null },
  { id: "in-express", name: "Express24", category: "OTHER", environment: "sandbox", connection: "UNVERIFIED", checkedAt: null, evidence: null },
];

/* ── the key registry ──────────────────────────────────────────────────────
 * ADR 0030 makes keys code-owned and enumerable, which is the only reason
 * "find a setting" can exist at all. `levels` is the stored state, not the
 * resolved one — the screen resolves it, most specific first, so that the trace
 * popover and the field agree by construction.
 *
 *   SET     — a row exists at this level
 *   NULLED  — a row exists and is an explicit null (`is_explicit_null`)
 *   UNSET   — no row. Different fact from NULLED, and ADR 0030 stores them
 *             differently, so the UI may not collapse them.
 */

const at = (state, value, by, when, reason) => ({ state, value, by, when, reason });

export const CONFIG_KEYS = [
  {
    code: "ordering.late_threshold_minutes",
    label: "Order counts as late after",
    where: "Selling → Order policy", screen: "order-policy", card: "sla",
    type: "minutes", unit: "min", built: false, adr: "ADR 0030 registry · content ADR 0019",
    note: "The single most-used value on the order board. Not a status — lateness is an overlay computed from this threshold.",
    settableAt: ["PLATFORM", "TENANT", "BRAND", "LOCATION"], explicitNullTerminates: false,
    levels: {
      PLATFORM: at("SET", 20, "Qoida", "2025-11-04T09:00:00", "Platforma qiymati"),
      TENANT: at("SET", 15, "Alisher Karimov", "2026-03-12T10:41:00", "Yagona va'da"),
      BRAND: { "br-osh": at("SET", 12, "Nodira Ismoilova", "2026-06-02T11:15:00", "Osh Markazi tezroq ishlaydi"), "br-rayhon": at("UNSET") },
      LOCATION: {
        "loc-bunyodkor": at("SET", 25, "Ilhom Toshmatov", "2026-01-19T16:02:00", "Savdo markazida lift kutish vaqti"),
        "loc-chilonzor": at("NULLED", null, "Nodira Ismoilova", "2026-07-30T09:20:00", "Filial darajasidagi eski qiymat olib tashlandi"),
      },
    },
  },
  {
    code: "ordering.average_order_minutes",
    label: "Average order time",
    where: "Selling → Order policy", screen: "order-policy", card: "sla",
    type: "minutes", unit: "min", built: false, adr: "ADR 0030 registry",
    settableAt: ["PLATFORM", "TENANT", "BRAND", "LOCATION"], explicitNullTerminates: false,
    levels: {
      PLATFORM: at("SET", 40, "Qoida", "2025-11-04T09:00:00", "Platforma qiymati"),
      TENANT: at("UNSET"),
      BRAND: { "br-osh": at("SET", 35, "Alisher Karimov", "2026-03-12T10:44:00", null), "br-rayhon": at("UNSET") },
      LOCATION: { "loc-chilonzor": at("SET", 30, "Alisher Karimov", "2026-08-04T12:10:00", null) },
    },
  },
  {
    code: "ordering.maximum_order_minutes",
    label: "Maximum order time",
    where: "Selling → Order policy", screen: "order-policy", card: "sla",
    type: "minutes", unit: "min", built: false, adr: "ADR 0030 registry",
    settableAt: ["PLATFORM", "TENANT", "BRAND", "LOCATION"], explicitNullTerminates: false,
    levels: {
      PLATFORM: at("SET", 90, "Qoida", "2025-11-04T09:00:00", null),
      TENANT: at("SET", 75, "Alisher Karimov", "2026-03-12T10:45:00", null),
      BRAND: { "br-osh": at("UNSET"), "br-rayhon": at("UNSET") },
      LOCATION: {},
    },
  },
  {
    code: "ordering.minimum_order_amount",
    label: "Minimum order amount",
    where: "Selling → Order policy", screen: "order-policy", card: "sla",
    type: "money", built: false, adr: "ADR 0030 registry",
    note: "Overlaps fulfillment.service_zone_versions.min_basket_minor. Decided once: the zone value wins for delivery, this one applies to pickup and dine-in.",
    settableAt: ["PLATFORM", "TENANT", "BRAND", "LOCATION"], explicitNullTerminates: false,
    levels: {
      PLATFORM: at("UNSET"),
      TENANT: at("SET", 40000, "Alisher Karimov", "2026-03-12T10:47:00", null),
      BRAND: { "br-osh": at("SET", 50000, "Nodira Ismoilova", "2026-05-21T15:30:00", "Yetkazib berish narxi oshdi"), "br-rayhon": at("UNSET") },
      LOCATION: { "loc-bunyodkor": at("SET", 70000, "Ilhom Toshmatov", "2026-01-19T16:05:00", "Savdo markazi ijara narxi") },
    },
  },
  {
    code: "ordering.business_day_start",
    label: "Business day starts at",
    where: "Selling → Order policy", screen: "order-policy", card: "sla",
    type: "time", built: false, adr: "ADR 0043 depends on it — a business day that crosses midnight is still an open question",
    settableAt: ["PLATFORM", "TENANT", "BRAND"], explicitNullTerminates: false,
    levels: {
      PLATFORM: at("SET", "09:00", "Qoida", "2025-11-04T09:00:00", null),
      TENANT: at("UNSET"),
      BRAND: { "br-osh": at("UNSET"), "br-rayhon": at("UNSET") },
      LOCATION: {},
    },
  },
  {
    code: "ordering.late_indicator_colour",
    label: "Late indicator colour",
    where: "Selling → Order policy", screen: "order-policy", card: "sla",
    type: "colour", built: false, adr: "ADR 0030 registry",
    note: "Must be validated against the SLA ramp for contrast (IA Part 4) before it can be offered — a tenant-chosen colour that fails contrast makes the whole late indicator unreadable.",
    settableAt: ["PLATFORM", "TENANT", "BRAND"], explicitNullTerminates: false,
    levels: { PLATFORM: at("UNSET"), TENANT: at("UNSET"), BRAND: {}, LOCATION: {} },
  },
  {
    code: "delivery.distance_mode",
    label: "Distance calculation",
    where: "Selling → Order policy", screen: "order-policy", card: "sla",
    type: "readonly", built: true, adr: "ADR 0037",
    readonlyValue: "Per delivery tariff",
    note: "Per tariff, never global — Delever's global toggle is the worse design and one tenant genuinely wants both. Edited in Delivery 3.7.",
    settableAt: [], explicitNullTerminates: false, levels: { PLATFORM: at("UNSET"), TENANT: at("UNSET"), BRAND: {}, LOCATION: {} },
  },
  {
    code: "delivery.out_of_zone_policy",
    label: "Address outside every zone",
    where: "Selling → Order policy", screen: "order-policy", card: "conditions",
    type: "enum", options: ["REJECT", "OFFER_PICKUP", "MANUAL_REVIEW"], built: false, adr: "ADR 0030 key · ADR 0037 consumer",
    note: "MANUAL_REVIEW holds the cart for an operator to approve a manual fee with a reason, audited — choosing it creates work for the operations queue.",
    settableAt: ["PLATFORM", "TENANT", "BRAND", "LOCATION"], explicitNullTerminates: false,
    levels: {
      PLATFORM: at("SET", "REJECT", "Qoida", "2025-11-04T09:00:00", null),
      TENANT: at("SET", "OFFER_PICKUP", "Alisher Karimov", "2026-04-02T13:00:00", "Zonadan tashqarida olib ketishni taklif qilamiz"),
      BRAND: { "br-osh": at("UNSET"), "br-rayhon": at("SET", "MANUAL_REVIEW", "Ilhom Toshmatov", "2026-02-14T10:00:00", "Rayhon uzoq buyurtmalarni qo'lda ko'rib chiqadi") },
      LOCATION: {},
    },
  },
  {
    code: "ordering.paid_orders_only_visible",
    label: "Show couriers and kitchen only paid orders",
    where: "Selling → Order policy", screen: "order-policy", card: "conditions",
    type: "boolean", built: false, adr: "ADR 0042",
    settableAt: ["PLATFORM", "TENANT", "BRAND", "LOCATION"], explicitNullTerminates: false,
    levels: {
      PLATFORM: at("SET", false, "Qoida", "2025-11-04T09:00:00", null),
      TENANT: at("UNSET"), BRAND: { "br-osh": at("UNSET"), "br-rayhon": at("UNSET") }, LOCATION: {},
    },
  },
  {
    code: "ordering.operator_may_enter_promocode",
    label: "Operator may enter a promo code",
    where: "Selling → Order policy", screen: "order-policy", card: "operator",
    type: "boolean", built: false, adr: "ADR 0030 key · ADR 0018 promotions",
    settableAt: ["PLATFORM", "TENANT", "BRAND"], explicitNullTerminates: false,
    levels: {
      PLATFORM: at("SET", false, "Qoida", "2025-11-04T09:00:00", null),
      TENANT: at("SET", true, "Alisher Karimov", "2026-06-18T17:22:00", "Call-markaz kompensatsiya bera oladi"),
      BRAND: { "br-osh": at("UNSET"), "br-rayhon": at("UNSET") }, LOCATION: {},
    },
  },
  {
    code: "ordering.show_change_due_field",
    label: "Show the «change from» field",
    where: "Selling → Order policy", screen: "order-policy", card: "operator",
    type: "boolean", built: false, adr: "ADR 0039 — ordering.orders.cash_tendered_expected_minor",
    settableAt: ["PLATFORM", "TENANT", "BRAND"], explicitNullTerminates: false,
    levels: {
      PLATFORM: at("SET", true, "Qoida", "2025-11-04T09:00:00", null),
      TENANT: at("UNSET"), BRAND: { "br-osh": at("UNSET"), "br-rayhon": at("UNSET") }, LOCATION: {},
    },
  },
  {
    code: "ordering.callback_requested_enabled",
    label: "Operator may flag «customer asks for a call back»",
    where: "Selling → Order policy", screen: "order-policy", card: "operator",
    type: "boolean", built: false, adr: "ADR 0039 — ordering.orders.callback_requested",
    settableAt: ["PLATFORM", "TENANT", "BRAND"], explicitNullTerminates: false,
    levels: { PLATFORM: at("SET", false, "Qoida", "2025-11-04T09:00:00", null), TENANT: at("UNSET"), BRAND: {}, LOCATION: {} },
  },
  {
    code: "notifications.quiet_hours_start_hour",
    label: "Quiet hours start",
    where: "Messages → Notifications", screen: "notifications", card: null,
    type: "hour", built: true, adr: "ADR 0030 — a real registered key today",
    note: "explicitNullTerminates. «Quiet hours cleared for this brand» and «never set» are different facts and the resolver treats them differently.",
    settableAt: ["PLATFORM", "TENANT", "BRAND"], explicitNullTerminates: true,
    levels: {
      PLATFORM: at("SET", 22, "Qoida", "2025-11-04T09:00:00", null),
      TENANT: at("SET", 23, "Alisher Karimov", "2026-01-08T08:30:00", null),
      BRAND: { "br-osh": at("NULLED", null, "Ilhom Toshmatov", "2026-02-27T21:44:00", "Tungi yetkazib berish — tinch soatlar kerak emas"), "br-rayhon": at("UNSET") },
      LOCATION: {},
    },
  },
  {
    code: "platform.default_locale",
    label: "Default language",
    where: "The business → Languages & formats", screen: "languages", card: null,
    type: "enum", options: ["uz", "uz-Cyrl", "ru", "en"], built: true, adr: "ADR 0030 — ConfigurationKeys.DEFAULT_LOCALE",
    note: "What a notification falls back to when the customer's preference is unknown (ADR 0020 resolution order).",
    settableAt: ["PLATFORM", "TENANT", "BRAND", "LOCATION"], explicitNullTerminates: false,
    levels: {
      PLATFORM: at("SET", "uz", "Qoida", "2025-11-04T09:00:00", null),
      TENANT: at("UNSET"),
      BRAND: { "br-osh": at("UNSET"), "br-rayhon": at("SET", "ru", "Ilhom Toshmatov", "2026-02-14T10:04:00", "Rayhon mijozlari ruscha yozadi") },
      LOCATION: {},
    },
  },
  {
    code: "fiscal.vat_rate",
    label: "VAT rate",
    where: "Money and tax → Fiscalization", screen: "fiscal", card: null,
    type: "readonly", built: false, adr: "ADR 0038 — tenant.legal_entities.tax_profile_id",
    readonlyValue: "12%",
    settableAt: ["PLATFORM", "TENANT"], explicitNullTerminates: false,
    levels: {
      PLATFORM: at("SET", "12%", "Qoida", "2025-11-04T09:00:00", null),
      TENANT: at("UNSET"), BRAND: {}, LOCATION: {},
    },
  },
];

/* ── the acceptance policy ─────────────────────────────────────────────────
 * A policy, not a setting: a versioned JSON document in tenant.policies with a
 * pointer row in tenant.policy_current. Never edited in place, never deleted —
 * orders pin acceptance_policy_id and _version and must still resolve years
 * later. Version 8 is above the ADR 0027 risk threshold, so it became an
 * approval request instead of an activation, and the requester may not approve
 * their own request.
 */

export const ACCEPTANCE_POLICY = {
  key: "ordering.acceptance",
  activeVersion: 7,
  activeSince: "2026-08-12T14:20:00",
  activeBy: "Alisher Karimov",
  activeReason: "Переход на подтверждение вручную в час пик",
  scopeLabel: "brand «Osh Markazi»",
  document: {
    mode: "RESTAURANT_APPROVAL",
    approvalChannel: "EITHER",
    approvalTimeoutSeconds: 300,
    timeoutAction: "AUTO_REJECT",
    rejectionReasonRequired: true,
    notifyCustomerWhilePending: true,
  },
  pending: {
    version: 8,
    requestedBy: "Alisher Karimov",
    requestedAt: "2026-08-21T18:41:00",
    reason: "Kechqurun oqim ko'paydi — avtomatik qabulni sinab ko'ramiz",
    approverHint: "Требует подтверждения второго администратора — запросивший не может подтвердить сам (ADR 0027)",
    document: {
      mode: "AUTO_CONFIRM",
      approvalChannel: "NONE",
      approvalTimeoutSeconds: 300,
      timeoutAction: "AUTO_REJECT",
      rejectionReasonRequired: true,
      notifyCustomerWhilePending: false,
    },
  },
  history: [
    { version: 7, at: "2026-08-12T14:20:00", by: "Alisher Karimov", reason: "Переход на подтверждение вручную в час пик" },
    { version: 6, at: "2026-05-30T11:02:00", by: "Ilhom Toshmatov", reason: "Тайм-аут увеличен до 5 минут" },
    { version: 5, at: "2026-02-14T09:50:00", by: "Nodira Ismoilova", reason: "Обязательная причина отказа" },
  ],
  /* Two fields Delever has, Qoida's document does not, and every tenant wants. */
  missing: [
    { field: "eligibleChannelIds", label: "Auto-accept only on these channels", adr: "ADR 0002 + ADR 0030", why: "Without it a tenant cannot say «auto-accept the bot, hand-check the aggregator»." },
    { field: "minimumPriorSuccessfulOrders", label: "Minimum prior successful orders", adr: "ADR 0002 + ADR 0030", why: "An anti-fraud gate, not a convenience. Cheap to add." },
  ],
};

/* ── readiness ─────────────────────────────────────────────────────────────
 * Blocking (0) → expiring (1) → advisory (2), then count descending. Every row
 * names its scope and links to the filtered view that produced the count, so a
 * number here and a number on the target screen come from the same query.
 */

export const READINESS = [
  { id: "r-fiscal", severity: 0, count: 12, title: "Locations with no active fiscal assignment", scope: "All 12 locations", to: "fiscal", detail: "tenant.location_fiscal_assignments", adr: "ADR 0038 — not built", why: "A location with no assignment cannot be activated for any channel that can produce a receipt obligation." },
  { id: "r-nochannel", severity: 0, count: 1, title: "Location active but bound to no sales channel", scope: "Sergeli", to: "locations", detail: "tenant.sales_channel_locations absent", adr: null, why: "The resolver returns CHANNEL_NOT_ENABLED — absence means refused." },
  { id: "r-nopay", severity: 0, count: 1, title: "Active channel with no enabled payment method", scope: "iOS ilova", to: "channels", detail: "tenant.channel_payment_methods — zero enabled rows", adr: null, why: "An empty matrix means «sells nothing», not «sells everything»." },
  { id: "r-nomode", severity: 0, count: 2, title: "Active channel with no enabled fulfilment mode", scope: "Kiosk — Chilonzor · Express24", to: "channels", detail: "tenant.channel_fulfillment_modes — zero enabled rows", adr: null, why: null },
  { id: "r-nosched", severity: 0, count: 1, title: "Location with no schedule bound for an enabled mode", scope: "Yunusobod — Yetkazib berish", to: "locations", detail: "tenant.location_service_bindings missing (location, mode)", adr: "ADR 0036", why: "The resolver refuses the mode outright." },
  { id: "r-click", severity: 0, count: 1, title: "Payment installation never verified", scope: "Click Merchant — prod", to: "integrations", detail: "integration.installations.last_connection_status = UNVERIFIED", adr: null, why: "Blocking for POS and payment, advisory otherwise." },
  { id: "r-rkeeper", severity: 0, count: 1, title: "POS installation failing", scope: "R-Keeper — prod", to: "integrations", detail: "HTTP 503 от api.rkeeper.uz — 14 попыток подряд", adr: null, why: null },
  { id: "r-fiscexp", severity: 1, count: 1, title: "Fiscal assignment expires this month", scope: "Yunusobod 19-kvartal — 31.08", to: "fiscal", detail: "tenant.location_fiscal_assignments.effective_until", adr: "ADR 0038 — not built", why: null },
  { id: "r-forced", severity: 2, count: 1, title: "Location manually forced closed with no expiry", scope: "Bunyodkor shoh ko'chasi 12 — closed since 18.08, «Fritür buzildi»", to: "locations", detail: "tenant.location_service_state.mode='FORCE_CLOSED' AND effective_until IS NULL", adr: "ADR 0036", why: "The fryer broke on Tuesday and the branch is still shut on Saturday. This row is the whole reason this panel exists." },
  { id: "r-mxik", severity: 2, count: 143, title: "Priceable nodes without an ИКПУ code", scope: "143 of 1 204 positions across both brands", to: "fiscal", detail: "ix_variants_unclassified + ix_modifier_options_unclassified", adr: "ADR 0038 — blocking once it lands", why: "Advisory today only because V0021 deliberately left the columns nullable while 0038 is Proposed." },
  { id: "r-secret", severity: 2, count: 1, title: "Secret past its rotation period", scope: "Payme — 412 days", to: "integrations", detail: "secret rotation age", adr: "ADR 0028 — not built", why: null },
];

export const SEVERITY_LABEL = ["Blocking", "Expiring", "Advisory"];

/* ── the left rail ─────────────────────────────────────────────────────────
 * A grouped rail, not an alphabetical list, and the group headings are nouns a
 * restaurant manager uses. `built` marks what this prototype actually renders;
 * the rest render their own scope note rather than pretending.
 */

export const SETTINGS_NAV = [
  {
    id: "g-business", label: "The business",
    screens: [
      { id: "brand", label: "Brand profile", purpose: "What the brand is called and what it looks like to a customer.", built: false, scope: "BRAND", adr: "10.1 · logo purpose ADR 0010, contacts ADR 0002" },
      { id: "locations", label: "Locations", purpose: "Where each point is, when it is open, how fast it cooks.", built: true, scope: "LOCATION" },
      { id: "languages", label: "Languages & formats", purpose: "Which languages this brand sells in.", built: false, scope: "BRAND", adr: "10.12 · per-brand language set not built, ADR 0030 key" },
    ],
  },
  {
    id: "g-selling", label: "Selling",
    screens: [
      { id: "channels", label: "Sales channels", purpose: "Which routes into the restaurant exist and what each may do.", built: true, scope: "TENANT" },
      { id: "channel-setup", label: "Channel setup", purpose: "The bot, the site, the kiosk, the QR menu, the apps.", built: false, scope: "TENANT", adr: "10.5 · type-dispatched forms, DNS TXT domain verification" },
      { id: "order-policy", label: "Order policy", purpose: "How an order is accepted, when it is late, what an operator may do.", built: true, scope: "BRAND / LOCATION" },
      { id: "delivery-policy", label: "Delivery policy", purpose: "Out-of-zone handling and what a courier is allowed to do.", built: false, scope: "BRAND / LOCATION", adr: "10.13 · all of it ADR 0042 + ADR 0037, not built" },
    ],
  },
  {
    id: "g-money", label: "Money and tax",
    screens: [
      { id: "payment-methods", label: "Payment methods", purpose: "Ways a customer can pay, and who is on the hook for the receipt.", built: false, scope: "TENANT", adr: "10.6 · payments.payment_methods does not exist, ADR 0038 — pilot blocker" },
      { id: "fiscal", label: "Fiscalization", purpose: "Whom this restaurant trades as, and whether every dish can legally appear on a receipt.", built: false, scope: "TENANT", adr: "10.7 · legal entities, terminals, ИКПУ coverage — all ADR 0038" },
      { id: "printing", label: "Printing & receipts", purpose: "What gets printed and what the customer ends up holding.", built: false, scope: "BRAND", adr: "10.14 · no ADR owns printing at all — ADR 0011 has no print capability port" },
    ],
  },
  {
    id: "g-messages", label: "Messages",
    screens: [
      { id: "notifications", label: "Notifications", purpose: "What the customer is told, and where staff alerts go.", built: false, scope: "BRAND / LOCATION", adr: "10.9 · the whole notifications schema is ADR 0020, accepted, not started" },
    ],
  },
  {
    id: "g-connections", label: "Connections",
    screens: [
      { id: "integrations", label: "Integrations", purpose: "Everything outside Qoida, and which connection is broken right now.", built: false, scope: "TENANT", adr: "10.8 · installations and bindings exist; the hub is not prototyped here" },
    ],
  },
  {
    id: "g-reference", label: "Reference",
    screens: [
      { id: "reference", label: "Reference data", purpose: "The short controlled lists operators pick from all day.", built: false, scope: "TENANT", adr: "10.10 · ordering.order_outcome_reasons is ADR 0039, not started" },
      { id: "privacy", label: "Data & privacy", purpose: "How long personal data is kept and how a customer request is answered.", built: false, scope: "TENANT", adr: "10.11 · retention and DSAR are ADR 0029, not built" },
    ],
  },
];
