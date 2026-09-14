# ADR 0127: A tenant browses and buys its own modules, and reads its own arrears state

- Decision status: Proposed
- Implementation status: Partial — `COMMERCIAL_MODULE_READ`/`COMMERCIAL_ARREARS_READ` exist and are composed into `TENANT_OWNER`/`TENANT_FINANCE`; `CommercialOperationsController.modulesOnSale`/`.modulesHeld`/`.purchaseModule` and `ArrearsController.tenantArrears` are built, capability-enforced, and covered by `CommercialSelfServiceEndpointTests` (positive, missing-capability, and cross-tenant refusal for all four). `frontend/operations`'s `subscription-page` renders the catalogue with inline purchase and the restricted-feature banner, covered by `subscription-page.spec.ts`. Not built: a tenant-reachable `end` to undo a self-purchase (stays `ScopeType.PLATFORM`), and a purchase-confirmation step before the click commits the tenant to the price shown.
- Date proposed: 2026-09-14
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0025 (capabilities), ADR 0087 (sellable modules), ADR 0088 (statements), ADR 0089 (arrears)
- Supersedes / Superseded by: —
- Open inputs: none — every choice below reuses an existing, already-decided mechanism (the ADR 0025 capability model, `ModuleCatalogService.add`'s existing rules, the ADR 0089 arrears lifecycle); the platform owner's acceptance of this record is the only outstanding step

## Context

Finance 8.6's own gap-map summary named three things absent from the
merchant's Subscription & billing screen: a purchasable-module catalogue with
inline purchase, a tenant-visible arrears state with a restricted-feature
banner, and period close. The first two are genuinely missing backend —
`CommercialModuleController`'s catalogue reads (`onSale`, `all`) and
`ArrearsController`'s board are declared `ScopeType.PLATFORM`, which no
tenant grant can satisfy, and nothing lets a tenant add a module to itself:
`CommercialModuleController.add` is `ScopeType.PLATFORM` too, by its own
class doc, "giving a tenant a module is HorecaOS selling it, and a
tenant-scoped grant that reached it would let a restaurant sell itself one."

The third — period close — turns out not to be missing at all. ADR 0088
("A month is closed by issuing a statement", Accepted, Built) already decided
that a month closes when HorecaOS staff issue its statement, deliberately
manual until tax and invoicing are approved. `commercial-api.ts` and
`subscription-page.ts` on this screen already carried a "no period close"
disclaimer copied from ADR 0021's older, unmaintained implementation
checklist rather than from ADR 0088, the record that actually answers it.
This ADR corrects that: there is nothing for T19 to build for period close,
and the disclaimer on the screen is rewritten to say so.

Finance 8/X.4 (invoices the merchant can read) turned out to be built
already, ahead of this wave — `commercial-api.ts`'s `statements`/`statement`/
`statementExport`, `finance-paths.ts`'s three paths, and the statements
section of `subscription-page.html`/`.spec.ts` all pre-date this ADR (wave
39's own commits). This ADR only reaches the two rows still genuinely open:
`8.6`'s purchasable-module catalogue and arrears state.

## Decision

1. **Two new capabilities, tenant-scoped mirrors of the platform-only
   reads they mirror**, per ADR 0025's model of one capability per act
   rather than reusing an existing one whose tenant-scope meaning is
   already something else:
   - `COMMERCIAL_MODULE_READ` (`commercial.module.read`) — browsing the
     modules HorecaOS sells, from the tenant's own console. Not
     `COMMERCIAL_PLAN_READ` again: that capability's tenant-scope meaning is
     already "my own subscription, entitlements and usage"; overloading it
     with "browse the whole catalogue" would make one grant answer two
     questions with no way to hold one without the other.
   - `COMMERCIAL_ARREARS_READ` (`commercial.arrears.read`) — this tenant's
     own place in the arrears lifecycle. Not `COMMERCIAL_USAGE_READ` again,
     for the identical reason: that capability already means "my metered
     usage" to a tenant.
   Both are composed into `TENANT_OWNER` and `TENANT_FINANCE` — the same
   pair that already holds the statement reads this wave's other row
   reaches, alongside `COMMERCIAL_PLAN_READ`/`COMMERCIAL_USAGE_READ`.
2. **The inline purchase reuses `COMMERCIAL_SUBSCRIPTION_MANAGE` unchanged,
   only at a new scope.** That capability was already composed into
   `TENANT_OWNER`/`TENANT_FINANCE` (`PlatformRole.java`) before this wave,
   with no tenant-reachable endpoint anywhere declaring `ScopeType.TENANT`
   for it — a grant nothing could exercise. `CommercialOperationsController
   .purchaseModule`, a new `POST /api/v1/tenants/{tenantId}/commercial
   /modules`, declares exactly that scope and reuses `ModuleCatalogService
   .add` unchanged: the same on-sale check, the same per-unit quantity rule,
   the same one-live-instance-per-module guard the platform-admin route
   enforces. `PlatformRoleTests.aTenantAdminHasNoCommercialOrExecutionAuthority`
   already asserts `TENANT_ADMIN` does not hold this capability, so the
   split this ADR relies on — purchase execution stays with owner and
   finance, the same pair that already holds `refund.execute` — was decided
   before this wave, not invented by it.
3. **A purchase reason is a fixed, non-PII string
   (`"Purchased from the operations console"`), not a field a merchant
   types.** Every other capability-gated write in `commercial` takes a
   free-text reason because a platform-admin or finance actor is making a
   documented judgment call. A tenant clicking "Add" on its own module
   catalogue is not making that kind of call — the audit trail already
   states who (the calling subject, ADR 0027) and what (which module); a
   forced text box would be UX friction with no reader who benefits from it.
4. **The arrears read is a new, single-tenant endpoint, not the existing
   board filtered.** `ArrearsController.board` only returns `PAST_DUE`/
   `SUSPENDED` rows — a tenant in good standing is not on it. A tenant is
   owed an answer either way, so `JdbcArrearsStore.forTenant` drops the
   status filter (mirroring `JdbcSubscriptionStore.findLive`'s own
   `status NOT IN ('TERMINATED', 'EXPIRED')`) and `ArrearsController
   .tenantArrears` answers `ACTIVE` with nothing restricted rather than a
   404 for the common case.
5. **The restricted-feature banner renders only when something is actually
   restricted** (`additionsBlocked` or plan entitlements not applying), not
   as a permanent status widget. A banner that always shows, healthy or not,
   stops meaning anything by the time a merchant needs to notice it.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Reuse `COMMERCIAL_PLAN_READ`/`COMMERCIAL_USAGE_READ` at `TENANT` scope for the catalogue and arrears reads instead of two new capabilities | Both already carry a settled tenant-scope meaning (own subscription; own usage). A capability answering two unrelated questions cannot be granted for one without the other, which is a worse authorization story than one more enum constant | Never — this is a one-time cost, not a debt |
| A tenant-typed purchase reason, matching every other `commercial` write | No reader benefits from free text on a self-service "buy" click the way finance benefits from a platform-admin's documented reason on a subscription change; the audit trail already has who and what | A future compliance requirement asks for a reason on every money-adjacent tenant action, not only staff ones |
| Gate the purchase under a brand-new capability instead of the already-tenant-bundled `COMMERCIAL_SUBSCRIPTION_MANAGE` | `PlatformRole.java` already granted this capability to `TENANT_OWNER`/`TENANT_FINANCE` at `TENANT` scope before this wave with no endpoint to exercise it — introducing a parallel capability would leave that grant dead and add a second one doing adjacent work | Never — this is exactly what that grant was for |
| Build a full self-service billing/payment step before letting a purchase take effect | ADR 0088 already decided billing is separate from what is owed: a purchase adds a `tenant_module` row, and the next statement bills it, the same as when platform-admin adds one today | ADR 0095 (wallet, blocked) changes how a statement is paid |
| Treat "period close" as still open and build a self-service or automatic close for this wave | ADR 0088 (Accepted, Built) already decided a month closes by HorecaOS staff issuing its statement, deliberately manual until tax/invoicing are approved — reopening that here would be relitigating an Accepted decision this wave has no standing to change | Never, unless ADR 0088 itself is superseded |
| Show the restricted-feature banner unconditionally, with a "good standing" state for healthy tenants | A banner that is always present stops being noticed the one time it matters; the plan card above it already shows status and any suspension reason for the healthy-path reader | A designer decides the console wants a permanent account-health widget, a broader UI decision than this row |

## Consequences

### Positive

- A tenant can, for the first time, see what HorecaOS sells beyond its
  current plan and add a module to itself without opening a support ticket
  — the "inline purchase" the gap map named as the credibility gap against
  a support-ticket-only flow.
- A tenant past due or suspended can see why, since when, and what it
  restricts, instead of discovering a restriction only by being refused
  somewhere else in the console.
- Both new capabilities are exactly the shape ADR 0025 asks for: one
  capability per act, mirrored at tenant scope rather than reused from an
  unrelated platform-scope read.
- The purchase path adds no new mutation logic — `ModuleCatalogService.add`
  is exercised exactly as the platform-admin route already exercises it,
  so there is one code path to trust rather than two.
- The stale "no period close" disclaimer this screen has carried since wave
  39 is corrected, closing a real documentation drift rather than adding a
  new capability to paper over it.

### Negative

- `COMMERCIAL_SUBSCRIPTION_MANAGE` now authorizes two different acts at two
  different scopes from the same grant a tenant owner already held — giving
  a tenant a module (HorecaOS staff, `PLATFORM`) and a tenant buying one for
  itself (`TENANT`) — which reads, at a glance, like the same capability
  means two different things depending on which endpoint enforces it. It
  does: the scope is the whole of the distinction, and nothing here renames
  either endpoint to make that clearer from the capability code alone.
- A purchased module is billed on the tenant's next statement with no
  confirmation step naming the price before the click — the catalogue table
  shows the price, but there is no "you are about to commit to N per month"
  interstitial. A merchant who does not read the price column purchases a
  module it did not mean to.
- The restricted-feature banner is read-only: it explains a restriction, it
  does not offer a way to resolve one (no self-service payment, since ADR
  0095 is blocked). A suspended tenant reads why but still has to contact
  HorecaOS to act on it.
- `modulesHeld` is a second read on the same screen load (three new calls in
  total: catalogue, held, arrears), adding to an already multi-request page
  load. None of the three is expensive, but the page now makes seven calls
  where it made four.

### Accepted trade-offs

- No purchase-confirmation step and no undo: `CommercialModuleController.end`
  stays `ScopeType.PLATFORM` — this ADR adds no tenant-reachable `end` to
  match the new tenant-reachable `add` — so a merchant that regrets a
  purchase has to ask HorecaOS to remove it. Building the symmetric
  self-service end is not this wave's brief and is left for a future one to
  pick up if it turns out to matter.

## Specification

- `GET /api/v1/tenants/{tenantId}/commercial/modules` (`COMMERCIAL_MODULE_READ`,
  `TENANT`) — mirrors `CommercialModuleController.onSale`.
- `GET /api/v1/tenants/{tenantId}/commercial/modules/held` (`COMMERCIAL_MODULE_READ`,
  `TENANT`) — mirrors `CommercialModuleController.tenantModules`.
- `POST /api/v1/tenants/{tenantId}/commercial/modules` (`COMMERCIAL_SUBSCRIPTION_MANAGE`,
  `TENANT`, mutating) — `{ moduleId, quantity? }`, reuses `ModuleCatalogService.add`.
- `GET /api/v1/tenants/{tenantId}/commercial/arrears` (`COMMERCIAL_ARREARS_READ`,
  `TENANT`) — this tenant's own `status`, `planEntitlementsApply`,
  `additionsBlocked`, `allowedNext`, `since`, `daysInStatus`,
  `suspensionReason`, `latestStatement`.
- `JdbcArrearsStore.forTenant(tenantId)` — the same five-table join as
  `board`, filtered by `tenant_id` instead of status, `status NOT IN
  ('TERMINATED', 'EXPIRED')`.
- No migration: every table read or written (`commercial.modules`,
  `commercial.tenant_modules`, `commercial.subscriptions`) already exists
  with its grants, since V0201/V0202.
- `frontend/operations`: `commercial-api.ts` gains `SellableModuleView`,
  `TenantModuleView`, `TenantArrearsView` and `modulesOnSale`/`modulesHeld`/
  `purchaseModule`/`arrears`; `finance-paths.ts` gains the three paths;
  `subscription-page.ts`/`.html` render the catalogue with an inline
  purchase button and the restricted-feature banner; all three i18n
  catalogues gain the new strings.

## Rollout and rollback

Additive only: two new capability constants, two new tenant roles' bundles
gain them, three new endpoints under an existing controller's path prefix,
no schema change. Rollback is reverting the wave's commits; no data was
written that a rollback would need to reconcile, since a purchase during the
window between deploy and any rollback is a real, valid `tenant_modules` row
under the same rules the platform-admin route already enforces.

## Implementation checklist

- [x] `COMMERCIAL_MODULE_READ`/`COMMERCIAL_ARREARS_READ` capabilities, composed
      into `TENANT_OWNER`/`TENANT_FINANCE`
- [x] Tenant-scoped catalogue read, held-modules read, and inline purchase
      endpoints on `CommercialOperationsController`
- [x] Tenant-scoped single-row arrears read on `ArrearsController`
- [x] `frontend/operations` client, paths, and screen sections
- [x] i18n in all three catalogues
- [x] Java endpoint tests: capability enforcement and cross-tenant refusal
      for all three new reads plus the purchase mutation
- [x] Angular specs: catalogue render, purchase success/failure, and the
      restricted-feature banner's presence and absence

## Exit criteria

A `TENANT_OWNER` or `TENANT_FINANCE` principal can open Subscription &
billing, see every module HorecaOS sells with which ones the tenant already
holds, add one with a single click, and — when the subscription is past due
or suspended — see a banner naming the state, how long it has held, and the
latest statement, all without a platform-admin capability or a support
ticket. A `TENANT_ADMIN` or a principal scoped to a different tenant is
refused on every one of the four new endpoints.

## References

- ADR 0025 — fine-grained authorization and capability model
- ADR 0087 — sellable modules
- ADR 0088 — a month is closed by issuing a statement
- ADR 0089 — a tenant in arrears is a conversation the platform schedules
- `platform/docs/operations-gap-map.md` rows `8.6`, `8/X.4`
