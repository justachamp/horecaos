# ADR 0050: Missing approval policy behavior is explicit per action

- Decision status: Accepted
- Implementation status: Built — the code-owned approval-action register defines every live
  action's absent-policy behavior, a fail-closed action returns a stable 409 before mutation,
  tenant policy coverage is visible, and V0082 makes new brand/location policies exact-scoped
  while retaining visibly labeled legacy fallbacks.
- Date proposed: 2026-08-26
- Date decided: 2026-08-26
- Deciders: Ayubkhon Abbosov (platform architecture)
- Depends on: ADR 0025, ADR 0027, ADR 0030, ADR 0031
- Supersedes / Superseded by: settles ADR 0027's missing-policy open input / —
- Open inputs: none

## Context

ADR 0027 supplied the approval workflow but originally treated an absent policy as
`NotRequired`. That was necessary while no authoring surface existed, but it made a
deployment that had forgotten to configure a control indistinguishable from a deployment
that had deliberately chosen one-signature operation. A global fail-closed switch would
make the opposite mistake: the first release would stop every refund, payout, adjustment,
onboarding activation and failure resolution with no safe migration path.

The same investigation found that `audit.approval_policies` recorded a BRAND or LOCATION
label but no resource identifier. Such a row matched all brands or locations at that level in
its tenant. An action cannot be safely rolled out from an ambiguous coverage signal.

## Decision

**The absence of an approval policy is a closed, code-owned per-action decision. There is no
global missing-policy switch.**

- `ApprovalAction` registers every operation that asks for maker-checker approval. Each entry
  declares either `ALLOW_WITHOUT_APPROVAL` or `REQUIRE_CONFIGURED_POLICY`. An unknown action
  is rejected at command construction; it never inherits the permissive mode.
- The initial modes preserve current production behavior: remedy recording and future discount,
  loyalty adjustment, courier payout, ordinary courier adjustment, tenant activation and
  integration failure resolution allow an absent policy. ADR 0042's unconditional manual
  courier penalty is `REQUIRE_CONFIGURED_POLICY` immediately.
- A permissive absent policy returns `NotRequired`. A fail-closed absence returns
  `APPROVAL_POLICY_REQUIRED` (409) with no business mutation. A real persistence/resolution
  failure remains a failure, never an absence.
- Changing one action to fail closed is a reviewed source change, not a tenant switch. Its
  change review must show its unresolved counter flat over the agreed observation window and
  inspect the coverage output. The tenant cannot relax this platform floor.
- `GET /approval-policies/coverage`, behind `approval.policy.manage`, returns every registered
  action, its mode, whether any active policy exists, and the exact active policy scopes. It
  deliberately does not display a brand or location row as tenant-wide coverage.
- V0082 adds `brand_id` and `location_id` plus composite ancestry foreign keys to
  `audit.approval_policies`. New BRAND/LOCATION authoring requires actual resource IDs and
  resolves via `ResourceScope.chain()` most-specific-first. Pre-V0082 rows cannot be assigned
  truthfully, so they retain their prior wide behavior with `legacy_scope_wide=true`; an exact
  current row wins over that legacy fallback. Operators replace legacy rows with exact versions.
- The resolution counter retains `action`, `scope`, and `outcome`; unresolved points add the
  bounded `missing_policy_mode` tag. Tenant identifiers appear in rate-limited warning logs,
  never metric labels or free-text reasons.

The approval-policy table remains audit-owned because its individual row/version is
snapshotted into approval evidence. It adopts ADR 0030's typed code registry and canonical
scope chain but does not silently become a second generic policy writer; consolidating its
physical storage into `tenant.policies` requires a separate migration and decision.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Global fail-closed flag | Immediately blocks every action on tenants without policies, converting a configuration gap into an outage | Never as the first migration; per-action modes remain the control |
| Keep absent policy silently permissive everywhere | A forgotten maker-checker policy remains indistinguishable from a deliberate choice | Never |
| Tenant-controlled fail-closed override | Lets a tenant relax a platform safety floor and makes support diagnose divergent semantics | A commercial isolation requirement explicitly requires tenant-controlled safety modes |
| Seed a policy for every action | Begins requesting approvals for every matching action without proving each threshold or approver is viable | A staged policy-import process has validated every tenant's threshold and approver reach |
| Treat old BRAND/LOCATION rows as exact | Invents a resource identifier the historical row never recorded and changes its meaning silently | Never |
| Move approval policies into generic `tenant.policies` now | Risks losing the approval request's row/version evidence contract in a scope repair | A separately reviewed data migration preserves all request snapshots |

## Consequences

### Positive

- Every absent-policy result has a named, reviewable reason.
- The platform can tighten controls one action at a time without a day-one outage.
- An operator can see both missing action coverage and ambiguous historical rows.
- Exact scoped policies now honor tenant, brand and location boundaries.

### Negative

- Adding an approval-gated action requires a registry edit and tests.
- The permissive entries remain a deliberate risk until evidence supports tightening them.
- Historical broad rows continue to cover broadly until an operator replaces them.

### Accepted trade-offs

The default is permissive only while an action is being observed; a source review is required
to tighten it. That makes rollout slower than a global switch but avoids making unconfigured
money operations unavailable. Legacy rows retain old broad behavior rather than being guessed
into a new scope, trading a clearly exposed migration chore for evidence integrity.

## Implementation checklist

- [x] Register each production approval action and reject unknown codes.
- [x] Apply explicit permissive and fail-closed absent-policy modes.
- [x] Add stable `APPROVAL_POLICY_REQUIRED` Problem Details behavior.
- [x] Expose tenant policy coverage with action mode and exact scopes.
- [x] Add exact brand/location columns, ancestry constraints and resolution ordering.
- [x] Preserve and label old broad rows as legacy fallbacks.
- [x] Prove fail-closed behavior, unknown action refusal and cross-brand isolation.

## Exit criteria

Every `ApprovalService` call is registered; no action can acquire a silent missing-policy
default; a policy author can identify which action/scope remains ungoverned; and a brand or
location policy cannot govern an unrelated resource. `make verify` and `make lint` pass.

## References

- [Intent and approved spec](../../../intent/0001-an-unconfigured-approval-policy/spec.md)
- [ADR 0027: Audit evidence and approval model](../partial/0027-audit-evidence-and-approval-model.md)
- [ADR 0030: Configuration and policy resolution](../partial/0030-configuration-and-policy-resolution.md)
- [ADR 0031: HTTP API conventions](../built/0031-http-api-conventions.md)
