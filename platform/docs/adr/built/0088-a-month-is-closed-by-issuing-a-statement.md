# ADR 0088: A month is closed by issuing a statement

- Decision status: Proposed
- Implementation status: Built — V0202's `commercial.statements` and `commercial.statement_lines` with their freeze triggers, `StatementService`, `CommercialStatementController` with CSV export, tested against the migrated schema in `ModulesStatementsAndArrearsTests` and `CommercialStatementCsvTests`; the control-plane statements screen. The prepaid wallet is not built: how tenants pay and how money held in advance is taxed are not decided
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-10 to finish the control plane's remaining waves; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0021, ADR 0027, ADR 0087
- Supersedes / Superseded by: —
- Open inputs: tax treatment of a subscription and of prepaid credit, and the invoicing system a statement is exported to (finance) — the wallet waits on both

## Context

ADR 0021's first slice promised invoice export evidence and stopped short of
it: its status line recorded no period close and no export. The usage ledger
could say what a tenant consumed, but nothing put the plan, the modules and
the overage for a month together, fixed them, and handed them to the people
who invoice. IA 5.5 also names a prepaid wallet; ADR 0021 keeps every payment
path out of this module, and its trigger for automating billing, approved tax
and invoicing requirements, has not fired.

## Decision

A statement is one calendar month, in the tenant's timezone, of what it owes
before tax.

1. **Lines.** The plan version in force that month, charged in the months its
   billing period starts; each module live during the month on its own unit;
   and usage beyond what the plan includes, for every key the plan prices
   overage on, with an override live at month end taking the plan's place.
   A month wholly in trial bills the plan at zero.
2. **Reproducible.** Standing counts are read from the ledger as they stood
   when the month closed, so computing the same month twice gives the same
   lines. Any month can be previewed.
3. **Issued once.** A month that has ended is issued by HorecaOS staff with a
   reason, numbered, and frozen at the database. One standing statement per
   tenant and month; a wrong one is voided with a reason and issued again.
4. **Exported.** An issued statement downloads as CSV for the accounting
   system; a cell that a spreadsheet would read as a formula is written as
   text.
5. **Wallet not built.** No balance, top-up or credit expiry is recorded until
   finance decides how tenants pay and how prepaid money is taxed. The screen
   says so.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Issue statements automatically at month end | ADR 0021 keeps billing manual until tax and invoicing are approved; a person closing the month is honest about that | Those requirements are approved |
| Store only the total | A disputed line could not be traced to its terms | Never |
| Build the prepaid wallet now | It holds tenants' money, which ADR 0021 keeps out of this module and ADR 0013 owns, with its tax treatment undecided | Finance decides how tenants pay |
| Prorate part months | Nothing in ADR 0021 prorates, and a statement that disagreed with the entitlement rules would be wrong in a new way | Plans are sold with proration |

## Consequences

### Positive

- The month an invoice is made from is fixed and numbered, with every line traceable.
- Finance can close a month from the console instead of from a spreadsheet.

### Negative

- Statements are issued by hand, tenant by tenant.
- A plan change mid-month bills the plan in force at month end only.

### Accepted trade-offs

- A statement is not a tax document; tax is added where the invoice is made.

## Specification

- `GET /control-plane/tenants/{tenantId}/statements`, `/statements/draft?periodKey=yyyy-MM`,
  `/statements/{id}`, `/statements/{id}/export` (`commercial.usage.read`).
- `POST /platform-admin/commercial/tenants/{tenantId}/statements` and
  `/statements/{id}/void` (`commercial.statement.issue`, HorecaOS staff only).
- V0202: `commercial.statements` with `ux_statement_issued_period` on
  `(tenant_id, period_key) WHERE status = 'ISSUED'`; `commercial.statement_lines`.

## Rollout and rollback

Additive. Dropping the tables removes issued statements; export them first.

## Implementation checklist

- [x] Tables, triggers, service, controller, CSV, tests
- [x] Statements screen with the wallet's state stated

## Exit criteria

A month with a plan, a per-branch module and overage issues once with those
lines, refuses a second issue, and issues again after being voided.

## References

- `docs/frontend-information-architecture.md` §5.5
