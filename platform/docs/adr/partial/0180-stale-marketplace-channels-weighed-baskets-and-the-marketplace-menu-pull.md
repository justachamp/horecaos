# ADR 0180: A stale channel is a self-closing incident, a weighed basket settles at handover, and the marketplace menu is pulled from one projection

- Decision status: Accepted
- Implementation status: Partial — in progress in operations batch 21 (wave `w5-catalog-stops-fiscal`);
  this line is rewritten with what exists when the wave closes.
- Date proposed: 2026-10-14
- Date decided: 2026-10-14
- Deciders: proposed by Claude (batch 21, wave `w5-catalog-stops-fiscal`); Ayubkhon Abbosov
  (platform owner) decides
- Depends on: ADR 0006, ADR 0011, ADR 0016, ADR 0025, ADR 0026, ADR 0027, ADR 0030, ADR 0031,
  ADR 0032, ADR 0038, ADR 0039, ADR 0040, ADR 0058, ADR 0137, ADR 0138, ADR 0141, ADR 0153,
  ADR 0173
- Supersedes / Superseded by: — (amends ADR 0040 and ADR 0141 by replacing "raises an ADR 0006
  failure an operator must resolve" with the incident Decision 1 defines, and ADR 0153 by taking
  its Decision 2 as final; each of those records gets a dated status addition pointing here, and
  none of their Decision, Alternatives or Consequences text is edited)
- Open inputs:
  - **Which marketplace exposes an availability write API, and which one a menu push**
    (product, partnerships). The mechanism is built and walked against a local fake; no adapter
    for a named aggregator ships, and the record does not claim one. Blocks: only the adapter and
    the comparison week; see `docs/runbooks/external-connections/w5-catalog-stops-fiscal.md`.
  - **Each marketplace's own content rules** (image resolution, description length, category
    depth, price parity, combo transport format; product, partnerships). The production ruleset
    registry stays empty by ADR 0138's decision.
  - **What Click, Payme and a fiscal terminal's OFD accept as a fractional quantity** (finance,
    integration discovery). Blocks: only the receipt line of a weighed item, which is wave
    `w11-fiscal-evidence`'s.
  - **A POS vendor whose API can revise an order or take a fractional line** (the vendor's answer
    to Clopos Q10, or another vendor). Until then the operator notice in Decision 6 is the whole
    answer.
  - **Whether any KBZhU accuracy line is required, and its wording in ru, uz and en** (legal,
    product). The switch is built and off.
  - **The Committee's official IKPU/MXIK file** (platform owner, finance). Everything is proved
    against the made-up `tasnif-fixture.csv`; the first real file needs the profile checked.

**Decision record, 2026-10-14.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "accept all defaults, start batch 21" (batch 21); each decision below is taken at the value the owner chose or, where the owner said nothing else, at the smallest default the batch-21 plan proposed.

## Context

Five gap-map rows (`2.5a`, `4.2c`, `4.2e`, `4.6a`, `10.7c`) were each held below BUILT by a
mixture of three things: a decision no record had taken, a build the Accepted records had
already specified, and an external fact nobody in the repository can supply. This record takes
the two decisions, and fixes the small design choices the build needed so the next reader does
not have to infer them from code.

**The stale channel.** ADR 0040 ("Liveness watermarks") and ADR 0141 ("When the partner is
down") both say a stale marketplace channel "raises an ADR 0006 failure an operator must resolve
explicitly". ADR 0006's queue holds outbox and inbox rows: failures that happened to a message.
A channel that has stopped confirming is work that stopped arriving, and the status lines of
both records say so and leave the question open. `MarketplaceStaleChannelMonitor` already does
the useful part: one ADR 0058 operations alert and one `MarketplaceChannelWentStale` fact per
episode, an episode named by the `stale_alerted_at` mark, ended when the binding has nothing
unconfirmed past its bound or stops being worked. What no record said is what that episode *is*,
who resolves it, and whether anyone has to.

**The weighed basket.** ADR 0153 Decision 2 (decided 2026-10-07, and worded "decided, not
pending" on every surface) keeps a provider-paid weighed order unsupported until the owner picks
one of three paths. Decision 4 proposes path C as a default to investigate and says no design
exists for it. The row cannot be BUILT while it waits on a choice among three XL builds, two of
which rest on provider facts nobody holds.

**The rest.** The remaining items are specified by Accepted records and were simply not built:
the stop-materialisation console and the Phase 3 dry run (ADR 0141), the flag on an inbound
order that holds a stopped dish (ADR 0141 Decision 6), the channel ruleset writer, the catalog
event and the partner menu pull (ADR 0138, ADR 0040), the picker shared by the two fiscal
editors (ADR 0173), and the operator's notice that a till holds a stale total (ADR 0039 by
analogy).

## Decision

1. **A stale marketplace channel is a tenant-queue incident that closes itself.** (Owner's
   choice, D8.) The incident is the ADR 0058 operations alert for the event class
   `MARKETPLACE_CHANNEL_STALE`, raised once per episode into the tenant's own alert queue, plus
   the `stale_alerted_at` mark on the binding's sync-state row that names the episode. It is
   not a maker-checker item, it needs no person to resolve it, and it is not an ADR 0006 outbox
   or inbox row; ADR 0006 is not amended. It closes when the binding recovers (nothing
   unconfirmed past its bound) or stops being worked (reconciler switched off, installation or
   binding suspended, adapter gone): the mark is cleared, the propagation read stops reporting
   the incident, the `stale_channels` gauge falls, and the next staleness is a new incident with
   its own alert. The propagation read and the stop-list banner carry the open incident
   (`incidentOpenSince`) so an operator who missed the chat message still sees that one is open.
   `MarketplaceChannelRecovered` stays unpublished and uncatalogued: nothing reads it.
2. **A weighed basket settles at handover, finally.** (Owner's choice, D9.) ADR 0153 Decision 2
   is accepted as the row's final scope: a basket holding a line sold by weight is paid at
   handover (cash, or a method that settles on handover), the weighing keeps refusing
   `PAYMENT_ALREADY_TAKEN` for an order that reached the pass paid through a provider, and
   nothing takes an incremental charge, holds and captures a smaller amount, or refunds the
   difference. Paths A, B and C of ADR 0153 Decision 3 are not built and not scheduled; the
   proposed default of Decision 4 is withdrawn as a build target. Information architecture row
   4.2c says so. ADR 0153 keeps its status and its text and gains a dated status addition.
3. **The Phase 3 dry run is a location-scoped switch.** A binding lives at one location, so
   "per binding" is the ADR 0030 key `marketplace.availability.dry_run` (boolean, default off)
   resolved at the binding's location and settable at platform, tenant, brand and location. On,
   the reconciler still sweeps (it touches only our database), records on each item row the value
   it *would* have pushed and when, calls no adapter, reports no staleness (nothing is
   attempted), and shows `DRY_RUN` in the propagation read and banner. Off again, the first live
   tick resends everything once, as a resumption does.
4. **A local-only fake aggregator walks a stop from the console to a "partner".** Under the
   `local` profile an adapter for the provider type `fake-aggregator` and a loopback partner
   endpoint that holds the availability it was told; nothing is registered in any other profile,
   so every real binding still reads `MANUAL`. The local seed creates the installation, the
   binding and the mappings.
5. **An inbound aggregator order that holds a stopped dish is accepted and flagged per line.**
   (ADR 0141 Decision 6, which already decided accept-and-flag.) The flag is a third value,
   `STOPPED`, of `ordering.order_lines.external_mapping_status` beside `MAPPED` and `UNMAPPED`:
   the same column the unmapped flag lives in, read and drawn by the same surfaces. A stopped
   line keeps its variant; an unmapped line has none; the two cannot coincide.
6. **A revised total the till does not hold is an operator notice, and a vendor that can revise
   is asked to.** A new capability `ORDER_REVISION` ("revise an exported order at the till")
   with a ceiling row per assessed vendor: `fake-pos` SUPPORTED, `clopos` UNSUPPORTED with its
   rationale (update-order accepts only status `IGNORE`; `count` is an integer; Q10 is
   unanswered), every other vendor unassessed, and an unassessed capability cannot be enabled
   on a binding. When a revision whose source is `AMENDMENT` or `CATCHWEIGHT` changes the total
   of an order the till already holds, the platform asks a vendor that supports the capability
   to revise it, and otherwise opens a revised-total notice that the weighing panel and the
   order detail show as a banner, with an ADR 0058 alert, until the operator confirms the till
   was corrected or a later revision brings the two totals back into agreement.
7. **The KBZhU accuracy line is a code-owned string behind a switch that is off.** ADR 0030 key
   `catalog.nutrition.accuracy_note_enabled` (default off); when on, both storefronts and the
   editor preview show one code-owned ru/uz/en line under the nutrition block. Its wording is
   an external fact; the shipped text is a placeholder the owner replaces, and the switch is not
   turned on by this record.
8. **The IKPU/MXIK picker is one component.** The product editor's fiscal tab and the backfill
   editor both use `q-mxik-picker`; a chosen reference row offers its default package codes and
   its default unit codes, and a withdrawn code is treated the same in both places. The fiscal
   backfill gains bulk assignment across a filtered selection, and lists the nodes that hold both
   codes and lack a fiscal unit code or a fiscal name, because the coverage headline already
   counts them.
9. **A marketplace binding can carry a ruleset code, and only a known one.** The writer accepts
   `MarketplaceRulesets.knownCodes()` (empty in production by ADR 0138), is audited and sits
   behind `integration.installation.manage`; the installation panel says "no ruleset authored"
   while the set is empty.
10. **Catalog writes publish `CatalogDraftChanged` on a new `catalog.events` topic**, through the
    outbox (ADR 0032), for an override write and for authoring writes (ADR 0138 "Events"; ADR
    0137).
11. **The partner menu pull identifies items by the platform's ids plus the partner's mapped item
    id.** `GET /api/v1/partner/tenants/{t}/restaurants` and `.../restaurants/{locationId}/menu`,
    capability `marketplace.menu.read`, an `ETag` over the channel's assembled projection (so the
    projection the preview draws and the menu a partner pulls are one thing), each variant
    carrying `variantId` and, where a `MENU_ITEM` mapping exists, `partnerItemId`.
12. **The channel's variant and category photo layer is carried by the assembled menu.**
    `MenuVariant` and `MenuCategory` gain a nullable image id each, so the override rows already
    accepted for `VARIANT` and `CATEGORY` are served and not only stored and previewed.
13. **The aggregator preview is data-accurate in HorecaOS frames.** There is no mock of a
    marketplace's own app and no rendering in an aggregator's language (ADR 0138 alternatives
    table; revisit when onboarding volume justifies it, product). Information architecture row
    4.6 says so.
14. **A terminal is a sales channel.** "Terminal" is struck from the stop-scope clause of
    information architecture row 2.5 and PART 5 section 3: a stop "for the kiosk" is a `CHANNEL`
    stop (ADR 0141 open input, closed on its default).

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| D8: an ADR 0006 failure resolved by an operator | ADR 0006 records failures that happened to a message; a channel that went quiet has no message, no row to replay and nothing to resolve but the partner's portal. Resolving it by hand would be a click that changes nothing | A partner offers a portal action the platform can invoke on resolution |
| D8: a maker-checker item | Nothing the second person approves; the incident carries no decision | Never for this |
| D8: a platform-queue incident | The people who can act are the branch's managers, who read their tenant's queue; the platform operator cannot update a tenant's partner portal | A platform-run marketplace programme |
| D9: path A, hold and capture | Needs a provider hold and a ceiling policy nobody agreed to; Payme and Click facts unknown | A provider confirms holds and a tenant asks for online weighed sales |
| D9: path B, settle the difference | A customer charged more than the quote shows, or a second payment at the door | A provider confirms partial refunds and incremental charges |
| D9: path C, take the money after the weighing | The record's own proposed default, but "a default to investigate, not a design": a new capture timing, a presentation startable from an order with no intent, an unpaid-at-pass policy and a receipt at the weighed amount would each need writing first | A tenant asks and finance agrees the unpaid-at-pass policy |
| Dry run as a tenant-wide switch | Phase 3 is "one aggregator at one branch"; a tenant-wide switch would dry-run every branch | Never |
| Flag a stopped line in a new column | A second column would need a second set of reads for the same surface; the existing one has no reader yet, so there is one to build either way | The flag gains a payload |
| Reject the inbound stopped order back to the aggregator | ADR 0141 open input: accepted and flagged, following ADR 0040's unmapped-line posture | Operations and partnerships ask for rejection |
| Refuse `VARIANT` and `CATEGORY` overrides until a menu serves them | The cheaper S fallback, but ADR 0138 accepted the layer and the partner menu is now the consumer | The pull proves unwanted |
| A mock of a marketplace's own app in the preview | Chrome, not data; ADR 0138 declined it | Onboarding volume justifies it |

## Consequences

### Positive

- A manager who misses the chat message still sees an open stale-channel incident on the stop
  list, and it disappears by itself when the partner recovers; nobody has to remember to close it.
- Platform administrators can switch the stop read-switch off with the report in front of them,
  and a runbook says how.
- A marketplace binding can be walked in dry run against a partner's portal for a week without
  one request leaving the platform.
- A weighed order never sits in an unrecoverable state, and the till is never silently left at
  the nominal weight: someone is told, or the vendor is asked.
- One projection answers the preview, the storefront channel menu and the partner pull.
- The product editor and the backfill editor can no longer disagree about a withdrawn code.

### Negative

- A tenant that sells by weight takes cash or settles at handover for those baskets, for good,
  until a later record reverses D9.
- The incident has no resolver: a stale channel the manager cannot fix (the partner is down) stays
  open until it is not, and the platform never escalates it beyond the alert and the banner.
- The dry run records what it would have pushed but cannot say whether the partner would have
  accepted it; the comparison is a person reading two lists.
- `STOPPED` widens a column whose name says "mapping"; the comment on the column says what it
  means now.
- A revised-total notice relies on an operator reading it. Clopos cannot be told.
- No real aggregator is connected; the fake proves the mechanism and nothing about any partner.

### Accepted trade-offs

- The stale-channel incident is derived from existing marks rather than stored as its own record,
  so there is no history of past incidents beyond the alerts that were sent and the
  `MarketplaceChannelWentStale` facts.
- The KBZhU line ships off with placeholder wording; turning it on is a content and legal act.

## Specification

Filled in at the close of the wave; see the implementation status line and the checklist.

## Rollout and rollback

Every addition is behind an existing switch or is additive: the dry run, the KBZhU line, the
capability and the local fake are off or unregistered by default; the flag, the ruleset code,
the notice table, the event and the photo fields are nullable and additive. Rolling back is
turning the switches off; no migration needs reverting.

## Implementation checklist

- [ ] The two decisions recorded and the three amended records pointed here (ADR 0040, 0141,
      0153); information architecture rows 2.5, 4.2c, 4.6 and PART 5 section 3 amended
- [ ] Stop materialisation console, the decommission runbook, the dry run, the local fake
      aggregator, the stopped-line flag
- [ ] Revised-total notice, `ORDER_REVISION` and its ceiling rows, decimal add-lines, the
      accuracy-line switch
- [ ] The shared picker, bulk assign, missing unit and name
- [ ] Ruleset code writer, `CatalogDraftChanged`, the partner menu pull, the photo layer
- [ ] `docs/runbooks/external-connections/w5-catalog-stops-fiscal.md`

## Exit criteria

A manager can run a stop materialisation from the console and read its report; a binding in dry
run records what it would have sent and nothing leaves; a stale channel shows as an open incident
and closes when the partner recovers; an inbound aggregator order holding a stopped dish is
accepted, flagged on the line and visible at the pass; a weighed total that the till does not
hold produces a banner; and the partner menu pull returns the channel's assembled menu under an
`ETag`.

## References

- ADR 0006: Message retry, dead letter and replay operations
- ADR 0011: POS capability model
- ADR 0038: Legal entities, fiscal receipts and product classification
- ADR 0039: Operator-assisted ordering and order amendment
- ADR 0040: Marketplace channel and the partner API
- ADR 0058: Telegram notification channels (operations alerts)
- ADR 0137: Physical and nutritional product attributes
- ADR 0138: Marketplace projection, preview and per-channel overrides
- ADR 0141: Stop scope and channel propagation
- ADR 0153: Weighed orders paid through a provider
- ADR 0173: The IKPU/MXIK reference dataset
