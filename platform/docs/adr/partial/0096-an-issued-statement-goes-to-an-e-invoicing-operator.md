# ADR 0096: An issued statement goes to an e-invoicing operator

- Decision status: Accepted
- Implementation status: Partial — built and tested against fakes, never run against an operator: V0516's platform installations (`commercial.einvoicing_installations`, seeded unbound for Didox and Faktura.uz), the classification code and VAT rate per statement line kind (`commercial.einvoicing_line_classifications`, every row provisional until finance confirms it), and the sent-document record (`commercial.statement_einvoices`, never holding signature material); the `EInvoicingOperator` port with `DidoxEInvoicingOperator` and `FakturaEInvoicingOperator` behind the `einvoicing.operator-api.v1` Camel route (`docs/routes/einvoicing-operator-api.md`, `docs/providers/didox.md`, `docs/providers/faktura-uz.md`); `EInvoicingService`'s send per statement by staff (the attempt is written before the operator is called, one live invoice per statement across both operators, an uncertain send resolved by asking and never by sending again), its state refresh and a fifteen-minute sweep (a signed, refused or cancelled document is final and is not asked again), a staff release of a statement from a draft the operator no longer holds, the audit of each act, `CommercialEInvoicingController` under `commercial.einvoice.send` and `commercial.einvoicing.manage`, and the control plane's E-invoicing screen. Not built: HorecaOS has no account with either operator, so both installations are unbound, the screen says so and nothing has been sent to a real operator; the adapters are written from Faktura.uz's published Swagger and Didox's partner SDK, and each answer shape and status mapping is unverified against a live account; the classification codes, VAT rates and contract reference are engineering's provisional defaults, flagged for finance; state callbacks (the state is polled); signing and cancelling, which stay in the operator's own product
- Date proposed: 2026-09-11
- Date decided: 2026-10-07
- Deciders: the platform owner decided on 2026-09-11 that issued statements go to the accountant as CSV and to the Didox and Faktura.uz e-invoicing operators; the structure below was proposed by Claude on that answer; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0007, ADR 0026, ADR 0028, ADR 0088
- Supersedes / Superseded by: —
- Open inputs: HorecaOS's accounts and API access with Didox and with Faktura.uz, and each operator's API documentation (finance, operations); the product classification code and VAT rate for each statement line kind (finance); which operator each tenant receives on, where a tenant uses only one (finance)

**Decision record, 2026-10-07.** Accepted by Ayubkhon Abbosov (platform owner) with the instruction "lets finish all" over every record still Proposed on this date. Every open input above is closed on the default this record proposes for it; an input that names a person other than the owner, or an external fact (a licence term, a provider capability, a tax treatment, an account that does not exist yet), stays with that owner as written and implementation proceeds without it, marking what waits. Implementation of what this record decides and has not yet built starts in operations batch 19 and 20 (2026-10-07).

## Context

In Uzbekistan an invoice between businesses is an electronic document signed
by both sides through an e-invoicing operator. A CSV the accountant retypes is
what ADR 0088 built; the owner decided on 2026-09-11 that statements should
also reach Didox and Faktura.uz directly. Neither operator's API has been
integrated or documented here, and the platform has no account with either.

## Decision

1. An issued statement can be sent as an electronic invoice to an operator,
   by HorecaOS staff, per statement. HorecaOS is the seller; the tenant's
   legal entity and tax number are the buyer.
2. Each operator is an adapter behind one port (ADR 0007), configured as a
   platform installation whose credentials are secret references (ADR 0026,
   ADR 0028). No operator's types leave its adapter.
3. The platform records what it sent, the operator's document identifier and
   its state as the operator reports it (sent, signed by the buyer, refused),
   and never the document's signature material.
4. CSV stays, for an accountant who works outside both operators.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| CSV only | The owner asked for both operators; retyping is where invoices go wrong | — |
| One operator | Tenants are split between the two | One operator covers every tenant |
| An accounting system (1C) in between | Adds a system HorecaOS does not run to reach the same operators | Finance adopts one |

## Consequences

### Positive

- An issued statement becomes a signed invoice without anybody retyping it.

### Negative

- Two adapters to keep current against two operators' changes.
- Nothing can be built against either operator until HorecaOS has an account
  and the documentation.

### Accepted trade-offs

- Sending is a deliberate act per statement, not automatic at issue, until
  the first months of operator traffic show it is safe.

## Specification

To be written against each operator's documentation: the document mapping,
the line classification codes, and the state callbacks.

## Rollout and rollback

Per operator, behind its installation; removing the installation stops sending.

## Implementation checklist

- [ ] Operator accounts and documentation
- [x] Port and the sent-document record
- [x] Didox adapter (against a fake; no account)
- [x] Faktura.uz adapter (against a fake; no account)
- [x] Control-plane send action and state

## Exit criteria

An issued statement sent to each operator appears there as a draft invoice to
the tenant's legal entity, and its state is shown in the control plane.

## References

- ADR 0088
