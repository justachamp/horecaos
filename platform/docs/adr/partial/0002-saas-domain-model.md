# ADR 0002: SaaS domain model and order acceptance

- Decision status: Accepted
- Implementation status: Partial — the hierarchy, the acceptance model, customer
  identity and Qoida's price authority are built. `V0003` creates
  `tenant.tenants`, `tenant.brands`, `tenant.locations` with composite
  tenant/brand ancestry foreign keys and `tenant.customer_identity_policies`
  (`TENANT_SHARED` / `BRAND_ISOLATED`, versioned); `V0016`/`V0018` make catalogs
  and products brand-owned with cross-brand reuse blocked by constraint; `V0013`
  holds provider installations, bindings and entity mappings and `V0037` the POS
  catalog staging and reconciliation tables. `V0012` migrated
  `ordering.order_acceptance_policies` onto the shared scoped resolver in
  `tenant.policies`, and `V0022` snapshots `acceptance_mode_snapshot`,
  `acceptance_policy_id/version`, `approval_channel_snapshot` and
  `approval_deadline_at` on every order. A principal may hold memberships in
  several tenants (`AuthenticatedActor.organizationRoles`). Customer identity now
  exists: `V0055` creates the verification challenges, `VerificationChallengeIssuer`
  and `CustomerVerificationService` issue and redeem the one-time code, and
  `StorefrontCustomerIdentityController` turns a proven phone into an account —
  so the identity mode is exercised rather than merely stored,
  `ConfiguredCustomerPolicyLookup` reading it and `JdbcCustomerStore`
  partitioning profiles on it. Price authority is exercised too:
  `PriceAuthoringController` and `PriceAuthoringService` write
  `pricing.price_books`, `pricing.prices` and `pricing.price_book_assignments`
  through `JdbcPricingStore`, behind `PRICING_AUTHOR` and `PRICING_ACTIVATE`, and
  `QuoteService` refuses a brand that has never been through them. Not built: the
  POS half of `RESTAURANT_APPROVAL` — `OperationsOrderController` has
  `POST /{orderId}/approval-decisions`, but nothing outside the `pos` module
  references it and no POS-originated decision path exists, so `POS` and
  `EITHER` approval channels cannot be satisfied; only a Clopos adapter exists —
  r_keeper and iiko have none. The divergence recorded here is closed: the
  versioned `tenant.customer_identity_policies` that `JdbcTenantControlPlaneStore`
  writes is the source of truth, and `ConfiguredCustomerPolicyLookup` reads it —
  through `tenant.current_customer_identity_policy` (`V0063`), the one definition
  of "which row is current". `V0060` backfilled the
  `tenant.tenants.customer_identity_policy` column (`V0017`) it used to read and
  had `trg_customer_identity_policy_mirror` maintain it, and refuses the
  deployment outright where honouring a tenant's real configured mode would
  re-partition customer accounts that already exist. `V0072` drops that column,
  the trigger and `tenant.mirror_customer_identity_mode()`: the trigger tested
  `superseded_at IS NULL` and not `effective_from`, so it mirrored the newest
  policy row rather than the governing one, and no predicate fixes that — a
  trigger fires on a write and the governing row changes at a scheduled cutover,
  an instant at which nothing writes. The mode is now only ever a function of
  a tenant and an instant.
- Date proposed: 2026-08-18
- Date decided: 2026-08-18
- Deciders: Ayubkhon Abbosov (platform architecture), product
- Depends on: none
- Supersedes / Superseded by: —
- Open inputs: Tenant grouping of legacy companies (product, per tenant)

## Context

The foundation intentionally deferred business tables until tenant ownership,
customer identity, catalog ownership, POS authority, order acceptance, and
state transitions were agreed. The legacy Company/Vendor hierarchy does not
represent a multi-brand SaaS tenant and mixes configuration, identity, media,
delivery, and operational concerns.

## Decision

- Model the hierarchy as `Tenant -> Brand -> Location`; a location belongs to
  exactly one brand.
- Allow one Keycloak principal to have memberships in multiple tenants.
- Give each tenant a versioned customer identity mode of `TENANT_SHARED` or
  `BRAND_ISOLATED`, while retaining brand-specific customer profiles in both.
- Make catalogs and products brand-owned and forbid cross-brand catalog reuse.
- Make Qoida authoritative for customer-facing products, prices, and
  availability; POS imports use staging and reconciliation.
- Integrate CLOPOS, r_keeper, and iiko through provider capability ports and
  Camel adapters.
- Resolve order-acceptance configuration from platform, tenant, brand, then
  location and snapshot the effective policy on every order.
- Support `AUTO_CONFIRM` and `RESTAURANT_APPROVAL` modes.
- In restaurant approval, accept decisions from both Qoida Operations and POS;
  the first valid atomic decision wins and late decisions are audited.
- Keep order, payment, POS transport, and fulfillment lifecycles separate.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep the legacy two-level `Company -> Vendor` hierarchy | It cannot represent one legal customer operating several brands, so billing, isolation, and administration would attach to the wrong object. Every downstream ADR would inherit that error | Never |
| Add a region or franchise tier (`Tenant -> Brand -> Region -> Location`) | No product evidence for region-scoped pricing or permissions today. A tier is expensive to remove and cheap to add later as a grouping | A tenant needs region-scoped configuration, pricing, or permission boundaries |
| One global catalog shared across brands | Cross-brand leakage of content, media, and pricing authority, with no single legal owner for a product. Brand isolation is a stated product promise | A franchise model requires a licensed master catalog. Model it then as copy-on-publish into brand-owned rows, never as shared rows |
| Let POS remain authoritative for customer-facing prices and availability | Qoida becomes a passive mirror that cannot run promotions, correct bad data, or serve a menu during a POS outage | Never for customer-facing values. POS stays authoritative for its own operational metadata |
| Support only `AUTO_CONFIRM` acceptance | Restaurants genuinely reject orders. Without an approval mode every rejection becomes a manual cancellation and refund | Never |
| Let only Qoida Operations decide approvals | Restaurants already work inside their POS. Forcing a second console guarantees slow decisions and abandoned orders | Never; POS approval remains optional per capability |

## Consequences

- Tenant grouping is new migration input; it cannot be inferred from legacy
  names or slugs.
- Composite PostgreSQL constraints include tenant and brand ancestry.
- POS capability differences are configuration and adapter concerns, not
  provider-name branches in ordering code.
- A POS failure cannot invalidate a confirmed customer order.
- Changing customer identity mode after customer data exists requires a
  separately approved merge/split migration.
- The canonical diagrams, transitions, processes, and legacy mapping live in
  `docs/domains` and gate physical schema changes.
