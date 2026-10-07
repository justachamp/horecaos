# ADR 0175: Telephony KPIs: ring time and call-to-order conversion

- Decision status: Proposed — proposed by Claude (batch 19); the platform owner decides
- Implementation status: Not started — the half of row `7.5b` that has a source is
  built and the half that has none is not. `reporting.fact_call_hour` (V0149)
  holds `offered_count`, `answered_count`, `missed_count`, `transferred_count`
  and `talk_duration_seconds` per location, business date, hour and operator,
  written by `DayCloseService.close` from `JdbcReportingStore.readSourceCallEvents`
  and `DayAggregator.callHourFacts`; `CallStatsController` serves it (one date per
  request, `REPORTING_READ`) to a Telephony tab on `/reports/staff` that renders
  the counts and, in place of the two other KPIs, the notices
  `reports.staff.telephony.answerSpeedNotBuilt` and `conversionNotBuilt`. There is
  no ring or wait column anywhere, and `readSourceCallEvents` selects six columns
  (brand, location, event type, operator, duration, time) and not the provider
  call id, so the close cannot pair an `OFFERED` event with the `ANSWERED` event
  that ends it. There is no conversion fact: `ordering.orders.source_call_id`
  (V0148, write-once, set by `OrderCallProvenanceService`) exists and is now
  written, since `new-order-page.ts` calls `recordCallProvenance` when an order is
  started from a claimed screen-pop card (`new-order-page.spec.ts`), which makes the
  conversion notice's text, "the call-provenance wiring that 1.6 supplies (not
  built yet)", stale; but nothing in `reporting` reads `source_call_id`, it has
  no index, and `reporting.fact_order` carries no call origin (V0274 added only
  `operator_principal_id`). Two latent defects sit in what is built and decide
  how the new columns must be shaped. First, `talk_duration_seconds` is not talk
  time: `VoiceEventInboundPort.ingest` computes an `ENDED` event's
  `duration_seconds` from `JdbcVoiceStore.earliestEventAt`, the earlier of the
  call's `OFFERED` and `ANSWERED` events, so the figure is call length with ring
  and queue time inside it, and the tab labels it «Talk time». Second, the tab's
  per-operator «Answer rate» divides answered by offered, and an `OFFERED` or
  `MISSED` event carries no operator (V0149's own comment says offered lands in
  `(unassigned)`), so an operator's row has no offered count and the cell is
  always «—»; the sentinel row is also sliced to eight characters by
  `shortSubject` and shown as «(unassig». Neither adapter has ever met a live
  provider: `AsteriskAmiSocketClient` stamps `occurredAt` with its own clock at
  receipt and the hosted-webhook mapper passes the provider's `payload.occurredAt()`;
  both are proven only against a fake PBX, no live provider account exists (ADR 0064
  says so), and `voice_platform/` is decision records only.
- Date proposed: 2026-10-07
- Date decided: —
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0007, ADR 0025, ADR 0026, ADR 0029, ADR 0030, ADR 0031,
  ADR 0032, ADR 0043, ADR 0064, ADR 0139
- Supersedes / Superseded by: — (amends ADR 0064 without editing it. It adds two
  measures to that record's "Stats are ADR 0043 facts" and corrects nothing in it:
  ADR 0064's text is unchanged, and its Decision bullet "Stats are ADR 0043 facts
  ... durations" stays true. The mislabel is only the console label «Talk time»
  and the V0149 column name `talk_duration_seconds`, both fixed here without
  touching ADR 0064: the label becomes «Call length», and a `COMMENT ON COLUMN`
  states what the column holds (call length), while the name stays, because
  renaming a stored column and its API field is a definition change that ADR
  0043 reserves for a new version. It reopens no row of ADR 0064's Alternatives table: "A stats
  dashboard fed by the PBX directly" stays rejected, and Decision 8 is written so
  that the voice platform's own analytics are never a source. It reopens one
  statement of `operations-spec/statistics.md` §2.5, that the telephony tab "is not
  rendered at all" until a provider exists, which the built tab contradicts and
  Decision 7 restores.)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written; the ones that name a person other than the owner stay with
  that person and the work they block is marked.
  - **Which moment is "the call starts ringing" for the pilot's telephony**
    (platform owner, with the author of `voice_platform`). Today `OFFERED` means
    the call reached the PBX (the Asterisk adapter maps `Newchannel` to it), so
    the measured interval includes any IVR and queue time. Proposed default:
    define ring time as `OFFERED` to the first `ANSWERED` of the same provider
    call, label it «Answer speed» with that definition in the tab's help text,
    and let a finer event (queue entry, agent ring) arrive later as an optional
    field on the inbound event that adds columns and changes none of these.
    Blocks: nothing.
  - **The longest interval still believed** (platform owner). A pair above it is
    almost certainly two different calls joined by a reused provider id, and it
    would dominate an average. Proposed default: 1800 seconds, as the ADR 0030
    key `voice.ring_time_ceiling_seconds`.
  - **Whether an order that is later rejected, cancelled or expired still counts
    as the call's conversion** (platform owner, product). Proposed default: yes,
    an order that was placed from the call counts, because an order's later
    status (completed, cancelled, expired) must not change what the operator
    achieved on the call, and because taking the order is what the operator
    controls; a second column for completed conversions is added if wanted, never
    a redefinition.
  - **Backfilling days closed before the migration** (platform owner). Proposed
    default: none. They stay `NULL`, read as «not measured», and no tenant holds
    live call data to be wrong about.
  - **How the voice platform meets this platform** (platform owner; the question
    is `voice_platform/docs/adr/0001-multi-tenant-restaurant-contact-center-platform.md`
    §35, item 2). Proposed default: it plugs in as a `VOICE` provider and pushes
    the five-word vocabulary through the hosted-webhook contract; HorecaOS's call
    screen stays the order-taking surface and the only writer of provenance. If
    its agent application takes over order-taking, that application carries
    HorecaOS's `callEventId` to the order and calls the existing provenance
    endpoint, so the key does not change.
  - **A service-level target** (percent answered within N seconds)
    (platform owner, operations). Proposed default: none is defined or shown; it
    needs the distribution of ring times, which this record does not store, and
    the voice platform's own reserved record on service levels (its ADR-012) has
    not been written.
  - **Whether an automated answer counts** (platform owner). A voicebot or IVR
    that answers is not an operator. Proposed default: an `ANSWERED` event means a
    person picked up, as an adapter-contract clause; an adapter that wants to
    report an automated leg does not emit `ANSWERED` for it.

**To accept as written:** say "accept 0175". Every open input above is then
closed on its proposed default.

## Context

Row `7.5b` is `PARTIAL`: "A Telephony tab now wraps `CallStatsController` (calls
offered/answered/missed/transferred, talk seconds/hour/operator) — but answer
speed and call-to-order conversion still render an explicit 'not built' notice
rather than a number: `V0149` has no ring/wait-time column and conversion needs
`W01`'s call-provenance wiring." Its "Blocked by" says the same: "Answer speed needs
a ring/wait-time column added to `V0149` (none exists); call-to-order conversion
needs `W01`'s call-provenance wiring. ADR 0064's live-VOICE-provider gap (both
adapters proven only against a fake PBX) no longer blocks the built half, which
reads whatever the configured adapter reports." Wave `T12`'s note adds that the
conversion "has no fact — the write-once `call-provenance` column exists and
`1.6`'s wiring (`T-W01`) is what starts populating it". The IA's row 7.5 names the
three KPIs, "telephony KPIs when 1.6 is installed". Two of those sentences are
now out of date and one is incomplete. `W01` is built (row `1.6` is `BUILT`: the
provenance endpoint has a caller), so the wiring no longer blocks; what remains
is the fact. And the row does not say that the existing «Talk time» and per-operator
«Answer rate» are wrong.

**Both new measures are derivable from what the platform already records, and
neither can be derived by the close job as it reads today.** A ring time is the
difference between two rows of `voice.call_events` that share a provider call id;
a conversion is one row of `voice.call_events` joined to one row of
`ordering.orders` by `source_call_id`. `readSourceCallEvents` reads neither the
call id nor the row id, and the call-hour aggregator counts events one at a time,
so it has no notion of "the same call". The V0149 comment states why the table
is hour-grained and event-counted (ADR 0043 had no hour grain, and ADR 0064's exit
criteria want per-operator, per-hour figures); this record keeps that and adds
call-level facts onto it as additive columns rather than a second table.

**The source rows are honest about what they know and what they do not.**
`OFFERED` has no operator; `ANSWERED` has one only when the provider reports it or
when the operator has claimed the screen-pop card before the event is ingested
(`store.acknowledgedOperator`); `ENDED` is often ingested after the claim and so
more often has one. A per-operator ratio is therefore only meaningful when its
numerator and denominator are counted on the same row, and the conversion and ring
columns below are defined that way. The caller's number is `ADR 0029`-protected
on the `OFFERED` row and nothing here selects it.

**Two sources already say how to treat "no live provider".** ADR 0064 states that
adapters declare what they can do "so the core degrades honestly per provider
instead of assuming every PBX can do everything". The statistics spec decides the
screen: the telephony KPIs come back "only with IA 1.6 and a telephony provider,
and until then the tab is not rendered at all — an empty tab teaches people the
screen is broken", and Delever's own instance shows «Нет данных» for the same
report. The built tab does the opposite, rendering for every branch with two
apologies in it.

**The voice platform changes the provider, not the vocabulary.** The owner decided
on 2026-10-03 that the contact centre is a separate project, `voice_platform/`
(its `voice_platform/docs/adr/0001-multi-tenant-restaurant-contact-center-platform.md`,
Proposed, no code). Its §35 lists what crosses the boundary: HorecaOS is the
system of record for customers and orders; ADR 0064 "already built a provider-neutral
voice core"; whether the voice platform plugs in as the `VOICE` provider or retires
HorecaOS's call screen is open; personal data crosses as identifiers only. Its
reserved records on the event architecture, the CRM integration and the
service-level model are unwritten. Everything here reads only the five-word
vocabulary ADR 0064 fixed and the provenance key, so it survives any of the
answers.

## Decision

**Add five call-level columns to `reporting.fact_call_hour` in a new migration,
written by the day-close transaction from events and orders the platform already
holds; compute ring time as `OFFERED` to the first `ANSWERED` and conversion as
answered calls that have a placed order; store counts and sums, never ratios;
and show nothing, rather than zero, wherever a figure was not measured.**

1. **Two measures, each defined once.** *Ring time* (the console's «Answer speed»)
   is the seconds from a call's `OFFERED` event to its first `ANSWERED` event,
   where "a call" is one `(installation_id, provider_call_id)` within a tenant.
   *Call-to-order conversion* is the share of answered calls for which at least one
   order exists whose write-once `source_call_id` is that call's `OFFERED` event id.
   Both are computed inside `DayCloseService.close`, in the same transaction as
   every other fact, and are rebuilt by `clearDay` and the close like every other
   row. There is no read-time join and no parallel store (ADR 0043, ADR 0064).

2. **Five additive columns, by a new migration; V0149 is never edited.**
   `answered_call_count`, `ring_seconds_total`, `ring_sample_count`,
   `ring_seconds_max` and `converted_count`, all nullable, `NULL` meaning "this row
   was closed before the measure existed and has not been rebuilt". A row the new
   close writes carries non-null values for the first, second, third and fifth
   (`0` where nothing was measured) and a non-null maximum exactly when it holds a
   sample. The migration adds columns, so the table-level grants of V0149 already
   cover them. It changes no existing column, no boundary regime and no
   calculation version: a column added next to the existing counts is not a change
   to how they are calculated, and `MetricRegistry.CALCULATION_VERSION` stays `1`.

3. **Pair by call, count once, on the answering row.** The close reads, for each
   `ANSWERED` event that is the first of its call, the call's earliest `OFFERED`
   event (even when it fell on the previous business day), and writes onto the row
   of that `ANSWERED` event's hour and **resolved operator**: `answered_call_count
   += 1`; if an `OFFERED` exists and the interval is between zero and
   `voice.ring_time_ceiling_seconds` (default 1800), then `ring_sample_count += 1`,
   `ring_seconds_total += interval` and `ring_seconds_max` is raised; and if an
   order exists, `converted_count += 1`. The resolved operator is the
   `ANSWERED` event's operator, else the operator on the call's `ENDED` event (a
   claim of the screen-pop card usually lands between answer and end), else
   `(unassigned)`. A call
   with no `OFFERED` row, a negative interval or one above the ceiling still counts
   in `answered_call_count` and is simply not a ring sample, which is how a lost
   event shows up as lower coverage and not as a wrong average.

4. **A conversion is an order that was placed from the call, and it counts the
   call once.** The test is `EXISTS` an order with `source_call_id` equal to the
   `OFFERED` event id and `created_at` not before the call was offered, in any
   status. The qualification does not look at status because an order's later
   status (completed, cancelled, expired) must not change what the operator
   achieved on the call: taking the order is what the operator controls, and what
   the kitchen does with it afterwards is not. The only drift left is late
   provenance, and it is stated in the tab: a provenance written after the day
   closed (the console writes it within seconds of placing the order) is not
   counted by that close, and a rebuild of the day would count it, so conversion
   is a lower bound and not a settled number. An index serves
   the lookup: `ordering.orders (tenant_id, source_call_id) WHERE source_call_id
   IS NOT NULL`, tiny because phone orders are a minority.

5. **Counts and sums are stored; ratios are computed where they are read.**
   Average ring time is `Σ ring_seconds_total ÷ Σ ring_sample_count`, the longest is
   the maximum of `ring_seconds_max`, conversion is `Σ converted_count ÷
   Σ answered_call_count`, and coverage is `Σ ring_sample_count ÷
   Σ answered_call_count`, each over the rows that are not `NULL` (V0149's own
   reasoning for average handle time, applied again). A ratio stored in a row
   could not be summed across hours, operators and days.

6. **The API grows additively and a per-operator row shows only same-row ratios.**
   `CallHourResponse` gains the five fields, nullable (ADR 0031: additive within
   `v1`). The tab shows, per operator, calls answered, answer speed with its
   coverage, and conversion with its coverage, all three of which have a numerator
   and denominator on that row. «Answer rate» (answered over offered) moves to the
   branch total, where both counts exist. `talk_duration_seconds` is not
   recalculated; its documented meaning is corrected to "time from the call's
   first event to its end" and the column is labelled «Call length», because
   changing the arithmetic under an existing column is a definition change that
   ADR 0043 reserves for a new version, and a true talk time (end minus answer) is
   deferred until a provider reports it. The `(unassigned)` sentinel renders as
   «Not attributed», not as a truncated subject.

7. **What is shown with no live provider, in a fixed order.** A `coverage` read
   answers one of three states for the branch and range. *Not connected* (no active
   `VOICE` installation with an active binding at the location, and no closed call
   fact in the range): the Telephony tab is not rendered, as statistics.md §2.5
   requires, and a link to it lands on the leaderboard. *Connected, nothing closed
   yet*: the tab renders with its existing empty sentence and a line naming when the
   next close runs. *Measured*: figures with their coverage («18 s, measured on 19
   of 20 answered calls»). In every state a figure that was not measured is «—,
   not measured» and never `0` or «0 s»; the two «not built» notices are removed.

8. **The voice platform is a provider here, not a source.** Ring time and
   conversion are computed from `voice.call_events` and `ordering.orders` whatever
   system produced the events. The voice platform's own call detail records,
   analytics and quality metrics are never read for these two figures: conversion
   needs HorecaOS's orders, and a second place computing the same words is the
   failure ADR 0043 exists to prevent.

9. **Not a signed metric yet.** The two KPIs stay a dedicated read beside the
   existing call stats, as ADR 0064 and `CallStatsQueryService` decided, with their
   formulas published in the endpoint's OpenAPI description and the tab's «?» text,
   and are promoted to `MetricRegistry` when an hour or call grain is added to
   `Grain` for a second consumer.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Compute both at read time by joining `voice.call_events` and `ordering.orders` per request | Puts a lateral join over an append-only ledger and the order table behind a screen that fans out one request per day; ADR 0043 and ADR 0064 both decided that stats are facts written by the close | Never |
| Take ring time and conversion from the voice platform's or the PBX's own analytics | The row "a stats dashboard fed by the PBX directly" in ADR 0064 stays rejected: it splits reporting truth, and conversion needs HorecaOS's orders, which no PBX has | Never for conversion. For audio-quality figures (jitter, loss) HorecaOS has no source and the voice platform owns them |
| Edit V0149 in place to add the columns | Flyway checksums make an applied migration immutable in every environment | Never |
| Store the ratios (average ring seconds, conversion percent) | A ratio cannot be summed across hours, operators or days, which is exactly how the tab rolls rows up | Never |
| A per-call fact table (`fact_call`) instead of columns on the hour grain | Right when the owner wants percentiles or a service-level percentage, which need the distribution; premature for an average and a ratio, and a second grain for calls that V0149 already declined to widen every table for | A service-level target is set (open input 6). ADR 0043's own rule for SLA buckets then applies: store raw seconds, cut buckets later |
| Count a conversion only for an order that completed | An order's later status (completed, cancelled, expired) would change what the operator achieved on the call, and the call's conversion would then depend on the kitchen's outcome and not on taking the order; the drift that remains with the chosen rule is late provenance only, which is documented as a lower bound | The owner wants "completed conversion": add a second column beside this one |
| Join calls to orders by caller number and a time window | Needs the plaintext number, which is ADR 0029-protected on the `OFFERED` row and nowhere else, and is a guess where the provenance link is a fact | Never |
| Make provenance mandatory when an order is started from a claimed call | An order must never fail to place over a reporting fact; the write is made after placement, as built | Conversion coverage proves too low in practice |
| Leave the tab rendering for every branch with notices | The spec's own reasoning is that an empty tab teaches people the screen is broken, and the notices are now stale as well as unhelpful | Tenants ask to see the empty state |
| Register the two as signed `MetricRegistry` metrics now | Needs an hour or call grain in `Grain` and the finance sign-off workflow for figures nobody has yet | The export centre or a second consumer needs them |
| Recalculate `talk_duration_seconds` as end minus answer | Changes the arithmetic under a stored column and mixes two meanings across days; no provider has reported an answer time worth trusting yet | An adapter reports answer and end times itself, and the owner wants talk time as a KPI |

## Consequences

### Positive

- Both KPIs named by row `7.5b` have a defined source, a rebuildable derivation
  and a place on screen, and the row's two notices can go.
- A per-operator figure can no longer be built from a denominator that sits on
  another row, and an unmeasured figure can no longer be shown as zero.
- No new event, no new provider category, no new capability and no personal data
  are needed: the platform already holds everything, and the voice platform can
  change the provider without changing a column.
- The latent «Talk time» and «Answer rate» defects are named and corrected
  without rewriting a stored number.

### Negative

- Five more columns on a table that grows by hours times operators times locations
  times days, and a heavier close: one lateral lookup and one indexed existence
  test per answered call.
- The first figures will be as good as the adapters' timestamps, and no adapter has
  run against a live provider. The Asterisk client stamps its own receipt time; the
  hosted webhook trusts the provider's.
- «Answer speed» is arrival-to-answer and includes any IVR and queue time until a
  finer event exists; a manager comparing it to a PBX's «ring time» will see
  different numbers.
- Per-operator figures are only as attributable as the claim of the screen-pop
  card: a call answered on a handset without claiming the card is `(unassigned)`.
- Conversion is a lower bound whenever provenance is written after its day closes.
- The existing recut does not cover call facts (ADR 0064's status line says so: the
  divergence check "does not yet cover call facts the way it covers order facts"; it
  compares revenue and order counts per branch-day and the promotion fact, and derives
  call hours without comparing them). Until the checklist's extension lands, nothing
  compares a stored call day with a rebuilt one.
- The tab changes for tenants who have seen it: a column's label and a ratio
  disappear from the operator rows.

### Accepted trade-offs

- Any-status conversion rewards an order that is later cancelled; the alternative
  makes the operator's figure depend on what the kitchen does afterwards.
- No backfill: history before the migration reads «not measured» for ever.
- No percentile or service-level figure: an average hides a long tail, and the
  maximum is the only tail the facts keep.
- A call answered twice (a transfer) contributes one ring sample and one
  conversion, while the existing `answered_count` counts both answers; the two
  columns answer different questions and are labelled so.

## Specification

### Physical model

One Flyway migration, numbered at implementation time: the next free number after
`V0489` once every active worktree's `db/migration/` has been checked (the gap map's
PART C reserves no number above `V0489`, and sibling worktrees already hold `V0490`,
`V0493` and `V0494`). Every row already carries
`tenant_id` and is in the primary key; the grants of V0149 are table-level and cover
added columns, so none is repeated.

```sql
ALTER TABLE reporting.fact_call_hour
    ADD COLUMN answered_call_count integer,   -- distinct calls first answered in this hour by this operator
    ADD COLUMN ring_seconds_total  bigint,    -- sum of OFFERED -> first ANSWERED, whole seconds, sampled calls only
    ADD COLUMN ring_sample_count   integer,   -- calls with a sane OFFERED -> ANSWERED pair
    ADD COLUMN ring_seconds_max    integer,   -- longest sampled interval; NULL when there is no sample
    ADD COLUMN converted_count     integer;   -- answered calls with at least one order placed from them

ALTER TABLE reporting.fact_call_hour ADD CONSTRAINT ck_fact_call_hour_kpi CHECK (
    ((answered_call_count IS NULL) = (ring_seconds_total IS NULL))
    AND ((answered_call_count IS NULL) = (ring_sample_count IS NULL))
    AND ((answered_call_count IS NULL) = (converted_count IS NULL))
    AND (answered_call_count IS NULL OR (
            answered_call_count >= 0
        AND ring_seconds_total >= 0
        AND ring_sample_count BETWEEN 0 AND answered_call_count
        AND converted_count BETWEEN 0 AND answered_call_count
        AND ((ring_sample_count > 0) = (ring_seconds_max IS NOT NULL))
        AND (ring_seconds_max IS NULL OR ring_seconds_max >= 0))));

CREATE INDEX ix_orders_source_call
    ON ordering.orders (tenant_id, source_call_id) WHERE source_call_id IS NOT NULL;
```

`ordering.orders` is not partitioned, so the index is an ordinary partial index.
The columns carry `COMMENT`s that state the definitions above in one sentence each,
as V0274 did for its column. The same migration issues `COMMENT ON COLUMN
reporting.fact_call_hour.talk_duration_seconds` stating that it is call length (first
event to `ENDED`, ring and queue time included); that comment is the whole of the
correction to the column, and its name and stored values do not change.

### The source read and the aggregation

`JdbcReportingStore.readSourceCallEvents` keeps its window and gains, for an
`ANSWERED` row, four derived values: whether it is the first `ANSWERED` of its
call, the call's earliest `OFFERED` time, the resolved operator, and whether a
qualifying order exists. The call identity stays inside the statement, so the
Java record gains no provider id. It never selects `caller_number_encrypted` or
`caller_number_masked`.

```sql
SELECT e.brand_id, e.location_id, e.event_type, e.operator_principal_id,
       e.duration_seconds, e.occurred_at,
       (e.event_type = 'ANSWERED' AND NOT EXISTS (
            SELECT 1 FROM voice.call_events a
             WHERE a.tenant_id = e.tenant_id AND a.installation_id = e.installation_id
               AND a.provider_call_id = e.provider_call_id AND a.event_type = 'ANSWERED'
               AND (a.occurred_at, a.id) < (e.occurred_at, e.id))) AS first_answer_of_call,
       ofr.occurred_at AS offered_at,
       COALESCE(NULLIF(e.operator_principal_id, ''),
                (SELECT l.operator_principal_id FROM voice.call_events l
                  WHERE l.tenant_id = e.tenant_id AND l.installation_id = e.installation_id
                    AND l.provider_call_id = e.provider_call_id
                    AND l.event_type = 'ENDED' AND l.operator_principal_id IS NOT NULL
                  ORDER BY l.occurred_at LIMIT 1)) AS resolved_operator,
       (ofr.id IS NOT NULL AND EXISTS (
            SELECT 1 FROM ordering.orders o
             WHERE o.tenant_id = e.tenant_id AND o.source_call_id = ofr.id
               AND o.created_at >= ofr.occurred_at)) AS has_order
  FROM voice.call_events e
  LEFT JOIN LATERAL (
        SELECT f.id, f.occurred_at FROM voice.call_events f
         WHERE f.tenant_id = e.tenant_id AND f.installation_id = e.installation_id
           AND f.provider_call_id = e.provider_call_id AND f.event_type = 'OFFERED'
         ORDER BY f.occurred_at, f.id LIMIT 1) ofr ON e.event_type = 'ANSWERED'
 WHERE e.tenant_id = :tenantId AND e.occurred_at >= :from AND e.occurred_at < :to
 ORDER BY e.occurred_at;
```

It runs on `ix_call_events_reporting_scan` for the window and
`ix_call_events_provider_call` for each lookup. `DayAggregator.callHourFacts`
accumulates the existing counts exactly as before on the event's own key, and the
five new values on the key `(brand, location, hour of the first answer, resolved
operator)`; a key that exists only for these values is a row of zero event counts,
which V0149's check allows. The tenant's ceiling is read once per close through
the ADR 0030 resolver. `CallHourFact` gains the five fields as nullable-aware
types so the read side can tell «not measured» from `0`.

### API

| Call | Capability | Notes |
|---|---|---|
| `GET /api/v1/operations/tenants/{t}/brands/{b}/locations/{l}/voice/call-stats?businessDate=` | `reporting.read`, `LOCATION` | Unchanged request; each `CallHourResponse` gains `answeredCallCount`, `ringSecondsTotal`, `ringSampleCount`, `ringSecondsMax`, `convertedCount`, each `null` for a row closed before the migration |
| `GET .../voice/call-stats/coverage?from=&to=` | `reporting.read`, `LOCATION` | New. `{ connection: NOT_CONNECTED or CONNECTED, activeInstallationCount, firstCallEventAt, lastCallEventAt, closedDaysWithCalls }`. Reads, through a consumer-declared port in `voice.api` implemented over the same installation and binding tables `VoiceInstallationLookup` already reads, whether a `VOICE` installation is active and bound at the location; reads call facts for the rest |

No new capability: both reads are the reporting read the existing endpoint already
requires, and no figure here reveals a person (ADR 0025). Names are not added to
the response; the tab composes them from the leaderboard as ADR 0139 decided.
The endpoint descriptions publish the formulas, and the problem codes are the
existing ones.

### Events, audit, personal data

- **Events (ADR 0032): none new.** `VoiceCallEventRecorded.v1` is unchanged, so its
  frozen baseline and `EventSchemaCompatibilityTests` are untouched; the new
  facts derive from rows, not from a message.
- **Audit (ADR 0027): none new.** No person changes a state. Reading a report is
  not audited, as for every `REPORTING_READ` surface, and a change to
  `voice.ring_time_ceiling_seconds` is audited by ADR 0030's own path.
- **Personal data (ADR 0029): none enters.** The facts hold counts, a sum of
  seconds and an operator principal id (a staff subject, not a person's name). The
  close reads `source_call_id` for existence and never selects an order id, a
  customer reference or either caller-number column; a test asserts the new SQL
  names none of them.
- **Policy (ADR 0030):** `voice.ring_time_ceiling_seconds`, integer, default
  `1800`, tenant scope, declared once in the voice module's configuration keys
  and mirrored in the tenancy registry with the drift test `OrderingConfigurationKeyTests`
  already models.

### Provider adapters and fakes (ADR 0026, ADR 0007)

No new capability code and no new provider category. The VOICE adapter contract
gains three clauses, enforced by the existing contract tests against the fakes:
`ANSWERED` means a person picked up, never an IVR or voicebot leg; `OFFERED` for a
provider call id precedes its `ANSWERED` and `ENDED` and `occurredAt` is
non-decreasing within a call; and the hosted webhook's `occurredAt` is the
provider's event time, not the delivery time. `FakeAsteriskAmiServer` and the hand-built hosted-webhook
payloads of the existing tests gain a configurable ring delay and an
answered-then-transferred script, so the figures below can be asserted against a
known interval. A route descriptor is not added: no route changes.

### Front-end contract

`staff-report-page` (`features/reports/`) and `call-centre-api.ts`:

| State | Behaviour |
|---|---|
| `coverage.connection = NOT_CONNECTED` and no closed call fact in the range | The Telephony tab is not in the tab list; a deep link selects the leaderboard |
| `CONNECTED`, no closed day with calls | Tab renders; the existing empty sentence plus the time of the next close |
| Measured | Per operator: answered, answer speed `avg (max)` with «measured on n of m», conversion `x%` with «n of m». Branch line adds answer rate and call length |
| A `null` field, or a zero denominator | «—», with the caption «not measured», never `0` or «0 s» |

New strings in ru, uz-latn and en replace the two «not built» notices; «Talk time»
becomes «Call length»; `(unassigned)` renders as «Not attributed». The help text
states both definitions and that conversion is a lower bound.

### Testing

- `DayAggregatorCallHourFactsTests` extended: first answer only, a re-answer after a
  transfer, an `OFFERED` on the previous business day, an interval above the
  ceiling, a negative interval, a call with no `OFFERED`, the resolved operator
  falling back to the `ENDED` operator and to `(unassigned)`, and a key that exists
  only for the new values.
- A close test against the migrated schema: with the fake PBX ringing seven seconds
  and an order carrying that call's provenance, the row holds
  `ring_seconds_total = 7`, `ring_sample_count = 1`, `converted_count = 1`; an order
  linked with `created_at` before the call is not counted; `clearDay` and a second
  close are idempotent; a row closed before the migration reads `NULL`.
- The migration test (the `PhysicalAttributesMigrationTests` genre): the constraint
  accepts every consistent shape and refuses `ring_sample_count > answered_call_count`.
- A test that the new SQL never names `caller_number_encrypted`,
  `caller_number_masked`, `resolved_customer_account_id` or an order id in its
  select list.
- `CallStatsController` returns `null`, not `0`, for a pre-migration row;
  `OpenApiContractTests` against a regenerated baseline (additive).
- Contract tests for the three adapter clauses against both fakes.
- Angular specs: the four states above, the ratios rolled up across days, no `0`
  for an unmeasured figure, the sentinel label, and the hidden tab with a deep link.

## Rollout and rollback

1. Ship the migration and the close change. Nothing is visible: the columns fill
   as days close, `CallStatsController` returns them, and the tab ignores them.
2. Ship the coverage read and the tab change together: the two notices go, the
   ratios appear with their coverage, and the tab hides for a branch with no
   provider.
3. Extend the fakes and the contract tests; when the pilot connects a real
   provider, compare its first day's `ring_sample_count` to its `answered_call_count`
   before anyone reads an average.

Rollback of the tab is a revert of the front end: the API fields are nullable and
additive. Rollback of the close is a revert of the aggregation: the old derivation
is untouched and the new columns simply stop filling and read as «not measured».
The migration is additive and forward-only.

## Implementation checklist

- [ ] Owner answers, or accepts the defaults for, the open inputs above.
- [ ] Migration: five columns, the check constraint, the partial index on
      `ordering.orders`, comments; migration test.
- [ ] `readSourceCallEvents` extended as specified; `SourceCallEvent` and
      `CallHourFact` carry the new values nullably.
- [ ] `DayAggregator.callHourFacts` accumulates the five columns; the ceiling read
      through ADR 0030; `voice.ring_time_ceiling_seconds` declared and mirrored.
- [ ] `CallHourResponse` fields and the `coverage` endpoint with its consumer-declared
      port; OpenAPI description carrying the formulas; baseline regenerated.
- [ ] Adapter contract clauses and the fakes' ring-delay and transfer scripts.
- [ ] Tab: states, ratios with coverage, hidden tab and deep-link fallback, «Call
      length», «Not attributed», strings in three languages, specs.
- [ ] `COMMENT ON COLUMN reporting.fact_call_hour.talk_duration_seconds` states it is
      call length (first event to `ENDED`); the tab label «Talk time» becomes «Call
      length» in ru, uz-latn and en. ADR 0064 is not edited.
- [ ] Extend `DayCloseService.recut` to compare `converted_count` and
      `ring_sample_count` per hour and operator, and treat a higher recut value as
      expected late-provenance drift.
- [ ] Update the `7.5b` gap-map row after a re-audit (the owner's step, not an edit
      of this record).

## Exit criteria

Against the fake PBX configured to ring seven seconds, a closed day shows, for the
operator who claimed the call and placed an order from its card, «Answer speed 7 s
(measured on 1 of 1 answered calls)» and «Conversion 100% (1 of 1)»; the same day
for a call nobody answered shows neither as zero. A branch with no `VOICE`
installation has no Telephony tab. A day closed before the migration shows «—, not
measured». No log, event or response carries a caller number, a customer or an order
id because of this change.

## References

- ADR 0007, ADR 0025, ADR 0026, ADR 0029, ADR 0030, ADR 0031, ADR 0032, ADR 0043
  (facts, ratios at report time, boundary and calculation versions, SLA buckets),
  ADR 0064 (the vocabulary, presence, call-to-order provenance, the fake PBX),
  ADR 0139
- `voice_platform/docs/adr/0001-multi-tenant-restaurant-contact-center-platform.md`
  §17 (call event architecture) and §35 (what crosses the boundary), and its
  reserved records listed in `voice_platform/docs/adr/README.md`
- `platform/docs/operations-gap-map.md` rows `7.5b`, `1.6`, `X.37` and waves
  `T12`, `W01`
- `platform/docs/operations-spec/statistics.md` §2.5 and §5 (telephony KPIs skipped
  until a provider exists); `platform/docs/frontend-information-architecture.md`
  rows 7.5 and 1.6
- `V0146`, `V0148`, `V0149`, `V0274`
- `VoiceEventIngestionService`, `JdbcVoiceStore.earliestEventAt`,
  `VoiceEventInboundPort`, `AsteriskAmiSocketClient`, `AsteriskAmiEventMapper`,
  `HostedPbxEventMapper`, `VoiceInstallationLookup`, `VoiceProviderCapabilityCatalog`,
  `OrderCallProvenanceService`, `JdbcOrderStore.recordCallProvenance`,
  `DayCloseService`, `DayAggregator`, `JdbcReportingStore`, `CallStatsController`,
  `CallStatsQueryService`, `MetricRegistry`, `DayAggregatorCallHourFactsTests`,
  `FakeAsteriskAmiServer`
- `frontend/operations/src/app/features/reports/staff-report-page.ts`,
  `features/orders/call-centre-api.ts`, `features/orders/new-order/new-order-api.ts`,
  `core/i18n/messages/reports.*.ts`
