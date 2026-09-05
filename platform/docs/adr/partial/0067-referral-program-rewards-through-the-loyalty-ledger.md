# ADR 0067: Referral program rewards through the loyalty ledger

- Decision status: Accepted
- Implementation status: Partial — `V0153` creates the `referral` schema:
  `programs` (draft/activate/retire, one `ACTIVE` row per brand, exactly
  ADR 0046's accrual-rule and redemption-policy shape), `codes` (one per
  customer per brand), and `redemptions` (one per referee per brand, ever).
  `loyalty.api.ReferralGrantPort`, implemented by `loyalty.application.
  ReferralGrantService`, is the one way a credit reaches the ledger — an
  `ADJUSTMENT` entry plus a lot, idempotent on `(reasonCode, referenceId)`.
  The `referral` module implements program authoring
  (`ReferralProgramAuthoringService`/`ReferralPolicyController`,
  `REFERRAL_POLICY_MANAGE`), code issuance (`ReferralCodeService`, Crockford
  base32 from a CSPRNG), redemption with self-referral and stacking refused
  at the database (`ReferralRedemptionService`), the qualifying-event handler
  (`ReferralQualificationService.onOrderOutcome`, `SELECT ... FOR UPDATE` plus
  a conditional `UPDATE` for replay safety, the referrer cap with a skip
  reason, lazy redemption-window expiry), and reads for both a marketer
  (`ReferralOperationsController`, `REFERRAL_READ`) and a customer
  (`ReferralStorefrontController`, `CustomerOwned`). The operations Marketing
  §6.6 screen authors a program and shows redemptions actually happening;
  website/Telegram acquisition links render there as an honest not-built
  panel. **Two things are not built.** First, nothing calls
  `onOrderOutcome` from a real order-completion event — the same gap ADR 0046
  itself names for `LoyaltyAccrualService.accrue`, which also has no
  production caller. Second, "the referee's first completed order" is a
  structural property of the redemption row (it pays on the first COMPLETED
  event that arrives while the row is still PENDING) rather than a verified
  fact about the referee's full order history: no `ordering.api` port exposes
  a per-customer order count, so a repeat customer who redeems a friend's code
  is not distinguished from a genuinely new one. Both are named in the
  checklist below with what would close them.
- Date proposed: 2026-09-05
- Date decided: 2026-09-05
- Deciders: Ayubkhon Abbosov (platform architecture; tenant-configurable
  reward shape via the loyalty ledger, 2026-09-05); product and finance
  (default amounts, cap, and redemption window — none are proposed here; see
  [Product defaults](#product-defaults-there-are-none)); legal (referral
  program terms, alongside ADR 0046's own open terms-of-use question)
- Depends on: ADR 0046, ADR 0044, ADR 0025, ADR 0027, ADR 0029, ADR 0015,
  ADR 0031
- Supersedes / Superseded by: Resolves ADR 0044's "referral reward mechanics"
  open input, without superseding ADR 0044 — attribution links and the
  referral edge (website `?ref=`, Telegram deep links) remain that ADR's own,
  unbuilt half
- Open inputs: The reward amounts, per-referrer cap, and redemption window a
  tenant sets are business numbers with no platform default (product,
  finance); whether referral program terms need disclosure in the customer
  terms of use, alongside ADR 0046's own open no-cash-value/expiry question
  (legal); whether "first completed order" needs to verify the referee's full
  order history once ordering exposes a per-customer count (product,
  engineering) — see [Alternatives considered](#alternatives-considered).
  **None is structural**: every one is a number, a policy value, or a stated
  gap this record's own tables and services already accommodate.

## Context

ADR 0044 recorded a referral as "an edge — referrer, referee, link, qualifying
event — recorded whether or not a reward exists," and left the reward itself as
an explicit open input: "Both surviving reward shapes already exist in the
model: a coded benefit grant here, or an ADR 0046 points accrual." That ADR
also records that neither the coded-grant machinery nor the referral edge
itself is built — `pricing.benefit_grants` does not exist, and
`marketing.attribution_links` is "independent of the send path" and remains
unbuilt. So this decision starts from an empty schema on both sides of the
question ADR 0044 posed.

`frontend-information-architecture.md` §6.6 gives the referral programme two
different jobs. One is acquisition tracking — website `?ref=` links, Telegram
`startapp` deep links, a guided Mini-App/BotFather setup flow — which is
squarely ADR 0044's `marketing.attribution_links` and stays there. The other
is "referral programme rewards", marked "to be specified from scratch —
Delever's is undocumented." The owner's 2026-09-05 decision answers that
second half only, and answers it in a specific way: **a tenant configures
which reward shape it runs**, not the platform. Both sides rewarded (the
referrer and the new customer each receive points on the new customer's first
completed order), or the referrer only (the new customer receives nothing
extra) — a brand's own choice, authored the same way it already authors its
accrual rate and redemption cap.

The two shapes are not new: ADR 0044 already named both, and ADR 0046's
9 000 basis-point redemption cap and non-transferability rules already bound
what a points credit can do once it lands. What was missing was the mechanism
between them — a code per customer, one redemption per new customer, and the
bookkeeping that decides whether and when a reward fires — and the decision
that a tenant, not a platform default, picks the shape.

**Why this rides on the loyalty ledger rather than a second primitive.**
ADR 0046 is unambiguous that points are the platform's one non-monetary
customer-facing credit, audited, capped, and structurally not money. A
referral reward is the same kind of fact as an accrual: value the platform
extends to a customer for a reason, backed by nothing a customer paid in.
Inventing a second ledger for it would duplicate every property ADR 0046
already built — append-only entries, per-lot expiry, oldest-expiry-first
consumption, the not-money enforcement — for no reason a referral needs that
loyalty does not already have.

## Decision

**A tenant authors a referral program the same way it authors a loyalty
accrual rule: draft, then activate, then retire, one `ACTIVE` row per brand.**
`referral.programs` carries the reward shape (`BOTH_SIDES` or
`REFERRER_ONLY`), the referrer's reward, the referee's reward (zero and
structurally required to be zero under `REFERRER_ONLY`), an optional
per-referrer cap on how many redemptions may ever pay that referrer, how many
days a redeemed code stays open before it lapses unqualified, and the reward
lot's own lifetime. Activating a draft retires whichever program currently
holds the brand, in one transaction, so a brand's live set never holds two —
`ReferralPolicyController` and `ReferralProgramAuthoringService` are the
identical shape as `LoyaltyPolicyController`/`LoyaltyPolicyAuthoringService`.

**A customer holds one code per brand, minted on first use.** Eight characters
of Crockford base32 from a CSPRNG, the same alphabet ADR 0044 specifies for a
coded benefit grant, so the two code surfaces a customer can encounter behave
alike. A code is not personal data and needs no active program to exist; only
redeeming one does.

**A new customer redeems a code at most once, ever, per brand, and never their
own.** `uq_referral_redemption_referee` is a unique index on `(tenant_id,
brand_id, referee_customer_account_id)`, and
`ck_referral_redemption_no_self_referral` refuses a redemption whose referrer
and referee are the same account — both at the database, not only in
`ReferralRedemptionService`. Redeeming resolves whichever program is `ACTIVE`
at that moment and snapshots its reward amounts, its program id and version
onto the redemption row: a later change to the program, or its retirement,
before the referee's first order completes does not move what was promised at
redemption.

**The reward fires on the referee's first order to reach `COMPLETED`, never
on signup, and it fires at most once.** A redemption starts and stays
`PENDING`. `ReferralQualificationService.onOrderOutcome` is the one entry
point a real order-completion listener would call; its first act is to refuse
every status but `COMPLETED`, so a cancelled or rejected order changes
nothing. It locks the redemption row (`SELECT ... FOR UPDATE`) before deciding
anything, so two concurrent deliveries of the same completion fact are
serialised by PostgreSQL rather than both reading `PENDING` and both paying:
the loser sees the row already `REWARDED` (or `EXPIRED`) once the winner
commits. A qualifying event arriving after the redemption's window has closed
expires it instead of paying it, judged at the moment something asks rather
than by a scheduled sweep.

**The credit is an ordinary loyalty ledger entry, minted through one new
narrow port.** `loyalty.api.ReferralGrantPort`, implemented by
`loyalty.application.ReferralGrantService`, is the only way this module (or
any future one) can credit a points account without reaching into loyalty's
internals. It writes an `ADJUSTMENT` entry — the same entry type
`LoyaltyAdjustmentService.clawBack` already uses for a system-authored
movement with no human approval — under a system actor, opens a lot with the
program's own configured lifetime, and is idempotent on
`(reasonCode, referenceId)`: a replayed qualifying event calls it again and is
credited nothing a second time. This does not reopen ADR 0046's decision that
there is no port crediting an account from a payment: a referral credit never
originates from a payment, is bounded by a program a brand chose to run, and
is exactly as auditable as an accrual.

**A referrer past their own cap is skipped; the referee is not.** The cap is
read from the redemption's own snapshotted program (not whatever is `ACTIVE`
now) and checked against how many times that program has already rewarded
that referrer. When it is reached, the referrer's own credit does not fire and
`referral.redemptions.referrer_skip_reason` records why; the referee's credit,
if the program shape pays one, is unaffected — the referee did nothing to be
denied for.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A platform-wide fixed reward shape (always both sides, or always referrer-only) | The owner's 2026-09-05 decision is explicitly that this is the tenant's own choice — different brands run different acquisition economics, and a platform default would be argued with by the first tenant it does not fit | Never; a shape becomes a per-tenant `referral.programs` row precisely so this question never needs a platform answer |
| Model the reward as an ADR 0044 coded benefit grant (a promo code) instead of a loyalty credit | `pricing.benefit_grants` does not exist yet — building the code-minting, verification, and redemption path this needs is real work with no caller today, while the loyalty ledger already exists and already does everything a non-monetary customer credit needs. A promo code also needs its own checkout redemption step, doubling the surfaces a referral reward can land on | `pricing.benefit_grants` ships and a tenant specifically wants a redeemable code (e.g., "10% off your next order") rather than automatic points — that is a different reward shape from either one this ADR covers, and would be a new decision, not an edit to this one |
| Resolve the reward's terms from whichever program is `ACTIVE` at the qualifying event, rather than snapshotting at redemption | A customer redeemed under a stated promise. Reading the terms fresh at the qualifying event means a tenant who lowers the reward, or retires the program, between redemption and the referee's first order silently changes what was promised — the exact "changing tomorrow's rate never restates yesterday's balance" property ADR 0046 already protects for accrual, applied in the other direction here | Never; this is the same snapshot discipline every rule-driven ledger entry in this platform already uses |
| Verify "first completed order" against the referee's full order history via a new `ordering.api` port | No such port exists, and building one is cross-module work — a new interface, a JDBC implementation, and its own tests in `ordering` — that this wave did not undertake. The structural gate (pays on the first `COMPLETED` event to arrive while the redemption is still `PENDING`) is weaker: an existing repeat customer who redeems a friend's code is not distinguished from a genuinely new one | `ordering` exposes a per-customer completed-order count (or an equivalent read), or product reports the weaker gate is being exploited in practice |
| Wire `onOrderOutcome` into the real order-completion pipeline now | No such pipeline exists to wire into: ADR 0046 itself records that `LoyaltyAccrualService.accrue(CompletedOrder)` "has no caller outside that test and no listener on order completion." Building referral's own listener would mean inventing the missing order-completion event infrastructure as a side effect of this ADR, which is `ordering`'s and `CheckoutService`'s decision to make, not this one's | `ordering`/`CheckoutService` publishes a real order-completion fact (an ADR 0032 event, or an in-process listener) — at that point both this service and `LoyaltyAccrualService` gain their production caller from the same wiring |
| A general `LoyaltyGrantPort` any module could call to credit points for an arbitrary reason | Widens the one back door ADR 0046 deliberately did not open. `ReferralGrantPort` is purpose-built and narrow, the same restraint `PointsRedemptionPort` already applies to the debit side — a generic credit port is a bigger surface than the one caller this ADR actually has | A second module needs to credit loyalty for an unrelated reason; extend narrowly again with a second named port, do not widen this one |
| A scheduled sweep that flips a stale `PENDING` redemption to `EXPIRED` on a timer, matching `LoyaltySweeper`'s treatment of lots | A lot actively decays a liability on the books, which is why it is swept. An unqualified `PENDING` redemption costs nothing while it waits — no money is held, no liability accrues — so judging it lazily, at the moment a qualifying event or a read actually asks, is proportionate. A stale `PENDING` row is a slightly wrong "pending" count on a report, not an unswept liability | Reporting needs an accurate "expired" count without waiting for a late qualifying event that may never come |

## Consequences

### Positive

- No second money-like primitive. A referral reward is exactly as auditable,
  capped, and non-transferable as every other point in the ledger, for free,
  because it is the same ledger.
- A tenant genuinely configures the programme it runs — shape, amounts, cap,
  and window — through the identical authoring idiom `LoyaltyPolicyController`
  already established, so a marketer learns one pattern rather than two.
- Self-referral and stacking are refused at the database, not only in a
  service a future change could bypass.
- A replayed or concurrently-delivered qualifying event pays exactly once,
  proven against a real PostgreSQL rather than asserted from a mock.
- The reward's terms are locked at redemption, so a tenant tuning their
  programme never retroactively changes what an already-redeemed code
  promised.

### Negative

- **The reward has no real trigger yet.** `ReferralQualificationService.
  onOrderOutcome` is reachable, tested, and correct, and nothing in production
  calls it — the same gap ADR 0046 already carries for its own accrual. Until
  `ordering`/`CheckoutService` publishes a real order-completion fact, a
  referral reward fires only when something calls this method directly.
- **"First completed order" is a promise about the redemption, not about the
  customer.** A repeat customer who redeems a friend's code after already
  placing fifty orders is paid on their fifty-first, indistinguishable from a
  genuinely new customer, because no port exists to ask "has this customer
  ordered before". This is a real abuse surface this ADR does not close.
- A referrer's cap is enforced against the program snapshotted at redemption,
  which means two redemptions under two different program versions could, in
  principle, be judged against two different cap values for the same
  referrer. Rare — a tenant rarely rewrites its programme mid-flight — but
  possible, and not reconciled into one number anywhere.
- A brand's referral liability is invisible on ADR 0046's own liability
  report: a referral credit is an ordinary `ADJUSTMENT` entry with no marker
  distinguishing it from a support credit, so "how much did referrals cost
  this brand" is a query against `reason_code`, not a report column.
- No expiry-warning notification and no scheduled sweep exist for a stale
  `PENDING` redemption, so a marketer's "pending" count can include
  redemptions that will never qualify and nobody is told to give up on.

### Accepted trade-offs

- Building the mechanism without its production trigger, in the same
  documented state ADR 0046's own accrual service has carried since wave 44.
  The alternative — inventing order-completion event infrastructure as a side
  effect of a referral ADR — reaches into a decision that belongs to
  `ordering`.
- A structural, not a verified, "first order" gate. Closing it needs a
  cross-module read this wave did not build; shipping without it is proposing
  a weaker guarantee honestly rather than not shipping the reward mechanism
  at all.
- No liability-report visibility for referral spend, trading a finance
  reporting convenience for not touching ADR 0046's report in a wave that did
  not audit its other consumers.

## Product defaults: there are none

Unlike ADR 0046, which proposed six numbers as a starting point for product
and finance to confirm, this record proposes none. A brand's `referral.
programs` row has no code default for shape, amounts, cap, or window — a
brand with no `ACTIVE` program runs no referral program at all, and every
number a tenant sets is authored by that tenant, not inherited from a
platform constant. The reasoning is the same ADR 0046 gives for accrual and
redemption: a missing row must never read as permission, and a referral
reward's amount is a business decision a platform default would misrepresent
as engineering's opinion. The operations screen suggests values in its own
draft form (10 000/5 000 points, a 14-day window, a 90-day lot lifetime) for
an operator who has never filled the form in before; nothing in the backend
compiles those numbers in, and drafting is always explicit.

## Specification

### Physical model

```text
referral.programs
  id, tenant_id, brand_id
  reward_shape (BOTH_SIDES | REFERRER_ONLY)
  referrer_reward_minor, referee_reward_minor, reward_currency
  max_rewarded_referrals_per_referrer null
  redemption_window_days, reward_lot_lifetime_days
  status (DRAFT | ACTIVE | RETIRED), version, valid_from, valid_until
  unique ACTIVE per (tenant_id, brand_id)

referral.codes
  id, tenant_id, brand_id, customer_account_id
  code (8-char Crockford base32), status (ACTIVE | DISABLED)
  unique (tenant_id, brand_id, customer_account_id)
  unique (tenant_id, code)

referral.redemptions
  id, tenant_id, brand_id
  code_id, program_id, program_version           -- snapshotted at redemption
  referrer_customer_account_id, referee_customer_account_id
  status (PENDING | REWARDED | EXPIRED | VOIDED)
  redeemed_at, expires_at
  qualifying_order_id null, rewarded_at null
  referrer_reward_minor, referee_reward_minor    -- snapshotted, referee 0 under REFERRER_ONLY
  referrer_entry_id null, referee_entry_id null  -- presence means paid
  referrer_skip_reason null
  unique (tenant_id, brand_id, referee_customer_account_id)  -- one per referee, ever
  check referrer_customer_account_id <> referee_customer_account_id
```

`loyalty.entries` gained no new column and no new `entry_type`: a referral
grant is an `ADJUSTMENT` entry with `reason_code` `REFERRAL_REFERRER_REWARD`
or `REFERRAL_REFEREE_REWARD`, `actor` `referral-program`, and an idempotency
key of `<reasonCode>:<redemptionId>`.

### APIs

```text
GET  /api/v1/operations/tenants/{tenantId}/brands/{brandId}/referrals/programs
POST /api/v1/operations/tenants/{tenantId}/brands/{brandId}/referrals/programs
POST .../programs/{programId}/activate
POST .../programs/{programId}/retire

GET  /api/v1/operations/tenants/{tenantId}/brands/{brandId}/referrals/summary
GET  /api/v1/operations/tenants/{tenantId}/brands/{brandId}/referrals/redemptions

GET  /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/referrals/me
POST /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/referrals/redemptions
```

Operations endpoints declare `REFERRAL_READ` or `REFERRAL_POLICY_MANAGE` at
`BRAND` scope, mirroring `LOYALTY_READ`/`LOYALTY_POLICY_MANAGE`. Storefront
endpoints declare `CustomerOwned`, resolving the caller's own account through
`customers.api.CurrentCustomer` — genuinely reachable by a real customer
principal, unlike ADR 0046's own storefront loyalty endpoints, which still
require a staff capability no customer can hold.

### Events

None published. No consumer outside this module needs to react to a
redemption or a reward today, and ADR 0032 requires a schema and catalogue
entry before a producer ships — inventing one with no reader would be exactly
the "designed for, not built" posture this platform avoids when nothing is
waiting on it. `LoyaltyPointsAccrued`-shaped consumers (a future balance-change
notification, ADR 0046's own still-unbuilt expiry warning) see a referral
credit automatically once they read `loyalty.entries`, because it is an
ordinary entry.

### Data handling

Referrer and referee are opaque `customer_account_id` references everywhere
this crosses a module or an API boundary — no contact value, no name, on a
redemption row, an entry, or a response. A referral code is not personal data
under ADR 0029; the customers behind it are, and nothing here reveals one.

## Testing

Against a real PostgreSQL, for the same reason every ADR 0046 test is: the
properties that matter are properties of a `CHECK` constraint, a unique index,
a row lock, and a conditional `UPDATE`, none of which a mock can stand in for.

- A drafted program does not resolve, and therefore cannot be redeemed
  against, until activated; activating a new one retires the old one; a
  brand's live set never holds two `ACTIVE` programs.
- Self-referral is refused before any row is written, and leaves none behind.
- A customer cannot redeem a second code once they hold one redemption for a
  brand — checked first for a friendly error, and enforced again by the
  database if the check is ever bypassed.
- `BOTH_SIDES` credits both accounts on the referee's first `COMPLETED` order;
  `REFERRER_ONLY` credits only the referrer and opens no account for the
  referee at all.
- A cancelled order and a rejected order both pay out nothing, and the
  redemption is proven still open for a real completion afterwards.
- Three deliveries of the identical completed-order fact credit exactly once,
  asserted against the account's own `balance_minor` and its entry count —
  not merely that the call returned without error.
- A qualifying event arriving after the redemption window closed expires the
  row instead of paying it.
- A referrer past their cap is skipped with a recorded reason while the
  second referee's own reward still lands, asserted against the referrer's
  actual balance, not only the redemption row's flag.
- The operations read side lists every redemption and totals points paid out
  from the same rows the list shows.

## Rollout and rollback

1. Ship the schema, the authoring service, and the operations screen. A
   tenant can already draft and activate a program; nothing pays out because
   nothing calls `onOrderOutcome` in production yet.
2. Wire `ReferralQualificationService.onOrderOutcome` into a real
   order-completion signal once `ordering`/`CheckoutService` has one to offer
   — the same wiring `LoyaltyAccrualService.accrue` is waiting for.
3. Enable for one brand with a conservative reward and a tight cap, verify a
   real redemption pays through the ledger correctly, then widen.
4. Close the "first order" gap if product decides the structural guarantee is
   not enough: add the `ordering.api` read this ADR names as missing.

Rollback retires the affected program (new redemptions are refused; nothing
already `PENDING` or `REWARDED` is touched) and, if needed, removes the
`onOrderOutcome` call site. No row is deleted: a redemption and every ledger
entry it produced are retained as the evidence they are, the same posture
ADR 0046 takes toward disabling accrual.

## Implementation checklist

- [x] `V0153`: `referral.programs`, `referral.codes`, `referral.redemptions`,
      with grants for the application role and no `DELETE` on any of them.
- [x] `loyalty.api.ReferralGrantPort` and `loyalty.application.
      ReferralGrantService` — the one caller into the ledger, idempotent by
      `(reasonCode, referenceId)`.
- [x] `ReferralProgramAuthoringService`/`ReferralPolicyController`:
      draft/activate/retire, `REFERRAL_POLICY_MANAGE`.
- [x] `ReferralCodeService`: one code per customer per brand, CSPRNG
      Crockford base32 with retry on collision.
- [x] `ReferralRedemptionService`: self-referral and stacking refused, terms
      snapshotted at redemption.
- [x] `ReferralQualificationService`: completes-only gate, row-locked replay
      safety, referrer cap with skip reason, lazy window expiry.
- [x] `ReferralOperationsController`/`ReferralQueryService` (`REFERRAL_READ`)
      and `ReferralStorefrontController` (`CustomerOwned`).
- [x] Operations Marketing §6.6 screen: program authoring plus the
      redemptions/summary read side, with an honest not-built panel for
      acquisition links.
- [x] Correct ADR 0044's "referral reward mechanics" open input to record
      that this ADR resolves it, leaving the attribution-link half of §6.6
      as that ADR's own remaining, unbuilt work.
- [ ] Wire `onOrderOutcome` to a real order-completion event or listener.
      **Not built** — no such production signal exists yet for this or for
      `LoyaltyAccrualService.accrue` to consume.
- [ ] A per-customer completed-order count from `ordering.api`, so "first
      completed order" can be verified against a referee's full history
      rather than assumed from the redemption row's own state. **Not built.**
- [ ] Website `?ref=` links, Telegram `startapp` deep links, and the guided
      Mini-App/BotFather setup flow. **Not built, and not this ADR's to
      build** — see ADR 0044's own `marketing.attribution_links` checklist
      item.
- [ ] An expiry-warning notification or a scheduled sweep for a stale
      `PENDING` redemption. **Not built** — expiry is judged lazily, at the
      moment a qualifying event or a read asks.
- [ ] Product, finance, and legal confirmation of default amounts, cap,
      window, and referral-specific terms-of-use language. **Not built** —
      no defaults are proposed; see
      [Product defaults](#product-defaults-there-are-none).

## Exit criteria

A tenant can draft and activate a referral program naming which shape it
runs, the amounts, an optional per-referrer cap, and a redemption window. A
customer can obtain their own code and a friend can redeem it once, never
their own. A referred customer's first order to reach `COMPLETED` credits the
referrer (and the referee, under `BOTH_SIDES`) through the ordinary loyalty
ledger, exactly once, even when the completion fact is delivered more than
once or concurrently. A cancelled or rejected order credits nothing. A
redemption whose window has closed before a qualifying order arrives expires
rather than pays. A referrer past their own cap is skipped, visibly, without
affecting the referee's own reward. And a marketer can see every redemption a
brand's program has produced, and what it has paid out, from the same rows a
customer's own history is drawn from.

## References

- [ADR 0046](../partial/0046-loyalty-points-and-split-tender.md) — the
  loyalty ledger this reward rides on: `ADJUSTMENT` entries, lots, and the
  not-money constraints this ADR inherits rather than restates.
- [ADR 0044](../partial/0044-marketing-campaigns-audiences-and-engagement.md)
  — the referral edge, attribution links, and the "referral reward
  mechanics" open input this ADR resolves.
- [ADR 0025](../built/0025-fine-grained-authorization-and-capability-model.md)
  — `REFERRAL_READ` and `REFERRAL_POLICY_MANAGE`.
- [ADR 0027](../partial/0027-audit-evidence-and-approval-model.md) — why a
  referral credit needs no separate audit fact: the ledger entry is the
  evidence.
- [ADR 0029](../partial/0029-pii-protection-envelope-encryption-and-key-rotation.md)
  — why a redemption carries account references and nothing personal.
- [ADR 0015](../partial/0015-customer-accounts-cross-brand-identity-and-consent.md)
  — the customer account a code and a redemption are keyed on.
- [ADR 0031](../built/0031-http-api-conventions.md) — the capability, scope,
  and idempotency conventions every endpoint here follows.
