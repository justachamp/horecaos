# Intent: what a missing approval policy should mean

- **Originator:** surfaced by the adversarial audit of 2026-08-26 (`bb239de`); the decision is the platform owner's
- **Date:** 2026-08-26
- **Status:** Delivered
- **Delivered by:** [ADR 0050](../../docs/adr/built/0050-missing-approval-policy-behavior.md)

## The problem

ADR 0027's maker-checker asks `ApprovalService.requireApproval` before a refund, a courier
payout, a settlement close, a loyalty adjustment or an onboarding step. When no policy row
covers the action, it answers `NotRequired` and the action proceeds on one signature.

Until 2026-08-26 that was unreachable in a different way: nothing could create a policy at
all, so *every* threshold answered `NotRequired` on every deployment. That is fixed —
`ApprovalPolicyService` and `ApprovalPolicyController` now author policies, V0059 grants the
privilege, and a decide path exists so an approval can actually be given.

Which turns a dormant question into a live one. An operator who has configured nothing is
now indistinguishable, at the point of the check, from an operator who has decided this
action needs no second signature. The control is only as good as somebody remembering to
configure it, and nobody is told when they have not.

## Evidence

- `JdbcApprovalService.requireApproval` returns `ApprovalOutcome.NotRequired` when
  `resolvePolicy` finds nothing — by design, and reasonable in isolation.
- Before V0059, `audit.approval_policies` was INSERTed only in three test files, and V0007
  granted the application role `SELECT` on it and nothing else. No production path could
  create one. Every four-eyes control on the platform was inert and nothing was red.
- The audit added a counter for unresolved action codes precisely so this state becomes
  visible rather than silent. It has no history yet.

## What "solved" looks like

An operator can tell, without reading code, which actions are governed and which are not —
and the platform behaves the way the owner intended for the ungoverned ones, deliberately
rather than by default.

## Scope

- **In:** what an unresolved action code should do; how an operator sees the ungoverned set;
  how the answer is configured and changed.
- **Out:** the authoring and decide surfaces themselves, which exist. The threshold model.
  Anything about who may approve — that is ADR 0027's `required_approver_capability` and it
  is now read.

## Affected areas

`audit` (the resolution path and the new counter), ADR 0027, ADR 0030 if the answer is a
resolved configuration key rather than a constant. Every call site that asks for approval:
`payments` remedies, `courier` adjustments and settlements, `loyalty` adjustments,
`tenancy` onboarding, `integration` failure resolution.

## Constraints and open questions

- **Failing closed globally would stop every refund on day one.** Refunds, payouts,
  settlement closes, loyalty corrections and onboarding are not equally risky, and a single
  switch treats them as if they were.
- The recommendation carried out of the audit, for whatever it is worth at stage 2: a
  per-action register resolved through ADR 0030 — `PERMIT` as today's default, `REFUSE`
  opt-in per action code — so `refund.execute` and `courier.payout.authorise` can be closed
  while `tenant.onboarding.*` stays permissive. Sequence it behind evidence: flip an action
  to `REFUSE` only once its unresolved counter has been flat at zero across every tenant for
  a period, because `REFUSE` on an action nobody has configured is an outage.
- A baseline seed of platform-wide policies was written during the audit and deliberately
  **not applied** — it would make every matching action `Pending` immediately. The DML is in
  the audit's workflow output if it is wanted.

## Why not do nothing

Doing nothing is a defensible answer here and should be stated as one rather than defaulted
into. The cost is that a control the owner believes is on is off for any action nobody
configured, and the failure is silent — the audit trail records the action as legitimately
not requiring approval, which is the same record it would write if a policy had genuinely
said so.
