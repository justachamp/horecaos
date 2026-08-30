# Frontend information architecture

Navigation trees for the two console applications, derived from the
[Delever parity matrix](delever-parity-matrix.md) and the three archived Qoida
applications. Sequenced by [the frontend and parity plan](frontend-and-parity-plan.md);
the framework and design-system decisions are
[ADR 0035](adr/partial/0035-angular-frontend-platform-and-design-system-adoption.md).

This document assigns every capability from the parity inventory to exactly one
screen in exactly one application, and says what was deliberately left out of
each. It is a design input, not a decision record — where a navigation choice
implies a data model, that implication is called out in Part 5 and belongs to an
ADR rather than here.

Both applications are `.console` surface: strict Carbon, square geometry,
platform blue, dense tables, never a tenant accent.

# Qoida front-end information architecture
## apps/control-plane and apps/operations

**Tier legend** — `P` = first single-location pilot (go-live blocker) · `2` = wave 2 (multi-location + Delever parity) · `3` = wave 3 (parity tail, or blocked on a decision)

---

# PART 1 — apps/control-plane

**Audience:** Qoida staff (onboarding, support, platform ops, billing, engineering).
**Template:** the existing platform console (Carbon, 0px corners, platform blue, hairline elevation) fits this app as-is.
**Governing principle:** control-plane administers *the platform*, never *the merchant's business*. Anything a merchant would do for themselves is reached by scoped, audited impersonation into operations — not by a parallel console.

## 1. Overview
| # | Screen | Purpose | Tier |
|---|---|---|---|
| 1.1 | Platform health | Single board: tenants live, order throughput, integration failure rate, fiscalization failure rate, queue lag, SLO burn. | P |
| 1.2 | Alerts & incidents | Active alerts with tenant/provider blast radius and on-call routing. | 2 |

## 2. Tenants
| # | Screen | Purpose & owned features | Tier |
|---|---|---|---|
| 2.1 | Tenant directory | Every tenant: plan, business type, country/residency, status, health score. | P |
| 2.2 | Tenant overview | One tenant at a glance — brands, locations, entitlements, installed providers, open issues, quick actions. | P |
| 2.3 | Brands & locations | The ownership tree; provision brands and locations on a tenant's behalf. **Owns:** brand→location hierarchy; cross-country multi-brand under one tenant (per-brand currency/locale/fiscal regime); business-type assignment. | P |
| 2.4 | Legal entities & tax identities | The INN/legal-entity registry behind branches and which entity each branch fiscalizes under. **Owns:** per-branch INN, tenant spanning several legal entities, billing-vs-legal boundary. | P |
| 2.5 | Onboarding | The resumable onboarding run (ADR 0008): steps, blockers, owner of each. **Owns:** fiscal-code backfill task, SMS sender-alias registration, domain verification, provider install checklist, first catalog import. | P |
| 2.6 | Identity & realm | Keycloak org/realm provisioning state (ADR 0009); staff client, courier client, break-glass. **Owns:** couriers as principals in a separate client; tenant-selection-at-login. | 2 |
| 2.7 | Configuration & policy | Platform defaults, tenant overrides, and a **resolution trace** for any key (ADR 0030: platform → tenant → brand → location → channel). | 2 |
| 2.8 | Impersonation & support sessions | Enter a tenant's operations app under a scoped, time-boxed, fully audited support identity. | P |

## 3. Providers
| # | Screen | Purpose & owned features | Tier |
|---|---|---|---|
| 3.1 | Provider registry | Catalogue of every integratable system (12 POS, 8 aggregators, 6 delivery, 7 payment, 2 SMS, analytics, telephony, fiscal operators) with category, markets, lifecycle state. | P |
| 3.2 | Capability matrix | Which capabilities each adapter declares, so operations can *hide* what a tenant's POS cannot do (ADR 0011). **Owns:** print, read-open-ticket, settle-ticket, stop-list push, catalog pull, courier quote, cancel-cascade, handover-code, courier tiers. | P |
| 3.3 | Installations explorer | Every `(tenant, provider, branch)` installation with credential status, last successful sync, error rate. **Owns:** the per-branch installation model at platform scope; last-inbound-order watermark; silent-skip detection. | P |
| 3.4 | Contracts & versions | Adapter versions, event/schema contract versions, deprecations, consumer compatibility (ADR 0032). | 2 |
| 3.5 | Sandbox & contract tests | Run an adapter against recorded provider fixtures before rollout (ADR 0007). | 2 |

## 4. Integration operations
| # | Screen | Purpose & owned features | Tier |
|---|---|---|---|
| 4.1 | Message flow | Outbox/inbox throughput, lag, stuck partitions (ADR 0004, 0005). | P |
| 4.2 | Dead letters & replay | Failed messages by cause with selective, audited replay (ADR 0006). The merchant-visible replay in operations is a scoped subset of this. | P |
| 4.3 | Webhook deliveries | Inbound/outbound webhook history and redelivery (Wolt Drive status webhooks, aggregator pushes). | 2 |
| 4.4 | Error taxonomy | Map raw provider failures to operator-legible causes and fixes. **Owns:** unmapped product, unmapped payment type, inactive product in POS, expired credential, venue mismatch. | 2 |

## 5. Commerce (plans, entitlements, billing)
| # | Screen | Purpose & owned features | Tier |
|---|---|---|---|
| 5.1 | Plan catalog | Plans, terms, 6/12-month term discounts, trials, activation deposit. | 2 |
| 5.2 | Module catalog | Sellable modules and their **heterogeneous billing units** — per brand, per branch (Kitchen/KDS), per kiosk unit, per courier service, one-off (white-label app). | 2 |
| 5.3 | Entitlements | What each tenant is entitled to; grants, module locks, overrides. **Owns:** ADR 0021 entitlement state; the composition rule `permission × entitlement × business type`. | P |
| 5.4 | Metering & usage | Counted units per tenant per period, reconciled to invoices. | 2 |
| 5.5 | Invoices & wallet | Subscription invoices, prepaid wallet ledger, top-ups, **credit expiry**. | 2 |
| 5.6 | Dunning | Arrears state machine and which features are restricted at each stage. | 3 |

## 6. Compliance & fiscal
| # | Screen | Purpose & owned features | Tier |
|---|---|---|---|
| 6.1 | Fiscalization operations | Cross-tenant fiscal receipt failures by operator and cause; bulk retry. **This is a legal go-live blocker in Uzbekistan and Qoida has no ADR for it.** | P |
| 6.2 | Fiscal reference | IKPU/MXIK classifier data, validation rules, package codes, Data Matrix marking config. | P |
| 6.3 | Residency & hosting | Where each tenant's data lives; country → currency/locale/timezone/fiscal regime (ADR 0034; UZ/KZ/GE). | 2 |
| 6.4 | PII & data classification | Classification registry, retention schedules, export egress audit, DSAR/erasure (ADR 0029). **Owns:** courier-location retention, abandoned-cart retention, candidate-record retention. | 2 |
| 6.5 | Approvals | Platform-side approvals: residency change, bulk export, retention override (ADR 0027). | 2 |

## 7. Access & security
| # | Screen | Purpose & owned features | Tier |
|---|---|---|---|
| 7.1 | Staff & roles | Qoida employees, roles, tenant scoping. | P |
| 7.2 | Capability registry | The canonical capability vocabulary that tenant roles are assembled from (ADR 0025), granular to per-bulk-action. | P |
| 7.3 | Effective access debugger | "Can this principal do this, on this resource, and why" (ADR 0003 + grants cache). | 2 |
| 7.4 | Secrets | Inventory of provider credentials by tenant/branch, rotation status, last use — **never rendered** (ADR 0028). Replaces Delever's `base64(login:password)` pseudo-secret. | P |
| 7.5 | Audit log | Platform actions including impersonation sessions; non-human actors are first-class (ADR 0027). | P |

## 8. Platform configuration
| # | Screen | Purpose & owned features | Tier |
|---|---|---|---|
| 8.1 | Feature flags & rollout | Progressive enablement by tenant cohort. **Replaces** Delever's merchant-facing v1/v2 module toggles. | P |
| 8.2 | Business types | The product shapes (restaurant, courier service, pharmacy, florist) and what each enables/defaults. | 2 |
| 8.3 | Reference data | Countries, currencies, locales, timezones, phone formats, national holiday seeds, default SLA buckets. | P |
| 8.4 | Notification providers & template moderation | SMS gateways, sender aliases, and the **approval state of regulated templates** — sends blocked while pending (Eskiz/Playmobile moderation). | 2 |
| 8.5 | Policy defaults | Platform-level order/SLA/retention defaults that tenants inherit. | 2 |

## 9. Migration & cutover (ADR 0024)
| # | Screen | Purpose | Tier |
|---|---|---|---|
| 9.1 | Migration runs | Per-tenant legacy import: stages, counts, failures, resumability; provenance retained (`source = import`). | P |
| 9.2 | ID mapping explorer | Legacy ↔ Qoida identity resolution across entities. | 2 |
| 9.3 | Dual-run comparison | Legacy vs Qoida output diffing during cutover. | 2 |
| 9.4 | Cutover checklist | Go/no-go per tenant. | 2 |

## 10. Support
| # | Screen | Purpose | Tier |
|---|---|---|---|
| 10.1 | Global lookup | Find an order, customer, courier or device across tenants by **any** identifier including provider external IDs (`eatsId`, Wolt, Uzum Tezkor). Returns identifiers and status; PII gated by capability. | P |
| 10.2 | Tenant issue queue | Open problems per tenant with linked evidence (DLQ entries, failed fiscalizations, expired credentials). | 2 |

### Deliberately excluded from control-plane
- **All merchant operations** — order board, order editing, KDS, dispatch, catalog authoring, campaigns, content. Rationale: a shadow operations console inside the platform app doubles the surface area, splits the source of truth, and turns every support request into an unaudited write. Support enters operations by impersonation (2.8) instead.
- **The merchant's own plan, wallet and invoice views** — those live in operations 8.6. Control-plane owns the platform side of the same objects; the tenant-facing view is not duplicated here.
- **Consumer content** (banners, stories, news, gallery, recipes, careers) and **storefront configuration** (bot token, domain, colours) — merchant-owned self-service, not a platform concern.
- **Merchant BI dashboards** — control-plane gets platform metrics only; tenant analytics belongs to operations 7.x.
- **Telephony operator UI** — a merchant call-centre tool, not platform administration.
- **Delever's `Управление версиями` per-module v1/v2 toggles** — that is a symptom of running two frontends simultaneously. Qoida builds once and rolls out with 8.1 flags.

---

# PART 2 — apps/operations

**Audience:** the merchant's own staff — operators/call-centre, dispatchers, kitchen, branch managers, marketers, finance, owners.
**Templates needed:** the platform console template is *not sufficient* — see §"Component gaps". Operations needs three shells: (a) **operator console** (dense, sidebar, keyboard-first), (b) **device/KDS fullscreen** (touch, no sidebar, offline banner), (c) **wallboard** (TV-distance, oversized counters).
**Vocabulary mapping vs Delever:** Qoida's `Delivery` replaces Delever's split between `Персонал → Курьеры` and `Настройки → Доставка`. Qoida's `Finance` collects what Delever scatters across Orders, Персонал and Настройки. Qoida folds Delever's V2 `Контент` into `Marketing`.

---

## 0. Home
| # | Screen | Purpose & owned features | Tier |
|---|---|---|---|
| 0.1 | Live board | Wall-board for the shift supervisor. **Owns:** Лайвтайм Дашборд; oversized "В процессе" / "Отменено" counters; the canonical definition of the "in progress" grouping over concrete statuses; live source-mix and type-mix; live branch and operator leaderboards; per-branch active-order load (also shown in the branch picker at order entry). | P |
| 0.2 | My work | The signed-in user's own queue and personal numbers. **Owns:** Личный кабинет statistics — orders by channel, revenue by payment method; personal data; UI personalization (**user-scoped**, unlike tenant-scoped settings). | 2 |

---

## 1. Orders
| # | Screen | Purpose & owned features | Tier |
|---|---|---|---|
| 1.1 | Order board | The operator cockpit: status-tab queues over a filterable, searchable table with row action menu and bulk actions. **Owns:** order list table and columns (id, branch, client, delivery type, courier + courier type, source icon, payment icon, order price, delivery fee, accepted-by, created-by, courier ETA); status tabs; filters (period, branch, aggregator, source, delivery type, courier, payment type) persisted per tab; **search across per-provider external IDs**; bulk selection + **bulk courier assignment** with defined partial-failure semantics; late-order highlight; live per-status counts; backward status transitions (gated, reason-required, audited — an improvement on legacy's silent one-click reversals). | P |
| 1.2 | Order detail | The full order record and every lifecycle action, expressed as **intent-named commands** (`add-items`, `change-payment-method`, `change-address`) with optimistic concurrency — not a loose partial PUT. **Owns:** order card; multi-step (multi-branch) composition with per-step status; status timeline with per-stage clocks; add items; edit customer/address/type/pre-order time; change payment type; assign in-house courier; call external courier; **provider quote-delta confirmation** (the Millenium pattern: estimate → re-quote → operator accepts or abandons); cascading cancel at the provider; the three comment channels (customer→order, customer→line, operator→kitchen); print to POS (suppressed when the POS lacks the capability); complete with completion reason; **cancel with reason + write-off type**; manual re-fiscalize; POS integration errors surfaced with a fix path; handover-code state; both delivery money fields (charged to customer vs billed by provider); change-due (Сдача); server-supplied `actions[]` capability array driving which affordances render. | P |
| 1.3 | New order | Call-centre order entry. **Owns:** manual order creation; client lookup by phone with auto-create and an order-history peek; address by map pin or search, with **дом / квартира / подъезд / этаж / ориентир as structured fields**; item search + interactive full-screen menu; pre-order time with out-of-hours branch-resolution warning; operator-channel allowed payment/order types; promo code entry when permitted; change-due; manual aggregator order creation with no live provider binding; repeat/re-order. | P |
| 1.4 | Drafts & abandoned carts | Carts started and never converted, by channel, for recovery and funnel diagnosis. **Owns:** Черновики; abandonment breakdown by source. | 2 |
| 1.5 | Reservations | Table bookings: create, edit, cancel, complete. **Owns:** Бронирования list and detail; multi-table bookings; auto-create client on unknown phone; external reservation ID as the displayed identifier; time-interval availability per table. (Floor plan is configured in 10.2.) | 3 |
| 1.6 | Call centre | Inbound/outbound telephony: screen-pop with caller lookup, click-to-call, call log. **Owns:** softphone; call list; caller→customer resolution; the raw inputs to operator KPIs. | 3 |

---

## 2. Kitchen — *device shell*
| # | Screen | Purpose & owned features | Tier |
|---|---|---|---|
| 2.1 | Kitchen queue (KDS) | The live production queue for one branch, partitioned by fulfilment type and colour-coded by SLA. **Owns:** Кухонные заказы with delivery/pickup/hall/aggregator tabs; courier ETA on the ticket; exact per-status counts; per-line comments and preset product comments; department routing of lines; **branch open/closed toggle**; assign own courier or dispatch an external provider; change payment type; create an order from the kitchen. | P |
| 2.2 | Buffer | Orders accepted but deliberately not yet released to the line. **Owns:** Буфер; the **kitchen fire time** as a field distinct from created-at and promised-at; paid-only hold; manual release. | 2 |
| 2.3 | Expo / handover (Раздача) | Assembly and release: per-department ready roll-up, packing check, custody transfer. **Owns:** Раздача; per-department ready states rolling up to order-ready; **provider handover-code verification as the release gate** (compare server-side; never ship the expected code to the device). | 2 |
| 2.4 | Display board (VDU) | Read-only wall display of the combined queue. **Owns:** VDU; **provider-assigned external identifiers shown to humans**. Keep the name "VDU" — it is R-Keeper vocabulary this market expects. | 2 |
| 2.5 | Stop list | Take items out of sale now and put them back, fanning out to every channel. **Owns:** Стоп-лист (available / on-stop tabs, single and bulk); stop **scope** (product × branch/menu/terminal/brand) and propagation; stop **source** (manual, POS push, quantity threshold, schedule); the single **"why can't I sell this?" explainer**; stop-change digest notification. | P |
| 2.6 | Capacity & buffer settings | Throughput ceilings feeding load levelling and cook-count planning. **Owns:** max preparations per hour per product per branch; cook headcount output. | 3 |

---

## 3. Delivery
| # | Screen | Purpose & owned features | Tier |
|---|---|---|---|
| 3.1 | Dispatch board | Live assignment: unassigned orders against courier cards, drag to assign, click to filter, X to unassign, map of points and routes. **Owns:** диспетчерский модуль; drag-drop assign/unassign as idempotent audited operations; per-courier current load; the `/deliveries` variant for courier-service tenants. | P |
| 3.2 | Live map | Every courier on one map. **Owns:** trackcourier; 10s refresh; active order count; **device battery level**; branch filter; in-house and provider couriers on one canvas. | 2 |
| 3.3 | Couriers | The in-house roster. **Owns:** courier CRUD; branch bindings (many-to-many); passport / PINFL / driving licence / vehicle registration / plate / fuel; photo; emergency contact; courier groups; courier-app account provisioning (**password is never derived from the passport number**); online status and rating (read-only). | P |
| 3.4 | Courier types & rates | Vehicle classes and pay schemes. **Owns:** Тип курьера (авто/мото/вело/грузовой, minimum distance, starting minute, work mode); Тариф курьера = fixed + per-order + per-km, kept **strictly separate from the customer-facing delivery tariff**; bonus/penalty rule definitions; the three salary models expressible by zeroing components. | 2 |
| 3.5 | Shifts & attendance | Rosters, shift activation, hours worked. **Owns:** Посещаемость; "check courier work schedule" as an **authorization gate on order acceptance**; hours feeding payout. | 2 |
| 3.6 | Delivery zones | Draw the polygons that decide serviceability, branch selection, tariff and provider. **Owns:** зоны доставки; branch geozones; regions with SW/NE bounding box constraining the geocoder; free geozone; zone → branch set; zone → courier-service binding; zone-beats-branch tariff precedence; bulk geozone upload. *(Qoida should collapse Delever's three overlapping geometry layers into one Zone entity with typed roles.)* | P |
| 3.7 | Delivery tariffs | What the **customer** pays. **Owns:** distance bands / per-km tiers; base and max distance; minimum order value; free-delivery-from threshold per zone; peak-hour surcharge windows; provider-quote-vs-own-tariff toggle; customer-visible tariff description; the applied tariff stamped immutably on the order. | P |
| 3.8 | Dispatch rules | **One provider-agnostic rule engine** replacing the near-identical config duplicated inside all five Delever provider pages. Conditions (source, zone, branch, timing basis, order status, prep time, delay minutes) → action (provider, service tier, fallback). **Owns:** auto-dispatch triggers; cascade/simultaneous multi-provider search with cheapest selection **and loser cancellation**; auto-recreate after late payment; courier order grouping with merge radius; unpaid-order cancellation timeout. | P |
| 3.9 | Courier policy | What a courier may see and do. **Owns:** GPS radius validation (accept radius in km, action radius in m); show only kitchen-ready orders; reveal customer location before vs after accept; post-delivery payment check; acceptance SLA in minutes with reassignment; max concurrent orders per courier; courier billing mode. | 2 |

---

## 4. Catalog
| # | Screen | Purpose & owned features | Tier |
|---|---|---|---|
| 4.1 | Products | The tenant product library. **Owns:** list ergonomics at 1000+ items (persisted filter/scroll, fast search, no-duplicate pagination); the five product types; **bulk edit of IKPU / package code / name**; bulk delete; copy-id; public share slugs. | P |
| 4.2 | Product editor | One product, end to end. **Owns:** parent↔variant relation (the variant is the priceable unit); per-variant price/weight/photo/modifiers; modifier groups with min/max and **modifier-level fallback to group values**; nested modifier variants; **hidden modifiers auto-selected by order type** (packaging); combo groups with per-variant price map; recommended products filtered by active + in-menu + not-stopped; **IKPU/MXIK + package code at every priceable node**; `needMarking` and the rule that marked units expand to separate POS lines; VAT; alcohol % and 18/21 age gate; weight, measure, catchweight + weight quantum, splittable; **portions as a decimal attribute**; КБЖУ; ordered images with per-aggregator overrides; tags; ingredients (a customer-facing composition label, **not** a BOM); kitchen department; per-item sale schedule; AI assist for description and composition only. | P |
| 4.3 | Categories | Hierarchical taxonomy. **Owns:** parent_id tree, sort order, active flag, description/image, category schedules, bulk delete. | P |
| 4.4 | Menus | The per-location assortment — **the single biggest divergence from ADR 0016**. **Owns:** the named Menu entity; bind menu to branch; copy menu to another menu/branch; add products with filtered select-all; per-order-type visibility; **per-channel price overrides** (hall, generic aggregator, per-aggregator); mass enable for aggregator; per-item schedule; per-item stock quantity with daily default and auto-reset; per-aggregator stop threshold; catalog base settings (use stock logic, QR/kiosk take hall prices). **Separate `offered_on_channel` from `price_on_channel`** rather than conflating them as Delever does. | P |
| 4.5 | Catalog sync & import | Bring the catalog in and see, per item, what happened. **Owns:** POS import with a persistent mapping table showing linked/unlinked state and manual pairing; import language selection; display-order carry-over; duplicate modifier/variant de-dup; price re-import; **per-item outcome reporting — never a silent skip**; Excel export, create/update templates, **dry-run import job with row-level summary**; image-by-URL fetch (SSRF-guarded). | P |
| 4.6 | Publication & channel readiness | Validate and preview the menu as each channel will render and reject it. **Owns:** pre-publication validation report (IKPU, package code, description, unit, weight, photos); aggregator preview in mobile and desktop modes in that aggregator's language; per-channel projection/override layer; combo transport format per aggregator; publish and rollback. | 2 |
| 4.7 | Reference data | The vocabularies products depend on. **Owns:** attributes (the variant axis backing `size_id`); brands; tags; ingredients; kitchen departments; product comment presets (a **controlled vocabulary with IDs**, because R-Keeper transports them as modifiers). | 2 |
| 4.8 | Price list | Bulk price change across a filtered selection, and the named price planes. **Owns:** Прейскурант; percentage/absolute change; hall vs base plane; aggregator-propagation flag. | 2 |
| 4.9 | Auto-add rules | Server-side cart injection that applies identically to web, bot and operator entry. **Owns:** the three rule kinds — plain, product-triggered, and **portion-band** — with order types, sources, offer-vs-silent, min/max quantity, and the removable-from-basket flag. | 2 |

---

## 5. Customers
| # | Screen | Purpose & owned features | Tier |
|---|---|---|---|
| 5.1 | Customer list | The CRM grid. **Owns:** list + search + status; header counters (total, ordered today, registered today) computed from **the same metric layer as the dashboard**; bulk CSV import with retained provenance; manual create; filtered export as an audited PII egress event. | P |
| 5.2 | Customer detail | **The screen Delever does not have.** One customer's whole record. **Owns:** profile + DOB; **consent state and marketing eligibility separated from record existence** (imported and operator-created customers start non-contactable); saved addresses, operator-visible and editable; order history + reorder; cashback and deposit balance ledger; promo redemptions; reviews left; blacklist/suppression with reason, actor, expiry and defined enforcement point; identity merge for aggregator-masked identities. | P |
| 5.3 | Segments | Build, **save**, and reuse audiences; hand off to a campaign. **Owns:** RFM builder (recency, frequency, monetary, registration source); the saved-segment entity; segmentation table; birthday-window segment (day+month, year-agnostic). | 2 |
| 5.4 | Reviews | Service-recovery board: New → In progress → Resolved / Closed unresolved. **Owns:** the kanban; four scored dimensions (meal, operator, courier, delivery time); compensation actions (promo code, bonus, discount); handling history; filters by branch/courier/rating. | 2 |
| 5.5 | Feedback settings | When feedback is asked for and what tags are offered. **Owns:** review tag library (localized text, icon, **category × sentiment**); prompt timing per channel/order type; public visibility. | 3 |

---

## 6. Marketing
| # | Screen | Purpose & owned features | Tier |
|---|---|---|---|
| 6.1 | Promotions | The discount/markup/promo-code rule engine. **Owns:** type (discount / **markup** / promo code); scope (order vs product); fixed, percentage, delivery, gift, **free-form aggregator** (externally computed, flagged as non-reproducible); gift triggers and multiplicity (`equal` vs `at least`); manual vs automatic activation; eligibility by order type × source × **payment method**; Nth-order + first-order-source; priority with a defined tie-break; stackable and cashback-compatible flags with **specified arithmetic**; birthday-only; date range, intra-day window, 24/7, weekday toggles; geozone polygon; branch / customer / category / product scoping plus **excluded products**; usage limits (Qoida addition); pre-order re-validation at the scheduled time; **quote simulator over a versioned policy snapshot** (Qoida addition — Delever's own notes record repeated double-deduction and cross-channel divergence bugs here). | 2 |
| 6.2 | Promo codes | Issued codes as first-class rows. **Owns:** shared campaign codes and **per-instance unique codes** (late-order apology), owner, expiry, redemption state and ledger. | 2 |
| 6.3 | Loyalty | Cashback/bonus and stored value. **Owns:** accrual rate (per channel/branch), redemption cap as a share of order value, point expiry + pre-expiry notification, deposit accounts, POS balance sync. Loyalty spend and deposit are **payment methods, not discounts**. | 3 |
| 6.4 | Campaigns | One-off broadcasts to a segment. **Owns:** SMS (customers and **couriers** as separate audiences, history, per-recipient delivery receipts); push (3:1 cover, scheduled send, recipient and read counts); Telegram post (media type, `{client_name}`, unregistered-only targeting, inline buttons, test send, sender recorded); RFM targeting; **consent and suppression enforced in audience selection** (Qoida addition — Delever has no opt-out at all). | 2 |
| 6.5 | Automations | Triggers that send without a human. **Owns:** birthday; cashback balance change (accrual vs debit templates); **late-order apology with auto-generated unique promo code**; inactivity / funnel abandonment (missed action, referral filter, delay minutes) with cancellation when the action completes. | 2 |
| 6.6 | Referrals | Trackable acquisition and the referral programme. **Owns:** website `?ref=` links; Telegram `startapp` deep links; Mini-App / BotFather setup as a guided resumable flow; referral programme rewards (**to be specified from scratch — Delever's is undocumented**). | 3 |
| 6.7 | Content | Everything customer-facing that is not a product. **Owns:** banners (image **or video**, priority, per-channel placement, active period); stories (group → ordered slides with per-slide media, duration, CTA, view counts); pop-ups (delay, duration, priority, time window, **plus frequency capping** — a Qoida addition); promotion content cards **linked to the promotion rule** (Qoida improvement: Delever keeps them unrelated so ads drift out of sync with pricing); news; gallery albums; recipes (editorial); vacancies (a public listing only). | 2 |
| 6.8 | Storefront merchandising | What shows on the home screen of each channel. **Owns:** home-page product groups (manual and by-category with priority ordering); offer carousel; multi-brand switcher entries; marketplace layout mode. | 2 |

---

## 7. Reports
| # | Screen | Purpose & owned features | Tier |
|---|---|---|---|
| 7.1 | Business overview | The headline period dashboard. **Owns:** KPI counters (revenue, orders, average check, cook/delivery/pickup time, distance); sales funnel by status with cancel reasons and late completions; source mix; payment mix; the **shared global filter bar** applied to every widget. | P |
| 7.2 | Order reports | Per-order operational and commercial logs with export. **Owns:** per-stage duration report; commercial/CRM order log; daily operations report (gross vs net, cancellations, per-fulfilment-type count+sum, per-3PL counts); daily summary reports 1 and 2; delayed-orders report; Excel/CSV export as an **audited PII egress**. | P |
| 7.3 | Branch & SLA reports | Per-branch performance. **Owns:** branch leaderboard; SLA time-bucket distribution with **tenant-configurable boundaries** (Delever hard-codes six); branch report with per-channel counts and the payment-method split used for cash-collection control. | 2 |
| 7.4 | Courier reports | **Owns:** courier leaderboard; efficiency (min/avg/max/total distance, transit hours); courier SLA buckets; delivery-sum-by-tariff audit; external-delivery cost report (order amount vs charged delivery vs provider cost vs provider status). | 2 |
| 7.5 | Staff reports | **Owns:** operator leaderboard (including machine principals as pseudo-operators); operator performance (orders, revenue, **average handling time**, average check, per-channel, delivery vs pickup); operator product/upsell report with receipt depth; telephony KPIs when 1.6 is installed. | 2 |
| 7.6 | Customer analytics | **Owns:** KPI tiles with **published formulas** (new customers, basket depth, order frequency, customer value, LTV) and a stated rule on cancelled/refunded orders; acquisition-source chart; registrations vs orders trend; repeat-purchase distribution; cohort views; new vs returning revenue and share; RFM cross views; the product funnel (visits → registrations → cart adds → orders). | 2 |
| 7.7 | Product analytics | **Owns:** ABC by revenue share with cumulative; XYZ by stdev / coefficient of variation; the ABC×XYZ matrix as filters; product report; kiosk sales report. | 2 |
| 7.8 | Demand forecast | **Owns:** forecast vs actual by hour, branch, department and product; the **operating-day window that may cross midnight**; holiday-aware modelling (calendar owned in 10.10). | 3 |
| 7.9 | Marketing reports | **Owns:** promo code summary; per-code redemption detail (customer, channel, timestamp); per-customer discount history; campaign delivery and read stats. | 2 |
| 7.10 | Geography | **Owns:** order-density heatmap; today's orders as pins; delivery-time and distance histograms; day-of-week × hour cohort. | 3 |

---

## 8. Finance
| # | Screen | Purpose & owned features | Tier |
|---|---|---|---|
| 8.1 | Payments & settlements | **Owns:** the `payment[]` array per order (**split tender**: cash + cashback + deposit); payment status; **re-issue a payment invoice to a phone number other than the order's**, idempotently; refunds; provider-mappable cancellation reason for voids; auto-send payment link. | P |
| 8.2 | Fiscal receipts | The fiscalization queue. **Owns:** per-order fiscal status and URL/codes; manual retry; which INN was used; fiscalized payment types; delivery-line IKPU; marking codes transmitted; fiscal operator errors. | P |
| 8.3 | Cash reconciliation | Courier shift close and инкассация. **Owns:** courier daily/shift totals by payment method; **cash acceptance and shortfall recording** (Qoida addition — Delever's courier hands in a report with nowhere to record it); bonus-paid amount reducing cash due. | 2 |
| 8.4 | Delivery cost reconciliation | Provider invoices vs recorded per-delivery cost. **Owns:** акт сверки per provider; charged-vs-cost delivery margin; provider terminal status. | 2 |
| 8.5 | Courier payouts | The settlement run. **Owns:** salary report (orders, km, hours, **вовремя**, penalties, bonus, **К оплате**) with an exact, immutable on-time definition; the courier balance as an **append-only ledger** with credits and debits; commission deductions; export with column fidelity. | 2 |
| 8.6 | Subscription & billing | The merchant's own Qoida account. **Owns:** current plan and term; purchasable modules with their billing units and inline purchase; prepaid wallet + top-up via Click/Atmos (UzCard, Humo, Visa, Mastercard); credit-expiry warning; arrears state and restricted-feature banner; invoices. | 2 |

---

## 9. Staff
| # | Screen | Purpose & owned features | Tier |
|---|---|---|---|
| 9.1 | Users & roles | **Owns:** accounts and role assignment; the capability grid granular to per-bulk-action; capability search; role templates; permission-gated navigation; **the locked-by-plan vs denied-by-permission distinction** with an inline upsell on the former. Server-enforced, unlike the legacy dashboard's client-side-only checks. | P |
| 9.2 | People | The staff directory. **Owns:** operator records; branch bindings; contact persons; **operator ↔ POS operator ID mapping**; the definition of created-by and accepted-by attribution. | 2 |
| 9.3 | Activity & audit | **Owns:** История изменений with **field-level before/after diff**; a named human actor even for background paths (never "server"); a bulk action producing N records, not one. | 2 |
| 9.4 | Approvals | **Owns:** approval queue for discretionary discounts, penalties levied against a worker, PII exports, refunds above a threshold. | 3 |

---

## 10. Settings
| # | Screen | Purpose & owned features | Tier |
|---|---|---|---|
| 10.1 | Brand profile | **Owns:** trade name, logo, aggregator/QR banner, description, country (driving currency, phone format, timezone), supported languages + default language, contact. | P |
| 10.2 | Locations | The branch registry and each branch's profile, with a **cascade action** to apply a change to every location of the brand. **Owns:** branch CRUD; phone, address, landmark, map pin; INN / legal entity; permitted order types; sort order; active; order limit; menu binding; delivery-tariff binding; tags; localized content; venue attributes (seats, average cheque, parking, playground, virtual tour). **Tabs:** *Hours* (separate venue and delivery schedules, 24/7, **time-of-day prep-time intervals**, pre-order lead times for delivery and pickup); *Floor plan* (sections and tables). | P |
| 10.3 | Order policy | The rulebook governing every order. **Owns:** VAT %, minimum order sum, working day start/end; average and maximum order time; **late threshold + indicator colour**; distance mode (road vs radius) + routing poll interval; auto-accept (eligible channels, minimum prior successful orders as an anti-fraud gate); branch order limits; reject out-of-zone; **courier-first vs branch-first acceptance**; show-only-paid-orders to branch and courier; pre-order branch resolution (nearest vs first-to-open); operator order-entry options (allowed payment/order types, callback flag, promo-code permission, change-due field). | P |
| 10.4 | Sales channels | **Owns:** the channel registry (system type + named instances, icon, active); **the per-channel capability matrix** — payment methods × channel, order types × channel; per-channel toggles (operator callback, promo code, change due); per-channel theming colours; per-channel social links. | P |
| 10.5 | Channel setup | Configure each storefront. **Owns:** Telegram bot (token verify, languages, menu buttons, mini-app mode, per-language texts, dynamic button, public offer, positive/negative review chats); website (subdomain or custom domain via **DNS TXT verification — replacing Delever's credential handover**, menu ordering, colours, behaviours, social links, about, header layout, SEO meta templates, static pages); mobile app per platform; kiosk device registry (branch, booking customer profile, fiscal INN, printer width, VAT, service PIN, device credentials, 9:16 idle media, fiscal operator, terminal protocol, marking endpoint, table service, available reports); QR dine-in modes (view-only / pay-open-bill / full self-ordering); the admin order-entry channel. | P |
| 10.6 | Payment methods | **Owns:** the payment type registry (localized name, icon, active, base type: cash / card / online / **cashback** / **deposit** / global pay) and its acquirer binding. | P |
| 10.7 | Fiscalization | **Owns:** INN per legal entity and branch; which payment types are fiscalized; fiscal operator; **delivery-line IKPU + package code**; marking enablement and endpoint; VAT defaults; fiscalization type. | P |
| 10.8 | Integrations | The provider hub. **Owns:** the **per-branch installation model** with tenant default → branch override; POS installs (iiko/Syrve incl. the plugin-vs-Transport failover toggle, R-Keeper XML and WH, Jowi, Poster, 1C, Paloma 365, Neon Alisa, Yaros, Clopos, Ali POS, yTimes); aggregator installs (Uzum Tezkor, Yandex Eda, Wolt, Chocofood, Foody, Click Mini-App, My Uzbekistan, Express24) plus a generic partner integration; delivery-provider installs (Yandex, Uzum, Noor, Wolt Drive, Millenium); payment gateways (Payme + Go, Click + PASS, Uzum, TipTop + Google/Apple Pay, Atmos, Kaspi, Epay); SMS (Eskiz, Playmobile) with sender alias; analytics (GTM, GA4, Search Console) and the GA4 ecommerce event contract; telephony provider; **all mapping tables** (products, payment types, discounts, couriers, cancellation reasons, channel → POS table/order-category code); partner API credentials as a **proper OAuth client with a rotatable secret**; **health & errors** (last successful inbound order per branch × provider, error frequency, operator-legible taxonomy, merchant-scoped replay). | P |
| 10.9 | Notifications | **Owns:** Telegram chat IDs per event class per branch (orders, errors, cancellations, dispatch failures, stop list) with topic IDs; channel selection; **order-status templates keyed by `(status × channel × order type × source × language)`** with ~13 merge variables and the show-item-prices toggle; OTP/confirmation templates carrying **provider moderation state, with sending blocked while pending or rejected**; payment-link auto-send; aggregator shift open/close notifications in the tenant country's timezone. | P |
| 10.10 | Reference data | **Owns:** cancellation reasons (internal name + softer customer-facing text per language + **write-off type**); completion reasons; business calendar (holidays incl. movable Islamic dates, **business-day boundary that may cross midnight**, weekend definition); SLA bucket boundaries; branch tags. | P |
| 10.11 | Data & privacy | **Owns:** retention schedules for abandoned carts, courier location, candidate records; consent state definitions; DSAR/erasure requests; export audit visible to the merchant. | 3 |

---

### Deliberately excluded from operations
- **Platform machinery** — plan/module catalog authoring, entitlement grant editing, metering internals, cross-tenant provider health, secrets rotation policy, residency assignment, capability-registry authoring, contract tests, platform-wide DLQ. Merchants see *their own* usage, plan, invoices and integration errors with a scoped replay; not the machine underneath.
- **Tenant, brand and location creation.** Merchants *request* an additional brand or location; provisioning happens in control-plane 2.3 so that legal entity, residency, entitlement and metering are settled at creation.
- **Keycloak realm/org internals.**
- **The customer storefront, the courier mobile app, and the kiosk device app.** All three are separate deployables. Operations *configures* them (10.5, 3.3) and *previews* them (PhoneFrame), but does not contain them. Everything in the inventory tagged "customer app profile", "courier app order flow / earnings / history", "bonus-payment visibility to courier" belongs to those apps.
- **Order status vocabulary CRUD.** Delever models statuses as configurable `status_id` UUID rows. Qoida keeps a **fixed, code-owned state machine parameterised by the courier-first/branch-first policy** (10.3). A tenant-editable status list makes ADR 0032 event contracts, status-driven automations and cross-tenant reporting ungovernable. This is an explicit parity gap, taken on purpose.
- **Ingredient-level BOM, tech cards, costing and waste.** The inventory explicitly corrects the assumption: Delever's `Ингредиенты` is a customer-facing composition label and `Рецепты` is editorial content. Neither is a bill of materials. Do not scope one on parity grounds.
- **AI generation of IKPU/MXIK or package codes.** Delever ships it behind a liability disclaimer. Qoida offers AI assist for descriptions and composition only (4.2); a tax classifier code must be entered or validated by a human before it can be used for fiscalisation.
- **A recruitment ATS.** Vacancies stay as content (6.7); a candidate pipeline collecting free-text CVs and phone numbers from anonymous public users is a retention liability with no revenue attached.
- **`Tenders`.** Present in Delever V2 with no documentation and no V1 counterpart. Excluded pending evidence it is a real feature.
- **Per-tenant v1/v2 module toggles, and beta switches on a personal profile page.** Tenant-scoped flags never live on a user-scoped screen.

### Where operations beats Delever (not just matches)
1. **Customer detail page** (5.2) — Delever has none; service recovery, consent and identity merge have nowhere to live in their product.
2. **One provider-agnostic dispatch rule engine** (3.8) — Delever duplicates near-identical config in five provider pages and therefore cannot express provider fallback.
3. **Saved segments as reusable entities** (5.3) feeding campaigns directly, rather than an ad-hoc filter re-typed per send.
4. **Quote simulator over a versioned policy snapshot** (6.1) — makes deterministic pricing survivable under a priority-ranked, stackable, geo- and time-conditional promotion set.
5. **Per-item outcome reporting on catalog sync** (4.5) — Delever silently skips unmapped external IDs with no error.
6. **A single availability explainer** (2.5) unifying schedule, stock, stop, channel disable and POS state.
7. **Tenant-configurable SLA buckets** (7.3) — Delever hard-codes six, which cannot be changed later without invalidating historical comparisons.
8. **Consent and suppression enforced at audience selection** (6.4) — Delever's marketing docs contain no unsubscribe flag anywhere.
9. **Cash-shortfall recording** (8.3) — closes the loop Delever leaves open at courier shift close.
10. **Frequency capping on pop-ups and usage limits on promo codes** — both absent from Delever.
11. **DNS TXT domain verification** and **OAuth client registration with rotatable secrets** replacing credential handover and `base64(login:password)`.
12. **One metric layer** shared by 5.1's header counters and 7.x's tiles, so two screens cannot disagree on average check.

---

# PART 3 — Pilot vs later, at a glance

## First single-location pilot — operations (34 screens)
`0.1` · `1.1 1.2 1.3` · `2.1 2.5` · `3.1 3.3 3.6 3.7 3.8` · `4.1 4.2 4.3 4.4 4.5` · `5.1 5.2` · `7.1 7.2` · `8.1 8.2` · `9.1` · `10.1 10.2 10.3 10.4 10.5 10.6 10.7 10.8 10.9 10.10`

Settings dominates the pilot and that is correct: you cannot take one legal order in Uzbekistan without a location with an INN, a menu bound to it, a zone, a tariff, a payment method wired to Payme or Click, a fiscal path, a Telegram bot or website, and an order-status message. Everything else — reporting depth, promotions, loyalty, reviews, reservations, telephony, payouts — is deferrable without blocking revenue.

## First single-location pilot — control-plane (23 screens)
`1.1` · `2.1 2.2 2.3 2.4 2.5 2.8` · `3.1 3.2 3.3` · `4.1 4.2` · `5.3` · `6.1 6.2` · `7.1 7.2 7.4 7.5` · `8.1 8.3` · `9.1` · `10.1`

Note `6.1 Fiscalization operations` is pilot despite Qoida having **no ADR for fiscalization at all**. It is a legal requirement, it needs a retry path with an operator-visible replay control, and it is not a subset of ADR 0013.

## Wave 2 — what closes the Delever gap
Kitchen buffer/expo/VDU · live courier map · courier rates, shifts and policy · publication preview · price list · auto-add rules · segments · reviews · the whole of Marketing · reports 7.3–7.7 and 7.9 · Finance 8.3–8.6 · Staff 9.2–9.3 · control-plane commerce, residency, PII and migration tooling.

## Wave 3 — decisions before design
Reservations and floor plans · call centre/telephony · loyalty · referral programme · demand forecast · geography reports · feedback settings · approvals · data & privacy self-service. Each is blocked on a product decision named in the inventory's open questions, not on engineering.

---

# PART 4 — Design system gaps

The current set (Button, Icon, DataTable, EmptyState, StatusPill, Input, Select, Tabs, Card, PhoneFrame, IconChip + the Carbon platform console template) covers roughly the control-plane and roughly nothing of operations. Grouped by urgency.

### Pilot blockers — cannot ship the pilot without these
| Component | Forced by |
|---|---|
| **MapCanvas + PolygonEditor + BoundingBoxEditor + MapPin/AddressPicker** | 3.6 zones, regions, branch geozones; 1.3 address pin with suggest; 10.2 branch pin. No map primitive exists at all. Yandex Maps is the market expectation, not Google. |
| **LocalizedFieldGroup** (language tabs, default-language marker, completeness indicator, optional auto-translate) | Roughly 40 forms. Every user-visible string in the inventory is `{ru, uz, en}` (+ kk, az, ka). Without one component this is re-invented per form and drifts. |
| **DataGrid** — inline edit, fill-down, keyboard nav, virtualization | 4.1 bulk IKPU/package-code backfill across hundreds of rows; 4.4 the menu editor (a spreadsheet of product × channel price + enabled + stock); 4.8 price list. `DataTable` is read-oriented and will not do. |
| **MatrixGrid** — editable cross-tab with row/column bulk toggle | 10.4 channel × payment method and channel × order type; 4.4 item × channel; provider × branch liveness. |
| **ActionMenu** (row overflow "…") + **Drawer** + **Modal** + **ConfirmDialog** | 1.1/1.2 — Delever's entire order UX is the "…" menu opening ~12 distinct dialogs, including the price-delta confirmation that gates committing a provider order. |
| **Combobox** — async search, create-on-miss, multi-select chips | 1.3 customer-by-phone-with-create; product picker over thousands; courier picker; address suggest. `Select` cannot do any of these. |
| **MoneyInput / PercentInput / MoneyOrPercent** | UZS is large-integer, no minor units, thousand-grouped. Every price, tariff, threshold and discount field. Do not inherit cents-based assumptions. |
| **DateRangePicker + TimeInput + DayOfWeekToggle + ScheduleGrid** | 10.2 venue and delivery hours; prep-time intervals; 4.2/4.4 item schedules; every report filter. |
| **MediaUploader with aspect-ratio crop** (1:1, 3:2, 3:1, 9:16, 640×360) + video support | Logos, banners (image *or* video), product images with per-aggregator variants, kiosk idle media, story slides, review-tag icons. Size caps down to 1 MB are specified per surface. |
| **ImportWizard** — FileDropzone + row-level preview table + dry-run diff + JobProgress + ResultSummary | 4.5 Excel catalog import; 5.1 customer CSV; 3.6 bulk geozone upload. Row-level outcome reporting is a deliberate improvement over Delever's silent skip. |
| **SecretInput** — masked, reveal-once, copy, rotate, last-used, never re-rendered | 10.8, control-plane 7.4. ADR 0028. |
| **StatusPill extensions** — a computed *overlay* (late) distinct from status, and a dual-state pill (order status + cooking status) | 1.1/1.2/2.1. Lateness must not be modelled as a state. |
| **LockedState** (plan lock, with inline buy CTA) and **DeniedState** (capability), both distinct from **EmptyState** | 9.1, 8.6, and every module-gated route. The inventory is explicit that these need different UX — one is an upsell, one is a wall. |
| **Toast + InlineAlert** | Every mutation in both apps. |
| **DataTable extensions** — saved views, per-tab persisted filters, column chooser, server pagination *and* infinite scroll, selection with a bulk-action bar, row action menu | 1.1 above all; the legacy app already persisted per-status filters in localStorage and merchants will notice its loss. |

### Parity components — needed for wave 2
| Component | Forced by |
|---|---|
| **Chart family** — line, bar, stacked bar, donut, funnel, histogram, cohort heatmap, sparkline, geographic heatmap | All of §7 and 0.1. `DataTable` cannot render a funnel or an ABC cumulative curve. Pair with the `dataviz` palette; note Delever backs these with ClickHouse and Qoida is Postgres-only. |
| **KpiTile** (value + delta + sparkline) and **WallboardTile** (TV-distance) | 7.x and 0.1. `Card` is close but the wallboard scale is a different component, not a size prop. |
| **Board / BoardColumn / BoardCard** with drag-drop | 5.4 review kanban; 3.1 dispatch. |
| **DragDropAssign** (drag a card onto a target card) | 3.1 dispatcher module. |
| **SortableList / TreeView with drag-reorder** | 4.3 category tree; 4.2 modifier group → modifier → modifier variant; 6.7 story slides; 6.8 home groups; 10.5 website menu ordering. |
| **MappingPane** — dual list, link/unlink, unmapped filter, bulk auto-match | 4.5 POS catalog mapping; 10.8 payment/discount/courier/reason/channel-code mappings. This is the single most repeated integration UI in the inventory. |
| **ConditionBuilder + RuleList with priority reorder + RuleSimulator** | 6.1 promotions, 4.9 auto-add (incl. portion bands), 3.8 dispatch rules, 3.4 bonus/penalty, 6.5 automations. Five rule engines share one component. |
| **Timeline + DiffViewer + ActorChip** (human vs system principal) | 1.2 status timeline; 9.3 field-level audit diff; 5.4 review handling history. |
| **TemplateEditor with VariableChip + live preview in PhoneFrame** | 10.9 order-status templates (~13 merge variables, per language); 6.4 campaigns. `PhoneFrame` finally earns its keep here. |
| **PhoneFrame siblings** — AggregatorCardFrame, KioskFrame (9:16), TelegramMiniAppFrame | 4.6 aggregator preview; 10.5 kiosk idle media and bot preview. |
| **NumberStepper** | Order line quantities, gift quantities, modifier min/max, portion bands. |
| **SplitPane** (master-detail) | 1.1 → 1.2 without losing queue position. |
| **RichTextEditor** (block-based, sanitized) | 10.5 static pages (Delever accepts **raw HTML from tenants** — an XSS surface Qoida must close), 6.7 news and vacancy bodies. |
| **ColorInput** | 10.3 late-order indicator colour; 10.4 per-channel brand colours. |
| **Steps / ProgressRail** | Control-plane 2.5 onboarding; 1.2 order lifecycle rail; import job stages. |
| **LiveBadge / RefreshIndicator / StaleIndicator / ConnectionState banner** | 0.1, 1.1, 2.1, 3.2 — the inventory specifies 10s refresh, live counters and, for KDS, a mid-service connectivity failure mode nobody has designed for. |
| **QRCode display + DataMatrix scan input** | 10.5 QR table codes and kiosk pairing; 4.2/8.2 marked-goods verification. |

### Wave 3 / conditional
**FloorPlanCanvas + TableToken + TimelineScheduler** (1.5 reservations — sections, tables, time-interval availability; nothing close exists) · **CallBar + CallScreenPop** (1.6 telephony) · **OtpInput** (staff MFA).

### Template-level gaps
1. **One console template is not enough.** Operations needs three shells: operator console (dense, keyboard-first), **device/KDS fullscreen** (touch targets, no chrome, offline banner), and **wallboard** (oversized, TV-legible). Carbon 0px corners survive all three; the density, hit-target and type scales do not.
2. **A semantic status colour ramp** beyond `StatusPill` — SLA green/amber/red at bucket boundaries, plus a tenant-chosen late-highlight colour that must remain accessible against the ramp.
3. **A `dir`/script-safe type stack.** Uzbek Latin (with apostrophes that the legacy storefront's own search regex rejected), Cyrillic Russian, Latin English, plus Kazakh and Georgian on the roadmap.

---

# PART 5 — IA decisions that are really data-model decisions

Stating these because the navigation above already commits to them, and they are expensive to reverse.

1. **Order state machine is fixed in code, parameterised by an acceptance-order policy.** No `Order statuses` CRUD screen exists (see exclusions). Cancel origin is preserved as an attribute on one Cancelled state, not as two terminal states, but both must be reportable.
2. **A `Menu` is a first-class named entity** between catalog and channel (4.4), because copy-menu, bind-to-branch, per-channel price and per-item stock all hang off it. ADR 0016's brand-catalog/publication/offering model does not currently express it.
3. **Availability is `(product × scope)` with a source, not a boolean.** ADR 0017's binary model cannot express counted daily stock with auto-reset, per-aggregator thresholds, or terminal-scoped POS stops. Screens 4.4 and 2.5 both assume the richer model.
4. **Price is a function of `(product, menu, channel)`**, and `offered_on_channel` is separate from `price_on_channel`. ADR 0018 has no channel dimension.
5. **Provider installation is `(tenant, provider, branch)`**, with tenant-default → branch-override credential resolution — the same scoped-resolution semantics as ADR 0030 configuration.
6. **Fiscal identity is branch-scoped.** One tenant can span several legal entities (control-plane 2.4). This breaks the assumption that tenant is the legal boundary.
7. **Reporting needs an OLAP answer before 7.x is built.** Cohort heatmaps, ABC/XYZ with standard deviation, per-day × per-branch × per-channel matrices and a business day that crosses midnight are not Postgres-on-the-transactional-tables workloads. There is no analytics ADR. This is the largest unaddressed decision the IA surfaces.
8. **No ADR exists for:** fiscalization, loyalty/stored-value ledger, promotions, in-house courier fleet, reviews/feedback, geospatial zones, telephony, behavioural/product-analytics events, or CMS content. Every one of those has at least one screen above.