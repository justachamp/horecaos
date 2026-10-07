# ADR 0167: Behavioural telemetry: lawful basis and the funnel

- Decision status: Proposed — proposed by Claude (batch 19); the platform owner decides
- Implementation status: Not started — nothing behavioural is collected, and the part
  of the funnel that does not need collecting is not assembled. ADR 0043 decided that
  behavioural telemetry "ships from day one, on its own topic, pseudonymous"
  (`analytics.events`: `session_started`, `customer_registered`, `cart_item_added`,
  `checkout_started`, `order_placed`, durable copy `reporting.fact_behaviour`); neither
  exists, there is no topic, no emitter in the storefronts, the bot or the app, and no
  migration. What does exist is every server-side fact the funnel's last three stages
  could be counted from. `customer.customer_accounts` has `created_at` and, since V0295,
  `origin` (`SELF_SERVICE`, `OPERATOR`, `IMPORT`, `AGGREGATOR`, `MIGRATION`);
  `customer.brand_profiles` has `created_at` per brand; `ordering.carts` and
  `ordering.cart_lines` carry `created_at`, an owner (an account, or `guest_reference_hash`,
  a keyed hash and never the reference), and are deleted by `CartRetentionSweeper` 90 days
  after they were last touched (ADR 0092); `ordering.checkout_attempts` (V0022) records
  each checkout with its outcome; `reporting.fact_order` carries `customer_subject_hash`,
  `is_first_order` and `channel_code`. Attribution has a half: `marketing.attribution_links`
  (V0309, `AttributionLinkService`, `AttributionLinkController`) mints `?ref=` and
  Telegram deep links with an anonymous `click_count`, but its click endpoint needs the
  staff capability `marketing-link.manage` and no surface that serves a link calls it, and
  neither `customer.customer_accounts` nor `ordering.orders` has a column for which link
  brought the customer or the order (V0309's own header says so; `marketing.customer_metrics`
  has `acquisition_channel` and `acquisition_link_id` columns that nothing writes). The
  console's customer analytics page (`customer-analytics-page.ts`) names the funnel and
  the acquisition chart as row `7.6c` and does not attempt them. Two things sharing words
  with this record are not it: the `telemetry` module is courier GPS (ADR 0045), and the
  storefront's `view_item` and `add_to_cart` events (ADR 0106, row `10.8e`) go into the
  *tenant's own* Google dataLayer under the tenant's own basis, never to HorecaOS.
- Date proposed: 2026-10-07
- Date decided: —
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0015, ADR 0025, ADR 0027, ADR 0029, ADR 0030, ADR 0031, ADR 0032,
  ADR 0033, ADR 0034, ADR 0043, ADR 0044, ADR 0068, ADR 0092, ADR 0106
- Supersedes / Superseded by: — (amends ADR 0043 without editing it. It reopens exactly
  one decision bullet, "Behavioural telemetry ships from day one, on its own topic,
  pseudonymous", and one rejected row, «Emit behavioural events only once the funnel
  dashboard is scheduled», whose revisit is "Never". It keeps the argument that history
  cannot be backfilled and answers it differently: for everything the platform already
  holds as a fact, history *is* recoverable; for what it does not hold, nothing is
  collected until counsel confirms a basis. Everything else in ADR 0043, the star schema,
  the metric registry and the close job, is untouched; ADR 0044's attribution design is
  built in two tiers rather than one)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written; the ones that name a person other than the owner stay with
  that person and the work they block is marked.
  - **The lawful basis, and the retention, for client-side behavioural telemetry**
    (visits, page views, what a person looked at) (legal counsel, engaged by the
    platform owner). Proposed default: none is collected. The conservative reading, that
    no basis other than a consent a visitor would actually give is available for
    measuring behaviour, is the one this record designs against. Counsel's answer can
    only widen what is collected (Decision 8); it cannot narrow what this record
    already does. Blocks: only the visits stage and anything that needs a session.
  - **Whether recording which attribution link brought an account, and which brought an
    order, needs the customer's consent** (legal counsel). Proposed default: not
    recorded per person (tier 1 only). Per-person attribution is specified (Decision 4)
    and built only after counsel confirms either a basis or a consent purpose, at which
    point it is enabled per brand. Blocks: only the per-link customer and order counts.
  - **The wording of the notice** (legal counsel, with the platform owner; the text
    lives in each tenant's terms of service, ADR 0068). Proposed default: "HorecaOS
    counts registrations, baskets, checkouts and orders, in total and never by person,
    so the restaurant can see how it is doing. It does not record what you look at or
    which pages you visit." Blocks: nothing.
  - **Retention of the funnel and registration counts** (finance and legal counsel).
    Proposed default: 400 days, the figure ADR 0043 proposed for behavioural history (a
    year-over-year comparison plus a month), as ADR 0030 policy `reporting.funnel_retention_days`,
    deleting by business date. The rows hold no personal data, so the period is a
    judgement about usefulness and not a legal minimum. Blocks: nothing.
  - **Whether the tenant's own GA4 property should ever feed HorecaOS's funnel**
    (Ayubkhon Abbosov, as product owner). Proposed default: no. It is the tenant's data
    under the tenant's contract with Google, it would need per-tenant OAuth, and a tenant
    without GA4 would have no funnel. Blocks: nothing.
  - **When the first tenant goes live, relative to the cart retention window**
    (Ayubkhon Abbosov). Proposed default: the daily capture job (Decision 3) ships before
    the first tenant's first order, because a cart is deleted 90 days after it was last
    touched and a basket count not captured by then is gone. Blocks: nothing; it is a
    sequencing rule.

**To accept as written:** say "accept 0167". Every open input above is then closed on
its proposed default.

## Context

Gap-map row `7.6c` (IA 7.6, tier 2) is `NOT BUILT`: "Customer analytics — acquisition-source
chart and the product funnel (visits → registrations → cart adds → orders)". Its "What is
missing" is "There is no visibility into anything before the order — visits, registrations
and cart adds are recorded nowhere, so the funnel will start empty on the day it is finally
built, and acquisition source is unknown." Its "Blocked by" is ADR 0043's open input: "legal
must confirm lawful basis and retention for behavioural telemetry (running on a provisional
default today, so a risk rather than a hard stop)". The statistics spec (`operations-spec/statistics.md`
§2.6, Band D) calls the funnel "the single item in this whole spec that cannot be backfilled"
and gives the unbuilt state a line to show: «Данные воронки собираются с момента включения
телеметрии — их нельзя восстановить задним числом.»

**The argument for shipping telemetry first was sound and rested on one assumption.** ADR 0043
reasoned that emitting events is cheap, deciding later means the funnel starts empty, and
therefore the emitters should ship before any consumer, on a retention that "cannot reach
production unconfirmed". The assumption was that the events could be collected on a basis
someone would confirm before production. That is the input still open, and the default
retention is the one thing in the record that is not a design but a guess. A platform whose
collection starts before its basis is confirmed has collected a year of data it may not be
entitled to hold, and cannot un-collect it; one that waits has lost a stage of a chart. The
first loss is not recoverable and the second is, once counsel answers. That asymmetry decides
the default.

**But most of what the funnel needs is not telemetry.** Three of its four stages are facts
about a person's transactions with the restaurant, held already for the purpose of serving
them:

| Stage | Where the fact is today | New collection? |
|---|---|---|
| Visits (Входы) | nowhere | yes, and it is the one that needs a basis |
| Registered | `customer.customer_accounts.created_at`, `origin`; `customer.brand_profiles.created_at` | no |
| Started a basket | `ordering.carts` / `cart_lines.created_at`, kept 90 days after last touch | no, but perishable |
| Reached checkout | `ordering.checkout_attempts.created_at`, with `outcome_code` | no |
| Ordered | `reporting.fact_order` | no |

A cart is a session proxy: it exists because a person (or a device, as a keyed hash) put
something in it, and it is the record the restaurant needs in order to take the order. The
count of such carts per day is a count of facts, not a log of behaviour. **The only part of
the funnel that cannot be backfilled from facts is the basket count, and only because the
sweeper deletes carts**; ADR 0092's 90 days were set for privacy, correctly, and the funnel
has to count before the deletion rather than ask the sweeper to wait.

**Acquisition source is half-built and the unbuilt half is the legally interesting one.**
ADR 0044 designed first-touch attribution on the account and last-touch on the order, from a
`?ref=` token. What exists is the link and an anonymous click counter. The step that would
make the chart answer "how many customers did this influencer bring" is recording the link
on the account and the order, which attaches a fact about how a person arrived to that person.
That is exactly what counsel has not yet been asked.

**What a tenant can do on its own.** ADR 0106 lets a tenant install its own GTM, GA4 and Search
Console identifiers, and the storefront fires the versioned GA4 ecommerce events into the
tenant's own property (row `10.8e`). That is the tenant's measurement under its own basis and
contract, and it is the answer to "I want to see visits" that needs no HorecaOS decision. The
gap-map note for it says it must not "grow into first-party behavioural telemetry", which is
this record's boundary from the other side.

## Decision

**Collect only server-side facts the platform already holds; build the funnel from them as
daily counts that contain no subject; do not collect visits or any client-side behaviour
until counsel confirms a basis; and build per-person acquisition attribution only after the
same confirmation.**

1. **No client telemetry.** No `analytics.events` topic, no `session_started`, no
   `fact_behaviour`, no emitter in the storefronts, the Telegram bot or the app, and no
   session identifier minted for measurement. ADR 0043's bullet is reopened to this extent
   and its rationale (history cannot be backfilled) is answered by Decision 3.
2. **The funnel is four stages, one of them honestly absent.** *Visits* (not measured),
   *Registered*, *Started a basket*, *Reached checkout*, *Ordered*, shown per business day,
   per brand and per channel. The visits stage renders as "not measured", with the reason,
   and never as zero; no ratio is drawn against it. The stages are counts of distinct events
   on that day, not a cohort: a ratio between two of them is a rate of activity and not a
   probability that a given person converts, and the screen says so.
3. **Counts are captured at day close, from the live tables, before the sweeper deletes them.**
   The ADR 0043 close job (`DayCloseService`) writes one row per business day, brand and
   channel from `ordering.carts`/`cart_lines` (distinct carts whose first line was added on
   that business date), `ordering.checkout_attempts` (distinct carts that reached checkout),
   `customer.brand_profiles` (new profiles) and `reporting.fact_order`. The rows hold counts
   only: no account id, no `guest_reference_hash`, no `customer_subject_hash`, no session. A
   count that cannot be recorded because the carts are already past retention is `NULL`,
   meaning "not recorded", and never 0. The recut re-derives a day while the tables still
   hold it and otherwise leaves it as it was.
4. **Acquisition source is built in two tiers.**
   - *Tier 1, now, no new personal data:* an anonymous click series per attribution link
     (a daily count, fed by a public, tokenised, rate-limited storefront endpoint), accounts
     registered per day by `origin`, and orders per day by sales channel. The chart shows
     the three beside each other and says it does not attribute a customer to a link.
   - *Tier 2, specified and not built until counsel answers:* first-touch
     `acquisition_link_id` on the account, written once at creation, and last-touch
     `attribution_link_id` on the order, both from a token the storefront passes in the same
     request and only if it names an `ACTIVE` link of the same brand. It is never written for an
     `OPERATOR`, `IMPORT`, `AGGREGATOR` or `MIGRATION` account. It is enabled per brand by a
     policy once the input closes, and nulled on an ADR 0029 erasure.
5. **A click is a count, not an event.** ADR 0044's rule stands: "a count only, never an
   event log naming who clicked". The endpoint records no address, no user agent and no
   cookie; the rate limit holds a keyed hash of the client address in the ADR 0033 limiter
   for sixty seconds and writes nothing to the database.
6. **Everything stays in the country, on the platform.** No third-party analytics processor
   is added to ADR 0034's register, no tag leaves the storefront for this purpose, and no
   count is exported to a service outside the platform.
7. **Retention.** The funnel, registration and click-series rows are kept 400 days by
   business date, as policy, and swept by a job that starts report-only like every
   destructive retention job (ADR 0029). They contain no personal data, so an erasure
   request does not touch them. The per-person facts they are counted from keep their own
   existing retention: carts 90 days after last touch (ADR 0092), accounts per ADR 0029.
8. **If counsel confirms a basis**, the next record adds, and only for what the basis
   covers: the named client events, a measurement identifier that is not the cart or the
   account, `SubjectPseudonym`'s keyed hash as the subject (ADR 0043's own design), a
   consent purpose registered in `customer.consent_decisions` if the basis is consent, and
   the retention counsel names. This record's tables stay and the visits stage fills in.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Ship ADR 0043's client telemetry now, on its provisional 400-day default | Starts collecting before the basis is confirmed, on a retention that is a guess, and cannot un-collect. The asymmetry is the whole argument: a chart stage is recoverable once counsel answers, a year of unlawfully held behaviour is not | Counsel confirms in writing a basis for a named purpose, an event list and a period, and a notice exists (Decision 8) |
| Collect nothing and leave `7.6c` blocked until counsel answers | Costs no legal risk and loses the basket stage for good: carts are deleted 90 days after they were last touched, so every week of waiting is a week of basket counts that never existed. The registered, checkout and ordered stages are facts that cost nothing to count | Never; the daily capture is the cheap half and it cannot wait |
| Count visits from the reverse proxy's access log | An address is personal data and the logs are held under ADR 0029's "observability, 90 days, PII-free by construction"; a log that is a measurement source is not PII-free | Never; the answer is a basis, not a cleverer source |
| A first-party cookie issued by the API on first request to count distinct visitors | A persistent identifier on a person who has done nothing but arrive, which is the collection that needs a basis most | Counsel confirms a basis for exactly this, and the consent or notice that goes with it |
| Read the funnel from the tenant's GA4 property through its Data API | Needs a per-tenant Google OAuth grant, puts the data at a processor outside the country, is sampled and quota-limited, and a tenant without GA4 has no funnel. It is also the tenant's data under the tenant's contract | Counsel rules out first-party measurement permanently *and* most tenants run GA4 |
| A third-party product-analytics service | A new processor on ADR 0034's register and a transfer of behaviour out of the country, to answer a question the platform can count itself | ADR 0034 admits such a processor for an anonymous aggregate class |
| Per-person attribution now (tier 2 on by default) | Attaches how a person arrived to the person, before anyone has been asked whether that needs consent. The price of waiting is the per-link customer count, and tier 1 shows the clicks and the registrations beside it | Counsel confirms a basis or a consent purpose (Decision 4) |
| Derive the funnel from domain events, adding events for cart changes | Cart edits are not domain events today; publishing them adds a stream of per-person facts with a subject, in a topic that outlives the cart, to avoid reading a table at close | Never as a substitute for reading the live table; an event stream is the shape of Decision 8's world, not this one |
| Store the funnel with a session or subject key so cohorts can be drawn | A cohort needs a subject, which makes the table personal data and puts erasure on it. Cohort views already exist from `fact_order` (row `7.6a`) | The visits stage is collected under a basis and a funnel cohort is asked for |

## Consequences

### Positive

- The funnel exists from the first order, with real data for four of five stages and an
  honest label on the fifth.
- Nothing is collected that needs a basis nobody has confirmed, so there is nothing to
  un-collect and nothing for an erasure request to chase.
- No new topic, no new processor, no cross-border flow, no consent screen.
- The perishable fact, the basket count, is captured before ADR 0092's sweeper deletes it.
- The tenant keeps its own visit measurement through the install it already has (ADR 0106).

### Negative

- **The chart does not start at the top of the funnel.** A restaurant that wants visits
  gets "not measured", and the first stage it can read is registrations. Delever's operators
  see more.
- The stages are not a conversion funnel in the strict sense: day-by-day counts of
  distinct events cannot say that the person who registered on Monday is the one who ordered
  on Thursday. The screen has to say so and the owner has to accept a weaker chart.
- Tier 1 acquisition shows how many clicked and how many registered, and cannot say which
  customers an influencer brought.
- A guest and a signed-in customer are both a cart, so basket counts mix them; the hashed
  guest reference is deliberately not turned into a distinct-visitor count.
- The capture job is one more thing the close must get right: a failed close leaves a day
  `NULL`, which must be a visible gap and not a zero.

### Accepted trade-offs

- A year of funnel history for visits is given up, not deferred: if counsel later confirms
  a basis, the visits stage starts that day, which is the cost ADR 0043 warned of and this
  record chooses on purpose.
- Tier 2 is designed and left unbuilt, against ADR 0046's own distaste for dormant
  features. It is a specification and not a switch: no column, no endpoint and no code
  exists for it until it is built.
- Capturing at close means a cart that is converted the next morning was counted as a basket
  yesterday and as an order today; that is correct for a daily activity count and wrong for
  a conversion rate, and the chart says which it is.

## Specification

### Physical model

All rows carry `tenant_id`; keys include it; the application role holds `SELECT`, `INSERT`,
`UPDATE` and `DELETE` (the retention job deletes) and `horecaos_reporting_read` holds `SELECT`,
as `reporting.agg_branch_day` does. Unpartitioned: a row per day, brand and channel is small.

```text
reporting.fact_funnel_day
  tenant_id, brand_id, business_date, channel_code
  basket_started int null          -- distinct carts whose first line was added that date
  checkout_started int null        -- distinct carts with a checkout attempt that date
  ordered int null                 -- orders placed that date (from fact_order)
  boundary_version, computed_at
  primary key (tenant_id, brand_id, business_date, channel_code)
  -- null = not recorded; the carts were already past retention. Never 0.

reporting.fact_registration_day
  tenant_id, brand_id, business_date, origin      -- SELF_SERVICE | OPERATOR | IMPORT | AGGREGATOR | MIGRATION
  registered int not null          -- new brand profiles that date
  boundary_version, computed_at
  primary key (tenant_id, brand_id, business_date, origin)

marketing.attribution_link_clicks_day
  tenant_id, link_id, day date, clicks int not null
  primary key (tenant_id, link_id, day)
  -- upserted by the click endpoint; marketing.attribution_links.click_count remains the total
```

No table in this record has a subject column, a session column or a hash. A structural test
asserts it (below). `business_date` follows the tenant's `reporting.business_day_start` policy
and `boundary_version`, exactly as `fact_order` does (ADR 0043).

*Tier 2, not built:*

```text
customer.customer_accounts   + acquisition_link_id uuid null, acquisition_channel varchar(32) null
                             -- first touch, written once, never for OPERATOR/IMPORT/AGGREGATOR/MIGRATION
ordering.orders              + attribution_link_id uuid null
                             -- last touch in the session; composite FK (tenant_id, id) to marketing.attribution_links
policy marketing.attribution_recording   PolicyKey<AttributionRecordingPolicy>
                             settable BRAND and above, default OFF
```

### APIs (ADR 0031) and capabilities (ADR 0025)

```text
GET  /api/v1/operations/tenants/{tenantId}/reporting/customer-funnel?from&to&brandId&channel
       reporting.read      stages with recorded flags; visits: {measured:false, reason:"NOT_COLLECTED"}
GET  /api/v1/operations/tenants/{tenantId}/reporting/acquisition-sources?from&to&brandId
       reporting.read      registrations by origin, clicks by link (label), orders by channel
POST /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/attribution-links/{token}/clicks
       public; same posture as StorefrontAnalyticsConfigController; 202; non-idempotent by design
```

The two reads reuse `reporting.read` and the existing brand scoping of the reporting endpoints
(the metric registry names `funnel.stage.v1` and `acquisition.source.v1` with their grain and
definition, so a number on this screen is defined once, ADR 0043). The click endpoint declares no
capability because there is nobody to authorise, and is rate limited by the ADR 0033 limiter,
keyed by token and a keyed hash of the client address held for sixty seconds in memory. An unknown,
archived or out-of-window token answers 202 and counts nothing, so the endpoint cannot be used to
probe which tokens exist. The existing staff `POST .../attribution-links/{linkId}/clicks` is
removed from the contract, because a staff person incrementing a counter by hand is a way to
make the chart wrong.

### Events, PII, audit, policy

- Events (ADR 0032): none. The close job writes the rows directly; no outbox event and no topic is
  added. Decision 8's `analytics.events` is a later record's, and its catalogue entry would be
  made then.
- PII (ADR 0029): no personal data is stored by tier 1. `acquisition_link_id`, if built, is
  `INTERNAL`, as `marketing.customer_metrics.acquisition_link_id` already is, because it is an
  opaque code the tenant minted; it is nulled with the account on erasure. No count, no click and
  no address reaches a log, metric label or event.
- Audit (ADR 0027): a change to `marketing.attribution_recording` and to
  `reporting.funnel_retention_days` is audited as any policy write is. Reading a count is not
  audited; it contains no person.
- Policy (ADR 0030): `reporting.funnel_retention_days` (settable `PLATFORM`, default 400, provisional
  flag set so the ADR 0029 startup guard keeps asking), and the tier 2 policy above.

### Close job and backfill

`DayCloseService` gains one step after the order facts: derive the day's funnel and registration
rows inside the close's own transaction, idempotent on the primary key, so the existing recut can
repeat it. A one-time backfill on deployment reads the carts, checkout attempts and profiles still
in the tables for each business date still held and writes the rows; dates older than the cart
retention window get `basket_started` and `checkout_started` as `NULL` and the registration and
ordered counts from the tables that keep them. Counting `ordering.carts` for the funnel reads a
module table from `reporting`, which ADR 0023 forbids a report doing: the close job, like the
existing facts, reads through a small published read port declared by `ordering` (a count by
business date and channel, carrying no account and no hash), the shape `AbandonedCartDirectory`
already has.

### Observability

A gauge of days with a `NULL` stage beyond the first week, and the close run's existing success
counter; a day with no funnel row at all is an alert, because it is the one loss that cannot be
repaired once the carts are gone. Labels are tenant-free and subject-free.

### Testing

- A structural test over the three new tables asserts no column whose name contains `account`,
  `customer`, `subject`, `session`, `guest`, `hash`, `ip` or `device`.
- The close job over seeded carts counts a cart once however many lines it has, counts a guest
  cart and an account cart alike, and writes `NULL` (not 0) for a date whose carts the sweeper has
  deleted; the clock is advanced past the retention window in the test.
- A cart converted the next morning is a basket on day 1 and an order on day 2, in both stages.
- The click endpoint counts a valid token, ignores an unknown, archived and expired one with the same
  202, never stores an address (the table is read back), and is limited at the rate set.
- The funnel read renders `measured:false` for visits and no ratio is computed against it.
- Tenant isolation: another tenant's brand answers 404; the reporting role reads the new tables and
  nothing else in `ordering` or `customer`.
- Front-end: the chart labels the stages as daily counts, shows "not measured" for visits, and the
  acquisition chart carries the sentence that it does not attribute a customer to a link.

## Rollout and rollback

Ship the capture first and the screens second, because the capture is the only part that loses
data by waiting: the migration and close step, the backfill from the carts still held, then the
click endpoint with the storefront calling it on a `?ref=` landing, then the two reads and the
console band replacing the unbuilt note in `customer-analytics-page`. Rollback of the screens is
removing the reads; the tables are additive and hold no person. Rollback of the click endpoint is
removing it from the permit list; the counters stop moving and nothing else changes. Nothing in
this record can be undone by deleting data it should not have held, because it holds none.

## Implementation checklist

- [ ] Owner accepts the record, or answers the open inputs; counsel is asked the two legal
      questions in parallel, because their answers widen the next record and do not gate this one.
- [ ] Migration: `reporting.fact_funnel_day`, `reporting.fact_registration_day`,
      `marketing.attribution_link_clicks_day`, grants for the application and reporting roles.
      Check every active worktree's `db/migration/` for the next free number first.
- [ ] A read port in `ordering.api` for per-day basket and checkout counts by channel (no
      identifiers), and the close-job step; the backfill.
- [ ] Metric registry entries `funnel.stage.v1` and `acquisition.source.v1`.
- [ ] The public click endpoint, rate limited; the storefronts call it on a `?ref=` landing;
      the staff click endpoint removed from the contract and the OpenAPI baselines regenerated
      (five documents).
- [ ] `GET .../reporting/customer-funnel` and `GET .../reporting/acquisition-sources`.
- [ ] `reporting.funnel_retention_days` and its report-only sweeper.
- [ ] Console: the funnel band and acquisition chart in `customer-analytics-page`, with the
      "not measured" and "no per-customer attribution" wording in ru, uz-Latn and en; the tenant
      terms notice wording supplied for ADR 0068's editor.
- [ ] The structural no-subject test, the capture and `NULL` tests, the click tests.
- [ ] Gap-map row `7.6c` re-audited and the statistics spec's Band D text updated (this record
      edits neither); ADR 0043's status line updated to say its telemetry bullet is amended.
- [ ] Tier 2 and Decision 8 are not on this list; each waits for counsel.

## Exit criteria

An operator opens customer analytics for a brand and sees, for any week since go-live, how many
people registered, how many baskets were started, how many reached checkout and how many
ordered, per day and per channel, with visits shown as "not measured" and no ratio drawn from
it; and sees, for each attribution link the brand minted, how many times it was opened, beside
registrations by origin and orders by channel. A day whose carts had been swept shows a gap and
not a zero. No table behind either view can be joined to a person, a session or a device, and a
test says so. Nothing is collected from a browser, an app or a bot for this purpose, and the
Network tab of a storefront page shows no request to a measurement endpoint other than the
tenant's own installed tags and the link-click call on a `?ref=` landing.

## References

- ADR 0015 (consent decisions), ADR 0023 (a report reads no module schema), ADR 0025, ADR 0027,
  ADR 0029 (classes, provisional retention, erasure), ADR 0030, ADR 0031, ADR 0032, ADR 0033,
  ADR 0034 (data residency and the processor list), ADR 0043 (the telemetry decision and its
  rejected row, the close job), ADR 0044 (attribution links), ADR 0045 (courier telemetry, a
  different subject), ADR 0068 (tenant terms), ADR 0092 (cart retention), ADR 0106 (the
  tenant's own analytics installs)
- `platform/docs/operations-gap-map.md` rows `7.6`, `7.6a`, `7.6b`, `7.6c`, `10.8e` and the
  note that the tenant's GA4 contract must not grow into first-party telemetry
- `platform/docs/frontend-information-architecture.md` row 7.6
- `platform/docs/operations-spec/statistics.md` §2.6, Band B and Band D, and the dependency table
- `V0017`, `V0022`, `V0031`, `V0043`, `V0295`, `V0309`; `CartRetentionSweeper`,
  `AbandonedCartDirectory`, `DayCloseService`, `SubjectPseudonym`, `ReportQueryService`,
  `AttributionLinkService`, `AttributionLinkController`, `StorefrontAnalyticsConfigController`
- `frontend/operations/src/app/features/reports/customer-analytics-page.ts`,
  `frontend/storefront/src/app/core/analytics/ecommerce-events.ts`
