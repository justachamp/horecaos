# ADR 0044: Marketing campaigns, audiences, and engagement content

- Decision status: Accepted
- Implementation status: Partial — `V0043` and the `marketing` module build the
  customer metric projection with its drift-then-recompute sweep, the closed
  predicate catalogue (ten of thirteen predicates), audiences and immutable
  snapshots, suppression, the frequency cap and quiet hours as tightening-only
  ADR 0030 policy, the campaign state machine with four-eyes approval and a
  reserved-cost ceiling, segment-aware cost estimation, and batched idempotent
  expansion, covered by `MarketingCampaignTests` and `EngagementPolicyTests`.
  A campaign can now send over TELEGRAM (wave 12): `notifications`'s
  `CampaignTelegramDeliveryService` implements `marketing.api.CampaignMessagePort`,
  expansion runs on the new `CampaignExpansionScheduler` (this module's first
  `@Scheduled` method), delivery is paced under the bot's per-brand ceiling
  (`CampaignPacer`, default 10/s of the shared ~30/s), a stored estimated delivery
  window replaces a promise, and a block-rate guard
  (`CampaignBlockRateMonitor`/`CampaignFeedbackPort`) pauses `SENDING → PAUSED`
  and fires a real operations alert when recipients start blocking — covered end
  to end by `CampaignBroadcastIntegrationTest`. Telegram launch is
  entitlement-gated (`telegram.broadcasts.enabled`, opt-in). SMS, EMAIL, and PUSH
  still have no adapter. A campaign paused by the block-rate guard can be resumed
  (wave 13): `POST .../campaigns/{campaignId}/resumptions` returns it to
  `SENDING`, re-checks the Telegram broadcasts entitlement, resets the guard's
  blocked-recipient counter so it measures the resumed run, and reports how many
  messages the pause suppressed with `CAMPAIGN_NOT_SENDING` — which are not
  retried — covered by `CampaignBroadcastIntegrationTest`,
  `MarketingCampaignTests` and `OperationsMarketingResumeEndpointTests`. Nothing schedules the
  projection sweep, the retention jobs or the erasure path either — the
  expansion scheduler is the module's only `@Scheduled` method, so the
  five-minute staleness budget still has no runner. Also not built: the
  four triggers and coded grant minting (`pricing.benefit_grants` does not exist),
  merchandising slots, attribution links, referral edges, reviews, the incremental
  inbox fold behind the projection, and the legacy `ratings` migration. The quiet
  hours and cap values still need counsel's confirmation before a first production
  send.
- Date proposed: 2026-08-21
- Date decided: 2026-08-23
- Deciders: Ayubkhon Abbosov (platform architecture), product, finance, legal
- Depends on: ADR 0013, ADR 0015, ADR 0018, ADR 0019, ADR 0020, ADR 0021, ADR 0025,
  ADR 0027, ADR 0029, ADR 0030, ADR 0035, ADR 0043
- Supersedes / Superseded by: —
- Open inputs: Marketing frequency cap and quiet-hours values (product, legal),
  carried from ADR 0020 — provisional defaults are set below and enforced from day
  one; marketing SMS sender of record and price per segment (finance, legal); the
  signed treatment of cancelled and refunded orders in RFM (finance), inherited
  from ADR 0043's provisional registry version 1. **None is structural** — see
  [Open inputs and why none is structural](#open-inputs-and-why-none-is-structural).
  The Telegram surface question that held this record at `Proposed` was answered by
  ADR 0035 on 2026-08-22 and is withdrawn rather than deferred. Referral reward
  mechanics — who is rewarded, on which qualifying event, amount, cap, expiry — was
  an open input here and is resolved by [ADR 0067](../partial/0067-referral-program-rewards-through-the-loyalty-ledger.md)
  on 2026-09-05: a tenant-configured shape (both sides, or the referrer only),
  riding on ADR 0046's loyalty ledger. The referral edge and attribution links
  this record still owns (below) are unaffected and remain unbuilt.

## Context

ADR 0020 owns notification delivery and already names `MARKETING` as a class.
That is the send side. The campaign side is a different problem with different
decisions, and nothing owns it.

The hard part is the audience. An audience is either an ad-hoc query over the
customer tables or a maintained per-customer aggregate. The competitor's RFM
filters — days since last order, order count, total spend, average check — and
its inactivity triggers both need the second, because those are aggregates over
every order a customer ever placed. Computing them live, for every customer in a
tenant, each time a marketer moves a slider, is a scan of the order table on the
same database that is taking orders during a dinner rush.

The second is that SMS costs money per message and the cost is not uniform.
Uzbek marketing copy is trilingual. Latin script encodes as GSM-7 at 153
characters per concatenated segment; the same text in Cyrillic falls to UCS-2 at
67. A 200-character message is two segments in uz-Latn and three in ru. An
estimator counting recipients rather than segments per recipient locale is wrong
by more than a factor of two, and whoever discovers that is reading the Eskiz
invoice.

The third is consent. ADR 0015 makes consent append-only and returns false when
no decision exists, which is the right default, but a campaign has a gap between
the audience being built and the messages going out, and an unsubscribe landing
in that gap must still take effect. Suppression — hard bounces, invalid numbers,
complaints, operator blocks — is absent from the competitor entirely, and is a
different fact from consent: consent is legal permission, suppression is a
deliverability or abuse fact, and one customer can carry both.

One decision belongs to pricing, not messaging. The competitor's late-order
apology mints a unique single-use promo code per message. ADR 0018's coupon model
has codes belonging to a promotion with programme-wide limits and no per-instance
grant with an owner and an expiry. It does have that exact shape in
`pricing.benefit_grants`, built for ADR 0013 recovery — and that has no code.

Whether Qoida ships an editorial CMS at all is also decided here. None of this
exists in code: the backend has tenancy, iam, audit, media, catalog, customers,
pricing, inventory, and integration, while `notifications`, `ordering`,
`payments`, and `reporting` are package declarations with almost nothing behind
them.

## Decision

**A `marketing` module owns audiences, campaigns, triggers, merchandising slots,
attribution links, and reviews, and never sends a message.** Every outbound
message is an ADR 0020 intent of class `MARKETING`. Marketing never calls Eskiz,
Firebase, or the Telegram Bot API, and holds no phone number, email address, or
push token — only `customer_account_id` references, per ADR 0020's endpoint rule.

**An audience is a saved, typed, versioned predicate set evaluated against a
maintained per-customer projection, and a send targets a materialised snapshot of
that evaluation.** The projection makes RFM cheap; the snapshot makes a send
costable before it starts and explainable afterwards.

**Predicates are a closed catalogue, not SQL.** ADR 0018's argument against
scriptable price rules applies here, plus one more: a marketer with arbitrary
query access over the customer tables has arbitrary read of the tenant's base.

**A campaign is an entity with an author, an approver, a channel, a cost ceiling,
a recipient cap, and a per-recipient receipt.** It cannot send without approval,
it stops at the ceiling, and its recipient rows carry the notification id.
**Consent and suppression are evaluated twice** — at snapshot build, so the
approver sees truthful reach and cost, and again per recipient at send, so the
unsubscribe that arrived in between wins.

**A unique per-recipient promo code is a coded benefit grant, not a coupon row.**
A shared campaign code word stays a `pricing.coupon_codes` row with limits. Two
concepts, stated, closing the ambiguity the parity analysis raised.

**Qoida does not build an editorial CMS.** It builds four bounded merchandising
placements — home carousel, story rail, promo grid, entry modal — each a typed
record with localised strings, one media reference, a closed union of link
targets, a schedule, and an optional audience. No tenant-authored markup.
**Triggers fire on domain facts Qoida owns**, not client-side telemetry: cart
abandonment is an ADR 0019 cart that did not become an order, and "opened the bot
and did nothing" is not modelled.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Evaluate audiences live against the customer and order tables | RFM is an aggregate over full order history, so live evaluation is a scan per slider change on the order-taking database, and it leaves no reproducible record of who was targeted | Never for interactive segmentation. If ADR 0043 lands a separate analytical store, the projection may move there rather than disappear |
| Store the predicate only and re-evaluate at send | Cheaper, no snapshot rows. Rejected because the audience then changes between approval and send: finance signs off on 12,000 recipients and 38,000 receive it because an import ran overnight. Cost approval over a moving set is meaningless | Never for cost-bearing sends. Free channels may re-evaluate under an explicit cap |
| Free-form SQL or a query builder over the customer schema | Hands a marketing user arbitrary read of the tenant's customer base, defeats ADR 0025 scoping, and makes every segment an unversioned artefact that cannot be replayed. The closed catalogue is small on purpose | A predicate is requested three times. Extend the catalogue, never the language |
| Mint one `pricing.coupon_codes` row per recipient | Forty thousand code rows on one promotion make "how many redemptions did this promotion get" ambiguous and fill the promotion registry with per-person data. `benefit_grants` already has an owner, a validity window, an expiry, and reservation under concurrency | Never; the shared-code / per-instance-grant split is the decision |
| A general editorial CMS with tenant-authored HTML | The competitor's static-page editor accepts raw HTML, which is stored XSS aimed at the tenant's own customers with a marketing user as the unwitting attacker. It is also website-builder work with no commerce value | A tenant needs long-form editorial. Embed a hosted CMS behind an allowlisted iframe; do not accept markup into the storefront |
| Hold this record at `Proposed` until Telegram broadcast messaging is in scope | The Telegram question was the stated reason this record sat at `Proposed`, and ADR 0035 answered its structural half on 2026-08-22 by placing the Mini App and, with it, the bot Qoida operates. What is left is a channel activation in ADR 0020 that this model already accommodates. Holding an otherwise-settled design against a sequencing question stops the projection, the predicate catalogue, and the consent machinery — the long-lead items — from being built at all | Never for this reason. If Telegram broadcast turns out to need a per-message cost or a provider rate limit the ceiling model cannot express, that is a new decision rather than this one reopened |
| Count the marketing frequency cap per channel rather than across all channels together | Simpler, and more generous to the marketer. Rejected because the customer experiences one brand, not three transports: three SMS, three pushes, and three Telegram messages in a week is nine interruptions and a spam report, which is the outcome the cap exists to prevent | A channel is shown to carry materially different tolerance — an in-app inbox the customer opens deliberately is not an interruption — in which case exempt that channel explicitly rather than splitting the counter |
| Let a tenant loosen the frequency cap or narrow the quiet-hours window | Tenants will ask, and it is their customer relationship. Rejected because both numbers also protect the sending reputation of the aggregator identity Qoida shares across tenants, and one tenant's aggressive sending degrades delivery for every other tenant on the same sender. Overrides may tighten only | Qoida moves to per-tenant sender identities with separately measured reputation. The externality is then contained and the limit can become the tenant's |
| Upload an audience to an advertising platform as a Custom Audience or match file | Standard practice and genuinely effective for acquisition. Rejected because it discloses personal data to a new controller: it needs a lawful basis, a processor agreement, and a consent purpose ADR 0015 does not carry, and the hashing usually offered as mitigation pseudonymises a known identifier rather than anonymising it | Legal settles a disclosure basis and ADR 0015 carries a distinct third-party-advertising purpose a customer can refuse on its own, independently of transactional and marketing consent |

## The customer metric projection

One row per brand profile, maintained from order terminal events through the ADR
0005 inbox, plus a nightly sweep that recomputes from source and reports drift
rather than silently correcting it.

```text
marketing.customer_metrics
  tenant_id, brand_id, customer_account_id            -- primary key
  order_count, completed_order_count, cancelled_order_count
  gross_spend_minor, net_spend_minor, average_check_minor
  first_order_at, last_order_at, days_since_last_order, registered_at
  acquisition_channel, acquisition_link_id null, preferred_locale
  birth_month_day null, last_marketing_message_at null
  marketing_messages_7d, marketing_messages_30d
  metric_definition_version, watermark_event_at, updated_at
```

`metric_definition_version` names the ADR 0043 definitions that produced these
numbers. Without it, "average check" in an audience and "average check" on the
dashboard are two implementations that will disagree, and a merchant noticing
that costs more credibility than the feature earns.

`birth_month_day` is a derived selector, not a copy of a birth date. ADR 0015 has
no date-of-birth field today; this ADR adds one to the customer account as a
`PERSONAL` encrypted field under ADR 0029, and the projection carries only month
and day. An encrypted column cannot be indexed and a birthday campaign is a daily
scan of the whole base, but duplicating the full date would double the erasure
surface for a fact nobody needs. `watermark_event_at` is the last order event
folded in, and a campaign records the watermark it evaluated against, so "why did
she not get it" is answerable. The projection is eventually consistent with a
five-minute staleness budget at p99; audiences needing transactional freshness
are triggers, not campaigns.

## Audiences, consent, and suppression

An audience is named typed predicates joined by AND, each from a closed
catalogue: recency band, order-count band, spend band, average-check band,
acquisition channel, registration date range, birthday window, locale, brand,
home location, holds an active benefit grant, and inclusion or exclusion of
another audience. Each is `(type, operator, value)` with a validated shape.
Building a snapshot evaluates the predicates, then subtracts — recording the
reason per exclusion:

1. Accounts not `ACTIVE`, or merged, or anonymised.
2. Missing or negative ADR 0015 consent for the purpose and channel. Absence is
   not consent.
3. An active suppression for the tenant, brand scope, and channel.
4. Accounts over the marketing frequency cap for the rolling window.
5. Accounts with no verified endpoint for the channel.

The same five run again per recipient inside ADR 0020's eligibility step.
Snapshots are immutable and store member metrics as evaluated, so a report can
say what a recipient looked like when they were chosen.

Suppression outranks consent. Reasons are `UNSUBSCRIBE`, `HARD_BOUNCE`,
`INVALID_NUMBER`, `COMPLAINT`, `OPERATOR_BLOCK`, and `PLATFORM_BLOCK`, each with
an actor, a stated reason, a scope, and an optional expiry. `PLATFORM_BLOCK` is
settable only by the control plane, and is how Qoida stops a tenant messaging
someone who complained to a regulator. Removing a suppression is capability-gated
and audited: a marketer cannot clear their own bounce list to inflate reach.

### An audience is a query over personal data, so its scope is bounded

**A predicate may reference** the columns of `marketing.customer_metrics`, the
account's lifecycle state, consent and suppression facts, and membership of
another audience. **A predicate may not reference** free text of any kind,
including review bodies and delivery notes; a raw date of birth, as opposed to the
derived `birth_month_day`; any contact value; payment instrument data; anything
from ADR 0043's behavioural telemetry, whose lawful basis and retention are still
open under ADR 0029; or the content of the legacy `search_histories` table. A
predicate over what somebody searched for is a behavioural profile, and this
catalogue is deliberately not one.

**An audience is evaluated inside the platform and nowhere else.** The predicates
run against `marketing.customer_metrics` in the tenant's own database. No audience
definition, snapshot, or member list is transmitted to an advertising platform, a
data broker, an analytics vendor, or any other third party: there is no Custom
Audience upload, no hashed-contact match file, and no tag that receives a segment
name. This is not a capacity limit a later release relaxes. A segment leaving the
platform is a disclosure of personal data to a new controller, which needs a
lawful basis, a processor agreement, and a consent purpose ADR 0015 does not carry
— none of which a marketer can create by clicking Export.

`audience.export` therefore returns metrics and pseudonymous account ids only, and
the export is itself an ADR 0027 audited event carrying the requester, the
audience version, the row count, and a stated purpose. Contact values additionally
require `customer.pii.reveal`.

## Quiet hours and frequency caps

Both numbers are ADR 0030 policy values that legal and product will confirm. They
are not left blank in the meantime, because an unset cap is an infinite cap and
the first production send would run without one. The provisional defaults below
are enforced from day one and are deliberately conservative — the failure they
accept is sending too little.

- **Quiet hours for `MARKETING`: 21:00 to 10:00 in the brand's timezone.**
  Uzbekistan is UTC+5 with no daylight saving, so a brand timezone is a fixed
  offset and a scheduled send does not shift twice a year. The morning boundary is
  later than a European default on purpose: a 09:00 marketing SMS in a market where
  the working day commonly starts at 09:00 arrives during the commute.
- **A message that becomes eligible inside the window is held to the next open
  boundary, not dropped.** Dropping loses the send silently, and a marketer reading
  a delivered count cannot distinguish a quiet-hour hold from a suppression. The
  recipient row records the deferral and the boundary it waited for.
- **Frequency cap: 3 marketing messages per rolling 7 days and 8 per rolling 30
  days, per customer per brand, counted across all channels together.** The
  projection's `marketing_messages_7d` and `marketing_messages_30d` are the
  counters, evaluated at snapshot build and again per recipient.
- **The cap is per brand, not per tenant.** Two brands under one tenant are two
  businesses to the customer, and a tenant-wide cap would let one brand's campaign
  silence another's.
- **A trigger firing counts towards the cap; a transactional message does not.** A
  transactional message is not the customer's to refuse, and counting it would let
  a busy ordering week suppress the marketing the customer did consent to.
- **A tenant override may tighten these values and may never loosen them.** The
  reasoning is in the alternatives table: the numbers also protect a sending
  reputation that is shared across tenants.

## Campaigns

```text
DRAFT -> IN_REVIEW -> APPROVED -> SCHEDULED -> SENDING -> SENT | PARTIALLY_SENT
SENDING -> PAUSED -> SENDING
SENDING -> HALTED_BUDGET | HALTED_OPERATOR
DRAFT | IN_REVIEW | APPROVED | SCHEDULED -> CANCELLED
```

Approval is ADR 0027 four-eyes whenever estimated recipients or estimated cost
exceed a threshold resolved through ADR 0030, and the approver must not be the
author. The failure being prevented: a marketer testing a template sends forty
thousand real SMS, and there is no undo for an SMS.

Cost estimation runs before approval. For SMS it renders the body in each
recipient's resolved locale and counts segments by encoding — GSM-7 at 160 single
or 153 concatenated, UCS-2 at 70 or 67 — times the tenant-configured price per
segment, reported as a range because a personalised name changes the length per
recipient. Push and Telegram carry no marginal money, so their ceiling is
optional; the recipient cap never is, because a runaway push campaign costs
nothing in cash and everything in uninstalls. The ceiling is enforced by a
reserved counter decremented under a conditional update as batches are claimed,
not by summing sent rows — summing is how two workers both conclude there is
budget left. Expansion is batched and idempotent on
`(campaign_id, snapshot_id, batch_sequence)`, with
`(campaign_id, customer_account_id)` as the ADR 0020 idempotency key per
recipient.

```text
marketing.campaigns
  id, tenant_id, brand_id, name, channel, purpose, status
  audience_id, audience_snapshot_id null, template_key, template_version
  scheduled_at null, timezone, recipient_cap, estimated_recipients
  estimated_cost_minor, cost_ceiling_minor, reserved_cost_minor, spent_cost_minor
  benefit_offer_id null, created_by, approved_by null, approval_id null
  version, timestamps

marketing.campaign_recipients            -- pk (campaign_id, customer_account_id)
  campaign_id, customer_account_id, sequence, status
  notification_id null, benefit_grant_id null, suppression_reason null
  terminal_status null, terminal_at null
```

`terminal_status` is a denormalised copy of the ADR 0020 outcome. ADR 0020 stays
the source of truth; the column exists so a campaign report does not fan out into
a hundred thousand cross-module lookups.

## Triggers

A trigger is a campaign whose audience is a rule rather than a snapshot.

| Kind | Fires on | Guard |
|---|---|---|
| `BIRTHDAY` | Daily sweep of `birth_month_day` in the brand timezone | Once per customer per year |
| `INACTIVITY` | Daily sweep of `days_since_last_order` crossing a configured band | Per-customer cooldown, default 90 days |
| `CART_ABANDONED` | An ADR 0019 cart with no order after a configured delay | Once per cart, cancelled if the cart converts first |
| `POST_ORDER_REVIEW` | Order reaches a terminal completed state | Once per order |

Late-order apology is deliberately absent. Lateness is an ADR 0013 recovery event
with a compensation decision attached, and a second compensation path in
marketing is how one late delivery gets both a refund from support and a promo
code from a trigger with nothing reconciling them. Triggers obey the same
frequency cap and quiet hours as campaigns, and each firing writes a
`trigger_firings` row whose guard key is a unique index, so the guard is enforced
by the database rather than remembered by a service.

## Per-recipient promo codes — extending ADR 0018

**A campaign references a benefit; it never defines one.** `benefit_offer_id`
points at an ADR 0018 offer that pricing owns, with its own discount type, value,
minimum basket, and stacking rules. Marketing decides *who* receives a grant and
*when*; pricing decides *what a grant is worth* and *whether it may combine with
anything else*. A marketer cannot raise a discount, relax a stacking rule, or
create an offer from the campaign editor — the campaign editor selects from
offers, and the selector is capability-gated separately from `campaign.author`.

The same boundary holds for loyalty. ADR 0046's scope settled on 2026-08-23 as
**points only, with no customer-funded cash balance**. A campaign may reference an
ADR 0046 accrual rule — a bonus multiplier, a fixed points award — and it cannot
mint points, because minting is the loyalty ledger's operation and a campaign that
could mint would be an unaudited issuance path. There is no cash-credit shape for
a campaign to reference at all, which removes the worst version of this risk:
points are non-withdrawable, non-transferable, and worthless outside the platform,
so no marketing action can issue something spendable.

ADR 0018 is extended, not corrected. Its coupon model is unchanged for shared code
words. This ADR adds a code to the benefit grant.

```text
pricing.benefit_grants  (added columns)
  code_hash null, code_encrypted null
  source_type (RECOVERY_CASE | CAMPAIGN | TRIGGER), source_id
  single_use boolean not null default true
  unique (tenant_id, code_hash) where code_hash is not null
```

| Concept | Row | Uniqueness | Limits | Issued by |
|---|---|---|---|---|
| Shared code word (`OSH2026`) | `pricing.coupon_codes` | One row, many redeemers | Total and per-customer limits on the row | A marketer, once |
| Unique per-recipient code | `pricing.benefit_grants` with a code | One row per recipient | Single use, bound to one account | A campaign or trigger, per message |

Codes are ten characters of Crockford base32 from a CSPRNG with `I`, `L`, `O`,
and `U` removed, unique by index with retry on collision. Sequential or short
codes are enumerable, and an enumerable single-use code is redeemed by whoever
guesses it first rather than by the customer it apologised to. The plaintext is
encrypted under ADR 0029 with a keyed lookup hash: an operator can verify a code
a customer reads out, and cannot list a customer's codes without
`customer.pii.reveal`. A campaign minting forty thousand codes creates forty
thousand rows that mostly expire unredeemed; that storage and sweep cost is
accepted, and ADR 0018's existing expiry job absorbs it.

## Merchandising slots

```text
marketing.slot_items
  id, tenant_id, brand_id, status, version, timestamps
  placement (HOME_CAROUSEL | STORY_RAIL | PROMO_GRID | ENTRY_MODAL)
  story_group_id null, sequence, priority, media_asset_id, media_kind
  link_target_type (NONE | CATEGORY | PRODUCT | PROMOTION | COLLECTION | EXTERNAL)
  link_target_id null, link_external_url null
  valid_from, valid_until, daily_from null, daily_until null
  channel_scope, location_scope, audience_id null
  show_after_seconds null, dismiss_policy null

marketing.slot_item_locales
  slot_item_id, locale (ru | uz-Latn | en), title, subtitle null
  body null, cta_label null, authored_by, updated_at
  translation_source (AUTHORED | ACCEPTED_SUGGESTION)
```

`link_external_url` is validated against a per-tenant host allowlist. Without it a
link target is an open redirect out of the storefront, and a banner on the home
screen is the most trusted surface a tenant has. A story rail is a group with
ordered slides; a pop-up is `ENTRY_MODAL` with a delay and a dismissal policy,
not a separate entity, because it differs from a promo card only in where it
appears. Translation assistance produces a suggestion a human accepts, recorded
as `ACCEPTED_SUGGESTION` — nothing machine-translated publishes unreviewed,
because dish names here are transliterated rather than translated and an
automatic Russian rendering of one is the kind of error a customer screenshots.
Not built, per the parity analysis: static pages, news, galleries, recipes,
tenders, and job postings.

## Attribution and referrals

```text
marketing.attribution_links
  id, tenant_id, brand_id, label, token, owner_note, status, timestamps
  channel (WEB | TELEGRAM_BOT | TELEGRAM_MINI_APP | MOBILE_APP)
  destination_type, destination_id null, valid_from, valid_until null
```

Web links render as `https://{tenant-domain}/?ref={token}`. Bot links render as a
Telegram `start` deep link, whose payload is limited to 64 URL-safe characters,
which is why the token is an opaque short id and not an encoded struct.

**Attribution is first-touch on the customer and last-touch on the order.** The
account records `acquisition_channel` and `acquisition_link_id` once, at
creation, and never again: last-touch attribution over a delivery base makes
every re-engagement SMS look like it acquired a customer who has ordered for two
years. Each order separately records the link active in its session, which is
what a campaign report actually needs. A referral is an edge — referrer, referee,
link, qualifying event — recorded whether or not a reward exists, and this
attribution mechanism itself is not built. Reward mechanics were an open
input and are resolved by ADR 0067: a tenant-authored program, paid through
ADR 0046's loyalty ledger, on the referee's first completed order.

## Reviews

One review per completed order, from the ordering account, within a window
resolved through ADR 0030. Ratings are 1 to 5 per subject across `FOOD`,
`OPERATOR`, `COURIER`, `DELIVERY_TIME`, and `PACKAGING`, plus optional tags from
a tenant-configured taxonomy and optional free text.

```text
marketing.review_tags
  id, tenant_id, brand_id null, subject, sentiment (POSITIVE | NEGATIVE)
  icon_media_asset_id null, display_order, status, localised text child rows

marketing.reviews
  id, tenant_id, brand_id, location_id, order_id, customer_account_id
  overall_rating, subject_ratings_json, free_text_encrypted null, timestamps
  board_status, assigned_to null, recovery_case_id null, resolution_note_encrypted null
```

Board states are `NEW -> IN_PROGRESS -> RESOLVED -> CLOSED`, with
`NEW | IN_PROGRESS -> DISMISSED` requiring a reason. **The board does not
compensate.** Its remedial action opens an ADR 0013 `ServiceRecoveryCase` and
stores the reference. Free text is customer-authored and may contain a phone
number or a complaint naming an employee, so it is classified `PERSONAL` and
encrypted under ADR 0029.

## Legacy engagement data

`docs/domains/legacy-profile-findings.md` section 13 settles what exists, and its
dispositions reversed once live code references were checked rather than row
counts. This module inherits those findings and adds no re-litigation of them.

- `offers`, `offer_orders`, `offer_users`, and `offer_users_used` are
  **structurally orphaned** — readable through `cart.offer` but unreachable, with
  no live reader or writer anywhere in the application. They are `RETIRE` or
  `ARCHIVE` on structural grounds and **nothing migrates from them into this
  module.** In particular, `offer_users` is not an opt-in list: a row recording
  that a customer was once eligible for an offer is not a consent decision. With
  ADR 0015's rule that absence is not consent, **the migrated base starts with no
  marketing consent at all**, and the first marketing send after cutover reaches
  only customers who have since consented. That is a real and expected drop in
  reach, and it is the correct one.
- `customer_invitations` has **six live code references** and its `RETIRE`
  disposition was reversed. It is the legacy referral mechanism and the natural
  source for this module's referral edges. Its disposition is unresolved pending a
  production re-query and is already owned by ADR 0015's open input on the
  disposition of invitations; it is inherited here rather than restated as a
  second open input.
- `search_histories` has **four live code references** and its `RETIRE`
  disposition was likewise reversed. Whatever becomes of the table, its content is
  not a predicate source — see the audience scope rules above.
- `ratings` has six live references and no rows in the development database, which
  section 13 warns proves nothing about production. It is the legacy counterpart
  of `marketing.reviews`. **Migrate the ratings; do not migrate a work queue.**
  Imported ratings land `CLOSED` with an imported marker, because loading years of
  historical complaints into a `NEW` board creates a backlog nobody can act on and
  teaches operations on day one that the board is not worth working.

## Retention

Evidence is retained because it proves a send was lawful, not because storage is
cheap. Each window below is the period in which the question it answers is still
asked.

- **Audience snapshots: 24 months** from the send, then member rows are deleted
  and the snapshot header is kept with its counts, predicate version, and
  watermark. Twenty-four months is the outer edge of a marketing-consent complaint
  reaching a regulator; past that the question is "what did you send and on what
  basis", which the header and the recipient rows answer without the membership
  list.
- **Campaign recipient rows: the life of the campaign record.** They carry the
  per-recipient consent decision and suppression reason that prove lawfulness, and
  they hold no contact value by construction, so they are pseudonymous references
  rather than a growing store of personal data.
- **Suppressions: indefinite while active.** A suppression records a person's
  refusal, and deleting it re-enables what they refused; `UNSUBSCRIBE` never
  expires on its own. `HARD_BOUNCE` and `INVALID_NUMBER` expire after **12
  months**, because numbers are reassigned in this market and a permanent block on
  a recycled number silences a different, willing person.
- **Trigger firing rows: 13 months** — one month past the longest guard window,
  which is the birthday's year.
- **Coded benefit grants: 12 months after expiry**, then the code ciphertext and
  lookup hash are deleted and the accounting row remains. After expiry the code
  cannot be redeemed, and the only surviving question is financial.
- **Review free text: the life of the review**, erased on an ADR 0029 erasure
  request and replaced by a tombstone that keeps the numeric rating, so a
  location's average does not move when one customer exercises a right.
- **An ADR 0029 erasure** removes the customer's projection row, their snapshot
  membership, and their review text, and leaves campaign counts and spend intact.
  An aggregate that no longer identifies anyone is not erased; reversing a finance
  number to honour a privacy request is a different kind of wrong.

## APIs, events, and testing

```text
POST /api/v1/operations/marketing/audiences/{audienceId}/estimate
POST /api/v1/operations/marketing/audiences/{audienceId}/snapshots
POST /api/v1/operations/marketing/campaigns/{campaignId}/{submit|approve|halt}
GET  /api/v1/operations/marketing/campaigns/{campaignId}/recipients
POST /api/v1/operations/marketing/suppressions
POST /api/v1/operations/marketing/reviews/{reviewId}/transition
GET  /api/v1/storefront/merchandising/slots?placement=&locale=
POST /api/v1/storefront/orders/{orderId}/review
POST /api/v1/customer/me/marketing-unsubscribe
```

Capabilities follow ADR 0025: `campaign.author`, `campaign.approve`,
`audience.read`, `audience.export`, `suppression.manage`, `review.resolve`,
`merchandising.publish`. `audience.export` returns metrics and pseudonymous ids;
contact values additionally require `customer.pii.reveal` with a stated purpose
and an audit record, because an unrestricted download of the customer base is how
a tenant's list ends up on a competitor's desk.

Events carry no contact values and no rendered bodies: `AudienceSnapshotBuilt`,
`CampaignApproved`, `CampaignSendStarted`, `CampaignHalted`,
`CampaignSendCompleted`, `MarketingSuppressionRecorded`, `TriggerFired`,
`CodedBenefitGranted`, `ReviewSubmitted`, `ReviewResolved`.

Tests that must exist:

- Consent revoked between snapshot build and send suppresses that recipient and
  records why; a suppression outranks a positive consent decision.
- Concurrent batch workers cannot exceed the cost ceiling or the recipient cap,
  and a replayed batch produces no second message.
- The same template estimates different segment counts for a ru and a uz-Latn
  recipient.
- Code minting survives an induced collision and codes are not predictable.
- The frequency cap holds when a birthday trigger and a campaign hit the same
  customer on the same day.
- A slot item pointing at a non-allowlisted host is rejected at publish, not at
  render.
- Cross-tenant reads of audiences, snapshots, suppressions, and reviews fail, and
  the nightly sweep reports projection drift rather than rewriting metrics.
- A message eligible inside quiet hours is deferred to the boundary and then
  delivered, not dropped, and the recipient row records the deferral.
- The cross-channel cap counts one SMS, one push, and one Telegram message as
  three against the same customer.
- A tenant override loosening the frequency cap is rejected; one tightening it is
  applied.
- Snapshot membership past the retention window is deleted while the header,
  counts, and per-recipient reasons survive.
- An ADR 0029 erasure removes the projection row, snapshot membership, and review
  free text, leaves the numeric rating as a tombstone, and leaves campaign
  recipient counts and spend unchanged.
- A campaign cannot be saved with benefit terms of its own, only with a reference
  to an existing ADR 0018 offer or ADR 0046 accrual rule.

## Rollout and rollback

Build and backfill the projection, then reconcile its aggregates against ADR
0043's metric layer for the same period and explain every difference before a
marketer sees a slider. Ship audiences and estimation with no send path. Ship
campaigns against ADR 0020's fake provider with snapshots and receipts, sending
nothing. Send to an internal seed audience, then one real campaign capped low,
with the ceiling set deliberately below the estimate to prove the halt works.
Enable triggers one kind at a time, birthday first: lowest volume, least
ambiguous. Merchandising slots and reviews are independent of the send path and
can ship in parallel. Rollback stops at the campaign layer — halting campaigns
and disabling triggers leaves notifications, pricing, and the storefront
untouched, and snapshots and receipts are retained as evidence rather than
deleted to tidy a failed send.

## Open inputs and why none is structural

**The Telegram question is answered, not deferred.** When this record was written
on 2026-08-21 it claimed that ADR 0035 "places four web surfaces and does not place
this one". ADR 0035 was rewritten on 2026-08-22 and now places it: the storefront
runs in two hosts from one codebase, standalone web and Telegram Mini App, and the
Mini App is "in scope for the storefront now", explicitly not a later channel. A
Mini App implies a bot — BotFather points at the Mini App URL, and the platform
holds the bot token to verify the `initData` HMAC server-side. So the tenant-facing
bot Qoida operates exists, the Mini App placement has a host, and the bot deep link
has a target. The premise of the open input is gone and the input is withdrawn.

What remains is sequencing, not structure. **Telegram broadcast messaging is not
enabled in the first slice**, and turning it on is an ADR 0020 channel activation
against this model rather than a change to it: `TELEGRAM_BOT` and
`TELEGRAM_MINI_APP` are already attribution channels, the cost model already states
that Telegram carries no marginal money so its ceiling is optional while its
recipient cap is not, a user blocking the bot arrives as a suppression under the
existing reason taxonomy, and marketing holds no chat id because ADR 0020 owns
endpoints. Activation costs an enum value and a provider binding. This is the same
"designed for, not built" posture ADR 0013 and ADR 0038 take towards Telegram
payments, for the same reason.

| Input | Owner | Why it does not change the structure |
|---|---|---|
| Marketing frequency cap and quiet-hours values | product, legal | Values resolved through ADR 0030 against ADR 0020's `quiet_hours` preference column. Provisional defaults are set above and enforced from day one, so the mechanism ships complete; counsel changes numbers, not tables |
| Referral reward mechanics — who is rewarded, on which qualifying event, amount, cap, expiry | product, finance | **Resolved 2026-09-05 by [ADR 0067](../partial/0067-referral-program-rewards-through-the-loyalty-ledger.md)**, which took the accrual shape this row names: a tenant-authored program (both sides rewarded, or the referrer only) paying through ADR 0046's loyalty ledger. The referral edge itself — recorded regardless of a reward, per this ADR's own decision above — and attribution links remain this ADR's own unbuilt work; ADR 0067 owns only the reward |
| Marketing SMS sender of record and price per segment | finance, legal | The campaign already carries `estimated_cost_minor`, `cost_ceiling_minor`, `reserved_cost_minor`, and `spent_cost_minor` against a tenant-configured price per segment. If campaign spend later becomes an ADR 0021 billable unit, ADR 0021 meters `spent_cost_minor`; that is a meter definition against an existing column |
| Signed treatment of cancelled and refunded orders in RFM, and the business-day boundary | finance, through ADR 0043 | Not this ADR's to answer. ADR 0043 is Accepted, its business-day boundary is implemented, and its registry ships version 1 as provisional. The projection carries `order_count` beside `completed_order_count` and `gross_spend_minor` beside `net_spend_minor`, and stamps `metric_definition_version`; a registry revision restates the projection rather than reshaping it |

## Consequences

### Positive

- A send is reproducible: who was targeted, what they looked like at the time,
  what was suppressed and why, and what the provider said, all against one row.
- Cost is known before approval and cannot be exceeded during the send, in the
  one channel where the mistake is unrecoverable.
- Consent enforcement is not a marketer's responsibility. The platform applies it
  twice from ADR 0015's authoritative record.
- The promo-code ambiguity is closed with two named concepts rather than one
  overloaded flag.
- The storefront's most trusted surfaces accept typed data only, so a careless or
  compromised marketing account cannot inject script or an open redirect into a
  customer's session.
- The record can be built now. Every remaining open input changes a number, a
  meter definition, or a reward amount; none of them changes a table.

### Negative

- The projection is another read model to backfill, maintain, reconcile, and
  explain when it disagrees with a report. Drift against ADR 0043 will be raised
  at a demo.
- Snapshots and recipient rows are the largest tables this module owns — a
  monthly campaign to a six-figure base is millions of rows a year — and the
  retention schedule above prunes them on a stated window rather than aggressively,
  because they are the evidence.
- That schedule now deletes things. Snapshot membership is gone at 24 months, so a
  dispute raised later is answered with counts, predicate versions, and
  per-recipient reasons but not with a list of who was in the segment. Somebody
  will eventually want that list, and the answer will be that it was deliberately
  not kept.
- Quiet hours defer rather than drop, so a campaign released at 20:50 finishes the
  following morning and its delivery report spans two days. A marketer checking the
  same evening sees a partial send that is not a failure.
- The frequency cap counts across channels, so enabling Telegram broadcast will not
  add reach on top of SMS — it substitutes for it. Whoever asks for Telegram
  expecting incremental volume will be disappointed by design.
- The provisional quiet-hour and cap values are enforced from day one, which means
  the first production behaviour follows a policy legal has not confirmed. The
  direction is conservative — too few messages rather than too many — but the
  numbers were still chosen by engineering.
- Refusing third-party audience upload closes off the paid-acquisition integrations
  that tenants arriving from an agency background will expect, and "export the
  metrics and pseudonymous ids" does not satisfy that request.
- The migrated base carries no marketing consent, because no legacy table records
  one. The first post-cutover campaign will reach a small fraction of the customers
  a merchant believes they have, and that conversation is owed to them before
  cutover rather than after the first send.
- The closed predicate catalogue will be too small for some request within
  months, and extending it is a schema and code change rather than configuration:
  the same trade ADR 0018 made, and the same complaint it will attract.
- Two-stage consent evaluation means the reach shown at approval is an upper
  bound and the delivered count is always lower, which reads as a bug to anyone
  who has not read this document.
- A suppression list is a new way to wrongly silence a real customer, and the fix
  requires a capability most marketers will not hold.
- The review board is a second work queue operations must actually work. An
  unworked board is worse than none, because the customer sees that reviews are
  collected and infers they are read.
- Refusing raw HTML means some tenant request — a formatted terms page, a
  seasonal landing page — has no answer inside Qoida at all.

### Accepted trade-offs

- Snapshot-based sending costs storage and makes very large audiences slower to
  prepare, in exchange for approvable cost and auditable targeting. Free channels
  relax this deliberately.
- Marketing duplicates `terminal_status` from ADR 0020 and `birth_month_day` from
  ADR 0015. Both are narrow, one-directional, and stated; neither becomes a
  second source of truth.
- The competitor's client-side funnel triggers are not replicated. Some
  re-engagement volume is lost rather than run an ungoverned telemetry pipeline
  producing messages Qoida cannot justify afterwards.
- Telegram broadcast is designed for and not built, matching ADR 0013 and ADR 0038
  on Telegram payments. The cost is an unused enum value and an unbound provider;
  the alternative was holding a settled design hostage to a sequencing question.
- Hard bounces expire at 12 months, so a number that is still bad will occasionally
  be messaged again. With numbers reassigned in this market, the opposite error —
  permanently silencing a new and willing owner — is the more likely one.

## Implementation checklist

- [x] Build SMS and push first. Telegram broadcast is an ADR 0020 channel
      activation against this model, not a change to it, and follows the Mini App
      that ADR 0035 places. `MarketingChannel` declares all four and marks which
      carry marginal money; `MESSAGING_APP` has no provider binding, as designed.
- [x] Configure quiet hours, both frequency caps, and the tighten-only override
      rule as ADR 0030 policy with the provisional values above, and flag them for
      counsel confirmation before the first production send. `EngagementPolicy`
      holds the platform defaults; `marketing.engagement_policies` holds a brand's
      override, and its CHECKs bound every column in the tightening direction so a
      loosening override is refused by the database as well as by the service.
      **The numbers still need counsel's confirmation before the first production
      send.**
- [~] Add the `marketing` module, schema, and Flyway migration — done, as
      `V0043__create_marketing_audiences_campaigns_and_suppression.sql`. Date of
      birth on the ADR 0015 account and the coded-grant columns on
      `pricing.benefit_grants` are **not** added: both are other modules' tables,
      and `pricing.benefit_grants` does not exist in this database yet. The
      projection carries `birth_month_day` and nothing writes it until ADR 0015
      lands the encrypted date.
- [x] Build the metric projection, backfill, and reconciliation sweep.
      `CustomerMetricProjectionService` observes drift and then recomputes, in that
      order, and records what it found rather than silently correcting it. The
      incremental fold from order terminal events through the ADR 0005 inbox is
      **not** built: the recompute sweep is the whole maintenance path today, which
      means the five-minute staleness budget is a sweep interval rather than a
      guarantee.
- [x] Implement the predicate catalogue, estimation, and snapshot build. Ten of the
      thirteen predicates the record names are built. Home location has no
      projection column, an active benefit grant has no table, and brand is the
      audience's own scope; audience membership resolves against the referenced
      audience's latest completed snapshot rather than recursing, which makes a
      cycle unrepresentable.
- [x] Implement suppression, frequency capping, and two-stage eligibility. The five
      subtractions live in one class and run twice, at snapshot build and again per
      recipient at send; every refusal is written down with its reason.
- [x] Implement the campaign state machine, approval, reserved-cost ceiling, and
      batched idempotent expansion.
- [x] Implement segment-aware cost estimation per locale and encoding.
- [ ] Implement the four triggers with unique-index guards, and coded grant
      minting, verification, and expiry. **Not built.** The coded grant needs
      columns on a `pricing.benefit_grants` table that does not exist yet, and a
      trigger firing table with no producer would read as a broken projection.
- [ ] Implement slots with the link-target allowlist, attribution links and
      referral edges, and reviews with the ADR 0013 handoff. **Not built.** All
      three are independent of the send path and can ship in parallel, as the
      rollout section says.
- [~] Implement the retention jobs and the ADR 0029 erasure path that preserves
      campaign aggregates. The operations exist and are tested — snapshot
      membership purges while the header, its counts, and the per-recipient reasons
      survive, and an erasure removes the projection row and the membership while
      leaving campaign counts and spend intact — but **nothing schedules them yet**.
- [ ] Migrate legacy `ratings` as `CLOSED` reviews; migrate nothing from the
      `offer_*` tables, and confirm with each merchant before cutover that the
      migrated base carries no marketing consent. **Not built**, and it follows
      reviews. The second half already holds by construction: a test asserts that a
      customer with no consent decision is excluded as `CONSENT_WITHHELD`.
- [x] Add capabilities, audit entries, and the tests above. `audience.read`,
      `audience.export`, `campaign.author`, `campaign.approve`, and
      `suppression.manage` are added; `review.resolve` and `merchandising.publish`
      wait for the features they gate. Of the tests this record names, the ones
      covering the unbuilt features are the ones not written.

## Exit criteria

A marketer can define an audience, see a truthful recipient count and a
segment-accurate cost before approval, obtain a second signature, send, and
afterwards show per-recipient outcomes including every suppression and its
reason. A customer who unsubscribes after approval does not receive the campaign.
A unique promo code minted for one customer cannot be redeemed by another or
guessed by anyone. No tenant-authored markup reaches a storefront. And a review
left on a late order becomes a service-recovery case, not a second discount.

A message that would land at 22:00 is delivered the next morning rather than
dropped, and a customer already at the weekly cap receives nothing further from
that brand no matter which channel the next campaign chooses. No audience
definition, snapshot, or member list leaves the platform. A campaign editor offers
a list of existing benefits and no field in which to invent one. And every table
this module owns has a stated retention window that a job actually enforces.
