/* Fixtures for Staff and access (operations spec §9).
 *
 * Authored backwards from the states the screen must show. The awkward cases are
 * here on purpose, because they are the ones that produce support calls:
 *
 *   · access revoked while the account still works — she can sign in and see
 *     nothing, which is the only genuinely urgent thing this screen can say
 *   · an account switched off while the jobs are still attached
 *   · a temporary job expiring in four days that nobody has noticed
 *   · someone invited a week ago who has never signed in
 *   · someone in the building with no job at all
 *   · a very long double-barrelled surname next to a three-letter first name
 *   · a person working at two branches, who must appear under both
 *   · a branch force-closed mid-service, whose team is still listed
 *   · the owner, whose job the signed-in administrator cannot confer and
 *     therefore cannot touch
 *
 * Vocabulary rule, from the spec's §0 and non-negotiable: nowhere in this file
 * or in the screen do the words capability, scope, grant, principal or role
 * appear in anything a user reads. The model keeps its names in the ids and in
 * the gap notes, where only engineers look. On screen it is a job, given
 * somewhere.
 *
 * Money is whole som. Times are ISO strings. Free text a human typed is in the
 * language they typed it in; the console chrome around it is English, as in
 * every other screen of this prototype.
 */

/* ── where the company is ──────────────────────────────────────────────────*/

export const BRANDS = [
  { id: "br-osh", name: "Osh Markazi" },
  { id: "br-milliy", name: "Milliy Taomlar" },
];

export const LOCATIONS = [
  { id: "lo-chilonzor", brandId: "br-osh", name: "Chilonzor", street: "Bunyodkor shoh ko'chasi 12" },
  { id: "lo-yunusobod", brandId: "br-osh", name: "Yunusobod", street: "Amir Temur ko'chasi 108" },
  { id: "lo-sergeli", brandId: "br-milliy", name: "Sergeli", street: "Yangi Sergeli 4-mavze",
    forceClosed: { since: "2026-08-21T19:05:00", reason: "Suv yo'q — sanepidemiologiya talabi" } },
  { id: "lo-mirobod", brandId: "br-milliy", name: "Mirobod", street: "Shota Rustaveli ko'chasi 41" },
];

/* Modules the tenant's plan actually includes. Entitlement is not permission:
 * ADR 0025 keeps ENTITLEMENT_REQUIRED and INSUFFICIENT_CAPABILITY as separate
 * codes precisely so the access check can answer "not your plan" without
 * implying "not your job". Nothing computes this server-side yet — §11.10. */
export const MODULES = {
  "br-osh": { couriers: true, reports: true },
  "br-milliy": { couriers: false, reports: true },
};

/* ── what a job reaches ────────────────────────────────────────────────────
 * One line per thing a person can do, written as an action and never as a place
 * in the interface. «Cancel orders», never «the Orders section» — the whole
 * reason to prefer this model over a menu-visibility grid is that it can answer
 * "what will happen if I give her this?".
 *
 * `asked` orders the "will not be able to" list: money first, then the menu,
 * then everyone else's branch. That is the order managers actually hesitate in.
 */

export const CAPABILITIES = {
  "order.read":       { area: "Orders",   says: "See the branch's orders",             asked: 90 },
  "order.approve":    { area: "Orders",   says: "Accept orders",                       asked: 80 },
  "order.cancel":     { area: "Orders",   says: "Cancel orders",                       asked: 40 },
  "order.edit":       { area: "Orders",   says: "Change an order after it is placed",  asked: 45 },
  "catalog.read":     { area: "Menu",     says: "See the menu",                        asked: 95 },
  "catalog.stop":     { area: "Menu",     says: "Put a dish on stop",                  asked: 60 },
  "catalog.price":    { area: "Menu",     says: "Change prices",                       asked: 12 },
  "catalog.publish":  { area: "Menu",     says: "Change the menu",                     asked: 14 },
  "courier.assign":   { area: "Couriers", says: "Assign couriers to orders",           asked: 55 },
  "courier.shift":    { area: "Couriers", says: "Open and close courier shifts",       asked: 58 },
  "courier.manage":   { area: "Couriers", says: "Add and remove couriers",             asked: 50 },
  "payment.read":     { area: "Money",    says: "See what has been paid",              asked: 30 },
  "payment.refund":   { area: "Money",    says: "Give money back",                     asked: 2 },
  "discount.grant":   { area: "Money",    says: "Give a discount off the price list",  asked: 6 },
  "report.location":  { area: "Reports",  says: "See this branch's numbers",           asked: 35 },
  "report.tenant":    { area: "Reports",  says: "See the whole company's numbers",     asked: 20 },
  "iam.invite":       { area: "People",   says: "Invite a new person",                 asked: 24 },
  "iam.grant.manage": { area: "People",   says: "Change what people may do",           asked: 22 },
  "audit.read":       { area: "People",   says: "Read the activity log",               asked: 26 },
};

export const AREA_ORDER = ["Orders", "Menu", "Couriers", "Money", "Reports", "People"];

/* ── the jobs ──────────────────────────────────────────────────────────────
 * Eight, code-owned, read-only in v1. `platform-admin` and `platform-support`
 * are absent and must stay absent: the server refuses them outright, and a
 * picker that lists what the server will refuse teaches a manager that the
 * software is taunting her.
 *
 * Tenant-defined jobs are the trigger for the role × capability grid the spec
 * declines to build now — §11.4, deferred by ADR 0025.
 */

const ALL = Object.keys(CAPABILITIES);

export const JOBS = [
  {
    code: "tenant-owner", name: "Owner", level: "company",
    blurb: "Everything, everywhere. Only the owner can give money back and change what other people may do.",
    caps: ALL,
  },
  {
    code: "tenant-admin", name: "Administrator", level: "company",
    blurb: "Runs the company day to day. Cannot touch money that has already been taken.",
    caps: ALL.filter((c) => !["payment.refund", "discount.grant"].includes(c)),
  },
  {
    code: "tenant-finance", name: "Finance", level: "company",
    blurb: "Money only: what came in, what went back, and the company's numbers.",
    caps: ["order.read", "payment.read", "payment.refund", "discount.grant", "report.location", "report.tenant"],
  },
  {
    code: "support-agent", name: "Support operator", level: "company",
    blurb: "Answers customers. Can fix and cancel an order, and cannot touch money or the menu.",
    caps: ["order.read", "order.cancel", "order.edit", "catalog.read", "payment.read", "audit.read"],
  },
  {
    code: "brand-manager", name: "Brand manager", level: "brand",
    blurb: "Runs every branch of one brand, including its menu and its prices.",
    caps: ["order.read", "order.approve", "order.cancel", "order.edit", "catalog.read", "catalog.stop",
      "catalog.price", "catalog.publish", "courier.assign", "report.location", "iam.invite", "iam.grant.manage"],
  },
  {
    code: "courier-dispatcher", name: "Dispatcher", level: "brand",
    blurb: "Couriers and deliveries for one brand. Sees orders, does not take them.",
    caps: ["order.read", "courier.assign", "courier.shift", "courier.manage"],
  },
  {
    code: "location-manager", name: "Branch manager", level: "branch",
    blurb: "Runs one branch: its orders, its kitchen, its couriers and its numbers.",
    caps: ["order.read", "order.approve", "order.cancel", "order.edit", "catalog.read", "catalog.stop",
      "courier.assign", "report.location"],
  },
  {
    code: "location-staff", name: "Branch staff", level: "branch",
    blurb: "Takes orders at one branch and stops a dish when it runs out.",
    caps: ["order.read", "order.approve", "catalog.read", "catalog.stop"],
  },
];

/* ── people ────────────────────────────────────────────────────────────────
 * `subject` is the Keycloak subject id. It is a support artefact and appears in
 * exactly one place on screen: behind the Security tab, with a copy button.
 *
 * Everything else about a person here — name, phone, photo, last sign-in,
 * orders taken today — is invented, because none of it exists in the backend.
 * See GAPS below; this is the single largest hole in the section.
 */

export const ACTOR_ID = "p-aziza";

const g = (id, jobCode, scopeType, scopeId, extra = {}) => ({
  id, jobCode, scopeType, scopeId,
  validFrom: "2026-03-12", validUntil: null, grantedBy: "p-sanjar",
  reason: "Ishga qabul qilindi", status: "ACTIVE", ...extra,
});

export const PEOPLE = [
  {
    id: "p-sanjar", name: "Sanjar Mahmudov", phone: "+998901120044",
    subject: "8c41f2a0-7d31-4e19-9a55-1f0e2b7c9d33",
    lastSignInAt: "2026-08-21T09:12:00", ordersToday: null, accountEnabled: true,
    assignments: [g("as-1", "tenant-owner", "company", null, { validFrom: "2024-11-02", reason: "Kompaniya asoschisi" })],
  },
  {
    id: "p-aziza", name: "Aziza Karimova", phone: "+998935567712",
    subject: "b7d0c113-5a4e-4c88-b21d-6e93aa47f501",
    lastSignInAt: "2026-08-21T18:40:00", ordersToday: 3, accountEnabled: true,
    assignments: [g("as-2", "tenant-admin", "company", null, { validFrom: "2025-02-01", grantedBy: "p-sanjar", reason: "Ma'muriyatga o'tkazildi" })],
  },
  {
    /* Two branches, one person. She must appear under both branch groups, each
     * time showing only the job that belongs to that group. */
    id: "p-nilufar", name: "Nilufar Abdurahmonova-Yo'ldosheva", phone: "+998907741203",
    subject: "3f5b9e27-11ca-4f7a-8d02-c4a1b60e8877",
    lastSignInAt: "2026-08-21T17:22:00", ordersToday: 12, accountEnabled: true,
    assignments: [
      g("as-3", "location-manager", "branch", "lo-chilonzor", { validFrom: "2026-03-12" }),
      g("as-4", "location-manager", "branch", "lo-yunusobod", {
        validFrom: "2026-07-01", grantedBy: "p-aziza",
        reason: "Yunusobodda boshqaruvchi yo'q, vaqtincha ikkalasini olib boradi",
      }),
    ],
  },
  {
    /* Account switched off, jobs still attached. The two halves of the model
     * have diverged and only this screen can say so. */
    id: "p-bekzod", name: "Bekzod Tursunov", phone: "+998946620183",
    subject: "d21c7740-9b6f-4a03-92e8-55f7c0b1e214",
    lastSignInAt: "2026-08-19T21:04:00", ordersToday: 0, accountEnabled: false,
    disabledReason: "Kassadagi kamomad tekshirilmoqda",
    disabledAt: "2026-08-20T10:15:00",
    assignments: [g("as-5", "location-staff", "branch", "lo-chilonzor", { validFrom: "2025-09-14" })],
  },
  {
    /* The opposite divergence, and the worse one: no access left, account still
     * works. She signed in yesterday and saw an empty console. */
    id: "p-nodira", name: "Nodira Saidova", phone: "+998903341276",
    subject: "5e8a1cc9-4d72-4b16-a7f3-9c0d2e5b6a90",
    lastSignInAt: "2026-08-20T08:31:00", ordersToday: null, accountEnabled: true,
    assignments: [g("as-6", "location-manager", "branch", "lo-yunusobod", {
      validFrom: "2025-05-06", status: "REVOKED", revokedAt: "2026-08-14T11:02:00",
      revokedBy: "p-aziza", revokedReason: "Dekret ta'tiliga chiqdi",
    })],
  },
  {
    /* Time-bounded, and the expiry is four days away. A temporary job whose end
     * is a surprise is worse than no temporary job. */
    id: "p-shohruh", name: "Shohruh Qodirov", phone: "+998977712095",
    subject: "9ab34f61-2e0d-4c55-8f19-73b6ca02d1e5",
    lastSignInAt: "2026-08-21T16:05:00", ordersToday: null, accountEnabled: true,
    assignments: [g("as-7", "courier-dispatcher", "brand", "br-osh", {
      validFrom: "2026-08-04", validUntil: "2026-08-25", grantedBy: "p-aziza",
      reason: "Ta'til davriga vaqtincha dispecher",
    })],
  },
  {
    /* Invited a week ago, never signed in. The invitation was probably read out
     * over the phone and written down wrong. */
    id: "p-malika", name: "Malika Nazarova", phone: "+998912057714",
    subject: "c07e5b32-8a44-4d90-b6c1-0f2e39a7dd48",
    lastSignInAt: null, invitedAt: "2026-08-15T12:40:00", ordersToday: null, accountEnabled: true,
    assignments: [g("as-8", "location-staff", "branch", "lo-sergeli", {
      validFrom: "2026-08-15", grantedBy: "p-aziza", reason: "Yangi ofitsiant",
    })],
  },
  {
    /* Short name beside the long one, and no job at all: never had one, which is
     * a different situation with a different fix from having had one taken. */
    id: "p-ali", name: "Ali Rasulov", phone: "+998909988771",
    subject: "1d4f8ae5-6c23-4b71-90ad-e8c5f7302b16",
    lastSignInAt: "2026-08-20T14:09:00", ordersToday: null, accountEnabled: true,
    assignments: [],
  },
  {
    id: "p-jasur", name: "Jasur Ismoilov", phone: "+998919876543",
    subject: "6b2d90fe-3c17-42a8-85be-1d7f4a09cc62",
    lastSignInAt: "2026-08-21T19:02:00", ordersToday: 34, accountEnabled: true,
    assignments: [g("as-9", "location-staff", "branch", "lo-chilonzor", { validFrom: "2025-01-20" })],
  },
  {
    id: "p-kamola", name: "Kamola Ergasheva", phone: "+998908846205",
    subject: "af13c8d0-59e6-4f22-b704-2ac6ed915b73",
    lastSignInAt: "2026-08-21T18:55:00", ordersToday: 21, accountEnabled: true,
    assignments: [g("as-10", "location-staff", "branch", "lo-yunusobod", { validFrom: "2025-11-03" })],
  },
  {
    id: "p-dilshod", name: "Dilshod Qurbonov", phone: "+998934421890",
    subject: "72e0b6a4-8d15-4c39-a0f6-b3719ce2440d",
    lastSignInAt: "2026-08-21T15:48:00", ordersToday: null, accountEnabled: true,
    assignments: [g("as-11", "courier-dispatcher", "brand", "br-osh", { validFrom: "2025-06-18" })],
  },
  {
    id: "p-zulfiya", name: "Zulfiya Toshmatova", phone: "+998901234567",
    subject: "e5c27091-4a6b-4d83-9127-fa0e63b8d215",
    lastSignInAt: "2026-08-12T11:30:00", ordersToday: null, accountEnabled: true,
    assignments: [g("as-12", "support-agent", "company", null, { validFrom: "2025-08-01" })],
  },
  {
    /* Runs the branch that was force-closed forty minutes ago. Nothing about her
     * access is wrong; the row is normal and the branch header is not. */
    id: "p-oybek", name: "Oybek Rahmatov", phone: "+998971140266",
    subject: "0a9d3f77-2b58-4e61-83c4-6d1e0b95af38",
    lastSignInAt: "2026-08-21T19:11:00", ordersToday: 0, accountEnabled: true,
    assignments: [g("as-13", "location-manager", "branch", "lo-sergeli", { validFrom: "2026-01-15" })],
  },
];

/* ── the activity log, person-scoped ───────────────────────────────────────
 * ADR 0027's actor types are the point of this table: a person gets a name and a
 * link, a scheduled job gets its own name, a data import gets a plainly
 * different treatment, and a background path that acted for someone says whose
 * behalf it acted on. Never "server".
 *
 * `bulkOf` is the correlation id chip: a bulk action produces N audited records,
 * not one, and each one has to answer "was Aziza in that batch?".
 */

export const EVENTS = [
  { id: "ev-1", at: "2026-08-21T18:44:12", actorType: "person", actorId: "p-aziza",
    what: "Suspended Bekzod Tursunov's access", where: "Chilonzor", targetType: "person",
    targetId: "p-bekzod", outcome: "done", reason: "Kassadagi kamomad tekshirilmoqda",
    bulkOf: { id: "co-8841", count: 2 } },
  { id: "ev-2", at: "2026-08-21T18:44:12", actorType: "person", actorId: "p-aziza",
    what: "Suspended Ali Rasulov's access", where: "Chilonzor", targetType: "person",
    targetId: "p-ali", outcome: "refused", reason: "Nothing to suspend — no job to take away",
    bulkOf: { id: "co-8841", count: 2 } },
  { id: "ev-3", at: "2026-08-21T18:31:55", actorType: "person", actorId: "p-aziza",
    what: "Tried to make Nilufar Abdurahmonova-Yo'ldosheva the owner", where: "Whole company",
    targetType: "person", targetId: "p-nilufar", outcome: "refused",
    reason: "You cannot give a job that reaches further than your own" },
  { id: "ev-4", at: "2026-08-21T17:20:09", actorType: "person", actorId: "p-nilufar",
    what: "Put Lo'la kabob on stop", where: "Chilonzor", targetType: "dish", targetId: "m-6",
    outcome: "done", reason: "Qiyma tugadi" },
  { id: "ev-5", at: "2026-08-21T16:58:41", actorType: "person", actorId: "p-nilufar",
    what: "Cancelled order 4815", where: "Chilonzor", targetType: "order", targetId: "QO-4815",
    outcome: "done", reason: "Item unavailable — qiyma tugadi" },
  { id: "ev-6", at: "2026-08-21T16:59:03", actorType: "person", actorId: "p-sanjar",
    what: "Gave money back on order 4815", where: "Chilonzor", targetType: "order",
    targetId: "QO-4815", outcome: "done", reason: "Bekor qilingan buyurtma", amountMinor: 91_000 },
  { id: "ev-7", at: "2026-08-21T16:12:00", actorType: "person", actorId: "p-shohruh",
    what: "Ended Shoxrux Qodirov's courier shift while order 4817 was still out",
    where: "Osh Markazi", targetType: "courier", targetId: "cr-4", outcome: "done",
    reason: "Mototsikl buzildi, buyurtma boshqasiga berildi" },
  { id: "ev-8", at: "2026-08-21T12:04:30", actorType: "system", actorId: null,
    actorLabel: "Nightly reconciliation", onBehalfOf: "p-aziza",
    what: "Closed 4 courier shifts left open overnight", where: "Whole company",
    targetType: "shift", targetId: "batch-0821", outcome: "done",
    reason: "Smena yopilmagan — avtomatik yopildi" },
  { id: "ev-9", at: "2026-08-21T09:40:18", actorType: "person", actorId: "p-aziza",
    what: "Gave Nilufar Abdurahmonova-Yo'ldosheva the branch manager job in Yunusobod",
    where: "Yunusobod", targetType: "person", targetId: "p-nilufar", outcome: "done",
    reason: "Yunusobodda boshqaruvchi yo'q, vaqtincha ikkalasini olib boradi" },
  { id: "ev-10", at: "2026-08-20T10:15:44", actorType: "person", actorId: "p-aziza",
    what: "Switched off Bekzod Tursunov's account", where: "Chilonzor", targetType: "person",
    targetId: "p-bekzod", outcome: "done", reason: "Kassadagi kamomad tekshirilmoqda" },
  { id: "ev-11", at: "2026-08-19T08:02:11", actorType: "person", actorId: "p-nilufar",
    what: "Asked to give a discount off the price list on order 4788", where: "Chilonzor",
    targetType: "order", targetId: "QO-4788", outcome: "waiting",
    reason: "Doimiy mijoz, kechikkan buyurtma uchun" },
  { id: "ev-12", at: "2026-08-14T11:02:07", actorType: "person", actorId: "p-aziza",
    what: "Took away Nodira Saidova's branch manager job in Yunusobod", where: "Yunusobod",
    targetType: "person", targetId: "p-nodira", outcome: "done", reason: "Dekret ta'tiliga chiqdi" },
  { id: "ev-13", at: "2026-03-12T07:15:00", actorType: "migration", actorId: null,
    actorLabel: "Import from the old dashboard", what: "Brought over 9 people and their jobs",
    where: "Whole company", targetType: "import", targetId: "run-0312", outcome: "done",
    reason: "Cutover — every manager scoped to their own branch, not the company" },
];

/* ── what the backend does not have ────────────────────────────────────────
 * Rendered on screen, verbatim, wherever the missing thing would have been. A
 * prototype that quietly invents a field teaches everyone that the field exists.
 */

export const GAPS = {
  identity: "Name, phone and photo are invented here. No table in the schema holds a staff person's profile — ADR 0009's principal record is specified and unbuilt, and the profile itself has no ADR yet (§11.1).",
  lastSignIn: "Last sign-in lives in Keycloak and nothing projects it (§11.6, ADR 0009).",
  ordersToday: "Orders have no author. `created_by_actor_id` is specified by ADR 0039 and absent from the table, so this count is invented (§11.5).",
  disable: "Switching an account off is not built. Taking away every job is not the same thing, and this screen does not pretend it is (§11.3, ADR 0009).",
  history: "Only active jobs are readable. Revoked ones stay in the table and no read path returns them (§11.2, ADR 0025).",
  bulk: "There is no bulk endpoint. This is N separate calls with N keys, and a per-row result (§11.8, ADR 0039).",
  security: "Sign-in method, sessions and terminal PIN are all Keycloak or unbuilt (§11.6, §11.7, §11.9).",
  entitlement: "Nothing computes what the plan includes. The module list here is a fixture (§11.10, ADR 0021).",
  diff: "The before-and-after of each change is stored but unreachable — no single-event endpoint exists (§11.13, ADR 0027).",
  enforce: "Enforcement is still in shadow mode. Every refusal shown here is computed and logged, and nothing is actually blocked yet (§11.14).",
};

/* The rule, printed verbatim where a manager is about to need it: the empty
 * state of the list, and under the job picker on the invite form. It is the only
 * place the model is explained, and it never uses the model's words. */
export const RULE =
  "A person has one or more jobs, and each job is given somewhere — the whole company, one brand, or one branch. " +
  "A job given at one branch works only at that branch.";
