# ADR 0095: A tenant's wallet keeps paid money and bonus money apart

- Decision status: Proposed
- Implementation status: Not started — nothing records a payment, a balance or a bonus yet; statements are issued and exported only
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

- `commercial.wallet_entries`: tenant, money kind, entry type (`TOP_UP`,
  `DEPOSIT`, `BONUS_GRANT`, `BONUS_EXPIRY`, `STATEMENT_PAYMENT`,
  `ADJUSTMENT`, `REFUND`), signed amount in minor units, currency, the
  statement or grant it concerns, the external reference, reason, who
  recorded it and who approved it. Update and delete refused by trigger.
- A bonus grant's remainder is the sum of the entries naming it; spends name
  the grant they draw on, one entry per grant.
- `commercial.tenant_billing`: the tenant's payment method, and the card
  token reference when it is `CARD` (a reference, never a card number).
- Approval actions for `WALLET_ADJUSTMENT`, `WALLET_BONUS_GRANT` and
  `WALLET_REFUND`, fail-closed, with a seeded platform policy.
- Control plane: the wallet's two balances and ledger on the invoices and
  wallet screen, recording a transfer, proposing a change, and each
  statement's paid and due amounts.

## Rollout and rollback

Additive. Existing statements stay unpaid until a payment is recorded against
them. Rolling back leaves the ledger in place and unread.

## Implementation checklist

- [ ] Ledger, grants, payment method, triggers and grants
- [ ] Statement payment at issue, and later money paying the oldest open statement
- [ ] Two-person manual changes through the approval model
- [ ] Bonus expiry sweep
- [ ] Deposit as a paid top-up; the statement stops billing it
- [ ] Card charging behind a port, with the adapter once a merchant account exists
- [ ] Control-plane wallet and payment screens

## Exit criteria

A tenant granted 200 000 of bonus and paying 1 000 000 by transfer has a
1 200 000 statement paid in full, bonus first, with a ledger that sums to
each balance; a refund of paid money moves nothing until a second person
approves it.

## References

- ADR 0088, ADR 0093, ADR 0027
- CLAUDE.md, on a stored balance beside its entries
