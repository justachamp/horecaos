# The dev/test proving run

**Last executed:** 2026-08-30, against a fresh local stack. See the dated stage
note in [minimum-viable-cutover.md](../minimum-viable-cutover.md) if this run
has since passed end to end; if it has not been re-run since, treat this
document as a draft the way every runbook here starts out.

```bash
cd platform
tools/proving-run
```

## What this proves, and why it exists

ADR 0055's launch specification puts "tenant onboarding workflow completed so
a pilot tenant is created through the API" and "dev/test environment
exercising 1-4 together" as steps 4 and 5 of the launch order, each "proven in
dev/test before the next". `tools/proving-run` is that proof: one script, run
against a fresh local stack, that creates a **brand-new** tenant through the
control-plane API — never the [local-fixtures](../local-fixtures.md) demo
tenant, which exists only for contrast and manual debugging — carries it
through [ADR 0008](../adr/partial/0008-resumable-tenant-onboarding-workflow.md)'s
resumable onboarding workflow with real validator failures and real resumes,
activates it, and takes a real phone+OTP customer order through payment,
operations approval, fulfilment, and fiscal issuance.

It is not a test suite. `make verify` already proves the code behaves
correctly under the test harness's own assumptions; this proves the same
claims hold when nothing stands in for Keycloak, OpenBao, Postgres, or the
scheduler's own clock. Several of the findings below — a Keycloak login quirk,
two bootstrap gaps with no HTTP endpoint, an onboarding check that does not
cover inventory — were invisible to the test suite and only appeared once the
whole stack was live at once.

## Precondition

A fresh stack. The script's own first phase runs `docker compose down -v &&
make up`, exactly as [local-fixtures.md](../local-fixtures.md) documents for
returning the stack to its first-start state. This project's local Docker
volumes are disposable; never point `HORECAOS_API_URL`/`HORECAOS_KEYCLOAK_URL`
at anything else.

## Running it

```bash
tools/proving-run
```

Every base URL is overridable (`HORECAOS_API_URL`, `HORECAOS_KEYCLOAK_URL`,
`HORECAOS_OPENBAO_URL`, `HORECAOS_OPENBAO_TOKEN`, `HORECAOS_KEYCLOAK_REALM`),
all defaulting to the local stack's own addresses. The script fails loudly at
the first unexpected HTTP status, printing the method, URL, and full response
body, and exits non-zero. A full run takes on the order of ten to fifteen
minutes, most of it waiting on the onboarding scheduler's own polling interval
and the fiscal sweepers' own schedule — both real, both intentionally not
sped up, because part of what is being proven is that they actually run.

Idempotency is not required across runs. A fresh `down -v` is the documented
precondition, and the script's own phase 0 performs it — running it twice in a
row is exactly like running it once.

Evidence is written to two log files, both printed at the end of the run:

- `${HORECAOS_PROVING_RUN_LOG}` (default `/tmp/horecaos-proving-run-<timestamp>.log`) —
  every phase, every `EVIDENCE:` line (an id, a status, a response fragment),
  every `GAP:` line, and the full JSON body of every onboarding-run poll.
- `/tmp/horecaos-proving-run-app-<timestamp>.log` — the application's own
  stdout/stderr for the duration of the run.

## What each phase proves

| Phase | Proves |
|---|---|
| 0. Fresh stack | `docker compose down -v && make up` leaves Keycloak fully provisioned (realm import, ADR 0009 service-account roles) from nothing — this is itself under test, per the task's own framing, not a given |
| 1. Identity bootstrap | A fresh deployment's platform-admin can reach the control plane at all — see Gaps A/B/E below, all confirmed live, not assumed |
| 2. Tenant, brand, location | `TenantControlPlaneController` creates a real tenant, brand, and located branch, address included |
| 2b. Acceptance policy | The tenant resolves `RESTAURANT_APPROVAL` (Gap D) so phase 9 exercises a real operations decision, not the silent `AUTO_CONFIRM` platform default |
| 3. Onboarding run, first pass | A required validator (`PAYMENT_CONFIGURATION_VALIDATE`) genuinely fails on an unconfigured tenant, on the first scheduler tick that reaches it — not a template that always passes |
| 4. Payment configuration, resume | The **tenant owner** — a distinct principal linked by `TENANT_OWNER_LINK_OR_INVITE`, not platform-admin — registers a legal entity, installs and reconciles a real CLICK provider connection (against the real `FakeClickHttpProvider` process), and activates a merchant binding, entirely over HTTP, then resumes the run |
| 5. Catalog, pricing, channel, inventory, resume | The owner authors a menu, prices it, sets a tax profile, configures the sales channel and inventory, publishes, and resumes again — proving a **second** real fail/fix/resume cycle (catalog readiness) |
| 6. Activation | `POST .../onboarding-runs/{runId}/activate` succeeds under ADR 0050's actual default (`ALLOW_WITHOUT_APPROVAL` for `TENANT_ACTIVATE`), single signature, honestly — not by configuring a policy that forces the easy path |
| 7. Customer order | Phone+OTP sign-in (local preset), a real published menu, a real priced quote, a real checkout — against the tenant just built, not the fixture tenant |
| 8. Payment | A real `PaymentCheckoutService` payment session, captured through the same `DevFakeClickPaymentController` -> `FakeCustomerPaymentService` -> `ClickCallbackProcessor` path a genuine Click webhook takes |
| 9. Operations | The order lands `AWAITING_APPROVAL` under the tenant's real acceptance policy; the owner approves and advances it through `PREPARING` -> `READY` -> `COMPLETED` using the same `ORDER_APPROVE`/`ORDER_ADVANCE` capabilities a location manager would hold |
| 10. Fiscal and payment evidence | The fiscal obligation opens, submits, and reaches `ISSUED` with `hasEvidence:true`; the payment attempt reads `CAPTURED` in the payments projection |

## Expected evidence

A passing run's summary (also in the log) looks like:

```text
tenant:          <uuid> (proving-run-<timestamp>) — ACTIVE
brand/location:  <uuid> / <uuid>
onboarding run:  <uuid> — READY, activated
owner:           owner@proving-run-<timestamp>.local (subject <uuid>)
legal entity:    <uuid>
merchant binding: <uuid> (CLICK, ACTIVE)
catalog:         <uuid>, publication PUBLISHED
order:           <uuid> — COMPLETED
payment attempt: <uuid> — CAPTURED
fiscal document: <uuid> — ISSUED
```

If the run stops before the summary, it failed loudly at the phase printed
immediately above the `!! FAILED:` line, with the exact request and response
that broke it — that is a **successful proving run reporting a real gap**, not
a broken script, and the honest thing to do is read the failure rather than
retry blindly.

## The honest list of accommodations

Every one of these is also a comment in `tools/proving-run` at the exact line
it happens. None of them touch the tenant's own business data — every
catalog, price, channel, legal entity, merchant binding, and order in this run
is created through the real HTTP API by the real owner principal. They fall
into two kinds: **platform bootstrap** (rows a real deployment's own migration
or a human clicking through Keycloak would create once, ever, that this
codebase currently has no API for) and **Keycloak/local-profile mechanics**
(getting a script into the same state a human reaches through a browser).

### Gap A — no HTTP path creates a PLATFORM-scope grant

The single biggest finding. ADR 0025's fresh-deployment bootstrap
(`JdbcAuthorizationService.isPlatformAdmin`) confers exactly one capability to
a Keycloak `platform-admin` realm-role holder — `IAM_GRANT_MANAGE`, at any
scope — specifically so "a platform admin can create the first grant and must
then grant themselves everything else through the ordinary audited API."

But `GrantController`'s own grant endpoint is
`POST /control-plane/tenants/{tenantId}/grants`, and its `scopeOf()` can only
ever construct a `TENANT`, `BRAND`, or `LOCATION` scope from that path — never
`PLATFORM`. There is no HTTP endpoint anywhere in this codebase that can
create a `PLATFORM`-scope grant. Confirmed live: a fresh platform-admin token
gets a plain `403 INSUFFICIENT_CAPABILITY` from
`POST /control-plane/tenants` (which needs `tenant.write` at `PLATFORM`
scope) before anything else runs.

`tools/proving-run` inserts one row directly into `iam.grants`, in exactly the
shape `ControlPlaneWiringIntegrationTests.grantPlatformAdministration` already
uses as a test fixture for the same reason — no API path exists, and the
honest thing was to name that rather than invent an ad hoc endpoint under this
task. `RoleRegistrySynchronizer` (an `ApplicationRunner`) has already seeded
the `platform-admin` role row into `iam.roles` by the time this runs.

**Follow-up**, not done here: either add a genuinely platform-scoped
bootstrap endpoint (narrow, audited, gated the same way the realm-role bypass
already is), or document this SQL step as part of the real deployment
bootstrap in `docs/runbooks/deploy.md`. Today, a real production deployment
would hit the identical wall.

### Gap B — no HTTP path creates an onboarding template

`tenant.onboarding_runs.template_id` has a `NOT NULL` foreign key to
`tenant.onboarding_templates`, and nothing in production code — no migration,
no local-fixtures seed, no controller — ever inserts a row there. Every test
that starts a run inserts one by hand
(`OnboardingFullRunIntegrationTests.seedARealisticTenant`); the script mirrors
that exact shape (`code='default', version=1, status='ACTIVE',
required_steps='[]'::jsonb`).

**Follow-up**: either an `OnboardingTemplateController` (platform-admin only,
matching the weight of the decision), or a migration that seeds the single
`default` template a fresh deployment needs, closing this the same way
Gap A's real fix would.

### Gap C — the tenant owner cannot create a second brand or location

`TenantAccessPolicy.requireTenantManagement`, which gates
`createBrand`/`createLocation`/`describeLocation`, accepts either the global
`platform-admin` realm role or an org-nested Keycloak client role
(`tenant-owner`/`tenant-admin`) read from the token's own
`organization.<org>.resource_access` claim. Nothing in this codebase ever
assigns that nested role — `OrganizationProvisioner`'s own class Javadoc
explains why `ensureOrganizationRoles` was deliberately left unimplemented:
Keycloak 26.7's Organizations Admin REST API has no organization-scoped role
sub-resource at all. `TenantOwnerAuthorityGrantorAdapter` instead confers the
`tenant-owner` capability *bundle* through `iam.grants`, which **is** real and
sufficient for every `@RequiresCapability`-gated endpoint — legal entity,
catalog, payments, channels, everything the owner does in phases 4–5 — but
`requireTenantManagement` is a second, independent, older check that
`iam.grants` cannot satisfy.

`tools/proving-run` proves this live and in the negative: right after the
owner signs in, it calls `createBrand` as the owner and asserts the `403`.
The rest of the run then has platform-admin create the brand and location,
which is what a real deployment would have to do too, today.

**Follow-up**: this is a real design decision, not a one-line fix —
`TenantAccessPolicy` needs to either drop the org-role check now that ADR
0025's capability model supersedes it, or ADR 0009's owner-link step needs a
real way to grant the nested role once Keycloak exposes one. Recorded here
rather than patched, because guessing wrong would silently reopen ADR 0003's
tenant-boundary question.

### Gap D — no HTTP path authors an order acceptance policy

[minimum-viable-cutover.md](../minimum-viable-cutover.md) names
`RESTAURANT_APPROVAL` through Operations as the intended v1 acceptance mode.
`OrderAcceptancePolicyService` resolves it through ADR 0030's generic
`tenant.policies` mechanism, and that mechanism is read-only in this
codebase — `JdbcPolicyResolver` has no writer anywhere, which matches
minimum-viable-cutover.md's own scope table: ADR 0030's "control-plane
editing UI" is explicitly listed as "Not blocking", i.e. deliberately
deferred. Absent a policy, `OrderAcceptancePolicy.platformDefault()` applies
— `AUTO_CONFIRM` — a legitimate answer, but not the one this slice exists to
prove, and not one that exercises `OperationsOrderController`'s approval
decision at all.

`tools/proving-run` inserts one `tenant.policies` row (`ordering.acceptance`,
`RESTAURANT_APPROVAL`, `HORECAOS_OPERATIONS` channel) plus its
`tenant.policy_current` pointer — the same shape a real policy-authoring
write would produce.

**Follow-up**: a policy-authoring surface for ADR 0030 (even a narrow one,
platform-admin or tenant-owner only) is the real fix, and is already known
work — this just confirms it is genuinely absent today, not merely
undocumented.

### Gap E — Keycloak 26's User Profile blocks password-grant login for an incomplete profile

`KeycloakOrganizationProvisioner.ensureMembership` creates the invited owner
with only `username`/`email` — correctly, that is ADR 0009's whole membership
contract. Keycloak 26's declarative User Profile then refuses **any**
password-grant login for an account missing `firstName` or `lastName`, with
the misleading `invalid_grant` / "Account is not fully set up" — no
exception anywhere in the logs, no realm setting names it, and every
password-length and required-action theory was ruled out empirically
(reproduced against a stock, freshly-created realm with `KC_LOG_LEVEL=DEBUG`
before finding the cause). This is
[keycloak/keycloak#36108](https://github.com/keycloak/keycloak/issues/36108),
a known Keycloak 26.0.7+ regression, still present in the pinned 26.7.0.

A real owner hitting this in a browser sees Keycloak's own "complete your
profile" required-action screen and fills it in as part of signing in for the
first time — this is not a HorecaOS bug and not something a human ever
notices. A script has no browser, so `tools/proving-run` does the Keycloak
Admin API equivalent directly (`PUT /admin/realms/horecaos/users/{id}` with
`firstName`/`lastName`), plus setting the password a real owner would set
through Keycloak's own invitation/reset flow.

Two more Keycloak-only local-dev artifacts exist for the identity bootstrap
this run needs, both loopback-guarded like `create-local-web-client.sh`:

- `infra/keycloak/create-local-dev-client.sh` — a confidential,
  direct-grant-enabled client (`horecaos-local-dev`), because
  `horecaos-api` is deliberately `bearerOnly` (it is the resource server, not
  something anyone signs into) and `horecaos-local-web` is a public PKCE
  client (no secret, cannot use the password grant). Also fixes a second,
  independent trap found while building it: creating a Keycloak client via
  the Admin API without an explicit `defaultClientScopes` list does **not**
  fall back to the realm's own `defaultDefaultClientScopes` — it creates a
  client with *no* default scopes, and every token that client issues comes
  back with no `sub`, no `realm_access`, no `resource_access`. There is no
  error; the token simply authenticates nobody and authorizes nothing. Fixed
  by assigning the realm's default scopes explicitly through the dedicated
  per-scope endpoints (`.../default-client-scopes/{id}`), which — unlike the
  client representation's own `defaultClientScopes` field — are honoured on
  every call, not just the first `POST`.
- `infra/keycloak/create-platform-admin.sh` — creates (or resets the
  password of) a realm user holding the global `horecaos-api`
  `platform-admin` client role. ADR 0003 deliberately leaves who holds this
  role to each deployment; a fresh one holds nobody.

### Gap F — onboarding readiness does not cover inventory

`ACTIVATION_SMOKE_TEST` (`ordering.application.onboarding.OrderingOnboardingStepHandlers`)
prices a representative item through `CartPricingPort` directly and never
touches `inventory.stock_items`. Confirmed live: with a published,
channel-bound, correctly-priced item and no stock item ever listed, the
onboarding run reached `READY` and activation succeeded — and the very first
real customer checkout against that item refused with
`409 ITEMS_UNAVAILABLE` / `NOT_STOCKED_AT_LOCATION`. `tools/proving-run` lists
the variant as `BINARY`-tracked stock and marks it available
(`InventoryController`) in phase 5, so this is not fatal to the run, but it
means **an activated tenant is not proof that its menu can actually be
ordered from** — worth a line in `ACTIVATION_SMOKE_TEST`'s own scope, or in
its own class Javadoc, so a future reader does not assume it.

### Two things that were investigated as gaps and turned out not to be

Both cost real time to run down and are recorded so nobody repeats the
investigation:

- **The CLICK fiscal document sticking at `SUBMITTED`/`UNCERTAIN` instead of
  `ISSUED`.** Traced through `FiscalObligationSweeper` ->
  `PartnerFiscalizationBridge.retry` -> `ClickFiscalAdapter.submit` ->
  `capturedPaymentId`'s live `status_by_mti` fallback -> the application log
  line `Provider CLICK rejected the cached credential ... refreshing once` ->
  `payment.status_by_mti on CLICK finished as REJECTED`. The root cause was
  this run's own setup, not the platform:
  `FakeClickHttpProvider` validates every merchant-API call's `Auth` header
  against `FakeClickProviderConfiguration`'s own configured secret
  (`horecaos.fake-providers.click.secret`), whose default is the literal
  string `local-fixture-click-secret-key-not-a-real-credential` — the fake
  never reads OpenBao itself, so whatever the merchant binding's
  `secretReference` resolves to **must equal that exact string**, not an
  arbitrary tenant-specific value, or every provider call authenticates as
  nothing. `tools/proving-run` writes exactly that value to OpenBao. Once
  corrected, the very same document reached `ISSUED` with `hasEvidence:true`
  on the next manual retry, no code change needed. This is the same
  agreement `tools/seed-payments`' own comment already documents for the
  fixture tenant — easy to miss when writing a *new* tenant's secret rather
  than copying the fixture's.
- **`PAYMENT_READ` has no controller.** The capability is declared in
  `Capability.java` and composed into every staff role bundle, but no
  controller in this codebase exposes a query surface for a payment attempt's
  own status. Not worked around — `tools/proving-run` reads
  `payments.payment_attempts` directly via `psql`, purely for evidence
  capture, and says so in its own output. Worth a small follow-up
  (`GET .../payments/attempts/{id}` behind `PAYMENT_READ`) since staff
  currently cannot answer "is this order actually paid" without a database
  connection.

## The compose.yaml fix that is not a "gap" but a real bug fix

`compose.yaml`'s `openbao-seed` service never seeded
`object_storage/platform/media-{access,secret}-key` or
`data_encryption/platform/handover-pepper`. Both are required only under
`HORECAOS_SECRETS_PROVIDER=openbao` — [development.md](../development.md)'s
own documented way to exercise the OpenBao path instead of the `environment`
provider default — and this proving run genuinely needs the OpenBao path:
`payments`' `SecretResolver` has no `environment`-provider fallback for an
arbitrary tenant-scoped merchant secret, so a real (fake-provider) CLICK
payment cannot be proved without it. Without this fix, `make run` under
`HORECAOS_SECRETS_PROVIDER=openbao` fails to start at all
(`ObjectStorageConfiguration.s3Client` and
`PartnerConfiguration.handoverCodeHasher` both resolve their secret eagerly
at bean construction). Fixed directly in `compose.yaml`'s `openbao-seed`
step, with values matching `application.yml`'s own `environment`-provider
defaults so behaviour is identical on either provider. This is a genuine
defect in the documented local dev loop, not a proving-run-only
accommodation, so it stays fixed rather than worked around.

## Re-running just one phase

The script is not designed to be re-entered mid-way — a fresh `down -v` is
the documented precondition, and re-running the whole thing costs about ten
minutes. If you need to iterate on one phase while debugging a change,
comment out the phases before it and hardcode the ids the later phases need
(tenant, brand, location, owner token) rather than trying to make the whole
script idempotent against a partially-built tenant.

## Exercising the two-principal approval path (not covered by default)

Phase 6 activates under ADR 0050's actual default for `TENANT_ACTIVATE`
(`ALLOW_WITHOUT_APPROVAL`), which is the honest behaviour of an unconfigured
tenant and needs only the platform-admin's own signature. To prove the
`REQUIRE_CONFIGURED_POLICY` path instead (two principals: one who requests,
one who approves), author an `audit.approval_policies` row naming
`tenant.activate` with a `required_approver_capability` before phase 6, using
a second platform-admin-or-delegated principal to decide it through
`POST .../approval-requests/{id}/decision`. Not exercised by default because
it would misrepresent this platform's actual default behaviour as something
that needed proving.
