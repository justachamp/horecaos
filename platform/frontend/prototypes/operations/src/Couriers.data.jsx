/* Fixtures for the Couriers section — the delivery fleet.
 *
 * Authored backwards from the states the screens must show, not forwards from a
 * happy path. Everything awkward is here on purpose:
 *
 *   - an order 41 minutes late, carried by Yandex, where we cannot see the courier
 *   - a Noor create that timed out, so the platform does not know whether a
 *     courier was booked at all — the row that outranks every late order
 *   - Shoxrux closed his shift at 18:40 while still holding order 4823
 *   - Dilshod's phone stopped reporting nine minutes ago, mid-delivery
 *   - Sanjar is carrying 4 200 000 so'm of the tenant's money, over the ceiling
 *   - Nigora's account is suspended and her licence expired on 14.08
 *   - Sebzor branch was force-closed at 19:05 with a delivery still in flight
 *   - Muhammadaziz Abdurahmonov sits beside Otabek Nazarov in the same rail
 *   - Lo'la kabob is sold out ("qiyma tugadi") and one order is stuck behind it
 *
 * Money is whole som as integers (ADR 0018 stores minor units as whole som).
 * Times are ISO strings. Nothing here is computed at render time that a backend
 * would compute once — the fixtures carry the derived values as the API would.
 *
 * Nothing in `fulfillment` exists yet (§19). Every table named in a comment
 * below is the table this fixture stands in for once ADR 0014 / 0042 / 0045 land.
 */

export const NOW = "2026-08-21T19:34:00";

/* tenant.configuration_values → courier.cash.ceiling_minor */
export const CASH_CEILING = 3_000_000;

/* Resolved through ADR 0030 for the selected branch. The board says which mode
 * is live, because "why can't Alisher take this order" is otherwise unanswerable. */
export const ENFORCEMENT = {
  mode: "ADVISORY",
  label: "Advisory — logged, not blocked",
  policyVersion: "courier.shift.enforcement v4",
  resolvedAt: "tenant",
};

/* ── branches (tenant.locations) ───────────────────────────────────────────*/

export const BRANCHES = [
  { id: "loc-chl", code: "CHL", name: "Chilonzor filiali", tz: "Asia/Tashkent", status: "OPEN" },
  { id: "loc-yun", code: "YUN", name: "Yunusobod filiali", tz: "Asia/Tashkent", status: "OPEN" },
  {
    id: "loc-seb", code: "SEB", name: "Sebzor filiali", tz: "Asia/Tashkent",
    status: "FORCE_CLOSED",
    closedReason: "Suvsiz qoldi — 19:05 da majburiy yopildi",
    closedBy: "Nodira Sattorova",
  },
];

export const branchName = (id) => BRANCHES.find((b) => b.id === id)?.name ?? "—";

/* ── zones (ADR 0037 fulfillment.service_zone_versions, zone_role = DELIVERY) ─*/

export const ZONES = [
  { id: "z-markaz", name: "Markaz" },
  { id: "z-chilonzor", name: "Chilonzor" },
  { id: "z-yunusobod", name: "Yunusobod" },
  { id: "z-sergeli", name: "Sergeli" },
];

export const zoneName = (id) => ZONES.find((z) => z.id === id)?.name ?? "Zonasiz";

/* ── courier types (ADR 0042 fulfillment.courier_types) ────────────────────*/

export const COURIER_TYPES = [
  { id: "ct-moto", name: "Mototsikl", vehicleClass: "motorcycle", fromM: 0, toM: 12_000, maxConcurrent: 3, offerTtlSec: 45 },
  { id: "ct-velo", name: "Velosiped", vehicleClass: "bicycle", fromM: 0, toM: 4_000, maxConcurrent: 2, offerTtlSec: 60 },
  { id: "ct-avto", name: "Avtomobil", vehicleClass: "car", fromM: 0, toM: 25_000, maxConcurrent: 4, offerTtlSec: 45 },
  { id: "ct-piyoda", name: "Piyoda", vehicleClass: "foot", fromM: 0, toM: 1_500, maxConcurrent: 1, offerTtlSec: 90 },
];

export const typeName = (id) => COURIER_TYPES.find((t) => t.id === id)?.name ?? "—";

/* ── the fleet ─────────────────────────────────────────────────────────────
 * Two independent status axes (§2), never conflated:
 *   account  fulfillment.courier_profiles.status — a manager changed it, on purpose
 *   work     derived from shifts, assignments, restrictions and the clock
 *
 * `live` is the free-text line under the work chip. The code is filterable, the
 * text is human, and the operator reads the text.
 */

export const COURIERS = [
  {
    id: "cr-1", name: "Alisher Karimov", typeId: "ct-moto",
    account: "ACTIVE", work: "CARRYING", live: "Late 29 min · order 4817",
    branchIds: ["loc-chl"], pools: ["Chilonzor kechki"],
    load: 1, shiftOpenedAt: "2026-08-21T16:00:00", deliveredToday: 11,
    batteryPercent: 38, charging: false, positionAgeMin: 1,
    phone: "+998919876543", phoneRevealed: true,
    cashOnHand: 640_000, balance: 412_000, onTime30: 94, onTimeSample: "47 of 50 · 6 late kitchen excluded",
    plate: "01 A 234 BC", licenceExpiry: "2027-03-11", documents: null,
    actions: ["message", "restrict", "endShift"],
  },
  {
    id: "cr-2", name: "Ravshan Umarov", typeId: "ct-moto",
    account: "ACTIVE", work: "AT_BRANCH", live: "At Chilonzor · 3 of 3 on board",
    branchIds: ["loc-chl", "loc-seb"], pools: ["Chilonzor kechki"],
    load: 3, shiftOpenedAt: "2026-08-21T15:00:00", deliveredToday: 14,
    batteryPercent: 61, charging: false, positionAgeMin: 0,
    phone: "+998903341276", phoneRevealed: true,
    cashOnHand: 1_180_000, balance: 690_000, onTime30: 88, onTimeSample: "44 of 50 · 3 late kitchen excluded",
    plate: "01 B 771 AA", licenceExpiry: "2026-11-02", documents: null,
    actions: ["message", "restrict", "endShift"],
  },
  {
    id: "cr-3", name: "Otabek Nazarov", typeId: "ct-velo",
    account: "ACTIVE", work: "IDLE", live: "On shift 1h 34m",
    branchIds: ["loc-chl"], pools: ["Chilonzor kechki", "Markaz velosiped"],
    load: 0, shiftOpenedAt: "2026-08-21T18:00:00", deliveredToday: 4,
    batteryPercent: 84, charging: true, positionAgeMin: 0,
    phone: "+998977712095", phoneRevealed: true,
    cashOnHand: 0, balance: 168_000, onTime30: 97, onTimeSample: "33 of 34",
    plate: null, licenceExpiry: null, documents: null,
    actions: ["message", "restrict", "endShift"],
  },
  {
    id: "cr-4", name: "Shoxrux Qodirov", typeId: "ct-avto",
    account: "ACTIVE", work: "OFF_SHIFT", live: "Shift closed 18:40 — still carrying order 4823",
    branchIds: ["loc-yun"], pools: ["Yunusobod avto"],
    load: 1, shiftOpenedAt: null, shiftClosedAt: "2026-08-21T18:40:00", deliveredToday: 6,
    batteryPercent: 22, charging: false, positionAgeMin: 27,
    phone: "+998946620183", phoneRevealed: true,
    cashOnHand: 310_000, balance: -310_000, onTime30: 91, onTimeSample: "29 of 32 · 1 late kitchen excluded",
    plate: "10 C 040 EA", licenceExpiry: "2028-06-30", documents: null,
    blockReason: "No open shift. Enforcement is Advisory, so this is logged, not blocked.",
    actions: ["message", "restrict"],
  },
  {
    id: "cr-5", name: "Dilshod Toshmatov", typeId: "ct-moto",
    account: "ACTIVE", work: "STALE", live: "No signal 9 min · carrying order 4825",
    branchIds: ["loc-chl", "loc-yun"], pools: ["Chilonzor kechki"],
    load: 1, shiftOpenedAt: "2026-08-21T17:15:00", deliveredToday: 9,
    batteryPercent: 12, charging: false, positionAgeMin: 9,
    phone: "+998935518840", phoneRevealed: true,
    cashOnHand: 0, balance: 355_000, onTime30: 85, onTimeSample: "40 of 47 · 2 late kitchen excluded",
    plate: "01 D 508 BB", licenceExpiry: "2026-09-10", documents: "Licence expires 10.09",
    actions: ["message", "restrict", "endShift"],
  },
  {
    id: "cr-6", name: "Muhammadaziz Abdurahmonov", typeId: "ct-velo",
    account: "ACTIVE", work: "IDLE", live: "On shift 42m · first evening",
    branchIds: ["loc-chl"], pools: ["Markaz velosiped"],
    load: 0, shiftOpenedAt: "2026-08-21T18:52:00", deliveredToday: 2,
    batteryPercent: 96, charging: true, positionAgeMin: 0,
    phone: "+998900461123", phoneRevealed: false,
    cashOnHand: 0, balance: 96_000, onTime30: 100, onTimeSample: "6 of 6 — too few to rank on",
    plate: null, licenceExpiry: null, documents: null,
    actions: ["message", "restrict", "endShift"],
  },
  {
    id: "cr-7", name: "Sanjar Xolmatov", typeId: "ct-avto",
    account: "ACTIVE", work: "CARRYING", live: "On shift 6h 04m · holding 4 200 000 so'm",
    branchIds: ["loc-yun"], pools: ["Yunusobod avto"],
    load: 2, shiftOpenedAt: "2026-08-21T13:30:00", deliveredToday: 19,
    batteryPercent: 44, charging: true, positionAgeMin: 2,
    phone: "+998909947316", phoneRevealed: true,
    cashOnHand: 4_200_000, balance: -3_420_000, onTime30: 92, onTimeSample: "58 of 63 · 4 late kitchen excluded",
    plate: "10 E 912 CD", licenceExpiry: "2029-01-20", documents: null,
    actions: ["message", "restrict", "endShift", "handover"],
  },
  {
    id: "cr-8", name: "Nigora Ismoilova", typeId: "ct-moto",
    account: "SUSPENDED", work: "RESTRICTED", live: "Suspended — haydovchilik guvohnomasi 14.08 da tugadi",
    branchIds: ["loc-chl"], pools: [],
    load: 0, shiftOpenedAt: null, deliveredToday: 0,
    batteryPercent: null, charging: false, positionAgeMin: null,
    phone: "+998974420561", phoneRevealed: false,
    cashOnHand: 0, balance: 0, onTime30: 89, onTimeSample: "31 of 35",
    plate: "01 F 116 AB", licenceExpiry: "2026-08-14", documents: "Licence expired 14.08",
    blockReason: "Account suspended on 15.08 by Nodira Sattorova — expired licence.",
    actions: ["reinstate"],
  },
  {
    id: "cr-9", name: "Bekzod Yusupov", typeId: "ct-velo",
    account: "ACTIVE", work: "OFF_SHIFT", live: "Rostered 18:00 — not opened",
    branchIds: ["loc-chl"], pools: ["Chilonzor kechki"],
    load: 0, shiftOpenedAt: null, deliveredToday: 0,
    batteryPercent: null, charging: false, positionAgeMin: null,
    phone: "+998912238004", phoneRevealed: true,
    cashOnHand: 0, balance: 24_000, onTime30: 78, onTimeSample: "18 of 23 · 2 late kitchen excluded",
    plate: null, licenceExpiry: null, documents: null,
    blockReason: "Rostered 18:00–23:00 and has not opened a shift. Phone him.",
    actions: ["message", "openShift"],
  },
  {
    id: "cr-10", name: "Jasur Rasulov", typeId: "ct-moto",
    account: "ACTIVE", work: "OFFERED", live: "Offer expires 00:41 · order 4833",
    branchIds: ["loc-yun"], pools: ["Yunusobod avto"],
    load: 0, shiftOpenedAt: "2026-08-21T17:45:00", deliveredToday: 7,
    batteryPercent: 55, charging: false, positionAgeMin: 1,
    phone: "+998907730192", phoneRevealed: true,
    cashOnHand: 220_000, balance: 245_000, onTime30: 90, onTimeSample: "36 of 40 · 1 late kitchen excluded",
    plate: "01 G 655 DA", licenceExpiry: "2027-08-08", documents: null,
    actions: ["message", "restrict", "endShift"],
  },
];

export const courierById = (id) => COURIERS.find((c) => c.id === id);

/* ── partners (integration.installations + binding_capabilities) ───────────
 * Same rail as the couriers above, because an operator does not want to choose a
 * tab before they can see who can take an order. Only the fields differ.
 */

export const PARTNERS = [
  {
    id: "pt-yandex", name: "Yandex Delivery", providerType: "yandex-delivery",
    health: "degraded", healthNote: "3 of last 20 calls timed out",
    inFlight: 3, lastQuoteMinor: 28_000, lastQuoteAt: "2026-08-21T19:29:00",
    acceptanceToday: 82, requestedToday: 34,
    branchIds: ["loc-chl", "loc-yun", "loc-seb"],
    zonesCovered: ["z-markaz", "z-chilonzor", "z-yunusobod"],
    capabilities: [
      { key: "Quote", ok: true }, { key: "Reserve", ok: true }, { key: "Confirm", ok: true },
      { key: "Cancellation cost", ok: true }, { key: "Track", ok: true }, { key: "Reschedule", ok: false },
    ],
  },
  {
    id: "pt-noor", name: "Noor Delivery", providerType: "noor-delivery",
    health: "open", healthNote: "Circuit open — next probe 19:38",
    inFlight: 1, lastQuoteMinor: 24_000, lastQuoteAt: "2026-08-21T19:12:00",
    acceptanceToday: 61, requestedToday: 18,
    branchIds: ["loc-chl", "loc-yun"],
    zonesCovered: ["z-markaz", "z-chilonzor"],
    capabilities: [
      { key: "Quote", ok: true }, { key: "Create", ok: true }, { key: "Cancel", ok: true },
      { key: "Track", ok: true }, { key: "Cancellation cost", ok: false }, { key: "Reschedule", ok: false },
    ],
  },
];

export const partnerById = (id) => PARTNERS.find((p) => p.id === id);

/* ── the dispatch queue ────────────────────────────────────────────────────
 * `rank` is the spec's stage rank (0..7). `severity` is the independent signal
 * channel. The screen sorts on severity first and rank second — see the note in
 * Couriers.jsx. `reason` is real text, never a badge.
 *
 * kitchen[] and logistics[] are the two clocks that can disagree. That
 * disagreement is the whole difference between "the kitchen is late" and "the
 * courier is late", which decides LATE vs LATE_EXCUSED on the courier's pay.
 */

export const QUEUE = [
  {
    id: "QO-4834", shortId: "4834", channel: "Telegram", branchId: "loc-chl",
    customer: "Kamola Tursunova", phone: "+998909912437",
    address: "Mustaqillik shoh ko'chasi 24, 11-xonadon", zoneId: "z-markaz", distanceKm: 3.4,
    kitchen: [{ s: "Accepted", t: "19:06" }, { s: "Cooking", t: "19:11" }, { s: "Ready", t: "19:28" }, { s: "Handed over", t: null }],
    logistics: [{ s: "Planned", t: "19:06" }, { s: "Sourcing", t: "19:26" }, { s: "Offered", t: null }, { s: "At branch", t: null }, { s: "Picked up", t: null }, { s: "Delivered", t: null }],
    promisedEnd: "2026-08-21T20:06:00", latestAssignmentAt: "2026-08-21T19:36:00",
    carriedBy: { kind: "unknown", partnerId: "pt-noor", ref: "NR-88213" },
    payment: { method: "Naqd", collectMinor: 186_000 }, totalMinor: 186_000,
    severity: "problem", rank: 0,
    reason: "Noor create timed out — state unknown, query before retrying. A blind retry books a second courier and bills twice.",
    actions: ["open", "queryCost", "cancelShipment", "callCustomer"],
  },
  {
    id: "QO-4829", shortId: "4829", channel: "Website", branchId: "loc-chl",
    customer: "Sardor Yo'ldoshev", phone: "+998934421890",
    address: "Chilonzor 9-kvartal, 42-uy", zoneId: "z-chilonzor", distanceKm: 2.1,
    kitchen: [{ s: "Accepted", t: "18:58" }, { s: "Cooking", t: null }, { s: "Ready", t: null }, { s: "Handed over", t: null }],
    logistics: [{ s: "Planned", t: "18:58" }, { s: "Sourcing", t: "19:14" }, { s: "Offered", t: null }, { s: "At branch", t: null }, { s: "Picked up", t: null }, { s: "Delivered", t: null }],
    promisedEnd: "2026-08-21T19:52:00", latestAssignmentAt: "2026-08-21T19:22:00",
    carriedBy: { kind: "none" },
    payment: { method: "Click", collectMinor: 0 }, totalMinor: 118_000,
    severity: "problem", rank: 0,
    reason: "Sourcing exhausted — no in-house candidate and every partner declined. Lo'la kabob tugadi, mijoz almashtirishni kutmoqda.",
    actions: ["open", "assign", "callExternal", "callCustomer"],
  },
  {
    id: "QO-4812", shortId: "4812", channel: "Telegram", branchId: "loc-yun",
    customer: "Jamshid Ergashev", phone: "+998908846205",
    address: "Yunusobod 19-kvartal, 7-uy", zoneId: "z-yunusobod", distanceKm: 6.8,
    kitchen: [{ s: "Accepted", t: "18:12" }, { s: "Cooking", t: "18:16" }, { s: "Ready", t: "18:33" }, { s: "Handed over", t: "18:47" }],
    logistics: [{ s: "Planned", t: "18:12" }, { s: "Sourcing", t: "18:20" }, { s: "Offered", t: "18:31" }, { s: "At branch", t: "18:44" }, { s: "Picked up", t: "18:47" }, { s: "Delivered", t: null }],
    promisedEnd: "2026-08-21T18:53:00", latestAssignmentAt: "2026-08-21T18:35:00",
    carriedBy: { kind: "partner", partnerId: "pt-yandex", ref: "YD-4471902" },
    payment: { method: "Payme", collectMinor: 0 }, totalMinor: 264_000,
    severity: "late", rank: 1, lateByMin: 41,
    reason: "Late 41 min. Yandex does not expose a position; the tracking link is all we have.",
    actions: ["open", "track", "cancelShipment", "callCustomer"],
  },
  {
    id: "QO-4817", shortId: "4817", channel: "Website", branchId: "loc-chl",
    customer: "Dilnoza Rahimova", phone: "+998901234567",
    address: "Navoiy ko'chasi 12, ofis 305", zoneId: "z-markaz", distanceKm: 4.2,
    kitchen: [{ s: "Accepted", t: "18:23" }, { s: "Cooking", t: "18:27" }, { s: "Ready", t: "18:41" }, { s: "Handed over", t: "18:49" }],
    logistics: [{ s: "Planned", t: "18:23" }, { s: "Sourcing", t: "18:28" }, { s: "Offered", t: "18:33" }, { s: "At branch", t: "18:46" }, { s: "Picked up", t: "18:49" }, { s: "Delivered", t: null }],
    promisedEnd: "2026-08-21T19:05:00", latestAssignmentAt: "2026-08-21T18:45:00",
    carriedBy: { kind: "courier", courierId: "cr-1" },
    payment: { method: "Click", collectMinor: 0 }, totalMinor: 91_000,
    severity: "late", rank: 1, lateByMin: 29,
    reason: "Late 29 min. Kitchen handed over on time at 18:49 — this is transit, so it counts as LATE, not LATE_EXCUSED.",
    actions: ["open", "reassign", "callCourier", "callCustomer", "overrideDistance"],
  },
  {
    id: "QO-4825", shortId: "4825", channel: "Phone", branchId: "loc-chl",
    customer: "Malika Yusupova", phone: "+998912057714",
    address: "Katta Darxon 14, 3-uy", zoneId: "z-markaz", distanceKm: 2.9,
    kitchen: [{ s: "Accepted", t: "18:44" }, { s: "Cooking", t: "18:48" }, { s: "Ready", t: "19:04" }, { s: "Handed over", t: "19:09" }],
    logistics: [{ s: "Planned", t: "18:44" }, { s: "Sourcing", t: "18:50" }, { s: "Offered", t: "18:55" }, { s: "At branch", t: "19:06" }, { s: "Picked up", t: "19:09" }, { s: "Delivered", t: null }],
    promisedEnd: "2026-08-21T19:44:00", latestAssignmentAt: "2026-08-21T19:08:00",
    carriedBy: { kind: "courier", courierId: "cr-5" },
    payment: { method: "Naqd", collectMinor: 143_000 }, totalMinor: 143_000,
    severity: "risk", rank: 6,
    reason: "Dilshod's phone has not reported for 9 min while carrying this. Battery was 12% at the last observation.",
    actions: ["open", "reassign", "callCourier", "callCustomer"],
  },
  {
    id: "QO-4826", shortId: "4826", channel: "Telegram", branchId: "loc-yun",
    customer: "Ozoda Karimova", phone: "+998903318827",
    address: "Buyuk Ipak Yo'li 118, 2-podyezd", zoneId: "z-yunusobod", distanceKm: 5.1,
    kitchen: [{ s: "Accepted", t: "18:51" }, { s: "Cooking", t: "18:56" }, { s: "Ready", t: null }, { s: "Handed over", t: null }],
    logistics: [{ s: "Planned", t: "18:51" }, { s: "Sourcing", t: "19:10" }, { s: "Offered", t: null }, { s: "At branch", t: null }, { s: "Picked up", t: null }, { s: "Delivered", t: null }],
    promisedEnd: "2026-08-21T19:51:00", latestAssignmentAt: "2026-08-21T19:21:00",
    carriedBy: { kind: "none" },
    payment: { method: "Naqd", collectMinor: 209_000 }, totalMinor: 209_000,
    severity: "risk", rank: 2,
    reason: "Past the latest assignment time by 13 min with nobody assigned.",
    actions: ["open", "assign", "callExternal", "callCustomer"],
  },
  {
    id: "QO-4819", shortId: "4819", channel: "Phone", branchId: "loc-chl",
    customer: "Nargiza Sobirova", phone: "+998971140273",
    address: "Bobur ko'chasi 61, 8-xonadon", zoneId: "z-chilonzor", distanceKm: 1.8,
    kitchen: [{ s: "Accepted", t: "18:53" }, { s: "Cooking", t: "18:57" }, { s: "Ready", t: "19:26" }, { s: "Handed over", t: null }],
    logistics: [{ s: "Planned", t: "18:53" }, { s: "Sourcing", t: "19:26" }, { s: "Offered", t: null }, { s: "At branch", t: null }, { s: "Picked up", t: null }, { s: "Delivered", t: null }],
    promisedEnd: "2026-08-21T19:56:00", latestAssignmentAt: "2026-08-21T19:38:00",
    carriedBy: { kind: "none" },
    payment: { method: "Naqd", collectMinor: 252_000 }, totalMinor: 252_000,
    severity: "risk", rank: 3,
    reason: "Ready 8 min, no courier at the branch. The food is cooling on the pass.",
    actions: ["open", "assign", "callExternal", "print", "callCustomer"],
  },
  {
    id: "QO-4833", shortId: "4833", channel: "Website", branchId: "loc-yun",
    customer: "Aziza Rahmonova", phone: "+998946612204",
    address: "Mirzo Ulug'bek ko'chasi 45, 12-xonadon", zoneId: "z-yunusobod", distanceKm: 4.6,
    kitchen: [{ s: "Accepted", t: "19:19" }, { s: "Cooking", t: "19:24" }, { s: "Ready", t: null }, { s: "Handed over", t: null }],
    logistics: [{ s: "Planned", t: "19:19" }, { s: "Sourcing", t: "19:31" }, { s: "Offered", t: "19:33" }, { s: "At branch", t: null }, { s: "Picked up", t: null }, { s: "Delivered", t: null }],
    promisedEnd: "2026-08-21T20:19:00", latestAssignmentAt: "2026-08-21T19:49:00",
    carriedBy: { kind: "offered", courierId: "cr-10", expiresSec: 41 },
    payment: { method: "Click", collectMinor: 0 }, totalMinor: 97_000,
    severity: null, rank: 4,
    reason: null,
    actions: ["open", "unassign", "callCustomer"],
  },
  {
    id: "QO-4831", shortId: "4831", channel: "Telegram", branchId: "loc-chl",
    customer: "Farrux Umarov", phone: "+998915526630",
    address: "Beruniy ko'chasi 7, 1-uy", zoneId: "z-chilonzor", distanceKm: 2.4,
    kitchen: [{ s: "Accepted", t: "19:14" }, { s: "Cooking", t: "19:18" }, { s: "Ready", t: null }, { s: "Handed over", t: null }],
    logistics: [{ s: "Planned", t: "19:14" }, { s: "Sourcing", t: "19:29" }, { s: "Offered", t: "19:33" }, { s: "At branch", t: null }, { s: "Picked up", t: null }, { s: "Delivered", t: null }],
    promisedEnd: "2026-08-21T20:14:00", latestAssignmentAt: "2026-08-21T19:44:00",
    carriedBy: { kind: "offered", courierId: "cr-3", expiresSec: 22 },
    payment: { method: "Naqd", collectMinor: 74_000 }, totalMinor: 74_000,
    severity: "risk", rank: 4,
    reason: "Offer expires in 22 s. If Otabek does not take it, this returns to sourcing.",
    actions: ["open", "unassign", "callCustomer"],
  },
  {
    id: "QO-4822", shortId: "4822", channel: "Website", branchId: "loc-seb",
    customer: "Ulug'bek Islomov", phone: "+998900276418",
    address: "Sebzor 4-tor ko'chasi 19", zoneId: "z-markaz", distanceKm: 1.2,
    kitchen: [{ s: "Accepted", t: "18:47" }, { s: "Cooking", t: "18:52" }, { s: "Ready", t: "19:03" }, { s: "Handed over", t: null }],
    logistics: [{ s: "Planned", t: "18:47" }, { s: "Sourcing", t: "19:03" }, { s: "Offered", t: null }, { s: "At branch", t: null }, { s: "Picked up", t: null }, { s: "Delivered", t: null }],
    promisedEnd: "2026-08-21T19:47:00", latestAssignmentAt: "2026-08-21T19:33:00",
    carriedBy: { kind: "none" },
    payment: { method: "Naqd", collectMinor: 88_000 }, totalMinor: 88_000,
    severity: "risk", rank: 2,
    reason: "Sebzor was force-closed at 19:05 with this order already cooked. Nobody is on shift there.",
    actions: ["open", "assign", "callExternal", "callCustomer"],
  },
  {
    id: "QO-4820", shortId: "4820", channel: "Website", branchId: "loc-chl",
    customer: "Shahnoza Qurbonova", phone: "+998939904471",
    address: "Amir Temur ko'chasi 108, 24-xonadon", zoneId: "z-markaz", distanceKm: 3.1,
    kitchen: [{ s: "Accepted", t: "19:19" }, { s: "Cooking", t: "19:23" }, { s: "Ready", t: null }, { s: "Handed over", t: null }],
    logistics: [{ s: "Planned", t: "19:19" }, { s: "Sourcing", t: "19:32" }, { s: "Offered", t: null }, { s: "At branch", t: null }, { s: "Picked up", t: null }, { s: "Delivered", t: null }],
    promisedEnd: "2026-08-21T19:58:00", latestAssignmentAt: "2026-08-21T19:41:00",
    carriedBy: { kind: "none" },
    payment: { method: "Naqd", collectMinor: 207_000 }, totalMinor: 207_000,
    severity: null, rank: 5, reason: null,
    actions: ["open", "assign", "callExternal", "print", "callCustomer"],
  },
  {
    id: "QO-4823", shortId: "4823", channel: "Phone", branchId: "loc-yun",
    customer: "Rustam G'aniyev", phone: "+998977034812",
    address: "Farg'ona yo'li 210, 5-uy", zoneId: "z-yunusobod", distanceKm: 7.9,
    kitchen: [{ s: "Accepted", t: "18:29" }, { s: "Cooking", t: "18:33" }, { s: "Ready", t: "18:52" }, { s: "Handed over", t: "18:58" }],
    logistics: [{ s: "Planned", t: "18:29" }, { s: "Sourcing", t: "18:36" }, { s: "Offered", t: "18:39" }, { s: "At branch", t: "18:55" }, { s: "Picked up", t: "18:58" }, { s: "Delivered", t: null }],
    promisedEnd: "2026-08-21T19:39:00", latestAssignmentAt: "2026-08-21T18:52:00",
    carriedBy: { kind: "courier", courierId: "cr-4" },
    payment: { method: "Naqd", collectMinor: 310_000 }, totalMinor: 310_000,
    severity: "risk", rank: 6,
    reason: "Shoxrux closed his shift at 18:40 and is still carrying this. He is holding 310 000 so'm against no open shift.",
    actions: ["open", "reassign", "callCourier", "callCustomer"],
  },
  {
    id: "QO-4830", shortId: "4830", channel: "Telegram", branchId: "loc-yun",
    customer: "Zilola Tojiboyeva", phone: "+998907715539",
    address: "Qo'yliq bozori yonida, Nukus ko'chasi 3", zoneId: "z-yunusobod", distanceKm: 9.3,
    kitchen: [{ s: "Accepted", t: "19:02" }, { s: "Cooking", t: "19:06" }, { s: "Ready", t: "19:21" }, { s: "Handed over", t: "19:26" }],
    logistics: [{ s: "Planned", t: "19:02" }, { s: "Sourcing", t: "19:08" }, { s: "Offered", t: "19:11" }, { s: "At branch", t: "19:23" }, { s: "Picked up", t: "19:26" }, { s: "Delivered", t: null }],
    promisedEnd: "2026-08-21T20:02:00", latestAssignmentAt: "2026-08-21T19:24:00",
    carriedBy: { kind: "courier", courierId: "cr-7" },
    payment: { method: "Naqd", collectMinor: 412_000 }, totalMinor: 412_000,
    severity: "risk", rank: 6,
    reason: "Courier is holding 4 200 000 so'm, over the 3 000 000 ceiling — consider a handover before this drop.",
    actions: ["open", "reassign", "callCourier", "callCustomer"],
  },
  {
    id: "QO-4828", shortId: "4828", channel: "Website", branchId: "loc-chl",
    customer: "Aziz Nurmatov", phone: "+998935560091",
    address: "Shota Rustaveli ko'chasi 88, 6-xonadon", zoneId: "z-chilonzor", distanceKm: 3.7,
    kitchen: [{ s: "Accepted", t: "19:00" }, { s: "Cooking", t: "19:04" }, { s: "Ready", t: "19:18" }, { s: "Handed over", t: "19:22" }],
    logistics: [{ s: "Planned", t: "19:00" }, { s: "Sourcing", t: "19:05" }, { s: "Offered", t: "19:07" }, { s: "At branch", t: "19:20" }, { s: "Picked up", t: "19:22" }, { s: "Delivered", t: null }],
    promisedEnd: "2026-08-21T20:00:00", latestAssignmentAt: "2026-08-21T19:20:00",
    carriedBy: { kind: "partner", partnerId: "pt-noor", ref: "NR-88190" },
    payment: { method: "Click", collectMinor: 0 }, totalMinor: 133_000,
    severity: null, rank: 6, reason: null,
    actions: ["open", "track", "cancelShipment", "callCustomer"],
  },
  {
    id: "QO-4835", shortId: "4835", channel: "Telegram", branchId: "loc-chl",
    customer: "Doniyor Ochilov", phone: "+998908812740",
    address: "Amir Temur ko'chasi 4, Poytaxt biznes markazi", zoneId: "z-markaz", distanceKm: 5.5,
    kitchen: [{ s: "Accepted", t: "19:30" }, { s: "Cooking", t: null }, { s: "Ready", t: null }, { s: "Handed over", t: null }],
    logistics: [{ s: "Planned", t: "19:30" }, { s: "Sourcing", t: null }, { s: "Offered", t: null }, { s: "At branch", t: null }, { s: "Picked up", t: null }, { s: "Delivered", t: null }],
    promisedEnd: "2026-08-21T21:30:00", latestAssignmentAt: "2026-08-21T21:00:00",
    carriedBy: { kind: "none" },
    payment: { method: "Payme", collectMinor: 0 }, totalMinor: 486_000,
    severity: null, rank: 7, reason: null, scheduled: "2026-08-21T21:30:00",
    actions: ["open", "assign", "callExternal", "callCustomer"],
  },
];

/* Status tabs. Counts are computed on the unfiltered set (§20) so a count never
 * collapses as the selection narrows — the screen recomputes these from QUEUE. */
export const QUEUE_TABS = [
  { id: "needs", label: "Needs a courier" },
  { id: "offered", label: "Offered" },
  { id: "toBranch", label: "On the way to branch" },
  { id: "road", label: "On the road" },
  { id: "problem", label: "Problem" },
  { id: "all", label: "All" },
];

/* ── shifts (ADR 0042 fulfillment.courier_shifts + courier_roster_entries) ──
 * Roster and shift are different objects and must stay in different columns. The
 * roster is what a manager planned; the shift is what happened; only the shift
 * produces paid hours.
 */

export const SHIFTS = [
  {
    id: "sh-1", courierId: "cr-7", branchId: "loc-yun",
    rostered: "13:30 – 22:00", openedAt: "2026-08-21T13:30:00", openSource: "SELF",
    closedAt: null, closeSource: null, paidSeconds: 21_840, varianceSeconds: 0,
    cash: "VARIANCE", approval: "—", state: "OPEN", rank: 0,
    note: "Holding 4 200 000 so'm — 1 200 000 over the ceiling, no handover recorded today.",
  },
  {
    id: "sh-2", courierId: "cr-4", branchId: "loc-yun",
    rostered: "12:00 – 20:00", openedAt: "2026-08-21T12:04:00", openSource: "SELF",
    closedAt: "2026-08-21T18:40:00", closeSource: "SELF", paidSeconds: 23_760, varianceSeconds: -4_800,
    cash: "NOT_DECLARED", approval: "Awaiting", state: "AWAITING_APPROVAL", rank: 1,
    note: "Closed 1h 20m early while still carrying order 4823. Cash of 310 000 so'm was never declared.",
  },
  {
    id: "sh-3", courierId: "cr-2", branchId: "loc-chl",
    rostered: "15:00 – 23:00", openedAt: "2026-08-21T15:00:00", openSource: "SELF",
    closedAt: null, closeSource: null, paidSeconds: 16_440, varianceSeconds: 0,
    cash: "DECLARED", approval: "—", state: "OPEN", rank: 5,
  },
  {
    id: "sh-4", courierId: "cr-8", branchId: "loc-chl",
    rostered: "16:00 – 22:00", openedAt: "2026-08-20T16:02:00", openSource: "SUPERVISOR",
    openedBy: "Nodira Sattorova (account disabled 18.08)",
    closedAt: "2026-08-21T02:00:00", closeSource: "AUTO_CLOSED", paidSeconds: 35_880, varianceSeconds: 14_280,
    cash: "CONFIRMED", approval: "Awaiting", state: "AWAITING_APPROVAL", rank: 2,
    note: "Auto-closed at 02:00 — needs approval before it is paid. Opened on her behalf by an account that is now disabled.",
  },
  {
    id: "sh-5", courierId: "cr-9", branchId: "loc-chl",
    rostered: "18:00 – 23:00", openedAt: null, openSource: null,
    closedAt: null, closeSource: null, paidSeconds: 0, varianceSeconds: -5_640,
    cash: "—", approval: "—", state: "MISSED", rank: 3,
    note: "Rostered at 18:00, never opened. 1h 34m of planned coverage missing at Chilonzor.",
  },
  {
    id: "sh-6", courierId: "cr-1", branchId: "loc-chl",
    rostered: "16:00 – 23:00", openedAt: "2026-08-21T16:00:00", openSource: "SELF",
    closedAt: null, closeSource: null, paidSeconds: 12_840, varianceSeconds: 0,
    cash: "DECLARED", approval: "—", state: "OPEN", rank: 5,
  },
  {
    id: "sh-7", courierId: "cr-5", branchId: "loc-chl",
    rostered: "17:00 – 22:00", openedAt: "2026-08-21T17:15:00", openSource: "SELF",
    closedAt: null, closeSource: null, paidSeconds: 8_340, varianceSeconds: 900,
    cash: "NOT_DECLARED", approval: "—", state: "OPEN", rank: 5,
  },
  {
    id: "sh-8", courierId: "cr-10", branchId: "loc-yun",
    rostered: "17:00 – 23:00", openedAt: "2026-08-21T17:45:00", openSource: "SELF",
    closedAt: null, closeSource: null, paidSeconds: 6_540, varianceSeconds: 2_700,
    cash: "DECLARED", approval: "—", state: "OPEN", rank: 5,
  },
  {
    id: "sh-9", courierId: "cr-3", branchId: "loc-chl",
    rostered: "18:00 – 23:00", openedAt: "2026-08-21T18:00:00", openSource: "SELF",
    closedAt: null, closeSource: null, paidSeconds: 5_640, varianceSeconds: 0,
    cash: "—", approval: "—", state: "OPEN", rank: 5,
  },
  {
    id: "sh-10", courierId: "cr-6", branchId: "loc-chl",
    rostered: "—", openedAt: "2026-08-21T18:52:00", openSource: "SUPERVISOR",
    openedBy: "Zafar Alimov", closedAt: null, closeSource: null,
    paidSeconds: 2_520, varianceSeconds: 0,
    cash: "—", approval: "Approved", state: "OPEN", rank: 5,
    note: "Opened on his behalf — first evening, app invite not yet accepted.",
  },
  {
    id: "sh-11", courierId: "cr-2", branchId: "loc-chl",
    rostered: "10:00 – 15:00", openedAt: "2026-08-21T09:58:00", openSource: "SELF",
    closedAt: "2026-08-21T14:56:00", closeSource: "SELF", paidSeconds: 17_880, varianceSeconds: -240,
    cash: "CONFIRMED", approval: "Approved", state: "CLOSED", rank: 6,
  },
];

/* Coverage strip, 15-minute cells 11:00 → 23:00 (48 cells per branch).
 * Run-length encoded as [cells, open, rostered] to keep the fixture readable.
 * Coverage is ordinal, so the screen ramps a single hue, never four. */
export const COVERAGE = [
  { branchId: "loc-chl", runs: [[8, 2, 2], [8, 3, 3], [6, 2, 3], [6, 3, 3], [4, 4, 4], [8, 5, 6], [8, 5, 6]] },
  { branchId: "loc-yun", runs: [[8, 1, 2], [8, 2, 2], [8, 2, 3], [8, 3, 3], [8, 3, 4], [8, 2, 4]] },
  { branchId: "loc-seb", runs: [[8, 1, 1], [8, 1, 1], [8, 1, 2], [8, 1, 2], [4, 1, 2], [12, 0, 2]] },
];

export const COVERAGE_START_HOUR = 11;

export const expandCoverage = (runs) =>
  runs.flatMap(([n, open, rostered]) => Array.from({ length: n }, () => ({ open, rostered })));

/* ── cash handovers (ADR 0042 fulfillment.courier_cash_handovers) ──────────
 * Three figures, always separate, never absorbed into one another:
 *   expected  the platform computed it
 *   declared  the courier typed it into the app
 *   confirmed the branch cashier counted it
 */

export const HANDOVERS = [
  {
    id: "hv-1", courierId: "cr-4", branchId: "loc-yun", closedAt: "2026-08-21T18:40:00",
    expectedMinor: 1_240_000, declaredMinor: null, confirmedMinor: null,
    status: "AWAITING_DECLARATION", ordersInShift: 9, rank: 2,
    note: "Shift closed 1h 20m early. He is still carrying order 4823 and has declared nothing.",
  },
  {
    id: "hv-2", courierId: "cr-8", branchId: "loc-chl", closedAt: "2026-08-21T02:00:00",
    expectedMinor: 890_000, declaredMinor: 890_000, confirmedMinor: 812_000,
    status: "VARIANCE", ordersInShift: 7, rank: 0,
    note: "Counted 78 000 so'm short. Needs a reason code before the shift can settle.",
  },
  {
    id: "hv-3", courierId: "cr-2", branchId: "loc-chl", closedAt: "2026-08-21T14:56:00",
    expectedMinor: 2_140_000, declaredMinor: 2_190_000, confirmedMinor: null,
    status: "AWAITING_CONFIRMATION", ordersInShift: 12, rank: 1,
    note: "Declared 50 000 so'm more than expected — likely a tip recorded as cash.",
  },
  {
    id: "hv-4", courierId: "cr-5", branchId: "loc-chl", closedAt: "2026-08-20T22:41:00",
    expectedMinor: 1_465_000, declaredMinor: 1_465_000, confirmedMinor: null,
    status: "AWAITING_CONFIRMATION", ordersInShift: 11, rank: 1,
    note: "Waiting on the Chilonzor cashier since last night.",
  },
  {
    id: "hv-5", courierId: "cr-1", branchId: "loc-chl", closedAt: "2026-08-20T23:12:00",
    expectedMinor: 980_000, declaredMinor: 980_000, confirmedMinor: 980_000,
    status: "CONFIRMED", ordersInShift: 8, rank: 3,
  },
  {
    id: "hv-6", courierId: "cr-10", branchId: "loc-yun", closedAt: "2026-08-20T22:05:00",
    expectedMinor: 640_000, declaredMinor: 640_000, confirmedMinor: 640_000,
    status: "CONFIRMED", ordersInShift: 6, rank: 3,
  },
];

/* The expected side of one reconciliation, one row per cash order. Authored for
 * hv-2 because it is the row with a variance, which is the row a cashier opens. */
export const HANDOVER_LINES = {
  "hv-2": [
    { order: "QO-4788", totalMinor: 186_000, capturedMinor: 0, loyaltyMinor: 0, dueMinor: 186_000 },
    { order: "QO-4791", totalMinor: 143_000, capturedMinor: 0, loyaltyMinor: 14_000, dueMinor: 129_000 },
    { order: "QO-4794", totalMinor: 96_000, capturedMinor: 0, loyaltyMinor: 0, dueMinor: 96_000 },
    { order: "QO-4796", totalMinor: 212_000, capturedMinor: 100_000, loyaltyMinor: 0, dueMinor: 112_000 },
    { order: "QO-4799", totalMinor: 74_000, capturedMinor: 0, loyaltyMinor: 0, dueMinor: 74_000 },
    { order: "QO-4803", totalMinor: 168_000, capturedMinor: 0, loyaltyMinor: 25_000, dueMinor: 143_000 },
    { order: "QO-4807", totalMinor: 150_000, capturedMinor: 0, loyaltyMinor: 0, dueMinor: 150_000 },
  ],
};

export const VARIANCE_REASONS = [
  { value: "", label: "Select a reason code" },
  { value: "SHORT_COUNT", label: "Short count at handover" },
  { value: "CUSTOMER_UNDERPAID", label: "Customer underpaid at the door" },
  { value: "CHANGE_NOT_RETURNED", label: "Change not returned" },
  { value: "COURIER_ADVANCE", label: "Retained as an advance against pay" },
  { value: "COUNTING_ERROR", label: "Counting error, recounted" },
];

/* ── ledger (ADR 0042 fulfillment.courier_ledger_entries, INSERT/SELECT only) ─
 * Append-only. Positive means the tenant owes the courier; negative means the
 * courier is holding the tenant's cash. One balance, never two.
 */

export const LEDGER = [
  { id: "le-9021", courierId: "cr-7", occurredAt: "2026-08-21T19:26:00", recordedAt: "2026-08-21T19:26:00", type: "CASH_COLLECTED", label: "Cash collected", amountMinor: -412_000, origin: "System", reason: null, source: "QO-4830", actor: "Auto-dispatch" },
  { id: "le-9014", courierId: "cr-7", occurredAt: "2026-08-21T19:26:00", recordedAt: "2026-08-21T19:26:00", type: "DELIVERY_EARNING", label: "Delivery earning", amountMinor: 18_000, origin: "Rate card: Yunusobod avto v3", reason: null, source: "QO-4830", actor: "System" },
  { id: "le-8998", courierId: "cr-7", occurredAt: "2026-08-21T18:02:00", recordedAt: "2026-08-21T18:02:00", type: "CASH_COLLECTED", label: "Cash collected", amountMinor: -1_880_000, origin: "System", reason: null, source: "QO-4801", actor: "Auto-dispatch" },
  { id: "le-8990", courierId: "cr-7", occurredAt: "2026-08-21T16:40:00", recordedAt: "2026-08-21T16:41:00", type: "BONUS", label: "Bonus", amountMinor: 60_000, origin: "Rule: 15 ta yetkazish v2", reason: "VOLUME_15", source: "Shift 21.08", actor: "Rule engine", approval: "Auto" },
  { id: "le-8961", courierId: "cr-7", occurredAt: "2026-08-21T13:30:00", recordedAt: "2026-08-21T13:30:00", type: "SHIFT_EARNING", label: "Shift earning", amountMinor: 90_000, origin: "Rate card: Yunusobod avto v3", reason: null, source: "Shift 21.08", actor: "System" },
  { id: "le-8940", courierId: "cr-7", occurredAt: "2026-08-20T23:04:00", recordedAt: "2026-08-20T23:04:00", type: "CASH_HANDED_OVER", label: "Cash handed over", amountMinor: 1_620_000, origin: "Manual", reason: null, source: "Handover hv-0 · 20.08", actor: "Zafar Alimov" },
  { id: "le-8902", courierId: "cr-7", occurredAt: "2026-08-19T21:10:00", recordedAt: "2026-08-21T09:14:00", type: "PENALTY", label: "Penalty", amountMinor: -40_000, origin: "Manual", reason: "GEO_UNVERIFIED", source: "QO-4712", actor: "Zafar Alimov", approval: "Approved by Nodira Sattorova" },
  { id: "le-8877", courierId: "cr-7", occurredAt: "2026-08-18T22:50:00", recordedAt: "2026-08-21T10:02:00", type: "PRIOR_PERIOD_ADJUSTMENT", label: "Prior period adjustment", amountMinor: 32_000, origin: "Manual", reason: "DISTANCE_CORRECTION", source: "Corrects le-8511, period 01–15.08", actor: "Zafar Alimov", approval: "Approved by Nodira Sattorova" },

  { id: "le-9018", courierId: "cr-1", occurredAt: "2026-08-21T18:49:00", recordedAt: "2026-08-21T18:49:00", type: "DELIVERY_EARNING", label: "Delivery earning", amountMinor: 22_000, origin: "Rate card: Chilonzor moto v5", reason: null, source: "QO-4817", actor: "System" },
  { id: "le-9002", courierId: "cr-1", occurredAt: "2026-08-21T17:36:00", recordedAt: "2026-08-21T17:36:00", type: "CASH_COLLECTED", label: "Cash collected", amountMinor: -640_000, origin: "System", reason: null, source: "QO-4795", actor: "Auto-dispatch" },
  { id: "le-8975", courierId: "cr-1", occurredAt: "2026-08-21T16:00:00", recordedAt: "2026-08-21T16:00:00", type: "SHIFT_EARNING", label: "Shift earning", amountMinor: 70_000, origin: "Rate card: Chilonzor moto v5", reason: null, source: "Shift 21.08", actor: "System" },
  { id: "le-8930", courierId: "cr-1", occurredAt: "2026-08-20T23:12:00", recordedAt: "2026-08-20T23:12:00", type: "CASH_HANDED_OVER", label: "Cash handed over", amountMinor: 980_000, origin: "Manual", reason: null, source: "Handover hv-5 · 20.08", actor: "Zafar Alimov" },

  { id: "le-9009", courierId: "cr-4", occurredAt: "2026-08-21T18:40:00", recordedAt: "2026-08-21T18:40:00", type: "CASH_COLLECTED", label: "Cash collected", amountMinor: -310_000, origin: "System", reason: null, source: "QO-4823", actor: "Auto-dispatch" },
  { id: "le-8968", courierId: "cr-4", occurredAt: "2026-08-21T12:04:00", recordedAt: "2026-08-21T12:04:00", type: "SHIFT_EARNING", label: "Shift earning", amountMinor: 90_000, origin: "Rate card: Yunusobod avto v3", reason: null, source: "Shift 21.08", actor: "System" },

  { id: "le-8985", courierId: "cr-2", occurredAt: "2026-08-21T15:00:00", recordedAt: "2026-08-21T15:00:00", type: "SHIFT_EARNING", label: "Shift earning", amountMinor: 70_000, origin: "Rate card: Chilonzor moto v5", reason: null, source: "Shift 21.08", actor: "System" },
  { id: "le-8944", courierId: "cr-2", occurredAt: "2026-08-21T14:56:00", recordedAt: "2026-08-21T15:02:00", type: "CASH_VARIANCE", label: "Cash variance", amountMinor: 50_000, origin: "Manual", reason: "COUNTING_ERROR", source: "Handover hv-3 · 21.08", actor: "Zafar Alimov", approval: "Awaiting" },
];

export const ledgerFor = (courierId) => LEDGER.filter((l) => l.courierId === courierId);
