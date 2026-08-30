# The founding review: what Qoida built, and why HorecaOS starts from it

Date: 2026-08-30. This is the review that founded this repository. It covers the legacy
MVP (`milliy`), the Qoida platform backend, its documentation and governance system, and
its frontends — and it names, without varnish, why the migration went differently than
planned. Read it before proposing a new module; the failure mode it describes is the one
this repository exists to avoid repeating.

Sources reviewed: `../Qoida/milliy` (legacy), `../Qoida/qoida-platform` (imported here as
[`platform/`](../platform)), the four frontend working trees (imported as
[`frontend/`](../frontend) and [`mobile/`](../mobile)), `../Qoida/qoida-dashboard` and
`../Qoida/qoida-storefront-jizbiz` (legacy/superseded — not imported), and the two iOS
projects (not imported).

---

## 1. The legacy MVP proved the business, not the software

`milliy` is a FastAPI/Python 3.9 monolith (~21k LOC, 64 tables) that genuinely shipped:
real domains (`rayhonmilliy.uz`, `mar-mar.uz`), Click payments with fiscal (OFD)
receipts, FCM push across three audiences, SMS OTP, six live branches with per-km
delivery tariffs, and a feature-complete SwiftUI iOS app. 76% of its 700 commits landed
in two months of 2024; since then it has been on life support (~5 maintenance commits a
month), and the iOS app froze 14 months before the backend did.

What it teaches, and what condemned it:

- **Multi-tenancy by accretion.** Rayhon, Marmar, and JizBiz were added by editing
  `config.py`, not by data. The last legacy commit is literally "Add JizBiz SMS
  configuration". This is the single strongest argument for the tenant-first data model
  the platform was redesigned around.
- **Zero tests.** 245 Python files, pytest installed, not one test file.
- **Secrets in git.** SMS API keys, a Sentry DSN, a Telegram bot token, an OTP bypass
  list, hardcoded Swagger credentials, and bcrypt hashes plus live-looking JWTs in
  `content/prod.sql`. **These credentials must be rotated before any cutover work** —
  that was Phase 1 of the migration plan and it was never executed.
- **Schema evolution bypassed migrations.** 15 Alembic revisions against 64 tables; the
  rest arrived by hand.
- Payme exists as an enum value and an empty file. The delivery/courier model, UI-driven
  home screen (`ui_elements`), favourites, ratings, FAQs — all real shipped behavior that
  the coverage register tracks so it cannot silently disappear.

The legacy iOS app (`milliy-ios`, ~9k LOC SwiftUI, three localizations) is polished,
frozen since 2024-06, and almost certainly drifted from current API behavior.
`RayhonMilliy/` is an empty ten-day-old Xcode template — a rewrite placeholder that was
never started; it can be deleted.

## 2. The platform backend is strong — stronger than its own README says

The imported backend (`platform/`) is ~152k LOC of main code, ~78k LOC of tests, 28
modules, 88 Flyway migrations across 24 schemas, built in an 11-day burst ending the day
before this review. The top-level READMEs still describe an older, smaller, more broken
state — all four "gaps that stop a pilot" they name have since been closed (price
authoring, phone/OTP customer sessions via ADR 0051, non-staff authorization via ADR
0049, shipments via V0054).

**Genuinely excellent, carried forward as-is:**

- The module discipline: `api/` as the only exported package (`@NamedInterface`), pure
  `domain/`, orchestration in `application/`, 112 `Jdbc*` adapters in `infrastructure/`.
  Modulith verification plus hand-rolled boundary tests keep it honest.
- The persistence stance: `JdbcClient` + explicit SQL + Flyway, no ORM anywhere.
- `Money` as integer minor units with `addExact`; an RFC-7807 `ErrorCode` vocabulary with
  per-constant rationale; 1,113 records; sealed hierarchies; zero TODO/FIXME, zero
  `System.out`, zero empty catches in 152k lines.
- The outbox: per-partition-key FIFO claims with `FOR UPDATE SKIP LOCKED`, lease tokens,
  stale-lease reclaim, jittered backoff, and a constructor that rejects a lease shorter
  than the worst-case batch publish time.
- A genre of test few codebases have: *ask PostgreSQL itself*. `DatabasePrivilegeTests`
  replays every GRANT as the application role (after discovering the app had been
  connecting as the DB owner, making 61 migrations of grants dead letters);
  `TenantScopedReferenceCatalogTests` queries `pg_constraint` for tenant-blind foreign
  keys and shares its allowlist with the fast regex checker so they cannot drift — and
  that allowlist has been driven to zero.

**Known weaknesses, now on HorecaOS's books:**

1. **No Maven-level quality gates.** No JaCoCo, no Checkstyle/Spotless, no Error
   Prone/NullAway, no dependency scanning. The (excellent) gates live in Python scripts
   and CI diffs. Coverage of the 152k lines is unmeasured.
2. **`CheckoutService.checkout()` is a ~420-line `@Transactional` method with 20
   constructor dependencies**, mixing availability, quoting, reservation, PII
   encryption, numbering, snapshots, payment intent, and events. Break it up before it
   calcifies.
3. **The layering rule is undeclared for persistence:** 113 of 184 application classes
   import concrete `Jdbc*Store` types; ports are used rigorously *between* modules but
   not within them. Web reaches past application into infrastructure in at least one
   controller (`StorefrontOrderingController` exposing `JdbcOrderStore.CustomerOrderRow`).
   Decide one rule and enforce it in `repo_hygiene.py`.
4. **No `allowedDependencies` on any Modulith module** — the graph is acyclic but
   unasserted; any module may reach any other's `api`.
5. **No Row-Level Security backstop.** Tenant isolation is application-enforced and
   test-verified; a single missing `WHERE tenant_id = ?` has no database-level net.
   A deliberate position, but it deserves an ADR that says so.
6. Cosmetics that confuse: 8 gap numbers in the migration sequence; naming that shifts
   from technical to prose mid-directory; 4 `JdbcTemplate` holdouts among 138
   `JdbcClient` files; schema names diverging from module names (`customer`/`customers`).

## 3. The governance system is the most valuable thing Qoida built

More valuable than the code, because it is what kept 28 agent-built modules coherent:

- **The two-status ADR model** (decision status × implementation status, `Built` only if
  an operator could use the whole feature, records physically filed by status). It
  produced confessions few codebases contain — ADR 0043: *"nothing calls `DayCloseService`
  in production… every query answers empty."* The status lines are trustworthy precisely
  because they are adversarial.
- **`minimum-viable-cutover.md`** — the sharpest document in the repo. It names the
  smallest slice that takes a real paid order, the seven rules the slice must not break,
  and instructs: "if this document has not changed after a stage, it probably was not
  consulted."
- **The migration coverage register** — every legacy table/route/dependency dispositioned
  as MIGRATE/TRANSFORM/ARCHIVE/RETIRE/DECIDE, with `DECIDE` blocking cutover visibly
  instead of silently.
- **Hooks block, skills advise** — deterministic guardrails (append-only migrations,
  ADR decision immutability, a deploy gate) as pre-tool hooks; policy as advisory skills;
  12 eval cases regression-testing the agent configuration itself, each naming the real
  incident it protects against.
- The `structural vs volumetric` tagging of legacy findings (profiled from a dev
  database, honestly labeled as such), the intent→spec→plan SDLC with human gates, the
  control-band watcher whose thresholds a model cannot move, and 17 runbooks + provider
  docs encoding hard-won local knowledge (Uzbekistan fiscalization happens *inside*
  Click/Payme payment acceptance).

Its one systemic failure: **hand-written status prose drifts.** The README was 38
migrations behind the code and still narrated four closed gaps as open; the roadmap
covers ADRs 0001–0035 and simply doesn't know 0036–0051 exist; the migration plan still
claims "0005 through 0034 are unimplemented." Rule for HorecaOS: **status numbers are
generated or absent.** Anything hand-counted will lie within a week.

## 4. The frontends are where the wheels came off

- Four working trees, four git repos, **three with no remote — they existed on one
  machine only.** (Fixed by this monorepo: they are now versioned at all.)
- Design tokens vendored in four places, four different checksums, guarded by a drift
  script that skips silently when the sibling checkout is missing.
- No generated OpenAPI clients anywhere, though ADR 0035 requires them and the backend
  regenerates a TypeScript baseline in CI that no frontend build ever saw.
- Two OIDC libraries against the same Keycloak realm; Angular 21/22 split.
- The Flutter app: ~20k LOC of features and ~9k LOC of tests, of which **cart, checkout,
  orders, and profile are complete, tested, and unreachable** — never mounted in
  `app_router.dart`. Its README says the opposite of the truth in both directions.
  The single highest-leverage frontend task in the repository is route registration.
- The storefront is a real product (~70%) with exactly one spec file.

Not imported: `qoida-dashboard` (a byte-identical copy already sits in
`platform/legacy-archive/`; it targets the legacy API with legacy auth) and
`qoida-storefront-jizbiz` (a stale fork of the storefront, superseded by the platform
copy). The React prototypes stay in the Qoida workspace as design references.

## 5. Why the migration went not as planned

Not for lack of code — the backend *outran* the plan. Five causes, in order of weight:

1. **Breadth instead of the slice.** The minimum-viable-cutover document asked for one
   vertical slice — one tenant, one location, real paid orders. What got built instead
   was every ADR in sight: dine-in, loyalty, marketplace, marketing, kitchen routing,
   courier settlement — 39 of 52 records `Partial`, and Stage 5 of the cutover never
   attempted. Fifty-two accepted ADRs with no scope or effort attached is exactly the
   "all thirty-four or nothing" stall the cutover document warned about, in its own repo.
2. **Phase 0 never happened.** Production discovery — the real schema dump, provider
   portal inventory, credential rotation — was never executed. Everything downstream was
   profiled from checked-in source and a dev database. 17 legacy table families sit at
   `DECIDE` and 14 register items await answers **only a human can give** (tenant
   grouping, Keycloak OTP migration, fiscal/legal model, courier privacy, SLOs, cutover
   cohorts). No amount of further code closes them, and `CUTOVER_READY` cannot open past
   them.
3. **The frontends were split off and starved.** The backend got 106 commits of
   discipline; the surfaces a restaurant would actually touch got unversioned
   directories, drifted tokens, and a router that hides finished features.
4. **Documentation drifted where it was hand-written** (and only there — the generated
   and adversarially-maintained parts stayed honest).
5. **The SDLC's human gates never met a human.** Two completed intent→spec→plan loops,
   both opened by an adversarial audit of the codebase, none by anyone operating a
   restaurant. Governance built for a team arrived before the team.

## 6. What HorecaOS does about it

**Carried as-is:** the entire platform tree (code, ADRs, domain docs, tools, evals,
hooks, runbooks), the four active frontend trees, the governance model.

**Founding decisions:** one repository
([ADR 0052](../platform/docs/adr/partial/0052-one-repository-for-the-whole-platform.md));
identity rename proposed as the *first* change while it is still one mechanical commit
([ADR 0053](../platform/docs/adr/not-started/0053-horecaos-identity-and-rebrand.md)).

**The standing rule this review adds:** `minimum-viable-cutover.md` outranks the ADR
list. New modules do not open while a cutover stage is unfinished; work that does not
serve a stage needs a written reason to exist.

**The queue this review leaves** (each its own intent, roughly in order):

1. ADR 0053 rename (needs the owner's name/domain/package decision).
2. Maven quality gates: JaCoCo with a ratcheting floor, Spotless (ratcheted from the
   import commit), Error Prone + NullAway staged in at warn-then-fail, dependency
   scanning.
3. Mount the four finished Flutter feature areas in `app_router.dart`; fix its README.
4. One OIDC library; canonical tokens re-pointed; the OpenAPI client generator ADR 0035
   promised.
5. Regenerate the ADR roadmap to cover 0036–0053; delete hand-written counts from
   README/AGENTS or generate them.
6. Break up `CheckoutService.checkout()`; declare the application↔persistence rule and
   the RLS position.
7. Legacy credential rotation (Phase 1) — before any cutover work touches production.
8. Then: the minimum-viable-cutover stages, in order, with a real restaurant's answers
   to the 14 open register decisions.
