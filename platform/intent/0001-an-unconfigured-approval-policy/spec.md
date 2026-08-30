# Spec: explicit behavior for missing approval policies

- **Intent:** [`intent.md`](intent.md)
- **Author:** Codex, reviewed by Ayubkhon Abbosov in conversation
- **Date:** 2026-08-26
- **Status:** Approved
- **Approver:** Ayubkhon Abbosov (platform owner)

## Summary

Make the absence of an approval policy a code-owned, per-action decision. Existing actions
begin permissive so an unconfigured deployment does not stop all refunds and payouts, while
the already unconditional manual courier penalty fails closed. An operator sees every action,
its mode, and its authored scopes; an author can target a real tenant, brand, or location
rather than a scope label that accidentally covers every brand or location in the tenant.

## Requirements

1. Every production caller of `ApprovalService.requireApproval` uses a registered action
   code with an explicit missing-policy mode; an unknown action cannot inherit a default.
2. `ALLOW_WITHOUT_APPROVAL` preserves a no-policy `NotRequired` outcome, while
   `REQUIRE_CONFIGURED_POLICY` returns stable `APPROVAL_POLICY_REQUIRED` without a side
   effect when no policy resolves.
3. The tenant policy surface returns all registered actions, their mode, and their non-ended
   configured scopes without claiming a scoped row covers the entire tenant.
4. New BRAND and LOCATION policies persist their actual resource identifiers and resolve
   most-specific-first through `ResourceScope.chain()`; cross-tenant resource combinations
   are refused by composite foreign keys.
5. Existing ambiguous BRAND and LOCATION rows survive the migration as visibly flagged
   `legacyScopeWide` fallbacks. An exact new row at the same level wins.
6. Resolution emits a bounded-cardinality counter. Unresolved counters include the mode;
   tenant IDs remain in rate-limited logs, not metric labels.

## Non-goals

- A global fail-closed switch or a deploy-time toggle.
- Seeding thresholds, changing the threshold model, or changing who may approve.
- Moving approval policy rows into `tenant.policies`; they remain audit-owned because an
  approval request snapshots its specific policy row and version. ADR 0030's scope chain and
  code-owned registry rules still apply.
- Retrofitting an exact brand/location identifier into historical rows that never recorded it.

## Design

### Domain impact

`ApprovalAction` is the closed register. Modes change through reviewed source, after the
action's unresolved counter has been flat and the policy coverage output has been checked.
The request command validates the register before resolution, separating an undeclared
developer action from a deliberately unconfigured policy.

### Data

V0082 adds `brand_id`, `location_id`, `legacy_scope_wide`, ancestry foreign keys, exact-scope
version indexes, and a resolution index to `audit.approval_policies`. Existing brand/location
rows become legacy fallbacks; new authoring writes only exact shapes. The existing narrow
application-role `INSERT` and `UPDATE(valid_until)` grants remain sufficient.

### API

`GET /api/v1/control-plane/tenants/{tenantId}/approval-policies/coverage` (under
`approval.policy.manage`) returns registered action codes, their missing-policy modes, a
`configuredAnywhere` indicator, and exact configured policy scopes. Policy authoring accepts
`brandId` for `BRAND` and both `brandId` and `locationId` for `LOCATION`. A fail-closed
business action returns Problem Details code `APPROVAL_POLICY_REQUIRED` (409).

### Events

No event changes. Policy authoring, retirement, cancellation, and supersession stay ADR 0027
security audit facts; end/cancel audit facts now carry the exact policy scope where it exists.

### Integration

Not applicable. No provider contract changes.

## Policy review

| Area | Verdict | Note |
|---|---|---|
| Tenant isolation | Satisfied | Composite resource foreign keys prohibit a brand/location from another tenant. |
| Authorization capability (ADR 0025) | Satisfied | Coverage and authoring remain behind `approval.policy.manage`. |
| Secrets and PII (ADR 0028, 0029) | Satisfied | Metrics contain only bounded action/scope/mode; free-text reasons and tenant IDs stay out of labels. |
| HTTP conventions (ADR 0031) | Satisfied | Fail-closed behavior has a stable 409 Problem Details code. |
| Event contracts (ADR 0032) | Not applicable | No event change. |
| Migration safety | Satisfied | Additive migration retains ambiguous rows as labeled behavior; it does not invent identifiers. |
| Observability and audit (ADR 0027) | Satisfied | Coverage, resolution counters, bounded warnings, and exact-scope audit facts are present. |

## ADR impact

[ADR 0050](../../docs/adr/built/0050-missing-approval-policy-behavior.md) settles ADR 0027's
missing-policy behavior and applies ADR 0030's code-owned register and scope-chain rules to
approval resolution. The existing approval-policy table remains a documented snapshotting
exception to ADR 0030's shared storage until a dedicated migration is separately approved.

## Rollout and rollback

V0082 is additive and preserves existing behavior. Release with all modes except the existing
manual-penalty invariant permissive; observe unresolved resolution rates and coverage before
changing a particular action to fail closed in reviewed source. Rollback leaves the columns and
legacy labels in place but can revert mode changes to permissive; it must not delete policy
evidence or reinterpret a legacy row as exact.

## Acceptance criteria

- `ApprovalActionTests` proves every registered action declares a mode and unknown actions fail.
- `JdbcApprovalServiceTests` proves a fail-closed action is refused and exact brand scope does
  not govern another brand.
- `ApprovalPolicyServiceTests` proves exact authoring/versioning and audit scope behavior.
- `ApprovalPolicyEndpointTests` proves owner-only coverage output and scoped authoring input.
- `make lint` and `make verify` pass.

## Open questions

| Question | Owner | Blocking? |
|---|---|---|
| Per-action observation window and alert threshold before a mode flip | Platform owner | no — required in the change review that flips it |
