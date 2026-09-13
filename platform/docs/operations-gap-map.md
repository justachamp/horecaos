# Operations gap map

What `apps/operations` owes the information architecture, row by row, verified against the
working tree rather than against the specs. Derived from
[the frontend information architecture](frontend-information-architecture.md)
PART 2 and PART 4, read back through `frontend/operations/src` and
`platform/src/main/java`, and checked a second time by a frontend and a backend lens that
were allowed to disagree with the reader and with each other.

This document is a **build input, not a decision record**. Where a row turns out to need a
decision rather than a wave, it is moved out of the plan and into PART B with the name of
the input and the person who owns it.

**Tier legend** — `P` = first single-location pilot (go-live blocker) · `2` = wave 2
(multi-location + Delever parity) · `3` = wave 3 (parity tail, or blocked on a decision) ·
`?` = the row has no tier in the IA, usually because the IA has no row for the screen at all.

**Status legend** — `BUILT` = every capability the IA row names is real and reachable ·
`PARTIAL` = a real screen or a real endpoint serves some of the row and a named capability
is absent · `NOT BUILT` = no screen, or a placeholder, or an endpoint with no caller ·
`BLOCKED` = the work cannot start because an owner, legal or provider input is missing.

A **†** on a row id means the verification moved the status. The **Reader said** column
carries the reader's original grade so a reviewer can see the disagreement without
re-reading the evidence.

---

## The shape of the debt

| | BUILT | PARTIAL | NOT BUILT | BLOCKED | Total |
|---|---|---|---|---|---|
| **P** — pilot | 31 | 83 | 36 | 1 | **151** |
| **2** — parity | 14 | 41 | 42 | 3 | **100** |
| **3** — tail | 2 | 14 | 12 | 2 | **30** |
| **?** — no IA row | 1 | 2 | 4 | 0 | **7** |
| **Total** | **48** | **140** | **94** | **6** | **288** |

**Re-audited 2026-09-13 after batches 1 and 2 merged; 46 rows changed status.** (27 waves,
main at `99f4af70` — see the wave index for which.)

48 rows of 288 are finished. The pilot tier alone carries 119 rows that are neither
built nor blocked, and **83 of them are PARTIAL** — a real screen against a real endpoint
with one named capability missing. That ratio is still the single most useful fact in this
document: the pilot is not a greenfield build, it is a finishing job, and most of the
remaining finishing is frontend wiring over endpoints that already answer — the same
pattern this re-audit found again and again: a component built with a spec and no call
site, or a capability fixed on the server with no screen that reads it yet.

By size: 52 S · 96 M · 92 L · 48 XL, scored `S`=1 · `M`=2 · `L`=4 · `XL`=8 size-points.
PART C sizes every wave in those points and caps a wave at eight points per agent-day.

## What the verification changed

The two lenses moved **53 of 284 statuses**. The direction is lopsided and instructive:

- **34 rows moved up**, NOT BUILT → PARTIAL, almost all for one reason: the backend had landed and the reader believed a stale code comment.
  `1.3` New order is the headline — the whole operator place-order path exists,
  capability-granted and tested, and the console renders a placeholder. `10.2d` Floor plan,
  `10.5b` QR dine-in, `X.2` KDS device enrolment, `1.2m` handover codes, `10.8c`
  integration health and `5/X.1` customer erasure are all the same story: a finished,
  audited, capability-gated endpoint with no caller anywhere in `frontend/operations`.
- **13 rows moved down** — ten BUILT → PARTIAL and three PARTIAL → NOT BUILT. These are the
  dangerous ones, because a BUILT row is one nobody re-reads. `9.1` Staff cannot be opened by the
  branch manager it was designed for. `10.10a` Reference data has no edit path at all —
  only create and archive — although the `PUT` exists. `5.2i` Blacklist reveals the actor
  and then never renders it. `1.2a` `actions[]` is a status array, not a capability array:
  `LOCATION_STAFF` is offered «Отменить» on every open order and gets a 403 when it clicks.
- **6 rows moved off BLOCKED** — five to NOT BUILT and one to PARTIAL. `10.10c`, `7.5b`,
  `3.1b`, `1.3b`, `4.2e` and `X.4` were each filed as waiting on a decision that had either
  already been taken, or was needed for only part of the row.

Three stale-comment classes are worth fixing in the same passes that touch them, because
every one of them has already misled a reader: the `ADR 0045 is not built` comments in
`order-queue.ts` and `today-page.ts`; the `order_handover_challenges does not exist`
comments in `expo-page.ts` and `KitchenBoardController`; and the
`order_outcomes does not exist` comments in `business-overview-page.ts`,
`ReportingController` and `MetricRegistry`.

## What the review added

A second reading against the IA and the parity matrix found **four obligations with no row
at all**, which are now rows `4.2h` (recommended products / cross-sell, IA 4.2), `4.4d`
(catalog base settings — stock logic and the QR/kiosk price plane, IA 4.4), `10.8e`
(analytics installs, the GA4 ecommerce event contract and the telephony provider install,
IA 10.8) and `3.6d` (free geozone, IA 3.6). Three statements were also wrong rather than
merely incomplete and are corrected in place: `1.2a` said «nothing is missing» about the
one contract this document's own introduction calls a status array pretending to be a
capability array; `9.1c` counted eleven rail sections where `navigation.ts` declares
fourteen in three groups; and `X.1` (§X) said `service-status.ts` holds two signals where
it holds three — `open`, `late` and an `updated` stamp that is written and never rendered.

`3.6d` is the interesting one of the four, because the answer is that the gap is smaller
than the parity matrix implies. `ZoneRole`'s own Javadoc settles it: «a 'free geozone' is
not a third role: it is a DELIVERY zone whose tariff resolves to zero, and expressing it as
a layer is what left three layers with no documented interaction». The mechanism is built;
what is missing is the console's ability to bind any tariff at all to a zone, which is
already `3.6`. The IA's free-geozone-as-a-layer wording is struck in PART B instead.

The build plan changed more than the map did. Waves are now scored in size-points and
capped at eight per agent-day, which split six of them (`P05`/`P41`, `P12`/`P42`,
`P22`/`P47`, `P23`/`P45`, `P31`/`P46`, `P32`/`P43`) and re-sized a dozen more; the wave
count is 75. Five file collisions the first draft left concurrent are now sequenced — the
order-board family is serialised end to end, the floor plan is built once rather than by two
waves at the same time, `@angular/cdk` has one owner, `reports-filter-bar` has one owner,
and every wave that writes into `shared/ui/` is behind the wave that creates it. Migration
numbers are allocated per wave rather than taken, since the sequence is strictly ordinal and
`V0210` is the head. Every brief now names its capability constants and its tests; three
capabilities that were left unnamed turned out to be two that already exist
(`SHIPMENT_CANCEL`, and `COURIER_REGISTRATION_REVEAL`, which is too narrow and is replaced
by a new `COURIER_PII_REVEAL`) and one pair that has to be minted
(`TENANT_CONFIGURATION_READ`/`WRITE`). Every repeated row id — `X.1` through `X.5`, one of
which names six different rows — is written section-qualified wherever a brief or the index
uses it.

---

# PART A — The gap map

One table per IA section. **What is missing** is the verified statement of what a person
cannot do today, not a restatement of the row title.
## §0 — Home: live board, my work, wallboard

12 rows — 3 built · 3 partial · 6 not built

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `0.1` | Live board | P | PARTIAL | The operator leaderboard band is an honest locked note pending the staff-identity ADR (see `0.1d`, still unbuilt). The 'Отменено' counter and both live mixes are now correctly cut to the tenant's business-day boundary, and per-branch load answers in one brand-scoped request per tick rather than one query per branch. TV-distance presentation now exists separately via the wallboard shell (`0.1e` / T23). | L | — | P15 |  |
| `0.1a` | Oversized counters + the canonical "in progress" grouping | P | BUILT | — | M | — | P15 |  |
| `0.1b` | Live source-mix and type-mix | P | BUILT | — | M | — | P15 |  |
| `0.1c` | Branch leaderboard / per-branch active-order load | P | PARTIAL | The branch leaderboard now answers in one brand-scoped request per tick instead of one counts query per branch every 10 seconds. The order-entry half of this row — seeing branch load at the moment of choosing a branch for a call — stays blocked on `1.3` New order, itself still an unbuilt placeholder. | M | — | P15 |  |
| `0.1d` | Live operator leaderboard | P | NOT BUILT | A shift supervisor cannot see who is taking or confirming orders, so the one number that ranks the floor during service is absent; and even a counting endpoint would print Keycloak subject UUIDs, because no staff person record with a display name exists anywhere. | XL | No ADR owns staff identity — staff-and-access.md §11.1 (line 957) states plainly that a new ADR covering staff identity, the employment record and terminal access is required; ADR 0009's iam.principals (V0057) holds identifiers by design, not a profile. | deferred |  |
| `0.1e †` | Wallboard presentation of 0.1 (TV-distance shell + WallboardTile) | 2 | BUILT | — | L | — | T23 | NOT BUILT |
| `0.1f` | Liveness: refresh, staleness and the ADR 0045 COUNTERS stream | 2 | PARTIAL | A supervisor watching the board has no way to tell a quiet branch from a broken connection — numbers up to 10 seconds stale are shown identically to live ones, and the built COUNTERS stream is unused. | M | — | P08 |  |
| `0.2` | My work | 2 | NOT BUILT | A signed-in operator or manager has no personal page at all: they cannot see their own queue, their own numbers, their own details, or set any preference that survives a different browser. | XL | — | T01 |  |
| `0.2a` | Personal statistics — orders by sales channel | 2 | NOT BUILT | An operator cannot see how many orders they personally took today, by channel — the data is in the table and nothing can ask it. | M | — | T01 |  |
| `0.2b` | Personal statistics — revenue by payment method | 2 | NOT BUILT | Nobody — on this page or on any report — can split revenue by how it was paid (Payme, Click, cash, transfer), so an operator cannot reconcile their own shift's takings. | L | — | T01 |  |
| `0.2c` | Personal data (own profile) | 2 | NOT BUILT | A staff member cannot view or correct their own name, phone or email from inside the console — there is nowhere for those values to live, so every screen that should show a colleague's name shows a UUID instead. | XL | A new ADR is required (staff-and-access.md §11.1 names the scope: staff identity, the employment record and terminal access); the parity matrix also records an open question on whether Личные данные includes self-service password/MFA change, which changes the Keycloak boundary. | deferred |  |
| `0.2d` | UI personalization (user-scoped) | 2 | NOT BUILT | An operator who sets a language on the kitchen terminal has to set it again on every other machine, and no other preference (default branch, column layout, notification sound) can be expressed at all. | L | ADR 0030 has no user scope — adding one is an ADR amendment, and the parity matrix's open question 'what is actually in Персонализация?' leaves the field set an owner decision. | deferred |  |

## §1 — Orders: queue, detail, taking an order, amendments, outcomes, inbox

40 rows — 3 built · 22 partial · 15 not built

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `1.1` | Order board — queue shell, table and columns | P | PARTIAL | The backend now returns customer, courier, payment-status and delivery-fee data on every row (`OrderSummaryResponse` widened to 23 fields) and a cursor-paginated, filterable `GET .../orders/board` exists — but `order-queue.ts` still calls only the frozen, deprecated `GET .../orders` and renders none of the new fields, so an operator still cannot see who the customer is, which courier holds the order, how it will be paid, or the delivery fee without opening every row. Branch is still absent because the board stays hard-scoped to one location via `CurrentLocation`. | L | — | P04 |  |
| `1.1a` | Status tabs (seven, route-bound, per-tab membership) | P | BUILT | Nothing for the pilot. `attention` membership omits the `MANUAL_ACTION_REQUIRED`/`FAILED_RETRYABLE` process signal and the unresolved callback flag, because neither is on OrderSummaryResponse. | S | — | — |  |
| `1.1b` | Live per-status counts and liveness | P | PARTIAL | Counters and rows lag by up to ten seconds and burn a full 200-row fetch per tick; a new order can sit unseen for that window even though the platform can already push it. The board's own code comments still say "until ADR 0045 live updates exist" — they do exist. | M | — | P08 |  |
| `1.1c` | Filters (period, branch, aggregator, source, delivery type, courier, payment type) persisted per tab | P | NOT BUILT | An operator cannot narrow the board to a shift, a channel, an aggregator, a courier or their own orders, and the legacy dashboard's per-status persisted filters are simply gone — merchants will notice the regression. | L | — | P07 |  |
| `1.1d` | Search across per-provider external IDs | P | NOT BUILT | `GET .../orders/board` now accepts a `reference` filter matching the public order number or a per-provider external id, but no screen calls it — `order-queue.ts` has no search box at all, so an operator still cannot find order 77-A-3391 by aggregator id, phone or HorecaOS number. | M | — | P04 |  |
| `1.1e †` | Row action menu (inline + overflow, server-driven) | P | PARTIAL | `OrderActionCode` is widened from 4 to 9 values and a terminal order now gets a minimal read-only overflow (Открыть/Копировать номер) instead of none, but emission still returns only the original four (APPROVE/REJECT/ADVANCE/CANCEL): AMEND is built and gated but deliberately held inert behind `AMEND_EMISSION_ENABLED=false` until a console can render it (ADR 0105, wave P10), and COMPLETE/RESOLVE/ASSIGN_COURIER/ISSUE_INVOICE are declared but never emitted, reserved for P09/P11/P12. | S | — | P05 | BUILT |
| `1.1f` | Bulk selection and bulk courier assignment | P | PARTIAL | During a peak an operator still advances or cancels orders one row at a time, and the headline parity feature — select many orders, assign one courier, save — does not exist on either side. The §2.10 result panel and "Повторить проблемные" have nothing to render. | M | — | P07 |  |
| `1.1g` | Late-order highlight / severity overlay | P | PARTIAL | A delivery order that will miss its promise looks identical to one that will not until 45 minutes have elapsed since checkout, and a stuck process never lights the row — so the Внимание tab under-reports exactly the orders it exists to surface. | XL | — | P06 |  |
| `1.1h` | Backward status transitions (gated, reason-required, audited) | P | NOT BUILT | An operator who advances an order to READY by mistake cannot put it back; the only exit is cancel, which after CONFIRMED is itself refused (see 1.2k). The IA's stated improvement on Delever's silent reversals is currently a promise with no machine behind it. | XL | — | P41 |  |
| `1.2` | Order detail — card, lines, money, customer, address | P | PARTIAL | An operator cannot see why a cancelled order was cancelled (the outcome record is on the wire and unrendered), who took it or who accepted it, the kitchen note, or whether a callback is outstanding — all facts V0029 already stores and the endpoint already returns. | M | — | P09 |  |
| `1.2a †` | Server-supplied actions[] capability array | P | BUILT | — | S | — | P05 | BUILT |
| `1.2b` | Status timeline with per-stage clocks (three lanes) | P | PARTIAL | The one question the timeline exists to answer — how long did the kitchen have it, when did the courier collect — cannot be answered; there are no per-stage clocks, only commercial status hops. | L | — | P11 |  |
| `1.2c †` | Amendments — add items, edit customer/address/type/pre-order time, change payment type | P | NOT BUILT | An operator taking "add one more pizza and change the address" on the phone cannot record any of it: the three built commands have no UI, and the seven the caller actually asks for are refused by the server because their payment, inventory, fiscal and POS consequences are unbuilt. | XL | — | deferred | PARTIAL |
| `1.2d` | Multi-step (multi-branch) composition with per-step status | ? | NOT BUILT | An order cannot be split across two branches. This is a deliberate decline, not a gap to schedule — the IA row should be amended rather than built against. | S | — | amend |  |
| `1.2e` | Assign in-house courier / call external courier from the order | P | NOT BUILT | From the order the operator is looking at, they cannot assign a courier or dispatch a provider; they must leave for the dispatch board and find the order again there, losing the customer context they are holding on the phone. | L | — | P11 |  |
| `1.2f` | Provider quote-delta confirmation (the Millenium pattern) | P | NOT BUILT | When a provider re-quotes above the estimate, nothing asks the operator to accept or abandon; the platform has no seam at which a human can refuse a price increase before the provider order commits. | L | — | P44 |  |
| `1.2g` | Cascading cancel at the provider | P | NOT BUILT | An operator cancelling a dispatched order cannot see whether the courier provider was told, so a cancelled order can still have a courier en route with no visible warning. | M | — | P44 |  |
| `1.2h †` | The three comment channels (customer→order, customer→line, operator→kitchen) | P | PARTIAL | An operator cannot leave a note for the kitchen, for the courier, or for the next operator; the legacy dashboard had all three and staff use all three. | L | — | P10 | NOT BUILT |
| `1.2i` | Print to POS, and POS integration errors with a fix path | P | NOT BUILT | An operator cannot push an order to the POS or retry a failed push, and when a POS export fails they see nothing — the failure is visible only to a platform operator in the control plane. | XL | — | P42 |  |
| `1.2j` | Complete with completion reason | P | PARTIAL | «Самовывоз выполнен» and «Доставлен сторонней службой» are recorded as the same undifferentiated advance, so the courier SLA report and external-logistics settlement lose the distinction ADR 0039 built the outcome table to preserve. | M | — | P09 |  |
| `1.2k` | Cancel with reason + write-off type | P | PARTIAL | An operator cannot cancel a confirmed, cooking or out-for-delivery order at all — the CANCEL action is simply absent from `actions[]` for those statuses — because the dialog never picks a registry reason. The write-off type and liable party are never recorded for any cancellation. | M | — | P09 |  |
| `1.2l` | Оплата panel and manual re-fiscalize on the order | P | PARTIAL | On the order an operator is disputing, they cannot see the tender, the attempt history or the receipt state, re-issue the invoice, or retry a failed fiscalisation without leaving for the Finance section and finding the order again by id. | M | — | P12 |  |
| `1.2m †` | Handover-code state | P | PARTIAL | An operator cannot tell a courier or an aggregator rider whether the handover code has been satisfied, so custody disputes have no record on the order. | M | — | P09 | NOT BUILT |
| `1.2n` | Both delivery money fields (charged to customer vs billed by provider) | P | NOT BUILT | Nobody can see what the customer was charged for delivery versus what the provider billed, so the margin on every delivered order is invisible at the order level. | M | — | P11 |  |
| `1.2o` | Change-due (Сдача) | P | PARTIAL | A courier taking cash is not told how much change to carry, even though the platform already stores the figure and can already amend it. | S | — | P09 |  |
| `1.2p †` | Ревизии — the revision chain | P | PARTIAL | Asked "what did this order look like before it was changed?", an operator has no answer — the append-only revision chain the platform maintains is unreachable from any screen. | S | — | P09 | NOT BUILT |
| `1.3 †` | New order — the call-centre order-entry screen | P | PARTIAL | An operator cannot take a single order by telephone. This is the highest-value unbuilt pilot screen in the section: the whole backend for it exists and is capability-granted, and the console renders a placeholder. | XL | — | P13 | NOT BUILT |
| `1.3a` | New order — customer pane (phone lookup, auto-create, order-history peek) | P | PARTIAL | There is no phone box: an operator cannot resolve a returning caller, see their last order, or create the account the order will hang from — and when one is created, marketing suppression cannot tell it came from an operator. | M | — | P13 |  |
| `1.3b †` | New order — address pane (map pin/search + дом/квартира/подъезд/этаж/ориентир) | P | NOT BUILT | An operator cannot enter a delivery address at all. The structured fields are cheap; the pin, the suggest and the geocoder are not — and the provider choice is an unmade product decision. | XL | ADR 0015's own open input, still open: "Approve address/geocoder provider, normalization, coordinate precedence, zone, and outage rules" (adr/partial/0015…:96, :297, :475). Yandex Maps is the market expectation per IA PART 4 but no provider is approved and no credential exists; ADR 0037 inherits the same input for the routing provider. | P14 | BLOCKED |
| `1.3c †` | New order — item search and the interactive full-screen menu/basket | P | PARTIAL | There is no way to put items into an operator-built order — no search over the menu, no modifier selection, no quantity control, no running total. | L | — | P13 | NOT BUILT |
| `1.3d` | New order — pre-order time with out-of-hours branch-resolution warning | P | NOT BUILT | An operator cannot take an order for later, and nothing warns them that the branch they are about to bind the order to is closed at the requested time. | M | — | deferred |  |
| `1.3e` | New order — operator-channel payment/order types, promo code, change-due | P | PARTIAL | Even when the screen is built, an operator can only take cash: no card link, no Click/Payme, and no promo code on a phone order — the operator channel's own allowed-type matrix has nothing to read it from. | M | — | P14 |  |
| `1.3f` | New order — repeat / re-order | P | NOT BUILT | "The usual, please" costs the operator a full re-entry: the resolved repeat plan the platform already computes is not reachable from a staff token. | M | — | P14 |  |
| `1.3g` | New order — manual aggregator order creation with no live provider binding | P | NOT BUILT | When an aggregator phones an order through because their integration is down, the operator has no way to record it as that aggregator's order with externally-set pricing — it would land as an ordinary own-channel order and corrupt the channel mix. | L | — | P14 |  |
| `1.4 †` | Drafts and abandoned carts | 2 | PARTIAL | No first-line product preview (`ordering.cart_lines` snapshots no name) and no hand-off to an ADR 0044 recovery audience. Converting a cart into an order is deliberately not offered — nobody agreed to that basket. | S | — | T10 | BUILT |
| `1.5` | Reservations — day plan, create, confirm/reject/cancel/no-show, amend | 3 | PARTIAL | The screen hard-codes an 08:00-23:00 window because no location exposes opening hours, and the guest's name, phone and note cannot be edited after booking. | M | — | W01 |  |
| `1.5a †` | Reservations — seat/complete, external reservation ID, auto-create client on unknown phone | 3 | PARTIAL | A host cannot seat a party or close out a booking from this screen, and a booking never becomes a customer record. The IA's "auto-create client on unknown phone" and "external reservation ID as the displayed identifier" should be amended rather than built. | L | — | W01 | NOT BUILT |
| `1.6` | Call centre — presence, screen-pop, call log | 3 | PARTIAL | Both named adapters are proven only against a fake PBX because no provider account exists, and the screen never links a claimed call to the order it produced — `call-provenance` has no caller in the frontend, so operator KPIs lose the join. | S | — | W01 |  |
| `1.6a` | Call centre — softphone and click-to-call | 3 | NOT BUILT | An operator cannot dial from the console and keeps whatever handset they already have. This is a recorded decline, not a scheduled gap; the IA's "Owns: softphone" line should be amended. | S | — | amend |  |
| `X.1` | Operator inbox (conversations) — not an IA row, but shipped in this section of the app | ? | BUILT | No needs-attention count badge on the rail entry (ADR 0059's own named gap — no counts service exists for it), and Telegram is the only channel adapter. The IA has no row for this screen at all, which is itself a documentation gap. | S | — | — |  |

## §2 — Kitchen (device shell)

16 rows — 11 partial · 4 not built · 1 blocked

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `2.1` | Kitchen queue (KDS) — the live board | P | PARTIAL | A cook cannot read the customer's own note on a line: `OrderLine.hasNote` renders as a bare "has note" flag (kitchen-queue-page.html) because the note text is behind the separate audited `OrderRevealApi.revealLineNote` call that this screen never makes, so "no onions" never reaches the line. There is no aggregator tab (kitchen-ticket.ts documents the reduction: `channelCode` is a free string on the wire, not a typed `sales_channels.system_type`, so the aggregator stream shows only as a raw chip), and the tab counts are per fulfilment mode over one 200-ticket page rather than the exact per-status counts the row names. It also runs inside the operator console shell at 10s polling — no touch shell, no offline banner, and no use of ADR 0045's SSE (telemetry/api/StreamChannel.java has ORDER_QUEUE and STOP_LIST channels; nothing in the operations frontend opens an EventSource). | M | — | P16 |  |
| `2.1a` | Courier ETA on the kitchen ticket | P | NOT BUILT | Expo and the line cannot see when the courier will actually arrive, so a branch either cooks too early and lets food sit, or holds a ticket and makes the courier wait. Needs an ETA concept in fulfilment (provider quote/booking arrival), a join onto the kitchen board response, and the ticket chip. | L | — | P11 |  |
| `2.1b` | Preset product comments on a kitchen line | P | NOT BUILT | An operator or customer cannot attach a standard instruction ("без лука", "хорошо прожарить") that the kitchen can read as a coded value; every wish is free text or nothing, and nothing round-trips to a POS that transports comments as modifiers. | L | — | deferred |  |
| `2.1c †` | Assign own courier / dispatch an external provider from the KDS | P | PARTIAL | A branch running without an operator on shift cannot get a finished order onto a courier from the screen it is already standing at — it must call the operator or walk to the dispatch board. Delever ships this from KDS v1/v2 and the parity matrix lists it as a filled circle. | M | — | P16 | NOT BUILT |
| `2.1d` | Change payment type from the KDS | P | NOT BUILT | When a customer at the counter switches from card to cash, kitchen staff cannot record it — the change has to go back through an operator. The endpoint is there; this is a screen affordance plus the confirmation step the amendment already requires. | S | — | deferred |  |
| `2.1e †` | Create an order from the kitchen | P | PARTIAL | A counter or walk-in sale cannot be rung up from the kitchen at all — there is no order-entry screen anywhere in the console yet, so this inherits IA 1.3's gap rather than adding one of its own. | L | — | P16 | NOT BUILT |
| `2.2` | Buffer — held tickets and the kitchen fire time | 2 | PARTIAL | A manager can fire a ticket now but cannot move *when* it fires — the release-schedule override is a live backend endpoint with no client, so a pre-order's kitchen fire time can only be accepted or jumped. Paid-only hold is not modelled at all: every held ticket is listed and release is offered unconditionally, so an unpaid pre-order cannot be kept off the line. | M | Which payment fact gates a paid-only hold is unspecified — orders.md names no rule and ADR 0013's payment-method registry is itself partial; a product decision, not code. | T02 |  |
| `2.3` | Expo / handover (Раздача) | 2 | PARTIAL | An aggregator order can be handed to the wrong courier: expo presses one unconditional button, with no code entry, no attempt counter and no supervisor bypass, even though the server-side compare the row specifies is already built and reachable. There is also no packing check and no per-department ready roll-up in the UI — items are a flat table with a station column. | M | — | T02 |  |
| `2.4` | Display board (VDU) | 2 | PARTIAL | The one feature IA 2.4 names by name is absent: a courier or customer quoting the aggregator's number sees a HorecaOS number on the wall and cannot match it. The screen also renders inside the operator console with its own oversized type rather than a wallboard shell, which vdu-page.ts's own doc admits. | M | — | T02 |  |
| `2.5` | Stop list — available/on-stop tabs, single and bulk | P | PARTIAL | No server-side batch mutation exists, so a 300-item stop is 300 sequential HTTP calls from the browser with a partial-failure count as the only report; and the tab counts and the on-stop tab only cover the 50-row page already loaded, so "what is on stop right now" is not answerable without scrolling the whole catalog. | S | — | P16 |  |
| `2.5a` | Stop scope (product × branch/menu/terminal/brand) and channel propagation | P | NOT BUILT | A manager cannot 86 a dish across a brand or for one terminal only, and a stop set here never reaches Yandex/Uzum/Wolt — those channels keep selling an item the kitchen has taken off. Fixing it is a data-model change plus an outbound adapter per aggregator. | XL | Needs an ADR: PART 5 §3 names the `(product × scope)` availability model as an unreversed decision against ADR 0017's binary one. | deferred |  |
| `2.5b †` | Stop source taxonomy and the "why can't I sell this?" explainer | P | PARTIAL | When an operator sees a dish blocked at checkout, nothing tells them whether the kitchen stopped it, the POS pushed it, or stock ran out — so they phone the branch. The single explainer the row makes the section's centrepiece has no data to draw on beyond a boolean and a free-text reason. | L | — | P16 | NOT BUILT |
| `2.5c` | Stop-change digest notification | P | PARTIAL | A branch that stops fifteen items in a rush gets fifteen separate messages instead of Delever's one grouped «Позиции на стопе / Вышли со стопа» digest, and nobody is told when an item returns to sale. No console screen lets a manager choose the chat or the cadence. | S | — | P16 |  |
| `2.6` | Capacity & buffer settings — throughput ceilings | 3 | PARTIAL | A ceiling can be created but never corrected: `CapacityApi` has no update or delete and the controller exposes none, so a mistyped 500 portions/hour is permanent and a second window for the same station and weekday is refused with a 409. The IA's per-product ceiling is also absent — the model is station-level only. | S | — | T02 |  |
| `2.6a` | Cook headcount output | 3 | BLOCKED | A manager cannot ask how many cooks the evening needs — the platform has neither a demand forecast to scale nor a stated portions-per-cook-hour policy to divide by, so the screen shows a ceiling and stops there. | XL | Two named inputs, both owner/product: the portions-per-cook-hour policy (varies by station and cuisine, nobody has decided it) and a demand-forecast decision — PART 3's wave 3 lists demand forecast as blocked on a product decision, not on engineering, and there is no analytics/forecasting ADR (PART 5 §7). | deferred |  |
| `X.2 †` | Kitchen device enrolment and device registry (no IA row exists) | ? | PARTIAL | A manager cannot pair a kitchen screen, see which devices are enrolled at the branch, or revoke a lost tablet — the approve/list/revoke endpoints have no caller, so every KDS device is either hand-provisioned or absent. The IA has no row for this at all (10.5 registers kiosks, not KDS devices), so it is an IA gap as much as a build gap. | M | — | P17 | NOT BUILT |

## §3 — Delivery

15 rows — 4 built · 9 partial · 2 not built

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `3.1` | Dispatch board | P | PARTIAL | A dispatcher gets a text table, not the диспетчерский модуль: no map of points and routes, no drag-to-assign, no click-to-filter, no bulk assignment, no 'call an external courier', and no customer name or address on the row (the join only yields the public order number). The exceptions endpoint is never called, so a MANUAL_ACTION_REQUIRED plan shows a status word and no reason; and the board polls every 10s instead of using ADR 0045's SSE stream (no EventSource anywhere in the app), so two dispatchers can both work from a stale queue. | L | — | P18 |  |
| `3.1b †` | `/deliveries` variant for courier-service tenants | ? | NOT BUILT | A courier-service company cannot use this console at all: there is no delivery-first landing route and no screen that treats a delivery (rather than an order) as the record being managed. | XL | Owner/product decision named as an open question in platform/docs/delever-parity-matrix.md (~line 661): 'Is HorecaOS one product or several product shapes on one platform?' — ADR 0002 has no business-type axis and adding one after launch is called out as expensive. | deferred | BLOCKED |
| `3.2` | Live map | 2 | PARTIAL | `accuracyMeters`, `headingDegrees` and `speedMps` are now rendered, a branch filter is added (reusing `CurrentLocation`), and the audited track-reveal path is now called end to end with its stated purpose. Positions still render as decimal coordinates in a table rather than on a map — MapCanvas is `X.4`, blocked on an unmade map-provider decision — and partner-courier pins remain correctly unbuilt, since ADR 0045 rejects that alternative outright. | L | No map primitive exists in the app (PART 4 pilot blockers: MapCanvas/MapPin); the map provider decision (Yandex Maps) and its key are not made anywhere in the repo. | T03 |  |
| `3.3` | Couriers (in-house roster) | P | PARTIAL | `P19` closed the compliance file, courier groups and branch bindings — all three are now first-class relations reachable from the console (`OperationsCourierController`, `couriers-page.ts`). What remains: no online status or rating (read-only per the spec, no source exists on either side), and courier-app access is not provisioned here — the register form asks the operator to type a Keycloak subject created outside the console. | S | — | P19 |  |
| `3.4a` | Courier types (Тип курьера) | 2 | BUILT | — | S | — | T16 |  |
| `3.4b` | Courier rate cards (Тариф курьера) | 2 | PARTIAL | The per-km distance ladder is now editable and a rate card can be activated from the console, and the create form sends `maxDistanceMeters` — but `CourierAccrualService.recordDelivery` still has no production caller anywhere in the codebase, so an activated card still earns a courier nothing outside a test, unchanged by this wave. | L | — | T16 |  |
| `3.4c` | Bonus / penalty rule definitions | 2 | PARTIAL | `AdjustmentRuleEvaluator` and a rule-authoring form are built and tested, but `evaluatePeriodClose` is not called from `CourierSettlementService.close`, so a SETTLEMENT_PERIOD-window rule never fires in production; `ORDER_UNDELIVERED` and `ORDER_DAMAGED` outcome bases have no reader at all and stay manual-only by design. | L | — | T16 |  |
| `3.5` | Shifts & attendance (Посещаемость) | 2 | BUILT | The roster grid, period filter and planned-vs-actual comparison are all live at LOCATION scope. The courier's own accept/decline of a published shift offer remains the courier app's surface, not this console's (ADR 0042's PUBLISHED→ACCEPTED/DECLINED has no writer here), and cash handed over at shift close is still confirmed in Finance, not here — both unchanged, neither part of this row's own ask. | L | — | T17 |  |
| `3.6` | Delivery zones | P | PARTIAL | The tariff-binding bug is fixed — `submitDraft` now sends `deliveryTariffId`, draft/activate/bind are separated with a version list, and unbind now exists alongside bind, with CATCHMENT offered and per-locale names. Circles only remains true: no polygon drawing and no map at all, blocked on `X.4`'s MapCanvas/PolygonEditor and an unmade map-provider decision, so a real city zone with a river or a ring road in it still cannot be drawn. | L | PolygonEditor/MapCanvas do not exist (PART 4 pilot blockers) and the map provider is unchosen. | P20 |  |
| `3.6b` | Regions & geocoder bounding boxes | P | BUILT | — | M | — | P20 |  |
| `3.6c` | Bulk geozone upload | 2 | PARTIAL | The dry-run/apply batch import flow is built and live (a batch endpoint, its report, and a frontend page), but activation is deliberately gated behind `X.4`'s map per ADR 0037 — a bulk-imported zone cannot go live from the console until that map exists. | L | — | T17 |  |
| `3.6d` | Free geozone (Бесплатная геозона) | P | BUILT | — | S | — | P20 |  |
| `3.7` | Delivery tariffs | P | PARTIAL | Distance tiers, time-rule surcharges, discounts, min/max, rounding, distance mode/accrual/fee-source and branch binding are all now authorable from the console, with RADIUS_FALLBACK/PROVIDER_QUOTE rendered. ROAD distance mode still silently prices as radius, since ADR 0037's `RoadDistancePort` answers empty — unchanged by this wave. | L | — | P20 |  |
| `3.8` | Dispatch rules | P | NOT BUILT | An operator cannot decide anything about dispatch: which provider serves which zone, source or branch; when auto-dispatch fires relative to prep time; the fallback order; courier order grouping and its merge radius; or the unpaid-order cancellation timeout. All of it is compiled-in defaults, and this is a pilot row. | XL | Needs its own ADR — no decision record covers an operator-authored, provider-agnostic rule engine; the parity matrix leaves racing/grouping semantics as an open question. | deferred |  |
| `3.9` | Courier policy | 2 | PARTIAL | A resolution-scope selector (tenant/brand/location), the inherited value beside each overridden field, a one-sentence consequence line per row, and policyId/policyVersion are now all rendered. The screen stays read-only by construction — the write path is P38, not merged — and six of the ten spec switches (GPS accept/action radius, show-only-kitchen-ready, reveal-customer-location timing, telemetry collection-gate default, post-delivery payment check, courier billing mode) still have no backing field in any policy document. | L | — | T03 |  |

## §4 — Catalog

25 rows — 12 partial · 11 not built · 2 blocked

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `4.1` | Products — the tenant product library | P | PARTIAL | Search and the status tabs only see the rows already loaded, so at the 1000+ items the IA row is written for, searching a dish that sits past the loaded pages returns nothing. None of the row actions the row owns exist — duplicate, archive, add-to-category, stop-in-all-branches, copy id, public share slug — and there is no selection or bulk-action bar at all. | L | — | P21 |  |
| `4.1a` | Bulk edit of ИКПУ / package code / name, bulk delete, copy-id, share slugs | P | NOT BUILT | Onboarding a 600-item menu means opening each product editor and saving ИКПУ variant by variant; nothing tells the operator how many priceable nodes are still unclassified, and there is no fill-down or paste-a-column grid — which is exactly the daily reality this market's onboarding consists of. | L | — | P21 |  |
| `4.2` | Product editor — the seven-tab core | P | PARTIAL | A product's status cannot be changed (Черновик↔Активен is read-only text), a product cannot be removed from a category or catalog, and "Add variant" (product-editor-page.ts:460) posts only sortOrder and an UNCLASSIFIED fiscal block — so it creates a nameless, SKU-less, unit-less variant that the console then offers no way to name, because the translation form writes entityType PRODUCT only. Photos cannot be reordered and a readiness finding names an entity id instead of linking to the tab that fixes it. | M | — | P22 |  |
| `4.2a` | Combo groups with per-variant price map | 3 | NOT BUILT | A merchant cannot sell a set meal as one sellable item: each component has to be ordered as a separate line at its own price, and no channel can render a choice-set. | XL | — | deferred |  |
| `4.2b` | Modifier depth — nested variant-modifiers, hidden modifiers auto-selected by order type, modifier-level fallback to group values | 3 | NOT BUILT | Packaging charges cannot be attached silently by order type (so a delivery box never reaches the receipt), an option cannot itself carry variants, and min/max selection rules cannot be authored from this console at all. | XL | — | deferred |  |
| `4.2c` | Physical & nutritional attributes — weight, measure, catchweight + quantum, splittable, portions as a decimal, КБЖУ | 3 | NOT BUILT | A weighed or splittable dish cannot be described at all, so catchweight items cannot be priced correctly and the portion-band rules in 4.9 have no attribute to band on. | XL | — | deferred |  |
| `4.2d` | Fiscal data on every priceable node — ИКПУ/MXIK, package code, marking, excise, VAT, alcohol % and age gate | P | PARTIAL | An operator can set ИКПУ and package code but cannot mark a product as excisable, marked, alcoholic or age-restricted from any screen, and a menu with missing classifications still publishes because the finding is only a warning — the legal wall ADR 0038 describes is not yet a wall. | M | — | P22 |  |
| `4.2e †` | ИКПУ/MXIK reference lookup (typeahead behind the classification field) | P | NOT BUILT | Staff type a tax classification code from memory or a spreadsheet into a free-text box, with no validation that the code exists — and a wrong code is a tax classification error on a legal document. | M | The official ИКПУ/MXIK dataset has never been imported; its source and refresh cadence are an unanswered finance/owner input, and the existing search endpoint is PLATFORM-scoped so a tenant operator could not call it even if it were loaded. | P22 | BLOCKED |
| `4.2f` | Ordered images with per-aggregator overrides | P | PARTIAL | An operator uploads a photo and then cannot see it, reorder it, crop it to an aggregator's aspect ratio, or give one channel a different image — so verifying the menu looks right requires leaving the console. | L | — | P22 |  |
| `4.2g †` | Per-item sale schedule and kitchen department on the product | P | PARTIAL | Breakfast-only items must be stopped and un-stopped by hand every day, and kitchen tickets cannot be routed to a department, so KDS cannot split a ticket between the grill and the bar. | L | — | P47 | NOT BUILT |
| `4.2h` | Recommended products / cross-sell (Рекомендованные товары) | P | NOT BUILT | A merchant cannot attach sauces to a hot dish, sides to a burger or a dessert to a set, so the storefront and the aggregator projections have no cross-sell to render and average check has no lever at all. IA 4.2 names it inside the product editor with the filter that makes it safe — active + in-menu + not-stopped — and that filter is where the work is: a recommendation that points at a stopped variant is worse than none. | M | — | P47 |  |
| `4.3` | Categories — hierarchical taxonomy | P | PARTIAL | Once a category is created, an operator cannot rename it, move it, re-order it, deactivate it, give it an image or a schedule, or delete it — the only way to fix a mis-parented category is to create another one. The detail pane also lists no products, so the tree cannot be used to see what is in a category. | L | — | P23 |  |
| `4.4` | Menus — the per-location offering matrix (Layer A) | P | PARTIAL | The matrix shows only two states for one location: a variant that is hidden at the offering level is indistinguishable from one that was never added, and there is no bulk stop/unstop, no filter, no per-order-type (delivery/pickup/hall) column. An operator cannot answer "what does this branch sell" without also knowing what is missing from the list. | L | — | P23 |  |
| `4.4a` | The named Menu entity — bind menu to branch, copy menu, add products with filtered select-all | P | NOT BUILT | A chain cannot roll one assortment out across branches — there is nothing to copy and nothing to bind — and adding a filtered set of products to a branch's menu in one gesture is impossible; each variant must be offered individually. | XL | — | deferred |  |
| `4.4b †` | Per-channel offering and price overrides — offered_on_channel separate from price_on_channel, mass enable for aggregator | P | PARTIAL | An item cannot be switched on for the hall and off for an aggregator, cannot carry a different price per aggregator from any screen, and onboarding a marketplace would mean toggling every item by hand — there is no mass enable. | XL | — | P45 | NOT BUILT |
| `4.4c` | Per-item stock quantity with daily default and auto-reset, per-aggregator stop threshold | 2 | NOT BUILT | A kitchen with twenty portions of a dish cannot say so — it must watch and stop the item by hand — and an aggregator cannot be cut off at a different remaining count than the storefront. | XL | — | deferred |  |
| `4.4d` | Catalog base settings (use stock logic; QR/kiosk take hall prices) | P | PARTIAL | `catalog.use_stock_logic` and `catalog.qr_kiosk_price_plane` are now registered ADR 0030 keys, rendered at /settings/catalog (TENANT scope), and a QUANTITY-tracking refusal now names which of the two true things is going on instead of a generic error — but the setting stays read-only in the console, since QUANTITY tracking itself remains unimplemented regardless of the flag: a merchant still cannot actually turn stock logic on for the company. | M | — | P46 |  |
| `4.5a †` | Catalog sync from POS — mapping table and run history | P | PARTIAL | A merchant cannot import their POS menu or see what an import did: no run list, no per-item outcome report, no mapping pane to pair an external id by hand, no import-language or price-re-import choice — all of which exist as endpoints the console never calls. | L | — | P24 | NOT BUILT |
| `4.5b` | Excel import/export — templates, dry run with row-level results, image-by-URL fetch | P | NOT BUILT | 800 items must be typed in one product at a time; there is no export to edit offline and no way to bring a corrected spreadsheet back, and the per-row outcome reporting the IA names as beating Delever has nothing to report against. | XL | — | deferred |  |
| `4.6` | Publication & channel readiness | 2 | PARTIAL | A finding names an entity id with no link to the tab that fixes it, and there is no draft-vs-live comparison — the operator cannot tell whether the draft differs from what is live before publishing, only what the live hash is. | M | — | T04 |  |
| `4.6a` | Aggregator preview and the per-channel projection/override layer | 2 | NOT BUILT | Nobody can see how a menu will render — or why a marketplace will reject it — before pushing it, so the first sign of a bad publication is a live aggregator refusing the feed. | XL | — | deferred |  |
| `4.7` | Reference data — attributes, tags, ingredients, kitchen departments, product comment presets (brands link out) | 2 | BLOCKED | There is no controlled vocabulary for tags, ingredients, attributes, kitchen departments or product comment presets, so anything that needs one (cross-sell, storefront filters, R-Keeper comment transport, ticket routing) has nowhere to draw from. | L | An owner/product decision: ADR 0016's open input on legacy merchandising disposition — in particular whether Атрибуты are the variant axis or a spec-sheet vocabulary — is unanswered, and the spec forbids building any of it first. | deferred |  |
| `4.8a` | Price books — the named price planes and their assignment | 2 | PARTIAL | A book can only be applied brand-wide from the console, so the hall-vs-base plane and the aggregator-propagation the IA row owns cannot be expressed even though the endpoints exist; VAT profiles have no screen either, and a book's variant prices can only be seen one product at a time in the editor. | M | — | T04 |  |
| `4.8b` | Bulk price change across a filtered selection (Прейскурант) | 2 | NOT BUILT | Raising every price 10% means editing each variant by hand in the product editor with no preview of what the change will cost, and no way to scope it to a category or a channel. | L | — | T04 |  |
| `4.9` | Auto-add rules — plain, product-triggered, portion-band | 2 | BLOCKED | Packaging, cutlery and gift items cannot be injected server-side, so web, bot and operator entry each get a different cart and staff add such lines by hand on every order. | XL | No ADR owns cart injection: the IA and catalog.md both record it as unowned and needing a product decision before design, and the portion-band kind additionally waits on the ADR 0016 portions attribute. | deferred |  |

## §5 — Customers

19 rows — 6 built · 8 partial · 4 not built · 1 blocked

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `5.1` | Customer list — grid, search, status filter, manual create | P | BUILT | Nothing an operator needs on the grid itself. Residue: registration dates render against a hardcoded PLACEHOLDER_TIME_ZONE = 'Asia/Tashkent' in customers-page.ts, so a tenant in another zone sees the wrong calendar day; MERGED accounts are unreachable from the filter by design. | S | — | — |  |
| `5.1a` | Header counters (total, ordered today, registered today) from the same metric layer as the dashboard | P | PARTIAL | The counters are computed by a second, unregistered code path against UTC midnight, so 'registered today' and 'ordered today' can disagree with the same figures on the dashboard and roll over at the wrong hour for a Tashkent tenant — exactly the 'one metric layer' promise PART 2 makes (item 12 of 'Where operations beats Delever'). | M | ADR 0043 has no customer metric definitions and no agreed business-day boundary; registering them is a metric-registry version bump that cannot be backfilled silently. | P26 |  |
| `5.1b` | Bulk CSV import with retained provenance | P | NOT BUILT | A merchant migrating off Delever cannot load their existing customer base at all; every row would have to be typed through the create dialog one phone number at a time, and there is no place to record where a row came from (the consent source enum already has IMPORT, with nothing writing it). | L | Needs the PART 4 pilot-blocker ImportWizard (FileDropzone + row-level preview + dry-run diff + JobProgress + ResultSummary), which does not exist — the same component 4.5 and 3.6 need. | P26 |  |
| `5.1c` | Filtered export as an audited PII egress event | P | BUILT | The export is silently capped at EXPORT_LIMIT = 2000 rows in CustomerListQueryService and neither the response nor the console says the set was truncated, so a marketer exporting a 5,000-customer base gets 2,000 rows and no warning. | S | — | — |  |
| `5.2` | Customer detail — profile and date of birth | P | BUILT | Nothing for the profile itself. The IA's 'the screen Delever does not have' claim is real here — but five of its named sub-features are separate rows below and four of them are not built. | S | — | — |  |
| `5.2a` | Contact points on the customer record | P | BUILT | — | M | — | P25 |  |
| `5.2b` | Consent state and marketing eligibility, separated from record existence | P | BUILT | — | M | There is no tenant-wide consent-type/policy-version registry anywhere (settings/data-privacy/data-privacy-page.ts says so in its own doc: 'a decision log exists, a type registry does not'); the purpose vocabulary is an ADR 0015/0044 decision, not a screen fix. | P25 |  |
| `5.2c` | Saved addresses, operator-visible and editable | P | PARTIAL | `coordinateSource` is now rendered on every address card, and an existing pin is now preserved through a text-only edit (previously a regression risk, now guarded by a test). An operator still cannot create a new address with real coordinates from this screen — there is still no suggest, no pin-drop and no map, blocked on the same MapCanvas/AddressPicker (`X.4`) and unmade map-provider decision as `3.2`/`3.6`. | M | Named open input on ADR 0015: geocoder/map provider selection (product). PART 4 lists MapCanvas + MapPin/AddressPicker as a pilot blocker with 'no map primitive exists at all'. | P25 |  |
| `5.2d` | Order history + reorder | P | PARTIAL | The reorder button lands on a not-built page: an operator taking a repeat order by phone has to retype the basket elsewhere, even though the platform can already resolve a repeat against the live menu. | M | — | P40 |  |
| `5.2e †` | Cashback balance and ledger | P | PARTIAL | Read-only: `POST .../loyalty/adjustments` exists on the same controller and nothing on this pane calls it, so an operator cannot grant or correct a goodwill balance from the customer record. Ledger rows also print `entry.amountMinor` raw while the balance above uses formatMoney, so the two numbers look like different currencies. | S | — | P40 | BUILT |
| `5.2f` | Deposit (stored-value) balance ledger | P | BLOCKED | A customer cannot hold a prepaid balance and an operator cannot see or move one; Delever's deposit payment method has no counterpart here. | XL | Named legal input, listed in /Users/admin/Developer/HorecaOS/platform/docs/frontend-and-parity-plan.md's blocked table: 'ADR 0046 — Whether HorecaOS may hold customer prepaid balances at all — Legal'. | deferred |  |
| `5.2g` | Promo redemptions on the customer record | P | NOT BUILT | When a customer claims a code was already used, an operator cannot see which codes this person redeemed, when, or on which order — the data is in the database with an index built for exactly this query and no endpoint over it. | M | — | P40 |  |
| `5.2h` | Reviews left by this customer | P | NOT BUILT | An operator handling a complaint cannot see this customer's rating history without scrolling the whole brand-wide review list and matching account ids by eye. | S | — | P40 |  |
| `5.2i †` | Blacklist / suppression with reason, actor, expiry and enforcement point | P | PARTIAL | Nothing operationally. The reason is free text with no tenant reason registry, so blacklist reasons cannot be reported on. | S | — | P40 | BUILT |
| `5.2j` | Identity merge for aggregator-masked identities | P | BUILT | No duplicate detection — an explicit non-goal in CustomerIdentityService's own doc ('this call is the write, not the search') — so finding the two accounts is still the operator's eye, and the merge search is a plain text box, not the async Combobox PART 4 asks for. | S | — | — |  |
| `5.3` | Segments — RFM builder, saved segment entity, segmentation table, birthday window | 2 | PARTIAL | The reach a marketer is shown is wrong: segments-page.ts:322 passes SNAPSHOT_PURPOSE = 'Operations console: segment snapshot from Customers 5.3' as the *consent purpose*, and MarketingEligibility matches that string exactly against recorded consent (which is written as 'MARKETING' by the import and 'MARKETING_PROMOTIONS' by campaigns), so every candidate is refused CONSENT_WITHHELD and the member count reads ~0. There is also no segmentation table: the page never calls the snapshot export endpoint, so an operator can size a segment but cannot see or download who is in it. | M | — | T05 |  |
| `5.4` | Reviews — service-recovery board | 2 | PARTIAL | An operator cannot work a bad review: there is no New -> In progress -> Resolved board, no handling history, no courier or four-dimension (meal/operator/courier/delivery-time) breakdown, and no compensation action — issuing a promo code or bonus means leaving for the order-remedy console with the order id copied by hand. One scalar 1-5 rating is all the data model holds. | XL | Reopening this needs a new ADR: ADR 0071 rejected the kanban and the scored dimensions on the record, so the IA's §5.4 row and the accepted decision currently disagree. | deferred |  |
| `5.5` | Feedback settings — review tag library, prompt timing, public visibility | 3 | NOT BUILT | A tenant cannot offer one-tap feedback tags, cannot choose when a customer is asked for a review per channel or order type, and cannot decide whether ratings are public. | XL | Needs a moderation/tagging decision that ADR 0071 explicitly declined, plus LocalizedFieldGroup and MediaUploader (3:2 tag icons) from PART 4's pilot-blocker list. | deferred |  |
| `X.1 †` | Data-subject erasure on the customer record (raise / withdraw / execute) | ? | PARTIAL | When a customer demands erasure, a tenant operator has no way to raise, withdraw or execute the request from the console — the only screen that shows those requests is the platform's own control plane, which a merchant cannot reach. The IA gives §5 no row for this at all; PART 3's nearest entry is wave 3 'data & privacy self-service'. | S | — | P40 | NOT BUILT |

## §6 — Marketing

15 rows — 1 built · 3 partial · 10 not built · 1 blocked

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `6.1` | Promotions — the discount/markup/promo-code rule engine | 2 | NOT BUILT | A marketer cannot author any automatic discount or markup at all: no order-vs-product scope, no gift triggers, no eligibility by order type × source × payment method, no Nth-order/first-order rules, no priority, stacking or cashback-compatibility arithmetic, no date/time/weekday window, no geozone or branch/customer/category scoping, and no quote simulator. The only discount authorable in the whole console today is a typed promo code (6.2). | XL | Rule vocabulary narrower than parity: /Users/admin/Developer/HorecaOS/platform/docs/delever-parity-matrix.md line 77 names markup (наценка), geozone-polygon conditions and named-customer conditions as missing condition/action types — a schema change, not a screen. PART 4 has no ConditionBuilder, RuleList with priority reorder, or RuleSimulator, and no DateRangePicker/DayOfWeekToggle/MoneyOrPercent. | deferred |  |
| `6.2` | Promo codes — shared campaign codes, lifecycle and limits | 2 | PARTIAL | An operator cannot give a code an expiry or a scope: the draft form has no validFrom/validUntil, channels or locationIds fields at all (grep for those names over promo-codes-page.ts/.html returns nothing, although PromoCodeController.DraftPromoCodeRequest accepts them), so every code created from this screen runs forever across every channel and branch, and the table (promo-codes-page.html:47-58) has no expiry column. There is also no redemption ledger: pricing.coupon_redemptions has no read endpoint, so the screen shows a bare redeemedCount and nobody can see which customer redeemed a code, on which channel, when. | M | — | T05 |  |
| `6.2a` | Per-instance unique promo codes (the late-order apology code) | 2 | NOT BUILT | An operator cannot issue a one-off code that only one named customer can redeem, so service recovery for a late order has to fall back on a shared code word anyone can spend, or on a manual loyalty adjustment on the customer record. | L | — | T05 |  |
| `6.3` | Loyalty — accrual rate, redemption cap, point expiry | 3 | PARTIAL | Points expire silently: expiryWarningDays is authorable on the accrual rule (loyalty-api.ts:28,43; loyalty-page.html:326-334) but nothing sends the pre-expiry message — ADR 0046's own checklist line 786 says 'Expiry warning notifications are not built'. A rule's location/channel scopeId is typed as a raw identifier rather than resolved through a picker (loyalty-page.ts:49-56), so a manager can bind a rate to the wrong branch without the screen noticing. | M | — | T18 |  |
| `6.3a` | Loyalty — deposit accounts (customer stored value) | 3 | BLOCKED | An operator cannot top up, refund or spend a customer cash balance, and deposit is not selectable as a payment method on an order — the Депозит half of the Delever loyalty pair simply does not exist. | XL | A Central Bank of Uzbekistan e-money authorisation (or the owner's decision to hold funds under a tenant's own licence). ADR 0046 §'What would bring stored value back' requires a new ADR, not an implementation task. | deferred |  |
| `6.3b` | Loyalty — POS balance sync | 3 | NOT BUILT | A tenant whose till also holds bonus balances runs two ledgers: points earned or spent at the POS terminal never reach the platform, and a platform redemption is invisible to the till, so a cashier and the app disagree about what a customer has. | XL | No ADR covers which side owns the balance; each POS vendor (iiko, R-Keeper) needs its own capability adapter. | deferred |  |
| `6.4` | Campaigns — lifecycle, audience targeting, suppression | 2 | PARTIAL | Only Telegram can actually deliver — notifications/application/CampaignTelegramDeliveryService.java:162-164 returns isWired=true for MESSAGING_APP alone — yet the create form offers SMS, EMAIL and PUSH with no warning (CHANNELS, campaigns-page.ts:32-37), so an operator can spend a four-eyes approval on a campaign that will never send. There is no scheduled send (launch is immediate; the only deferral is per-recipient quiet hours — CampaignService has no scheduling), no campaign history/statistics view, and the audience-snapshot export endpoint is not wired into MarketingApi, so the filtered-audience CSV the IA row implies is unreachable. | M | — | T18 |  |
| `6.4a` | Campaigns — SMS, email and push delivery channels | 2 | NOT BUILT | A marketer choosing SMS, email or push gets a campaign that drafts, estimates and passes approval and then cannot launch; the IA row's push cover (3:1, scheduled send, recipient and read counts) and SMS per-recipient delivery receipts have nowhere to come from. | L | A real SMS gateway contract — integration/camel/notification/SmsGatewayAdapter.java is deliberately generic because 'no SMS contract exists yet'; email and push have no provider named at all. | deferred |  |
| `6.4b` | Campaigns — couriers as a separate SMS audience | 2 | NOT BUILT | A dispatcher cannot send an operational blast to couriers (shift change, weather, a route closure) from the console; Delever's Курьеры SMS tab has no counterpart, and the workaround is an external phone list. | M | — | T18 |  |
| `6.5` | Automations — birthday, cashback change, late-order apology, inactivity/abandonment triggers | 2 | NOT BUILT | Nothing sends without a human. A marketer can hand-build a BIRTHDAY_WITHIN_DAYS audience (marketing/domain/PredicateType.java:53) and launch a manual campaign each morning, but birthdays, cashback accrual/debit messages, the late-order apology with its minted code, and inactivity or cart-abandonment follow-ups (with cancellation when the action completes) never fire on their own. | XL | — | deferred |  |
| `6.6` | Referrals — the referral programme and its rewards | 3 | BUILT | Nothing an operator needs on this screen. The residual gap is outside it: frontend/storefront and the ADR 0075 Telegram bot do not call ReferralStorefrontController, so a customer can only see and share their code from storefront-milliy. | S | — | — |  |
| `6.6a` | Referrals — trackable acquisition links and the Mini-App/BotFather setup flow | 3 | NOT BUILT | A marketer cannot mint a trackable website ?ref= link or a Telegram startapp deep link for a campaign or an influencer, and no account or order records which link brought it, so acquisition-source analysis has nothing but the channel the order arrived on. There is also no guided, resumable Mini-App/BotFather setup — an integrator does that by hand in Telegram. | L | — | T18 |  |
| `6.7` | Content — banners, stories, pop-ups, promotion content cards | 2 | NOT BUILT | An operator cannot put anything in front of a customer except products: no banner (image or video, priority, per-channel placement, active period), no story group with ordered slides and view counts, no entry pop-up with frequency capping, and no promo card linked to a promotion rule — so every storefront home screen shows whatever the app hardcodes. | XL | PART 4 pilot blockers that do not exist: MediaUploader with aspect-ratio crop and video support, LocalizedFieldGroup for the {ru,uz,en} strings every creative carries, plus SortableList for story slide order. | deferred |  |
| `6.7a` | Content — editorial surfaces (news, gallery albums, recipes, vacancies) | ? | NOT BUILT | An operator cannot publish news, a photo gallery, a recipe or a job listing from the console and must use their own website or a Telegram channel. This is a recorded exclusion, so the honest gap is that the IA row still lists these sub-features while two platform documents say they will not be built. | XL | An owner decision is required to reinstate scope the parity matrix and ADR 0044 both excluded; PART 4's RichTextEditor (block-based, sanitized) does not exist either. | amend |  |
| `6.8` | Storefront merchandising — home groups, offer carousel, brand switcher, marketplace layout | 2 | NOT BUILT | An operator cannot decide what a channel's home screen shows — no manual or by-category product groups with priority ordering, no offer carousel, no multi-brand switcher entries, and no marketplace layout mode — so ordering of the storefront landing surface is a frontend code change, not a merchandiser's decision. | L | Depends on the same unbuilt merchandising-slot tables as 6.7; PART 4 has no SortableList/TreeView with drag-reorder for group ordering. | deferred |  |

## §7 — Reports

39 rows — 2 built · 13 partial · 24 not built

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `7.1` | Business overview — KPI counters (revenue, orders, average check, cook/delivery/pickup time, distance) | P | PARTIAL | A manager cannot see delivery time, pickup time or distance on the headline dashboard — two of the five IA-named counters render as literal 'not built' cards and distance has no tile at all. No tile carries the per-metric '?' published-formula panel statistics.md §1.2 requires: ReportingApi.metrics() exists in reporting-api.ts but no page calls it. | L | reporting.fact_delivery and kitchen fire-to-pass timings have no producer; ADR 0042's CourierAccrualService.recordDelivery has no production caller | P27 |  |
| `7.1a` | Business overview — sales funnel by status with cancel reasons and late completions | P | PARTIAL | An operator sees how many orders were cancelled and under which reason code but cannot see what a cancellation cost (write-off vs returned stock, who bore the liability); the breakdown is a table, not the funnel the IA names — no funnel chart exists in the design system. | S | — | P27 |  |
| `7.1b` | Business overview — source mix (channel and fulfilment-type mix) | P | BUILT | Nothing an operator cannot do. The bars are hand-rolled CSS rather than the PART 4 Chart family, so there is no legend, tooltip or drill-through, and a channel with no orders in range is absent rather than shown as zero. | S | — | — |  |
| `7.1c` | Business overview — payment mix | P | NOT BUILT | A manager cannot see what share of takings came in as cash, card or online — the figure a restaurant uses for cash-collection control — anywhere in Reports. | L | — | P39 |  |
| `7.1d` | Reports — the shared global filter bar applied to every widget | P | PARTIAL | A manager cannot pick a custom date range, change granularity, filter by branch, channel or legal entity, step the range with the arrow keys, or share a filtered view as a URL — filters are in-memory and reset on reload. ReportsFilterState.channelCodes exists but no control sets it and no page forwards channelCode, so the channel slice is dead state. | M | — | P27 |  |
| `7.2` | Order reports — per-stage duration report («Этапы») | P | PARTIAL | The clock a manager reads as 'kitchen time' is really the order's PREPARING→READY status gap and is not labelled as such; there is no courier/delivery stage and no operator-accept stage, and the read is capped at 200 rows with no pagination, so a wide range is silently a sample. | M | — | P27 |  |
| `7.2a` | Order reports — commercial/CRM order log («Заказы») | P | PARTIAL | The CRM half of the log is absent: an operator cannot see which customer, operator or courier an order belonged to, so the log serves money and status reconciliation only, not a call-back or an operator dispute. No column chooser, no saved views, no server pagination (200-row cap). | L | — | P27 |  |
| `7.2b` | Order reports — daily operations report («Посуточно»: gross vs net, cancellations, per-fulfilment count+sum, per-3PL counts) | P | PARTIAL | A manager cannot see per-aggregator (Yandex/Uzum) order counts or commission in the daily table — the 'per-3PL counts' the IA names are absent, so the aggregator share of a day has to be inferred from 7.1's channel mix. | M | — | P27 |  |
| `7.2c` | Order reports — daily summary reports 1 and 2 («Сводка») | P | PARTIAL | The roll-up reads as a long list instead of a branch×channel grid, and neither summary carries the delivery-inclusive sum column Delever's report 1 has, so comparing two branches across five channels means scanning rather than reading a matrix. | S | — | P27 |  |
| `7.2d` | Order reports — delayed-orders report («Опоздания») | P | BUILT | Nothing an operator cannot do, beyond the shared 200-row cap: a wide range shows the worst 200 late orders rather than all of them, and lateness cannot be exported. | S | — | — |  |
| `7.2e` | Order reports — Excel/CSV export as an audited PII egress (plus the export centre) | P | NOT BUILT | Nobody can take a single figure out of the console: no Excel/CSV of any report, no column chooser with a PII group, no row quota, no audit fact recording who exported what — the control ADR 0029 relies on to catch a bulk customer egress. | XL | — | P28 |  |
| `7.3` | Branch & SLA reports — branch leaderboard | 2 | PARTIAL | Columns the IA and spec name are absent: average delivery time, on-time percentage and the delivery/pickup/aggregator count triple — so a manager can see which branch is slow at the pass but not which is slow at the door. No secondary sort control, and prep time costs one request per branch. | M | — | T06 |  |
| `7.3a` | Branch & SLA reports — SLA time-bucket distribution with tenant-configurable boundaries | 2 | PARTIAL | The distribution is real but the boundaries are platform-fixed: a tenant whose promise is 45 minutes cannot re-cut its own buckets without a release. Cosmetic gaps: no median column and no green→red tint ramp. | S | Deliberate refusal: ADR 0043 fixes the bucket set in code and statistics.md §2.3 calls the IA stale here; making it tenant-configurable needs an owner decision to overturn that | T06 |  |
| `7.3b †` | Branch & SLA reports — branch report with per-channel counts and the payment-method split for cash collection | 2 | PARTIAL | A branch manager cannot reconcile cash: there is no per-branch split of takings by payment method, and no per-channel (Админ/Бот/Сайт/Приложение) count-and-average-check block per branch. | L | — | T06 | NOT BUILT |
| `7.4` | Courier reports — courier leaderboard and efficiency (min/avg/max/total distance, transit hours) | 2 | NOT BUILT | A dispatcher cannot compare couriers on distance, transit hours, deliveries or on-time percentage anywhere in Reports — the worst performer, which is the reason the screen exists, is invisible. | L | — | T11 |  |
| `7.4a` | Courier reports — courier SLA time buckets | 2 | NOT BUILT | A manager cannot see how one courier's deliveries distribute across the six duration buckets, so an individual's slowness cannot be separated from a branch's. | M | — | T11 |  |
| `7.4b` | Courier reports — delivery-sum-by-tariff audit | 2 | NOT BUILT | Finance cannot audit which tariff zone each courier actually worked, so an overcharged or mis-zoned delivery fee is undetectable from Reports. | M | — | T11 |  |
| `7.4c` | Courier reports — external-delivery cost report (order amount vs charged delivery vs provider cost vs provider status) | 2 | NOT BUILT | Nobody can list per-order 'charged to customer vs billed by provider vs variance vs reconciliation status', mark rows reconciled, or keep UNBILLED rows out of the variance total — the one report in §7 that finds money. | L | — | T11 |  |
| `7.5` | Staff reports — operator leaderboard and performance (orders, revenue, average handling time, average check, per-channel, delivery vs pickup; machine principals as pseudo-operators) | 2 | NOT BUILT | A call-centre manager cannot see how many orders an operator took, what they were worth, or the average handling time the IA singles out, nor compare human operators against the bot and website as typed pseudo-operators. | L | — | T12 |  |
| `7.5a` | Staff reports — operator product/upsell report with receipt depth | 2 | NOT BUILT | A manager cannot tell whether upsell coaching worked: there is no operator × product view and no receipt-depth figure anywhere in the console. | L | — | T12 |  |
| `7.5b †` | Staff reports — telephony KPIs (calls handled, answer speed, conversion), when 1.6 is installed | 2 | NOT BUILT | A supervisor cannot see answer speed, missed calls or call-to-order conversion in Reports even though the platform already computes them at every day close — the tab is deliberately not rendered until real telephony exists. | M | ADR 0064: no live VOICE provider account exists (both adapters are proven only against a fake PBX) and the owner's numbering decision (per-brand DIDs vs a shared line) is still open | T12 | BLOCKED |
| `7.6` | Customer analytics — KPI tiles with published formulas (new customers, basket depth, order frequency, customer value, LTV) and the cancelled/refunded rule | 2 | NOT BUILT | An owner cannot see whether the customer base is growing or what a customer is worth, and the published-formula panel that was the whole credibility argument against Delever's unstated LTV does not exist. | L | — | T13 |  |
| `7.6a` | Customer analytics — registrations vs orders trend, repeat-purchase distribution, cohort views, new vs returning revenue and share | 2 | NOT BUILT | Nobody can tell whether the people ordering this month are new or returning, or how a cohort decays — the monthly question the screen exists to answer. The PART 4 Chart family (line, cohort heatmap) is also still missing. | L | — | T13 |  |
| `7.6b` | Customer analytics — RFM cross views | 2 | NOT BUILT | A marketer can build an RFM segment but cannot read RFM as an analytic cross view (cells with member counts and revenue) to decide which cell is worth targeting. | M | — | T13 |  |
| `7.6c` | Customer analytics — acquisition-source chart and the product funnel (visits → registrations → cart adds → orders) | 2 | NOT BUILT | There is no visibility into anything before the order — visits, registrations and cart adds are recorded nowhere, so the funnel will start empty on the day it is finally built, and acquisition source is unknown. | XL | ADR 0043 open input: legal must confirm lawful basis and retention for behavioural telemetry (running on a provisional default today, so a risk rather than a hard stop) | deferred |  |
| `7.7 †` | Product analytics — product report («Продажи») | 2 | PARTIAL | Complete for the IA's 'product report' bullet; the spec's extras are absent — no Категория column (the API returns categoryId and the table drops it), no СТОП marker on a stopped product, no sort control, 200-row cap. | S | — | T14 | BUILT |
| `7.7a` | Product analytics — ABC by revenue share with the cumulative column | 2 | NOT BUILT | A manager cannot see which products make 80% of revenue, nor the cumulative curve and recorded thresholds that let a class-C ruling be disputed — the explicit improvement over Delever. | L | — | T14 |  |
| `7.7b` | Product analytics — XYZ (stdev / coefficient of variation) and the ABC×XYZ matrix as filters | 2 | NOT BUILT | Nobody can separate steady sellers from erratic ones, and there is no AX/CZ matrix to click through to the products worth acting on. The 28-day minimum window the spec requires is also unexpressible in today's filter bar. | L | — | T14 |  |
| `7.7c` | Product analytics — kiosk sales report | 2 | NOT BUILT | Nothing an operator loses today — statistics.md §2.7 deliberately declines this report ('a separate report for one channel is a precedent that ends in eleven reports'); if kiosk (10.5) ships, kiosk sales must be readable as a channel slice, which the filter bar cannot express yet. | S | Deliberately declined in statistics.md §2.7; reinstating it is an owner call, not engineering | amend |  |
| `7.8` | Demand forecast — forecast vs actual by hour and branch | 3 | PARTIAL | A manager sees what usually happened on this weekday, not what is expected: no forecast line, no forecast-vs-actual comparison, no confidence interval, no branch selector (own location only), and the hour axis is wall-clock 00:00–24:00 rather than the operating-day window that may cross midnight. | XL | — | W02 |  |
| `7.8a` | Demand forecast — by department and product | 3 | NOT BUILT | A kitchen cannot plan prep per station or per dish — demand is orders-per-hour for the whole branch, which is not enough to pre-portion anything. | L | — | W02 |  |
| `7.8b` | Demand forecast — holiday-aware modelling (calendar owned in 10.10) | 3 | NOT BUILT | A holiday silently skews the average, and a manager cannot exclude or weight those dates when reading demand. | L | — | W02 |  |
| `7.9` | Marketing reports — promo-code summary and per-code redemption detail (customer, channel, timestamp) | 2 | NOT BUILT | A marketer cannot tell how many times a code was redeemed or how much discount it gave away, let alone who redeemed it and through which channel — promotion spend is invisible after the fact. | XL | No promotions ADR exists (statistics.md §7 names the owning ADR as absent); the redemption fact grain needs one before it can be designed | deferred |  |
| `7.9a` | Marketing reports — per-customer discount history | 2 | NOT BUILT | Nobody can answer 'how much has this customer been discounted' before granting another goodwill code — the abuse check the report exists for. | L | — | T15 |  |
| `7.9b` | Marketing reports — campaign delivery and read statistics | 2 | NOT BUILT | A marketer cannot see how many recipients a campaign reached or how many opened it — only a capped raw recipient list from the Marketing section, with no read receipts at all. | L | — | T15 |  |
| `7.10` | Geography — order-density heatmap over the delivery zones | 3 | NOT BUILT | Nobody can see where orders actually come from against the zone boundaries they drew, so a badly cut zone or a missing branch catchment is undetectable from the console. | XL | PART 4 pilot blocker: no MapCanvas/PolygonEditor primitive exists, and Yandex Maps credentials are a provider input nobody has supplied | deferred |  |
| `7.10a` | Geography — today's orders as pins | 3 | NOT BUILT | A dispatcher cannot see the day's orders on a map, so clustering and outlier drops have to be inferred from the order list. | L | Same MapCanvas gap as 7.10 | deferred |  |
| `7.10b` | Geography — delivery-time and distance histograms | 3 | NOT BUILT | A manager cannot see the distribution of delivery durations or distances — only the fixed six SLA buckets per branch — so a long tail of far deliveries stays invisible. | L | — | W04 |  |
| `7.10c †` | Geography — day-of-week × hour cohort grid | 3 | PARTIAL | A manager cannot read the whole week as one heat grid to decide staffing, and cannot click a cell to see the orders behind it. | M | — | W04 | NOT BUILT |

## §8 — Finance

11 rows — 3 built · 6 partial · 1 not built · 1 blocked

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `8.1` | Payments & settlements | P | BUILT | A granted future discount still cannot be redeemed anywhere, since ADR 0018 pricing never calls `RemedyEntitlementPort`, and `DeliveryFeeBasisPort` stays unwired — both pre-existing, deliberately not fixed by this wave. | M | — | P29 |  |
| `X.1 †` | 8.1 — split tender: the payment[] array (cash + cashback + deposit) | 3 | PARTIAL | The `payment[]` array — every settlement tender, in sequence, with status and refunded amount — is now on the order-payment read and rendered in `payments-page.html`. Recording a redemption leg after checkout is deliberately not built: doing so would mean shrinking an already-created `PaymentIntent` and renegotiating it with the provider mid-flight, a real design decision, not an oversight. Deposit tender remains out of scope. | XL | — | W05 | NOT BUILT |
| `8.2` | Fiscal receipts | P | BUILT | — | M | — | P29 |  |
| `X.2` | 8.2 — the fiscal evidence itself: receipt URL/codes, INN used, fiscalized payment types, delivery IKPU, marking codes | P | NOT BUILT | An operator handed a customer complaint or a tax query cannot produce the receipt: no fiscal sign or receipt URL, no statement of which INN issued it, no fiscalized payment types, no delivery-line IKPU and no marking codes anywhere in the console — and no correction or void command exists to fix a wrong one. | XL | ADR 0038's legal open input — which party is the legal fiscal agent per settlement path (legal, finance), per platform/docs/frontend-and-parity-plan.md:168 — governs correction/void obligations and marked-goods handling. | deferred |  |
| `8.3` | Cash reconciliation | 2 | PARTIAL | The worklist will be empty on a real tenant: a handover only opens when CourierShiftService.openCashHandover sees cashCollectedDuringShift > 0, and that query (JdbcCourierLedgerStore.java:387) joins fulfillment.courier_assignment_earnings, which nothing writes — CourierAccrualService.recordDelivery has no production caller (only a test; ADR 0042's own status line and JdbcCourierShiftStore.java:133 both say so). Separately, the screen shows one bag per shift, not the IA's courier daily/shift totals by payment method, and no bonus-paid amount reducing cash due. | L | — | T07 |  |
| `8.4` | Delivery cost reconciliation | 2 | PARTIAL | An operator can read an exposure but cannot act on one: no акт сверки workflow, no charged-vs-cost delivery margin (the report returns cost only — grep for 'margin' in the page finds only the doc comment quoting the IA), and no provider terminal status. Cost lines also only appear if somebody imports an invoice, since PartnerInvoiceService.recordPartnerCost has no production caller outside CourierCompensationTests. | M | — | T07 |  |
| `X.5 †` | 8.4 — partner invoice import and variance matching (operator workflow) | 2 | PARTIAL | An operator handed Yandex's or Noor's monthly settlement file has no way to load it or to resolve a VARIANCE / UNMATCHED_LINE row — the only path is calling the API directly, so the invoice worklist shows whatever an integration happened to write and nothing more. | M | — | T07 | NOT BUILT |
| `8.5` | Courier payouts | 2 | PARTIAL | The salary report's columns are not there — SettlementPeriodView (courier-finance-api.ts:118-138) has no km, no hours, and no penalties/bonus split, only delivered and on-time counts — and there is no export: financePaths.settlementPeriodStatement is declared in core/api/finance-paths.ts but CourierFinanceApi has no method for it, so the statement the backend generates and hashes cannot be opened or downloaded from the console. Every figure is also zero in production, because gross earnings come from CourierAccrualService.recordDelivery, which nothing calls outside tests. | L | — | T07 |  |
| `8.6` | Subscription & billing | 2 | PARTIAL | Plan, entitlements, usage and statements all render now. A purchasable-module catalogue with inline purchase, an arrears state and restricted-feature banner, period close, and the prepaid wallet (`8/X.3`, still blocked on ADR 0095) all remain absent. | L | — | T19 |  |
| `X.4 †` | 8.6 — invoices the merchant can read | 2 | BUILT | — | M | — | T19 | NOT BUILT |
| `X.3` | 8.6 — prepaid wallet and top-up via Click/Atmos | 2 | BLOCKED | A merchant cannot hold a prepaid balance, top it up with a card, see bonus money kept apart from money it paid, or be warned before a credit lapses — the activation deposit is billed as an ordinary statement line instead of being held. | XL | ADR 0095 is not-started with 'Date decided: —'; its Deciders line records that the platform owner answered the inputs on 2026-09-11 but that Ayubkhon Abbosov (platform owner) has yet to decide the proposed structure. No schema, service or endpoint may be written before that acceptance. | deferred |  |

## §9 — Staff: grants, roles, telegram links, shifts

20 rows — 6 built · 6 partial · 8 not built

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `9.1 †` | Users & roles — accounts and role assignment (grants) | P | PARTIAL | Capability search and the dead holder-count button are now real, so a manager can find a grant by name or role — but the TENANT-scope `IAM_GRANT_MANAGE` limit that keeps this screen unreachable by a branch manager is only raised as a proposal in ADR 0103, not fixed: the spec's «Chilonzor manager sees Chilonzor's team» view is still unreachable by the person it was designed for. | S | — | P30 | BUILT |
| `9.1a` | Users & roles — accounts: invite a staff member (Пригласить) | P | NOT BUILT | An operator cannot add a new colleague: there is no way to create an account from the console, so a person must already exist in Keycloak before any job can be given to them, and nobody types a Keycloak subject UUID by hand. | L | ADR 0097 (partial) open inputs: which sending provider, SPF/DKIM/DMARC on horecaos.uz (platform owner), and counsel's answer on a foreign provider processing staff email under ZRU-547 — nothing is delivered until those exist. | deferred |  |
| `9.1b` | Users & roles — capability grid, capability search, role templates (Должности) | P | PARTIAL | An owner cannot author a job of her own, cannot tick or untick a single permission, and cannot search the permission list — she can only read what the eight fixed jobs happen to carry and pick the nearest one. | L | ADR 0025 closed input: «no tenant-defined roles in v1» — reopening that decision is the trigger for the permission grid. | deferred |  |
| `9.1c †` | Users & roles — permission-gated navigation | P | BUILT | — | M | — | P30 | NOT BUILT |
| `9.1d †` | Users & roles — locked-by-plan vs denied-by-permission, with inline upsell | P | BUILT | — | M | — | P30 | NOT BUILT |
| `9.2` | People — the staff person record (operator records: name, phone, photo, employment) | 2 | NOT BUILT | Every name on every Staff screen — and every actor in the audit log — is a UUID, so a manager cannot tell who a row is, let alone phone them; there is no employment status, employee number, photo or languages anywhere. | XL | No ADR owns staff identity: ADR 0025 is authorization, ADR 0009 is Keycloak reconciliation, ADR 0042 is couriers. Needs a new staff-identity ADR covering the employment record and terminal access. | deferred |  |
| `9.2a` | People — branch bindings (who works where) | 2 | BUILT | Nothing substantive for the binding itself; it inherits 9.1's ceiling — only a tenant-scope administrator can see or change it, and a person with no grant at all has no row to bind. | S | — | — |  |
| `9.2b` | People — contact persons | 2 | NOT BUILT | A manager cannot record who to call about a branch (or for a person), so contact details live in someone's phone rather than in the system. | M | — | T20 |  |
| `9.2c` | People — operator ↔ POS operator ID mapping | 2 | NOT BUILT | An order pushed to iiko cannot carry the HorecaOS operator who took it, so POS-side reporting attributes every order to the integration account. | L | — | T20 |  |
| `9.2d †` | People — created-by / accepted-by attribution | 2 | NOT BUILT | Deferred by this wave. A manager still cannot see how many orders an operator took today from the person's own card, and the actor on an order is still a UUID rather than a name — T08's new `StaffDisplayNames` port resolves actor names for audit rows, but nothing wires it onto an order or a staff card yet. | M | — | T08 | PARTIAL |
| `9.3` | Activity & audit — the activity log screen (История изменений) | 2 | PARTIAL | Cursor paging past 200 events, four backend-ready filters, an action-code dictionary with a humanized fallback, and a person-picker datalist are now built, with the screen's first `.spec.ts`. The dictionary is still not complete code-to-sentence coverage, and there is still no deep link from a person's card into their own activity. | M | — | T08 |  |
| `9.3a` | Activity & audit — field-level before/after diff | 2 | PARTIAL | `ChangeDocuments.diff` exists and round-trips through the viewer's own parser, migrated onto 2 of roughly 130 `.changed(...)` call sites (`brand.revised`, `location.revised`) — the rest still write a flat after-only map. The rendering itself is also a hand-rolled `<div class="diff">` in `activity-log-page.html` rather than T22's `q-diff-viewer` component, which has no call site anywhere in the app. | L | — | T08 |  |
| `9.3b` | Activity & audit — a named human actor, even for background paths | 2 | BUILT | — | M | — | T08 |  |
| `9.3c †` | Activity & audit — a bulk action produces N records, not one | 2 | BUILT | — | S | — | T08 | PARTIAL |
| `9.4` | Approvals — the maker-checker worklist | 3 | PARTIAL | A Decided tab and a decided-history read are both now live, so a manager can see what she approved last week. Two of the IA's four named cases still cannot appear at all: no call site raises a PII-export approval (`CustomerController.export` still records the reveal without gating it) and there is still no discretionary order-line discount producer; thresholds are still authored only in control-plane. | M | — | W03 |  |
| `X.1` | Telegram staff links (person ↔ Telegram account) | 2 | PARTIAL | A staff member has no screen anywhere in the console that hands her the `/link <code>` command, so the only built path to a link is someone reading it out of the API; and an administrator can see that Aziza is linked but never to which account, nor unlink her. | S | — | T20 |  |
| `X.2` | Смены — staff shifts and attendance (spec §7, non-courier staff) | 2 | NOT BUILT | A branch manager cannot roster or record a single hour for kitchen or floor staff, so the «часы» a payroll conversation needs exist only for couriers. | XL | Needs a decision: amend ADR 0042 or fold staff shifts into the new staff-identity ADR — the spec is explicit it must not become a third shift model. | deferred |  |
| `X.3` | Терминалы — shared devices and staff PINs (spec §8) | 2 | NOT BUILT | A kitchen tablet has no way to say which human pressed «ready», so the first tenant with a shared login called `kuxnya` silently empties the audit log of meaning. | XL | No owning ADR; the spec proposes folding it into the staff-identity ADR from §11.1. | deferred |  |
| `X.4` | Проверка доступа — access check («can she do this, and why») | 3 | BUILT | — | M | — | W03 |  |
| `X.5` | Мой профиль — staff self-service (spec §10) | 2 | NOT BUILT | A staff member cannot see or change anything about her own account: no name or phone to correct, no sign-in history, no way to link her Telegram, no UI personalization. | M | — | T20 |  |

## §10 — Settings: brands, locations, channels, legal entities, payments, delivery, notifications, terms, policies

36 rows — 8 built · 21 partial · 7 not built

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `10.0` | Settings home & "Find a setting" | P | PARTIAL | A readiness panel now names every offending location (not just the first) and deep-links to it, and '/' finds a setting by filtering the six-group nav index — both real, but narrower than settings.md's ambition: only the payment step reports every offending item (delivery/POS/catalog readiness still stop at the first), the panel only checks what `OnboardingController.validate` already covers (no fiscal assignment, channel-payment coverage, notification moderation or secret-rotation-age checks exist anywhere), and find-a-setting is a label/description filter over the nav index, not the full Combobox over every configuration key and reference list the spec asks for. | M | — | P31 |  |
| `X.1` | Scope bar + InheritedField (settings.md §1.1/§1.2 — the pattern every row renders through) | P | PARTIAL | The scope bar and InheritedField now exist and are live across Settings, so a merchant can set a value at brand level and override it for one branch, and see which level they are about to change. The resolution-trace popover shows only scope+outcome per level — no per-level actor/reason, since ADR 0030's `ResolutionTrace` carries neither. 'Locked by plan', the fifth InheritedField state, is not wired, since no ADR 0021 entitlement data reaches this surface. And the bar itself only ever offers BRAND/LOCATION — a TENANT-level edit (needed by, for example, P46's order-policy cards) has no selector at all. | XL | — | P31 |  |
| `10.1` | Brand profile | P | PARTIAL | An operator cannot change the trade name, upload a logo or aggregator/QR banner, write a description in any language, set the contact phone or Telegram handle, or choose supported languages — the whole screen is a read-out and brand facts are changed only through control-plane onboarding. | L | — | P32 |  |
| `10.2a` | Locations — branch list | P | PARTIAL | A chain manager cannot answer 'which branches are shut and why' from this list, cannot filter by current state or channel, and cannot close or reopen several branches at once — the per-row close/open modal and the bulk bar do not exist. | M | — | P32 |  |
| `10.2b` | Locations — detail Tab 1 (Основное: address, phone, landmark, map pin, venue attributes, tags, sort order) | P | PARTIAL | An operator cannot place or correct the branch's map pin (the column and the write both exist; no UI reaches them), and cannot set sort order, tags, seats/average cheque/parking or localized branch content at all. The Notifications tab points at Settings → Notifications, whose routing tab is itself not built — a dead end. | L | — | P32 |  |
| `10.2c` | Locations — detail Tabs 2–3 (Hours, prep-time bands, order limit) | P | PARTIAL | An operator cannot edit a branch's opening hours, add a holiday exception, bind a different timetable to delivery vs the venue, set time-of-day prep-time bands (the write endpoint exists, unused), or set pre-order lead times. Only the manual open/close override and the order ceiling work. | L | — | P43 |  |
| `10.2d †` | Locations — Floor plan tab (sections and tables) | 3 | PARTIAL | An operator cannot create a hall section, add tables, or rotate a table's QR token, so QR dine-in cannot be configured from the console at all even though the platform supports it. | L | — | P38 | NOT BUILT |
| `10.3a` | Order policy — Card 1 Приём заказа (acceptance mode, approval channel, timeout, timeout action) | P | BUILT | Only that it publishes straight through with no draft → diff → activate step and no version history read, and it can only be written at brand level (no per-branch override, see X.1). Courier-first vs branch-first acceptance is not expressible — settings.md §4 calls it design-forcing on ADR 0019's state machine, not configuration. | S | — | — |  |
| `10.3b` | Order policy — Cards 2–5 (timings & SLA, automation/auto-accept, conditions, operator order entry) | P | PARTIAL | Order policy cards 2-5's eleven fields (VAT, minimum order sum, the late threshold, the working-day boundary, auto-accept rules, out-of-zone rejection, operator hand-entry rules) are now authorable at BRAND/LOCATION through the console via `q-inherited-field`, and TENANT scope is proven reachable at the API/Java layer — but it has no selector in the console: P31's `SettingsScope` exposes only `'BRAND'` and `'LOCATION'` as its `SettingsEditingLevel`, so an operator cannot set a tenant-wide default from Settings. | XL | — | P46 |  |
| `10.4a` | Sales channels — channel registry | P | PARTIAL | An operator cannot say which branches a channel sells from (the endpoint exists, unused), cannot activate or suspend a channel, and cannot set its icon, brand colours, social links or the three per-channel order-entry toggles the IA assigns to this row. | L | — | P33 |  |
| `10.4b` | Sales channels — capability matrix (payment method × channel, order type × channel) | P | PARTIAL | The matrix offers three hard-coded methods, so a tenant that registers TELEGRAM or MARKETPLACE cannot enable it, and the list can silently disagree with payments.payment_methods. No row/column bulk toggle and no hatching of fiscally-incapable cells. | M | — | P33 |  |
| `10.5` | Channel setup (Telegram bot, website/domain, mobile apps, kiosk device registry, aggregator, call centre) | P | NOT BUILT | A tenant cannot configure a single storefront: no bot token verify-and-name flow, no subdomain or custom domain, no website colours/menu ordering/SEO/static pages, no kiosk device pairing, no QR dine-in mode choice. Without this the pilot has no customer-facing surface to take an order through. | XL | — | deferred |  |
| `10.5b †` | Channel setup — QR dine-in modes and table QR codes | P | PARTIAL | An operator cannot choose view-only vs pay-open-bill vs full self-ordering, nor issue or rotate a table's QR code, even though both endpoints answer today. | M | — | P38 | NOT BUILT |
| `10.6` | Payment methods (registry, localized name, icon, base type, acquirer binding) | P | NOT BUILT | A tenant cannot add, rename, localize, icon, order, activate or disable a payment method, and cannot bind one to its acquirer installation — the registry only ever grows by accident, from whatever a checkout happened to tender. | L | — | P33 |  |
| `10.7a †` | Fiscalization — Tab 1 Юридические лица (legal entities, INN, per-branch assignment) | P | BUILT | — | S | — | P34 | BUILT |
| `10.7b` | Fiscalization — Tab 2 Фискальные терминалы | P | BUILT | Terminal register/manage capabilities (`FISCAL_TERMINAL_MANAGE`/`READ`) are BRAND-scoped, so a location-level operator needs a brand-level grant to register or service their own branch's terminal. ADR 0038's 'cash requires a bound fiscal-capable terminal' precondition is still not enforced as a hard checkout-time refusal — deliberately left for the payment-method-registry wave (row `1.2c`). | L | — | P34 |  |
| `10.7c` | Fiscalization — Tab 3 Классификация товаров (IKPU/MXIK coverage, delivery-line IKPU + package code, marking, VAT defaults) | P | PARTIAL | `CatalogQueryService.fiscalCoverage` (how much of the menu is unclassified) and a delivery-fee ИКПУ/marking write are now wired to the previously-uncalled fiscal-classification endpoint — a minimum: bulk IKPU/package-code backfill and an ИКПУ/MXIK typeahead still do not exist anywhere. P21's fiscal workbench, not yet merged, is expected to supersede this surface. | L | — | P34 |  |
| `10.8a` | Integrations — hub: provider installs, connect fields, secret door, rotation, bindings | P | PARTIAL | The bindings list/activate/suspend, capability-reconciliation and Clopos-settings endpoints are all wired now, and `q-secret-input` is built and live. The per-branch install model — tenant-default overridden per branch, which the IA and ADR 0030 both assume — is still absent: an install remains tenant-wide, and retiring an installation stays out of scope per ADR 0065. | M | — | P35 |  |
| `10.8b †` | Integrations — mapping tables (products, payment types, discounts, couriers, cancellation reasons, channel → POS codes) | P | PARTIAL | A tenant that connects iiko or R-Keeper cannot map its own payment types, discounts, couriers or cancellation reasons onto the provider's, so orders flow out with unmapped codes and nothing in the console explains why. | XL | — | P24 | NOT BUILT |
| `10.8c †` | Integrations — health & errors (last successful inbound per branch × provider, error taxonomy, merchant-scoped replay) | P | PARTIAL | Marketplace liveness now renders, with a tenant-scoped error taxonomy and inbox replay, both capability-gated. Liveness watermarks for POS, fiscal and notification providers — not only marketplace — are still not built; this wave ships only the narrower `installations.secret_last_used_at` watermark instead (evidence a secret still resolves, not evidence of a live provider call), named as an open ADR 0106 input. | L | — | P35 | NOT BUILT |
| `10.8d` | Integrations — partner API credentials as an OAuth client with a rotatable secret | P | BUILT | Provisioning the real `horecaos-partner-provisioning` Keycloak service-account credential in each environment is an infrastructure step outside this repository, owned by platform operations. | M | — | P35 |  |
| `10.8e` | Analytics installs (GTM, GA4, Search Console), the GA4 ecommerce event contract, and the telephony provider install | P | PARTIAL | `ProviderCategory.ANALYTICS`, the install shape (GTM/GA4/Search Console non-secret fields), the storefront analytics read/injector, and a versioned GA4 ecommerce event contract are all built, with `purchase` wired at order confirmation. `view_item` and `add_to_cart` are contract-ready with a shared helper but have no call site in any product or cart screen yet. | L | — | P35 |  |
| `10.9a` | Notifications — Tab 1 order-status and OTP templates | P | PARTIAL | A marketer cannot see or insert the merge variables, cannot preview the message, and cannot key a template to order type or source — so one wording has to serve delivery, pickup and dine-in across every channel. | L | — | P36 |  |
| `10.9b` | Notifications — Tab 2 Маршрутизация (Telegram chat IDs per event class per branch, topic IDs) | P | NOT BUILT | A manager cannot say where this branch's order, error, cancellation, dispatch-failure and stop-list alerts go — there is no screen to link a chat, pick a topic, or choose which event classes a chat receives, so alerting is invisible from the console. | L | — | P36 |  |
| `10.9c` | Notifications — provider moderation state and the send block it implies | P | PARTIAL | The block is enforced but invisible: a merchant publishes an OTP wording, the platform silently withholds every send until a gateway approves it, and no operations screen says so or shows the state. | S | — | P36 |  |
| `10.9d` | Notifications — payment-link auto-send and aggregator shift open/close notifications | P | NOT BUILT | An operator cannot turn on automatic sending of a payment link to the customer, and cannot have the aggregator shift open/close messages delivered in the tenant's own timezone — both are Delever-parity behaviours with no switch. | M | — | P36 |  |
| `10.10a †` | Reference data — cancellation and completion reasons | P | PARTIAL | `P37` closed both named gaps: the screen now calls `OperationsOrderOutcomeReasonController` instead of reaching across to control-plane, and gained a versioned edit path (with the "creates a new version" warning), the `allowedFulfillmentModes` control, and system category/status/default refund columns. What remains: reasons still cannot be drag-reordered (no `display_order` column). | S | — | P37 | BUILT |
| `10.10b` | Reference data — business calendar (holidays, business-day boundary across midnight, weekend definition) | P | BUILT | `P37` built the tenant's own weekend declaration, its own holiday list (`BusinessCalendarController`), and the boundary editor `BusinessDayService.setBoundary` never had a caller for, gated by a new ADR 0027 approval action. A boundary change marks a recut outstanding rather than performing one; `DayCloseService` still does the actual recut. Not built: offering to create `tenant.service_schedule_exceptions` rows from a holiday — settings.md says "offer", never "silently create", and no UI builds the offer yet. | L | — | P37 |  |
| `10.10c †` | Reference data — SLA bucket boundaries | 2 | PARTIAL | ADR 0107 resolves the contradiction this row named: SLA buckets stay platform-fixed and versioned (ADR 0043), full stop — tenant configurability is declined, not deferred. `P37` ships the honest half instead: a tenant-readable version card (`ReportingController.slaBucketSet`) naming the active bucket set so a report can be read against the definition it was computed under. An operator still cannot change where 'late' starts, by design. | M | — | P37 | BLOCKED |
| `10.10d` | Reference data — branch tags | 2 | BUILT | `P37` built the registry (`tenant.branch_tags`), the per-branch assignment (`tenant.location_branch_tags`), and the settings screen's location × tag matrix — the filter-and-group affordance this row named as the reason to have tags at all. | M | — | P37 |  |
| `10.11` | Data & privacy (retention schedules, consent definitions, DSAR/erasure, export audit) | 3 | PARTIAL | Retention periods for three categories, a tenant consent-type registry, and a tenant-wide DSAR erasure worklist are all live now (the worklist's own URL bug — a missing `/customers` segment — is fixed). Export and correction of a customer's own data remain not-built, per ADR 0029 — unchanged, and explicitly out of scope. | L | — | W03 |  |
| `10.12` | Languages & regional formats (supported languages, default language, currency/phone/timezone formats) | 2 | NOT BUILT | A tenant cannot choose which languages its storefront offers or which is default, so every localized field in the console (~40 forms) is authored against a hard-coded ru/uz-Latn/en triple. | M | — | P32 |  |
| `10.13` | Delivery policy in Settings (out-of-zone addresses, what the courier sees and may do, GPS action checks) | P | NOT BUILT | An operator cannot decide what happens to an address outside every zone, cannot restrict couriers to kitchen-ready orders, cannot set when the customer's exact location is revealed, and cannot turn GPS verification on — the courier rulebook is readable and unwritable. | L | — | P38 |  |
| `10.14` | Printing & receipts (POS printing, fiscal receipt presentation, tape width) | ? | NOT BUILT | An operator cannot choose what prints where, on what tape width, or what the customer's receipt looks like; every print today is whatever the POS adapter happens to do. | XL | No ADR owns printing or receipt presentation — one has to be written before any screen can be specified (settings.md §4). | deferred |  |
| `X.2` | Terms of service (storefront terms per brand, per locale, versioned) | P | BUILT | Nothing an operator needs for the pilot. Only a rich-text editor is absent (plain textareas per locale) and the route/api comments still cite 'ADR 0067', which is the referral-programme ADR — the real record is ADR 0068. | S | — | — |  |
| `X.3 †` | HorecaOS support visits (who from support entered this account, and ending a live visit) | 2 | BUILT | — | S | — | P31 | BUILT |

## §X — Shells and design system

40 rows — 12 built · 26 partial · 2 not built

| # | Row | Tier | Status | What is missing | Size | Blocked by | Wave | Reader said |
|---|---|---|---|---|---|---|---|---|
| `X.1` | Operator console shell (dense, sidebar, keyboard-first) | P | PARTIAL | The shell's 'always visible' counters have no fetch of their own — /Users/admin/Developer/HorecaOS/frontend/operations/src/app/shell/service-status.ts holds three signals (`open`, `late`, `updated`, lines 28-30) and all three are only ever written by features/orders/order-queue.ts:217 through `set()` — so the late and open badges read zero until the operator opens Orders and then freeze at the last value the moment they navigate away, which is exactly the failure the shell's own doc comment says it exists to prevent. The updated-at signal exists and is rendered nowhere. No connection or stale banner, no toast host, no brand picker or scope readout (settings.md §1.1's scope bar is half-built), and no keyboard scheme beyond F2. | M | — | P08 |  |
| `X.2 †` | KDS / kitchen device fullscreen shell (touch, no chrome, offline banner) | P | PARTIAL | A kitchen tablet enrolled under ADR 0079 has no application to run: no device-authenticated fullscreen route, no touch scale, no offline banner for a mid-service connectivity drop. And nobody can pair one — the console has no screen at all for the approve/list/revoke endpoints, so the enrolment code a device shows on the wall cannot be accepted by a manager. | L | — | P17 | NOT BUILT |
| `X.3` | Wallboard shell (TV-distance, oversized counters) | 2 | BUILT | — | M | — | T23 |  |
| `X.4 †` | MapCanvas + PolygonEditor + BoundingBoxEditor + MapPin/AddressPicker | P | PARTIAL | An operator cannot draw or edit a real delivery polygon, cannot set a region's SW/NE bounding box to constrain the geocoder, and cannot place or correct an order's address pin — a zone is a radius in metres typed into a form. | XL | ADR 0015 open input, 'Geocoder/map provider selection (product)' (/Users/admin/Developer/HorecaOS/platform/docs/adr/partial/0015-customer-accounts-cross-brand-identity-and-consent.md:96), inherited by ADR 0037's own open-inputs line. No provider chosen means no tiles, no geocoder, no credential — the IA names Yandex as the market expectation but nobody has decided. | deferred | BLOCKED |
| `X.5` | LocalizedFieldGroup (language tabs, default marker, completeness) | P | BUILT | A tenant still cannot auto-translate a field across locales — deferred as a platform-owner decision on a translation provider (PART B), not a build gap of this component. | M | — | P02 |  |
| `X.6` | DataGrid (inline edit, fill-down, keyboard nav, virtualization) | P | PARTIAL | `q-data-grid` is built and tested (inline edit, fill-down, keyboard nav, virtualization, a batched save modeled on the bulk-actions endpoint) but has no call site anywhere in the console; its first intended consumer is P21's bulk IKPU/price edit, not merged. | L | — | P03 |  |
| `X.7` | MatrixGrid (editable cross-tab with row/column bulk toggle) | P | PARTIAL | Wired into `sales-channels-page` as one row per selected channel, not yet the full channels×methods cross-tab `10.4b` names — that remains P33's job, not merged. | M | — | P03 |  |
| `X.8` | ActionMenu + Drawer + Modal + ConfirmDialog | P | PARTIAL | `q-modal` shipped with real call sites (create-customer-dialog, order-reason-dialog, reference-data-page, track-reveal-dialog, plan-shift-dialog). `q-action-menu`, `q-drawer` and `q-confirm-dialog` still have zero call sites anywhere in the console after 27 merged waves — P05's own order-row overflow menu, one of the component's two intended first consumers, was hand-rolled instead of migrated onto `q-action-menu`. | M | — | P01 |  |
| `X.9 †` | Combobox (async search, create-on-miss, multi-select chips) | P | BUILT | — | M | — | P02 | NOT BUILT |
| `X.10 †` | MoneyInput / PercentInput / MoneyOrPercent | P | BUILT | — | S | — | P02 | NOT BUILT |
| `X.11` | DateRangePicker + TimeInput + DayOfWeekToggle + ScheduleGrid | P | PARTIAL | TimeInput and DayOfWeekToggle are live at `capacity-page`. DateRangePicker and ScheduleGrid are built and tested but have no call site yet — DateRangePicker's first consumer is the reports filter bar (P27) and ScheduleGrid's is the settings hours editor (P32), neither merged. | L | — | P02 |  |
| `X.12` | MediaUploader with aspect-ratio crop (+ video) | P | PARTIAL | An operator cannot see the photo they just uploaded, cannot crop it to an aggregator's required ratio, and cannot upload a logo, banner (image or video), kiosk idle media, story slide or review-tag icon anywhere in the console — product photos are the only media path that exists. | M | — | P22 |  |
| `X.13 †` | ImportWizard (dropzone, row preview, dry-run diff, job progress, results) | P | PARTIAL | A merchant cannot import a price list or a customer file, and cannot even review a POS catalog difference report from operations — the dry-run/apply loop is platform-staff-only, so the per-item outcome reporting the IA sells as beating Delever has no merchant-facing surface. | L | — | P26 | NOT BUILT |
| `X.14` | SecretInput (masked, reveal-once, copy, rotate, last-used) | P | BUILT | — | S | — | P35 |  |
| `X.15` | StatusPill extensions (lateness overlay; dual-state order+cooking pill) | P | BUILT | — | S | — | P01 |  |
| `X.16` | LockedState + DeniedState (distinct from EmptyState) | P | BUILT | — | S | — | P01 |  |
| `X.17 †` | Toast + InlineAlert | P | BUILT | — | M | — | P01 | NOT BUILT |
| `X.18` | DataTable extensions (saved views, persisted filters, column chooser, server paging + infinite scroll, selection with bulk-action bar, row action menu) | P | PARTIAL | Persisted filters, saved views, a column chooser, cursor paging/infinite scroll, a selection model with a bulk-action bar, and a row action menu are all built; selection+bulk bar is live at `stop-list-page` and cursor paging at `products-page`. Saved views and the column chooser have no live call site yet, and the order board's own filter/bulk toolbar (P07) still depends on this component, not merged. | L | — | P03 |  |
| `X.19` | Chart family (line, bar, stacked, donut, funnel, histogram, cohort heatmap, sparkline, geo heatmap) | 2 | PARTIAL | Line, bar, stacked-bar, donut, histogram and an hour×weekday heatmap are all built and live over real report queries. Funnel, cohort heatmap, ABC cumulative curve and geo heatmap remain unbuilt — no data source exists yet for any of them; they arrive with T13/T14/a deferred geography row. | L | — | T09 |  |
| `X.20` | KpiTile (value + delta + sparkline) and WallboardTile | 2 | BUILT | — | M | — | T09 |  |
| `X.21 †` | Board / BoardColumn / BoardCard with drag-drop | 2 | PARTIAL | A dispatcher cannot see unassigned orders laid against courier columns. Scope note for planning: only 3.1 still forces this component, so it should be sized as dispatch-only rather than as a general kanban. | M | — | P18 | NOT BUILT |
| `X.22 †` | DragDropAssign (drag a card onto a target card) | 2 | PARTIAL | Assigning a courier is a picker and a click per order; nothing shows a courier's current load as a drop target and there is no drag-to-unassign, so two dispatchers working the same queue still race each other. | M | — | P18 | NOT BUILT |
| `X.23` | SortableList / TreeView with drag-reorder | 2 | NOT BUILT | A category's position and parent are fixed at the moment it is created — nobody can reorder a menu, re-parent a category, or sequence modifier options from the console. | M | — | P23 |  |
| `X.24` | MappingPane (dual list, link/unlink, unmapped filter, bulk auto-match) | 2 | NOT BUILT | A merchant cannot see which POS products, payment types, courier services, cancellation reasons or channel codes failed to map, and cannot fix one — the single most repeated integration screen in the inventory has no surface at all. | L | — | P24 |  |
| `X.25` | ConditionBuilder + RuleList with priority reorder + RuleSimulator | 2 | PARTIAL | `q-condition-builder` is wired into `segments-page` as its sole implementation (AND/OR grouping). `q-rule-list` (drag+keyboard priority reorder) and `q-rule-simulator` (candidate-driven match preview) are built and tested but have no live consumer yet — the promotion and dispatch-rule screens that would use them are future waves. | L | — | T21 |  |
| `X.26` | Timeline + DiffViewer + ActorChip | 2 | PARTIAL | `q-timeline` is wired into `order-detail-pane`'s lifecycle lane and the staff activity log; `q-actor-chip` replaces three duplicated `actorLabel` implementations (`data-privacy-page`, `product-editor-page`, and the audit query's own `actor_display` resolution). `q-diff-viewer` has no call site anywhere: T08's `activity-log-page` renders its own hand-rolled `<div class="diff">` markup instead of consuming it, so the same 'three ideas re-implemented per screen' problem this row exists to fix has reappeared even after the component was built. | M | — | T22 |  |
| `X.27` | TemplateEditor with VariableChip + live preview in PhoneFrame | 2 | PARTIAL | An operator writing an order-status message has nothing telling them which merge variables exist or what the result will look like on a phone — a mistyped variable name ships silently to customers. | M | — | P36 |  |
| `X.28` | PhoneFrame siblings (AggregatorCardFrame, KioskFrame 9:16, TelegramMiniAppFrame) | 2 | PARTIAL | `q-phone-frame`, AggregatorCardFrame, KioskFrame and TelegramMiniAppFrame are all built and spec'd, but against a storefront-shaped sample only — no live consumer is wired anywhere, since neither ADR 0040 nor a kiosk config exists yet to preview against. | M | — | T22 |  |
| `X.29` | NumberStepper | 2 | PARTIAL | The component is built and tested but has no call site anywhere in the console yet; its first intended consumer is P13's order composer, not merged. | S | — | P02 |  |
| `X.30` | SplitPane (master-detail) | 2 | BUILT | — | S | — | P01 |  |
| `X.31` | RichTextEditor (block-based, sanitized) | 2 | BUILT | The storefront still renders terms content as plain text via a numbered-point regex split (`TermsSectionsPipe`); a tenant using a heading or a list in the new sanitized editor will show literal tags to customers until the storefront is updated to render the emitted HTML with its own sanitizer pass. | L | — | T22 |  |
| `X.32` | ColorInput | 2 | PARTIAL | The component is built and tested but has no call site anywhere in the console yet; its first intended consumer is P32's brand settings, not merged. | S | — | P02 |  |
| `X.33` | Steps / ProgressRail | 2 | BUILT | — | S | — | T22 |  |
| `X.34` | LiveBadge / RefreshIndicator / StaleIndicator / ConnectionState banner | 2 | PARTIAL | A board that stopped updating looks exactly like a quiet shift — no staleness marker, no connection banner, and nowhere on the KDS to learn that the screen is showing cancelled work. Every screen also pays a designed-in 10-second lag while a built SSE endpoint goes unused. | M | — | P08 |  |
| `X.35 †` | QRCode display + DataMatrix scan input | 2 | PARTIAL | Nobody can print a table QR, pair a kiosk or a KDS by scanning a code, or scan a marked-goods DataMatrix at receipt — the QR half of dine-in exists in the platform with no way for a merchant to get the codes onto tables. | M | — | P17 | NOT BUILT |
| `X.36` | FloorPlanCanvas + TableToken + TimelineScheduler (1.5 Reservations) | 3 | PARTIAL | A host cannot see the room — section layout and table adjacency are invisible, so 'can I fit a party of six at 20:00' is answered from a grid of names — and a booking cannot be seated or completed anywhere in the console. | L | — | P38 |  |
| `X.37` | CallBar + CallScreenPop (1.6 Telephony) | 3 | PARTIAL | An operator anywhere but the call-centre screen never sees a call arrive — the screen pop cannot follow them across the console, which is the entire purpose of a call bar. | L | — | W01 |  |
| `X.38` | OtpInput (staff MFA) | 3 | PARTIAL | The component (6-cell code entry, paste-fill, auto-advance) is built and tested but has no consumer and no backend: staff MFA has no endpoint anywhere, so nothing in the console can render it yet. | S | — | T22 |  |
| `X.39` | Semantic status colour ramp (SLA green/amber/red at bucket boundaries + tenant late colour) | P | PARTIAL | Two screens can disagree about when the same order is 'at risk'; a tenant cannot configure its SLA buckets (the IA's stated advantage over Delever's hard-coded six) or its late colour, and nothing checks that a chosen highlight stays legible against the ramp. | M | — | P06 |  |
| `X.40` | dir/script-safe type stack (uz-Latn, Cyrillic ru, Latin en; kk and ka on the roadmap) | 2 | PARTIAL | All 261 raw `font-size: Npx` declarations found across 65 files are fixed via 11 `--q-type-*` custom properties, and P02's ESLint rule now enforces no regression. Kazakh and Georgian faces are still not bundled and no `dir`/RTL handling exists — adding kk or ka still means a font and layout change, not just a message file. | S | — | T22 |  |

---

# PART B — What is not in the plan, and why

## Blocked: six rows waiting on an input nobody in engineering owns

| # | Row | Tier | Size | The input | Who owns it |
|---|---|---|---|---|---|
| `2.6a` | Cook headcount output | 3 | XL | A portions-per-cook-hour policy (it varies by station and cuisine, and nobody has stated it) and a demand-forecast decision. ADR 0041 L661-666 says so in as many words. `GET /reporting/demand-history` could stand in for the forecast in a first cut once the ratio exists. | Owner / product |
| `X.5` auto-translate | The auto-translate button on LocalizedFieldGroup | P | M | The component itself ships in `P02`; the **button** has no service to call. `ProviderCategory` declares POS, PAYMENT, DELIVERY, MARKETPLACE, NOTIFICATION, GEOCODING and VOICE and no translation category, and every `translate` hit in `platform/src/main/java` is the manual `PUT /translations` path. The parity matrix grades trilingual content **with** auto-translation ●, so this is a real parity item, not a nicety. | Owner / product — pick a provider (and answer whether machine output may be published unreviewed, which is a brand-voice question, not an engineering one). |
| `4.7` | Reference data — attributes, tags, ingredients, kitchen departments, comment presets | 2 | L | ADR 0016's open input on legacy merchandising disposition, in particular whether Атрибуты are the variant axis or a spec-sheet vocabulary. The spec forbids building any of it first. Note kitchen-department routing already exists by another route (`KitchenStationController` routing rules), so only the vocabularies wait. | Owner / product |
| `4.9` | Auto-add rules | 2 | XL | No ADR owns server-side cart injection; catalog.md and the IA both record it as unowned and needing a product decision before design. The portion-band kind additionally waits on `4.2c` portions-as-decimal. | Owner / product |
| `5.2f` | Deposit (stored-value) balance ledger | P | XL | «Whether HorecaOS may hold customer prepaid balances at all» — named in the blocked table of `frontend-and-parity-plan.md`. | Legal |
| `6.3a` | Loyalty — deposit accounts | 3 | XL | A Central Bank of Uzbekistan e-money authorisation, or the owner's decision to hold funds under a tenant's own licence. ADR 0046 withdrew stored value outright; its return is a new ADR, not an implementation task. | Legal / owner |
| `8/X.3` | Prepaid wallet and top-up | 2 | XL | ADR 0095 is `not-started` with «Date decided: —». The inputs were answered on 2026-09-11; the platform owner has not yet accepted the proposed structure. No schema, service or endpoint may be written before that acceptance. | Platform owner |

## Deferred: rows an agent could not finish in an isolated worktree

Each of these is XL, or depends on a decision that has to be written down first. They are
not scheduled as waves; they are scheduled as **decisions**.

| # | Row | Tier | Size | Why it is not a wave | What unblocks it |
|---|---|---|---|---|---|
| `0.1d` | Live operator leaderboard | P | XL | Even a counting endpoint would print Keycloak subject UUIDs. | The staff-identity ADR (staff-and-access.md §11.1), then `9.2`. |
| `0.2c` | Personal data (own profile) | 2 | XL | Nowhere for a staff name, phone or email to live. | The same ADR, plus an answer on whether Личные данные includes self-service password/MFA. |
| `0.2d` | UI personalization (user-scoped) | 2 | L | ADR 0030 has no user scope. | An ADR 0030 amendment and an owner ruling on what Персонализация contains. |
| `1.2c` | Amendments — the seven financial commands | P | XL | `AmendmentCommandType` marks all seven `built=false`; each needs its repricing, payment, fiscal and POS consequence. | No external input — sequence it **after P33** (payment-method registry) and **P34** (fiscalization), then schedule as three waves, one per consequence family. |
| `1.3d` | Pre-order time with out-of-hours warning | P | M | ADR 0019 names scheduled orders as still open; V0056 deliberately leaves the requested-time column out. | An ADR 0019 decision on scheduled orders. The out-of-hours half is already servable from `ServiceabilityResolver`. |
| `2.1b` | Preset product comments | P | L | Needs the coded vocabulary in `4.7`. | `4.7` unblocking. |
| `2.1d` | Change payment type from the KDS | P | S | `CHANGE_PAYMENT_METHOD` is declared and refused server-side. | `1.2c`. |
| `2.5a` | Stop scope and channel propagation | P | XL | A data-model change: availability must become `(product × scope)` with a source. | An ADR superseding ADR 0017's binary model, plus ADR 0040's outbound adapters. |
| `3.1b` | `/deliveries` variant for courier-service tenants | ? | XL | The discriminator exists; what does not is a decision to let business type drive routing — ADR 0090 explicitly rejected that. | Owner answer to «is HorecaOS one product or several product shapes?» |
| `3.8` | Dispatch rules | P | XL | No decision record covers an operator-authored, provider-agnostic rule engine; racing and grouping semantics are open. | A new ADR. The sourcing policy document already exists, so the wave after it is one controller plus one screen. |
| `4.2a` `4.2b` `4.2c` | Combos · modifier depth · physical attributes | 3 | XL ×3 | Each needs new columns on `catalog.variants`/`modifier_groups` and a publication transport format. | An ADR 0016 amendment. `4.2c` must land with marking (ADR 0038 forbids splittable marked goods). |
| `4.4a` | The named Menu entity | P | XL | Nothing to copy and nothing to bind; even the degraded fallback is unavailable because the offering write is one variant × one location. | An ADR 0016 amendment (IA PART 5 §2). |
| `4.4c` | Per-item stock quantity, daily reset | 2 | XL | `QUANTITY` tracking throws `UnsupportedTrackingModeException`; the daily seed job is unowned. | An ADR 0017 amendment. |
| `4.5b` | Excel import/export | P | XL | There is no import-job entity anywhere, and no multipart endpoint outside media. | Widening ADR 0012 past POS sources, plus ADR 0010's SSRF-guarded URL fetch. |
| `4.6a` | Aggregator preview and per-channel projection | 2 | XL | There is no channel projection to render. | ADR 0040's menu-pull and availability-push adapters. |
| `5.4` | Reviews — service-recovery board | 2 | XL | ADR 0071 rejected the kanban and the scored dimensions on the record; the IA and the accepted decision disagree. | A superseding ADR, or an IA amendment. The `review → remedy` link is small and can ship regardless. |
| `5.5` | Feedback settings | 3 | XL | The moderation and tagging decision ADR 0071 declined. | The same ADR. |
| `6.1` | Promotions rule engine | 2 | XL | The engine and the conditions exist; authoring endpoints do not, and parity needs markup, geozone-polygon and named-customer condition types — a schema change. | ADR 0018's `POST /promotions` and `/validate`, plus a ruling on the three missing condition types. |
| `6.3b` | Loyalty — POS balance sync | 3 | XL | No ADR covers which side owns the balance; each POS needs its own capability adapter and a `pos_provider_capabilities` ceiling row. | An ADR plus a vendor. |
| `6.4a` | SMS, email and push campaign channels | 2 | L | `SmsGatewayAdapter` is deliberately generic because no SMS contract exists; email and push have no provider at all. | A gateway contract. The `isWired` gate on the channel picker is small and should ship in `T18` regardless. |
| `6.5` | Automations | 2 | XL | No trigger schema, no service, no `trigger_firings` table. | No external input — sequence after `T05` delivers `6.2a` coded benefit grants. |
| `6.7` `6.8` | Content slots · storefront merchandising | 2 | XL, L | No merchandising-slot tables exist on either side, and the creatives need MediaUploader with crop and video. | ADR 0044's slot tables; then one wave each. |
| `7.6c` | Acquisition source and the product funnel | 2 | XL | Visits, registrations and cart adds are recorded nowhere; the funnel starts empty whenever it is built. | Legal confirmation of lawful basis and retention for behavioural telemetry (ADR 0043 open input). |
| `7.9` | Promo-code summary and redemption detail | 2 | XL | ADR 0023 forbids a report reading a module schema, so the redemption fact is the required path and its grain needs an owner. | A promotions ADR. |
| `7.10` `7.10a` | Order-density heatmap · today's orders as pins | 3 | XL, L | No map primitive, no geographic dimension in the facts. | `X.4` (map provider) and a reporting geo aggregation. |
| `8/X.2` | The fiscal evidence itself | P | XL | Receipt URL, fiscal sign, issuing INN, marking codes and the correction/void commands are all absent, and the obligations differ by settlement path. | ADR 0038's legal open input: which party is the legal fiscal agent per settlement path. |
| `9.1a` | Invite a staff member | P | L | Nothing is delivered until a sender exists. | ADR 0097's open inputs — sending provider, SPF/DKIM/DMARC on `horecaos.uz`, and counsel on a foreign processor under ZRU-547. |
| `9.1b` | Capability grid, search, role templates | P | L | ADR 0025's closed input, «no tenant-defined roles in v1». | Reopening that decision. Capability **search** alone is frontend-only and rides in `P30`. |
| `9.2` · `9/X.2` · `9/X.3` | Staff person record · staff shifts · terminals and PINs | 2 | XL ×3 | No ADR owns staff identity: 0025 is authorization, 0009 is Keycloak reconciliation, 0042 is couriers. | One staff-identity ADR covering the person record, the employment record, staff shifts and terminal access. This single ADR unblocks nine rows. |
| `10.5` | Channel setup | P | XL | No storefront-domain table, no DNS-TXT verification, no kiosk registry, no bot verify-and-name flow. | A decision covering domains and the kiosk device registry. The QR dine-in slice is already carved out into `P38`. |
| `10.14` | Printing and receipts | ? | XL | Settings §4: «A print capability port on ADR 0011, and a decision that owns printing and receipt presentation at all. Nothing owns it today.» | An ADR. |
| `X.4` | MapCanvas · PolygonEditor · BoundingBoxEditor · MapPin | P | XL | No provider means no tiles, no geocoder, no credential. Region CRUD is the one genuinely backend-missing piece and ships in `P20`. | ADR 0015's «approve address/geocoder provider», inherited by ADR 0037. |

## Amend the IA, do not build

Five rows describe behaviour a decision record has already refused. Building against them
would be building against a superseded design.

| # | Row | The decision that refuses it | Done |
|---|---|---|---|
| `1.2d` | Multi-step (multi-branch) composition | orders.md:52 and :1342 — Delever's `steps[]` is declined, «two nested state machines for a behaviour no evidence shows anyone using». | 2026-09-11 — IA §1 row `1.2`: the clause is struck in place in the **Owns** list with a dated note citing orders.md §0.1 and the parity matrix. |
| `1.6a` | Call centre — softphone and click-to-call | ADR 0064's Alternatives refused WebRTC inside operations. The IA's «Owns: softphone» line should be struck with the ADR cited. | 2026-09-11 — IA §1 row `1.6`: «Owns: softphone» struck with a dated note quoting ADR 0064's Alternatives; click-to-call is removed from the screen's purpose line in the same note, because dialling needs the same client. |
| `6.7a` | Editorial surfaces (news, gallery, recipes, vacancies) | The parity matrix excludes the general-purpose CMS with tenant-authored raw HTML; ADR 0044 repeats it. The IA still lists them in three places. | 2026-09-11 — struck in all three: IA §6 row `6.7`'s **Owns** list, the «Deliberately excluded from operations» bullet on a recruitment ATS (which had kept vacancies as content), and PART 4's **RichTextEditor** component row. Each keeps a dated note citing the parity matrix's exclusion and ADR 0044. The control-plane exclusion bullet in PART 1 still names consumer content generically and was left alone. |
| `3.6` free geozone | «Бесплатная геозона» as a geometry layer beside zones and branch geozones | `fulfillment/domain/zone/ZoneRole.java` — «a 'free geozone' is not a third role: it is a DELIVERY zone whose tariff resolves to zero, and expressing it as a layer is what left three layers with no documented interaction». The IA's 3.6 bullet and the parity matrix's ○ row should both read «a zone whose tariff resolves to zero», and the buildable half is `3.6`'s tariff binding, tracked as `3.6d`. | 2026-09-11 — IA §3 row `3.6`'s **Owns** list and the parity matrix's ○ «Free geozone (Бесплатная геозона)» row both now read «a DELIVERY zone whose tariff resolves to zero, shown as free», citing `ZoneRole.java` and ADR 0037. The buildable half stays `3.6d` in `P20`. |
| `7.7c` | Kiosk sales report | statistics.md §2.7: «a separate report for one channel is a precedent that ends in eleven reports». Kiosk must be readable as a channel slice instead. | 2026-09-11 — IA §7 row `7.7`: «kiosk sales report» replaced by «kiosk readable as a channel slice of the sales report», citing statistics.md §2.7. |

Two further IA defects the verification surfaced, both documentation rather than build:
the **operator inbox** (`§1/X.1`, shipped, ADR 0059) has no IA row at all, and **KDS device
enrolment** (`§2/X.2`, backend complete) has none either — IA 10.5 registers kiosks, not
kitchen devices. `1.5a`'s «auto-create client on unknown phone» and «external reservation ID
as the displayed identifier» contradict a deliberate ADR 0047 decision and should be struck
from the row rather than scheduled.

**Done, 2026-09-11.** The operator inbox is now IA §1 row `1.7` and KDS device enrolment is
IA §2 row `2.7`, each marked as already existing — `1.7` shipped under ADR 0059 stage 2,
`2.7` backend-complete under ADR 0079 with no console caller. Both strikes in `1.5a` landed
in IA §1 row `1.5`, with one dated note citing ADR 0047: a booking for a guest with no
account creates no customer record and no consent, and the external reservation id lives
once in `integration.provider_entity_mappings` as a lookup key rather than as the displayed
identifier. No other IA row was touched; `settings.md`, `orders.md` and `statistics.md`
already carried the refusing wording and needed no edit. IA PART 3's pilot screen lists
were deliberately left alone: they enumerate what the pilot has to **build**, and `1.7` is
already shipped while `2.7`'s console half is scheduled here as `2/X.2` in `P17`.

---

# PART C — The build plan

## How this plan is meant to be run

**One wave, one worktree, one agent.** Each wave below is written to be handed to a fresh
agent with no other context. The brief names the rows, what *done* means for each, the
files, the endpoints and **their capability constants by name**, the migration numbers it
may use, the ADR to write or amend, **the tests it must add**, and the traps that have
already caught a reader. A brief that leaves the agent a choice is a defect: where two
implementations were arguable, this revision picks one and says which wave owns the file.

**Row ids are section-qualified wherever they repeat.** Five ids — `X.1`, `X.2`, `X.3`,
`X.4` and `X.5` — name a different row in each of up to six IA sections (`X.1` alone is
the operator inbox in §1, customer erasure in §5, split tender in §8, Telegram staff links
in §9, the settings scope bar in §10 and the console shell in §X). Wherever one of those
appears in the wave index or a brief it is written `<section>/<id>` — `8/X.1`, `10/X.1`,
`X/X.5`. An unqualified `X.n` in this document is an id that occurs exactly once.

**Sizing is scored, not asserted.** Each row carries `S`=1, `M`=2, `L`=4, `XL`=8
size-points; the **Points** column in the index is their sum. A wave may carry **at most
eight points per declared agent-day**, and **a wave containing an `XL` row is never 1d** —
an XL row is a week's worth of consequence compressed into one line and it drags whatever
sits beside it. That rule is what split `P05`/`P41`, `P12`/`P42`, `P22`/`P47`,
`P23`/`P45`, `P31`/`P46` and `P32`/`P43`, and what moved `X.38` out of `W05` and `10.2d`
out of `P32`. `2d`–`4d` waves are groups that share a file set and cannot be split without
two agents fighting over the same file; run them as one agent over several days.

**Parallelism is bought with file discipline.** Two waves may run at the same time only if
they do not write the same file. Where the IA's sections collide with the repo's folders
the plan splits by *file*, not by folder, and says so: `features/orders/` carries several
waves that each own a different file set (`order-queue*`, `order-detail-pane*`,
`new-order/*`, `reservations-page*`, `call-centre-page*`), and the settings section is cut
along its sub-folders so `locations`, `sales-channels`, `fiscalization`, `integrations`,
`notifications` and `reference-data` never meet. Where that was impossible the waves are
**sequenced** and the `After` column says which wave must merge first. Every entry in that
column is a collision, not a preference.

**Nine shared surfaces are sequencing hazards.** Anything touching them must go through the
wave that owns them:

1. `frontend/operations/src/app/shared/ui/` **does not exist yet.** `P01` creates it. Every
   later component wave adds its own files and never edits another wave's — which is why
   `P08` (`X.34` indicators), `P17` (`q-qr-code`), `P18` (board and drag-assign), `P22`
   (`q-media-uploader`), `P24` (`q-mapping-pane`) and `P26` (`q-import-wizard`) are all
   sequenced behind `P01` and none of them is «after nothing».
2. `OperationsOrderController` and its `OrderSummaryResponse` / `OrderDetailResponse`
   records are the hottest file in the backend. `P04` owns the list shape, `P05` owns
   `actions[]`, `P09` owns the detail shape, and **nothing else edits those records**.
   `P11` and `P42` therefore add *sibling order-keyed endpoints* —
   `GET .../orders/{orderId}/delivery` and `GET .../orders/{orderId}/pos-export` — rather
   than fields on the detail record, which is the only way both can be true at once.
3. The order-board file family — `order-queue.ts/.html/.css`, `order-severity.ts`,
   `order-tabs.ts`, `order-counts.ts`, `order-money.ts` — is **strictly serialised**:
   `P01` (status pill) → `P05` (action menu) → `P06` (lateness constants) → `P07`
   (toolbar, selection, class doc) → `P08` (live counters, ADR 0045 comment). None of
   these five may run beside another. `order-severity.ts` is imported by six production
   files, so `P06`'s deletion of its constants reaches `order-detail-pane.ts`,
   `order-tabs.ts`, `order-counts.ts`, `order-money.ts` and `kitchen-ticket.ts`.
4. `kitchen-ticket.ts` and `KitchenBoardController.TicketResponse`: `P06` owns the
   lateness constants, `P16` owns the board query and its tab counts, `P11` owns the ETA
   field on `TicketResponse`, `P17` owns the device client. The order is `P06` → `P16` →
   `P11` → `P17` and the index enforces it.
5. `MetricRegistry` is append-only by ADR 0043 discipline. `P27` owns the first additions;
   `T06`, `T12`, `T13`, `T14` and `P39` add theirs on top of it. Add, never edit.
6. `frontend/design-tokens/tokens.css` is **generated, not authored** — a token is added
   upstream or not at all. `P06` adds the SLA ramp, `T09` the dataviz palette, `T23` the
   TV type step, `T22` the type-scale corrections. Treat it like `MetricRegistry`: append a
   contiguous block, never reformat, no serialisation needed.
7. `frontend/operations/package.json`: `@angular/cdk` is **absent** and two waves want it.
   `P03` adds the dependency and the lockfile entry; `P18` consumes it and is sequenced
   behind `P03` for that reason alone.
8. `frontend/operations/src/app/core/api/*-paths.ts` are shared barrels. Add entries, never
   reformat the file, and keep each wave's additions in its own contiguous block.
9. `platform/src/main/resources/db/migration` is **strictly ordinal** and `V0222` is the
   current head, so two parallel waves would both write `V0223`. Numbers are allocated
   below; a wave uses its own block or none.

**Migration numbers are allocated, not chosen.** Head is `V0222`: `V0211`–`V0222` were
taken while this map was being written — the wallet (ADR 0095: `V0211`, `V0212`, `V0214`,
`V0222`), the password reset (ADR 0098: `V0213`), the invitation history (ADR 0100:
`V0215`) and P19 (`V0220`, `V0221`) — so P11, P14 and P16 moved to the end of the table
and `V0216`–`V0219` stay unused rather than reallocated. Each wave that adds schema owns
the block below and may use fewer numbers than it reserves, never more; a wave not listed
here adds no migration and must raise it rather than take a number.

| Wave | Reserved | What it creates |
|---|---|---|
| **P19** | `V0220`–`V0222` (used: `V0220`–`V0221`; `V0222` went to the wallet's own ADR 0095 allocation) | courier compliance columns under the ADR 0029 envelope; `courier_groups`; `courier_branch_bindings` |
| **P22** | `V0223`–`V0225` | a channel dimension on `catalog.media_relations`; the widened media allowlist |
| **P47** | `V0226`–`V0228` | the per-item sale-schedule binding V0020 withdrew; `catalog.product_recommendations` |
| **P24** | `V0229`–`V0231` | indexes for the mapping reads; the `OPERATOR` mapping source has no schema change |
| **P26** | `V0232`–`V0234` | the import-job entity and its per-row report |
| **P28** | `V0235`–`V0237` | `reporting.report_exports` |
| **P39** | `V0238`–`V0240` | `reporting.fact_order_tender` |
| **P32** | `V0241`–`V0243` | brand logo, banner, localized description, contact phone, Telegram handle; the per-brand supported-locale set; the brand media relation |
| **P33** | `V0244`–`V0246` | payment-method localized names, `provider_installation_id`, `contract_reference` |
| **P34** | `V0247`–`V0249` (used: `V0247` only; `V0248`–`V0249` were not needed — the legal-entity fields already existed since `V0053`) | `fiscal.fiscal_terminals`; legal-entity short name, VAT certificate, tax profile, registered address, contact phone |
| **P35** | `V0250`–`V0252` (used: all three) | partner API client last-used; provider-installation last-used watermarks |
| **P36** | `V0253`–`V0255` | the per-version variables schema; the template unique index widened past `(tenant, brand, key, channel)` |
| **P37** | `V0256`–`V0258` (used: all three) | branch tags and their location assignment; the tenant business calendar and its boundary |
| **T16** | `V0259`–`V0261` (used: `V0259`–`V0260`; `V0261` not needed) | courier-type starting minute and work mode; the adjustment-reason amount and condition columns |
| **T17** | `V0262`–`V0264` (used: `V0262` only) | `courier_roster_entries` |
| **T05** | `V0265`–`V0267` | `pricing.benefit_grants` |
| **T18** | `V0268`–`V0270` | `marketing.attribution_links`; a foreign key on `accrual_rules.scope_id` |
| **T11** | `V0271`–`V0273` | `reporting.fact_delivery`; the `COURIER` scope of `agg_sla_bucket_day`; an index on `delivery_fee_resolutions (tenant_id, created_at, tariff_id)` |
| **T12** | `V0274`–`V0276` | `operator_principal_id` on `reporting.fact_order` |
| **T14** | `V0277`–`V0279` | `reporting.classification_run` and `classification_result` |
| **T15** | `V0280`–`V0282` | `notification_deliveries.read_at` and the `READ` status |
| **T08** | `V0283`–`V0285` | a correlation column on the audit fact |
| **W02** | `V0286`–`V0288` | `reporting.forecast_run` and `fact_forecast`; a holiday flag per sample date |
| **W03** | `V0289`–`V0291` (used: `V0289` only; `V0290`–`V0291` not needed — retention periods reuse `tenant.configuration_values` via ADR 0030 keys) | the consent-type registry; tenant retention periods |
| **P11** | `V0292`–`V0294` | courier ETA persisted into the domain and joined onto the kitchen ticket |
| **P14** | `V0295`–`V0297` | `customer_accounts.origin` and `created_by_actor_id` |
| **P16** | `V0298`–`V0300` | a structured stop source on the availability read model |

**Sizing.** `1d` means one agent-day at eight size-points. **Every wave ends the same way.**
`mvn spotless:apply` before the gate, never after — a formatting-only gate failure costs a
full slot. Java tests run against the migrated schema, not a mock. Angular specs are
mandatory for every component the wave touches, and a wave that touches a screen with no
`.spec.ts` adds one. Do not commit a running wave's tree; gate first, merge second.

**Stale comments are part of the work.** Several waves below are told to delete a specific
code comment. Those comments have already produced wrong gap reports twice; leaving them
in place is how this document goes out of date.

## Wave index

Seventy-five waves. **Points** is the size-point sum defined above; **After** is the merge
order the file-collision analysis forces.

| Wave | Title | Tier | Size | Points | Rows | After | Merged |
|---|---|---|---|---|---|---|---|
| **P01** | Console primitives: overlays, feedback, status | P | 1d | 7 | `X.8`, `X.30`, `X.17`, `X.16`, `X.15` | — | 2026-09-12 |
| **P02** | Console primitives: value, time and text inputs | P | 2d | 11 | `X.10`, `X.11`, `X.29`, `X.32`, `X.9`, `X/X.5` | P01 | 2026-09-13 |
| **P03** | Console primitives: DataGrid, DataTable extensions, MatrixGrid | P | 2d | 10 | `X.6`, `X.18`, `X.7` | P01, P04 | 2026-09-13 |
| **P04** | Order list read model and query surface | P | 1d | 6 | `1.1`, `1.1d` | — | 2026-09-12 |
| **P05** | Server-driven actions: actions[] as a capability array | P | 1d | 2 | `1.2a`, `1.1e` | P01, P04 | 2026-09-13 |
| **P41** | Backward status transitions and the override policy | P | 2d | 8 | `1.1h` | P05 | — |
| **P06** | Lateness policy and the SLA colour ramp | P | 2d | 10 | `1.1g`, `X.39` | P05 | — |
| **P07** | Order board: filters, search, selection, bulk | P | 1d | 6 | `1.1c`, `1.1f` | P03, P06 | — |
| **P08** | Realtime push and the shell's own counters | P | 2d | 8 | `1.1b`, `0.1f`, `X/X.1`, `X.34` | P07 | — |
| **P09** | Order detail: the record and its outcomes | P | 2d | 10 | `1.2`, `1.2o`, `1.2p`, `1.2m`, `1.2j`, `1.2k` | P05 | — |
| **P10** | Operator notes and the first amendment client | P | 1d | 4 | `1.2h` | P09 | — |
| **P11** | The order-to-fulfilment seam | P | 2d | 14 | `1.2e`, `1.2n`, `2.1a`, `1.2b` | P09, P16 | — |
| **P44** | Provider cancel cascade and quote-delta confirmation | P | 1d | 6 | `1.2g`, `1.2f` | P11 | — |
| **P12** | Money on the order: payment and fiscal panels | P | 1d | 2 | `1.2l` | P11 | — |
| **P42** | Machines on the order: POS export and its errors | P | 2d | 8 | `1.2i` | P12 | — |
| **P13** | New order: the screen, the basket, the caller | P | 2d | 14 | `1.3`, `1.3c`, `1.3a` | P02 | — |
| **P14** | New order: address, money, promo, repeat, aggregator | P | 2d | 16 | `1.3b`, `1.3e`, `1.3f`, `1.3g` | P13 | — |
| **P15** | Live board: period scoping, mixes, branch load | P | 2d | 10 | `0.1`, `0.1a`, `0.1b`, `0.1c` | — | 2026-09-12 |
| **P16** | Kitchen queue and stop list | P | 2d | 14 | `2.1`, `2.1c`, `2.1e`, `2.5`, `2.5b`, `2.5c` | P03, P06 | — |
| **P17** | KDS device shell, enrolment and QR | P | 2d | 8 | `X/X.2`, `2/X.2`, `X.35` | P01, P16 | — |
| **P18** | Dispatch board, board columns and drag-assign | P | 1d | 8 | `3.1`, `X.21`, `X.22` | P01, P03 | — |
| **P19** | Courier roster: the compliance file | P | 1d | 4 | `3.3` | — | 2026-09-12 |
| **P20** | Zones, regions, free geozones and delivery tariffs | P | 2d | 11 | `3.6`, `3.6d`, `3.6b`, `3.7` | — | 2026-09-12 |
| **P21** | Product library and the fiscal workbench | P | 1d | 8 | `4.1`, `4.1a` | P03 | — |
| **P22** | Product editor: variants, media and fiscal data | P | 2d | 12 | `4.2`, `4.2f`, `4.2d`, `4.2e`, `X.12` | P01, P02 | — |
| **P47** | Per-item schedule, kitchen department and cross-sell | P | 1d | 6 | `4.2g`, `4.2h` | P02, P22 | — |
| **P23** | Categories and the per-location menu matrix | P | 2d | 10 | `4.3`, `X.23`, `4.4` | P03 | — |
| **P45** | Menus: per-channel offering and price | P | 2d | 8 | `4.4b` | P23 | — |
| **P24** | POS catalog sync and the mapping pane | P | 2d | 16 | `4.5a`, `10.8b`, `X.24` | P03 | — |
| **P25** | Customer record depth: contact, consent, address | P | 2d | 6 | `5.2a`, `5.2b`, `5.2c` | — | 2026-09-12 |
| **P40** | Customer record: ledger, promos, reviews, blacklist, erasure | P | 1d | 8 | `5.2d`, `5.2e`, `5.2g`, `5.2h`, `5.2i`, `5/X.1` | P25, P14 | — |
| **P26** | ImportWizard, customer CSV and the header counters | P | 2d | 10 | `X.13`, `5.1b`, `5.1a` | P02 | — |
| **P27** | Reports: the filter bar, the overview and the order log | P | 2d | 16 | `7.1d`, `7.1`, `7.1a`, `7.2`, `7.2a`, `7.2b`, `7.2c` | P02, P07 | — |
| **P28** | The export centre | P | 2d | 8 | `7.2e` | P27 | — |
| **P29** | Finance: payments and fiscal, per order | P | 1d | 4 | `8.1`, `8.2` | — | 2026-09-12 |
| **P39** | Payment mix and the tender fact | P | 2d | 4 | `7.1c` | P33 | — |
| **P30** | Staff: gated navigation, locked versus denied | P | 1d | 5 | `9.1`, `9.1c`, `9.1d` | — | 2026-09-12 |
| **P31** | Settings: the scope bar, readiness and support visits | P | 2d | 11 | `10/X.1`, `10.0`, `10/X.3` | — | 2026-09-12 |
| **P46** | Settings: order policy cards and catalog base settings | P | 2d | 10 | `10.3b`, `4.4d` | P31 | 2026-09-13 |
| **P32** | Settings: brand, languages and the branch list | P | 2d | 12 | `10.1`, `10.12`, `10.2a`, `10.2b` | P02, P31 | — |
| **P43** | Settings: hours, prep bands and the order limit | P | 1d | 4 | `10.2c` | P32 | — |
| **P33** | Settings: channels, the capability matrix and payment methods | P | 2d | 10 | `10.4a`, `10.4b`, `10.6` | P03, P31 | — |
| **P34** | Settings: fiscalization | P | 2d | 9 | `10.7a`, `10.7b`, `10.7c` | P31 | 2026-09-13 |
| **P35** | Settings: integrations, analytics and partner credentials | P | 2d | 13 | `10.8a`, `10.8c`, `10.8d`, `10.8e`, `X.14` | P31 | 2026-09-13 |
| **P36** | Settings: notifications | P | 2d | 13 | `10.9a`, `10.9b`, `10.9c`, `10.9d`, `X.27` | P02, P31 | — |
| **P37** | Settings: reference data | P | 2d | 9 | `10.10a`, `10.10b`, `10.10c`, `10.10d` | P31 | 2026-09-13 |
| **P38** | Settings: delivery policy, dine-in QR and the floor plan | P | 2d | 14 | `10.13`, `10.5b`, `10.2d`, `X.36` | P17, P31 | — |
| **T01** | My work | 2 | 2d | 14 | `0.2`, `0.2a`, `0.2b` | P08 | — |
| **T23** | The wallboard shell | 2 | 1d | 6 | `0.1e`, `X/X.3` | P15 | 2026-09-13 |
| **T02** | Kitchen buffer, expo gate, VDU and capacity | 2 | 2d | 7 | `2.2`, `2.3`, `2.4`, `2.6` | P16 | — |
| **T03** | Live map and courier policy | 2 | 1d | 8 | `3.2`, `3.9` | P19 | 2026-09-13 |
| **T16** | Courier types, rate cards and adjustment rules | 2 | 2d | 9 | `3.4a`, `3.4b`, `3.4c` | P19 | 2026-09-13 |
| **T17** | Shifts, attendance and bulk geozone upload | 2 | 1d | 8 | `3.5`, `3.6c` | P19 | 2026-09-13 |
| **T04** | Publication readiness, price books and bulk pricing | 2 | 2d | 8 | `4.6`, `4.8a`, `4.8b` | P03 | — |
| **T05** | Segments and promo codes | 2 | 2d | 8 | `5.3`, `6.2`, `6.2a` | P02 | — |
| **T18** | Campaigns, loyalty and acquisition links | 2 | 2d | 10 | `6.3`, `6.4`, `6.4b`, `6.6a` | T05 | — |
| **T06** | Branch and SLA reports | 2 | 1d | 7 | `7.3`, `7.3a`, `7.3b` | P27 | — |
| **T11** | Courier reports and the delivery fact | 2 | 2d | 12 | `7.4`, `7.4a`, `7.4b`, `7.4c` | T06 | — |
| **T12** | Staff and telephony reports | 2 | 2d | 10 | `7.5`, `7.5a`, `7.5b` | T08 | — |
| **T13** | Customer analytics | 2 | 2d | 10 | `7.6`, `7.6a`, `7.6b` | P27 | — |
| **T14** | Product analytics: ABC and XYZ | 2 | 2d | 9 | `7.7`, `7.7a`, `7.7b` | P27 | — |
| **T15** | Marketing reports | 2 | 1d | 8 | `7.9a`, `7.9b` | T05 | — |
| **T07** | Finance: courier money | 2 | 2d | 12 | `8.3`, `8.4`, `8/X.5`, `8.5` | P29, T11 | — |
| **T19** | Finance: subscription and invoices | 2 | 1d | 6 | `8.6`, `8/X.4` | P29 | 2026-09-13 |
| **T08** | Staff: the audit log | 2 | 2d | 11 | `9.3`, `9.3a`, `9.3b`, `9.3c`, `9.2d` | P30 | 2026-09-13 |
| **T20** | Staff: contacts, POS ids, Telegram links, self-service | 2 | 2d | 9 | `9.2b`, `9.2c`, `9/X.1`, `9/X.5` | T08 | — |
| **T09** | Chart family and KPI tiles | 2 | 2d | 6 | `X.19`, `X.20` | P01 | 2026-09-13 |
| **T21** | Rule authoring components | 2 | 2d | 4 | `X.25` | P01 | 2026-09-13 |
| **T22** | Timeline, frames, rich text, steps, OTP, type stack | 2 | 2d | 11 | `X.26`, `X.28`, `X.31`, `X.33`, `X.38`, `X.40` | P01 | 2026-09-13 |
| **T10** | Drafts and abandoned carts | 2 | 1d | 1 | `1.4` | P03 | — |
| **W01** | Reservations, dine-in sessions and telephony | 3 | 2d | 11 | `1.5`, `1.5a`, `1.6`, `X.37` | P02, P32 | — |
| **W02** | Demand forecast | 3 | 2d | 16 | `7.8`, `7.8a`, `7.8b` | P27 | — |
| **W04** | Geography: histograms and the week grid | 3 | 1d | 6 | `7.10b`, `7.10c` | W02 | — |
| **W03** | Privacy self-service, access check and approvals | 3 | 2d | 8 | `10.11`, `9/X.4`, `9.4` | P30 | 2026-09-13 |
| **W05** | Split tender: the payment[] array | 3 | 2d | 8 | `8/X.1` | P29 | 2026-09-13 |
## The waves

### P01 · Console primitives: overlays, feedback, status
**Rows** `X.8` ActionMenu/Drawer/Modal/ConfirmDialog (M) · `X.30` SplitPane (S) · `X.17` Toast + InlineAlert (M) · `X.16` LockedState/DeniedState/EmptyState (S) · `X.15` StatusPill with a lateness overlay (S) — **1d**, after nothing.

Create `frontend/operations/src/app/shared/ui/` — it does not exist; the app has only `core/`, `features/` and `shell/`. This wave is the unblocker for eleven later waves, so its job is the components, not the migration of all their call sites.

Build, each with its own `.spec.ts`: `q-modal` / `q-drawer` / `q-confirm-dialog` with Escape-to-close, a focus trap and focus restore (the whole app has exactly one keydown handler today, `shell.ts:93`); `q-action-menu` with `role="menu"`/`menuitem`, `aria-haspopup`, arrow-key roving tabindex and outside-click close; `q-split-pane` with a drag handle and a width remembered in `localStorage` per section key; `q-toast` plus a toast host mounted once in `shell.html`; `q-inline-alert` with severity, optional dismissal and the correct `role="status"`/`role="alert"` choice; `q-empty-state`, `q-denied-state` (with the capability name and where to ask for it) and `q-locked-state` (plan lock, with a slot for a buy CTA); `q-status-pill` taking a status *and* an independent lateness overlay, plus the dual-state order+cooking variant.

Done means: two call sites migrated per component and no more — `order-reason-dialog` and `create-customer-dialog` for the dialog set, `orders-page` and `inbox-page` for SplitPane, the order queue's `.status-badge` for StatusPill. Fifteen files carry `aria-modal="true"` and ~65 templates carry a hand-written `denied` branch; migrating them is each owning wave's job, not this one's.

**Backend** none. **ADR** update ADR 0035's status line — it currently says «No shared Angular component library exists» and lists these components as «Required throughout» with an open task to author them.

**Capabilities** none — no endpoint is added. `q-denied-state` takes a capability *name* as a string input; it never resolves one.

**Tests** a `.spec.ts` per component and no shared fixture: Escape closes and focus returns to the invoker on all three dialog types; the action menu's roving tabindex wraps and outside-click closes; SplitPane restores its width from `localStorage` under a section key and survives a corrupt value; InlineAlert picks `role="alert"` for error and `role="status"` otherwise; StatusPill renders a lateness overlay **on a non-late status** without changing the status word. Plus one migration test per component proving the two migrated call sites still render.

**Traps** `tokens.css` is generated, not authored: do not add tokens locally, and do not introduce raw px. Do not rename existing per-feature CSS classes in this wave — a later wave will. `q-empty-state` does not exist either, despite the IA's framing of `X.16` as «distinct from EmptyState».

### P02 · Console primitives: value, time and text inputs
**Rows** `X.10` Money/Percent/MoneyOrPercent (S) · `X.11` DateRangePicker/TimeInput/DayOfWeekToggle/ScheduleGrid (L) · `X.29` NumberStepper (S) · `X.32` ColorInput (S) · `X.9` Combobox (M) · `X/X.5` LocalizedFieldGroup (M) — **2d**, after `P01`.

Six input primitives into `shared/ui/`, each with a spec. `q-money-input`: NBSP thousands grouping while typing, som suffix, paste tolerance for `125 000`, rejection of a decimal point (UZS has no minor unit), emitting an integer `…Minor`. `q-percent-input` owns the percent→basis-points conversion currently inlined at `promo-codes-page.ts:195`. `q-money-or-percent` renders the choice `TariffDiscount.Kind` already enforces. `q-number-stepper` with min/max display. `q-color-input`. `q-date-range-picker` with presets *and* a custom range plus arrow-key stepping; `q-time-input`; `q-day-of-week-toggle`; `q-schedule-grid` binding to the `{dayOfWeek, opensAt, closesAt}` + dated-exception shape `LocationServiceOperationsController`'s `ServiceSummaryResponse` already returns. `q-combobox`: async search, debounce, create-on-miss, multi-select chips, full listbox ARIA. `q-localized-field-group`: locale tabs, a default-language marker and a completeness indicator, over `{ru, uz-Latn, en}`.

Done means the components exist and exactly one call site each is migrated: the tariff form for money, `capacity-page` for the weekday/time set, `product-editor-page`'s locale strip for LocalizedFieldGroup, `customers-page` search for Combobox.

**Backend** none. **Traps** 249 raw `font-size` declarations already violate the closed type scale and there is no ESLint config in `frontend/operations` — add one in this wave with a rule banning raw `px` font sizes, or the drift wins. The catalog wire default locale is `uz` while the console default is `ru`; the default marker must read the entity's own default, not the UI locale. `delivery-zones` writes one name into all three locale fields — do not codify that.

**Capabilities** none.

**Tests** specs for: NBSP grouping while typing and a paste of `125 000`; rejection of a decimal separator and emission of an integer `…Minor`; a percent→basis-points round trip against `promo-codes-page`'s current inline arithmetic; the schedule grid binding to a dated exception as well as a weekly rule; combobox create-on-miss and multi-select chip removal by keyboard; and a LocalizedFieldGroup whose default marker follows the **entity's** default (`uz`) while the UI locale is `ru`. The new ESLint rule gets a fixture that fails on a raw `px` font size.

### P03 · Console primitives: DataGrid, DataTable extensions, MatrixGrid
**Rows** `X.6` DataGrid (L) · `X.18` DataTable extensions (L) · `X.7` MatrixGrid (M) — **2d**, after `P01` and `P04`.

Three table primitives. `q-data-grid`: inline cell edit, fill-down, cell-to-cell keyboard navigation, virtualization (**this wave adds `@angular/cdk` and owns the `package.json` and lockfile change** — it is absent today, so `cdk-virtual-scroll` is not available; `P18` consumes the same dependency and is sequenced behind this wave for that reason alone), and a batched save that reports per-row outcomes. `q-data-table` extensions on top of the hand-written `<table class="table">` pattern: persisted per-tab filters in `localStorage`, saved views, a column chooser, cursor paging *and* infinite scroll, a selection model with a bulk-action bar, and a row action menu using `P01`'s ActionMenu. `q-matrix-grid`: an editable cross-tab with row and column bulk toggles, shift range-select, and a hatched non-clickable «unavailable» cell state.

Done means the three components plus one migrated call site each: `stop-list-page` (the only screen with selection today) for the bulk bar, `products-page` for cursor paging, `sales-channels-page` for MatrixGrid. The bulk-write contract every grid should follow already exists and is proven: `POST .../orders/bulk-actions` (`OperationsOrderController.java:736`) with an `Idempotency-Key`, a `bulkOperationId`, a 200-item cap, per-item statuses and a 202. Model the grid's save on it.

**Backend** none of its own — it consumes `P04`'s cursor paging.

**Capabilities** none of its own; the grid's batched save inherits whatever capability its host screen's endpoint requires.

**Tests** specs for inline edit + fill-down + cell keyboard navigation; a batched save that reports a per-row failure without losing the other rows' successes; persisted per-tab filters surviving a reload; the column chooser; cursor paging **and** infinite scroll over the same source; MatrixGrid shift range-select and the hatched unavailable cell refusing a click. Add a regression spec for `menus-page` iterating a `Page<T>` envelope — the bug this wave fixes has no test today.

**Traps** tab counts computed over `items()` are wrong until the operator has paged to the end (`stop-list-page.ts:136-150`); the component must count server-side or refuse to show a count. `menus-page` fetches a `Page<T>` envelope with the non-paged `api.get` and iterates it as an array — a latent runtime break every spec misses because the API is stubbed wholesale; fix it here.

### P04 · Order list read model and query surface
**Rows** `1.1` Order board columns (L) · `1.1d` search across per-provider external IDs (M) — **1d**, after nothing. Backend only.

`OperationsOrderController.list` (`:121`) accepts `status` and `limit` and nothing else, and `OrderSummaryResponse` (`:1340-1366`) carries eleven fields. Widen both.

Add to the response, from data `JdbcOrderStore.OrderRow` already hydrates: `promisedAt` (and the promise basis — `SELECT_ORDER` at `:1323` already selects it and `OrderSummaryResponse.of` drops it), `paymentStatusProjection`, `customerAccountId`, `guestReferenceHash`, `feeMinor`, `discountMinor`, `createdByActorType/Id`, `acceptedByActorType/Id`, and a boolean derived from `ordering.order_process_states` for `MANUAL_ACTION_REQUIRED`/`FAILED_RETRYABLE`. Add to the query: `from`/`to`, cursor paging through the existing `web/api/Page`+`Cursor` primitives, `channelCode`, `fulfillmentMode`, `courierId`, `paymentMethodCode`, `createdByActorId`, and a `reference` search that normalises and joins `ordering.order_external_references` (V0038) — written today by `JdbcMarketplaceOrderIntake:221` and read only by the control-plane global lookup. Public order number and customer phone hash must match the same parameter.

Done means: the board can be narrowed and paged server-side, `77-A-3391` finds its order, and `GET .../orders/counts` grows the same `from`/`to` so the counters stop being lifetime totals (see `P15`, which needs the same parameter).

**ADR** none new; record the widening in orders.md §11.

**Capabilities** `ORDER_READ` at `LOCATION` scope throughout; the reference search must not widen scope.

**Tests** Java integration tests against the migrated schema for each new filter, for cursor stability under concurrent inserts, for the reference search finding an aggregator id and refusing a cross-tenant one, and for `orders/counts` honouring the same `from`/`to`. Assert that customer name and phone are absent from `OrderSummaryResponse` — that is the invariant a later wave will be tempted to break.

**Traps** customer name and phone stay off this response — they are ADR 0029 PERSONAL and belong behind the audited reveal.

### P05 · Server-driven actions: actions[] as a capability array
**Rows** `1.2a` actions[] as a capability array (S) · `1.1e` row action menu (S) — **1d**, after `P01` and `P04`.

The gap map graded `1.2a` PARTIAL and then wrote «nothing is missing» in its own cell; the cell was wrong and the grade was right. `actions[]` is a status+mode array pretending to be a capability array. `OrderActionsPolicy.availableFor(status, mode)` takes no principal and consults no grant, so `LOCATION_STAFF` — which holds `ORDER_READ`/`APPROVE`/`ADVANCE` but not `ORDER_CANCEL` — is offered «Отменить» on every open order and 403s at `OperationsOrderController:472`. `SUPPORT_AGENT`, `TENANT_FINANCE`, `BRAND_MANAGER` and `COURIER_DISPATCHER` get the same false offer. Make the policy principal-aware: `availableFor(status, mode, grantedCapabilities)` keyed off the same `Capability` constants the mutating endpoints require, applied on both the list and the detail read. Add a test asserting a `LOCATION_STAFF` read yields no `CANCEL`.

Then widen `OrderActionCode` past its closed four. `OperationsOrderController` already serves completion (`:548`), amendments (`:594`), amendment confirmation (`:656`) and bulk actions (`:736`) with no action code to reach them; add `COMPLETE`, `AMEND`, `ASSIGN_COURIER`, `ISSUE_INVOICE`, `RESOLVE` and mark terminal orders as carrying a read-only menu rather than no menu (`order-queue.html:151` hides the trigger when the array is empty).

Backward transitions (`1.1h`) were split out into `P41`: they are an XL row, they need an ADR 0019 amendment, and they would have taken this wave to ten size-points in a day. `P41` runs straight after this one and inherits the principal-aware policy built here.

**ADR** none. Record the widened `OrderActionCode` set in `orders.md` §11.

**Capabilities** the policy is keyed off the same `Capability` constants the mutating endpoints already require — `ORDER_APPROVE`, `ORDER_ADVANCE`, `ORDER_CANCEL`, `ORDER_BULK_ACTION` — read from the principal's granted set. No new capability.

**Tests** extend `OrderActionsPolicyTests` per role: a `LOCATION_STAFF` read yields no `CANCEL`; `SUPPORT_AGENT`, `TENANT_FINANCE`, `BRAND_MANAGER` and `COURIER_DISPATCHER` each get exactly the set their grants justify. A Java test that every widened `OrderActionCode` resolves to an endpoint that accepts it, so the array cannot offer an action with no route. An Angular spec that a terminal order renders a read-only menu rather than none.

### P41 · Backward status transitions and the override policy
**Rows** `1.1h` backward status transitions (XL) — **2d**, after `P05`.

The split-out ADR half of `P05`, and an XL row gets its own wave: an order advanced to READY
by mistake has no exit, because cancel is also refused past CONFIRMED (`1.2k`) and
`OrderStateMachine` declares no edge that goes back.

Decide in **ADR 0019** and `orders.md` §11 whether compensating transitions exist at all —
the answer this plan assumes is yes, narrowly — and then declare the edges in
`OrderStateMachine` as **compensating** rather than as literal reversals. A literal reversal
is what produces two nested state machines and an order whose history cannot be read; a
compensating edge is a new forward step that happens to restore an earlier status, and it
carries its own reason and its own timeline entry.

Add a state-actions branch gated on `Capability.ORDER_STATE_OVERRIDE` — already registered,
granted to nobody by default — with a **mandatory registry reason** drawn from
`ordering.order_outcome_reasons` rather than free text, and a timeline row naming the actor.
`P05` has already made `actions[]` principal-aware, so the override action appears only for a
principal that holds the capability; do not re-open that policy here.

**ADR** amend ADR 0019 (the decision, the edge list and the reason requirement) and tick the
orders.md §11 line.

**Capabilities** `Capability.ORDER_STATE_OVERRIDE`, already registered and granted to nobody by default; the reason is mandatory and the registry entry is the audit key.

**Tests** a Java test that an override transition writes **both** the audit fact and the timeline row and refuses without a registry reason; a state-machine test that each compensating edge is declared compensating and not as a literal reversal; and a test that a principal without `ORDER_STATE_OVERRIDE` is refused at the endpoint, not merely hidden in `actions[]`.

**Traps** do not model this as an `UNDO`. Every compensating edge is a named transition with
its own audit fact; a generic undo is what makes the fiscal and POS consequences of `1.2c`
unanswerable later. Terminal orders stay terminal — a cancelled order is not reopened by this
wave, and saying so in the ADR is part of the work.

### P06 · Lateness policy and the SLA colour ramp
**Rows** `1.1g` late-order highlight (XL) · `X.39` semantic status colour ramp (M) — **2d**, after `P05`.

Lateness is invented client-side, twice, with different numbers. `order-severity.ts` hard-codes `APPROVAL_DEADLINE_THRESHOLD_MS = 2min` and `NO_PROMISE_FALLBACK_MS = 45min`; `kitchen-ticket.ts:83` hard-codes `AT_RISK_THRESHOLD_MS = 5min`. The order board's amber tier is unreachable dead code because nothing gives it a promise, while the kitchen board paints amber freely. `ordering.lateness` does not exist server-side at all — it is spec prose in orders.md:389-392 and a React prototype constant.

Register an `ordering.lateness` policy document in `OrderingConfigurationKeys` (which registers exactly one key today) with `at_risk_before_seconds`, `late_after_seconds` and `no_promise_fallback_seconds` per fulfilment mode, resolved through ADR 0030's existing resolver, and serve it to the console. `OrderPromise.lateAt(Instant, OrderStatus)` (`domain/OrderPromise.java:177`) already implements the LATE predicate including «terminal orders are never late» and has no production caller — use it rather than writing a second one.

This wave **owns the lateness constants wherever they live** — `order-severity.ts` and `kitchen-ticket.ts:83` — which is why `P16`, `P11` and `P17` are all sequenced behind it, and why it sits inside the serialised order-board family (`P01` → `P05` → **`P06`** → `P07` → `P08`). `order-severity.ts` is imported by six production files; deleting its constants reaches `order-detail-pane.ts`, `order-tabs.ts`, `order-counts.ts`, `order-money.ts` and `kitchen-ticket.ts`, and no other wave may be running in that family while it does.

Frontend: delete both constant pairs, read the resolved policy, implement AT_RISK and LATE from `promisedAt` (arriving in `P04`) and BLOCKED from the process-state flag, and add a named SLA ramp to the design tokens instead of borrowing `--q-warning`/`--q-error`. The tenant-chosen late colour stays refused, per orders.md §2.7.

**Capabilities** `ORDER_READ` to read the resolved policy; authoring `ordering.lateness` is `TENANT_CONFIGURATION_WRITE` and lands with `P31`, so this wave ships the key and its defaults, not its editor.

**Tests** a Java test that the resolved `ordering.lateness` document falls back per fulfilment mode and resolves through ADR 0030's precedence; a test pinning `OrderPromise.lateAt` to its «terminal orders are never late» rule now that it has a production caller. Angular specs that the board and the kitchen ticket compute the same AT_RISK boundary from the same policy, which is the whole point of the row, and that BLOCKED comes from the process-state flag and not from a clock.

**Traps** `tokens.css` is generated — the ramp has to be added upstream in `frontend/design-tokens`, not patched in the app. Do not confuse this with `sla_bucket_set.v1`: those are ADR 0043 reporting histogram buckets, deliberately platform-fixed, and they do not serve this row.

### P07 · Order board: filters, search, selection and bulk
**Rows** `1.1c` filters persisted per tab (L) · `1.1f` bulk selection and courier assignment (M) — **1d**, after `P03` and `P06`. Frontend only.

Build the toolbar `order-queue.html` has never had: period, branch, aggregator, source, delivery type, courier, payment type and «мои заказы», plus the external-ID search box, all bound to `P04`'s query parameters and persisted per tab through `P03`'s DataTable extensions. The legacy dashboard persisted per-status filters in `localStorage` and merchants will notice the regression. §2.4 also requires the queue and the order reports to share one filter component. **This wave owns that extraction and the decision is made, not offered**: lift `features/reports/reports-filter-bar.*` into `shared/ui/` as `q-filter-bar` with a typed control set, leave a thin `reports-filter-bar` wrapper in place so the reports screens keep compiling, and build the order toolbar on the extracted component. `P27` is sequenced after this wave and re-points Reports at `q-filter-bar`, deleting the wrapper; do not let both waves extract it.

Then selection: a checkbox column, a bulk-action bar, and `POST .../orders/bulk-actions` — built, idempotent, capability-gated on `ORDER_BULK_ACTION`, 200 orders per call, per-item outcomes, 202 — which has no path constant in `operations-paths.ts` and no caller. Render §2.10's result panel and «Повторить проблемные» from the per-item outcome list the endpoint already returns.

Done means: an operator can narrow the board to a shift and a channel, the filter survives navigation and reload, and a peak can be advanced or cancelled in one gesture. **Bulk courier assignment is explicitly out of scope** — `BulkActionType` is `ADVANCE|CANCEL` only and ADR 0039 defers the courier case; say so in the UI rather than offering it.

**Capabilities** `ORDER_READ` for the board; `ORDER_BULK_ACTION` for the bulk bar, which the endpoint already enforces — render the bar only when the session context carries it.

**Tests** Angular specs for each filter mapping to its `P04` query parameter, for the filter surviving navigation and reload per tab, for the selection model across a page boundary, and for the bulk result panel rendering per-item outcomes including a partial failure. A spec asserting the UI does **not** offer bulk courier assignment.

**Traps** `order-queue.ts`'s class doc (~:96-104) claims there are no row actions, no bulk actions, no filters and no column picker; it is already wrong about row actions. Rewrite it, do not extend it. `attention` is counted client-side over a 200-row page and undercounts above that — with server paging it must be counted server-side or shown as «200+».

### P08 · Realtime push and the shell's own counters
**Rows** `1.1b` live per-status counts (M) · `0.1f` liveness and the COUNTERS stream (M) · `X/X.1` operator console shell (M) · `X.34` LiveBadge/Stale/ConnectionState (M) — **2d**, after `P07`.

The pipe is built and both faucets are shut. `OperationsStreamController:104` serves `text/event-stream` with heartbeats, `Last-Event-Id` resync and a 500-stream cap; `StreamChannel` declares `ORDER_QUEUE`, `ORDER_DETAIL`, `DISPATCH_BOARD`, `STOP_LIST`, `COUNTERS`. Nothing in `ordering` or `fulfillment` ever references `RealtimeSignalPublisher`, and `SseStreamRegistry:369` resolves snapshot channels through `SnapshotSource`, whose only implementation is `CourierPositionSnapshotSource`. So a correctly subscribed client receives keep-alives forever.

Backend: publish a signal on `ORDER_QUEUE`/`ORDER_DETAIL` from the order state transitions, implement a `SnapshotSource` for `COUNTERS` returning the same aggregate as `OrderCountsResponse`, and add a `DISPATCH_BOARD` producer in fulfillment. Version and classification-check the snapshot contract.

Frontend: one `EventSource` client in `core/` with `Last-Event-Id` resync, jittered reconnect and a degrade-to-poll fallback keeping the existing 10s interval; the `X.34` indicator set (LiveBadge, RefreshIndicator, StaleIndicator with an age threshold, ConnectionState banner) in `shared/ui/`; and `shell/service-status.ts` — three signals (`open`, `late`, `updated`), all three written only by `order-queue.ts:217` — given a fetch of its own against `GET .../orders/counts` so the rail's open/late badges stop reading zero until Orders is opened and then freezing. Render `updatedAt` in the shell — the signal exists and is displayed nowhere.

**Capabilities** `ORDER_READ` for `ORDER_QUEUE`/`ORDER_DETAIL`/`COUNTERS`; `DELIVERY_PLAN_READ` for `DISPATCH_BOARD`. The stream must re-check the capability per channel on subscribe, not once at connect.

**Tests** Java tests that an order transition publishes on `ORDER_QUEUE` and `ORDER_DETAIL`, that the `COUNTERS` snapshot equals `OrderCountsResponse` for the same scope, and that a subscriber without the channel's capability is refused. Angular specs for `Last-Event-Id` resync after a dropped connection, for the jittered reconnect, for the degrade-to-poll fallback, and for `service-status.ts` fetching its own counts on shell init rather than waiting for Orders.

**Traps** eleven screens each hand-roll the same visibility-gated 10s poll; extract one. There is no KDS stream channel — a live kitchen board is not in scope here. Delete the «until ADR 0045 live updates exist» comments in `order-queue.ts:56` and `today-page.ts` as part of the wave.

### P09 · Order detail: the record and its outcomes
**Rows** `1.2` order detail (M) · `1.2o` change-due (S) · `1.2p` revision chain (S) · `1.2m` handover-code state (M) · `1.2j` complete with reason (M) · `1.2k` cancel with reason and write-off (M) — **2d**, after `P05`. Mostly frontend.

The detail pane drops 13 of ~19 top-level response fields. Render them: the whole `OutcomeResponse` (kind, system category, reason, stock disposition, liability party, customer refund, occurred-at), `kitchenNote`, `callbackRequested`/`callbackResolvedAt`, `cashTenderedExpectedMinor` and the server-computed `changeDueMinor`, `createdBy`/`acceptedBy`/`acceptedAt`, `currentRevision`, `warnings`, `acceptanceMode`. Add a revisions view over `GET .../{orderId}/revisions` (`:310`, built over V0029, test-covered, no path constant in `operations-paths.ts`). Add a handover panel over `POST .../marketplace/orders/{orderId}/handover-verifications` and `/handover-bypasses` — both built, attempt-consuming, expected value never returned — plus the read projection for challenge state, which is the one backend addition here.

Outcomes: call `POST .../{orderId}/completion` (`:548`) instead of a generic advance, offering the COMPLETION reasons for the order's fulfilment mode from the tenant registry. Today every console completion books `DELIVERED_OWN_COURIER` for delivery, so an order handed to an external service is recorded as the branch's own courier — actively wrong, not merely blind. For cancellation, post `reasonId` from `ordering.order_outcome_reasons` instead of free text; `P05` supplies the reason-required CANCEL action that makes the dialog reachable past CONFIRMED.

**Capabilities** `ORDER_READ` for the record and the revisions; `ORDER_COMPLETE` and `ORDER_CANCEL` for the two outcome dialogs; `MARKETPLACE_HANDOVER_VERIFY` for the handover panel.

**Tests** Angular specs for each newly rendered band and for both outcome dialogs, including a completion that books the fulfilment mode's **own** reason rather than `DELIVERED_OWN_COURIER`. A Java test of the HTTP `/completion` path, which has none today, and one of the challenge-state read projection.

**Traps** the timeline prints a raw `reasonCode` and no actor although `actorType` is on the wire. `order-reason-dialog.ts`'s comment claiming the reason registry «does not exist yet» is stale.

### P10 · Operator notes and the first amendment client
**Rows** `1.2h` the three comment channels (L) — **1d**, after `P09`. Frontend-led.

There is no amendment client in the console at all: `operations-paths.ts`'s only `/amendments` builder is `reservationAmendments` (ADR 0047 table bookings), and nothing under `features/orders/` posts to `/orders/{orderId}/amendments`. Build it: an `orderAmendments` + `orderAmendmentConfirmation` path pair, an `OrderAmendmentsApi` with `If-Match` and `Idempotency-Key`, and the §3.6 «Комментарии» block on the detail pane keeping the customer's words separate from ours.

Wire the three commands the server actually accepts — `SET_KITCHEN_NOTE`, `SET_CALLBACK_REQUESTED`, `SET_CASH_TENDERED` — as the first three uses of that client. That closes the operator→kitchen channel (the note arrives on the payload and is never rendered, and the kitchen board shows it), the callback flag, and the «Сдача с» entry `1.2o` needs, including the acknowledgeable `CASH_TENDERED_INSUFFICIENT` notice when a later amendment pushes the total above the tendered amount. Add the amendment history view over `GET .../amendments` (`:713`).

Operator→courier and internal operator→operator notes have **no owning decision at all** (orders.md §11 calls adding two commands «a one-line ADR amendment»). Make that amendment in this wave: add the two command types to ADR 0039 and to `AmendmentCommandType` as non-financial and therefore `built=true`, with the same shape as `SET_KITCHEN_NOTE`.

**Capabilities** the amendment endpoints already gate correctly — `ORDER_AMEND` for the commands and the confirmation; the two new non-financial command types inherit it.

**Tests** an Angular spec per wired command, one for the `CASH_TENDERED_INSUFFICIENT` notice appearing when a later amendment raises the total, and one asserting the UI never renders the seven financial commands. Java tests for the two new non-financial command types, including that they are accepted with `If-Match` and refused without it.

**Traps** the seven financial commands are `built=false` and are refused by name at `OrderAmendmentService:497-510` — do not let the UI offer them; they are deferred (`1.2c`). The §3.11 POS-export interlock must hide amend affordances while an export is unacknowledged, which lands with `P12`.

### P11 · The order-to-fulfilment seam
**Rows** `1.2e` assign courier from the order (L) · `1.2n` both delivery money fields (M) · `1.2b` status timeline with per-stage clocks (L) · `2.1a` courier ETA on the kitchen ticket (L) — **2d**, after `P09` and `P16`.

Four rows, one missing idea: nothing maps an order id to its production and delivery facts. `kitchen.ticket_events` is written and `JdbcKitchenStore.eventsOf` reads it — with no production caller, only tests. `KitchenBoardController` is location+status scoped, so a finished order's ticket falls off the board entirely. `fulfillment.shipments` holds `assigned_at`/`picked_up_at`/`delivered_at` (V0054:135-137) and `DispatchController.ShipmentView` serialises none of them; `/dispatch/queue` excludes COMPLETED and CANCELLED plans.

Add an order-keyed read: `GET .../orders/{orderId}/delivery` returning plan id and version, current shipment, courier, `estimated_ready_at`, `promised_delivery_start/end`, `customer_delivery_fee_minor` and the provider-billed figure from `fulfillment.delivery_cost_subsidies`; and an order-keyed kitchen-events read. Then: the assign/unassign control on the detail pane against the existing `ManualDispatchService` (`DispatchController:100/:121`, `DELIVERY_MANUAL_ASSIGN`); the two Доставка rows plus the margin line in the Money panel; the three real timeline lanes with elapsed durations between stages, filled/hollow/muted marks, the actor resolved where possible and the losing `ordering.approval_decisions` shown; and the courier ETA chip on the kitchen ticket, which needs the provider `etaMinutes` — discarded today at the Yandex/Noor adapter boundary — persisted into the domain and joined onto `KitchenBoardController.TicketResponse`.

**ADR** ADR 0014 gains the ETA concept; tick its per-order tracking line.

**Migration** yes — reserved numbers **`V0211`–`V0213`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: courier ETA persisted into the domain and joined onto the kitchen ticket.

**Capabilities** `DELIVERY_PLAN_READ` for the order-keyed delivery read, `DELIVERY_MANUAL_ASSIGN` for assign/unassign, `ORDER_READ` for the timeline and the kitchen-events read.

**Tests** Java tests against the migrated schema for the order-keyed delivery read returning both money figures and the shipment timestamps, for the kitchen-events read of a **completed** order (the case that falls off the board today), and for the provider `etaMinutes` surviving the adapter boundary into `TicketResponse`. Angular specs for assign and unassign from the detail pane, for the three timeline lanes with elapsed durations, and for the losing approval decision being shown.

**Traps** «call an external courier» has no backend and is not in this wave — it is `P44`'s. Do not put a customer name or address on the dispatch contract; that is a deliberate `DispatchController` decision.

### P44 · Provider cancel cascade and quote-delta confirmation
**Rows** `1.2g` cascading cancel at the provider (M) · `1.2f` provider quote-delta confirmation (L) — **1d**, after `P11`.

Two rows where the adapter layer is finished and everything above it is missing, so a cancelled order can still have a courier en route with nothing visible in the console.

**Cascade.** `DeliveryCapability.CANCEL_SHIPMENT`, `DeliveryGateway.cancelShipment`, the `DeliveryProcessor` branch, Noor's `PATCH /orders/{id}/cancel`, Yandex's `claims/cancel` and its cancellation-cost probe all exist. Missing above them: a cancel method on `fulfillment.api.ShipmentBookingPort` and its Camel implementation; a fulfillment listener on `OrderCancelled` (or a call from `OrderStateService`/`OrderOutcomeService`) that finds the open plan and cascades; the ADR 0014 endpoint `POST /api/v1/operations/shipments/{shipmentId}/cancel` — named at ADR line 528 and listed as still missing at line 639 — including cancellation-cost classification and the UNCERTAIN path into `fulfillment.delivery_exceptions`; and, on the screen, a provider-cancellation outcome in the cancel dialog's response plus a delivery-exception band on the order detail.

**Quote delta.** There is no operator-invoked provider booking anywhere — `SourceType.EXTERNAL` appears nowhere in main — so there is not yet a moment at which a delta dialog could interpose. Build that moment: a «call an external courier» action on the order and on the dispatch board, a read exposing the last quote price against the customer estimate (`fulfillment.delivery_quotes` and `assignment_attempts` exist and `SourcingPlanner` runs), and an accept/abandon command on `DispatchController` so a human can refuse a price increase before the provider order commits. This is the Millenium pattern the IA names by name and the only seam at which a merchant controls its own delivery cost.

**ADR** tick ADR 0014's shipment-cancel and operator-sourcing lines.

**Capabilities** `DELIVERY_MANUAL_ASSIGN` for the operator booking and the accept/abandon commands; `Capability.SHIPMENT_CANCEL` (`shipment.cancel`) for the cancel endpoint — **it is already registered**, at `iam/api/Capability.java:362`, so name it rather than minting a second one.

**Tests** Java tests against the migrated schema for the cascade on an already-picked-up shipment, for an UNCERTAIN provider response landing in `fulfillment.delivery_exceptions`, and for accept and abandon on a re-quote. An Angular spec that the cancel dialog renders the provider-cancellation outcome and that a price increase cannot be accepted implicitly.

**Traps** no `orders.*quote*` or `delivery.*quote*` i18n key exists — this wave adds the vocabulary too.

### P12 · Money on the order: payment and fiscal panels
**Rows** `1.2l` payment panel and manual re-fiscalize (M) — **1d**, after `P11`.

Two panels on the order detail, both over backends that already answer. POS export (`1.2i`) was split into `P42`: it is an XL row with a backend of its own and it doubled this wave. Payment: `GET /api/v1/operations/tenants/{t}/orders/{orderId}/payment` and `POST .../re-presentations` (`OperationsPaymentController:62/:134`) give the tender, the attempt history and re-issue; `FiscalDocumentController:97` gives that order's fiscal documents and `:135` retries one. `FiscalApi.forOrder` already exists in `fiscal-api.ts:79` and is dead code — no component calls it, so today a FAILED document outside the blocked worklist cannot be inspected or retried from any screen. Render both panels with localized status labels, not the raw enum tokens the Finance page prints.

**ADR** none. `P42` carries the ADR 0011/0012 status lines with the POS half.

**Capabilities** `PAYMENT_READ` and `PAYMENT_REPRESENT` for the payment panel; `FISCAL_DOCUMENT_READ` and `FISCAL_DOCUMENT_RETRY` for the fiscal panel. All four already gate the endpoints this wave calls.

**Tests** Angular specs for both panels including the re-presentation and the fiscal retry paths, and one asserting the localized labels replace the raw enum tokens. A Java test that `FiscalApi.forOrder`'s endpoint returns a FAILED document that never reached the blocked worklist — the case that is unreachable from any screen today.

**Traps** this wave adds no field to `OrderDetailResponse` — `P09` owns that record (hazard #2), and both panels read their own endpoints.

### P42 · Machines on the order: POS export and its errors
**Rows** `1.2i` print to POS and its errors (XL) — **2d**, after `P12`.

The other half of the old `P12`, split out because it is an XL row with a backend of its own.

`integration.pos_order_exports` is written from the ordering side by `PosOrderExportTrigger`
on `OrderConfirmed`/`OrderAwaitingApproval`, and its only HTTP surface is control-plane
(`PosOrderExportController:59`). A merchant therefore cannot see that the order in front of
her never reached the kitchen's POS, and cannot push it again.

Add an operations-plane **sibling endpoint** — `GET .../orders/{orderId}/pos-export` — plus a
push/retry command. It is a sibling deliberately: `P09` owns `OrderDetailResponse` and nothing
else edits that record, which is hazard #2 in this plan and the reason `P11` took the same
shape. Suppress the affordance entirely when the tenant's POS adapter does not declare the
capability (ADR 0011), and add the §3.11 interlock that hides amendment affordances while an
export is unacknowledged — `P10` builds the amendment client that the interlock hides.

**ADR** ADR 0011/0012 status lines. Note that `orders.md`'s «recognised by the schema and
written by nothing» line refers to the dead `order_process_states.POS_ORDER_EXPORT` column,
not to POS export as such — correct it rather than quoting it.

**Capabilities** `pos.export.read` and `pos.export.resolve` — both already registered and already carrying console sentences at `capability-sentences.ts:679-689`, pointing at a screen that does not exist. Suppress the affordance when the tenant's POS adapter does not declare the capability (ADR 0011).

**Tests** Java tests against the migrated schema for the operations-plane export read, for push and retry, and for the endpoint refusing when the tenant's POS adapter does not declare the capability. An Angular spec for the §3.11 interlock hiding amendment affordances while an export is unacknowledged, and one for the affordance being absent entirely on an adapter without the capability.

**Traps** a failed export is not an order failure: the order is real, the kitchen copy is
missing. Say that in the UI, because the obvious wording («order failed») will send an
operator to cancel a paid order.

### P13 · New order: the screen and the basket
**Rows** `1.3` the call-centre order-entry screen (XL) · `1.3c` item search and basket (L) — **2d**, after `P02`.

The highest-value unbuilt pilot screen, and the backend is finished: `POST .../locations/{l}/orders` (`OperationsOrderController:145`, `OperatorOrderingService`, `ORDER_PLACE` at LOCATION scope, granted to `LOCATION_STAFF` and `LOCATION_MANAGER`) reuses `CartService` + `CheckoutService` end to end, so pricing, availability, promise and quote all apply unchanged. `app.routes.ts:68-73` renders `NotBuiltPage`.

Build `features/orders/new-order/` replacing the placeholder: the three-pane composer, a menu/item search using `P02`'s Combobox over the published menu, modifier-option selection, quantity via NumberStepper, a running total that reconciles against the server quote, and the submit with an `Idempotency-Key` followed by a route to the created order's detail. `F2` in the shell already points here.

Known backend constraint to respect, not fix: `OperatorOrderingService.java:94` refuses anything but cash in this release, by ADR 0039 decision. Render that as a stated limit.

One genuine backend gap to close in this wave: there is no text search over products or variants at location scope — `CatalogQueryController.products` and `CatalogAuthoringController.variantsAtLocation` take only `cursor` and `limit`. Add a `query` parameter to the location-variants read so the picker can search thousands of items rather than paging them.

**Capabilities** `ORDER_PLACE` at `LOCATION` scope, already granted to `LOCATION_STAFF` and `LOCATION_MANAGER`; `CATALOG_READ` for the picker's new `query` parameter.

**Tests** an Angular spec for search, modifier selection, quantity, total reconciliation against the server quote, and submit with an `Idempotency-Key` followed by the route to the created order. A Java test for the new `query` parameter on the location-variants read, including that it stays location-scoped.

**Traps** the console must not invent its own pricing; read the quote. Do not build the address pane here — `P14` owns it, and the map half is deferred behind `X.4`.

### P14 · New order: address, money, promo, repeat, aggregator entry
**Rows** `1.3b` address pane (XL, structured half) · `1.3e` payment/order types, promo, change-due (M) · `1.3f` repeat / re-order (M) · `1.3g` manual aggregator order (L) — **2d**, after `P13`.

The address pane's structured half is not blocked and ships now: list the resolved customer's saved addresses through the already-wired reveal (`GET .../customers/{accountId}/addresses`), send the chosen id as `DestinationRequest.customerAddressId`, and offer an inline «add address» reusing the дом/квартира/подъезд/этаж/ориентир form that already exists at `customer-detail-pane.html:277-360`, saving with `coordinateSource: NOT_GEOCODED` or `LANDMARK_ONLY`. Add the ADR 0037 zone-resolved branch selector with «по зоне» and the out-of-zone reason code. The pin, the suggest and the geocoder are deferred with `X.4`.

Money: thread `promoCode` from the place-order request through `OperatorOrderingService` into `CartService.applyPromoCode` — ADR 0072 is built and the wiring is cheap — and surface change-due at creation. The operator channel's allowed payment types are already enforced by `CheckoutEligibilityGuard:257-260`; read that matrix rather than hard-coding, and render the cash-only rule as the current state of the matrix.

Repeat: `ReorderPlanService` and `GET .../orders/{orderId}/reorder` are built but live on `StorefrontOrderingController:525` behind `@CustomerOwned`, so a staff token cannot call them at all. Add a staff-capability wrapper — `GET .../customers/{accountId}/orders/{orderId}/reorder` under `ORDER_READ` — delegating to the same service, and wire «Повторить» here and from the customer's order history (`5.2d`).

Aggregator entry: let an operator record an order as a named marketplace channel with externally-set pricing, writing the V0038 authority columns (`origin`, `pricing_authority`, `entry_mode`, `marketplace_binding_id`) that only `JdbcMarketplaceOrderIntake` writes today, so a phoned-through aggregator order does not corrupt the channel mix.

**ADR** ADR 0040 and ADR 0039 status lines. **Traps** `customer_accounts.origin` and `created_by_actor_id` still do not exist, so an operator-created account cannot be suppressed from marketing — add them here or record the gap on `1.3a`.

**Migration** yes — reserved numbers **`V0214`–`V0216`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `customer_accounts.origin` and `created_by_actor_id`.

**Capabilities** `ORDER_PLACE`; `CUSTOMER_PII_REVEAL` with a stated purpose for the saved-address list; `ORDER_READ` for the new staff reorder-plan wrapper, which must not inherit `@CustomerOwned`.

**Tests** Angular specs for choosing a saved address, for the inline add with `coordinateSource: LANDMARK_ONLY`, for the zone-resolved branch selector and its out-of-zone reason. Java tests for `promoCode` threading into `CartService.applyPromoCode`, for the staff reorder wrapper refusing a principal without `ORDER_READ`, and for an aggregator-entry order writing all four V0038 authority columns.

### P15 · Live board: period scoping, mixes and branch load
**Rows** `0.1` live board (L) · `0.1a` oversized counters (M) · `0.1b` source and type mix (M) · `0.1c` branch leaderboard (M) — **2d**, after nothing.

`JdbcOrderStore.counts` (`:582-614`) filters on tenant, brand and optional location and **nothing else** — no date, shift or business-day predicate — so «Отменено» is every cancelled, rejected and expired order the branch has ever had, and `completed` and `total` are lifetime figures too. One period parameter on `GET .../orders/counts` fixes all three; use ADR 0043's `BusinessDayService` boundary rather than UTC midnight (`reporting.business_day_start` already exists per tenant).

The two mixes are computed client-side over a `MIX_FETCH_LIMIT = 200` page and under-count silently above it, with no truncation flag on `LiveBoardSnapshot` — even though the branch band on the same screen already renders orders.md §2.11's «Показаны N из M» honesty. Either add a group-by mix aggregate to the live order store (there is no `group by` anywhere in the ordering package today) or plumb a truncation signal into the mix cards. Prefer the aggregate: it also serves the wallboard.

The branch leaderboard is real and tested; its cost is N+1 — one `orders/counts` call per active location every 10 seconds — because no endpoint returns counts for a whole brand in one read. Add a brand-scoped counts read under `LOCATION_READ`/`ORDER_READ` at BRAND scope and collapse the fan-out. The other half of `0.1c`, load shown in the branch picker at order entry, lands with `P13`.

**Done** a supervisor can read «cancelled today», the mix is either exact or visibly approximate, and a ten-branch tenant costs one request per tick.

**Capabilities** `ORDER_READ` for the period-scoped counts; the brand-scoped counts read is `ORDER_READ` at `BRAND` scope and must refuse a location-scoped principal.

**Tests** Java tests for the period predicate at a business-day boundary that crosses midnight, and for the brand-scoped counts read refusing a location-scoped principal. Angular specs for the truncation note, the period label, and the leaderboard issuing **one** request per tick rather than one per branch.

**Traps** the operator leaderboard band stays an honest locked note — it is deferred behind the staff-identity ADR. `order-status.spec.ts` asserts the in-progress set against the tab union, not against the SQL rule; do not claim it proves server alignment.

### P16 · Kitchen queue and stop list
**Rows** `2.1` kitchen queue (M) · `2.1c` dispatch from the pass (M) · `2.1e` counter sale from the kitchen (L) · `2.5` stop list (S) · `2.5b` stop source explainer (L) · `2.5c` stop-change digest (S) — **2d**, after `P03` and `P06`.

Sequenced behind `P03` because it migrates `stop-list-page`'s selection model to `q-data-table`, and behind `P06` because that wave owns the lateness constants in `kitchen-ticket.ts`. This wave owns `KitchenBoardController`'s board query and its tab counts; `P11` adds the ETA field to `TicketResponse` afterwards, and `P17` builds the device client after that.

Kitchen queue: a cook sees `line.hasNote` as a bare chip because the note text sits behind the audited `OrderRevealApi.revealLineNote`, which no kitchen file imports — so «без лука» never reaches the line. Call it, with the reveal's purpose recorded. Add the aggregator tab by typing `channelCode` against `sales_channels.system_type` instead of rendering a raw string, and make the tab counts exact rather than per-fulfilment-mode over one 200-ticket page. Then the two affordances the screen's own doc wrongly calls impossible: inject `DispatchApi` + `CouriersApi` and assign an in-house courier from a delivery ticket (`DispatchController:100/:121` is real and already client-proven on the dispatch board, joining plan to order by `orderId`), and link a counter sale to `P13`'s new-order screen from the kitchen shell.

Stop list: `applyBulk` is a sequential loop of one `PUT` per row with a failure count as the only report; add a server-side batch availability endpoint on `InventoryController` modelled on the ADR 0039 bulk contract, and make the tab counts and the ON_STOP tab server-side rather than over the 50-row page already loaded. Add a search box. Then the explainer: surface a structured stop source on the availability read model (`VariantAvailabilityResponse` carries no source, last-changed or reason), so an operator can tell a kitchen stop from a POS push — the audit facts already distinguish them (`OPERATIONS_STOP_LIST_TOGGLE` vs `POS_STOP_LIST`, actor `pos-availability-poll`) and are readable through the audit-events endpoint whose path helper already exists. Finally, coalesce the stop-change alert: `InventoryOperationsAlertTrigger` fires one message per item and never fires on return-to-sale; add a windowed digest and the back-in-stock direction.

**Migration** yes — reserved numbers **`V0217`–`V0219`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: a structured stop source on the availability read model.

**Capabilities** `KITCHEN_BOARD_READ` for the queue; `CUSTOMER_PII_REVEAL` with a recorded purpose for `revealLineNote`; `INVENTORY_AVAILABILITY_MANAGE` for the batch stop/unstop; `DELIVERY_MANUAL_ASSIGN` for assigning from the pass.

**Tests** Angular specs for the revealed line note reaching the ticket, for the aggregator tab typed off `sales_channels.system_type`, for assigning a courier from the pass, and for server-side tab counts. Java tests against the migrated schema for the batch availability endpoint (idempotency, per-item outcomes, the 200-item cap), for the structured stop source distinguishing an operations toggle from a POS push, and for the digest coalescing a burst and firing on return-to-sale.

**Traps** scope (`2.5a`) and channel propagation are deferred — this wave stays location-scoped. Correct the stale comments in `kitchen-queue-page.ts:62-63` and `kitchen-api.ts:11-12` that claim per-line notes are already read.

### P17 · KDS device shell, enrolment and QR
**Rows** `X/X.2` KDS fullscreen shell (L) · `2/X.2` device enrolment and registry (M) · `X.35` QRCode display and scan input (M) — **2d**, after `P01` and `P16`. Frontend only.

ADR 0079 is done and unreachable: `iam.device_principals` and `iam.device_enrolment_requests` (V0192) exist, `PlatformRole.KITCHEN_DEVICE` exists, `KitchenDeviceController` (`:53` list, `:70` approve by user code, `:95` revoke, all `KITCHEN_STATION_MANAGE`) and `DeviceEnrolmentController`'s unauthenticated bootstrap pair are real — and no file under `frontend/` references any of them. A kitchen tablet has no application to run and no manager can accept the code it shows.

Build two things. First, a **device shell**: a top-level route sibling of the console `Shell` (the way `/login` and `/invite` are), chrome-less, with touch-scale typography and hit targets replacing `kitchen-queue-page.css`'s 6-12px paddings and 12/14px type, a device-token auth path instead of the staff Keycloak session, and an offline banner driven by `navigator.onLine` plus the online/offline events — nothing in the app reads either today. Second, a **pairing screen** under Kitchen: list enrolled devices, approve a typed user code, revoke a lost tablet with a reason. Per ADR 0079 the code is typed, not scanned.

`X.35` rides along: a `q-qr-code` display component in `shared/ui/` (no frontend package renders a QR bitmap anywhere), used here for device pairing and by `P38` for table QR cards, plus rendering the `qrPayload` the payments API already returns and the console drops (`payments-api.ts:53`).

**Capabilities** `KITCHEN_STATION_MANAGE` for list, approve and revoke on `KitchenDeviceController`; the device shell itself authenticates as `PlatformRole.KITCHEN_DEVICE` through the ADR 0079 device token, never through the staff Keycloak session.

**Tests** Angular specs for the device shell's offline banner driven by `navigator.onLine` and the online/offline events, for the device-token auth path rejecting a staff session, and for the pairing screen's approve-by-user-code and revoke-with-reason. A spec for `q-qr-code` rendering the `qrPayload` the payments API returns.

**Traps** ADR 0079's status line claims a kitchen device «can enrol, read the board and mark a line ready today» — true of the API, false of any shipped client; fix that sentence. Marked-goods DataMatrix scanning has no endpoint, no model and no ADR: split it out, do not grade this wave against it.

### P18 · Dispatch board, board columns and drag-assign
**Rows** `3.1` dispatch board (L) · `X.21` Board/BoardColumn/BoardCard (M) · `X.22` DragDropAssign (M) — **1d**, after `P01` and `P03`.

A dispatcher gets a text table where the IA promises the диспетчерский модуль. The server half is done and idempotent — `DispatchController` `/queue`, `/plans/{planId}/assign`, `/unassign`, `/plans/{planId}/exceptions`, with compare-and-set single-winner semantics and ADR 0027 audit facts, under `DELIVERY_PLAN_READ` and `DELIVERY_MANUAL_ASSIGN`.

Build `q-board` / `q-board-column` / `q-board-card` and `q-drag-drop-assign` in `shared/ui/` — **`P03` adds `@angular/cdk` and its lockfile entry; consume it, do not add it again**, which is why this wave is sequenced behind `P03` as well as `P01` — then re-lay `dispatch-board-page.html` as courier-keyed columns of order cards: drag a card onto a courier to assign, drag off to unassign, with the courier's `activeAssignments`/`concurrencyCeiling` rendered as the drop target's load. The optimistic-concurrency `expectedVersion` a drop must send is already in hand. Call `GET /plans/{planId}/exceptions` — built and never called — so a `MANUAL_ACTION_REQUIRED` plan shows its reason instead of a status word. Add click-to-filter.

Scope note for the planner: only `3.1` still forces this component — ADR 0071 withdrew the review kanban — so size it as dispatch-only.

**Out of scope, deliberately**: the map (deferred with `X.4`), «call an external courier» (no backend; `P44`), bulk assignment (`BulkActionType` is ADVANCE|CANCEL and ADR 0039 defers the courier case), and customer name or address on the row — `DispatchController`'s class doc makes that a contract decision, and the page already joins the public order number client-side.

**Capabilities** `DELIVERY_PLAN_READ` for the queue and the exceptions read, `DELIVERY_MANUAL_ASSIGN` for the drop.

**Tests** Angular specs for drag-to-assign sending the correct `expectedVersion`, for a stale version surfacing the single-winner refusal rather than silently reverting, for drag-off unassign, for the courier column rendering `activeAssignments`/`concurrencyCeiling` as load, and for a `MANUAL_ACTION_REQUIRED` plan showing its exception reason.

**Traps** `StreamChannel.DISPATCH_BOARD` is declared and unfed; live updates arrive with `P08`, not here. Keep the 10s poll until then.

### P19 · Courier roster: the compliance file
**Rows** `3.3` couriers (L) — **1d**, after nothing.

`fulfillment.couriers` carries id, type, subject, reference, a protected name and status — and the self-employment model the platform assumes needs a file. Add, behind the ADR 0029 envelope with the same protection the name already has: passport, PINFL, driving licence, vehicle registration, plate and fuel type, photo, address, emergency contact, referral and notes; plus courier groups and branch bindings as first-class relations (a courier is attached to a branch today only by an open shift). Extend `OperationsCourierController` (`GET /couriers:137`, `POST /couriers:299`) and `CourierRegistrationService` accordingly, with a reveal path for the protected fields carrying a purpose, exactly as customers do.

Frontend: a courier detail pane behind the roster, the register form widened, and the inline honesty notice at `couriers-page.html:76` deleted once its list is served. Render `vehicleClass` — it is in the DTO, in the component doc and in the spec fixture, and in no template.

Courier-app provisioning stays as it is: the register form asks for a Keycloak subject created outside the console, and ADR 0042 forbids deriving a password from a passport number. Online status and rating are read-only and out of scope until a source exists.

**Migration** yes — reserved numbers **`V0220`–`V0222`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: courier compliance columns under the ADR 0029 envelope; `courier_groups`; `courier_branch_bindings`.

**Capabilities** `COURIER_READ` for the roster, `COURIER_ENGAGEMENT_MANAGE` for the register and suspend paths, `COURIER_REGISTRATION_VERIFY` for verification. The compliance file needs a **new** `COURIER_PII_REVEAL` (`courier.pii.reveal`) modelled on `CUSTOMER_PII_REVEAL`: `COURIER_REGISTRATION_REVEAL` already exists but its Javadoc scopes it to the registration identifier for the accountant export, and passport, PINFL, licence, address and emergency contact are a wider set that must not ride in on it.

**Tests** Java tests against the migrated schema for envelope encryption of every new protected field, for the reveal writing its audit fact with a purpose, and for `PINFL` being absent from the list read. Angular specs for register, verify and suspend — none of which is covered today (`couriers-page.spec.ts` has two tests) — and one asserting `vehicleClass` renders.

**Traps** do not put PINFL on the list read; it is a reveal.

### P20 · Zones, regions and delivery tariffs
**Rows** `3.6` delivery zones (L) · `3.6d` free geozone (S) · `3.6b` regions and bounding boxes (M) · `3.7` delivery tariffs (L) — **2d**, after nothing.

The backends are further along than the screens. `ServiceZoneController.DraftVersionRequest` already accepts `geoJson` and validates self-intersecting rings, the region bounding box and an area ceiling; `DeliveryTariffController` already accepts many bands with named band sets, peak-hour time rules with day masks and multipliers, `AMOUNT` and `DISTANCE_ALLOWANCE` discounts, `feeSource` `TARIFF|PROVIDER_QUOTE`, `distanceMode` `RADIUS|ROAD`, min/max fee, rounding and road factor — and returns all of it. The console authors one flat band and one circle.

Zones: pass `deliveryTariffId` on the draft (the field exists on both sides and `submitDraft` simply never sends it, so every console-drawn zone carries a null tariff and the zone-beats-branch precedence cannot be exercised); offer the `CATCHMENT` role the form hard-codes away; author per-locale names instead of writing one string into all three; separate draft from activate and bind, and add a version list, a deactivate and an unbind — the console can only ever add today. Polygon drawing is deferred with `X.4`.

`3.6d` **free geozone** rides on that one fix and is smaller than the parity matrix makes it look. `ZoneRole`'s own Javadoc settles the design — «a 'free geozone' is not a third role: it is a DELIVERY zone whose tariff resolves to zero, and expressing it as a layer is what left three layers with no documented interaction» — so do **not** build a third geometry layer. What this wave owes is the consequence of the tariff binding: a zone bound to a zero-resolving tariff, and a «бесплатно» marker on the zone list and on the fee evidence so an operator can see which zones are free without opening each tariff. The IA's free-geozone-as-a-layer wording is struck in PART B; cite ADR 0037 when you strike it.

Regions: build the CRUD that does not exist at any layer — `fulfillment.regions` (V0025) is written only by test SQL, so a fresh production database has no region at all and onboarding a second city needs hand-written SQL. Add create/update/archive/list with ADR 0027 audit facts, and a SW/NE bounding-box form.

Tariffs: author distance tiers, peak-hour windows, discounts, min/max fee and rounding; render the fields the detail panel drops (distance mode, reach, discounts, and time rules as more than a count); bind a tariff to a branch (`bindLocation` exists and has no caller). Point the page at the operations mirror — `delivery-paths.ts:66` builds the tariff base URL from `CONTROL_PLANE`, so the screen calls the wrong surface for the operator it serves.

**Capabilities** `DELIVERY_ZONE_READ`/`DELIVERY_ZONE_MANAGE` and `DELIVERY_ZONE_ACTIVATE` for zones (the activate split is ADR 0037's, keep it); `DELIVERY_TARIFF_READ`/`MANAGE`/`ACTIVATE` for tariffs; region CRUD reuses `DELIVERY_ZONE_MANAGE` — a region constrains a geocoder, it is not a separate authority.

**Tests** Java tests that a draft carrying `deliveryTariffId` activates with the tariff bound and that zone-beats-branch precedence resolves to it; that a `CATCHMENT` draft with a tariff is refused by `ck_zone_version_catchment_is_not_priced`; that region create/update/archive writes ADR 0027 audit facts; and that activating `ROAD` without a routing binding is refused while the fallback stamps `RADIUS_FALLBACK`. Angular specs for the zero-fee zone rendering as free (`3.6d`), for per-locale zone names, and for unbind — both pages' write paths are entirely untested today.

**Traps** activating ROAD without a routing binding is refused server-side, and a fallback stamps `RADIUS_FALLBACK` on the resolution — render that, do not hide it. Both pages' write paths are entirely untested.

### P21 · Product library and the fiscal workbench
**Rows** `4.1` products list (L) · `4.1a` bulk ИКПУ, delete, copy-id, share slugs (L) — **1d**, after `P03`.

Search and the status tabs are computed client-side over the pages already fetched, so on the 1000+ item catalogue the row is written for, searching a dish past the loaded pages returns nothing. Add server-side `query` and `status` parameters to `GET /catalogs/{catalogId}/products` and drive the tabs and the search box from them.

Then the row actions, none of which exist: duplicate (needs a new endpoint), archive / status transition (`Product` carries a `Status` and no mutation exposes it), add-to-category (the endpoint exists — `PUT /categories/{categoryId}/products/{productId}` — and has no caller anywhere), stop-in-all-branches (a product-level fan-out over the existing per-variant, per-location offering write), copy-id and the public share slug (no backend at all). Add the selection model and bulk-action bar from `P03`.

The fiscal workbench is the other half and the one that decides whether a 600-item menu can be onboarded in a day: a coverage read answering «N of M priceable nodes unclassified» over `catalog.fiscal_classifications` (V0028 has `ix_fiscal_classifications_incomplete` built for exactly this query and no endpoint asks it), an unclassified worklist including `MODIFIER_OPTION` and `FEE` nodes, and a bulk classify endpoint so ИКПУ and package codes can be filled down a `q-data-grid` column instead of one variant at a time in the editor.

**Capabilities** `CATALOG_READ` for the list, the status tabs and the coverage read; `CATALOG_AUTHOR` at `BRAND` scope for duplicate, archive, add-to-category, stop-in-all-branches and the bulk classify.

**Tests** Java tests for the server-side `query` and `status` parameters and for the node-level coverage read using `ix_fiscal_classifications_incomplete`; a test that the bulk classify is idempotent and reports per-node outcomes. Angular specs for the row actions and for the `NO_MXIK` tab showing the **node-level** figure, not the product-level one.

**Traps** the `NO_MXIK` tab count today is client-side, product-level `hasMxik` — it is not the node-level coverage figure and must not be presented as one. Every catalog controller is on the control-plane prefix; if this wave adds an operations mirror, do it once and move the paths file with it.

### P22 · Product editor: variants, media and fiscal data
**Rows** `4.2` the seven-tab core (M) · `4.2f` ordered images with per-aggregator overrides (L) · `4.2d` fiscal data on every priceable node — marking, excise, alcohol, age gate (M) · `4.2e` ИКПУ/MXIK reference typeahead (M) · `X.12` MediaUploader with crop (M) — **2d**, after `P01` and `P02`.

Sequenced behind `P01` because `q-media-uploader` lands in `shared/ui/`, and behind `P02` because that wave migrates this screen's locale strip to `q-localized-field-group` — two waves rewriting `product-editor-page` at once is the collision this ordering removes. The per-item schedule, the kitchen department and cross-sell moved to `P47`.

Three defects on one screen. **Variants**: «Add variant» posts only `sortOrder` and an `UNCLASSIFIED` fiscal block, creating a nameless, SKU-less, unit-less variant the console then cannot name because the translation form writes `entityType PRODUCT` only — `AddVariantRequest` accepts sku, unitCode and name, so send them and add `VARIANT` translations. The variants tab is otherwise read-only apart from the price input: add name, SKU, unit, default-variant and deactivate. **Status**: `Черновик↔Активен` is read-only text; add the status endpoint (none exists) and a remove-from-category/catalog path (there is no `@DeleteMapping` anywhere in the catalog package).

**Media**: the photo grid renders a role label and no image — there is not one `<img>` tag in the entire console — although `GET /media/assets/{assetId}/download-url` exists and `operations-paths.ts:529` already declares the helper. Render thumbnails, add reorder by re-`PUT`ting the attach endpoint with a new `sortOrder`, add a detach endpoint (missing at every layer, so a wrong upload cannot be undone), and build `q-media-uploader` with aspect-ratio crop (1:1, 3:2, 3:1, 9:16), size caps and progress. Per-aggregator variants need a channel dimension on `catalog.media_relations` and are the backend half of `4.2f`; video needs the image-only allowlist and 10MB cap widened.

**Migration** yes — reserved numbers **`V0223`–`V0225`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: a channel dimension on `catalog.media_relations`; the widened media allowlist.

**Capabilities** `CATALOG_AUTHOR` at `BRAND` scope for the variant, status and media writes; `CATALOG_READ` for the editor's reads. The media detach endpoint gets `CATALOG_AUTHOR`, not a media-specific capability — the asset is catalog content.

**Tests** Java tests against the migrated schema for `AddVariantRequest` persisting sku, unitCode and name; for `VARIANT` translations; for the status endpoint; for the detach endpoint removing exactly one relation; and for the channel dimension on `media_relations`. Angular specs for the thumbnail grid actually rendering an `<img>`, for reorder by re-`PUT` with a new `sortOrder`, and for the uploader's aspect-ratio crop and size cap.

**Traps** derivatives are rendered and stored and never served — neither download-url accepts a variant, so a thumbnail grid loads full-size originals unless you add one.

### P47 · Per-item schedule, kitchen department and cross-sell
**Rows** `4.2g` per-item sale schedule and kitchen department (L) · `4.2h` recommended products / cross-sell (M) — **1d**, after `P02` and `P22`.

Two things that hang off an item rather than describing it, split out of the product editor so
`P22` can stay inside its day and so this wave can wait for `P02`'s `q-schedule-grid`.

**Kitchen department** is pure wiring: the existing `POST .../kitchen/routing-rules` already
routes a product to a station, and the editor renders a «not built — ADR 0016 open input»
caption over it. Delete the caption and wire the picker.

**Per-item sale schedule** genuinely has no binding — V0020 withdrew
`location_offerings.sales_schedule_id` — so breakfast-only items are stopped and unstopped by
hand twice a day. Add the binding table and the `q-schedule-grid` editor, resolving at order
time against the location's timezone and not the operator's.

**Cross-sell** (`4.2h`) is new to this map and unbuilt at every layer: no
`catalog.product_recommendations` table in any of the 159 migrations, no read model carrying a
recommendations list, and `grep -ari recommend frontend/operations/src` returns nothing. Add
the table, the attach/detach/reorder endpoints and the block in the product editor. **The
filter is the row**: IA 4.2 asks for recommendations «filtered by active + in-menu +
not-stopped», and a recommendation that points at a stopped variant is worse than none —
resolve the filter server-side at read time, not by pruning the stored set, so a stop that
lifts restores the recommendation.

**Migration** yes — reserved numbers **`V0226`–`V0228`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: the per-item sale-schedule binding V0020 withdrew; `catalog.product_recommendations`.

**Capabilities** `CATALOG_AUTHOR` for the schedule binding, the kitchen-department routing rule and the recommendation set; `CATALOG_READ` for the pickers.

**Tests** Java tests against the migrated schema for the per-item schedule binding resolving at order time and for a recommendation set filtered to active + in-menu + not-stopped — the filter is the row, so test the stopped case explicitly. Angular specs for the schedule grid editor and for the kitchen-department picker writing a routing rule.

**Traps** a recommendation is directional and the temptation is a symmetric link table; do not
build one — «chips with this burger» is not «this burger with chips». Cross-sell does not
imply a combo: combos are `4.2a` and are deferred behind an ADR 0016 amendment.

### P23 · Categories and the per-location menu matrix
**Rows** `4.3` categories (L) · `X.23` SortableList/TreeView with drag-reorder (M) · `4.4` menus matrix (L) — **2d**, after `P03`.

Categories are write-once: there is no `PUT` that changes `parentCategoryId`, `sort_order`, `status`, `code` or description, and `JdbcCatalogStore` has only `INSERT INTO catalog.categories`. Add the update and archive endpoints, then the tree the spec asks for: `q-tree-view` with drag-reorder and keyboard navigation (arrow keys, left/right collapse, Alt+arrow reorder and promote, Enter rename — none of which exists), child counts, status dots, the empty-category state, and the red cycle path the current `nodes()` silently skips past so `CATEGORY_TREE_HAS_CYCLE` is invisible. Rename and description go through the existing `PUT /translations`; the image through the existing `attachMedia`; the per-category product list is derivable from the products read.

Menus: the matrix shows two states for one location, so a variant hidden at the offering level is indistinguishable from one never added — widen the location-variants read to surface `lo.status` and drop its AVAILABLE-only join, and add filter/search parameters and a bulk stop/unstop endpoint. Add the per-order-type column: `SetOfferingRequest.fulfillmentModes` is already accepted and persisted, and only the read's projection drops it. Follow the cursor — the backend pages at 50 and `nextCursor` is never passed, so a location with more than 50 sellable variants is silently truncated.

The per-channel offering and price plane (`4.4b`) is an XL row and was split into `P45`, which runs straight after this wave over the same files. The named `Menu` entity itself is deferred (`4.4a`).

**Capabilities** `CATALOG_AUTHOR` for the category update, archive and reorder and for the bulk stop/unstop; `CATALOG_READ` for the tree and the matrix.

**Tests** Java tests for the category update and archive including a refused cycle (`CATEGORY_TREE_HAS_CYCLE` must surface, not be skipped), and for the widened location-variants read distinguishing «hidden at offering level» from «never added». Angular specs for tree drag-reorder, keyboard reorder and promote, and for the matrix following `nextCursor` past 50 variants.

**Traps** `catalog.md` and `menus-page.ts` both assert the exclusions table does not exist; V0020 built it. Fix both.

### P45 · Menus: per-channel offering and price
**Rows** `4.4b` per-channel offering and price (XL) — **2d**, after `P23`.

Split out of `P23` on size; sequenced behind it because both write `features/catalog/menus*`.

The **price plane is already complete server-side** — `PriceAuthoringController.assignToChannel`
and `JdbcPricingStore`'s CHANNEL precedence — with a frontend client that nothing calls. Add
the channel selector and let a hall price and an aggregator price coexist on one variant.

The **enablement plane** is the gap: `catalog.channel_offering_exclusions` (V0020) has a reader
and no writer anywhere, so «this dish is not on Uzum Tezkor» cannot be expressed. Add the write
path and a **mass-enable** for onboarding an aggregator — enabling 600 items one at a time is
the thing that makes an aggregator launch take a week.

Keep the two planes separate in the UI as the IA insists: `offered_on_channel` and
`price_on_channel` are different questions, and conflating them is the Delever behaviour this
row exists to avoid. The named `Menu` entity itself stays deferred (`4.4a`).

**Capabilities** `PRICE_AUTHOR` for `assignToChannel`; `CATALOG_AUTHOR` for the exclusion write and the mass-enable; `CATALOG_READ` for the matrix.

**Tests** Java tests against the migrated schema for `assignToChannel` precedence over the base plane, for the exclusion write and the mass-enable, and for `SetOfferingRequest.fulfillmentModes` surviving into the read projection. Angular specs for the channel selector and the per-order-type column.

**Traps** `catalog.md` and `menus-page.ts` both assert the exclusions table does not exist;
V0020 built it. Fix both — `P23` fixes the same pair of claims for the matrix, so check whether
it has already landed before editing.

### P24 · POS catalog sync and the mapping pane
**Rows** `4.5a` POS sync: mapping table and run history (L) · `10.8b` integration mapping tables (XL) · `X.24` MappingPane (L) — **2d**, after `P03`.

`/catalog/import` is a `NotBuiltPage` and the merchant-facing half of ADR 0012 does not exist, although staging, the difference engine, the durable scheduler and raw snapshots are all built behind `/api/v1/control-plane/tenants/{tenantId}/pos-sync-runs` (start, differences, review-decisions, apply, resume).

Backend: add a run-history list and a run detail (there is no listing `GET` at all), a read over `integration.pos_sync_apply_items` for per-item outcomes after the fact, import-language and price-re-import parameters on the run start, and — the larger piece — a tenant-facing mapping API over `integration.provider_entity_mappings`: list by binding and entity type, list unmapped external entities, create an `OPERATOR`-sourced mapping (the enum value is allowed by the CHECK and nothing ever writes it), retire one, and bulk auto-match by name. Five of the six entity types have no code path at all — `PAYMENT_TYPE`, `DISCOUNT`, `COURIER`, `CANCELLATION_REASON` and the channel→POS category code are never read or written, and no adapter method discovers a provider's payment-type or discount list, so the right-hand side of the pane has to be sourced too.

Frontend: `q-mapping-pane` in `shared/ui/` — dual list, link/unlink, unmapped-only filter, bulk auto-match, and the two-sided conflict card the spec requires instead of last-write-wins — plus the import screen (run list, per-item outcome report, mapping tab) and the installation-detail «Соответствия» tab.

**Migration** yes — reserved numbers **`V0229`–`V0231`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: indexes for the mapping reads; the `OPERATOR` mapping source has no schema change.

**Capabilities** the run reads and the mapping list are `POS_SYNC_READ`; starting a run, applying it and every mapping mutation are `POS_SYNC_MANAGE`. Neither may be satisfied by `PLATFORM_ADMIN` alone — the point of the row is that a merchant can do this without the platform.

**Tests** Java tests against the migrated schema for the run-history list and detail, for the per-item outcome read, for an `OPERATOR`-sourced mapping being accepted by the CHECK, for retire, and for bulk auto-match reporting conflicts rather than last-write-wins. Angular specs for the dual list, the unmapped-only filter and the two-sided conflict card.

**Traps** `catalog.md:1352` says the external-id mapping table with linked/unlinked/conflict states is unbuilt; V0013:145 built it and `JdbcPosApplyStore` writes it. Excel import (`4.5b`) is a different row and is deferred — do not let this wave grow into it.

### P25 · Customer record depth: contact, consent, address
**Rows** `5.2a` contact points (M) · `5.2b` consent and marketing eligibility (M) · `5.2c` saved addresses (M) — **2d**, after nothing.

Three rows, one screen, two of them with a live correctness bug.

**Contact points**: `CustomerController` has only `POST` and `GET` on `/contact-points` — no `PUT`, `PATCH` or `DELETE`, and `JdbcCustomerStore` has no update, delete or set-primary — so a mistyped number can only be shadowed by adding another. Add the three mutations. The other two complaints are screen-only: `ContactType` already accepts `EMAIL` and only `customer-detail-pane.ts:330` hard-codes `'PHONE'`, and `verificationStatus` is on the wire and never rendered although it is what `MarketingEligibility` uses to refuse with `NO_VERIFIED_ENDPOINT`. Render the masked summary too — it is fetched and displayed nowhere, so the section is blank before Reveal.

**Consent**: the console posts no `brandId`, a hard-coded `policyVersion: 'operator-recorded-v1'`, a lowercase `'marketing'` purpose and a null channel, while `JdbcCustomerStore.currentConsent` matches brand, purpose and channel exactly — so the default submission misses on all three axes and recording consent here cannot make anyone contactable. Send the brand, replace free text with a constrained picker aligned to the strings the platform actually matches, and stop faking the policy version. Add the missing backend read: nothing answers «is this customer contactable» — `MarketingEligibility.refusalFor` is reachable only from `AudienceService` and `CampaignSendService` — so add a per-customer eligibility endpoint and render the answer with its refusal reason.

**Addresses**: every address the operator saves is created without coordinates, so a phone order captured here cannot be zone-resolved or tariffed. The operator-pin half is frontend-only (`OPERATOR_PIN` with lat/long already passes `requireCoordinatesMatchSource`) and rides on `X.4`; what ships now is showing `coordinateSource` so an operator can see which addresses are landmark-only, and protecting the hand-written guard at `:493-495` that stops a text edit dropping a storefront pin — it has no test.

**Capabilities** `CUSTOMER_READ` for the record; `CUSTOMER_MANAGE` for the three contact-point mutations and the consent write; `CUSTOMER_PII_REVEAL` with a purpose for the unmasked values. The eligibility read is `CUSTOMER_READ` — it answers a yes/no and a refusal reason, never a contact value.

**Tests** Java tests for contact-point update, delete and set-primary, including that deleting the last verified endpoint changes the eligibility answer; and for the per-customer eligibility endpoint returning `NO_VERIFIED_ENDPOINT` and `CONSENT_WITHHELD` distinctly. Angular specs for the consent form sending a brand, a constrained purpose and a channel that `currentConsent` actually matches, and a spec pinning the `:493-495` guard that stops a text edit dropping a storefront pin.

**Traps** the `SendPulse` import records purpose `MARKETING` while campaigns default to `MARKETING_PROMOTIONS`; pick one and record it.

### P40 · Customer record depth: ledger, promos, reviews, blacklist, erasure
**Rows** `5.2d` order history + reorder (M) · `5.2e` cashback ledger (S) · `5.2g` promo redemptions (M) · `5.2h` reviews left (S) · `5.2i` blacklist (S) · `5/X.1` data-subject erasure (S) — **1d**, after `P25` and `P14`.

Five small rows on the same pane, one of which is an accountability hole.

**Blacklist**: the reveal is paid for — a purpose-stamped decrypt — and then most of the payload is discarded. `RevealedBlacklistEntry` carries `actorType`, `actorId`, `expiresAt`, `liftedAt`, `liftedByActorId` and `liftReason`; the history card renders reason, status and created-at. For a row whose own title is «reason, actor, expiry», render the actor. Same for `BlacklistStatus.expired` and `since`.

**Cashback**: `POST .../loyalty/adjustments` exists on the same controller with `LOYALTY_ADJUST` and an ADR 0027 approval above threshold, and nothing on the pane calls it — while `loyalty-page.ts:54-56` tells the reader the manual adjustment UI is «already built ... on Customer detail». It is not. Add it, format `entry.amountMinor` with the enclosing balance's currency (it prints raw beside a formatted balance), render `balanceAfterMinor`, `reasonCode` and the linked order, and label each balance card with its brand — per-brand separation is the endpoint's whole point and two brands render as two indistinguishable cards.

**Promo redemptions**: `pricing.coupon_redemptions` has `ix_redemptions_customer` built for exactly this query and no read path at any layer. Add the store query, the port method, the service and an operations endpoint, then the pane section.

**Reviews left**: add a `customerAccountId` parameter to `OperationsReviewController.list` and a tab. Today there is no path at all from a customer to their reviews — the review list never renders an account id, so even matching by eye is impossible.

**Erasure**: raise, withdraw and execute are fully built (`CustomerController:567/:586/:602/:631`, V0178, `CustomerErasureService`) and the only screen showing them is the control plane, which a merchant cannot reach. Add the section, gating execute on `CUSTOMER_ERASURE_EXECUTE`.

**Reorder**: point «Повторить» at `P14`'s staff reorder-plan wrapper instead of the not-built page, and render order status through the console's own label map rather than the raw enum.

**Capabilities** `CUSTOMER_READ` for the ledger, promo and review sections; `LOYALTY_ADJUST` for the manual adjustment, with the ADR 0027 approval above threshold; `CUSTOMER_PII_REVEAL` for the blacklist actor; `CUSTOMER_ERASURE_RAISE` for raise and withdraw and `CUSTOMER_ERASURE_EXECUTE` for execute.

**Tests** Angular specs for the blacklist history rendering actor, expiry and lift reason; for per-brand balance cards being distinguishable; for `entry.amountMinor` formatted in the enclosing balance's currency; and for the erasure section gating execute. Java tests against the migrated schema for the promo-redemption read using `ix_redemptions_customer` and for the `customerAccountId` filter on the review list.

### P26 · ImportWizard, customer CSV and the header counters
**Rows** `X.13` ImportWizard (L) · `5.1b` bulk CSV import with provenance (L) · `5.1a` header counters from the metric layer (M) — **2d**, after `P02`.

Build `q-import-wizard` in `shared/ui/`: FileDropzone, a row-level preview table, a dry-run diff, JobProgress and a ResultSummary with per-row outcomes. It is a pilot blocker for three rows (`4.5`, `5.1b`, `3.6c`) and the per-row outcome report is the IA's stated improvement over Delever's silent skip.

Its first consumer is the customer CSV. The reusable half already exists and must not be rebuilt: `Capability.CUSTOMER_IMPORT` is registered and granted to tenant-owner, `CustomerImportDirectory` (`accountsWithPhone` / `createAccountWithoutPrincipal` / `attachPhoneContact` / `record`) fixes the recorded consent source to `IMPORT`, and `JdbcSendPulseImportStore` already stores per-row run reports with a reject-reason vocabulary. What is missing is a non-Telegram parser (the only parser keys on `chat_id` and rejects any row without one), a tenant-scoped controller over that same port, and an async job surface — both existing imports are synchronous `POST`s returning a report, so a progress bar has nothing to poll until one exists.

`5.1a` rides along because it is the same team and the same screen: the header counters are a second, unregistered code path computed at UTC midnight, so between 00:00 and 05:00 Tashkent a row the grid dates «today» is excluded from «registered today». `reporting.agg_branch_day` already stores `distinct_customers` and `new_customers` on the ADR 0043 business-day boundary — register the metric definitions, move the counters onto `BusinessDayService`, and give `total` an agreed meaning (`countActive` counts every non-`MERGED` row including CLOSED and ANONYMIZED).

**Migration** yes — reserved numbers **`V0232`–`V0234`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: the import-job entity and its per-row report.

**Capabilities** `CUSTOMER_IMPORT` — already registered and granted to tenant-owner — for the run; `CUSTOMER_READ` for the preview and the header counters.

**Tests** Java tests against the migrated schema for the non-Telegram parser accepting a row with no `chat_id`, for the per-row reject-reason vocabulary, for the async job surface reporting progress, and for the counters computed on `BusinessDayService` rather than UTC midnight — pin the 00:00–05:00 Tashkent case explicitly. Angular specs for the wizard's dry-run diff and result summary, for the search debounce, and for the export truncation flag.

**Traps** `customers-page.ts` has no search debounce and issues one request per keystroke; fix it here. The CSV export is silently capped at 2000 rows with no truncation flag — add one.

### P27 · Reports: the filter bar, the overview and the order log
**Rows** `7.1d` global filter bar (M) · `7.1` KPI counters (L) · `7.1a` funnel with cancel reasons (S) · `7.2` per-stage durations (M) · `7.2a` commercial order log (L) · `7.2b` daily operations (M) · `7.2c` daily summaries (S) — **2d**, after `P02` and `P07`.

Sequenced behind `P07`, which extracts `reports-filter-bar` into `shared/ui/` as `q-filter-bar` for the order board. This wave re-points every reports page at the extracted component and deletes the wrapper `P07` left behind; it does not extract it a second time.

The filter bar is not global: `fulfilmentType()` is read in one file and applied client-side over already-fetched rows, four of the five built pages read only `range()`, and the demand tab does not inject the state at all while the bar renders above it. Make every page consume the shared state, push the fulfilment axis down to the query (the backend already groups by it), add a custom range, granularity, branch, channel and legal-entity controls, arrow-key stepping and URL-shareable filter state — everything is in-memory signals today and resets on reload. `channelCodes` exists in the state with no writer and no reader; wire it. Legal entity needs a new request parameter — it exists only as a `groupBy` dimension.

Overview: add a pickup/delivery elapsed-time metric (derivable from `fact_order.seconds_total` + fulfilment type — a registry and endpoint gap, not a data gap), call the already-live `GET /reporting/metrics` so every tile carries the published-formula panel §1.2 requires, and plumb the cancellation cost: `ordering.order_outcomes` is built and written on every cancellation, and `JdbcReportingStore.insertOrderFact` simply never copies `stock_disposition` and `liability_party`, so V0031's two columns stay NULL. Resolve the reason code to its `internal_name` instead of printing a machine code.

Order log: add `Филиал`, `Предзаказ` and the public order number (the table prints eight characters of a UUID), a column chooser, saved views and cursor paging, and stop discarding the server's `maybeMore` on the stages tab. Split the stage clocks correctly — `seconds_to_ready` is CONFIRMED→READY, wider than the spec's «Приготовлен», and the branch-acceptance gap has no column at all, so eleven minutes of waiting reads as cooking. Add a per-aggregator column to «Посуточно» by asking for `groupBy=['CHANNEL']` — no new endpoint needed.

**Capabilities** `REPORTING_READ` at `TENANT` scope throughout. The `ORDER_READ` log that answers the CRM half of `7.2a` lives outside the reporting module and keeps `ORDER_READ` — do not widen `REPORTING_READ` to cover it.

**Tests** Java tests that the fulfilment axis is applied in the query rather than client-side, that a money metric grouped without `LEGAL_ENTITY` on a two-entity tenant surfaces `CombinedEntityTotalException` as a handled error rather than an errored page, and that `insertOrderFact` now copies `stock_disposition` and `liability_party`. Angular specs for URL-shareable filter state, for every page consuming the shared state, and for the stage clocks splitting branch acceptance from cooking.

**Traps** any money metric grouped without `LEGAL_ENTITY` throws `CombinedEntityTotalException` for a two-entity tenant and errors the whole page. The CRM half of `7.2a` cannot come from reporting — `SubjectPseudonym` exists to prevent that linkage; it needs an `ORDER_READ` log outside the reporting module.

### P28 · The export centre
**Rows** `7.2e` Excel/CSV export as an audited PII egress (XL) — **2d**, after `P27`.

Nobody can take a single figure out of Reports. The Export button is hard-coded `disabled`, there is no `/statistics/exports` route, no `POST /reporting/exports`, no `GET /reporting/reports/{id}`, no `reporting.report_exports` table, and `Capability` registers only `REPORTING_READ` — `report.export` and `customer.pii.export` do not exist. ADR 0043 puts this last deliberately, behind capabilities and an audited job queue; it is still a pilot row because ADR 0029 relies on it to catch a bulk egress.

Build the whole chain: the two capabilities; a `reporting.report_exports` migration with the job state, the requested columns, the row quota and the resulting artefact reference; an async export service writing through the existing media lifecycle; `POST /reporting/exports` and `GET /reporting/reports/{id}` under `report.export`; an `AuditFact` per export recording who exported what, with which filters, how many rows and which PII column group — the control ADR 0029 needs; and the export centre screen plus the per-report trigger, including a column chooser whose PII group is separately gated on `customer.pii.export`.

Model the job on what already works: the customers export (`CustomerListQueryService.exportFiltered`) decrypts behind exactly one `customer.list.exported` audit fact carrying `revealedCount` and the filters — reuse that shape, and fix its silent 2000-row truncation while you are in it so a marketer stops receiving a quietly cut set.

**ADR** ADR 0043's export line and ADR 0029's egress checklist.

**Migration** yes — reserved numbers **`V0235`–`V0237`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `reporting.report_exports`.

**Capabilities** two new constants: `report.export` at `TENANT` scope for the job, and `customer.pii.export` additionally for the PII column group. Neither exists today — `Capability` registers only `REPORTING_READ` — so register both in this wave with their sentences.

**Tests** Java tests against the migrated schema for the row quota, for the audit fact recording who exported what with which filters and which PII column group, for a principal holding `report.export` but not `customer.pii.export` receiving a file with the PII columns **omitted rather than a 403**, and for the customers export no longer truncating silently at 2000 rows. An Angular spec for the column chooser hiding the PII group when the capability is absent.

### P29 · Finance: payments and fiscal, per order
**Rows** `8.1` payments and settlements (M) · `8.2` fiscal receipts (M) — **1d**, after nothing.

Both screens are real and both are unreachable from the thing an operator actually has: an order number. `payments-page` takes a raw UUID in a text box; the fiscal blocked queue prints `document.orderId` as a mono UUID with no link. Add lookup by public order number on both — and a payment-status column on the order queue, which `P04` puts on the wire.

Payments: replace the free-text 48-character `reasonCode` with the provider-mappable vocabulary a void needs, localize the raw enum tokens (`orderStatus`, `intent.status`, `attempt.status` print unlocalized while every other status on the screen goes through `finance-labels`), and wire `POST /orders/{orderId}/future-discounts` — the third `RemedyType` exists, its path helper exists, and `payments-api.ts` narrows the form to two kinds so the endpoint is unreachable.

Fiscal: call `FiscalApi.forOrder` — it wraps `GET /fiscal/orders/{orderId}/documents` and has no caller, so a FAILED or unissued document outside the blocked worklist cannot be inspected or retried from any screen. Add `reasonNote`, `submittedAt` and `reportingDeadlineAt` to the queue (all on the wire) so a `PROVIDER_REPORT_OVERDUE` row can be triaged by urgency, and add an order-number projection to `BlockedDocumentResponse` — the response record carries no display number, so even a fully wired screen could not tie a row back to an order without it.

**Known limits to state, not fix**: delivery-fee reimbursements record a null basis because `DeliveryFeeBasisPort` is unwired; granted future discounts can never be redeemed because ADR 0018 pricing does not call `RemedyEntitlementPort`; the unverified-attestation worklist has no mechanical discharge path because no settlement-file import exists. Each is an ADR 0048/0013 checklist line, not this wave's work.

**Capabilities** `PAYMENT_READ` and `PAYMENT_VOID`/`PAYMENT_REPRESENT` on the payments screen; `FISCAL_DOCUMENT_READ` and `FISCAL_DOCUMENT_RETRY` on the fiscal queue; `ORDER_READ` for the new lookup-by-order-number.

**Tests** Angular specs for lookup by public order number on both screens, for the localized status labels, and for the third `RemedyType` reaching `future-discounts`. Java tests for the order-number projection on `BlockedDocumentResponse` and for `FiscalApi.forOrder`'s endpoint under a location-scoped principal.

**Traps** the fiscal evidence itself — receipt URL, fiscal sign, INN, marking codes — is `8/X.2` and is blocked on a legal input. Do not render a partial receipt.

### P39 · Payment mix and the tender fact
**Rows** `7.1c` business overview — payment mix (L) — **2d**, after `P33`.

The one figure a restaurant uses for cash-collection control is absent from every report, and the business overview renders a hard-coded locked card for it. This is not a screen gap: the chain is missing from the source column up.

`reporting.fact_order_tender` was deliberately not created (V0031 header line 9) and `ordering.orders` stores no payment method. The tender data does exist further down — `payments.order_settlements` and `payments.tenders` are populated in production by `CheckoutSettlementPlanner`, and V0048 records how much of a tender was refunded — so the work is a projection, not a capture.

Build: the `fact_order_tender` migration at the ADR 0043 grain; a producer inside `DayCloseService`'s own transaction, next to the existing order-fact write, reading the settlement's tenders; a `Dimension.PAYMENT_METHOD` in `Grain`; a registered `payment_mix.*` metric (the registry is append-only — add, never edit); and the overview card plus a per-branch split so `7.3b`'s cash-reconciliation half can be answered from the branch report. The reports filter bar already renders a locked «Payment type» chip naming exactly this blocker — unlock it in the same wave.

Sequenced after `P33` because the method vocabulary has to be a registry before a fact can carry it: `payments.payment_methods` exists but rows are created lazily at first checkout, so a mix keyed on it before `P33` seeds and lists the registry would be keyed on whatever happened to be tendered.

**ADR** ADR 0043 metric registration, plus a note on ADR 0013.

**Migration** yes — reserved numbers **`V0238`–`V0240`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `reporting.fact_order_tender`.

**Capabilities** `REPORTING_READ` for the mix; the fact producer runs inside `DayCloseService` as a system principal and needs none.

**Tests** a Java test that a split-tender order produces two `fact_order_tender` rows summing to the order total and that a refunded tender nets out; a `MetricRegistry` drift test for the new `payment_mix.*` definitions; and a test that the producer runs inside `DayCloseService`'s own transaction, so a failed fact rolls back the close rather than half-writing it.

**Traps** do not read `payments.*` from a report — ADR 0023 forbids a report reading a module schema, which is why the fact is the required path.

### P30 · Staff: gated navigation, locked versus denied
**Rows** `9.1` accounts and grants (S) · `9.1c` permission-gated navigation (M) · `9.1d` locked-by-plan versus denied-by-permission (M) — **1d**, after nothing.

Every operator sees all fourteen rail sections and discovers what she may not do by clicking into a screen and being refused — the opposite of the legacy dashboard, which hid what you had no rights to. The data is already in the browser: `GET /api/v1/session/context` returns per-scope capability lists and `core/auth/session-context.ts` reads them for usability only. Add a `capability` field to `NavItem`, filter in `Shell`, and add a capability-aware route guard beside `auth.guard.ts`, which today asks only «is anybody signed in». The sibling console already does exactly this (`frontend/control-plane/.../sections.ts` plus `console-shell.ts:47-49`) — port the pattern, and reuse `features/staff/scope-coverage.ts`, which already ports `ResourceScope.covers` client-side.

Then make refusal legible. `ENTITLEMENT_REQUIRED` and `INSUFFICIENT_CAPABILITY` are distinct error codes that both fall through `describeApiError` to flat one-line sentences. Use `P01`'s `q-denied-state` (naming the capability and who can grant it) and `q-locked-state` (naming the module). The buy CTA cannot be wired — there is no tenant-scoped purchase endpoint, all of `CommercialModuleController` is platform-scoped — so render the lock without a CTA and say why in the brief's follow-up, not in the UI.

`9.1` itself contributes two items: capability **search** on the Должности screen, which is frontend-only over the existing `capability-sentences.ts` table (the platform's own `CapabilityRegistryController` is `PLATFORM_ADMIN`-gated and unreachable), and the dead holder-count button at `staff-roles-page.html:35-37` that looks actionable and only stops propagation.

**Known limit to record, not fix**: every grant endpoint demands `IAM_GRANT_MANAGE` at TENANT scope, held only by tenant-owner and tenant-admin, so «the Chilonzor manager sees Chilonzor's team» is unreachable by the person it was designed for. That is an ADR 0025 scope decision, not a screen fix — raise it, do not work around it.

**Capabilities** this wave *reads* capabilities rather than adding one: the rail filter and the route guard consume `GET /api/v1/session/context`'s per-scope lists. The capability **search** on Должности is frontend-only over `capability-sentences.ts`; do not call `CapabilityRegistryController`, which is `PLATFORM_ADMIN`-gated.

**Tests** Angular specs for the rail hiding a section whose capability the session context lacks, for the route guard refusing a direct URL to that section, for `ENTITLEMENT_REQUIRED` rendering `q-locked-state` and `INSUFFICIENT_CAPABILITY` rendering `q-denied-state` with the capability named, and for capability search. A spec asserting the lock renders **without** a buy CTA.

### P31 · Settings: the scope bar, readiness and support visits
**Rows** `10/X.1` scope bar + InheritedField (XL) · `10.0` settings home and «find a setting» (M) · `10/X.3` support visits (S) — **2d**, after nothing.

The pattern every settings row renders through does not exist. ADR 0030's resolver, precedence, explicit-null and resolution trace are all built and filed under `built/` — and the only HTTP surface is `ConfigurationController` at `/api/v1/control-plane/configuration/**`, every method `PLATFORM_ADMIN` at PLATFORM scope. So a merchant cannot set anything at brand level and override it for one branch, cannot see where a value came from, and cannot tell which level they are about to change: the single worst settings mistake the spec names.

Build an operations-surface configuration API — read, write and resolution-trace over `ConfigurationResolver`/`ConfigurationValueAuthor` under the two new capabilities named below, which is what `ConfigurationController`'s own Javadoc anticipates when it says tenant self-service «would need its own narrower capability and its own conversation about who holds it» — then the §1.1 scope bar (brand picker, location picker, a «level being edited» readout, `?brand=`/`?location=` query state) and the §1.2 `q-inherited-field` wrapper with its trace popover. `settings-shell.html` prints a raw UUID pair today.

Its first consumer, the order policy cards, moved to `P46` on size — `10.3b` is XL and would have taken this wave to nineteen size-points in two days. `P46` runs straight after and is the acceptance test for everything built here: if a card cannot be authored at brand level and overridden for one branch through the surface this wave ships, the surface is not finished.

`10.0`'s readiness panel does not need a new cross-module aggregate: `OnboardingController.validate` dry-runs every validating-phase check against current configuration under `TENANT_READ` and already emits `NO_LEGAL_ENTITY` and `NO_MERCHANT_BINDING`. Reshape it into a countable, deep-linkable list (it returns on the first offending location today) and add the `/` search over the nav items.

`10/X.3` is a two-line template fix: support visits declares `principalSubject` and `ticketReference` and renders neither, so «who from support entered this account» is unanswered. Add the labels and the missing `.spec.ts`.

**Capabilities** two new constants, because `ConfigurationController`'s own Javadoc says tenant self-service «would need its own narrower capability»: `TENANT_CONFIGURATION_READ` (`tenant.configuration.read`) and `TENANT_CONFIGURATION_WRITE` (`tenant.configuration.write`), both at `TENANT` scope and both filtered to `ConfigurationKey#tenantVisible()` entries — a platform default a tenant may never see is not one it may author. `TENANT_READ` already covers `OnboardingController.validate`.

**Tests** Java tests against the operations configuration API for read, write and resolution trace at each of `TENANT`/`BRAND`/`LOCATION`; that a key without `tenantVisible()` is absent from the list and refused on write; that explicit-null overrides resolve as ADR 0030 specifies; and that `PLATFORM_ADMIN`-only keys stay unreachable. Angular specs for the scope bar's `?brand=`/`?location=` query state, for `q-inherited-field`'s trace popover, and for the readiness list counting **every** offending location rather than returning on the first.

### P46 · Settings: order policy cards and catalog base settings
**Rows** `10.3b` order policy cards 2–5 (XL) · `4.4d` catalog base settings (M) — **2d**, after `P31`.

`P31` builds the scope bar and the operations configuration API; this wave is its first
consumer, and it is two card sets rather than one because both are the same work: register an
ADR 0030 key, give it a default, expose it through the new surface, and render it in an
inherited field.

**Order policy cards 2–5** need registry entries that do not exist: business-day start,
average and maximum order time, the late threshold, minimum order sum, VAT, routing poll
interval, pre-order branch resolution, operator promo-code permission, the auto-accept
eligible-channel set and the minimum-prior-successful-orders gate. Card 1 already proves the
pattern end to end — extend it to `TENANT` and `LOCATION` scope, which the controller already
accepts and only `order-policy-api.ts` prevents by hard-coding `brandId`.

**Catalog base settings** (`4.4d`) is the same shape and has no row-level backend of its own:
two tenant-wide switches with no registered key anywhere. `catalog.use_stock_logic` turns
quantity tracking on for the whole company — today the only expression is a per-variant,
per-location `TrackingMode` argument, which is unusable on a 600-item catalogue, and `QUANTITY`
still throws `UnsupportedTrackingModeException`, so the switch must surface that refusal in
operator language rather than a 500. `catalog.qr_kiosk_price_plane` says «QR and kiosk sell at
hall prices», which is what stops those two channels either needing a full price plane authored
by hand or silently taking the wrong one.

**Capabilities** `TENANT_CONFIGURATION_READ`/`TENANT_CONFIGURATION_WRITE` from `P31` for every card, at whichever of `TENANT`/`BRAND`/`LOCATION` the scope bar is pointing at.

**Tests** a registry drift test naming every new key and its default; Java tests that each card writes at the scope the bar is pointing at and that card 1's existing TENANT and LOCATION support is now reachable; and a test that `catalog.use_stock_logic` turning on quantity tracking surfaces `UnsupportedTrackingModeException` as an operator-legible refusal rather than a 500.

**Traps** `4.4d` is a settings row, not a catalog row: it must not grow into `4.4c` per-item
stock quantity, which is deferred behind an ADR 0017 amendment. Registering a key whose
enforcement does not exist is worse than no key — if `use_stock_logic` cannot be honoured yet,
ship it disabled with the reason on the card.

### P32 · Settings: brand, languages and the branch list
**Rows** `10.1` brand profile (L) · `10.12` languages and regional formats (M) · `10.2a` branch list (M) · `10.2b` location tab 1 (L) — **2d**, after `P02` and `P31`.

One folder, four rows, and a live data-loss bug to fix first. Hours, prep bands and the order limit split into `P43`; the floor-plan tab moved to `P38`, which owns `X.36`'s canvas — building the floor plan in two concurrent waves was the worst collision in the first draft of this plan. `PUT .../locations/{id}/place` is a whole-place write: `DescribeLocationCommand.toPlace()` nulls the point when latitude is absent and defaults `coordinateSource` to `NOT_GEOCODED`, and `savePlace()` sends only address line, district, city and phone — so **every address or phone edit from the console silently erases the branch's map pin and landmark**, and ADR 0037 originates delivery zones from that point. Carry the existing coordinates and landmark through, add `draftLandmark` (the column, the command field and the DTO field all exist), and add a spec that asserts it.

Brand profile is a read-out of four of thirteen fields. Renaming is already built and shipped — `TenantControlPlaneService.reviseBrand` behind `PUT /control-plane/tenants/{t}/brands/{brandId}` with `BRAND_WRITE` and `If-Match`, used by the control-plane console — so the trade name needs an operations route and a form, not a backend. Logo, aggregator/QR banner, localized description, contact phone, Telegram handle and supported languages have **no columns at all** (`tenant.brands` is code/slug/display_name/status) and no logo or banner concept exists anywhere; add them with the media relation and `10.12`'s per-brand supported-locale set in the same migration, since `platform.default_locale` was deleted from the registry on 2026-09-10 and the console's locale list is a frontend constant mirrored by a hard-coded server list.

Locations: add the branch-list columns and filters (state, reason, channels, INN) over a brand-scoped batch read of `location_service_state` — per-row today is an N+1 — plus the close/open action from the row and a bulk bar; and sort order, tags and venue attributes as new columns.

**Migration** yes — reserved numbers **`V0241`–`V0243`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: brand logo, banner, localized description, contact phone, Telegram handle; the per-brand supported-locale set; the brand media relation.

**Capabilities** `BRAND_WRITE` with `If-Match` for the brand revision (already enforced by `TenantControlPlaneService.reviseBrand`); `LOCATION_READ` for the branch list and the batch service-state read; `LOCATION_MANAGE` for the place write, the close/open action and the new columns.

**Tests** a Java test — the one this wave exists for — that a phone-only or address-only `PUT .../locations/{id}/place` **preserves** the existing point, `coordinateSource` and landmark; plus tests for the brand revision under `If-Match`, for the new brand columns and the media relation, and for the per-brand supported-locale set replacing the hard-coded server list. Angular specs for the branch-list filters over the batch service-state read and for the close/open row action.

**Traps** the place write is the whole reason this wave is sequenced first inside its folder: fix it before adding a single column, because every later editor in `P43` and `P38` saves through the same screen.

### P43 · Settings: hours, prep bands and the order limit
**Rows** `10.2c` hours, prep bands, order limit (L) — **1d**, after `P32`.

Split out of `P32` on size, and sequenced behind it because both write
`features/settings/locations/`.

Three editors over endpoints that already answer. The **hours and holiday-exception**
editors use `P02`'s `q-schedule-grid` over the existing `PUT /service-schedules/{id}/rules`
and `/exceptions`, binding to the `{dayOfWeek, opensAt, closesAt}` plus dated-exception shape
`ServiceSummaryResponse` already returns. The **preparation-band** editor goes over the unused
`PUT /preparation-bands`. The **order limit** is an ADR 0030 key authored through `P31`'s
configuration surface at location scope.

Two corrections this wave owes. `ServiceScheduleController` has **no `GET`**, so rebinding a
fulfilment mode to a different timetable has no picker source — add one. And a schedule can
be shared: `sharedWithLocationCount` is on the wire, so editing one branch's hours can silently
change another's. Warn before saving, naming the count.

**Capabilities** `LOCATION_MANAGE` for the schedule rules, the dated exceptions and the preparation bands; `LOCATION_READ` for the new `GET` on `ServiceScheduleController`.

**Tests** Java tests for the schedule rules and dated exceptions written through `P02`'s grid, for the new `GET` on `ServiceScheduleController`, and for the preparation-band write. An Angular spec that editing a schedule with `sharedWithLocationCount > 1` warns before saving.

**Traps** the day window a schedule expresses is the location's, not the browser's — `W01`
depends on this being readable and correct, and gets it wrong today for exactly that reason.

### P33 · Settings: channels, the capability matrix and payment methods
**Rows** `10.4a` channel registry (L) · `10.4b` capability matrix (M) · `10.6` payment-method registry (L) — **2d**, after `P03` and `P31`.

These three are one wave because the matrix cannot be correct until the registry exists, and today the matrix ships a **live 500**: since V0175 the FK on `tenant.channel_payment_methods.payment_method_code` is enforced, `replacePaymentMethods` inserts a row for every key in the map, and the page sends a hard-coded `['CASH','CLICK','PAYME']` — so on a tenant whose `payments.payment_methods` lacks one of them, toggling raises an untranslated `DataIntegrityViolationException`. `replaceLocations` catches and explains its FK violation; this path does not.

`10.6` first: `payments.payment_methods` (V0042) has no controller on any surface — rows are created lazily by `JdbcSettlementStore.registerMethod` at first checkout, so «the registry only ever grows by accident». Add create, rename, localize, icon, order, activate and disable; a seeding path at onboarding (ADR 0038's checklist line «seed each tenant's methods» is still unticked); the acquirer-installation binding (`provider_installation_id`, `contract_reference` and a localized-name table are unbuilt); and a tenant-scoped `GET` so the matrix columns come from the registry instead of a frontend constant.

`10.4a`: seven of the eleven spec columns are absent although `ChannelView` already carries the data (branches, payment-method count, order types, price plane, externally-priced, guest orders, installation). Add them, plus an edit endpoint (none exists — nothing can be changed after create), the `ACTIVE→INACTIVE` transition (the status value is declared and unreachable), filters and the severity sort, and the branch-serviceability write (`PUT /{channelId}/locations` exists, its path helper exists, and `matrices().locationIds` is fetched and dropped).

`10.4b`: render it as `P03`'s `q-matrix-grid` — rows are channels, so a cross-channel comparison is possible — with row and column bulk toggles, shift range-select, hatched cells for a method the channel cannot fiscalise, a confirmation before turning off the last enabled method on an active channel, and the channel × location plane that is fully backed and entirely unrendered.

**Migration** yes — reserved numbers **`V0244`–`V0246`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: payment-method localized names, `provider_installation_id`, `contract_reference`.

**Capabilities** `SALES_CHANNEL_MANAGE` for the registry edit, the status transition and the matrix writes; `SALES_CHANNEL_READ` for the list; `PAYMENT_METHOD_MANAGE` for the new registry (register it — `payments.payment_methods` has no controller on any surface, so it has no capability either) and `PAYMENT_METHOD_READ` for the tenant-scoped `GET` the matrix columns read.

**Tests** the regression this wave owes: a Java test that toggling a payment method absent from `payments.payment_methods` returns an operator-legible refusal rather than an untranslated `DataIntegrityViolationException` — the live 500. Plus tests for the registry CRUD, the onboarding seed, the acquirer binding, the channel edit endpoint, the `ACTIVE→INACTIVE` transition and the branch-serviceability write. Angular specs for the matrix's bulk toggles, the hatched non-fiscalisable cell, and the confirmation before disabling the last enabled method on an active channel.

**Traps** icons, brand colours and social links belong to `10.5`, not here; the three per-channel order-entry toggles belong to the call-centre row. Delete the `PROVISIONAL_PAYMENT_METHODS` comment claiming no registry exists «until ADR 0038 lands».

### P34 · Settings: fiscalization
**Rows** `10.7a` legal entities (S) · `10.7b` fiscal terminals (L) · `10.7c` product classification coverage (L) — **2d**, after `P31`.

Tab 1 works and has two rough edges plus four missing fields: move the app off the control-plane path onto `OperationsLegalEntityController`, which publishes the identical six operations at `/api/v1/operations/tenants/{tenantId}/legal-entities`; expose suspend and archive, which exist in `LegalEntityService` and have no `@PostMapping` on either controller, so a retired company can only be left ACTIVE; add short name, VAT certificate reference, tax profile, registered address and contact phone with an edit form (there is none, so a registered entity can never be corrected); and render the assignment table with its effective window and the ADR 0027 approval reference the API already accepts.

Tab 2 is a pilot blocker with nothing beneath it. Create `fiscal.fiscal_terminals` — kind `POS|COURIER_TERMINAL|KIOSK|VIRTUAL`, location, legal entity, provider binding, terminal reference, capability snapshot, status, last health check, sketched at ADR 0038 lines 503-513 and never migrated — plus a store, a service and an operations controller for register, list and health. Until it exists `CheckoutSettlementPlanner.responsibilityOf()` cannot declare CASH as `TERMINAL` (it registers `OPERATOR` and the file flags that as wrong) and ADR 0038's activation precondition «cash requires a fiscal-capable terminal bound to the location» has nowhere to run.

Tab 3 is the coverage view `P21`'s workbench serves: a per-brand count of unclassified priceable nodes with the node list by type, name, category and offering breadth, ordered by how many locations sell them; the delivery-fee node's own ИКПУ and package code (the `catalog.fees` DELIVERY row and its `PUT .../fees/{feeCode}/fiscal-classification` already exist and have no caller); the marking-enablement control (storage exists, UI does not); and the read-only VAT-defaults projection.

**Migration** yes — reserved numbers **`V0247`–`V0249`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `fiscal.fiscal_terminals`; legal-entity short name, VAT certificate, tax profile, registered address, contact phone.

**Capabilities** `LEGAL_ENTITY_READ`/`LEGAL_ENTITY_MANAGE` for tab 1 including the new suspend and archive; a new `FISCAL_TERMINAL_MANAGE` for register/health and `FISCAL_TERMINAL_READ` for the list; `CATALOG_READ` for the coverage view and `CATALOG_AUTHOR` for the fee node's classification.

**Tests** Java tests against the migrated schema for `fiscal.fiscal_terminals` register/list/health, for `CheckoutSettlementPlanner.responsibilityOf()` declaring CASH as `TERMINAL` once a capable terminal is bound, for ADR 0038's activation precondition refusing a location with no terminal, and for legal-entity suspend and archive. Angular specs for the edit form and for the coverage view ordering nodes by offering breadth.

**Traps** settings.md lines 771-777 and 1400 state that `fiscal.fiscal_terminals` already exists. It does not; fix the spec in this wave. `FiscalReferenceController` is `PLATFORM`-scoped, so no tenant operator can search the ИКПУ reference — re-scope it or add a tenant alias, as `P22` needs too.

### P35 · Settings: integrations
**Rows** `10.8a` install hub, secret door, bindings (M) · `10.8c` health and errors (L) · `10.8d` partner API credentials (M) · `10.8e` analytics installs and the GA4 event contract (L) · `X.14` SecretInput (S) — **2d**, after `P31`.

The backend is ahead of the screen, and the screen's own doc comment says the opposite. Five operations-surface endpoints have no frontend caller at all: `GET /{installationId}/bindings` (landed 2026-09-10, real SQL, and its path helper already exists in `settings-paths.ts:215` with only a `POST` caller), `POST /bindings/{id}/activate` and `/suspend` — the controller documents bindings as «created suspended», so the console creates bindings it can never bring live — `POST /{installationId}/capability-reconciliation`, and `GET`/`POST /{installationId}/settings`, whose Javadoc names this very screen as its caller. Wire all five, and the merchant-binding activate/suspend the UI also never calls.

Build `q-secret-input` in `shared/ui/` — three bare `type="password"` inputs do the job today — with masked presence, a reveal-once **at entry** (never a readback: the secret door is write-only by ADR 0028/0065 and no surface returns a stored value; a spec promising reveal-once for a *stored* credential contradicts the security model. Two documents need the qualifier and one already has it: rewrite `platform/docs/frontend-and-parity-plan.md:67` («`SecretInput` — masked, reveal-once, rotate») and `platform/docs/frontend-information-architecture.md:342` to say **reveal-once at entry**, and leave `platform/docs/operations-spec/settings.md:900` alone — it already reads «masked, reveal-once at entry and never re-rendered», which is the correct sentence to copy), copy, rotate and slots for last-rotated and last-used. Last-used has no column, no field and no endpoint anywhere — that is the row's one genuine backend gap.

`10.8c`: a merchant cannot see that their aggregator stopped pushing orders two hours ago. `GET /api/v1/operations/tenants/{tenantId}/marketplace/liveness` is built and has zero callers — render it — then add the two tenant-scoped reads that do not exist: an operator-legible error taxonomy and a merchant-scoped replay, both of which live only at `/api/v1/control-plane/**` under PLATFORM scope today. Add liveness watermarks for POS, fiscal and notification providers, not only marketplace.

`10.8d`: `partner.api_clients` exists with rotation-pair and expiry constraints and `PartnerOrderController` authenticates against it, and no controller issues, lists, rotates or revokes a client — nor does anything create the Keycloak confidential client. Build all of it; this is the row that replaces `base64(login:password)`.

`10.8e` is new to this map and is two unequal halves. **Telephony is nearly free**: `ProviderCategory.VOICE` and `VoiceProviderCapabilityCatalog` already exist (ADR 0064), so a telephony install is just another card in the hub this wave builds. **Analytics has no model at all**: `ProviderCategory` declares POS, PAYMENT, DELIVERY, MARKETPLACE, NOTIFICATION, GEOCODING and VOICE and no `ANALYTICS`, so a GTM container id, a GA4 property or a Search Console verification token cannot be stored against a tenant anywhere. Add the category, the install shape (a container id or measurement id plus the verification token, no secret) and the per-brand storefront injection. Today `frontend/storefront/src/index.html:58-68` carries a **commented-out** Yandex Metrika counter with one hard-coded platform-wide id — so the analytics that exists is both switched off and, switched on, the wrong tenant's.

The **GA4 ecommerce event contract** is the larger half and is a producer, not a settings row: a named, versioned event set (`view_item`, `add_to_cart`, `begin_checkout`, `purchase`) that the storefront emits into the tenant's own `dataLayer`. `dataLayer` appears exactly once in the whole storefront source, inside that dead comment, so nothing emits anything today. Version the contract and write it down — an unversioned event set is what makes a merchant's year-on-year comparison meaningless the first time a field is renamed. The tenant's own property is the tenant's own lawful basis, which is why this does not inherit `7.6c`'s legal block; do **not** let it grow into first-party behavioural telemetry, which does.

**Migration** yes — reserved numbers **`V0250`–`V0252`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: partner API client last-used; provider-installation last-used watermarks.

**Capabilities** `INTEGRATION_INSTALLATION_MANAGE` for bindings, activate/suspend, reconciliation and settings; `INTEGRATION_INSTALLATION_READ` for the hub and the liveness reads; a new `PARTNER_API_CLIENT_MANAGE` for issue/list/rotate/revoke, which must be separate from the installation capability — a partner credential is a different blast radius. Analytics installs reuse the installation pair once `ProviderCategory.ANALYTICS` exists.

**Tests** Angular specs for all five previously uncalled endpoints, including creating a binding and then activating it — the path the console cannot complete today. Java tests against the migrated schema for the partner API client issue/rotate/revoke pair with its expiry constraint, for the Keycloak confidential client being created and cleaned up, for the last-used column being written on use, and for the tenant-scoped error taxonomy and replay refusing another tenant's installation.

**Traps** the only real backend gap on `10.8a` is the absent `RETIRED` transition, which ADR 0065 assigns to owner and platform, not to a frontend task. Do not put an analytics measurement id behind the secret door: it is a public identifier, and hiding it teaches operators that the mask means nothing.

### P36 · Settings: notifications
**Rows** `10.9a` order-status and OTP templates (L) · `10.9b` Telegram routing (L) · `10.9c` provider moderation state (S) · `10.9d` payment-link auto-send and shift notifications (M) · `X.27` TemplateEditor with VariableChip (M) — **2d**, after `P02` and `P31`.

One folder, four rows and a silent failure mode. `NotificationTemplateService.addVersion` stamps a new SMS wording `PENDING` when the gateway requires moderation, `NotificationEligibilityService` withholds every `PENDING`/`REJECTED` send, and the screen activates immediately by default — so a merchant publishes an OTP wording, watches it go ACTIVE, and every message is suppressed with nothing anywhere saying so. The state is exposed only at `/api/v1/control-plane/template-reviews`. Add `provider_review` (plus note, reference and timestamp) to the tenant-facing `WordingResponse` — `JdbcTemplateStore` already selects it — render the state and the two suppression reasons, and warn at publish time.

The editor is create-only: `Изменить` (add version) and `Активировать` exist as API methods reachable only inside the create flow, and `notificationTemplateVersion` has no call site at all, so an author cannot read back the wording of a template that already exists. Add row actions, a version list, locale-completeness chips, the moderation column, a test send, and the sort the spec asks for. Build `q-template-editor` with VariableChip insertion and a live `q-phone-frame` preview — and fix the defect underneath it: the page hard-codes `variablesSchema: {}` on every version, and `TemplateRenderer.validate` refuses any placeholder not in the declared schema, so **the editor cannot author a variable-bearing template at all**. Publish a per-notification-class variable catalogue (none exists) and return the stored schema on `GET`.

Tab 2 is not built: no chat linking, no topic id, no per-event-class routing rows, no test send, no brand→location origin chip and no quiet-hours control, although the key is registered. Add the admin API over ADR 0058's bindings — list, set which event classes a chat receives (only a hard-coded `DEFAULT_SUBSCRIPTIONS` set exists), change topic, unbind — and the screen. `10.9d` adds the payment-link auto-send switch and the aggregator shift open/close notification in the tenant's timezone; neither has a configuration key today.

**Migration** yes — reserved numbers **`V0253`–`V0255`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: the per-version variables schema; the template unique index widened past `(tenant, brand, key, channel)`.

**Capabilities** `NOTIFICATION_TEMPLATE_MANAGE` for versions, activation and the test send; `NOTIFICATION_TEMPLATE_READ` for the list, the version history and the moderation state; `NOTIFICATION_ROUTING_MANAGE` for the Telegram bindings. The moderation state is *read* on the tenant surface — approving it stays platform-side.

**Tests** a Java test that a version stamped `PENDING` is withheld by `NotificationEligibilityService` and that `provider_review` reaches the tenant-facing `WordingResponse`; a test that a template declaring a variables schema renders while one with `{}` and a placeholder is refused by `TemplateRenderer.validate` — the defect that makes the editor unusable; and tests for the Telegram binding list, per-event-class routing and unbind. Angular specs for the version list, the moderation column, the test send and the publish-time warning.

**Traps** the channel dropdown offers EMAIL and PUSH for which `isWired()` is false. Order type and source as key dimensions need a schema change — the unique index is `(tenant, brand, template_key, channel)`.

### P37 · Settings: reference data
**Rows** `10.10a` cancellation and completion reasons (S) · `10.10b` business calendar (L) · `10.10c` SLA bucket boundaries (M) · `10.10d` branch tags (M) — **2d**, after `P31`.

`10.10a` reads as finished and is not: the API exposes `list`, `categories`, `create` and `archive` and **no update**, although `PUT /{reasonId}` exists and returns a version. An operator cannot rename a reason, fix its customer text, or change its stock disposition, liability party or default refund after creation — the only recourse is archive-and-recreate. Add the edit path with the spec's «this creates a new version» warning. Add the `allowedFulfillmentModes` control, which the spec calls «the thing Delever lacks» and which is typed on both sides and rendered nowhere — it is what stops «Самовывоз выполнен» landing on a delivery order. Render the system category, default refund and status in the rows, and move the screen onto an operations surface (the pilot-blocker list names this one by name).

`10.10b`: a tenant cannot enter its own holidays or movable Islamic dates, declare its weekend, or set a business day that closes after midnight, so every report cuts the day at the platform's assumption. A platform-level per-country holiday list exists (ADR 0090, Uzbekistan seeded) and `reporting.business_day_policies` plus `BusinessDayService` exist with **no production caller for `setBoundary`**. Add the tenant-facing calendar and boundary editor, and surface the resolved `businessDayStart` read-only in the reports provenance banner.

`10.10c` is not blocked and is not what the IA promises. ADR 0043 decided that SLA buckets are platform-fixed and versioned, and says so in the controller's own Javadoc; the IA and settings.md promise tenant-configurable boundaries. Ship the small honest thing — a tenant-readable endpoint for the active bucket set and its version, and a read-only card naming the version the reports were computed under — and raise the contradiction as a doc correction (settings.md lines 1105 and 1325) or a superseding ADR. Do not build configurability in this wave.

`10.10d`: branch tags have no table, no endpoint and no screen; add the registry, the location assignment, and the filter-and-group affordance that makes them worth having.

**Migration** yes — reserved numbers **`V0256`–`V0258`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: branch tags and their location assignment; the tenant business calendar and its boundary.

**Capabilities** `REFERENCE_DATA_MANAGE` for the reason edit, the calendar and the branch tags; `REFERENCE_DATA_READ` for the lists and for the read-only SLA bucket set.

**Tests** Java tests for the reason edit creating a new version, for `allowedFulfillmentModes` refusing «Самовывоз выполнен» on a delivery order, for the tenant calendar and a business day that closes after midnight, and for branch tag assignment. An Angular spec that the SLA bucket card renders the active version read-only and offers no editor.

### P38 · Settings: delivery policy and dine-in QR
**Rows** `10.13` delivery policy (L) · `10.5b` QR dine-in modes and table QR codes (M) · `10.2d` floor plan tab (L) · `X.36` FloorPlanCanvas + TableToken + TimelineScheduler (L) — **2d**, after `P17` and `P31`.

The courier rulebook is readable and unwritable. `CourierPolicyResolver` resolves ADR 0042's document through ADR 0030 and one `GET .../courier-policy` reads it; there is no writer anywhere, because `PolicyAuthor` — the platform's only policy-document writer — has exactly two non-Javadoc consumers and no controller injects it. Add a write endpoint beside the read, then the `/settings/delivery-policy` screen, which does not exist even as a placeholder.

Five of the switches the spec names have no backing field at all and need adding to the policy document: the GPS master toggle with accept radius and status-change radius, show-only-kitchen-ready, reveal-customer-location timing, and the post-delivery payment check. Two corrections to carry: courier billing mode is **refused** by ADR 0042, not missing, and must not be counted as a gap; the telemetry collection gate **is** a registered ADR 0030 key today but is writable only at PLATFORM scope, so it belongs on this screen as «platform-only», not as «no field anywhere». `delivery.out_of_zone_policy` (`REJECT|OFFER_PICKUP|MANUAL_REVIEW`) is not a registered key — register it; the enforcement it switches already exists, since `DeliveryFeeResolver` returns `OUTSIDE_CATCHMENT` today.

The dine-in half is pure wiring over a finished backend: `FloorPlanController`'s `GET`/`PUT /dine-in/settings` persists `qrMode`, and `POST /tables/{tableId}/qr-token-rotations` mints a one-shot token, both audited and `If-Match`-guarded. Add the settings form (turnaround minutes, guest-session TTL, service-charge rate, reason), the per-table issue/rotate action rendering the token as a printable table card through `P17`'s `q-qr-code`, and the revoked-guest-session count. `SETTLE_OPEN_TICKET` is declared non-selectable and refused at both `QrMode.require` and the CHECK constraint — render it disabled with its reason, not as a missing feature.

`X.36` and `10.2d` are the same screen and are built **once, here**. The first draft of this plan had `P32` building a floor-plan tab over `FloorPlanController` while this wave built the canvas under it, both after `P31` and concurrent; `10.2d` now belongs to this wave and `P32` does not touch it.

`FloorPlanController` is complete on the read side — sections, tables, status, QR rotation — and has no caller. What is missing is the write: table coordinates are write-only (`TableRequest` takes `layoutX`/`layoutY`, `TableResponse` omits them) and there is no `PUT /tables/{tableId}`, so drag-to-reposition has nowhere to save. Add the response fields and the endpoint, then the canvas, then the tab that hosts it under Settings → Locations. Sequenced behind `P17` for `q-qr-code`, which prints the table cards.

**Capabilities** `DELIVERY_POLICY_READ` for the resolved courier policy and `DELIVERY_POLICY_WRITE` for the new write endpoint beside it; `DINE_IN_MANAGE` for the QR settings, the table writes and the token rotation, `DINE_IN_READ` for the floor plan. The telemetry gate stays `PLATFORM_ADMIN` and renders as «platform-only».

**Tests** Java tests against the migrated schema for the courier-policy write endpoint and each of the five new fields resolving through ADR 0030; for `PUT /tables/{tableId}` moving a table and `TableResponse` now returning `layoutX`/`layoutY`; and for the QR token rotation staying one-shot under `If-Match`. Angular specs for the floor-plan canvas drag-reposition, for the printable table card, and for `SETTLE_OPEN_TICKET` rendering disabled with its reason.

### T01 · My work
**Rows** `0.2` my work (XL) · `0.2a` personal statistics by channel (M) · `0.2b` revenue by payment method (L) — **2d**, after `P08`.

A signed-in operator has no personal page at all: `/today/my-work` is a `NotBuiltPage` whose spec string names the reason. Build the page against what exists rather than waiting for what does not. `0.2a` needs an actor-grouped read of `ordering.orders` over `created_by_actor_id`/`accepted_by_actor_id` — the columns and `ix_orders_created_by` exist since V0029 and nothing aggregates them — scoped to the caller's own subject, which is the one case that needs no staff directory because the console already knows who it is. `0.2b` rides on `P39`'s tender fact: without it, revenue by payment method cannot be split for anyone. **What «done» means for `0.2` is narrower than the row, and this is the boundary**: `0.2` is the umbrella row for a page that has four named capabilities, two of which — personal data (`0.2c`) and user-scoped personalization (`0.2d`) — are deferred with the staff-identity ADR. This wave discharges `0.2` when the page exists, is reachable at `/today/my-work`, renders `0.2a` and `0.2b`, and carries a `q-locked-state` band naming the staff-identity ADR for the other two. It does **not** discharge `0.2c` or `0.2d`, which keep their own rows and their own entries in PART B. Do not treat an empty profile section as done, and do not build a profile store to make the row look finished.

**Capabilities** `ORDER_READ` at the caller's own scope; the actor-grouped read must be **self-scoped by the token's subject**, not by a request parameter, so no capability can widen it to another operator.

**Tests** Java tests for the actor-grouped read being self-scoped — a request naming another subject must be refused, not merely empty — and for the aggregate using `ix_orders_created_by`. Angular specs for the two statistics bands and for the locked band naming the staff-identity ADR.

**Traps** do not print another principal's subject id; the personal page is the one actor view that is legible today precisely because it is self-scoped.

### T23 · The wallboard shell
**Rows** `0.1e` wallboard presentation of the live board (L) · `X/X.3` wallboard shell (M) — **1d**, after `P15`.

Every read a wallboard needs already answers — counts, the in-progress list and the brand roster — so this is a presentation wave. Add a chrome-less route outside the console `Shell` (a `WallboardShell` peer to `KitchenShell`), a TV-distance type step above the current 42px `.q-display` top of the scale, a `q-wallboard-tile`, an unattended/fullscreen entry (nothing calls `requestFullscreen` anywhere), and a wall-legible freshness state. The staleness stamp is not missing — it exists at caption size, which is the actual defect: a frozen board must be distinguishable from a quiet shift from across the pass.

**Capabilities** `ORDER_READ` and `LOCATION_READ` — the wallboard reads exactly what `P15` already serves and adds no endpoint.

**Tests** Angular specs for the wallboard route rendering outside the console `Shell`, for the fullscreen entry, and for the freshness state being legible at the TV type step rather than at caption size — assert the computed font size, since that *is* the defect.

**Traps** the operator leaderboard band stays a locked note. The TV type step must go into `frontend/design-tokens`, not into a feature stylesheet.

### T02 · Kitchen buffer, expo gate, VDU and capacity
**Rows** `2.2` buffer (M) · `2.3` expo handover (M) · `2.4` display board (M) · `2.6` capacity ceilings (S) — **2d**, after `P16`.

**Expo** is the sharp one: an aggregator order can be handed to the wrong courier because expo presses one unconditional button, while the server-side compare is built, audited and reachable — `POST .../marketplace/orders/{orderId}/handover-verifications` consumes an attempt before comparing and never returns the expected value, and `/handover-bypasses` needs `MARKETPLACE_HANDOVER_BYPASS` plus a reason. Wire both, with attempts-remaining and a supervisor bypass, and add the packing check and the per-department ready roll-up the row claims and the flat station table does not give.

**Buffer** ships one of ADR 0041's three actions. `PUT /kitchen/tickets/{ticketId}/release-schedule` covers both placing a ticket on manual hold and editing its fire time, and has no client; pulling a fire time earlier is an ordinary non-privileged act that is equally unreachable. Paid-only hold is unmodelled on both tiers and stays out until the payment fact that gates it is decided.

**VDU**: the one feature IA 2.4 names is the provider-assigned identifier, and `TicketResponse` carries none — `sequenceLabel` is HorecaOS's own number, so a courier quoting an aggregator code cannot match the wall. Add the external reference to the board response. The device class, station filter and dedicated projection are ADR 0041 rollout step 4 and are a separate, larger item.

**Capacity**: a mistyped ceiling is permanent — there is no update or delete on `KitchenStationController` — and because overlapping windows are refused it also blocks the correct window from ever being authored. Add update and delete. Fix the screen's own copy, which tells a manager the ceiling does nothing while `KitchenTicketService` actually shifts `release_at` on it.

**Capabilities** `KITCHEN_BOARD_READ` for the buffer and the VDU; `MARKETPLACE_HANDOVER_VERIFY` for the expo compare and `MARKETPLACE_HANDOVER_BYPASS` for the supervisor bypass; `KITCHEN_STATION_MANAGE` for the capacity update and delete.

**Tests** Java tests for the handover compare consuming an attempt and returning attempts-remaining, for the bypass requiring `MARKETPLACE_HANDOVER_BYPASS` and a reason, for capacity update and delete (including that deleting an overlapping window unblocks authoring the correct one), and for the external reference reaching `TicketResponse`. Angular specs for the buffer's hold and fire-time edit and for the per-department ready roll-up.

### T03 · Live map and courier policy
**Rows** `3.2` live map (L) · `3.9` courier policy (L) — **1d**, after `P19`.

Positions render as a table of decimal coordinates. The backend is complete — `GET .../operations/couriers/positions` with staleness and accuracy rules, and the audited `POST .../{courierId}/track-reveals` whose path helper exists with zero call sites, so a stored track cannot be opened for a dispute. Render `accuracyMeters`, `headingDegrees` and `speedMps`, which the table drops, add a branch filter, and wire the reveal. The map canvas itself is deferred with `X.4`; partner-courier pins are **not** a gap — ADR 0045 records them as an explicitly rejected alternative.

Courier policy is read-only by construction and its write path lands in `P38`; what belongs here is the screen the spec asks for: the resolution scope selector (tenant → brand → location) so a manager can compare an override against the default, the inherited value beside each overridden field, and the one-sentence consequence line per row. The page reads only `CurrentLocation` and prints a single static «Resolved at» line today, and renders neither `policyId` nor `policyVersion` although both are on the wire.

**Capabilities** `COURIER_POSITION_READ` for the live positions; `COURIER_TRACK_REVEAL` with a stated purpose for the stored-track reveal; `DELIVERY_POLICY_READ` for the resolution-scope selector.

**Tests** Angular specs for `accuracyMeters`, `headingDegrees` and `speedMps` rendering, for the branch filter, and for the track reveal sending a purpose. A Java test that the reveal writes its audit fact. Specs for the courier-policy scope selector showing the inherited value beside an overridden one, and for `policyId`/`policyVersion` rendering.

### T16 · Courier types, rate cards and adjustment rules
**Rows** `3.4a` courier types (S) · `3.4b` rate cards (L) · `3.4c` bonus/penalty rules (L) — **2d**, after `P19`.

Types are create-only at every layer: no update or archive on the store, no `PUT`/`DELETE` endpoint, so a mistyped code or a wrong offer TTL is permanent even though `courier_types.status` already has `ARCHIVED`. Add them, send `maxDistanceMeters` (accepted, enforced by `CourierDispatchGate`, never sent), and add the two attributes with no column anywhere — starting minute and work mode.

Rate cards: the backend accepts all four component types with band ladders, gap and overlap validation at activation and `AccrualCalculator` pricing them; the form emits two. Add the `PER_KM_BAND` ladder editor and `PER_ORDER_MINIMUM`, render a card's components at all (`GET /rate-cards/{cardId}` has no caller, so a manager activates a card whose terms the console will not show him), render effective dates, and allow a second version and a narrower scope — `submitCard` hard-codes `cardVersion: 1` and brand scope, so a code whose v1 is ACTIVE cannot be re-priced.

Rules: there is no rule catalogue. `fulfillment.courier_adjustment_reasons` has kind and outcome basis and no amount, no condition and no evaluation; `insertAdjustmentReason` is called only by tests; and `POST /couriers/{courierId}/adjustments` has **zero callers in the console**, so a manager cannot record even a one-off manual penalty. Build the manual entry form first, then the reason registry, then the rule columns and an evaluator emitting `AdjustmentOrigin.RULE` from delivery outcomes — note that origin is client-supplied today, so a caller sending `RULE` bypasses the four-eyes branch.

**Migration** yes — reserved numbers **`V0259`–`V0261`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: courier-type starting minute and work mode; the adjustment-reason amount and condition columns.

**Capabilities** `COURIER_TYPE_MANAGE` for types; `COURIER_RATECARD_MANAGE` for cards and versions; a new `COURIER_ADJUSTMENT_RECORD` for the manual entry with the ADR 0042 four-eyes branch above threshold — and the evaluator must stamp `AdjustmentOrigin.RULE` **server-side**, never from the request, because a client-supplied `RULE` bypasses four eyes today.

**Tests** Java tests against the migrated schema for type update and archive, for `maxDistanceMeters` being enforced by `CourierDispatchGate`, for a second rate-card version under a narrower scope, for band gap/overlap validation at activation, and — the security one — that `AdjustmentOrigin.RULE` cannot be set from the request and so cannot bypass four eyes. Angular specs for the `PER_KM_BAND` ladder editor and for a card's components rendering before activation.

**Traps** ADR 0042's checklist marks rule adjustments implemented while its own body describes the evaluator as unbuilt; correct it.

### T17 · Shifts, attendance and bulk geozone upload
**Rows** `3.5` shifts and attendance (L) · `3.6c` bulk geozone upload (L) — **1d**, after `P19`.

There is no roster: only the shift a courier opened himself is visible, so planned-versus-actual cannot be compared. V0040 deliberately omits `courier_roster_entries` and `ENFORCED_WITH_ROSTER` appears nowhere in Java. Add the planned-shift model, the roster grid over `P02`'s ScheduleGrid, a period filter (the page fetches the most recent 200 shifts with no `from`/`to`), and render what the view already carries and the template drops: `breakSeconds` and `dutyState` — time on break is the central attendance figure — plus a courier name instead of a raw UUID. The shift-enforcement gate is real in `CourierDispatchGate` and cannot be switched from the console because the policy write lands in `P38`; sequence accordingly.

Bulk geozone upload is more than a file picker: ADR 0037 requires imported legacy geometry to land as DRAFT versions and be rendered on a map beside its source before activation, because a coordinate-order error yields a hemisphere-flipped polygon no containment test catches. So this row needs a batch import endpoint, a review surface, and the shadow comparison — and the review surface needs `X.4`. Ship the endpoint and the dry-run report now; gate activation behind the map.

**Migration** yes — reserved numbers **`V0262`–`V0264`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `courier_roster_entries`.

**Capabilities** `COURIER_SHIFT_READ` for the roster and the period filter, `COURIER_SHIFT_APPROVE` for the planned-shift writes; `DELIVERY_ZONE_MANAGE` for the batch geozone import, whose activation stays behind `DELIVERY_ZONE_ACTIVATE` and the map.

**Tests** Java tests against the migrated schema for the planned-shift model, the `from`/`to` period filter and the roster-versus-actual comparison; and for the batch geozone import landing every polygon as DRAFT and refusing activation without the map. Angular specs for `breakSeconds` and `dutyState` rendering and for a courier name replacing the raw UUID.

### T04 · Publication readiness, price books and bulk pricing
**Rows** `4.6` publication and channel readiness (M) · `4.8a` price books (M) · `4.8b` bulk price change (L) — **2d**, after `P03`.

The readiness report is a histogram, not a report: `findingsByCode()` collapses every finding to `{code, severity, count}` and the template renders «PRICE_MISSING ×12» — while `entityType`, `entityId`, `entityCode` and `detail` are all on the wire and discarded. An operator sees twelve broken items and cannot learn which twelve. Render them and deep-link each to the tab that fixes it. Add per-channel state on the channel card (it shows a name, a type chip and a button — no hash, no last-published time) and the draft-versus-live comparison, which is backend-shaped: nothing computes a draft's would-be content hash without writing a snapshot.

Price books can only be applied brand-wide from the console. `assignToLocation` and `assignToChannel` exist as client methods with zero callers against real endpoints, so the hall-versus-base plane and aggregator propagation cannot be expressed. Wire both, add the VAT/tax-profile screen (`PUT /tax-profiles/{jurisdictionCode}` has no wrapper at all and no route), let `priority`, `validFrom` and `validUntil` be authored, and stop gating assign and activate on `status === 'DRAFT'` in the template when the endpoint permits more.

`4.8b` is the DataGrid's second consumer: a filtered selection joined against current prices (the read primitive exists — `GET /price-books/resolved/prices`, capped at 200 ids), a percent/absolute calculator with rounding using `P02`'s MoneyOrPercent, a preview of what the change costs, and a **new server-side bulk-apply with a dry run** — composing it client-side as N optimistic-locked `PUT`s gives no atomicity and no partial-failure story.

**Capabilities** `CATALOG_READ` for the readiness report; `PRICE_AUTHOR` for the price-book assigns, the tax profile and the bulk apply; `CATALOG_PUBLISH` for the publish action.

**Tests** Angular specs for the readiness report rendering `entityType`/`entityId`/`entityCode`/`detail` per finding and deep-linking each; for `assignToLocation` and `assignToChannel` being reachable; and for assign/activate no longer gated on `status === 'DRAFT'` in the template. Java tests for the bulk-apply dry run and for its partial-failure report.

### T05 · Segments and promo codes
**Rows** `5.3` segments (M) · `6.2` promo codes (M) · `6.2a` per-instance unique codes (L) — **2d**, after `P02`.

Segments has a correctness bug that makes the feature read as broken: `segments-page.ts:322` passes its own descriptive string as the **consent purpose**, and `MarketingEligibility` matches that string exactly against recorded consent, so every candidate is refused `CONSENT_WITHHELD` and the member count reads ~0. Send a real purpose. Then stop discarding the diagnostic: the snapshot returns `candidates`, `members` and `excluded` precisely so «a marketer who sees only the reach concludes the audience is broken», and the page keeps only `members`. Render all three with a refusal breakdown, and wire the snapshot export so a segment can be read and downloaded rather than only sized.

Promo codes: the draft form has no `validFrom`, `validUntil`, `channels` or `locationIds` fields at all, although the request accepts them, the service validates them and the eligibility lookup enforces them — so every code created from this screen runs forever across every channel and branch. Add the fields and an expiry column. Then the redemption ledger: `pricing.coupon_redemptions` is written only, with no `SELECT`, no port method, no service and no endpoint, so `redeemedCount` is a counter with no drill-down.

`6.2a` is the migration: `pricing.benefit_grants` does not exist, so a one-off code only one named customer can redeem — the late-order apology — cannot be minted, and service recovery falls back on a shared code word anyone can spend. Build the table, the minting path and the redemption check; it is also the prerequisite for automations (`6.5`).

**Migration** yes — reserved numbers **`V0265`–`V0267`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `pricing.benefit_grants`.

**Capabilities** `SEGMENT_MANAGE` for the builder and the snapshot export; `PROMOTION_MANAGE` for the promo-code draft and its new fields; `PROMOTION_READ` for the redemption ledger.

**Tests** the regression first: a Java test that a segment snapshot with a **real** consent purpose yields a non-zero member count, and an Angular spec asserting the page no longer sends its own descriptive string as the purpose. Then tests for `candidates`/`members`/`excluded` all rendering with a refusal breakdown; for promo-code `validFrom`/`validUntil`/`channels`/`locationIds` being sent and enforced; and, against the migrated schema, for `pricing.benefit_grants` minting a code redeemable by exactly one named customer and refusing a second.

### T18 · Campaigns, loyalty and acquisition links
**Rows** `6.3` loyalty policies (M) · `6.4` campaigns (M) · `6.4b` couriers as an SMS audience (M) · `6.6a` trackable acquisition links (L) — **2d**, after `T05`.

Campaigns: only Telegram can deliver, and the create form offers SMS, EMAIL and PUSH with no warning, so an operator can spend a four-eyes approval on a campaign that dies inside the expansion scheduler with an exception no operator ever sees. Add an `isWired` flag to the campaigns read model and disable the unwired options. Add scheduled send — `CampaignStatus` declares `SCHEDULED` and the `APPROVED→SCHEDULED→SENDING` edges and nothing ever writes that status, and the request has no `scheduledAt`. Wire the two endpoints the console has and never calls: `POST /suppressions` (an operator can lift a suppression and cannot record one) and `getAudience`/`redefineAudience` (once defined, an audience's predicates can never be viewed or changed).

Loyalty: points expire silently. `expiryWarningDays` is authorable, validated and persisted, and nothing sends the message while `LoyaltySweeper.expireLots()` destroys the points on schedule. Add the ADR 0020 trigger. Add the backend validation the row needs more than the picker: a non-BRAND rule is only checked for carrying *some* UUID, and `accrual_rules.scope_id` has no foreign key, so a bad id persists and resolution silently falls back to the brand rule. Author `allowedChannels` and the validity window — both accepted, both never sent.

`6.4b` needs an audience shape that is not a customer: every recipient is a `customerAccountId` with a marketing consent purpose, so an operational blast to couriers has no model. `6.6a` needs `marketing.attribution_links`, which has no migration — mint a trackable `?ref=` link and a Telegram `startapp` deep link and record which link brought an account or an order.

**Migration** yes — reserved numbers **`V0268`–`V0270`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `marketing.attribution_links`; a foreign key on `accrual_rules.scope_id`.

**Capabilities** `CAMPAIGN_MANAGE` for scheduling, suppressions and audience redefinition (with the ADR 0027 four-eyes approval unchanged); `LOYALTY_POLICY_MANAGE` for the accrual rules; `MARKETING_LINK_MANAGE` for the attribution links.

**Tests** Java tests that an unwired channel cannot be chosen (`isWired` false for SMS, EMAIL and PUSH), that `APPROVED→SCHEDULED→SENDING` is writable and `scheduledAt` honoured, that a suppression can be recorded as well as lifted, that an audience's predicates can be read back and redefined, that `expiryWarningDays` fires before `LoyaltySweeper.expireLots()`, and that a non-BRAND accrual rule with an unresolvable `scope_id` is refused rather than silently falling back to the brand rule. Angular specs for the campaign form and the attribution-link minting.

### T06 · Branch and SLA reports
**Rows** `7.3` branch leaderboard (M) · `7.3a` SLA bucket distribution (S) · `7.3b` per-channel counts and the payment split (L) — **1d**, after `P27`.

Two of the three missing leaderboard columns need no backend at all: the delivery/pickup/aggregator triple is `groupBy=['LOCATION','FULFILMENT_TYPE']` (plus `CHANNEL` for the aggregator slice) over an aggregate already keyed by fulfilment type, and on-time percentage needs one registry metric for the denominator — `orders.late.v1` is computed and `promised_count` is already stored on `agg_branch_day`. Average delivery time is the real gap and waits for `T11`'s delivery fact. Add a secondary sort control and collapse the per-branch preparation-time fan-out, which costs one request per branch.

`7.3a` carries an arithmetic defect worth more than its cosmetics: `buildSlaRows` sums counts across days while overwriting `sharePercent` per day, so for the 7-day and month presets the rendered percentage is the **last day's share printed beside a whole-range count**. Fix it and add the median column. The tint ramp and the median already exist, contrary to the row's own wording — do not rebuild them. Tenant-configurable boundaries stay refused per ADR 0043; render the version instead.

`7.3b` splits: the per-channel count-and-average-check block is one extra query and a third table, and the payment split waits for `P39`.

**Capabilities** `REPORTING_READ`.

**Tests** the arithmetic first: a spec pinning `buildSlaRows` so `sharePercent` is the whole range's share and not the last day's printed beside a whole-range count. Then Java tests for the `['LOCATION','FULFILMENT_TYPE']` grouping, for the on-time denominator metric over `promised_count`, and for the per-branch preparation-time fan-out collapsing to one request.

### T11 · Courier reports and the delivery fact
**Rows** `7.4` courier leaderboard (L) · `7.4a` courier SLA buckets (M) · `7.4b` delivery-sum-by-tariff audit (M) · `7.4c` external-delivery cost report (L) — **2d**, after `T06`.

`/statistics/couriers` is a placeholder because reporting has no courier-attributed fact at all. The root cause is upstream and must be fixed first: `CourierAccrualService.recordDelivery` has **no production caller** — only a test — so `courier_assignment_earnings` and the `CASH_COLLECTED` ledger entries are never written, which is also why Finance's cash worklist is permanently empty on a real tenant. Wire the acceptance-to-delivery path that calls it. Then add `reporting.fact_delivery` and its projector, the `COURIER` scope of `agg_sla_bucket_day` (the CHECK already allows it and `DayAggregator` hard-codes `LOCATION`), a courier dimension on the reporting endpoints, and the screen.

`7.4b` does **not** need `fact_delivery`: `fulfillment.delivery_fee_resolutions` already holds tariff, tariff version, zone, band and final fee, so a range query grouped by tariff and joined through quote→order→shipment answers the audit today. Add it with a supporting index on `(tenant_id, created_at, tariff_id)` — the current indexes are quote- and location-keyed.

`7.4c` is the one report in §7 that finds money: per-order «order amount vs charged delivery vs provider billed vs variance vs reconciliation status», a per-line reconcile action, and `UNBILLED` actually produced and excluded from the variance total — it is a dead enum constant today. The invoice-scoped half already renders in Finance 8.4; this is the per-order cut, and `fee_charged_som`/`variance_som` exist in the schema with zero Java readers.

**Migration** yes — reserved numbers **`V0271`–`V0273`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `reporting.fact_delivery`; the `COURIER` scope of `agg_sla_bucket_day`; an index on `delivery_fee_resolutions (tenant_id, created_at, tariff_id)`.

**Capabilities** `REPORTING_READ` for every report; the accrual wiring runs inside the delivery path as a system principal. The courier dimension must not leak a courier's protected name into a report — join on id and resolve the display through `P19`'s reveal, never through the fact.

**Tests** the root cause first: a Java test that the acceptance-to-delivery path calls `CourierAccrualService.recordDelivery`, writing `courier_assignment_earnings` and the `CASH_COLLECTED` ledger entry — today only a unit test reaches it. Then tests against the migrated schema for `fact_delivery` and its projector, for the `COURIER` scope of `agg_sla_bucket_day`, for the tariff-audit query over `delivery_fee_resolutions` using the new index, and for `UNBILLED` being produced and excluded from the variance total.

### T12 · Staff and telephony reports
**Rows** `7.5` operator leaderboard (L) · `7.5a` operator product/upsell (L) · `7.5b` telephony KPIs (M) — **2d**, after `T08`.

`/statistics/staff` is a placeholder for a stated reason: `fact_order` carries no operator attribution. `ordering.orders.created_by_actor_*` and `accepted_by_actor_*` are built and the close job copies neither. Add `operator_principal_id` to the order fact (ADR 0043's own physical model lists it and V0031 did not implement it), a `Grain.Dimension` for it, and the leaderboard — orders taken, revenue, average check, per-channel, delivery versus pickup — with machine principals typed as pseudo-operators so the bot and the website can be compared against people. Names come from the staff-identity ADR; until it lands, render the typed principal kind and the subject, not a bare UUID with no explanation.

`7.5a` follows from the same column plus an operator × product endpoint over `fact_order_line`, and an aggregated receipt-depth metric — `item_count` and `line_count` exist per order and are rendered in 7.2, and no metric aggregates them.

`7.5b` is not blocked on telephony existing: `CallStatsController` serves offered/answered/missed/transferred and talk seconds per hour per operator off `reporting.fact_call_hour`, written inside the day-close transaction, and has **zero consumers**. Build the tab. Two of the three named KPIs genuinely have no source: answer speed has no ring/wait column in V0149 at all, and call-to-order conversion has no fact — the write-once `call-provenance` column exists and `1.6`'s wiring (`T-W01`) is what starts populating it.

**Migration** yes — reserved numbers **`V0274`–`V0276`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `operator_principal_id` on `reporting.fact_order`.

**Capabilities** `REPORTING_READ`. `operator_principal_id` is a principal id, not a person — rendering it needs no PII capability precisely because no name is attached until the staff-identity ADR lands.

**Tests** Java tests against the migrated schema that the close job copies `created_by_actor_*`/`accepted_by_actor_*` into `operator_principal_id`, that a machine principal is typed as a pseudo-operator rather than dropped, and that `CallStatsController`'s existing figures reach the new tab. A registry drift test for the receipt-depth metric.

### T13 · Customer analytics
**Rows** `7.6` KPI tiles with published formulas (L) · `7.6a` trends, cohorts, new versus returning (L) · `7.6b` RFM cross views (M) — **2d**, after `P27`.

`/statistics/customers` is a placeholder because `MetricRegistry` has no customer-grain definition, so `GET /queries` would refuse the metric ids outright. The grain, however, is already there and the row's own evidence understates it: `reporting.fact_order` carries `is_first_order` and `customer_subject_hash`, and `agg_branch_day` carries `distinct_customers` and `new_customers` with a CHECK between them. So new-versus-returning and a repeat-purchase distribution are derivable today without any `dim_*` table.

Register the metrics — new customers, distinct customers, repeat share, basket depth, order frequency, customer value, LTV — with their formulas published through the `/reporting/metrics` endpoint `P27` wires, so the tiles carry the «?» panel that was the credibility argument against Delever's unstated LTV. Add a cohort/retention read (there is no cohort query path and no dimension a caller could group by) and a revenue cut by `is_first_order`, which is expressible from the fact and not requestable through `/queries`.

`7.6b` is a reporting-side R×F cross-tab with member counts and revenue per cell, distinct from 5.3's segment builder — a marketer can size one segment today and cannot read the grid that says which cell is worth targeting.

**Capabilities** `REPORTING_READ`.

**Tests** a `MetricRegistry` drift test naming each new customer-grain metric and its published formula; Java tests that `/reporting/metrics` returns those formulas, that a revenue cut by `is_first_order` is requestable through `/queries`, and that the cohort read refuses a range longer than its retention window. Angular specs for the «?» panel on every tile.

**Traps** the acquisition chart and the pre-order funnel are `7.6c` and are blocked on a legal input; cohort heatmap and line charts need `T09`.

### T14 · Product analytics: ABC and XYZ
**Rows** `7.7` product report (S) · `7.7a` ABC with the cumulative column (L) · `7.7b` XYZ and the ABC×XYZ matrix (L) — **2d**, after `P27`.

`7.7` ships eight of the spec's nine columns and two inert controls. Add the `Категория` column (the API returns `categoryId` and the row model drops it), the `СТОП` row-severity marker, and — the defect worth more than both — make the page react to the filter bar: `load()` runs only from the constructor, so clicking a period pill changes nothing and the table silently keeps the previous range, and the fulfilment control is ignored entirely. Also disclose that DINE_IN lines are summed into the total and fall into neither split column, so delivery plus pickup need not add up.

ABC and XYZ are the analytic half. The substrate is closer than «zero»: `readVariantSales` already returns per-product net revenue ordered descending and the page already computes a per-row share, so the cumulative column is arithmetic over data on the wire. What is genuinely absent is the **persisted run**: `reporting.classification_run` and `classification_result` have no migration, and it is the recorded parameters and thresholds — 80/15/5 and the range — that let a class-C ruling be disputed, which is the stated improvement over Delever. Add the tables, the run endpoint, the ≥28-day range guard, the XYZ coefficient of variation, and the AX/CZ matrix as a click-through filter onto the product table.

**Migration** yes — reserved numbers **`V0277`–`V0279`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `reporting.classification_run` and `classification_result`.

**Capabilities** `REPORTING_READ` for the report; a new `REPORTING_CLASSIFICATION_RUN` for starting a persisted ABC/XYZ run, because a run writes rows and its recorded thresholds are what a disputed class-C ruling is defended with.

**Tests** Java tests against the migrated schema for a persisted run recording its thresholds and range, for the ≥28-day guard refusing a month-to-date preset shorter than 28 days rather than silently accepting it, and for the XYZ coefficient of variation on a known series. Angular specs for the cumulative column, the `Категория` column, the `СТОП` marker, and — the defect — the page reloading when the filter bar changes.

**Traps** the month preset is month-to-date, so the closest available range silently violates the 28-day floor rather than refusing it. The kiosk report (`7.7c`) is a recorded decline — kiosk must be readable as a channel slice instead, which needs the filter bar's channel control from `P27`.

### T15 · Marketing reports
**Rows** `7.9a` per-customer discount history (L) · `7.9b` campaign delivery and read statistics (L) — **1d**, after `T05`.

`7.9a` answers «how much has this customer been discounted» before another goodwill code is granted — the abuse check the report exists for. It cannot come from reporting: `fact_order.customer_subject_hash` is a one-way ADR 0029 hash, so a per-account total must come from `pricing.coupon_redemptions`, which is indexed by `(tenant_id, customer_account_id)` for exactly this query and has no read path. Add the store query, a port in `pricing.api`, an operations endpoint and both consumers — the customer detail pane from `P40` and this report.

`7.9b`: `GET /campaigns/{campaignId}/recipients` returns per-recipient status, notification id and refusal reason — a real base — and there is no aggregate. Add delivery counts (the store-level `recipientCounts` rollup exists and is internal-only) and the campaign tab. Read receipts have **no data source at all**: `NotificationStatus` has no `READ` and V0043 has no `read_at`, so «how many opened it» needs a column and a provider callback before it needs a screen. Say so on the screen rather than rendering a zero.

The promo-code summary itself (`7.9`) stays deferred — ADR 0023 forbids a report reading `pricing` directly, so it needs `reporting.fact_promotion_redemption`, whose grain needs a promotions ADR.

**Migration** yes — reserved numbers **`V0280`–`V0282`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `notification_deliveries.read_at` and the `READ` status.

**Capabilities** `REPORTING_READ` for the campaign statistics; the per-customer discount total is `CUSTOMER_READ` plus `PROMOTION_READ` and lives outside reporting, since `fact_order.customer_subject_hash` is one-way by ADR 0029.

**Tests** Java tests against the migrated schema for the per-customer discount total over `pricing.coupon_redemptions` using `ix_redemptions_customer`, and for the campaign delivery aggregate matching the per-recipient list. An Angular spec that the read-receipt figure renders as «no data source» rather than a zero.

### T07 · Finance: courier money
**Rows** `8.3` cash reconciliation (L) · `8.4` delivery cost reconciliation (M) · `8/X.5` partner invoice import and matching (M) · `8.5` courier payouts (L) — **2d**, after `P29` and `T11`.

All four screens render, and on a real tenant three of them render nothing, for one reason: `CourierAccrualService.recordDelivery` has no production caller, so `courier_assignment_earnings` and the `CASH_COLLECTED` ledger entries are never written, `cashCollectedDuringShift` returns 0 on every shift, and `openCashHandover` never opens a handover. **`T11` owns that wiring and this wave is sequenced behind it** — the first draft told both waves to fix the same call site and to «coordinate», which is not a decision. Assume `CourierAccrualService.recordDelivery` has a production caller by the time this wave starts, and if it does not, stop and say so rather than wiring it a second time.

Cash: add courier identity, shift, location and declared-at to the worklist (the view carries them all and the table is five money columns), add the status and location filters the API already accepts, and add the IA's courier daily/shift totals by payment method and the bonus-paid amount that reduces cash due.

Delivery cost: there is no акт сверки workflow — import and match are the only mutations and neither has a caller, so an operator handed Yandex's monthly file has no way to load it or resolve a `VARIANCE`/`UNMATCHED_LINE` row. Add the import form and the match action (its provider-ref→shipment map is caller-supplied by design and needs a resolution UI), a dispute/accept-variance path, the charged-versus-cost margin, and translate `matchStatus`, which prints as a raw enum beside every other status that does not.

Payouts: the salary report's columns do not exist on the backend DTO — no km, no hours, no penalty/bonus split — so adding them is a `SettlementPeriodResponse` change, not a template edit. Render `grossEarningsMinor`, `adjustmentsMinor`, `cashHeldMinor` and `statementHash`, all on the wire and none bound, and wire the statement download: the endpoint exists, the path is declared, and no method calls it. Stop hard-coding `'UZS'` on the ledger balance.

**Capabilities** `FINANCE_READ` for the four worklists; `COURIER_CASH_RECONCILE` for the handover close; `DELIVERY_INVOICE_MANAGE` for the import and the match; `COURIER_PAYOUT_MANAGE` for the statement and its download.

**Tests** Java tests that a shift with recorded deliveries produces a non-zero `cashCollectedDuringShift` and opens a handover — the case that is empty on every real tenant today; for the invoice import and the provider-ref→shipment match including an `UNMATCHED_LINE` resolution; and for `SettlementPeriodResponse` carrying km, hours and the penalty/bonus split. Angular specs for the statement download and for `matchStatus` rendering localized.

### T19 · Finance: subscription and invoices
**Rows** `8.6` subscription and billing (L) · `8/X.4` invoices the merchant can read (M) — **1d**, after `P29`.

A merchant can see the plan it is on and do nothing about it. Three things are missing and one is smaller than it looks. Statements are **already tenant-scoped**: `CommercialStatementController` serves list, draft, single and a CSV export at `ScopeType.TENANT`, held by tenant-owner and tenant-finance — the console simply has no client and no screen. Add both: `commercial-api.ts` gains list/detail/download, `finance-paths.ts` gains the three paths, and a statements page renders lines and totals. Optionally alias the reads onto the operations prefix so the app stops calling `/control-plane/**` for its own invoices.

Genuinely absent, and each needing backend: a tenant-facing purchasable-module catalogue with inline purchase (the catalogue is `CommercialModuleController`, control-plane only), tenant-visible arrears state and the restricted-feature banner (`ArrearsController`, control-plane only), and period close. The prepaid wallet and credit expiry are `8/X.3` and are blocked on ADR 0095's acceptance.

Add the missing `.spec.ts` — `subscription-page` ships without one.

**Capabilities** the statement reads are already `ScopeType.TENANT` and held by tenant-owner and tenant-finance — reuse them unchanged; the purchasable-module catalogue and the arrears read need tenant-scoped mirrors of the control-plane capabilities, named in the wave's ADR note.

**Tests** Angular specs for the statements list, detail and CSV download against the already-tenant-scoped endpoints, and the missing `subscription-page.spec.ts`. Java tests for the tenant-facing module catalogue and the arrears read refusing another tenant.

### T08 · Staff: the audit log
**Rows** `9.3` activity log (M) · `9.3a` field-level diff (L) · `9.3b` a named human actor (M) · `9.3c` a bulk action produces N records (S) · `9.2d` created-by/accepted-by attribution (M) — **2d**, after `P30`.

The most valuable screen in the section is a column of UUIDs. Three fixes, in order of leverage.

**Names**: `actor_display` is null on nearly every row because ~99 call sites pass `ActorRef.user(subject, null)`. Do not backfill 99 call sites — add a read-time resolution step in `AuditQueryService`, which already selects `actor_display` in both queries, and every audit-consuming screen benefits at once (activity log, data-privacy, product-editor history). The name source is the staff-identity ADR; Keycloak's `firstName`/`lastName` are written by `KeycloakStaffAccounts.completeSetup` and read back by nothing, so a cached resolver over the admin API is the interim.

**Diffs**: `ChangeDocuments.change(field, before, after)` produces the `{before, after}` shape the viewer already parses and has **zero production callers**; ~130 of the 132 `.changed(...)` sites write flat after-only maps. This is a write-side migration, not a screen change. Normalise `TenantControlPlaneService`'s two hand-rolled root-level `{before, after}` maps into the per-field shape while you are there.

**Bulk correlation**: the grouping data is not there, contrary to the row's premise — `GrantAuditListener:43` correlates by the grant's **own id**, so twelve revokes carry twelve different correlation ids and pasting one into the filter returns one row. Add a correlation field to `GrantChanged`, accept the request `X-Correlation-Id`, have the listener use it, and have `staff-page.ts` mint one id across its `Promise.allSettled` fan-out. Only then the «часть массового действия» chip and the click-through.

Also: cursor paging (`Page.last(events)` always yields a null cursor, so past 200 events the operator is stuck), an action-code dictionary so «Что» stops printing `iam.grants.revoke`, a person picker instead of a subject-id text box, the four backend-ready filters the UI never exposes, a deep link from a person's card, and a `.spec.ts` — this is the only staff screen without one. `9.2d` adds the actor to the order detail and an actor-count aggregate.

**Migration** yes — reserved numbers **`V0283`–`V0285`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: a correlation column on the audit fact.

**Capabilities** `AUDIT_READ` at `TENANT` scope. The name resolver runs **inside** `AuditQueryService` under the caller's own capability; it must not become a directory lookup anyone can call, and it must never resolve a name for a principal the caller could not otherwise see.

**Tests** Java tests that `AuditQueryService` resolves `actor_display` at read time for a row written with a null display, that it resolves **no** name for a principal the caller could not otherwise see, that `ChangeDocuments.change` output round-trips through the viewer's `{before, after}` parser, and that a bulk fan-out sharing one `X-Correlation-Id` yields N rows under one id — the premise the current `GrantAuditListener:43` breaks. Plus cursor paging past 200 events, and the screen's first `.spec.ts`.

### T20 · Staff: contacts, POS ids, Telegram links, self-service
**Rows** `9.2b` contact persons (M) · `9.2c` operator ↔ POS operator id (L) · `9/X.1` Telegram staff links (S) · `9/X.5` my profile (M) — **2d**, after `T08`.

Four rows that mostly wait on the staff person record, with three pieces that do not.

`9/X.1` ships now: no console screen issues a link code, so the only built path to a staff Telegram link is someone reading `POST .../staff/telegram/link-codes` out of the API by hand. Add a self-service card that hands the staff member her `/link <code>` command, and render the `telegramUserId` the console already receives. Unlinking is **missing in the backend**, not merely unwired — `TelegramStaffLinkService` has no revoke and V0105's header says revocation is check-at-tap against grants — so «unlink Aziza» needs an endpoint plus a screen, or an explicit decision that the grant revoke is the unlink.

`9/X.5` splits the same way: «Мои должности» is thin wiring over `GET /api/v1/session/context`, already in the browser, and the Telegram self-link above. Everything else — name, phone, email, sign-in history, active sessions, «выйти везде», PIN and MFA state — needs the staff profile store, the Keycloak session projection and the MFA decision, all deferred. The language switcher exists but is browser-local; say so rather than calling personalization built.

`9.2b` must be rescoped before it is built: the claim that «a manager cannot record who to call about a branch» is false — `tenant.locations.contact_phone` is editable in the console today with an E.164 check constraint. What is missing is a **named contact person** (person, role, phone) for a branch, and any contact detail attached to a staff member, which is ADR 0029 PII with no owning decision.

`9.2c` is a provider-neutral contract change, not a mapping row: `PosAdapter.OrderExport` has no operator field, so wiring an iiko operator id touches the export contract and each adapter, on top of the missing mapping table and the missing staff record it would hang from.

**Capabilities** `IAM_GRANT_MANAGE` for issuing and revoking a staff Telegram link code; `TENANT_READ` for «Мои должности», which is `GET /api/v1/session/context` and needs nothing more; `LOCATION_MANAGE` for a branch contact person.

**Tests** Java tests for issuing a link code and for the unlink endpoint (new — `TelegramStaffLinkService` has no revoke), including that a revoked link cannot be re-tapped. Angular specs for the self-service card rendering the `/link <code>` command and the `telegramUserId`, and for «Мои должности» reading only `GET /api/v1/session/context`.

### T09 · Chart family and KPI tiles
**Rows** `X.19` chart family (L) · `X.20` KpiTile and WallboardTile (M) — **2d**, after `P01`.

There is not one `<svg>` or `<canvas>` in the entire operations console, and no charting dependency. Every report answers in numbers and tables, so «is today going normally, and if not where» has to be read line by line.

Build the half that has data today and stop there: line, bar, stacked bar and donut over `/reporting/queries`' per-day rows; a histogram over the six fixed SLA buckets; an hour-of-day heatmap over `/reporting/demand-history`. Each needs axis, legend, tooltip and an accessibility affordance better than a `title` attribute, plus a dataviz palette in `frontend/design-tokens` — `tokens.css` has none. The two shapes that exist today are `[style.width.%]` divs duplicated in two files; replace both.

Funnel, cohort heatmap, ABC cumulative curve and geo heatmap have **no data source** and are not in this wave: they arrive with `T13`, `T14` and the deferred geography rows.

`X.20`: extract one `q-kpi-tile` from the overview's inline markup plus its `deltaOf`, and draw the sparkline from the per-business-date rows `/queries` already returns and `report-rollup.ts` discards. A today-so-far hourly series does not exist, so the sparkline is period-over-period, not intraday. `q-wallboard-tile` and the TV scale belong to `T23`.

**Capabilities** none — the chart family renders whatever its host screen already fetched.

**Tests** a spec per chart type over a fixed dataset asserting axis, legend and tooltip, plus a table-equivalent accessible affordance that is not a `title` attribute; a spec that the two replaced `[style.width.%]` implementations render identically to the new bar; and a dataviz-palette contrast check in both themes.

### T21 · Rule authoring components
**Rows** `X.25` ConditionBuilder, RuleList with priority reorder, RuleSimulator (L) — **2d**, after `P01`.

Five rule engines share one component and the console has none: promotions, automations, auto-add, dispatch rules and courier bonus/penalty. The only rules anybody can write today are segment predicates, hand-rolled inside `segments-page.ts`.

Extract that builder into `shared/ui/` as `q-condition-builder` — a typed condition catalogue, and/or grouping, value editors bound to `P02`'s money, percent, date and day-of-week inputs — plus `q-rule-list` with drag priority reorder and an enabled/disabled state, and `q-rule-simulator` taking a candidate input and rendering which rules matched and what they did. Re-point the segments page at the extracted components so there is exactly one implementation, and give each component a spec.

Two of the five consumers are closer than the row implies and should be named as the first real users once this lands: **promotions** needs only ADR 0018's `POST /promotions` and `/validate` — the engine, the condition catalogue and the priority tie-break already exist in `PromotionEvaluator`, so it is one controller plus one screen; and **dispatch rules** has its policy document (`DeliverySourcingPolicies.SOURCING`) already resolved through ADR 0030 and needs a write endpoint, which makes the `no backend exists` annotation on its placeholder route wrong. Automations and auto-add genuinely have nothing and stay deferred.

**Capabilities** none. The simulator runs against a candidate input the caller supplies and must not read live orders; a dry run that touches production data needs the host screen's own capability and is out of scope here.

**Tests** specs for the condition builder's and/or grouping and typed value editors, for rule-list priority reorder by drag and by keyboard, and for the simulator reporting which rules matched and what they did. A regression spec that the re-pointed segments page produces the same predicate JSON it produced before extraction.

**Traps** a dry run already exists for audiences (campaign estimate, segment snapshot); the simulator must not duplicate it, only generalise it.

### T22 · Timeline, frames, rich text, steps, OTP and the type stack
**Rows** `X.26` Timeline/DiffViewer/ActorChip (M) · `X.28` PhoneFrame siblings (M) · `X.31` RichTextEditor (L) · `X.33` Steps/ProgressRail (S) · `X.38` OtpInput (S) · `X.40` dir/script-safe type stack (S) — **2d**, after `P01`.

Six component rows that share one property: each is re-implemented per screen or not at all.

`X.26`: the same `actorLabel(event) => actorDisplay ?? actorSubject ?? '—'` appears in three files. Extract `q-actor-chip` (human versus service versus job versus migration principal), `q-timeline` (the order detail's hand-rolled lanes and the audit log's event list are the same idea) and `q-diff-viewer`. The names themselves come from `T08`.

`X.33`: build `q-steps` and give it a first consumer that already needs it — the two-step connect-provider drawer in Settings → Integrations renders no step indicator at all — plus the order lifecycle rail over the existing timeline endpoint.

`X.28`: `PhoneFrame` does not exist in Angular either, so build the base and the three siblings. Note that `AggregatorCardFrame` and `KioskFrame` have nothing real to preview until ADR 0040's projection and a kiosk configuration exist; ship the frames against the storefront projection only.

`X.31`: a sanitized **block** editor, not a textarea — the parity behaviour being replaced is Delever accepting raw tenant HTML, which is the XSS surface HorecaOS closes. Its consumers (static pages, news, vacancy bodies) are deferred, so its first honest consumer is the terms document, which ships plain textareas today.

`X.38`: build `q-otp-input` — six segmented cells, paste handling, auto-advance and auto-submit, screen-reader labelling. It moved here from `W05`, where it sat beside an XL split-tender row it has nothing to do with. Build the **component only**: there is no staff second-factor endpoint today, the storefront's phone-OTP session is a different surface and a different principal, and whether the factor is enrolled in Keycloak or modelled on the platform is the staff-identity ADR's call. The component is useful either way and is what a future terminal PIN screen reuses.

`X.40`: fix the drift the lint catches. **`P02` adds the ESLint config and the rule banning raw `px` font sizes** — this wave does not add them a second time; it fixes the 249 raw `font-size` declarations that rule fails on, 38 of which use sizes absent from the closed scale. Then normalise the uz-Latn catalogue's mixed U+02BB/U+2019 apostrophes, and record what a fourth locale actually costs — two enums, three all-locales-required validators, a hard-coded reference list, a SQL fallback CASE and nine append-only CHECK constraints, one of which already disagrees with the others about `uz` versus `uz-Latn`.

**Capabilities** none. `q-otp-input` is a component only — the staff second-factor endpoint it will bind to is the staff-identity ADR's, and this wave must not invent one.

**Tests** specs for `q-actor-chip` across human, service, job and migration principals; for `q-timeline` rendering both the order lanes and the audit event list from one component; for `q-diff-viewer` on a per-field `{before, after}` document; for `q-steps` in the two-step connect-provider drawer; for `q-rich-text` **rejecting** a script tag and a javascript: href, which is the row's whole point; and for `q-otp-input` paste, auto-advance and screen-reader labelling. `X.40` adds the lint fixture and a test that the uz-Latn catalogue uses one apostrophe codepoint.

### T10 · Drafts and abandoned carts
**Rows** `1.4` drafts and abandoned carts (S) — **1d**, after `P03`.

A small screen with a misleading number. The by-channel breakdown counts every draft including ACTIVE carts under a heading that reads «Отказы по каналам», so a live in-progress basket is reported as an abandonment; and it emits raw counts with no denominator, while the reason the screen exists is a **rate** («the Telegram bot loses 40% of baskets»). Fix the classification and show the rate.

Then the filters: orders.md §6 requires period, channel, location and owner type, the backend already accepts `from`, `to` and `channelId`, and the page calls `list(scope)` with an empty query. Add them, and render the two spec'd columns already on the response — location and `expires_at`. Do not rely on backend ordering for «newest first»; sort explicitly.

Two limits to keep: converting a cart into an order is deliberately not offered — nobody agreed to that basket — and the first-line product preview needs `ordering.cart_lines` to snapshot a name, which it does not. The ADR 0044 recovery-audience hand-off belongs with automations and is deferred.

**Capabilities** `ORDER_READ` — a cart is an order in draft and the existing list already gates on it.

**Tests** Angular specs that an ACTIVE cart is excluded from «Отказы по каналам», that the rate is shown with its denominator, that `from`/`to`/`channelId` reach the query, and that ordering is explicit rather than inherited from the backend.

### W01 · Reservations, dine-in sessions and telephony
**Rows** `1.5` reservations (M) · `1.5a` seat/complete (L) · `1.6` call centre (S) · `X.37` CallBar and CallScreenPop (L) — **2d**, after `P02` and `P32`.

Reservations is a real ADR 0047 build with three holes. The day window is hard-coded 08:00–23:00 and computed in the **browser's** timezone, so a branch in another zone gets a shifted day and bookings past 23:00 fall outside the grid — bind it to the location's service schedule, which `P43` makes readable through the `GET` it adds to `ServiceScheduleController` (`P32` is this wave's merge dependency for the locations folder; `P43` is the one that supplies the read). The guest is not merely uneditable, it is invisible: `ReservationResponse` carries no name, phone or note by design, so a host cannot match a walk-in to a booking; add them to the response and to `AmendmentRequest`, which accepts them on create and not on amend. And the detail pane prints raw table UUIDs where the grid prints table codes.

`1.5a`: seating needs the dine-in session surface — `TableSessionController` exposes create, rounds, state-actions and force-closures with **zero frontend callers** — so add a «seat this booking» action and `COMPLETED` to the target-status union. No backend work. The row's other two sub-features contradict ADR 0047 and should be struck from the IA, not built.

Telephony: the screen is real and misses one join. `POST .../{orderId}/call-provenance` (write-once, `ORDER_PROVENANCE_RECORD`) has **zero callers anywhere**, so a claimed screen-pop card is never linked to the order it produced and every operator KPI loses that join — add `recordCallProvenance` and call it from the order-taking path with the claimed `callEventId`. `roster()` is dead code: the screen marks only the operator's own presence, with no team board showing who is ONLINE/PAUSED/WRAP_UP. And the pop cannot follow an operator: lift the presence and current-call poll into a service and put a call bar in the shell — the call centre is not even in the rail today, reachable only from a tab on the orders queue.

**Capabilities** `RESERVATION_MANAGE` for create, amend and seat; `DINE_IN_MANAGE` for the table session actions; `ORDER_PROVENANCE_RECORD` for the write-once call-provenance link; `VOICE_PRESENCE_MANAGE` for the roster and the operator's own presence. Guest name and phone on `ReservationResponse` are ADR 0029 PERSONAL — they arrive behind the same reveal the rest of §5 uses, not as plain fields.

**Tests** Java tests that the reservation day window comes from the location's service schedule and not the browser (pin a location in another timezone and a booking after 23:00), that `AmendmentRequest` accepts guest name, phone and note on amend as it does on create, and that `recordCallProvenance` is write-once. Angular specs for seat-this-booking through `TableSessionController`, for table codes replacing raw UUIDs, and for the shell call bar surviving navigation.

**Traps** a softphone is a recorded decline, not a gap. There is no push channel for voice, so a console-wide bar means every operator polling from every screen until ADR 0045 covers it.

### W02 · Demand forecast
**Rows** `7.8` forecast versus actual (XL) · `7.8a` by department and product (L) · `7.8b` holiday-aware modelling (L) — **2d**, after `P27`.

What ships today is honest history, not a forecast: a same-weekday, same-hour average of completed orders for the operator's own location, refusing to show an average below the minimum sample size and never using the word «forecast» in an operator-facing string. The owner decided that on 2026-09-05 and ADR 0043 keeps the seasonal-naive model, `forecast_run` and `fact_forecast` unbuilt.

This wave is the decision's other half. Build `forecast_run` and `fact_forecast`, the seasonal-naive model with a confidence interval, and the forecast-versus-actual comparison — then the three screen gaps: a branch selector (frontend-only; the endpoint already takes an arbitrary `locationId` under a tenant-scoped capability), an operating-day hour axis instead of wall-clock 00:00–23:00, and the per-department and per-product breakdown, which needs `fact_order_line` to carry a timestamp it does not have.

`7.8b`: the calendar already exists — `tenant.public_holidays` (V0203, ADR 0090) with a read API — and reporting never joins it, so Navruz enters the same-weekday average as an ordinary Saturday with nothing marking it. Add a holiday flag per sample date, keyed by the location's country, and an exclude-or-weight option on `/reporting/demand-history`. ADR 0043's own holiday factor with `reporting.holidays` and `calendar_version` is the larger, separate item.

**Migration** yes — reserved numbers **`V0286`–`V0288`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: `reporting.forecast_run` and `fact_forecast`; a holiday flag per sample date.

**Capabilities** `REPORTING_READ` for the forecast and the comparison; the run itself is a scheduled system job.

**Tests** Java tests against the migrated schema for `forecast_run` and `fact_forecast`, for the seasonal-naive model's confidence interval on a known series, for the operating-day hour axis, and for a Navruz sample being flagged and excludable. An Angular spec that the branch selector changes the series and that the filter bar is either wired or hidden — not rendered and ignored.

**Traps** the filter bar renders above this tab and the page ignores it entirely — either wire it or hide it. Cook headcount (`2.6a`) is the consumer waiting on this plus a portions-per-cook-hour policy, and stays blocked on the policy.

### W04 · Geography: histograms and the week grid
**Rows** `7.10b` delivery-time and distance histograms (L) · `7.10c` day-of-week × hour cohort grid (M) — **1d**, after `W02`.

§7.10 has no route, no nav entry and no placeholder — unlike every other unbuilt reports section, it is not even honestly surfaced. Add the route and the tab, then the two sub-rows that need no map.

The cohort grid is the cheapest thing in §7: seven `demand-history` calls, one per weekday, for the selected location, coloured by average orders — no new endpoint, no new SQL, no new fact. Cell drill-down is the only backend piece and it is thin: `/reporting/orders` has no weekday or hour-of-day filter, so either add one or filter client-side on the `occurredAt` the response already returns, capped at 300 rows.

The histograms need distances and durations in reporting. `fulfillment.courier_assignment_earnings.distance_meters` holds them and nothing copies them across, so this row waits on `T11`'s delivery fact; build the component against the SLA buckets first and widen it when the fact lands. Today the only duration view in the section is the fixed six-bucket table per branch, so a long tail of far or slow deliveries is invisible.

The map-shaped rows — the density heatmap and today's orders as pins — stay deferred with `X.4`.

**Capabilities** `REPORTING_READ`.

**Tests** Angular specs for the seven-call week grid rendering and for cell drill-down capped at 300 rows; a Java test if the weekday/hour filter is added server-side rather than client-side. A spec that the histogram renders against SLA buckets today and widens when `T11`'s fact lands.

### W03 · Privacy self-service, access check and approvals
**Rows** `10.11` data and privacy (L) · `9/X.4` access check (M) · `9.4` approvals worklist (M) — **2d**, after `P30`.

`10.11`'s headline gap is wrong in the merchant's favour: the tenant-scoped DSAR erasure backend is **fully built** — raise, history, execute under its own tighter capability, and cancel, over V0178 and `CustomerErasureService` — and the only screen showing those requests is the control plane. So this is a missing screen, not a missing backend; `P40` adds the per-customer controls and this wave adds the tenant-wide worklist. Genuinely missing: a tenant read and write for its own retention periods (the only surface is `PLATFORM`-scoped), a consent-type registry (no table anywhere, which is also what breaks `5.2b`'s purpose vocabulary), and data-subject **export** and **correction**, which ADR 0029 still lists as out of scope. Fix the page's stale copy — it renders the DSAR card as not-built quoting a sentence ADR 0029 no longer says, and describes two retention rules as unenforced that `CartRetentionSweeper` and `CourierApplicantRetentionSweeper` now enforce; its spec asserts the stale text, so the test changes too.

`9/X.4` is screen 9.5 in its own spec, fully designed, scheduled and unimplemented. The engine exists as `GET /control-plane/access-debugger` and is `PLATFORM_ADMIN`-only, so add a tenant-facing `access-check` under `IAM_GRANT_MANAGE` with scope containment, fold in the entitlement branch (`ENTITLEMENT_REQUIRED` versus `INSUFFICIENT_CAPABILITY`, which `EntitlementQueryService` computes and this endpoint would not), return the reason chain — which grant, at which scope, since when — and link it from every denied state `P30` builds.

`9.4`: two of the IA's four named cases cannot appear because nothing raises them — `CustomerController.export` records the reveal and does not gate it, and there is no discretionary order-line discount producer. Add a decided-history read (only pending exists), and note the scope limitation the controller already documents: approvals are TENANT-scope only, so a brand or location manager has no worklist.

**Migration** yes — reserved numbers **`V0289`–`V0291`** (head is `V0210`; PART C allocates them so two parallel waves cannot both write `V0211`). Create: the consent-type registry; tenant retention periods.

**Capabilities** `CUSTOMER_ERASURE_RAISE`/`CUSTOMER_ERASURE_EXECUTE` for the tenant-wide DSAR worklist; `TENANT_CONFIGURATION_WRITE` from `P31` for the retention periods; `IAM_GRANT_MANAGE` for the tenant-facing access check, which must enforce scope containment so a brand manager cannot probe another brand; `APPROVAL_DECIDE` for the approvals worklist.

**Tests** Java tests for the tenant-wide DSAR worklist, for tenant retention read and write, for the consent-type registry, and for the access check enforcing scope containment — a brand manager probing another brand must be refused, not answered. Update `data-privacy-page.spec.ts`, which currently asserts the stale copy this wave deletes.

### W05 · Split tender: the payment[] array
**Rows** `8/X.1` the payment[] array (XL) — **2d**, after `P29`.

Split tender is less missing than it reads. `OrderSettlementService` really models an ordered set of tenders with a sum-equals-total check and refunds that unwind in reverse sequence, and `CheckoutSettlementPlanner` is a live production caller that plans a loyalty-points leg beside the money leg — so a points-plus-cash split is already written to `payments.order_settlements` and `payments.tenders` at checkout. What is missing is entirely operator-facing: expose the settlement's tenders as the `payment[]` array on the operations order-payment read, with per-tender status and refunded amount, and render that array where `payments-page.html:60` currently prints a «split tender not built» note. Optionally add an operator path to record a redemption leg. A **deposit** tender does not exist at all and stays blocked with `5.2f` and `6.3a`.

`X.38` OtpInput moved to `T22`, the component wave: it is a design-system row that happened to be filed next to staff MFA, and a small component does not belong beside an XL settlement row.

**Capabilities** `PAYMENT_READ` for the tender array; recording a redemption leg is `LOYALTY_ADJUST`, which already carries the ADR 0027 approval.

**Tests** a Java test that the operations order-payment read returns the settlement's tenders in sequence with per-tender status and refunded amount, and that a refund unwinds in reverse sequence. An Angular spec replacing the «split tender not built» note at `payments-page.html:60`. Assert that no deposit tender enum value is introduced.

**Traps** do not model the deposit tender «for later» — ADR 0046 withdrew it outright and a speculative enum value is what produced the `UNBILLED`-style dead constants elsewhere in this map.
