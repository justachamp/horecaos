# ADR 0087: A module is sold on its own unit and switches features on

- Decision status: Proposed
- Implementation status: Built — V0201's `commercial.modules` and `commercial.tenant_modules` with the activation trigger, `ModuleCatalogService`, `CommercialModuleController`, module features in `EntitlementResolution` with `EntitlementSource.MODULE`, tested against the migrated schema in `ModulesStatementsAndArrearsTests` and in `EntitlementBoundaryTests`; the control-plane module catalog. A module never raises a counted limit
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-10 to finish the control plane's remaining waves; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0021, ADR 0025, ADR 0027
- Supersedes / Superseded by: —
- Open inputs: which modules HorecaOS sells and at what price (product, finance) — the catalog is empty until someone drafts one

## Context

HorecaOS sells more than plans. A kitchen display is priced per branch, a
kiosk per unit, a white-label app once. ADR 0021 modelled plans and their
entitlements only, so there was nowhere to put a price that is not the
plan's, and nothing a statement could bill such a thing from. The module
catalog screen (IA 5.2) said as much.

## Decision

A module is a price-list entry beside the plans.

1. It has a code, a name, a price in minor units of its currency, and a
   **billing unit**: per tenant, per brand, per branch, per unit, or once.
   Brands and branches are counted from the usage ledger; a per-unit module
   carries the quantity agreed when it is added.
2. It may name **features** it switches on. Only features: raising a counted
   limit stays with the plan or an override, so "why does this tenant have
   that number" keeps one answer.
3. It is drafted by one person and activated by another, like a plan version,
   and its terms are frozen at the database once active. Retiring stops new
   sales; tenants that have it keep it.
4. A tenant has at most one live instance of a module. Adding and ending one
   are audited, with a reason, and ending keeps the row so a past month can
   still bill it.
5. In resolution, a module sits between an override and the plan: it turns on
   a feature the plan left off, and lapses exactly when the plan's
   entitlements do (suspended, ended). The resolved value says it came from a
   module.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A plan per combination of modules | The number of plans multiplies with every module, and a price change touches all of them | Never |
| Modules as overrides | Overrides are exceptional and time-bounded; a sold module is neither | Never |
| Let a module raise a limit | Two sources for one number, and a limit change hidden in a module | A module is sold whose whole point is capacity |

## Consequences

### Positive

- Everything HorecaOS sells has a price that a statement can bill.
- A feature can be sold separately without a new plan.

### Negative

- One more query in every entitlement snapshot.
- A module and a plan can disagree on currency; the statement refuses rather
  than converts.

### Accepted trade-offs

- No proration: a module live on any day of a month bills the whole month,
  as nothing in ADR 0021 is prorated.

## Specification

- `GET /control-plane/modules` (on sale), `GET /platform-admin/commercial/modules` (all).
- `POST /platform-admin/commercial/modules`, `/{id}/activation`, `/{id}/retirement`.
- `GET /control-plane/tenants/{tenantId}/modules`;
  `POST /platform-admin/commercial/tenants/{tenantId}/modules` and `/{id}/end`.
- Capabilities: `commercial.plan.read`, `.manage`, `.activate`;
  `commercial.subscription.manage` to give or end one.

## Rollout and rollback

Additive. With no module drafted, resolution is unchanged.

## Implementation checklist

- [x] Tables, trigger, service, controller, resolution, tests
- [x] Module catalog screen with tenant modules

## Exit criteria

A tenant given a module that names a feature its plan leaves off has that
feature on, and loses it when the module ends or the subscription is suspended.

## References

- `docs/frontend-information-architecture.md` §5.2
