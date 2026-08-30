# ADR 0013: Payment, refund, and service-recovery compensation

- Decision status: Accepted
- Implementation status: Partial — V0027, V0042, V0045 and V0048 carry
  intents, attempts, transactions, `provider_callbacks`, `merchant_bindings`,
  `order_settlements` and `tenders`; the `payments` module holds both provider
  adapters, both inbound endpoints (`ClickShopApiController` with raw-value
  signature verification and a deterministic `merchant_prepare_id`,
  `PaymeMerchantApiController` with auth-before-dispatch and the expiry rule),
  the attempt state machine, the `UncertaintyResolver` pair, the cash
  `NOT_APPLICABLE` fiscal document, and the outbound checkout surface at
  `POST /api/v1/storefront/.../orders/{orderId}/payment-sessions` — now
  authorised by order ownership (`@CustomerOwned` over `CurrentCustomer`)
  rather than by the `payment.initiate` capability no customer principal could
  hold. **Refunds and service recovery are no longer this record's to report
  on.** The `payments.refunds` model described below, its
  `PROVIDER_CONSOLE | PLATFORM` execution channel and its deferral of every
  non-refund remedy are superseded by
  [ADR 0048](../partial/0048-refunds-as-bookkeeping-and-the-order-remedy-model.md),
  which owns the order-remedy model that actually shipped. What exists there,
  what does not, and which migrations carry it are stated in that record's own
  implementation status and nowhere else, so the two cannot drift apart. V0053
  creates
  `tenant.legal_entities` and the foreign key from
  `payments.merchant_bindings.legal_entity_id`, so the seller dimension is real
  schema and `PaymentLegalEntityResolver` resolves against it. Not built:
  settlement import and daily reconciliation; the recovery case, versioned
  remedy policy and execution outbox (`recovery.case.manage` and
  `recovery.remedy.approve` are still bare capability constants, and the model
  that would have used them is superseded by ADR 0048); and the ArchUnit money
  rule. **Two things block an operator.**
  Nothing writes `payments.merchant_bindings`
  or `tenant.legal_entities` over HTTP — `JdbcPaymentBindingResolver` only
  reads the first and `LegalEntityService` has no controller, so both are
  hand-written SQL. And no callback moves an order out of `PAYMENT_AUTHORIZING`,
  because `ordering.api.OrderDirectory` is read-only and neither inbound
  endpoint transitions the order a captured attempt belongs to. What the state
  of `payments.order_settlements` does to a remedy is ADR 0048's to report, and
  it reports it. **Nothing has touched
  a real provider sandbox**, so the unsigned link's exact acceptance — Click's
  `merchant_id`, Payme's base64 padding — is asserted against the documentation
  and not against a provider.
- Date proposed: 2026-08-19
- Date decided: 2026-08-22
- Deciders: Ayubkhon Abbosov (platform architecture), finance (merchant topology and settlement), legal (fiscal agency and principal), product (compensation policy)
- Depends on: ADR 0004, ADR 0005, ADR 0006, ADR 0007, ADR 0018, ADR 0019, ADR 0026, ADR 0027, ADR 0028, ADR 0029, ADR 0030, ADR 0036, ADR 0038, ADR 0046
- Supersedes / Superseded by: Fiscal receipts section superseded by ADR 0038; payment-intent cardinality corrected by ADR 0046; payable subject widened by ADR 0047; refund and service-recovery compensation superseded by [ADR 0048](../partial/0048-refunds-as-bookkeeping-and-the-order-remedy-model.md) — the `payments.refunds` model, its `PROVIDER_CONSOLE | PLATFORM` execution channel, and the deferral of every non-refund remedy to a later `recovery` module are replaced by the owner's decision of 2026-08-25 that a refund is permanently a bookkeeping record
- Open inputs: The Click MERCHANT API `error_code` enumeration and the `status_by_mti` date segment (Click); Prepare/Complete replay guarantees (Click); `CommissionInfo` TIN ownership and `submit_items` idempotency (Click); the `CheckPerformTransaction` code for "order already paid", the `SetFiscalData` `status_code` list and arrival deadline, and whether a Telegram bot-cashbox payment reaches the Merchant API endpoint at all (Payme); compensation remedy set, approval thresholds, and delivery-subsidy bearer (product, finance); fiscal agency per settlement path and evidence retention per legal entity (legal, finance). **None is structural** — see [Open inputs](#open-inputs-and-who-answers-them)
- Closed inputs: What fiscal evidence Click and Payme return, and merchant topology and capture/refund capability per provider — both closed 2026-08-22 against the provider contracts in [`docs/providers/`](../../providers/); fiscalization is performed by the payment partners **for provider-settled online payments only** (business decision, 2026-08-20), narrowed and relocated by ADR 0038; a cash order receives no provider fiscal receipt (user decision, 2026-08-22)

> **Three later ADRs restructure parts of this one, and the pointers stay.**
> [ADR 0038](../partial/0038-legal-entities-fiscal-receipts-and-product-classification.md)
> owns fiscalization: it is an obligation of the *order*, evidence lives in
> `fiscal.fiscal_documents` rather than `payments.fiscal_receipts`, and a
> per-location legal entity sits behind it. This ADR keeps only the partner seam
> — what Click and Payme return, and when.
> [ADR 0046](../partial/0046-loyalty-points-and-split-tender.md) puts a settlement and
> an ordered set of tenders between the order and the payment intents below, so
> an intent sits beneath a tender rather than beside the order.
> [ADR 0047](../partial/0047-dine-in-table-service-and-qr-ordering.md) widens the payable
> subject from an order to an order **or** a dine-in session.
> Everything else here — the provider port, the payment state machine,
> uncertainty resolution, the refundable-balance invariant, and the
> service-recovery model — is owned here and is current.

## Context

The first draft of this ADR was written before either provider's contract had
been read. It proposed a card-acquirer capability set — authorize, capture, void,
refund — and left "merchant topology and capture/refund capabilities per
provider" as an open input. The contracts have now been read in full and
recorded in [`docs/providers/click-merchant-api.md`](../../providers/click-merchant-api.md),
[`payme-merchant-api.md`](../../providers/payme-merchant-api.md), and
[`fiscalization-via-payment-providers.md`](../../providers/fiscalization-via-payment-providers.md).
They answer the open input by contradicting the draft.

**Click and Payme are not two instances of one thing.** Click is
outbound-dominant: the merchant creates invoices, charges card tokens, queries
status and issues reversals against `api.click.uz`, and Click calls back into
the merchant's SHOP API with a form-encoded, MD5-signed Prepare and Complete —
and that callback, not the outbound call, is what credits an order. Payme is
inbound: Payme is the JSON-RPC client and the merchant's single endpoint *is* the
integration, with six mandatory methods, HTTP 200 on every response including
errors, and a localised error object. A port designed around either provider
alone gets the other's transaction identity, reversal direction, and
reconciliation direction backwards. Two providers is the constraint that stops
this being shaped like one provider.

**The seller is the restaurant, and the providers enforce it.** Neither provider
accepts a fiscal identity as a per-request field. Payme derives it from the
cashbox — its `receipts.create` response carries a `merchant.organization`
populated from the cashbox with no input from the request — and Click derives it
from `service_id` plus `merchant_user_id`/`secret_key`. One Qoida account
therefore cannot serve many restaurants, because every receipt it issued would
name Qoida as the seller. The legacy corroborates by construction: `fin_agents`
is `UNIQUE (payment_method_id, vendor_id)`, one provider agent per vendor.

**The money crosses a unit boundary twice, in opposite directions, inside one
provider.** ADR 0018 stores `amount_minor` as whole som. Click's SHOP API
`amount`, payment link, `invoice/create` and `card_token/payment` are in som;
Click's fiscalization `Price`, `VAT` and `received_*` are in tiyin, as is every
amount Payme has ever sent or received. The same logical amount is som in Click's
payment call and tiyin in Click's fiscal call for that same payment.

**Cash is the majority tender and no provider can fiscalize it.** The legacy
`payment_methods` table seeds `cash` enabled, `payme` enabled, `click` disabled.
Click's `submit_items` requires a CLICK `payment_id` that a cash order does not
have; Payme's fiscal data attaches to a Payme receipt that a cash order does not
have. So partner fiscalization covers the minority of orders, and the platform's
answer for the majority has to be something other than silence.

## Decision

- **The restaurant's legal entity is the seller and the principal; Qoida is an
  agent.** Every merchant account is the restaurant's own, and **each legal
  entity holds its own Click service and its own Payme cashbox**, registered to
  its own INN, with credentials as ADR 0028 secret references resolved at call
  time. This is forced by the contracts, not preferred.
- **A payment provider binding that is tenant-scoped and singular is wrong.** The
  binding resolves per legal entity, on the order's business date, the same way
  ADR 0038's `tenant.location_fiscal_assignments` resolves the entity itself. See
  [Merchant topology](#merchant-topology-and-the-legal-entity-dimension) for what
  this requires of ADR 0038 and ADR 0026.
- **Both providers ship together, and the port is the union of two opposite
  shapes.** The capability set is not authorize/capture/void — neither provider
  has that machine. It is present, reserve, capture, cancel, resolve, reverse,
  reconcile, and attach fiscal evidence, with each provider declaring which it
  implements through its ADR 0026 capability snapshot. Where the abstraction
  leaks, the leak is named here rather than discovered in an adapter.
- **Qoida's payment states are Qoida's.** Payme's signed numeric states and
  Click's `error`/`payment_status` vocabularies are recorded verbatim as provider
  evidence beside the Qoida state, and neither becomes the platform's.
- **An uncertain outcome is a first-class state with a named resolver and a
  deadline, never a retry.** Click resolves through `payment/status_by_mti` then
  `payment/status`; Payme resolves through `CheckTransaction`. An `UNCERTAIN`
  attempt blocks any second charge against the same order until it resolves.
- **Money crosses the som/tiyin boundary exactly once, in a typed place.** The
  domain speaks whole som; each adapter method takes an explicitly som- or
  tiyin-typed amount, and a bare numeric money parameter does not compile.
- **Refunds are deferred to the provider console for the cutover, with mandatory
  back-recording under a runbook and daily settlement reconciliation.** Neither
  provider offers a refund primitive in the shape the platform needs: Click's
  reversal is full-amount, online-card-only and bounded by the reporting month;
  Payme's refund is initiated in Payme's cabinet and arrives *inbound* as
  `CancelTransaction`, which Qoida can only veto.
- **A cash order records fiscal status `NOT_APPLICABLE` with a reason, never a
  null.** User decision, 2026-08-22.
- **Service recovery stays a separate `recovery` module.** For the cutover it
  decides and approves remedies and executes exactly one of them — a
  back-recorded refund. Benefit grants and subsidies activate behind ADR 0015 and
  ADR 0018 gates.
- **Telegram is designed for, not built.** The payment port carries a provider
  token and an opaque invoice payload without redesign, and ADR 0036 already has
  a `TELEGRAM` channel type with a `provider_installation_id`. No bot is built.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| One Qoida Click service and one Qoida Payme cashbox serving every restaurant | Not available. Neither provider takes the seller's identity as a per-request field — Payme derives it from the cashbox, Click from `service_id` + `merchant_user_id` — so every receipt would name Qoida as the seller, which is the opposite of the decision that the restaurant is the principal. Legacy `fin_agents` is `UNIQUE (payment_method_id, vendor_id)` for the same reason | Only if Click confirms `CommissionInfo.TIN` names the committent and may vary per line under one service, **and** legal confirms the Qoida–restaurant agreement is a commission agreement in the fiscal sense. Both answers together make it conceivable, not automatic |
| Model the port on Click and treat Payme as a variant, or the reverse | Click is outbound-dominant with an inbound callback that credits the order; Payme is an inbound JSON-RPC server whose endpoint *is* the integration. Either-alone gets the other's transaction identity, reversal direction, and reconciliation direction backwards — three inversions, not one | Never with these two providers |
| Keep the `AuthorizePayment` / `CapturePayment` / `VoidAuthorization` capability set this ADR first proposed | It describes a card-acquirer hold-then-capture machine that neither provider has. Click's Prepare and Payme's state `1` are *reservation obligations on the merchant*, not authorization holds against an issuer, and neither provider exposes a void | A provider with genuine separate authorization joins the set |
| Adopt Payme's signed numeric states as the platform's payment states | The sign carries "cancelled" and the magnitude carries how far the transaction got — `-1` before money moved, `-2` after. Click has no equivalent, no expiry state, and no provider-side reservation state at all, so half of Click's outcomes would be unrepresentable | Never |
| Retry a mutating provider call after a timeout | A lost response on `card_token/payment` may be a completed charge, and Click's MERCHANT API has no idempotency key anywhere. The retry is a second charge on a customer's card | Never |
| Build platform-initiated refunds for the cutover | Click's `payment/reversal` takes no amount — there is no partial reversal in the documented API — requires an online-card payment, is limited to the current reporting month (previous month only on the first day of the current one), and may still be refused by UZCARD. Payme has no outbound refund call at all: the cabinet's refund button calls the merchant's `CancelTransaction`. A primitive that exists on neither provider in the needed shape is not a foundation | Click publishes a partial-refund endpoint, or Payme exposes a Subscribe API refund the platform may drive |
| Issue refunds in the provider console with no back-recording | The platform's state after a console refund is indistinguishable from an unrefunded capture, so the refundable-balance invariant stops holding and a second console refund has nothing to fail against | Never as the primary path; retained only as the documented path *with* back-recording |
| Use Click's `received_cash` to fiscalize a cash order | `received_cash` is a tender split *inside a CLICK payment*. A cash order has no CLICK payment and therefore no `payment_id` to call `submit_items` with. Building on it yields a system that appears to fiscalize cash and does not — invisible until an inspection | Never; cash fiscalization is ADR 0038's `TERMINAL` responsibility |
| Record a cash order's fiscal status as null | Null means "unknown", and the entire point of the decision is that this is known and deliberate. A reversal of the decision must be able to find the affected orders by query | Never |
| Copy either provider's official reference implementation | Both are official and both are defective on the paths that cost money. Django's amount check reads `abs(float(amount) - float(order.total) > 0.01)` — misplaced parenthesis, so **underpayment passes**; Payme's Java template returns a bodyless 401, filters `GetStatement` to completed transactions only, and re-cancels an already-cancelled transaction, rewriting `-2` back to `-1` | Never. The provider notes record, per disagreement, which source to believe and why |
| Use Spring Security's stock `httpBasic()` on the Payme endpoint | It answers a bodyless HTTP 401. Every Payme response must be HTTP 200 — any other status is read as `-32400` — and the first sandbox test expects `-32504` in a JSON-RPC error body. The integration fails before any money moves | Never |
| Report a business failure through Click's Complete response | After a successful charge, Complete may return only `-4` (already paid) or `-9` (already cancelled). An error response leaves the customer charged and uncredited while CLICK retries and then escalates to CLICK support | Never; answer `error: 0`, then call `payment/reversal` |
| Convert som to tiyin wherever a provider field needs it | The same logical amount is som in Click's payment call and tiyin in Click's fiscal call for that same payment. A `× 100` in more than one place is a hundredfold charge waiting for a refactor to introduce it | Never |
| Share one receipt-line builder between the Click and Payme adapters | Click's `Price` is the **line total**; Payme's `price` is the **unit price**. One shared helper fiscalizes an order at quantity² times its value | Never; each adapter builds lines from the ADR 0018 quote snapshot independently |
| Credit an order from the checkout return | Both checkout surfaces are unsigned: Click's `my.click.uz/services/pay/` link takes an arbitrary `amount` and `transaction_param` from anyone, and Payme's base64 checkout link is equally unauthenticated. `return_url` and `callback` are browser events that prove nothing, and Payme's `:transaction` placeholder can be the literal `"null"` on a perfectly good payment | Never |
| Build the Telegram channel now | Telegram is a third shape, not a variant: money flows through Telegram's Bot API with no provider callback, Payme requires a **separate bot cashbox** that cannot be an existing one, and Payme does not document whether a bot payment calls the Merchant API endpoint at all — its own sequence diagram has no merchant-billing lane. Designing for it costs a provider-token field and an opaque payload; building it costs a reconciliation path nobody can specify yet | Payme answers whether a bot-cashbox payment reaches the Merchant API endpoint and `GetStatement`, and product puts a bot in scope |
| Implement Click's Advanced/Split Shop (direction D) instead of SHOP API | A different CLICK→merchant protocol: JSON body, a `params` object, five actions whose numbering collides with SHOP API's, and a signature over "all `params` values in transmitted order" that depends on JSON key order surviving the parser. It is the utility-billing integration; using it for an e-commerce order means implementing the wrong half of Click | Qoida needs Click-side split settlement across `cntrg_id` receivers and Click confirms Split Shop is the supported mechanism |
| Use a third-party payment orchestrator | The provider set is Click, Payme, and local acquirers under Uzbek fiscalization and local settlement. General-purpose orchestrators cover none of it, and adding one puts a foreign processor in the payment path under ADR 0034 residency rules | A local orchestrator with fiscal support appears and covers every required capability |
| Adjust order totals when a refund or compensation is issued | Destroys the immutable commercial record, makes settlement reconciliation impossible, and hides why money moved | Never |
| Put service recovery inside the `payments` module | Mixes customer-care policy, approval thresholds, and remedy decisions with financial execution. Different actors, different approval rules, different retention | Never; the split is the point |
| Model compensation as a discount on the original order | A discount changes what the customer was charged and therefore the taxable base, so the fiscal receipt no longer matches the sale that happened — and the receipt was correct when it was issued, so this shape requires correcting a document that was never wrong. ADR 0046 takes the opposite decision for a points redemption, and the reasons differ by timing rather than in principle: a redemption is known before the receipt exists, and compensation is decided after it | Never |
| Store card data to enable retries | Brings PCI scope with no product benefit; both providers already own the instrument, and Click's own `card_token` flow keeps the PAN off Qoida entirely | Never |
| Reissue fiscal receipts during legacy import | Would create duplicate fiscal documents with a tax authority. Import preserves evidence and never calls a provider | Never |

## Consequences

### Positive

- Two providers with opposite shapes sit behind one port, and every place the
  abstraction leaks is written down before an adapter discovers it.
- An uncertain outcome has a named resolver per provider and structurally cannot
  become a blind retry, so the double-charge path is closed the same way ADR 0014
  closed the double-booking path.
- A cash order's missing receipt is a queryable fact with a reason code, so
  reversing the decision is a migration rather than an archaeology exercise.
- Money crosses the som/tiyin boundary in exactly one typed place, so the
  factor-of-100 error is a compile failure rather than a support ticket.
- Merchant topology is settled by the contracts rather than by preference, so the
  legal-entity dimension is designed in rather than retrofitted onto live payment
  configuration.
- Orders stay immutable while refunds, remedies, and subsidies remain
  independently auditable.

### Negative

- **Onboarding a restaurant becomes a provider-onboarding project.** Each legal
  entity needs a payment-acceptance contract with one of Click's connected banks,
  its own `service_id` and `secret_key`, and its own Payme cashbox with its own
  36-character key. None of it is self-service, and a tenant holding six legal
  entities keeps twelve provider accounts alive.
- **One inbound endpoint per binding.** Payme's Basic-auth key is per cashbox and
  Click's `secret_key` is per service, so the credential identifies the account
  and a single shared callback URL cannot authenticate a request. The deployment
  exposes an endpoint per binding — more routing, more certificate surface, and a
  URL that must be registered in each provider's cabinet by hand.
- **Refunds are manual at cutover.** A customer waits for an operator to open a
  provider console, and on Click nothing tells the platform a refund happened
  until the next settlement reconciliation.
- **The Click adapter's retry classification is Qoida's inference.** Click
  publishes HTTP statuses for MERCHANT API and no `error_code` table at all;
  every documented example shows `error_code: 0`. Until Click publishes it, a
  retryable failure may be treated as terminal and a terminal one retried.
- **Several Payme behaviours are settled only by a reference implementation with
  known defects.** The sharpest is which code `CheckPerformTransaction` must
  return for "order already paid": the docs permit only `-31001` and
  `-31050…-31099` there, Payme's own template returns `-31008`, and the sandbox
  is the only arbiter available before go-live.
- **Partner fiscal coverage is the minority of orders**, because cash is the
  majority tender and no provider can fiscalize it. ADR 0038's terminal
  precondition is therefore load-bearing rather than conservative.
- Two modules now sit between "customer complains" and "money moves", which is
  more machinery than a support tool would need for a simple refund, and approval
  thresholds slow genuine recovery exactly when a service failure is in progress.

### Accepted trade-offs

- Uncertain outcomes reconcile before retrying, so a customer may wait longer for
  a resolution than a blind retry would take. A double charge is the worse
  outcome, and on a customer's card it is the one that ends the relationship.
- The port refuses to offer refund as a uniform outbound capability, so the
  domain must ask the binding what is possible instead of calling and handling
  failure. An honest asymmetry is worth more than a uniform lie.
- Qoida enforces Payme's twelve-hour expiry itself, including a background sweep
  the documentation never requires, because lazy expiry never fires for an
  abandoned checkout. Qoida will therefore cancel some transactions Payme might
  have left alone.
- Click's `status_by_mti` carries an undocumented trailing `YYYY-MM-DD`, so the
  platform snapshots a business date at initiation that it may later learn was the
  wrong field. Snapshotting a possibly-wrong date is recoverable; not snapshotting
  one is not.
- Compensation policy is versioned and snapshotted on the decision, so changing a
  policy never changes what an already-approved case was allowed to do.

## Specification

### The two shapes, and where the abstraction leaks

Click runs three protocols and Payme runs two. Only some of them move money.

| Provider | Direction | Surface | Does it credit an order? |
|---|---|---|---|
| Click | Click → Qoida | SHOP API `Prepare` (`action=0`) and `Complete` (`action=1`), form-encoded, MD5 `sign_string`, no auth header | **Yes.** This is the only thing that does |
| Click | Qoida → Click | MERCHANT API on `api.click.uz/v2/merchant`, JSON, `Auth: user:sha1(ts+secret):ts` — `invoice/create`, `card_token/*`, `click_pass/*`, `payment/status*`, `payment/reversal`, `payment/ofd_data/*` | No; it initiates, queries, reverses, fiscalizes |
| Click | Browser | `my.click.uz/services/pay/` link or `checkout.js` card form, unsigned | No. UX only |
| Payme | Payme → Qoida | JSON-RPC 2.0 over one endpoint URL — `CheckPerformTransaction`, `CreateTransaction`, `PerformTransaction`, `CancelTransaction`, `CheckTransaction`, `GetStatement`, `SetFiscalData` | **Yes.** `PerformTransaction` is the money |
| Payme | Browser | base64 GET link or POST form to `checkout.paycom.uz`, unsigned | No. UX only |

**What the port abstracts.** One thing, and it is worth more than it looks: on
both providers the authoritative "money moved" signal arrives **inbound**, and on
both providers the outbound checkout surface is unauthenticated and proves
nothing. So the common shape is a two-phase inbound conversation — *may this be
paid?* then *it was paid* — behind an outbound presentation step that produces a
link, a QR payload, or a pushed invoice and returns no payment. Click's
Prepare/Complete and Payme's `CreateTransaction`/`PerformTransaction` are the
same two phases with different transports, and Telegram's
`pre_checkout_query`/`successful_payment` is a third instance of it, which is why
designing for Telegram costs a field rather than a redesign.

**Where it leaks. Seven places, named.**

1. **Transaction identity runs in opposite directions.** Payme mints the
   transaction id (a 24-character string) and Qoida stores it. On Click the join
   key is `merchant_trans_id` — *Qoida's* id — and `merchant_prepare_id` is a
   value Qoida mints and Click hands back. The port cannot assume "the provider
   gives us an identifier", and it cannot assume the reverse either.
2. **Phase-one obligations differ.** Click's Prepare requires verifying the order
   and the amount and, for e-commerce, reserving stock. Payme's
   `CreateTransaction` requires all of that plus **freezing the order so the buyer
   cannot modify it**, plus setting it to "awaiting payment". Payme also imposes a
   hard twelve-hour expiry with a specific cancellation reason. Click imposes no
   expiry at all. Reservation is common; the obligations attached to it are not.
3. **Reversal direction is inverted.** On Click, Qoida calls
   `DELETE payment/reversal/:service_id/:payment_id` and may be refused. On Payme,
   Payme calls `CancelTransaction` and Qoida's only lever is `-31007`. A single
   outbound `RefundPayment` capability would be a lie on Payme.
4. **Reconciliation direction is inverted.** Payme *pulls* a statement from Qoida
   through `GetStatement`, whose implementation the docs call mandatory, over all
   states inclusive. Click offers no inbound equivalent in SHOP API; reconciling
   Click means Qoida reading `payment/status` per payment plus the cabinet's
   settlement export.
5. **Fiscal timing is inverted.** Click fiscalizes strictly *after* capture,
   because `submit_items` needs a CLICK `payment_id` that does not exist earlier.
   Payme fiscalizes from a `detail` object fixed *before* the customer pays and
   reports the outcome back afterwards through `SetFiscalData`. A captured
   payment with no receipt is reachable on both paths, by different mechanisms.
6. **Error vocabularies share nothing.** Click direction A answers HTTP 200 with
   `error`/`error_note`, small negative integers, and note strings that must be
   returned verbatim because they surface in CLICK's support tooling. Payme
   answers HTTP 200 with a JSON-RPC `error` whose `message` is a localised object
   `{ru, uz, en}` and whose `data`, for `-31050…-31099`, must name the offending
   `account` sub-field. There is no shared error type; the adapter owns the whole
   mapping and the domain never sees a provider code.
7. **Telegram bypasses both integrations.** For Click, Telegram payments run
   entirely through Telegram's Bot Payments API with a BotFather-issued provider
   token and no CLICK webhook. For Payme, the same, plus a mandatory **separate
   bot cashbox** — and it is undocumented whether such a payment reaches the
   Merchant API endpoint at all. Telegram is a third shape wearing the two-phase
   silhouette, and it must not be modelled as a variant of either provider.

### Ports

```java
interface PaymentPresentationPort {                 // outbound; optional per provider
    Presentation present(BindingRef binding, PaymentAttempt attempt);
}

interface PaymentInboundPort {                      // the provider calls Qoida
    ReservationDecision reserve(ProviderReservationRequest request);
    CaptureAck          capture(ProviderCaptureRequest request);
    CancellationAck     cancel(ProviderCancellationRequest request);
    AttemptView         describe(ProviderAttemptQuery query);
    StatementPage       statement(Instant from, Instant to);
}

interface PaymentOutcomeResolver {                  // outbound; resolves UNCERTAIN
    ResolvedOutcome resolve(PaymentAttempt attempt);
}

interface PaymentReversalPort {                     // outbound
    ReversalOutcome reverse(PaymentAttempt attempt);
}

interface PartnerFiscalPort {                       // ADR 0038 owns the document
    void                    submitLines(PaymentAttempt attempt, ReceiptLines lines);
    Optional<FiscalEvidence> readBack(PaymentAttempt attempt);
    void                    attachExternalReceipt(PaymentAttempt attempt, ReceiptUrl url);
}
```

Capability declaration per provider, recorded on the ADR 0026 installation:

| Capability | Click | Payme |
|---|---|---|
| `present` | Payment link, `checkout.js` card form, `invoice/create` push, CLICK Pass QR | base64 GET link, POST form, `Paycom.QR` |
| `reserve` | Inbound Prepare | Inbound `CheckPerformTransaction` + `CreateTransaction` |
| `capture` | Inbound Complete | Inbound `PerformTransaction` |
| `cancel` | Inbound Complete with negative `error` → answer `-9` | Inbound `CancelTransaction` |
| `describe` | Outbound `status_by_mti` + `payment/status` | Inbound `CheckTransaction` |
| `statement` | **Unsupported** — no SHOP API equivalent | Inbound `GetStatement`, mandatory |
| `reverse` | Outbound `payment/reversal`, full amount only, calendar-bounded | **Unsupported outbound** — refund arrives inbound |
| `submitLines` | `payment/ofd_data/submit_items`, after capture, tiyin | **Unsupported** — lines ride the checkout `detail` |
| `readBack` | `GET payment/ofd_data/:service_id/:payment_id` | **Unsupported** — evidence arrives via `SetFiscalData` |
| `attachExternalReceipt` | `payment/ofd_data/submit_qrcode` | Subscribe API `receipts.set_fiscal_data` |

An unsupported capability is a declared fact the control plane renders, not a
runtime `UnsupportedOperationException`. Where `reverse` is unsupported, the
operations console must say that rejecting a paid order requires a console refund
under the runbook, before the operator rejects it.

### The payment state machine

Qoida's states. The provider's own state is stored beside them as evidence and is
never the source of a transition.

```text
INITIATED ─► PRESENTED ─► RESERVED ─► CAPTURED ─► REVERSED
                │             │            │
                │             ├─► CANCELLED (released, no money moved)
                │             └─► EXPIRED   (reservation aged out)
                └─► FAILED    (declined before any reservation)

any state ─► UNCERTAIN ─► (resolves to exactly one of the above)
```

`UNCERTAIN` is not terminal and is not a failure. It is a state carrying an
obligation: a resolver, a first-observed timestamp, and a deadline after which it
becomes an operations exception. `CAPTURED` is settled but not terminal, because
a refund still reaches it.

**Click's vocabulary onto Qoida's:**

| Qoida state | How Click expresses it |
|---|---|
| `RESERVED` | Prepare answered `error: 0` with a `merchant_prepare_id` that is a deterministic function of the order |
| `CAPTURED` | Complete answered `error: 0` with a `merchant_confirm_id`; **also** a replayed Complete answered `-4 Already paid`, which means success |
| `CANCELLED` | Complete whose incoming `error` was negative, answered `-9`; or a replayed Complete on an already-cancelled attempt, also `-9` |
| `REVERSED` | `DELETE payment/reversal/...` returned `error_code: 0` |
| `EXPIRED` | **Click has no such state.** Produced by Qoida's own reservation TTL, and the provider is never told |
| `FAILED` | A terminal direction-B `error_code`, or a 4xx on the initiating call |
| `UNCERTAIN` | Timeout, 500, 502, or transport failure on any mutating direction-B call |

Two Click readings that must not be confused, because confusing them credits an
unpaid order: `error_code: 0` means *the API call* succeeded, and only
`payment_status: 2` means *the money moved*. `payment_status` `0` is created and
`1` is in processing — both in-flight, neither money — and several documented
examples pair `payment_status: 1` with `error_note: "Success"`.

**Payme's vocabulary onto Qoida's:**

| Qoida state | Payme state | Notes |
|---|---|---|
| `RESERVED` | `1` | Created, awaiting confirmation |
| `CAPTURED` | `2` | Successful, and reversible — not terminal |
| `CANCELLED` | `-1` with reason `1`, `2`, `3`, or `10` | Cancelled before money moved |
| `EXPIRED` | `-1` with reason `4` | Qoida produces this itself; see the timeout rule below |
| `REVERSED` | `-2`, in practice reason `5` | Cancelled *after* completion; the money has left the settlement |
| `FAILED` | none | Payme rejects at checkout (`-31601`, `-31610`…`-31630`) before Qoida is called |
| `UNCERTAIN` | none on the wire | Payme retries; see the uncertainty section |

Three rules the sign imposes:

- **Test `state < 0` for cancelled, never `state == -1`.** The magnitude records
  how far the transaction got, not which kind of cancellation it was.
- **`-1` and `-2` are terminal; `2` is not.** Treat `2` as "settled, reversible".
- Store `reason` verbatim, return it from `CheckTransaction` and `GetStatement`,
  and leave it `null` for a transaction that was never cancelled.

### Uncertainty, resolved per provider

The platform already holds this discipline for delivery partners: a lost response
is not a failure, and retrying a create produces a double booking. For payments
the equivalent is a double charge, and the resolution mechanism is different on
each provider.

**Click.** Uncertainty exists only outbound. Direction A is a callback — Qoida
answers, and a lost answer is CLICK's problem to retry. Direction B has no
idempotency key on any call, and `card_token/payment` is the worst case: it moves
money and offers nothing to key on.

```text
timeout | 500 | 502 | transport failure on a mutating direction-B call
  → attempt := UNCERTAIN, resolver := CLICK_STATUS_BY_MTI, uncertain_since := now
  → GET /v2/merchant/payment/status_by_mti/{service_id}/{merchant_trans_id}/{business_date}
      found    → GET /v2/merchant/payment/status/{service_id}/{payment_id}
                   payment_status = 2 → CAPTURED
                   payment_status = 0 | 1 → still UNCERTAIN, re-poll with backoff
                   payment_status < 0 → FAILED
      not found → widen business_date by one day either side and repeat once
                   still not found → stay UNCERTAIN and raise an operations exception
```

Two preconditions make this work, and both must be built before the first real
transaction. `merchant_trans_id` is minted and persisted **before** the mutating
call, so an uncertain outcome always has a key to ask about. And the **business
date is snapshotted at initiation**, because `status_by_mti` carries a trailing
`YYYY-MM-DD` whose meaning and timezone Click does not document. A wrong date
reads as "no payment found", which is precisely the answer that would make a
retry look safe. That is why "not found" never unblocks a retry here: on this
provider, absence of evidence is not evidence of absence.

Never retry a mutating direction-B call on a 4xx — those are configuration or
programming errors and will fail identically. `500`/`502` on a *read* call is
ordinarily retryable with backoff.

**Payme.** Uncertainty is structurally rarer, because the roles are reversed:
Payme is the client, it repeats `CreateTransaction`, `PerformTransaction` and
`CancelTransaction` with identical parameters when a response is lost, and the
sandbox requires the second response to match the first. Qoida's obligation is
therefore idempotency, not polling.

Where uncertainty does exist is inside Qoida: a crash between persisting the
transaction state and committing the order change. Payme's retry then hits the
`state == 2` branch and cheerfully reports success against an unpaid order.

```text
Perform the state change and the order-status change in ONE database transaction.
Reconciliation control: GetStatement is mandatory anyway; compare it daily against
payments.payment_transactions, and treat any transaction Payme knows and Qoida
does not — or the reverse — as a finance exception.
Resolver for a manual query: CheckTransaction(id). It never mutates; do not expire
a transaction from inside it.
```

A `PerformTransaction` that never arrives is **not** uncertain — it is unpaid, and
the twelve-hour rule resolves it deterministically.

**Both providers.** An `UNCERTAIN` attempt blocks any further charge against the
same intent, enforced by a compare-and-set on the attempt row rather than by
application convention, the same single-winner mechanism ADR 0014 uses for
delivery bookings.

### Money, units, and the two words that mean different things

ADR 0018 stores `amount_minor` as **whole som** for UZS. The wire is not
consistent with that or with itself:

| Surface | Unit |
|---|---|
| Click SHOP API `amount` on Prepare/Complete | **som**, as decimal text |
| Click payment link `amount` | **som**, formatted `N.NN` |
| Click `invoice/create`, `card_token/payment`, `click_pass/payment` | **som** |
| Click fiscalization `Price`, `VAT`, `received_ecash/cash/card` | **tiyin** |
| Every Payme amount, everywhere | **tiyin** |

Rules:

- **One conversion, one place, obvious from the type.** The domain passes
  `SomAmount`. Every adapter method takes `SomAmount` or `TiyinAmount`, never a
  bare `long` or `BigDecimal`, so a som value cannot be passed where tiyin is
  expected. `TiyinAmount.of(SomAmount)` is the only multiplication by 100 in the
  codebase, enforced by an ArchUnit rule rather than by review.
- **Compare Click's `amount` after parsing, never as a float, and never before
  hashing** — see the signature rule below. Both Click references compare with a
  ±0.01 tolerance; parse to whole som and compare integers instead. Reject an
  underpayment: Django's reference does not, through a misplaced parenthesis, and
  that is a named regression test here.
- **Click's `Price` is the line total; Payme's `price` is the unit price.** Same
  word, a factor of quantity apart. Neither adapter shares a line builder with the
  other.
- **VAT rate is a whole percent on both.** ADR 0018's `rate_basis_points` must be
  a multiple of 100 or the adapter rejects the tax profile rather than rounding
  it; a rate that is not a whole number of percent is unrepresentable in either
  provider's integer field.
- Click's `VAT` amount is computed by Qoida per line, and comes from the accepted
  quote's recorded tax share — never recomputed at fiscalization time.

### Idempotency and replay, per provider

**Click.**

- There is no idempotency key anywhere in MERCHANT API. The only recovery
  mechanism is `status_by_mti`.
- `merchant_prepare_id` is a **deterministic function of the order**, not a fresh
  id per Prepare call. Complete carries exactly one of them, so a per-call id
  makes Complete unresolvable.
- Credit the order in Complete inside a database transaction **guarded by the
  order's own state**, not by "have I seen this `click_trans_id`". A replay then
  falls out naturally as `-4`.
- **Verify `sign_string` on the raw received strings.** It is a lowercase-hex MD5
  of a bare concatenation, and Prepare and Complete sign *different field lists* —
  Complete inserts `merchant_prepare_id` after `merchant_trans_id`, and Prepare
  omits it entirely rather than padding it with an empty string. `click_paydoc_id`
  is in neither signature, and neither are `error` and `error_note`. Read the raw
  form value, digest it verbatim, and only then parse it as a decimal:
  reformatting `1000.00` to `1000` before hashing is the commonest cause of a
  spurious `-1 SIGN CHECK FAILED!`. Compare in constant time.
- Evaluate failure conditions in this order: `-8` missing fields, `-1` signature,
  `-3` action, `-5` order lookup, `-6` prepare-id (Complete only), **`-4` already
  paid**, `-2` amount, `-9` cancelled. `-4` deliberately precedes `-2`, so a
  replayed Complete for an already-credited order reports "settled" rather than
  tripping an amount check against a total that has since been adjusted.
- **Always answer HTTP 200** with the error in the JSON body, and return
  `error_note` strings verbatim.
- **Never report a business failure through Complete.** After a successful charge
  the only permitted error responses are `-4` and `-9`. If the order cannot be
  fulfilled, answer `error: 0` and emit a reversal command to the outbox.
- Persist per callback: `click_trans_id`, `click_paydoc_id`, `merchant_trans_id`,
  `merchant_prepare_id`, `sign_time`, and the raw body behind an ADR 0029
  protected reference.

**Payme.**

- Idempotency is a hard requirement, not an optimisation: `CreateTransaction`,
  `PerformTransaction` and `CancelTransaction` are each sent at least twice and
  the repeat's response must match the first.
- Key on `params.id` with a unique index, **plus a partial unique index enforcing
  at most one transaction in state `1` or `2` per order**. A read-then-write is
  the classic double-charge path, and concurrent `CreateTransaction` calls for one
  order are exactly what it fails under.
- The replay answer is derived from the persisted attempt state, not from a stored
  response body: `PerformTransaction` on state `2` returns the stored
  `perform_time`; `CancelTransaction` on `-1`/`-2` returns the stored `cancel_time`
  and `state` and **never** overwrites either, nor rewrites `-2` back to `-1`.
- **Authenticate before dispatch, for every method including `GetStatement`**, with
  a constant-time comparison, answering HTTP 200 and `-32504` with the request `id`
  echoed. Login defaults to the literal `Paycom` and is configurable; the password
  is the 36-character cashbox key.
- **Every response is HTTP 200.** Any other status is read as `-32400`, and a run
  of them surfaces to the customer as `-31622`/`-31623` on the checkout page.
- Emit `{ru, uz, en}` for every error message. For `-31050…-31099`, `data` is the
  name of the offending `account` sub-field.
- **Expiry is twelve hours — 43 200 000 ms — measured from `params.time`**,
  Payme's creation time, not Qoida's `create_time`. Enforce it in
  `CreateTransaction` and `PerformTransaction`, cancelling to `-1` with reason `4`
  and answering `-31008`, and **never perform an expired transaction**. Add a
  background sweep, because lazy expiry never fires for a checkout the customer
  abandoned, and the reservation would hold stock forever.
- `CheckPerformTransaction` runs the same checks as `CreateTransaction`. If it
  says `allow` and `CreateTransaction` then errors, the customer has already
  entered card details.
- `GetStatement` returns **all** states — `1`, `-1`, `-2` included — for
  `from <= time <= to` inclusive, ascending on Payme's `time`, with `account`
  reproduced in the shape it arrived. It is a ledger, not a list of successes.
- The `account` schema is **one field, `order_id`, frozen once**, carrying an
  opaque non-sequential order reference. Sequential integers let anyone enumerate
  other customers' orders through `CheckPerformTransaction`, which is
  unauthenticated from the customer's side.
- Never trust `params.amount`. Recompute from the ADR 0018 quote: the checkout
  link is unsigned.

### Merchant topology and the legal-entity dimension

Resolution at order time:

```text
order → location → tenant.location_fiscal_assignments (effective on the order's
        business date) → legal entity → the payment binding for (legal entity,
        provider) → Click service_id / Payme cashbox and its secret reference
```

If no binding exists for the resolved legal entity, the payment method is not
offered on any channel serving that location. That is a serviceability
precondition resolved before checkout — the same shape ADR 0038 uses for cash
without a fiscal terminal — not a runtime failure after the customer has chosen.

**What this requires of two other ADRs, stated here and owned there.** ADR 0038's
`payments.payment_methods` carries one nullable `provider_installation_id` on a
row unique by `(tenant_id, code)`; a tenant holding three legal entities cannot
express three Click services with it. ADR 0026's binding scopes to brand or
location and carries no legal-entity dimension either. One of the two must gain
it — either the registry's uniqueness becomes
`(tenant_id, legal_entity_id, code)`, or the installation reference moves into a
per-entity binding table. This ADR does not choose between them; it records that
the tenant-scoped singular binding is wrong and that the choice must be made
**before the `PARTNER` slice is built**, because retrofitting it means re-pointing
live payment configuration.

A consequence that falls straight out and shapes the deployment: because Payme's
Basic-auth key is per cashbox and Click's `secret_key` is per service, **the
credential identifies the account**. A single shared callback URL cannot
authenticate, so the inbound endpoints carry the binding in the path and verify
against that binding's secret.

### Opening an attempt and presenting a surface

```text
POST /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/orders/{orderId}/payment-sessions
```

Capability `payment.initiate` at `BRAND` scope; ADR 0031 throughout, including
the `Idempotency-Key`. Body names the surface (`PAYMENT_LINK`, `QR`,
`INVOICE_PUSH`), an optional Click `return_url`, a language, and — for the push
only — the number to push to. Answers the surface and the attempt's identity: its
id and its `merchant_trans_id`, which is what the callback will carry and
therefore what a support conversation about a payment that never arrived starts
from.

Three properties, in the order they matter:

1. **The attempt is committed before any provider call, in its own transaction.**
   `merchant_trans_id` and the snapshotted business date are the resolver's only
   two arguments, and a mutating call made before they are durable produces a
   charge nobody can ask about. The presentation then runs in a second
   transaction, so an outbound call is never inside one that can roll back — a
   Click invoice pushed inside a rolled-back transaction is a payment request on a
   customer's phone that no row remembers, with no idempotency key anywhere in
   Click's MERCHANT API to find it again.
2. **Re-presentation, not re-opening.** A customer who abandons a checkout and
   returns is handed the same attempt and the same link. Enforced by
   `ux_payment_attempt_open_per_intent` rather than by a read-then-write, because
   two payable links against one intent is the outbound shape of the double
   charge the inbound index already prevents. `CAPTURED` and `UNCERTAIN` are
   refused rather than re-presented; a terminal attempt lets the customer try
   again.
3. **A push is not a link.** Click's `invoice/create` is the one presentation that
   mutates. A lost answer to it is `UNCERTAIN` with the `CLICK_STATUS_BY_MTI`
   resolver and is never sent again, and a customer pressing "pay" a second time
   is refused rather than pushed a second invoice.

The amount on both surfaces is a suggestion and never a commitment: neither link
is signed, so the amount the platform enforces is the one it recomputes when the
provider calls back. Click's link carries `merchant_id`, which V0045 adds to the
binding — a binding without it refuses to build a link rather than emitting one
missing a parameter Click documents as mandatory.

### Provider-facing endpoints

```text
POST /providers/click/{bindingRef}/prepare    form-encoded, MD5-signed, always HTTP 200
POST /providers/click/{bindingRef}/complete   form-encoded, MD5-signed, always HTTP 200
POST /providers/payme/{bindingRef}            JSON-RPC, Basic auth, always HTTP 200
```

These are deliberately outside ADR 0031's HTTP conventions: the wire format is
the provider's, down to the content type, the error envelope, and the requirement
that a failure still be HTTP 200. The exemption is recorded here so it is a
decision rather than a violation someone finds later. Payme's fifteen source
addresses (`185.234.113.1`–`.15`) are allowlisted as defence in depth and never
as the only check; Click publishes no callback addresses, which is an open input.

### Operations APIs

```text
POST /api/v1/operations/orders/{orderId}/recovery-cases
GET  /api/v1/operations/recovery-cases/{caseId}
POST /api/v1/operations/recovery-cases/{caseId}/remedies
POST /api/v1/operations/recovery-cases/{caseId}/approve
POST /api/v1/operations/recovery-cases/{caseId}/decline
POST /api/v1/operations/recovery-cases/{caseId}/execute
POST /api/v1/operations/orders/{orderId}/refunds:back-record
GET  /api/v1/operations/payment-attempts?status=UNCERTAIN
POST /api/v1/operations/payment-attempts/{attemptId}/resolve-outcome
POST /api/v1/operations/refunds/{refundId}/reconcile
```

All mutations require idempotency keys. Responses distinguish requested,
provider-pending, completed, failed, and **uncertain** outcomes — uncertain is a
response the caller must handle, not an error.

### Physical model

```text
payments.payment_intents            -- beneath an ADR 0046 tender, not beside the order
  id, tenant_id, tender_id, order_id
  legal_entity_id                   -- the seller, resolved at initiation (ADR 0038)
  requested_amount_minor, currency  -- whole som (ADR 0018)
  status, capture_mode, version, timestamps

payments.payment_attempts
  id, tenant_id, intent_id, provider_type, provider_binding_id
  merchant_trans_id                 -- Qoida's id: Click's join key, Payme's account.order_id
  business_date                     -- snapshotted; the status_by_mti path segment
  external_payment_id null          -- Click payment_id / Payme transaction id
  external_document_id null         -- Click click_paydoc_id
  external_invoice_id null            -- Click invoice_id; not a payment_id
  requested_amount_minor, currency, status
  presentation_kind null, presented_at null, presentation_count
  provider_state null, provider_reason null, provider_state_recorded_at null
  failure_code null
  uncertain_since null, uncertain_resolver null, uncertain_deadline null
  version, timestamps
  unique(tenant_id, provider_type, merchant_trans_id)
  unique(tenant_id, provider_type, external_payment_id)
    where external_payment_id is not null
  unique(intent_id) where status not in ('CANCELLED','EXPIRED','REVERSED','FAILED')

payments.payment_transactions       -- append-only
  id, tenant_id, intent_id, attempt_id
  transaction_type (RESERVE|CAPTURE|CANCEL|REVERSE|REFUND)
  amount_minor, currency
  external_transaction_id, occurred_at, recorded_at
  protected_request_reference, protected_response_reference   -- ADR 0029
  reconciliation_status

payments.provider_callbacks         -- ADR 0005 inbox, provider-shaped
  id, tenant_id, provider_type, provider_binding_id
  callback_kind (CLICK_PREPARE|CLICK_COMPLETE|PAYME_RPC|PAYME_SET_FISCAL_DATA)
  provider_reference                -- click_trans_id / params.id
  request_body_hash, signature_valid, received_at
  response_status, protected_request_reference, protected_response_reference
  unique(tenant_id, provider_type, callback_kind, provider_reference, request_body_hash)

payments.refunds
  id, tenant_id, order_id, payment_intent_id, payment_transaction_id
  recovery_case_id null
  requested_amount_minor, currency, reason_code
  execution_channel (PROVIDER_CONSOLE|PLATFORM)   -- PLATFORM unimplemented at cutover
  status, provider_reference null
  executed_at null, executed_by null              -- the console operator, as recorded
  recorded_by, recorded_at, evidence_reference    -- ADR 0029 protected reference
  idempotency_key, version, timestamps

payments.settlement_imports
  id, tenant_id, provider_binding_id, period, status
  protected_source_reference, checksum, timestamps

payments.settlement_lines
  import_id, external_transaction_id, amount_minor, currency
  settlement_status, matched_transaction_id null, difference_code null
```

Invariant, unchanged and now also enforced against back-recorded refunds:

```text
completed refunds + in-flight reserved refunds <= captured refundable amount
```

Refundable balance is reserved atomically **before** a provider call and, on the
console path, **at the point of recording** — so a second console refund for the
same capture fails validation while an operator is looking at it, rather than
during reconciliation a day later.

### Refunds at cutover

No platform-initiated refund ships. A refund is executed in the provider's own
console and back-recorded, and the runbook is the ADR 0027 evidence path.

What the platform records, so a manual refund reconciles:

- the capture being refunded, by `payment_intent_id` and `payment_transaction_id`;
- the provider's own identifiers — Click `payment_id`, `click_paydoc_id` and
  `merchant_trans_id`; Payme's transaction `id` and the `account` object;
- `amount_minor` in whole som, `currency`, and a `reason_code`;
- `execution_channel = PROVIDER_CONSOLE`, `executed_at`, and `executed_by` as the
  console operator, distinct from `recorded_by` and `recorded_at` in Qoida;
- `provider_reference` — the reversal or cancellation identifier the console shows;
- `evidence_reference` — a protected reference to the console export or capture;
- the settlement line it matched, once the provider's settlement file arrives.

**The two providers behave differently here, and operations will feel it.** On
Payme a cabinet refund *calls Qoida*: `CancelTransaction` arrives, the attempt
moves to `REVERSED`, and the back-record is automatic — the runbook covers only
the order-side reversal. On Click nothing tells Qoida anything, so a capture whose
settlement line shows a reversal with no matching refund row becomes a finance
exception within one settlement cycle. That reconciliation is what makes the
runbook enforceable; without it, back-recording is a request rather than a
control, which is why it ships with the refund path and not after it.

Fiscal consequences, owned by ADR 0038 and noted here because the partner seam
produces them: a Payme refund produces a second `SetFiscalData` with
`type: "CANCEL"`, which is a **separate** fiscal document linked by
`corrects_document_id` and never an overwrite of the sale document. Whether
Click's reversal requires a corrective fiscal document is undocumented and is an
open input.

### Cash, and the state that says so

Decided by the user on **2026-08-22**: a cash order receives no fiscal receipt
from a payment provider, because neither provider can produce one. Click's
`submit_items` needs a CLICK `payment_id` that does not exist; Payme's fiscal data
attaches to a Payme receipt that does not exist; and Click's `received_cash` is a
tender split *inside a CLICK payment*, not a cash-order path — reading it as one
produces a system that appears to fiscalize cash and does not.

This is built as an explicit, queryable state, never a null:

```text
fiscal status      NOT_APPLICABLE
reason_code        CASH_TENDER_NO_PROVIDER_FISCALIZATION
reason_note        "cash tender, no provider fiscalization"
```

A null would mean "unknown", and the whole point is that this is known. If the
decision reverses — a fiscal terminal is deployed under ADR 0038's `TERMINAL`
responsibility, or the `OPERATOR` path is built — the affected orders must be
found by a query on that reason code, not by inspecting orders one at a time.

Scale matters to how this is read: legacy `payment_methods` has cash enabled and
cash is this market's majority tender, so this is the common case rather than an
edge case, and `PARTNER` fiscal coverage is the minority of orders at cutover.

### Service recovery

`recovery.cases`, `recovery.remedy_decisions`, and `recovery.remedy_executions`
are unchanged in shape:

```text
recovery.cases
  id, tenant_id, brand_id, location_id, order_id, customer_account_id
  reason_code, severity, description, status, version
  opened_by, assigned_to, opened_at, resolved_at, updated_at

recovery.remedy_decisions
  id, tenant_id, case_id, remedy_type
  amount_minor null, currency null, percentage null, maximum_minor null
  scope_type null, scope_id null, expires_at null
  reason, status, proposed_by, approved_by, timestamps

recovery.remedy_executions
  id, tenant_id, case_id, decision_id
  target_module, target_reference, idempotency_key
  status, attempt_count, last_error, timestamps
```

```text
OPEN -> ASSESSING -> AWAITING_APPROVAL -> APPROVED -> EXECUTING -> RESOLVED
                                       -> DECLINED               -> MANUAL_ACTION_REQUIRED -> EXECUTING
```

| Remedy | Execution owner | Cutover |
|---|---|---|
| `FULL_REFUND` | payments | Built — as a back-recorded console refund |
| `PARTIAL_REFUND` | payments | Built — back-recorded; note Click has no partial reversal at all |
| `DELIVERY_FEE_REFUND` | payments | Built — back-recorded |
| `FIXED_CREDIT` | pricing/benefits | Gated on ADR 0015 and ADR 0018 |
| `PERCENT_DISCOUNT` | pricing/benefits | Gated |
| `FREE_DELIVERY` | pricing/benefits | Gated |
| `FREE_ITEM` | pricing/catalog validation | Gated; always brand-scoped, catalogs are brand-owned |
| `DELIVERY_COST_SUBSIDY` | fulfillment/reporting | Gated; an internal cost allocation, not a customer refund |

Policy resolves location, brand, tenant, then platform default through ADR 0030,
and is versioned and snapshotted onto the decision. It configures the allowed
remedies per reason and severity, operator self-approval amounts, manager and
maker-checker thresholds, maximum compensation per order, customer and time
window, whether remedies combine, benefit scope and expiry, and who bears a
delivery subsidy. `MANUAL_ACTION_REQUIRED` is the console-refund state, not an
error state: it is where a `FULL_REFUND` waits for an operator, and it carries the
runbook link.

Under `TENANT_SHARED` identity, fixed credit and free delivery may be
tenant-scoped where policy allows; under `BRAND_ISOLATED` benefits stay
brand-scoped. Concurrency must prevent double redemption across carts and orders.

### Events

```text
PaymentReserved          PaymentCaptured        PaymentCancelled
PaymentExpired           PaymentReversed        PaymentFailed
PaymentOutcomeUncertain  PaymentOutcomeResolved
RefundBackRecorded       RefundReconciled
RecoveryCaseOpened       RecoveryRemedyApproved RecoveryRemedyCompleted
CustomerBenefitGranted   DeliveryCostSubsidized
PaymentSettlementExceptionDetected / Resolved
```

Partition payment facts by intent and recovery facts by case. Never publish raw
provider payloads. A fiscal sign, a receipt URL, and any marking code are ADR 0029
protected evidence and never travel in an ADR 0032 event; a TIN is a business
identifier and may.

### Security

- Click's SHOP API has no auth header: the MD5 `sign_string` is the *only*
  authentication on the endpoint that credits orders, and MD5 over a
  secret-prefixed concatenation is not a strong primitive. Constant-time compare,
  alert on any burst of `-1`, and request Click's callback IP allowlist.
- Payme's Basic credential is compared constant-time before dispatch, and the
  source-IP allowlist is defence in depth.
- Merchant secrets stay in the ADR 0028 manager and resolve at call time from the
  binding's stable reference; rotation never changes the installation id.
- Store no PAN or CVV. Click's `card_token` flow keeps card data off Qoida
  entirely; the payment link and `checkout.js` form do the same.
- Every refund, remedy, approval, and replay is immutable ADR 0027 audit evidence.
- Both providers' checkout surfaces are unauthenticated, so the enforced amount is
  always the one recomputed from the ADR 0018 quote server-side.

### Testing

Contract and property tests that would each have caught a real defect:

- A recorded Click Prepare and Complete pair reproduces the documented MD5 exactly
  from the **raw** field values; a paired fixture proves that normalising
  `1000.00` to `1000` before hashing fails the check.
- Prepare and Complete sign different field lists — a Complete verified with the
  Prepare formula must fail.
- Complete never returns anything but `0`, `-4`, or `-9` after a successful
  charge, across every business-failure path; a business failure emits `error: 0`
  plus a reversal command in the outbox.
- Underpayment by one som is rejected on both providers — the named regression for
  the Django reference's misplaced parenthesis.
- The Payme endpoint answers a bad credential with HTTP 200 and `-32504`, asserted
  against the raw HTTP response rather than the handler's return value.
- Every Payme error carries `{ru, uz, en}`; `-31050…-31099` carries `data` naming
  the account sub-field.
- `PerformTransaction` twice returns the same result. `CancelTransaction` twice
  does not overwrite `cancel_time` or `reason` and never rewrites `-2` to `-1`.
- A transaction 43 200 001 ms past **`params.time`** cancels to `-1` reason `4` and
  answers `-31008` — with a fixture where `params.time` and Qoida's `create_time`
  are an hour apart, so measuring from the wrong clock fails.
- `GetStatement` includes states `1`, `-1` and `-2`, bounds inclusive, ascending.
- Concurrent `CreateTransaction` for one order: exactly one succeeds, the other
  gets `-31008`, asserted at the database against the partial unique index.
- One 12 000-som quote produces `12000` on a Click payment call and `1200000` on a
  Click fiscal call, from a single test.
- One two-unit line produces a Click `Price` of the line total and a Payme `price`
  of the unit price, from one fixture through both adapters.
- A timeout on `card_token/payment` produces `UNCERTAIN`, issues no retry, and
  calls `status_by_mti`; a "not found" from `status_by_mti` does not unblock a
  retry.
- Money is integer minor units with matching currency everywhere; parallel refunds
  cannot exceed the remaining captured balance; duplicate callbacks duplicate
  nothing; completed orders and totals are unchanged after any remedy.
- A cash order's fiscal document is `NOT_APPLICABLE` with the reason code, and a
  query returns every such order.

### Deliberately not built

| Not built | Why, and what it costs to add |
|---|---|
| Telegram bot payments | Designed for: ADR 0036 already carries a `TELEGRAM` channel with a `provider_installation_id`, and the port's two-phase inbound shape matches `pre_checkout_query`/`successful_payment`. Adding it costs a provider token, an opaque invoice payload, and a reconciliation path — the last of which cannot be specified until Payme answers whether a bot-cashbox payment reaches the Merchant API endpoint |
| Split tender | ADR 0046 owns it. The intent model here already sits beneath a tender |
| Platform-initiated refunds | Neither provider supports the needed shape; see the alternatives table |
| Recovery compensation beyond back-recorded refunds | Benefit grants and subsidies wait on ADR 0015 identity and ADR 0018 benefit reservation. The decision and approval contracts ship now so the gate is a flag, not a redesign |
| Qoida as a fiscal issuer | ADR 0038 specifies `OPERATOR` and leaves it unimplemented |
| Click Advanced/Split Shop, Click card tokens, CLICK Pass | Not required by the cutover channels. Card tokens and CLICK Pass are outbound direction-B calls the port already accommodates; CLICK Pass carries a 30-second auto-cancellation deadline that a till integration must be designed around |

## Rollout and rollback

1. Payme inbound endpoint against `test.paycom.uz` with the cashbox `TEST_KEY`.
   Both sandbox scenarios green — create-and-cancel unconfirmed, and
   create-perform-cancel confirmed — before a production cashbox exists.
2. Click SHOP API against one service in test configuration. Verify the signature
   against a real Click request before trusting any fixture: the documentation
   carries no worked example, so ours are computed rather than quoted.
3. One legal entity, one location, one channel, card only, cash disabled on that
   channel. Read-only settlement reconciliation from day one.
4. Cash enabled only where ADR 0038's fiscal-terminal precondition holds.
5. Console refund back-recording, with the daily reconciliation that detects an
   unrecorded Click refund, shipped together.
6. Second legal entity, proving the per-entity binding resolves without a code
   change.

Rollback disables new payment initiation per binding and never rewrites completed
financial facts. A binding cannot be retired while any attempt against it is
`UNCERTAIN`.

## Open inputs and who answers them

**To Click**, through the acquiring bank relationship or the onboarding contact:

1. The full MERCHANT API `error_code` / `error_note` table, and specifically which
   codes on `card_token/payment` and `click_pass/payment` are safe to retry.
   Documented today: HTTP statuses only, with `error_code: 0` in every example.
2. `status_by_mti`'s trailing `YYYY-MM-DD` — which date it is, in what timezone,
   and what happens when a payment falls outside it. This is the uncertainty
   resolver, and a wrong date reads as "no payment found".
3. Prepare and Complete replay semantics: is Prepare at-most-once, how many times
   and on what schedule is Complete retried, and does a retried Complete reuse
   `click_trans_id` or mint a new one.
4. Whose TIN or PINFL `CommissionInfo` carries, and whether it may vary per line
   under one `service_id`.
5. Is `submit_items` idempotent for a repeated `payment_id`, and does
   `payment/reversal` require a corrective fiscal document.
6. The `Auth` timestamp tolerance, and the callback source IP allowlist.

**To Payme**, through the technical contact:

7. Which code `CheckPerformTransaction` must return for "order already paid" and
   for "another transaction is active". The docs permit only `-31001` and
   `-31050…-31099` there; Payme's own PHP template returns `-31008`; the sandbox
   tests `-31008` for the same condition on the neighbouring method. Settled today
   only by a reference implementation with documented defects.
8. Whether a Telegram bot-cashbox payment reaches the Merchant API endpoint at all
   and appears in `GetStatement`. The docs never say; the sequence diagram has no
   merchant-billing lane and the bot-cashbox form has no endpoint field.
9. The `SetFiscalData` `status_code` enumeration, referenced as "list of codes
   below" on two pages with no list on either.
10. Whether a deadline exists after which `SetFiscalData` will not arrive — the
    interval that turns silence into a blocked fiscal document.
11. The HTTP response timeout and retry backoff against the merchant endpoint.
12. Whether the Basic-auth login is always literally `Paycom`.

**To product, finance, and legal:**

13. Compensation remedy set, approval thresholds, benefit expiry, and who bears a
    delivery subsidy (product, finance).
14. Which party is the legal fiscal agent per settlement path, and whether the
    Qoida–restaurant agreement is a commission agreement in the fiscal sense
    (legal). Shared with ADR 0038's open input of the same name.
15. Retention period for payment, fiscal, and settlement evidence, per legal
    entity (finance, legal) — an ADR 0030 policy value, not a constant.

**None of these is structural**, which is why this ADR is `Accepted` with them
open, under ADR 0000's rule. Items 1, 3, 5, 6, 9, 10, 11 and 12 change a mapping
table, a retry classification, or a timeout constant inside one adapter. Item 2
changes a query parameter the platform already snapshots. Item 4 can only
*relax* the per-legal-entity account requirement, never invalidate it, because
per-entity accounts are correct under either answer. Item 7 changes one returned
constant. Items 13 and 15 are ADR 0030 policy values resolved and snapshotted on
a decision; the case, decision, and execution aggregates do not change with the
numbers. Item 14 is a legal characterisation that changes who signs, not what is
built. Item 8 *is* structural — for a Telegram channel, which is exactly why the
Telegram channel is designed for and not built.

## Implementation checklist

- [x] Settle the legal-entity dimension on the payment binding with ADR 0038 and ADR 0026 before any `PARTNER` code is written. `payments.merchant_bindings.legal_entity_id` is NOT NULL, V0053 creates `tenant.legal_entities` and adds `fk_merchant_binding_legal_entity` over `(tenant_id, legal_entity_id)`, and `PaymentLegalEntityResolver` resolves the seller through it. `LegalEntityService` and `JdbcLegalEntityStore` author an entity; no control-plane endpoint calls them, so one is created by hand-written SQL today.
- [ ] Add `SomAmount` and `TiyinAmount` with the single conversion, and the ArchUnit rule that fails any other `× 100` on money. Both value types exist in `payments.domain`; `ModularArchitectureTests` has no money rule.
- [x] Add intent, attempt, transaction, callback, and refund tables, together with ADR 0046's settlement and tender tables in one migration. Not one migration: V0027 added intents, attempts, transactions, `provider_callbacks`, `merchant_bindings` and `fiscal_documents`; V0042 added `order_settlements` and `tenders`; V0048 added `tenders.refunded_minor`; and V0052 added the refund record as `payments.order_remedies`, `remedy_entitlements` and `entitlement_redemptions` rather than as the `payments.refunds` named in the physical model above.
- [ ] Implement the refundable-balance reservation and the Qoida payment state machine. `PaymentAttemptStateMachine` and `PaymentIntentStatus` are built and tested; `OrderSettlementService.refund` unwinds tenders in reverse settlement order against `tenders.refunded_minor` (V0048), refuses to exceed what they settled, and is now called by `OrderRemedyService`. **It still cannot run against a real order**: `OrderSettlementService.plan` has no production caller, so no `payments.order_settlements` row is ever written and `require` answers "The order has no settlement" for every order checkout produced.
- [x] Implement the Payme inbound endpoint: auth before dispatch, HTTP 200 always, localised errors, the twelve-hour rule from `params.time`, the expiry sweep, and `GetStatement`. `PaymeMerchantApiController` + `PaymeEndpointSecurity` + `PaymeMerchantApi`, covered by `PaymeMerchantApiTests` and `PaymeMerchantApiEndpointTests` (expiry, replay, cancel-after-perform, `GetStatement` returning every state).
- [x] Implement the Click SHOP API endpoints: raw-value signature verification, the documented check order with `-4` before `-2`, deterministic `merchant_prepare_id`, and the answer-`0`-then-reverse rule. `ClickShopApiController` `/providers/click/{bindingRef}/prepare|complete`, `ClickSignature`, `ClickPrepareId`, `ClickCallbackProcessor`; `ClickSignatureTests` and `ClickShopApiCallbackTests` cover raw-value signing, idempotent prepare and amount enforcement.
- [x] Implement `PaymentOutcomeResolver` for both providers, with the business-date snapshot and the no-retry-on-not-found rule. Named `UncertaintyResolver` in code: `CLICK_STATUS_BY_MTI` resolves through `ClickMerchantApi.statusByMti` against the business date `PaymentBusinessCalendar` snapshots before the call, `PAYME_CHECK_TRANSACTION` through the non-mutating `CheckTransaction`, and a not-found never unblocks a second charge.
- [x] **Open the attempt and present the checkout surface** (V0045). The attempt
      is opened and committed — with the `merchant_trans_id` Click's callback names
      and the business date its resolver asks about — *before* any provider call,
      and in a transaction that closes before the presentation opens its own, so an
      outbound call never sits inside a transaction that can roll back. Three
      surfaces: Click's unsigned `my.click.uz` payment link, Click's
      `invoice/create` push, and Payme's base64 checkout URL. The storefront calls
      `POST /orders/{orderId}/payment-sessions` after checkout returns
      `PAYMENT_AUTHORIZING`; the seam is the comment in
      `CheckoutService.awaitPayment` that names the endpoint. A customer who
      abandons a checkout and returns is re-presented the same attempt, enforced by
      widening `ux_payment_attempt_live_per_intent` into
      `ux_payment_attempt_open_per_intent` over every non-terminal state — the old
      index left `INITIATED` and `PRESENTED` outside it, so a second payable link
      could be issued against one intent. A captured or uncertain attempt is
      refused rather than re-presented, and an invoice push is never repeated on a
      customer's own request, because it is a mutating call with no idempotency
      key.
- [x] Implement the ADR 0005 provider-callback inbox keyed per provider and body hash. `payments.provider_callbacks` with `uq_provider_callback_delivery (tenant_id, provider_type, callback_kind, provider_reference, request_body_hash)`, written by `JdbcProviderCallbackStore` from both inbound endpoints.
- [ ] Implement settlement import and daily reconciliation, including the unmatched-Click-reversal exception. No settlement-file table, importer or reconciliation job exists.
- [ ] Implement console-refund back-recording, its evidence capture, and the runbook. Back-recording and evidence are built: `POST /api/v1/operations/tenants/{tenantId}/orders/{orderId}/refunds` under `refund.request`, `payments.order_remedies` carrying `execution_channel`, `provider_reference`, `executed_by`, `executed_at` and an attestation distinct from `recorded_by`, and `VerificationState` with the unverified worklist at `GET /remedies/unverified` under `refund.execute`. `docs/runbooks/` has no refund runbook, and the settlement blocker on the line above means no real order can be back-recorded yet.
- [ ] Implement the recovery case, remedy policy, approval, and execution outbox, with `MANUAL_ACTION_REQUIRED` carrying the runbook. The remedy set and its approval threshold are built inside `OrderRemedyService.approvalFor`, weighed against everything the order has already given back, and every record raises an ADR 0027 `payments.remedy.record` audit fact. There is still no recovery case — no table, no case id, no lifecycle — no versioned policy snapshot, no execution outbox, and no `MANUAL_ACTION_REQUIRED`; `recovery.case.manage` and `recovery.remedy.approve` are granted by `PlatformRole` and declared by no endpoint.
- [x] Record a cash order's fiscal status as `NOT_APPLICABLE` with its reason code, and add the query that finds them. `FiscalDocument.notApplicableForCash` with `CASH_TENDER_NO_PROVIDER_FISCALIZATION`, written by `PaymentFiscalService` on the cash path from `PaymentIntentService`, and queried by `JdbcFiscalDocumentStore` and the ADR 0038 coverage report.
- [ ] Register payment providers as ADR 0026 installations; add no payments-local credential rows. The schema enforces the second half — `ck_merchant_binding_secret_is_a_reference` refuses anything but a `qoida:…:provider_payment:…` reference and both foreign keys point at `integration.installations`/`bindings` — but **nothing writes `payments.merchant_bindings`**; `JdbcPaymentBindingResolver` only reads it, so a binding can be created today only by hand-written SQL.
- [ ] Add the concurrency, duplicate, uncertainty, unit-crossing, signature, and financial-invariant tests above. Duplicate/replay, signature, unit-crossing and uncertainty are covered by the Click and Payme test classes, and `RefundAndRemedyTests` now covers the financial invariants against PostgreSQL — the cumulative cap taken from the settlement service rather than reimplemented, repeated small refunds that cannot walk around the approval threshold, an approval that cannot be reused for another remedy, and an order that is never reached by its id alone. The concurrency tests are not written.
- [ ] Send the twelve provider questions and record the answers in `docs/providers/`. The questions are written down in `docs/providers/click-merchant-api.md` and `payme-merchant-api.md`; no answers have come back, which is what keeps the open inputs open.

## Exit criteria

A card order placed on one legal entity's channel is reserved, captured, and
fiscalized end to end through **both** Click and Payme in their sandboxes, using
that entity's own service and cashbox. A lost response on each provider resolves
to exactly one outcome, through that provider's own resolver, without a second
charge. A refund executed in each provider's console is back-recorded, holds the
refundable-balance invariant, and matches its settlement line — and an
unrecorded Click reversal appears as a finance exception within one settlement
cycle. A cash order carries a fiscal document in `NOT_APPLICABLE` with a reason a
query can find. And the payment binding for a second legal entity in the same
tenant resolves without a code change.

## References

- [`docs/providers/click-merchant-api.md`](../../providers/click-merchant-api.md) — SHOP API and MERCHANT API contract, signatures, error tables, reference-implementation defects, and the twelve open questions.
- [`docs/providers/payme-merchant-api.md`](../../providers/payme-merchant-api.md) — JSON-RPC envelope, state machine, timeouts, method contracts, account lookup semantics, and twenty-four collected uncertainties.
- [`docs/providers/fiscalization-via-payment-providers.md`](../../providers/fiscalization-via-payment-providers.md) — the fiscal surface of both providers, the field-list comparison, the timing inversion, and the merchant-identity finding.
- [ADR 0038](../partial/0038-legal-entities-fiscal-receipts-and-product-classification.md), [ADR 0046](../partial/0046-loyalty-points-and-split-tender.md), [ADR 0047](../partial/0047-dine-in-table-service-and-qr-ordering.md) — the three ADRs that restructure parts of this one.
