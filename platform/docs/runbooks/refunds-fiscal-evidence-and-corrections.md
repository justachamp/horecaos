# A receipt is wrong, a customer wants money back, or an order changed after its receipt

**Last executed:** never. This is a draft (ADR 0023). Nothing in it has been run against a real Click or Payme
cabinet or a real fiscal terminal; every step below names the screen or the query and what to check, and the
first real run is what turns it into a runbook.

**The way back, before the first step.** HorecaOS **records** a correction, a void or a refund; it never issues
one, and it never edits or deletes a fiscal document (ADR 0166). So nothing here can make a receipt worse by itself.
A correction or void *request* is withdrawn by closing it as `CANCELLED` (step 3.6), which puts the document back to
`ISSUED` with its evidence exactly as it was. What cannot be taken back is what the tenant's own fiscal agent files
in its cabinet or on its terminal, so the order of the steps is: ask the agent first, record second.

**Who owes what** (ADR 0166 Decision 1). The tenant's own legal entity is the seller and the principal on every
path. HorecaOS is a software vendor that requests receipts and keeps the evidence. It is on no row as the issuer and
on no row as the party that receives the money (ADR 0165).

| Path | Methods | Who issues | A wrong or refunded receipt is corrected… |
|---|---|---|---|
| `PARTNER` | Click, Payme | the provider, through the tenant's merchant account | in the provider's cabinet: Payme's `CANCEL` is a **second** receipt; Click's reversal exists only **inside the reporting month** and takes no amount; otherwise the tenant corrects on its own equipment |
| `TERMINAL` | cash at a branch with a registered, capable terminal | the tenant's own fiscal terminal | on the same terminal, by the tenant |
| none | cash at a branch with **no** capable terminal | nobody: the document reads `NOT_APPLICABLE` (the 2026-08-22 decision) | not a HorecaOS document; the tenant's own books |
| `MARKETPLACE` | an aggregator collected the money | the aggregator, if its contract says so | outside HorecaOS |

---

## 1. A customer or an inspector wants the receipt

**Who:** a role holding `fiscal.document.read` sees the order's fiscal documents with every identifier **masked**
(last four characters). Seeing an identifier in full needs `fiscal.evidence.reveal` (tenant owner and finance) and a
**purpose** from the closed list `TAX_INQUIRY`, `CUSTOMER_COMPLAINT`, `AUDIT`, `CORRECTION`. Every reveal writes a
`SECURITY` audit fact naming the document, the fields and the purpose; the identifiers never reach a log.

1. Open the order. The fiscal panel states, per document: its status, **which INN issued it** (as it was when the
   document was opened, not as the company is registered today), the merchant account or terminal, the lines **as
   filed** (the delivery fee as its own line), how the payment was split, and the marking codes as a **count**
   (never a code, never a tail).
2. Press **show** and choose a purpose. The link to the tax authority's page and the fiscal sign appear for two
   minutes.
3. Read the lines against the sale. The lines are what the checkout carried (Payme) or what was built from the
   order when the receipt was filed (Click, terminal); they are **kept as sent**, so the catalogue changing since
   does not change what they show.

**Check:** the document reads `ISSUED`. Anything else is section 6.

## 2. The receipt is blocked, or was never issued

Finance → **Fiscal**, the blocked worklist. Find the reason in section 6, fix the cause, press **Unblock** (the
document returns to the queue with a fresh deadline) or **Ask again**. Asking again is safe on every path: the
read-back precedes any new request, and a document that already holds a receipt is never sent again.

## 3. The receipt is wrong: a correction or a void

A correction changes what the receipt states; a void withdraws the sale. Both are a **request HorecaOS records and the
tenant's agent carries out**.

1. **Raise it** (`fiscal.document.resolve`): on the document's card choose *Correct* or *Void*, a reason from the
   list (`WRONG_AMOUNT`, `WRONG_LINES`, `WRONG_CLASSIFICATION`, `WRONG_SELLER`, `DUPLICATE_RECEIPT`,
   `CUSTOMER_REFUND`, `OTHER`) and a sentence. Only an `ISSUED` sale can be asked about.
2. **A second person approves** (`fiscal.correction.request`, ADR 0027). Your first call is recorded and answers
   `SECOND_APPROVER_REQUIRED`; the checker decides it in their approvals list; you then repeat the same call. You cannot
   approve your own request.
3. The document moves to `CORRECTION_REQUESTED` or `VOID_REQUESTED` and appears on the **open requests** list,
   aged in days. There is no HorecaOS deadline: the legal deadline per path is finance's and counsel's, and the list
   does not alarm.
4. **Do it on the path the sale used** (the table above). Write down the new receipt's fiscal sign, number and its
   link on the tax authority's own site.
5. **Attach the reference.** On the request press *Attach the agent's reference* and type the sign, the number and
   the `https` link (only `soliq.uz` and its subdomains are accepted; anything else is refused with 400 before a
   second person is asked). Attaching is **itself** an approval action (`fiscal.correction.attach`): a second person
   signs what was typed, so type exactly what the agent printed. When it is signed and you repeat the call, a linked
   `CORRECTION` (or `REFUND`) document is created and the original reads `CORRECTED` (or `VOIDED`). The original's
   evidence is never rewritten.
6. **Getting out without a receipt.** If the agent would not issue it, close the request as `DECLINED`; if it was
   raised in error, `CANCELLED`. Both file nothing, record who closed it and why, and return the document to `ISSUED`.
   An attached request cannot be closed: the corrected receipt is not taken back.

**Check:** the original is `CORRECTED` or `VOIDED`, beside a linked document whose evidence you can open with purpose
`CORRECTION`.

## 4. The order was amended after its receipt

Every financial amendment of an order records, on its revision, that its command carries a fiscal consequence
(ADR 0039). When the order's receipt had **already left** before that revision, the platform opens a correction request
on its own, every five minutes, once per revision, for the latest of several:

- The request reads reason `AMENDED_AFTER_ISSUE` («Order amended after the receipt was filed»), is requested by
  `fiscal-amendment-watch` and has **no approver**: nobody has agreed yet that the receipt is wrong.
- What opens it: an `ISSUED` sale whose receipt was sent before the revision. A receipt not yet sent is built from the
  amended order; one still waiting on its provider is picked up on a later pass; one already `CORRECTED`, `VOIDED` or
  being corrected is left alone.
- What to do: compare the receipt's lines and total with the amended order. If they differ, carry on from step 3.4. If
  the amendment did not change what the receipt states (a note, a promised time), close it `CANCELLED` with that
  sentence. It is not reopened for the same revision; a later amendment asks again.

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -c "
SELECT r.id, r.document_id, r.source_revision, r.status, r.requested_at
  FROM fiscal.fiscal_correction_requests r
 WHERE r.requested_by = 'fiscal-amendment-watch' AND r.status = 'REQUESTED'
 ORDER BY r.requested_at;"
```

## 5. A customer wants money back (ADR 0048, ADR 0165)

HorecaOS never calls a provider's refund API. **Before you open the console, do the refund where the money is.**

| The customer paid by | Do this first | Then in HorecaOS |
|---|---|---|
| Click | in the Click cabinet, reverse the payment. It takes **no amount** (no partial) and works only **inside the reporting month**; a card scheme may still refuse it | record the refund (`refund.request`, then `refund.execute`) against the order; raise a **void** request on the receipt (reason `CUSTOMER_REFUND`) and attach the reversal's receipt reference |
| Payme | in the Payme merchant cabinet, cancel the transaction. It reaches HorecaOS as `CancelTransaction`, which HorecaOS can accept or veto | the same, once Payme has told HorecaOS; Payme's `CANCEL` is a second receipt on a `REFUND` document |
| cash | at the counter, pay the cash back, and issue the refund receipt **on the branch's terminal** | record the refund as attested money (the platform did not move it) with the reference; raise the void request and attach the terminal's receipt reference |

**The refund-without-a-source rule (ADR 0165 Decision 5).** If the original method **cannot** take the money back
(Click's month has closed, Payme's cabinet refuses, a card is closed), the tenant pays it out of band: cash at the
counter, a bank transfer, or the provider's cabinet when it works. The operator records **exactly that**, with the
reference. There is **no** «credit the account instead» remedy: a money refund never becomes a balance, a balance never
becomes money, and no remedy converts points to money or money to points. A refund recorded as attested money is
listed on the unverified worklist until finance confirms it against a named source (`remedies/{id}/verification`, by
someone other than the person who recorded it).

**Check:** the order's remedies show the refund; the receipt reads `VOIDED` (or `CORRECTED` if only part was
returned and the agent issued a smaller receipt); the unverified worklist shows the attested part until it is
confirmed.

## 6. A document is waiting or blocked: what each reason means

| Status / reason | Meaning | What to do |
|---|---|---|
| `PENDING` / `AWAITING_CAPTURE` | a provider order whose payment has not captured, so there is nothing for Click to fiscalize yet | wait; if the order never completes it is not a sale |
| `PENDING` / `AWAITING_TERMINAL` | a cash sale at a branch with a capable terminal; nothing has been asked of the terminal yet | the sweeper sends it within a minute; if it stays, see `NOT WIRED` below |
| `SUBMITTED` / `AWAITING_PROVIDER` | asked; the answer has not come, or **was lost** | **do not** assume it failed. Wait for the reporting deadline (sixty minutes by default); the next ask reads the receipt back before it sends anything |
| `BLOCKED` / `CLASSIFICATION_MISSING` | a dish, an option or the delivery fee has no complete ИКПУ, package code, unit code and fiscal name | classify it (the catalogue's fiscal classification of that node, or of the delivery fee), then **Unblock**. Nothing is ever filed with a placeholder code |
| `BLOCKED` / `VAT_NOT_EXPRESSIBLE` | the rate is not a whole percent, the prices were not VAT-inclusive, or the quote recorded no rule | a tax-profile fix by finance; then **Unblock** |
| `BLOCKED` / `RECEIPT_DOES_NOT_RECONCILE` | the lines cannot be made to add up to the payment (a weighed line never weighed, a second money tender, modifier prices an old order never itemised) | read the note; fix the order's data; **Unblock**. No line absorbs a difference |
| `BLOCKED` / `MARKS_INCOMPLETE` | a marked line holds fewer scanned codes than units | scan the rest; it reopens by itself when the counts complete |
| `BLOCKED` / `NO_FISCAL_PATH` | no merchant account for the method, **or** no active capable terminal of the selling entity at the location | bind the account or register the terminal (a terminal registered under another company does not count), then **Ask again** |
| `BLOCKED` / `TERMINAL_OFFLINE` | the terminal could not be reached and **nothing was sent** | bring the device back, then **Ask again**; the document is issued once |
| `BLOCKED` / `PROVIDER_REPORT_OVERDUE` | sent, and nobody reported within the deadline | find out from the provider whether a receipt exists (do **not** press a second time before you know); **Ask again** reads back first |
| `FAILED` / `PROVIDER_REJECTED` | the provider or terminal refused it | the note carries the refusal; correct the cause; **Ask again** |
| `CORRECTION_REQUESTED`, `VOID_REQUESTED` | section 3 | |

**«Not wired».** An **Ask again** that answers it means no adapter is present in this deployment: no
`FiscalTerminalPort` (no vendor adapter exists yet, [the provider note](../providers/fiscal-terminal.md)) or no
partner port. The document is returned to the queue with nothing sent.

## 7. Payme: the receipt goes with the checkout

Payme takes its fiscal lines **with the checkout**, before the customer pays, so they are built when the order's
payment is first presented and kept as sent on the payment attempt. Consequences:

- A fiscalised Payme cashbox is **not presented** a payment whose receipt cannot be built. The customer is told the
  order cannot be paid through Payme yet (`RECEIPT_NOT_BUILDABLE`); the cause is one of the blocked reasons above (usually
  `CLASSIFICATION_MISSING`). Classify the node and ask the customer to try again, or take another method.
- `CheckPerformTransaction` is refused (`-31008`) for a fiscalised cashbox that holds no lines or whose lines do not add
  up to the amount.
- Which Payme surface reads the lines (the checkout form's `detail` field, or the `CheckPerformTransaction` result) is an
  open question to Payme (U10). Until it is answered the property `horecaos.payments.payme.receipt-surface`
  (`HORECAOS_PAYMENTS_PAYME_RECEIPT_SURFACE`) is `BOTH`: the customer's browser is handed a form to POST, and a link or QR
  is covered by `CheckPerformTransaction`. **Do not set it to `FORM` while operators send payment links or QR codes**: a
  link cannot carry `detail`.

## 8. How long evidence is kept

`fiscal.evidence_retention_years` is seven years and **provisional** until counsel and finance confirm it. A tenant may
lengthen it and cannot shorten it below the platform's. Offboarding a tenant does not shorten it: no role the application
uses may delete a fiscal row, a tenant row cannot be removed while a fiscal document names it, and a test fails the build
if a statement that deletes from a fiscal table is added.

The platform's report (`GET /api/v1/control-plane/fiscal/retention`, `fiscal.document.read` at platform scope) lists, per
tenant that holds fiscal documents, how many are dated before now less the retention. **It deletes nothing and marks
nothing.** The ADR 0029 startup guard that refuses a production profile while the key is provisional is **not built**.

## What you must not do

- Do not press **Ask again** twice because nothing seemed to happen. The read-back makes it safe, but the second press
  adds an attempt to a document that may already hold a receipt.
- Do not attach a reference you did not see on the agent's own cabinet or printout. A second person signs what you typed,
  and a wrong reference in front of the customer is a legal artifact.
- Do not «fix» a blocked document by giving the item a placeholder code. The classification is the tenant's.
- Do not issue a correction «for the tenant» from a HorecaOS account. HorecaOS has no INN of its own to issue under.
