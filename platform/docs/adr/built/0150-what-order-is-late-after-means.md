# ADR 0150: What "order is late after" means

- Decision status: Accepted
- Implementation status: Built — `ordering.late_order_threshold_minutes` (card 2, now
  «Заказ без обещанного времени опаздывает через») is the tenant-wide default for the
  `ordering.lateness` document's no-promise fallback, read by
  `OrderLatenessPolicyService.noPromiseDefaultAt` exactly as the at-risk scalar is read: a
  mode's own document value wins, a blank (the field is now optional, the stored JSON unchanged
  and both generations reading) takes the scalar when one was set anywhere in the chain, the
  narrowest scope winning, and the platform's 45 minutes otherwise; a resolved registry default
  is never read as a choice, and an unusable value (under a minute, over ten hours) is ignored.
  The order board's «Только опаздывающие», `GET .../{orderId}/lateness`, the kitchen queue, both
  VDUs and the wallboard agree on one order at one instant
  (`OrderLatenessScalarSurfacesHttpTests`), acceptance starts and shortens nothing
  (`OrderLatenessPolicyTests`: awaiting-approval and scheduled orders), a scalar write drops the
  cached resolutions beneath its scope (`ConfigurationValueCacheEvictor`; this was not true of a
  scalar before), and the write is validated server-side to 1–600. The authored read reports
  `noPromiseDefault {seconds, source}` beside `atRiskDefault`; card 2's label and hint are
  reworded in ru / uz-Latn / en, «Пока не применяется» is removed from it and stands on every
  card 2 field nothing reads (average, maximum, business-day start, VAT rate, routing poll
  interval); the lateness card's fallback field takes a blank and says what a blank means; the
  console words the late report metric «after the promise» (`orders.late.v1` is unchanged). The
  migration note is `docs/runbooks/late-order-threshold-stored-values.md`, its SQL executed by
  `LateOrderThresholdRunbookTests`. Not done: that runbook has not been run against pre-prod or
  production, and no tenant has been told (operations, before the release is deployed);
  `ordering.average_order_minutes` and `ordering.maximum_order_minutes` have no reader by this
  record's own decision 5, and the key is still declared twice (`OrderingConfigurationKeys` and
  `ConfigurationKeys`) because the registry is internal to tenancy and importing it would make
  the modules cyclic (a drift test guards the pair).
- Date proposed: 2026-10-01
- Date decided: 2026-10-07
- Deciders: proposed by Claude (wave batch 17); Ayubkhon Abbosov (platform owner)
  decides
- Depends on: ADR 0030, ADR 0036, ADR 0041, ADR 0043, ADR 0102, ADR 0144
- Supersedes / Superseded by: — (does not reopen the `ordering.lateness` document
  or `orders.md` §2.7's levels; gives one setting a meaning and a reader)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written.
  - **Whether a tenant already holds a stored value** (operations). The setting
    could be edited while it did nothing, so a pilot tenant may have saved a number
    expecting the old Delever meaning. Proposed default: query for explicit values
    before the reader ships, tell any such tenant, and apply the new meaning.
  - **What `ordering.maximum_order_minutes` and `ordering.average_order_minutes`
    are for** (product). Neither is decided here. Proposed default: both get the
    same «Пока не применяется» hint now, and each is either given a reader by a
    record that names one or removed from the card.
  - **Whether the reports should count graced lateness** (finance, product).
    `orders.late.v1` is promise-adherence at close with no grace. Proposed
    default: unchanged, and its name in the console says "after the promise".

**To accept as written:** say "accept 0150". Every open input above is then
closed on its proposed default.

**Decision record, 2026-10-07.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "lets finish all" over every record still Proposed on this date. Every open input above is closed on the default this record proposes for it; an input that names a person other than the owner, or an external fact (a licence term, a provider capability, a tax treatment, an account that does not exist yet), stays with that owner as written and implementation proceeds without it, marking what waits. Implementation of what this record decides and has not yet built starts in operations batch 19 and 20 (2026-10-07).

## Context

The batch 15 audit left one question for the owner, and the code has been
carrying it in comments ever since: `ordering.late_order_threshold_minutes` is
«Заказ опаздывает с» — "the single most-used value on the order board" in
Delever's console, and the one the order spec says to **match**: *"Late-order
threshold: Match the threshold. It is the right primitive."* What the setting
should mean is "a grace past the promise, or a limit from acceptance — an owner
decision". Meanwhile a *different* mechanism was built around it: the
`ordering.lateness` document, read by the order board, the order detail header, the
kitchen queue, both VDUs, the wallboard, the board's `?late=true` filter (ADR
0144) and `GET …/{orderId}/lateness`. So the platform already has a late line. The
question is what the scalar would add, and whether the answer is "nothing".

**Four readings are available, and each fails somewhere the platform has already
decided.**

*Acceptance-relative: late when `now > accepted_at + N`.* This is what the key's
own description says and what a Delever operator expects. Against it:

- ADR 0036 decided where the clock starts, and said why. `CheckoutOrderWriter`:
  the promise starts "at checkout, and not at confirmation — including for an order
  that will sit in `AWAITING_APPROVAL`… Restarting the clock on acceptance would
  hide exactly the delay a manager needs to see." An acceptance-relative line is
  that restart under another name.
- An order not yet accepted has no `accepted_at`. On this reading an order waiting
  forty minutes for approval is never late, which is the case a manager most needs
  flagged. (It has its own deadline, the acceptance timeout, and appears under
  «Внимание»; but a line named "late" that cannot see it is wrong in the one place
  it matters.)
- A scheduled order (`SCHEDULED_SLOT`, ADR 0047) is accepted hours ahead. An elapsed
  limit from acceptance flags it late long before its slot.
- It is a *second* late line. The existing design deliberately draws one: the
  authoring service's header says "the document's own `lateAfterSeconds` is the only
  late line the boards draw." Two lines need a precedence rule and a second
  explanation on every screen.

*Created-at-relative: late when `now > created_at + N`.* It sees the approval wait
and matches the document's no-promise fallback. It fails the same scheduled-order
case, and it is again a second line for promised orders.

*Promise-relative grace in minutes: late when `now > promised_at + N`.* This is the
coherent reading, and it is already built — it is `lateAfterSeconds`. As the scalar
it would duplicate that field, and its registered default would break it: 45 as a
grace moves every tenant's late line to forty-five minutes past the promise the
moment the reader ships, for tenants who never touched the setting. (The at-risk
scalar avoids this by having a registered default equal to the document's: "5:
exactly the `ordering.lateness` platform default's 300 seconds, so registering the
key changes nothing for a tenant that has not set it".)

*Retire it.* Honest and cheap. It deletes the one knob a Delever migrant looks for,
and the one tenant-wide number that is useful for orders that arrive with no promise
at all.

**The number 45 is the clue.** The scalar's registered default is 45 minutes. The
document's platform default for the no-promise fallback is 2700 seconds, which is 45
minutes, measured from `created_at`. Wave 16 gave the at-risk scalar a job by
making it the tenant-wide default for a document field the document does not set,
precisely because the two defaults agree. The same shape fits here, and the
fallback is the field with the matching default. It is also the right job on the
merits: the orders for which a promise-relative line is undefined are the ones the
scalar can usefully cover. Every marketplace order is inserted `NOT_PROMISED`
(`JdbcMarketplaceOrderIntake`, `JdbcAggregatorOrderStore`), deliberately, because
the partner made the promise; and a native order is `NOT_PROMISED` when "no band
covered the checkout instant and no default was configured" (V0023). For all of
those, "late after N minutes" *is* the Delever primitive, unchanged, and for a
tenant whose volume is mostly aggregator orders it governs most of what the board
shows.

**What other things also say "late", and are not changed.** Reporting's
`orders.late.v1` counts an order whose `closed_at` is after `promised_at` — no
grace, evaluated at close, and not counted at all when there is no promise
(`secondsLate` is null). That is a measurement of promise adherence and stays one.
The kitchen's `target_ready_at` (promise less travel) feeds the ticket's own
severity and is unaffected.

## Decision

**Lateness has one definition, and the scalar is the tenant-wide default for the
part of it that has no promise.**

1. **One definition of late.** For a non-terminal order: with a promise, late when
   `now > promised_at + lateAfter(mode)`; without one, late when
   `now > created_at + noPromiseFallback(mode)`. Acceptance never starts, restarts
   or shortens a lateness clock. An order awaiting approval is late by the same rule
   as any other order, because its clock started at checkout (ADR 0036), and a
   scheduled order is late against its slot, not against the day it was placed. This
   is `orders.md` §2.7 as written; the record states it so the scalar cannot be read
   as an exception to it.

2. **The scalar's meaning.** `ordering.late_order_threshold_minutes` is the
   tenant-wide default for the document's `noPromiseFallbackSeconds`: "an order with
   no promised time counts as late after N minutes." It follows the at-risk
   scalar's rule exactly. A mode's document value wins when the mode carries one; a
   blank in the document means "none of its own", so the scalar applies when a value
   was *set* somewhere in the chain (tenant, brand or location, the narrowest
   winning); otherwise the platform's 45 applies. A resolved registry default is
   never read as something a tenant chose.

3. **The document's fallback becomes optional, like the at-risk window.**
   `ModeThresholds.noPromiseFallbackSeconds` is nullable in the authored form. The
   stored JSON is unchanged and every existing document keeps reading, because the
   values already in it are values "of their own". The concrete
   `OrderLatenessPolicy` the boards read is unchanged in shape.

4. **The screen says what it does.** Card 2's label becomes «Заказ без обещанного
   времени опаздывает через» (and its uz-Latn and English equivalents); the «Пока не
   применяется» hint is removed from it and added to the two siblings; the lateness
   card's fallback field gains the same «blank means the default» line and the
   «default: the value above» reading the at-risk field has.

5. **The siblings are not decided here.** `ordering.maximum_order_minutes`
   ("unambiguously overdue") would be a fourth severity beyond `BLOCKED`, `LATE` and
   `AT_RISK`, which `orders.md` §2.7 does not have and which would need a design for
   its tier; `ordering.average_order_minutes` overlaps the preparation bands that
   already produce the promise. Each stays inert and honest until a record names a
   reader.

6. **Reports keep their meaning.** `orders.late.v1` stays promise adherence at
   close. A board that flags an unpromised order as late and a report that does not
   count it are both correct, and the console says "after the promise" beside the
   report metric.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Acceptance-relative limit (the key's own description) | Restarts the clock ADR 0036 refused to restart; cannot see an order awaiting approval; flags scheduled orders hours early; adds a second late line | The owner wants "time in the kitchen" as its own measured level or metric, not as "late" |
| Created-at-relative ceiling for every order | Sees the approval wait but flags scheduled orders and duplicates the promise line | Never as a second late line |
| Promise-relative grace in minutes | Coherent and already built as `lateAfterSeconds`; as the scalar it duplicates it, and its default of 45 would move every untouched tenant's late line 45 minutes past the promise | The owner prefers to edit the grace in minutes on card 2: re-point the scalar at `lateAfterSeconds` with a registered default of 0 |
| Two lines, whichever comes first (promise line and an elapsed ceiling) | The richest model, and the one Delever's three card-2 numbers imply. Two numbers for one colour, a precedence rule on every screen, and the boards currently guarantee one line | A fourth, "very late" level is wanted |
| Retire the setting | Simplest, no behaviour change. Removes the Delever migrant's landing place and the only tenant-wide control over unpromised orders | The pilot shows no tenant ever sets it |
| Leave it stored and read by nothing | The standing defect the batch 16 audit found in ten of thirteen order-policy keys: a control that changes nothing teaches operators that settings are decoration | Never |
| Count graced lateness in `orders.late.v1` | Grace and fallback are tenant-tunable, so a historical report would move when a tenant edits a setting; the metric is versioned precisely so it does not | A separate versioned metric is wanted |

## Consequences

### Positive

- The setting does what its label says, for the orders it can apply to, and the
  screen stops carrying a field that changes nothing.
- One definition of late, stated once, with the approval-wait and scheduled cases
  decided rather than left to whichever reader is written first.
- No behaviour change for a tenant that never set the value: 45 minutes from
  creation is already the platform default.
- A Delever migrant's habit — set 45, orders go red — works for the orders
  where no promise exists, which is where it is meaningful.

### Negative

- For orders taken through HorecaOS's own channels, which carry a promise, the
  card-2 number does nothing, and an operator who expects "red after 45 minutes"
  must understand that the promise governs. The label says so; some operators will still look for the other
  setting, which now lives in the lateness card.
- A tenant that saved a value while it was inert will see unpromised orders go late
  at that value, which may be earlier or later than they expected.
- The authored document's shape changes (an optional field), and the stored-JSON
  reading must stay tolerant of both generations.
- Two of the three scalars on the card are still inert.

### Accepted trade-offs

- A board flag and a report can disagree about an unpromised order.
- Elapsed-time-in-kitchen is not a lateness concept; if a tenant wants it as a
  measurement it is a separate metric.

## Specification

### Resolution

```text
late(order, now, policy):
  non-terminal only
  promised  -> now > promised_at + policy.forMode(mode).lateAfterSeconds
  unpromised -> now > created_at + policy.forMode(mode).noPromiseFallbackSeconds

policy.noPromiseFallbackSeconds(mode) =
    document[mode].noPromiseFallbackSeconds                         if present
    else minutes(ordering.late_order_threshold_minutes) * 60        if set anywhere in the chain
    else 2700                                                       (platform default)
```

`OrderLatenessPolicyService.noPromiseDefaultAt(scope)` mirrors `atRiskDefaultAt`:
the registered default is never treated as a choice (`cameFromDefault()`), and an
unusable value (< 1) is ignored rather than taking the boards down. The authored
read reports `{seconds, source: SCALAR | PLATFORM_DEFAULT}` beside `atRiskDefault`.

### Changes

- `OrderLatenessDocument.ModeThresholds.noPromiseFallbackSeconds` becomes
  `@Nullable Integer`; `effective(...)` takes both defaults; `violations()` applies
  the one-minute floor only when a value is present; `platformDefault()` is
  unchanged in effect.
- `OrderLatenessPolicyAuthoringService` returns the fallback default and its source;
  publishing a document with a blank fallback is allowed.
- The `?late=true` predicate (`OrderBoardReadModels.withLatenessResolved`), the
  boards, the VDUs and `GET …/lateness` read the same resolved numbers by
  construction; no new endpoint and no new capability.
- The settings cache eviction wave 16 added (a publication drops every brand and
  location resolution beneath its scope) applies to a scalar write as well; this is
  tested rather than assumed.
- Console: card 2's label and hints in ru / uz-Latn / en; the lateness card's
  fallback field; the key-parity spec stays green.

### Testing

- With the scalar unset, an unpromised order is late at 45 minutes from creation
  (today's behaviour, pinned first).
- With the scalar set to 20 at tenant scope, an unpromised order is late at 21
  minutes and a promised order is unaffected — seen failing first.
- A narrower scope's scalar wins; a document's own fallback wins over the scalar; a
  blank document fallback takes the scalar; a stored document from before the change
  still reads.
- An order awaiting approval is late by its promise; a scheduled order accepted
  hours early is not late before its slot.
- The board filter, the order header and the VDU agree for the same order at the
  same instant.
- A stale cached resolution is not served after a scalar write at any scope below
  the one written.

## Rollout and rollback

Query for tenants with an explicit value first and tell them. Then ship the
nullable field and the resolver with the label change; behaviour is unchanged for
everyone who never set the scalar. Rollback is reverting the reader: the key returns
to inert, which is its present state.

## Implementation checklist

- [~] Owner accepts the record (done 2026-10-07); operations queries for explicit stored values (the runbook and its tested SQL exist; not yet run against a real host).
- [x] `OrderLatenessDocument` nullable fallback and `effective(...)`; document
      reading tolerant of both generations.
- [x] `OrderLatenessPolicyService.noPromiseDefaultAt` and its source in the authored
      read; tests listed above, each seen failing first.
- [x] Card 2 label and hints (ru / uz-Latn / en); the same hint on the two sibling
      fields; the lateness card's fallback default line.
- [x] Remove the duplicate registration of the key if the two registries can share
      one definition (`OrderingConfigurationKeys` and `ConfigurationKeys`). They cannot:
      the registry is internal to tenancy (importing it would make the modules cyclic), so
      the pair stays and `OrderingConfigurationKeyTests` guards it.
- [x] Update `orders.md` §2.7 and `settings.md` card 2 where they say the scalar is
      "still stored and read by nothing", and the two comments that name this record's
      question.

## Exit criteria

An operator sets «Заказ без обещанного времени опаздывает через» to 20 for a branch
and the order board, the order header and the kitchen VDU all show an aggregator
order with no promised time as late at 21 minutes; the same branch's promised
orders are unchanged; and no field on card 2 changes nothing without saying so.

## References

- ADR 0030, ADR 0036 (the clock starts at checkout), ADR 0041, ADR 0043, ADR 0047,
  ADR 0102, ADR 0144
- `platform/docs/operations-gap-map.md` rows `X.39`, `10.3b`, `1.1`; the batch 15
  and batch 16 notes on keys with no reader
- `platform/docs/operations-spec/orders.md` §2.7 (levels, `ordering.lateness`) and
  the parity table row "Late-order threshold"; `platform/docs/operations-spec/settings.md`
  card 2; `platform/docs/delever-parity-matrix.md` (late-order detection)
- `OrderingConfigurationKeys`, `OrderLatenessDocument`, `OrderLatenessPolicy`,
  `OrderLatenessPolicyService`, `OrderLatenessPolicyAuthoringService`,
  `OrderBoardReadModels`, `CheckoutOrderWriter`, `DayCloseService`; `V0022`,
  `V0023`; `frontend/operations/src/app/features/settings/order-policy/`
