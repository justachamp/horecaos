# Route descriptor: `einvoicing.operator-api.v1`

Required by ADR 0007. A production route may not ship without one of these; see
`docs/routes/README.md` for the format. This is the route behind ADR 0096's
e-invoicing operator adapters (Didox and Faktura.uz): the only way HorecaOS code
talks to either operator.

| Field | Value |
|---|---|
| Route IDs | `einvoicing.operator-api.v1`, `einvoicing.operator-api.dead-letter` |
| Version | 1 |
| Owning module | `integration` (route, gateway and both adapters); commanded by `commercial`, which owns the port `EInvoicingOperator`, decides what is invoiced and records what became of it. `commercial` never speaks an operator's protocol and an operator's types never leave `integration.camel.einvoicing` |
| Owner | Ayubkhon Abbosov (platform architecture) |
| Input contract | `EInvoicingApiCall` v1 (in-process command record), built by an `EInvoicingOperator` adapter from the provider-neutral `EInvoiceDocument` / `EInvoiceDocumentReference` |
| Output contract | `ProviderOutcome` v1, which the adapter reads into `EInvoiceSendOutcome` (`Accepted`, `Refused`, `NotSent`, `Uncertain`) or `EInvoiceStateOutcome` (`Known`, `NotFound`, `Unavailable`) |
| Source | `direct:einvoicing.operator-api` |
| Destination | An e-invoicing operator's HTTPS API: Didox (`api-partners.didox.uz`, stage `stage.goodsign.biz`) or Faktura.uz (`api.faktura.uz` for calls, `account.faktura.uz` for the token). Selected by the ADR 0096 platform installation (`commercial.einvoicing_installations`) and bounded by the environment's `egress_allowlist`: the gateway refuses any host that environment does not name. **No account exists with either operator yet**: both installations are seeded unbound (no secret reference, no seller identity, status `DRAFT`), an unbound installation is never called, and the control plane says so |
| Service identity | HorecaOS's own operator account, one per operator (a platform installation, no tenant). The credential is one JSON object of the operator's login fields behind the secret reference (Didox: partner token and password; Faktura.uz: username, password, client id and client secret) |
| Secret reference type | `horecaos:{env}:provider_einvoicing:{owner}:{id}` (ADR 0028), written by an operator with `bao kv put`; the category is platform-owned and not writable through the tenant secret door |
| Connect timeout | 5s |
| Total timeout | `EInvoicingApiCall.timeout`, or 20s when the adapter names none |
| Retry classification | **None in-route, on purpose.** Neither operator documents an idempotency key on creating a document, so a bounded redelivery of a create would be a bounded number of extra invoices to a tenant. The route classifies and returns. The adapter decides: only an outcome that provably wrote nothing (connect failure, open circuit, 429, a refused credential) is `NotSent`; a business refusal is `Refused`; everything else, above all a timeout or reset after the request was written, a 5xx, or a 409, is `Uncertain`, which the platform resolves by **asking** the operator for the document by its number or client reference and never by sending it again. The one repeat an adapter makes is the login after a refused session token, because a refused credential means the operator refused before it acted |
| Idempotency key | None documented by either operator on a create. Our own identifier for the document (the sent-record's id without its dashes) is carried as Faktura.uz's `id` of the invoice and Didox's invoice is found by its number (the statement number, unique across the platform) -- both are for **finding** a document after a lost answer, not for deduplicating a send. A database index (`ux_einvoice_live`) keeps one live document per statement across both operators, so a second send of the same statement is refused before any call is made |
| Circuit breaker | Sliding window 10, minimum 5 calls, 50% failure rate, 30s open, 3 half-open probes, **one breaker per installation** (HorecaOS's account with one operator) so Didox being down never stops Faktura.uz. Only `RETRYABLE` counts as a failure; a business refusal or an unknown outcome is not evidence the operator is down |
| Dead-letter destination | `einvoicing.operator-api.dead-letter` produces an `UNCERTAIN` `UNCLASSIFIED` outcome (not retryable: there is no evidence the operator was untouched), which the adapter turns into `Uncertain` and the platform records on the attempt, which then holds its statement until the state is asked for |
| PII classification | Company identifiers only: taxpayer numbers, company names and registered addresses of the seller (HorecaOS) and the buyer (a tenant's legal entity), and the invoice's lines and amounts. No natural person's data is sent or logged: the body is never logged at any level, the MDC holds the provider type and correlation id only, and an operator's error text is reduced to an allow-listed set of fields with long digit runs masked before it is stored. No signature material is ever sent, received or stored |
| Expected volume | One document per tenant per month, sent by hand: tens a month at pilot scale, and a state poll every 15 minutes for the few documents not yet signed, refused or cancelled |
| SLO | A send answers within the 20s total timeout; a state refresh likewise. A document's state is at most one sweep interval (15 minutes) stale |
| Runbook | `docs/routes/einvoicing-operator-api.md#runbook` |
| Dashboard | Metrics `horecaos.einvoicing.route` (tags `event`, `provider`, `operation`, `status`) and `horecaos.einvoicing.circuit.not_closed` -- none labelled by installation, tenant or document |

## What the platform concludes after a send

| Answer | When | What the attempt row does |
|---|---|---|
| `Accepted` | The operator created the draft and named it | `SUBMITTED`, with the operator's document id and the state it reports |
| `Refused` | The operator answered and refused the document on business grounds (a 4xx, an error item in an import) | `FAILED`; nothing is held at the operator; the statement may be sent again |
| `NotSent` | Nothing reached the operator: the account is unbound or the credential refused, the circuit is open, the host is not approved, connect failed, rate limited | `FAILED`; nothing is held at the operator; the statement may be sent again |
| `Uncertain` | The request was written and the answer was lost or unreadable, a 5xx, a 409, an accept with no document id, or the adapter threw | `UNCERTAIN`; the statement stays held; resolved by the state refresh (or the sweep): found, it becomes `SUBMITTED`; not found five minutes after the send, it becomes `FAILED` |

## Runbook

**"HorecaOS has no connected Didox/Faktura.uz account yet" (422 `OPERATOR_NOT_CONNECTED`).**
The installation is unbound. Control plane > E-invoicing shows what it still needs:
`SECRET_REFERENCE` (the operator login is put in the secrets manager with `bao kv put` under a
`provider_einvoicing` reference, and the reference is entered on the account),
`SELLER_TAXPAYER_NUMBER` and `SELLER_NAME`. Then activate it. Nothing is sent before then, and
nothing about an unbound account is guessed.

**An attempt shows `UNCERTAIN`.** The send was written and its answer never recorded. Press
"Refresh state" (or wait for the sweep). Do not send the statement again: that is exactly what the
live-document index refuses, because a second send may create a second invoice. If the operator
holds nothing under the statement's number five minutes after the send, the attempt becomes
`FAILED` (`NOT_FOUND_AT_OPERATOR`) and the statement may be sent again.

**A document stays `DRAFT` at the operator.** That is correct: the platform sends a draft and
never signs. Somebody at HorecaOS signs and sends it from the operator's own product; the next
refresh shows `SENT`, then `SIGNED` once the buyer signs, or `REFUSED`.

**A draft was deleted at the operator to be corrected.** Every classification is provisional until
finance confirms it, so a first draft may carry wrong codes or VAT, and staff delete it in the
operator's product. The next refresh (or the sweep) finds nothing under the document's identifier
and the attempt reads `UNKNOWN` with the status `NOT_FOUND_AT_OPERATOR`: still the statement's live
invoice, so it can be neither sent again nor voided. In Control plane > E-invoicing the attempt
says the operator no longer holds it and offers "Release statement". Releasing takes a reason, is
audited as `commercial.einvoice.released` under `commercial.einvoice.send`, and records the attempt
as `CANCELLED` with the status `RELEASED_BY_STAFF` (never the operator's own word for it); then the
statement can be sent again or voided. Only an attempt the operator itself says it does not hold is
released: a document the operator holds, or reports in a status the adapter cannot read, is not
(422 `NOT_RELEASABLE`), because it may be signed. A signed, refused or cancelled document is final:
refreshing it is refused (422 `SETTLED_AT_OPERATOR`) and no answer moves it.

**An attempt is `FAILED` with `OPERATOR_REJECTED_DOCUMENT` or `PROVIDER_REJECTED`.** The operator
refused the document; the failure text is its reason with long digit runs masked. Fix the cause
(commonly the buyer's taxpayer number is not registered with the operator, or a classification
code is not one the operator knows -- see the line-classification table, whose rows are
provisional until finance confirms them) and send again.

**`OPERATOR_LOGIN_REFUSED`.** The operator refused HorecaOS's login. Rotate the credential at the
operator, `bao kv put` the new value under the same reference, and send again; no identifier changes.

**`horecaos.einvoicing.circuit.not_closed` above zero.** Five or more calls in a window of ten
failed for one account. The breaker half-opens after 30s on its own. Suspending the account
(Control plane > E-invoicing > Suspend) stops all sending through it at once.

**A statement cannot be voided ("has an e-invoice at an operator").** An invoice made from it stands
at the operator, or may. Cancel the invoice in the operator's product, refresh its state until it
reads `CANCELLED` or `REFUSED`, and void the statement then.

## Rollout

Per ADR 0096: per operator, behind its installation. It ships with both installations unbound and
nothing registered to send through, so it does nothing until somebody binds an account and
activates it. Rollback is suspending the installation: no call reaches the operator, documents
already sent keep their last known state, and resuming asks for the current state of each.
