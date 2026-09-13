# ADR 0112: Campaigns are versioned, offers reference the catalogue, and the contact policy decides

- Decision status: Proposed
- Implementation status: Not started — everything this record specifically decides
  (per-guest scenarios, action selection with a written reason, dispatch as commands
  carrying an attempt id to three surfaces, control-group measurement, and a unified
  `pricing`-referencing offer catalogue) is new. The `marketing` module this record
  extends already ships a broadcast campaign state machine with four-eyes approval,
  a closed audience-predicate catalogue, suppression, quiet hours and a frequency
  cap, and Telegram delivery — all `Built` under ADR 0044, unchanged here — and
  `pricing` already ships a real promotion rule engine (`Promotion`/`PromotionEvaluator`)
  and shared promo codes (ADR 0072) with no versioned-offer authoring surface above
  them. Nothing described below as a scenario, an action-selection decision log, a
  control group, or an attribution model exists in the codebase today.
- Date proposed: 2026-09-13
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of
  2026-09-13 ("write a new ADR for campaign management and CRM (customer card); do a
  similar architecture for these"); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0004, ADR 0005, ADR 0018, ADR 0020, ADR 0025, ADR 0027, ADR 0029,
  ADR 0030, ADR 0031, ADR 0032, ADR 0043, ADR 0044, ADR 0046, ADR 0055, ADR 0072,
  ADR 0111
- Supersedes / Superseded by: —
- Open inputs:
  - Whether a control group is mandatory above a recipient-count threshold, or a
    tenant's own choice always — small tenants pay a real cost for a control group
    (see Consequences) and product must set the floor (product, finance). Until this
    resolves, `control_group_percent` stays nullable/optional (see Decision §1).
  - Named attribution models beyond first-touch/last-touch — the bank reference
    names "first touch, lead/application"; a restaurant's shortest funnel (contact
    to next order) may not need more than two models, but counsel should confirm
    no regulatory reporting expects a specific model (product, counsel).
  - SMS gateway contract and per-segment price, inherited unresolved from ADR 0044 —
    this record's cost ceiling on a scenario step is meaningless until it is set
    (finance).
  - Whether a scenario's wait step may hold a customer for longer than the
    frequency-cap window ADR 0044 already enforces, which would let a slow scenario
    silently starve itself of eligible steps (product).
  - Whether the courier-as-audience gap (`6.4b`) is answered by widening this
    record's audience shape or by a genuinely separate operational-messaging
    concept outside `marketing` entirely (platform owner).
  - Sequencing against ADR 0055's greenfield launch order: a per-guest scenario
    engine with control groups and named attribution is a mature-CVM feature this
    record does not itself rank against the pilot's storefront → operations →
    payments → onboarding build order, and control groups are a real cost for a
    small pilot-scale tenant specifically — this record's own Consequences uses a
    40-recipient scenario as the worked example (platform owner).

## Context

**The broadcast half of CVM is built; the scripted half is not.** ADR 0044 and its
`marketing` module already implement a real campaign state machine — `DRAFT ->
IN_REVIEW -> APPROVED -> SCHEDULED -> SENDING -> SENT | PARTIALLY_SENT`, with
`HALTED_BUDGET`/`HALTED_OPERATOR` terminal
(`platform/src/main/java/uz/horecaos/platform/marketing/domain/CampaignStatus.java:20-58`)
— four-eyes approval where the approver may never be the author
(`CampaignService.java:219-256,269-280`), a closed nine-predicate audience catalogue
(`PredicateType.java:31-97`), a `RefusalReason` written for every excluded
recipient rather than a silent filter
(`RefusalReason.java:18-56`), and quiet-hours deferral plus a cross-channel
frequency cap (`EngagementPolicy.java:26-100`). All of it sends a **one-off
broadcast to a static snapshot**. There is no per-guest scenario: no wait step, no
branching on a reaction, no re-entry, no control group. The IA's own §6.4 row
names this gap directly: "One-off broadcasts to a segment"
(`platform/docs/frontend-information-architecture.md:216`).

**Only one channel actually sends.** `CampaignTelegramDeliveryService.isWired`
returns `true` only for `MESSAGING_APP`; SMS, EMAIL and PUSH pass creation,
estimation and even four-eyes approval, then throw
`IllegalStateException` the moment `CampaignSendService.expandNextBatch` tries to
start them, because no adapter is wired
(`CampaignTelegramDeliveryService.java:161-163`,
`CampaignSendService.java:104-111`; gap map row `6.4`). An operator can today spend
a second signature on a campaign that cannot send.

**Promotions have a real engine and no authoring surface above the coupon-code
slice.** `pricing.domain.Promotion` and `PromotionEvaluator` already implement an
11-condition, 8-action, two-stage rule engine with stacking groups, exclusivity and
largest-remainder capping
(`Promotion.java:1-222`, `PromotionEvaluator.java:1-528`) — the operations-spec
prose calling promotions "decided and unbuilt" is simply wrong about the engine.
What is missing is authorship: the only discount an operator can create today is a
typed promo code through `PromoCodeAuthoringService`, restricted to three discount
shapes (`PERCENTAGE_OFF_ORDER`, `FIXED_AMOUNT_OFF_ORDER`, `FREE_DELIVERY`), always
exclusive, always coupon-gated (`PromoCodeAuthoringService.java:27-33,71-79`), with
no `validFrom`/`validUntil`/`channels`/`locationIds` fields on the console's draft
form even though the request, service and eligibility check all accept and enforce
them (gap map row `6.2`). Every automatic, non-coupon discount the IA's §6.1 names —
markup, gift triggers, geozone scoping, Nth-order rules, a quote simulator — has no
authoring surface at all (gap map row `6.1`).

**Templates and content are genuinely built and multilingual.**
`NotificationTemplateService.addVersion` requires `ru`, `uz-Latn` and `en` wordings
in one call and refuses to activate a partially translated version
(`NotificationTemplateService.java:99-205`); `TemplateRenderer` is
substitution-only over an allowlisted variable map, validated at authoring time
(`TemplateRenderer.java:12-124`). This record reuses both unchanged.

**There is no measurement of a campaign against a control group, and no acquisition
attribution link exists to mint.** `CampaignBlockRateMonitor` measures delivery
health (block rate), not sales outcome
(`CampaignBlockRateMonitor.java:34-90`); `marketing.attribution_links` has no
migration at all (gap map row `6.6a`), and Marketing reports on redemption and
delivery statistics are `NOT BUILT` because the underlying
`reporting.fact_promotion_redemption` does not exist
(`platform/docs/operations-spec/statistics.md:397,403,1025-1026`).

**Module ownership today**: `marketing` owns audiences, campaigns, suppression and
engagement policy (ADR 0044); `pricing` owns promotions, promo codes and coupon
redemption (ADR 0018, ADR 0072); `notifications` owns dispatch, templates and
delivery attempts (ADR 0020); `loyalty` owns the points ledger and accrual rules
(ADR 0046); `reporting` owns the ADR 0043 fact tables. This record does not move
ownership of any of them.

## Decision

**Campaigns gain a second shape — a scenario — beside the existing broadcast, both
governed by the same approval and audience machinery ADR 0044 already built.**
Offers become a versioned, catalogue-referencing entity `pricing` exposes and
`marketing` selects from, never authors. The contact policy becomes an explicit,
per-(channel, guest, campaign, period, type) decision with a written reason for
every block, reusing ADR 0030 for the tenant-tunable parts.

**1. Campaign formation.** Audiences and segments are unchanged from ADR 0044 —
this record adds no predicate and no snapshot mechanism. **New**: a campaign may
declare itself a `SCENARIO` (per-guest steps) instead of a `BROADCAST` (the
existing one-off send); both share `marketing.campaigns`' approval, cost-ceiling
and channel machinery. A scenario is an ordered list of steps —
`(channel, offer_or_template, wait_duration, continuation_condition,
stop_condition)` — evaluated per guest against `marketing.customer_metrics` and
the guest's own step-state row, never against the whole audience at once. A
control group is optional per scenario (`control_group_percent` is nullable): a
tenant that sets one withholds that fixed percentage of the snapshot from every
step, recorded once at scenario start and never resampled per step; a tenant that
leaves it null runs the scenario against its full audience with no measurement
baseline. Whether a floor should ever be mandatory is Open Input #1 — until that
resolves, this record does not require one. Approval and
publication remain separate rights exactly as ADR 0044 built them: `campaign.approve`
requires an approver distinct from the author, and a published scenario version is
immutable — editing one produces a new version requiring fresh approval, the same
rule ADR 0044 already enforces for a broadcast's audience snapshot.

**2. Campaign management — per-guest execution.** A new
`marketing.scenario_participant_state` row per (scenario campaign, customer)
tracks the current step index, the wait-until instant, and a terminal outcome
(`COMPLETED`, `STOPPED_BY_CONDITION`, `STOPPED_BY_CONSENT_WITHDRAWN`,
`STOPPED_BY_SUPPRESSION`). **Action selection is "event → condition → action"**,
implemented as one method that, for a due participant, checks: does the offer or
template still apply (validity window, audience membership unchanged), does it
conflict with another live scenario's offer for the same guest, what is its
priority against a simultaneously-due broadcast, is the resolved channel allowed
by the contact policy below — and writes exactly one row to
`marketing.scenario_step_decisions` recording the choice and, for every block, a
reason, extending `RefusalReason` with three scenario-specific values
(`SCENARIO_CONFLICT`, `SCENARIO_PRIORITY_LOST`, `SCENARIO_STOPPED`) rather than a
second enum. **Dispatch is a command, never a direct call**, following ADR 0020
exactly as broadcasts already do: a due step becomes a `SendNotificationCommand`
to `notifications` carrying the step's `attempt_id`, an `EnqueueCallTaskCommand`
to `customers` when the channel is `CALL_CENTRE`, or a storefront/Telegram "offer
to show" row `marketing.presented_offers` the storefront and bot surfaces poll,
when the channel is `IN_APP`. **`marketing` keeps no private call queue.**
`EnqueueCallTaskCommand` is consumed under ADR 0005's inbox by `customers`, which
inserts exactly one `customer.leads` row (ADR 0111, source `CAMPAIGN_SCENARIO`) —
the same queue, capabilities and audit a phoned-in enquiry already uses.
`marketing` persists only the `scenario_step_decisions` row already written for
the decision, with an `acknowledged_at` column as the command-delivery receipt;
Principle 1's rule that marketing never keeps a private copy of a customer or a
private contact log holds for this dispatch target exactly as it does for the
other two. **Result
measurement** compares, per step and per scenario, the treated group's rate of
reaching a named goal event (next order, reservation, redemption) against the
withheld control group's rate over the same window, using one of two named
attribution models: `FIRST_TOUCH` (credit the first scenario or campaign that
contacted the guest in the attribution window) and `LAST_TOUCH` (credit the most
recent one before the goal event) — the bank reference's richer "lead/application"
model has no restaurant equivalent and is not built. ADR 0111's customer card reads
this history through `marketing.api.CustomerEngagementHistoryPort`; this record
grows that port's contract (defined narrowly by ADR 0111 for promo redemptions and
campaign receipts) to add `scenarioStepDecisions` and `presentedOffers` reads, so
the card ADR 0111 builds can show scenario history without a second port.

**3. Offer management.** An offer is `marketing.offers`: a reference to exactly one
of a `pricing` promotion/coupon-code lineage or a `loyalty` accrual rule (ADR 0046),
plus conditions, a validity window, target audiences, allowed channels and a
version. **Marketing never authors a discount or mints a point.** Exactly as ADR
0044 already decided for its `benefit_offer_id` and accrual-rule reference
(`CampaignService.java:78-103`), an offer's `pricing_promotion_id` or
`loyalty_accrual_rule_id` must already exist; this record adds no new field to
`pricing.promotions` or `loyalty.accrual_rules`, and no campaign or scenario editor
gains a "create a discount" button. **The two offer shapes coexist, and this
record does not migrate the older one.** `CampaignService.create`'s existing
`benefitOfferId`/`loyaltyAccrualRuleId` parameters keep working unchanged for a
`BROADCAST` campaign — an already-approved or already-sent broadcast must not
change meaning underfoot. A `SCENARIO` campaign's steps, and any new broadcast
that wants versioning, a validity window or audience/channel scoping richer than a
bare id, reference `marketing.offers` instead. Retiring the raw-id path once every
broadcast author uses `marketing.offers` is a follow-on record's call, not this
one's. **This record also backs the storefront's already-defined but
backend-less wire type.** `frontend/storefront/src/app/types/home.types.ts:90-103`
declares `OfferItem`/`CustomerUiOffer` (id, name, image, priority) for `GET
/customers/ui/`, with no Java controller found producing it
(`home.component.ts:91-92,159-162`). `marketing.offers` gains `display_name` and
`banner_image_reference` for exactly this purpose: one `OfferItem` is one
`presented_offers` row joined to its `offers` banner fields, scoped to surface
`STOREFRONT`, so a follow-on `customers.ui` controller can serve the existing
frontend type rather than a second, incompatible offer representation. Content
and templates are unchanged from ADR 0020: an offer's message reuses
`notifications` templates and their three mandatory locales, never a
marketing-owned copy of template text.

**4. Contact policy.** The bank's five-axis policy (channel, customer, product,
campaign, period, communication type) becomes four axes here — "product" has no
restaurant meaning distinct from the offer itself, so it collapses into the
existing offer scope. **New**: `marketing.contact_policy_overrides` lets a tenant
set, per `(channel, campaign_purpose, period)`, a tighter cap or a wider quiet-hour
exclusion than ADR 0044's platform defaults — reusing ADR 0044's existing
tighten-only rule and its CHECK-constraint enforcement, not loosening it. Priority
between two simultaneously due sends to the same guest (a scenario step and a
broadcast) is resolved by a single per-tenant `marketing.channel.priority.order`
ADR 0030 key ranking `campaign.purpose` values; whichever loses is logged with
`SCENARIO_PRIORITY_LOST` or a `RefusalReason` and deferred to the next eligible
slot, never dropped. In-app show caps are a new `marketing.presented_offers`
column (`shown_count`, capped per ADR 0030 key
`marketing.in_app.show_cap_per_day`), because a storefront banner has no delivery
attempt to count against the messaging frequency cap and needs its own.

**5. What is explicitly not decided or built.** No external CDP or marketing
automation vendor — every scenario runs inside this platform's own database
against `marketing.customer_metrics`, per ADR 0044's existing prohibition on
segment export. No ClickHouse — `reporting`'s fact tables remain the only mart,
and this record's result-measurement queries run against them, not a new store.
No SAP integration of any kind. No separate lead service — ADR 0111 already
settled that; a scenario's `CALL_CENTRE` step hands off into ADR 0111's callback
queue rather than inventing a second one. Courier-as-audience (`6.4b`) is not
resolved here — it is named as an open input because widening this record's
`customer_account_id`-keyed audience shape to include a courier identity is a
structural change ADR 0044 did not anticipate and this record should not decide
in passing. Nor is this record's own priority against the pilot's launch order —
whether a scenario engine with control groups is worth building before payments
and onboarding finish is left to the platform owner (Open Inputs).

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A third-party CDP or marketing-automation vendor (Braze, Klaviyo, SendPulse-successor) for scenarios | ADR 0044 already rejected exporting any audience or customer list to a third party as a disclosure with no lawful basis; a vendor running scenarios would need the full customer base disclosed, not just a segment | Legal establishes a distinct third-party-marketing consent purpose and a processor agreement a tenant can opt into per-brand |
| Put scenarios inside the `notifications` module, since it already owns dispatch | `notifications` deliberately holds no `customer_account_id`-keyed audience logic and no consent/suppression decision-making — ADR 0020 draws that line so a send-side module never forms its own opinion about who should receive what. Scenarios are audience logic first and dispatch second | `notifications` is asked to do audience-aware routing for a reason unrelated to marketing (e.g. transactional throttling), which is a different decision |
| A single "marketing" module owning campaigns, offers, and the contact policy with no `pricing`/`loyalty` split | Collapses the boundary ADR 0044 already drew and re-litigated in its own alternatives table: marketing decides who and when, pricing decides what a benefit is worth. A marketer able to define a discount from inside a campaign editor is the exact "campaign editor selects from offers" line ADR 0044 forbids crossing | Never; if it happens it undoes ADR 0044, not this record |
| A shared cross-tenant guest identity for scenario measurement, so a chain's control group is measured across all its tenants at once | HorecaOS's tenant isolation is the platform's primary security boundary (`platform/CLAUDE.md`), and `marketing.customer_metrics` is tenant-scoped by construction; a cross-tenant measurement would need a new aggregation layer reading across tenant boundaries, which nothing in this platform does today | A single legal entity operates several HorecaOS tenants and asks for consolidated CVM reporting — a control-plane-level reporting decision, not a `marketing`-module one |
| Mandatory control groups on every scenario, regardless of recipient count | A five-table restaurant sending a scenario to 40 guests cannot spare a withheld group large enough to measure anything, and mandating one anyway is a cost with no statistical payoff | Never as a blanket rule; a size threshold is this record's own open input |
| Resolve simultaneous-send priority per-message at dispatch time rather than a single per-tenant ranking key | More flexible, and unauditable — two operators could each believe their campaign has priority, and nothing would explain a guest's actual delivery order after the fact. A single ranked key answers "why did A arrive before B" from configuration alone | A tenant needs priority that varies by guest segment rather than campaign purpose alone, which is a genuinely richer policy model |
| A marketing-owned `scenario_call_tasks` queue mirroring the bank reference's call-task shape 1:1 | Duplicates ADR 0111's lead/contact-attempt journal and breaks Principle 1 (marketing keeps no private copy of a customer or contact log) exactly where the reference architecture states it most plainly; a delivery-receipt column on the existing decision row is enough | A channel needs richer in-flight call state (multi-attempt retries, IVR routing) than a lead row can express, at which point ADR 0111's own `contact_attempts` table grows, not a marketing table |

## Consequences

### Positive

- A late-order apology, a birthday sequence, or a win-back nudge can finally be a
  multi-step scenario with a wait and a stop condition, not just a single broadcast
  — closing the gap the IA's §6.4 names directly.
- Every block anywhere in action selection — a conflict, a lost priority race, a
  contact-policy refusal — is written down with a reason, extending ADR 0044's
  existing discipline (`RefusalReason`) rather than inventing a second, inconsistent
  one.
- Offers stay strictly downstream of `pricing` and `loyalty`; a scenario or a
  broadcast can reference richer benefits (the real `Promotion`/`PromotionEvaluator`
  engine, not just the three coupon-code shapes) without marketing gaining the
  ability to invent a discount.
- Dispatch to three surfaces — notifications, call-centre tasks, in-app offers — is
  uniformly a command carrying an attempt id, so the same reconciliation discipline
  `NotificationDispatchService` already has for uncertain outcomes applies without a
  second implementation.

### Negative

- A scenario is genuinely more operational load than a broadcast: a marketer must
  now think about wait durations, stop conditions and what happens when a guest
  enters two scenarios at once, none of which a one-off send required.
- Control groups cost real reach for a small tenant — withholding even 10% of a
  40-recipient scenario from a benefit is four guests who would have converted and
  did not, for the sake of a measurement a chain with thousands of guests can afford
  far more cheaply per percentage point withheld.
- Approval friction increases: a scenario now needs approval both at first
  publication and at every subsequent version, where a broadcast needed it once.
  This is the direct cost of "a published version is immutable" applied to
  something with many steps instead of one send.
- `marketing.presented_offers` is one more table carrying `customer_account_id`
  references, widening the PII surface a breach or a misconfigured export could
  expose, even though it carries no contact value directly. A scenario's
  `CALL_CENTRE` step adds no equivalent marketing-side table — it lands in ADR
  0111's `customer.leads` instead — but it does mean a scenario's call-task volume
  now shows up in the call centre's queue and audit load, a cost ADR 0111 already
  accounts for and this record adds to without a table of its own.
- The channel-priority ranking key is a single per-tenant setting; a tenant running
  many concurrent scenarios and broadcasts will eventually want per-guest or
  per-segment priority, which this record does not build and which would be a
  second ADR 0030 key layer to design correctly.
- Two named attribution models is deliberately fewer than a mature CVM platform
  offers, and a marketer arriving from one will ask for multi-touch or
  time-decay models this record does not build.
- SMS, email and push scenario steps inherit ADR 0044's existing unwired-channel
  problem unchanged: a scenario step can be approved and scheduled on a channel
  that still throws at send time, exactly as a broadcast can today (gap row `6.4`),
  until a gateway contract exists.

### Accepted trade-offs

- A scenario step's control group is fixed at scenario start and never resampled;
  a guest who unsubscribes mid-scenario simply stops being measured rather than
  being replaced, which slowly shrinks statistical power over a long-running
  scenario and is accepted rather than solved by re-randomizing.
- Priority resolution is coarse (campaign purpose, not per-guest urgency), which
  will occasionally defer a genuinely more important message behind a
  lower-priority one that happened to be configured with a higher rank; the
  alternative (per-message resolution) was rejected above for being unauditable.
- This record extends `RefusalReason` rather than replacing it, which means two
  enums (`RefusalReason` and its three scenario additions) must be read together
  to understand a full block history — accepted because a shared enum with
  scenario-only values mixed in would make broadcast-only code handle cases that
  can never occur for it.

## Specification

### Module and table ownership (Spring Modulith)

| Table / concept | Owning module | Package |
|---|---|---|
| `marketing.campaigns` (existing, `kind` column added: `BROADCAST` \| `SCENARIO`) | `marketing` | `marketing.domain` |
| `marketing.scenario_steps` (new) | `marketing` | `marketing.domain` |
| `marketing.scenario_participant_state` (new) | `marketing` | `marketing.application` |
| `marketing.scenario_step_decisions` (new, append-only) | `marketing` | `marketing.application` |
| `marketing.presented_offers` (new) | `marketing` | `marketing.application` |
| `marketing.offers` (new) | `marketing` | `marketing.domain` |
| `marketing.contact_policy_overrides` (new) | `marketing` | `marketing.domain` |
| `marketing.api.CustomerEngagementHistoryPort` (existing SPI from ADR 0111, extended here with `scenarioStepDecisions`/`presentedOffers` reads) | `marketing` | `marketing.api` |
| `customer.leads` (existing table from ADR 0111, gains source `CAMPAIGN_SCENARIO` — this record's `EnqueueCallTaskCommand` writes here, never to a marketing table) | `customers` | `customers.domain` |
| `pricing.promotions`, `pricing.coupon_codes` (existing, unchanged — read-only reference from `marketing.offers`) | `pricing` | `pricing.*` |
| `pricing.benefit_grants` (planned under `T05`, not yet built — read-only reference from `marketing.offers` once it exists) | `pricing` | `pricing.*` |
| `loyalty.accrual_rules` (existing, unchanged — read-only reference from `marketing.offers`, ADR 0046) | `loyalty` | `loyalty.*` |
| `reporting.fact_order`, `fact_promotion_redemption` (existing/planned, read-only for result measurement) | `reporting` | `reporting.*` |

`marketing` never writes to `pricing` or `loyalty` tables — an offer holds a foreign
key, never a duplicated discount definition, exactly as ADR 0044 already established
for `benefit_offer_id`.

### Data model

```text
marketing.campaigns                       -- existing table, ALTER adds:
  kind (BROADCAST | SCENARIO) not null default 'BROADCAST'
  control_group_percent smallint null      -- SCENARIO only, 0-100

marketing.scenario_steps
  id, tenant_id, brand_id, campaign_id, sequence
  channel, offer_id null, template_key, template_version
  wait_after_previous_seconds, continuation_condition, stop_condition
  version                                   -- a published campaign's steps are immutable;
                                             -- editing produces a new campaign version

marketing.scenario_participant_state
  tenant_id, brand_id, campaign_id, customer_account_id      -- pk
  current_step_sequence, wait_until null, in_control_group
  outcome null (COMPLETED | STOPPED_BY_CONDITION
                | STOPPED_BY_CONSENT_WITHDRAWN | STOPPED_BY_SUPPRESSION)
  entered_at, updated_at

marketing.scenario_step_decisions          -- append-only
  id, tenant_id, brand_id, campaign_id, customer_account_id, step_sequence
  decision (SENT | BLOCKED), refusal_reason null, resolved_channel null
  attempt_id null, acknowledged_at null    -- command-delivery receipt for a
                                            -- CALL_CENTRE step; the queue itself
                                            -- is ADR 0111's customer.leads, not a
                                            -- marketing-owned row
  decided_at

marketing.presented_offers
  id, tenant_id, brand_id, campaign_id null, offer_id, customer_account_id
  surface (STOREFRONT | TELEGRAM_MINI_APP), shown_count, last_shown_at
  dismissed_at null

marketing.offers
  id, tenant_id, brand_id, status, version, timestamps
  pricing_promotion_id null, loyalty_accrual_rule_id null      -- exactly one set
  valid_from, valid_until null, audience_id null
  allowed_channels[], template_key, template_version
  display_name null, banner_image_reference null   -- backs the storefront's existing
                                                     -- OfferItem/CustomerUiOffer wire
                                                     -- type (home.types.ts:90-103)

marketing.contact_policy_overrides
  tenant_id, brand_id, channel, campaign_purpose, period_kind (DAILY | WEEKLY | ROLLING_7D | ROLLING_30D)
  cap_count null, quiet_hours_start null, quiet_hours_end null
  check (this row only ever tightens the ADR 0044 platform default, enforced the
         same direction EngagementPolicy.tightenedBy() already checks)
```

Every table carries a non-null `tenant_id` and `brand_id` — a scenario and a
presented offer are all brand-scoped exactly as `marketing.campaigns` already is.
`marketing.scenario_step_decisions` is append-only by grant (INSERT/SELECT only),
matching the discipline `consent_decisions` and ADR 0111's `contact_attempts`
already establish.

### Events and commands (ADR 0004, ADR 0005, ADR 0032, ADR 0029)

```text
marketing.events / ScenarioStepDecided     { campaignId, customerAccountId, stepSequence, decision }
marketing.events / ScenarioParticipantStopped { campaignId, customerAccountId, outcome }
marketing.events / OfferPublished          { offerId, brandId, version }
marketing.commands / SendNotificationCommand   -- existing ADR 0020 command, unchanged,
                                                -- carries attempt_id, no PII
marketing.commands / EnqueueCallTaskCommand    -- new, addressed to customers (ADR 0111),
                                                -- consumed under its ADR 0005 inbox into
                                                -- exactly one customer.leads row; carries
                                                -- customer_account_id and attempt_id only,
                                                -- no contact value, and marketing keeps no
                                                -- copy of the resulting queue state
```

All written to the outbox in the same transaction as the deciding row (ADR 0004);
consumed under a stable `consumer_name` through the ADR 0005 inbox at the receiving
module, so a redelivered command claims its attempt id once. No payload carries a
phone number, an address, or rendered message text, per ADR 0029.

### Capabilities (ADR 0025) and four-eyes (ADR 0027)

| Capability | Gates | Scope |
|---|---|---|
| `campaign.author` (existing) | Drafting a scenario, same as a broadcast | Unchanged — see ADR 0044 |
| `campaign.approve` (existing) | Approving and publishing a scenario version — approver ≠ author, unchanged from ADR 0044 | Unchanged — see ADR 0044 |
| `marketing.offer.manage` (new) | Creating/versioning `marketing.offers` — never grants authority to create the underlying promotion or accrual rule | `TENANT` \| `BRAND` (offers are brand-scoped; no `LOCATION` grant, a brand's offers are not location-specific) |
| `marketing.contact_policy.manage` (new) | Writing `marketing.contact_policy_overrides` | `TENANT` \| `BRAND`, matching `contact_policy_overrides`' own scoping |
| `audience.read`, `audience.export`, `suppression.manage` (existing) | Unchanged | Unchanged — see ADR 0044 |

A scenario version reaching `SENDING` requires the same ADR 0027 four-eyes grant a
broadcast requires; publishing a **new version** of an already-approved scenario
requires a fresh approval under a fresh grant, exactly as ADR 0044 already
specifies for a changed campaign.

### Configuration keys (ADR 0030)

- `marketing.channel.priority.order` (`TENANT`, ranked list of campaign purposes) —
  resolves a simultaneous-send tie between a scenario step and a broadcast.
- `marketing.in_app.show_cap_per_day` (`BRAND`, default `3`) — the separate in-app
  show cap the bank reference names explicitly, distinct from the messaging
  frequency cap.
- `marketing.scenario.control_group_percent.default` (`TENANT`, default `10`) — the
  default withheld percentage a scenario author sees, overridable per scenario up
  to the open input's eventual floor.
- `marketing.contact_policy.*` — every `contact_policy_overrides` row is itself an
  ADR 0030-resolved value at `BRAND` scope, most-specific-wins over the ADR 0044
  platform default, tighten-only.

## Rollout and rollback

This record governs two gap-map waves in full and touches none of the remaining
three, which stay owned by ADR 0111 or a future reporting record.

**Governed by this record:**
- **`T05` — Segments and promo codes** (gap map line 764 confirms the wave; rows
  `5.3`, `6.2`, `6.2a`). The segment consent-purpose bug and the promo-code draft
  form's missing fields are pure bug fixes this record does not re-decide; `6.2a`'s
  `pricing.benefit_grants` table is exactly the per-recipient coded grant this
  record's offers reference for a scenario step's unique code, so `T05` is a
  prerequisite this record consumes rather than redesigns.
- **`T18` — Campaigns, loyalty and acquisition links** (rows `6.3`, `6.4`, `6.4b`,
  `6.6a`). `6.4`'s `isWired` flag and scheduled-send field are exactly this record's
  own "no unwired-channel promise" and scenario `wait_after_previous_seconds` needs;
  `6.6a`'s `marketing.attribution_links` is the acquisition-link mechanism this
  record's `FIRST_TOUCH`/`LAST_TOUCH` models read `acquisition_link_id` from. `6.4b`
  (couriers as an audience) is named above as an open input this record does not
  resolve, even though `T18` scopes a first attempt at it.

**Adjacent, not governed by this record:**
- **`P40`, `P26`** — customer-card and import waves (gap-map lines 1326, 1347),
  owned by ADR 0111.
- **`P27`** — order and business reports, unrelated to campaigns; any marketing
  reporting fact (`fact_promotion_redemption`) is a future reporting-module record,
  cited here as a dependency and not built by this one.

**Sequence**: `T05` first (benefit grants, segment fix) since scenarios need a
correct segment member count and a coded-grant table to reference. `T18`'s
`isWired`/`scheduledAt` next, since a scenario step must know at authoring time
whether its channel can actually send. Then this record's own tables: offers first
(no dependency on scenario machinery), scenario steps and participant state second,
call-task and presented-offer dispatch last, since they are the two new command
targets and need the receiving queues (ADR 0111's `customer.leads`; the
storefront/bot poll surface) ready first. Ship scenarios against the existing fake
`notifications` provider before any real send, exactly as ADR 0044's own rollout
did for broadcasts. Rollback is additive: disabling `kind = SCENARIO` campaign
creation at the API layer leaves every broadcast campaign, and `pricing`/`loyalty`
entirely, untouched.

**Against the pilot's launch order.** ADR 0055 sequences storefront → operations →
payments → onboarding. A scripted per-guest scenario engine with control groups and
named attribution is a mature-CVM feature this record does not itself rank against
that order, and the single pilot tenant's guest count makes a withheld control
group a real cost, not a rounding error (see Consequences). This record specifies
the shape; the platform owner decides the build-now-or-later call (Open Inputs).

## Implementation checklist

- [ ] `marketing.campaigns.kind` and `control_group_percent` (nullable) columns;
      `scenario_steps`, `scenario_participant_state`, `scenario_step_decisions`
      (with `acknowledged_at`), `presented_offers`, `offers` (with `display_name`,
      `banner_image_reference`), `contact_policy_overrides` migrations with grants,
      under the next free Flyway numbers.
- [ ] `ScenarioService`: step evaluation, action selection with the extended
      `RefusalReason`, and dispatch to the three command targets.
- [ ] `OfferService`: authoring, versioning, and the exactly-one-reference
      validation against `pricing`/`loyalty`.
- [ ] `EnqueueCallTaskCommand` published to the outbox and consumed by ADR 0111's
      `customers` inbox (no marketing-owned call-task table); storefront/bot poll
      endpoint for `presented_offers`.
- [ ] `marketing.contact_policy.manage` capability and the tighten-only CHECK on
      `contact_policy_overrides`.
- [ ] Result measurement: a query comparing treated vs. control-group goal-event
      rates per scenario, against `reporting.fact_order` (existing) with a named
      attribution model.
- [ ] Tests: a scenario version cannot be edited once approved without a fresh
      approval; a control group is fixed at entry and never resampled; a
      simultaneous scenario-step/broadcast tie resolves per the configured priority
      key and the loser is deferred, not dropped; an offer cannot be created without
      exactly one of a promotion or accrual-rule reference; cross-tenant reads of
      every new table fail.

## Exit criteria

A marketer can author a multi-step scenario referencing an existing promotion or
accrual rule, publish it under a second signature, and see it run per guest —
when a control group is configured, its outcome is measured against a named
attribution model. Every block —
a conflict, a lost priority race, a contact-policy refusal — is answerable from a
`scenario_step_decisions` row with a reason. A due step reaches `notifications`,
the call centre or the storefront/bot surface as a command carrying an attempt id,
never a direct call. No campaign or scenario editor offers a field to invent a
discount or a points award. And a tenant's contact-policy override can only ever
be stricter than the ADR 0044 platform default, enforced by the database as well
as the service.

## References

- [ADR 0004](../built/0004-sql-outbox-and-kafka-delivery.md) — the outbox every new event and command writes through
- [ADR 0005](../built/0005-kafka-inbox-and-idempotent-consumers.md) — the inbox `customers` uses to consume `EnqueueCallTaskCommand`
- [ADR 0018](../partial/0018-deterministic-pricing-promotions-taxes-and-quotes.md) — the promotion engine offers reference, unchanged
- [ADR 0020](../partial/0020-notification-preferences-templates-and-delivery.md) — the command/dispatch pattern this record's three surfaces reuse
- [ADR 0025](../built/0025-fine-grained-authorization-and-capability-model.md) — capabilities two new ones join
- [ADR 0027](../built/0027-audit-evidence-and-approval-model.md) — the four-eyes model a scenario version reuses unchanged
- [ADR 0029](../partial/0029-pii-protection-envelope-encryption-and-key-rotation.md) — no PII in any new event or command payload
- [ADR 0030](../built/0030-configuration-and-policy-resolution.md) — the tenant-tunable contact-policy and priority keys
- [ADR 0031](../built/0031-http-api-conventions.md) — the HTTP conventions new offer/contact-policy/scenario endpoints follow
- [ADR 0032](../built/0032-event-contract-governance-and-topic-policy.md) — the event/command catalogue new topics join
- [ADR 0043](../partial/0043-reporting-analytics-and-the-metric-layer.md) — the fact tables result measurement reads
- [ADR 0044](../partial/0044-marketing-campaigns-audiences-and-engagement.md) — the campaign, audience, suppression and engagement-policy model this record extends, not redecides
- [ADR 0046](../partial/0046-loyalty-points-and-split-tender.md) — the accrual-rule reference an offer may point at instead of a promotion
- [ADR 0055](../meta/0055-greenfield-launch-scope.md) — the pilot's build order this record's rollout is sequenced against
- [ADR 0072](../built/0072-promo-codes-as-a-coupon-gated-pricing-input.md) — the coupon-gated pricing input an offer may reference
- [ADR 0111](../not-started/0111-customer-card-and-communication-history.md) — the `customer.leads` queue a `CALL_CENTRE` scenario step's command lands in, and the port this record extends
- `platform/docs/operations-gap-map.md` lines 680-681, 764-765, 1744, 1759 (waves
  `T05`, `T18`); lines 740, 741, 1326, 1347 (adjacent waves `P40`, `P26`); §6 rows
  `5.3`, `6.1`, `6.2`, `6.2a`, `6.3`, `6.4`, `6.4b`, `6.6a`
- `frontend/storefront/src/app/types/home.types.ts:90-103` — the `OfferItem`/`CustomerUiOffer` wire type this record's `marketing.offers` backs
- `/private/tmp/claude-501/-Users-admin-Developer-HorecaOS/06b46f36-9f32-4bff-8658-edc24c462f82/scratchpad/crm-reference-architecture.md` — the bank reference this record adapts
