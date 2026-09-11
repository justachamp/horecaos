# ADR 0096: An issued statement goes to an e-invoicing operator

- Decision status: Proposed
- Implementation status: Not started — statements export as CSV only
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: the platform owner decided on 2026-09-11 that issued statements go to the accountant as CSV and to the Didox and Faktura.uz e-invoicing operators; the structure below was proposed by Claude on that answer; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0007, ADR 0026, ADR 0028, ADR 0088
- Supersedes / Superseded by: —
- Open inputs: HorecaOS's accounts and API access with Didox and with Faktura.uz, and each operator's API documentation (finance, operations); the product classification code and VAT rate for each statement line kind (finance); which operator each tenant receives on, where a tenant uses only one (finance)

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
- [ ] Port and the sent-document record
- [ ] Didox adapter
- [ ] Faktura.uz adapter
- [ ] Control-plane send action and state

## Exit criteria

An issued statement sent to each operator appears there as a draft invoice to
the tenant's legal entity, and its state is shown in the control plane.

## References

- ADR 0088
