/* Fixtures for the Catalog section of the operations console.
 *
 * Authored backwards from the states catalog.md says the screens must show, so
 * the awkward cases are here on purpose and not as an afterthought:
 *
 *   · a 74-character dish name sitting next to "Non"
 *   · a dish stopped at 11:05 and still stopped at 19:34 — eight and a half
 *     hours of a dish nobody is selling and nobody has noticed
 *   · a stop written by an actor who has since been deactivated, and one
 *     written by POS-import, which is not a person at all (ADR 0027)
 *   · a branch force-closed with a reason and an effective_until
 *   · a product whose only variants are archived, one whose variant has no
 *     active price, one whose photo was rejected by the media pipeline
 *   · a variant with no inventory.stock_items row, which InventoryService
 *     treats as unavailable rather than as an error
 *   · a variant in QUANTITY tracking mode, which the schema accepts and the
 *     service refuses
 *   · an aggregator channel that prices on its own side
 *   · a brand-new draft nobody has published, beside a live published menu
 *
 * Shape notes. Money is whole som as integers (bigint minor units = som).
 * Times are ISO strings. Offerings are held on the variant as a location map,
 * because catalog.location_offerings is one row per (location, variant) and
 * flattening it here is what lets the matrix and the stop list read the same
 * fact rather than two copies of it.
 */

import { NOW } from "./data";

export const BRAND = {
  id: "b-osh",
  name: "Osh Markazi",
  defaultLocale: "uz",
  locales: ["uz", "ru", "en"],
  catalogs: [
    { id: "cat-main", name: "Asosiy menyu" },
    { id: "cat-season", name: "Yozgi menyu" },
  ],
};

/* ── locations ─────────────────────────────────────────────────────────────
 * Four branches. Mirobod is force-closed by its own manager with a reason and
 * an end time, and the matrix must not imply it is selling.
 */
export const LOCATIONS = [
  { id: "chi", name: "Chilonzor filiali", address: "Chilonzor 9-kvartal, 42-uy", mode: "OPEN" },
  { id: "yun", name: "Yunusobod filiali", address: "Yunusobod 19-kvartal, 7-uy", mode: "OPEN" },
  { id: "ser", name: "Sergeli filiali", address: "Sergeli, Yangi Sergeli ko'chasi 4", mode: "OPEN" },
  {
    id: "mir", name: "Mirobod filiali", address: "Amir Temur ko'chasi 108",
    mode: "FORCE_CLOSED", closedReason: "Suv o'chirilgan", closedUntil: "2026-08-21T21:00:00",
  },
];

export const LOC = Object.fromEntries(LOCATIONS.map((l) => [l.id, l]));

/* ── channels ──────────────────────────────────────────────────────────────
 * tenant.sales_channels. `pricePlane` is the price_plane_channel_id pointer —
 * QR tables take hall prices without a global switch. `externallyPriced` is
 * true for the aggregator that prices on its own side, whose price column is
 * therefore read-only rather than editable and wrong.
 */
export const CHANNELS = [
  { id: "ch-web", code: "WEB", name: "Veb-sayt", systemType: "WEB", status: "ACTIVE", externallyPriced: false, pricePlane: null },
  { id: "ch-tg", code: "TELEGRAM", name: "Telegram bot", systemType: "TELEGRAM", status: "ACTIVE", externallyPriced: false, pricePlane: null },
  { id: "ch-cc", code: "CALL", name: "Call-markaz", systemType: "CALL_CENTRE", status: "ACTIVE", externallyPriced: false, pricePlane: null },
  { id: "ch-qr", code: "QR", name: "QR stol", systemType: "QR_TABLE", status: "ACTIVE", externallyPriced: false, pricePlane: "ch-hall" },
  { id: "ch-uzum", code: "UZUM", name: "Uzum Tezkor", systemType: "AGGREGATOR", status: "ACTIVE", externallyPriced: true, pricePlane: null },
  { id: "ch-kiosk", code: "KIOSK", name: "Kiosk (zalda)", systemType: "KIOSK", status: "ARCHIVED", externallyPriced: false, pricePlane: "ch-hall" },
];

/* ── categories ────────────────────────────────────────────────────────────
 * catalog.categories, a real tree: parent_category_id constrained to the same
 * catalog by V0018. Shirinliklar has no active products, which the customer
 * reads as an empty heading, so it is rendered muted.
 */
export const CAT_CATEGORIES = [
  { id: "c-osh", parentId: null, name: "Palov va guruch", code: "OSH", sort: 1, status: "ACTIVE" },
  { id: "c-kabob", parentId: null, name: "Kabob va grill", code: "KAB", sort: 2, status: "ACTIVE" },
  { id: "c-non", parentId: null, name: "Non va somsa", code: "NON", sort: 3, status: "ACTIVE" },
  { id: "c-somsa", parentId: "c-non", name: "Somsa", code: "SOM", sort: 1, status: "ACTIVE" },
  { id: "c-sho", parentId: null, name: "Sho'rva", code: "SHO", sort: 4, status: "ACTIVE" },
  { id: "c-salat", parentId: null, name: "Salatlar", code: "SAL", sort: 5, status: "ACTIVE" },
  { id: "c-ich", parentId: null, name: "Ichimliklar", code: "ICH", sort: 6, status: "ACTIVE" },
  { id: "c-ich-issiq", parentId: "c-ich", name: "Issiq ichimliklar", code: "ICH-H", sort: 1, status: "ACTIVE" },
  { id: "c-ich-sovuq", parentId: "c-ich", name: "Sovuq ichimliklar", code: "ICH-C", sort: 2, status: "ACTIVE" },
  { id: "c-shirin", parentId: null, name: "Shirinliklar", code: "SHI", sort: 7, status: "ACTIVE" },
];

export const CAT = Object.fromEntries(CAT_CATEGORIES.map((c) => [c.id, c]));

/* ── actors ────────────────────────────────────────────────────────────────
 * ADR 0027: non-human actors are first class. A deactivated user still owns
 * the rows they wrote — history does not get rewritten when someone leaves.
 */
export const ACTORS = {
  "u-aziz": { name: "Aziz Tursunov", role: "Oshpaz", type: "USER", active: true },
  "u-sardor": { name: "Sardor Yo'ldoshev", role: "Smena boshlig'i", type: "USER", active: true },
  "u-nigora": { name: "Nigora Sobirova", role: "Menejer", type: "USER", active: true },
  "u-nodira": { name: "Nodira Ismoilova", role: "Oshpaz", type: "USER", active: false },
  "sys-pos": { name: "POS-import", role: null, type: "SYSTEM", active: true },
};

/* The four reason chips, in the words a kitchen actually uses. Four, because a
 * list you have to read is a list nobody uses mid-rush. */
export const STOP_REASONS = [
  { code: "OUT_OF_STOCK", label: "Tugadi" },
  { code: "NO_INGREDIENT", label: "Mahsulot yo'q" },
  { code: "EQUIPMENT", label: "Uskuna ishlamayapti" },
  { code: "OTHER", label: "Boshqa" },
];

export const REASON_LABEL = Object.fromEntries(STOP_REASONS.map((r) => [r.code, r.label]));

/* ── products ──────────────────────────────────────────────────────────────
 * `at` on a variant is catalog.location_offerings.status per location:
 *   AVAILABLE · UNAVAILABLE · HIDDEN · null (no offering row at all — unlisted,
 *   which the matrix renders dashed because it is not the same as stopped).
 * `track` is inventory.stock_items.tracking_mode; NONE means no stock_items row.
 * `photo` is the PRIMARY media relation's media.assets.status, or null.
 */
export const PRODUCTS = [
  {
    id: "p-1", code: "OSH-001", name: "Toshkent oshi", nameRu: "Ташкентский плов",
    categoryIds: ["c-osh"], status: "ACTIVE", mxik: "01203001001000001", photo: "AVAILABLE",
    published: true, catalogIds: ["cat-main"], updatedAt: "2026-08-19T11:20:00",
    variants: [
      { id: "v-1a", sku: "OSH-001-Y", name: "Yarim porsiya", unit: "PIECE", isDefault: false, priceMinor: 33_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "BINARY", at: { chi: "AVAILABLE", yun: "AVAILABLE", ser: "AVAILABLE", mir: "AVAILABLE" } },
      { id: "v-1b", sku: "OSH-001-T", name: "To'liq porsiya", unit: "PIECE", isDefault: true, priceMinor: 45_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "BINARY", at: { chi: "AVAILABLE", yun: "AVAILABLE", ser: "AVAILABLE", mir: "AVAILABLE" } },
      { id: "v-1c", sku: "OSH-001-K", name: "Katta porsiya", unit: "PIECE", isDefault: false, priceMinor: 60_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "BINARY", at: { chi: "AVAILABLE", yun: "AVAILABLE", ser: "AVAILABLE", mir: null } },
    ],
  },
  {
    id: "p-2", code: "OSH-002", name: "Chayonli osh", nameRu: "Плов с казы",
    categoryIds: ["c-osh"], status: "ACTIVE", mxik: "01203001001000002", photo: "AVAILABLE",
    published: true, catalogIds: ["cat-main"], updatedAt: "2026-08-17T09:02:00",
    variants: [
      { id: "v-2a", sku: "OSH-002", name: null, unit: "PIECE", isDefault: true, priceMinor: 52_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "BINARY", at: { chi: "AVAILABLE", yun: "AVAILABLE", ser: "HIDDEN", mir: "AVAILABLE" } },
    ],
  },
  {
    id: "p-3", code: "OSH-003", name: "Shavla", nameRu: "Шавля",
    categoryIds: ["c-osh"], status: "ACTIVE", mxik: "01203001001000003", photo: null,
    published: true, catalogIds: ["cat-main"], updatedAt: "2026-08-12T16:41:00",
    variants: [
      { id: "v-3a", sku: "OSH-003", name: null, unit: "PIECE", isDefault: true, priceMinor: 38_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "BINARY", at: { chi: "UNAVAILABLE", yun: "AVAILABLE", ser: "AVAILABLE", mir: "AVAILABLE" } },
    ],
  },
  {
    id: "p-4", code: "KAB-001", name: "Qo'y kabob", nameRu: "Шашлык из баранины",
    categoryIds: ["c-kabob"], status: "ACTIVE", mxik: "01203002001000001", photo: "AVAILABLE",
    published: true, catalogIds: ["cat-main"], updatedAt: "2026-08-20T13:15:00",
    /* Two active variants and is_default on neither — PRODUCT_HAS_NO_DEFAULT_VARIANT,
     * raised only because there is more than one. */
    variants: [
      { id: "v-4a", sku: "KAB-001-1", name: "1 sixt", unit: "PIECE", isDefault: false, priceMinor: 42_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "BINARY", at: { chi: "AVAILABLE", yun: "AVAILABLE", ser: "AVAILABLE", mir: "AVAILABLE" } },
      { id: "v-4b", sku: "KAB-001-P", name: "Porsiya, 4 sixt", unit: "PIECE", isDefault: false, priceMinor: 156_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "BINARY", at: { chi: "AVAILABLE", yun: "AVAILABLE", ser: null, mir: "AVAILABLE" } },
    ],
  },
  {
    id: "p-5", code: "KAB-002", name: "Tovuq kabob", nameRu: "Куриный шашлык",
    categoryIds: ["c-kabob"], status: "ACTIVE", mxik: "01203002001000002", photo: "AVAILABLE",
    published: true, catalogIds: ["cat-main"], updatedAt: "2026-08-14T10:00:00",
    variants: [
      { id: "v-5a", sku: "KAB-002", name: null, unit: "PIECE", isDefault: true, priceMinor: 32_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "BINARY", at: { chi: "AVAILABLE", yun: "AVAILABLE", ser: "AVAILABLE", mir: "AVAILABLE" } },
    ],
  },
  {
    id: "p-6", code: "KAB-003", name: "Lo'la kabob", nameRu: "Люля-кебаб",
    categoryIds: ["c-kabob"], status: "ACTIVE", mxik: "01203002001000003", photo: "AVAILABLE",
    published: true, catalogIds: ["cat-main"], updatedAt: "2026-08-21T14:32:00",
    variants: [
      { id: "v-6a", sku: "KAB-003", name: null, unit: "PIECE", isDefault: true, priceMinor: 38_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "BINARY", at: { chi: "UNAVAILABLE", yun: "UNAVAILABLE", ser: "AVAILABLE", mir: "AVAILABLE" } },
    ],
  },
  {
    /* The long one. 74 characters, and it sits directly above "Non" in the
     * default sort — if the table breaks on either it breaks in service. */
    id: "p-7", code: "KAB-004",
    name: "Qozon kabob (qo'y go'shti, tandirda sekin pishirilgan, achchiq ziravorlar bilan)",
    nameRu: "Казан-кебаб из баранины томлёный",
    categoryIds: ["c-kabob"], status: "ACTIVE", mxik: null, photo: "REJECTED",
    photoRejection: { code: "IMAGE_TOO_SMALL", detail: "640×360 — минимум 1200×800" },
    published: false, catalogIds: ["cat-main", "cat-season"], updatedAt: "2026-08-21T09:48:00",
    variants: [
      { id: "v-7a", sku: "KAB-004", name: null, unit: "PIECE", isDefault: true, priceMinor: 96_000, mxik: null, pkg: null, status: "ACTIVE", track: "BINARY", at: { chi: "AVAILABLE", yun: null, ser: null, mir: null } },
    ],
  },
  {
    id: "p-8", code: "NON-001", name: "Non", nameRu: "Лепёшка",
    categoryIds: ["c-non"], status: "ACTIVE", mxik: "01203003001000001", photo: "AVAILABLE",
    published: true, catalogIds: ["cat-main"], updatedAt: "2026-06-02T08:00:00",
    variants: [
      { id: "v-8a", sku: "NON-001", name: null, unit: "PIECE", isDefault: true, priceMinor: 6_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "UNTRACKED", at: { chi: "AVAILABLE", yun: "AVAILABLE", ser: "AVAILABLE", mir: "AVAILABLE" } },
    ],
  },
  {
    id: "p-9", code: "SOM-001", name: "Tandir somsa", nameRu: "Тандыр-самса",
    categoryIds: ["c-somsa"], status: "ACTIVE", mxik: "01203003002000001", photo: "AVAILABLE",
    published: true, catalogIds: ["cat-main"], updatedAt: "2026-08-21T19:31:00",
    variants: [
      { id: "v-9a", sku: "SOM-001", name: null, unit: "PIECE", isDefault: true, priceMinor: 12_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "BINARY", at: { chi: "UNAVAILABLE", yun: "AVAILABLE", ser: "AVAILABLE", mir: "AVAILABLE" } },
    ],
  },
  {
    id: "p-10", code: "SOM-002", name: "Varaqi somsa", nameRu: "Слоёная самса",
    categoryIds: ["c-somsa"], status: "DRAFT", mxik: "01203003002000002", photo: "AVAILABLE",
    published: false, catalogIds: ["cat-main"], updatedAt: "2026-08-21T15:55:00",
    variants: [
      { id: "v-10a", sku: "SOM-002", name: null, unit: "PIECE", isDefault: true, priceMinor: 14_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "BINARY", at: { chi: null, yun: null, ser: null, mir: null } },
    ],
  },
  {
    id: "p-11", code: "SHO-001", name: "Mastava", nameRu: "Мастава",
    categoryIds: ["c-sho"], status: "ACTIVE", mxik: "01203004001000001", photo: "AVAILABLE",
    published: true, catalogIds: ["cat-main"], updatedAt: "2026-08-21T19:20:00",
    variants: [
      { id: "v-11a", sku: "SHO-001", name: null, unit: "PIECE", isDefault: true, priceMinor: 28_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "BINARY", at: { chi: "UNAVAILABLE", yun: "AVAILABLE", ser: "AVAILABLE", mir: "AVAILABLE" } },
    ],
  },
  {
    /* No current pricing.prices row for the only variant — VARIANT_HAS_NO_ACTIVE_PRICE,
     * a publication blocker, and the reason this dish cannot be quoted. */
    id: "p-12", code: "SHO-002", name: "Qo'y sho'rva", nameRu: "Шурпа из баранины",
    categoryIds: ["c-sho"], status: "ACTIVE", mxik: "01203004001000002", photo: "AVAILABLE",
    published: false, catalogIds: ["cat-main"], updatedAt: "2026-08-20T18:30:00",
    variants: [
      { id: "v-12a", sku: "SHO-002", name: null, unit: "PIECE", isDefault: true, priceMinor: null, mxik: null, pkg: "1000000", status: "ACTIVE", track: "BINARY", at: { chi: "AVAILABLE", yun: "AVAILABLE", ser: null, mir: null } },
    ],
  },
  {
    id: "p-13", code: "SAL-001", name: "Achichuk", nameRu: "Ачичук",
    categoryIds: ["c-salat"], status: "ACTIVE", mxik: "01203005001000001", photo: "AVAILABLE",
    published: true, catalogIds: ["cat-main"], updatedAt: "2026-08-21T11:05:00",
    variants: [
      { id: "v-13a", sku: "SAL-001", name: null, unit: "PIECE", isDefault: true, priceMinor: 14_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "BINARY", at: { chi: "UNAVAILABLE", yun: "AVAILABLE", ser: "AVAILABLE", mir: "AVAILABLE" } },
    ],
  },
  {
    /* Active, priced, photographed, classified — and in no branch's menu. The
     * most common cause of "why can't customers see it". */
    id: "p-14", code: "SAL-002", name: "Sazan salat", nameRu: "Салат из сазана",
    categoryIds: ["c-salat"], status: "ACTIVE", mxik: "01203005001000002", photo: "AVAILABLE",
    published: true, catalogIds: ["cat-main"], updatedAt: "2026-08-16T12:10:00",
    variants: [
      { id: "v-14a", sku: "SAL-002", name: null, unit: "PIECE", isDefault: true, priceMinor: 26_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "BINARY", at: { chi: null, yun: null, ser: null, mir: null } },
    ],
  },
  {
    id: "p-15", code: "ICH-001", name: "Ko'k choy", nameRu: "Зелёный чай",
    categoryIds: ["c-ich-issiq"], status: "ACTIVE", mxik: "01203006001000001", photo: "AVAILABLE",
    published: true, catalogIds: ["cat-main"], updatedAt: "2026-05-30T10:00:00",
    variants: [
      { id: "v-15a", sku: "ICH-001", name: null, unit: "PIECE", isDefault: true, priceMinor: 8_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "UNTRACKED", at: { chi: "AVAILABLE", yun: "AVAILABLE", ser: "AVAILABLE", mir: "AVAILABLE" } },
    ],
  },
  {
    id: "p-16", code: "ICH-002", name: "Qora choy", nameRu: "Чёрный чай",
    categoryIds: ["c-ich-issiq"], status: "ACTIVE", mxik: "01203006001000002", photo: null,
    published: true, catalogIds: ["cat-main"], updatedAt: "2026-08-21T17:40:00",
    variants: [
      { id: "v-16a", sku: "ICH-002", name: null, unit: "PIECE", isDefault: true, priceMinor: 8_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "UNTRACKED", at: { chi: "UNAVAILABLE", yun: "AVAILABLE", ser: "AVAILABLE", mir: "AVAILABLE" } },
    ],
  },
  {
    /* tracking_mode = QUANTITY is accepted by the schema and refused by
     * InventoryService. Rendered read-only rather than as a control that 409s. */
    id: "p-17", code: "ICH-003", name: "Ayron", nameRu: "Айран",
    categoryIds: ["c-ich-sovuq"], status: "ACTIVE", mxik: "01203006002000001", photo: "AVAILABLE",
    published: true, catalogIds: ["cat-main"], updatedAt: "2026-08-10T14:00:00",
    variants: [
      { id: "v-17a", sku: "ICH-003", name: null, unit: "PIECE", isDefault: true, priceMinor: 10_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "QUANTITY", at: { chi: "AVAILABLE", yun: "AVAILABLE", ser: "AVAILABLE", mir: "AVAILABLE" } },
    ],
  },
  {
    id: "p-18", code: "ICH-004", name: "Coca-Cola 0.5", nameRu: "Кока-кола 0,5",
    categoryIds: ["c-ich-sovuq"], status: "ACTIVE", mxik: null, photo: "AVAILABLE",
    published: true, catalogIds: ["cat-main"], updatedAt: "2026-07-21T09:30:00",
    variants: [
      { id: "v-18a", sku: "ICH-004", name: null, unit: "PIECE", isDefault: true, priceMinor: 12_000, mxik: null, pkg: null, status: "ACTIVE", track: "BINARY", at: { chi: "AVAILABLE", yun: "AVAILABLE", ser: "AVAILABLE", mir: "AVAILABLE" } },
    ],
  },
  {
    /* No inventory.stock_items row at all. InventoryService reads this as
     * unavailable, not as an error — so the row offers "add to stock". */
    id: "p-19", code: "NON-002", name: "Patir non", nameRu: "Патыр",
    categoryIds: ["c-non"], status: "ACTIVE", mxik: "01203003001000002", photo: "AVAILABLE",
    published: true, catalogIds: ["cat-main"], updatedAt: "2026-08-19T07:55:00",
    variants: [
      { id: "v-19a", sku: "NON-002", name: null, unit: "PIECE", isDefault: true, priceMinor: 9_000, mxik: null, pkg: "1000000", status: "ACTIVE", track: "NONE", at: { chi: "AVAILABLE", yun: "AVAILABLE", ser: null, mir: null } },
    ],
  },
  {
    /* Every variant archived. PRODUCT_HAS_NO_ACTIVE_VARIANT — the product is
     * live in the catalog and sells nothing. */
    id: "p-20", code: "SET-001", name: "Osh seti (2 kishilik)", nameRu: "Сет на двоих",
    categoryIds: ["c-osh"], status: "ACTIVE", mxik: "01203001002000001", photo: "AVAILABLE",
    published: false, catalogIds: ["cat-season"], updatedAt: "2026-08-18T20:44:00",
    variants: [
      { id: "v-20a", sku: "SET-001", name: null, unit: "PIECE", isDefault: true, priceMinor: 118_000, mxik: null, pkg: "1000000", status: "ARCHIVED", track: "BINARY", at: { chi: null, yun: null, ser: null, mir: null } },
    ],
  },
  {
    id: "p-21", code: "SHI-001", name: "Chak-chak", nameRu: "Чак-чак",
    categoryIds: ["c-shirin"], status: "ARCHIVED", mxik: "01203007001000001", photo: "AVAILABLE",
    published: false, catalogIds: ["cat-main"], updatedAt: "2026-04-11T15:20:00",
    variants: [
      { id: "v-21a", sku: "SHI-001", name: null, unit: "PIECE", isDefault: true, priceMinor: 18_000, mxik: null, pkg: "1000000", status: "ARCHIVED", track: "BINARY", at: { chi: null, yun: null, ser: null, mir: null } },
    ],
  },
  {
    id: "p-22", code: "ICH-005", name: "Choyxona kompoti", nameRu: "Компот",
    categoryIds: ["c-ich-sovuq"], status: "DRAFT", mxik: null, photo: null,
    published: false, catalogIds: ["cat-season"], updatedAt: "2026-08-21T18:12:00",
    variants: [
      { id: "v-22a", sku: "ICH-005", name: null, unit: "PIECE", isDefault: true, priceMinor: 9_000, mxik: null, pkg: null, status: "ACTIVE", track: "BINARY", at: { chi: null, yun: null, ser: null, mir: null } },
    ],
  },
];

/* ── fulfilment modes ──────────────────────────────────────────────────────
 * catalog.location_offerings.fulfillment_modes, defaulting to DELIVERY,PICKUP.
 * Held as an override map rather than on every variant, because three modes
 * repeated forty times is noise and the exceptions are the whole point: a
 * casserole that cannot survive a courier bag is hall-only, and a customer
 * looking at delivery will never see it.
 */
export const MODES_DEFAULT = ["DELIVERY", "PICKUP", "HALL"];
export const MODE_OVERRIDES = {
  "v-7a": ["HALL"],
  "v-4b": ["DELIVERY", "HALL"],
  "v-19a": ["PICKUP"],
};
export const MODE_LABEL = { DELIVERY: "Delivery", PICKUP: "Pickup", HALL: "Hall" };
export const MODE_SHORT = { DELIVERY: "Dlv", PICKUP: "Pck", HALL: "Hall" };
export const modesFor = (v) => MODE_OVERRIDES[v.id] || MODES_DEFAULT;

/* ── availability movements ────────────────────────────────────────────────
 * The latest inventory.movements row per (variant, location) where the offering
 * is UNAVAILABLE. Keyed `variantId@locationId`, exactly as the offering is.
 * Aziz's 11:05 stop on Achichuk is still standing at 19:34 — eight and a half
 * hours of a salad nobody is selling. That row is the whole argument for the
 * stop list being the first screen and not the fourth.
 */
export const MOVEMENTS = {
  "v-3a@chi": { at: "2026-08-21T18:58:00", actorId: "sys-pos", reason: "EQUIPMENT" },
  "v-6a@chi": { at: "2026-08-21T14:32:00", actorId: "u-aziz", reason: "NO_INGREDIENT" },
  "v-6a@yun": { at: "2026-08-21T16:10:00", actorId: "u-sardor", reason: "NO_INGREDIENT" },
  "v-9a@chi": { at: "2026-08-21T19:31:00", actorId: "u-aziz", reason: "OUT_OF_STOCK" },
  "v-11a@chi": { at: "2026-08-21T19:20:00", actorId: "u-aziz", reason: "OUT_OF_STOCK" },
  "v-13a@chi": { at: "2026-08-21T11:05:00", actorId: "u-nodira", reason: "NO_INGREDIENT" },
  "v-16a@chi": { at: "2026-08-21T17:40:00", actorId: "u-sardor", reason: "OTHER" },
};

/* ── derivations ───────────────────────────────────────────────────────────
 * Kept beside the fixtures rather than in the screen, because every view reads
 * the same facts and two copies of "is this a blocker" is how a prototype ends
 * up disagreeing with itself.
 */

export const activeVariants = (p) => p.variants.filter((v) => v.status !== "ARCHIVED");

/** The publication blockers CatalogValidator raises for this product, in code
 *  form. Order matters: the first is the one the row caption shows. */
export function blockersFor(p) {
  const out = [];
  const av = activeVariants(p);
  if (p.status !== "ARCHIVED") {
    if (!av.length) out.push("PRODUCT_HAS_NO_ACTIVE_VARIANT");
    if (av.length > 1 && !av.some((v) => v.isDefault)) out.push("PRODUCT_HAS_NO_DEFAULT_VARIANT");
    if (av.some((v) => v.priceMinor == null)) out.push("VARIANT_HAS_NO_ACTIVE_PRICE");
    if (p.photo && p.photo !== "AVAILABLE") out.push("MEDIA_NOT_AVAILABLE");
  }
  return out;
}

/** Warnings today, blockers once ADR 0038 is accepted. Severity comes off the
 *  finding, never off a client-side table, so that stays a backend change. */
export function warningsFor(p) {
  const out = [];
  if (p.status === "ARCHIVED") return out;
  if (!p.mxik && !p.variants.some((v) => v.mxik)) out.push("FISCAL_CLASSIFICATION_MISSING");
  if (!p.photo) out.push("NO_PHOTO");
  return out;
}

export const FINDING_TEXT = {
  PRODUCT_HAS_NO_ACTIVE_VARIANT: "No active variant",
  PRODUCT_HAS_NO_DEFAULT_VARIANT: "No default variant chosen",
  VARIANT_HAS_NO_ACTIVE_PRICE: "No active price",
  MEDIA_NOT_AVAILABLE: "Photo not ready",
  MODIFIER_GROUP_HAS_NO_OPTIONS: "Modifier group with no options",
  MODIFIER_GROUP_MINIMUM_UNSATISFIABLE: "More choices required than options offered",
  MISSING_TRANSLATION: "No name in the brand locale",
  FISCAL_CLASSIFICATION_MISSING: "No ИКПУ/MXIK",
  NO_PHOTO: "No photo",
};

/** Severity band for the default `by state` sort. Lower sorts first. */
export function severityOf(p) {
  if (p.status === "ARCHIVED") return { band: 4, level: "none", reason: null };
  const b = blockersFor(p);
  if (b.length) return { band: 0, level: "blocker", reason: FINDING_TEXT[b[0]] };
  const w = warningsFor(p);
  if (w.length) return { band: 1, level: "warn", reason: w.map((c) => FINDING_TEXT[c]).join(" · ") };
  if (p.status === "DRAFT") return { band: 2, level: "none", reason: null };
  return { band: 3, level: "none", reason: null };
}

/** n of 4 branches: offerings at AVAILABLE across the product's variants. */
export function inMenuCount(p) {
  const locs = new Set();
  for (const v of activeVariants(p)) {
    for (const [l, s] of Object.entries(v.at)) if (s === "AVAILABLE") locs.add(l);
  }
  return locs.size;
}

export const priceRange = (p) => {
  const prices = activeVariants(p).map((v) => v.priceMinor).filter((x) => x != null);
  if (!prices.length) return null;
  return { min: Math.min(...prices), max: Math.max(...prices) };
};

/** The customer's menu order: a child sorts inside its parent, never against
 *  the roots. Without this a sub-category's sort_order collides with a root's
 *  and the list reads in an order no menu has ever been printed in. */
export const menuOrder = (id) => {
  const c = CAT[id];
  if (!c) return 9999;
  return c.parentId ? CAT[c.parentId].sort * 100 + c.sort : c.sort * 100;
};

export const categoryPath = (id) => {
  const c = CAT[id];
  if (!c) return "—";
  return c.parentId ? `${CAT[c.parentId].name} · ${c.name}` : c.name;
};

/** Every variant offered, stopped or unlisted at one location, flattened into
 *  the rows the stop list and the explainer both read. */
export function variantRowsAt(locationId) {
  const rows = [];
  for (const p of PRODUCTS) {
    if (p.status === "ARCHIVED") continue;
    for (const v of activeVariants(p)) {
      const status = v.at[locationId] ?? null;
      if (status === null) continue;
      const mv = MOVEMENTS[`${v.id}@${locationId}`] || null;
      rows.push({
        key: `${v.id}@${locationId}`,
        product: p, variant: v, locationId,
        offering: status,
        stopped: status === "UNAVAILABLE",
        movement: mv,
        categoryId: p.categoryIds[0],
        unsupported: v.track === "QUANTITY",
        unstocked: v.track === "NONE",
      });
    }
  }
  return rows;
}

/** Minutes between two ISO instants, floored. */
export const minutesBetween = (a, b) => Math.floor((new Date(b) - new Date(a)) / 60000);

/** "8 h 29 min" / "14 min" — a duration an operator reads, not a timestamp they
 *  have to subtract from the clock on the wall. */
export function since(iso, now = NOW) {
  const m = minutesBetween(iso, now);
  if (m < 1) return "just now";
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)} h ${m % 60} min`;
}

/* ── the availability explainer ────────────────────────────────────────────
 * Six independent reasons a dish can be unbuyable, resolved in the order
 * ADR 0017's projection resolves them. Delever has no such screen; this is one
 * endpoint and it answers the most common support question in the product.
 */
export function explain(row, channelId = "ch-tg") {
  const { product: p, variant: v, locationId } = row;
  const loc = LOC[locationId];
  const ch = CHANNELS.find((c) => c.id === channelId);
  const mv = row.movement;
  const layers = [];

  layers.push({
    layer: "Published", ok: p.published, view: "publication",
    line: p.published ? "In the live menu publication" : "Not in the current menu publication",
  });
  layers.push({
    layer: "In the branch menu", ok: row.offering !== "HIDDEN", view: "menus",
    line: row.offering === "HIDDEN" ? `Hidden at ${loc.name}` : `Listed at ${loc.name}`,
  });
  const modes = modesFor(v);
  layers.push({
    layer: "Order types", ok: modes.length === MODES_DEFAULT.length, view: "menus",
    line: modes.length === MODES_DEFAULT.length
      ? "Delivery, pickup and hall"
      : `${modes.map((m) => MODE_LABEL[m]).join(" and ")} only`,
  });
  layers.push({
    layer: "Availability", ok: !row.stopped, view: "availability",
    line: row.stopped && mv
      ? `On stop since ${mv.at.slice(11, 16)}, ${ACTORS[mv.actorId].name} — ${REASON_LABEL[mv.reason]}`
      : row.unstocked ? "No stock record at this branch"
      : "Available",
  });
  layers.push({
    layer: "Branch", ok: loc.mode !== "FORCE_CLOSED", view: "availability",
    line: loc.mode === "FORCE_CLOSED"
      ? `Branch closed until ${loc.closedUntil.slice(11, 16)} — ${loc.closedReason}`
      : "Branch open",
  });
  layers.push({
    layer: "Channel", ok: ch.status === "ACTIVE", view: "publication",
    line: ch.status === "ACTIVE" ? `Sold on ${ch.name}` : `Not sold on ${ch.name}`,
  });
  layers.push({
    layer: "Price", ok: v.priceMinor != null, view: "products",
    line: v.priceMinor != null ? "Active price in the brand book" : "No active price",
  });
  return layers;
}

/* ── publications ──────────────────────────────────────────────────────────
 * catalog.publications. The Uzum Tezkor row is deliberately absent: a channel
 * with no live publication shows customers nothing at all, which is the most
 * consequential state on the publication screen and must not be a subtle grey.
 */
export const LIVE_PUBLICATIONS = {
  "ch-web": { hash: "a91f2c17", activatedAt: "2026-08-20T11:04:00", createdBy: "u-nigora", items: 148, draftDiffers: true },
  "ch-tg": { hash: "a91f2c17", activatedAt: "2026-08-20T11:05:00", createdBy: "u-nigora", items: 148, draftDiffers: true },
  "ch-cc": { hash: "5c0dd84b", activatedAt: "2026-08-14T09:30:00", createdBy: "u-nigora", items: 141, draftDiffers: true },
  "ch-qr": { hash: "a91f2c17", activatedAt: "2026-08-20T11:05:00", createdBy: "u-nigora", items: 148, draftDiffers: true },
  "ch-uzum": null,
  "ch-kiosk": null,
};

export const PUBLICATION_HISTORY = [
  { id: "pub-9", createdAt: "2026-08-21T10:12:00", channel: "ch-uzum", status: "REJECTED", hash: "c47b90ee", items: 148, createdBy: "u-nigora", problems: 3 },
  { id: "pub-8", createdAt: "2026-08-20T11:04:00", channel: "ch-web", status: "PUBLISHED", hash: "a91f2c17", items: 148, createdBy: "u-nigora", problems: 0 },
  { id: "pub-7", createdAt: "2026-08-20T11:05:00", channel: "ch-tg", status: "PUBLISHED", hash: "a91f2c17", items: 148, createdBy: "u-nigora", problems: 0 },
  { id: "pub-6", createdAt: "2026-08-20T11:05:00", channel: "ch-qr", status: "PUBLISHED", hash: "a91f2c17", items: 148, createdBy: "u-nigora", problems: 0 },
  { id: "pub-5", createdAt: "2026-08-14T09:30:00", channel: "ch-cc", status: "PUBLISHED", hash: "5c0dd84b", items: 141, createdBy: "u-sardor", problems: 0 },
  { id: "pub-4", createdAt: "2026-08-14T09:22:00", channel: "ch-cc", status: "REJECTED", hash: "1f33a0d2", items: 141, createdBy: "u-sardor", problems: 2 },
  { id: "pub-3", createdAt: "2026-08-02T16:40:00", channel: "ch-web", status: "RETIRED", hash: "77ac1b04", items: 133, createdBy: "u-nigora", problems: 0 },
];

/* Catalog-level warnings render as banners, not as findings rows.
 * PRICING_VALIDATION_NOT_WIRED means a check did not run, which is a different
 * and more alarming thing than a check that failed. */
export const CATALOG_WARNINGS = [
  {
    code: "FISCAL_CLASSIFICATION_NOT_ENFORCED",
    /* The count is computed, not written: a banner that disagrees with the list
     * under it teaches the operator to distrust both. */
    text: (n) => `${n} items have no ИКПУ — aggregators will reject the menu`,
    note: "Warning today; a blocker when ADR 0038 is accepted.",
  },
  {
    code: "PRICING_VALIDATION_NOT_WIRED",
    text: () => "Price validation did not run",
    note: "Not a check that failed — a check that did not happen. The validator emits it on every report for that reason.",
  },
];

/* ── capability sets ───────────────────────────────────────────────────────
 * The prototype has no session, so the capability set is a control. In the
 * product it comes from iam Capability and the landing follows from it: an
 * actor with only OFFERING_MANAGE / INVENTORY_ADJUST never sees the authoring
 * tree at all, and lands on the stop list.
 */
export const ROLES = [
  {
    id: "service",
    label: "Line cook / shift",
    caps: ["CATALOG_READ", "INVENTORY_READ", "INVENTORY_ADJUST", "OFFERING_MANAGE"],
    landing: "availability",
    views: ["availability"],
    note: "Holds inventory.adjust and offering.manage, not catalog.author. Lands on the stop list and never sees the authoring tree.",
  },
  {
    id: "manager",
    label: "Manager",
    caps: ["CATALOG_READ", "CATALOG_AUTHOR", "CATALOG_PUBLISH", "PRICING_READ", "OFFERING_MANAGE", "INVENTORY_ADJUST", "MEDIA_UPLOAD"],
    landing: "products",
    views: ["products", "menus", "availability", "publication"],
    note: "Holds catalog.author and catalog.publish. Lands on the product library.",
  },
  {
    id: "reader",
    label: "Dispatcher (read only)",
    caps: ["CATALOG_READ", "INVENTORY_READ"],
    landing: "products",
    views: ["products", "menus", "availability", "publication"],
    note: "catalog.read without catalog.author or inventory.adjust: the same screens with no write affordances. Affordances are omitted, not disabled.",
  },
];

/* Things this section names as absent, so a builder does not improvise them.
 * Rendered on screen where the view would otherwise silently lack them. */
export const NOT_BUILT = {
  channelPlane: "Per-channel item enablement (offered_on_channel) — ADR 0036. V0020 built the channels, not the item table.",
  menuEntity: "A named Menu entity bound to branches, and copy-menu with it — ADR 0016 amendment.",
  itemSchedule: "Per-item sale schedule (sales_schedule_id on location_offerings) — ADR 0036.",
  countedStock: "Counted per-item stock with a daily reset — ADR 0017 QUANTITY mode plus an unowned scheduled seed.",
  stopSource: "Stop scope and stop source (operator / POS terminal / rule) — ADR 0017 + ADR 0041.",
  aggregatorPreview: "Aggregator menu preview and per-marketplace pre-publication checks — ADR 0040.",
  fiscal: "marking_required, excisable, age_restriction_years, classified_by and the ИКПУ reference list — ADR 0038 (Proposed).",
};
