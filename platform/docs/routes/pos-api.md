# Route descriptor: `pos.api.v1`

Required by ADR 0007. A production route may not ship without one of these; see
`docs/routes/README.md` for the format.

| Field | Value |
|---|---|
| Route IDs | `pos.api.v1`, `pos.api.dead-letter` |
| Version | 1 |
| Owning module | `integration` (route and gateway), commanded by `pos` |
| Owner | Ayubkhon Abbosov (platform architecture) |
| Input contract | `PosApiCall` v1 (in-process command record) |
| Output contract | `ProviderOutcome` v1 |
| Source | `direct:pos.api` |
| Destination | Point-of-sale vendor API over HTTPS, selected by ADR 0026 binding |
| Service identity | Per-installation vendor credential, ADR 0026 |
| Secret reference type | `horecaos:{env}:provider_pos:{owner}:{id}` (ADR 0028) |
| Connect timeout | 5s |
| Total timeout | `PosApiCall.timeout`, or 25s when the adapter names none |
| Retry classification | **None in-route.** The route classifies by `PosApiCall.effect` and returns; the `pos` module, and usually a person, decides whether to send again |
| Idempotency key | **None.** The implemented POS has no idempotency mechanism of any kind — its documentation does not contain the word |
| Circuit breaker | Sliding window 20, minimum 10 calls, 50% failure rate, 30s open, 3 half-open probes, one breaker per provider type |
| Dead-letter destination | `pos.api.dead-letter` → an `UNCERTAIN` outcome for the adapter, then ADR 0006 failure operations |
| PII classification | An order export carries the customer's name, phone, and delivery address — personal data under ADR 0029. Bodies are never logged, and `PosApiCall.toString` omits the body and the authorization function |
| Expected volume | Pilot: under 3,000 calls/day/tenant, concentrated in two service peaks |
| SLO | p95 under 5s for an order export; under 2s for a catalog read |
| Runbook | `docs/routes/pos-api.md#runbook` |
| Dashboard | Metric `horecaos.pos.route`, tagged `event`, `provider`, `operation`, `effect`, `status` |

## Why this route has neither a retry nor a reconcile branch

**No redelivery**, for the reason ADR 0007's rule 7 gives: redelivery is safe only
under a proven idempotency key, and this vendor has none. A bounded redelivery on
an order export is a bounded number of extra dinners cooked.

**No reconcile branch either**, unlike the delivery route. Reconciling a POS
export means searching the day's orders and comparing line composition, which is
business judgement about whether two baskets are the same basket. Putting that in
route DSL would be exactly the coupling ADR 0007 exists to prevent, so the
uncertain outcome goes back to the `pos` module, where the decision can be shown
to a human.

## Outcome policy

| Outcome | What the route does | Why |
|---|---|---|
| `SUCCESS` | Returns the normalized result | — |
| `REJECTED` | Returns as-is, no retry, circuit untouched | A till refusing an item that is 86'd is the till working |
| `RETRYABLE` | Returns with the classification | Whether a re-send is safe depends on `PosApiCall.effect`, which the caller holds |
| `UNCERTAIN` | Returns for the `pos` module to resolve | On a read-only call this is harmless; on an export it means a person compares the ticket in the kitchen against the order |

## Runbook

**Circuit open for one vendor.** Only that vendor's restaurants are affected.
Check `horecaos.pos.route` with `event=circuit_open` for the onset. The breaker
half-opens after 30s on its own. While it is open, the restaurant takes tickets
the way it did before the integration existed; say so when you call them, because
the useful instruction is operational, not technical.

**`UNCERTAIN` on an order export.** The order may or may not be on the till. Call
the location and ask whether the ticket printed before re-sending anything. There
is no key that would make a second export safe, which is why this decision is a
phone call rather than a button.

**Repeated `PROVIDER_AUTHENTICATION`.** The gateway already retried once past the
secret cache. The credential is wrong or revoked at the vendor. Rotate it there,
update the secret, then re-enable the binding.

## Rollout

Per ADR 0007: ships with its bindings disabled, enabled for one test tenant and
one location after the connection and contract checks, then widened. Rollback
disables the binding; durable state stays in PostgreSQL for reconciliation.
