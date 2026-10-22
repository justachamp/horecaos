# ADR 0180: A stale channel is a self-closing incident, a weighed basket settles at handover, and the marketplace menu is pulled from one projection

- Decision status: Accepted
- Implementation status: Partial — built in operations batch 21 (wave `w5-catalog-stops-fiscal`): a
  stop materialisation panel on the stop list with the read-switch decommission runbook; the Phase 3
  dry run (`marketplace.availability.dry_run`, V0663) with `DRY_RUN` and the open stale incident on the
  propagation read and banner; a local-profile fake aggregator (`FakeAggregatorHttpProvider`, seeded by
  `tools/seed-marketplace`); the `STOPPED` line flag for an inbound aggregator order (V0664), drawn on
  the order detail, the ticket queue and the pass; the revised-total notice for a weighed total the till
  does not hold (V0665) with `ORDER_REVISION`, its ceiling rows, an operator-initiated send, a banner and
  the `TILL_REVISION_NEEDED` chat alert; decimal add-lines; the КБЖУ accuracy line switch
  (`catalog.nutrition_accuracy_line_enabled`, off); the shared IKPU picker, bulk assign and the missing
  unit/name list; the marketplace ruleset writer, `CatalogDraftChanged` on `catalog.events`, the partner
  menu pull and the channel photo layer. Not built, all external (see
  `docs/runbooks/external-connections/w5-catalog-stops-fiscal.md`): any real aggregator adapter or menu
  push and the comparison week against a partner portal; any per-marketplace ruleset; a vendor that can
  revise an order; the wording of the accuracy line; the provider encoding of a fractional receipt
  quantity (wave `w11`); the Committee's official IKPU/MXIK file. Nothing here has been run against a
  real aggregator, till or provider, and the local seed script has been syntax-checked but not walked.
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
   `local` profile an adapter for the provider type `FAKE_AGGREGATOR` and a loopback partner
   endpoint (`FakeAggregatorHttpProvider`, port 18090) that holds the availability it was told,
   with an outage switch and a state read for the developer; nothing is registered in any other
   profile, so every real binding still reads `MANUAL`. `tools/seed-marketplace` (`make
   seed-marketplace`) creates the installation, the binding, the channel and the mappings. Its
   wire format is its own invention: it proves the platform's side, not any vendor's.
5. **An inbound aggregator order that holds a stopped dish is accepted and flagged per line.**
   (ADR 0141 Decision 6, which already decided accept-and-flag.) The flag is a third value,
   `STOPPED`, of `ordering.order_lines.external_mapping_status` beside `MAPPED` and `UNMAPPED`:
   the same column the unmapped flag lives in, read and drawn by the same surfaces (the
   operations order read carries it as `intake` on the line; the order detail, the ticket queue
   and the pass draw it). Both intake paths ask the availability resolver the storefront menu and
   the partner pull use, at the order's own instant: a variant it says cannot be sold on that
   channel at that branch (a stop, sold out, not offered, not stocked) is flagged, and the reason
   is not copied onto the line because it changes by the minute. A stopped line keeps its
   variant; an unmapped line has none; the two cannot coincide.
6. **A revised total the till does not hold is an operator notice, and a vendor that can revise
   can be sent it.** A new capability `ORDER_REVISION` ("revise an exported order at the till")
   with a ceiling row per assessed vendor: `fake-pos` SUPPORTED, `clopos` UNSUPPORTED with its
   rationale (update-order accepts only status `IGNORE`; `count` is an integer; Q10 is
   unanswered), every other vendor unassessed, and an unassessed capability cannot be enabled
   on a binding. When a captured weight changes the total of an order the till already holds
   (its export is past `PENDING` and not settled absent), the weighing's own transaction opens a
   revised-total notice (`ordering.order_till_revision_notices`, V0665), one open per order at a
   time, which the order detail and the pass show as a banner, with one ADR 0058 alert
   (`TILL_REVISION_NEEDED`). It ends when the platform has sent the revised total to a binding
   that has the capability enabled and usable (an operator's button; a repeat is the same send,
   under a key built from the notice), when a person confirms the till was changed by hand, or
   when a later weighing brings the two totals back into agreement. Only a captured weight can
   move the total of an order the till holds: an amendment is refused while the till holds the
   order, so there is no amendment source.
7. **The KBZhU accuracy line is a code-owned string behind a switch that is off.** ADR 0030 key
   `catalog.nutrition_accuracy_line_enabled` (default off; settable at platform, tenant and
   brand; not tenant-visible, because the wording is legal's). The platform resolves it when the
   menu is read, and the storefront menu and the physical-attributes read carry the answer
   (`nutritionAccuracyNotice`); when on, both storefronts show one code-owned ru/uz/en line
   under the nutrition table and the editor previews it. Its wording is an external fact; the
   shipped text is a placeholder the owner replaces, and the switch is not turned on by this
   record.
8. **The IKPU/MXIK picker is one component.** The product editor's fiscal tab and the backfill
   editor both use `q-mxik-picker`; a chosen reference row offers its default package codes and
   its default unit codes, and a withdrawn code is treated the same in both places. The fiscal
   backfill gains bulk assignment across a filtered selection, and lists the nodes that hold both
   codes and lack a fiscal unit code or a fiscal name, because the coverage headline already
   counts them.
9. **A marketplace binding can carry a ruleset code, and only a known one.** `PUT
   /api/v1/operations/tenants/{t}/integrations/{installationId}/bindings/{bindingId}/marketplace-ruleset`
   accepts `MarketplaceRulesets.knownCodes()` (empty in production by ADR 0138; an unknown code
   is a 400), is audited, versioned under `If-Match` and sits behind
   `integration.installation.manage`; the installation panel says "no ruleset authored" while
   the set is empty.
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

**Schema.** V0663: `would_push_available`, `would_push_seq`, `would_push_at` on
`integration.marketplace_item_availability` (all three or none) and `was_dry_run` on the sync state.
V0664: `ck_order_line_mapping_status` admits `STOPPED`. V0665: `ordering.order_till_revision_notices`
(one `OPEN` row per order at a time; `source` is `CATCHWEIGHT` only; closed as `TOLD` or `ACKNOWLEDGED`;
money and codes, no text a person typed; no delete grant), the `ORDER_REVISION` rows of
`integration.pos_provider_capabilities`, and `TILL_REVISION_NEEDED` added to the full list restated in
`ck_telegram_binding_event_class`.

**Configuration keys (ADR 0030).** `marketplace.availability.dry_run` (boolean, off; platform, tenant,
brand, location; declared in `MarketplaceConfigurationKeys` and the tenancy registry).
`catalog.nutrition_accuracy_line_enabled` (boolean, off; platform, tenant, brand; not tenant-visible; declared
in `CatalogConfigurationKeys` and the tenancy registry). `inventory.stops.read_enabled` and its guard are
unchanged.

**Routes.**

| Route | Capability | Notes |
|---|---|---|
| `GET /api/v1/operations/tenants/{t}/orders/{o}/till-revision` | `pos.export.read` | the open notice or the latest closed one; `canTell` |
| `POST .../till-revision/send` | `pos.export.resolve` | `200` always with an outcome (`TOLD`, `NO_OPEN_NOTICE`, `NOT_AVAILABLE`, `REJECTED`, `RETRYABLE`, `UNCERTAIN`); idempotency key built from the notice |
| `POST .../till-revision/acknowledge` | `pos.export.resolve` | `409 NO_OPEN_NOTICE` when none is open |
| `GET .../integrations/marketplace-rulesets`, `PUT .../integrations/{i}/bindings/{b}/marketplace-ruleset` | `integration.installation.manage` | `If-Match` the binding version; unknown code `400` |
| `GET /api/v1/partner/tenants/{t}/restaurants`, `.../restaurants/{locationId}/menu` | `marketplace.menu.read` (`@PartnerBound`) | `ETag` over the serialised projection; `304` on a match |

Existing reads gain fields: the operations order line gains `intake` (`UNMAPPED` or `STOPPED`, and the
partner's item reference), the propagation read gains `mode: DRY_RUN`, `wouldPush` and `incidentOpenSince`,
the stop materialisation report resolves dish and branch names when read, the storefront menu and the
physical-attributes read gain `nutritionAccuracyNotice`, and the storefront menu's variants and categories gain
the channel's image ids.

**Events.** `CatalogDraftChanged` (`catalog.events`, three partitions, schema and baseline copy, outbox through
a `BEFORE_COMMIT` listener, one per transaction however many writes it holds). `TillRevisionNoticeOpened` is an
in-process signal between `ordering` and `notifications`, not an event others consume.

**Behaviour that is easy to get wrong.** A dry-run binding still sweeps, claims nothing, calls nothing and
reports no staleness; leaving dry run resends everything once. A stale incident is the alert plus the mark, and
the mark is cleared by recovery, by the reconciler being switched off, or by the binding ceasing to be active.
A `STOPPED` line is flagged from the resolver's answer at the order's own instant and keeps its variant. A
second weighing before anyone has acted updates the open notice (the till still holds what it held) and raises
no second alert; a weight corrected back to what the till holds closes the notice. Sending the revised total
outside a transaction, under a key built from the notice, and never storing the vendor's words.

**Tests.** `MarketplaceAvailabilityReconcilerTests` (dry run, incident), `StopMaterialisationControllerEndpointTests`,
`MarketplaceChannelTests` and `AggregatorOrderIntakeServiceTests` (the stopped flag on both intake paths and the
status check), `FakeAggregatorHttpProviderTests` (the fake through the production HTTP client and the push
conclusion), `ChannelPreviewEndpointTests` (ruleset writer, partner menu, photo layer, event),
`CatchweightReconciliationServiceTests`, `TillRevisionNoticeServiceTests`, `PosOrderRevisionServiceTests`,
`OrderPosRevisionControllerTests`, `TillRevisionAlertTriggerTests`, `PosSchemaTests` (ceilings and the trigger),
`StorefrontCatalogQueryTests` and `ConfiguredNutritionNoticeSwitchTests`; and in the console the specs of the
materialisation panel, the propagation banner, the installation panel, the order lines, the pass, the queue, the
add-lines dialog, the till-revision banner, the editor and both storefronts.

## Rollout and rollback

Every addition is behind an existing switch or is additive: the dry run, the KBZhU line, the
capability and the local fake are off or unregistered by default; the flag, the ruleset code,
the notice table, the event and the photo fields are nullable and additive. Rolling back is
turning the switches off; no migration needs reverting.

## Implementation checklist

- [x] The two decisions recorded and the three amended records pointed here (ADR 0040, 0141,
      0153); information architecture rows 2.5, 4.2c, 4.6 and PART 5 section 3 amended
- [x] Stop materialisation console, the decommission runbook, the dry run, the local fake
      aggregator, the stopped-line flag
- [x] Revised-total notice, `ORDER_REVISION` and its ceiling rows, decimal add-lines, the
      accuracy-line switch
- [x] The shared picker, bulk assign, missing unit and name
- [x] Ruleset code writer, `CatalogDraftChanged`, the partner menu pull, the photo layer
- [x] `docs/runbooks/external-connections/w5-catalog-stops-fiscal.md`
- [ ] A real aggregator adapter and the Phase 3 comparison week (external)
- [ ] A vendor adapter implementing `ORDER_REVISION` (external)
- [ ] The accuracy line's wording from legal (external)

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
