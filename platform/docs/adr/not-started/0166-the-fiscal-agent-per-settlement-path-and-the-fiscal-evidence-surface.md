# ADR 0166: The fiscal agent per settlement path and the fiscal evidence surface

- Decision status: Proposed — proposed by Claude (batch 19); the platform owner decides
- Implementation status: Not started — the evidence exists and nothing shows it.
  `fiscal.fiscal_documents` (V0027, moved by V0039) already holds, per settlement
  leg, `legal_entity_id`, `provider_type`, `external_receipt_id`, `fiscal_sign`,
  `terminal_id`, `receipt_reference`, `registered_at`, `receipt_url`,
  `provider_status_code`, `provider_message` and the two ADR 0029 references to the
  request and response as sent; `ClickReceiptUrl` parses Click's OFD URL into those
  columns and `PaymeMerchantApi` writes Payme's `SetFiscalData` into them, and
  `FiscalCustomerReceiptTrigger` already sends the customer their receipt link when a
  document reaches `ISSUED`. No staff surface reads any of it:
  `FiscalDocumentController` (`/api/v1/tenants/{tenantId}/fiscal/...`) answers
  `hasEvidence` and nothing else, by its own documentation "read through the payments
  module's authorized order-payment view, with a recorded purpose", and that view does
  not exist; `OperationsPaymentController`'s `OrderPaymentResponse` carries no fiscal
  field; the console's `order-fiscal-panel` and `fiscal-page` print a document's type,
  provider, status, reason and a yes or no for evidence. Behind that, four facts the
  surface would expose are not true yet. (1) Nothing opens a provider obligation:
  `PaymentFiscalService.openPartnerObligation` has no caller, and
  `PaymentFiscalService.submit` has one, the operator's retry
  (`PartnerFiscalizationBridge.retry`), which — because `fiscal_document_lines` is not
  built and lines are not stored — sends one synthetic line named "Order <number>"
  with the placeholder ИКПУ `00000000000000000`, package code `0000000`, VAT 0 and, if
  the seller cannot be resolved, TIN `000000000`. (2) No delivery-line ИКПУ or package
  code can reach a receipt: `catalog.fiscal_classifications` carries them for the
  `FEE` node and the real line builder that would read them does not exist. (3)
  Marking codes have no store: ADR 0038's `fiscal.fiscal_unit_marks` is not built (ADR
  0161 puts the store in `ordering` instead), and
  `catalog.api.FiscalNodeFacts.requiresMarkingCapablePayment`, the check that removes
  Payme from a cart holding a marked good, has no caller in `CartPaymentOptions` or
  `CheckoutEligibilityGuard`. (4) A correction or void has no command:
  `CORRECTION_REQUESTED`, `VOID_REQUESTED`, `CORRECTED` and `VOIDED` are unbuilt,
  `ordering.order_revisions.fiscal_correction_required` (V0395) records that a
  correction is owed and nothing reads it, and `CheckoutSettlementPlanner` registers a
  cash tender at a location with no capable `fiscal.fiscal_terminals` row (V0247) as
  `OPERATOR`, a responsibility ADR 0038 says nothing implements.
- Date proposed: 2026-10-07
- Date decided: —
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0007, ADR 0011, ADR 0013, ADR 0025, ADR 0026, ADR 0027, ADR 0028,
  ADR 0029, ADR 0030, ADR 0031, ADR 0032, ADR 0038, ADR 0039, ADR 0040, ADR 0046,
  ADR 0048, ADR 0161, ADR 0165. ADR 0161 (Proposed) owns the per-unit mark store and
  the count-only display of marking; neither record blocks the other: this surface
  shows the count where ADR 0161's port exists and says "marks not built" where it
  does not, and ADR 0161 relies on this record for the retention key and the
  payment-options wiring.
- Supersedes / Superseded by: — (amends ADR 0038 without editing it. It closes that
  record's open input "which party is the legal fiscal agent per settlement path", and
  it reopens exactly one rejected row, «Integrate a fiscal operator directly, creating
  the `FISCAL` category ADR 0026 declined», to narrow its revisit condition: the
  condition "the legal open input returns Qoida is the fiscal agent" is declined for
  good, and the other, "a tenant needs cash where no fiscal-capable POS exists", leaves
  the row rejected for now and says that anything built then issues on the tenant's own
  INN with the tenant's own credentials. It also narrows the revisit of the row «Qoida is
  the principal, with one Qoida Click service and one Qoida Payme cashbox» from three
  conditions to "never on HorecaOS's INN". ADR 0038's decisions on per-entity merchant
  accounts, the `PARTNER`/`TERMINAL`/`MARKETPLACE`/`OPERATOR` taxonomy, cash as
  `NOT_APPLICABLE` and the document lifecycle are not touched. ADR 0038's sketch of
  `fiscal.fiscal_unit_marks` is left to ADR 0161, which places the mark store in
  `ordering` as `ordering.order_line_marks`: this record builds no mark table, shows
  marking as a count only, and does not reveal a marking code)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written; the ones that name a person other than the owner stay with
  that person and the work they block is marked.
  - **Whether the HorecaOS–restaurant agreement is a commission agreement (договор
    комиссии) or an agency arrangement in the sense the tax rules use** (legal
    counsel, engaged by the platform owner). Proposed default: treat it as neither.
    The tenant's legal entity is the seller and the principal; HorecaOS is a software
    vendor that requests receipts and retains evidence; no receipt ever names HorecaOS.
    Correct under either answer, which is why nothing waits. Blocks: nothing.
  - **Whose TIN or PINFL Click's per-line `CommissionInfo` carries, and whether one
    service may name a different TIN per line** (Click, asked in writing by the
    platform owner). Proposed default: the selling legal entity's own TIN, one Click
    service per legal entity (ADR 0038's standing default). A "yes, per line" answer
    changes no structure, because Payme has no such field. Blocks: nothing.
  - **Who fiscalizes an order an aggregator collected the money for** (finance, with
    each tenant). Proposed default: `NOT_REQUIRED` with the contract reference as
    evidence only where the tenant's written aggregator contract says the aggregator
    issues the receipt as the tenant's agent; where the contract is silent or absent,
    the obligation is the tenant's, the document is `BLOCKED` with `NO_FISCAL_PATH` until
    the tenant attaches its own receipt reference. Blocks: only the `MARKETPLACE` reading
    of the surface.
  - **The legal deadline and form of a correction or void, per settlement path**
    (finance and legal counsel). Proposed default: HorecaOS records and never issues.
    The request carries the reason and the approver, the agent issues the correction on
    the path the sale used, and the operator attaches the reference. No HorecaOS-side
    deadline is enforced; the request is aged on a worklist instead. Blocks: only a
    deadline-driven alert, which is not built.
  - **Whether HorecaOS owes anything at the moment of sale of a marked good** (legal
    counsel). Proposed default: nothing beyond transmitting, through the tenant's own
    agent and only on Click, the codes the tenant's staff scanned; Payme is removed from
    a cart holding a marked node (the check ADR 0038 specified and nothing calls yet).
    Blocks: only the first marked SKU a tenant sells.
  - **Retention of fiscal evidence per legal entity** (finance and legal counsel).
    Proposed default: seven years, the ADR 0029 `FINANCIAL` provisional period and above
    the five-year floor the provider notes cite, as ADR 0030 policy
    `fiscal.evidence_retention_years`, still flagged provisional so the ADR 0029 startup
    guard keeps refusing a production profile until someone confirms it. It is the one
    retention key for fiscal evidence: marking evidence (ADR 0161's mark rows and the sent
    `Labels`) follows it and ADR 0161 declares no second key. Blocks: only that guard.
  - **How the receipt identifiers are classified and stored under ADR 0029**
    (Ayubkhon Abbosov, as the owner of platform security). Proposed default: the fiscal
    sign, receipt number, terminal id and receipt URL are `FINANCIAL` for *reads* —
    purpose-bound, audited, masked in lists, never in a log, metric or event; a marking
    code is `FINANCIAL` too but is never returned by this surface at all (ADR 0161: no
    screen, no reveal, a count only) — and the existing plain columns stay plain until
    ADR 0029's financial rollout reaches `payments`; this record neither needs that
    migration nor blocks it.
    The INN is a business identifier and may travel. Blocks: nothing.
  - **Cash at a location with no capable terminal** (Ayubkhon Abbosov). Proposed
    default: the surface reports it as it is, an unevidenced tenant obligation with
    reason `NO_FISCAL_PATH`, never as "HorecaOS fiscalizes this"; and ADR 0038's
    activation precondition (cash is not offered where no capable terminal is bound) is
    enforced when a channel or payment method is activated, in the payment-method
    registry wave (row `1.2c`), which this record does not move.

**To accept as written:** say "accept 0166". Every open input above is then closed on
its proposed default.

## Context

Gap-map row `X.2` of §8 ("8.2 — the fiscal evidence itself: receipt URL/codes, INN used,
fiscalized payment types, delivery IKPU, marking codes") is `NOT BUILT`, tier `P`: "An
operator handed a customer complaint or a tax query cannot produce the receipt: no
fiscal sign or receipt URL, no statement of which INN issued it, no fiscalized payment
types, no delivery-line IKPU and no marking codes anywhere in the console — and no
correction or void command exists to fix a wrong one." Its "Blocked by" is ADR 0038's
legal open input, "which party is the legal fiscal agent per settlement path", which
`docs/frontend-and-parity-plan.md` (line 168) lists as owned by legal and finance and
says "governs correction/void obligations and marked-goods handling". The Part B note
for the finance wave adds a trap: "Do not render a partial receipt." IA row 8.2 names
the same list: per-order fiscal status and URL/codes, which INN was used, fiscalized
payment types, delivery-line IKPU, marking codes transmitted, fiscal operator errors.

**The question is already answered four times over and nobody wrote it as one answer.**
ADR 0038 decided "the restaurant's legal entity is the seller and the legal principal;
HorecaOS is an agent and never the issuer", and recorded as a closed input (2026-08-22)
that provider merchant accounts are the restaurant's own, one per legal entity, because
neither Click nor Payme accepts a seller identity per request — the cashbox, or the
`service_id` plus credentials, *is* the taxpayer (`docs/providers/fiscalization-via-payment-providers.md`).
It decided cash is `NOT_APPLICABLE` as an explicit state. ADR 0040's open question
about aggregator-collected money was closed by the same record the same day. And ADR
0038 then left one input open: whether that agent arrangement is a commission agreement
in the tax sense. What is open is a legal characterisation, not a design. The design is
the same under both readings, which is exactly what ADR 0038 said when it wrote "nothing
is blocked on the answer". What the owner has never been given is the whole answer on
one page, per path, with the consequence for corrections and marking that the row's
blocker names — so that is what this record gives, and the defaults it proposes are the
ones that are correct under either legal answer.

**Why the evidence cannot simply be shown.** Three things would make a screen that
printed today's rows misleading, and the record has to decide each before the screen is
built.

1. **The only submission path sends a placeholder.** `PartnerFiscalizationBridge`'s
   own documentation calls its synthetic line "the honest stand-in until [the line
   builder] exists" and says a tenant that goes live with Click "needs a real line
   builder before its receipts are correct, not before its receipts exist at all". That
   is a statement about a receipt filed with the tax authority, and the only thing that
   triggers it is a button an operator is shown on the order. Showing the resulting
   evidence as a receipt would present a placeholder as a sale.
2. **The identifiers are called protected and are plain.** The controller's and the
   finance spec's documentation both say the fiscal sign, receipt URL and marking codes
   are ADR 0029 protected evidence read "with a recorded purpose". The columns are
   `varchar` (V0027), no reveal path exists, and the customer is sent the URL by
   notification. A surface built on the plain columns with no rule would be the first
   place an identifier leaves the database for a staff screen, so the rule has to come
   first.
3. **The delivery line and the marking codes have nowhere to live.** `catalog.fees`'s
   DELIVERY node carries an ИКПУ and package code (row `10.7c`'s delivery-fee write), and
   neither Payme's `shipping` block nor any built line builder reads them: ADR 0038's
   own decision is that the fee is "an ordinary item line", and the provider notes
   explain that emitting it through `shipping` would drop the classification silently.
   Marking codes have no store yet (ADR 0161 builds one, `ordering.order_line_marks`; ADR
   0038's `fiscal.fiscal_unit_marks` sketch is not built), and Payme has no field for them
   at all.

**What each settlement path means for who owes what.** ADR 0038's responsibility table
is the frame, and this record only completes its two missing columns.

| Path | Methods | Who issues | Under whose INN | HorecaOS's role | A wrong or refunded receipt |
|---|---|---|---|---|---|
| `PARTNER` | Click, Payme (online card, wallet) | The provider, through the merchant account | The selling legal entity's own, one Click service and one Payme cashbox per entity | Supplies the lines, retains the evidence, chases silence | Payme's `CANCEL` is a second receipt on a `REFUND` document; Click's reversal exists only within the reporting month; a wrong receipt is corrected in the provider's cabinet or on the tenant's own equipment, and HorecaOS records it |
| `TERMINAL` | Cash, courier terminal, kiosk, dine-in POS settlement | The tenant's fiscal-capable equipment | The legal entity the location is assigned to on the business date | Requests issuance when an adapter exists, records the returned reference, blocks on failure | The tenant issues the correction on the same equipment; HorecaOS attaches the reference (Click `submit_qrcode`, Payme `receipts.set_fiscal_data` where a provider payment is involved) |
| `MARKETPLACE` | Aggregator-collected payments | The aggregator, where its contract makes it the tenant's fiscal agent | The tenant's | Records `NOT_REQUIRED` with the contract reference, or blocks | The aggregator's, outside HorecaOS |
| `OPERATOR` | none today | Specified by ADR 0038 as HorecaOS calling a fiscal operator directly; unimplemented | — | None in v1 | — |

**Not on that table, and the reason this record exists in the money wave beside ADR
0165.** HorecaOS is on no row as the issuer and on no row as the party that receives the
money. Both records rest on one premise: the customer's payment settles to the tenant's
own account (ADR 0038), the aggregator pays the tenant (ADR 0040), and the courier's cash
is the tenant's (row `8.3`). A platform that holds no customer money (ADR 0165) and
issues no receipt is a software vendor. The moment it does either, it needs a licence, an
INN on the receipt and a correction obligation of its own, and every tenant inherits them.

## Decision

**The tenant's own legal entity is the fiscal agent on every settlement path, HorecaOS
never fiscalizes on its own INN, and the evidence of what each path issued is shown to
staff as references to what the agent filed, read through a purpose-bound, audited
endpoint, on the order and payments screens.**

1. **One agent, on every path.** The selling legal entity (ADR 0038's
   `tenant.legal_entities`, resolved through the location's effective-dated assignment)
   is the fiscal agent for `PARTNER`, `TERMINAL` and `MARKETPLACE` alike. No receipt, no
   `CommissionInfo`, no merchant account, no terminal and no credential used on a
   tenant's sale ever names HorecaOS, and the platform holds no INN of its own for that
   purpose. ADR 0038's `OPERATOR` responsibility stays unimplemented and `ProviderCategory`
   keeps no `FISCAL` value (its own documentation: "a separate category would model a
   provider relationship HorecaOS does not have"). If a tenant with cash and no
   fiscal-capable equipment ever makes building it worthwhile, what is built transmits a
   receipt to a fiscal operator *as that tenant*, with that tenant's contract and
   credentials as an ADR 0026 installation per legal entity; HorecaOS remains a requester.
2. **The evidence surface is a read model over the rows that already exist, never a
   second copy and never a receipt HorecaOS composes.** One endpoint answers, per order,
   the order's fiscal documents with: document type, status and reason (as today); the
   issuing legal entity's INN and name **as they were when the document was opened**
   (Decision 3); the responsibility and the merchant account or terminal reference that
   issued it; the receipt URL, fiscal sign, receipt number, terminal or virtual-module id
   and registration time; which tender the document covers and how the provider split it
   (Click's `received_cash`, `received_card`, `received_ecash`); the lines as sent, with
   ИКПУ, package code, unit, quantity, price, VAT and discount, the delivery fee marked as
   such; and the marking codes transmitted, as a count only (the number of `Labels` in
   the request as sent, beside the captured-of-required count that ADR 0161's
   `ordering.api.OrderMarksPort.markCounts` provides): never a code, never a tail, and
   no reveal, which is ADR 0161's rule for marking. The console
   never draws a receipt body: it shows identifiers, a link to the OFD, and the sent lines,
   which honours the finance wave's trap, "do not render a partial receipt".
3. **The INN used is snapshotted onto the document, not joined later.** A location's
   assignment is effective-dated and a legal entity can be re-registered, so a join made a
   year later answers for today. Opening a document writes `seller_tin` and `seller_name`
   from the assignment in force on the order's business date, the same resolution the
   adapters already do, and the surface reads those.
4. **"Fiscalized payment types" has two meanings and the surface shows both.** Per
   document, the tender method and the provider's own split of it. Per tenant, a read of
   the payment-method registry (`payments.payment_methods`) as a table of method, who
   issues (`responsibility`), under which legal entity's merchant binding or terminal, and
   whether that path is live or `NO_FISCAL_PATH`. The per-tenant answer is the one a
   Delever operator sets by hand as a tenant-wide list; here it is derived and cannot be
   set, because a setting that disagreed with the bindings would be a lie on a tax
   document.
5. **A purpose is required to see an identifier.** Lists and the worklist show masked
   values (the last four characters) and `hasEvidence`; the full fiscal sign, receipt URL
   and receipt number are returned only to a principal holding the new
   capability `fiscal.evidence.reveal`, for a declared purpose (`TAX_INQUIRY`,
   `CUSTOMER_COMPLAINT`, `AUDIT`, `CORRECTION`), and every reveal writes a `SECURITY` audit
   fact naming the document, the fields and the purpose. The identifiers never appear in a
   log, a metric, an event or an error message; the INN may. A marking code is not among
   the fields a reveal can return: ADR 0161 keeps every code off every screen and gives it
   no reveal, so the evidence block shows the count only.
6. **No synthetic line is ever filed outside a sandbox.** Until the real line builder
   exists, `PartnerFiscalizationBridge.retry` answers 409 `FISCAL_LINES_NOT_BUILT` for
   any provider environment other than a sandbox, and the document stays `BLOCKED` with
   its reason. The placeholder is allowed to prove the wire in a sandbox, where its zeroes
   cannot be mistaken for a sale. The real line builder is the prerequisite for every
   Click receipt in production, and it is also what makes the delivery line real:
   derived from the accepted quote snapshot (ADR 0018, ADR 0038), the fee as an ordinary
   item line, the ИКПУ and package code read from `catalog.fees`'s classification at the
   time and **stored as sent**, so the evidence shows what was filed and not what the
   catalog says today.
7. **Marking is stored by ADR 0161, wired into payment options here, and shown by
   count.** The per-unit mark store is ADR 0161's `ordering.order_line_marks`, with the
   code under ADR 0029 protection and returned by no endpoint; ADR 0038's
   `fiscal.fiscal_unit_marks` sketch is not built and this record builds no mark table.
   This record reads counts through `ordering.api.OrderMarksPort.markCounts`, for the
   evidence block's "captured of required" and its "codes transmitted" figure.
   `FiscalNodeFacts.requiresMarkingCapablePayment` is called from the payment-options
   path so Payme is not offered for such a cart; that wiring is this record's (it is
   precondition P1 of ADR 0161) and lands before `feature.marked_goods` is turned on for
   any tenant. HorecaOS does nothing at the moment of sale beyond transmitting the codes
   (open input).
8. **Correction and void are requests HorecaOS records, and the agent issues.** An
   operator with `fiscal.document.resolve` raises a correction or void request against a
   document with a reason, under an ADR 0027 approval; the document moves to
   `CORRECTION_REQUESTED` or `VOID_REQUESTED`; the agent issues on the path the sale used;
   the operator attaches the agent's new reference, which creates a linked `CORRECTION` or
   `REFUND` document and moves the original to `CORRECTED` or `VOIDED`. HorecaOS never
   edits or deletes a fiscal document and never composes a correction receipt. The same
   request is how `order_revisions.fiscal_correction_required` (V0395) is finally read:
   an amendment that changed a filed total opens a request automatically, unapproved
   until a person acts.
9. **A provider-settled payment's receipt carries exactly this**, and the evidence
   surface checks it against what it shows: the seller is the merchant account's
   taxpayer; one line per priced item, modifier option and the delivery fee, each with the
   tenant's fiscal name, ИКПУ, package code, unit code, quantity, price, whole-percent VAT
   and the quote's recorded tax share; the loyalty redemption as a per-line discount (ADR
   0046), never a tender; `CommissionInfo` with the seller's own TIN (Click) and nothing in
   its place on Payme; the tender split inside the payment; marking codes on Click only.
   It never carries HorecaOS's name, INN or fee.
10. **Evidence is retained for the legal entity's period, as policy.** `fiscal.evidence_retention_years`
    (ADR 0030, provisional) governs the document rows and the protected references, and
    the marking evidence of ADR 0161 (its `ordering.order_line_marks` rows and the sent
    `Labels`), which declares no key of its own; offboarding a tenant does not shorten it,
    because the obligation is the tenant's.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| HorecaOS as the fiscal agent: one HorecaOS Click service and Payme cashbox, receipts naming each restaurant per line | Neither provider takes a seller identity per request, so receipts would name HorecaOS as the seller of a meal it did not cook, under its INN, with its output VAT, and HorecaOS would be the merchant of record for every tenant's audit (ADR 0038's reasons, unchanged) | Never on HorecaOS's INN. If Click confirms per-line `CommissionInfo` and counsel confirms a commission agreement, that still would not make HorecaOS the issuer |
| HorecaOS as a fiscal operator integrator issuing on its own account for cash | Makes HorecaOS the issuer of a legal document, with operator contracts per entity, correction and void obligations of its own and an outage class in which orders cannot lawfully complete | A tenant needs cash where no fiscal-capable equipment exists; then it is built as that tenant's installation, issuing on the tenant's INN (Decision 1) |
| Show the identifiers to anyone with `fiscal.document.read`, in the existing worklist | The most helpful screen and the first place a receipt identifier leaves the database for a staff view with no purpose and no trail. ADR 0029's reveal rule exists for this | Never; a separate reveal capability is cheap |
| Join the INN from the legal entity when the screen is read | Correct today, wrong after a re-registration or a reassignment: a receipt from last year would show this year's taxpayer | Never |
| Store the receipt body (a rendered copy) so the console can draw it | The OFD's page is the receipt; a HorecaOS rendering is a second document that can disagree with it, and "do not render a partial receipt" is the finance wave's own warning | The tax authority's document is not retrievable by link for the retention period, and counsel asks for a copy |
| Let the operator retry file the synthetic line in production, since it exists | A receipt of one line, placeholder classification, VAT 0 and a possibly zero TIN is filed with the tax authority and can only be corrected, never withdrawn (ADR 0038); a blocked document is recoverable | The real line builder ships (then the placeholder is deleted, not allowed) |
| Make "fiscalized payment types" a tenant setting, as Delever does | A setting can say a method is fiscalized when no binding or terminal supports it, which is how a tenant finds out at an inspection. The registry plus bindings already say it | Never; the derived table replaces the setting |
| Build a HorecaOS-issued correction or void ("fix it for the tenant") | Makes HorecaOS the issuer by the back door, with a tax-authority correction it cannot take back | Never on HorecaOS's INN; for a tenant's own installation under Decision 1's revisit |
| Wait for legal on the commission question before building the surface | The surface and the agent rule are correct under both answers; waiting leaves an operator unable to produce a receipt for a complaint in the meantime | Not needed: the open input narrows nothing structural |

## Consequences

### Positive

- An operator handed a complaint or a tax query can produce, in one screen, the receipt
  link, the sign, the INN that issued it, how the payment was split, the lines as filed
  and the number of marking codes, with a record of who looked and why.
- The legal question that blocked the row stops blocking it: the defaults are the ones
  that hold under either answer.
- A placeholder receipt can no longer be filed in production by a click.
- The correction path stops being "ask an accountant": a request, an approver, an
  attached reference, and `fiscal_correction_required` finally has a reader.

### Negative

- Three prerequisites are real work and gate the screen's honesty: the line builder,
  ADR 0161's mark store and the terminal issuance adapter. Until they land the surface
  shows `BLOCKED` documents and "lines not built", which is correct and unflattering.
- A tenant with cash and no fiscal equipment is shown, accurately, as an unevidenced
  obligation. That is the tenant's position today and it was invisible.
- A second capability and a mandatory purpose put one more step between a support agent
  and a customer's receipt link, which the customer was already sent.
- The surface reads the sent request through a protected reference, so a reveal costs a
  decrypt and an audit write per document.

### Accepted trade-offs

- The existing plain columns stay plain for now. That is an ADR 0029 deviation the open
  input names rather than hides; reads are controlled, storage is not yet.
- The INN snapshot adds two columns and a backfill for rows opened before it; rows with
  no snapshot show "resolved from the entity, not from the filing" and say so.
- HorecaOS records corrections and cannot speed them up. A tenant whose provider's
  reversal window has closed corrects on its own equipment and attaches the reference.

## Specification

### Physical model

All rows carry `tenant_id` and unique and foreign keys include it.

```text
fiscal.fiscal_documents        (existing; V0027, V0039)
  + seller_tin varchar(9) null            -- snapshot, ck: ~ '^[0-9]{9}$'
  + seller_name varchar(200) null
  + lines_synthetic boolean not null default false
  + sent_tender_split_reference null      -- ADR 0029 protected reference to what was sent
  status gains CORRECTION_REQUESTED, VOID_REQUESTED, CORRECTED, VOIDED (ADR 0038)

(marks)                        no table here. The per-unit mark store is ADR 0161's
                               ordering.order_line_marks (ADR 0029 protected code, never
                               returned). This record reads counts only, through
                               ordering.api.OrderMarksPort.markCounts, and builds no
                               fiscal.fiscal_unit_marks.

fiscal.fiscal_correction_requests
  id, tenant_id, document_id, kind (CORRECTION|VOID)
  reason_code, reason_note
  status (REQUESTED|ATTACHED|DECLINED|CANCELLED)
  requested_by, approval_id null, requested_at
  attached_document_id null, attached_by null, attached_at null
  version, created_at, updated_at
  unique (tenant_id, document_id) where status = 'REQUESTED'   -- one open request per document
```

Grants: the application role holds `SELECT, INSERT, UPDATE` on the two tables and no
`DELETE` on either, matching `fiscal.fiscal_documents` (V0039: "a fiscal document is
evidence"). The new columns carry the same grants through the table. A check ties
`seller_tin` to `seller_name` (both or neither). `lines_synthetic` is set by the bridge
when it sends the placeholder; refusing that outside a sandbox is done in code and not
by a constraint, because the sandbox flag lives on the installation.

### APIs (ADR 0031) and capabilities (ADR 0025)

```text
GET  /api/v1/operations/tenants/{tenantId}/orders/{orderId}/fiscal-evidence
       fiscal.document.read (TENANT)       masked values, structure, INN, lines as sent
       ?purpose= + fiscal.evidence.reveal   full identifiers, audited
GET  /api/v1/operations/tenants/{tenantId}/fiscal/payment-types
       fiscal.document.read (TENANT)       method, responsibility, entity, binding or terminal, live
POST /api/v1/operations/tenants/{tenantId}/fiscal/documents/{documentId}/correction-requests
       fiscal.document.resolve (TENANT), mutating; Idempotency-Key; If-Match = document version
POST /api/v1/operations/tenants/{tenantId}/fiscal/correction-requests/{requestId}/attachments
       fiscal.document.resolve (TENANT), mutating; Idempotency-Key; If-Match
```

The existing `/api/v1/tenants/{tenantId}/fiscal/...` worklist, coverage and retry/unblock
endpoints are unchanged except that `retry` answers 409 `FISCAL_LINES_NOT_BUILT` as in
Decision 6. Scope is `TENANT`, as `FiscalDocumentController` already argues: an
obligation belongs to a legal entity, which cuts across brands. One new capability,
`fiscal.evidence.reveal` (`FISCAL_EVIDENCE_REVEAL("fiscal.evidence.reveal", "fiscal",
"evidence.reveal")`), granted to the tenant owner and finance and not to
`fiscal.document.read` holders by default; `EndpointCapabilityDeclarationTests` decides
the scope and mutating flag at build time. The purpose is a closed enum in the request,
never free text.

### Events (ADR 0032, through the outbox)

`FiscalCorrectionRequested.v1` and `FiscalCorrectionAttached.v1` on a new `fiscal.events`
topic (one producing module, `fiscal`), keyed by `documentId`, carrying identifiers, the
kind and the status, written through the ADR 0004 outbox in the transaction that changes
the row. They are catalogued in `docs/domains/events.md` with a schema file before the
producer ships. No evidence field, no INN beyond the legal entity id, and no marking code
is reachable from either payload type (the ADR 0029 structural test). `FiscalDocumentIssued`
and `FiscalDocumentBlocked` stay in-process signals as they are.

### PII, audit and policy

- No personal data is added. The identifiers are `FINANCIAL`-handled on read (open input),
  the INN is a business identifier, the legal name is business data. Nothing here may
  reach a log line, metric label, error message or dead-letter summary.
- Audit (ADR 0027): `fiscal.evidence.revealed` (`SECURITY`: document, fields, purpose,
  actor), `fiscal.correction.requested` and `fiscal.correction.attached` (`BUSINESS`: before
  and after status, reason code, approval id). The correction request is an ADR 0027
  approval action; the approver is a second person.
- Policy (ADR 0030): `fiscal.evidence_retention_years` (settable `PLATFORM`, `TENANT`;
  default 7; provisional flag set), and `fiscal.synthetic_lines_allowed` (settable
  `PLATFORM` only, boolean, default false, honoured only for a sandbox provider
  environment; there is deliberately no tenant scope, so a tenant cannot switch the
  placeholder on).

### Providers (ADR 0026, ADR 0007)

No new provider category. `PAYMENT` installations keep the `FISCAL_RECEIPT` capability
(`PaymentProviderCapabilityCatalog`); `TERMINAL` issuance is a `FiscalTerminalPort`
behind `fiscal.fiscal_terminals.provider_binding_id` with capability `IssueFiscalReceipt`,
specified here and built later with a `FakeFiscalTerminalAdapter` first and the ADR 0007
contract tests (accepted-then-lost reply, timeout, malformed body, duplicate submission).
Attachments use the existing provider calls (Click `submit_qrcode`, Payme
`receipts.set_fiscal_data`) through the same adapters, after the read-back Click requires.
Endpoints come from the approved provider environment catalogue and never from tenant
configuration (ADR 0026).

### The surface

Order detail's fiscal panel gains an evidence block per document (the sent lines, the
INN, the tender split, the marks count (ADR 0161's `markCounts`: captured of required,
never a code or a tail), masked identifiers, a "show" action that asks for a purpose)
and the payments page gains the per-tenant payment-types table and a link from
each tender to its document. The finance fiscal page's blocked worklist gains the reason
text and ageing it already receives. All strings in ru, uz-Latn and en. No receipt is
drawn.

### Testing

- A reveal without the capability is 403; with it and no purpose is 422; with both is
  audited, and the audit fact is read back in the same test.
- No marking code or tail is in any response of the evidence endpoint, with or without
  the capability and whatever the purpose; the block carries a count.
- A document opened for a location reassigned later still shows the original INN
  (the clock is advanced and the assignment changed, so the test is about a duration, not
  an instant).
- `retry` against a non-sandbox binding with no stored lines is 409 and sends nothing;
  against a sandbox binding it sends one line with `lines_synthetic = true`.
- A cart with a marked node does not offer Payme (the check now has a caller).
- A correction request raises one open request per document, needs a second approver,
  and an attachment creates a linked document and never edits the original.
- The structural test shows no evidence field reachable from an event payload.
- Tenant isolation: another tenant's order answers 404 on every endpoint above.
- Front-end: the evidence block renders masked by default and never renders a receipt body.

## Rollout and rollback

Order matters because the surface is only honest once its inputs are real. First the
guard (Decision 6) and the INN snapshot with its backfill, which change no screen. Then
the reveal capability, the endpoint and the console block, which show what exists —
`BLOCKED` documents, the cash `NOT_APPLICABLE` reason, `NO_FISCAL_PATH` — and say "lines
not built" where that is true. Then the line builder and `PARTNER` submission at capture
(ADR 0038 rollout stage 4), then terminal issuance and ADR 0161's marks, each behind its
own configuration. Then correction requests. Rollback of the surface is removing the endpoint
and the block; the columns and the snapshot are additive and harmless. Rollback of Decision
6 is setting `fiscal.synthetic_lines_allowed` for a named environment, a platform write
that is audited.

## Implementation checklist

- [ ] Owner accepts the record, or answers the open inputs.
- [ ] Migration: `seller_tin`, `seller_name`, `lines_synthetic`, the sent-split reference,
      with a backfill from the assignment for existing rows and a marker for rows it cannot
      resolve; `fiscal.fiscal_correction_requests` with grants (the mark store is ADR
      0161's migration, not this one). Check every active worktree's `db/migration/` for the
      next free number first.
- [ ] `fiscal.evidence.reveal` capability, `fiscal.evidence_retention_years` and
      `fiscal.synthetic_lines_allowed` keys; `fiscal.events` topic and its catalogue entries.
- [ ] `GET .../orders/{orderId}/fiscal-evidence` and `GET .../fiscal/payment-types`, with
      masking and the audited reveal.
- [ ] `PartnerFiscalizationBridge.retry` refuses a non-sandbox synthetic submission.
- [ ] The real line builder from the accepted quote snapshot, delivery fee as an item line,
      ИКПУ and package code stored as sent; then `PaymentFiscalService.openPartnerObligation`
      and `submit` driven at capture.
- [ ] `FiscalNodeFacts.requiresMarkingCapablePayment` called from `CartPaymentOptions` and
      `CheckoutEligibilityGuard`.
- [ ] The evidence block's marks count reads ADR 0161's `OrderMarksPort.markCounts`; until
      that port exists the block says "marks not built" and shows no count.
- [ ] Correction and void requests, attachments, the four states, and a reader for
      `order_revisions.fiscal_correction_required`.
- [ ] `FiscalTerminalPort`, a fake adapter and its contract tests (not required for the
      surface; required for `TERMINAL` evidence to exist).
- [ ] Console: the evidence block, the payment-types table, the strings in three languages.
- [ ] ADR 0038's status line updated to say its open input is closed, and the gap-map row
      `X.2` re-audited (this record does not edit either).

## Exit criteria

A customer complains that a receipt is wrong. The operator opens the order, sees the
fiscal documents, states the issuing INN, shows the receipt link and the sign to a
colleague after choosing a purpose, reads the lines exactly as filed with the delivery fee
as its own line, and reads how the payment was split. The audit trail shows who revealed
what and why. The operator raises a correction request, a second person approves it, the
tenant corrects on the path the sale used, the operator attaches the reference, and the
original document reads `CORRECTED` with a linked correction beside it. No receipt filed in
production carries a placeholder code, a HorecaOS name or a HorecaOS INN, and a test proves
the first.

## References

- ADR 0007, ADR 0011 (capability model), ADR 0013, ADR 0018, ADR 0025, ADR 0026, ADR 0027,
  ADR 0028, ADR 0029, ADR 0030, ADR 0031, ADR 0032, ADR 0038 (legal entities, fiscal
  receipts, the responsibility table, its Alternatives table, its open input), ADR 0039
  (amendment consequence matrix), ADR 0040 (aggregator-collected money), ADR 0046
  (redemption as a per-line discount), ADR 0048, ADR 0058 (the receipt link is a legal
  artifact), ADR 0161 (the mark store and the count-only display of marking), ADR 0165
  (HorecaOS holds no customer funds)
- `platform/docs/operations-gap-map.md` row `X.2` of §8, rows `8.2`, `10.7a`, `10.7b`,
  `10.7c`, `4.2d`, and the finance wave's trap note
- `platform/docs/frontend-information-architecture.md` row 8.2
- `platform/docs/frontend-and-parity-plan.md` (the blocked table, line 168)
- `platform/docs/operations-spec/finance.md` §2
- `platform/docs/providers/fiscalization-via-payment-providers.md`,
  `click-merchant-api.md`, `payme-merchant-api.md`
- `V0027`, `V0028`, `V0039`, `V0053`, `V0247`, `V0395`; `FiscalDocumentController`,
  `FiscalDocumentService`, `FiscalReasonCode`, `PartnerFiscalizationBridge`,
  `PaymentFiscalService`, `FiscalCustomerReceiptTrigger`, `ClickReceiptUrl`,
  `PaymeMerchantApi`, `FiscalReceiptLine`, `CheckoutSettlementPlanner`,
  `FiscalNodeFacts`, `PaymentProviderCapabilityCatalog`, `ProviderCategory`
- `frontend/operations/src/app/features/orders/order-fiscal-panel.*`,
  `frontend/operations/src/app/features/finance/fiscal/*`,
  `frontend/operations/src/app/features/finance/payments/*`
