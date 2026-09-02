# Operations spec — Finance

`apps/operations` · IA section 8 · screens 8.1 Payments & settlements, 8.2
Fiscal receipts.

Sources, in the order they were trusted: the built backend —
`OperationsRemedyController`, `OperationsPaymentController`,
`FiscalDocumentController`, ADR 0048 (refunds as bookkeeping), ADR 0013
(payment, refund and service-recovery compensation), ADR 0038 (legal
entities, fiscal receipts and product classification) — the
[information architecture](../frontend-information-architecture.md) Part 2
§8, and the [parity matrix](../delever-parity-matrix.md)'s Финансы notes.

Where a field exists today the real table and column are named. Where it
does not, the entry reads **not built** and names the owning decision.

---

## 0. Scope, and why this wave built two screens and not six

IA §8 lists six Finance screens. The tier legend (`frontend-information-architecture.md`
line 21: `P` = first single-location pilot, a go-live blocker) marks exactly
two of them `P`: **8.1 Payments & settlements** and **8.2 Fiscal receipts**.
8.3 Cash reconciliation, 8.4 Delivery cost reconciliation, 8.5 Courier
payouts and 8.6 Subscription & billing are all tier `2`, and Part 3 of the
IA names them explicitly: "Wave 2 ... Finance 8.3-8.6". This wave (34,
`wave34-opsmoney`) was framed around "Finance and Marketing"; checked
against the IA's own tiers, **Marketing §6 has no `P`-tier row at all** —
every one of its eight screens is tier `2` or `3`, and Part 3 lists "the
whole of Marketing" under Wave 2. Marketing therefore gets no nav entry and
no route this wave, the same silent omission Settings 10.11 (Data &
privacy, tier `3`) already gets — not a not-built placeholder, because a
placeholder is for a `P`-tier row with a real backend gap, not for a row
outside this wave's tier at all.

8.6's backend groundwork (`COMMERCIAL_SUBSCRIPTION_MANAGE`,
`COMMERCIAL_PLAN_READ`, `COMMERCIAL_USAGE_READ` on `TENANT_FINANCE`) already
exists — ADR 0021's own status line says there is no period close and no
invoices yet, so even a Wave 2 build of 8.6 would be honest-not-built for
those two rows. That is a later wave's problem, not this one's.

---

## 1. 8.1 Payments & settlements

**What IA 8.1 asks for:** "the `payment[]` array per order (split tender:
cash + cashback + deposit); payment status; re-issue a payment invoice to a
phone number other than the order's, idempotently; refunds;
provider-mappable cancellation reason for voids; auto-send payment link."

**What is real, checked against the code rather than assumed from the
prose:**

- **Payment status and the intent, not an array.** `PaymentIntent.tenderId`'s
  own Javadoc: split tender "sits between the order and this... empty until
  split tender ships". `payments.payment_intents` has one live intent per
  order (`ux_payment_intent_live_per_order`, `findLiveForOrder`), never a
  simultaneous cash-plus-cashback-plus-deposit settlement. The IA line is
  aspirational for a feature ADR 0046 has not decided. `OperationsPaymentController.forOrder`
  (new this wave) returns that one intent, its attempts, and what a
  `payments.payment_transactions` capture/return total says — the
  `payment[]` array's first and, for now, only element. The screen says so
  in a banner rather than pretending an array with one guaranteed entry is
  the feature.
- **Payment status was not reachable from any operations endpoint at
  all before this wave.** `OperationsOrderController`'s order list and
  detail responses carry no `payment_status_projection` field (confirmed by
  reading the controller source, not the migration alone) — Finance's own
  read had to be a new endpoint rather than a reuse, and it stays inside the
  `payments` module rather than reaching into `ordering`'s controller.
- **Re-issue to an alternate phone: real, and it existed half-built.**
  `PaymentCheckoutService.openOrRePresent` already took a nullable
  `customerAccountId` in its own Javadoc ("or null for a call that is not on
  a customer's behalf") without the type saying so — a NullAway gap this
  wave closed with one `@Nullable` annotation, no behaviour change.
  `StorefrontPaymentController`'s own doc predicted the missing piece by
  name: "`PAYMENT_INITIATE` is delegated staff authority over somebody
  else's payment... declaring it here refused every customer this endpoint
  exists for." This wave adds that capability (`Capability.PAYMENT_INITIATE`,
  granted to `TENANT_OWNER` and `TENANT_FINANCE`) and
  `OperationsPaymentController.rePresent`, which calls the same service with
  `customerAccountId = null`. `POST .../orders/{orderId}/payment/re-presentations`.
- **Refunds: fully real**, via `OperationsRemedyController` (ADR 0048),
  already built before this wave: `recordRefund`,
  `reimburseDeliveryFee`, per-order remedy history
  (`remediesOfOrder`), the reconciliation worklist (`unverifiedAttestations`)
  and totals report (`totalsByType`). This screen is the first console to
  read any of it.
- **Grant future discount exists on the backend** (`grantFutureDiscount`,
  ADR 0013's entitlement machinery) but is **not** owned by 8.1's own bullet
  list — the IA names only "refunds", and a future discount is a different
  shape (`EntitlementScope`/`EntitlementBenefit`, uses, a validity window)
  with no money to reconcile. Deferred, not because the backend is
  incomplete, but because this screen's IA text does not ask for it.
- **Provider-mappable cancellation reason for voids: not built as a
  distinct feature.** There is no "void" concept in
  `PaymentAttemptStatus` beyond a pre-capture `CANCELLED`/`EXPIRED`, and
  ADR 0048's whole design is that HorecaOS never calls a provider to reverse
  anything — a refund's `reasonCode` is free text an operator types, not a
  taxonomy mapped per provider. Honest not-built as IA describes it.
- **Auto-send payment link: a Settings concern, not this screen's.** 10.9
  Notifications owns "payment-link auto-send" as a tenant-configured
  toggle. What this screen owns is the manual counterpart — an operator
  sending or re-sending one on request — which is the re-issue action above.

## 2. 8.2 Fiscal receipts

Entirely built before this wave (`FiscalDocumentController`, ADR 0038): the
blocked worklist (`GET .../fiscal/documents/blocked`, longest-waiting
first, filterable by the five blocking reason codes
`FiscalReasonCode.BLOCKING`), per-order documents
(`GET .../fiscal/orders/{orderId}/documents`), the coverage report
(`GET .../fiscal/coverage`, counts and shares — never one collapsed figure,
because cash cannot be receipted by any provider and a single number would
misreport either a compliant cash-heavy branch or a genuine unreceipted
majority), and the two operator actions, `retry` and `unblock`. This wave
adds no fiscal endpoint — only the console that reads them.

**Deliberately absent from this screen, per the controller's own doc:** no
fiscal sign, receipt URL, or marking code. Those are ADR 0029-protected
evidence, read through "the payments module's authorized order-payment
view, with a recorded purpose" — a view `FiscalDocumentController`'s own
comment names as not existing yet. `OperationsPaymentController` (new this
wave, §1 above) is not that view either: it exists for payment status and
re-presentation, holds no fiscal evidence field, and was not built to
satisfy this gap. A worklist needs to know a document *has* evidence
(`hasEvidence`), not what the evidence says, and that is all 8.2 renders.

---

## 3. Conventions this screen follows

- **No client-side capability gate.** Consistent with every other screen in
  this app (`session-context.ts`'s own doc): the screen attempts the read,
  and a `403` renders the shared denied state. `TENANT_OWNER` and
  `TENANT_FINANCE` hold every capability both screens need
  (`PAYMENT_READ`, `PAYMENT_INITIATE`, `FISCAL_DOCUMENT_READ`,
  `FISCAL_DOCUMENT_RESOLVE`, `REFUND_REQUEST`/`REFUND_EXECUTE`).
- **Tenant-scoped, not location-scoped.** Every endpoint here is declared at
  `ScopeType.TENANT`, and `TENANT_FINANCE`/`TENANT_OWNER` are `TENANT`-scoped
  bundles with no `BRAND` or `LOCATION` grant of their own — `CurrentLocation`
  and `CurrentBrand` would both resolve to nothing for exactly the people
  this section is for. `CurrentTenant` (new this wave) reads
  `SessionContext.activeTenantId` directly instead.
- **Order lookup, not an order list.** No endpoint projects payment status
  onto `OperationsOrderController`'s list, and building one is Orders'
  surface to grow — this wave does not touch `features/orders` or
  `OperationsOrderController`. 8.1 instead looks up one order by id, the
  same way a finance operator would be handed an order number over the
  phone, and shows the two genuinely cross-order reads — the unverified
  worklist and the remedy totals — without needing an order id at all.
- **Money** renders through `core/format/money.ts`'s existing idiom —
  integer minor units, the platform's own grouping and decimal rules, never
  `Intl`'s currency formatter.
