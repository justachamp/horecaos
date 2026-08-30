/* Fixture data for the operations prototype.
 *
 * This console belongs to one restaurant's staff during service. Its subject is
 * the order: taking one, watching it cook, getting it to a customer, and knowing
 * which one is late.
 *
 * Authentic Uzbek and Russian content. Money is whole som. Timestamps are ISO.
 *
 * Authored backwards from the states each screen must show, so the awkward cases
 * are deliberately present: an order running late, one whose courier has not been
 * found, a sold-out dish still on the menu, a customer with two saved addresses
 * and one with none, and a basket large enough to test a cramped line list.
 */

export const NOW = "2026-08-21T19:34:00";

/* ── the menu, as an order-taker sees it ───────────────────────────────────
 * Flat, because an operator taking a phone order types a name; they do not
 * browse a category tree. Categories exist for grouping, not for navigation.
 */

export const CATEGORIES = [
  { id: "c-osh", name: "Palov va guruch" },
  { id: "c-kabob", name: "Kabob" },
  { id: "c-non", name: "Non va somsa" },
  { id: "c-sho", name: "Sho'rva" },
  { id: "c-salat", name: "Salatlar" },
  { id: "c-ich", name: "Ichimliklar" },
];

export const MENU_ITEMS = [
  { id: "m-1",  categoryId: "c-osh",   name: "Toshkent oshi",       priceMinor: 45_000, available: true,  prepMinutes: 15, popular: true },
  { id: "m-2",  categoryId: "c-osh",   name: "Chayonli osh",        priceMinor: 52_000, available: true,  prepMinutes: 18 },
  { id: "m-3",  categoryId: "c-osh",   name: "Shavla",              priceMinor: 38_000, available: true,  prepMinutes: 14 },
  { id: "m-4",  categoryId: "c-kabob", name: "Qo'y kabob",          priceMinor: 42_000, available: true,  prepMinutes: 20, popular: true },
  { id: "m-5",  categoryId: "c-kabob", name: "Tovuq kabob",         priceMinor: 32_000, available: true,  prepMinutes: 18 },
  { id: "m-6",  categoryId: "c-kabob", name: "Lo'la kabob",         priceMinor: 38_000, available: false, prepMinutes: 20, soldOutReason: "Qiyma tugadi" },
  { id: "m-7",  categoryId: "c-non",   name: "Tandir non",          priceMinor: 6_000,  available: true,  prepMinutes: 2,  popular: true },
  { id: "m-8",  categoryId: "c-non",   name: "Tandir somsa",        priceMinor: 12_000, available: true,  prepMinutes: 5,  popular: true },
  { id: "m-9",  categoryId: "c-non",   name: "Varaqi somsa",        priceMinor: 14_000, available: true,  prepMinutes: 6 },
  { id: "m-10", categoryId: "c-sho",   name: "Mastava",             priceMinor: 28_000, available: true,  prepMinutes: 10 },
  { id: "m-11", categoryId: "c-sho",   name: "Qo'y sho'rva",        priceMinor: 34_000, available: true,  prepMinutes: 12 },
  { id: "m-12", categoryId: "c-salat", name: "Achichuk",            priceMinor: 14_000, available: true,  prepMinutes: 4,  popular: true },
  { id: "m-13", categoryId: "c-salat", name: "Sazan salat",         priceMinor: 26_000, available: true,  prepMinutes: 6 },
  { id: "m-14", categoryId: "c-ich",   name: "Ko'k choy",           priceMinor: 8_000,  available: true,  prepMinutes: 3,  popular: true },
  { id: "m-15", categoryId: "c-ich",   name: "Qora choy",           priceMinor: 8_000,  available: true,  prepMinutes: 3 },
  { id: "m-16", categoryId: "c-ich",   name: "Ayron",               priceMinor: 10_000, available: true,  prepMinutes: 1 },
  { id: "m-17", categoryId: "c-ich",   name: "Coca-Cola 0.5",       priceMinor: 12_000, available: true,  prepMinutes: 1 },
];

/* Modifiers, kept small on purpose. An order-taker on the phone cannot navigate
 * a deep tree, and every extra tap is a second the customer waits. */
export const MODIFIER_GROUPS = [
  {
    id: "g-portion", name: "Porsiya", appliesTo: ["m-1", "m-2", "m-3"],
    required: true, min: 1, max: 1,
    options: [
      { id: "o-half", name: "Yarim", priceDeltaMinor: -12_000 },
      { id: "o-full", name: "To'liq", priceDeltaMinor: 0, isDefault: true },
      { id: "o-large", name: "Katta", priceDeltaMinor: 15_000 },
    ],
  },
  {
    id: "g-extra", name: "Qo'shimcha", appliesTo: ["m-1", "m-2", "m-3", "m-4", "m-5"],
    required: false, min: 0, max: 4,
    options: [
      { id: "o-kazy", name: "Qazi", priceDeltaMinor: 18_000 },
      { id: "o-egg", name: "Tuxum", priceDeltaMinor: 5_000 },
      { id: "o-salad", name: "Achichuk", priceDeltaMinor: 14_000 },
    ],
  },
];

/* ── customers ─────────────────────────────────────────────────────────────
 * Phone is how an operator finds someone, because that is what the caller says
 * first. One customer deliberately has no saved address, and one has two, so
 * the address step has to handle both.
 */

export const CUSTOMERS = [
  {
    id: "cu-1", name: "Dilnoza Rahimova", phone: "+998901234567",
    ordersCount: 27, lastOrderAt: "2026-08-18T20:12:00", note: "Domofon ishlamaydi, qo'ng'iroq qiling",
    addresses: [
      { id: "ad-1", label: "Uy", line: "Amir Temur ko'chasi 108, 24-xonadon", entrance: "2", floor: "4", landmark: "Metro yonida", lat: 41.3111, lon: 69.2797, isDefault: true },
      { id: "ad-2", label: "Ish", line: "Navoiy ko'chasi 12, ofis 305", entrance: "1", floor: "3", landmark: null, lat: 41.3205, lon: 69.2412 },
    ],
  },
  {
    id: "cu-2", name: "Sardor Yo'ldoshev", phone: "+998934421890",
    ordersCount: 4, lastOrderAt: "2026-08-11T13:40:00", note: null,
    addresses: [
      { id: "ad-3", label: "Uy", line: "Chilonzor 9-kvartal, 42-uy", entrance: "3", floor: "2", landmark: "Maktab ro'parasida", lat: 41.2755, lon: 69.2043, isDefault: true },
    ],
  },
  {
    id: "cu-3", name: "Malika Yusupova", phone: "+998912057714",
    ordersCount: 1, lastOrderAt: "2026-07-02T18:05:00", note: null,
    addresses: [],
  },
  {
    id: "cu-4", name: "Jamshid Ergashev", phone: "+998908846205",
    ordersCount: 63, lastOrderAt: "2026-08-21T12:20:00", note: "Doimiy mijoz",
    addresses: [
      { id: "ad-4", label: "Uy", line: "Yunusobod 19-kvartal, 7-uy", entrance: "1", floor: "5", landmark: null, lat: 41.3641, lon: 69.2894, isDefault: true },
    ],
  },
];

/* ── orders ────────────────────────────────────────────────────────────────
 * The canonical states are code-owned. `lateBy` is a computed overlay on top of
 * a status and never a status itself — an order can be PREPARING and late, and
 * modelling lateness as a state would make that unrepresentable.
 */

export const ORDER_STATES = [
  "PLACED", "ACCEPTED", "PREPARING", "READY", "DISPATCHED", "DELIVERED", "CANCELLED",
];

export const ORDERS = [
  {
    id: "QO-4821", shortId: "4821", placedAt: "2026-08-21T19:31:00",
    status: "PLACED", channel: "Telegram", fulfilment: "DELIVERY",
    customerId: "cu-1", customerName: "Dilnoza Rahimova", customerPhone: "+998901234567",
    addressLine: "Amir Temur ko'chasi 108, 24-xonadon", promisedAt: "2026-08-21T20:15:00",
    lines: [
      { itemId: "m-1", name: "Toshkent oshi", qty: 2, modifiers: ["To'liq", "Qazi"], unitMinor: 63_000 },
      { itemId: "m-12", name: "Achichuk", qty: 1, modifiers: [], unitMinor: 14_000 },
      { itemId: "m-14", name: "Ko'k choy", qty: 2, modifiers: [], unitMinor: 8_000 },
    ],
    subtotalMinor: 156_000, deliveryMinor: 15_000, totalMinor: 171_000,
    payment: "CLICK", paid: true, courierId: null, lateBy: null,
    note: "Domofon ishlamaydi, qo'ng'iroq qiling",
  },
  {
    id: "QO-4820", shortId: "4820", placedAt: "2026-08-21T19:18:00",
    status: "PREPARING", channel: "Website", fulfilment: "DELIVERY",
    customerId: "cu-4", customerName: "Jamshid Ergashev", customerPhone: "+998908846205",
    addressLine: "Yunusobod 19-kvartal, 7-uy", promisedAt: "2026-08-21T19:58:00",
    lines: [
      { itemId: "m-4", name: "Qo'y kabob", qty: 4, modifiers: [], unitMinor: 42_000 },
      { itemId: "m-7", name: "Tandir non", qty: 4, modifiers: [], unitMinor: 6_000 },
    ],
    subtotalMinor: 192_000, deliveryMinor: 15_000, totalMinor: 207_000,
    payment: "CASH", paid: false, courierId: "cr-2", lateBy: null,
    note: null,
  },
  {
    id: "QO-4819", shortId: "4819", placedAt: "2026-08-21T18:52:00",
    status: "PREPARING", channel: "Phone", fulfilment: "DELIVERY",
    customerId: "cu-2", customerName: "Sardor Yo'ldoshev", customerPhone: "+998934421890",
    addressLine: "Chilonzor 9-kvartal, 42-uy", promisedAt: "2026-08-21T19:30:00",
    lines: [
      { itemId: "m-2", name: "Chayonli osh", qty: 1, modifiers: ["Katta"], unitMinor: 67_000 },
      { itemId: "m-11", name: "Qo'y sho'rva", qty: 2, modifiers: [], unitMinor: 34_000 },
      { itemId: "m-8", name: "Tandir somsa", qty: 6, modifiers: [], unitMinor: 12_000 },
      { itemId: "m-16", name: "Ayron", qty: 3, modifiers: [], unitMinor: 10_000 },
    ],
    subtotalMinor: 237_000, deliveryMinor: 15_000, totalMinor: 252_000,
    payment: "CASH", paid: false, courierId: null,
    lateBy: 4, lateReason: "Kitchen behind on kabob",
    note: null,
  },
  {
    id: "QO-4818", shortId: "4818", placedAt: "2026-08-21T18:40:00",
    status: "READY", channel: "Telegram", fulfilment: "PICKUP",
    customerId: "cu-3", customerName: "Malika Yusupova", customerPhone: "+998912057714",
    addressLine: null, promisedAt: "2026-08-21T19:10:00",
    lines: [
      { itemId: "m-8", name: "Tandir somsa", qty: 4, modifiers: [], unitMinor: 12_000 },
      { itemId: "m-14", name: "Ko'k choy", qty: 1, modifiers: [], unitMinor: 8_000 },
    ],
    subtotalMinor: 56_000, deliveryMinor: 0, totalMinor: 56_000,
    payment: "PAYME", paid: true, courierId: null,
    lateBy: 24, lateReason: "Customer has not collected",
    note: "Olib ketish",
  },
  {
    id: "QO-4817", shortId: "4817", placedAt: "2026-08-21T18:22:00",
    status: "DISPATCHED", channel: "Website", fulfilment: "DELIVERY",
    customerId: "cu-1", customerName: "Dilnoza Rahimova", customerPhone: "+998901234567",
    addressLine: "Navoiy ko'chasi 12, ofis 305", promisedAt: "2026-08-21T19:05:00",
    lines: [{ itemId: "m-3", name: "Shavla", qty: 2, modifiers: ["To'liq"], unitMinor: 38_000 }],
    subtotalMinor: 76_000, deliveryMinor: 15_000, totalMinor: 91_000,
    payment: "CLICK", paid: true, courierId: "cr-1",
    lateBy: 29, lateReason: "Courier delayed in traffic",
    note: null,
  },
  {
    id: "QO-4816", shortId: "4816", placedAt: "2026-08-21T17:55:00",
    status: "DELIVERED", channel: "Telegram", fulfilment: "DELIVERY",
    customerId: "cu-4", customerName: "Jamshid Ergashev", customerPhone: "+998908846205",
    addressLine: "Yunusobod 19-kvartal, 7-uy", promisedAt: "2026-08-21T18:35:00",
    deliveredAt: "2026-08-21T18:29:00",
    lines: [{ itemId: "m-1", name: "Toshkent oshi", qty: 1, modifiers: ["To'liq"], unitMinor: 45_000 }],
    subtotalMinor: 45_000, deliveryMinor: 15_000, totalMinor: 60_000,
    payment: "CASH", paid: true, courierId: "cr-2", lateBy: null, note: null,
  },
  {
    id: "QO-4815", shortId: "4815", placedAt: "2026-08-21T17:40:00",
    status: "CANCELLED", channel: "Phone", fulfilment: "DELIVERY",
    customerId: "cu-2", customerName: "Sardor Yo'ldoshev", customerPhone: "+998934421890",
    addressLine: "Chilonzor 9-kvartal, 42-uy", promisedAt: null,
    lines: [{ itemId: "m-6", name: "Lo'la kabob", qty: 2, modifiers: [], unitMinor: 38_000 }],
    subtotalMinor: 76_000, deliveryMinor: 15_000, totalMinor: 91_000,
    payment: "CASH", paid: false, courierId: null, lateBy: null,
    cancelledReason: "Item unavailable — qiyma tugadi", cancelledBy: "Operator",
    note: null,
  },
];

/* ── couriers ──────────────────────────────────────────────────────────────*/

export const COURIERS = [
  { id: "cr-1", name: "Alisher Karimov", phone: "+998919876543", vehicle: "Mototsikl", status: "ON_DELIVERY", activeOrders: 1, shiftStart: "2026-08-21T16:00:00", todayDeliveries: 11 },
  { id: "cr-2", name: "Ravshan Umarov", phone: "+998903341276", vehicle: "Mototsikl", status: "ON_DELIVERY", activeOrders: 2, shiftStart: "2026-08-21T15:00:00", todayDeliveries: 14 },
  { id: "cr-3", name: "Otabek Nazarov", phone: "+998977712095", vehicle: "Velosiped", status: "AVAILABLE", activeOrders: 0, shiftStart: "2026-08-21T18:00:00", todayDeliveries: 4 },
  { id: "cr-4", name: "Shoxrux Qodirov", phone: "+998946620183", vehicle: "Avtomobil", status: "OFF_SHIFT", activeOrders: 0, shiftStart: null, todayDeliveries: 0 },
];

/* ── kitchen ───────────────────────────────────────────────────────────────
 * A kitchen ticket is per order and its lines carry their own readiness, so a
 * dish finishing early is visible without the whole order being ready.
 */

export const KITCHEN_TICKETS = [
  {
    orderId: "QO-4820", shortId: "4820", firedAt: "2026-08-21T19:19:00", station: "Kabob",
    elapsedMinutes: 15, targetMinutes: 20,
    lines: [
      { name: "Qo'y kabob", qty: 4, state: "COOKING" },
      { name: "Tandir non", qty: 4, state: "READY" },
    ],
  },
  {
    orderId: "QO-4819", shortId: "4819", firedAt: "2026-08-21T18:53:00", station: "Osh",
    elapsedMinutes: 41, targetMinutes: 18,
    lines: [
      { name: "Chayonli osh", qty: 1, state: "COOKING" },
      { name: "Qo'y sho'rva", qty: 2, state: "READY" },
      { name: "Tandir somsa", qty: 6, state: "READY" },
      { name: "Ayron", qty: 3, state: "READY" },
    ],
  },
  {
    orderId: "QO-4821", shortId: "4821", firedAt: null, station: "Osh",
    elapsedMinutes: 0, targetMinutes: 15,
    lines: [
      { name: "Toshkent oshi", qty: 2, state: "WAITING" },
      { name: "Achichuk", qty: 1, state: "WAITING" },
      { name: "Ko'k choy", qty: 2, state: "WAITING" },
    ],
  },
];

/* ── today ─────────────────────────────────────────────────────────────────*/

export const TODAY = {
  ordersTotal: 87,
  ordersOpen: 5,
  ordersLate: 3,
  revenueMinor: 7_412_000,
  averageBasketMinor: 85_195,
  averagePrepMinutes: 17,
  cancelledCount: 4,
  location: "Chilonzor filiali",
  brand: "Osh Markazi",
};

export const HOURLY = [
  { hour: "11", orders: 4 }, { hour: "12", orders: 9 }, { hour: "13", orders: 14 },
  { hour: "14", orders: 8 }, { hour: "15", orders: 3 }, { hour: "16", orders: 5 },
  { hour: "17", orders: 7 }, { hour: "18", orders: 13 }, { hour: "19", orders: 16 },
];

/* Grouped, because a flat list of eleven entries is a list nobody reads.
 *
 * The order is the working day, not the org chart: what is happening now, then
 * the people doing it, then the things that are only changed occasionally. A
 * manager lives in the first group during service and visits the third one on a
 * Tuesday morning. */
export const MENU_NAV = [
  { group: "Service", items: [
    { id: "today",      label: "Today" },
    { id: "orders",     label: "Orders" },
    { id: "kitchen",    label: "Kitchen" },
    { id: "delivery",   label: "Delivery" },
  ] },
  { group: "People", items: [
    { id: "couriers",   label: "Couriers" },
    { id: "customers",  label: "Customers" },
    { id: "staff",      label: "Staff and access" },
  ] },
  { group: "Business", items: [
    { id: "statistics", label: "Statistics" },
    { id: "catalog",    label: "Menu" },
    { id: "places",     label: "Brands and locations" },
    { id: "settings",   label: "Settings" },
  ] },
];

/** Flattened, for the places that need to look a label up by id. */
export const NAV_ITEMS = MENU_NAV.flatMap((section) => section.items);
