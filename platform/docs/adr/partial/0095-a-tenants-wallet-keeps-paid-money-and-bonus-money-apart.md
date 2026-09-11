# ADR 0095: A tenant's wallet keeps paid money and bonus money apart

- Decision status: Proposed
- Implementation status: Partial — V0211's `commercial.wallet_entries` (append-only, UPDATE and DELETE refused by trigger and by GRANT), `commercial.tenant_billing`, `subscriptions.deposit_due_minor` and the three seeded PLATFORM approval policies; `WalletService` with settlement at issue, oldest-open-statement settlement for money arriving later, maker-checker corrections, bonus grants and refunds, the reversal a voided statement writes, and `WalletBonusExpirySweeper`; `JdbcWalletStore`, `CommercialWalletController`, and the statement's deposit line removed in favour of the wallet; thirteen cases in `WalletTests` against the migrated schema; the control plane's Invoices & wallet screen shows both balances, the ledger, live grants, each statement's paid and due, and proposes every manual change. The card charging adapter is absent until a merchant agreement exists — `CardCharger`'s only implementation answers "not configured", so a CARD tenant's remainder stays due exactly as an INVOICE one does
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: the platform owner decided on 2026-09-11 that tenants pay by invoice and bank transfer, from a prepaid wallet or by card; that bonus money HorecaOS grants is kept apart from money a tenant paid, is spent first and lapses on a date set per grant; that paid money never lapses and is refunded when a tenant leaves; that every manual change needs a proposer and a different approver; and that the activation deposit is credited to the first statement. The structure below was proposed by Claude on those answers; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0021, ADR 0027, ADR 0028, ADR 0088, ADR 0093
- Supersedes / Superseded by: —
- Open inputs: how a subscription and prepaid money are taxed (finance) — amounts stay before tax until then; HorecaOS's own Click or Payme merchant account with card-token (recurring) access, for card charging (finance, operations); the bank details an invoice shows (finance)

## Context

ADR 0088 closes a tenant's month with an issued statement and stops there: it
says what is owed and nothing records whether it was paid. IA 5.5 names a
prepaid wallet, and on 2026-09-11 the platform owner decided how tenants pay
HorecaOS: by invoice and bank transfer, from money paid in advance, or by card
charged automatically — all three, per tenant. HorecaOS will also give tenants
money of its own (a launch credit, goodwill after an outage), and finance must
be able to tell that apart from money a tenant actually paid, because only the
second is revenue received and only the second can be refunded.

Money is where the platform's earlier ledgers went wrong: the loyalty ledger
held a stored balance beside its entries, and every defect lived in the gap
between them (see CLAUDE.md). ADR 0021 keeps payment paths out of `commercial`
for tenants' customers; this is HorecaOS billing its own tenants, which is
`commercial`'s business, and the card adapter stays behind a port.

## Decision

1. **One ledger, two kinds of money.** Every tenant has a wallet: an
   append-only ledger in its billing currency. Each entry is `PAID` (money
   the tenant paid HorecaOS) or `BONUS` (money HorecaOS granted). A balance is
   the sum of its entries; none is stored beside them. No entry is edited or
   deleted — the database refuses both.
2. **Money in.** Paid money arrives as a bank transfer recorded by finance
   with the bank's reference, a card charge that succeeded, or the activation
   deposit. Bonus money arrives as a grant with an expiry date.
3. **Statements are paid from the wallet.** When a statement is issued it is
   paid from the wallet at once: bonus first, the grant expiring soonest
   first, then paid money. What remains is due and is collected by the
   tenant's payment method: `INVOICE` waits for a bank transfer, `WALLET`
   waits for a top-up, `CARD` is charged for the remainder. Money that
   arrives later pays the oldest open statement first.
4. **Every manual change has two people.** A correction of either kind of
   money, up or down, a bonus grant, and a refund of paid money are proposed
   with a reason by one person and approved by another through the approval
   model (ADR 0027). Nothing moves until it is approved. Recording a bank
   transfer is not a correction: it is one person's audited act, like issuing
   a statement, and carries the bank reference that proves it.
5. **Bonus lapses; paid money does not.** Each grant's unspent remainder
   lapses on its expiry date with an entry that says so. Paid money never
   lapses; a tenant leaving with some is refunded by an approved refund entry
   naming the payout.
6. **The deposit is paid money.** A subscription that starts with an
   activation deposit (ADR 0093) makes the deposit due; paying it is a paid
   top-up marked as the deposit, and the first statement is paid from it. The
   statement stops billing a deposit line.
7. **Before tax.** The wallet holds amounts as issued and as received. It
   computes no tax until finance states how a subscription and money held in
   advance are taxed.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A stored balance per tenant, updated with each entry | The loyalty ledger's defects all lived between a stored balance and its entries | A balance read is too slow from the entries, which a partial index answers long before |
| One kind of money with a "promotional" flag on the spend | Finance could not tell received revenue from granted credit without replaying every spend | Never; the separation is the point |
| Payment status on the statement row | Issued statements are frozen at the database (ADR 0088); a payment would have to unfreeze one | — |
| Card only | Uzbek businesses pay suppliers by bank transfer against an invoice; card-only would lose most tenants | — |
| The payments module owns it | Payments is tenants' customers paying tenants through the tenants' merchant accounts; this is HorecaOS's own receivable | HorecaOS's billing moves to an external billing system |
| Manual changes by one person with an audit entry | Money moved by one person on their own word is the control a finance review asks for first | Volume makes second approval a bottleneck; then a threshold below which one person suffices |

## Consequences

### Positive

- Finance can say at any moment what each tenant paid, what HorecaOS gave,
  and what is owed, from one ledger that cannot drift from itself.
- A bonus can be granted without it ever being mistaken for a refundable
  balance.

### Negative

- Every manual change waits for a second person, including a small goodwill
  credit.
- Card charging depends on a merchant agreement HorecaOS does not have yet;
  until it does, `CARD` tenants are collected like `INVOICE` ones.
- Without a tax rule, the wallet cannot produce a tax invoice on its own.

### Accepted trade-offs

- A statement paid partly from bonus shows two spends; the statement itself
  stays what was owed, and the ledger explains how it was paid.

## Specification

As built on 2026-09-11.

### Schema (V0211)

- `commercial.wallet_entries`: tenant, money kind (`PAID`/`BONUS`), entry type
  (`TOP_UP`, `DEPOSIT`, `BONUS_GRANT`, `BONUS_EXPIRY`, `STATEMENT_PAYMENT`,
  `STATEMENT_REVERSAL`, `ADJUSTMENT`, `REFUND`), signed amount in minor units,
  currency, the statement or grant it concerns, the external reference, reason,
  who recorded it and who approved it. `BEFORE UPDATE OR DELETE` raises; the
  application role is granted `SELECT, INSERT` and nothing else, so neither
  stop depends on the other.
- A bonus grant's remainder is the sum of the entries naming it. **Every bonus
  entry but the grant itself names its grant** — a spend, the lapse, and a
  correction alike. A bonus belonging to no grant could not be spent (a
  statement draws grant by grant) and would never lapse, so the check
  constraint refuses one.
- `STATEMENT_REVERSAL` is what a voided statement gives back (ADR 0088 voids a
  wrong statement and issues again). The ledger is never reopened, so a draw is
  undone by the opposite entry naming the same statement — and the same grant,
  for bonus money — and the freed money then pays whatever else is open.
- `commercial.tenant_billing`: the tenant's payment method and, at `CARD`, the
  card token reference (a reference, never a card number). It is also the row
  every wallet mutation takes `FOR UPDATE` before writing: the ledger has no
  balance column to protect and a row a reader could lock does not exist until
  the writing transaction commits, so serialising one tenant's writers belongs
  on the billing row, which the application may update anyway.
- `commercial.subscriptions.deposit_due_minor`: set to the plan version's
  activation deposit when the subscription starts, cleared when the deposit is
  recorded as a wallet top-up. **Why a column rather than a ledger entry:** a
  deposit that is owed is not money that has moved, and the wallet holds only
  money that moved. An entry for it would put a negative balance in a ledger
  whose balances are money on hand, and a second table would be a second place
  for the same fact to drift from the subscription it belongs to. It is a
  due-or-not flag on the subscription, and no statement draft reads it.
- Approval policies for `commercial.wallet.adjustment`,
  `commercial.wallet.bonus-grant` and `commercial.wallet.refund`, seeded at
  `PLATFORM` scope and fail-closed (`REQUIRE_CONFIGURED_POLICY`), as V0203
  seeds `tenant.country.change`.

### Behaviour

- Issuing a statement pays it at once: bonus before paid money, the grant
  expiring soonest drawn first, one `STATEMENT_PAYMENT` entry per grant. The
  remainder is due. Money arriving later — a transfer, the deposit, an approved
  upward correction or grant — pays the oldest open statement first.
- A wallet pays statements in its own currency only. There is no rate at which
  a `UZS` wallet could settle a statement priced in another currency, so a plan
  version sold in a second currency leaves its statements to be invoiced.
- Neither money kind goes below zero: a refund is refused above the paid
  balance, and a correction above the paid balance or above the grant's
  remainder. Both are checked under the billing lock and before the approval is
  spent, so a refusal leaves the signature for a retry.
- `WalletBonusExpirySweeper` (hourly, `runOnce()`/`sweepOnce()`) lapses each
  expired grant's unspent remainder with a `BONUS_EXPIRY` entry, one grant per
  short transaction. Its candidate query asks for a remainder rather than for
  the absence of a lapse: a grant whose remainder came back after it expired —
  a statement it had paid was voided — is a candidate again, and would be
  invisible forever under the other question.
- `CardCharger` is a port in `commercial.application`; the only adapter wired,
  `NotConfiguredCardCharger`, answers `NotConfigured`. A CARD tenant is
  therefore collected exactly like an INVOICE one, and the screen says so.

### API

- Reads (`commercial.wallet.read`): `GET /control-plane/tenants/{tenantId}/wallet`,
  `/wallet/ledger` (cursor-paginated), `/wallet/grants`, `/wallet/statements`.
- Writes (`commercial.wallet.manage`, HorecaOS staff only):
  `POST /platform-admin/commercial/tenants/{tenantId}/wallet/transfers`,
  `/wallet/deposit`, `/wallet/adjustments`, `/wallet/bonus-grants`,
  `/wallet/refunds`, `/wallet/payment-method`.
- Control plane: the Invoices & wallet screen carries both balances, the
  ledger, live grants, each statement's paid and due amounts, the payment
  method with its change action, and the sentence that card charging is not
  connected. A proposed change answers that nothing has moved and links to
  Approvals.

## Rollout and rollback

Additive. Existing statements stay unpaid until a payment is recorded against
them. Rolling back leaves the ledger in place and unread.

## Implementation checklist

- [x] Ledger, grants, payment method, triggers and grants
- [x] Statement payment at issue, and later money paying the oldest open statement
- [x] Two-person manual changes through the approval model
- [x] Bonus expiry sweep
- [x] Deposit as a paid top-up; the statement stops billing it
- [x] Card charging behind a port; the adapter itself waits for a merchant account
- [x] Control-plane wallet and payment screens

## Exit criteria

A tenant granted 200 000 of bonus and paying 1 000 000 by transfer has a
1 200 000 statement paid in full, bonus first, with a ledger that sums to
each balance; a refund of paid money moves nothing until a second person
approves it.

## References

- ADR 0088, ADR 0093, ADR 0027
- CLAUDE.md, on a stored balance beside its entries
