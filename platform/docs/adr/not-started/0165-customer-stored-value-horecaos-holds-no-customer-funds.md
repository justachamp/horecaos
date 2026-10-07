# ADR 0165: Customer stored value: HorecaOS holds no customer funds

- Decision status: Accepted — proposed by Claude (batch 19); accepted by the platform owner 2026-10-07
- Implementation status: Not started — there is nothing to build to hold this
  position, and that is the point; what exists is the structure that already makes
  it true, and three loose ends this record names. The ledger is points only:
  `loyalty.entries.entry_type` is a closed set at the database
  (`ck_loyalty_entry_type`, V0042) with no top-up and no withdrawal,
  `loyalty.accounts` has no account-type column, and the application role holds
  `SELECT` and `INSERT` on `loyalty.entries` and nothing else. Settlement refuses a
  balance that behaves like money: `payments.payment_methods.settles_from_balance`
  is pinned to the `OPERATOR` responsibility
  (`ck_payment_method_balance_is_not_a_fiscal_path`), a balance tender carries no
  payment intent (`ck_tender_balance_has_no_intent`), and
  `ux_tender_one_balance_per_settlement` admits one balance leg per settlement;
  `PaymentMethod` is exactly `CASH`, `CLICK`, `PAYME`, `TELEGRAM` and
  `MARKETPLACE`, plus the `LOYALTY_POINTS` leg that
  `CheckoutSettlementPlanner.POINTS_METHOD_CODE` registers. `NoDepositTenderTests`
  holds the method set and
  `LoyaltyLedgerAndSplitTenderTests.theDatabaseRejectsTopUpAndWithdrawal` the entry
  types; of the three settlement constraints only `ck_tender_balance_has_no_intent`
  has a test today, and a weak one, so the Specification adds a guard for each of
  the other two and tightens that one. The operations console says so in three places
  and three languages:
  the «Deposit accounts» card on the loyalty page (`marketing.loyalty.deposit.*`,
  `data-testid="loyalty-deposit-not-built"`), the customer pane's
  `customers.cashback.depositNotBuilt`, and an order payment read
  (`OperationsPaymentController`, `payment[]`) that can name no deposit method.
  Three loose ends are open. The manual adjustment endpoint
  (`POST /api/v1/operations/tenants/{tenantId}/customers/{customerId}/loyalty/adjustments`,
  `LoyaltyOperationsController.AdjustmentRequest`) takes `reasonCode` as any
  non-blank string, and the console's adjustment dialog
  (`customer-detail-pane.ts`, `adjustReasonCode`) lets the operator type it, so
  points can be credited against cash received under any label. No record says what
  a refund does when the original method cannot take the money back. And the three
  gap-map rows are still `BLOCKED` or `PARTIAL` on a legal question instead of
  closed on a decision.
- Date proposed: 2026-10-07
- Date decided: 2026-10-07
- Deciders: proposed by Claude (batch 19); Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0007, ADR 0013, ADR 0025, ADR 0026, ADR 0027, ADR 0028,
  ADR 0029, ADR 0030, ADR 0031, ADR 0032, ADR 0038, ADR 0042, ADR 0046, ADR 0048,
  ADR 0055, ADR 0095, and ADR 0166, which is **Proposed** in the same batch:
  accepting this record does not accept 0166, and nothing here assumes its outcome
  (the two places this record touches it, Decision 4's fiscal line and item 6 of
  the superseding ADR's list, say "whatever ADR 0166 decides")
- Supersedes / Superseded by: — (amends ADR 0046 without editing it; that record
  stays Accepted as written. It reopens three items of it and nothing else. Two
  are rows of its Alternatives table: «Issue customer-funded stored value now, as
  Delever does», whose revisit condition — a licence, or an acquirer-held float —
  Decision 6 turns into a procedure; and «Delegate the balance to the acquirer's
  wallet (Payme, Click)», which ADR 0046 rejected as a *default* and which this
  record keeps rejected as a default while naming it the only route a tenant who
  needs stored value may take, Decision 4. The third is the first bullet of ADR
  0046's «What would bring stored value back», "A licence exists": there a licence
  held by a tenant, or by Qoida (that record's wording), lets a `DEPOSIT` account
  type join the loyalty ledger, with `TOPUP` and `WITHDRAWAL` entry types. This
  record narrows it: **a tenant's licence yields a Decision 4 external tender
  only, never an in-ledger deposit.** A ledger that HorecaOS operates for customer
  funds needs an authorisation of HorecaOS's own, or counsel's written
  confirmation that a tenant's authorisation obliges HorecaOS to operate that
  tenant's ledger (Decision 6, route a), and then a new ADR. The second bullet,
  "An acquirer holds the float", is restated as Decision 4 and not changed. ADR
  0046's closed input of 2026-08-23, "Loyalty is points only", is not touched)
- Open inputs: each is closed on its proposed default if the owner accepts the
  record as written; the ones that name a person other than the owner stay with
  that person and the work they block is marked.
  - **Whether the owner wants a Central Bank of Uzbekistan authorisation for
    HorecaOS, or for a company the owner controls, at all** (Ayubkhon Abbosov,
    platform owner). Proposed default: no. Not pursued before a pilot tenant asks
    in writing for prepaid stored value and states a volume; until then rows
    `5.2f` and `6.3a` stay declined and Decision 6 is dormant. Blocks: only
    Decision 6's route (a).
  - **Whether HorecaOS merely relaying a payment request to a provider that is
    itself authorised needs an authorisation of its own** (legal counsel, engaged
    by the platform owner). Proposed default: assume HorecaOS needs none only while
    it never holds, receives or sends customer funds — only a payment request and
    a settled outcome, the shape of the Click and Payme adapters today — and build
    no stored-value adapter until counsel confirms that in writing for the named
    provider. Blocks: only Decision 4's adapter, which is not built in v1.
  - **Whether a gift card or certificate a tenant sells for money, redeemable
    only at that tenant, is stored value** (legal counsel). Proposed default:
    treat it as stored value, which is the conservative reading, and decline it
    with the rest; a tenant who wants one issues it outside the platform, and a
    free promo code (ADR 0018, ADR 0140) remains fine because nobody paid for it.
    Blocks: nothing.
  - **The operator reason-code list for manual points adjustments** (finance, with
    Ayubkhon Abbosov). Proposed default: `GOODWILL`, `SERVICE_RECOVERY`,
    `CORRECTION`, `ACCOUNT_MERGE`, `LEGACY_OPENING_BALANCE`, in report-only mode
    for one release and then refusing anything else (Decision 3). Two things ride
    on the same default. Finance reads, monthly and from the audit facts, the
    five operators with the largest `GOODWILL` plus `CORRECTION` total in each
    tenant, because a sale labelled with a listed code is the one case the list
    cannot stop (Consequences, Negative). And closing the list is treated as
    compatible under ADR 0031, not as a reason for a `v2` (Specification, API);
    Ayubkhon Abbosov overrules that by saying so before the enforcing release.
    Blocks: only the enforcing switch.
  - **What a tenant migrating from Delever does with the deposit balances its
    customers hold there** (Ayubkhon Abbosov, for the future migration programme
    of ADR 0055, with each tenant). Proposed default: never imported as
    platform-held value. The tenant's liability to its customers stays the
    tenant's, listed per tenant in the migration plan to honour or settle outside
    the platform; converting any of it to points happens only by an explicit
    per-tenant decision, recorded as `LEGACY_OPENING_BALANCE` adjustments with the
    tenant's written consent, because it takes a cash right away from a customer.
    Blocks: only that future programme.
  - **What a prospect who asks for a deposit is told** (Ayubkhon Abbosov, as
    product owner). Proposed default: the sentence in Decision 8. Blocks: nothing.

**To accept as written:** say "accept 0165". Every open input above is then closed
on its proposed default.

**Decision record, 2026-10-07.** Accepted by Ayubkhon Abbosov (platform owner) under the standing instruction "lets finish all" given the same day, which accepts every record proposed in batch 19 (ADRs 0154–0176) on the default each open input proposes. An input that names a person other than the owner, or an external fact (a device model, a legal wording, a provider capability, a dataset publication), stays with that owner as written and implementation proceeds without it, marking what waits. Implementation starts in operations batch 20 (2026-10-07).

## Context

Three rows of the operations gap map wait on one sentence of ADR 0046.

- **`5.2f`, the deposit balance ledger (IA 5.2)**, is `BLOCKED`: "A customer cannot
  hold a prepaid balance and an operator cannot see or move one; Delever's deposit
  payment method has no counterpart here." Its "Blocked by" is the line in the
  blocked table of `docs/frontend-and-parity-plan.md`: "ADR 0046 — Whether
  HorecaOS may hold customer prepaid balances at all — Legal".
- **`6.3a`, loyalty deposit accounts (IA 6.3)**, is `BLOCKED`: "An operator cannot
  top up, refund or spend a customer cash balance, and deposit is not selectable
  as a payment method on an order." Its "Blocked by" is "A Central Bank of
  Uzbekistan e-money authorisation (or the owner's decision to hold funds under a
  tenant's own licence). ADR 0046 §'What would bring stored value back' requires a
  new ADR, not an implementation task."
- **`X.1` of §8, the `payment[]` array (IA 8.1, "cash + cashback + deposit")**, is
  `PARTIAL`, built apart from its last clause, and its note ends: "Deposit tender
  remains out of scope."

Delever lists cashback (Кешбэк) and a customer deposit (Депозит) as two payment
types, both selectable on an order, and its balance funds carry an expiry date
(`docs/delever-parity-matrix.md`). ADR 0046 took the first and withdrew the second
on 2026-08-23, and wrote the withdrawal into the structure instead of a switch:
V0042's own header says "no DEPOSIT account type, no CUSTOMER_DEPOSIT payment
method, no TOPUP entry type, no WITHDRAWAL entry type, and no kill switch".

**What is and is not known.** Whether a restaurant may hold customers' prepaid
money, and under which authorisation, is a legal question this repository has no
answer to: there is no counsel opinion and no authorisation anywhere in it. ADR
0046 said so in its own words, "an e-money issuer or payment-service authorisation,
depending on what the regulator classes a restaurant prepayment as", and nothing
since has answered it. This record does not try to. It observes something narrower:
an unanswered legal question is a reason not to build a feature, not a reason to
keep three rows open as if the answer were coming. The engineering-safe default for
"may we hold funds" is no, and it can be written down in a form that closes the
rows and says what would reopen them.

**What a stored-value balance is, for this record's purposes.** One test, applied
to any feature that looks like it: a balance is stored value when money a customer
handed over, or an amount a customer paid for, sits in a balance that the platform
or a tenant owes back in money (on closure, on expiry, on dispute) or that can be
spent without belonging to one particular order. By that test, five things that
look close are not stored value and nothing in this record changes them:

| Thing | Why it is not stored value |
|---|---|
| Loyalty points (ADR 0046) | Never bought, never paid out, destroyed at expiry and at closure; the three not-money properties are enforced by constraint, not asserted |
| An ADR 0048 future discount | `amount_minor = 0`, bounded by uses and a window, granted by a person and never sold |
| A referral credit (ADR 0067) | Tenant-authored, granted on a qualifying event, an ordinary points `ADJUSTMENT` |
| A tenant's wallet (ADR 0095, `commercial.wallet_entries`, V0211) | Money a *tenant* paid *HorecaOS* in advance for HorecaOS's own subscription; HorecaOS is the seller. A different party, a different relationship, a different ledger. Nobody should cite one as precedent for the other |
| A card payment in flight | Click or Payme capture it for one order and settle it to the tenant's own merchant account (ADR 0038 makes the account the tenant's, one per legal entity); no code path in the platform receives a customer's money |

**Three loose ends, each a way stored value could arrive without anybody deciding.**

1. **Points can be sold.** `AdjustmentRequest.reasonCode` is `@NotBlank` and
   nothing else. An operator who takes cash at a counter and credits 100 000 points
   under a reason of their choosing has run a prepaid scheme. ADR 0046 accepted
   that an adjustment is "visible, attributable, and countable" rather than
   impossible, which is right for a favour and wrong for a product: the approval
   threshold and the audit fact record that it happened, and nothing records that
   it was a sale.
2. **A refund that cannot reach its source.** Click's reversal takes no amount and
   is bounded by the reporting month; Payme has no outbound refund at all (ADR 0013,
   ADR 0048). ADR 0048 settled that HorecaOS never calls a provider's refund API
   and records what staff did in the provider's cabinet. It did not say what
   happens when the cabinet cannot do it, and the obvious answer — "credit the
   customer's account instead" — is stored value created by a support agent in a
   hurry.
3. **The rows themselves.** `5.2f` and `6.3a` carry a status that says "waiting on
   legal", which a prospect, a reader of `docs/horecaos-vs-delever.md` and the next
   audit all read as "coming". Nothing is coming in v1.

**What a tenant that needs prepayment can still have.** The licence sits with
whoever holds the money. Where a tenant has its own programme through a licensed
bank or an e-money operator, the money is theirs and their provider's, and what
HorecaOS sees is a payment instruction going out and a settled outcome coming back
— the shape of the Click and Payme adapters, which `ProviderCategory.PAYMENT`
already models and ADR 0046 called "an ordinary ADR 0013 payment intent beneath an
ordinary tender with `settles_from_balance` false". That route needs a tenant, a
provider and counsel's reading, none of which exists, so it is specified here and
not built.

## Decision

**Decline stored value in v1: HorecaOS holds no customer funds, and nothing in the
platform is shaped to hold them. A tenant that needs stored value uses its own
licensed provider through an ordinary payment adapter, later and only when one is
named.**

1. **The rows close as declines.** `5.2f`, `6.3a` and the deposit tender of `X.1`
   in §8 are declined, not blocked and not deferred. No deposit account, tender,
   entry type, payment-method row, endpoint, event, capability, policy key, report
   line or screen exists for them in v1, and none is left dormant behind a switch
   (ADR 0046's own reason: a disabled feature still costs its tables, its registry
   row and its tests, and its first defect is found by whoever enables it). The
   console's two "not built" notices become "not offered", with the rule in a
   sentence (Decision 8).
2. **The test is the one in Context.** A change that makes a customer's money, or
   an amount a customer paid for, sit in a balance that is owed back or spendable
   without an order is stored value and needs a new ADR that supersedes this one.
   Review applies the test to anything that looks near it: points bundles bought
   with money, gift cards and certificates sold for money, prepaid meal plans,
   "credit instead of refund", an account top-up by any name.
3. **One new guard narrows the only back door the structure does not already
   close: operator reason codes become a closed list.** The manual adjustment
   endpoint accepts a `reasonCode` only from a platform-scope policy list
   (default `GOODWILL`, `SERVICE_RECOVERY`, `CORRECTION`, `ACCOUNT_MERGE`,
   `LEGACY_OPENING_BALANCE`). The list is settable at `PLATFORM` scope only, so a
   tenant cannot widen it to include a code that means "paid in cash". The
   free-text `reason` narrative stays. Codes written by the system itself (the
   accrual clawback, the referral grant) never pass through the operator path and
   are unaffected. It starts in report-only mode — a counter, labelled by mode
   only, incremented for every code outside the list — for one release, then
   refuses with 400 `VALIDATION_FAILED` and an `errors[]` entry naming
   `reasonCode`, so a tenant's existing habits show up in a metric before they
   show up as a refusal. The console learns the list from a read, not from a copy
   in its own source (Specification, API). **What this does not do:** it removes
   the custom label, not the sale. An operator who takes cash can still pick
   `GOODWILL` or `CORRECTION`; that case is caught only afterwards, by the audit
   facts, the ADR 0027 approval threshold and finance's monthly read (Consequences,
   Negative).
4. **Stored value, if a tenant needs it, is an external tender and never a
   HorecaOS balance.** It is a `PAYMENT` provider installation (ADR 0026) whose
   instrument is the provider's own wallet or prepaid card: the customer funds it
   in the provider's app, never through HorecaOS; the platform sends a payment
   request and receives a settled outcome, the way it does for Click and Payme; the
   tender row has `settles_from_balance = false` and a payment intent; the
   tenant's `payments.payment_methods` row has `responsibility = PARTNER`; and
   HorecaOS stores no copy of the provider's balance, because a stored copy is a
   second ledger that will disagree with the first. A balance shown to the
   customer is read live from the provider or not shown. Refunds follow ADR 0048:
   bookkeeping against the provider's own cabinet. It is built only when all four
   hold: a named tenant, a named provider with its own authorisation, counsel's
   written answer to the second open input, and the provider's contract read and
   recorded under `docs/providers/` (the house rule before any adapter). A fake
   adapter and the ADR 0007 contract tests come first.
5. **A money refund never becomes a balance, and a balance never becomes money.**
   Where the original method cannot take a refund back, the tenant pays it out of
   band (cash at the counter, a bank transfer, the provider's cabinet when it
   works) and the operator records exactly that under ADR 0048, with the
   reference. The platform offers no "credit the account instead" remedy, and no
   remedy converts points to money or money to points. This restates ADR 0046's
   refund rule for the one case it left open.
6. **What reopens this, and what changes.** Either (a) HorecaOS, or a company the
   owner controls, obtains a Central Bank authorisation that covers customer
   prepayments, or counsel confirms in writing that a tenant's own authorisation
   obliges HorecaOS to operate that tenant's ledger; or (b) a pilot tenant that
   holds its own authorisation, or whose bank runs the programme, names its
   provider and volume, and HorecaOS only transmits payment requests. Under (b)
   Decision 4 is built for that provider and nothing else changes: this is the
   narrowing of ADR 0046's "A licence exists" named in Supersedes, a tenant's
   licence alone does not put a deposit in a HorecaOS ledger. Under (a) a new ADR
   supersedes this one, and the Specification section "If an authorisation is
   obtained" lists what it must decide before any table is written. Neither
   trigger is met by a tenant asking for the feature, or by a legal opinion that
   the risk is low.
7. **Legacy deposit balances are not imported as platform-held value.** The
   migration programme (ADR 0055; ADR 0024 stays dormant until then) lists each
   tenant's outstanding deposit liability for the tenant to honour or settle
   outside the platform. Converting any of it to points is a per-tenant, written,
   audited decision (open input), never a bulk import.
8. **Say it plainly, once, in the words below.** The console's two notices, the
   gap-map and IA entries, and the answer to a prospect all use the same
   sentence: *"HorecaOS does not hold customer money, so there is no prepaid
   deposit. Customers earn and spend loyalty points, which are not money; a
   tenant that runs its own licensed prepaid programme can connect its provider
   as a payment method."*

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Build the Delever-style deposit now, behind a kill switch, and answer the legal question before enabling it | ADR 0046 already rejected the dormant feature: tables, a registry row, endpoints and tests maintained against no user, with the first defect found by whoever enables it two years on. It would also put a money-holding schema in every tenant's database from day one | Never as a shape; the reversal conditions produce a new ADR with a live feature, not a dormant one |
| Build it and apply for the authorisation in parallel | Operates a payment service across every tenant for as long as the application takes, which is the failure ADR 0046 named: not a bug but an unlicensed service, everywhere at once | An authorisation is in hand (Decision 6, route a) |
| Hold the funds "under a tenant's own licence" (the wording of row `6.3a`'s blocker; the tenant form of ADR 0046's "A licence exists") | A tenant's licence covers that tenant's customers; the platform would still be the party whose accounts and code hold the float for every other tenant, and whose failure loses it. If the tenant is the issuer and HorecaOS only transmits instructions, that is Decision 4 and not custody | Counsel confirms in writing a licence that obliges HorecaOS to operate the tenant's ledger: this option itself then wins, through a new ADR under Decision 6, route (a). A structure in which the tenant is the issuer and HorecaOS holds nothing is the other option, Decision 4 |
| A segregated or escrow bank account that HorecaOS operates | Still custody: HorecaOS signs for the money and owes it back. Segregation limits the loss, it does not remove the authorisation question | HorecaOS holds an authorisation that contemplates such an account |
| Delegate the balance to the acquirer's wallet (Payme, Click) as the *default* | ADR 0046's reason stands: a wallet at Payme is not spendable at a Click-only branch, and one customer's money splits across acquirers with no combined view. Kept as the *route*, not the default | A named tenant and a named provider exist (Decision 4) |
| Sell points: bundles bought with money, "pay 100 000 get 120 000" | A balance bought with money that is spent like money is stored value under another name; the three not-money properties of ADR 0046 hold only because points are never sold | HorecaOS holds an authorisation (route a) |
| Gift cards and certificates as promo codes with a face value sold for money | Same: a face value paid for in advance is a prepaid balance with a code on it. Free promo codes are different and stay (ADR 0018, ADR 0140) | Counsel rules that a single-tenant, single-brand certificate is not stored value, or route (a) |
| Credit the account when a refund cannot reach the original method | Creates a balance owed back in money from a support desk, with no ledger, no liability report and no terms | Never without route (a) |
| Leave rows `5.2f` and `6.3a` `BLOCKED` until legal answers | Costs nothing today and tells every reader the feature is coming. It also leaves the custom reason-code label open and the sales answer undefined | Never; the cheapest part of this record is the part that narrows the accident |

## Consequences

### Positive

- Three rows leave the "waiting on legal" list, and the IA stops promising a
  deposit balance, so the next audit counts one fewer unanswerable item.
- HorecaOS carries no safeguarding, AML or unclaimed-balance obligation, and no
  customer-money liability line, in v1.
- The one back door in the points ledger that the structure did not close — a
  sale disguised as an adjustment — narrows: the label that says "paid in cash"
  goes, visibly first (a counter before a refusal). A sale labelled with a listed
  code stays possible, which is the first residual risk below.
- A prospect gets one clear answer, and a tenant with a licensed programme gets a
  route that does not need a HorecaOS ledger.

### Negative

- **A parity gap with Delever stays open on purpose.** A tenant that uses deposits
  there has no equivalent here, and the Delever migration (a later programme) meets
  customers holding balances nobody can convert.
- Customers cannot prepay. A tenant that wants prepaid meal plans or certificates
  runs them outside the platform and the platform cannot see them.
- A refund that cannot reach its source becomes an out-of-band payment with a
  reference, which is slower and more manual than a credit.
- Closing the reason-code list changes a console field from free text to a list, and
  an operator whose habit is a custom code will hit a refusal after one release.
- **Residual risk: a sale can still be labelled `GOODWILL` or `CORRECTION`.** The
  closed list removes the label that says "paid in cash"; it cannot tell a favour
  from a sale, and the counter sees only unlisted codes, never a listed one used
  wrongly. What catches it is after the fact: the ADR 0027 approval threshold
  puts a second person on every adjustment above it, and finance reads the
  `loyalty.balance.adjust` audit facts monthly (a saved query grouped by actor and
  reason code, no new report) for the five operators with the largest `GOODWILL`
  plus `CORRECTION` total in each tenant, and asks about any whose figure stands
  out. That is detection, not prevention, and this record does not claim more.

### Accepted trade-offs

- The rows are closed on a conservative reading of a regulation nobody has read to
  the end. If the reading is wrong in the permissive direction, a tenant waits for a
  feature it could have had; if it were wrong in the other direction, the platform
  would be operating an unlicensed service. The first error is cheap.
- Decision 4 describes an adapter that is not built and may never be. It is written
  so that the answer to "can a tenant have prepaid?" is a route and not a refusal,
  and so that nobody builds a ledger first.
- A tenant's own wallet at HorecaOS (ADR 0095) is real money held in advance and is
  not in conflict with this record, because HorecaOS is the seller there. The
  difference is easy to lose in a code review, which is why the test is stated.

## Specification

### What already holds, and what each guard is tested by

| Property | Where it is enforced | Test |
|---|---|---|
| No movement funds or drains a points account | `ck_loyalty_entry_type` (closed set, V0042); `EntryType` | `LoyaltyLedgerAndSplitTenderTests.theDatabaseRejectsTopUpAndWithdrawal` (`TOPUP`, `WITHDRAWAL`, `PAYOUT`) |
| The ledger cannot be rewritten | Application role has `SELECT, INSERT` on `loyalty.entries`; append-only trigger | the same suite's append-only cases |
| A balance tender is never a provider call | `ck_tender_balance_has_no_intent` | `LoyaltyLedgerAndSplitTenderTests.pointsCannotReachAProvider`, which asserts only that the insert fails: its `payment_intent_id` is a random UUID that `fk_tender_intent` would also refuse, so it passes with the check dropped. Tightened in the checklist |
| A balance method is never a partner, terminal or marketplace path | `ck_payment_method_balance_is_not_a_fiscal_path` | None today: the three `registerMethod(..., true, ...)` calls in the test tree (`LoyaltyLedgerAndSplitTenderTests`, `RefundAndRemedyTests`, `OrderCompletionAccrualTriggerTests`) all register `LOYALTY_POINTS` as `OPERATOR`, and nothing inserts a balance method under another responsibility. New guard 4 |
| One balance leg per settlement | `ux_tender_one_balance_per_settlement` | None today: nothing inserts two balance tenders into one settlement. New guard 5 |
| No deposit payment method exists | `PaymentMethod` is five values; `POINTS_METHOD_CODE` is the only `settles_from_balance` code | `NoDepositTenderTests` |
| Only points credit paths exist | `ReferralGrantPort` is credit-only and idempotent; `loyalty.api`'s package documentation says no port credits an account from a payment and none pays one out | No test, only documentation and `ModularArchitectureTests`' module boundary — which is why new guards 1 and 3 below exist |

### New guards (tests, no new table)

1. **Capability stems.** A structural test over `Capability` asserts that no code
   contains `deposit`, `topup`, `withdraw`, `payout`, `wallet`, `storedvalue` or
   `giftcard` (compared after removing `-` and `_`, so `top-up` and `gift-card` are
   caught), with three named exceptions: the `commercial.wallet.*` pair
   (`COMMERCIAL_WALLET_READ`, `COMMERCIAL_WALLET_MANAGE`), which belong to ADR 0095's
   tenant wallet, and `courier.payout.authorise` (`COURIER_PAYOUT_AUTHORISE`), which
   authorises the payout of a closed courier-settlement period under ADR 0042: pay
   owed to a courier, no customer funds. A test over code strings cannot tell that
   payout from a points payout, so the exception is by name. The exception list is
   asserted exactly (equal to those three, not a superset), so a new exception is a
   visible diff in the test; a new capability whose code contains a stem needs an
   ADR and an entry in the list in the same diff.
2. **Provider types.** A test over `PaymentProviderType` and the
   `ProviderCategory.PAYMENT` catalogue asserts that no value names a wallet or a
   deposit; Decision 4's first adapter, when it exists, adds its own value and its own
   assertion that it declares `settles_from_balance = false`.
3. **Reason codes.** `LoyaltyLedgerAndSplitTenderTests` (the adjustment cases; no
   `LoyaltyAdjustmentServiceTests` exists) gains a case per listed code accepted, a
   case for `CASH_RECEIVED` refused in enforcing mode, and a case that report-only
   mode accepts it and increments the counter; `LoyaltyOperationsControllerTests`
   gains the refusal mapping (400 `VALIDATION_FAILED`, an `errors[]` entry for
   `reasonCode`, a `detail` that does not echo the code) and the read of the
   effective list in both modes. What would still pass if the guard were broken is
   the refusal alone, so the report-only case reads the counter.
4. **A balance method is never a fiscal path.** `LoyaltyLedgerAndSplitTenderTests`,
   beside `pointsCannotReachAProvider`, inserts a `payments.payment_methods` row with
   `settles_from_balance` true under each of `PARTNER`, `TERMINAL` and `MARKETPLACE`
   and asserts each is refused with `ck_payment_method_balance_is_not_a_fiscal_path`
   named in the exception; a control insert under `OPERATOR` succeeds, so the refusal
   cannot be some other error.
5. **One balance leg per settlement.** The same suite inserts two tenders with
   `settles_from_balance` true into one settlement (sequences 1 and 2, both against
   the points method), asserts the first succeeds, and asserts the second is refused
   with `ux_tender_one_balance_per_settlement` named in the exception.

### The reason-code policy (ADR 0030)

```text
loyalty.adjustment_reason_codes      PolicyKey<AdjustmentReasonPolicy>
  settable scopes   PLATFORM only
  document          { "mode": "REPORT_ONLY" | "ENFORCE", "codes": ["GOODWILL", ...] }
  default           REPORT_ONLY, the five codes above
```

A policy rather than a configuration value because the decision it records ("this
adjustment was permitted under list version N") is a business decision, and ADR 0030
asks a durable decision to persist `policy_id` and `policy_version` with its
business fact. The durable record of this decision is the `loyalty.balance.adjust`
audit fact (class `BUSINESS`), which already carries the reason code in its change
document and gains `policyId` and `policyVersion` there. The `loyalty.entries` row
is not touched: V0042 fixes its columns and the application role may only insert it.

### API (ADR 0031) and capabilities (ADR 0025)

One new read, one behavioural change, no new capability.

**The read.** `GET /api/v1/operations/tenants/{tenantId}/loyalty/adjustment-reason-codes`
on `LoyaltyOperationsController` returns the effective policy,
`{ "mode": "REPORT_ONLY" | "ENFORCE", "codes": ["GOODWILL", ...], "policyVersion": 3 }`.
It needs `loyalty.adjust` at `TENANT` scope, not mutating: only someone who can
adjust needs the list. ADR 0030's resolution read (`ConfigurationController`,
`/api/v1/control-plane/configuration/keys/{code}/resolution`) serves configuration
keys, not policy keys, and needs `PLATFORM_ADMIN`, which no tenant operator holds;
`PolicyKey`, unlike `ConfigurationKey`, has no tenant-visible flag. The policy has
no other way to reach the console. A `PLATFORM`-scope policy has no tenant or
brand dimension, so the answer is the same for every tenant. The adjustment dialog
calls it when it opens. In `REPORT_ONLY`
mode the field stays free text with the list offered as suggestions, so the counter
measures what operators really type; in `ENFORCE` mode it becomes a closed choice.
A new endpoint is additive under ADR 0031, and the published OpenAPI documents and
the generated client types gain it.

**The refusal.** In enforcing mode only,
`POST /api/v1/operations/tenants/{tenantId}/customers/{customerId}/loyalty/adjustments`
answers 400 `VALIDATION_FAILED` with one `errors[]` entry,
`{ "field": "reasonCode", "code": "REASON_CODE_NOT_PERMITTED" }`. 400, not 422:
`ErrorCode` documents `VALIDATION_FAILED` as "the client sent something wrong and
should send something else", which is this case, and reserves the 422 codes
(`UNPROCESSABLE_STATE`, `SECOND_APPROVER_REQUIRED`) for a valid request that the
current state refuses; it is also the answer this endpoint already gives for a zero
amount or a balance below zero. The `detail` does not echo the code, which a person
typed and may contain a name (ADR 0031: no PII in `detail`).

**Compatibility with ADR 0031.** `reasonCode` stays `string` in the OpenAPI
contract, with no `enum`, because a list that changes by policy must not change the
schema; `OpenApiContractTests`, which refuses a changed property type or a dropped
required field, sees nothing. No status code changes. What changes is which values
the server accepts, by policy, as it already does for amounts and balances. ADR
0031's breaking list is about the contract's shape and does not classify that, so
this record rules it compatible within `v1` for three reasons: the report-only
release is the notice; the read lets any client learn the list before it posts; and
the only client in this repository is the operations console, changed in the same
release. If the owner rules it breaking instead, the alternative is a `v2`
adjustment endpoint for one field, which this record does not propose: two parallel
endpoints to move one string. That is the second rider on the reason-code open
input.

The capability is unchanged: `loyalty.adjust` at `TENANT` scope, mutating, with the
ADR 0027 approval above the existing threshold; the read reuses it without the
mutating flag. No capability is added, and the guard test above keeps it that way.

### Data, PII, audit, events

No table, no column, no migration. Nothing here carries personal data. The existing
`loyalty.balance.adjust` audit fact (ADR 0027) is unchanged except for the
`policyId` and `policyVersion` its change document gains (a map, so no schema
change). No event: `LoyaltyBalanceChanged` is untouched and still not a
governed ADR 0032 event.

### Observability

One counter, `loyalty.adjustment.reason_code_unlisted`, labelled by mode
(`REPORT_ONLY` or `ENFORCE`) and nothing else: not by reason code, because a code is
typed by a person today and may contain anything including a name (ADR 0029 keeps
personal data out of metrics), and not by tenant, customer or amount. This record
specifies no log line, and none may carry the code either. *Which* codes operators
used is read from the existing `loyalty.balance.adjust` audit facts, which already
hold it under the ADR 0027 redaction rules. The counter is what the report-only
release is for: how often an operator typed something off the list.

### Decision 4's shape, for the day it is built (specification, not a plan)

```text
installations   category PAYMENT, provider_type <new PaymentProviderType value>
                capabilities  PAYMENT_PRESENT, PAYMENT_QUERY, PAYMENT_REVERSE if outbound
payment_methods code <e.g. TENANT_WALLET_X>, responsibility PARTNER,
                settles_from_balance = false
tenders         payment_intent_id NOT NULL  (ck_tender_balance_has_no_intent unaffected)
balance         never stored; read live through PAYMENT_QUERY, or not shown
top-up          not a HorecaOS feature: the customer funds it in the provider's app
refund          ADR 0048 bookkeeping against the provider's cabinet
fiscal          the sale is fiscalized by the tenant's own account when it is spent,
                as whatever ADR 0166 (Proposed) decides for the tenant-issued
                receipt; what a prepayment is for fiscal purposes is counsel's
```

Provider credentials are ADR 0028 secret references per legal entity, as ADR 0038
requires of every `PARTNER` method.

### If an authorisation is obtained (what the superseding ADR must decide first)

This list is the reason Decision 6 can say "a new ADR" and mean it. Before any table:

1. **Where the ledger lives.** A recommendation to the superseding ADR, which
   decides: a separate module with its own schema rather than a second account type
   in `loyalty`. ADR 0046 allows the second ("a `DEPOSIT` account type can join the
   ledger") and this record does not forbid it; it recommends against, because
   points are non-withdrawable, non-transferable and valueless outside the
   platform, and a deposit is the opposite on all three. The closed `entry_type`
   set, the `SELECT, INSERT` grant and the three not-money constraints exist to
   keep the two from being mixed; an in-ledger `DEPOSIT` would have to replace each
   of them with something that still does.
2. **The registry row and its constraints.** A `CUSTOMER_DEPOSIT` method with
   `settles_from_balance` true would need `ck_payment_method_balance_is_not_a_fiscal_path`
   and `ux_tender_one_balance_per_settlement` reconsidered, because a deposit plus
   points is two balance legs in one settlement.
3. **Safeguarding and the liability report.** Where the money sits, who signs the
   monthly funded-balance liability report, what happens on insolvency.
4. **Identification and limits.** Customer identification thresholds, per-customer
   and per-day limits, and the monitoring the authorisation requires.
5. **Terms, expiry, closure, unclaimed balances.** None of ADR 0046's forfeiture
   rules may be copied: closing a funded account pays out.
6. **Fiscal treatment of a prepayment.** What the tax rules call money taken before
   the sale, and what the receipt at spend must say, read against whatever ADR 0166
   has decided for the tenant-issued receipt by then (it is Proposed today).
7. **Offboarding.** What becomes of customers' balances when a tenant leaves, which
   ADR 0046 left to legal for points and which is a hard obligation for money.

## Rollout and rollback

One release: the guard tests, the report-only reason-code policy and its counter, the
read of the effective list and the dialog that uses it, and the copy change on the two
console notices. The next release, after the counter has been read against real
tenants, flips the policy to enforcing. There is no migration and no data to move.
Rollback is setting the policy back to `REPORT_ONLY` (a platform write, audited),
which also returns the dialog to free text with suggestions, and reverting the copy;
nothing else was changed, so nothing else needs undoing.

## Implementation checklist

- [ ] Owner accepts the record, or answers the open inputs.
- [ ] Amend the IA (rows 5.2, 6.3 and 8.1) to strike the deposit balance, deposit
      accounts and the deposit leg of the split tender, and the sentence "Loyalty spend
      and deposit are payment methods, not discounts" to read points only, the way IA
      row `6.7` was struck on 2026-09-11. The gap map has no `DECLINED` status
      (`operations-gap-map.md`, batch 16 note); `5.2f`, `6.3a` and `X.1`'s deposit clause
      leave the blocked count when their IA rows are struck, and the integrator
      re-statuses them in the next audit. This record does not edit the gap map.
- [ ] Add the declined capability to `docs/delever-parity-matrix.md`'s "Deliberately
      not building" table with the Decision 8 sentence.
- [ ] Capability-stem test (with its three named exceptions, asserted exactly) and
      provider-type test (new guards 1 and 2).
- [ ] `loyalty.adjustment_reason_codes` policy key, report-only default, the counter,
      and `policyId` and `policyVersion` in the `loyalty.balance.adjust` change
      document.
- [ ] The read `GET .../loyalty/adjustment-reason-codes`, the regenerated OpenAPI
      documents and client types (`reasonCode` stays `string`), and the adjustment
      dialog: free text with the list as suggestions in `REPORT_ONLY`, a closed
      choice in `ENFORCE`.
- [ ] Reason-code tests (new guard 3) in `LoyaltyLedgerAndSplitTenderTests` and
      `LoyaltyOperationsControllerTests`, the read included.
- [ ] Guards 4 and 5 in `LoyaltyLedgerAndSplitTenderTests`, beside
      `pointsCannotReachAProvider`; and tighten that test to insert a real payment
      intent and assert `ck_tender_balance_has_no_intent` is the constraint named.
- [ ] Reword `marketing.loyalty.deposit.*` and `customers.cashback.depositNotBuilt` in
      ru, uz-Latn and en to "not offered", with the Decision 8 sentence.
- [ ] Add the refund-without-a-source rule (Decision 5) to ADR 0048's operator
      runbook under `docs/runbooks/` when that runbook is written (it does not exist
      yet).
- [ ] After one release of report-only data: flip the policy to enforcing.
- [ ] Note the legacy-deposit rule (Decision 7) in `docs/migration-plan.md`.

## Exit criteria

An operator opening the loyalty page or a customer's pane reads that a deposit is not
offered and why, in their language, with the same sentence a prospect is given. An
adjustment dialog takes its list from `GET .../loyalty/adjustment-reason-codes`. An
adjustment submitted with reason code `CASH_RECEIVED` is refused with 400
`VALIDATION_FAILED` naming `reasonCode` once the policy enforces, and in the release
before it is accepted and counted; the counter shows how often operators typed
something else, and the audit trail shows what. The capability-stem and provider-type
tests pass, and fail when a `DEPOSIT` value, or a capability whose code contains a
stem and is not one of the three named exceptions, is added (shown once by adding one
and watching the build go red). Guards 4 and 5 pass, and each goes red when its
constraint is dropped on a scratch database (shown once). The next gap-map audit
carries no `BLOCKED` row whose blocker is "may HorecaOS hold customer funds".

## References

- ADR 0007 (provider route machinery and contract tests), ADR 0013 (payment, refund),
  ADR 0018 (promo codes), ADR 0024 and ADR 0055 (legacy migration, greenfield launch),
  ADR 0025, ADR 0026, ADR 0027, ADR 0028, ADR 0029, ADR 0030, ADR 0031, ADR 0032,
  ADR 0038 (merchant accounts are the tenant's own), ADR 0042 (courier settlement
  payout; a named exception to guard 1), ADR 0046 (loyalty is points only; "What would bring
  stored value back"; its Alternatives table), ADR 0048 (refunds as bookkeeping; a
  future discount is not money), ADR 0067 (referral credit), ADR 0095 (the tenant's
  wallet), ADR 0140 (promotions), ADR 0166 (fiscal agent; Proposed)
- `platform/docs/operations-gap-map.md` rows `5.2f`, `6.3a`, `X.1` of §8
- `platform/docs/frontend-information-architecture.md` rows 5.2, 6.3, 8.1
- `platform/docs/frontend-and-parity-plan.md` (the blocked table: ADR 0046, "Legal")
- `platform/docs/operations-spec/finance.md` §1 (the `payment[]` array)
- `platform/docs/delever-parity-matrix.md` (Кешбэк and Депозит as payment types;
  "Deliberately not building")
- `V0042`, `V0079`, `V0211`; `EntryType`, `PaymentMethod`, `CheckoutSettlementPlanner`,
  `OperationsPaymentController`, `LoyaltyOperationsController`,
  `LoyaltyAdjustmentService`, `ReferralGrantPort`, `loyalty/package-info.java`,
  `ErrorCode`, `Capability`, `OpenApiContractTests`;
  `NoDepositTenderTests`, `LoyaltyLedgerAndSplitTenderTests`
- `frontend/operations/src/app/features/marketing/loyalty/loyalty-page.html`,
  `frontend/operations/src/app/features/customers/customer-detail-pane.ts`
