# Migration coverage and readiness register

## Purpose and status

This register prevents a successful target build from leaving an unexamined
legacy table, user journey, background process, provider endpoint, or operational
dependency behind. It complements the [migration plan](migration-plan.md), the
[legacy mapping](domains/legacy-mapping.md), and
[ADR 0024](adr/partial/0024-legacy-data-migration-cutover-and-retirement.md).

Status: planning baseline. It proves checked-in source coverage, not production
readiness. Production schema/data profiling, business disposition, provider
contract discovery, runbooks, rehearsals, and signed reconciliation still gate
every cutover.

## Audit basis

The initial register was built from:

- all 64 `__tablename__` declarations in `../milliy/backend/app/models`;
- the customer, dashboard, vendor, courier, and Click integration router trees;
- Angular customer web, Angular customer mobile-oriented web, React dashboard,
  and legacy Angular dashboard source trees;
- checked-in Redis cache/pub-sub, filesystem media, notification, payment, SMS,
  map/geocoding, and provider integration boundaries; and
- the target ADRs and canonical domain documents.

This list must be regenerated from the production schema, deployed artifacts,
runtime configuration, traffic logs, job scheduler, provider portals, DNS, and
object/filesystem inventory during Phase 0. Code that is absent from this
checkout or runtime-only configuration can add rows to the register.

## Disposition rules

Every source receives exactly one approved disposition:

| Disposition | Meaning |
|---|---|
| `MIGRATE` | Preserve the business fact in a target owner and keep it operational. |
| `TRANSFORM` | Preserve approved meaning while redesigning structure/ownership. |
| `ARCHIVE` | Keep immutable query/export evidence, but do not implement an active target feature. |
| `RETIRE` | Deliberately remove behavior/data after retention and product/legal approval. |
| `DECIDE` | Product/legal/operations has not yet selected one of the above; cutover is blocked. |

`DECIDE` is visible work, not permission to ignore the source. No scoped cutover
can reach `CUTOVER_READY` while an in-scope source remains `DECIDE`.

## Legacy database coverage

The tables below are all known ORM-owned legacy tables. The detailed field
transformations belong in `docs/domains/legacy-mapping.md` and the executable
migration specification for each capability.

### Platform, tenancy, identity, and configuration

| Legacy tables | Proposed disposition and target | Gate |
|---|---|---|
| `companies`, `vendors` | `TRANSFORM` into tenant-approved `Tenant -> Brand -> Location` mappings. | Legal tenant grouping and all ancestry mappings approved. |
| `dashboard_users`, `vendor_users` | `TRANSFORM` into Keycloak subjects/organizations plus Qoida memberships/grants; never migrate bearer tokens. | Credential strategy, roles, duplicates, disablement, and recovery reconciled. |
| `configs` | `TRANSFORM` only known settings into typed platform/tenant/brand/location configuration; archive unknown keys. | Every observed key has owner, type, scope, default, secrecy, and disposition. |
| `statuses`, `cities`, `delivery_methods`, `order_statuses`, `order_types`, `payment_methods`, `vendor_delivery_methods` | `TRANSFORM` into domain enums/reference/configuration and translations; archive the original lookup rows. | Every observed value is mapped; no silent default. |

### Customers, access, and engagement

| Legacy tables | Proposed disposition and target | Gate |
|---|---|---|
| `customers`, `customer_addresses`, `customer_devices` | `TRANSFORM` through ADR 0015 into customer accounts, brand profiles, protected contact/address data, and device endpoints. | Tenant/brand identity partition, duplicate/merge, PII, consent, and retention rules approved. |
| `customer_sessions` | `RETIRE` after required audit retention; require a fresh Keycloak session. | All supported cohorts use Keycloak and compatibility adapters are removed. |
| `otps`, `courier_otps` | `ARCHIVE` or `RETIRE`; never import usable codes. Keycloak/approved broker owns new authentication challenges. | Security/legal retention and account-recovery flow approved. |
| `customer_invitations` | `DECIDE`: retain as referral/invitation history, redesign as a benefit/referral capability, or archive. | Product defines reward, fraud, expiry, identity, and historical-value rules. |
| `favourite_products` | `DECIDE`: migrate to a customer saved-item capability or retire with communicated UX change. | Cross-brand scope and deleted/unpublished product behavior approved. |
| `search_histories` | `DECIDE`: migrate only with privacy purpose/retention or aggregate/archive and retire. | Consent, analytics purpose, retention, and deletion policy approved. |
| `black_lists` | `DECIDE`: transform into an auditable risk/restriction decision or archive. | Owner, reason codes, appeal, expiry, permissions, and legal basis approved. |

### Catalog, merchandising, content, and inventory

| Legacy tables | Proposed disposition and target | Gate |
|---|---|---|
| `categories`, `products`, `variants` | `TRANSFORM` through ADR 0016 into brand-owned catalog entities, translations, variants, publication, and location offerings. | Product/variant/modifier rules, SKU/code policy, publication, and legacy ownership approved. |
| `kitchens` | `DECIDE`: redesign as fulfillment preparation stations/routing or retain only as catalog preparation metadata. | Kitchen queue/routing behavior and location ownership characterized. |
| `stocks` and variant stock fields | `TRANSFORM` through ADR 0017 into one opening position plus append-only movements; do not sum competing sources blindly. | Source-of-truth rule and every discrepancy reconciled. |
| `tags`, `product_tags` | `DECIDE`: migrate as brand-owned taxonomy/merchandising metadata or retire. | Search/filter/display semantics and locale rules approved. |
| `recommended_products` | `DECIDE`: migrate as curated brand/location merchandising rules or rebuild as a projection; never preserve invalid cross-scope links. | Ranking, scope, schedule, and unavailable-product behavior approved. |
| `ui_elements`, `ui_element_items`, `ui_offers` | `DECIDE`: transform into versioned storefront content/merchandising, replace with target design-system configuration, or retire. | Every element type/JSON shape and target journey is classified. |
| `faq_categories`, `faqs`, `social_medias` | `DECIDE`: migrate to a scoped content capability or external CMS, or archive/retire. | Tenant/brand scope, localization, publishing, moderation, and ownership approved. |

### Carts, promotions, orders, recovery, and feedback

| Legacy tables | Proposed disposition and target | Gate |
|---|---|---|
| `carts`, `cart_line_items` | `TRANSFORM` through ADR 0019, normally for active recent carts only; archive or expire stale carts. | Cohort/session/customer mapping, TTL, location, currency, and reprice policy approved. |
| `offers`, `offer_orders`, `offer_users`, `offer_users_used` | `TRANSFORM` through ADR 0018 into versioned promotions/coupons/redemptions; historical applied value remains on order snapshots. | Rule meaning, counters, eligibility, stacking, and concurrent limit reconciliation approved. |
| `orders`, `order_line_items` | `TRANSFORM` through ADR 0019 into immutable snapshots and separate process states. | Per-state historical and in-flight mapping plus exact monetary reconciliation approved. |
| `incidents` | `TRANSFORM` into ADR 0013 service-recovery cases when evidence is sufficient, otherwise archive. | Reason/severity/status/remedy mapping and sensitive-note retention approved. |
| `ratings` | `DECIDE`: migrate as immutable order feedback with moderation/privacy policy, archive for analytics, or retire. | Rating dimensions, edit window, abuse/moderation, visibility, and aggregate rebuild approved. |

### Payments, tax/fiscal evidence, and settlement

| Legacy tables | Proposed disposition and target | Gate |
|---|---|---|
| `fin_agents` | `TRANSFORM` into scoped merchant/provider installations; move secrets to a secrets manager and rotate them. | Provider/merchant ownership and secret rotation verified for every active record. |
| `payments`, `transactions` | `TRANSFORM` through ADR 0013 into intents, attempts, immutable transactions, external references, and reconciliation status. | Exact captured/refunded/pending totals and provider settlement reconcile by currency/status. |
| `tax_receipts` | `DECIDE` then `TRANSFORM` or `ARCHIVE`; active fiscalization needs a provider-neutral receipt lifecycle and current legal/provider validation. | Finance/legal approves retention, status mapping, correction/cancellation, and new authority. |

### Internal courier and geographic operations

| Legacy tables | Proposed disposition and target | Gate |
|---|---|---|
| `couriers` | `TRANSFORM` into Keycloak-linked courier/workforce profiles plus fulfillment availability; protect identity-card/JSHIR/emergency data. | Employment/contractor, identity, consent, PII retention, status, shift, and access policy approved. |
| `areas` | `TRANSFORM` into validated PostGIS service/dispatch zones. | Coordinate format, SRID, topology, overlap, and location/tenant ownership verified. |
| `courier_groups`, `courier_group_areas`, `courier_group_couriers`, `courier_group_vendors` | `DECIDE` then transform into explicit dispatch pools and scope assignments or retire. | Dispatch semantics, precedence, membership history, and multi-tenant prohibition approved. |
| `courier_blocks` | `DECIDE`: migrate as auditable availability/restriction intervals or archive. | Reason, actor, expiry, appeal, and scheduling semantics approved. |
| `courier_client_notes`, `courier_instructions` | `DECIDE`: migrate only approved operational facts with restricted access/retention; otherwise archive. | PII/free-text classification, author, audience, purpose, and retention approved. |
| `courier_locations` | `TRANSFORM` only inside the approved live-location window; aggregate/archive or delete older points under policy. | Consent, precision, frequency, access, retention, and incident-use policy approved. |

### Notifications, audit, and operational evidence

| Legacy tables | Proposed disposition and target | Gate |
|---|---|---|
| `notification_preferences` | `TRANSFORM` through ADR 0020 after mapping broad legacy booleans to explicit purpose/channel consent semantics. | Legal basis, default, evidence, brand scope, and unsubscribe behavior approved. |
| `notifications` | `TRANSFORM` active/recent delivery evidence when useful; archive older payloads according to PII retention. Do not resend during import. | Status/provider mapping and content/payload retention approved. |
| `logs`, `action_logs` | `ARCHIVE` security/business evidence and transform only verified audit facts into the target audit model; ordinary debug logs are not business records. | Classification, immutability, PII redaction, retention, access, and legal hold approved. |

## API and user-journey coverage

Each route is inventoried at operation level during Phase 0. These checked-in
families cannot be retired until every operation is mapped to a target journey,
compatibility facade, or explicit retirement:

| Legacy surface | Known journeys | Target owner |
|---|---|---|
| Customer APIs | authentication/account, addresses, cart, devices, favorites, invitations, support, catalog/search, notifications, orders, storefront UI/content | Keycloak plus customers/catalog/pricing/ordering/notifications and any approved engagement/content capability |
| Dashboard APIs | authentication, company/location, courier/areas/groups, customers, orders, catalog/kitchens/variants | control-plane and Operations APIs |
| Vendor APIs | restaurant user, product availability, order list/state/preparation | Operations API and scoped Keycloak membership |
| Courier APIs | OTP/login, profile/location, assigned orders/state | courier app plus fulfillment API |
| Click integration API | prepare/complete callbacks | payments webhook/integration adapter |

The route inventory records method/path, caller, auth mechanism, request/response
schema, side effects, data owner, traffic, frontend/deep links, target contract,
compatibility period, cutover cohort, and retirement evidence.

## Frontend coverage

Known source trees:

| Source | Required disposition |
|---|---|
| `frontend/client` | Journey-map into target storefront, then retire. |
| `frontend/client-mobile` | Determine whether this is supported responsive/PWA behavior or a distinct mobile distribution; migrate journeys and release/deep-link obligations. |
| `frontend/dashboard` | Journey-map React dashboard features into target control-plane/Operations apps. |
| `frontend/dashboard-angular-debug/dashboard-frontend-angular` | Compare with the React dashboard, identify unique behavior, then explicitly retire or migrate each route. |

For installed/mobile clients, record app-store/web-distribution lead time,
minimum supported version, API compatibility window, forced-upgrade policy,
push credentials, universal/app links, and rollback limits. Gateway rollback
cannot instantly replace an already-installed binary.

## Runtime and external dependency coverage

The production inventory must explicitly cover:

- PostgreSQL databases, schemas, extensions, Alembic history, sequences, views,
  triggers, functions, reporting queries, replicas, backups, and restore access;
- Redis keys/TTL use separately from Pub/Sub channels, publishers, subscribers,
  payloads, reconnect behavior, and side effects;
- every process, deployment, worker, subscriber, scheduler/cron, one-off script,
  health check, filesystem mount, and environment-specific configuration source;
- filesystem media, orphaned files, generated assets, CDN/proxy paths, upload
  limits, MIME behavior, and backup/archive copies;
- Click, Payme, tax/fiscal services, SMS gateway, FCM projects/credentials,
  Telegram chats/bots, Yandex maps/geocoding, and any uncommitted runtime provider;
- new CLOPOS, r_keeper, iiko, Noor, Yandex Delivery, and Millennium accounts,
  contracts, sandboxes, quotas, callback URLs, IP allowlists, credentials,
  idempotency/status guarantees, and operational contacts;
- DNS zones, custom domains, certificates, CDN/WAF/gateway routes, webhook
  ingress, outbound fixed IPs, email/SMS sender registrations, and redirects;
- dashboards, alerts, Sentry/analytics projects, log stores, support tools,
  finance exports, manual spreadsheets, and operator runbooks.

No provider endpoint is switched merely by changing a secret. The cutover must
coordinate credential rotation, callback ownership, allowlists, replay safety,
old-endpoint fencing, synthetic verification, and provider/operations signoff.

Known checked-in Redis Pub/Sub baseline: channel `order` carries `event`,
`order_id`, `target` (`customer`, `vendor`, or `courier`), and `extra`; its
subscriber loads the order, creates customer notification evidence in some
paths, and invokes FCM for customer/vendor/courier targets. Phase 0 must find
every deployed subscriber instance and every publisher call/event value. Kafka
migration requires one logical notification intent per legacy occurrence and
must prove that the old subscriber is fenced before target sending is enabled.
Redis cache keys/TTLs remain a separate inventory and may remain Redis-backed.

## Capability readiness pack

Before any capability/scope can become target-owned, store these versioned
artifacts and assign an owner/approver:

1. Source inventory and production profile with captured watermark.
2. Product disposition and domain invariants.
3. Target logical/physical model, data dictionary, constraints, and retention.
4. Table/column/value mapping including defaults, quarantine, and reversibility.
5. API/event/provider/frontend compatibility matrix.
6. Backfill and incremental-catch-up design with idempotency/checkpoints.
7. Structural, domain, monetary, security, and side-effect reconciliation rules.
8. Read/write/job/webhook/provider ownership matrix for every migration mode.
9. Capacity estimate, throttle, maintenance window, and abort procedure.
10. Security/privacy review, secrets/keys plan, and production access grant.
11. Observability dashboard, SLO, alert, and support/incident runbook.
12. Cutover procedure, communication, decision authority, and exact stop rules.
13. Rollback feasibility by state, maximum decision time, and forward-fix path.
14. Rehearsal results from production-shaped data plus backup/restore evidence.
15. Post-cutover soak, legacy read-only/archive, retention, and retirement plan.

## Remaining decision register

These are intentionally unresolved and block only the capabilities they affect:

1. Approved production company-to-tenant grouping and data-residency region.
2. Keycloak workforce/customer realm topology, customer and courier phone/OTP,
   password migration/reset, MFA, recovery, and identity-provider policy.
3. Retain/redesign/archive decisions for invitations/referrals, favorites,
   search history, ratings, FAQs/social links, UI merchandising, tags,
   recommendations, kitchens, incidents, and blacklists.
4. Internal courier workforce/dispatch scope, live-location privacy, areas,
   groups, shifts, blocks, and notes.
5. Fiscal receipt provider/legal model, settlement/import/export, and financial
   retention/correction rules.
6. Packaging/deposit/ancillary-item semantics; SPIC/fiscal product
   classification, unit/VAT mapping; scheduled/pre-order reprice, capacity,
   payment/inventory timing, and timezone rules.
7. Internal courier compensation/bonus, vehicle/document, referral, shift, and
   assignment-policy migration.
8. Map/geocoder provider, address normalization, service-zone source, and
   coordinate migration rules.
9. SaaS plan prices, trials, quotas, overages, suspension, invoicing, tax, and
   billing-provider requirements.
10. Target frontend framework/tooling, PWA/native scope, supported clients,
   accessibility/performance targets, and white-label/custom-domain model.
11. PII classifications, consent/legal basis, retention/deletion/legal hold,
   encryption/key management, and data-subject processes.
12. Current official capability contracts for all payment, POS, delivery,
    notification, map, and fiscal providers.
13. Production platform/regions, numeric SLO/error budgets, capacity baseline,
    RPO/RTO, on-call ownership, and disaster-recovery strategy.
14. Exact cutover cohorts/windows, rollback windows, support communication,
    legacy archive access, and destructive retirement approvals.

## Definition of migration complete

Migration is complete only when:

- all production tables/columns/values and non-database stores have an approved
  disposition and reconciliation result;
- every API operation, frontend route/deep link, job, subscriber, provider
  callback, report, and manual operator workflow is target-owned or retired;
- exactly one writer, timer, webhook receiver, and provider-effect owner exists
  for every scoped capability;
- active/in-flight orders, payments, refunds, receipts, POS exports, shipments,
  notifications, and scheduled jobs have settled or have explicit target
  checkpoints;
- money, identity, tenant ancestry, inventory, media, and external references
  meet signed zero-tolerance rules;
- Keycloak, S3, Kafka, Camel, PostgreSQL, frontends, providers, observability,
  backups, restore, rollback, security, privacy, and support gates have passed;
- the soak/rollback window closes without an unresolved critical discrepancy;
  and
- legacy traffic/jobs/credentials/routes are proven inactive before separately
  approved archival and destructive retirement work.
