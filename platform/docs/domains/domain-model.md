# Domain model and invariants

## Ownership map

| Module | Owns | Does not own |
|---|---|---|
| `iam` | Keycloak subject links, principals, memberships, scoped role grants | Passwords, sessions, MFA policy |
| `tenancy` | Tenants, brands, locations, customer identity policy, configuration inheritance, onboarding | Orders, provider business state |
| `customers` | Tenant customer accounts, brand profiles, addresses, consent, devices | Keycloak credentials |
| `catalog` | Catalogs, categories, products, variants, modifiers, location offerings | Inventory quantities, order snapshots |
| `pricing` | Price lists, prices, promotions, coupons, calculated adjustments | Payment transactions |
| `inventory` | Positions, reservations, movements, availability calculation | Product identity |
| `ordering` | Carts, immutable commercial orders, acceptance policies and decisions, order transitions | Payment, POS-export, and shipment lifecycles |
| `payments` | Payment intents, attempts, transactions, refunds, reconciliation | Order acceptance decisions |
| `fulfillment` | Shipments, assignments, delivery partners, courier tracking, delivery zones | Order commercial totals |
| `integration` | Installations, provider bindings, POS mappings/sync, inbox/outbox, Camel adapters | Domain decisions and aggregate state |
| `media` | Asset upload, validation, derivatives, visibility, retention | Product or brand ownership rules |
| `notifications` | Preferences, templates, notifications, attempts | Order decisions |
| `commercial` (planned) | SaaS plans, subscriptions, entitlements, overrides, usage | User authorization, customer-order payments |
| `recovery` (planned) | Service-recovery cases, remedy decisions and execution coordination | Payment/refund execution, price calculation, shipment cost facts |
| `reporting` | Tenant-aware read models, exports, operational reporting | Authoritative business writes |
| `audit` | Immutable security and business audit facts | Mutable workflow state |

Modules communicate through public application APIs and versioned domain
events. A module never imports another module's internal entity or repository.
The planned rows become physical module/schema boundaries only when ADRs 0013
and 0021 are approved for implementation.

## Tenancy and identity

### Tenant

A tenant is the legal and commercial SaaS customer. It is the primary data
isolation, subscription, billing, administration, and configuration boundary.
Its stable identity is independent from its mutable display name and domain.

Invariants:

- Tenant slug is globally unique and normalized.
- Default currency is an ISO 4217 code.
- Default timezone is an IANA timezone.
- The immutable Keycloak organization ID is unique when provisioned.
- Activation is permitted only after required onboarding checks pass.

### Brand

A brand is a customer-facing identity owned by one tenant. Products, catalogs,
themes, and customer-facing presentation are brand-scoped.

Invariants:

- A brand never changes tenant.
- Brand slug and code are unique within its tenant.
- A brand cannot reference media, catalogs, or configuration from another
  tenant.

### Location

A location is one physical or virtual fulfillment point owned by exactly one
brand.

Invariants:

- A location never operates for multiple brands.
- Location code and slug are unique within its brand.
- Tenant and brand ancestry are enforced with composite foreign keys.
- The location retains its IANA timezone for schedules and local presentation.
- Orders reference tenant, brand, and location as one database-enforced
  hierarchy.

### Principal and membership

`Principal` is Qoida's stable link to a Keycloak subject. A principal can have
many tenant memberships. Tenant, brand, and location role grants scope what a
membership can do; they are application authorization facts, not credentials.

The client selects an active tenant context. The API verifies that context
against the authenticated principal and current membership instead of trusting
an arbitrary tenant header.

## Customer identity

`CustomerAccount` is the tenant-level technical customer anchor. It can link to
a Keycloak principal or represent a guest. `CustomerBrandProfile` contains
brand-specific name, consent, loyalty, preferences, and presentation data.

The control-plane policy has two modes:

- `TENANT_SHARED`: approved customer history and customer-level features may
  operate across the tenant's brands.
- `BRAND_ISOLATED`: customer search, history, consent, loyalty, and profile
  access remain brand-scoped, even when the same Keycloak login is used.

Changing the mode after customer data exists requires an explicit, audited
merge or split migration. A control-plane toggle alone cannot reinterpret
existing customer data.

## Catalog, pricing, and inventory

- A catalog and product belong to one brand.
- A product may appear in more than one catalog owned by that same brand.
- Every sellable product has at least one variant, including a default variant.
- Modifier groups are brand-owned and may be assigned to multiple products in
  that brand.
- A location offering joins a location to a same-brand variant and controls
  sellability, schedule, preparation metadata, and availability.
- Price lists are brand-owned and assigned to locations. Money is stored as
  integer minor units plus currency.
- Inventory is never duplicated on product and location-offering rows.
  Quantity-tracked products use a position, reservations, and an append-only
  movement ledger.
- POS data is imported into staging. New POS products can become draft Qoida
  products; differences never silently overwrite authoritative fields.

## Ordering

An order is an immutable commercial record after placement. Mutable lifecycle
status is explicit, but the customer, address, item, modifier, tax, discount,
price, total, currency, and acceptance-policy facts used at checkout are stored
as snapshots.

An order belongs to one tenant, brand, and fulfillment location. It may have
multiple payment attempts and shipments even if the first release normally
uses one shipment.

Acceptance configuration resolves in this order:

```text
Location override -> Brand override -> Tenant default -> Platform default
```

The resolved policy is copied to the order with its policy ID and version.
Supported modes are:

- `AUTO_CONFIRM`
- `RESTAURANT_APPROVAL`

Restaurant approval uses `EITHER`: Qoida Operations and the POS may decide.
The first valid approval or rejection wins with an atomic state transition.
Later responses are retained as stale decisions for audit and diagnostics.
The safe timeout default is `AUTO_REJECT`.

## Payments

A payment intent represents the desired collection for an order. It can have
multiple attempts and transactions such as authorization, capture, void, and
refund. Provider capabilities decide whether authorization and capture can be
separated. Provider IDs and callbacks are idempotent and reconcilable.

For restaurant approval, Qoida authorizes before requesting approval and
captures after approval. Rejection or expiry voids the authorization. When a
provider cannot separate authorization and capture, the control plane must
show that rejection requires a refund.

Fiscal receipts and settlement evidence remain payment-adjacent but have their
own provider-neutral lifecycle and reconciliation. Importing historical receipt
evidence never reissues it. Current legal/provider rules must be approved before
the active target capability is implemented.

## SaaS commercial and service recovery

ADR 0021 proposes a tenant subscription whose immutable plan version resolves
typed entitlements and whose usage facts can be rebuilt. Commercial entitlement
never grants actor authorization. ADR 0013 proposes service-recovery cases and
versioned remedy decisions; payments executes refunds, pricing owns future
benefits, and fulfillment records delivery-cost allocation. Neither capability
mutates the historical order.

## Fulfillment

Fulfillment and shipment state is independent from order state. Delivery
providers implement small capability interfaces for quote, create, cancel,
query, and track. Provider assignments and webhook events are idempotent and
retain external references.

Internal couriers are tenant-scoped principals/workforce profiles, not provider
strings. Dispatch pools/zones, availability/restrictions, assignment, and live
location require explicit privacy/retention and single-winner rules before the
legacy courier workflow is migrated.

## Integrations and POS

An integration installation is tenant-owned and stores non-sensitive settings
plus a secrets-manager reference. Brand/location bindings determine where it
is effective.

POS capabilities are independently discoverable: catalog read, availability
read, approval, order export, cancellation, and preparation status. Unsupported
capabilities fall back to Qoida workflows instead of provider-name conditionals
inside the domain.

Camel owns protocol mediation, authentication, transformation, throttling,
retries, circuit breaking, and dead-letter routing. Domain application services
own every business transition.

## Media

`MediaAsset` stores an immutable tenant-aware object key, content type, byte
size, SHA-256 checksum, visibility, lifecycle status, dimensions, and audit
fields. Business ownership uses constrained relation tables, never an
unconstrained polymorphic owner ID.

ADR 0010 sketches that as media-owned `brand_media`, `location_media` and
`product_media`. What exists is one catalog-owned table, `catalog.media_relations`,
carrying a composite foreign key `(media_asset_id, tenant_id)` against
`media.assets`'s tenant-scoped key (`V0065`), so a relation cannot name another
tenant's asset or one that was never uploaded. It is not brand-scoped by the
database: `media.assets` has no key a brand-scoped reference could point at, so a
brand attaching a sibling brand's asset within the same tenant is still only
refused by the application.

Derivatives are owed rather than rendered inline. Reaching `AVAILABLE` writes a
`media.derivative_jobs` row and a `MediaAssetAvailable` outbox fact in the same
transaction; `MediaDerivativeWorker` claims a job under a lease and renders
outside any transaction, because decoding an image is neither fast enough for a
request thread nor safe to hold a pooled connection across.

## Cross-cutting database invariants

- Use UUID public identifiers, UTC `timestamptz`, and optimistic-lock versions.
- Every tenant-owned table has non-null `tenant_id`.
- Composite keys prevent cross-tenant and cross-brand relationships.
- Monetary values are non-floating integer minor units with currency.
- Quantities, timeouts, versions, and valid-time ranges have check constraints.
- Core searchable facts are columns, not JSONB.
- Business records use lifecycle status and retention; initial cutovers never
  physically delete legacy evidence.
