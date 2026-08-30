# Legacy-to-target domain mapping

This mapping is based on the checked-in FastAPI models. It is a transformation
contract, not evidence that production data is clean. Tenant grouping, enum
meaning, JSON shape, duplicates, and filesystem ownership still require a
production profile and business-approved mapping input.

The checked-in model contains 64 ORM-owned tables. Every one is listed in this
document or the detailed tenant/vendor field table below. The companion
[migration coverage register](../migration-coverage.md) also inventories route,
frontend, runtime, provider, and infrastructure sources that do not appear as
tables. A provisional target does not mean the feature has been approved for
the target product; rows marked `DECIDE` block the affected cutover.

## Tenant, brand, and location

| Legacy source | Target | Rule |
|---|---|---|
| Approved grouping input | `tenant.tenants` | Create one tenant per approved legal/commercial customer grouping. Never infer a tenant from similar names or slugs. |
| `companies.id` | `tenant.brands.legacy_company_id` | Preserve the UUID and map the company to exactly one approved tenant. |
| `companies.slug` | `tenant.brands.slug` | Normalize, detect duplicates within the target tenant, and resolve conflicts explicitly. |
| `companies.name` JSON | brand display name/translations | Validate locale keys and retain the raw source during migration. |
| `companies.description` JSON | brand description/translations | Normalize supported locales; do not bury target searchable fields in JSONB. |
| `companies.image` | `media_asset` plus `brand_media` | Inventory, checksum, copy to S3, and retain the legacy path as migration evidence. |
| `companies.background_image` | `media_asset` plus `brand_media` | Same checksum and ownership process as the primary image. |
| `vendors.id` | `tenant.locations.legacy_vendor_id` | Preserve the UUID and inherit tenant/brand through the mapped company. |
| `vendors.company_id` | location brand/tenant ancestry | Resolve through the company-to-brand mapping; reject orphaned vendors. |
| `vendors.slug` | `tenant.locations.slug` | Normalize and enforce uniqueness within the owning brand. |
| `vendors.name`, `description` | location display data/translations | Validate locale keys and preserve raw source for reconciliation. |
| `vendors.phone` | location contact | Normalize and validate; do not use as identity. |
| `vendors.managers` JSON | principal membership and scoped grants | Do not migrate as authoritative identity. Resolve each manager to a Keycloak subject through an approved identity-link process. |
| `vendors.status_id` | location lifecycle status | Map every observed value explicitly; unknown values are quarantined. |
| `vendors.pre_order` | ordering/location configuration | Map only after its actual production behavior is characterized. |
| `vendors.tin` | legal or location tax profile | Confirm whether tax identity belongs to tenant, brand, or location before applying. |
| `vendors.latitude`, `longitude` | location geographic point | Validate range and pair completeness; use PostGIS when geographic schema is introduced. |
| `vendors.address` | structured location address | Preserve original text and parse only fields with testable rules. |
| `vendors.city_id` | normalized locality reference | Map through an explicit locality table; do not keep an unexplained lookup integer. |
| `vendors.work_time` JSON | location operating schedules and exceptions | Validate every weekday, overnight interval, and exception before transformation. |
| `vendors.delivery` JSON | fulfillment zone/rate configuration | Split into fulfillment-owned structured rules after JSON-shape profiling. |
| `vendors.rating` | reporting/review projection | Do not make a mutable aggregate rating a location source-of-truth field. |
| `vendors.visibility_distance` | storefront or delivery-zone policy | Confirm semantics and units before migration. |
| `vendors.tg_chat_id`, `tg_delivery_chat_id` | notification installation/channel binding | Treat as sensitive integration configuration and validate ownership. |
| vendor image paths | `media_asset` plus `location_media` | Copy to generated S3 keys and verify checksums. |

## Legacy vendor users

`vendor_users` does not become a local credential table.

| Legacy source | Target rule |
|---|---|
| `username` | Identity-link input only; immutable Keycloak subject is the final identity key. |
| `password` | Import only through a separately approved Keycloak credential mechanism, otherwise invite/reset. Never store in HorecaOS. |
| `role` | Map to Keycloak coarse role and/or HorecaOS location role grant after explicit review. |
| `access_token` | Never migrate. Require a new Keycloak session. |
| `fcm_token`, device fields | Migrate into customer/staff device or notification registration only after ownership and consent validation. |
| `last_login`, IP | Retain only when required by security/audit policy and normalize timestamps. |

## POS and ordering additions

The legacy model does not provide the target installation, binding, import-run,
mapping, dual-channel approval, or POS-export lifecycle. These are new target
facts and must not be fabricated for historical rows. Existing external IDs
and payloads can seed mappings only after provider/location reconciliation.

## Core lookup and configuration tables

| Legacy source | Target rule |
|---|---|
| `configs` | Profile every key and JSON shape. Move approved facts into typed, scoped configuration with version/default/secret classification; archive unknown keys rather than copying an untyped global map. |
| `statuses` | Map each observed use to the owning domain lifecycle. Do not preserve one generic mutable status table as a cross-domain dependency. |
| `cities` | Map to an approved locality/address reference or archive if the target geocoder becomes authoritative. Preserve the legacy code in the mapping. |
| `delivery_methods` | Map to typed fulfillment modes with translated presentation. |
| `vendor_delivery_methods` | Transform into location-scoped enabled fulfillment modes after tenant/brand ancestry validation. |
| `order_statuses`, `order_types` | Map every observed ID into the canonical order lifecycle/fulfillment mode; unknown values quarantine the affected order. |
| `payment_methods` | Map display/configuration into provider-neutral payment method types. Provider installation/merchant configuration remains separate. |

## Customer, access, and engagement tables

| Legacy source | Target rule |
|---|---|
| `customers` | Transform into tenant-owned `CustomerAccount`, identity partition, brand profile, contact points, and immutable legacy link under ADR 0015. Never deduplicate solely by phone/username. |
| `customer_addresses` | Transform into protected customer addresses; historical orders copy the address actually used into an immutable snapshot. Invalid coordinates/ownership quarantine the row. |
| `customer_devices` | Transform only active, attributable endpoints into protected customer/notification device records; deduplicate provider tokens and respect consent. |
| `customer_sessions` | Do not migrate as sessions or tokens. Retain only required audit metadata and require a new Keycloak login. |
| `otps`, `courier_otps` | Never migrate usable codes. Archive or delete after the approved short security-retention period; Keycloak/approved broker owns new challenges. |
| `customer_invitations` | `DECIDE`: redesign as a referral/invitation and possible benefit grant, or archive/retire. Never infer unearned target credit from ambiguous legacy status. |
| `favourite_products` | `DECIDE`: migrate as brand-aware saved items only after product mappings and privacy scope are approved, otherwise retire. |
| `search_histories` | `DECIDE`: migrate only with an approved analytics/personalization purpose and retention; otherwise aggregate/archive then retire. |
| `black_lists` | `DECIDE`: transform into an auditable risk/restriction decision with reason, actor, expiry, and appeal or archive as restricted evidence. |

## Catalog, merchandising, content, and inventory tables

| Legacy source | Target rule |
|---|---|
| `categories` | Transform translations/status/order into same-brand catalog categories and preserve the legacy ID mapping. |
| `products` | Legacy rows are location-owned. Resolve their brand product identity, content, schedule, kitchen, discount, stock, and media fields into their true module owners; do not copy the row shape. |
| `variants` | Transform into brand product variants plus location offering, price/tax/preparation, inventory, SPIC/unit/VAT, and packaging facts under approved rules. Packaging relations require exact quantities and cycle checks. |
| `kitchens` | `DECIDE`: model as location preparation stations/routing if operationally used, or map only approved preparation metadata and archive the rest. |
| `stocks` plus product/variant stock fields | Reconcile competing sources and create one target opening inventory movement per stock item. Never add or overwrite them without evidence. |
| `tags`, `product_tags` | `DECIDE`: transform into brand-owned taxonomy/merchandising assignments with translations or retire. |
| `recommended_products` | `DECIDE`: transform into scoped curated recommendations/projection input after product/variant mapping, or rebuild/retire. |
| `ui_elements`, `ui_element_items`, `ui_offers` | `DECIDE`: map each observed element/JSON type to versioned storefront content/merchandising or explicitly retire during the target journey redesign. |
| `faq_categories`, `faqs`, `social_medias` | `DECIDE`: migrate to tenant/brand-scoped published content or an approved CMS, or archive/retire with product signoff. |

The target `LocationOffering` owns sellability, sales schedule, fulfillment
modes, and preparation overrides. Pricing owns money/tax calculation; inventory
owns positions/reservations/movements; integration owns external mappings.

## Cart, promotion, order, recovery, and feedback tables

| Legacy source | Target rule |
|---|---|
| `carts`, `cart_line_items` | Transform only approved active/recent carts into tenant/brand/location/currency-owned carts, revalidate product mappings, and reprice before checkout. Expire or archive stale carts. |
| `offers` | Transform approved rules into versioned promotions/coupons; explicitly map time windows, minimum spend/order count, scope, counters, and concurrency semantics. |
| `offer_orders` | Preserve historical association as immutable applied-promotion evidence on the order plus a source mapping. |
| `offer_users`, `offer_users_used` | Reconcile eligibility and usage into coupon/promotion reservation/redemption facts. Do not trust denormalized counters without comparing associations/orders. |
| `orders` | Transform into immutable order snapshots plus separate payment, preparation, POS, fulfillment, recovery, and status history. Preserve public/legacy IDs and raw evidence. Map `planned_time` only after timezone semantics; map courier bonus/policy-group fields into an approved compensation/dispatch model or archive them. |
| `order_line_items` | Transform name/price/quantity/package fields into immutable line/modifier/packaging snapshots; retain a source variant link only when valid. |
| `incidents` | Transform sufficiently classified records into service-recovery cases; archive ambiguous records without inventing remedies or financial actions. |
| `ratings` | `DECIDE`: transform into immutable order feedback with moderation/privacy and aggregate-rebuild rules, or archive/retire. |

## Payment, transaction, and fiscal tables

| Legacy source | Target rule |
|---|---|
| `fin_agents` | Transform into tenant/brand/location-scoped merchant/provider installations. Extract no secret into target tables; create a secrets-manager reference and rotate credentials. |
| `payments` | Transform into payment intents/attempts after reconciling order amount, method, merchant account, status, and provider reference. |
| `transactions` | Preserve as immutable provider transaction facts with external references, occurrence/record times, status, raw-evidence reference, and reconciliation status. |
| `tax_receipts` | `DECIDE`: transform into a provider-neutral fiscal receipt lifecycle if still operational, or archive as financial evidence under approved retention. Never resend a receipt during import. |

Provider settlement reports/portals are independent reconciliation sources; a
successful-looking database status alone is insufficient for payment cutover.

## Internal courier and geographic tables

| Legacy source | Target rule |
|---|---|
| `couriers` | Transform identity into a Keycloak-linked courier principal/profile and fulfillment availability. Encrypt/restrict identity card, JSHIR, emergency contacts, device, schedule, and notes. |
| `areas` | Validate source coordinate JSON and transform approved polygons into PostGIS zones with explicit SRID, tenant, and scope. |
| `courier_groups` | `DECIDE`: transform into dispatch pools/policies or retire after behavior characterization. |
| `courier_group_areas`, `courier_group_couriers`, `courier_group_vendors` | Transform only after group semantics, history, precedence, and location ancestry are approved; cross-tenant membership is forbidden. |
| `courier_blocks` | `DECIDE`: map to an auditable availability/restriction interval with reason/actor/expiry or archive. |
| `courier_client_notes`, `courier_instructions` | `DECIDE`: migrate approved operational facts with strict audience/retention, otherwise archive protected free text. |
| `courier_locations` | Migrate only points inside the approved live operational/incident window. Aggregate, archive, or delete older points under the location privacy policy. |

External partner shipments from Noor, Yandex Delivery, and Millennium are new
target facts and must not be fabricated from internal courier columns.

## Notification, audit, and operational evidence tables

| Legacy source | Target rule |
|---|---|
| `notification_preferences` | Map broad booleans to explicit purpose/class/channel preferences and consent evidence. Product/legal must approve defaults; absence is not consent. |
| `notifications` | Preserve required delivery evidence without sending on import. Normalize only attributable provider statuses and protect/expire response payloads. |
| `logs` | Archive according to operational/security retention. Do not load ordinary debug logs into immutable business audit. |
| `action_logs` | Transform only authenticated, attributable business/security actions with stable actor/action/target/time semantics; archive the raw source separately. |

## Coverage invariant

The known table set is:

```text
action_logs, areas, black_lists, cart_line_items, carts, categories, cities,
companies, configs, courier_blocks, courier_client_notes, courier_group_areas,
courier_group_couriers, courier_group_vendors, courier_groups,
courier_instructions, courier_locations, courier_otps, couriers,
customer_addresses, customer_devices, customer_invitations, customer_sessions,
customers, dashboard_users, delivery_methods, faq_categories, faqs,
favourite_products, fin_agents, incidents, kitchens, logs,
notification_preferences, notifications, offer_orders, offer_users,
offer_users_used, offers, order_line_items, order_statuses, order_types, orders,
otps, payment_methods, payments, product_tags, products, ratings,
recommended_products, search_histories, social_medias, statuses, stocks, tags,
tax_receipts, transactions, ui_element_items, ui_elements, ui_offers, variants,
vendor_delivery_methods, vendor_users, vendors
```

Phase 0 compares this set to the production schema, Alembic history, views,
functions, triggers, sequences, and runtime SQL. Any production object missing
here is added before Phase 2 can be complete.

## Required profiling before backfill

- Duplicate company/vendor slugs and IDs across environments
- Vendors with missing or invalid companies
- All observed status, city, role, schedule, and delivery JSON values
- Invalid coordinate pairs and ambiguous distance units
- Missing, unsafe, duplicate, and orphaned media paths
- Manager/vendor-user duplicates and inactive accounts
- Which legacy companies belong to one new tenant
- Which location configuration currently controls order acceptance
- Every `configs` key, lookup value, content/UI JSON shape, promotion rule, and
  kitchen behavior observed in production
- Active/stale carts, invitations, favorites, search history, ratings,
  incidents, blacklists, and their business/retention value
- Courier PII completeness, group/area semantics, live-location volume and age,
  and location/tenant ancestry
- Payment/transaction/tax-receipt status combinations compared with provider
  settlement/fiscal evidence
- Notification preference meaning, delivery status strength, payload PII, and
  provider identifiers
- Tables, views, jobs, raw SQL, or runtime stores present in production but not
  represented by the checked-in ORM

Transformation failures are quarantined with source identity and reason. They
are never silently coerced into a default tenant, brand, location, or status.
