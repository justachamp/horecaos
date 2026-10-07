# ADR 0163: Cook headcount and demand forecast

- Decision status: Proposed — proposed by Claude (batch 19); the platform owner decides
- Implementation status: Not started — the screen says it does not exist and
  the pieces it would divide are half there. Built: `kitchen.station_capacity`
  (V0144, V0334: station, ISO weekday, local time window, `portions_per_hour`, a
  ceiling "a manager sets by eye"), read by `KitchenTicketService.capacityOffsetSeconds`
  to pull `release_at` earlier (V0191) and edited on `capacity-page.ts` (Card 1);
  `reporting.forecast_run` / `reporting.fact_forecast` (V0367), a seasonal-naive
  model with an 80% band generated daily per location and weekday by
  `ForecastScheduler` / `ForecastService` and served at
  `GET .../reporting/demand-forecast` and `.../demand-forecast/breakdown`
  (`demand-forecast-page.ts`, rows `7.8`, `7.8a`, `7.8b` BUILT). Not built:
  anything at the grain a cook is planned at. `ForecastService` counts **orders**
  per operating hour at branch grain, and **portions** per catalogue category or
  variant (the 30 and 20 best sellers of the sample, `MAX_BREAKDOWN_CATEGORIES`,
  `MAX_BREAKDOWN_VARIANTS`) at the breakdown grain, always by the order's own hour
  and for completed orders only; `reporting.fact_order_line` has no station column
  (V0031), and row `7.8a` says in as many words that "department" is catalogue
  category, not a kitchen station. No table, key or document holds a
  portions-per-cook-hour figure (`grep -a` over `src/main` finds none; the only
  "portions" number in the schema is `kitchen.station_capacity.portions_per_hour`,
  a station ceiling), and the "Not built (Card 2)" paragraph in
  `capacity-page.ts` still says "there is no forecasting model anywhere in the
  platform", which stopped being true when V0367 landed.
- Date proposed: 2026-10-07
- Date decided: —
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0025, ADR 0027, ADR 0029, ADR 0030, ADR 0031, ADR 0032, ADR 0041,
  ADR 0043
- Supersedes / Superseded by: — (amends ADR 0041 and ADR 0043 by adding to them, and
  reopens exactly one line of ADR 0041's "What was not built": «Cook headcount
  output … stays a product-policy gap». Neither record is edited. ADR 0041's
  station-level ceiling model, its rule that a ceiling "never rejects an order", and
  ADR 0043's "deliberately unsophisticated" forecast all stand.)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written; the ones that name a person other than the owner stay with that
  person and the work they block is marked.
  - **The portions-per-cook-hour figure, per station** (the tenant's kitchen manager
    states it; the platform owner decides only that the platform never supplies
    one). Proposed default: the platform ships **no number**. The policy is empty
    until a tenant publishes it; a station with no figure answers "no policy" and no
    headcount. Blocks: nothing in the build; blocks the screen showing a cook count
    for any tenant that has not typed one.
  - **Which demand method** (platform owner, product). Proposed default: the
    method ADR 0043 already names and ADR 0043's branch forecast already runs — the
    mean of the most recent four qualifying occurrences of the same weekday, per
    operating-day hour, with ADR 0043's `HolidayMode` (default `INCLUDE`), an 80%
    band from the sample standard deviation, refused below three qualifying dates —
    applied to a new station-grain fact. No machine learning, no external signal.
  - **Which instant a portion counts at** (operations). Proposed default: the
    ticket's fire instant, `released_at`, because that is when a cook starts work;
    not the order's creation, which would put a 20:00 pre-order in the 11:00 hour.
  - **Which stations count** (operations). Proposed default: every station role
    except `EXPO` (the pass is not a cooking station, ADR 0041); a role the tenant
    gives a figure for is planned, a role it does not is shown without one.
  - **Rounding** (engineering). Proposed default: `ceil`, to whole cooks, shown
    for the expected demand and for the top of the band.
  - **Capability names** (platform owner). Proposed default: `kitchen.headcount.read`
    and `kitchen.productivity.manage`, new, ADR 0025.

**To accept as written:** say "accept 0163". Every open input above is then closed
on its proposed default.

## Context

**Row `2.6a` — "Cook headcount output" — is `BLOCKED`, tier 3, size XL.** Its "What
is missing" says: *"A manager cannot ask how many cooks the evening needs — the
platform has neither a demand forecast to scale nor a stated
portions-per-cook-hour policy to divide by, so the screen shows a ceiling and
stops there."* Its "Blocked by" says: *"Two named inputs, both owner/product: the
portions-per-cook-hour policy (varies by station and cuisine, nobody has decided
it) and a demand-forecast decision — PART 3's wave 3 lists demand forecast as
blocked on a product decision, not on engineering, and there is no
analytics/forecasting ADR (PART 5 §7)."* The information architecture (§2.6,
"Capacity & buffer settings") gives the row two halves: *"max preparations per
hour per product per branch; cook headcount output"*. ADR 0041 decided the first
half at station level instead of product level and built it; it names the second
half a product-policy gap "rather than a schema one".

**Half of the second blocker is already gone, and the documents have not caught
up.** ADR 0043's "Forecasting" section says the model is "seasonal-naive over a
trailing window with a day-of-week and hour-of-day profile … explainable to a
kitchen manager who wants to know why it asked for 40 portions". The first cut
(7.8) shipped a historical average by the owner's 2026-09-05 decision, and the
model followed in V0367 and `ForecastService`: `MODEL_VERSION = 1`,
`CONFIDENCE_LEVEL = 0.80`, `CONFIDENCE_Z = 1.2816`, a four-date default sample
(`ForecastScheduler.DEFAULT_SAMPLE_SIZE`), a three-date refusal
(`ReportQueryService.DEMAND_HISTORY_MINIMUM_SAMPLE`), an operating-day-relative
hour axis (0 is the location's own business-day start, `BusinessDayBoundary`),
`HolidayMode` `INCLUDE` / `EXCLUDE` / `WEIGHT` over `tenant.public_holidays`, and
the actual written back after the day closes so `absolute_percentage_error` is a
stored fact. So the "demand-forecast decision" the row waits on is not "build a
forecast"; it is "which forecast, at which grain, feeds a cook count". The gap
map's own "What is missing" text for `2.6a` and the comment in `capacity-page.ts`
both still describe a world with no model.

**What the existing forecast cannot feed.** A cook is planned per station. Three
facts stand in the way, each verified in the code rather than assumed.

1. *The branch rows are the wrong unit.* They count orders (`fact_order`). A station
   cooks portions; one order is anything from one portion to thirty, and a combo
   (ADR 0136) is one line and several station items. The breakdown beside them has
   the right unit (`readLineDemandByDimension` sums `fact_order_line.quantity`,
   rounded up once per date, hour and product, ADR 0137) but the wrong axis.
2. *The axis is the catalogue, not the kitchen.* A category is a menu grouping. A
   station is where the routing rules (`kitchen.brand_routing_rules`,
   `kitchen.location_routing_rules`, five levels, ADR 0041) put a line, and one
   category can reach three stations. `fact_order_line` records no station,
   `reporting` does not read `kitchen`, and the breakdown is capped to the sample's
   best sellers and carries no band.
3. *The instant and the population are wrong for kitchens.* Both reads bucket by the
   order's operating hour (`occurred_at`) and keep `terminal_status = 'COMPLETED'`
   only. A pre-order placed at 11:00 for 20:00 is the 11:00 hour in the fact and the
   19:30 hour on the line (ADR 0041's `release_at`), and an order cancelled after
   the grill started on it never counts although a cook worked on it. The cook's
   work starts at `kitchen.tickets.released_at`.

**The ratio the row calls a policy.** `kitchen.station_capacity.portions_per_hour`
is a station's plant ceiling ("the grill does 40 plates an hour"), already read by
the release scheduler. It is not a cook's productivity: a grill that tops out at
40 plates may be worked by one cook or by two. Dividing forecast portions by the
ceiling gives "how many grills", which is the number the screen shows today by eye.
The figure the row needs is a different one — portions one cook produces in an
hour — it varies by station and by cuisine (the row says so), and it is a fact only
the tenant's kitchen manager can state. ADR 0030 already gives the platform a place
for a value that varies by scope: a policy at tenant, brand or location. Brand is
the nearest thing the data model has to cuisine.

**What exists to build on, in the places the rest of the platform keeps it.**
`kitchen.ticket_items` (`station_id`, `quantity` numeric since V0449, one row per
routed line, voids keep their `released_at`); `kitchen.tickets.released_at`;
`reporting`'s day-close job, which "is the one thing that reads `ordering` and
`payments`" and writes facts (`DayCloseService.close`, with `fact_order_tender`,
V0304, ADR 0115, as the latest precedent for adding a source to it);
`kitchen.stations.role` (closed set `HOT`, `COLD`, `GRILL`, `BAR`, `BAKERY`,
`PACKING`, `EXPO`); the capacity screen at `/kitchen/capacity`
(`CapacityPage`), which names "Card 2" as its reserved place.

## Decision

**A cook count is `ceil(forecast portions per hour ÷ the tenant's portions per
cook-hour)`, per station and per operating hour, from a station-grain fact the day
close writes and a tenant policy nobody but the tenant fills in. The platform
supplies no productivity number, and says the output is a planning aid.**

1. **A new fact, `reporting.fact_station_load`, one row per business date,
   location, station and operating-day hour** — the portions fired. The day close
   reads `kitchen.ticket_items` joined to `kitchen.tickets` for tickets with
   `released_at` set (a ticket voided after it fired still cost the labour, and
   keeps its `released_at`), bucketed by the hour of `released_at` in the tenant's
   business day. It stores the station's `code` and `role` as they were that day, so
   renaming or archiving a station does not rewrite last quarter's planning. It is
   written in the same transaction as the other facts and rewritten with them on a
   recut, never by a second job. This extends the close job's one stated exception
   ("reads `ordering` and `payments`") by one source, `kitchen`, exactly as ADR 0115
   did for tenders; ADR 0043's rules on boundary version and calculation version
   apply unchanged.
2. **A new metric id, `kitchen_station_portions.v1`**, registered in
   `MetricRegistry` (ADR 0043: a number on a screen is a registered metric), sum of
   `portions`, grain day × location × station × operating hour, append-only. The
   headcount read and any later report take it from there; nothing reads
   `kitchen.ticket_items` on a request path.
3. **The forecast is the one ADR 0043 describes, run at station grain and
   computed on read.** For a location, a weekday and a station: take the most recent
   `sampleSize` (default 4, ceiling 12) qualifying dates of that weekday, where a
   date qualifies when the location fired at least one ticket that day, per
   operating hour; `mean` is the mean (holiday-weighted under `WEIGHT` exactly as
   `HolidayAwareness` does), `sd` the sample standard deviation of the raw counts,
   `high = mean + 1.2816·sd`, `low = max(0, mean − 1.2816·sd)`. Fewer than three
   qualifying dates refuses every hour of that weekday with `TOO_FEW_DATES` and
   returns the raw per-date portions instead, the refusal `demand-history` already
   makes. Nothing is persisted per request: the sample is at most twelve dates × 24
   hours × the branch's stations, a read of a small fact table, and a stored run
   would only add a second place for the number to disagree with itself.
4. **A tenant policy, `kitchen.station_productivity`, holds the productivity.**
   An ADR 0030 policy document settable at `TENANT`, `BRAND` and `LOCATION`,
   replace-not-merge like every policy, published whole under `If-Match`:
   `{ "schema": 1, "roles": { "GRILL": 40, "HOT": 30 }, "stations": { "GRILL-1":
   35 } }`, each value whole portions per one cook per hour, 1 to 1000. A station
   resolves to its `stations[code]`, else its `roles[role]`, else nothing. The
   brand level is where a cuisine's different pace is stated; the location level is
   where one branch's stove is. **Its built-in default is the empty document**, so
   a tenant that has not published one has no figure for any station and the screen
   says so.
5. **The output is a plan per station and hour, never a roster.** For each station
   with a figure and each operating hour: `expectedPortions`, `lowPortions`,
   `highPortions`, `sampleSize`, `portionsPerCookHour`, `cooksExpected =
   ceil(expected ÷ figure)`, `cooksAtPeakOfBand = ceil(high ÷ figure)`, the
   station's own ceiling for that hour from `kitchen.station_capacity` (the lowest
   `portions_per_hour` of any window overlapping the hour; absent when no window
   covers it), and `plantLimited` when `highPortions` exceeds that ceiling — the
   one case where more cooks do not help, shown beside the cook count so a manager
   does not staff a station that cannot cook the forecast. Per branch and weekday:
   the peak hour and the cooks needed at it across stations. A station with no
   figure, or a weekday with too few dates, returns a row with a `status`
   (`NO_POLICY`, `TOO_FEW_DATES`) and no number.
6. **What it explicitly does not promise.** It is a planning aid computed from the
   branch's own last four same-weekday evenings. It is not a staffing rota, a
   schedule, a labour cost, a guarantee of coverage, or a statement of what any
   person can cook; it names no person and is never keyed to one (a per-person
   output is performance management, and it would put a metric about a named
   employee on a screen). It does not change `release_at`, the capacity offset, or
   any ceiling: ADR 0041's rule that a ceiling "never rejects an order" and "never
   holds a ticket past its promise" is untouched. It does not see a known future
   spike: tickets already `HELD` in the buffer for the target date are not added.
   It does not model weather, events, promotions or opening-hour changes, beyond
   `HolidayMode`. It does not plan stations a tenant has not given a figure, the
   pass, couriers or front of house. It does not claim accuracy: the screen shows
   the sample dates beside the forecast so a manager can see what it was built
   from, and no error rate is stored at this grain in this record.
7. **The console completes Card 2 of `/kitchen/capacity`** (`CapacityPage`):
   a weekday picker, a station × hour grid, the peak summary, the plant-limit flag,
   the sample dates, and a link to the productivity editor (settings pattern
   `q-inherited-field`: set here, inherited from, trace). The copy at the top says
   what point 6 says.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Leave `2.6a` `BLOCKED` and decline the row | Costs nothing, and the screen stays honest today. But the forecast now exists, the station ceiling is already a reader of the same tickets, and the only input nobody can supply is one this record refuses to invent. The honest outcome of "no number" is a screen that works the day a manager types one | The owner decides cook planning is not a platform concern |
| Machine learning or an external forecasting service | ADR 0043 chose "deliberately unsophisticated" for a stated reason: a kitchen manager must be able to ask why it wanted 40 portions. A hosted model is a seventh stateful dependency and a residency question (ADR 0034) for a number a seasonal average gets close to | The stored error of the branch forecast (7.8) stays above a median absolute percentage error of 40% for a pilot branch over eight consecutive weeks, and the manager still has to plan with it |
| Map the existing variant breakdown through the routing rules | The unit is already portions, but the read is capped to the sample's 20 best-selling variants and carries no band, counts completed orders at the order's own hour, and would re-run five-level routing per variant per day to land on a station. The fact says directly what each station fired | The routing evaluation is persisted on the order line, so a station is a column the existing facts already carry |
| Use the existing category breakdown as "department" | Row `7.8a` already records that a category is not a station. One category can route to three stations, and a combo splits across them | Routing is made one-to-one with category, which ADR 0041 refuses |
| A platform-supplied default productivity per role | An engineer's number becomes every tenant's plan, and it is wrong for most of them in a way nobody can see. The shape is the one `EntitlementKeys` already refuses ("a limit invented by an engineer") | At least five tenants have published figures with a stable median per role; then offer it as a suggested pre-fill the tenant accepts, never as a default |
| Measure productivity from rosters (cooks on shift ÷ portions) | The platform has no kitchen roster: ADR 0042's shifts are couriers', and staff identity (ADR 0139) records people, not rota | Kitchen clock-in or a rota exists |
| Persist a `forecast_run` per request, as `fact_forecast` does | A stored run is how the branch forecast reconciles with actuals. At station grain the first use is a read, and a second copy of a small table is a second number | A manager asks "how wrong was it last Friday" at station grain |
| A per-product ceiling and per-product cooks (IA §2.6's first half) | ADR 0041 decided station-level, and the station is where the work is staffed | A kitchen with one cook per dish asks for it |
| Productivity per person | A metric about a named employee, with the labour-law and privacy weight that carries (ADR 0029). The cook count needs a pace, not a name | Never |

## Consequences

### Positive

- A manager can ask "how many cooks does Friday evening need" and read an answer per
  station and hour, with the sample it came from, in the console they already use.
- The unit is portions, the instant is when cooking starts and the axis is the
  station — the three things the branch forecast cannot say.
- No new job, no new store of forecasts, no new dependency: one fact beside the
  others, one policy in the existing mechanism, one read.
- A tenant that never publishes a figure is not misled: the screen says "no policy".
- The row's "stale" claims (the capacity-page comment, ADR 0041's pointer, the gap
  map's text) become fixable, because the forecast's existence is now stated once.

### Negative

- The day close gains a reader of `kitchen`. The close is the platform's most
  failure-sensitive job (a failed fact write rolls the close back, ADR 0043), and
  this is one more table in it.
- A plan from four evenings is wrong on a branch whose pattern shifted last week,
  on an opening after a long closure, and on any day the history does not contain.
  Three qualifying dates is the floor, not a comfort level.
- The figure is only as good as the manager's number, and a number typed once is
  rarely revisited. A station with a stale figure shows a confident cook count.
- `fact_station_load` is a station snapshot. A station archived mid-quarter keeps
  its code and role in old rows but disappears from the plan, so a branch that
  rebuilds its stations starts the history again for the new ones.
- Counting at fire time means a branch that holds tickets in the buffer shows the
  load where it was cooked, not where it was ordered, which is right for staffing
  and different from the branch forecast beside it on a neighbouring screen.

### Accepted trade-offs

- No number until a tenant types one. For a new tenant the card is a form and an
  explanation, not a chart, for as long as it takes someone to answer.
- Ceil rounds up: two stations each needing 0.2 of a cook each show one, and the
  branch peak adds them. The screen over-asks slightly rather than under-asks.
- The forecast ignores the future buffer. A known pre-order surge is not seen
  until the history has one like it.

## Specification

### Physical model

```text
reporting.fact_station_load        -- not partitioned: grain is date x station x hour, tens of rows a day per branch
  tenant_id uuid NOT NULL, business_date date NOT NULL, location_id uuid NOT NULL,
  station_id uuid NOT NULL,
  station_code varchar(32) NOT NULL, station_role varchar(16) NOT NULL,   -- as they were that day
  operating_hour smallint NOT NULL CHECK (operating_hour BETWEEN 0 AND 23), -- 0 = the location's business-day start
  portions numeric(12,3) NOT NULL CHECK (portions >= 0),
  tickets integer NOT NULL CHECK (tickets >= 0),
  boundary_version integer NOT NULL, metric_calculation_version integer NOT NULL,
  PRIMARY KEY (tenant_id, business_date, location_id, station_id, operating_hour)
  FK (tenant_id, location_id) -> tenant.locations; no FK to kitchen.stations (a derived copy, like fact_order_line.category_id)
  GRANT SELECT, INSERT, UPDATE, DELETE ON ... TO horecaos_application;  GRANT SELECT ... TO horecaos_reporting_read
```

`reporting.ensure_fact_partition` refuses tables it does not manage by name, so the
table is unpartitioned on purpose, the shape V0367 chose for `fact_forecast` for the
same reason. The producer is `JdbcReportingStore` (a `readSourceStationLoad` beside
`readSourceTenders`), written from `DayCloseService.close`. A one-time backfill
re-derives the last 56 days from `kitchen.tickets` by the same query, through the
close job's own recut path (ADR 0043: a recut re-derives a closed day from source)
and never by a migration (a migration does not read tenants' data). The next free migration number is taken across every active
worktree, per `AGENTS.md`.

`kitchen.station_productivity` is a policy: **no table**. `PolicyKey<StationProductivity>`
owned by `kitchen`, scopes `TENANT`, `BRAND`, `LOCATION`, empty document as the
default, registered with the startup validator, resolved through `PolicyResolver`,
published through `PolicyAuthor`.

### APIs (ADR 0031)

```text
GET  /api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/kitchen/headcount-plan
       ?weekday=1..7&sampleSize=3..12&holidayMode=INCLUDE|EXCLUDE|WEIGHT
       capability kitchen.headcount.read, LOCATION scope; no side effect
GET  /api/v1/operations/tenants/{tenantId}/kitchen-productivity?brandId=&locationId=     ETag = document version
PUT  /api/v1/operations/tenants/{tenantId}/kitchen-productivity?brandId=&locationId=     If-Match, Idempotency-Key, whole document
       capability kitchen.productivity.manage; read needs kitchen.headcount.read
```

The plan response carries `targetDate` (the next occurrence of the weekday, as
`ForecastService` computes it), `sampleDates`, `generatedFrom` (the metric id and
calculation version), one `rows[]` entry per station and hour (fields as Decision 5;
`status` is `OK`, `NO_POLICY` or `TOO_FEW_DATES`), and a `peak` object. A weekday outside
1 to 7 answers `VALIDATION_FAILED`, as `demand-history` does. An unknown location or
one in another tenant answers `RESOURCE_NOT_FOUND`. The two paths belong to existing
surface groups (ADR 0057); `everyPublishedPathBelongsToExactlyOneSurfaceGroup`
fails the build if either is not placed.

### Capabilities (ADR 0025)

`kitchen.headcount.read` and `kitchen.productivity.manage`, new, code-owned.
`kitchen.headcount.read` is bundled where `KITCHEN_STATION_MANAGE` is today
(`TENANT_OWNER`, `TENANT_ADMIN`, `LOCATION_MANAGER`) and not into `LOCATION_STAFF` or
`KITCHEN_DEVICE`:
a cook has no business reading what the branch plans to pay for. The write is the
same bundles. Neither is `REPORTING_READ`: that grants branch and company figures,
and this reads one branch's kitchen.

### Events, audit, PII, observability

- **Events (ADR 0032):** none. The plan is a read, the fact is written inside the
  close, and the policy is configuration. No schema file or catalogue entry is
  needed, and none is added.
- **Audit (ADR 0027):** every policy publication is a `BUSINESS` fact,
  `kitchen.station_productivity.published`, naming the scope, the actor, the reason,
  and `ChangeDocuments.diff` of the roles and stations added, removed or changed.
  The plan read is not audited: it holds no personal data and no money.
- **PII (ADR 0029):** none. The fact is station and hour counts; the policy is
  numbers by role and station code. No staff name, no staff identifier, no ticket
  or order identifier is stored in `fact_station_load`. A test asserts the table
  has no column typed to an order or a person.
- **Observability:** the close already reports per-fact row counts; this adds one.
  A counter `kitchen.headcount.plan` with bounded labels (`status`) and the read's
  latency. No per-station label (stations are tenant-defined, unbounded).

### Testing

- **The fact:** a ticket fired at 19:30 for a 20:00 promise counts in the 19:00
  hour; a ticket voided after firing still counts; a ticket voided before firing
  does not; two items on two stations split by `station_id`; decimal quantities sum
  (`0.5` plus `1`); recomputing a closed day reproduces byte-identical rows; the
  09:00 to 09:00 tenant's operating hours are relative to its own business day.
- **The plan:** a hand-computed fixture (four Fridays, two stations) gives
  `cooksExpected` and `cooksAtPeakOfBand`; `TOO_FEW_DATES` below three; `NO_POLICY`
  with an empty document; a station override beats a role; a brand document
  replaces the tenant's rather than merging; `plantLimited` appears exactly when the
  band top exceeds the lowest overlapping ceiling; `EXPO` is never planned;
  `HolidayMode` changes the mean as it does for `ForecastService`.
- **Boundaries:** cross-tenant and cross-location reads fail; the capability
  declarations are asserted for both endpoints; a location-scoped line cook is
  refused the read.
- **Console:** Card 2 renders each state (no policy, too few dates, plan, plant
  limit, denied); the not-a-rota sentence is present in all three locales.

## Rollout and rollback

Ship the fact and its metric first: it only adds rows and changes no screen, and the
56-day backfill proves the numbers against a pilot branch's board before anyone
reads them. Then the policy key and its editor, empty, so nothing changes. Then
the plan read and Card 2. Correct the three stale statements (the `capacity-page.ts`
comment, ADR 0041's pointer, row `2.6a`'s text) in the same change that ships the
card; the gap map is the owner's document and this record does not edit it.
Rollback at any step is hiding Card 2: the fact is inert, the policy is unread, and
`KitchenTicketService.capacityOffsetSeconds` is untouched throughout.

## Implementation checklist

- [ ] Owner answers (or accepts the defaults for) the open inputs above.
- [ ] Migration for `reporting.fact_station_load` with GRANTs; `MetricRegistry`
      entry `kitchen_station_portions.v1`; `JdbcReportingStore` source read and
      `DayCloseService` write; the recut and the 56-day backfill through it.
- [ ] `ModularArchitectureTests` green with the close reading `kitchen` (a SQL read,
      the ADR 0115 precedent; a `kitchen.api` port only if that test objects).
- [ ] `PolicyKey` `kitchen.station_productivity`, document type, validator (range
      1 to 1000; role names from `StationRole`; station codes matching
      `ck_station_code`); `PolicyAuthor` publication and audit fact.
- [ ] `HeadcountPlanService` (pure function over the fact rows, the policy and the
      station ceilings) and its two controllers; capabilities and role bundles.
- [ ] Card 2 on `CapacityPage`; the productivity editor on `q-inherited-field`;
      ru / uz-latn / en strings including the not-a-rota sentence.
- [ ] Correct `capacity-page.ts`'s comment and ADR 0041's pointer to say the
      forecast exists at branch grain and this record adds it at station grain.
- [ ] Tests listed under Testing, each seen failing first.

## Exit criteria

A branch manager opens Kitchen › Capacity, picks Friday, and sees for the grill, the
hot line and the bar the portions expected in each hour with their band and the dates
they came from; a station the tenant has stated a figure for shows cooks needed per
hour and the peak, and flags the hour where the station's own ceiling is below the
forecast; a station with no figure says so and shows no number; the page says it is a
planning aid and not a rota. The same day's fact rows reproduce byte-identically on a
recut. Gap-map row `2.6a` can be marked `BUILT` for tenants that have published a
figure.

## References

- ADR 0025, ADR 0027, ADR 0029, ADR 0030 (policy documents, replace-not-merge),
  ADR 0031, ADR 0032, ADR 0041 (station ceilings, "What was not built"), ADR 0043
  ("Forecasting", day close), ADR 0115 (the latest source added to the close),
  ADR 0136 (combos), ADR 0137 (decimal quantities)
- `platform/docs/operations-gap-map.md` rows `2.6`, `2.6a`, `7.8`, `7.8a`, `7.8b`
- `platform/docs/frontend-information-architecture.md` §2.6
- `platform/docs/delever-parity-matrix.md` ("Forecasting", unverified)
- `V0030`, `V0031`, `V0144`, `V0191`, `V0304`, `V0334`, `V0367`, `V0449`;
  `KitchenTicketService`, `StationRole`, `KitchenStationController`;
  `ForecastService`, `ForecastScheduler`, `ReportQueryService`, `HolidayAwareness`,
  `DayCloseService`, `JdbcReportingStore`, `MetricRegistry`;
  `frontend/operations/src/app/features/kitchen/capacity-page.ts`,
  `.../reports/demand-forecast-page.ts`
