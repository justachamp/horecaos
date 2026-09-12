# ADR 0109: Tenant self-service for privacy, access checks, and approval history

- Decision status: Proposed
- Implementation status: Partial — see status line detail below
- Date proposed: 2026-09-12
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0025 (capabilities), ADR 0027 (audit and approvals), ADR 0029 (PII protection), ADR 0030 (configuration and policy resolution), ADR 0021 (entitlements)
- Supersedes / Superseded by: —
- Open inputs: see table below

## Context

Wave W03 closes three gap-map rows that turned out to be three different
shapes of the same problem — a backend exists and nobody tenant-side can
reach it, or the backend genuinely does not exist and a decision about its
shape has never been recorded:

- **10.11 Data & privacy.** The tenant-scoped DSAR erasure lifecycle (raise,
  history, execute, cancel) has existed since `V0178` and
  `CustomerErasureService` — the only screen that could read it was the
  control plane's, unreachable by a tenant's own staff. Retention periods for
  abandoned carts and unverified courier applicants were plain `@Value`
  Spring properties, identical for every tenant, with no ADR 0030 key and no
  surface at all; courier-location retention was already an ADR 0030 key but
  not marked `tenantVisible()`, so only `PLATFORM_ADMIN` could reach it. No
  table anywhere named what a consent `purpose` string means, which is also
  what blocked 5.2b's own purpose vocabulary.
- **9/X.4 Проверка доступа.** `GrantController.debugAccess` already computes
  the exact shape of answer staff-and-access.md §6 asks for, gated
  `PLATFORM_ADMIN` because it can reveal any principal's grants across every
  tenant. A tenant-facing sibling needs its own guard, and needs to fold in
  ADR 0021's entitlement question, which `AuthorizationService` deliberately
  keeps independent of capability.
- **9.4 Approvals.** The pending worklist (wave 45) has no way to answer "what
  did I already decide" — a manager could see what was waiting and nothing
  once it was signed.

The one structural decision this ADR actually makes, rather than merely
records, is the second bullet's mechanism: `iam` cannot import `commercial`
(the reverse edge already exists, and Spring Modulith's module graph must stay
a DAG), yet the access check has to fold in the entitlement answer.

## Decision

1. **The DSAR worklist is a new tenant-wide list, not a new erasure
   mechanism.** `CustomerController.tenantErasureRequests` reads across every
   account in the tenant (`CustomerErasureService.worklist`,
   `JdbcCustomerStore.erasureRequestsForTenant`); raise, execute and cancel
   stay the existing per-account endpoints. A new capability,
   `CUSTOMER_ERASURE_RAISE`, gates the worklist read — see Open inputs for why
   its name anticipates a P40 refactor rather than only describing this wave.
2. **Retention periods are ADR 0030 keys, not a bespoke table.** Abandoned-cart
   retention (`ordering.cart_retention_days`) and unverified courier-applicant
   retention (`courier.applicant_retention_months`) are new, `tenantVisible()`
   keys, settable at `PLATFORM` and `TENANT`; courier-location retention
   (`telemetry.track_retention_days`) gets the same `tenantVisible()` flag it
   was always missing. All three read and write through the existing
   `OperationsConfigurationController` and `TENANT_CONFIGURATION_WRITE` — no
   new endpoint. `CartRetentionSweeper` and `CourierApplicantRetentionSweeper`
   now sweep on `GREATEST(platform default, longest tenant-configured value)`,
   the identical rule `TrackRetentionSweeper.effectiveRetentionDays` already
   used for courier tracks: a shorter stored value can never delete another
   tenant's data early, because one sweep pass still covers every tenant.
3. **A tenant-scoped consent-type registry**, `customer.consent_types`
   (`V0289`), one row per purpose a tenant asks about. Seeded with the two
   purposes already in production use (`MARKETING_PROMOTIONS`,
   `TERMS_OF_SERVICE`) on a tenant's first read rather than by migration
   (a migration cannot enumerate tenants that do not exist yet). It is a
   reference catalogue: `ConsentService.record` is unchanged and still
   accepts whatever purpose string its caller passes. Constraining every
   consent-decision writer to a code drawn from this table is a larger,
   separate decision this wave does not make.
4. **The access check is a new endpoint under `/api/v1/tenants/{tenantId}/access-check`**
   (not `/control-plane/**` — that surface is the platform-staff app's own
   OpenAPI group and would be unreachable from Operations), gated
   `IAM_GRANT_MANAGE`. `AccessCheckService` answers one of three verdicts —
   `ALLOWED`, `INSUFFICIENT_CAPABILITY`, `ENTITLEMENT_REQUIRED` — using
   `AuthorizationService.has` for the yes/no (so this console can never
   disagree with what a real request does) and
   `GrantManagementService.grantsCarrying` for the reason chain: every other
   scope the subject holds the capability at, so a negative answer can point
   at the grant that almost worked instead of a bare no. Scope containment is
   enforced again inside the service (`AuthorizationService.has(caller,
   IAM_GRANT_MANAGE, targetScope)`) because the controller annotation, reading
   only a `tenantId` path variable, can only ever prove "the caller holds it
   somewhere in the tenant" — not that the caller's own grant covers the
   specific brand or location being asked about.
5. **A new port, `iam.api.EntitlementGate`, crosses the module boundary the
   other way.** `commercial` already depends on `iam.api` throughout (every
   capability annotation); a direct `iam` → `commercial` reference would make
   the two modules cyclic. `EntitlementGate` is declared in `iam.api` (zero
   dependency on `commercial`) and implemented by `commercial.application.EntitlementGateAdapter`,
   which wraps `EntitlementService`/`EntitlementKeys`. `AccessCheckService`
   takes `List<EntitlementGate>` — the same "port owned by the consumer,
   implemented by the provider that can answer it" seam
   `customers.spi.CustomerErasureParticipant` already uses — so the entitlement
   branch is checked only once the capability branch is `ALLOWED`, matching how
   a real request is actually enforced (capability check first, entitlement
   check second, inside the service).
6. **A decided-history read**, `ApprovalDecisionService.decided` /
   `ApprovalRequestController.decided`, mirroring `pending`'s shape minus
   `mayDecide` (meaningless once decided) plus `decidedBy`/`decidedAt`. Newest
   decision first — the opposite of `pending`'s oldest-first, which exists so
   the request closest to lapsing is never starved; a decided request has no
   lapse to race.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A bespoke `tenant_retention_periods` table for the three retention values | AGENTS.md is explicit: reuse the shared policy-resolution model (ADR 0030) over a module-local reinvention. A dedicated table would also need its own scope-bar UI, its own resolution trace, and its own optimistic-locking story that `ConfigurationApi`/`OperationsConfigurationController` already have | Never, unless ADR 0030's shape genuinely cannot express a future retention rule (e.g. one keyed by more than tenant/platform) |
| A `TENANT` sweep pass per tenant instead of one global pass taking `GREATEST(...)` | Correct in principle — a tenant's own configured retention would be exact rather than a floor — but rewrites three sweepers' whole execution model (one query per tenant, tenant-scoped transactions) for a value nobody has asked to shorten yet. `TrackRetentionSweeper` already carries this exact trade-off for courier tracks | A tenant asks to set a retention shorter than the platform default and means it |
| Let `iam` depend on `commercial.api` directly for the entitlement check | `commercial` already depends on `iam.api`; the reverse edge makes the two modules cyclic, which Spring Modulith's module graph forbids regardless of which sub-package is involved | Never, unless the two modules are deliberately merged |
| Skip the entitlement branch entirely this wave (defer to a later ADR) | The brief names it explicitly as the difference `debugAccess` does not draw; skipping it would ship a console that gives a "no" for exactly the case staff-and-access.md's own worked example is written around | Superseded by this decision |
| A consent-type registry that also constrains `ConsentService.record` to a known code | A real, larger decision (which existing callers' free-text purposes would need to migrate, and a legal-basis-per-purpose exercise ADR 0029's own Open input already leaves to legal) that this wave's narrower scope does not reach | ADR 0029's retention-and-lawful-basis Open input is answered |
| Reuse `CUSTOMER_MANAGE` for the tenant-wide worklist read instead of a new capability | The gap map's own P40 section already names `CUSTOMER_ERASURE_RAISE` as the capability that will replace `CUSTOMER_MANAGE` for raise/withdraw; introducing it now under a different name would leave two capabilities doing the same conceptual job once P40 lands | Never — this is the name P40 itself will extend |

## Consequences

### Positive

- A tenant's own staff can, for the first time, see every outstanding DSAR
  erasure request across the whole tenant, set its own retention periods for
  two data classes that had no self-service surface at all and one that had
  the wrong capability, read a real consent-purpose registry instead of a
  paragraph saying none exists, ask "can she do this, and why" without
  needing platform-admin, and see what a request decided last week resolved
  to.
- Every one of these reuses an existing mechanism (ADR 0025 capabilities,
  ADR 0030 configuration, the ADR 0027 approval model, `V0178`'s erasure
  lifecycle) rather than inventing a parallel one, per AGENTS.md's own
  standing instruction.
- `EntitlementGate` is a reusable seam: the next consumer that needs "is this
  tenant entitled to X" without depending on `commercial` uses the same port.

### Negative

- The consent-type registry is inert: nothing enforces that a
  `consent_decisions.purpose` value matches a registered code, so it can
  drift from the registry exactly the way it already drifted before this
  wave (three spellings of "marketing"). This wave narrows the drift (a
  registry exists to check against) without closing it.
- Retention self-service is a floor, not an exact value, for two of the three
  keys: a tenant that sets a shorter cart or courier-applicant retention than
  the platform default sees no effect, because one sweep pass covers every
  tenant and can only take the longest configured value. The screen does not
  currently say this to the operator setting the value — the copy states the
  rule, but a value that is silently ignored below the platform floor is a
  real point of confusion until a per-tenant sweep exists.
- `AccessCheckService`'s scope-containment check is presently unreachable
  below `TENANT` scope through the HTTP endpoint: `RequiresCapability`'s
  static `scope()` attribute can only resolve from path variables, and the
  route carries only `tenantId`, so the annotation itself requires
  `IAM_GRANT_MANAGE` at `TENANT` scope before the service is ever reached.
  The service-level containment check is real and unit-tested directly, but
  it is defence for a bundle that does not exist yet (gap map row 9.1: only
  `TENANT_OWNER`/`TENANT_ADMIN` hold this capability today).
- `CUSTOMER_ERASURE_RAISE` currently gates one read (the worklist) and reads,
  in its own name, like it should gate a write. This is deliberate — see
  Alternatives — but leaves a capability whose name does not match its own
  current scope until P40 lands.

### Accepted trade-offs

- The tenant-wide worklist is read-only from this screen; executing or
  cancelling a request still requires opening the customer's own record
  (P40). A worklist that could act on a row would duplicate P40's own
  controls before that wave has decided its shape.
- The access-check screen shows raw capability codes and Keycloak subject
  strings rather than translated sentences and names, because neither a
  capability-to-sentence label registry nor a staff person record (gap map
  row 9.2) exists yet. Building either is out of this wave's scope.

## Specification

### Schema

`V0289__a_tenant_names_its_own_consent_purposes.sql` creates
`customer.consent_types` (`id`, `tenant_id`, `code`, `label_ru`/`label_uz`/`label_en`,
`description`, `channel_specific`, `policy_version`, `active`, `version`,
audit columns), unique on `(tenant_id, code)`, FK to `tenant.tenants`. No
migration creates a retention table — see Decision §2.

### Capabilities

`CUSTOMER_ERASURE_RAISE` (`customer.erasure.raise`), held by `TENANT_OWNER`
and `TENANT_ADMIN` (matching `CUSTOMER_ERASURE_EXECUTE`'s own bundle).
`TENANT_CONFIGURATION_WRITE`/`READ` (already built by P31) cover the
retention rows. `IAM_GRANT_MANAGE` (already built) covers the access check,
plus this ADR's own service-level scope containment. `APPROVAL_DECIDE`
(already built) covers the decided-history read.

### Ports

`iam.api.EntitlementGate#checkFeature(tenantId, entitlementKeyCode): Optional<Answer>`,
implemented by `commercial.application.EntitlementGateAdapter`.

## Rollout and rollback

Additive throughout: one new table, three new/changed configuration keys, one
new capability, two new read endpoints, one new port/adapter pair. Nothing
here changes an existing endpoint's contract or an existing table's shape.
Rollback is deleting the new endpoints and leaving the schema in place —
`consent_types` and the three configuration keys are inert until read.

## Implementation checklist

- [x] Tenant-wide DSAR worklist read (`CustomerController.tenantErasureRequests`)
- [x] `CUSTOMER_ERASURE_RAISE` capability, held by `TENANT_OWNER`/`TENANT_ADMIN`
- [x] Three retention keys marked/declared `tenantVisible()`, read and written
      through `OperationsConfigurationController`
- [x] `CartRetentionSweeper`/`CourierApplicantRetentionSweeper` sweep on the
      longer of platform default and tenant-configured value
- [x] `customer.consent_types` (V0289), `ConsentTypeService`, `ConsentTypeController`
- [x] `iam.api.EntitlementGate` port + `commercial` adapter
- [x] `AccessCheckService` + `GrantController.accessCheck`, with scope
      containment and the entitlement branch
- [x] `ApprovalDecisionService.decided` / `ApprovalRequestController.decided`
- [ ] A per-tenant (not global-floor) retention sweep, if a tenant ever needs
      a genuinely shorter window than the platform default
- [ ] Constrain `ConsentService.record` to a registered consent-type code
- [ ] A staff-identity record and a capability-to-sentence label registry, so
      the access-check screen can show names and sentences instead of codes

## Exit criteria

A tenant manager can, from the operations console alone: see every
outstanding data-subject erasure request in the tenant; read and change its
own abandoned-cart, courier-location and courier-applicant retention periods;
read the tenant's consent-purpose registry; ask whether a named colleague may
perform a named action at a named scope and get one of three honest answers;
and see what the tenant has already approved or declined, not only what is
still waiting.

## References

- [ADR 0025: Fine-grained authorization and capability model](../built/0025-fine-grained-authorization-and-capability-model.md)
- [ADR 0027: Audit evidence and approval model](../built/0027-audit-evidence-and-approval-model.md)
- [ADR 0029: PII protection, envelope encryption, and key rotation](../partial/0029-pii-protection-envelope-encryption-and-key-rotation.md)
- [ADR 0030: Configuration and policy resolution](../built/0030-configuration-and-policy-resolution.md)
- [ADR 0021: SaaS plans, entitlements, and usage metering](../partial/0021-saas-plans-entitlements-and-usage-metering.md)
