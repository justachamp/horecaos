# The tenant's own fiscal terminal (the port, not a vendor)

**Status:** no vendor adapter exists. This note states what a vendor adapter must do, so that the first one
is judged against a written contract and not against whatever its documentation happens to allow. The vendor
facts (model, protocol, authentication, how a receipt is requested and read back) are an external input: see
[the external-connections runbook](../runbooks/external-connections/w11-fiscal-evidence.md), section 6.

Governing records: [ADR 0166](../adr/partial/0166-the-fiscal-agent-per-settlement-path-and-the-fiscal-evidence-surface.md)
(«Providers»), [ADR 0038](../adr/partial/0038-legal-entities-fiscal-receipts-and-product-classification.md) stage 5,
[ADR 0007](../adr/partial/0007-camel-route-foundation-and-provider-contract-testing.md).

## What the port is

`uz.horecaos.platform.fiscal.api.FiscalTerminalPort`: **`issue`** asks the terminal to issue a sale receipt for the
lines of a cash sale, and **`lookup`** reads back what the terminal did for a key. The seller is the tenant's own legal
entity, the issuer is its own equipment, and HorecaOS is a requester that records what comes back. It never
issues on its own INN and never composes a receipt the equipment did not issue.

Money is whole som, HorecaOS's own minor unit; the adapter converts at its boundary. A marked good cannot be
issued through it (the port carries a count of codes, never a code; ADR 0161).

## The four rules every adapter satisfies

These are `FiscalTerminalPortContract`; the vendor adapter's test class extends it and supplies its own way of
making the vendor misbehave (the other adapters use `ControlledFakeProvider`, an HTTP server that accepts a request and
loses the reply).

| Situation | The answer | Why |
|---|---|---|
| The same idempotency key twice | `Issued`, the **same** receipt | A document has one receipt however often anything asks. The key is `terminal-issue:<documentId>` |
| The request left and the reply is lost, late or unreadable (read timeout, reset, malformed body) | `Uncertain` | The terminal may have issued. Calling it refused would invite a second legal receipt, which can only be corrected, never withdrawn |
| After `Uncertain` | the caller calls **`lookup`**, never `issue` | `lookup` answers `Issued` when a receipt exists for the key, `NotFound` when none does, and issues nothing |
| Nothing left (connection refused, the terminal reports itself offline before the request leaves) | `Unreachable` | Safe to ask again; the document is blocked `TERMINAL_OFFLINE` so the worklist shows which sales wait on a device |
| The terminal says no (closed shift, a line it cannot express) | `Rejected(code)` with a stable HorecaOS code | Permanent for this request; the vendor's vocabulary stays in the adapter |

**Read before write.** `TerminalFiscalizationBridge` looks the key up before it ever issues, the way Click's
read-back precedes `submit_items`: a terminal that already holds a receipt for the document is recorded and
never asked again.

## What the platform does with each answer

| Answer | The document |
|---|---|
| `Issued` | `ISSUED` (reason `TERMINAL_FISCALIZED`), the fiscal sign, receipt number, terminal id and link recorded, the lines kept as sent behind ADR 0029 protection with the whole amount as cash, the terminal named (`fiscal_terminal_id`) |
| `Uncertain` | stays `SUBMITTED`; the lines are kept as the request that left; the next ask finds the receipt by looking |
| `Rejected` | `FAILED` with the refusal recorded |
| `Unreachable` | blocked `TERMINAL_OFFLINE`; nothing is recorded as sent |
| no capable terminal of the selling entity at the location | blocked `NO_FISCAL_PATH` |
| no adapter wired | the claim is released; the document stays `PENDING` and says so |

## What exists in the tree

- `FakeFiscalTerminalAdapter`: honours the contract and has **no** way to fail on demand (ADR 0007 forbids a scenario
  switch in production provider code). Registered under the `local` profile only, so the local stack shows a cash
  sale issued end to end; its receipts are visibly not receipts (`FAKE-` signs, a host no tax authority owns).
- `ControlledFiscalTerminal` (test sources): fails on demand, and is held to the same contract.
- Nothing is registered outside `local`. A deployment with no vendor adapter answers «not wired», and a cash sale at a
  branch with a registered terminal waits, visibly, instead of being given a receipt nobody issued.

## When the first vendor adapter is written

It implements `FiscalTerminalPort`, extends `FiscalTerminalPortContract`, takes its endpoint from the approved provider
environment of the terminal's `provider_binding_id` (ADR 0026; never from tenant configuration), and, if it uses Camel,
arrives with a route descriptor under `docs/routes/` (`RouteDescriptorTests` fails the build otherwise). This note
gains the vendor's contract, read, in the house style of the Click and Payme notes.
