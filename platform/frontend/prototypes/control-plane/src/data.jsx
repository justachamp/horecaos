/* Fixture data for the control-plane prototype.
 *
 * This console is for the people who run Qoida as a business: who the customers
 * are, how they were brought on, what they pay, what they use, and how the
 * platform is configured for them. It is not an engineering console. Nothing
 * here names an internal decision record, a migration, or a code module, because
 * the person using this screen does not have those and should not need them.
 *
 * Authentic Uzbek and Russian content, not machine-translated placeholder — a
 * fixture of "Tenant 1, Tenant 2" hides every layout problem a real name exposes.
 * Money is whole som. Timestamps are ISO; components format them 24h and DD.MM.
 *
 * Fixtures are authored backwards from the visual states each screen must show,
 * so the ugly cases are present on purpose: a tenant with one location beside one
 * with forty-one, a name of three characters beside one of thirty, an invoice
 * sixty-two days overdue, and a churned account.
 */

/* ── tenants ───────────────────────────────────────────────────────────────*/

export const TENANTS = [
  {
    id: "t-osh", slug: "osh-markazi", displayName: "Osh Markazi",
    legalName: "Osh Markazi MChJ", inn: "302847195",
    status: "ACTIVE", lifecycle: "LIVE",
    plan: "Growth", brands: 2, locations: 5,
    joinedAt: "2026-03-14T09:12:00", owner: "Dilnoza Rahimova",
    ownerPhone: "+998 90 123-45-67", city: "Toshkent",
    ordersLast30: 4_182, gmvLast30Minor: 512_400_000, health: "healthy",
  },
  {
    id: "t-non", slug: "non-uyi", displayName: "Non uyi",
    legalName: "Non Uyi XK", inn: "301558402",
    status: "ACTIVE", lifecycle: "LIVE",
    plan: "Network", brands: 3, locations: 41,
    joinedAt: "2026-01-08T11:40:00", owner: "Sardor Yo'ldoshev",
    ownerPhone: "+998 93 442-18-90", city: "Toshkent",
    ordersLast30: 19_640, gmvLast30Minor: 1_884_900_000, health: "healthy",
  },
  {
    id: "t-choy", slug: "choyxona", displayName: "Choyxona №1",
    legalName: "Choyxona Bir MChJ", inn: null,
    status: "PENDING", lifecycle: "ONBOARDING",
    plan: "Basic", brands: 1, locations: 1,
    joinedAt: "2026-08-19T16:05:00", owner: "Aziz Tursunov",
    ownerPhone: "+998 94 771-30-22", city: "Samarqand",
    ordersLast30: 0, gmvLast30Minor: 0, health: "unknown",
  },
  {
    id: "t-shirin", slug: "shirinliklar", displayName: "Shirinliklar",
    legalName: "Shirin Savdo MChJ", inn: "304112877",
    status: "SUSPENDED", lifecycle: "LIVE",
    plan: "Basic", brands: 1, locations: 2,
    joinedAt: "2025-11-22T08:30:00", owner: "Malika Yusupova",
    ownerPhone: "+998 91 205-77-14", city: "Buxoro",
    ordersLast30: 0, gmvLast30Minor: 0, health: "at-risk",
    suspendedReason: "Invoice unpaid for 62 days", suspendedAt: "2026-08-21T13:12:55",
  },
  {
    id: "t-laz", slug: "lazzat", displayName: "Lazzat",
    legalName: "Lazzat Servis XK", inn: "306990241",
    status: "ACTIVE", lifecycle: "LIVE",
    plan: "Growth", brands: 1, locations: 3,
    joinedAt: "2026-06-02T10:15:00", owner: "Jamshid Ergashev",
    ownerPhone: "+998 90 884-62-05", city: "Namangan",
    ordersLast30: 1_204, gmvLast30Minor: 96_300_000, health: "at-risk",
    healthNote: "Order volume down 38% against the previous 30 days",
  },
  {
    id: "t-tuz", slug: "tuz", displayName: "Tuz",
    legalName: "Tuz Kafe XK", inn: "308221950",
    status: "CLOSED", lifecycle: "OFFBOARDED",
    plan: "Basic", brands: 1, locations: 1,
    joinedAt: "2025-08-11T12:00:00", owner: "Nodira Qodirova",
    ownerPhone: "+998 97 310-45-88", city: "Toshkent",
    ordersLast30: 0, gmvLast30Minor: 0, health: "closed",
    closedAt: "2026-07-30T17:00:00", closedReason: "Business ceased trading",
  },
];

export const BRANDS = [
  { id: "b-osh", tenantId: "t-osh", name: "Osh Markazi", locations: 4, status: "ACTIVE" },
  { id: "b-tandir", tenantId: "t-osh", name: "Tandir", locations: 1, status: "ACTIVE" },
  { id: "b-non", tenantId: "t-non", name: "Non uyi", locations: 28, status: "ACTIVE" },
  { id: "b-shirin", tenantId: "t-shirin", name: "Shirinliklar", locations: 2, status: "SUSPENDED" },
];

export const LOCATIONS = [
  { id: "l-chilonzor", tenantId: "t-osh", brandId: "b-osh", name: "Chilonzor filiali", city: "Toshkent", status: "OPEN", openedAt: "2026-03-20T00:00:00" },
  { id: "l-yunusobod", tenantId: "t-osh", brandId: "b-osh", name: "Yunusobod filiali", city: "Toshkent", status: "OPEN", openedAt: "2026-04-11T00:00:00" },
  { id: "l-mirzo", tenantId: "t-osh", brandId: "b-osh", name: "Mirzo Ulug'bek filiali", city: "Toshkent", status: "OPEN", openedAt: "2026-05-02T00:00:00" },
  { id: "l-sergeli", tenantId: "t-osh", brandId: "b-osh", name: "Sergeli filiali", city: "Toshkent", status: "PREPARING", openedAt: null },
  { id: "l-tandir1", tenantId: "t-osh", brandId: "b-tandir", name: "Tandir — Amir Temur", city: "Toshkent", status: "OPEN", openedAt: "2026-06-18T00:00:00" },
];

/* ── onboarding and offboarding ────────────────────────────────────────────
 * The steps are what a Qoida account manager actually walks a restaurant
 * through. Each names the person responsible, because the commonest onboarding
 * failure is a step that is nobody's job.
 */

export const ONBOARDING = {
  tenantId: "t-choy", tenantName: "Choyxona №1",
  startedAt: "2026-08-19T16:05:00", accountManager: "Bekzod Toshmatov",
  targetLiveDate: "2026-09-02",
  steps: [
    { code: "CONTRACT",   label: "Contract signed",                owner: "Sales",           status: "DONE",     at: "2026-08-19T16:05:00" },
    { code: "ACCOUNT",    label: "Account and owner login created", owner: "Account manager", status: "DONE",     at: "2026-08-19T16:12:00" },
    { code: "BRAND",      label: "Brand and first location set up", owner: "Account manager", status: "DONE",     at: "2026-08-19T16:16:00" },
    { code: "TAX",        label: "Tax details and INN registered",  owner: "Restaurant",      status: "WAITING",  at: null, waitingOn: "Owner has not sent the INN certificate" },
    { code: "MENU",       label: "Menu loaded and photographed",    owner: "Content team",    status: "ACTIVE",   at: null, note: "46 of 46 dishes entered, 12 photographed" },
    { code: "ZONE",       label: "Delivery area and fees agreed",   owner: "Account manager", status: "PENDING",  at: null },
    { code: "PAYMENT",    label: "Click and Payme connected",       owner: "Restaurant",      status: "PENDING",  at: null },
    { code: "COURIERS",   label: "Couriers registered and trained", owner: "Operations",      status: "PENDING",  at: null },
    { code: "TRAINING",   label: "Staff trained on the console",    owner: "Account manager", status: "PENDING",  at: null },
    { code: "TEST_ORDER", label: "Test order placed end to end",    owner: "Account manager", status: "PENDING",  at: null },
    { code: "GO_LIVE",    label: "Go live",                         owner: "Account manager", status: "PENDING",  at: null },
  ],
};

export const ONBOARDING_PIPELINE = [
  { tenantId: "t-choy", tenant: "Choyxona №1", city: "Samarqand", plan: "Basic",  stage: "Menu",     daysOpen: 2,  target: "2026-09-02", manager: "Bekzod Toshmatov", stalled: false },
  { tenantId: "t-anor", tenant: "Anor",        city: "Toshkent",  plan: "Growth", stage: "Tax",      daysOpen: 11, target: "2026-08-25", manager: "Bekzod Toshmatov", stalled: true, stalledReason: "Waiting on the owner for 9 days" },
  { tenantId: "t-bek",  tenant: "Bek kabob",   city: "Andijon",   plan: "Basic",  stage: "Contract", daysOpen: 1,  target: "2026-09-15", manager: "Aziza Karimova",   stalled: false },
];

export const OFFBOARDING = [
  {
    tenantId: "t-tuz", tenant: "Tuz", requestedAt: "2026-07-14T10:00:00",
    reason: "Business ceased trading", closedAt: "2026-07-30T17:00:00",
    finalInvoiceMinor: 1_200_000, finalInvoiceSettled: true,
    dataRetentionUntil: "2027-07-30", exportDelivered: true, stage: "COMPLETE",
  },
  {
    tenantId: "t-shirin", tenant: "Shirinliklar", requestedAt: null,
    reason: "Non-payment — suspension may become closure", closedAt: null,
    finalInvoiceMinor: 3_600_000, finalInvoiceSettled: false,
    dataRetentionUntil: null, exportDelivered: false, stage: "AT_RISK",
  },
];

/* ── subscriptions and payments ────────────────────────────────────────────*/

export const PLANS = [
  { id: "p-basic",   name: "Basic",   monthlyMinor: 1_200_000, locationsIncluded: 1,  extraLocationMinor: 400_000, commissionBps: 0,   tenants: 3 },
  { id: "p-growth",  name: "Growth",  monthlyMinor: 3_500_000, locationsIncluded: 5,  extraLocationMinor: 350_000, commissionBps: 0,   tenants: 2 },
  { id: "p-network", name: "Network", monthlyMinor: 9_000_000, locationsIncluded: 20, extraLocationMinor: 250_000, commissionBps: 0,   tenants: 1 },
];

export const SUBSCRIPTIONS = [
  { id: "s-1", tenantId: "t-osh",    tenant: "Osh Markazi",  plan: "Growth",  status: "ACTIVE",    startedAt: "2026-03-14", renewsAt: "2026-09-14", monthlyMinor: 3_500_000, extraLocations: 0,  billedMinor: 3_500_000 },
  { id: "s-2", tenantId: "t-non",    tenant: "Non uyi",      plan: "Network", status: "ACTIVE",    startedAt: "2026-01-08", renewsAt: "2026-09-08", monthlyMinor: 9_000_000, extraLocations: 21, billedMinor: 14_250_000 },
  { id: "s-3", tenantId: "t-shirin", tenant: "Shirinliklar", plan: "Basic",   status: "PAST_DUE",  startedAt: "2025-11-22", renewsAt: "2026-06-22", monthlyMinor: 1_200_000, extraLocations: 1,  billedMinor: 1_600_000 },
  { id: "s-4", tenantId: "t-choy",   tenant: "Choyxona №1",  plan: "Basic",   status: "TRIAL",     startedAt: "2026-08-19", renewsAt: "2026-09-19", monthlyMinor: 0,         extraLocations: 0,  billedMinor: 0 },
  { id: "s-5", tenantId: "t-laz",    tenant: "Lazzat",       plan: "Growth",  status: "ACTIVE",    startedAt: "2026-06-02", renewsAt: "2026-09-02", monthlyMinor: 3_500_000, extraLocations: 0,  billedMinor: 3_500_000 },
  { id: "s-6", tenantId: "t-tuz",    tenant: "Tuz",          plan: "Basic",   status: "CANCELLED", startedAt: "2025-08-11", renewsAt: null,          monthlyMinor: 1_200_000, extraLocations: 0,  billedMinor: 0 },
];

export const INVOICES = [
  { id: "INV-2026-0841", tenantId: "t-non",    tenant: "Non uyi",      issuedAt: "2026-08-08", dueAt: "2026-08-22", amountMinor: 14_250_000, status: "SENT",     method: "Bank transfer", paidAt: null },
  { id: "INV-2026-0840", tenantId: "t-osh",    tenant: "Osh Markazi",  issuedAt: "2026-08-14", dueAt: "2026-08-28", amountMinor: 3_500_000,  status: "PAID",     method: "Payme",         paidAt: "2026-08-15T09:41:00" },
  { id: "INV-2026-0839", tenantId: "t-laz",    tenant: "Lazzat",       issuedAt: "2026-08-02", dueAt: "2026-08-16", amountMinor: 3_500_000,  status: "PAID",     method: "Click",         paidAt: "2026-08-16T18:22:00" },
  { id: "INV-2026-0712", tenantId: "t-shirin", tenant: "Shirinliklar", issuedAt: "2026-06-22", dueAt: "2026-06-22", amountMinor: 1_600_000,  status: "OVERDUE",  method: null,            paidAt: null, daysOverdue: 62 },
  { id: "INV-2026-0698", tenantId: "t-shirin", tenant: "Shirinliklar", issuedAt: "2026-05-22", dueAt: "2026-05-22", amountMinor: 1_600_000,  status: "OVERDUE",  method: null,            paidAt: null, daysOverdue: 93 },
  { id: "INV-2026-0655", tenantId: "t-tuz",    tenant: "Tuz",          issuedAt: "2026-07-14", dueAt: "2026-07-28", amountMinor: 1_200_000,  status: "PAID",     method: "Bank transfer", paidAt: "2026-07-29T11:05:00" },
];

/* What each tenant is charged for, so an invoice line has a defensible source. */
export const USAGE = [
  { tenantId: "t-non", tenant: "Non uyi", month: "2026-08", locations: 41, ordersProcessed: 19_640, gmvMinor: 1_884_900_000, smsSent: 18_220, storageGb: 41 },
  { tenantId: "t-osh", tenant: "Osh Markazi", month: "2026-08", locations: 5, ordersProcessed: 4_182, gmvMinor: 512_400_000, smsSent: 3_940, storageGb: 12 },
  { tenantId: "t-laz", tenant: "Lazzat", month: "2026-08", locations: 3, ordersProcessed: 1_204, gmvMinor: 96_300_000, smsSent: 1_110, storageGb: 5 },
];

/* ── platform statistics ───────────────────────────────────────────────────
 * Fourteen days, so a chart has something to be shaped like. Weekends are
 * visibly higher, which is what this business actually looks like.
 */

export const DAILY = [
  { date: "2026-08-08", orders: 742, gmvMinor: 78_200_000, activeTenants: 4 },
  { date: "2026-08-09", orders: 981, gmvMinor: 104_600_000, activeTenants: 4 },
  { date: "2026-08-10", orders: 1_012, gmvMinor: 108_900_000, activeTenants: 4 },
  { date: "2026-08-11", orders: 688, gmvMinor: 71_400_000, activeTenants: 4 },
  { date: "2026-08-12", orders: 705, gmvMinor: 74_100_000, activeTenants: 4 },
  { date: "2026-08-13", orders: 726, gmvMinor: 76_800_000, activeTenants: 4 },
  { date: "2026-08-14", orders: 758, gmvMinor: 80_500_000, activeTenants: 4 },
  { date: "2026-08-15", orders: 1_004, gmvMinor: 110_200_000, activeTenants: 4 },
  { date: "2026-08-16", orders: 1_048, gmvMinor: 114_700_000, activeTenants: 4 },
  { date: "2026-08-17", orders: 712, gmvMinor: 74_900_000, activeTenants: 4 },
  { date: "2026-08-18", orders: 734, gmvMinor: 77_600_000, activeTenants: 4 },
  { date: "2026-08-19", orders: 749, gmvMinor: 79_300_000, activeTenants: 4 },
  { date: "2026-08-20", orders: 781, gmvMinor: 82_900_000, activeTenants: 3 },
  { date: "2026-08-21", orders: 620, gmvMinor: 66_100_000, activeTenants: 3 },
];

export const TENANT_LEAGUE = [
  { tenantId: "t-non", tenant: "Non uyi",      orders: 19_640, gmvMinor: 1_884_900_000, avgBasketMinor: 95_970, changePct: 12 },
  { tenantId: "t-osh", tenant: "Osh Markazi",  orders: 4_182,  gmvMinor: 512_400_000,   avgBasketMinor: 122_524, changePct: 4 },
  { tenantId: "t-laz", tenant: "Lazzat",       orders: 1_204,  gmvMinor: 96_300_000,    avgBasketMinor: 79_983, changePct: -38 },
];

export const KPIS = {
  tenantsLive: 3,
  tenantsOnboarding: 3,
  tenantsAtRisk: 2,
  mrrMinor: 24_850_000,
  arrearsMinor: 3_200_000,
  ordersLast30: 25_026,
  gmvLast30Minor: 2_493_600_000,
  churnedLast90: 1,
};

/* ── platform configuration ────────────────────────────────────────────────
 * What a platform administrator sets once and rarely touches. Reference data
 * every tenant inherits, and the switches that decide what a plan may do.
 */

export const CONFIG = {
  locales: [
    { code: "uz", label: "O'zbekcha (lotin)", isDefault: true, coverage: 100 },
    { code: "ru", label: "Русский", isDefault: false, coverage: 100 },
    { code: "en", label: "English", isDefault: false, coverage: 74 },
  ],
  currency: { code: "UZS", symbol: "so'm", decimals: 0, grouping: "space" },
  vatRateBps: 1200,
  cities: [
    { code: "TAS", name: "Toshkent", tenants: 4, active: true },
    { code: "SAM", name: "Samarqand", tenants: 1, active: true },
    { code: "BUX", name: "Buxoro", tenants: 1, active: true },
    { code: "NAM", name: "Namangan", tenants: 1, active: true },
    { code: "AND", name: "Andijon", tenants: 0, active: false },
  ],
  paymentProviders: [
    { code: "PAYME", name: "Payme", status: "ACTIVE", tenants: 4, feeBps: 150 },
    { code: "CLICK", name: "Click", status: "ACTIVE", tenants: 3, feeBps: 170 },
    { code: "CASH", name: "Cash on delivery", status: "ACTIVE", tenants: 5, feeBps: 0 },
    { code: "UZUM", name: "Uzum Bank", status: "PLANNED", tenants: 0, feeBps: null },
  ],
  deliveryPartners: [
    { code: "NOOR", name: "Noor Delivery", status: "ACTIVE", tenants: 3, cities: ["Toshkent"] },
    { code: "YANDEX", name: "Yandex Delivery", status: "ACTIVE", tenants: 2, cities: ["Toshkent", "Samarqand"] },
  ],
  supportHours: "09:00 – 22:00, every day",
};

/* ── platform staff and their access ───────────────────────────────────────*/

export const STAFF = [
  { id: "u-1", name: "Aziza Karimova",  email: "aziza.k@qoida.uz",  role: "Platform administrator", tenants: "All", lastActive: "2026-08-21T14:02:00", status: "ACTIVE" },
  { id: "u-2", name: "Bekzod Toshmatov", email: "bekzod.t@qoida.uz", role: "Account manager",       tenants: "6 assigned", lastActive: "2026-08-21T13:44:00", status: "ACTIVE" },
  { id: "u-3", name: "Nilufar Sobirova", email: "nilufar.s@qoida.uz", role: "Support agent",        tenants: "All (read)", lastActive: "2026-08-21T11:20:00", status: "ACTIVE" },
  { id: "u-4", name: "Rustam Aliyev",    email: "rustam.a@qoida.uz",  role: "Finance",              tenants: "All (billing)", lastActive: "2026-08-20T17:55:00", status: "ACTIVE" },
  { id: "u-5", name: "Kamola Nazarova",  email: "kamola.n@qoida.uz",  role: "Support agent",        tenants: "All (read)", lastActive: "2026-05-02T09:10:00", status: "DISABLED" },
];

export const ACTIVITY = [
  { id: "a-1", at: "2026-08-21T13:44:02", actor: "Nilufar Sobirova", action: "Viewed a customer's contact details", tenant: "Osh Markazi", note: "Support ticket #4821" },
  { id: "a-2", at: "2026-08-21T13:12:55", actor: "Aziza Karimova",   action: "Suspended a tenant",                  tenant: "Shirinliklar", note: "Invoice unpaid for 62 days" },
  { id: "a-3", at: "2026-08-21T10:30:11", actor: "Rustam Aliyev",    action: "Issued an invoice",                   tenant: "Non uyi", note: "INV-2026-0841 · 14 250 000 so'm" },
  { id: "a-4", at: "2026-08-20T17:55:10", actor: "Aziza Karimova",   action: "Changed a plan",                      tenant: "Lazzat", note: "Basic → Growth" },
  { id: "a-5", at: "2026-08-19T16:05:00", actor: "Bekzod Toshmatov", action: "Started onboarding",                  tenant: "Choyxona №1", note: "Target go-live 02.09" },
];

export const MENU = [
  { id: "overview",     label: "Overview" },
  { id: "tenants",      label: "Tenants" },
  { id: "onboarding",   label: "Onboarding" },
  { id: "subscriptions", label: "Subscriptions" },
  { id: "payments",     label: "Payments" },
  { id: "statistics",   label: "Statistics" },
  { id: "config",       label: "Platform configuration" },
  { id: "staff",        label: "Staff and access" },
];
