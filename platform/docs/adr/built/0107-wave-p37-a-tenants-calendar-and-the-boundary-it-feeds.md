# ADR 0107: A tenant's own calendar, the boundary it feeds, and the SLA buckets that stay the platform's to set

- Decision status: Proposed
- Implementation status: Built — `OperationsOrderOutcomeReasonController` (edit, `allowedFulfillmentModes`, moved off control-plane), `BusinessCalendarController`/`BusinessCalendarService`/`JdbcBusinessCalendarStore` (weekend, tenant holidays, boundary editor over `BusinessDayService.setBoundary`, gated by `ApprovalAction.REPORTING_BUSINESS_DAY_BOUNDARY_CHANGE`), `ReportingController.slaBucketSet` (tenant-readable version card), `BranchTagController`/`BranchTagService`/`JdbcBranchTagStore` (registry, archive, location × tag assignment), `V0256`–`V0258`, and `reference-data-page.ts`'s edit dialog, calendar card, SLA card and tag matrix, plus `provenance-banner.ts` surfacing `businessDayStart`. Not built: automatic `tenant.service_schedule_exceptions` creation from a holiday (settings.md says "offer", never "silently create" — deferred rather than half-built), and any recut *execution* triggered from this screen — a boundary change marks `recut_completed_through` null and leaves the recut to `DayCloseService`
- Date proposed: 2026-09-12
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0025, ADR 0027, ADR 0029, ADR 0031, ADR 0039, ADR 0043, ADR 0050, ADR 0057, ADR 0090
- Supersedes / Superseded by: —
- Open inputs: Delever's forecast-window default of 09:00→09:00 for the business-day boundary — the matrix records this as open, and this wave answers "can a tenant set its own boundary" rather than "what should the platform default to" (platform owner); whether a boundary change should default to `REQUIRE_CONFIGURED_POLICY` rather than `ALLOW_WITHOUT_APPROVAL` once real tenants have used it (platform owner, per ADR 0050's own revisit discipline — see the alternatives table below); whether a holiday entry should gain the "offer to create schedule exceptions" flow settings.md names, and who owns designing that offer so it never becomes a silent side effect (platform owner / whoever takes 10.2's Hours tab next)

## Context

`docs/operations-gap-map.md`'s 10.10 row named four separate problems under
one settings screen, and they needed one ADR rather than four because the
same tenant-configurability question threads through three of them.

**10.10a looked finished and was not.** `OrderOutcomeReasonController`
already had `PUT /{reasonId}` — list, categories, create, update and archive
were all implemented — but the operations console's own API client
(`reference-data-api.ts`) never called the update endpoint, so the only way
to correct a reason's stock disposition, liability party or default refund
was archive-and-recreate, silently discarding the version history ADR 0039
built specifically to survive a rename. `allowedFulfillmentModes` was typed
on both sides of the wire and rendered on neither: `ordering.order_outcome_reasons`'s
own check constraint requires a `COMPLETION` reason to carry a non-empty
mode list, and the create form never set one, so every completion reason
ever authored through this screen was one `INSERT` away from a constraint
violation the operator would read as a mysterious 500. And the screen
called `/api/v1/control-plane/tenants/{tenantId}/order-outcome-reasons` from
`apps/operations` — an ADR 0031 surface violation with an established fix
already in this codebase (`OperationsProviderInstallationController`'s
delegate pattern, wave 53).

**10.10b and 10.10c share one fact: ADR 0043 already decided how much of
"the business calendar" a tenant may change, and the frontend information
architecture and this screen's own doc had drifted from that decision in
opposite directions.** `BusinessDayService.setBoundary` existed with a
Javadoc that named its own caller obligation ("the caller is responsible for
the ADR 0027 approval") and had no caller. `SlaBucketController` existed,
was deliberately read-only by its own Javadoc, and was `PLATFORM_ADMIN`-only
— a tenant could not read it, let alone change it. Meanwhile
`docs/operations-spec/settings.md` promised tenant-configurable SLA buckets
at two separate lines (1105, 1325), the exact thing ADR 0043 refused. One of
the two had to give, and it is not ADR 0043: `SlaBucketSet`'s own Javadoc
gives the reason a version-per-release exists — a tenant-editable bucket
boundary rewrites the meaning of every chart already drawn, silently, with
no column anywhere recording that it happened.

**10.10d had nothing at all.** No table, no endpoint, no screen. Delever's
own equivalent page ships empty, which settings.md already noted as
acceptable to defer — but the gap map named it as this wave's fourth row.

## Decision

1. **10.10a's edit path is the existing `PUT`, exposed from the surface the
   console actually calls.** `OperationsOrderOutcomeReasonController`
   delegates every method — list, categories, create, update, archive —
   unmodified to `OrderOutcomeReasonController`, the same shape wave 53 used
   for provider installations: one implementation, a second published path.
   The edit dialog shows the "this creates a new version" warning as
   permanent text (`q-inline-alert`, `severity="warning"`), not a dismissible
   confirm — it is a fact about what the button does, true every time, not a
   one-time interruption. `allowedFulfillmentModes` is now a checkbox group
   on both the create and edit forms for a `COMPLETION` reason, required
   non-empty before either form may submit.
2. **A tenant's business calendar is two things, split by what already owned
   each half.** The weekend declaration and the tenant's own holiday list
   are new tenant-schema tables (`tenant.business_calendars`,
   `tenant.business_calendar_holidays`, V0258) — profile facts nothing recuts
   against, the same shape as the platform's own `tenant.public_holidays`
   (ADR 0090). The business-day boundary stays exactly where ADR 0043 put it
   (`reporting.business_day_policies`); this wave adds the caller
   `BusinessDayService.setBoundary` never had, gated by the ADR 0027 approval
   model through a new `ApprovalAction.REPORTING_BUSINESS_DAY_BOUNDARY_CHANGE`.
   Both live behind one `BusinessCalendarController` in the reporting module,
   because the settings screen composes them into one card and the reporting
   module already reads `tenant.tenants` directly for the same boundary
   (`JdbcReportingStore.findTenantTimezone`) — this is that precedent
   extended, not a new crossing.
3. **A boundary change is `ALLOW_WITHOUT_APPROVAL` by default, not
   `REQUIRE_CONFIGURED_POLICY`.** ADR 0050's own registry documents the
   permissive default as normal for a brand-new action; unlike
   `TENANT_COUNTRY_CHANGE`, there is no existing single-signature behaviour
   this preserves, because nothing could call `setBoundary` before this wave.
   A tenant wanting a second signature authors its own `TENANT`-scope
   `audit.approval_policies` row naming this action. The write marks a recut
   outstanding (`recut_completed_through = null`) and states that in its own
   response and on the settings screen; it does not perform the recut, which
   is `DayCloseService`'s job against production volumes.
4. **SLA buckets stay platform-fixed, and the settings screen says so
   instead of promising otherwise.** `ReportingController.slaBucketSet`
   mirrors `SlaBucketController`'s read exactly — same `SlaBucketSet`, same
   shape — at `Capability.REPORTING_READ`/`TENANT` scope instead of
   `PLATFORM_ADMIN`, so a tenant can finally see which version its own
   `/sla-buckets` distribution was computed under. No write path is built.
   `settings.md` lines 1105, 1325 and its "missing" table are corrected in
   place to state the decision rather than the withdrawn promise.
5. **Branch tags are a tenant-owned registry plus an assignment table**
   (`tenant.branch_tags`, `tenant.location_branch_tags`, V0256–V0257),
   archived rather than deleted — the same shape V0029 established for
   outcome reasons, for the same reason: an already-tagged branch must keep
   resolving the tag it carries. The registry is `TENANT` scope
   (`LOCATION_READ`/`WRITE`, reused rather than a new capability — a tag is
   the "rearranging a branch" power `LOCATION_WRITE`'s own doc already
   names, on the tenant-wide object instead of the floor plan); setting one
   branch's own tags is `LOCATION` scope. The settings screen's location ×
   tag matrix is the filter-and-group affordance the gap map named as the
   whole reason to have tags at all.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Build tenant-configurable SLA buckets, closing the frontend information architecture's promise instead of the code's decision | Rewrites the meaning of every historical chart the moment a tenant moves a boundary, with no record that it happened — the exact failure `SlaBucketSet`'s own Javadoc exists to prevent, and doing it now would mean redoing this wave's read-only card as a full versioned-and-recut editor twice | A tenant's promised SLA is provably wrong for its market and the platform owner accepts the recut and historical-comparability cost this would carry |
| Fold the tenant calendar (weekend, own holidays) into `reporting.business_day_policies` as extra columns | Borrows the boundary's versioning semantics for facts that are not versioned today — a tenant's holiday list is not stamped onto historical facts the way the boundary is, and conflating the two would make a future "is this recut-sensitive?" question ambiguous by construction | A report starts varying its computation by weekend or by the tenant's own holiday list, at which point the versioning question becomes real for them too |
| `REQUIRE_CONFIGURED_POLICY` as the boundary change's missing-policy default, matching `TENANT_COUNTRY_CHANGE`'s weight | ADR 0050's own text: the permissive default is normal for a new action, and forcing a seeded policy here means every tenant needs a migration-authored policy before the editor this wave builds does anything at all — a worse first release than a permissive default a tenant can tighten | The unresolved-request signal for this action stops being flat, or the platform owner decides a boundary change deserves country-change-level ceremony regardless |
| Give branch tags their own new capability (`BRANCH_TAG_MANAGE`) instead of reusing `LOCATION_READ`/`WRITE` | `PlatformRoleTests` requires every capability held by a real bundle; a tag is exactly the branch-rearranging power `LOCATION_WRITE` already grants to the same people, and a second capability naming the identical authority would let a role hold one and not the other while a reader assumed they were equivalent | A tenant wants to grant "manage tags" without granting the rest of `LOCATION_WRITE` — nothing in the gap map or settings.md suggests this split matters yet |
| Archive-and-recreate as the answer to "how does a reason get corrected", i.e. build nothing for 10.10a's edit path | Exactly the gap this ADR closes: it discards `OrderOutcomeReasonService`'s own version history, the mechanism ADR 0039 built precisely so a rename never rewrites last year's funnel | Never — this is the defect, not a considered option |

## Consequences

### Positive

- A reason can be corrected without losing its audit trail, and a completion
  reason can no longer be created in a way that already violates its own
  table's check constraint.
- A tenant can finally see, declare and change facts about its own trading
  calendar that were previously either invisible (the boundary), absent (the
  weekend, its own holidays) or wrongly promised (SLA buckets).
- The frontend information architecture and the ADR that governs the same
  subject now agree, in the document a future author will read first.

### Negative

- `BusinessCalendarController` reads `tenant.*` tables from the `reporting`
  module rather than through a tenancy-module API — an accepted crossing,
  not a clean one, and a second instance of a pattern that should eventually
  either get a named port (mirroring `ordering.api.BusinessDayWindows`) or
  stop being repeated.
- A boundary change is `ALLOW_WITHOUT_APPROVAL` today: a single actor with
  `TENANT_CONFIGURATION_WRITE` can move it and leave a recut outstanding with
  no second signature, unless a tenant has authored its own approval policy.
- The settings screen's location × tag matrix fetches the tenant's whole
  assignment set on load rather than paging; fine at the branch counts this
  platform's tenants carry today, not a design for a chain with hundreds.

### Accepted trade-offs

- No UI builds `tenant.service_schedule_exceptions` rows from a holiday yet;
  settings.md's own "offer, never silently create" instruction is honoured
  by not building the offer at all rather than building a silent version of
  it.
- SLA bucket configurability is declined outright by this ADR, not deferred
  behind an open input — a tenant asking for a different SLA promise gets a
  platform release, per `SlaBucketSet`'s own doc, not a settings screen.

## Specification

- `PUT /api/v1/operations/tenants/{tenantId}/order-outcome-reasons/{reasonId}`,
  `DELETE .../{reasonId}` (`ORDER_OUTCOME_REASON_MANAGE`, `TENANT`); reads at
  `ORDER_READ`, `TENANT`.
- `GET|PUT /api/v1/tenants/{tenantId}/business-calendar`,
  `PUT .../boundary`, `PUT .../weekend`, `POST|DELETE .../holidays[/{id}]`
  (`REPORTING_READ` for the read, `TENANT_CONFIGURATION_WRITE` for the four
  writes, all `TENANT` scope).
- `GET /api/v1/tenants/{tenantId}/reporting/sla-bucket-set` (`REPORTING_READ`,
  `TENANT`).
- `GET|POST /api/v1/operations/tenants/{tenantId}/branch-tags`,
  `GET .../assignments`, `POST .../{tagId}/archive` (`LOCATION_READ`/`WRITE`,
  `TENANT`); `GET|PUT .../brands/{brandId}/locations/{locationId}/branch-tags`
  (`LOCATION_READ`/`WRITE`, `LOCATION`).
- `V0256` `tenant.branch_tags`, `V0257` `tenant.location_branch_tags`,
  `V0258` `tenant.business_calendars` + `tenant.business_calendar_holidays`.
- `ApprovalAction.REPORTING_BUSINESS_DAY_BOUNDARY_CHANGE`
  (`ALLOW_WITHOUT_APPROVAL`).

## Rollout and rollback

Additive on every axis: three new migrations, no altered table, no capability
removed or narrowed, no published path dropped (the control-plane-prefixed
`order-outcome-reasons` path stays published and unused, per
`OpenApiContractTests`). Rollback is redeploying the previous build; no data
migration to reverse.

## Implementation checklist

- [x] `OperationsOrderOutcomeReasonController`, edit dialog, fulfilment-mode
      control on create and edit
- [x] `V0256`–`V0258`, `BusinessCalendarController`/`Service`/`Store`,
      `BranchTagController`/`Service`/`Store`
- [x] `ReportingController.slaBucketSet`, read-only card
- [x] `provenance-banner.ts` renders `businessDayStart`
- [x] `settings.md` lines 1105/1325 and the two "missing" table rows
      corrected

## Exit criteria

An operator can rename a cancellation reason and see the version bump, the
old snapshot stay on already-recorded outcomes, and a completion reason
refuse to save with no fulfilment mode selected. A tenant can move its
business-day boundary, see a recut-outstanding warning until one runs, and
read (never change) the SLA bucket version its reports are computed under. A
chain can tag a branch and filter the tag registry by which branches carry
it.

## References

- `docs/operations-gap-map.md` PART A rows `10.10a`–`10.10d`, PART C §P37
- `docs/operations-spec/settings.md` §10.10
- `docs/frontend-information-architecture.md` PART 2
- ADR 0039, ADR 0043, ADR 0050, ADR 0090
