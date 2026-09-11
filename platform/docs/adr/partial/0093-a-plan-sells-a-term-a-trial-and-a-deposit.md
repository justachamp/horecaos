# ADR 0093: A plan sells a term, a trial and a deposit

- Decision status: Accepted
- Implementation status: Partial — V0205's `commercial.plan_version_terms` and `commercial.plan_term_discounts` with their activation trigger, `subscriptions.term_months`; `PlanTerms`, term-aware `PlanCatalogService`, `SubscriptionService` and `StatementService`, and V0206's `EARLY_EXIT` line repaying the discount when a term is left early, tested in `PlanTermsTests` and `ModulesStatementsAndArrearsTests`; the plan catalog's draft form and the entitlements screen's start form. The deposit is still billed as a statement line until the wallet (ADR 0095) holds it
- Date proposed: 2026-09-11
- Date decided: 2026-09-11
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-10 to finish the control plane's remaining waves; accepted by Ayubkhon Abbosov (platform owner) on 2026-09-11, who answered its open inputs the same day
- Depends on: ADR 0021, ADR 0088
- Supersedes / Superseded by: —
- Open inputs: closed 2026-09-11: the activation deposit is paid up front and credited to the first statement, and leaving a term early repays the term discount received

## Context

IA 5.1 names 6- and 12-month term discounts, trials and an activation deposit
as part of a plan. A plan version held one price and a billing period; a trial
was typed per subscription by whoever started it, a longer commitment could
not be offered at a better price, and a deposit had nowhere to live. The
statement, which bills what the terms say, could therefore not bill any of it.

## Decision

1. A plan version can offer, beside its price: a trial length used when a
   subscription starts without naming one; an activation deposit; and a
   discount, in basis points of the monthly price, for committing to a 3, 6,
   12 or 24-month term. Term discounts are offered on a monthly plan only.
2. All of it is drafted with the version and frozen at the database once a
   second person activates it, like the price and the entitlements.
3. A subscription records the term it was sold on (month to month by default),
   and only a term the version offers can be chosen.
4. The statement bills the monthly price less the term's discount, rounded to
   the nearest minor unit.
5. **Deposit.** The activation deposit is paid up front, when the
   subscription starts, and is the tenant's money until the first statement
   is paid from it (ADR 0095's wallet holds it). Until the wallet is built,
   the statement for the month the subscription started bills it as a line.
6. **Leaving early.** A subscription that ends before its term does repays
   the term discount it received: the statement for the month it ended bills
   each charge made at the discounted price at the difference from the full
   price. A plan change is an end and a start today, so it repays too.

Items 5 and 6 are the platform owner's decisions of 2026-09-11.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A separate plan per term | The price list multiplies by every term, and a price change touches all of them | Never |
| Bill the whole term up front | Statements are monthly; billing a year in one month needs invoicing and refund rules that are not decided | Finance asks for up-front billing |
| Prorate the first month after a trial | Nothing in ADR 0021 prorates; a trial ending mid-month bills that month in full | Plans are sold with proration |

## Consequences

### Positive

- A longer commitment can be sold at a better price, and the statement shows the discount on its line.
- A deposit is billed once, with the plan it belongs to.

### Negative

- Leaving a term early is not priced; a subscription can still be cancelled or terminated through the lifecycle.

### Accepted trade-offs

- The discount applies to the plan price only, not to modules or overage.

## Specification

- Drafting a version takes `trialDays`, `activationDepositMinor` and `termDiscounts`;
  plan reads return `terms`.
- Starting a subscription takes `termMonths`; the subscription read returns it.
- V0205: the two terms tables, `subscriptions.term_months`, and `DEPOSIT` among statement line kinds.

## Rollout and rollback

Additive; existing versions sell no terms and existing subscriptions are month to month.

## Implementation checklist

- [x] Tables, trigger, services, statement lines, tests
- [x] Plan catalog and entitlements screens

## Exit criteria

A 12-month subscription on a plan offering 10% off bills its monthly price
less 10%, and its deposit once in its first month.

## References

- `docs/frontend-information-architecture.md` §5.1
