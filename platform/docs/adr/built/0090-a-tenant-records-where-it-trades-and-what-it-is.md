# ADR 0090: A tenant records where it trades and what it is

- Decision status: Proposed
- Implementation status: Built — V0203's `tenant.tenants.country_code` and `business_type`, the seeded `tenant.country.change` platform policy and `tenant.public_holidays` with Uzbekistan's fixed dates; `TenantProfileService`, `TenantProfileController`, the cross-tenant approvals read on `ApprovalRequestController`, `TenantHealthController`, `TenantPlansController`, `SlaBucketController`, holidays on `ReferenceDataController`, tested in `TenantProfileServiceTests` and the controller tests; the control-plane residency, approvals, business types, reference data screens and the directory's new columns. Bulk export and retention override are not platform actions yet
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-10 to finish the control plane's remaining waves; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0027, ADR 0030, ADR 0043, ADR 0050, ADR 0073
- Supersedes / Superseded by: —
- Open inputs: whether a business type should choose the onboarding template (product); the movable holidays of each year (operations, entered as announced)

## Context

A tenant carried a currency and a timezone and nothing else placing it in a
country or saying what kind of business it is, so the directory (IA 2.1),
residency (6.3), business types (8.2) and platform approvals (6.5) screens had
nothing to show, and the directory could say neither which plan a tenant is
on nor whether anything is wrong with it. Reference data (8.3) named national
holidays and default time buckets as missing. Hosting is settled: every
tenant's data lives in one place in Uzbekistan (ADR 0073), and residency
does not vary per tenant.

## Decision

1. **Country.** A tenant records the market it trades in, one of the
   countries the platform serves (Uzbekistan, Kazakhstan, Georgia). Existing
   tenants are Uzbek. Changing it is a residency decision governed by the
   approval model: the action `tenant.country.change` is fail-closed and a
   platform-scope policy is seeded so it needs a second signature from day one.
   The change records the market only; currency and timezone stay as they are.
2. **Hosting.** The residency screen states where all data is hosted, from one
   setting, and does not invent a region per tenant.
3. **Business type.** A tenant records one of a code-owned list of kinds
   (restaurant, café, fast food, bakery, delivery-only kitchen, catering,
   courier service, pharmacy, florist), each with its usual handovers and
   whether it runs a kitchen display. It is recorded, audited and shown; it
   enables and disables nothing, and onboarding still applies the default
   template to every type.
4. **Platform approvals.** One read lists the platform decisions waiting in any
   tenant (a change of country, an activation a policy governs), decided
   through each tenant's own decision route. Bulk export and retention override
   are not actions the platform has; the screen says so.
5. **Directory columns.** The directory shows country and type from the tenant,
   the plan from one read of live subscriptions, and health as a count of open
   problems: dead letters, blocked receipts and POS orders awaiting a decision,
   the same three the tenant's issue queue lists. A count, not a weighted score.
6. **Reference data.** Public holidays are kept per country, either recurring
   on a date or dated for one year; Uzbekistan's seven fixed-date holidays are
   seeded, and the lunar ones are entered as announced. The order time buckets
   are read from the reporting module, where they are fixed per release.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A hosting region per tenant | Nothing hosts a tenant anywhere else; the column would record a choice nobody can make | A second hosting location exists |
| Change currency and timezone with the country | Orders and money already recorded in them would read in the wrong currency | A tenant is moved before it has trading history |
| Business type drives what a tenant may do | Two sources of permission; capabilities and entitlements already decide | Per-type templates are designed |
| A weighted health score | Nobody has decided what a blocked receipt is worth against a dead letter | Support agrees the weights |
| Seed Kazakhstan's and Georgia's holidays | No tenant trades there yet, and a wrong seeded date is worse than an empty list | The first tenant there signs |

## Consequences

### Positive

- Every tenant says where it trades and what it is, and a residency change has a second signature and an audit trail.
- The directory shows at a glance which tenants have something waiting on a person.

### Negative

- A deployment that deletes the seeded policy cannot change a tenant's country until one is authored.
- The directory's plan and health columns are two more platform-wide reads on page open.

### Accepted trade-offs

- New tenants start as Uzbek restaurants until someone records otherwise.

## Specification

- `GET /control-plane/residency`, `POST /control-plane/tenants/{id}/country-change`
  (`tenant.read` / `tenant.write`, platform scope).
- `GET /control-plane/business-types`, `PUT /control-plane/tenants/{id}/business-type`.
- `GET /control-plane/approval-requests` (`approval.decide`, platform scope).
- `GET /control-plane/tenant-plans` (`commercial.plan.read`), `GET /control-plane/tenant-health` (`tenant.read`).
- `GET /control-plane/reference-data` now with holidays; `POST /control-plane/reference-data/holidays`,
  `DELETE /control-plane/reference-data/holidays/{id}`; `GET /control-plane/reference-data/sla-buckets`.
- `horecaos.hosting.country` (default `UZ`).

## Rollout and rollback

Additive; the columns default to the only values in use.

## Implementation checklist

- [x] Migration with policy and holiday seeds, services, controllers, tests
- [x] Residency, approvals, business types, reference data screens; directory columns

## Exit criteria

Moving a tenant to Kazakhstan waits for a second person, happens once it is
approved, and the directory shows its country, type, plan and open problems.

## References

- `docs/frontend-information-architecture.md` §2.1, §6.3, §6.5, §8.2, §8.3
