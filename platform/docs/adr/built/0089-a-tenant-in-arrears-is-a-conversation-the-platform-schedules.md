# ADR 0089: A tenant in arrears is a conversation the platform schedules

- Decision status: Proposed
- Implementation status: Built — V0202's `subscriptions.status_changed_at` and arrears index, `JdbcArrearsStore`, `ArrearsService` (the `ArrearsDirectory` port), `ArrearsController`, `CommercialArrearsReviewSweeper` raising an ADR 0085 incident, tested in `ModulesStatementsAndArrearsTests` and `CommercialArrearsReviewSweeperTests`; the control-plane dunning board. Nothing moves a subscription by itself
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-10 to finish the control plane's remaining waves; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0021, ADR 0085, ADR 0088
- Supersedes / Superseded by: —
- Open inputs: how long late is too long (finance) — the review interval defaults to fourteen days and is a setting

## Context

ADR 0021's subscription lifecycle already has the arrears stages: past due,
which changes nothing, and suspended, which stops additions and deletes
nothing. It says lateness is a conversation, not a switch. What was missing
was anywhere to see who is in arrears and for how long, and anything that
reminded someone to have the conversation. IA 5.6 asks for the state machine
and what each stage restricts.

## Decision

1. A subscription records when its status last moved.
2. The dunning board lists every past-due or suspended subscription, longest
   first, with its tenant, plan, days in the stage and last issued statement.
   What each stage restricts is read from the lifecycle the resolver applies,
   so the board and the entitlements cannot disagree.
3. Moving a tenant between stages stays the subscription transition, with its
   reason, expected version and, for suspension, a suspension reason.
4. A sweeper raises a control-plane incident for each subscription past due
   longer than the review interval, and a fresh one each further interval, so
   an operator who closed the first after a call is asked again only when the
   next review is due. It never suspends anyone.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Suspend automatically after N days | ADR 0021 makes lateness a conversation; an automatic switch would lock out a restaurant whose payment is in the post | Finance asks for it with a policy |
| Remind by email or Telegram to the tenant | Messaging a tenant about money is a decision with wording finance owns | Finance writes the message |
| Keep a status history table | The audit log already records every transition; the board needs only the latest | A report needs time in each stage |

## Consequences

### Positive

- Arrears are visible in one place, and the first reminder does not depend on anyone remembering.

### Negative

- Rows that existed before this change read their last update as the time
  their status moved.

### Accepted trade-offs

- The review interval is one setting for the whole platform.

## Specification

- `GET /control-plane/arrears` (`commercial.usage.read`, platform scope).
- `horecaos.notifications.control-plane.arrears-review.after` (default `P14D`),
  `.interval` (default `PT1H`), `.enabled`.
- Incident class `COMMERCIAL_ARREARS_REVIEW`, subject `Subscription`,
  variables `tenantId`, `subscriptionId`, `pastDueDays`.

## Rollout and rollback

Additive. Disabling the sweeper leaves the board.

## Implementation checklist

- [x] Column, index, store, service, controller, sweeper, tests
- [x] Dunning board

## Exit criteria

A tenant moved to past due appears on the board with the day it moved, and
after the review interval an incident names it.

## References

- `docs/frontend-information-architecture.md` §5.6
