# ADR 0016: Brand catalog, publication, and location offerings

- Decision status: Accepted
- Implementation status: Partial — V0016 (tightened by V0018 and V0046) carries
  catalogs, products, variants, categories, modifier groups and options,
  translations, media relations, `location_offerings`, `publications` and
  `publication_items`; the `catalog` module authors all of them
  (`CatalogAuthoringController` under `catalog.author` / `offering.manage`),
  validates against media, pricing and offering ports (`CatalogValidator`),
  publishes an immutable content-hashed snapshot and republishes an older one
  (`CatalogPublicationService.publish` / `rollbackTo`), and serves the
  location menu with an ETag (`StorefrontCatalogController`). The caveat that
  used to close this line has lifted: ADR 0018's `PriceAuthoringController`
  now writes price books, prices and assignments, so `PricingVariantLookup`
  answers the publication validator from real rows rather than from an empty
  table. A published menu still carries no money of its own, and that is the
  design — money reaches a customer through an ADR 0018 quote, not through
  `publication_items`. Not built: the ADR 0012 apply seam (0012 has no apply at
  all), brand migration and render comparison, the reconciliation reports, and
  any catalog test beyond `CatalogPublicationTests`.
- Date proposed: 2026-08-19
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture), product
- Depends on: ADR 0002, ADR 0010, ADR 0026
- Supersedes / Superseded by: —
- Open inputs: Disposition of legacy tags, recommendations, storefront/FAQ content, and kitchens (product)
- Closed inputs: SPIC/unit/VAT classification semantics — settled in code under ADR 0038 and verified here: V0028 creates `catalog.mxik_reference` and `catalog.fiscal_classifications` with the unit-code and marking-scheme constraints, and constrains `pricing.tax_profiles` to whole-percent rates (`ck_tax_rate_whole_percent`) because Click's `VATPercent` and Payme's `vat_percent` are integer percents; `catalog.api.FiscalVatRate` is the single conversion, and `CatalogAuthoringController` authors a classification per variant, per modifier option and per fee

## Context

Catalogs belong to exactly one brand and are not shared between brands. A
location belongs to one brand, but locations may sell different subsets of the
brand catalog with different schedules and availability. POS is an integration
source used for daily synchronization; after reviewed import, Qoida is the
source of truth. The current model needs explicit product composition,
publication, external mappings, translations, and immutable order-facing
snapshots.

## Decision

The catalog module owns authoring and versioned publication. A catalog is a
brand-owned aggregate container; products, variants, modifier groups, modifier
options, categories, media references, and translations are tenant/brand
scoped. `LocationOffering` determines whether and when a specific location
sells a variant. Inventory determines quantity availability and pricing
determines money; neither is embedded in catalog authoring rows.

The public/storefront read model is built only from an immutable
`CatalogPublication`. Draft edits cannot leak into a live menu.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Serve the storefront directly from authoring tables | Draft edits leak into live menus, and no two reads are guaranteed consistent while an editor is working | Never |
| Publish the full menu payload onto Kafka | Large, frequently changing payloads with awkward versioning, and consumers would hold stale copies of catalog content. Publishing an immutable publication reference instead keeps events small | Never |
| Make products catalog-owned rather than brand-owned | Prevents one product appearing in several of that brand's catalogs, such as a delivery menu and a dine-in menu, and complicates archival | Never |
| Model products as EAV or a JSONB document | Unqueryable and unvalidatable at the database level, which defeats the invariants this ADR exists to enforce. JSONB stays reserved for evidence and provider payloads | Never for core searchable state |
| Keep a `catalog.external_mappings` table alongside the integration mapping table | Two stores for one fact, with no defined winner when they disagree. ADR 0026 owns provider mappings; catalog reads them through a port | Never |
| Allow POS sync to publish directly | A provider export would become a live menu change with no review, undoing the guarantees of ADR 0012 | Never |
| Mutable publications that can be edited after activation | A historical order could no longer be rendered as the customer saw it | Never; rollback republishes a prior snapshot |

## Core invariants

- A catalog, product, category, modifier, and publication belongs to one brand.
- A location offering can reference only a variant from the location's brand.
- Product and variant stable IDs survive renaming and republishing.
- A product has at least one sellable variant before publication.
- Required modifier minimum/maximum selections are satisfiable.
- Packaging requirements are same-brand, acyclic, and use an exact approved
  quantity/unit rule; packaging price, tax, and inventory remain in their owners.
- Archived entities remain resolvable by historical order snapshots.
- POS external IDs are mappings owned by ADR 0026, never Qoida primary keys.

## Physical model

### Authoring tables in schema `catalog`

```text
catalogs
  id, tenant_id, brand_id, name, status, version, timestamps

categories
  id, tenant_id, brand_id, catalog_id, parent_category_id null
  code, sort_order, status, version, timestamps

products
  id, tenant_id, brand_id, code, tax_category_code null
  status, version, timestamps

variants
  id, tenant_id, brand_id, product_id, sku null, unit_code
  status, default_variant, version, timestamps

modifier_groups
  id, tenant_id, brand_id, code
  required, minimum_selections, maximum_selections
  allow_same_option_multiple_times, sort_order, status, version

modifier_options
  id, tenant_id, brand_id, modifier_group_id, code
  linked_variant_id null, maximum_quantity, status, sort_order, version
```

Composite foreign keys include `tenant_id` and `brand_id`. Products are
brand-owned independently of a catalog so one product may appear in several
catalogs belonging to that brand. Modifier groups are also brand-owned and use
explicit assignment tables. Media ownership uses ADR 0010's constrained
relations rather than a nullable media column.

### Content and organization

```text
catalog.category_products
  tenant_id, brand_id, category_id, product_id, sort_order

catalog.catalog_products
  tenant_id, brand_id, catalog_id, product_id, sort_order

catalog.product_modifier_groups
  tenant_id, brand_id, product_id, modifier_group_id, sort_order

catalog.variant_modifier_groups
  tenant_id, brand_id, variant_id, modifier_group_id, sort_order

catalog.variant_packaging_requirements
  tenant_id, brand_id, sold_variant_id, packaging_variant_id
  quantity_numerator, quantity_denominator, version

catalog.translations
  tenant_id, brand_id, entity_type, entity_id, locale
  name, description null, version, timestamps

catalog.location_offerings
  id, tenant_id, brand_id, location_id, variant_id
  status, sales_schedule_id null, fulfillment_modes
  preparation_duration_override null, version, timestamps
```

Catalog does not own provider identifiers. External POS identifiers live in
`integration.provider_entity_mappings`, owned by ADR 0026, and catalog reads
them through a `ProviderEntityMappingLookup` port when it needs to display or
validate a mapping. Two mapping stores would have no defined winner when they
disagree, so the catalog-local table proposed in the first draft of this ADR is
deliberately removed.

Translations use an approved locale list and a deterministic fallback order:
requested locale, brand default, tenant default. A missing required name blocks
publication instead of exposing a database code.

## Publication model

```text
catalog.publications
  id, tenant_id, brand_id, catalog_id, channel
  publication_number, status, source_catalog_version
  content_hash, validation_report, created_by
  created_at, activated_at null, retired_at null

catalog.publication_items
  publication_id, tenant_id, brand_id, entity_type, entity_id
  immutable_content_json, entity_version
```

Lifecycle:

```text
DRAFT -> VALIDATING -> READY -> PUBLISHED -> RETIRED
                   -> REJECTED
```

Publishing takes a transactionally consistent snapshot, validates it, writes a
content hash, then atomically changes the active publication pointer for a
brand. There is at most one active publication per brand/channel combination.
Rollback republishes a previously valid snapshot; it does not edit history.

### What an item carries

A publication item is the whole of what a customer-facing client can see: the
storefront reads publication items and never the authoring tables. Anything the
snapshot omits is therefore not merely stale for a client, it is absent.

That made an omission expensive to notice. Membership — `category_products` and
`product_modifier_groups` — was authored, stored, and never projected, so every
published menu reached customers as a flat list of products with no category to
browse and no choice to make, while the authoring console showed a correctly
organised menu. Both are now carried: a CATEGORY item lists its `productIds` in
the category's own order, and a PRODUCT item lists its `modifierGroupIds`.

`variant_modifier_groups` is deliberately not projected. The table exists here
and nothing writes it, so a variant-level link would be an always-empty list in
front of every client, which reads as "this variant has no options" rather than
"this was never authored". Publishing it is a change to make when authoring
starts writing it, not before.

Publications are immutable, so every publication written before this carries
neither. Clients read absent membership as "not carried" and switch the browse
off, rather than rendering an empty menu.

## Validation rules

Validation returns stable codes and entity paths for:

- missing names/translations and broken category trees;
- products with no active variant;
- impossible modifier selection ranges or dependency cycles;
- duplicate brand codes/SKUs where uniqueness policy requires them;
- media assets not in an approved/available state;
- location offerings pointing outside the location's brand;
- missing active prices, tax category, or required availability information;
- unresolved POS conflicts marked as blockers.

Inventory and pricing contribute validation ports but remain owners of their
facts.

## What is built so far

The authoring tables, the validator, versioned publication, and the storefront
read. `CatalogPublicationTests` proves the stage-two goal end to end: a menu is
authored, published, and seen at a location.

**Publication is a copy, not a pointer.** `publication_items` holds each entity's
content as it was at snapshot time, and the storefront reads only from there.
That is the mechanism — not a convention anyone has to remember — that stops a
draft edit reaching a live menu; it is asserted directly by renaming a dish
mid-service and watching the live menu not move. `publication_items` is
insert-only at the grant level for the same reason.

**Names travel inside the snapshot.** An early version left translations behind
in the authoring tables, which would have rendered a published menu as database
codes. Publication items now carry every locale's name and description.

**Location offerings are read live, deliberately.** Marking a dish sold out must
take effect at once; routing it through a republish would mean re-validating an
entire menu to hide one item. A variant a location does not offer is absent from
the menu; one it has run out of is present and not orderable, because "we don't
sell that" and "we're out of that" are different things to tell a customer.

**One live publication per brand and channel is a partial unique index**, not a
transaction convention. Two live menus must be impossible rather than rare.

**Validation catches what the schema cannot.** The check constraints enforce
minimum ≤ maximum on a modifier group, but they cannot know how many options
exist — "choose 2 sauces" with one sauce on the list passes every constraint and
traps the customer at checkout. The validator counts. Similarly, the database
forbids a category being its own parent but not a longer A→B→A cycle, so the
validator walks each ancestry to its root.

**Validation is a GET, correcting this ADR's original listing.** It has no side
effect, and ADR 0031's gate is right that a POST must have one.

**Pricing validation now runs.** `VariantPricingLookup` moved to
`catalog.api` — the consumer declares the contract, so the dependency points one
way and catalog never learns what a price book is — and ADR 0018's
`PricingVariantLookup` implements it. The stand-in is
`@ConditionalOnMissingBean`, so it disappeared without a code change and
`PRICING_VALIDATION_NOT_WIRED` no longer appears on reports.

**Fiscal classification fields exist; enforcement does not.** V0021 adds
`mxik_code` and `package_code` to `products`, `variants`, and
`modifier_options`, closing this ADR's "SPIC/unit/VAT classification semantics"
open input far enough for an operator to enter ИКПУ/MXIK before the module
carries production data. A variant with no code of its own inherits its
product's, and a modifier option inherits the variant it links to, so a
single-variant dish is classified once. The publication snapshot carries the
*resolved* code per node: a partner adapter reading a published menu cannot
reach back into authoring to inherit, and authoring is mutable.

`CatalogValidator` reports the gap as `FISCAL_CLASSIFICATION_MISSING` and
`FISCAL_CLASSIFICATION_NOT_ENFORCED`, both WARNING. ADR 0038 (Proposed) makes
classification mandatory per priceable node and those findings blockers, but it
is pending legal input on the reference list and sign-off, so blocking today
would wall off every existing brand over an unratified decision. Surfaced rather
than hidden, in the same way `PRICING_VALIDATION_NOT_WIRED` was. ADR 0038's
accepted form is a separate `catalog.fiscal_classifications` table with marking,
excise, and age restriction validated against an imported `mxik_reference`;
these columns are the smaller interim, and moving them is a data migration
inside one schema.

Not yet built: `variant_packaging_requirements` (its legacy disposition is still
an open input), catalog events, the POS `APPLYING` transition, and the SQL
projections and ETag caching described below.

## POS synchronization boundary

ADR 0012 may implement ingestion, staging, diff, mapping, and review before this
ADR. Its live `APPLYING` transition is disabled until this model exists. Apply
uses catalog commands under the same invariants as control-plane edits, records
source run/mapping IDs, and produces a new draft. POS sync never publishes
directly and never mutates an active publication.

## Legacy merchandising and preparation scope

The legacy `tags`, `product_tags`, `recommended_products`, `ui_elements`,
`ui_element_items`, `ui_offers`, `faqs`, `faq_categories`, `social_medias`, and
`kitchens` tables—and the legacy variant `package_id`, `package_volume`,
`is_package`, SPIC, unit, and VAT fields—are not copied into catalog JSON.
Before catalog cutover,
product must choose a disposition from the migration coverage register.
Retained taxonomies/recommendations become scoped, versioned merchandising
facts; retained storefront/FAQ content needs an explicit content owner and
publication model; retained kitchens become location-owned preparation
stations/routing or approved preparation metadata. Otherwise the feature is
archived or retired with journey signoff. Retained packaging uses an explicit
same-brand variant relationship with exact quantity semantics; fiscal product
classification/unit/tax mappings are approved jointly with pricing and payment
fiscalization.

## Commands and APIs

```text
POST /api/v1/control-plane/brands/{brandId}/catalogs
POST /api/v1/control-plane/catalogs/{catalogId}/products
PUT  /api/v1/control-plane/products/{productId}
PUT  /api/v1/control-plane/variants/{variantId}/location-offerings/{locationId}
GET  /api/v1/control-plane/catalogs/{catalogId}/validation
POST /api/v1/control-plane/catalogs/{catalogId}/publications
POST /api/v1/control-plane/publications/{publicationId}/activate
GET  /api/v1/storefront/brands/{brandSlug}/locations/{locationSlug}/catalog
```

Every mutation includes an idempotency key where creation/action semantics can
be retried and an expected version for edits.

## Events

```text
CatalogDraftChanged
CatalogValidationCompleted
CatalogPublicationCreated
CatalogPublished
CatalogPublicationRetired
LocationOfferingChanged
CatalogExternalMappingChanged
```

Storefront projections consume `CatalogPublished` and retrieve the immutable
publication by ID. Events carry references and versions rather than the full
catalog.

## Query and cache strategy

Use SQL projections keyed by tenant, brand, location, locale, and publication.
ETags derive from publication content hash plus location-offering/pricing/
availability projection versions. Caches are disposable accelerators, not
authorities. Publication events invalidate by stable scope; TTL is a backstop.

## Security and audit

- Control-plane roles author and publish; location Operations may manage only
  explicitly delegated offering/availability fields.
- Publication activation, rollback, POS apply, and external mapping changes are
  audited with actor, source, before/after version, and reason.
- Free-form descriptions are sanitized at render boundaries; media is served
  through ADR 0010 controls.
- Tenant/brand/location ancestry is checked in application policy and composite
  SQL constraints.

## Testing

- Aggregate tests cover variants, modifier cardinality, category cycles, and
  lifecycle transitions.
- PostgreSQL tests prove cross-tenant/brand/location links fail.
- Concurrent draft edits detect stale versions; concurrent activation produces
  one active publication.
- Snapshot hashes are deterministic and an old publication remains renderable.
- POS staged changes cannot bypass commands or publish automatically.
- Contract tests prove storefront output is stable across internal authoring
  schema changes.

## Rollout and rollback

Migrate one brand into drafts, compare rendered menus and entity counts, then
publish to a hidden/shadow channel. Move storefront reads by brand/location
after catalog, pricing, and inventory projections reconcile. Rollback switches
the active publication pointer or routes that brand to the legacy reader;
authoring rows and external mappings are retained.

## Consequences

### Positive

- The storefront renders from an immutable publication, so a menu is
  reproducible and a bad edit cannot leak live.
- Rollback is switching an active publication pointer rather than a data repair.
- Brand ownership is enforced by composite keys, so cross-brand contamination
  fails at the database.

### Negative

- Publication snapshots duplicate catalog content per version, which grows
  storage and requires a retirement policy for old publications.
- Authors must publish to see customer-visible changes, which is a workflow
  change from editing a live row and will feel slower.
- Validation blocking publication means an incomplete translation stops a
  release, which is correct but will be unpopular.

### Accepted trade-offs

- Products are brand-owned rather than catalog-owned, so cross-brand reuse is
  impossible by design even where two brands of one tenant sell the same item.
- Storefront reads depend on projections whose invalidation must be correct;
  caches are accelerators and never authorities.

## Implementation checklist

- [ ] Approve product/variant/modifier semantics, locales, and code/SKU rules.
- [ ] Decide legacy tags, recommendations, storefront/FAQ/social content, and kitchen disposition.
- [ ] Approve packaging, SPIC/product classification, unit, and VAT mapping semantics.
- [ ] Approve publication channels, validation severity, and author permissions.
- [x] Add authoring, content, offering, mapping, and publication tables via Flyway. V0016, corrected by V0018's referential integrity and V0046's tenant boundaries; provider entity mappings live in ADR 0026's `integration` tables rather than in `catalog`.
- [x] Implement catalog aggregates and raw-SQL repositories with version checks. `CatalogEntities` and `JdbcCatalogStore` over `JdbcClient`, with `version` columns on the authoring tables.
- [ ] Implement validator ports for media, pricing, inventory, and POS conflicts. Media (`MediaAssetId` displayability) and pricing (`VariantPricingLookup`, implemented by `PricingVariantLookup`, degrading to `PRICING_VALIDATION_NOT_WIRED`) are in `CatalogValidator`; there is no inventory port and no POS-conflict port.
- [x] Implement the `ProviderEntityMappingLookup` read port against ADR 0026. `integration.api.provider.ProviderEntityMappingLookup`, implemented by `JdbcProviderInstallationLookup`; read today by `PosOrderExportService` rather than by catalog.
- [x] Implement immutable snapshot, hashing, activation, and republish rollback. `CatalogPublicationService` snapshots into `publication_items`, hashes content order-independently and process-independently (`contentHashOf`), activates in the same transaction, and `rollbackTo` republishes an old snapshot without editing history.
- [x] Implement control-plane and storefront APIs with ETags. `CatalogAuthoringController` and `CatalogPublicationController` under `/api/v1/control-plane/...`, and `StorefrontCatalogController` returning the location menu with the publication id as its ETag.
- [ ] Connect ADR 0012 apply through catalog commands, initially disabled. ADR 0012 has no apply path to connect; nothing in `pos` writes to `catalog.*`.
- [ ] Build brand migration/render comparison and reconciliation reports. Nothing compares a rendered Qoida menu to a legacy one.
- [ ] Add domain, PostgreSQL, publication-race, API, and isolation tests. `CatalogPublicationTests` is the only catalog test class; there is no publication-race or cross-brand isolation test.

## Exit criteria

A brand can author and validate a complete menu, publish one immutable version,
offer only valid variants at its locations, safely apply reviewed POS changes
into a new draft, and serve a deterministic storefront catalog without sharing
catalog entities across brands.
