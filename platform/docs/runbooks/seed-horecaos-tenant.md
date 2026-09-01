# Seeding HorecaOS's own tenant

**Last executed:** 2026-09-01, against a local instance built from the shared
dev stack's Postgres/Kafka/Keycloak/OpenBao (`make up`), API on a non-default
port. Not yet executed against staging or production — run it there in that
order, per "When to run this" below, and update this line with the date and
target.

```bash
cd platform
HORECAOS_SEED_PLATFORM_ADMIN_USERNAME=<a platform-admin realm user> \
HORECAOS_SEED_PLATFORM_ADMIN_PASSWORD=<their password> \
tools/seed-horecaos-tenant
```

## What this is, and why it is not a migration

The owner wants "the horecaos tenant, with brands, locations, menu and
catalog, for ourselves, to see how our platform works" — HorecaOS's own
tenant, dogfooding the platform it built, in production. A Flyway migration
seeding that data would land it in **every** database this platform ever
runs — every Testcontainers instance, staging, all of them — and would
bypass the maker-checker/audit onboarding path ([ADR
0008](../adr/partial/0008-resumable-tenant-onboarding-workflow.md), [ADR
0027](../adr/partial/0027-audit-evidence-and-approval-model.md)) the platform
deliberately made SQL-free. `tools/seed-horecaos-tenant` instead drives the
same real HTTP APIs a human operator would, in the same style as
[`tools/proving-run`](proving-run.md) (read that runbook first — this tool is
built from it and reuses its mechanics: `req`/`section`/`evidence` helpers,
password-grant token acquisition, the onboarding wait loop). The two
differences that matter:

- **It is idempotent.** `tools/proving-run` mints a fresh randomly-slugged
  tenant every run and is not meant to be re-entered. This tool always
  targets the fixed slug in `tools/seed-data/horecaos-tenant.json`
  (`horecaos`) and is meant to be re-run — see "Idempotency" below.
- **It never authenticates as the tenant owner.** `PLATFORM_ADMIN`'s role
  bundle already holds every capability this seed needs (ADR 0025 scopes
  cover downwards — a `PLATFORM`-scope grant satisfies any narrower
  `TENANT`/`BRAND`/`LOCATION`-scoped check), so it never resets or even
  learns the real owner's Keycloak password. The owner is still linked and
  invited by the real onboarding workflow, so a real person can sign in and
  manage this tenant afterwards.
- **It never installs a payment provider.** CLICK/Payme need a real merchant
  account and a real secret; a script must never hold one (ADR 0028). The
  seeded channel accepts cash only, which is enough to clear
  `PAYMENT_CONFIGURATION_VALIDATE` and reach `ACTIVE` — a human adds a real
  provider through the control-plane UI/API later, exactly as
  [`production-setup.md`](production-setup.md) already expects for any
  tenant's payment setup.

## When to run this

**Staging first, then production — never the reverse.** Run it against
staging (`docs/runbooks/production-setup.md` section 10's variant) after any
change to `tools/seed-data/horecaos-tenant.json` or to this tool itself, and
confirm the verification section below before running it against
`https://api.horecaos.uz`. It is also the tool to re-run any time the seed
data changes (a new menu item, a corrected address) — see "Idempotency".

## Credentials it needs

Exactly one of:

- **`HORECAOS_SEED_PLATFORM_ADMIN_TOKEN`** — a bearer token, used as-is. The
  production/staging path: obtain it however that deployment's identity
  setup allows (today, that means a human signing in through the
  control-plane frontend or Keycloak's own admin console and copying a
  token — there is no non-interactive path for a non-loopback Keycloak yet,
  a real and separate gap; see "Known gaps" below) and pass it in.
- **`HORECAOS_SEED_PLATFORM_ADMIN_USERNAME` + `_PASSWORD`** (+
  `HORECAOS_KEYCLOAK_URL`/`_REALM`/`_CLIENT_ID`/`_CLIENT_SECRET` as needed) —
  a password grant against a realm user already holding the `horecaos-api`
  `platform-admin` client role. This is the local/dev path: `make up`'s
  Keycloak plus `infra/keycloak/create-local-dev-client.sh` and
  `infra/keycloak/create-platform-admin.sh` (both loopback-guarded, see
  [`proving-run.md`](proving-run.md)) get you there exactly the way
  `tools/proving-run`'s own phase 1 does.

Either way, the credential only needs to resolve to a `platform-admin` — see
the tool's own header comment for why that is safe and sufficient.

## What it creates

From `tools/seed-data/horecaos-tenant.json`:

- Tenant `horecaos` (UZS, Asia/Tashkent), brand `HORECAOS`, two Tashkent
  locations (Chilonzor, Yunusabad) with placeholder addresses/coordinates and
  placeholder contact phones — **not the company's real registered address or
  phone**; replace them in the data file before this is customer-facing in a
  way that matters.
- A legal entity (`HORECAOSLLC`) with a placeholder nine-digit TIN, assigned
  as each location's seller — **not a real tax registration**; a human must
  correct this before the tenant issues a real fiscal document.
- One catalog, four categories, fourteen products (plov, lagman, manti,
  shashlik, somsa, salads, non, drinks) with uz/ru/en names and descriptions,
  each priced in integer UZS (VAT-inclusive, 12%), available and stocked at
  both locations.
- A delivery tariff and one circle delivery zone per location (6 km radius),
  so delivery is real, not just declared.
- The `STOREFRONT` sales channel: cash payment, pickup and delivery, bound
  to both locations; a wide-open (09:00–23:00, weekends to midnight) service
  schedule bound to both locations for both fulfilment modes.
- A `PUBLISHED` catalog publication, and the tenant carried through
  onboarding to `ACTIVE`.

## Idempotency — what re-running does

Safe to re-run. Concretely:

| Resource | Behaviour on a second run |
|---|---|
| Tenant, brand, locations | Rediscovered via `GET .../tenants/by-slug/{slug}` and the existing brand/location list endpoints, matched by code — never re-created. A location's address/coordinates are always re-asserted (idempotent `PUT`). |
| Legal entity and its location assignments | Rediscovered via `GET .../legal-entities` and `GET .../assignments`, matched by code / open assignment — never re-created. |
| Catalog, categories, products, price book, delivery tariff, delivery zones, service schedule | **Skipped once recorded** in a local state file (see below) — no HTTP calls at all for a resource already known. First run creates everything; every later run against the same state file is a fast no-op for these. |
| Sales channel config, schedule bindings | Always re-asserted (natural-key `PUT`s) — every run leaves them freshly reconciled. |
| Catalog publication | Always re-published — a fresh, valid snapshot every run, deliberately, so a reconcile run always ends with a live menu even if nothing else changed. |
| Onboarding resume/activation | Skipped entirely once the tenant is already `ACTIVE` (checked first). |

The state file is `tools/seed-data/.state/<slug>.<sha256(api-url)[:12]>.json`
(gitignored — see the `.gitignore` comment) unless
`HORECAOS_SEED_STATE_FILE` overrides it. It exists because catalog authoring,
pricing, and delivery-zone/tariff authoring genuinely have **no**
list-or-find-by-code HTTP endpoint in this codebase today — a real gap, not
an oversight; see "Known gaps" below. The tool refuses to reuse a state file
recorded against a different `HORECAOS_API_URL` (a copy-pasted state file
pointed at the wrong environment fails loudly rather than silently attaching
to the wrong tenant's ids).

**If the state file is lost** on an environment that already has this
tenant's catalog (a wiped checkout, a fresh clone), the tool has no way to
rediscover a catalog/category/product/price-book/tariff/zone/schedule id and
will attempt to create all of them again — which the database's own unique
constraints on `(tenant_id, brand_id, code)` will refuse loudly (a `409`/`500`
naming the constraint, not silent duplication), but it is still a manual
recovery, not an automated one. Recovery today: rebuild the state file by
hand from the actual ids (`psql`, read-only, or a future `GET` endpoint once
one exists — see "Known gaps"), or accept that the catalog step is already
done and skip straight to re-running from `make run`'s log for the phases
that still matter (channel, publish, activation are always safe to
re-attempt regardless of state).

## Backend changes this tool needed

Three small, capability-gated additions — reported per this task's own
instruction to do so loudly. None needed a migration, and all three are
covered by the generic `EndpointCapabilityDeclarationTests` (capability
declared, correctly scoped, replay-protected) that scans every controller in
the codebase, so no capability-denial test had to be hand-written for any of
them.

1. **A brand and a location are created `DRAFT` and nothing in this codebase
   ever moved either to `ACTIVE`.** The load-bearing one, found live: a
   tenant can reach `ACTIVE` — `BRANDS_AND_LOCATIONS_VALIDATE` checks only
   that a brand and a location *exist*, never their status — while its brand
   and every location under it stay `DRAFT` forever, because no controller,
   onboarding step, or migration anywhere calls `Brand.activate()` /
   `Location.activate()`. The published menu endpoint does not care (it
   never reads location/brand status), so this was invisible until this
   tool's own phase 12 asked `GET /api/v1/storefront/pickup-locations` to
   find a location it had just created, activated, published a menu for, and
   priced — and got back one row, belonging to the unrelated
   `local-fixtures` demo tenant (`JdbcStorefrontPickupLocationStore.nearestTo`
   requires `l.status = 'ACTIVE'` and `b.status = 'ACTIVE'` explicitly).
   `tools/proving-run` never notices, because it goes straight to
   `GET .../locations/{locationId}/menu` with a known id rather than through
   discovery. Fixed with two new idempotent (no-op once already `ACTIVE`)
   endpoints mirroring `LegalEntityController`'s own activate pattern:
   `POST /api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/activate`
   and
   `POST /api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/activate`
   (`TenantControlPlaneController`/`Service`/`Store`). Tested in
   `TenantControlPlaneServiceTests.activatesADraftBrandAndLocationAndIsIdempotent`.
   **Every tenant onboarded through the standard control-plane flow before
   this fix — including every `tools/proving-run` tenant — has a `DRAFT`
   brand and `DRAFT` locations today** and will not appear in pickup-location
   discovery until activated; this is worth flagging beyond this task.
2. **`GET /api/v1/control-plane/tenants/by-slug/{slug}`**
   (`TenantControlPlaneController`, `TenantControlPlaneService`,
   `TenantControlPlaneStore`/`JdbcTenantControlPlaneStore`) — platform-admin
   only. There was no way to look up a tenant by slug before this; only by
   id, which idempotent tooling driving a fixed known slug does not have
   until the first run. Read-only, `PLATFORM`-scoped, tested at the
   store/service layers (`JdbcTenantControlPlaneStoreTests`,
   `TenantControlPlaneServiceTests`).
3. **`PUT /api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/catalog/translations`**
   (`CatalogAuthoringController`) — a thin HTTP exposure of
   `CatalogAuthoringService.translate`, which already existed, was already
   tested (`CatalogTranslationTenantScopeTests`,
   `CatalogPublicationTests`), and was already tenant-isolation-checked, but
   had no controller calling it. Every catalog-authoring create endpoint
   takes exactly one locale; this is the only way to add a further one (say,
   `ru`/`en` beside a product's `uz` name) afterwards, which the seed data's
   uz/ru/en names need.

## Known gaps (not fixed here — out of this task's scope)

- **No non-interactive credential path for a non-loopback Keycloak.**
  `infra/keycloak/create-local-dev-client.sh` and `create-platform-admin.sh`
  both refuse to run against anything but `localhost`/`127.0.0.1`, correctly
  — but that leaves staging/production with no scripted way to obtain a
  platform-admin token, only a human copying one out of a browser session.
  `HORECAOS_SEED_PLATFORM_ADMIN_TOKEN` works around this for this tool
  specifically; the underlying gap is platform-wide.
- **No list-or-find-by-code endpoint for catalogs, categories, products,
  price books, delivery tariffs, delivery zones, or service schedules.**
  This is why the state file exists at all. A future
  `GET .../catalog/catalogs?code=` (and siblings) would let this tool — and
  any future onboarding UI — reconcile without local state.

## How to verify

Two runs (the second proving idempotency), then the anonymous storefront
endpoints — the same ones `production-setup.md` section 7 checks:

```bash
tools/seed-horecaos-tenant   # first run: creates everything
tools/seed-horecaos-tenant   # second run: green, mostly skips, republishes
```

A passing run's summary (also in the log,
`${HORECAOS_SEED_LOG:-/tmp/horecaos-seed-horecaos-tenant-<timestamp>.log}`)
looks like:

```text
tenant:          <uuid> (horecaos) — ACTIVE
brand:           <uuid> (HORECAOS)
locations:       2 (CHILONZOR YUNUSABAD)
onboarding run:  <uuid> — READY, activated
legal entity:    <uuid>
catalog:         <uuid>, 14 products, publication PUBLISHED
price book:      <uuid>
delivery tariff: <uuid>
menu at CHILONZOR: 14 products, 1 pickup branch(es) nearby
```

Then, with `<tenantId>`/`<brandId>`/`<locationId>` from that summary (or from
`GET .../tenants/by-slug/horecaos`):

```bash
# The published menu — must list all 14 products, priced
curl -fsS "${HORECAOS_API_URL:-http://localhost:8080}/api/v1/storefront/tenants/<tenantId>/brands/<brandId>/locations/<locationId>/menu?locale=uz&channel=STOREFRONT" | jq '.products | length'

# Pickup-location discovery near the branch's own point — must list it
curl -fsS "${HORECAOS_API_URL:-http://localhost:8080}/api/v1/storefront/pickup-locations?lat=41.2856&lon=69.2034" | jq '.locations | length'

# Delivery fee at the branch's own point — must resolve a fee, not a refusal
curl -fsS "${HORECAOS_API_URL:-http://localhost:8080}/api/v1/storefront/tenants/<tenantId>/brands/<brandId>/locations/<locationId>/delivery-fee?lat=41.2856&lon=69.2034&currency=UZS" | jq .

# Serviceability for both fulfilment modes — both must answer available:true
curl -fsS "${HORECAOS_API_URL:-http://localhost:8080}/api/v1/storefront/tenants/<tenantId>/brands/<brandId>/locations/<locationId>/serviceability?channel=STOREFRONT&mode=PICKUP" | jq .
curl -fsS "${HORECAOS_API_URL:-http://localhost:8080}/api/v1/storefront/tenants/<tenantId>/brands/<brandId>/locations/<locationId>/serviceability?channel=STOREFRONT&mode=DELIVERY" | jq .
```

The tool itself already runs all five of these as its own phase 12 and fails
loudly if any of them do not answer as expected — this section is for
verifying independently, after the fact, or from a different machine.
