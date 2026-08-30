# Legacy mapping audit

An audit of [`legacy-mapping.md`](legacy-mapping.md) and the
[migration coverage register](../migration-coverage.md) against the two sources
they claim to be derived from:

- `../milliy/backend/app/models/` — the 64 checked-in ORM tables;
- `../milliy/backend/app/apps/{customer,dashboard,vendor,courier,integrations}/` —
  every router, service, and dependency that reads or writes them;
- `src/main/resources/db/migration/` — the target columns the mapping rules name.
  `V0001..V0023` are committed; `V0024__create_migration_control_plane.sql` is
  present in the working tree but untracked at the time of this audit, and is
  called out by name wherever a finding depends on it.

Neither source document is edited by this audit. Each finding names the document,
the row, the claim, what the source shows, and the correction to apply. Rows are
ranked by consequence: a wrong tenant-key or ancestry rule silently loses or
merges data, a wrong target-column rule fails at load time and is caught, a
wrong disposition wastes a decision meeting.

## Result

| | |
|---|---|
| Claims checked | 125 |
| Claims the source contradicts | 31 |
| Claims verified correct | 94 |

Both documents are honest about being a planning baseline, and the largest
single claim in either of them — that the 64-table list in the coverage
invariant is exactly the checked-in set — holds. A name-by-name diff of the
fenced list in `legacy-mapping.md` against `grep __tablename__` over the models
package returns no difference in either direction, and every one of the 64 also
appears in a disposition row of the coverage register. What fails is not the
inventory; it is the field rules, the target columns, and eleven dispositions
that the code already decides.

## Rank 1 — rules that lose or merge data

### A1. The legacy customer partition key is not `companies`, and neither document names it

| | |
|---|---|
| Document | `legacy-mapping.md`, "Customer, access, and engagement tables", `customers` row; coverage register, "Customers, access, and engagement", `customers` row |
| Claims | "Transform into tenant-owned `CustomerAccount`, identity partition, brand profile … Never deduplicate solely by phone/username." Coverage: "Tenant/brand identity partition, duplicate/merge, PII, consent, and retention rules approved." |
| Source shows | `app/models/customer.py` — `Customer.company` is a **string column**, not a foreign key, defaulted to `enums.Company.rayhon.value`, with `UniqueConstraint("username", "company", name="uq_customers_username_company")`. `app/shared/enums/vendor.py` fixes its domain at five literals: `rayhon`, `marmar`, `jizbiz`, `kids_plate`, `pharmacy`. There is no `customers.company_id`, and no foreign key from `customers` to `companies` anywhere in the model. |
| Correction | Say explicitly that legacy customer identity is partitioned by a five-valued string discriminator that shares no key with `companies.id`. The same `username`/`phone` legitimately exists once per discriminator value, so up to five distinct legacy customers can carry identical contact details and are **not** duplicates. The `customers → tenant/brand` join therefore has no derivable key at all: it needs a hand-authored, business-approved `company` string → brand mapping, produced before any customer backfill, and listed in the decision register alongside the company-to-tenant grouping. Every dedup rule must key on `(username, company)`, never `username`. |

This is ranked first because it fails silently in both directions: dedup on
`username` merges five people into one, and ignoring the column assigns every
customer to one brand.

### A2. `orders` is dispositioned `TRANSFORM`, but `ordering.orders` cannot accept a historical row

| | |
|---|---|
| Document | Coverage register, "Carts, promotions, orders, recovery, and feedback", `orders`, `order_line_items` row; `legacy-mapping.md`, `orders` row |
| Claims | "`TRANSFORM` through ADR 0019 into immutable snapshots and separate process states." Mapping: "Preserve public/legacy IDs and raw evidence." |
| Source shows | `V0022` `ordering.orders` requires, `NOT NULL` and with no default, twelve columns that have no legacy source: `public_order_number`, `channel_id`, `channel_code_snapshot`, `fulfillment_mode`, `acceptance_mode_snapshot`, `approval_channel_snapshot`, `currency`, `pricing_quote_id`, `pricing_context_hash`, `catalog_publication_id`, `cart_id`, `idempotency_key`. `pricing_quote_id` and `catalog_publication_id` are foreign keys to rows that never existed for a legacy order. Legacy `orders.id` is a `BigInteger` sequence with no public order code. Legacy has no currency column at all; `enums.Currency` exists but no model uses it. |
| Correction | Split the row. Historical orders cannot become `ordering.orders` rows without fabricating exactly the facts the mapping's own "POS and ordering additions" section forbids fabricating; they are `ARCHIVE` evidence plus a read model. Only in-flight orders at cutover are `TRANSFORM`, and each needs a real cart, quote, publication, and channel created for it. Identity preservation is not the blocker — the uncommitted `V0024` `migration.entity_mappings` crosswalk carries `legacy_id varchar(255)` and covers it — but the twelve required columns are, and clearing them is a prerequisite of the disposition rather than a detail of it. |

### A3. Brand and location imagery has no target attachment table

| | |
|---|---|
| Document | `legacy-mapping.md`, "Tenant, brand, and location" — `companies.image`, `companies.background_image`, and "vendor image paths" rows |
| Claims | Three rows map to "`media_asset` plus `brand_media`" and "`media_asset` plus `location_media`". |
| Source shows | No table named `brand_media` or `location_media` exists in any migration. `V0015` creates `media.assets`, whose `ck_media_asset_owner_scope` does allow `owner_scope IN ('TENANT','BRAND','LOCATION')`. But the only relation table, `catalog.media_relations` (`V0016`), constrains `entity_type IN ('CATALOG','CATEGORY','PRODUCT','VARIANT','MODIFIER_OPTION')` — `BRAND` and `LOCATION` are excluded. |
| Correction | A brand or location image can be *stored* (`media.assets` with the right `owner_scope`) but cannot be *designated*: nothing records which asset is the logo and which is the background. Rewrite the three rows to name `media.assets` and state the gap — designating brand/location media needs either a new relation table or two new `entity_type` values on `catalog.media_relations`. Until that migration exists, `companies.image`, `companies.background_image`, `vendors.image`, and `vendors.background_image` have no complete target and the brand/location cutover is blocked on schema, not on profiling. |

### A4. Legacy identity is preserved in a crosswalk, not on the row, and neither document says so

| | |
|---|---|
| Document | `legacy-mapping.md`, `customers` row ("immutable legacy link under ADR 0015"), `categories` row ("preserve the legacy ID mapping"), `orders` row ("Preserve public/legacy IDs"), `vendors.id`/`companies.id` rows |
| Source shows | Only two `legacy_*` columns exist on business tables in the whole target schema: `tenant.brands.legacy_company_id` and `tenant.locations.legacy_vendor_id`, both `V0003`. `V0017`, `V0016`, `V0019`, and `V0022` contain none — `grep -n legacy` over each returns nothing. Every other legacy identity is carried by the uncommitted `V0024` `migration.entity_mappings`: `(scope_id, entity_type, legacy_id)` unique, `legacy_id varchar(255)` deliberately text "because the legacy estate keys on integers, strings and composites", plus `mapping_status IN ('MAPPED','QUARANTINED','SUPERSEDED')` and `target_id` nullable exactly when quarantined. |
| Correction | The mechanism exists but neither document names it, and the two documents read as if every legacy id lands on the target row. State the split explicitly: brand and location keep an in-row legacy column, everything else resolves through `migration.entity_mappings` with an `entity_type` per source table. Two consequences the rows should carry. First, the crosswalk is unique per **scope**, so the same legacy row imported under two scopes produces two mappings and the scope decomposition becomes part of the mapping contract rather than a runbook detail. Second, `migration.entity_mappings` is untracked at the time of this audit — until it lands, the "immutable legacy link", "preserve the legacy ID mapping", and "preserve public/legacy IDs" rules name nothing that exists, and no backfill of customers, categories, products, variants, or orders is re-runnable. |

### A5. `courier_groups` cross-tenant prohibition will quarantine correct data, not validate it

| | |
|---|---|
| Document | `legacy-mapping.md`, `courier_group_areas`/`courier_group_couriers`/`courier_group_vendors` row; coverage register, courier groups row |
| Claims | "…cross-tenant membership is forbidden." Coverage: "multi-tenant prohibition approved." |
| Source shows | `app/models/courier.py` — `Courier` has no company, vendor, or tenant column; couriers are platform-global. `CourierGroupVendor` joins a group to a vendor with no constraint tying the group's other vendors to the same `company_id`, and `Courier.get_vendor_ids()` flattens vendors across every group a courier belongs to. Nothing in the model or in `app/apps/dashboard/services/courier/` prevents one group from spanning two companies. |
| Correction | Cross-company groups are the expected shape of a shared internal courier fleet, not a defect. Profile the actual span first; if groups do cross companies, the rule quarantines the entire dispatch configuration. The correct target rule is a decision — either the fleet becomes a platform-level (non-tenant) resource, or each group is split per tenant and the split is a business decision with an owner — not a validation that fails the load. |

## Rank 2 — target columns the mapping assumes or mis-states

### B1. `vendors.latitude`/`longitude`: wrong target technology and no honest `coordinate_source`

| | |
|---|---|
| Document | `legacy-mapping.md`, `vendors.latitude`, `longitude` row |
| Claims | "Validate range and pair completeness; use PostGIS when geographic schema is introduced." |
| Source shows | `V0023` added `tenant.locations.latitude`/`longitude` as plain `double precision`, not PostGIS geography, plus `coordinate_source varchar(24) NOT NULL DEFAULT 'NOT_GEOCODED'` and `ck_locations_coordinate_source_agrees CHECK ((coordinate_source = 'NOT_GEOCODED') = (latitude IS NULL))`. The permitted values are `NOT_GEOCODED`, `GEOCODER`, `MERCHANT_PIN`, `OPERATOR_PIN`; the migration comment states there is deliberately no `LEGACY_UNSOURCED` "since these columns are new and no row can predate them". On the legacy side `Vendor.latitude`/`longitude` are `Mapped[float]` — non-nullable — so every vendor already has a pair. |
| Correction | Pair completeness is not at risk; range and plausibility are. The real blocker is `coordinate_source`: a migrated legacy coordinate is none of the four permitted values, and the agreement constraint forbids carrying it as `NOT_GEOCODED`. Either `V0024` adds `LEGACY_UNSOURCED` (reversing a deliberate V0023 decision, which needs an ADR note) or every legacy coordinate is re-sourced through the geocoder or a merchant/operator pin during onboarding. Drop the PostGIS sentence for locations — it belongs on the `areas` row, where polygons are actually involved. |

### B2. `vendors.phone`: the target constraint is strict E.164 and legacy phones are not

| | |
|---|---|
| Document | `legacy-mapping.md`, `vendors.phone` row |
| Claims | "location contact. Normalize and validate; do not use as identity." |
| Source shows | `V0023` `ck_locations_contact_phone CHECK (contact_phone IS NULL OR contact_phone ~ '^\+[1-9][0-9]{7,14}$')` — a leading `+` is mandatory. Legacy `vendors.phone` is `Mapped[Optional[str]]` with no format constraint, and the one phone convention the legacy code does enforce (`OTP.validate_phone`, `CourierOtp.validate_phone`) asserts the value **starts with `998`**, with no `+`. |
| Correction | Name the target constraint. Every legacy vendor phone must gain a `+` and be re-validated against `^\+[1-9][0-9]{7,14}$`; anything that cannot be normalized to E.164 loads as `NULL` and lands on the onboarding gap list, because the column is nullable but a non-conforming string is rejected outright. |

### B3. `vendors.address`: the target deliberately does not parse

| | |
|---|---|
| Document | `legacy-mapping.md`, `vendors.address` row |
| Claims | "structured location address. Preserve original text and parse only fields with testable rules." |
| Source shows | `V0023` gives `address_line varchar(200)`, `district varchar(120)`, `city varchar(120)`, `landmark varchar(200)`, each guarded by `ck_locations_address_not_blank`. The migration states the one-line choice is intentional: "no query filters on a house number, and Uzbek addresses do not decompose the way a street/number split assumes." |
| Correction | There is no street/number parse to perform. `vendors.address` maps whole to `address_line`, truncation at 200 characters is a real risk that needs a pre-count, and `district`/`landmark` have no legacy source and stay `NULL` until onboarding fills them. Also note the blank guard: a legacy empty-string address must load as `NULL`, not `''`. |

### B4. `vendors.city_id`: the "explicit locality table" does not exist

| | |
|---|---|
| Document | `legacy-mapping.md`, `vendors.city_id` row; coverage register, lookup-table row |
| Claims | "Map through an explicit locality table; do not keep an unexplained lookup integer." |
| Source shows | The only target column for this fact is `tenant.locations.city varchar(120)` from `V0023` — a denormalized string. No locality, city, or region table exists in `V0001..V0023`. Legacy `cities.name` is a JSONB locale dictionary (`uz`, `en`, `ru`, all three required by `validate_languages_in_dictionary`). |
| Correction | The lookup integer is not preserved anywhere. Rewrite the rule as: resolve `vendors.city_id → cities.name`, select a locale (the target column is a single string, so this is a decision, not a copy), and write it to `tenant.locations.city`. Record the legacy id in the migration ledger, not in the schema. If a normalized locality reference is genuinely wanted, it is an unbuilt migration and belongs in the decision register. |

### B5. Required target columns with no legacy source and no rule

| | |
|---|---|
| Document | `legacy-mapping.md`, "Tenant, brand, and location" table (all rows) |
| Claims | The table maps `companies`/`vendors` field by field. |
| Source shows | `V0003` makes `tenant.brands.code`, `tenant.locations.code`, and `tenant.locations.timezone` `NOT NULL`. `code` is additionally constrained to `^[A-Z0-9][A-Z0-9_-]{0,31}$` on both tables. Legacy has no code column, and `grep -riw timezone` over the legacy app returns nothing outside `datetime.timezone` imports — the legacy system has no per-vendor timezone at all. |
| Correction | Add three rows. `code` must be generated and made stable before load, because it is part of `uq_brands_tenant_code` and `uq_locations_brand_code`; a regenerated code on a re-run creates duplicate brands. `timezone` must be assigned per location by business decision, and it is a prerequisite of the `orders.planned_time` rule the mapping already flags — that row says "map `planned_time` only after timezone semantics", which cannot be satisfied until locations have timezones. Also note the slug constraints: `varchar(63)` plus `^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$`, against a legacy `Vendor.make_slug()` that does `name["en"].lower().replace(" ", "-")` and will happily emit trailing hyphens, apostrophes, and over-length values. |

### B6. `LocationOffering` no longer owns the sales schedule, and fulfillment modes are channel-scoped

| | |
|---|---|
| Document | `legacy-mapping.md`, closing paragraph of the catalog section; `vendor_delivery_methods` row in the lookup table |
| Claims | "The target `LocationOffering` owns sellability, sales schedule, fulfillment modes, and preparation overrides." `vendor_delivery_methods` → "Transform into location-scoped enabled fulfillment modes." |
| Source shows | `V0016` `catalog.location_offerings` has `status`, `fulfillment_modes`, `preparation_duration_override` — and no schedule column. `V0020` put schedules in `tenant.service_schedules` / `service_schedule_rules` / `service_schedule_exceptions`, brand-owned and attached to locations through `tenant.location_service_bindings`. The same migration created `tenant.channel_fulfillment_modes`, keyed on `(channel_id, fulfillment_mode)` and owned by a sales channel, not a location. |
| Correction | Sales schedule ownership moved to `tenant.service_schedules` — `vendors.work_time` maps there, and per-vendor schedules become one brand-owned schedule per distinct timetable plus a binding per location, which is a deduplication step the current row does not mention. `vendor_delivery_methods` resolves to *two* target facts: what a channel offers (`tenant.channel_fulfillment_modes`) and what an individual offering supports (`catalog.location_offerings.fulfillment_modes`); the row must say which one legacy per-vendor rows become, and that is a decision. |

## Rank 3 — `DECIDE` rows the source already answers

Fifteen legacy tables have **no reference anywhere in the application** outside
`app/models/` and the Alembic DDL. The check is a whole-word grep for each ORM
class and each `__tablename__` across `app/`, excluding `app/models/` and
`app/alembic/`:

```text
action_logs, courier_blocks, courier_client_notes, courier_instructions,
incidents, logs, offer_orders, offer_users, offer_users_used, product_tags,
recommended_products, stocks, tags, tax_receipts
```

plus `configs`, whose `models.Config` class is never queried — only the unrelated
`enums.Config` name appears in the app.

### C1. The entire promotions subsystem is unreferenced, yet dispositioned `TRANSFORM`

| | |
|---|---|
| Document | Coverage register: "`offers`, `offer_orders`, `offer_users`, `offer_users_used` — `TRANSFORM` through ADR 0018 into versioned promotions/coupons/redemptions"; `legacy-mapping.md`, four corresponding rows |
| Source shows | `models.OfferOrder`, `models.OfferUser`, and `models.OfferUserUsed` appear in zero non-model, non-Alembic lines. `models.Offer` is never selected or constructed either; the only live read is `cart.offer.promo_code` in `app/apps/customer/services/cart/get.py:108`, reached through `carts.offer_id` — and no code path anywhere assigns `cart.offer_id`. There is no promo-code entry, validation, or redemption endpoint in any of the five router trees. |
| Correction | This is not a `TRANSFORM` on evidence; it is a table set whose writer was removed. Move `offer_orders`, `offer_users`, and `offer_users_used` to `ARCHIVE` pending a production row-count, and mark `offers` `ARCHIVE` unless production shows non-empty `carts.offer_id`. Redesigning ADR 0018 promotions is greenfield work, not a migration, and treating it as a migration invents a rule-meaning reconciliation gate for rules nothing has applied. Note the two facts a rebuild does need from the source: `offers.vendor_id` is `NOT NULL`, so legacy promotions are location-scoped rather than brand-scoped, and `offers.promo_code` is **globally unique across all vendors**, which a tenant-scoped or brand-scoped coupon code will not reproduce. |

### C2. `configs` has no reader

| | |
|---|---|
| Document | Coverage register, `configs` row (`TRANSFORM`); `legacy-mapping.md`, `configs` row ("Profile every key and JSON shape") |
| Source shows | `models.Config` is never referenced outside `app/models/core.py`. The `Config` name that does appear in the app is `enums.Config`, a separate string enum in `app/shared/enums/core.py`. |
| Correction | The gate "every observed key has owner, type, scope, default, secrecy, and disposition" is answerable by a single production `SELECT` rather than by a profiling exercise, because no code path consumes any key. Downgrade to `ARCHIVE` unless production rows exist and something outside this checkout reads them — which is exactly the kind of runtime-only consumer the register's Phase 0 note anticipates, and should be stated as the one open question rather than as a full key-by-key profile. |

### C3. `stocks` is dead and there are no competing sources

| | |
|---|---|
| Document | `legacy-mapping.md`: "`stocks` plus product/variant stock fields — Reconcile competing sources and create one target opening inventory movement per stock item." Coverage: "`TRANSFORM` through ADR 0017 … do not sum competing sources blindly", gate "Source-of-truth rule and every discrepancy reconciled." |
| Source shows | `models.Stock` has zero non-model references. The live stock path is `Variant.stock_count` gated by `Product.stock_enabled`, checked in `Variant.validate_availability`. |
| Correction | There is one source, not several. The reconciliation gate collapses to a comparison performed once for evidence, then `variants.stock_count` is authoritative and `stocks` is `ARCHIVE`. Separately, and more consequentially: `V0019` `inventory.stock_items.tracking_mode` allows `QUANTITY` in the check constraint but the migration comment states the service **refuses** it — only `BINARY` and `UNTRACKED` are implemented. A legacy `stock_count` is a quantity, so the `TRANSFORM` cannot land as a quantity today. Either inventory ships the quantity path first, or legacy quantities degrade to `BINARY` (in stock / out of stock) and that loss is an approved product decision. |

### C4. Nine more `DECIDE` and `TRANSFORM` rows resolvable on code evidence

| Table | Both docs say | Source shows | Correction |
|---|---|---|---|
| `incidents` | Coverage: `TRANSFORM` into ADR 0013 service-recovery cases; gate names "reason/severity/status/remedy mapping" | Zero references. The model has `order_id`, `content`, `cause` only — no severity, no status, no remedy, no actor | `ARCHIVE`. Three of the four fields the gate wants to map do not exist, and nothing writes the fourth |
| `tax_receipts` | Both: `DECIDE` then `TRANSFORM` or `ARCHIVE` | Zero references. No fiscalization code path exists in this checkout | `ARCHIVE` on code evidence; the remaining question is only whether an out-of-checkout job writes it. "Never resend a receipt during import" stays — it is correct and cheap |
| `logs`, `action_logs` | Coverage: `ARCHIVE` plus transform verified audit facts | Zero references; no writer for either | `ARCHIVE` only. Drop "transform only verified audit facts into the target audit model" — there is no attributable actor stream to transform, and `audit.audit_events` (`V0007`) should start empty |
| `tags`, `product_tags` | Both: `DECIDE` | Zero references either side | `RETIRE` on evidence. Also note `tags` has no vendor or company column — it is global, so "brand-owned taxonomy" has no source scope |
| `recommended_products` | Both: `DECIDE` | Zero references | `RETIRE` on evidence. The gate's "never preserve invalid cross-scope links" is moot |
| `courier_blocks` | Both: `DECIDE`, "auditable availability/restriction **interval** with reason, actor, **expiry**" | Zero references. Columns are `courier_id`, `reason`, `object`, `object_type` — a courier-to-object block, not a time interval. No start, no end, no actor | `RETIRE` on evidence, and correct the characterization: this is a courier↔object exclusion, closer to a dispatch blacklist than to an availability window. The current wording would build the wrong feature |
| `courier_client_notes`, `courier_instructions` | Both: `DECIDE` | Zero references | `RETIRE` on evidence, subject to a PII retention decision on the free text. Note `courier_instructions.status_id` is a bare enum with no foreign key to `statuses`, unlike every other `status_id` in the schema |
| `black_lists` | Both: `DECIDE`, transform into an auditable risk decision with "reason, actor, expiry, and appeal" | Read-only: `app/apps/customer/services/authentication/login.py:83` selects it to gate login. **Nothing writes it.** Columns are `customer_id`, `order_id` (`NOT NULL`), `action` — no reason, no actor, no expiry | Keep `DECIDE`, but correct the shape: this is a per-order login gate with a writer that no longer exists, and three of the four fields the target rule names have no source. The decision is whether to rebuild the capability, not how to map it |
| `kitchens` | Both: `DECIDE`; coverage gate "Kitchen queue/routing behavior and **location ownership** characterized" | Live (dashboard CRUD in `app/apps/dashboard/services/product/kitchen.py`), but `Kitchen` has **no** `vendor_id` or `company_id` — kitchens are global, and `products.kitchen_id` is the only link | Keep `DECIDE`, correct the gate: there is no location ownership to characterize. Kitchens are a global classification on products, so a per-location preparation-station model is a redesign with a fan-out decision, not a transformation |

## Rank 4 — `TRANSFORM`/`MIGRATE` rows the source complicates

### D1. Six catalog and content tables are globally scoped, not brand-scoped

| | |
|---|---|
| Document | `legacy-mapping.md`: `categories` → "same-brand catalog categories"; `tags` → "brand-owned taxonomy"; `faq_categories`, `faqs`, `social_medias` → "tenant/brand-scoped published content". Coverage gate: "Tenant/brand scope, localization, publishing, moderation, and ownership approved" |
| Source shows | `Category`, `Kitchen`, `Tag`, `FAQ`, `FAQCategory`, `SocialMedia`, and `Area` have no `vendor_id`, `company_id`, or any other scoping column. They are single global tables shared by every vendor. Only `Product`, `Offer`, `UiElement`, `UiOffer`, `FinAgent`, and `VendorDeliveryMethod` carry a `vendor_id`. |
| Correction | "Same-brand" and "brand-owned" are target requirements, not source facts, and the difference is a fan-out decision with a data-loss edge: one global category row becomes N brand categories, and `products.category_id` must be rewritten per product to the copy belonging to that product's vendor's brand. Say so on each row. `catalog.categories` is `NOT NULL` on both `tenant_id` and `brand_id` and `fk_category_catalog` binds it to a brand's catalog, so there is no "shared" option in the target to fall back on. |

### D2. `orders.address_id` points at a mutable row — the historical address snapshot is not recoverable

| | |
|---|---|
| Document | `legacy-mapping.md`, `customer_addresses` row: "historical orders copy the address actually used into an immutable snapshot" |
| Source shows | `Order.address_id` is a foreign key to `customer_addresses.id`. `CustomerAddress` has no versioning, no `deleted_at`, and is updated in place by `app/apps/customer/services/address/`. Legacy stores no address text on the order. `V0022` `ordering.order_customer_snapshots` is the target that wants the snapshot. |
| Correction | The snapshot cannot be reconstructed for historical orders: what is joinable today is the address's *current* state, not the delivered-to state, and an address edited or deleted since delivery is silently wrong or missing. State that the migrated snapshot is "last known address as of migration", flag it as a known fidelity loss, and quarantine orders whose `address_id` no longer resolves. |

### D3. `orders.vendor_id` is nullable

| | |
|---|---|
| Document | `legacy-mapping.md`, `orders` row; coverage `orders` row |
| Source shows | `Order.vendor_id: Mapped[typing.Optional[uuid.UUID]]`. Defensive `if order.vendor is None` checks appear four times in `app/apps/dashboard/services/order.py` (lines 975, 1002, 1244, 1334), so null vendors are an expected runtime state, not a theoretical one. `ordering.orders.location_id` is `NOT NULL`. |
| Correction | Add a quarantine rule: an order with no `vendor_id` has no brand or tenant ancestry and cannot be placed in `ordering.orders` at all. Count these in Phase 0 profiling — the mapping's profiling list covers "vendors with missing or invalid companies" but not orders with missing vendors. |

### D4. Two competing delivery radii, and the JSON shape is already known

| | |
|---|---|
| Document | `legacy-mapping.md`: `vendors.visibility_distance` → "Confirm semantics and units before migration"; `vendors.delivery` JSON → "Split into fulfillment-owned structured rules after JSON-shape profiling" |
| Source shows | `visibility_distance` is a maximum delivery distance, not a storefront visibility radius: it gates address selection (`address/set_address.py:78`), cart validity (`cart/get.py:31`), and order creation (`order/create_order.py:53`), each time compared directly against `cart.distance`. Immediately below, `create_order.py:57` applies a **second** limit, `cart.vendor.delivery.get("max_distance", -1)`, to the same `cart.distance`. The `delivery` JSON's keys are fully enumerated by the code that reads them: `distance`, `distance_price`, `prices_per_km`, `discount`, `peak_hours[]`, `max_distance` (`cart/calculate_delivery_price.py`, `company/vendor.py:163`). |
| Correction | Rename the `visibility_distance` row to what it is — a serviceability radius sharing units with `orders.delivery_distance` — and add the real finding: two independent radius fields, one column and one JSON key, both live, with no code enforcing agreement between them. Which one wins in production is a genuine `DECIDE`, and it directly affects the ADR 0037 zone origin. For the `delivery` row, replace "after JSON-shape profiling" with the known key list; profiling confirms value ranges and the `peak_hours` element shape, not the schema. |

### D5. `vendors.managers` is display-only, never an access-control input

| | |
|---|---|
| Document | `legacy-mapping.md`, `vendors.managers` row |
| Claims | "principal membership and scoped grants … Resolve each manager to a Keycloak subject through an approved identity-link process." |
| Source shows | Two non-model references only: `app/apps/dashboard/services/company/vendor.py:30` echoes `vendor.managers` in a response, and line 113 sets `managers=None` at vendor creation. No authentication or authorization path reads it. Legacy vendor access control runs entirely through `vendor_users` (`username`/`password`/`role`). |
| Correction | Downgrade to a contact list. The rule as written implies migrating an access-control fact and will generate `iam.grants` rows for people who never had access. Actual grants derive from `vendor_users.role` (`admin`, `packer`, `finance`) and `dashboard_users.role` (`admin`, `manager`, `dispatcher`). |

### D6. `carts`: no status, no expiry, no currency, no channel, and one per customer

| | |
|---|---|
| Document | `legacy-mapping.md`, `carts` row: "…into tenant/brand/location/**currency**-owned carts … Expire or archive stale carts." Coverage gate: "Cohort/**session**/customer mapping, TTL, location, currency, and reprice policy approved" |
| Source shows | `Cart.customer_id` is `unique=True` — exactly one cart per customer, no guest or session carts. The model has no `status`, no `expires_at`, and no currency column; nothing in the legacy schema has a currency column at all. `V0022` `ordering.carts` requires `channel_id` and `currency` `NOT NULL`. |
| Correction | "Expire or archive stale carts" has no source field to key on except `BaseModel.updated`, which should be named explicitly. "Session cohort" does not exist — every legacy cart belongs to an authenticated customer. Currency and channel are target-only facts assigned by rule, exactly like the `orders` case in A2. |

### D7. `transactions` has no provider occurrence time

| | |
|---|---|
| Document | `legacy-mapping.md`, `transactions` row |
| Claims | "Preserve as immutable provider transaction facts with external references, **occurrence/record times**, status, raw-evidence reference, and reconciliation status." |
| Source shows | `Transaction` carries `fin_agent_transaction_id`, `fin_agent_extra` (JSONB), `amount`, `status`, and the `BaseModel` `created`/`updated` timestamps. There is no provider-supplied occurrence timestamp column. |
| Correction | Only the record time exists as a column. Occurrence time, if recoverable at all, must be extracted from `fin_agent_extra` per provider, and that extraction rule is per-provider work the row does not currently acknowledge. Where it is absent, occurrence time is unknown and must not be defaulted to `created`. |

### D8. `dashboard_users` has a disposition but no field rule, and orders depend on it

| | |
|---|---|
| Document | `legacy-mapping.md` — the document states "Every one is listed in this document or the detailed tenant/vendor field table below" |
| Source shows | `dashboard_users` appears only inside the fenced coverage-invariant block. There is no rule row for it anywhere, while `vendor_users` gets a six-row section. Meanwhile `orders.operator_id` and `orders.shipment_bonus_by_id` are both foreign keys to `dashboard_users.id`, and `DashboardUser` has 26 non-model references. |
| Correction | Add a `dashboard_users` section mirroring the `vendor_users` one — `username`/`password`/`access_token`/`last_login`/IP get the same Keycloak treatment, and `role` maps from `admin`/`manager`/`dispatcher`. Add the order-attribution rule the coverage register also misses: the operator and bonus-approver references on historical orders must resolve to something, or order actor attribution is lost when `dashboard_users` retires. |

## Verified correct

Recorded so a re-audit does not repeat the work:

- The 64-table coverage invariant in `legacy-mapping.md` matches
  `grep __tablename__` over `app/models/` exactly, in both directions. Every one
  of the 64 also carries a disposition in the coverage register. The register's
  "all 64 `__tablename__` declarations" audit-basis claim is accurate.
- `companies.id` **is** `UUID(as_uuid=True)`, and `tenant.brands.legacy_company_id`
  **is** `uuid` with `uq_brands_legacy_company UNIQUE`. The rule holds.
- `vendors.id` is likewise `uuid`, matching `tenant.locations.legacy_vendor_id`.
- Slug uniqueness scopes are right: `uq_brands_tenant_slug (tenant_id, slug)`
  matches "within the target tenant", and
  `uq_locations_brand_slug (tenant_id, brand_id, slug)` matches "within the
  owning brand".
- `products` really are location-owned (`Product.vendor_id` `NOT NULL`), so
  "Legacy rows are location-owned … do not copy the row shape" is correct.
- `vendor_users.password` is bcrypt via `bcrypt.hashpw`, and `access_token` is a
  self-signed HS256 JWT with a 7-day expiry — both mapping rules ("never store in
  HorecaOS", "never migrate") are right and, given the self-signed token, urgent.
- The locale rule is enforceable as written: `validate_languages_in_dictionary`
  requires all of `uz`, `en`, `ru` in every translated JSONB field, so
  "validate locale keys" has a definite, testable answer.
- `fin_agents` really is scoped by `UniqueConstraint("payment_method_id",
  "vendor_id")` with `vendor_id NOT NULL`, and its secrets really do sit in an
  untyped `extra` JSONB — the secrets-manager rule is correct and necessary.
- The Redis Pub/Sub baseline in the coverage register (channel `order`, fields
  `event`/`order_id`/`target`/`extra`) matches the checked-in publisher and
  subscriber.
- The quarantine principle both documents rely on — "Transformation failures are
  quarantined with source identity and reason. They are never silently coerced
  into a default tenant, brand, location, or status" — is directly implementable
  against `migration.entity_mappings` (`QUARANTINED` with a null `target_id`)
  and `migration.quarantine_items`, once `V0024` is committed.

## Source quirks worth a profiling line

Not document errors, but traps for anyone writing the extract:

- `search_histories.customer_id`, `favourite_products.customer_id`, and
  `offer_users.customer_id` are annotated `Mapped[int]` while their foreign keys
  point at `customers.id` (`uuid`). SQLAlchemy takes the type from the foreign
  key, so the columns are `uuid` in the database — but any hand-written extract
  that trusts the annotation will generate the wrong cast.
- `customers.archive_id` is a self-referential foreign key to `customers.id`,
  an existing legacy merge/archive chain. Neither document mentions it, and it
  interacts directly with `customer_accounts.merged_into_account_id`.
- `Vendor.managers`, `Courier.emergency_contact`, and `Courier.work_time` are
  typed as lists but wrapped in `MutableDict.as_mutable(JSONB())`. The stored
  JSON shape needs verifying before any parse rule is written.
- `Rating.validate_rating` raises on `None`, yet both `service_rating` and
  `delivery_rating` are nullable — rows written before the validator may hold
  values the current code cannot re-read.
- `ratings.order_id` is `unique=True`: exactly one rating per order, which
  simplifies the order-feedback transform the `ratings` row leaves open.
