# ADR 0141: Stop scope and channel propagation

- Decision status: Accepted
- Implementation status: Not started — no stop has a scope narrower or wider
  than one location, an end, or a recorded source, and no stop reaches a
  marketplace. Today a stop is `inventory.positions.binary_available = false`
  on one `(tenant, location, variant)` stock item (ADR 0017, `V0019`); it can
  only be set on a `BINARY`-tracked item (`InventoryService.setAvailability`
  throws for `UNTRACKED` and `QUANTITY`, and the bulk endpoint reports
  `UNSUPPORTED_TRACKING_MODE` per item); both write capabilities
  (`INVENTORY_ADJUST`, `INVENTORY_AVAILABILITY_MANAGE`) are `LOCATION`-scoped;
  every availability movement is written with `source_type = 'OPERATOR'`
  whoever caused it, and the "source" the stop list shows is inferred from a
  free-text reason string (`JdbcCatalogStore.stopSourceOf`: `POS_STOP_LIST`
  reads as POS, anything else as MANUAL). What is built and this record keeps:
  the position toggle and its ledger, `InventoryAvailabilityChanged` on
  `inventory.events`, the per-channel-type remaining-quantity threshold
  (`inventory.channel_stop_thresholds`, `V0407`) read by the storefront menu,
  the POS stop-list poll (`PosAvailabilityPoll`), the Telegram `/86` command,
  the digest (`InventoryStopDigestSweeper`), and the stop-list page. Not built:
  everything in "Specification" below, and ADR 0040's `marketplace.availability.push`
  capability has no adapter, no event and no table.
- Date proposed: 2026-09-29
- Date decided: 2026-10-01
- Deciders: proposed by Claude (wave batch 14, w7-adrs-stops-dispatch-walkin)
  from `platform/docs/operations-gap-map.md` row `2.5a`, blocked on an ADR;
  Ayubkhon Abbosov (platform owner) decides.
- Depends on: ADR 0004, ADR 0007, ADR 0012, ADR 0016, ADR 0017, ADR 0025,
  ADR 0026, ADR 0027, ADR 0030, ADR 0031, ADR 0032, ADR 0036, ADR 0040,
  ADR 0045, ADR 0058
- Supersedes / Superseded by: — (extends ADR 0017's availability projection;
  its ledger, reservations, `QUANTITY` branch and invariants are not touched, so
  nothing in ADR 0017 is superseded — the gap map's "superseding ADR 0017's
  binary model" is stronger than this design needs)
- Open inputs:
  - Which of Uzum Tezkor, Yandex Eda, Wolt and Express24 expose a
    menu-availability write API to a third party at all, with what item
    identifier, batch size and rate limit, and whether any of them lets a
    merchant read the state back. This record builds a provider-agnostic
    mechanism and one capability code (`marketplace.availability.push`, already
    reserved by ADR 0040); it cannot name a protocol, and ADR 0040 already
    treats admission of a third-party integration as a commercial question
    outside a design decision (product, partnerships; integration discovery).
  - What a "terminal" is for stop purposes. The IA (`frontend-information-architecture.md`
    2.5, PART 5 §3) lists scope as product × branch/menu/terminal/brand; the
    parity matrix says "POS-terminal-level (iiko)". This record folds terminal
    into channel (a kiosk or POS that takes orders is a `tenant.sales_channels`
    row, ADR 0036) and needs product to confirm that no tenant needs to stop a
    dish on one of two identical kiosks in the same room (product).
  - Whether a modifier option that is not itself a variant can be stopped. The
    parity matrix records Delever as inconsistent. This record stops variants
    only; a stopped variant that is also a modifier option's `linked_variant_id`
    is stopped wherever it is offered (product; overlaps ADR 0136's modifier
    depth).
  - Whether an operator stop should default to indefinite (today's behaviour) or
    to the end of the trading day. The mechanism supports both; the default is a
    product call (product, operations).
  - Whether an inbound aggregator order for a stopped item should ever be
    rejected back to the aggregator rather than accepted and flagged (this
    record accepts and flags, following ADR 0040's unmapped-line posture)
    (operations, partnerships).


**Decision record, 2026-10-01.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "accept all" over ADRs 0136–0144. Every open input above is closed on the default this record proposes for it; where a record defers an input to a named owner, that deferral stands as written and implementation proceeds without it. Implementation starts in operations batch 17 the same day.

## Context

**Gap-map row `2.5a` — "Stop scope (product × branch/menu/terminal/brand) and
channel propagation" — is `NOT BUILT`, severity `P`, `deferred`, and reads:**
*"A manager cannot 86 a dish across a brand or for one terminal only, and a stop
set here never reaches Yandex/Uzum/Wolt — those channels keep selling an item
the kitchen has taken off. Fixing it is a data-model change plus an outbound
adapter per aggregator."* `platform/docs/frontend-information-architecture.md`
PART 5 §3 states the underlying decision as still unreversed: *"Availability is
`(product × scope)` with a source, not a boolean. ADR 0017's binary model cannot
express counted daily stock with auto-reset, per-aggregator thresholds, or
terminal-scoped POS stops."* Two of those three halves have since been built
(counted stock with a daily reset and per-channel-type thresholds landed with row
`4.4c`, `V0407`); what remains is the scope, the source and the propagation.
`platform/docs/operations-spec/catalog.md` §4.6 says the same in its own words:
*"Per-aggregator stop thresholds and stop scope + stop source (POS terminal vs
operator vs rule) are likewise unowned ... Do not model these as a boolean with
a comment."* This record is the ADR those documents were pointing at.

**What a stop is today, exactly.**

- One boolean per stock item. `inventory.positions.binary_available` on the
  unique `(tenant_id, location_id, variant_id)` row of `inventory.stock_items`.
  `InventoryService.setAvailability` refuses any item that is not `BINARY`, so a
  kitchen cannot 86 an `UNTRACKED` dish at all — the console's bulk stop reports
  such an item as a failed row (`UNSUPPORTED_TRACKING_MODE`), not as a stop.
- Four writers, one column, no memory of who. The console's single toggle
  (`OPERATIONS_STOP_LIST_TOGGLE`), the console's bulk stop (an operator-picked
  reason code, 200 items at most), the Telegram `/86` command (`TELEGRAM_BOT_86`)
  and the POS poll (`POS_STOP_LIST`) all end in `InventoryService.setAvailability`.
  The poll calls it with `available = true` for every entity that
  `PosAvailabilityPoll.propagate` finds `newlyBackInStock`, so **a POS "back in
  stock" reading lifts a stop an operator set by hand**, and an operator lifting
  their own stop leaves no trace of the POS still saying the dish is out. One
  boolean cannot hold two opinions.
- Location scope only. `catalog.location_offerings` (ADR 0016) is per-location;
  `inventory.stock_items` is per-location; a brand with forty branches stops a
  dish forty times, and a branch bound later to the same menu starts life with
  the dish on sale.
- Read live, for the storefront only. `StorefrontCatalogQuery` reads availability
  through `catalog.api.MenuAvailabilityLookup`, implemented by
  `InventoryMenuAvailabilityLookup`, which calls
  `InventoryService.checkAvailabilityForChannel`: a `BINARY` stop hides the dish on
  every channel, and a `QUANTITY` item is additionally hidden on a channel whose
  `system_type` has a row in `inventory.channel_stop_thresholds` and whose
  remaining quantity is at or below it. `CartService.requireAvailable` and
  `InventoryService.reserveForQuote` pass no channel (`evaluateAvailability(...,
  null)`), so the batch-11 threshold is a projection-only cutoff by deliberate
  design (its migration says a direct-channel order "can still hold and commit stock
  an aggregator has already stopped selling").
- Not read by any marketplace. ADR 0040 declared `marketplace.availability.push`
  and `MarketplaceAvailabilityPushed` and states "Menu is pull-first" with a
  partner `GET .../restaurants/{locationId}/availability`; the controller
  (`PartnerOrderController`) exposes only `POST /orders`, the outbound Camel
  adapters ADR 0040 lists as unbuilt do not exist, and
  `MarketplaceIngestionService` never consults inventory, so an aggregator order
  for a stopped dish is accepted like any other. The batch-11 threshold for
  `AGGREGATOR` therefore has no live consumer: nothing calls the menu read under
  an aggregator channel code.
- Announced, not enforced elsewhere. `InventoryAvailabilityChanged`
  (`{variantId, locationId, available, reasonCode}`) reaches `inventory.events`
  through the outbox and the in-process `ItemAvailabilityChanged` reaches the
  ADR 0058 digest. ADR 0045 registers a `STOP_LIST` realtime channel (source
  `catalog.events`, capability `catalog.read`); nothing produces on it.

**What exists beside it and must not be confused with it.**

- `catalog.channel_offering_exclusions` (ADR 0036, `V0020`) already suppresses
  one variant on one channel, optionally at one location, has a reader
  (`JdbcCatalogStore.channelExcludedVariantIds`) and a writer since wave P45. It
  is authored assortment: it has a `reason_code`, no end, no source and no lift
  semantic, and it lives in `catalog`.
- `catalog.location_offerings.status` (`AVAILABLE`/`UNAVAILABLE`/`HIDDEN`) is the
  per-branch assortment switch of ADR 0016.
- `catalog.menus` and `catalog.branch_menu_bindings` (`V0389`/`V0390`, row `4.4a`)
  give a brand a named assortment bound to a branch, by default or for one
  channel. A "menu" scope for a stop has an entity to point at that it did not
  have when the IA was written.
- `tenant.location_service_state` closes a whole branch, with an expiry.

Three mechanisms already say "not for sale here" for authored reasons. The gap is
the fourth thing — an operational, attributable, often temporary refusal that must
reach a party outside the platform — and the risk in adding it carelessly is that
"why can't I sell this?" gets a fourth answer instead of a shorter one.

## Decision

**A stop is a record with a scope, a source and an optional end, held in a new
inventory-owned table, and an outbound reconciler makes each marketplace agree
with what the platform currently believes. The position boolean is not replaced.**

1. **Two kinds of "not sellable" stay separate, and one function reads both.**
   *Supply state* is what ADR 0017 already owns: the `BINARY` boolean and the
   `QUANTITY` remaining count, "we do not have it". An *embargo* is new: "we, or
   a system we trust, have decided not to sell it here, there, or until then". A
   variant is sellable at `(location, channel)` when it is offered, its supply
   state allows it, **no active embargo covers that `(variant, location,
   channel)`**, and — for `QUANTITY` items only, exactly as built — its remaining
   quantity is above the channel type's threshold. One resolver
   (`AvailabilityResolver`, in `inventory.application`) is the only reader of all
   of these; the storefront menu, cart, checkout, the stop-list page, the
   "why can't I sell this?" explainer `catalog.md` §4.6 specifies (row `2.5b` built
   its Source column and tooltip, not the dialog) and the marketplace reconciler
   all call it.

2. **Scope is four values, and the union of covering embargoes stops the sale.**
   `LOCATION` (every channel at one branch), `BRAND` (every branch of the brand,
   including one bound later), `MENU` (every `(location, channel)` whose resolved
   `catalog.branch_menu_bindings` menu is that menu) and `CHANNEL` (one
   `tenant.sales_channels` row, at one location or everywhere it runs). There is
   no "allow" record that overrides a broader stop: a brand-wide stop is
   authoritative until someone with brand scope lifts it.

3. **"Terminal" is not a fifth scope.** A device that takes orders and needs its
   own menu behaviour is already a sales channel — ADR 0036 makes `KIOSK`, `POS`
   and `QR_TABLE` channel types, and the parity matrix shows Delever registering
   each kiosk as a channel instance. A stop "for the kiosk" is a `CHANNEL` stop
   at that location. `TERMINAL` stays a named, refused value in the scope
   vocabulary (the pattern `V0034` used for `SETTLE_OPEN_TICKET`) until a device
   registry exists that can name two terminals in one channel.

4. **Source is data, and sources do not lift each other.** A closed, code-owned
   vocabulary — `OPERATOR` (console single and bulk), `BOT` (Telegram `/86`),
   `POS` (the poll, with the binding as `source_ref`), `KITCHEN_DEVICE` (ADR 0041's
   origin, reserved) and `RULE` (reserved) — is written on every embargo row.
   The POS poll stops writing the position boolean and writes and ends its own
   `POS` embargo; a POS "back in stock" ends only `POS` rows, so it can no longer
   un-86 a dish an operator stopped, and an operator lifting theirs no longer
   erases the POS's opinion. Movement rows written by the existing toggle start
   carrying the true `source_type` (`OPERATOR` or `BOT`); no migration is needed
   for that, the column has no `CHECK`.

5. **An embargo has an optional end, evaluated at read.** `ends_at` in the past
   means the row covers nothing, with no sweeper on the correctness path of any
   read; a sweeper only marks the row `EXPIRED`, writes the audit fact and emits
   the event so the digest hears about it and the reconciler hears about it sooner.
   The reconciler's correctness does not rest on that sweeper: the resync sweep of
   Decision 7 evaluates `ends_at` at its own `now`, so an embargo that expired while
   the sweeper was down or lagging still restores the dish on the partner within
   one resync interval. An operator stop keeps today's default (indefinite); a bulk
   or single stop may name "until the end of the trading day", computed from the
   tenant's business-day boundary (`BusinessDayWindows`, already used by the daily
   quantity reset).

6. **An embargo refuses at cart and checkout for the channels it covers; a
   threshold still only hides.** This keeps batch 11's decision intact for
   thresholds (a reserve for direct channels) and gives an operator's explicit
   instruction the force the word "stop" implies. The reservation port gains the
   caller's channel; a covered channel is refused with a new reason code
   `ON_STOP` beside `SOLD_OUT`, `NOT_STOCKED_AT_LOCATION` and `CHANNEL_STOPPED`.
   An inbound aggregator order for a stopped dish is accepted and flagged, not
   rejected — the aggregator has taken the customer's money, `RejectionCode` has no
   item-availability code, and ADR 0040 already accepts a line it cannot classify
   rather than refusing a paid order over a menu-sync lag.

7. **Propagation to a marketplace is a level-triggered reconciler, not a replay
   of events, and its guarantee is a periodic full recompute — not the dirty
   markers.** Each `(binding, mapped item)` row keeps the *desired* availability,
   recomputed from the resolver, and `confirmed_available`, what the partner is
   *known* to hold (null when that is not known). A worker, at-least-once and
   claimed under a lease, sends whenever the two differ or the partner's state is
   unknown, through a Camel route (ADR 0007) that implements
   `marketplace.availability.push`. Two mechanisms recompute desired, and only one
   is load-bearing. **Markers** — a row marked dirty in the same transaction as any
   change to a resolver input (the list is under "Marketplace propagation") — are an
   accelerator: they get a change to the partner in seconds. **The resync sweep**
   — every mapped item of every active binding, recomputed through the resolver at
   least every `resync_interval` — is the guarantee: it catches an input change that
   carries no marker (a branch rebound to a menu that carries a `MENU` stop, an
   offering switched off, an `ends_at` passing) and any input the resolver gains
   after this record, so a missing marker delays a correction and never prevents
   it. And a push whose outcome is unknown does not leave the platform believing the
   partner holds the old value: it sets `confirmed_available` to null, and the next
   tick sends the current desired value whatever it is. What the partner is told is
   always the current truth, never an old event; a stop and a lift that both happen
   while no push could have reached the partner collapse into nothing to send. A stop
   is pushed before a restore (fail toward under-selling).

8. **Availability pushed to a marketplace is binary.** ADR 0040 already decided
   that "availability is binary"; thresholds and counts are translated to a
   boolean when the reconciler computes the desired state. A partner that needs a
   quantity is a separate decision.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Replace `binary_available` with a stops table as the only authority (the gap map's "superseding ADR 0017's binary model") | Rewrites every writer (console single and bulk, Telegram, POS poll), the movement ledger's meaning, the digest, the stop-list SQL and the reservation path in one release, to change a boolean that works into a set that must be migrated. ADR 0017's supply-state semantics are correct and are not what is missing; the embargo overlay adds what is | The overlay-plus-boolean composition causes a correctness incident a single authority would have prevented, or a fourth supply-state source cannot be expressed as either supply or embargo |
| Fan a brand or menu stop out to per-location toggles at write time | Needs no new model, but a brand stop becomes N writes, N audit facts and N events; lifting it can miss a branch bound in between; a branch bound later starts with the dish on sale; and `source` has nowhere to live | Never for `BRAND` and `MENU`; acceptable only for a tenant with two or three branches, which needs no ADR |
| Model an embargo as a `catalog.channel_offering_exclusions` row with an end and a source added | Cheapest: the table already has channel and optional location scope, a reader and a writer. Rejected because an exclusion is *authored assortment* that lives until someone re-includes it; the sources that matter here (the POS poll, Telegram, the threshold) act on stock and are inventory's, and putting their writes in `catalog` inverts the dependency (`inventory` implements catalog's lookup, not the reverse). It also has no `BRAND` or `MENU` scope | Product decides that "temporarily off Uzum" and "not on Uzum" are one thing to operators — then add `ends_at` and `source` to exclusions and drop the `CHANNEL` scope here rather than keep two tables |
| Reconcile only the rows a fixed list of triggers marked dirty | The trigger list becomes the correctness argument, so every resolver input that no trigger names (a menu rebinding, an offering status, an `ends_at` with a stalled sweeper, an input added next year) is a silent divergence with nothing to detect it, and the partner keeps selling a dish the kitchen stopped | Never as the only mechanism; markers stay as the accelerator, the periodic full recompute is the guarantee |
| Push each availability event to the partner as it happens (edge-triggered consumer of `inventory.events`) | An outage replays a backlog in order, so a partner recovering from an hour of downtime receives an hour of intermediate states, and an event lost or reordered leaves the partner wrong until the next change to that dish with nothing to detect it. A reconciler over desired state has neither failure | A partner API only accepts sequenced deltas and rejects state-set calls — then the reconciler emits deltas from the same desired-versus-confirmed diff |
| Pull only: the partner polls ADR 0040's `GET .../availability` | Zero outbound state and cannot go stale differently per partner. Rejected as the *only* mechanism because a poll interval is the partner's to choose (minutes), which is how a 14:32 stop is still on sale at 14:40, and some partners have no poll contract at all. Kept as a second consumer of the same resolver | A partner commits in writing to poll at or under 60 seconds |
| A fifth scope, `TERMINAL`, now | There is no terminal entity to point at: kiosk pairing is gap-map row `10.5` (not built), the POS binding is a venue, `fiscal.fiscal_terminals` is unrelated. A scope with no referent is a column nothing can fill | A device registry with a per-device principal exists and a tenant runs two identical devices in one channel at one location |
| Allow-records that override a broader stop at a narrower scope | Lets a branch sell through a brand recall, and turns "why can't I sell this?" into a precedence puzzle — the exact question the explainer exists to shorten | A tenant needs a pilot branch to keep selling a dish the brand has paused, and product accepts the recall risk |
| Persist threshold crossings as embargo rows with source `THRESHOLD` | Needs a writer on every commit, release and adjustment of a `QUANTITY` item to keep rows equal to a value that is already a pure function of quantity; the read-time evaluation batch 11 built is correct and cheap | Reconciler volume shows that detecting a crossing without an event costs more than writing the row |
| Per-aggregator thresholds now (`channel_stop_thresholds` keyed by channel instance) | Batch 11 chose `system_type` on purpose ("stop every aggregator at 3"). Nothing here needs it, and changing a shipped key in the same ADR as the scope model doubles the review surface | A tenant needs Yandex at 3 and Uzum at 5 — then add a nullable `channel_id` to the threshold table, more specific row wins |

## Consequences

### Positive

- A manager can stop a dish for a brand, a menu or a channel in one action with
  one audit fact, and lift it in one action; a branch bound tomorrow is covered
  without anyone remembering.
- An `UNTRACKED` or `QUANTITY` dish can finally be 86'd; the bulk stop stops
  reporting them as failed rows.
- The stop list can say who stopped a dish and why from a column, not a parsed
  string, and the POS-lifts-manual-stop defect is closed by construction.
- A marketplace converges on the platform's current truth after any outage
  without replay, and the platform can say which items it has not been able to
  confirm and since when.
- The batch-11 threshold finally has a live consumer for the `AGGREGATOR` type:
  the reconciler computes it.

### Negative

- Two places now answer "is this stopped": the position boolean and the embargo
  rows. Every reader must go through `AvailabilityResolver`. The stop list's own
  SQL (`JdbcCatalogStore.variantsAtLocation` and its counts) joins
  `inventory.positions` directly and would silently ignore embargoes until moved;
  a missed reader shows a dish as sellable that the storefront refuses.
- "On stop" stops being one bit. The stop-list page needs a scope and a source
  column, and the tab counts have to say what they count.
- The storefront menu render, every cart add and every checkout gain one more
  indexed read (embargoes by variant set), plus a menu-binding lookup when any
  `MENU` embargo exists.
- The platform gains an outbound dependency whose latency and rate limits it does
  not control. A brand-wide stop of a hundred dishes across forty bound venues is four
  thousand pushes; at a partner's rate limit that is minutes during which the
  partner keeps selling.
- A stop and a lift inside one partner outage never reach the partner, provided no
  push in between could have been applied. Correct, and confusing to whoever asks why
  the partner never saw the 14:32 stop; the propagation ledger has to answer. If a
  push in between timed out its outcome is unknown, so the platform resends rather
  than assume, and the partner may receive a state-set it did not strictly need —
  the safe error.
- The resync sweep reads every mapped item of every active binding every few
  minutes. It is one batched indexed read per binding through the resolver, the
  cost of one storefront menu render, and it sends nothing when nothing differs;
  forty bindings at a five-minute interval is a read about every seven seconds. A
  resolver too slow for that is a finding about the resolver, not a reason to drop
  the sweep.
- A `BRAND` embargo covers a branch bound after it was made. Right for a recall,
  surprising to a new branch manager who asks why plov is off; the explainer has
  to name the brand stop.
- Union-only precedence means a branch cannot override a brand stop.
- Threshold granularity (channel type) and stop granularity (channel instance)
  differ, and an operator will notice.
- Rollback is asymmetric. Freezing and suspending are safe and instant; ceasing to
  consult embargoes is not, and needs a materialisation run and an acknowledged
  report because some stops (`UNTRACKED`, `QUANTITY`, `CHANNEL`) have no position to
  land on. A build that offers one switch labelled "disable" hides that.
- New capability `inventory.stop.manage` and a new event `InventoryStopChanged`
  are code-owned registry entries (ADR 0025, ADR 0032): each is a release.

### Accepted trade-offs

- Modifier options that are not variants cannot be stopped; a product-level stop
  is a group of variant stops written together, so a variant added to the product
  afterwards is not covered.
- No stop can be scheduled to start later. That is the per-item sale window (row
  `4.2g`), which already refuses a variant outside its window at cart time.
- Quantities are not pushed to marketplaces; thresholds collapse to on/off at
  the edge (ADR 0040).
- Brand-wide stops are audited (ADR 0027) but not maker-checker gated by default.

## Specification

### Physical model (additive; number reserved by the wave that builds it)

```text
inventory.availability_stops
  id uuid pk, tenant_id, brand_id
  variant_id                       -- composite FK (variant_id, tenant_id, brand_id) -> catalog.variants
  scope_type    LOCATION | BRAND | MENU | CHANNEL      -- TERMINAL: named in code, refused
  location_id null   -- required for LOCATION, optional for CHANNEL, null for BRAND and MENU
  menu_id null       -- required for MENU, composite FK -> catalog.menus
  channel_id null    -- required for CHANNEL, composite FK -> tenant.sales_channels
  source        OPERATOR | BOT | POS | KITCHEN_DEVICE | RULE
  source_ref    varchar(64) null   -- POS: the integration.bindings id; never a name or a phone
  reason_code   varchar(64) not null   -- a short enumerated code, ADR 0029: never free text
  ends_at       timestamptz null
  status        ACTIVE | LIFTED | EXPIRED
  group_id uuid null               -- one gesture, many variants: bulk, or a product-level stop
  created_by varchar(128), created_at, lifted_by null, lifted_at null
  version, timestamps
  ck: scope columns present exactly as listed above
  unique (tenant_id, variant_id, scope_type, location_id, menu_id, channel_id, source)
         WHERE status = 'ACTIVE'   -- nulls compared as equal (NULLS NOT DISTINCT)
  index  (tenant_id, variant_id) WHERE status = 'ACTIVE'
```

Rows are never deleted; `LIFTED` and `EXPIRED` rows are the history. Position
`movements` are per stock item, and a `BRAND` stop has no stock item, so the
record is the row plus the ADR 0027 audit fact, not a ledger row per branch.
`GRANT SELECT, INSERT, UPDATE, DELETE` to `horecaos_application` (see `V0035`);
Row-level security follows the module's existing tables (ADR 0056).

```text
integration.marketplace_item_availability
  tenant_id, binding_id, external_entity_id       -- the MENU_ITEM mapping ADR 0040 already uses
  variant_id
  desired_available boolean not null, desired_seq bigint not null
  confirmed_available boolean null, confirmed_at timestamptz null   -- what the partner is KNOWN to hold; null = unknown
  last_attempt_available boolean null, last_attempt_at timestamptz null
  last_attempt_outcome  CONFIRMED | NOT_APPLIED | UNKNOWN | null      -- what the gateway route concluded, not a FailureCategory
  state          IN_SYNC | PENDING | UNCERTAIN | REJECTED_UNMAPPED | SUSPENDED
  attempt_count integer, next_attempt_at timestamptz null, last_failure_code varchar(48) null
  lease_owner null, lease_expires_at null          -- the JdbcSourcingJobStore pattern
  primary key (binding_id, external_entity_id)
  ck: state <> 'IN_SYNC' OR (confirmed_available IS NOT NULL AND confirmed_available = desired_available)
        -- IS NOT NULL written out: a CHECK passes on NULL, and "in sync with an unknown" is the bug
  ck: state <> 'UNCERTAIN' OR confirmed_available IS NULL
```

`state` is derived, never authoritative: a row is `IN_SYNC` only when
`confirmed_available` is known and equals `desired_available`; `UNCERTAIN` is a row
whose `confirmed_available` an `UNKNOWN` attempt nulled and that has had no
`CONFIRMED` since (a later `NOT_APPLIED` attempt does not clear it); the diff the
worker sends on is `confirmed_available IS DISTINCT FROM desired_available`, so a null
always sends. A row created for a new mapping starts with `confirmed_available` null,
which makes the first push of any item unconditional.

### Resolution

```text
sellable(variant, location, channel) =
      offered(variant, location, channel)                       -- ADR 0016 / 0036, unchanged
  AND supply_ok(variant, location)                              -- BINARY boolean | QUANTITY remaining >= 1 (with catalog.use_stock_logic on) | UNTRACKED
  AND NOT EXISTS active embargo covering (variant, location, channel)
  AND (tracking != QUANTITY OR remaining > threshold(channel.system_type))   -- projection only
```

An embargo covers `(variant, location, channel)` when it is `ACTIVE`, `ends_at`
is null or in the future, and one of: `LOCATION` with that location; `BRAND` with
that brand; `CHANNEL` with that channel and either no location or that location;
`MENU` with the menu that `catalog.branch_menu_bindings` resolves for that
`(location, channel)` — the channel-specific binding, else the branch default,
else none — read through a new `catalog.api` lookup so `inventory` never reaches
into catalog tables. The resolver returns every covering stop, not the first, so
the explainer can list them. `threshold(...)` is `V0407`, unchanged.

### Sources

| Source | Writer | Scope it writes | Lifts |
|---|---|---|---|
| `OPERATOR` | Console single and bulk stop | Any; `LOCATION` by default | Console, or `ends_at` |
| `BOT` | Telegram `/86` (ADR 0060) | `LOCATION` | The same command, or `ends_at` |
| `POS` | `PosAvailabilityPoll` | `LOCATION`, `source_ref` = binding | The next poll that reads the entity back in stock, or a binding suspended or removed |
| `KITCHEN_DEVICE` | ADR 0041 KDS origin | `LOCATION` | Reserved; refused until ADR 0041's device write path exists |
| `RULE` | A future rule engine | Any | Reserved; refused |

The position toggle for a `BINARY` item is unchanged in Phase 1; for an
`UNTRACKED` or `QUANTITY` item the same gesture writes a `LOCATION`/`OPERATOR`
embargo instead of failing. Whether the 86 gesture for `BINARY` items should also
move to an embargo row (leaving the boolean to supply facts alone) is a later
decision, taken when the POS source has moved and the composition has run in
production.

### APIs

```text
POST   /api/v1/tenants/{tenantId}/brands/{brandId}/inventory/stops                    scope BRAND, MENU, CHANNEL (all locations)
POST   /api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/inventory/stops   scope LOCATION, CHANNEL (one location)
DELETE /api/v1/tenants/{tenantId}/brands/{brandId}/inventory/stops/{stopId}           If-Match; lifts
GET    /api/v1/tenants/{tenantId}/brands/{brandId}/inventory/stops?variantId=&scope=&source=   INVENTORY_READ
GET    .../locations/{locationId}/inventory/variants/{variantId}/availability-explanation?channel=
GET    .../locations/{locationId}/inventory/marketplace-propagation                    per-binding pending items and since when
GET    /api/v1/partner/tenants/{tenantId}/restaurants/{locationId}/availability       ADR 0040's pull, over the same resolver
```

A create takes a variant id, a product id (expanded to its variants in one
transaction under one `group_id`) or, in bulk, up to 200 variant ids (the cap
`InventoryBulkAvailabilityService.MAX_ITEMS` already has), a scope, a
`reasonCode`, and an optional `endsAt`. Per ADR 0031 every mutation carries an
`Idempotency-Key`; a lift carries `If-Match`. Bulk keeps the per-item outcome
contract `POST .../variants/bulk-availability` already returns (one failed item
never rolls back the rest, ADR 0039's argument).

### Capabilities (ADR 0025)

`INVENTORY_AVAILABILITY_MANAGE` at `LOCATION` (existing) for the location-path
create and its lift. New `inventory.stop.manage` at `BRAND` for the brand-path
create and its lift: a location manager must not be able to stop a dish for the
brand, and one endpoint can declare only one scope. Reads use `INVENTORY_READ` at
the matching scope. `EndpointCapabilityDeclarationTests` must stay green with the
two routes declared.

### Marketplace propagation

**What recomputes a row.** Two paths, and only the second is a guarantee.

*Markers, an accelerator.* In the same transaction as the change (a
`BEFORE_COMMIT` listener, the pattern `InventoryOutboxEventListener` uses), the
affected `(binding, mapped item)` rows are marked dirty and recomputed at the next
tick instead of the next resync, for: every embargo create, lift and expiry; every
position toggle; every `QUANTITY` movement that can cross a channel threshold; and
every change to the other inputs the resolver reads — a
`catalog.location_offerings` status change, a `catalog.channel_offering_exclusions`
add or remove, a `catalog.branch_menu_bindings` bind, rebind or unbind (a branch
rebound to a menu that carries a `MENU` stop changes desired for every item without
touching an embargo row), a `tenant.sales_channels` change to
`provider_installation_id` or status, and a new, changed or removed `MENU_ITEM` row
in `integration.provider_entity_mappings`. The list exists so that a missed entry
costs a delay. It is not the correctness argument and cannot be: an input the
resolver gains next year has no marker on the day it ships.

*The resync sweep, the guarantee.* Every active `MARKETPLACE` binding is recomputed
in full — every mapped item, one batched resolver read, the call the storefront menu
render already makes — at least every `resync_interval` (an ADR 0030 setting, default
5 minutes, jittered per binding so forty venues do not tick together), and at once
when a binding becomes active, when an adapter starts declaring
`marketplace.availability.push`, and when a suspended reconciler is resumed. It sends
only differences, so its cost is database reads and not partner calls. Every
time-dependent input is evaluated at the sweep's own `now` — an embargo's `ends_at`
above all, and any sale window or trading-day boundary the resolver consults — which
is why an expiry the `EXPIRED` sweeper never marked still restores the dish.
Threshold crossings ride the same sweep, which replaces the interim per-binding
threshold sweep for as long as `InventoryPositionChanged` has no producer. Because
expiry is re-evaluated here and not when a row happens to be marked dirty, the
`EXPIRED` sweeper is not on the propagation correctness path.

**What is pushed.** For each `MARKETPLACE` binding `B` (installation `I`, location
`L`) the channel is the `tenant.sales_channels` row whose
`provider_installation_id = I` (ADR 0036). For each mapped item the desired value
is `sellable(variant, L, channel)` evaluated at the reconciler's own `now`. A `BRAND` embargo therefore expands to one
row per binding of that brand's aggregators at the moment the reconciler runs,
not at write time.

**How.** Claimed rows are sent through a `MarketplaceGateway` route mirroring
`DeliveryGateway` and `PosGateway`: capability `marketplace.availability.push`
declared by the adapter, circuit breaker per binding, `RetryBackoff` (equal
jitter) between attempts, failure category (retry, backoff, alert) from
`FailureClassifier`. The call is a state-set (`available = true|false`) keyed by
`(binding, item, desired_seq)`, which makes a retry naturally idempotent. A partner
refusal that is a business answer (unknown item id) is not retried: the row becomes
`REJECTED_UNMAPPED` and appears in the mapping pane (ADR 0012).

**What the platform believes the partner holds after an attempt.** The gateway route,
not `FailureClassifier`, concludes one of three outcomes. `CONFIRMED`: a success
answer. `NOT_APPLIED`: no request was written (connection refused, breaker open, a
rate-limit rejection before the send) or the partner answered with a refusal that
changed nothing (a 4xx business answer, a 429). `UNKNOWN`: everything else — above
all a timeout or a reset after the request was written, and any 5xx whose contract
does not promise atomicity. `FailureClassifier` cannot make this call: it files
`SocketTimeoutException` and `ConnectException` under the same
`TRANSIENT_INFRASTRUCTURE`, and only one of them leaves the partner's state as it
was. On `CONFIRMED` the row records the value sent as `confirmed_available`; on
`NOT_APPLIED` it changes nothing; on `UNKNOWN` it sets `confirmed_available` to null
and `state` to `UNCERTAIN`, and only a later `CONFIRMED` clears that. Without this,
a timed-out restore the partner did apply, followed by a stop, would diff
`false` against a stale confirmed `false`, mark the row `IN_SYNC` and send nothing
while the partner sells the stopped dish.

**When the partner is down.**

- The stop is committed locally at once; the storefront and every direct channel
  obey it immediately. Only the partner is behind.
- Rows stay `PENDING`, retried under the backoff; the breaker opens after
  repeated failures and probes half-open, so an outage costs probes, not a storm.
- Stops are claimed before restores. A restore that cannot be pushed leaves the
  dish off-sale on the partner longer than necessary, which is the safe error.
- `integration.provider_activity_watermarks` (`direction = OUTBOUND`, ADR 0040)
  records last success and last failure; past `stale_after_seconds` the channel
  raises `MarketplaceChannelWentStale` and an ADR 0006 failure an operator must
  resolve, and the stop-list page shows "Yandex: 7 items not confirmed since
  14:32 — update in the partner portal". An item unconfirmed longer than a
  configurable bound (ADR 0030) raises an ADR 0058 alert.
- An inbound order for a stopped dish during the gap is accepted and flagged on
  the order for the pass; it is never silently dropped.
- On recovery the reconciler sends the current state of every `PENDING` and
  `UNCERTAIN` row once, whatever `confirmed_available` last said.
- When a suspended reconciler is resumed, or a binding's watermark went stale, every
  row of that binding has `confirmed_available` set to null first: the partner
  portal may have been edited by hand in the meantime (the stop-list banner tells the
  operator to do exactly that), so resumption resends everything once.

**When there is no push API.** A binding whose adapter does not declare
`marketplace.availability.push` shows `MANUAL` on the propagation read and the
stop list, with the words "not propagated automatically". ADR 0011's rule — an
unsupported capability may never be the sole business path — is met by saying so
where the operator will see it.

### Events

`InventoryStopChanged` v1 on `inventory.events`, aggregate `Variant`, partition by
variant: `{stopId, variantId, scopeType, locationId?, menuId?, channelId?, source,
active, endsAt?, reasonCode}` — no names, no free text, no personal data (ADR
0029). Schema file, `EventCatalog` entry and a row in `docs/domains/events.md`
before the producer ships (ADR 0032). `InventoryAvailabilityChanged` is unchanged.
The `STOP_LIST` realtime channel (ADR 0045) gets its first producer from the same
transaction. The marketplace events ADR 0040 declared (`MarketplaceAvailabilityPushed`)
are produced by the reconciler on a confirmed change.

### Audit and observability

`inventory.stop.created`, `inventory.stop.lifted`, `inventory.stop.expired` as ADR
0027 facts with `ChangeDocuments.created`/`diff` (the source-scan guard
`ChangeDocumentUsageTests` fails on a flat `.changed(...)`). Metrics with bounded
labels: pending items per binding, oldest pending age, push outcomes by
classification, breaker state, resolver cache-free read latency.

### Testing

- The resolver truth table: supply × each scope × channel × threshold, including
  union precedence (a covering brand stop beats a branch with nothing) and that a
  threshold never refuses a hold while an embargo does.
- Expiry at read: a stop one second past `ends_at` covers nothing without a sweep.
- A `BRAND` stop covers a location bound after it was made.
- `MENU` resolution with a channel-specific binding, a default binding and none.
- The POS source: back-in-stock ends only `POS` rows; a manual stop survives it.
- Cart and checkout refuse an embargo on its channel and sell on another.
- Reconciler against a fake partner (ADR 0007 provider-contract suite): outage,
  recovery, stop-then-lift collapsing to no call, duplicate and out-of-order dirty
  markers, breaker open and half-open, partner 4xx unknown item, and a stop pushed
  before a restore.
- Unknown outcome: the fake partner confirmed `false`; the stop is lifted; the push
  of `true` times out after the fake partner applied it; the dish is stopped again.
  The reconciler sends `false` and the partner ends `false`, and the row is never
  `IN_SYNC` on a diff against the stale confirmation. Seen failing first against a
  reconciler that diffs desired against the last confirmed value alone. A
  connection-refused attempt in the same position leaves `confirmed_available`
  untouched and the stop-lift pair still collapses to nothing.
- No marker: each of a branch rebound to a menu carrying a `MENU` stop, an offering
  set `UNAVAILABLE`, a channel exclusion added, a new `MENU_ITEM` mapping, and an
  embargo whose `ends_at` passes with the `EXPIRED` sweeper disabled, with the marker
  listener disabled, converges on the fake partner within one `resync_interval`
  (the clock is advanced). Seen failing first with the resync sweep removed.
- A resumed reconciler resends every row of the binding once.
- Freeze: with creation frozen, a `BRAND` embargo made earlier still hides the dish
  and refuses it at cart and checkout on its channels, a new `OPERATOR` create
  answers `STOPS_FROZEN`, a lift succeeds, and the POS poll still writes and ends
  its own `POS` rows. Seen failing first against a switch that turns the resolver's
  embargo read off.
- Decommission: `inventory.stops.read_enabled` refuses to turn without an
  acknowledged materialisation report and again after any embargo is created
  since. The run writes `binary_available = false` for a `LOCATION` embargo and for
  a `BRAND` and a `MENU` embargo over their locations' `BINARY` items, and lists
  every `UNTRACKED`, `QUANTITY` and `CHANNEL` stop. After the switch, a recalled
  `BINARY` dish is still refused at checkout while the same recall on an `UNTRACKED`
  dish is on sale and is on the report (both asserted, so the report is the only
  place a stop can vanish), and a POS "back in stock" lifts a materialised POS stop.
- Cross-tenant and cross-brand reads and writes fail; capability declaration test.

## Rollout and rollback

**Phase 0, no schema.** Write the true `source_type` on availability movements and
change `stopSourceOf` to read it, so the stop list stops parsing a reason string.
**Phase 1.** The embargo table, the resolver and the `LOCATION` path behind the
ADR 0030 switches named under "Rollback"; an empty table changes nothing (the
`V0389`/`V0390` posture).
Move the stop-list SQL, the storefront lookup and the cart/checkout ports onto the
resolver. **Phase 2.** `BRAND`, `MENU` and `CHANNEL` scopes; the POS poll moves to
its own embargo. **Phase 3.** The reconciler for one aggregator at one branch in a
dry-run that computes and logs desired-versus-confirmed without calling the
partner, compared against that partner's portal for a week, then live — after
ADR 0040's inbound step has proven the binding. **Phase 4.** The partner pull
endpoint over the same resolver.

### Rollback: freeze, do not disable

After Phase 1 an embargo row is, for many stops, the only record that a dish is
stopped: an `UNTRACKED` or `QUANTITY` item has no position boolean to fall back to; a
`BRAND`, `MENU` or `CHANNEL` stop has no position at all; and after Phase 2 a POS stop
is an embargo row and no longer a boolean. Ceasing to consult embargoes therefore does
not return the system to "exactly as today". It sells every one of those dishes again
at the storefront, cart and checkout in the same instant, the POS poll no longer
writes a boolean that would bring a POS stop back, and the reconciler, suspended
alongside, leaves each aggregator holding whatever it last had. So rollback is three
independent ADR 0030 switches, and the ordinary rollback uses only the first two:

1. **Freeze new stops** (`inventory.stops.creation_enabled = false`). The create
   routes for `OPERATOR` and `BOT` stops answer `409 RESOURCE_CONFLICT { conflict:
   "STOPS_FROZEN" }` and the console says scope stops are paused. Lifts, expiry and
   the resolver's reads continue, so every stop already made stays in force on
   every channel and nothing is sold again. A `BINARY` item is still stoppable
   through the position toggle exactly as in Phase 0; an `UNTRACKED` or `QUANTITY`
   item is not, as today, and the console says so. The POS poll keeps writing and
   ending its own `POS` embargoes: after Phase 2 they are the only record of a POS
   stop, and the poll acts on the diff (`newlyOutOfStock`, `newlyBackInStock`) and
   will not repeat an "out of stock" it has already reported.
2. **Suspend the reconciler** (`marketplace.availability.reconcile_enabled =
   false`). No call reaches any partner, every affected channel shows `MANUAL` with
   "not propagated automatically", and the resolver is unaffected. On resume every
   row of the binding has `confirmed_available` nulled first (see Marketplace
   propagation), so the resumption resends everything once.
3. **Stop consulting embargoes** (`inventory.stops.read_enabled = false`). This is
   the only switch that can sell a stopped dish again, so it is a decommission and
   not a rollback, and it refuses to turn (`409 MATERIALISATION_REQUIRED`) until a
   *materialisation run* has completed and its report has been acknowledged by a
   holder of `inventory.stop.manage` at `BRAND` scope; an embargo created after the
   run re-blocks it. The run:
   - writes each `ACTIVE` embargo onto positions wherever that is exact: a
     `LOCATION` embargo on a `BINARY` item sets `binary_available = false` through
     `InventoryService.setAvailability` with the embargo's true source and reason
     code and a movement reason `EMBARGO_MATERIALISED`; a `BRAND` or `MENU` embargo
     expands the same way over every location it covers at that moment;
   - reports, by variant, scope, source and location, everything it cannot carry:
     `UNTRACKED` and `QUANTITY` items (no boolean to set) and `CHANNEL` embargoes (a
     position is location-wide, so writing it would stop the dish on channels the
     embargo never covered, an over-stop the operator chooses in the report and the
     run never makes on its own). The report is the acknowledgement: these dishes
     will be on sale again;
   - names the POS poll's writer: with reads off, the poll goes back to writing the
     position boolean through the stock-availability port, as in Phase 0 and 1, and
     the run has already turned its `ACTIVE` `POS` embargoes on `BINARY` items into
     `binary_available = false`, so the next `newlyBackInStock` transition lifts
     them as it always did. A POS stop on a non-`BINARY` item is in the report.

   Rows are not deleted: `ACTIVE` embargoes stay, ignored and marked so, and turning
   reads back on resumes them.

Rollback therefore is not free of stock writes: step 3 writes positions, and the
report is the one place where the loss of a stop can be seen.

## Implementation checklist

- [ ] Flyway migration for `inventory.availability_stops` and
      `integration.marketplace_item_availability`, granted to `horecaos_application`.
- [ ] `AvailabilityResolver`; move `InventoryMenuAvailabilityLookup`,
      `InventoryService.checkAvailability*`, `reserveForQuote`,
      `CartService.requireAvailable` and `JdbcCatalogStore.variantsAtLocation`
      (and its counts) onto it; add the channel to `InventoryReservationPort`.
- [ ] A `catalog.api` lookup for the menu bound to `(location, channel)`.
- [ ] `ON_STOP` reason code; ru / uz-latn / en strings in the console and both
      storefronts.
- [ ] Console: stop scope and source columns, the scope picker, the propagation
      banner, the explainer's embargo layer.
- [ ] Stop `PosAvailabilityPoll` writing the boolean; write and end its own
      `POS` embargo.
- [ ] `inventory.stop.manage` capability and role bundles; the two create routes.
- [ ] `InventoryStopChanged` schema, catalogue entry, `docs/domains/events.md`
      row; first producer for the `STOP_LIST` channel.
- [ ] `MarketplaceGateway` route, fake-partner contract suite, reconciler worker,
      the `CONFIRMED`/`NOT_APPLIED`/`UNKNOWN` outcome and the `UNCERTAIN` state, the
      resync sweep and `resync_interval`, the marker listener for the inputs listed
      under Marketplace propagation, watermark writes, stale alert (ADR 0007,
      ADR 0040).
- [ ] ADR 0040's partner `GET .../availability`.
- [ ] The three rollback switches, `STOPS_FROZEN` and `MATERIALISATION_REQUIRED`, the
      materialisation run and its acknowledged report, and the POS poll's two
      writers (embargo with reads on, boolean with reads off).
- [ ] Tests listed under Testing, each seen failing first.
- [ ] Update ADR 0017's status line to record the extension, and ADR 0040's to
      record the producer for `marketplace.availability.push`.

## Exit criteria

A manager can stop a dish for a brand, a menu or one channel in one action, see
who stopped it, from which source, until when, and lift it in one action. That
stop is refused at the storefront, the cart and checkout of every channel it
covers within one request, and reaches a connected marketplace within the
partner's rate limit — or the operator is told, per binding and per item, that it
has not and since when. Whichever input changed the answer — a stop, a menu
rebinding, an offering switch, an expiry — a connected marketplace converges on the
platform's current answer within one resync interval even if nothing marked the
change, and a push whose outcome was unknown is resent, not assumed. A POS "back
in stock" no longer un-stops a dish an operator stopped. Gap-map row `2.5a` can be
marked `BUILT`.

## References

- ADR 0004 (outbox), ADR 0007 (Camel routes, fake provider suites), ADR 0016
  (offerings, publication), ADR 0017 (inventory ledger, reservations,
  availability), ADR 0025, ADR 0026 (bindings, mappings), ADR 0027 (audit),
  ADR 0030 (policy and configuration), ADR 0031, ADR 0032, ADR 0036 (sales
  channels, exclusions), ADR 0040 (marketplace, `marketplace.availability.push`),
  ADR 0045 (`STOP_LIST` channel), ADR 0058 (digest and alerts)
- `platform/docs/operations-gap-map.md` rows `2.5`, `2.5a`, `4.4a`, `4.4c`
- `platform/docs/frontend-information-architecture.md` 2.5 and PART 5 §3
- `platform/docs/operations-spec/catalog.md` §4.6 (stop list, the explainer,
  "Not built, named")
- `platform/docs/delever-parity-matrix.md` (stop-list rows and open questions on
  expiry, scope key and modifiers)
- `InventoryService`, `PosAvailabilityPoll`, `InventoryMenuAvailabilityLookup`,
  `JdbcCatalogStore#stopSourceOf`, `V0407__inventory_channel_stop_thresholds.sql`,
  `V0389`/`V0390` (menus and bindings)
