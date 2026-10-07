# ADR 0161: DataMatrix scan input for marked goods

- Decision status: Proposed — proposed by Claude (batch 19); the platform owner decides
- Implementation status: Not started — the display half of row `X.35` is built
  and the scan half has nothing behind it. `q-qr-code`
  (`frontend/operations/src/app/shared/ui/qr-code.ts`) renders a QR bitmap and,
  by ADR 0119's own sentence, "performs no scanning and no camera access"; no
  file under `frontend/operations/src`, `frontend/storefront/src` or `mobile/lib`
  reads a barcode, calls `BarcodeDetector` or listens for a scanner's keystrokes
  (the only `barcode` in the console is a text field in the product editor,
  `catalog-domain.ts` and `product-editor-page.ts`). The catalog half of marking
  is built: `catalog.fiscal_classifications.marking_required` and
  `marking_scheme` (`NONE` or `DATA_MATRIX`, V0028, with
  `ck_fiscal_classification_marking_agrees`), the `barcode varchar(13)` beside
  them, the partial index `ix_fiscal_classifications_marked`, the publication
  blocker `PHYSICAL_ATTRIBUTES_CONFLICT_WITH_MARKING` (`CatalogValidator`), and
  `catalog.api.FiscalNodeFacts.markedNodes` / `requiresMarkingCapablePayment`,
  implemented by `CatalogFiscalFacts` and **called by nobody**: no payments code
  reads it and `payments.payment_methods.supports_marking`, which ADR 0038 says
  removes Payme from a marked cart, exists in no migration. The receipt half is
  half built: `FiscalReceiptLine.markingCodes` carries Click's `Labels` and
  `ClickFiscalAdapter` sends them, `PaymeReceiptDetail` refuses a marked line
  (`MARKING_CODES_UNSUPPORTED`), but `PartnerFiscalizationBridge` still submits
  one visibly synthetic aggregate line for the whole order, so no real line, and
  no code, reaches a receipt. The capture half is absent: `fiscal.fiscal_unit_marks`
  does not exist (V0039 says so and why), `FiscalReasonCode.MARKS_INCOMPLETE` has
  no producer, `OperationsOrderWeighingController` records that "no such endpoint
  exists yet (marking is not built)", `OrderStateService` holds `FULFILLING` and
  `COMPLETED` behind `CATCHWEIGHT_NOT_RECONCILED` and behind nothing about marks,
  `ordering.order_lines` carries no marking fact, and `AmendmentCommandType`
  refuses `REMOVE_LINES`. One live hazard follows from the gaps: a tenant
  administrator can already set `marking_required` on a variant through
  `PUT .../variants/{id}/fiscal-classification` and sell it, with no capture, to a
  customer paying through Payme. No tenant sells a marked SKU today (V0028 says
  so), which is the only reason it has not mattered.
- Date proposed: 2026-10-07
- Date decided: —
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0007, ADR 0017, ADR 0025, ADR 0026, ADR 0027, ADR 0029,
  ADR 0030, ADR 0031, ADR 0032, ADR 0033, ADR 0035, ADR 0038, ADR 0039,
  ADR 0055, ADR 0056, ADR 0060, ADR 0076, ADR 0079, ADR 0082, ADR 0101,
  ADR 0119, ADR 0137, ADR 0166, ADR 0169. The last two are Proposed and neither
  blocks this record: ADR 0166 (the fiscal evidence surface) owns the retention
  key and the payment-options wiring this record relies on, and ADR 0169 builds
  the line removal and decrease that Decision 10 governs; each is cited where it
  is used and each has a stated fallback if it is not accepted.
- Supersedes / Superseded by: — (amends ADR 0038 and ADR 0166 without editing
  either Accepted text. It reopens exactly two things in ADR 0038: the
  *placement* in that record's physical-model sketch of
  `fiscal.fiscal_unit_marks`, which this record puts in `ordering` as
  `ordering.order_line_marks`, and its open input "the retention period for
  fiscal evidence per legal entity", which for marking evidence is answered by
  ADR 0166's key `fiscal.evidence_retention_years`; this record declares no key
  of its own. It reopens exactly three things in ADR 0166, which is also
  Proposed and is edited in the same change to match: Decision 7's storage
  clause together with the `fiscal.fiscal_unit_marks` entry of its physical
  model (the marks live in `ordering.order_line_marks`, and ADR 0166 builds no
  mark table); the words "as a count and a masked tail" in its Decision 2 (here:
  a count only, no tail); and the words "and marking codes" in its Decision 5
  reveal (here: no screen shows a code and nothing reveals one). ADR 0166's
  wiring of `FiscalNodeFacts.requiresMarkingCapablePayment` into the
  payment-options path is not reopened: it is precondition P1 below. This record
  reopens no row of ADR 0038's Alternatives table: the row "Integrate a fiscal
  operator directly, creating the `FISCAL` category" stays rejected, and
  Decision 9 is written so as not to reopen it. ADR 0079's rule that a kitchen
  device's pairing code is typed, never scanned, is untouched.)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written; the ones that name a person other than the owner stay with
  that person and the work they block is marked.
  - **The structure of a marking code, per product group, in this market**
    (platform owner, with the pilot tenant's compliance contact). This
    repository holds no specification of the Asl Belgisi code; it holds only that
    the symbol is a DataMatrix (`marking_scheme DATA_MATRIX`) and that Click
    accepts it as a string. Proposed default: assume the GS1 element-string
    shape `01`+GTIN-14+`21`+serial (+ further elements after a group separator),
    check exactly that and no more, behind the policy key
    `fiscal.marking.structure_check` (`GS1` default, `NONE` to check only
    character set and length), so a wrong assumption is a configuration change.
    Blocks: nothing in the build; it blocks turning the flag on.
  - **Which product groups the pilot tenant actually sells under marking, and
    from when** (platform owner, pilot tenant, legal). Proposed default: none.
    `feature.marked_goods` stays `false` for every tenant until one names a
    marked SKU, and Decision 7 refuses to publish one meanwhile.
  - **Whether one Click `Item` may carry N labels with `Amount = N`, or marked
    units must be N items of quantity 1** (Click, via the platform owner; ADR 0038
    already lists provider-discovery questions of this kind). The repository's
    Click notes document `Labels` as `string[300]` and cannot say whether 300
    bounds each string or the array. Proposed default: one line, N labels, each
    up to 300 characters, proven in the Click sandbox before the flag is turned
    on; if the sandbox refuses it the projection (Decision 6) splits the line
    and nothing else here changes.
  - **Whether the state system offers a retailer an online check of a code and a
    separate consumption call, and whether HorecaOS may make either on a
    tenant's behalf** (platform owner, counsel). Proposed default: none; the
    sale reaches the state system through the receipt that carries the code (the
    repository's market note says receipts must carry the marking code), and
    `MarkingVerificationPort` answers `NOT_WIRED`.
  - **Retention of marking evidence** (finance, legal; ADR 0038's open input,
    which ADR 0166's retention input answers for all fiscal evidence). Proposed
    default: marking evidence is fiscal evidence and follows the one key ADR 0166
    declares, `fiscal.evidence_retention_years` (ADR 0030 policy, seven years,
    flagged provisional so ADR 0029's production startup check keeps refusing it
    until legal confirms), which also governs the `ordering.order_line_marks`
    rows that the sent `Labels` come from. This record adds no second key. If ADR
    0166 is not accepted, whichever of the two records is implemented first
    declares that same key with that same default and flag and the other cites
    it, so there is never more than one key for one period. Owner: finance and
    legal, who already hold ADR 0166's input.
  - **Which scanner the pilot buys and what it transmits** (platform owner, pilot
    tenant operations). Proposed default: any 2D imager in USB or Bluetooth
    keyboard mode (a 1D laser cannot read a DataMatrix), configured per the
    runbook this record adds; camera capture only where the browser's
    `BarcodeDetector` reports `data_matrix`. Recorded keystroke profiles from the
    chosen model replace the synthetic ones in the tests before the flag is on.
  - **Returns of marked goods** (finance, legal). Proposed default: not supported.
    A code on an issued receipt is consumed on HorecaOS's side for good; a
    returned unit is handled in the state system and the accountant's own
    process until ADR 0038's correction command exists.
  - **Marked modifier options and marked delivery fees** (product). Proposed
    default: refused at publication (`MARKED_MODIFIER_UNSUPPORTED`), because a
    modifier row has a quantity and no line of its own to hold a mark.
  - **Hand-keyed entry of a code** (platform owner). Proposed default: refused,
    `fiscal.marking.allow_keyed_entry = false`; the human-readable text on a
    package does not include the cryptographic tail, so a keyed code is a guess
    at best.
  - **Whether the courier, rather than the branch, scans at pickup** (platform
    owner). Proposed default: the branch scans at the pass; the staff Flutter
    app of ADR 0060 is not built (its shell is an open checklist item, and ADR
    0055 holds Flutter out of the launch scope) and gets the same endpoint when
    it is.

**To accept as written:** say "accept 0161". Every open input above is then
closed on its proposed default.

## Context

Row `X.35` of the operations gap map is `PARTIAL`: its display half is built
(`q-qr-code`: device pairing, the payments `qrPayload`, the printable table QR
card) and the row's own "What is missing" is the other half, "the DataMatrix
marked-goods scan-input half has no endpoint, model or ADR — split out, not a gap
of this wave". Wave `P17`'s own trap says the same in two sentences: "Marked-goods
DataMatrix scanning has no endpoint, no model and no ADR: split it out, do not
grade this wave against it." The IA names the consumer — "4.2/8.2 marked-goods
verification" under "QRCode display + DataMatrix scan input"
(`frontend-information-architecture.md` PART 4) — and the specs name the data:
the fiscal-receipt screen shows `fiscal_unit_marks` count against line quantity
(`operations-spec/orders.md` §3.9) and never a code (`finance.md` §2, 8.2), and
the product editor's fiscal tab carries «Требуется маркировка».

**ADR 0038 decided the shape and built none of the capture.** A marked good is
one identifier per physical unit, so quantity stops being a number the customer
chose and becomes a set of specific objects somebody scans. The order line stays
one line and carries an ordered set of unit identifiers; marks are captured at
`PICK` or `HANDOVER`, never at cart time, because the customer cannot know which
bottle they will receive; the document leaves `PENDING` only when the captured
count equals the line quantity, otherwise it is `BLOCKED` with `MARKS_INCOMPLETE`;
codes are ADR 0029 protected, stored by reference, never logged, never carried in
an ADR 0032 event; and a marked cart removes every payment method whose
`supports_marking` is false, which today means Payme. ADR 0137 then relied on that
shape: catchweight "is captured late, reconciled before the receipt is final", the
"shape ADR 0038 accepted for marking codes", and it built the weighing endpoint
`PUT .../orders/{orderId}/lines/{lineId}/actual-weight` (`order.advance`) as the
sibling of an endpoint that does not exist. What ADR 0038 left to this record is
the part nobody can build from the sketch: where the table lives, what a scan
means on a keyboard or a camera, what is validated and by whom, and what happens
when the symbol will not read.

**The sketch's placement does not survive the module graph.** ADR 0038 puts
`fiscal.fiscal_unit_marks` in the `fiscal` schema. The guard that matters is the
handover guard, which lives where catchweight's lives, in `OrderStateService`
(`requireCatchweightReconciled`, called on the way to `FULFILLING` and
`COMPLETED`); it must read the captured count in the same transaction as the
status change. `fiscal.web.FiscalDocumentController` already imports
`ordering.api.OrderDirectory`, so `fiscal` depends on `ordering`; a guard in
`ordering` that reads a `fiscal` table would close a cycle, and the codebase's
answer to "consumer-declared port, implemented by the other side" (the pattern
`PartnerFiscalizationPort` and `OrderProgressPort` already use) would put a
port call and a second module's transaction on the hottest write path in the
product. The mark is also, truthfully, a fact about an order line captured at the
pass, the sibling of `actual_weight_grams`, not a fact about a document. Placing
`ordering.order_line_marks` in `ordering`, and letting the two modules that need
it read it through an `ordering.api` port, points the dependencies the way they
already point: `fiscal` and `payments` each import `ordering.api` today, and
`ordering` imports neither.

**ADR 0166 sketches the same store, and this record is the one that builds it.**
ADR 0166 (Proposed, written in the same batch) repeats ADR 0038's
`fiscal.fiscal_unit_marks` in its Decision 7 and physical model, has its evidence
surface show the marking codes "as a count and a masked tail", lets its
`fiscal.evidence.reveal` capability return marking codes, and answers the
retention input with `fiscal.evidence_retention_years`. Accepting both records as
written would give two mark stores in two schemas, a screen that shows a tail of
a code beside a record that says no screen ever does, and one retention question
answered by two keys. So the Supersedes line names what this record reopens in
ADR 0166, ADR 0166 is edited in the same change to point here for the store and
for the count-only display, and the retention key is ADR 0166's alone. ADR 0166
keeps everything that is not about storing marks: the payment-options wiring
(P1) and the evidence surface itself, which shows the count that this record's
port provides.

**What the repository cannot establish, and this record therefore does not
assume.** It holds no specification of the Asl Belgisi code, no statement of
which retailers must verify or retire a code and how, and no Click answer on
`Labels` cardinality. Its only market statement is secondary-sourced and marked
"medium confidence" in `horecaos-vs-delever.md`: marking enforcement is
tightening through 2025 and 2026, receipts must carry the product code and the
digital marking code, and e-invoicing gains marking-code auto-verification from
July 2026. The record is built so that every one of those facts, when learned, is
a policy value, a provider-discovery answer or an adapter, not a schema change.

**The module graph and the console set the shape of the input.** The operations
console is where a person stands at the pass: `q-order-weighing-panel` is already
embedded in the order detail and the expo screen, and `q-order-handover-panel`
beside it. Its initial bundle is a budget, cut from 832 kB to 434 kB in batch 18,
so a vendor decoder in the initial chunk is not available. A kitchen display
(`/device`, `PlatformRole.KITCHEN_DEVICE`) holds a device token with a deliberately
narrow grant and is not where a unit is scanned. The staff Flutter app of ADR
0060 is not built, and ADR 0055 holds Flutter out of the launch scope. Inventory
(ADR 0017) is a count and an availability switch, not a ledger of units:
`BINARY`, `UNTRACKED` and, where `catalog.use_stock_logic` is on, `QUANTITY`, a
number per stock item that an operator sets with
`PUT .../variants/{variantId}/on-hand`, where "a physical recount, a delivery
received, breakage found" is one `CORRECTION` movement with a reason. There is
no goods-receipt document, no unit identity and no per-unit stock, and the IA
excludes ingredient-level stock, costing and waste.

## Decision

**Capture one mark per physical unit of a marked order line, at the pass, with a
keyboard-wedge scanner first and the browser's camera where it can read a
DataMatrix; hold the order at the pass until every marked line is complete; send
the codes to the receipt only through a real line projection; and keep the whole
capability behind a per-tenant flag that is off until the payment exclusion and
that projection exist.**

1. **A mark is a row, not a column.** `ordering.order_line_marks` holds one row
   per scanned unit: the line, a sequence, the GTIN in clear (a product
   identifier, not a secret), the code itself as an ADR 0029 `FINANCIAL` envelope
   value bound to its row, a per-tenant keyed lookup hash, the stage
   (`PICK` or `HANDOVER`), the entry method, the actor and the time. A code is
   unique among *live* marks of a tenant, and a mark is **released, never
   deleted**: a removal, a cancellation or a line replaced by an amendment stamps
   `released_at` and a reason, and the same physical unit can then be scanned
   again. Placement in `ordering` amends ADR 0038's sketch as the Supersedes line
   states.

2. **One guard, at the one call site that already exists.** `OrderStateService`
   refuses `FULFILLING` and `COMPLETED` with `MARKS_INCOMPLETE` (naming the line
   ids, as `CatchweightNotReconciledException` does) while any live marked line
   has fewer live marks than its integer quantity. There is no bypass: an
   unscanned marked sale is the breach this exists to prevent, and a supervisor
   override would be the way it happens. Bulk advance reports the same code per
   order. The count and the status change are made under the order row's lock,
   which this record adds (Guard, release and amendment), so a concurrent
   removal cannot slip between them. The requirement is read **live** from the
   catalog classification
   through `FiscalNodeFacts.markedNodes`, not snapshotted onto the line: three
   code paths insert order lines (`JdbcOrderStore`, `JdbcAggregatorOrderStore`,
   `JdbcMarketplaceOrderIntake`) and a snapshot written by two of them would be a
   guard that silently skips the third. A line that already holds a live mark
   stays marked whatever the catalog later says.

3. **Where a unit is scanned: the pass and the handover, in the operations
   console, and nowhere else in v1.** `q-order-marking-panel`, a sibling of the
   weighing panel, is embedded in the order detail and the expo screen. The stage
   is derived by the server, not chosen by the client: `PICK` while the order is
   `CONFIRMED` or `PREPARING`, `HANDOVER` once it is `READY` (the statuses
   `CatchweightReconciliationService` already treats as weighable, and
   `FULFILLING` and later are refused for the same reason that service gives). A
   counter pickup, a table delivery and a courier collecting at the branch all
   pass through the same screen. **Inventory is not a scan point in v1**: a
   delivery is a counted adjustment of a number, so a scan has no unit to attach
   to. The courier's own app, the kitchen display and both storefronts
   never scan.

4. **The scan input is one shared component that never keeps what it reads.**
   `q-datamatrix-input` in `shared/ui/`, beside `q-qr-code`, emits
   `{ raw, method }` and clears its own buffer in the same tick. It has two
   sources: a focused, visually hidden field fed by the scanner's keystrokes
   (`SCANNER`, the default, which needs no permission and no decoder), and
   `BarcodeDetector` with `formats: ['data_matrix']` where
   `getSupportedFormats()` lists it (`CAMERA`, lazy-loaded, feature-detected, and
   absent without apology on a browser that cannot do it). It bundles no decoder
   library. It performs no network call, writes nothing to storage, the console or
   the DOM beyond a masked progress count, and never echoes a code. A pasted or
   slowly typed code is classed `KEYED` and refused by default.

5. **Validation is layered, and the server is the authority.** The same
   normalisation runs in the browser (instant feedback) and on the server
   (authoritative), proved by one shared vector file: strip a leading AIM
   identifier (`]d2` or `]d1`), strip a trailing terminator, keep the GS control
   character (U+001D), accept only U+0020 to U+007E and GS, length 19 to 300.
   The server then checks that the line is live, belongs to the order and the
   caller's location, is marked, and still needs a mark; that the order is in a
   markable status; the structure (`01`, a GTIN-14 with a valid GS1 check digit,
   `21`, a serial of 1 to 20 characters) when
   `fiscal.marking.structure_check` is `GS1`; that the GTIN matches the
   classification's `barcode` once left-padded to 14 digits (`MARKING_CODE_WRONG_PRODUCT`
   when it does not, and an unchecked-GTIN flag on the response when the
   classification has no barcode); and uniqueness among live marks. Nothing
   cryptographic is verified: the tail is carried, never interpreted.

6. **Codes reach the receipt through the projection, never around it, and the
   projection lives in `payments`.** `payments.application`, beside
   `PartnerFiscalizationBridge` (the implementation of
   `fiscal.api.PartnerFiscalizationPort`), reads the live marks of an order
   through `ordering.api.OrderMarksPort` and builds one `FiscalReceiptLine` per
   marked order line with `quantity = N` and `markingCodes` in sequence order,
   decrypting once per document under one audited purpose. `FiscalReceiptLine`,
   `FiscalDocument` and the bridge are `payments` types and `fiscal` imports
   nothing from `payments`, so a projection in `fiscal` would add the
   fiscal-to-payments edge that closes the cycle Context uses to move the table;
   payments already imports `ordering.api` and `fiscal.api`, so this one stays
   payments to ordering and payments to fiscal. A marked order **never takes the
   synthetic line**: when a marked line holds fewer live marks than its quantity
   (or the port cannot be read), the bridge builds nothing, sends nothing and
   answers a new `PartnerFiscalizationPort.Outcome` value, `BLOCKED_MARKS`.
   `FiscalObligationService.settle`, whose switch over `Outcome` is exhaustive so
   the compiler finds every site, maps it to `documents.blockUnsent(...,
   FiscalReasonCode.MARKS_INCOMPLETE, ...)` the way it already maps
   `NO_PROVIDER_PATH` to `NO_FISCAL_PATH`, and the operator's retry reports the
   outcome and leaves the document `BLOCKED`. `MARKS_INCOMPLETE` thereby gets its
   first producer, which is also the defence in depth for an order that completed
   through a path the guard did not see. `CLASSIFICATION_MISSING` is not used: it
   says a priceable node has no ИКПУ, which is a different fact.

7. **A launch valve, default closed.** `feature.marked_goods` (ADR 0082) is
   `false`. While false, publishing a classification with `marking_required`
   is refused (`MARKED_GOODS_NOT_ENABLED`), the capture endpoints answer
   `FEATURE_DISABLED`, and the handover guard is skipped, so a tenant with no
   marked SKU pays nothing for any of this. The platform turns it on for a tenant
   only when three preconditions hold: **P1** payments reads
   `FiscalNodeFacts.requiresMarkingCapablePayment` and a `supports_marking`
   registry flag removes Payme from a marked cart (built by ADR 0166's Decision
   7, and under ADR 0038's stage 4 to 6 if that record is not accepted; this
   record does not build it); **P2** the order-line to `FiscalReceiptLine`
   projection replaces the synthetic line (for a marked order, Decision 6 is that
   projection; the real line builder for every other order is ADR 0166's
   Decision 6); **P3** the Click sandbox has accepted a receipt with `Labels`.
   This closes today's hazard.

8. **No personal data and no code outside the protected row.** A marking code is
   evidence and is treated as a secret-adjacent identifier: it is never in a log,
   metric label, trace, error body, URL, query string, event, export or screen.
   No endpoint returns one; progress responses carry the GTIN, the sequence and
   the time. There is no human reveal in v1, so there is no reveal capability,
   and ADR 0166's `fiscal.evidence.reveal` returns no marking code either (its
   evidence block shows the count). The only decryption is `payments`', for a
   receipt (Decision 6). The scan carries no
   customer, address, phone or note, and the audit fact names the staff actor and
   the line, not the customer.

9. **The state system is a seam, not a call.** `fiscal.api.MarkingVerificationPort`
   is declared and implemented by an unwired stand-in that answers `NOT_WIRED`
   (the way `UnwiredPartnerFiscalization` does), with a fake for tests under ADR
   0007. No `ProviderCategory` is added: ADR 0026 declined a `FISCAL` category and
   ADR 0038 rejected making HorecaOS an operator, and this record does not
   reopen either by the back door. Making it real is a later record.

10. **A damaged symbol has an answer and it is not a bypass.** The operator scans
    another unit, or the order does not leave the pass. A scanned unit can be
    removed (`POST .../marks/{markId}/removals`, a coded reason, audited) while
    the order is still `CONFIRMED`, `PREPARING` or `READY`. A line that already
    holds a live mark cannot be changed by `CHANGE_LINE_QUANTITY` (it closes the
    line and inserts a replacement, which would orphan the marks), refused as
    `MARKS_ALREADY_CAPTURED`; adding units is `ADD_LINES`, a new line with its own
    scans. The refusal covers every amendment command that closes or reduces a
    line, so it covers the `REMOVE_LINES` and the line decreases that ADR 0169
    (Proposed) would build once they exist: the operator releases the line's
    marks through the removal endpoint first, then amends and rescans, and ADR
    0169 adds no marking logic beyond calling the same check. Carrying marks
    across a replacement is the reserved `LINE_REPLACED` and is not built here.
    Cancelling an order before handover releases its marks.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep `fiscal.fiscal_unit_marks` in `fiscal`, exactly as ADR 0038 sketched it | `fiscal` imports `ordering.api` already; the handover guard in `OrderStateService` would need a `fiscal` read, which is a module cycle or a port call plus a foreign transaction on the order-advance path | The fiscal module stops depending on `ordering.api`, or the guard moves out of `OrderStateService` |
| Marks as a `text[]` or `jsonb` column on `ordering.order_lines` | An array cannot carry one AAD-bound ciphertext per code, cannot enforce "unique while live" per tenant, cannot be released one unit at a time and cannot be audited per unit; `order_lines` also holds no UPDATE grant beyond a few named columns | Never |
| Snapshot `marking_scheme` onto the order line at placement | Three insert sites exist (`JdbcOrderStore`, `JdbcAggregatorOrderStore`, `JdbcMarketplaceOrderIntake`) and a missed one is a marked line that never blocks; a live read cannot be missed | The catalog classification becomes versioned with an effective date, so "marked when ordered" is a fact worth freezing |
| Expand N marked units into N order lines of quantity 1, as the IA says the POS path does | Changes line counts, kitchen tickets, receipt depth and every report; ADR 0038 chose one line with an ordered set | Click's sandbox refuses N labels on one item (open input 3); the expansion then lives in the receipt projection, not the order |
| Capture at cart or checkout | The customer does not choose the bottle; ADR 0038 rejects it | Never |
| Bundle a JavaScript or WebAssembly decoder for camera scanning as the primary path | Spends the initial-bundle budget batch 18 just won back, adds a supply-chain dependency the platform must keep patched, and a pass has a scanner on the counter anyway | The pilot's devices lack `BarcodeDetector` support for `data_matrix` and the tenant will not buy imagers; the decoder is then lazy-loaded behind the same component |
| Verify every code online with the state system before accepting it | The repository holds no retailer API to build against; it needs a new provider category ADR 0026 declined for fiscal reasons; and it makes handover depend on a third party's availability | The operator publishes a retailer API, counsel confirms HorecaOS may call it for a tenant, and a tenant asks |
| Allow a hand-keyed code as the fallback for a damaged symbol | The printed text omits the cryptographic tail, so the keyed code is not the code; a fallback that cannot be right invites a plausible wrong one | The state system documents a manual procedure |
| Scan at inventory receiving and stock count in v1 | A delivery is `PUT .../on-hand`, a number with a reason; there is no receipt document and no unit identity, so a scan would have no row to write to | A receiving document with per-unit identity is added to ADR 0017's ledger and a tenant must confirm receipt of marked goods in the state system |
| The courier scans at pickup, in a native app | The staff Flutter app of ADR 0060 is not built and ADR 0055 holds Flutter out of the launch scope; a courier outside the branch also cannot fix a damaged symbol | ADR 0060's app is built; it then calls the same endpoints under the same capability |
| A supervisor bypass for `MARKS_INCOMPLETE`, as a damaged-code escape | It is the breach, with a button; catchweight has none either | Counsel states a lawful exception and its evidence |
| Store only a hash of each code | A receipt has to carry the code, and a hash cannot be sent to Click | Never |
| Build the receipt projection in `fiscal.application` | `FiscalReceiptLine`, `FiscalDocument` and `PartnerFiscalizationBridge` are `payments` types and `fiscal` imports nothing from `payments`; constructing them from `fiscal` adds the fiscal-to-payments edge that closes the cycle the placement rule removes, and `CLASSIFICATION_MISSING` names a different fact from an incomplete mark set | `FiscalReceiptLine` moves to `fiscal.api`, or `payments` stops owning the receipt adapters |
| Make the guard race-free by bumping `ordering.orders.version` on every scan and removal and adding `AND version = :expectedVersion` to `transition` | Every scan would make the version the operator's screen holds stale, so the advance button would answer `STALE` after each scan of a five-unit line, and the change to `transition` touches every status transition in the product for one guard | Order transitions move to optimistic versioning for another reason |
| Count the marks without a lock, as `requireCatchweightReconciled` counts weighed lines | A weighed line is never un-weighed, so catchweight can read and then transition; a mark can be released, so a removal that commits between the count and the status compare-and-set would hand over an order with fewer live marks than units | Never |

## Consequences

### Positive

- The second half of `X.35` has an endpoint, a model and a component, and the
  first real consumer of `q-qr-code`'s sibling arrives with a guard behind it.
- ADR 0038's `MARKS_INCOMPLETE` stops being a constant with no producer, and
  ADR 0137's reference to a mark-capture endpoint stops being a forward
  reference to nothing.
- The platform can say, per order line, that the units on a receipt are the units
  scanned, each one once, without any person ever being able to read a code.
- The hazard of a marked SKU sold with no capture through a provider that cannot
  carry the code is closed by a flag that defaults off and a publication blocker,
  not by a convention.
- A tenant with no marked goods pays one flag read per handover and nothing else.

### Negative

- Every marked unit costs a scan at the pass, and a damaged symbol stops an order
  with a customer waiting (ADR 0038 predicted exactly this). The answer is
  another unit, and when there is no other unit the answer is removing the line,
  which `AmendmentCommandType` does not yet allow (`REMOVE_LINES` is refused for
  want of an ADR 0017 return-to-stock primitive, which ADR 0169 proposes to
  build), so the order is cancelled.
- Three preconditions (P1 to P3) are outside this record and gate its use; until
  they land the capability is built, tested and switched off.
- A new table on the order path, with a unique index on a keyed hash, a column
  grant and a new event. It has no row-level-security policy in v1 (Physical
  model), so its tenant isolation is the application's, as every `ordering`
  table's is today, until ADR 0056's rollout reaches that schema.
- Every capture and removal, and the handover guard, take the order row's lock
  (Guard, release and amendment), so a scan and a status change on one order wait
  for each other. The lock is held for one insert or one count, and an order with
  no marked line never takes it.
- The keyboard-wedge path trusts the scanner's configuration: a scanner that
  drops the group separator makes a valid code unparseable, and the failure looks
  like a bad code. The runbook and a diagnostic line in the panel are the only
  defences.
- Camera capture works on some devices and not others, and cannot be tested in
  CI against a real sensor.
- The structure check encodes an assumption about the code that this repository
  cannot verify; the policy key makes it cheap to correct, not impossible to get
  wrong on day one.

### Accepted trade-offs

- A code on an issued receipt is consumed on HorecaOS's side for ever, so a
  returned marked unit cannot be re-sold through HorecaOS until a correction path
  exists. The alternative, releasing it, risks selling one unit twice.
- The requirement is read live, so a reclassification mid-service changes what an
  open order needs. That is the legally correct direction, and the cost is that
  an order can become blocked by a catalog edit.
- No online verification means a counterfeit code that is well formed is accepted.
  Detecting it was never HorecaOS's job in this build; carrying it faithfully is.
- Keyed entry is refused even though a tired operator will ask for it. The
  alternative is a guessed code on a legal document.

## Specification

### Physical model

One Flyway migration, numbered at implementation time: the next free number
after `V0489` once every active worktree's `db/migration/` has been checked. The
gap map's PART C reserves no number above `V0489`, and sibling worktrees already
hold `V0490`, `V0493` and `V0494`, so no number is named here. No earlier
migration is edited. Every row carries `tenant_id` and every foreign key is
composite on `(.., tenant_id)`.

**No row-level-security policy in v1.** ADR 0056 ratifies application-enforced
tenant isolation and has rolled the database backstop out to `inventory` only;
no `ordering` table has a policy. The policy reads
`tenant_id = current_setting('horecaos.tenant_id')`, so a transaction that has
not bound a tenant sees no rows and cannot insert, and binding is a per-method
call (`rls.bindTenant` first, `bindPlatform` for a cross-tenant sweep). Turning
it on for this one table would make it the first `ordering` table to need a bind
on every path that touches it, and an unbound path would count zero live marks,
which the guard reads as "every marked order is incomplete". The table is
isolated as the rest of `ordering` is, by a `tenant_id` predicate in every
statement, the composite foreign keys and `TenantScopedReferenceCatalogTests`,
and it joins `ordering`'s RLS wave when ADR 0056's rollout reaches the schema.
The bind points for that day are listed now so it is a statement and a bind and
not a hunt: `rls.bindTenant` in `OrderStateService.advance` and
`OrderBulkActionService` (the guard's count), capture, removal, the read
endpoint, the cancellation release and the payments projection's read (with the
document's tenant); `bindPlatform` in the order-expiry sweep that releases marks.

```sql
CREATE TABLE ordering.order_line_marks (
    id                  uuid PRIMARY KEY,            -- Ids.newId(), time-ordered, ADR 0076
    tenant_id           uuid NOT NULL,
    order_id            uuid NOT NULL,
    order_line_id       uuid NOT NULL,
    sequence            integer NOT NULL,            -- 1..N within the line, gaps allowed after a release
    marking_scheme      varchar(16) NOT NULL DEFAULT 'DATA_MATRIX',
    gtin                varchar(14) NOT NULL,        -- INTERNAL: a product identifier
    code_encrypted      text NOT NULL,               -- ADR 0029 FINANCIAL, AAD-bound to ordering.order_line_marks.code_encrypted:<id>
    code_lookup_hash    varchar(64) NOT NULL,        -- lookupHash(tenant, 'marking-code', canonical code)
    capture_stage       varchar(8)  NOT NULL,        -- PICK | HANDOVER, derived from the order's status
    entry_method        varchar(8)  NOT NULL,        -- SCANNER | CAMERA | KEYED, client-reported and advisory
    gtin_checked        boolean     NOT NULL,        -- false when the classification had no barcode
    captured_by_actor_type varchar(16) NOT NULL,
    captured_by_actor_id   varchar(255) NOT NULL,
    captured_at         timestamptz NOT NULL,
    released_at         timestamptz,
    released_reason     varchar(24),                 -- MISSCAN | UNIT_REPLACED | UNIT_DAMAGED | ORDER_CANCELLED | LINE_REPLACED
    CONSTRAINT fk_order_line_mark_line FOREIGN KEY (order_line_id, tenant_id)
        REFERENCES ordering.order_lines (id, tenant_id),
    CONSTRAINT ck_order_line_mark_scheme CHECK (marking_scheme IN ('DATA_MATRIX')),
    CONSTRAINT ck_order_line_mark_stage  CHECK (capture_stage IN ('PICK', 'HANDOVER')),
    CONSTRAINT ck_order_line_mark_method CHECK (entry_method IN ('SCANNER', 'CAMERA', 'KEYED')),
    CONSTRAINT ck_order_line_mark_gtin   CHECK (gtin ~ '^[0-9]{14}$'),
    CONSTRAINT ck_order_line_mark_release CHECK ((released_at IS NULL) = (released_reason IS NULL))
);
CREATE UNIQUE INDEX uq_order_line_marks_live_code
    ON ordering.order_line_marks (tenant_id, code_lookup_hash) WHERE released_at IS NULL;
CREATE UNIQUE INDEX uq_order_line_marks_live_sequence
    ON ordering.order_line_marks (order_line_id, sequence) WHERE released_at IS NULL;
CREATE INDEX ix_order_line_marks_order
    ON ordering.order_line_marks (tenant_id, order_id) WHERE released_at IS NULL;
GRANT SELECT, INSERT ON ordering.order_line_marks TO horecaos_application;
GRANT UPDATE (released_at, released_reason) ON ordering.order_line_marks TO horecaos_application;
```

No `DELETE` grant: evidence is released, not removed, as V0450 argued for the
weighed columns. The `@Classified` scan (ADR 0029, `ClassificationScanner`)
declares `code_encrypted` `FINANCIAL` and `gtin` `INTERNAL`, and the
`EventPayloadClassificationTests` and the log-canary test below hold the line
mechanically. A customer erasure (ADR 0029) leaves marks alone: they are not
personal data, and the order-to-customer link is anonymised by the existing path.

### Normalisation and structure

```text
normalise(input):
  s = input
  if s starts with "]d2" or "]d1": drop those three characters
  s = s without trailing CR, LF, TAB, and surrounding spaces
  refuse unless every character is U+0020..U+007E or U+001D, and 19 <= length <= 300
  return s                                  # the canonical string: stored, hashed, sent

structure(canonical, policy GS1):
  "01" + 14 digits with a valid GS1 mod-10 check digit
  + "21" + serial (1..20 characters, up to the first U+001D or the end)
  + optionally U+001D and further elements, carried verbatim and never interpreted
```

The GTIN match left-pads the classification's `barcode` (an EAN-8, UPC-A or EAN-13
as stored) with zeros to 14 digits. The vector file
`platform/src/test/resources/marking/normalisation-vectors.json` holds accepted
and refused inputs, each with the canonical output; the Angular spec reads a
byte-identical copy and `tools/checks/repo_hygiene.py` fails when the two differ.

### API

Under the same path prefix and OpenAPI group as `OperationsOrderWeighingController`
(`/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders/...`),
ADR 0031 conventions throughout: problem+json with a stable `code`, `Idempotency-Key`
required on every mutation, the code only ever in a request body. The
capabilities are ADR 0025's and none is added: the two the weighing endpoint
and the order read already declare.

| Call | Capability | Notes |
|---|---|---|
| `POST .../orders/{orderId}/lines/{lineId}/marks` body `{ code, entryMethod }` | `order.advance`, `LOCATION`, mutating | Takes `SELECT ... FOR UPDATE` on the order row before it reads the order's status or counts anything, the lock the handover guard takes too (Guard, release and amendment), so a scan, a removal and a status change serialise. Replaying the same `Idempotency-Key` returns the first answer. A new key with a code already live on this line answers `MARKING_CODE_ALREADY_CAPTURED` with `onThisLine = true` so the panel says "already scanned" rather than "duplicate". 201 returns `{ markId, sequence, gtin, captured, required, orderMarksComplete, gtinChecked }` |
| `POST .../orders/{orderId}/lines/{lineId}/marks/{markId}/removals` body `{ reasonCode }` | `order.advance`, `LOCATION`, mutating | Takes the same order-row lock first and reads the status after it. Releases, never deletes. Refused once the order is `FULFILLING` or later (`ORDER_NOT_MARKABLE`). Reason codes are a code-owned list; no free text, because free text is where a customer's name arrives |
| `GET .../orders/{orderId}/marks` | `order.read`, `LOCATION` | Per marked line: `required`, `captured`, and the marks as `{ markId, sequence, gtin, stage, capturedAt, capturedBy }`. Never a code |

Refusals, all 409 unless stated: `FEATURE_DISABLED` (404-shaped to a tenant that
does not have it), `MARKING_CODE_MALFORMED` (400), `MARKING_CODE_WRONG_PRODUCT`,
`MARKING_CODE_ALREADY_CAPTURED`, `MARKING_KEYED_ENTRY_REFUSED`, `LINE_NOT_MARKED`,
`LINE_MARKS_COMPLETE`, `ORDER_NOT_MARKABLE` (status), `MARKS_INCOMPLETE` (on an
order transition, carrying `orderLineIds`), `MARKS_ALREADY_CAPTURED` (an
amendment that would replace a scanned line), `MARKED_GOODS_NOT_ENABLED` (a
publication). A limiter bucket `marking.capture` allows 240 captures a minute per
principal (ADR 0033); beyond it the answer is 429 with `Retry-After`. The order
read DTO's lines gain `marking: { required, captured }` and nothing else.

### Guard, release and amendment

`OrderStateService.requireMarksComplete` sits beside `requireCatchweightReconciled`
and runs on the same targets (`FULFILLING`, `COMPLETED`), after it, only when
`feature.marked_goods` is on for the tenant. It asks the catalog for the marked
variants among the order's live lines (one indexed query on
`ix_fiscal_classifications_marked`) and, when the order holds a marked line or a
live mark (one indexed query on `ix_order_line_marks_order`), **locks the order
row and then counts** live marks per line. It throws
`MarksIncompleteException(lineIds)`, mapped in `OperationsOrderController` like
its catchweight sibling and reported per order by `OrderBulkActionService`.

**The guard runs under the order row's lock, because `advance` takes none.**
`OrderStateService.advance` reads the order with a plain `find`, compares the
caller's version in Java, runs `requireCatchweightReconciled` and then calls
`orders.transition`, whose `UPDATE ordering.orders ... WHERE tenant_id = :tenantId
AND id = :id AND status = :from` is a status-only compare-and-set: no version
predicate, and no lock before it. That is safe for catchweight, because a weighed
line is never un-weighed. It is not safe for marks, because a mark can be
released: a removal that commits between the guard's count and the
compare-and-set leaves the status unchanged, the compare-and-set still wins, and
the order leaves the pass with fewer live marks than units. So this record adds
the lock instead of relying on one. `requireMarksComplete` begins with
`SELECT 1 FROM ordering.orders WHERE tenant_id = :tenantId AND id = :orderId FOR UPDATE`
(a new `OrderStore.lockForUpdate`) inside the advance transaction, before it
counts; the capture and removal endpoints take the same lock first and read the
order's status only after they hold it. Whoever holds the lock first wins: a
removal that commits first leaves the guard counting one fewer and refusing
`MARKS_INCOMPLETE`; a guard that holds the lock first makes the removal wait, and
the removal then finds the order `FULFILLING` and is refused `ORDER_NOT_MARKABLE`.
A status change by another transition that commits while `advance` waits is still
caught by `transition`'s own `status = :from` compare-and-set, which answers
`STALE`. The status `UPDATE` of a cancellation, rejection or expiry already holds
the same row lock, so the release that follows it in that transaction takes no
second one. The other way to close the race, a version bump on every scan, is
rejected in Alternatives considered.

A cancellation, rejection or expiry of an order that has not reached `FULFILLING`
releases its marks (`ORDER_CANCELLED`) in the same transaction; one that has
keeps them. An amendment (ADR 0039's engine, Decision 10 above) that closes or
reduces a line holding a live mark is refused before it writes anything;
`LINE_REPLACED` is reserved for the day an amendment may carry marks forward and
is written by nothing in v1.

### Receipt projection

```text
ordering.api.OrderMarksPort            liveMarks(tenantId, orderId)  -> per line: gtin, sequence, code_encrypted ref
                                       markCounts(tenantId, orderId) -> per marked line: required, captured (no code, no ref)
payments.application                   beside PartnerFiscalizationBridge: projects one FiscalReceiptLine per marked
                                       order line: quantity = N, markingCodes = [N canonical codes in sequence order]
fiscal.api.PartnerFiscalizationPort    Outcome gains BLOCKED_MARKS (Decision 6)
fiscal.application                     reads markCounts only: the worklist, and reopening a MARKS_INCOMPLETE document
```

The dependencies stay payments to ordering, payments to `fiscal.api` and fiscal to
ordering. `fiscal` imports nothing from `payments` and `ordering` imports neither,
which `ModularArchitectureTests` asserts (Testing). The codes are decrypted in
`payments`, through the ADR 0029 protected-value service, once per document, under
the purpose `fiscal-receipt` and a system principal, and `payments` writes exactly
one ADR 0027 fact per document (`payments.document.marking_codes_disclosed`:
count, document id, no code). The exact `Items` array that was sent already
sits behind `fiscal.fiscal_documents.protected_request_reference` (V0027), which
now contains `Labels`; it inherits that reference's protection and retention.
The projection refuses a marked line whose payment path is Payme
(`MARKING_CODES_UNSUPPORTED`, as `PaymeReceiptDetail` already does), which is P1's
safety net rather than its substitute. The fiscal worklist and receipt screen
(8.2), and ADR 0166's evidence block, show `captured` against `required` per
blocked document from `markCounts` and never a code or a tail.

### Events, audit, policy

- **Event** (ADR 0032, through the outbox): `OrderMarksCompleted.v1` on
  `ordering.events`, key `orderId`, payload `eventId`, `eventType`,
  `eventVersion`, `tenantId`, `orderId`, `brandId`, `locationId`,
  `markedLineCount`, `markedUnitCount`, `completedAt`. Identifiers and counts only.
  It is a nudge: the fiscal module re-reads `markCounts` (counts only, nothing
  decrypted) to reopen a `MARKS_INCOMPLETE` document through
  `FiscalDocumentService.reopen`, and the board refreshes through the existing
  realtime signal; a removal after completion publishes nothing and consumers
  treat the current count as truth. Schema under
  `src/main/resources/events/ordering.events/`, a frozen baseline copy, a
  catalogue entry in `EventCatalog` and a row in `docs/domains/events.md`.
- **Audit** (ADR 0027, class `BUSINESS`): `ordering.order.line_mark_captured`
  (line, sequence, stage, entry method, GTIN, `gtin_checked`; no code),
  `ordering.order.line_mark_removed` (reason code), `ordering.order.marks_released`
  (order cancelled, count), `payments.document.marking_codes_disclosed` (document,
  count; actor is the system principal; written by `payments`, which decrypts). Each carries the ADR 0027 change
  document and `usingCapability`.
- **Policy** (ADR 0030, declared in the module's `ConfigurationKeys` and in the
  tenancy registry, with a drift test as `OrderingConfigurationKeyTests` does):
  `fiscal.marking.structure_check` (`GS1` | `NONE`, default `GS1`),
  `fiscal.marking.allow_keyed_entry` (boolean, default `false`). Retention of
  marking evidence is not a key of this record: it is ADR 0166's
  `fiscal.evidence_retention_years` (seven years, provisional), as the retention
  open input says. Flag: `feature.marked_goods` (ADR 0082), default `false`.
- **Metrics**: `horecaos.marking.capture` with tags `outcome` and `stage` only; no
  GTIN, no order, no code.

### The state-system seam

```text
fiscal.api.MarkingVerificationPort     verify(tenantId, canonicalCode) -> VERIFIED | REJECTED | UNCERTAIN | NOT_WIRED
```

Unwired in v1; the contract tests (ADR 0007 genre) run against a controlled fake
that includes the accepted-then-lost reply and a malformed body. No route
descriptor is added until an adapter exists.

### Front-end contract

`q-datamatrix-input` (`frontend/operations/src/app/shared/ui/`, the console's
own in-app component library of ADR 0101, on ADR 0035's design system;
standalone, `OnPush`, signals, the `t` pipe, ru, uz-latn and en strings):

| Input | Behaviour |
|---|---|
| `armed` (boolean), `remaining` (number) | Focus is taken and held while armed; Escape disarms; `remaining = 0` disarms |
| Keystrokes from a scanner | Built from `keydown` `event.key`, not from a field value, because a field strips control characters; `Ctrl+]` and a literal U+001D both become GS; Enter or Tab ends a scan; a gap over 100 ms between characters, or a paste, classes the scan `KEYED` |
| Camera | Offered only when `BarcodeDetector.getSupportedFormats()` includes `data_matrix`; the module is a dynamic import so it never touches the initial chunk; the stream is stopped on disarm and on destroy |
| Output | `scanned({ raw, method })`, once per scan; the component's buffer is empty before the handler runs |
| Never | storage, `console`, the DOM, the URL, an `autocomplete` hint (`autocomplete="off"`, `inputmode="none"` so a tablet's soft keyboard stays down) |
| Accessibility | An `aria-live` region announces "2 of 5 scanned" and "refused"; a short tone for accept and a different one for refuse, behind a per-device toggle kept in `localStorage` (a convenience, with a working default if storage is unavailable) |

`q-order-marking-panel` (`features/orders/`, beside the weighing panel) owns the
write: pick a marked line (it advances itself), arm the input, post each scan
with a fresh `Idempotency-Key`, show `captured of required` and the GTIN, offer
removal with a coded reason, and report one fact upward, as the weighing panel
does. A runbook, `docs/runbooks/marking-scanner-setup.md`, names the imager
settings that matter (GS1 DataMatrix enabled, FNC1 sent as GS, suffix Enter,
symbology identifier off) and a one-minute self-test in the panel.

### Testing

- Normalisation and structure vectors, shared by the Java and Angular suites.
- Component spec with recorded keystroke sequences: scanner with `Ctrl+]`, scanner
  with U+001D, scanner that drops the separator (a diagnostic, not a crash),
  slow typing, paste, burst of five scans, disarm mid-scan, and a proof that no
  code remains in any signal after emit.
- Server: capture, replay by key, duplicate on the same line and on another
  order, wrong GTIN, no-barcode classification, over-capture, each refusing
  status, flag off, a second tenant's code with the same text (no collision),
  and the migration test of the `PhysicalAttributesMigrationTests` genre.
  Tenant isolation is tested at the application level, because the table has no
  row-level-security policy in v1: with two tenants, the other tenant's order,
  line and mark ids answer 404 on capture, removal and read, and the guard counts
  only its own tenant's marks.
- Guard: `MARKS_INCOMPLETE` on advance and on bulk advance, none for an unmarked
  order, none with the flag off, release on cancellation, `MARKS_ALREADY_CAPTURED`
  on an amendment, a reclassification mid-order.
- Concurrency, two threads against the real database: one advances an order whose
  marked line holds exactly `quantity` live marks, held by a latch just after it
  takes the order lock and before it counts; the other posts a removal for one of
  those marks. The removal must block until the advance commits and then be
  refused `ORDER_NOT_MARKABLE`; in the reverse order, with the removal committed
  first, the advance must be refused `MARKS_INCOMPLETE`. Repeated with the latch
  at each of the three points (before the lock, after the lock, between the count
  and the transition). In every interleaving the end state is either a handed-over
  order whose live marks equal its quantity, or an order that did not leave the
  pass (`STALE` or `MARKS_INCOMPLETE`); never a handed-over order with fewer live
  marks than units.
- Receipt: a marked order never submits the synthetic line; the projection yields
  `quantity = N` and N labels in order; Payme is refused; one disclosure fact per
  document; a marked line short of its quantity answers `BLOCKED_MARKS`, sends
  nothing and leaves the document `BLOCKED` with `MARKS_INCOMPLETE` (never
  `CLASSIFICATION_MISSING`), and counts arriving later reopen it.
- Module boundaries: `ModularArchitectureTests` gains the assertions that `fiscal`
  imports nothing from `payments` and that `ordering` imports neither `fiscal` nor
  `payments`.
- Privacy: a log-canary test (the ADR 0029 pattern) that a scanned code never
  reaches an appender, a problem body, a metric label or an event payload;
  `EventPayloadClassificationTests` over the new schema; the OpenAPI baseline
  regenerated and `OpenApiContractTests` green.

## Rollout and rollback

1. Ship the table, the guard (inactive), the endpoints (answering
   `FEATURE_DISABLED`), the publication blocker and the two components, all with
   the flag off. Nothing a tenant can see changes, and the product-editor warning
   about marking stays.
2. Meet P1 (ADR 0166's Decision 7), P2 and P3, in that order, under ADR 0038's
   stages 4 to 6.
3. Record the chosen scanner's keystroke profile; replace the synthetic fixtures.
4. Turn `feature.marked_goods` on for the pilot tenant when it names a marked SKU,
   with the first marked SKU published only after the flag.

Rollback at any step is the flag. Turning it off stops the guard and the capture
endpoints and refuses new marked publications; marks already captured stay, and a
marked line already published stays marked in the catalog, so an order for it
completes with no capture, which is exactly today's behaviour and is why the flag
is turned off only with the SKU. The migration is additive and forward-only.

## Implementation checklist

- [ ] Owner answers, or accepts the defaults for, the open inputs above.
- [ ] Migration for `ordering.order_line_marks` and its grants (no RLS policy in
      v1), numbered at implementation time from every active worktree's
      `db/migration/`, and the migration test.
- [ ] Normalisation and structure code on both sides, the shared vector file and
      the hygiene check that keeps the copies identical.
- [ ] Capture, removal and read endpoints, the limiter, the problem codes and the
      OpenAPI baseline.
- [ ] `OrderStateService.requireMarksComplete` running under
      `OrderStore.lockForUpdate`, the same lock taken first by capture and
      removal, and the two-thread test; its controller mapping and bulk
      reporting; cancellation release; the amendment refusal.
- [ ] `ordering.api.OrderMarksPort` (`liveMarks`, `markCounts`); in
      `payments.application`, the receipt projection, the disclosure audit fact
      `payments.document.marking_codes_disclosed` and the bridge refusing the
      synthetic line for a marked order with `Outcome.BLOCKED_MARKS`; in `fiscal`,
      `FiscalObligationService.settle` mapping it to `MARKS_INCOMPLETE` (its first
      producer); the `ModularArchitectureTests` assertions.
- [ ] `OrderMarksCompleted.v1`: schema, baseline, catalogue and `events.md`.
- [ ] Policy keys `fiscal.marking.structure_check` and
      `fiscal.marking.allow_keyed_entry` (the retention key is ADR 0166's) and
      the `feature.marked_goods` flag, with the drift test;
      `MARKED_GOODS_NOT_ENABLED` and `MARKED_MODIFIER_UNSUPPORTED` in
      `CatalogValidator`.
- [ ] `MarkingVerificationPort`, its unwired stand-in and its fake.
- [ ] `q-datamatrix-input` and `q-order-marking-panel`, lazy camera module,
      strings in three languages, specs, the order detail and expo embeds, the
      8.2 captured-of-required column.
- [ ] `docs/runbooks/marking-scanner-setup.md`.
- [ ] P1 (ADR 0166's Decision 7), P2 and P3 tracked under ADR 0038 and linked
      from the flag's runbook.
- [ ] ADR 0038 and ADR 0137 status lines updated to say the capture exists; the
      gap map's `X.35` re-audited.

## Exit criteria

An operator at the pass opens an order holding a marked line of five units, scans
five physical units with an ordinary imager, sees five of five, and hands the
order over; the same order with four scans cannot leave the pass and says which
line is short. The fiscal document for that order is submitted through Click with
one line of quantity five and five labels, never through Payme and never as a
synthetic aggregate, and the worklist shows five of five. A sixth scan of an
already scanned unit is refused on the same line and on any other order. No code
appears in any log, event, export, error or screen, and a tenant with the flag
off cannot publish a marked SKU.

## References

- ADR 0007, ADR 0017, ADR 0025, ADR 0026, ADR 0027, ADR 0029, ADR 0030, ADR 0031,
  ADR 0032, ADR 0033, ADR 0035, ADR 0038 (marked goods, the payment-method
  constraint, the `fiscal_unit_marks` sketch, rollout stage 6), ADR 0039, ADR 0055,
  ADR 0056, ADR 0060, ADR 0076, ADR 0079, ADR 0082, ADR 0101, ADR 0119, ADR 0137,
  ADR 0166 (the evidence surface, the retention key, P1), ADR 0169 (line removal
  and decrease)
- `platform/docs/operations-gap-map.md` rows `X.35`, `4.2c`, `2.3`, `1.2c`
  and wave `P17`
- `platform/docs/frontend-information-architecture.md` PART 4 ("QRCode display +
  DataMatrix scan input"), rows 4.2, 6.2, 8.2
- `platform/docs/operations-spec/orders.md` §3.9, `finance.md` §2,
  `catalog.md` Tab 5, `settings.md` KIOSK
- `platform/docs/providers/click-merchant-api.md` (`Item.Labels`) and
  `platform/docs/providers/fiscalization-via-payment-providers.md` (the four
  differences, marking)
- `platform/docs/horecaos-vs-delever.md` (the secondary-sourced market note on
  marking enforcement, medium confidence)
- `V0027`, `V0028`, `V0029`, `V0039`, `V0449`, `V0450`
- `OrderStateService` (`requireCatchweightReconciled`, `isHandover`),
  `OperationsOrderWeighingController`, `CatchweightReconciliationService`,
  `OrderBulkActionService`, `AmendmentCommandType`, `FiscalReceiptLine`,
  `ClickFiscalAdapter`, `PaymeReceiptDetail`, `PartnerFiscalizationBridge`,
  `FiscalReasonCode`, `FiscalNodeFacts`, `CatalogFiscalFacts`, `CatalogValidator`,
  `PartnerFiscalizationPort`, `FiscalObligationService`, `FiscalDocumentService`,
  `ModularArchitectureTests`, `ProviderCategory`
- `frontend/operations/src/app/shared/ui/qr-code.ts`,
  `features/orders/order-weighing-panel.ts`, `features/kitchen/expo-page.ts`,
  `features/finance/fiscal/fiscal-page.ts`
