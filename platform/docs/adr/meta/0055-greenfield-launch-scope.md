# ADR 0055: HorecaOS launches greenfield; legacy migration is a later program

- Decision status: Accepted
- Implementation status: Not applicable — a scoping decision; its effect is the execution
  order and what gates a launch, not code.
- Date proposed: 2026-08-30
- Date decided: 2026-08-30
- Deciders: platform owner
- Depends on: 0002, 0024, 0052
- Supersedes / Superseded by: —
- Open inputs: none

## Context

The migration-shaped plan inherited from Qoida gates every launch behind legacy
concerns: Phase 0 production discovery of the milliy system, credential rotation there,
17 `DECIDE` legacy-table families, and a `CUTOVER_READY` gate whose register mixes
questions a greenfield tenant never asks (what happens to legacy favourites?) with
questions any launch asks (which legal entity fiscalizes?). That framing is why the
predecessor stalled: the code outran a plan whose gates only a legacy cutover could open.

The owner decided on 2026-08-30: the platform goes to production **from scratch**, with
new tenants onboarded natively. Migrating existing restaurants becomes a separate
program that starts only after production exists.

## Decision

- HorecaOS's first production launch serves greenfield tenants only. No legacy data,
  identity, or traffic is in scope for it.
- The legacy register's `DECIDE` items and the milliy-facing phases (production
  discovery, credential rotation, backfill, shadow, cutover, retirement) move to the
  future migration program. They stop gating launch. The `migration` module and ADR 0024
  remain in the tree, dormant.
- The launch path is: complete the Angular storefront, build the Operations application,
  wire payments (fake-provider suite first, per the roadmap rule; a real provider
  sandbox when credentials exist), finish tenant onboarding — proven in a dev/test
  environment first; production deployment is its own later step.
- The Flutter customer app is on hold. The Angular storefront is the customer surface
  for launch.
- The minimum-viable-cutover document's slice definition (one tenant, one brand, one
  location, real paid orders) stands, reread as a greenfield launch rather than a
  cutover.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Launch by migrating one existing restaurant | Ties the first production deploy to legacy discovery, credential rotation, and 17 unresolved data dispositions — the exact coupling that stalled Qoida | The migration program starts, after production is stable |
| Keep Flutter in the launch scope | Two customer surfaces to finish before any customer exists; the storefront is further along and web ships without app-store latency | A pilot tenant asks for the app, or storefront limits show up in real orders |
| Delete the migration module until needed | Deleting working, tested code to re-import later buys nothing; dormant code costs only build time | Never — revisit only if it impedes a change |

## Consequences

### Positive

- The launch gate shrinks to things a new tenant actually needs; the 14-item register
  reduces to the handful that bind any launch (legal entity and fiscalization, SLOs,
  hosting) — decided when production deployment is planned, not before dev/test.
- The milliy system stays untouched and serving its restaurants while HorecaOS proves
  itself.

### Negative

- Existing restaurants (Rayhon, Marmar, JizBiz) see no benefit until the migration
  program runs; two systems operate in parallel longer.
- The migration knowledge in the docs ages; the future program must re-verify it.

### Accepted trade-offs

The legacy credentials committed to milliy's git history remain unrotated for now.
Acceptable only because HorecaOS shares no secret with it and touches nothing there;
that rotation is the migration program's first act, and sooner would still be better.

## Specification

Execution order for launch, each stage proven in dev/test before the next:

1. Storefront completed end to end against the platform API.
2. Operations application: the order board a restaurant runs on (`RESTAURANT_APPROVAL`).
3. Payments wired through the fake-provider suite; provider sandbox behind credentials.
4. Tenant onboarding workflow completed so a pilot tenant is created through the API.
5. Dev/test environment exercising 1–4 together.
6. Production deployment planning (hosting, domain, TLS, secrets, observability — ADR
   0023's open items) as its own subsequent effort.

Non-blocking quality work (client generation, static-analysis promotion, decomposition,
OIDC convergence) proceeds alongside without displacing 1–5.

## Rollout and rollback

This record and a dated note in the minimum-viable-cutover document. Rollback is
superseding this record if the launch strategy changes.

## Implementation checklist

- [x] Dated scope note added to docs/minimum-viable-cutover.md

## Exit criteria

Not applicable — the proof of this decision is the launch itself arriving without a
legacy gate ever being consulted.

## References

- ADR 0024 — legacy data migration (dormant until the migration program)
- [Minimum viable cutover](../../minimum-viable-cutover.md)
- [Founding review](../../../../docs/qoida-review.md)
