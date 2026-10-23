# External connections: the fiscal evidence (wave `w11-fiscal-evidence`, gap-map row `X.2`)

**Last executed:** never. Nothing here has touched a real Click, Payme, tax-authority or fiscal-terminal endpoint.
Every receipt line the platform builds is proved against the fake Click provider (`FakeClickHttpProvider`,
`tools/pay-fake-click`), the fake fiscal terminal (`FakeFiscalTerminalAdapter`) and hand-written golden receipts; the
facts below are the ones only the owner, a provider, a vendor or a tenant can supply. Per ADR 0023 a runbook that
has never been executed is a draft.

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

**The fact (Payme, in writing; question U10 of `docs/providers/payme-merchant-api.md`).** Which Payme surface reads
the `detail` object of a fiscalised cashbox: the checkout form's `detail` field, or the `CheckPerformTransaction`
result member. The docs mark `items` «required» on the one page and «optional» on the other, and `detail` is not
documented as a GET parameter at all (U20), so a link or a QR cannot carry it. Also: the `SetFiscalData` deadline and
status codes, and whether `count` may be fractional.

**What exists.** Both surfaces are built and tested against a fake, behind one switch.

- The receipt lines are built **when the payment is first presented** (`ProtectedPresentedReceipts`), kept on the
  payment attempt behind an ADR 0029 protected reference (V0687), and carried unchanged on every surface and every
  re-presentation. A fiscalised cashbox is refused a presentation whose receipt cannot be built
  (`RECEIPT_NOT_BUILDABLE`); nothing is presented with a placeholder.
- `FORM`: `PaymentSession.form` (`StorefrontPaymentController.PaymentFormResponse`) hands the storefront a form
  (`action`, hidden `fields`, `detail` among them) that both storefronts submit (`payment-form.ts`, `https` only).
- `CHECK`: `CheckPerformTransaction` returns `detail` beside `allow`, and answers `-31008` for a fiscalised cashbox
  that holds no lines or whose lines do not add up.
- `PaymeReceiptDetail` writes a decimal `count` (`1.04`, never an exponent; a whole count stays an integer) and `price`
  as the unit price, and checks `price × count − discount` equals the amount charged to the tiyin.
- The filing sends exactly the lines the checkout carried and keeps them as the document's sent request.

**Where the value goes.** The property `horecaos.payments.payme.receipt-surface`
(`HORECAOS_PAYMENTS_PAYME_RECEIPT_SURFACE` in `/etc/horecaos/production.env`), restart the platform. No migration.
The default is **`BOTH`**, the provider note's own advice for U10 («always send items when the cashbox is fiscalised»),
and it is correct under either answer. When Payme answers, narrow it: `FORM` if only the form is read **and** no
operator sends payment links or QR codes (a link cannot carry `detail`, so `FORM` alone loses the receipt on every
link), `CHECK` if only the pre-flight is read. This wave decides nothing about the default.

**A browser rule to check at the edge.** The form is submitted by the storefront page to the Payme checkout host. If an
edge ever sets a Content-Security-Policy `form-action` directive, it must allow that host, or the browser refuses the POST
silently. None is set in this repository today (a search of both storefronts and `platform/infra` finds no
Content-Security-Policy); the host is per environment (the approved provider environment's checkout host).

**How to prove it.** With a Payme test cashbox that has fiscalisation switched on and a tenant whose dish is
classified: place an order paid by Payme and open the checkout; Payme's cabinet must show the same lines and total as
the order. Then read what Payme recorded through `SetFiscalData` (the document turns `ISSUED` with the sign and link).
Repeat once through a payment link (not the form) to see which surface carried it, and once with a weighed line to
see whether a fractional `count` is accepted. Record the answers in the provider note.

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

**The fact (counsel and finance, ADR 0166 open input).** How many years a legal entity's fiscal evidence must be kept,
and whether HorecaOS's arrangement with a restaurant is a commission agreement in the tax sense. Both are closed on
defaults that are correct under either answer («blocks: nothing»; for retention only the *provisional* flag).

**What exists.** The key is seven years and provisional. Its readers: the evidence screen, the platform's report
(`GET /api/v1/control-plane/fiscal/retention`) and a daily report-only log line (`FiscalRetentionSweeper`), all
through `FiscalEvidencePolicies.effectiveRetention`: the tenant's figure, **never below the platform's**. Nothing
deletes anything; the application role has no `DELETE` on a fiscal table. `TenantOffboardingKeepsFiscalEvidenceTests`
pins that offboarding cannot shorten it. **Not built:** the ADR 0029 startup guard that refuses a production profile
while the key is provisional.

**Where the value goes.** The policy `fiscal.evidence_retention_years` (ADR 0030) at `PLATFORM`, and optionally at
`TENANT`, through the policy editor: `{"years": N, "provisional": false}` once counsel has confirmed N. A tenant's value
below the platform's is ignored.

**How to prove it.** Read the report: the tenant's `retentionYears` is the confirmed figure and `provisional` is `false`.

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

## 6. A fiscal terminal

**The fact (the owner, a tenant and a vendor).** A real fiscal-capable device: vendor and model, its API
documentation, the device itself, and a tenant that owns one and registers it. Without it a cash sale stays what it
always was.

**What exists.** `FiscalTerminalPort` (`issue`, `lookup`), the contract every adapter satisfies
(`FiscalTerminalPortContract`) and the note that states it ([`docs/providers/fiscal-terminal.md`](../../providers/fiscal-terminal.md)),
a fake adapter under the `local` profile only, and the whole path against it: a cash sale at a branch with a registered,
capable terminal is opened by the obligation opener when the order completes, sent to the terminal (lookup first, issue
only if it holds nothing), and recorded (`ISSUED` / `TERMINAL_FISCALIZED`, the sign, number, terminal id and link, the
lines kept as sent, the whole amount as cash, the terminal named). Where there is no terminal the decision stands: cash
is `NOT_APPLICABLE`, an unevidenced tenant obligation, and a terminal registered under another company does not count.
In a deployment with no adapter a cash sale at a terminal branch waits `PENDING` and says «not wired»; it is never given a
receipt nobody issued.

**Where the value goes.** A vendor adapter implementing `FiscalTerminalPort` and extending `FiscalTerminalPortContract`;
the terminal registered in Settings → Fiscalization (the terminals tab) (`provider_binding_id` naming an installation whose approved
provider environment holds the endpoint, ADR 0026, never a tenant-typed URL) with the capability `IssueFiscalReceipt`.

**How to prove it.** Register the device, make a cash sale, complete the order, and watch the document go `ISSUED`;
check the receipt number and sign on the device's own journal against the order's fiscal card. Then switch the device
off and make another sale: the document must block `TERMINAL_OFFLINE` with **nothing** issued, and issue exactly once
when the device is back.

**Not built.** The recovery calls that attach a terminal receipt to a *provider* payment (Click `submit_qrcode`, Payme
`receipts.set_fiscal_data`): they matter only when a card payment and a terminal receipt coexist for one sale, which no
flow in this build produces, and Payme's `set_fiscal_data` belongs to the Subscribe API, which is not adopted. A marked
good cannot be issued through the terminal port (it carries no code).

## 7. Counsel's confirmation of the retention period

See 3a: one fact, one place. It is listed separately only because it belongs to a person, not to a deployment.
