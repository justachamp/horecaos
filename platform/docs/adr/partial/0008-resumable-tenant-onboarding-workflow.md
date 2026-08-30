# ADR 0008: Resumable tenant onboarding workflow

- Decision status: Accepted
- Implementation status: Partial — eleven of the twelve catalogued steps have
  handlers as of 2026-08-30; only `TENANT_ACTIVATE` has none, by design (it
  parks awaiting a platform-admin's approval). The seven formerly-BLOCKED
  validators are real and `requiredInV1`: six live in `tenancy`
  (`OnboardingStepHandlers`, five reading sibling schemas via SQL because a
  genuine module cycle forbids the api edge — documented in the class javadoc)
  and the activation smoke test lives in `ordering`, where its pricing
  dependency is legal. `TenantOwnerLinkOrInvite` now grants `tenant-owner`
  through `GrantManagementService.grantSystemInitiated` after membership
  succeeds, and `OnboardingFullRunIntegrationTests` proves a realistic
  cash-only pickup tenant reaches `READY` on every step with a real ADR 0025
  grant in `iam.grants`. The stuck-run alert is wired end to end.
  All five onboarding facts
  (`TenantOnboardingStarted`, `TenantOnboardingStepCompleted`,
  `TenantOnboardingFailed`, `TenantReady`, `TenantActivated`) are published
  through the ADR 0004 outbox in the same transaction as the transition they
  describe, keyed by tenant because onboarding order is tenant-scoped, carrying
  error codes rather than error text and never the owner's email;
  `refreshRunStatus` updates only on a real transition, so `READY` emits its fact
  once instead of on every scheduler pass. Every claim in this
  Implementation status line was re-read against the code on 2026-08-25, and
  three were wrong; all three are corrected here. The first and worst: this line
  said the v1 workflow runs, and it did not. `dueRuns` selected
  `DISTINCT r.id ... FOR UPDATE OF r SKIP LOCKED`, which PostgreSQL rejects
  outright with `0A000` — so `OnboardingScheduler.drive()` threw on every tick
  and no run has ever advanced outside a test calling `runNextStep` directly. It
  is an `EXISTS` subquery now, and `aRunWaitingForPlatformApprovalIsNeverStalled`
  asserts the query against a real database so a scheduler that cannot claim
  cannot pass again. `horecaos.onboarding.runs.stalled.age.seconds` joins the two
  existing gauges,
  excluding the `TENANT_ACTIVATE` step by name so the signal is "the workflow has
  stopped" and not "a person has not decided yet". That exclusion used to rest on
  the far-future `available_at` the service parks the step with, which is only
  set if the scheduler reaches activation in the same tick that completes the
  last required step — true with the default batch size by exactly one spare
  iteration, false after any retry or with a smaller configured batch, and then a
  tenant correctly awaiting approval aged forever and raised the one alert this
  gauge exists never to raise. The gauge now excludes the step by key, and
  `aRunWaitingForPlatformApprovalIsNeverStalled` drives the real scheduler with a
  batch of four to hold it there. Reading it is the `check_onboarding_stalled`
  stanza in `infra/observability/horecaos-probe.sh`, at one hour — ten times the
  longest a healthy step waits, which is one expired five-minute lease plus one
  five-second poll — in the morning tier, because the tenant this concerns is by
  definition not live, and pointing at
  `docs/runbooks/onboarding-run-stalled.md`. There is deliberately no
  dashboard, because ADR 0023 chose a probe and a dashboard would be a second
  unwatched surface. `V0014` creates
  `tenant.onboarding_templates`, `onboarding_runs`, `onboarding_steps` and
  `readiness_checks` with a partial unique index enforcing one active run per
  tenant; `OnboardingStep` is the twelve-step catalogue with required flags and
  a `blockedUntil` ADR for each step that has no capability yet;
  `OnboardingService` implements claim leases and compare-and-set transitions
  and `OnboardingScheduler` drives runs across replicas with
  `FOR UPDATE SKIP LOCKED`; `OnboardingController` exposes start, read, resume
  and activate behind `TENANT_ONBOARDING_MANAGE` / `TENANT_READ` /
  `TENANT_WRITE`, each mutating one requiring an `Idempotency-Key` — but the
  `cancel` and `validate` endpoints listed under "APIs" below are not built, and
  because that partial unique index counts a `FAILED` run as active, a run that
  cannot be repaired also cannot be replaced; the four v1 handlers exist in
  `tenancy.application.onboarding.OnboardingStepHandlers` —
  `KEYCLOAK_ORGANIZATION_RECONCILE`, `TENANT_OWNER_LINK_OR_INVITE`,
  `DEFAULT_CONFIGURATION_APPLY` and `BRANDS_AND_LOCATIONS_VALIDATE`, the last of
  which has no external effect — so the ADR 0009 organization and membership
  steps are wired in. The second wrong claim was here: this
  Implementation status line said the remaining eight stay visibly blocked, and
  seven do, each with its `blockedUntil` ADR. The eighth is `TENANT_ACTIVATE`,
  which has no handler and no `blockedUntil` because it is not blocked on a
  capability at all — it parks awaiting the platform's approval, which is
  precisely why the stalled gauge has to exclude it. Audit facts
  (`tenant.onboarding_started`, `tenant.onboarding_resumed`,
  `tenant.activated`) and the `horecaos.onboarding.runs.waiting` /
  `.failed` gauges are emitted. The bootstrap gap noted here is closed:
  `JdbcAuthorizationService` now confers `IAM_GRANT_MANAGE` on a caller holding
  the `platform-admin` realm role, so an operator on a fresh deployment can grant
  themselves `TENANT_ONBOARDING_MANAGE` through the ordinary audited API and
  start a run.
- Date proposed: 2026-08-19
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture)
- Depends on: ADR 0004, ADR 0005, ADR 0006, ADR 0030
- Supersedes / Superseded by: —
- Open inputs: none
- Closed inputs: Template v1 requires identity and structure only; activation is platform-approved (2026-08-21)

## Context

Tenant, brand, and location APIs exist, but onboarding still requires manual
coordination. A production SaaS tenant must be provisioned, configured,
validated, and activated through an observable workflow that survives process
restarts and can resume one failed step without repeating completed external
side effects.

## Decision

Implement onboarding as a PostgreSQL-backed application workflow in the
`tenancy` module. Kafka communicates facts to other modules but is not the
workflow state store or timer. Workers claim due steps using SQL leases and
invoke idempotent application ports.

The workflow is:

```text
DRAFT -> PROVISIONING -> CONFIGURING -> VALIDATING
      -> READY -> ACTIVATING -> ACTIVE
                         \-> FAILED -> resume failed phase
```

Current `TenantStatus.PROVISIONING` remains compatible. Any enum/schema
expansion must use an expand-first migration and preserve existing rows.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Temporal | The strongest technical fit for durable execution, and the ergonomics are better than hand-rolled step tables. Rejected on operations: self-hosting needs a datastore cluster, a search index, and a server fleet, which is disproportionate for one twelve-step workflow and a small team. Temporal Cloud removes that burden but adds an external dependency inside tenant provisioning and a cross-border processor to assess under ADR 0034 residency rules | Durable workflows multiply past roughly five distinct processes (onboarding, delivery sourcing, migration, merges, close-out), or a regional managed offering removes the residency question. At that point the step-table pattern becomes the thing being re-invented |
| Camunda or Flowable with BPMN | Strong human-task and modeling support, but adds an engine, a BPMN skillset, and a second state store. Onboarding here is twelve deterministic idempotent steps with no business-analyst authoring requirement | Non-engineers must edit the process, or human task assignment and escalation become a product feature |
| Spring Batch | Job- and chunk-oriented rather than resumable-workflow oriented. It has no natural model for one long-lived run with external side effects, per-step idempotency keys, and reconciliation | Never for onboarding; appropriate for ADR 0024 bulk backfills |
| Kafka choreography with no run or step aggregate | No resumability, no single place to see why a tenant is stuck, and retries can reorder steps. Support would be reduced to reading topics | Never |
| Kafka delayed or scheduled messages as the timer | Kafka is not a scheduler. Delays are approximate, invisible, and impossible to query or cancel per tenant | Never |
| Synchronous onboarding inside one HTTP request | Any Keycloak or provider hiccup fails the whole tenant creation, and a timeout leaves external side effects with no owner | Never |

## Aggregates and tables

### `tenant.onboarding_templates`

Versioned, immutable after use:

```text
id, code, version, status, description
required_steps jsonb
default_configuration jsonb
created_by, created_at, retired_at
unique(code, version)
```

JSON is acceptable for versioned template configuration, but searchable
business status and ownership remain columns.

### `tenant.onboarding_runs`

```text
id, tenant_id, template_id, template_version
status, current_phase, version
started_by, started_at, completed_at, failed_at
last_error_code, last_error
created_at, updated_at
unique active run per tenant
```

### `tenant.onboarding_steps`

```text
id, tenant_id, run_id, step_key, step_version
phase, sequence_number, status, required
attempt_count, available_at
claim_token, claimed_at
input_snapshot jsonb, result_snapshot jsonb
external_reference, checkpoint
last_error_code, last_error
started_at, completed_at, updated_at
unique(run_id, step_key)
```

### `tenant.readiness_checks`

Store each check result, evidence, evaluator version, and evaluation time. A
run cannot reach `READY` while any required check is missing or failing.

## Initial step catalog

1. `KEYCLOAK_ORGANIZATION_RECONCILE`
2. `TENANT_OWNER_LINK_OR_INVITE`
3. `DEFAULT_CONFIGURATION_APPLY`
4. `BRANDS_AND_LOCATIONS_VALIDATE`
5. `PAYMENT_CONFIGURATION_VALIDATE`
6. `DELIVERY_CONFIGURATION_VALIDATE`
7. `POS_BINDINGS_VALIDATE`
8. `CATALOG_READINESS_VALIDATE`
9. `MEDIA_READINESS_VALIDATE`
10. `FRONTEND_DOMAIN_VALIDATE`
11. `ACTIVATION_SMOKE_TEST`
12. `TENANT_ACTIVATE`

Steps whose capability is not yet implemented remain visibly blocked or
optional under an explicitly versioned template; they are never silently
reported successful.

## Step execution contract

```java
interface OnboardingStepHandler {
    String stepKey();
    int stepVersion();
    StepResult execute(OnboardingStepContext context);
}
```

Each handler must define its idempotency key, reconciliation method, retryable
errors, timeout, required evidence, and compensation/rollback behavior. External
create calls first check the stored immutable external ID and reconcile provider
state.

## APIs

```text
POST /api/v1/control-plane/tenants/{tenantId}/onboarding-runs
GET  /api/v1/control-plane/tenants/{tenantId}/onboarding-runs/current
GET  /api/v1/control-plane/tenants/{tenantId}/onboarding-runs/{runId}
GET  /api/v1/control-plane/tenants/{tenantId}/onboarding-runs/{runId}/steps
POST /api/v1/control-plane/tenants/{tenantId}/onboarding-runs/{runId}/resume
POST /api/v1/control-plane/tenants/{tenantId}/onboarding-runs/{runId}/cancel
POST /api/v1/control-plane/tenants/{tenantId}/onboarding-runs/{runId}/validate
POST /api/v1/control-plane/tenants/{tenantId}/onboarding-runs/{runId}/activate
```

Creation is idempotent under an `Idempotency-Key`, per ADR 0031. Resume operates only on a
failed/blocked run and never resets completed steps. Activation uses an
optimistic compare-and-set from `READY`.

## Events

Publish versioned facts through the outbox:

```text
TenantOnboardingStarted
TenantOnboardingStepCompleted
TenantOnboardingFailed
TenantReady
TenantActivated
```

Do not publish raw secrets, full configuration, or error stack traces. Partition
by tenant ID because onboarding order is tenant-scoped.

## Authorization and audit

- Platform admins can create, resume, and inspect all runs.
- Tenant owner/admin may inspect and perform explicitly delegated configuration
  steps after the Keycloak organization is linked.
- Only authorized platform activation policy can activate initially.
- Every override, skipped optional step, failed check, resume, cancellation,
  and activation is audited with actor and reason.

## Concurrency and scheduling

Use optimistic versioning on the run and lease tokens on steps. One run may
execute independent validation steps in parallel only after dependencies are
encoded explicitly; the initial implementation executes deterministically by
sequence. A durable SQL scheduler claims due steps with `FOR UPDATE SKIP
LOCKED`. Kafka is not used as a delayed timer.

## Testing

- Process restart resumes the claimed step after lease expiry.
- Completed external steps are reconciled, not created again.
- Two resume/activate requests produce one transition.
- Required readiness failure prevents `READY` and `ACTIVE`.
- Optional steps require template evidence and remain visible.
- Cross-tenant run/step access is rejected by application and composite keys.
- Every state transition emits its outbox fact in the same transaction.
- A production-shaped happy path and failures at every step are covered.

## Rollout and rollback

Create runs for internal test tenants first. Existing tenants receive an
explicit imported/reconciled run; do not fabricate historical step completion
without evidence. Rollback stops workers and returns APIs to read-only while
run/step evidence remains intact.

## Consequences

### Positive

- Tenant provisioning survives restarts, and a failed step resumes without
  repeating completed external side effects.
- Support can answer "why is this tenant not live" from one run and its steps.
- Readiness evidence gates activation, so a tenant cannot be sold a platform
  that is not actually configured.

### Negative

- Qoida now owns workflow infrastructure: leases, timers, retries, and step
  registries are code that must be maintained and tested rather than bought.
- Every new onboarding step must define idempotency, reconciliation, timeout,
  and compensation, which is real design work per step.
- Template versioning means changing onboarding defaults is a versioned change,
  not an edit.

### Accepted trade-offs

- This is a deliberate re-implementation of a subset of what Temporal provides.
  The trade is operational simplicity now against feature depth later, and the
  alternatives table records the exact trigger for revisiting it.
- Deterministic sequential execution is slower than parallel steps. Parallelism
  waits until dependencies are encoded explicitly.

## Implementation checklist

- [x] Confirm template versioning and which checks are required in v1: identity and structure only.
- [x] Add workflow and readiness tables with composite constraints (`V0014`).
- [x] Implement the step catalogue, handler registry, claim leases, and compare-and-set transitions.
- [x] Add start, read, resume, and activate endpoints behind ADR 0025 capabilities.
- [x] Implement the four required handlers, including the structure validation that has no external effect.
- [x] Integrate the ADR 0009 organization and membership steps.
- [x] Publish onboarding events through the outbox.
- [x] Add metrics, audit facts, dashboards, and stuck-run alerts. Metrics, audit facts, and the alert are done: `horecaos.onboarding.runs.stalled.age.seconds` excludes the `TENANT_ACTIVATE` step by name so a run correctly waiting for platform approval is never counted, `check_onboarding_stalled` in `infra/observability/horecaos-probe.sh` reads it at one hour in the morning tier, and `docs/runbooks/onboarding-run-stalled.md` is the one runbook it points at. No dashboard is coming: ADR 0023 chose a probe, and a dashboard would be a second unwatched surface. The runbook is a draft until it has been executed once, like every other in that directory.
- [x] Test resume, blocked steps, readiness gating, double activation, and reconciliation without recreation.

## Exit criteria

A multi-brand tenant can progress through a versioned workflow, a failed step
can resume without repeating completed work, and activation cannot bypass
required readiness evidence.
