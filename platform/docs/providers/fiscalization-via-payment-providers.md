# Fiscalization via payment providers: Click and Payme

- Status: working notes, not a decision. Feeds ADR 0038's `PARTNER`
  responsibility and its open input "which party is the legal fiscal agent for
  each settlement path".
- Sources: `https://docs.click.uz/merchant-api/fiscalization` (RU, read
  2026-08-22) and `https://developer.help.paycom.uz/metody-merchant-api/setfiscaldata`
  plus the pages it depends on — `receipts.create`, `Отправка чека по методу POST`,
  `receipts.set_fiscal_data`, `Типы данных`, `Ошибки отправки чека`.
- Related, and owned elsewhere: the Click and the Payme provider-contract notes
  in `docs/providers/`. Those files own the payment protocol itself — Prepare /
  Complete, CheckPerformTransaction / CreateTransaction / PerformTransaction /
  CancelTransaction, signatures, and error codes. This file owns only the fiscal
  surface and cites them rather than restating them.
- Decision this serves: **the restaurant's legal entity is the seller and the
  principal; HorecaOS is an agent and never the issuer.** ADR 0038 already says
  HorecaOS stays a requester and retainer of fiscal documents.

## Answers up front

1. **Neither provider's field list is covered by V0021.** `mxik_code` and
   `package_code` are two of roughly seven required per-line fields. Missing
   today: the fiscal **unit code**, the **VAT percent as an integer**, a
   **fiscal name** distinct from the display name, the **marking-code array**
   for marked goods, and — for Click only — a per-line **`CommissionInfo`**
   carrying a TIN or PINFL. See [Field lists](#field-lists-side-by-side).
2. **The two providers invert the timing.** Click fiscalizes strictly *after*
   capture — `submit_items` requires a CLICK `payment_id`, which does not exist
   until the payment does. Payme fiscalizes from data supplied *before* the
   customer pays, and reports the fiscal outcome back asynchronously afterwards.
   A captured payment with no receipt is therefore reachable on both paths, by
   different mechanisms. See [Timing and the failure-after-capture path](#timing-and-the-failure-after-capture-path).
3. **What comes back is the same shape on both:** a fiscal sign, a receipt
   number, a virtual-fiscal-module (terminal) id, an OFD registration date, and
   a `https://ofd.soliq.uz/epi?...` QR URL. All of it is evidence and all of it
   must be stored as fields, not as a link. See [What comes back](#what-comes-back-and-what-is-evidence).
4. **Neither provider takes the seller's identity as a per-request field.** It is
   derived from the merchant account: the Click `service_id` plus
   `merchant_user_id`/`secret_key`, and the Payme cashbox. Payme's
   `receipts.create` response proves it — the receipt carries a `merchant`
   object with `organization` on it, populated from the cashbox, with no input
   from the request. **Therefore one HorecaOS merchant account cannot serve many
   restaurants under the `PARTNER` path.** Each restaurant legal entity needs
   its own Click service and its own Payme cashbox. See [Whose fiscal identity](#whose-fiscal-identity-is-used).
5. **A cash order gets no fiscal receipt from either provider, and cannot.**
   There is no payment for Click to hang `payment_id` on and no Payme receipt to
   fiscalize. Click's `received_cash` field is a *tender split inside a Click
   payment*, not a cash-order path, and reading it as one is the single most
   expensive misreading available in this document. Cash is `TERMINAL` or it is
   unreceipted. See [Cash](#cash-the-part-that-gets-skipped).

## How each provider actually fiscalizes

### Click — merchant pushes items after the payment

Three endpoints, all on `https://api.click.uz/v2/merchant/`, all authenticated
with the same header the rest of the Merchant API uses:

```text
Auth: merchant_user_id:sha1(timestamp + secret_key):timestamp
```

| Call | Direction | Purpose |
|---|---|---|
| `POST payment/ofd_data/submit_items` | HorecaOS → Click | Send the receipt lines and the tender split for one completed CLICK payment |
| `POST payment/ofd_data/submit_qrcode` | HorecaOS → Click | Attach a fiscal receipt URL that *someone else* produced, to a CLICK payment |
| `GET payment/ofd_data/:service_id/:payment_id` | HorecaOS → Click | Read back `{ paymentId, qrCodeURL }` |

```json
{
  "service_id": 12345,
  "payment_id": 987654321,
  "items": [],
  "received_ecash": 0,
  "received_cash": 0,
  "received_card": 100000
}
```

`payment_id` is CLICK's payment identifier, not HorecaOS's. HorecaOS's own identifier
is `merchant_trans_id`, which the legacy system populated with `transactions.id`
— and which is recoverable in the other direction through
`GET payment/status_by_mti/:service_id/:merchant_trans_id/YYYY-MM-DD`. That
lookup is the recovery hinge: it is how a captured-but-unfiscalized order finds
the `payment_id` it needs when the original callback was lost.

The response to all three is `{ "error_code": 0, "error_note": "Success" }` over
standard HTTP status codes (400, 401, 403, 404, 406, 410, 500, 502).

`submit_qrcode` deserves attention out of proportion to its size: it is the seam
through which a receipt issued by *the restaurant's own cash register* is
attached to a Click payment. It is the only place in either provider's fiscal
surface where the issuer is not the provider.

### Payme — merchant supplies detail before, provider reports after

Payme has no "fiscalize this payment" call for a Merchant API integration. The
line data rides along with the payment itself:

- **Merchant API / hosted checkout**: the `detail` object, a JSON document
  base64-encoded into the `detail` form field alongside `merchant`, `amount` and
  `account[...]`, at the moment the customer is sent to Payme.
- **Subscribe API**: the same `detail` object as a parameter of
  `receipts.create`.

Then, after the receipt reaches a successful state, Payme calls **the merchant's**
JSON-RPC endpoint:

```json
{
  "method": "SetFiscalData",
  "params": {
    "id": "61396aaed8b87a4c215ae556",
    "type": "PERFORM",
    "fiscal_data": {
      "receipt_id": 121,
      "status_code": 0,
      "message": "accepted",
      "terminal_id": "EP000000000025",
      "fiscal_sign": "800031554082",
      "qr_code_url": "fiscal receipt url",
      "date": "20220706221021"
    }
  }
}
```

Three facts about this call that are easy to miss and expensive to miss:

- **It is optional to implement.** The docs say so outright («Данный метод не
  обязателен к реализации»). Not implementing it means the money moves, the
  receipt is issued, and HorecaOS holds no evidence of it. For a platform whose
  entire fiscal position is "retain evidence", not implementing it is the same
  as not fiscalizing.
- **Its arrival is not proof of a receipt.** `status_code` is a status, and
  `message` is «Детальная информация об ошибке (если произошла ошибка при
  регистрации чека в ОФД)». A `SetFiscalData` with a non-zero `status_code`
  reports an OFD *failure*. Storing `fiscal_data` and marking the document
  `ISSUED` on arrival is a defect that will pass every test written against the
  happy-path example.
- **`type: "CANCEL"` is a second fiscal document, not an update to the first.**
  The docs are explicit: the tax authority forms two different fiscal receipts,
  so cancel data must be stored as a separate receipt. Payme's own worked
  example nests `perform_data` and `cancel_data` side by side.

There is also `receipts.set_fiscal_data` on the Subscribe API, pushing fiscal
data *into* Payme. That is the mirror case — a merchant who fiscalizes on their
own equipment and registers the result with Payme — and it is Payme's equivalent
of Click's `submit_qrcode`. Both matter later, under `TERMINAL`, and neither
matters for the `PARTNER` path.

## Field lists side by side

### Per receipt line

| Concept | Click `Items[]` | Payme `detail.items[]` | Notes |
|---|---|---|---|
| Name | `Name*` `string(63)` — "название товара/услуги (с единицей измерения)" | `title*` `String` | Click wants the unit of measure inside the string and caps it at 63. A Cyrillic dish name plus modifiers plus a unit will exceed that |
| ИКПУ / MXIK | `SPIC*` `string(17)` | `code*` `String` | Same value, two names |
| Package code | `PackageCode*` `string(20)` | `package_code*` `String` | Required by both |
| Unit code | `Units` `uint64` | `units` `Number` | **Numeric**, optional on paper for both, and Click compensates by demanding the unit inside `Name` |
| Quantity | `Amount*` `uint64` | `count*` `Number` | See the fractional-quantity note below |
| Unit price | `GoodPrice` `uint64` (optional) | `price*` `Number` (required) | |
| Line total | `Price*` `uint64` (required) | — (derived) | **The trap.** Click's `Price` is the *line total*; Payme's `price` is the *unit price*. Same word, factor of `quantity` apart |
| VAT amount | `VAT*` `uint64` tiyin | — (derived) | Click makes HorecaOS compute it |
| VAT rate | `VATPercent*` `int` | `vat_percent*` `Number` | Whole percent on both |
| Discount | `Discount` `uint64` + `Other` `uint64` | `discount` `Number` | Payme's is explicitly "с учётом количества" — already multiplied out |
| Barcode | `Barcode` `string(13)` | — | |
| Marking codes | `Labels` `string[300]` | **absent** | See below |
| Commission / agency | `CommissionInfo*` `{ TIN string(9), PINFL string(14) }`, one of the two required | **absent** | See [Whose fiscal identity](#whose-fiscal-identity-is-used) |

`*` marks fields the provider documents as required.

### Per receipt

| Concept | Click | Payme |
|---|---|---|
| Seller | `service_id` + `Auth` credentials | cashbox (`merchant` id + `X-Auth`) |
| Payment reference | `payment_id` (CLICK's) | `params.id` (Payme's receipt id) |
| Receipt type | — | `receipt_type` `Number`, sale/refund = 0 |
| Delivery | none — delivery is an ordinary item | `shipping { title, price }`, **with no `code`, no `package_code`, no `vat_percent`** |
| Tender split | `received_cash` / `received_card` / `received_ecash`, all tiyin | — |

### The four differences that will actually bite

1. **`Price` means opposite things.** Click: line total. Payme: unit price. One
   shared `toReceiptLine()` helper across both adapters is how an order gets
   fiscalized at `quantity²` times its value.
2. **Payme's `shipping` cannot be classified.** It accepts a title and a price
   and nothing else. ADR 0038 requires the `FEE` priceable node to carry an
   ИКПУ and a package code and blocks publication without them — so the
   delivery fee must be emitted as an entry in `items`, never in `shipping`,
   or the classification ADR 0038 spent a validator rule enforcing never reaches
   the receipt. Click has no `shipping` at all and forces the right answer.
3. **Payme has no field for a marking code.** Click has `Labels`. ADR 0038's
   `fiscal.fiscal_unit_marks` therefore has an outlet on Click and none on
   Payme. Concretely: **a marked good cannot be lawfully fiscalized through
   Payme's `detail` object.** If a tenant ever sells a marked SKU, the
   `marking_required` flag must remove Payme from the channel's payment methods,
   the same way ADR 0038 removes cash from a location with no fiscal terminal.
   This is not currently in ADR 0038 and should be.
4. **Money is tiyin on both wires and som in the platform.** ADR 0018 is
   explicit that `amount_minor` holds *whole som* for UZS. Click and Payme both
   specify tiyin. Every amount crossing either adapter is `amount_minor * 100`,
   and every amount coming back is `/ 100`. It must be one named conversion at
   the adapter boundary; a `* 100` appearing in two places is how half a receipt
   ends up in the wrong unit.

### Whether V0021's two columns are sufficient

They are not. V0021 put `mxik_code` and `package_code` on `catalog.products`,
`catalog.variants` and `catalog.modifier_options`, and its own comment says the
columns are "the smaller interim … the two fields every aggregator and every
receipt needs". Against the actual provider contracts, the remaining gaps are:

| Missing | Needed by | Where it should live | Note |
|---|---|---|---|
| Fiscal unit code | `Units` / `units` | Priceable node, alongside `mxik_code` | The legacy schema already had it: `variants.unit_code`, `NOT NULL`. It was carried in the legacy and dropped in the interim |
| VAT rate as whole percent | `VATPercent` / `vat_percent` | Derived from ADR 0018 `tax_profiles.rate_basis_points` | 1200 bp → `12`. **A rate that is not a whole number of percent cannot be expressed** in either provider's integer field. ADR 0018 must either constrain the basis points to multiples of 100 or the adapter must reject the profile rather than round it |
| VAT amount in tiyin | `VAT` (Click) | Derived per line from the ADR 0018 quote | Must come from the quote's recorded tax share, never recomputed at fiscalization time — ADR 0038 already requires lines to be derived from the accepted quote snapshot |
| Fiscal name, ≤63 chars, unit included | `Name` (Click) | Priceable node | The display name is a locale dict aimed at a customer. A fiscal name is a different string with a different constraint and a different audience |
| Marking codes | `Labels` (Click) | ADR 0038 `fiscal.fiscal_unit_marks` | The table is specified; the wire field only exists on Click |
| Agency TIN/PINFL | `CommissionInfo` (Click) | `tenant.legal_entities.tin` | Required per line by Click, and unresolved — see below |
| Delivery-fee classification | both, as an `items` entry | ADR 0038 `FEE` priceable node | V0021's columns are on products, variants and modifier options only. The fee has nowhere to carry a code today |

One naming trap while the interim columns are live: legacy `variants.package_id`
and `variants.is_package` are a **bundle** concept and have nothing to do with
the fiscal `package_code`. A migration that maps one onto the other produces a
menu that looks classified and is not.

## Timing and the failure-after-capture path

This is the case that matters. Both providers can leave money captured with no
fiscal receipt, and they get there differently.

```text
CLICK        quote ──► payment ──► CAPTURED ──► submit_items ──► OFD receipt
                                      ▲              │
                             money has moved         └─ fails here → unreceipted sale

PAYME        quote ──► detail ──► payment ──► CAPTURED ──► SetFiscalData(status_code)
                          │                                        │
                 lines fixed here                                  └─ never arrives, or
                 (before any money)                                   arrives non-zero
                                                                      → unreceipted sale
```

### Click

`submit_items` cannot be called before capture, because `payment_id` does not
exist before capture. The window between "money moved" and "receipt exists" is
structural, not incidental, and its width is the platform's retry policy.

Recovery path, in order:

1. **Read back before retrying.** `GET payment/ofd_data/:service_id/:payment_id`
   returns `{ paymentId, qrCodeURL }`. A populated `qrCodeURL` means a receipt
   exists and the retry must not be sent. The docs do not state that
   `submit_items` is idempotent, so this read is what makes retry safe. **Confirm
   idempotency with Click in writing** and record the answer here; until then,
   treat read-then-write as mandatory.
2. **Retry `submit_items` with the same `payment_id` and the same lines.**
   ADR 0038's rule holds: the retry reuses the same `fiscal.fiscal_documents`
   row, incrementing `attempt_count`, and never creates a second document. Two
   receipts against one payment is a discrepancy that can only be corrected,
   never deleted.
3. **If the `payment_id` is unknown** — the callback was lost — recover it with
   `GET payment/status_by_mti/:service_id/:merchant_trans_id/YYYY-MM-DD` using
   HorecaOS's own transaction identifier and the payment's business date. This is
   why the business date must be snapshotted on the order at acceptance and not
   inferred later.
4. **If it cannot be fiscalized at all**, the document goes to `FAILED` and then
   to `BLOCKED` with a reason an operator can act on. The two exits are:
   - Reverse the payment: `DELETE payment/reversal/:service_id/:payment_id`.
     **Constrained**: the payment must have completed, must be an online-card
     payment, and must be in the current reporting month — a payment from the
     previous month can only be reversed on the first day of the current one,
     and UZCARD can refuse. A reversal is therefore not available as a general
     remedy for an unfiscalized sale discovered a week later.
   - Issue the receipt on the restaurant's own equipment and attach it with
     `POST payment/ofd_data/submit_qrcode`. This is the `TERMINAL` mechanism
     reaching into the `PARTNER` path, and it is the only remedy that does not
     depend on a calendar boundary.

### Payme

The failure mode is quieter, because nothing HorecaOS does fails. The lines are
fixed at checkout initiation; the payment succeeds; and then either
`SetFiscalData` never arrives, or it arrives with `status_code != 0` and a
`message` describing an OFD registration error.

- **There is no merchant-initiated retry on the Merchant API.** `SetFiscalData`
  is inbound only. `receipts.set_fiscal_data` runs the other way and is for a
  merchant who fiscalized elsewhere; it is not a "please try again" call.
- **A missing `SetFiscalData` is invisible unless the platform looks for it.**
  The document must therefore have a deadline: an order captured through Payme
  whose fiscal document is still `REQUESTED` after an agreed interval becomes
  `BLOCKED` with a reason, rather than staying quietly pending. Without a
  sweeper, "no receipt" and "receipt not yet reported" are the same state, and
  the tenant learns the difference at an audit. This sweeper does not exist in
  ADR 0038 and is the concrete thing this document asks for.
- **Remedies** are the same two as Click's, minus the first: the receipt is
  cancelled (producing a `CANCEL` fiscal document, stored separately), or it is
  issued on the restaurant's equipment and registered with
  `receipts.set_fiscal_data`.
- **Bad line data cannot be repaired after the fact.** Because `detail` is fixed
  before payment, an unclassified line reaches Payme as an unclassified line and
  the payment still succeeds. This is precisely why ADR 0038's publication-time
  classification blocker exists, and it is the argument for keeping it a blocker
  rather than a warning: on the Payme path there is no later checkpoint.

### The shared rule

Both paths converge on one requirement ADR 0038 already states and that these
contracts confirm is load-bearing: **the fiscal document is an obligation of the
order, resolved to issued, not-required, or visibly blocked, and never silently
absent.** Neither provider will tell HorecaOS that a receipt is missing. Only HorecaOS
asking will.

## What comes back, and what is evidence

| Field | Click | Payme | Keep |
|---|---|---|---|
| Fiscal sign | inside the QR URL (`&s=...`) | `fiscal_sign` | Yes — the identifier the tax authority recognises |
| Receipt number | inside the QR URL (`&r=...`) | `receipt_id` | Yes |
| Fiscal module / terminal | inside the QR URL (`&t=...`) | `terminal_id` | Yes — this is the virtual cash register that issued it, and therefore the taxpayer's equipment |
| Registration timestamp | inside the QR URL (`&c=...`) | `date` (`YYYYMMDDhhmmss`) | Yes |
| Receipt URL | `qrCodeURL` | `qr_code_url` | Yes, but see below |
| Outcome | `error_code` / `error_note` | `status_code` / `message` | Yes — a non-zero code is the evidence that there is *no* receipt |

Both providers return the same underlying object; Click returns it packed into
one `https://ofd.soliq.uz/epi?t=…&r=…&c=…&s=…` URL and Payme returns it as
named fields plus the URL. **Parse the Click URL into fields and store both.**
A URL is a pointer to a service HorecaOS does not run; its lifetime belongs to the
OFD, not to HorecaOS, and an evidence record that is only a dead link is not
evidence. Storing the components costs four columns —
`external_receipt_id`, `fiscal_sign`, `receipt_reference` and the terminal id —
which is exactly the shape ADR 0038's `fiscal.fiscal_documents` already has.

Alongside the response, store the **request**: the exact `items` array or
`detail` object that was sent, and the `service_id`/cashbox and legal entity in
force. The legacy `tax_receipts` table understood this — it held `payload`,
`request`, `response` and `error` as four separate columns — and it is the only
thing that makes an incorrect receipt explicable a year later.

### Where the evidence hangs

The legacy `tax_receipts.transaction_id` is a clue, and it is right about
something ADR 0038 states slightly too strongly. Under `PARTNER`:

- The **obligation** is per order. That is ADR 0038's position and it holds.
- The **evidence** is per provider transaction. Payme's `PERFORM` and `CANCEL`
  are two distinct fiscal receipts for one order, by the docs' own statement.
  A split-tender order under ADR 0046 settled part on Click and part on cash
  produces evidence on two different paths for one order.

So `fiscal.fiscal_documents` is correctly keyed by `order_id` with a nullable
`payment_transaction_id`, but **there must be no unique index on `order_id`
alone**. ADR 0038's "exactly one fiscal document" is a statement about the
obligation being resolved once, not about row count: the `SALE` document and its
`REFUND`/`CORRECTION` are linked by `corrects_document_id`, and Payme's
`CANCEL` payload maps to that second row rather than overwriting the first.
An implementer reading "exactly one" as a constraint will write the cancel data
over the perform data and destroy the only record that the sale was fiscalized.

### Retention

Neither provider's documentation says anything about retention — it is not their
obligation, it is the seller's. The practical floor is the tax limitation
period, which in Uzbekistan is commonly cited as five years, and the honest
position is that **finance and legal set the number, not the code.** Two things
follow regardless of what they choose:

- Retention is per legal entity, because the obligation is the restaurant's, not
  HorecaOS's, and a tenant offboarding does not extinguish it.
- Retention must be an ADR 0030 policy value, not a constant, because it is a
  jurisdiction fact and this platform already intends to serve more than one.

Under ADR 0029 the fiscal sign, the receipt URL and any marking code are
protected evidence: stored behind a protected reference, never logged, and never
carried in an ADR 0032 event. An INN is a business identifier and may travel.

## Whose fiscal identity is used

**Neither provider accepts the seller's identity as a per-request field.**

- **Payme.** Nothing in `detail`, `receipts.create` or the checkout form names a
  taxpayer. The `receipts.create` response settles it from the other direction:
  the receipt carries a `merchant` object with `_id`, `name`, `organization`
  (the worked example shows `"ЧП «test test»"`), `business_id` and an `epos`
  block with `merchantId`/`terminalId`. All of it comes from the cashbox the
  `X-Auth` credential belongs to. **The cashbox is the taxpayer.**
- **Click.** `service_id` plus `merchant_user_id`/`secret_key` identify the
  merchant, and every fiscal call carries `service_id`. No seller TIN appears
  anywhere in the request body.

**Consequence, stated plainly: one HorecaOS merchant account cannot serve many
restaurants on the `PARTNER` path.** Every receipt issued through a shared HorecaOS
Click service or a shared HorecaOS Payme cashbox would name HorecaOS as the seller,
which is the opposite of the decision that the restaurant is the principal. Each
restaurant legal entity needs its own Click service and its own Payme cashbox,
registered to its own INN, with credentials held as ADR 0026 secret references
bound per legal entity — not per tenant.

The legacy system already worked this way and is corroborating evidence:
`fin_agents` is keyed `UNIQUE (payment_method_id, vendor_id)`, one provider
agent per vendor, with an `extra` JSON column that "suggests it is where a
provider's merchant credentials or terminal identifiers would live". That is a
per-restaurant merchant account by construction.

This has a direct consequence for ADR 0038's schema. `payments.payment_methods`
carries a single `provider_installation_id`, required when `responsibility` is
`PARTNER`. If the taxpayer is the cashbox and a tenant holds several legal
entities, then one tenant-scoped `CLICK` method row cannot be correct for all of
them — the binding has to resolve per legal entity, the same way
`tenant.location_fiscal_assignments` resolves the entity itself. Either the
installation binding gains a legal-entity dimension, or the method registry
does. This should be settled before the `PARTNER` slice is built, because
retrofitting it means re-pointing live payment configuration.

### The one thing the docs do not answer: `CommissionInfo`

Click requires `CommissionInfo` on every item, carrying either a `TIN`
(9 characters) or a `PINFL` (14), and labels it «Данные комиссионного чека» —
commission-receipt data. The documentation does not say **whose** TIN it is.

This matters more than any other open question here, because commission trade
(комиссионная торговля) is the Uzbek fiscal construct for exactly the
arrangement HorecaOS has chosen: goods sold by one party on behalf of another, with
the receipt naming the committent. If `CommissionInfo.TIN` is the committent's
— the restaurant's — then a single Click merchant account issuing receipts that
name each restaurant per line becomes at least conceivable, and the "one account
per restaurant" conclusion above softens into a legal question rather than a
technical one. If it is the buyer's, or the commission agent's, it does not.

**Do not guess this.** Two actions, both cheap:

- Ask Click, in writing, whose TIN/PINFL `CommissionInfo` carries and whether a
  receipt issued under service A may name a different TIN per line. Record the
  answer in this file.
- Ask legal whether the HorecaOS–restaurant agreement is a commission agreement
  (договор комиссии) in the sense the tax rules use, or an agency arrangement
  that does not qualify. ADR 0038's open input "which party is the legal fiscal
  agent for each settlement path" is the same question and should be closed with
  this one.

Until both are answered, the safe assumption — and the one to build against — is
**per-restaurant merchant accounts**, because it is correct under either answer.

## Cash: the part that gets skipped

**A cash order gets no fiscal receipt under this model. None. Not partially, not
by a workaround.**

The reasoning is short and there is no way around it:

- Click's `submit_items` requires `payment_id`, a CLICK payment identifier. A
  cash order has no CLICK payment, so there is nothing to call the endpoint
  with.
- Payme's fiscal data attaches to a Payme receipt. A cash order has no Payme
  receipt.
- Neither provider is in the transaction, sees the transaction, or is contracted
  to report it.

**`received_cash` is not a cash path.** Click's `submit_items` takes
`received_ecash`, `received_cash` and `received_card` together, and they
describe how a *CLICK payment* was tendered. They exist for split tender inside
a payment Click already knows about. Reading `received_cash` as "the endpoint
supports cash orders" is a plausible-looking error that produces a system which
appears to fiscalize cash and does not — the worst possible outcome, because the
gap is invisible until an inspection.

This is not a marginal case. The legacy `payment_methods` table seeds exactly
three rows — `cash` (on), `click` (off), `payme` (on) — and cash is the method
this market's customers use most. So the platform's fiscal coverage under a
`PARTNER`-only implementation is *the minority of orders*, and ADR 0013's
original position ("the partners fiscalize, HorecaOS retains evidence") is
confirmed here as correct-and-inapplicable exactly as ADR 0038 says.

What that leaves:

| Path | Who issues | Status |
|---|---|---|
| Click / Payme online card | The provider, under the restaurant's merchant account | Documented here; buildable now |
| Cash, courier terminal, kiosk, dine-in POS | The restaurant's own fiscal-capable equipment (`TERMINAL`) | ADR 0038 stage 5; depends on ADR 0011 POS work not yet built |
| A receipt issued on the restaurant's equipment for an order paid through a provider | The restaurant's equipment, registered back via Click `submit_qrcode` or Payme `receipts.set_fiscal_data` | Documented here; the failure-recovery remedy |
| HorecaOS calling a fiscal operator directly (`OPERATOR`) | HorecaOS | Specified by ADR 0038, deliberately unimplemented |

ADR 0038's serviceability precondition — cash is not offered on any channel
serving a location with no active fiscal-capable terminal — is therefore not
conservatism. It is the only thing standing between the pilot and a large share
of its orders being unreceipted sales, and these provider contracts contain
nothing that relaxes it.

## Open questions to close

| Question | Ask | Blocks |
|---|---|---|
| Whose TIN/PINFL does Click's `CommissionInfo` carry, and may it vary per line under one service? | Click | Whether one merchant account can serve many restaurants |
| Is `submit_items` idempotent for a repeated `payment_id`? | Click | Whether read-back before retry is mandatory or merely prudent |
| How is a fractional quantity expressed in `Amount` (`uint64`) and `count`? | Click, Payme | Any catch-weight or splittable item; ADR 0038 forbids it only for *marked* goods |
| Is there a deadline after which `SetFiscalData` will not arrive? | Payme | The sweeper interval that turns silence into `BLOCKED` |
| Full `status_code` list for `SetFiscalData` | Payme | Distinguishing a retryable OFD error from a permanent one |
| Is the HorecaOS–restaurant relationship a commission agreement in the fiscal sense? | Legal | ADR 0038's open input on fiscal agency |
| Retention period for fiscal evidence, per legal entity | Finance, legal | The ADR 0030 policy value |

## What this implies for ADR 0038

Nothing here contradicts the ADR. Five things sharpen it, and all five are
cheaper to fix in the ADR than in code:

1. `mxik_code` + `package_code` are necessary and not sufficient. Add the unit
   code, the whole-percent VAT rate, and a fiscal name to the classification
   table's field list.
2. Payme cannot carry a marking code. `marking_required` must constrain the
   payment method the same way it constrains the channel.
3. The delivery fee must be emitted as an ordinary receipt line, never through
   Payme's `shipping`, or the `FEE` classification never reaches a receipt.
4. "Exactly one fiscal document per order" is a statement about the obligation.
   Do not enforce it as a unique index — Payme's `PERFORM`/`CANCEL` pair and
   split tender both produce more than one row.
5. A Payme `PARTNER` order needs a sweeper that turns a never-arriving
   `SetFiscalData` into `BLOCKED`. Without it the `PENDING`/`REQUESTED` state is
   indistinguishable from a missing receipt, which is the exact failure ADR 0038
   exists to prevent.
