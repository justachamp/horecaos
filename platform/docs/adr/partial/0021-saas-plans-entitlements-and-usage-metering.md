# ADR 0021: SaaS plans, entitlements, and usage metering

- Decision status: Accepted
- Implementation status: Partial — V0033's eight tables and the `commercial`
  module are complete in meter-only form: immutable plan versions, the
  subscription state machine, typed entitlement resolution over
  `EntitlementKeys` with overrides and boundary policy, an append-only usage
  ledger with idempotent movements, adjustments and exact rebuild, and the
  platform-admin and control-plane APIs with four-eyes approval and audit, all
  covered by `CommercialPlatformTests`, `EntitlementBoundaryTests` and
  `EnforcementCeilingKeyTests`. Not built, and it is what keeps this from being
  usable: **no product module calls `EntitlementService` or `UsageMeter`** — the
  grep for either outside `uz.horecaos.platform.commercial` is empty, so no feature
  is gated and no usage is metered; no consumer is wired to the ADR 0005 inbox;
  there is no period close, invoice export, dashboard, alert or runbook; and no
  onboarding path creates a subscription.
- Date proposed: 2026-08-19
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture), product
- Depends on: ADR 0002, ADR 0008, ADR 0009, ADR 0015, ADR 0030
- Supersedes / Superseded by: —
- Open inputs: Plan catalogue, trial, overage, and termination policy (product, finance); entitlement limit values (product)

## Context

Smooth tenant onboarding is necessary but not sufficient for a SaaS product.
Qoida needs explicit plans, subscriptions, feature access, resource limits,
trials, suspension, and usage evidence. Scattered `if tenant == ...` checks or
per-tenant deployments would not scale and would make commercial changes unsafe.
Customer-order payments in ADR 0013 are distinct from billing a restaurant for
using Qoida.

## Decision

A control-plane `commercial` module owns versioned plans, tenant subscriptions,
entitlements, overrides, and an append-only usage ledger. Product modules check
a local entitlement service using a typed feature/limit catalog. They do not
query an external billing provider on request paths and do not implement custom
tenant branches.

The first slice supports manual/admin subscription assignment and invoice export
evidence. Automated recurring billing is a later adapter behind the same ports
after tax, invoicing, and provider requirements are approved.

This module records what is owed and never how it is paid. Dunning, proration,
subscription tax treatment and every payment path are outside it: ADR 0013 owns
money movement, and a second place in the platform that can move money is a
second place that can move it wrongly. What the tables here produce is the
evidence a charge is computed from — a plan price, a limit, a consumed quantity,
and an agreed overage rate — which is the input to an invoice rather than an
invoice.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Per-tenant conditionals in application code | Unscalable, untestable, and invisible to support. Every commercial change becomes a deployment | Never |
| Query the billing provider during request handling | Puts an external commercial dependency inside customer-critical paths, so a billing outage would stop order taking | Never |
| Express entitlements as Keycloak roles | Conflates authorization with commercial capability. A plan grants a feature; it never grants a user permission, and confusing the two creates privilege escalation through billing | Never |
| Deploy a separate instance per plan or tenant | Operational cost and configuration drift, and it makes cross-tenant platform operations impossible | Never |
| Compute usage from reports at period end | Not reproducible, not idempotent, and impossible to reconcile when a dispute arrives | Never |
| Enforce hard limits from the start | A limit bug would block a paying tenant from operating before any usage evidence exists. Metering first, then soft warnings, then selected hard limits | Never; the staged rollout is the decision |
| Delete resources when a tenant exceeds a lowered limit | Destroys customer data over a commercial dispute | Never; block additions and set a remediation deadline |
| Automate recurring billing in the first slice | Invoicing, tax treatment, and provider selection are unresolved. Manual assignment with export evidence is honest about that | Tax, invoicing, and provider requirements are approved |

## Plan and subscription model

```text
commercial.plans
  id, code, name, status, current_version_id null, timestamps

commercial.plan_versions
  id, plan_id, version_number, currency, price_minor null
  billing_period null, effective_from, effective_until null
  status, terms_reference, created_by, approved_by, timestamps

commercial.plan_entitlements
  plan_version_id, entitlement_key, value_type
  boolean_value null, integer_value null, decimal_value null, string_value null
  enforcement_mode, reset_period null

commercial.subscriptions
  id, tenant_id, plan_version_id
  status, start_at, trial_end_at null, current_period_start/end
  cancel_at null, suspended_at null, external_billing_reference null
  version, timestamps

commercial.entitlement_overrides
  id, tenant_id, entitlement_key, typed_value
  reason, valid_from, valid_until, approved_by, version, timestamps
```

Plans are immutable after activation. Changing features or price creates a new
version and a deliberate subscription transition. Overrides are exceptional,
time-bounded, audited, and visible in support tooling.

## Subscription lifecycle

```text
DRAFT -> TRIALING -> ACTIVE -> PAST_DUE -> SUSPENDED -> TERMINATED
                  -> CANCELLATION_SCHEDULED -> TERMINATED
TRIALING -> ACTIVE | EXPIRED
PAST_DUE -> ACTIVE
SUSPENDED -> ACTIVE when policy permits
```

Tenant operational lifecycle and subscription lifecycle are separate. A billing
problem does not delete the tenant. Suspension maps entitlements to an approved
degraded behavior such as read-only Operations, disabled new ordering, and data
export access.

## Entitlement catalog

Each key is a code-owned typed definition with description, scope, default,
enforcement mode, and owner. Initial candidates include:

```text
brands.max_count
locations.max_count
control_plane.users.max_count
pos.integrations.enabled
pos.installations.max_count
delivery.partner_integrations.enabled
payments.provider_integrations.enabled
catalog.products.max_count
orders.monthly_included
media.storage_bytes_included
notifications.monthly_included
analytics.advanced.enabled
```

Candidate values are product inputs, not accepted limits in this ADR. Unknown
keys or type mismatches fail plan activation. Entitlements are tenant-scoped;
brand/location-specific rollout uses feature configuration, not a new commercial
subscription.

## Enforcement semantics

- `HARD`: reject a capacity-increasing action before mutation with current
  usage, limit, stable error code, and upgrade path.
- `SOFT`: allow, record overage, and alert tenant/platform Operations.
- `METER_ONLY`: measure without changing behavior.
- `DISABLED`: deny feature activation while preserving existing data and a safe
  deactivation workflow.

Every enforcement point is named and tested. Existing resources are not deleted
when a limit decreases; new additions are blocked or a remediation deadline is
created. Critical customer flows never depend on a remote billing API.

## Usage metering model

```text
commercial.usage_events
  id, tenant_id, entitlement_key, quantity, unit
  source_event_id, source_type, occurred_at, recorded_at
  dimensions_json, unique(tenant_id, entitlement_key, source_event_id)

commercial.usage_aggregates
  tenant_id, entitlement_key, period_start/end
  consumed_quantity, last_event_at, calculation_version, updated_at

commercial.usage_adjustments
  id, tenant_id, entitlement_key, period
  quantity_delta, reason, approved_by, source_reference, timestamps
```

Product events are consumed through inbox and converted to idempotent usage
facts. Dimensions are allowlisted and bounded; no customer IDs or PII. Aggregates
are reproducible from events plus adjustments. Late events apply to their event
period with an auditable correction/export policy.

## Entitlement resolution

Resolution combines active subscription plan version, valid override, and
system safety policy into an immutable `EntitlementSnapshot` with version/hash.
Application commands ask typed questions such as `canCreateLocation(tenantId)`
or `requireFeature(POS_INTEGRATIONS)` through a local port. A short local cache
may be used, invalidated by outbox events, with PostgreSQL as authority.

Security checks always run independently. An entitlement allows a capability;
it never grants a user permission or tenant scope.

## Onboarding and offboarding integration

ADR 0008 onboarding selects an approved plan/trial, creates the subscription,
and records the entitlement snapshot before activating tenant ordering. Plan
failure is a resumable onboarding step. Termination/suspension triggers a
governed workflow for new-order blocking, credential disablement, exports,
retention, and eventual deletion—never immediate destructive cleanup.

## APIs

```text
GET  /api/v1/control-plane/plans
POST /api/v1/platform-admin/plans/{planId}/versions
POST /api/v1/platform-admin/plan-versions/{versionId}/activate
GET  /api/v1/control-plane/tenants/{tenantId}/subscription
POST /api/v1/platform-admin/tenants/{tenantId}/subscription-transitions
POST /api/v1/platform-admin/tenants/{tenantId}/entitlement-overrides
GET  /api/v1/control-plane/tenants/{tenantId}/usage
```

Platform-admin commercial mutations require strong authentication, ADR 0027
four-eyes approval where configured, a reason, an expected version per ADR 0031,
and immutable ADR 0027 audit.

## Events

```text
PlanVersionActivated
TenantSubscriptionStarted/Changed/Suspended/Terminated
TenantEntitlementsChanged
TenantUsageRecorded
TenantUsageLimitApproaching/Exceeded
TenantCommercialActionRequired
```

Product modules consume entitlements changed, not billing-provider webhooks.

## Testing

- Plan versions and activated terms cannot mutate in place.
- Concurrent resource creation cannot exceed a hard count limit.
- Duplicate/out-of-order usage events do not double count.
- Aggregate rebuild equals the live aggregate including adjustments.
- Trial/period boundaries and override expiry are deterministic with a fixed
  clock and tenant timezone policy.
- Suspension preserves data and approved read/export capabilities.
- Entitlement never bypasses authorization, and cross-tenant reads fail.
- Provider outage does not affect local entitlement checks.

## Rollout and rollback

Define the entitlement catalog and run `METER_ONLY` for all tenants first.
Compare counts to source systems, then assign shadow plans, expose usage, enable
soft warnings, and finally turn on selected hard limits for new resources.
Rollback changes enforcement to meter-only while retaining subscriptions,
events, and evidence; it never rewrites historical usage.

## What was built, and where it departs from the text above

Migration `V0033` creates a `commercial` schema and the Java module lives at
`uz.horecaos.platform.commercial`. Seven departures from the plan sketched above are
deliberate and each is recorded here rather than left to be discovered.

**The boundary is six answers, not four modes.** A mode says how the platform
behaves; it does not say what happened. `Boundary` answers `UNLIMITED`,
`WITHIN`, `APPROACHING`, `OVER_BILLABLE`, `OVER_UNBILLED`, or `REFUSED`, and
`OVER_UNBILLED` is the one the sketch was missing: a limit passed under a plan
that sells no overage, or under a ceiling that suppressed the charge, is free
and recorded, which is the state every tenant is in during the meter-only
rollout.

**Every check carries two answers.** `LimitCheck.boundary()` is what the
platform did; `LimitCheck.wouldBe()` is what the commercial terms alone would
have done. Counting the second is the entire evidence base for raising
enforcement, and it does not exist if the mode is baked into the decision.

**Enforcement is an ADR 0030 configuration value, not a column.**
`commercial.enforcement_ceiling` resolves platform then tenant through the
existing resolver, defaults to `METER_ONLY`, and caps — never raises — the mode
a plan declares. Rollback is setting one row back, and it rewrites no usage.
The key is declared in both `ConfigurationKeys` and
`CommercialConfigurationKeys` because the ADR 0030 registry is internal to the
tenancy module and a reference the other way would make the two modules cyclic;
a test fails the build if the two declarations drift.

**Overage carries a unit price.** `plan_entitlements.overage_unit_price_minor`
is what makes "allow and bill" billable rather than merely permitted. Without
it the control-plane console cannot decompose 14 250 000 into 9 000 000 plus 21
locations at 250 000, and an invoice line an account manager cannot decompose is
one that gets disputed.

**The usage idempotency key includes `source_type`.** A source event id is
minted in another system's namespace and two systems can produce the same
string; without the type, one collision silently drops a real movement.

**There is no entitlement cache and therefore no invalidation event.**
Resolution is one indexed read per tenant. The ADR lists caching among its own
negative consequences, and until a measured request path needs it, a plan change
that is not yet visible is a support ticket bought for nothing.

**Suspension zeroes counted limits and nothing else.** Read-only Operations and
continued export access are ADR 0025 capability decisions; expressing them as
entitlements would let a plan grant or remove a user's permission, which this
ADR forbids elsewhere in its own text.

Not built, and deliberately: product enforcement points inside ordering,
tenancy, catalog and the rest; usage consumers wired to the ADR 0005 inbox;
period close and invoice export; the published event set; dashboards, alerts and
support runbooks; and anything at all that moves money.

## Consequences

### Positive

- Commercial capability is expressed once, typed, and checked locally, so no
  request path depends on a billing provider.
- Usage is reproducible from immutable events plus adjustments, which makes
  disputes answerable.
- Suspension degrades a tenant safely instead of deleting or breaking it.

### Negative

- Every enforcement point is code that must be named, placed, and tested, and a
  missing enforcement point is invisible until a limit is exceeded.
- Entitlement caching introduces a window where a plan change is not yet
  reflected, which support will interpret as a bug.
- The entitlement catalog becomes a compatibility surface: renaming a key is a
  migration.

### Accepted trade-offs

- Manual subscription assignment first means commercial operations do real work
  per tenant until automated billing exists.
- Meter-only rollout means limits are unenforced for a period during which a
  tenant can exceed what it pays for.

## Implementation checklist

- [ ] Approve plan catalogue, trial, suspension, overage, and termination policy.
      Still a product and finance input. Every value in `EntitlementKeys` is
      unlimited or available, so nothing an engineer invented can refuse a
      paying tenant.
- [x] Define typed entitlement keys, owners, enforcement points, and safe
      defaults. Keys, owners and defaults are in `EntitlementKeys`; the
      enforcement *points* inside product modules are not placed yet, which is
      the ADR's own named risk and is tracked on the line below.
- [x] Add plan, subscription, override, usage, aggregate, and adjustment tables.
      `V0033`, with plan immutability, ledger append-only-ness and four eyes
      enforced by triggers and constraints rather than by convention.
- [x] Implement immutable plan activation and subscription state machine.
- [x] Implement typed local entitlement resolution. Invalidation is not built
      because no cache is: PostgreSQL is read on every resolution.
- [ ] Implement idempotent usage consumers, rebuild, period close, and export.
      The meter, its idempotency, adjustments and rebuild are built and tested
      (`UsageMeteringService`, `JdbcUsageStore`). No consumer is wired to the
      inbox and nothing in any product module records a movement, so the ledger
      is empty by construction; there is no period close and no invoice export.
- [ ] Integrate onboarding, tenant lifecycle, and product enforcement points. None of the three is integrated: `ordering`, `catalog`, `notifications` and the rest reference no commercial type, and ADR 0008 onboarding creates no subscription.
- [x] Build platform-admin/control-plane APIs with approvals and audit.
- [ ] Add metering/limit dashboards, alerts, reconciliation, and support
      runbooks. Reconciliation exists as a rebuild that reports divergences; the
      dashboards, alerts and runbooks do not.
- [x] Add race, boundary-time, rebuild, authorization, and isolation tests.
      `EntitlementBoundaryTests` covers every mode at the limit, the ceiling,
      override expiry and period boundaries; `CommercialPlatformTests` covers
      immutability, duplicate movements, rebuild equality, suspension, and
      cross-tenant isolation against PostgreSQL.

## Exit criteria

Every tenant has a versioned plan/subscription and explainable entitlement
snapshot; features and limits are enforced consistently without remote billing
dependency or tenant-specific code; usage can be rebuilt exactly; and
suspension/offboarding preserve data and safe operational access.
