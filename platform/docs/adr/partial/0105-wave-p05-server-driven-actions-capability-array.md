# ADR 0105: `actions[]` is a capability array, not a status array

- Decision status: Proposed
- Implementation status: Partial — `OrderActionsPolicy.availableFor(status, mode, grantedCapabilities)` is built and tested: every one of the original four codes (`APPROVE`, `REJECT`, `ADVANCE`, `CANCEL`) is now gated on the same `Capability` the corresponding mutating endpoint declares, read once per request from `AuthorizationService` and threaded through both the list and the detail read. `AMEND`'s gate (`ORDER_AMEND` plus `canAmend(status)`) is built the same way but is deliberately held behind `OrderActionsPolicy.AMEND_EMISSION_ENABLED` (currently `false`) and not yet emitted: the console has no translated label or working handler for it (`order-actions.ts`'s `ORDER_ACTION_CODES` still lists four values on purpose, and its unhandled-code fallback renders the raw string `"AMEND"` in every locale, which is not an acceptable button for a real operator to see) — wave `P10` ships the amendment client and flips the constant on. `COMPLETE`, `RESOLVE`, `ASSIGN_COURIER` and `ISSUE_INVOICE` are declared in `OrderActionCode` — so the wire contract names them now — but are not yet emitted by the policy either: each needs state (a fulfilment-mode-aware completion reason, a specific amendment's confirmation state, a courier assignment path, an invoice-issue endpoint) that does not exist yet or that this status/mode/capability signature cannot carry. The frontend's read-only overflow item (`Открыть`, `Копировать номер`) ships client-only and does not wait on any of that.
- Date proposed: 2026-09-12
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0025, ADR 0019, ADR 0039
- Supersedes / Superseded by: —
- Open inputs: whether backward status transitions (IA row 1.1h) reuse this policy directly or sit behind a separate override policy — ADR 0019 amendment, wave `P41` (platform owner); which `Capability` each of `COMPLETE` (wave `P09`), `ASSIGN_COURIER` (wave `P11`) and `ISSUE_INVOICE` (wave `P12`) should declare once its endpoint exists — this record reserves the codes and does not commit a capability for any of them (platform owner)

## Context

`docs/operations-spec/orders.md` §4.2 states the rule the console is built to:
availability is server-supplied, the response carries an `actions[]` array, and
the client renders exactly that — "the client never computes availability from
a status string." The operations gap map's verification pass found that the
implementation did not hold that rule. `OrderActionsPolicy.availableFor` took
exactly two inputs — the order's status and its fulfilment mode — and no
principal. Every actor holding `ORDER_READ` on a branch was offered every
action legal for the order at that status, regardless of which capabilities
that actor actually held.

The concrete failure: `LOCATION_STAFF` holds `ORDER_READ`, `ORDER_APPROVE` and
`ORDER_ADVANCE`, and deliberately not `ORDER_CANCEL` — cancelling a confirmed
order is a decision about stock disposition and who is liable for it (ADR
0019), and the floor is not meant to make that decision alone. Because the
array was status-only, `LOCATION_STAFF` was offered «Отменить» on every open
order exactly as if it held `ORDER_CANCEL`, and the click was refused with a
403 at `OperationsOrderController`'s cancellation endpoint —
`RequiresCapability`-gated correctly at the point of mutation, but not at the
point of offer. `SUPPORT_AGENT`, `TENANT_FINANCE`, `BRAND_MANAGER` and
`COURIER_DISPATCHER` — none of which holds `ORDER_CANCEL` either — got the
same false offer. An `actions[]` array a client is contractually told never to
second-guess had, for four of five inspected roles, been lying about what it
would let them do.

A second, narrower gap sat beside the first: `OrderActionCode` was closed at
four values (`APPROVE`, `REJECT`, `ADVANCE`, `CANCEL`) while
`OperationsOrderController` already served four more mutations with no code
that could ever reach them from the array — order completion (`POST
.../completion`, ADR 0039 §4.6), amendment proposal and confirmation (`POST
.../amendments`, `POST .../amendments/{id}/confirmation`, ADR 0039 §4.4) and
the bulk-action endpoint. And a terminal order's `actions[]` was correctly
empty, but the console read "empty" as "nothing to show" and hid the row's
overflow trigger entirely (`order-queue.html:151`) — collapsing "no mutation
is legal" into "there is nothing here," when §2.9's own row-state table
already specifies the opposite: `terminal | none [inline] | overflow
(read-only items only)`.

## Decision

**`availableFor` takes the principal's granted capabilities as a third,
required argument**, and every branch that adds an action to the array is
gated on the exact `Capability` constant the corresponding mutating endpoint
declares:

| Code | Gated on | Endpoint | Emitted today |
|---|---|---|---|
| `APPROVE` / `REJECT` | `ORDER_APPROVE` | `POST .../approval-decisions` | Yes |
| `ADVANCE` | `ORDER_ADVANCE` | `POST .../state-actions` | Yes |
| `CANCEL` | `ORDER_CANCEL` | `POST .../cancellations` | Yes |
| `AMEND` | `ORDER_AMEND` | `POST .../amendments` | No — gate built, held behind `OrderActionsPolicy.AMEND_EMISSION_ENABLED` |

No new capability is introduced. `OperationsOrderController` computes the
granted set once per request — one `AuthorizationService.has` call per
capability, at this order's `LOCATION` scope, never once per row — and passes
it into `OrderSummaryResponse.of` for both the list and the detail read, so
the two projections cannot drift into disagreeing about what one order offers.
The set it asks for still includes `ORDER_AMEND` even while `AMEND` itself
stays unemitted, so wave `P10` inherits the wiring already resolved and has
only the policy-side constant to flip.

`OrderActionCode` widens from four values to nine, but only four codes are
emitted today. `AMEND`'s gate is built and tested — `canAmend(status)` mirrors
`OrderAmendmentService.propose`'s own guard, which refuses only a terminal
order, and the branch in `availableFor` reads `grantedCapabilities.contains(ORDER_AMEND)
&& canAmend(status)` exactly like the other four — but adding it to the array
is held behind a named constant, `AMEND_EMISSION_ENABLED = false`, because
`ORDER_AMEND` already reaches five real `PlatformRole`s (`platform/src/main/java/uz/horecaos/platform/iam/api/PlatformRole.java`)
and the console has nothing ready to receive the code: `order-actions.ts`'s
`actionLabel` has no `AMEND` case, so its untranslated `default` branch would
print the raw string `"AMEND"` in every locale, and `order-queue.ts`'s
`onActionClick` would silently no-op the click. That is a real, out-of-language,
permanently dead button reaching real operators — not the "harmless, inert"
case the other four codes are in, because nobody can click them at all (no
`ORDER_ACTION_CODES` case exists for them either, and nothing emits them to
trigger the fallback). Wave `P10` ships the amendment client — a translated
label and a working dialog — and flips `AMEND_EMISSION_ENABLED` to `true`;
nothing else in the gate needs to change. `COMPLETE`, `RESOLVE`,
`ASSIGN_COURIER` and `ISSUE_INVOICE` are declared — named in the enum and in
`orders.md` §11 — but **not yet emitted**, and for the reason the frontend's
`ORDER_ACTION_CODES` union in `order-actions.ts` does **not** also name them:
that union is four values on purpose (`APPROVE`, `REJECT`, `ADVANCE`,
`CANCEL`), documented in the file's own class comment as "the four lifecycle
actions the server can ever offer" today, and `OrderActionResponse.action` is
a plain `string` on the wire rather than a union type — so the frontend does
not need to track the server's still-inert codes to stay safe, and widening it
ahead of real UI for each code would be speculative. Each of the four targets
state `availableFor`'s three-argument signature cannot carry (a
fulfilment-mode-aware completion reason from the tenant registry; one specific
amendment's own confirmation state; a courier assignment path that does not
reach an order yet; an invoice-issue endpoint that does not exist yet).
Declaring them now, inert, means the wire contract and
`OperationsOrderController`'s own class doc name the full action vocabulary
before every one of them has a route, and the frontend's forward-compatible
rendering (an unrecognised code renders its raw name, never nothing) is ready
to receive whichever one ships first without a frontend change — the same
fallback that made `AMEND`'s premature emission a defect rather than a
non-event, because `AMEND` alone reaches real roles today while the other four
reach nobody.

A terminal order's `actions[]` stays correctly empty — no mutating action is
legal on it, and this record does not change that. What changes is the
console's own reading of empty: `order-queue.html` no longer hides the
overflow trigger when the mutating array is empty. Every row's overflow menu
leads with two client-only, capability-free reads — «Открыть» and
«Копировать номер», the first two entries of §2.9's own general overflow list
— ahead of whatever the server-driven array supplies, so a terminal order
gets the read-only menu §2.9's row table specifies rather than no menu at
all. These two items are never sent to the server and never appear in
`actions[]`; they are a client affordance, not a widened capability.

Backward status transitions (IA row 1.1h — putting a mistakenly-advanced order
back a step) are explicitly out of this record's scope. They are gated
behind an ADR 0019 amendment of their own (an override capability, an audited
reason, a narrower actor set than `ORDER_ADVANCE`) and are the whole subject
of the wave that follows this one (`P41`), which inherits the principal-aware
policy this record builds.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep `availableFor(status, mode)` and filter the array in the controller after computing it | Two authorities over one array — the exact drift §4.2 is written to prevent, and the filter would need to know each code's capability anyway, just in a second place | Never; this is the failure mode `OrderActionsPolicy`'s own class doc already names as the reason it lives beside `OrderStateService` |
| Pass the whole `CapabilityView` (every capability the principal holds, platform-wide) instead of a curated four-capability set | Cheaper to wire, but couples this one read model to IAM's entire capability surface and invites a future branch to key off a capability nobody audited for this purpose | If a second read model needs the same broad view, extract a shared "capabilities at this scope" helper rather than widening this one |
| Emit `COMPLETE`/`RESOLVE`/`ASSIGN_COURIER`/`ISSUE_INVOICE` now, computed liberally from status alone (e.g. `COMPLETE` wherever `ADVANCE→COMPLETED` is legal) | Offers a button with no endpoint behind it for three of the four, or duplicates `ADVANCE→COMPLETED` for the fourth — exactly the dead-end §4.2 exists to prevent, and the wave that owns each endpoint (`P09`/`P11`/`P12`) would inherit an already-wrong emission rule rather than an empty one | Each code, individually, the moment its owning wave lands the endpoint it targets |
| Read-only overflow items server-driven, as their own `OrderActionCode`s | Would need a capability, an endpoint that does nothing, and a place in the array for something that is not an authorization decision at all — over-modelling a client-only affordance | If a read-only item ever needs server data (e.g. a receipt URL) rather than data already on `OrderSummaryResponse` |

## Consequences

### Positive

- The 403 the gap map named cannot recur for these five codes: an actor is
  never offered a button its own grants would refuse.
- `OperationsOrderController`, `OrderActionsPolicy` and `OrderActionsPolicyTests`
  now read one definition of "who may see what," proven per role against
  `PlatformRole`'s own capability sets rather than a hand-typed table that
  could drift from them.
- The wire contract names the full action vocabulary orders.md §4 already
  documents, ahead of the waves that wire each remaining code — `P09`, `P10`,
  `P11` and `P12` inherit a named target rather than inventing one.
- A terminal order is never a dead end in the UI: an operator can always open
  it and copy its number, independent of what mutations that status permits.

### Negative

- `OperationsOrderController` now calls `AuthorizationService.has` up to four
  times per request (once per gating capability) rather than zero — a small,
  cached cost `AuthorizationService`'s own contract already accepts ("a pure
  function of the principal's already-cached grants... safe to call several
  times in one request"), paid once per request rather than once per row.
- Five of nine `OrderActionCode` values are declared and inert: `AMEND`
  (gate built, held behind `AMEND_EMISSION_ENABLED` for wave `P10`) and
  `COMPLETE`/`RESOLVE`/`ASSIGN_COURIER`/`ISSUE_INVOICE` (gate not yet built at
  all). A reader of the enum who does not also read its class doc could
  reasonably expect any of the five to appear in a live response today; none
  does.
- `RESOLVE`'s capability is not yet chosen — this record's own Decision table
  above has four rows, not five, and `RESOLVE`'s eventual gate is an open
  input, not a decision made here.

### Accepted trade-offs

Declaring five inert codes ahead of their endpoints (four with no gate yet,
one — `AMEND` — with a built gate switched off) trades a moment of
enum/response confusion (a code that exists but never appears) for a wire
contract that does not have to be renegotiated four more times as `P09`,
`P10`, `P11` and `P12` each land. The alternative — adding each code only when
its endpoint (or, for `AMEND`, its frontend client) ships — is individually
cleaner and was rejected only because the brief for this wave named all five
together; a future reviewer who finds this trade-off wrong should split the
declaration back out per-wave rather than reverting the principal-aware gate
this record actually depends on. `AMEND` itself is the cautionary case for
this trade-off: its gate shipped inert-in-name-only for one build (this
wave's own first commit) before the adversarial review that produced this
text caught that "inert" was not true for a code reaching five real roles
with no frontend to receive it — see the Wire contract note below.

## Specification

**Policy.** `uz.horecaos.platform.ordering.application.OrderActionsPolicy`:

```java
public static List<OrderAction> availableFor(
        OrderStatus status, FulfillmentMode mode, Set<Capability> grantedCapabilities)
```

Each branch checks `grantedCapabilities.contains(Capability.ORDER_X)` before
adding to the array, exactly as tabulated in the Decision section.
`canCancel`/`canCancelWithoutReason` are unchanged; a new package-private
`canAmend(OrderStatus)` returns `!status.terminal()`, mirroring
`OrderAmendmentService.propose`'s own guard. The `AMEND` branch itself is
gated a second way: `AMEND_EMISSION_ENABLED`, a `private static final boolean`
currently `false`, wraps the `ORDER_AMEND`/`canAmend` check so the whole
branch is a one-line flip for wave `P10` rather than a rewrite.

**Controller.** `OperationsOrderController` gains an `AuthorizationService`
collaborator and a package-private `grantedOrderActionCapabilities(tenantId,
brandId, locationId)` that asks `authorization.has(subject, capability,
ResourceScope.location(...))` once per capability in a fixed four-element set
and returns whichever are held. Both `list` and `detail` compute this once and
thread it through `OrderSummaryResponse.of` → `OrderActionResponse.allFor` →
`OrderActionsPolicy.availableFor`.

**Wire contract.** `OrderActionCode` (Java) names nine values today, and only
`orders.md` §11 was widened to match it. The frontend's `ORDER_ACTION_CODES`
(`order-actions.ts`) still names four values (`APPROVE`, `REJECT`, `ADVANCE`,
`CANCEL`) — deliberately, per that file's own class comment — because
`OrderActionResponse.action` is a plain `string` on the wire, not a union
type, so the frontend does not have to track a server code it does not yet
render. Its unhandled-code fallback (`actionLabel`'s `default` case,
`onActionClick`'s `default` case) is real and does render an unrecognised
code as an inert button, but that fallback is **not** correctly labelled: the
`default` branch of `actionLabel` returns the raw code string untranslated,
so any code that reaches it renders in English regardless of locale. That is
tolerable only for a code nothing has ever granted to a real operator; it is
not tolerable for `AMEND`, which `ORDER_AMEND` already grants to five
`PlatformRole`s, and is the reason `AMEND`'s emission is held behind
`AMEND_EMISSION_ENABLED` until wave `P10` gives it a translated label and a
working handler instead of the raw-string fallback.

**Frontend.** `order-queue.html`'s row overflow menu always renders its
trigger and leads with two client-only items — `openFromOverflow` (navigates,
same as the row's own click) and `copyOrderNumber`
(`navigator.clipboard.writeText`, silently swallowing a denial or absence) —
ahead of whatever `overflowActions(order)` supplies from the wire.

## Rollout and rollback

No migration, no capability change, no event change. Rollback is reverting
the three touched files (`OrderActionsPolicy.java`,
`OperationsOrderController.java`, `order-queue.html`) and their tests; nothing
downstream depends on the widened `OrderActionCode` set existing, because
nothing yet emits the five inert values (`AMEND` included, while
`AMEND_EMISSION_ENABLED` stays `false`).

## Implementation checklist

- [x] `OrderActionsPolicy.availableFor` takes `grantedCapabilities` and gates
      `APPROVE`/`REJECT`/`ADVANCE`/`CANCEL` on it and emits them
- [x] `AMEND`'s gate (`ORDER_AMEND` + `canAmend`) is built and tested, but its
      emission stays behind `AMEND_EMISSION_ENABLED = false` until wave `P10`
      ships a translated label and a working handler for it
- [x] `OperationsOrderController` computes the granted set once per request
      from `AuthorizationService` (including `ORDER_AMEND`, ready for `P10`)
      and threads it through list and detail
- [x] `OrderActionCode` widened to nine values, with each inert code's target
      endpoint and blocking wave documented on the constant
- [x] `OrderActionsPolicyTests` extended per role (`LOCATION_STAFF`,
      `SUPPORT_AGENT`, `TENANT_FINANCE`, `BRAND_MANAGER`, `COURIER_DISPATCHER`,
      `LOCATION_MANAGER`) against `PlatformRole`'s own capability sets, plus a
      test asserting `AMEND` is not emitted today regardless of grant
- [x] A Java test that every code `availableFor` can ever emit has a real
      route, exhaustive over `OrderActionCode`'s full value set
- [x] `order-queue.html` renders a read-only overflow menu (`Открыть`,
      `Копировать номер`) on every row, terminal orders included
- [x] A `@SpringBootTest`/`MockMvc` test exercises `actions[]` through the
      real `AuthorizationService`, `RoleRegistrySynchronizer` and Jackson —
      not only the mocked-collaborator unit tests — across the list, board
      and detail reads, including a principal whose action grant is scoped to
      one branch reading another branch's order
- [ ] `AMEND` emitted: `AMEND_EMISSION_ENABLED` flipped to `true` once the
      frontend ships a translated label and a real handler (wave `P10`)
- [ ] `COMPLETE` emitted and wired to `POST .../completion` (wave `P09`)
- [ ] `RESOLVE`'s capability decided and wired to the amendment confirmation
      endpoint (open input above)
- [ ] `ASSIGN_COURIER` reaches an order at all (wave `P11`)
- [ ] `ISSUE_INVOICE` endpoint built and wired (wave `P12`)

## Exit criteria

An actor lacking a gating capability never sees the corresponding code in
`actions[]`, on either the list or the detail read, for any order status or
fulfilment mode — proved today by `OrderActionsPolicyTests` (the pure policy,
per role) and `OperationsOrderControllerActionCapabilitiesTests` (the
controller's question to `AuthorizationService`), and proved end to end,
through the real `AuthorizationService`/`RoleRegistrySynchronizer` and
Jackson, by `OperationsOrderControllerActionCapabilitiesHttpTests`. A terminal
order's row always offers a working overflow menu. Fully met once `AMEND`
(wave `P10`), `COMPLETE` (`P09`), `RESOLVE` and `ASSIGN_COURIER` (`P11`) and
`ISSUE_INVOICE` (`P12`) are each emitted — `AMEND` by flipping
`AMEND_EMISSION_ENABLED` once the frontend can render it, the other four by a
real endpoint — and covered by the same per-role proof.

## References

- `docs/operations-spec/orders.md` §2.9, §4.1–§4.6, §11
- `docs/operations-gap-map.md` rows `1.1e`, `1.2a`; wave `P05`
- ADR 0025 (capability model), ADR 0019 (order lifecycle), ADR 0039 (amendment,
  cancellation and completion outcomes)
