# HorecaOS vs Delever: a competitive brief

- Date: 2026-09-12
- Status: first brief; revisit before any external use
- Basis: 253 sourced facts from four parallel researchers (the Delever parity matrix and the operations gap map; the operations specifications, which describe Delever screen by screen; the public web on Delever.uz and the Uzbek market; HorecaOS's decision records), synthesised, reviewed by an adversarial critic against the facts, and revised
- Published page: https://claude.ai/code/artifact/bacfe08e-9cf9-4a4a-9759-05b532e8b9b3
- Companion documents: `delever-parity-matrix.md`, `operations-gap-map.md`, `minimum-viable-cutover.md`

## The verdict

Read plainly: Delever has an operating business today; HorecaOS has an architecture. Delever runs in production for a self-reported ~1,400 establishments and processes real orders across ordering, kitchen, delivery, catalog, CRM, marketing, reporting and settings — with well-documented rough edges (a flat 12-item order-action menu, newest-first queue sort, no cash-reconciliation field for couriers, LTV/RFM metrics with no stated definitions, SLA buckets that don't sum) but a working system nonetheless. HorecaOS, against its own 288-row operations gap map built specifically to reach Delever parity, has 12 rows fully built, 152 partial, 118 not built, and 6 blocked — including the pilot tier alone (151 rows) at just 9 built.

It has zero production tenants. Where HorecaOS is ahead is in design, not delivery: a canonical, code-owned order-status vocabulary instead of Delever's tenant-editable chaos; a single delivery-zone entity instead of Delever's three overlapping, undocumented geometry layers; rotatable OAuth partner credentials instead of a base64(login:password) secret; a strangler-pattern migration model instead of permanent dual V1/V2 toggles; and a fully built commercial billing/statements system. These are real architectural advantages, but three of the areas most often cited for them — sales channels, delivery zones/tariffs, and fiscal entities/receipts — were previously flagged in HorecaOS's own parity matrix as having "no owning ADR" and blocking the pilot; that language is now stale, since partial ADRs (0036, 0037, 0038) exist with substantial documented design decisions.

The honest state is "decision made, implementation not started," not a blank page and not a shipped capability — advantages drawn from these areas should be read as design promise, not proof. HorecaOS also has several disadvantages with no Delever-side mitigation to point to: no staff-identity model at all (every audit-log entry is an anonymous Keycloak UUID), no chosen map/geocoding provider (which degrades the core delivery order-taking flow), no bulk catalog or fiscal-code editing tool for onboarding a real menu, no working multi-branch settings override, an unresolved production-hosting decision, and a single-operator ops model. Many of the specific Delever criticisms in this analysis are inferred from screen recordings and vendor documentation rather than confirmed through direct product access, and Delever's own scale figures (1,400 establishments, 22M orders, $245M GMV) are self-reported and unaudited — treat the direction of the gap (Delever operating, HorecaOS not) as solid, and the specific numbers on both sides as softer.

Going to market now means competing on architecture and a narrow, provably-working pilot slice, not on breadth, scale, or an unproven security/reliability story.

> **The counts lag the code.** The gap map's 12 built / 152 partial / 118 not built / 6 blocked is its 2026-09-11 audit. Since then batch 1 (9 waves, 27 rows) merged into main and batch 2 (18 waves, 54 rows) is integrated pending one gate: code now exists for 81 of the 228 planned rows, concentrated in settings, staff, delivery, the shell and the shared component library, and the status column has not been re-audited. It moves the picture from "architecture only" toward "a working pilot slice"; it does not close the breadth gap.

## Scorecard

13 dimensions to Delever, 2 to HorecaOS, 1 even, of 16 compared.

| Dimension | HorecaOS today | Delever today | Edge | Confidence | Why |
|---|---|---|---|---|---|
| Product breadth | Own audit matched 355 Delever capabilities (152 core): 41 map to an existing ADR, 12 need a new ADR (3 marked blocks-pilot), 12 declined outright. Against the 288-row build gap map: 12 built, 152 partial, 118 not built, 6 blocked. | Broad suite across ordering, kitchen, delivery, catalog, CRM, marketing, reporting, and settings, in daily production use for a self-reported customer base over several years. | Delever | high | Delever's breadth is shipped and used; HorecaOS's breadth is mostly specified, not built. |
| Order/kitchen/courier operations | Canonical order-status vocabulary and single-writer design specified (ADR 0024), but §1 Orders is 2/40 built (23 partial, 15 not built), §2 Kitchen 0/16 built, §3 Delivery 0/15 built. The single highest-value unbuilt pilot screen ('New order' call-centre entry) has a built backend behind a placeholder UI. | Working order board, kitchen ticket flow, and dispatch (including drag-and-drop assignment) in production, with documented defects: a flat 12-item overflow action menu, newest-first queue sort, no cash-reconciliation field at courier hand-in, an undefined 'on-time' basis, and a courier status field that conflates account lifecycle with shift availability. | Delever | high | Delever's flaws are real per its own release notes and documentation, but it is running; HorecaOS's cleaner design is not yet tested against real orders. Several of the Delever-specific criticisms here are inferred from screen recordings and vendor docs rather than confirmed hands-on access. |
| Storefront & channels | Angular storefront is the launch surface (ADR 0055); phone+OTP checkout, CLICK payment capture and RESTAURANT_APPROVAL were proven end-to-end in a documented local proving run. Sales channels/location serviceability (ADR 0036) is a partial ADR with real design content, but zero shipped implementation — the parity matrix's earlier 'no owning ADR, blocks-pilot' language for this area is now stale. | Own storefront/app/Telegram/QR-menu channels plus aggregator intake consolidated into one order screen; its order-type/channel vocabulary is documented as conflating dine-in with kiosk and bot in a single enum. | Delever | high | Delever's channels are live; HorecaOS has one proven end-to-end path (storefront + CLICK) and a designed-but-unbuilt broader channel model. |
| Aggregator integrations | Zero aggregator integrations — a deliberate scope decision for the launch slice, confirmed in the minimum-viable-cutover plan, not a gap discovered late. | Vendor materials list order intake from Wolt, Glovo, Yandex Eats, Uzum Tezkor, Bolt Food, Chocofood and Foody, consolidated into one order screen. | Delever | medium | HorecaOS's zero-integration position is high confidence (documented, deliberate). Delever's aggregator count is vendor-published (delever.io content, medium confidence), not independently verified, so the size of the lead — though not its direction — is uncertain. |
| POS integrations | Zero POS integrations at launch, explicitly deferred rather than attempted and failed. | Vendor materials list 16 POS/back-office integrations (iiko, R-Keeper and R-Keeper White Server, Poster, Jowi, Syrve, Paloma 365, and others). | Delever | medium | Same caveat as aggregators: Delever's '16 systems' figure is vendor-published (medium confidence). HorecaOS's zero is a confirmed, deliberate deferral (high confidence on that side only). |
| Payments | CLICK payment capture proven end-to-end in a documented proving run; a tenant prepaid wallet (ADR 0095) is Proposed, not yet accepted by the owner, and its card-charging adapter has no implementation ('not configured' on any charge attempt). | Documented integrations with Payme, Click, Uzum Bank, Atmos, Anorbank, Magnati, Rahmat, Magnet, UzQR (Uzbekistan), Kaspi/Epay (Kazakhstan), and TipTop Pay. | Delever | medium | Delever's payment integration list is vendor-published (medium confidence). HorecaOS has one proven payment path from a pre-production proving run, not multiple live providers. |
| Fiscalization & compliance | Deliberately not a fiscal issuer for the pilot: no OPERATOR route to the tax service, and fiscal evidence (receipt URL, fiscal sign, marking codes) is entirely absent, blocked on an unresolved legal question of which party is the fiscal agent per settlement path. Legal entities/fiscal receipts/product classification (ADR 0038) is a partial ADR with substantial documented design (audited approved-list ИКПУ search, per-branch taxpayer model) — again, decision made, implementation not started, not the 'no owning ADR' state the matrix once recorded. Government e-invoicing for HorecaOS's own commercial billing (ADR 0096, sending statements to Didox/Faktura.uz) is separately 'Not started.' | Implements tenant-wide fiscalization INN, per-payment-type fiscalization settings, AI-generated ИКПУ codes (behind a liability disclaimer), and bulk ИКПУ editing described as 'a daily reality of this market.' | Delever | high | HorecaOS's launch cannot fiscalize cash-on-delivery, courier-terminal, kiosk, or POS-settled dine-in orders — most of this market's volume per the parity matrix. This is a hard blocker for cash-heavy segments, not a cosmetic gap, and is compounded by the platform's own e-invoicing not being wired up. |
| Multi-brand/multi-branch & multi-tenancy | No brand-to-branch scope-override pattern exists — every settings screen writes to one fixed scope, called 'the single worst settings mistake' the spec names (0 of the relevant §10 Settings rows built). | Working 'copy menu to branch' feature (its most-cited chain capability) and per-branch integration/credential granularity, though bulk branch actions apply with no preview, opt-out, or audit record ('fire and forget'). | Delever | high | Delever today, functional if sloppy; HorecaOS has architecture intent but no working multi-branch settings override at all. This is not an even tradeoff — it is Delever ahead on a shipped capability against one that is entirely unbuilt on the HorecaOS side. |
| Analytics/reporting | §7 Reports is the worst-performing section of the gap map: 2/39 built, 24 not built, including payment mix, courier leaderboard/efficiency, ABC/XYZ analytics, customer LTV/cohort analytics, and audited export. No Chart component family exists yet, so reports render as raw numbers and tables. | Eight-tab dashboard, ABC/XYZ classification, LTV/RFM metrics, and an external-delivery cost reconciliation report called 'the best report and the only one that reliably finds money' — but with documented defects: LTV/RFM have no stated definitions or baseline, SLA time buckets overlap and don't sum to 100%, several reports show 'no data' in their own documented instances, and customer PII prints unmasked by default. | Delever | high | Delever has a working, if flawed, reporting suite; HorecaOS has almost none of the equivalent built yet regardless of design intent. |
| Customer/CRM/loyalty | §5 Customers has the best build ratio in the gap map (4/19 built, 10 partial); the deposit/stored-value ledger is blocked on an unresolved legal question of whether HorecaOS may hold customer prepaid balances at all. | Has no dedicated single-customer record screen at all ('the screen Delever does not have'), no unsubscribe/opt-out flag anywhere in its campaign tooling, and repeated double-deduction/cross-channel-divergence bugs in its promotion engine per its own release notes. | Even | medium | This is the area where HorecaOS's shipped fraction comes closest to Delever's documented gaps — Delever has scale and live loyalty programs but a structurally missing CRM screen; HorecaOS has a partial build and an unresolved legal blocker on deposits. |
| Billing model & pricing | Commercial modules/statements/arrears (ADRs 0087-0089) are fully Built: per-module unit sales, month-close via a frozen statement with CSV export, and an arrears sweeper that raises a scheduled incident rather than auto-terminating service. Plan terms/trials/deposits (ADR 0093) are also Built — decided and shipped the same day (2026-09-11) the owner answered its open inputs, which is a fast cycle worth a caveat about review depth. | Published tiered pricing (Start 1.3M to Enterprise 13M UZS/month by order-volume band, per-order overage) plus roughly ten separately priced add-on modules and refundable deposits; its own SaaS commerce (subscription screen, prepaid wallet with expiring credit and dunning) is displayed directly inside the same panel used to run the restaurant. | HorecaOS | medium | HorecaOS's design avoids showing expiring-wallet dunning inside the operator's own tool and is genuinely built, not just specified — but unproven, zero live tenants, so this is a design/process advantage, not a demonstrated commercial one yet. |
| Onboarding & migration from an incumbent | No tool exists to migrate a tenant off Delever specifically. ADR 0024's migration/cutover program targets HorecaOS's own prior-generation apps (Milliy/Rayhon), not competitor data, and is dormant until production exists ('no wave can actually be migrated' yet). Sample-menu onboarding (ADR 0099, Built) and control-plane invitation visibility (ADR 0100, Built) exist for brand-new tenants. No bulk catalog or fiscal-code editing tool exists. | Bulk ИКПУ editing and Excel/POS import (with silent skip on unmapped rows) support onboarding a real, populated menu; incumbent installed base and years of practice doing exactly this. | Delever | high | A real prospect currently on Delever has nothing built today to ease their move, and HorecaOS has no bulk-edit tooling to onboard a multi-hundred-SKU catalog by hand. |
| Security/privacy/audit | Capability-based authorization (ADR 0025, Built), audit-evidence/single-use-approval model (ADR 0027, Built), and provider/secret-reference architecture (ADR 0026, Built) explicitly avoid Delever's named anti-patterns. Secrets rotation (ADR 0028) and PII key rotation (ADR 0029) are Partial; tenant isolation is application-enforced today, with the RLS database backstop (ADR 0056) still Partial and scheduled to production-hardening, not before launch. No staff-identity model exists, so every actor in the audit log renders as a raw Keycloak UUID rather than a named person. | Documented anti-patterns: a base64(login:password) partner API credential, and a process of tenants handing Cloudflare/registrar credentials to a Delever staff member for domain setup; its change-history/before-after diff view is called the single most valuable thing in its own settings area. | HorecaOS | medium | High confidence that HorecaOS's architecture avoids Delever's named anti-patterns; separately, low confidence that this is a proven operational security edge, since HorecaOS has zero production tenants, two named partial pieces (secrets rotation, PII rotation), and an application-only tenant-isolation backstop. (Unproven — zero live tenants.) The missing staff-identity model also means the audit log currently attributes actions to anonymous UUIDs, which weakens the accountability story the design intends. |
| Reliability & hosting | No accepted, built production hosting environment exists: prior decisions (ADR 0034 colocated Tashkent, ADR 0061 Sarkor owned-hardware) are both Superseded; the replacement (ADR 0073, rented in-country VM) is Proposed and Not started. A strict app/worker process split for horizontal scaling is 'not yet safe to deploy' because two scheduled jobs hold in-process state. Production ops assumes exactly one operator running the whole platform, 'alone, sometimes asleep, and occasionally on a plane.' | Presumably runs production infrastructure for its reported establishment base over multiple years, implying an operational team HorecaOS does not yet have. | Delever | medium | HorecaOS's gap is directly documented (no accepted hosting decision, single-operator risk); Delever's presumed reliability is inferred from apparent longevity and scale, not from any direct evidence on its infrastructure found in this research. |
| Team/track record/customer base | Formal ADR/SDLC process (105 numbered ADRs, two-axis decision/implementation status model) but zero production tenants and zero paying customers as of this draft. | Self-reports 1,400 establishments, 22M orders processed, and ~$245M GMV as of Q2 2026 (a 2024 profile cited lower, more corroborated figures: 400+ restaurants, ~10,000 orders/day); founded 2020/2021; one reported $100K funding round (Aloqa Ventures, July 2024). | Delever | medium | The directional edge (Delever operates a real business, HorecaOS does not) is high confidence. The magnitude of Delever's specific figures is only medium confidence and self-reported/unaudited. Two points argue for discounting the vendor numbers further: no funding round has been reported since the $100K Aloqa Ventures round in July 2024 despite claims of continued major growth, and Delever's stated footprint has grown from 'Uzbekistan + Kazakhstan, one pilot' (2024 profile) to '7 markets' (current vendor content) with no independent corroboration of that expansion. |
| Speed of delivery/engineering model | Formal SDLC (intent→spec→plan→build/test→PR/release→maintain) with machine-checked ADR status; a gap-map re-verification pass corrected 53 of 284 graded statuses — 34 rows moved up (previously understated) but 13 moved the other way (10 built→partial, 3 partial→not built), meaning the process has now been wrong in both directions on a single pass. | Has shipped and iterated a broad, in-use feature set over multiple years, with fixes recorded in its own release notes (scroll-position bugs, search debounce, system-actor attribution). | Delever | medium | Delever ships more, if rougher, functionality per unit time so far — a real current throughput gap, not an even tradeoff. HorecaOS's advantage is process rigor (a self-correcting, documented SDLC), not present delivery speed, and that rigor is itself not fully proven: the same pass that caught 34 understated rows produced 13 overstated ones, and ADR 0093 went from Proposed to Built the same day the owner answered its open inputs, one day before this draft — worth a caveat about review depth rather than uncritical praise. |

## Parity with Delever, by area

Rows of the operations gap map, whose 288 rows were written against Delever's product to define parity. Totals as of the 2026-09-11 audit: 12 built, 152 partial, 118 not built, 6 blocked on a decision.

| Area | Built | Partial | Not built | Blocked | Verdict |
|---|---:|---:|---:|---:|---|
| §0 Home (live board/my work/wallboard) | 0 | 6 | 6 | 0 | No shift-supervisor wallboard, no live operator leaderboard, and no personal 'My work' page exist yet. |
| §1 Orders | 2 | 23 | 15 | 0 | Highest-value unbuilt pilot screen ('New order' call-centre entry) has a built backend behind a placeholder UI. |
| §2 Kitchen (device shell) | 0 | 11 | 4 | 1 | Stop-list has no batch mutation (300 items = 300 sequential calls); cook-headcount output is blocked on an unstated policy. |
| §3 Delivery | 0 | 10 | 5 | 0 | Dispatch board has no map or drag-to-assign and still polls instead of using the built SSE stream; dispatch rules are entirely unbuilt. |
| §4 Catalog | 0 | 11 | 12 | 2 | Combos, nested modifiers, and physical/nutritional attributes are unbuilt and blocked on an ADR 0016 amendment; reference-data vocabularies and auto-add rules are blocked on open decisions. |
| §5 Customers | 4 | 10 | 4 | 1 | Best build ratio in the gap map; deposit/stored-value ledger is blocked on an unresolved legal question. |
| §6 Marketing | 1 | 3 | 10 | 1 | The entire promotions/discount rule engine is unbuilt; a plain shared promo code is the only discount type an operator can author today. |
| §7 Reports | 2 | 13 | 24 | 0 | Worst section by not-built count: payment mix, courier leaderboard, ABC/XYZ analytics, LTV/cohort analytics, and audited export are all unbuilt. |
| §8 Finance | 0 | 9 | 1 | 1 | Cash reconciliation and courier payouts will show empty/zero on a real tenant; the prepaid wallet is blocked on ADR 0095 acceptance. |
| §9 Staff | 1 | 8 | 11 | 0 | No ADR owns staff identity; every actor renders as a raw Keycloak UUID. |
| §10 Settings | 2 | 18 | 16 | 0 | No brand-to-branch scope-override pattern exists — every settings screen writes to one fixed scope. |
| §X Shells/design system | 0 | 30 | 10 | 0 | No DataGrid with inline edit and no Chart family at all — reports render as raw numbers and tables. |

## Advantages

### Cleaner delivery-zone and order-status design (decided, not yet built)

Confidence: medium

HorecaOS's ZoneRole model collapses Delever's three overlapping, undocumented geometry layers (branch geozone, delivery zone, 'free geozone') into a single entity with a zone role, and fixes one canonical, code-owned order-status vocabulary instead of Delever's tenant-editable, reorderable status machine. Both live in partial ADRs (0036, 0037) with substantial documented design content — the parity matrix's earlier 'no owning ADR, blocks-pilot' framing for these areas is now stale and should be read as 'decision made, implementation not started,' not as a shipped or even fully unblocked capability.

Evidence: platform/docs/adr/partial/0037-delivery-zones-tariffs-and-fee-resolution.md:75-83,119,549; platform/docs/delever-parity-matrix.md:103-105,120,542,548; platform/docs/operations-gap-map.md:555

### Migration architecture avoids permanent dual-implementation cost

Confidence: medium

ADR 0024's single-authoritative-writer-per-capability strangler pattern is structurally cheaper to operate long-term than Delever's own per-module V1/V2 toggle system, which requires shipping and maintaining two live implementations of every module indefinitely.

Evidence: platform/docs/delever-parity-matrix.md:128

### Rotatable OAuth partner credentials instead of a reversible-password pattern

Confidence: medium

HorecaOS's marketplace/partner API (ADR 0040) is designed around rotatable, scoped OAuth client credentials from the start, explicitly rejecting the static-header-API-key pattern Delever uses for its 1C and generic aggregator integrations — described in HorecaOS's own ADR as 'a real user's password in a reversible encoding.' This is a design decision, not yet operated under real partner traffic.

Evidence: platform/docs/adr/partial/0040-marketplace-channel-and-partner-api.md:186-187,205; platform/docs/adr/built/0026-provider-installations-bindings-and-secret-references.md:3-4

### Fully built commercial billing/statements system

Confidence: high

Unlike most of the platform, HorecaOS's own billing engine (ADRs 0087-0089, all Built) is not aspirational: per-module unit sales, month-close via a frozen statement with CSV export, and an arrears sweeper that raises a scheduled incident rather than auto-terminating service are working code today. This is a genuine built-vs-designed asset, though it has not yet run against a real paying tenant.

Evidence: platform/docs/adr/built/0087-a-module-is-sold-on-its-own-unit-and-switches-features-on.md:3-4; platform/docs/adr/built/0089-a-tenant-in-arrears-is-a-conversation-the-platform-schedules.md:3-4

### A formal, machine-checked ADR/SDLC process with demonstrated (if imperfect) self-correction

Confidence: medium

105 numbered ADRs under a two-axis decision/implementation status model, plus a 6-stage CI-integrated SDLC, produced a gap-map re-verification that caught 53 of 284 previously-graded statuses as wrong. This is real process discipline, but it cuts both ways: the same pass moved 34 rows up (previously understated) and 13 rows down (previously overstated), so 'we caught ourselves being wrong twice' is evidence of rigor, not proof the current 12/288 figures are final.

Evidence: platform/docs/adr/README.md:9-47; platform/docs/sdlc.md:15-27; platform/docs/operations-gap-map.md:50-66

### Deliberate scope discipline reduces attack surface and long-term maintenance burden

Confidence: high

HorecaOS declined 12 Delever capabilities outright, including a tenant-editable order-status machine, a general CMS accepting raw tenant HTML (an XSS surface Delever's own documentation names), embedded telephony/PBX, AI-generated fiscal codes, and an expiring prepaid tenant wallet — each declined with a stated reason tied to governance, security, or accounting risk rather than simply being unbuilt.

Evidence: platform/docs/delever-parity-matrix.md:116-131

### A documented, passing proving run for the actual launch slice

Confidence: high

A recorded proving run exercised the launch slice end to end against a fresh stack for a genuinely new tenant — onboarding with real validator failures and resumes, phone+OTP checkout, CLICK payment capture, RESTAURANT_APPROVAL, fulfilment, and a fiscal document reaching ISSUED with OFD evidence — and passed, with six confirmed non-blocking gaps, none a business-data shortcut. This is narrow (one tenant, one brand, one location) but it is evidence, not just a design claim.

Evidence: platform/docs/minimum-viable-cutover.md:29-51,60-73

### Onboarding tooling for new (not migrated) tenants

Confidence: medium

A new tenant can be onboarded with a pre-built sample menu it did not author (ADR 0099, Built), and the control plane has full audited visibility into every owner invitation's lifecycle (ADR 0100, Built) — concrete, shipped conveniences for a greenfield launch, distinct from and not a substitute for migration tooling from an incumbent.

Evidence: platform/docs/adr/built/0099-a-new-tenant-can-be-onboarded-with-a-sample-menu.md:3-4; platform/docs/adr/built/0100-the-control-plane-sees-every-owner-invitation.md:3-4

## Disadvantages

### Overwhelming build gap against the platform's own parity target

Confidence: high

Of 288 rows in the operations gap map built specifically to reach Delever parity, only 12 are BUILT, 152 are PARTIAL, 118 are NOT BUILT, and 6 are BLOCKED. Even the pilot tier alone (151 rows, what's needed to go live) has only 9 rows BUILT. The document's own conclusion calls the pilot 'not a greenfield build, it is a finishing job.'

Evidence: platform/docs/operations-gap-map.md:31-43

**Mitigation.** Do not market against Delever's full feature breadth. Finish the staged 0-5 rollout (safe platform operation → tenant creation → publishable menu → priceable cart → paid/approved order → single-location production cutover) and sell only what stage the pilot has actually reached.

### Zero production tenants and no operational track record

Confidence: high

HorecaOS has never run against real traffic, real attackers, or real operational failure modes; Delever has operated for multiple years at some real scale. Design advantages (security architecture, canonical status model, billing system) are unproven in production regardless of their documented quality.

Evidence: platform/docs/minimum-viable-cutover.md:19-27; platform/docs/adr/README.md:153-165

**Mitigation.** Run the proving-run-validated pilot with one to three design-partner tenants before making any comparative claim beyond 'architecturally different,' and only claim operational maturity once incident history exists.

### No staff-identity model — blocks nine rows and weakens the audit story

Confidence: high

No ADR owns an employee/person record at all (ADR 0025 is authorization only, ADR 0009 is Keycloak reconciliation, ADR 0042 is couriers only) — there is no employee name, phone, or photo anywhere in the platform, so every actor on every screen and in the audit log renders as a raw Keycloak subject UUID. This single gap is named as blocking at least nine gap-map rows (staff shifts, terminal PINs, personal profile, the live operator leaderboard) and directly undercuts the claimed audit-trail advantage: an audit log of anonymous UUIDs is a weaker accountability story than the design implies.

Evidence: platform/docs/operations-gap-map.md:372-374,383,540 (also 127,133)

**Mitigation.** Prioritize a staff-identity ADR ahead of most other unstarted decisions — it is named as unblocking more rows than any other single missing decision, and it directly strengthens the security/audit pitch.

### No map/geocoding provider chosen — degrades the core delivery order-taking flow

Confidence: high

No map or geocoder provider is selected anywhere in the codebase (an open input inherited from ADR 0015 into ADR 0037). This blocks or degrades the New-order address-entry pane, the live courier map, delivery-zone drawing, and the geography heatmap — i.e. it affects the actual pilot order-taking flow for delivery orders, not just a reporting nicety.

Evidence: platform/docs/operations-gap-map.md rows '1.3b','3.2','3.6','7.10','X.4'

**Mitigation.** Select and integrate a map/geocoding provider before offering the pilot to any tenant that takes delivery orders; treat this as a pre-sales blocker equal in priority to the fiscal gap.

### Fiscalization gap covers most of the market's actual payment volume

Confidence: high

HorecaOS deliberately is not a fiscal issuer for the pilot — no OPERATOR route to the tax service exists, and it cannot fiscalize cash-on-delivery, courier-terminal, kiosk, or POS-settled dine-in orders, which the parity matrix calls 'most of this market's volume.' Separately, government e-invoicing for HorecaOS's own commercial billing (ADR 0096, sending statements to Didox/Faktura.uz) is 'Not started' — a compliance gap in the platform's own operations, distinct from the tenant-facing fiscal-receipt gap.

Evidence: platform/docs/adr/partial/0038-legal-entities-fiscal-receipts-and-product-classification.md:216-233; platform/docs/delever-parity-matrix.md:42-46; platform/docs/adr/README.md:72; platform/docs/adr/built/0088-a-month-is-closed-by-issuing-a-statement.md:3

**Mitigation.** Resolve the fiscal-agent-per-settlement-path legal question and build the OPERATOR route before targeting any cash-heavy or dine-in-heavy segment; implement ADR 0096 before HorecaOS's own invoicing volume grows large enough to attract scrutiny.

### No bulk catalog or fiscal-code tooling for onboarding a real menu

Confidence: high

Delever ships bulk ИКПУ (fiscal classification) editing described as 'a daily reality of this market'; HorecaOS has no bulk fiscal-code editing or POS-mapped import equivalent. Onboarding a real multi-hundred-SKU restaurant by hand is a realistic Day-2 problem this analysis would otherwise miss.

Evidence: platform/docs/operations-spec/catalog.md:1170-1171,1237

**Mitigation.** Prioritize bulk-edit/import tooling before targeting any prospect with a catalog larger than a handful of SKUs; until then, restrict pilot outreach to small, hand-authorable menus (consistent with the current launch slice's own scope).

### No working multi-branch settings architecture

Confidence: high

There is no brand-to-branch scope-override pattern at all — every settings screen writes to one fixed scope, called 'the single worst settings mistake' the spec names. Delever's equivalent ('copy menu to branch') is working, if sloppy. This is zero built capability against a functioning incumbent feature, not an even tradeoff.

Evidence: platform/docs/operations-gap-map.md:399-401,406

**Mitigation.** Treat this row as a pre-sales blocker for any multi-branch or chain prospect; do not pitch chain/multi-brand customers until a scope-override pattern ships.

### No accepted production hosting decision, and a single-operator ops model

Confidence: high

Both prior hosting decisions (ADR 0034 colocated Tashkent, ADR 0061 Sarkor owned-hardware) are Superseded; the replacement (ADR 0073, rented in-country VM) is Proposed and Not started. Production operations explicitly assumes exactly one operator running the whole platform, 'alone, sometimes asleep, and occasionally on a plane' — a stated single point of failure for incident response.

Evidence: platform/docs/adr/partial/0034-hosting-environments-topology-and-data-residency.md:3-4; platform/docs/adr/not-started/0073-production-runs-on-a-rented-vm-in-country.md:3-4,33-36

**Mitigation.** Accept and implement ADR 0073 (or an alternative) before any tenant goes live, and put a monitored escalation contact or on-call backstop in place before the single operator is the platform's only failure response.

### Vendor scale claims are large and hard to independently verify — but the directional gap is real

Confidence: medium

Delever's self-reported figures (1,400 establishments, 22M orders, ~$245M GMV) are medium-confidence and unaudited. Two points argue for further discounting them: no funding round has been reported since the $100K Aloqa Ventures round in July 2024 despite claims of continued major growth, and Delever's stated footprint has grown from 'Uzbekistan + Kazakhstan, one pilot' (2024 profile) to '7 markets' (current vendor content) with no independent corroboration. Even discounted, Delever operates a real, multi-year business against HorecaOS's zero tenants.

Evidence: https://delever.io/llms-full.txt (fetched 2026-09-12); https://the-tech.kz/delever-uz-startap-kotoryj-zanimaetsya-dostavkoj-edy-v-uzbekistane-i-kazahstane/; WebSearch queries for 2025-2026 Delever funding news returning no results

**Mitigation.** Do not compete on scale or breadth messaging. Compete on named, verifiable architecture differences and a small number of real pilot customers once live, and treat Delever's public numbers as directional only in any investor-facing materials.

### Process rigor is demonstrated but not yet proven correct

Confidence: medium

The gap-map re-verification that caught 34 understated rows also produced 13 overstated ones (10 built→partial, 3 partial→not built) in the same pass, meaning the process has now been wrong in both directions once. Separately, ADR 0093 (plan/term/trial/deposit) moved from Proposed to Built the same day the owner answered its open inputs — a same-day propose-to-built cycle that sits awkwardly next to a 'formal, self-correcting SDLC' pitch.

Evidence: platform/docs/operations-gap-map.md:50-66; platform/docs/adr/built/0093-a-plan-sells-a-term-a-trial-and-a-deposit.md:3-9

**Mitigation.** Schedule a third independent verification pass on the gap map before quoting the 12/152/118/6 figures externally, and adopt a minimum review-latency or second-reviewer norm for ADRs before calling the process mature in investor materials.

## Market context

Uzbekistan's food-delivery aggregator layer is already concentrated and under active antitrust scrutiny: the Competition Committee designated Yandex Eats LLC JV and Express24 APP LLC as dominant digital platform operators in October 2024 (following Yandex's acquisition of part of Express24's delivery assets), and did the same to Uzum Tezkor in April 2025 — both now carry mandatory antitrust-compliance obligations (medium-high confidence, primary regulatory press coverage). Uzum Tezkor reports operating in 25 cities with 2,600+ partners and 7,000+ couriers (medium confidence, company press release); Wolt ranks as the top Food & Drink app in the country per Similarweb, with Uzum Tezkor second (medium confidence). Statista's own forecast model puts Uzbekistan's online food delivery market at roughly $281M in 2025, growing at a 14.12% CAGR toward ~$477M by 2029 (medium confidence, a vendor forecast, not a government statistic). Separately from Delever, at least one smaller, unaffiliated Uzbek restaurant-tech startup (REZVO) exists in an adjacent niche (table booking, customer database) with roughly 10 paying venues on a self-funded budget (medium confidence).

On the compliance side, fiscal receipts must carry the ИКПУ product code, digital marking code, and standard merchant identifiers as a baseline requirement for any POS/ordering system (high confidence, government guidance); digital-marking enforcement is tightening through 2025-2026 (cashless-only wholesale for marked goods from mid-2025, remote inspection powers from 2026, progressive penalties for violations — medium confidence, secondary-sourced); and e-invoicing (ЭСФ) rules add automatic risk-scoring from January 2026 and marking-code auto-verification from July 2026 (medium confidence). Payment rails are dominated by Click, Payme, and Paynet, with 2025-2026 consolidation (TBC acquiring Payme; Paynet acquiring a stake in Humo's payment-system operator) and a presidential decree mandating expanded cashless payment and a unified national QR code through 2026 (low-medium confidence, secondary-sourced). One claim worth flagging separately: the statement that "data residency is not a legal constraint in Uzbekistan" is not an independently verified legal conclusion — it traces to HorecaOS's own internal architecture decision record (ADR 0034, closed input, "Data residency does not constrain Qoida"), i.e. a company decision, not outside-counsel research, and should be treated as an internal assumption pending its own legal verification, not as established market fact.

## Go-to-market moves that follow

1. Target segment first: single-location or small (2-3 branch) independent restaurants that can run on the proven launch slice today (pickup or manual-courier delivery, one payment provider, hand-authored small catalog) — not chains, not cash-heavy or dine-in-heavy operators, given the fiscalization gap.
2. Position on architecture and governance, not breadth or scale: lead with the canonical order-status model, single-writer migration pattern, and rotatable-credential security design as concrete, named differences from Delever's documented anti-patterns — and be explicit that these are design advantages awaiting production proof, not a completed track record.
3. Do not lead with vendor-style scale numbers on either side; Delever's figures are self-reported and HorecaOS has none yet. If competitive numbers are needed for investors, present them with the confidence caveats in this report attached.
4. Sequence pre-sales blockers before outreach: staff-identity ADR (unblocks the most rows of any single decision), map/geocoding provider selection (blocks the delivery order-taking flow), and bulk catalog/fiscal-code tooling (blocks onboarding any real-sized menu) should all be resolved or explicitly scoped-around before pitching a prospect with more than a handful of SKUs or any delivery fulfillment.
5. Build no Delever-migration tool for the initial push — none exists today and ADR 0024 does not cover it — so restrict outreach to prospects opening fresh, not those asking to be moved off an incumbent; state this limitation upfront rather than let it surface mid-sales-cycle.
6. Pricing posture: HorecaOS's built commercial-billing system (per-module sales, frozen monthly statements, arrears-as-conversation rather than auto-termination) is a real differentiator versus Delever's expiring-wallet dunning model embedded in the operator's own console — but pilot pricing should be simple and low-commitment (trial + deposit per ADR 0093) rather than tiered-by-volume like Delever's published Start/Medium/Big/Enterprise bands, since HorecaOS has no track record to justify volume-based pricing yet.
7. Resolve the production-hosting decision (accept or replace ADR 0073) and put a monitored on-call backstop in place before any paying tenant goes live — a single-operator ops model is not a sellable claim to a chain or an investor.
8. Run a small number (1-3) of design-partner pilots on the already-proven launch slice, instrument them for real incident and uptime history, and only then expand messaging to include reliability and security claims with actual operational evidence behind them.
9. Correct internal documentation before external use: update the parity matrix's stale 'no owning ADR / blocks-pilot' language for sales channels, delivery zones, and fiscal entities now that ADRs 0036/0037/0038 exist as partial records, so investor materials don't cite two different states of the same fact.
10. Have the owner or counsel independently verify the 'data residency is not a legal constraint' claim before it appears in any external-facing material, since it currently rests on an internal ADR decision rather than outside legal research.

## Questions only the owner can answer

- Which segment does the first paying pilot target — and is the owner willing to explicitly exclude cash-on-delivery, kiosk, and POS-settled dine-in tenants given the fiscalization gap?
- Who owns and when will a staff-identity ADR be written, given it is named as unblocking more gap-map rows than any other single missing decision?
- Which map/geocoding provider will be selected, and what is the cost/licensing implication for a market where this hasn't been evaluated yet?
- Will ADR 0073 (rented in-country VM) be accepted as-is, or does the owner want to revisit hosting given both prior decisions were superseded?
- Has legal counsel been engaged on the fiscal-agent-per-settlement-path question blocking ADR 0038's fiscal-evidence work, and on whether HorecaOS may hold customer prepaid balances at all (blocking the deposit ledger and loyalty deposit accounts)?
- What is the actual review process for ADRs going forward, given ADR 0093 went from Proposed to Built the same day its open inputs were answered — is a minimum review latency or second-reviewer step needed before external claims lean on 'a formal, self-correcting SDLC'?
- Is single-operator production risk acceptable for the first paying tenant, or does the owner want a co-op/backup on-call arrangement before launch?
- Should HorecaOS build any Delever-migration tooling at all, or is the strategy permanently greenfield-only (new tenants, no incumbent data)?
- When (if ever) will ADR 0096 (e-invoicing to Didox/Faktura.uz) be prioritized relative to other unstarted work, given it's a compliance gap in the platform's own billing rather than a tenant-facing feature?
- What pricing model will the owner actually offer pilot tenants, and how does it relate to the plan/trial/deposit structure already built under ADR 0093?

## Method and confidence

Four Sonnet researchers worked in parallel on the parity matrix and gap map, the operations specifications, the public web, and the decision records. Their facts carry a file-and-line or URL citation and a confidence grade; the synthesis was reviewed by an adversarial critic against those facts and revised. Delever-side claims come from vendor material, release notes and screen recordings, not hands-on access; its scale figures are self-reported. HorecaOS-side claims come from this repository and were checked against code where the critic asked. Treat directions as solid and specific numbers on both sides as softer.

## Sources

- platform/docs/delever-parity-matrix.md
- platform/docs/operations-gap-map.md
- platform/docs/minimum-viable-cutover.md
- platform/docs/sdlc.md
- platform/intent/README.md
- platform/docs/operations-spec/orders.md
- platform/docs/operations-spec/catalog.md
- platform/docs/operations-spec/couriers.md
- platform/docs/operations-spec/statistics.md
- platform/docs/operations-spec/settings.md
- platform/docs/operations-spec/brands-and-locations.md
- platform/docs/operations-spec/staff-and-access.md
- platform/docs/frontend-information-architecture.md
- platform/docs/adr/README.md
- platform/docs/adr/built/0025-fine-grained-authorization-and-capability-model.md
- platform/docs/adr/built/0026-provider-installations-bindings-and-secret-references.md
- platform/docs/adr/built/0027-audit-evidence-and-approval-model.md
- platform/docs/adr/partial/0028-secrets-management-and-credential-lifecycle.md
- platform/docs/adr/partial/0029-pii-protection-envelope-encryption-and-key-rotation.md
- platform/docs/adr/partial/0034-hosting-environments-topology-and-data-residency.md
- platform/docs/adr/partial/0036-sales-channels-and-location-serviceability.md
- platform/docs/adr/partial/0037-delivery-zones-tariffs-and-fee-resolution.md
- platform/docs/adr/partial/0038-legal-entities-fiscal-receipts-and-product-classification.md
- platform/docs/adr/partial/0040-marketplace-channel-and-partner-api.md
- platform/docs/adr/partial/0041-kitchen-execution-and-production-routing.md
- platform/docs/adr/partial/0056-tenant-isolation-enforcement-and-rls.md
- platform/docs/adr/partial/0061-production-deployment-pilot-on-owned-hardware-portable-by-construction.md
- platform/docs/adr/partial/0064-voice-channels-and-the-operator-presence-model.md
- platform/docs/adr/partial/0023-production-operating-model-observability-security-and-recovery.md
- platform/docs/adr/partial/0024-legacy-data-migration-cutover-and-retirement.md
- platform/docs/adr/partial/0095-a-tenants-wallet-keeps-paid-money-and-bonus-money-apart.md
- platform/docs/adr/not-started/0073-production-runs-on-a-rented-vm-in-country.md
- platform/docs/adr/built/0071-order-reviews-a-rating-the-tenant-can-see.md
- platform/docs/adr/built/0087-a-module-is-sold-on-its-own-unit-and-switches-features-on.md
- platform/docs/adr/built/0088-a-month-is-closed-by-issuing-a-statement.md
- platform/docs/adr/built/0089-a-tenant-in-arrears-is-a-conversation-the-platform-schedules.md
- platform/docs/adr/built/0093-a-plan-sells-a-term-a-trial-and-a-deposit.md
- platform/docs/adr/built/0099-a-new-tenant-can-be-onboarded-with-a-sample-menu.md
- platform/docs/adr/built/0100-the-control-plane-sees-every-owner-invitation.md
- https://delever.io/llms-full.txt (fetched 2026-09-12)
- https://www.delever.uz/
- https://the-tech.kz/delever-uz-startap-kotoryj-zanimaetsya-dostavkoj-edy-v-uzbekistane-i-kazahstane/
- https://www.spot.uz/ru/2024/07/10/delever/
- https://the-tech.kz/kak-startapy-teryayut-milliony-fakapy-faunderov-trustme-delever-i-smartestprep
- https://play.google.com/store/apps/details?id=uz.delever.courier
- crunchbase.com/organization/delever (via search-result summary; direct fetch returned HTTP 403)
- https://kun.uz/en/news/2024/10/26/uzbekistans-food-delivery-market-deemed-monopolized-by-yandex-eats-and-express24
- https://www.gazeta.uz/ru/2024/10/25/yandex-express24/
- https://kun.uz/en/news/2025/04/02/uzum-tezkor-named-dominant-digital-platform-in-delivery-market
- https://uzum.com/en/press-center/news-and-press-releases/25cities-2600partners-7000couriers-uzum-tezkor-continues-regional-expansion/
- https://www.similarweb.com/apps/top/google/store-rank/uz/food-drink/top-free/AndroidPhone/
- https://moderndelivery.substack.com/p/delivering-an-uzbek-unicorn
- https://www.statista.com/outlook/emo/online-food-delivery/uzbekistan
- https://digitalbusiness.kz/en/2025-12-03/entrepreneurs-from-uzbekistan-set-out-to-build-a-super-app-for-restaurants-on-a-20k-budget-here-s-what-they-re-cooking-up/
- https://gov.uz/ru/soliq/news/view/14530
- https://gov.uz/ru/soliq/news/view/19443
- https://buxgalter.uz/publish/group6786_kody_na_tovary_i_uslugi
- https://www.gazeta.uz/ru/2025/05/26/marking/
- https://buxgalter.uz/publish/doc/text208507_9_glavnyh_izmeneniy_v_sfere_obyazatelnoy_markirovki_po_pp-190
- https://uz.kursiv.media (Humo в цифрах, ~2026-03-27, via search summary)
- gazeta.uz/2025/12/25/uzcard; vc.ru beznalichnye-platezhi-v-uzbekistane-s-2026-goda; fintech-retail.com QR-code coverage (via search summaries)
- https://sharh.commeta.uz/en/blog/yetkazib-berish-xizmatlari-jangi-uzum-tezkor-va-yandex-eatsga-qarshi
