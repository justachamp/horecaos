# ADR 0113: The console's first amendment client, and two more non-financial notes

- Decision status: Proposed
- Implementation status: Partial — the console's first `/amendments` builder
  for orders: `operations-paths.ts` gains `orderAmendments`/
  `orderAmendmentConfirmation`, and `OrderAmendmentsApi` wraps all five built
  `AmendmentCommandType` commands with `Idempotency-Key` and `If-Match`
  (`setKitchenNote`, `setCallbackRequested`, `setCashTendered`,
  `setCourierNote`, `setInternalNote`, plus `confirm` for the day a financial
  command needs the customer-confirmation path and `history` over
  `GET .../amendments`). `order-detail-pane.ts` wires all five from a new
  orders.md §3.6 «Комментарии» block — kitchen note and change-due behind an
  edit affordance reading the order's own field, the callback flag as an
  instant toggle with no dialog, courier and internal notes behind an add
  affordance with no current-value field to read (see the physical-model note
  below) — and from a new `q-order-amend-menu` the header's `AMEND` action now
  opens, listing exactly the five built commands. `OrderActionsPolicy
  .AMEND_EMISSION_ENABLED` is flipped to `true`: `AMEND` was built and
  gated since wave P05 (ADR 0105) and held inert for want of a console that
  could render it; this wave is that console, so `order-actions.ts` gained a
  real `AMEND` case (translated in all three locales) and `order-queue.ts`'s
  row action opens the order rather than falling through the silent default a
  code that reaches five real `PlatformRole`s cannot be left with. The
  acknowledgeable `CASH_TENDERED_INSUFFICIENT` notice renders after a
  `SET_CASH_TENDERED` call whose response carries it, dismissed by the
  operator rather than by the next order load. `AmendmentCommandType` gains
  `SET_COURIER_NOTE` and `SET_INTERNAL_NOTE`, both non-financial and
  `built = true` — see this ADR's own dated status addition on ADR 0039 for
  the full amendment to that record's closed set, now twelve commands rather
  than ten. Covered by `OrderAmendmentAndOutcomeTests`
  (`courierAndInternalNotesApplyAndTouchNoOrderField`,
  `courierNoteAmendmentRefusesAStaleVersion`) on the Java side and by
  `order-detail-pane.spec.ts`, `order-note-dialog.spec.ts`,
  `order-cash-tendered-dialog.spec.ts` and `order-amend-menu.spec.ts` on the
  Angular side. Not built: the seven financial commands stay refused by name
  (`OrderAmendmentService:497-510` in this wave's numbering — see that
  method's own doc for why); `order-queue.ts`'s row menu does not embed the
  five-command picker itself, only a shortcut to the order where it lives (see
  Open inputs); and neither `SET_COURIER_NOTE` nor `SET_INTERNAL_NOTE` has a
  materialized "current value" anywhere but the amendment history, which is
  this record's own discovered scope change — see below.
- Date proposed: 2026-09-14
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of
  2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0025, ADR 0029, ADR 0031, ADR 0039, ADR 0105
- Supersedes / Superseded by: — (amends ADR 0039; see that record's own dated
  status addition rather than a rewritten Decision/Alternatives/Consequences)
- Open inputs:
  - Whether `order-queue.ts`'s row overflow should eventually embed the
    five-command picker directly (a popover from the row, matching how
    `order-detail-pane.ts` does it) rather than navigating to the order first.
    This wave chose the navigation because the picker's own dialogs already
    assume the detail pane's full-width layout and its `order()` signal;
    building a row-scoped variant is real work with no gap-map row asking for
    it yet (frontend owner, next wave that revisits `order-queue.ts`'s own
    action surface).
  - Whether `SET_COURIER_NOTE`/`SET_INTERNAL_NOTE` should eventually get their
    own `ordering.orders` columns, the way `kitchen_note` has one, so a
    "current note" reads as one field instead of "the latest matching command
    in the history" — this wave deliberately did not spend a second migration
    number on it (platform owner, next wave that has a reason to touch
    `ordering.orders` for an unrelated column and can fold this in for free).

## Context

Gap map row `1.2h`/`1.1e`: the console has no `/amendments` client for orders
at all. `reservationAmendments` (ADR 0047) is the only `/amendments` path
builder in `operations-paths.ts`, and it is a different aggregate's client —
table bookings, not orders. `OrderAmendmentService` (ADR 0039) has carried
three working, tested, capability-gated commands since V0029 —
`SET_KITCHEN_NOTE`, `SET_CALLBACK_REQUESTED`, `SET_CASH_TENDERED` — and
nothing in `frontend/operations` has ever called `POST .../amendments`. An
operator cannot leave a kitchen note, request a callback, or record change-due
from the console; each exists only as a field on the wire and a row in a
table.

Two more channels the legacy dashboard had — `courier_note` (operator to
courier) and `internal_note` (operator to operator) — never got a home at
all. orders.md §11 names the gap in one line: *"Adding two commands is a
one-line ADR amendment; discovering the omission after cutover is a regression
report."* Nothing about either channel needs new consequence machinery — they
are the same shape `SET_KITCHEN_NOTE` already has, free text with no reprice,
no reservation, no payment, no fiscal consequence, no POS consequence — so
folding them into this wave, which builds the client both need, avoids a
second wave paying the same client-side cost for one field.

Separately, ADR 0105 built `OrderActionCode.AMEND`'s gate — `ORDER_AMEND` at
the right scope, `OrderActionsPolicy.canAmend` — and then, in wave P05's own
adversarial review, found `order-actions.ts` had no translated label for it
and `order-queue.ts` had no click handler, while `ORDER_AMEND` already reached
five real `PlatformRole`s. Emitting `AMEND` in that state would have put a
permanently dead, out-of-language button in front of a real operator, so wave
P05 held emission behind a named constant, `AMEND_EMISSION_ENABLED`, and
wave P10 — this wave — was named in that record as the one that flips it.

## Decision

**Build the console's first `/amendments` client, wire the five commands ADR
0039 (as amended here) declares built, and turn `AMEND` on.** Two amendment
command types are added to the closed set — `SET_COURIER_NOTE` and
`SET_INTERNAL_NOTE`, non-financial, `built = true` from the day they are
declared, the same shape `SET_KITCHEN_NOTE` already has.

## What this record discovered, and had to spend a migration number on

This wave was allocated no migration number — the two new commands were
expected to be free, the same "add two enum values" orders.md §11 calls a
one-line ADR amendment. That was true of `AmendmentCommandType` in Java and
false of the database: `order_amendment_commands.ck_amendment_command_type`
(V0029) is a `CHECK (command_type IN (...))` naming exactly the original ten
values by name, and this repo's own rule for that shape of constraint (stated
by `V0172`, restated in the migration below) is that a value list cannot be
extended in place — it has to be dropped and recreated whole. The first time
either new command tried to insert a row, `ck_amendment_command_type` refused
it, and `AmendmentCommandType#built()`'s own contract — *"whether the
application can actually carry this command out today"* — was broken by a
command that was `true` in Java and a constraint violation in Postgres.

`V0290` widens the constraint to twelve values. It is not this wave's only
deviation from its own allocation: because no migration number was budgeted
at all, neither new command gets an `ordering.orders` column the way
`kitchen_note` has one (that would have been a second migration), so
`OrderAmendmentService#patchOf` folds neither into an order field, and
`GET .../amendments` is genuinely the only place either note's own text can be
read back — not a temporary shortcut, a real consequence of spending the one
migration this wave could still justify (a constraint fix a failing test
proved necessary) and not a second one (a column an operator convenience
wanted but nothing proved was required).

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Leave `SET_COURIER_NOTE`/`SET_INTERNAL_NOTE` declared with `built = false`, matching the seven financial commands, and defer both to a future wave | The whole reason orders.md §11 calls this a one-line amendment is that no consequence work is needed; declaring them refused would recreate the exact gap this wave exists to close, for two commands with nothing standing in the way but a missing migration number | Never, short of a reason to reconsider whether either channel is wanted at all |
| Give each new command its own `ordering.orders` column this wave, spending a second migration number | Nothing proved that need the way the failing `ck_amendment_command_type` insert proved the constraint fix — an operator convenience ("read the current note as one field") is not the same class of requirement as "the built command can be carried out at all," and this wave's own budget discipline (documented above) drew the line there | A future wave has an independent reason to touch `ordering.orders` and can fold the two columns in at no extra migration cost, or an operator complaint makes "walk the history for the latest note" a real usability problem rather than a theoretical one |
| Store the courier/internal note text as a generic key-value fact, decoupled from the amendment aggregate | Reintroduces exactly the "generic field-level diff" ADR 0039's own alternatives table already rejected — a fact with no declared consequence vector, existing outside the revision machinery that gives every other amendment command its audit trail and its idempotency | Never — ADR 0039's own rejection stands, and nothing about this wave's discovery changes the argument |
| Invent a migration number outside this wave's allocation without checking sibling worktrees first | Twenty-odd wave agents branch from the same head in parallel; a number picked without checking active worktrees' own `db/migration/` directories is exactly the collision CLAUDE.md's Flyway-numbering note warns about | Never — check first, every time, which is what this wave did (`V0290` was confirmed free across every sibling `wave138-*` worktree before use) |
| Embed the five-command picker directly in `order-queue.ts`'s row overflow, matching `order-detail-pane.ts` | The picker's dialogs assume the detail pane's full-width layout and its `order()` signal for the fields each dialog seeds from (current kitchen note, current change-due); building a row-scoped variant is real, separate work with no gap-map row asking for it | A gap-map row specifically asks for amending an order from the queue without opening it |

## Consequences

### Positive

- The console can finally do what ADR 0039 built in V0029 and nothing has
  called since: set a kitchen note, toggle the callback flag, and record
  change-due, from a real §3.6 block instead of the read-only "interim"
  section it replaces.
- Two more note channels the legacy dashboard had, and staff use, now have an
  owner — closing the last unowned item in orders.md §11's own list of
  genuine gaps.
- `AMEND` stops being a five-role-reaching capability with no way to invoke
  it: the exact hazard ADR 0105's adversarial review found.
- The amendment history view (`GET .../amendments`, a new
  `AmendmentHistoryEntryResponse` shape) gives every command a visible record
  — not just the five built ones — which is also the seam a future financial
  command renders into once one is built, at no further client work.

### Negative

- Courier and internal notes have no "current value" the way kitchen note's
  column gives it. A caller has to open the amendment history and find the
  latest matching command, which costs more than reading one field and is a
  plausible source of a stale-looking value if a future caller does that walk
  incorrectly. This wave's own UI does not do that walk either — the §3.6
  block shows "None yet — see history" rather than a derived current value,
  which is honest but less useful than a real field would be.
- Two more command types in `AmendmentCommandType`'s exhaustive switches
  (`OrderAmendmentService#patchOf`, `AmendmentCommandRequest.toCommand`) is
  two more branches the next author has to keep exhaustive — a smaller cost
  than a silent fallthrough, but a real one.
- `GET .../amendments`'s response shape changed from the shared
  `AmendmentResponse` (which `amend`/`confirmAmendment` still return) to a new
  `AmendmentHistoryEntryResponse`, specifically to keep a "note"-named field
  off the idempotency-tracked type. Two response shapes for what reads as one
  concept ("an amendment") is a real seam a future reader has to understand
  rather than a single uniform type.
- This wave spent a migration number it was told it would not need. The
  discovery was real and documented, but it is still a deviation from the
  wave plan that the next planning pass should account for when it allocates
  "no migration" to a wave that adds enum values a CHECK constraint also
  enumerates.

### Accepted trade-offs

- The five built commands are still five of twelve — the seven financial
  commands stay refused by name, and the §3.6 block and the amend menu both
  have to render that honestly (never offering a command that would 409)
  rather than looking like a finished amendment surface.
- `order-queue.ts`'s row action for `AMEND` is a navigation, not the picker
  itself — a real, working action rather than the silent no-op the code would
  otherwise get, but not the full experience `order-detail-pane.ts` has.

## Specification

### Physical model

```text
ordering.order_amendment_commands  (ck_amendment_command_type widened, V0290)
  command_type now also accepts 'SET_COURIER_NOTE', 'SET_INTERNAL_NOTE'
  -- no new column anywhere; both commands' text lives only in payload_json
```

### APIs

```text
POST     /api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}
           /orders/{orderId}/amendments             (unchanged endpoint;
                                                       two new AmendmentCommandType
                                                       values now accepted)
GET      .../orders/{orderId}/amendments             (response narrowed from
                                                       AmendmentResponse to the
                                                       new AmendmentHistoryEntryResponse)
POST     .../orders/{orderId}/amendments/{id}/confirmation   (unchanged)
```

`frontend/operations`: `operations-paths.ts` gains `orderAmendments`,
`orderAmendmentConfirmation`; `order-amendments.ts` (models),
`order-amendments-api.ts` (`OrderAmendmentsApi`), `order-note-dialog.ts`,
`order-cash-tendered-dialog.ts` and `order-amend-menu.ts` are new;
`order-detail-pane.ts`/`.html`/`.css`, `order-actions.ts` and `order-queue.ts`
are changed. `OrderActionsPolicy.AMEND_EMISSION_ENABLED` flips `false` →
`true`.

### Testing

- Java: `OrderAmendmentAndOutcomeTests` proves both new commands apply,
  append a revision, and touch no order field
  (`courierAndInternalNotesApplyAndTouchNoOrderField`), and that a stale
  version is refused the same way every other amendment command's is —
  the service-level compare-and-set an HTTP request's missing `If-Match`
  ultimately relies on (`courierNoteAmendmentRefusesAStaleVersion`).
  `OrderActionsPolicyTests` re-proves the `AMEND` emission flip against every
  status/mode/grant combination, replacing the three tests that proved the
  hold.
- Angular: a spec per wired command in `order-detail-pane.spec.ts`
  (`SET_KITCHEN_NOTE`, `SET_CALLBACK_REQUESTED`, `SET_CASH_TENDERED`,
  `SET_COURIER_NOTE`, `SET_INTERNAL_NOTE`), one proving the acknowledgeable
  `CASH_TENDERED_INSUFFICIENT` notice, and one proving the header `AMEND`
  action opens the same five-command menu the row affordances use.
  `order-amend-menu.spec.ts` proves the menu renders exactly the five built
  commands and never one of the seven financial ones. `order-note-dialog.spec.ts`
  and `order-cash-tendered-dialog.spec.ts` prove each dialog's own field
  behaviour, including the delayed-`componentRef.setInput` resync both needed
  (the same pattern `q-time-input`/`q-money-input` already carry).

## Rollout and rollback

Additive throughout: a new migration widening one CHECK constraint, two new
enum values, a new console surface over an endpoint that already existed. No
data is rewritten. Rollback is `AMEND_EMISSION_ENABLED = false` plus reverting
the two `AmendmentCommandType` values' `built()` flag to `false` if the two
new commands ever need to be pulled — the CHECK constraint stays widened
either way, since narrowing it back would risk rejecting rows already written.

## Exit criteria

An operator can set a kitchen note, toggle the callback flag, record
change-due, and leave a courier note and an internal note, all from the order
detail pane's §3.6 block or from the header's `AMEND` menu; a later amendment
that pushes change-due below the total shows the acknowledgeable
`CASH_TENDERED_INSUFFICIENT` notice rather than a refusal; the amendment
history view shows every command an order has ever carried, including the two
new note types' own text; and the seven financial commands never appear in
any of this wave's UI.

## References

- [orders.md](../../operations-spec/orders.md) §3.6, §4.4, §11
- [ADR 0039](../partial/0039-operator-assisted-ordering-and-order-amendment.md) — the amendment aggregate this record amends
- [ADR 0105](../partial/0105-wave-p05-server-driven-actions-capability-array.md) — `AMEND`'s gate and the emission hold this record lifts
- [operations-gap-map.md](../../operations-gap-map.md) row `1.2h`
