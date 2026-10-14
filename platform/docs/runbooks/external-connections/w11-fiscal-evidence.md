# External connections: the fiscal evidence (wave `w11-fiscal-evidence`, gap-map row `X.2`)

**Last executed:** never. Nothing here has touched a real Click, Payme or tax-authority endpoint. Every
receipt line the platform builds is proved against the fake Click provider (`FakeClickHttpProvider`,
`tools/pay-fake-click`) and against hand-written golden receipts; the facts below are the ones only the owner,
a provider or a tenant can supply. Per ADR 0023 a runbook that has never been executed is a draft.

**The way back, before the first step.** None of the facts below is a code change. Each is a value in a
configuration row, a property or tenant data, and each is withdrawn by putting the previous value back. Nothing
in this wave can file a receipt on its own: a document whose receipt cannot be built is `BLOCKED` with a reason
(`CLASSIFICATION_MISSING`, `VAT_NOT_EXPRESSIBLE`, `RECEIPT_DOES_NOT_RECONCILE`), and a blocked document is
recoverable where a filed receipt can only be corrected.

This file has one section per fact. Each says what exists in the tree now, where the value goes, and how to prove
the connection.

---

## 1. The Click quantity scale (how a weighed count becomes `Amount`)

**The fact.** Click types `Items[].Amount` as `uint64`. How a fractional count (1.040 kg) is written into it, in
particular whether the field is in thousandths, is not documented. It is an open question to Click, to be
answered in writing (`docs/providers/fiscalization-via-payment-providers.md`, «Open questions to close»; ADR 0038
open input).

**What exists.** `ClickQuantityScale`, a uniform multiplier. The default is `1`: every receipt filed so far sends
the count as it is, and **a fractional count is refused before anything leaves**, with the rejection code
`QUANTITY_UNREPRESENTABLE`, because rounding a weighed quantity misstates a line on a legal document. At scale
`1000` a count of `1.040` is `Amount 1040`, `0.5` is `500`, and a whole `2` is `2000` (the scale is uniform: a
policy that scaled only fractional lines would send `2` and `1040` to a field that means one thing). The
`Price` of a line is its total and `GoodPrice` its unit price at any scale; `received_card` is what the customer
paid. `ClickShopApiCallbackTests` pins all of it.

**Where the value goes.** The property `horecaos.payments.click.quantity-scale` (an environment variable
`HORECAOS_PAYMENTS_CLICK_QUANTITY_SCALE` in `/etc/horecaos/production.env`), restart the platform. No migration.

**How to prove it.** Against the Click sandbox of runbook `connect-click-payme-sandbox.md`: place a cash-free
order holding a weighed line (a basket with a weighed line is sold only by a method that settles at handover, so
for this proof a document is built by hand: weigh a line on a sandbox tenant and use the operations console's
retry), then read the receipt Click returns (`GET payment/ofd_data/...`) and compare the count and the total on
the OFD page with the order. Set the scale only when the count on the OFD page equals the weight. Until Click has
answered, leave it at `1`: weighed lines then block as above and nothing wrong is filed.

## 2. Payme: which surface reads the receipt detail, and whether `count` may be a decimal

**The fact.** Which Payme surface reads the `detail` object (the checkout form's `detail` field, or the
`CheckPerformTransaction` result member; open question U10), the `SetFiscalData` deadline and status codes, and
whether `count` may be fractional.

**What exists.** `PaymeReceiptDetail` builds the documented shape, now with a decimal `count` (`1.04`, never an
exponent; a whole count stays an integer) and `price` as the unit price, and checks that `price × count − discount`
equals the amount charged to the tiyin. Both surfaces are **not yet wired** (row `X.2-5`, a later step of this wave):
the detail is validated and discarded.

**Where the value goes.** Nothing to set in this step. When the answer arrives it decides the default of the
`FORM`/`CHECK`/`BOTH` switch that the Payme wiring step introduces.

**How to prove it.** With a Payme test cashbox that has fiscalisation switched on: create a payment for a
sandbox order, then read what Payme recorded. Not possible before the wiring step lands.

## 3. The tax rule of a sale: delivery and points

**The fact (finance and legal).** Two tax determinations the receipt states and the platform does not own:

- **Whether a delivery charge is VAT-bearing** (ADR 0037's open input). The pricing engine records **no tax share
  on the delivery fee**, so the receipt files the fee as an ordinary item line at **0% VAT with a VAT amount of 0**,
  which is what the quote recorded. If finance rules that delivery carries VAT, the engine has to record a share on
  the fee first (it is a pricing change, not a receipt change), and only then does the line carry a rate. Until
  then this is a stated filing, not an invented one.
- **The VAT effect of a points redemption** (ADR 0046's first open input, «the number finance must sign off before
  redemption is enabled for a brand»). The builder implements ADR 0046's worked order: the redemption is a per-line
  discount on the goods, the delivery fee takes none, and the goods' VAT is extracted once from what the goods were
  paid (food 84 000, points 12 000: VAT 7 714, not 9 000). If finance rules the base may not be reduced, the
  allocator's redemption branch in `ReceiptLineAllocator` is the one place that changes.

**Where the value goes.** Nothing to configure. The sign-off is recorded on ADR 0046 (and ADR 0037 for delivery);
enabling redemption for a brand waits on it.

**How to prove it.** `ReceiptLineAllocatorTests.theWorkedOrderOfAdr0046` and
`ReceiptLineBuilderTests.pointsAreADiscountOnTheFood` state the arithmetic; finance checks one real redeemed order's
receipt against it.

## 3a. The retention period (`fiscal.evidence_retention_years`)

Not touched by this step. It stays a provisional seven years (ADR 0166 open input, counsel and finance); the
reader and the report-only sweep are a later step of this wave.

## 4. Click and Payme merchant credentials, one real round trip

**The fact.** A Click `service_id`, `merchant_user_id` and secret key per legal entity, and a Payme test cashbox
id and key with fiscalisation switched on, issued by the providers to the owner.

**What exists.** `runbooks/connect-click-payme-sandbox.md` walks the screens that connect them (**last executed:
never**). Everything in this wave runs against `FakeClickHttpProvider` and `tools/pay-fake-click`, with golden
receipts in `ReceiptLineBuilderTests`, `PartnerFiscalizationBridgeTests` and `ClickFakeProviderRoundTripTests`.

**Where the value goes.** `integration.provider_environments` (the approved environment) and an OpenBao reference
per installation (ADR 0028); never a value in a property file.

**How to prove it.** Run `connect-click-payme-sandbox.md`, pay one real sandbox order, run the operations console's
**Ask again** on the document, and read the lines on the order's fiscal card («Lines as filed»): the classification,
the unit and package codes and the delivery line must be the tenant's, and the OFD page must show the same total.

## 5. The ИКПУ/MXIK reference file, and each tenant classifying its catalogue

**The fact.** The official Tasnif file (`runbooks/mxik-reference-import.md`, ADR 0173) is not in the tree. The
classification itself, an ИКПУ, a package code, a unit code and a fiscal name per dish, per modifier option and for
the delivery fee, is tenant data.

**What exists.** The importer, the classification endpoints and the coverage report. **The builder refuses to guess:**
an order holding a node with no complete classification (any of the four fields) is `BLOCKED` with
`CLASSIFICATION_MISSING`, nothing is sent, and no placeholder code is ever written. The delivery fee is a node of its
own (`catalog.fees`, code `DELIVERY`) and blocks the same way.

**Where the value goes.** `catalog.fiscal_classifications` (the catalogue screens), and the national list through the
import runbook.

**How to prove it.** On a tenant with an unclassified dish: place an order, wait for the document to block, read the
reason on the finance fiscal page, classify the dish, press **Unblock**, and watch the document file.

## 6. A fiscal terminal, and counsel's confirmation of the retention period

Both belong to later steps of this wave (the `FiscalTerminalPort` and its fake; the retention reader). Nothing was
built against them here, and nothing in this step depends on them.
