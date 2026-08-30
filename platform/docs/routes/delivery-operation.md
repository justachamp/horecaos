# Route descriptor: `delivery.operation.v1`

Required by ADR 0007. A production route may not ship without one of these.

| Field | Value |
|---|---|
| Route IDs | `delivery.operation.v1`, `delivery.reconcile.v1`, `delivery.operation.dead-letter` |
| Version | 1 |
| Owning module | `integration` (adapters), commanded by `fulfillment` |
| Owner | Ayubkhon Abbosov (platform architecture) |
| Input contract | `DeliveryOperation` v1 (in-process command record), built either by `CamelShipmentBookingPort` from an ADR 0014 booking or by `ShipmentReconciliationHandler` from `ShipmentReconciliationRequested` v1 |
| Output contract | `ProviderOutcome` v1 to a synchronous caller; `ShipmentOutcomeReconciled` v1 on `fulfillment.events` for a command that arrived over Kafka |
| Source | `direct:delivery.operation`, reached synchronously from `CamelShipmentBookingPort` and asynchronously from `ShipmentReconciliationHandler` on the `fulfillment.commands` topic |
| Destination | Courier partner HTTPS API, selected by ADR 0026 binding |
| Service identity | Per-installation credential, ADR 0026 |
| Secret reference type | `horecaos:{env}:provider_delivery:{owner}:{id}` (ADR 0028) |
| Connect timeout | 5s |
| Total timeout | 20s per call |
| Retry classification | `RETRYABLE` only; returned to the caller with a backoff, not looped in-route |
| Idempotency key | `DeliveryOperation.commandId`, stable across retries |
| Circuit breaker | Sliding window 20, minimum 10 calls, 50% failure rate, 30s open, 3 half-open calls |
| Dead-letter destination | `delivery.operation.dead-letter` → ADR 0006 failure operations |
| PII classification | Recipient name, phone, and address — personal data under ADR 0029. Bodies are never logged |
| Expected volume | Pilot: under 500 operations/day/tenant |
| SLO | p95 under 3s for quote; under 8s for create |
| Runbook | `docs/routes/delivery-operation.md#runbook` |
| Dashboard | Metric `horecaos.delivery.route`, tagged `event`, `provider`, `capability`, `status` |

## Registered adapters

| Provider type | Partner | Create semantics | Idempotency on create |
|---|---|---|---|
| `yandex-delivery` | Yandex Delivery (Cargo B2B v2) | Two-phase: create is a hold, accept makes it live | `request_id`, documented |
| `noor-delivery` | Noor Delivery v1 | One-phase: create dispatches a courier | `vendor_order_id`, **not documented — unverified** |

POS providers are deliberately absent. Their adapters wait on partner API
documentation and will be added as separate descriptors.

## Outcome policy

| Outcome | What the route does | Why |
|---|---|---|
| `SUCCESS` | Returns the normalized result | — |
| `REJECTED` | Returns as-is, no retry, circuit untouched | A business "no" is the partner working. Retrying produces the same "no" forever while looking like an outage, and counting it would open the circuit on a healthy partner |
| `RETRYABLE` | Returns with a backoff for the caller to re-send under the same command id | Keeps the provider's own idempotency in play on the next attempt |
| `UNCERTAIN` | Queries the partner for the true state. **Never repeats the original call** | On a one-phase partner, repeating a create books a second courier and bills the merchant twice |

An uncertain outcome with no external reference to query by is escalated to a
human as `RECONCILE_MANUAL`. There is no safe automated move: guessing "it
succeeded" drops the order, and guessing "it failed" duplicates it.

## The asynchronous entry point

This route has two callers and they differ in what happens to the result.

`CamelShipmentBookingPort` calls it in-process and receives a `ProviderOutcome`.
That is right for a booking: sourcing has a decision to make about the answer and
it is holding a job lease while it makes it.

`ShipmentReconciliationHandler` is reached by a Kafka command instead, and its
result is written to the ADR 0004 outbox rather than returned. Nothing is
returned because by the time it runs there is nobody to return it to. The
sequence is:

```text
route classifies UNCERTAIN
  → in-exchange status query
    → settled?  yes → the caller's outcome, done
              no  → outbox row: ShipmentReconciliationRequested v1
                    → relay → fulfillment.commands
                      → ADR 0005 inbox (deduplicates on eventId)
                        → direct:delivery.operation, capability QUERY_SHIPMENT
                          → outbox row: ShipmentOutcomeReconciled v1
                            (written in the transaction that marks the
                             inbox record PROCESSED)
```

Four things about it are deliberate and each has a failure behind it.

**The original operation is never re-sent.** Only a status query is. On a
one-phase partner a repeated create is a second courier and a second delivery
fee, which is why `UNCERTAIN` is a separate outcome from `RETRYABLE` at all.

**The command carries no personal data.** A booking command would have to carry
the recipient's name, phone and address to be executable, and ADR 0029 keeps all
three off every topic. A status query needs the partner's own reference and
nothing else, which is part of why this is the operation that could be made
asynchronous first.

**The binding id on the command is a request, not an authority.** The handler
re-resolves the bindings the envelope's tenant may use at that brand and location
and refuses an id that is not among them. A tenant id on a record is
producer-controlled; treating it as proof is how one tenant's command reaches
another tenant's partner account.

**The provider call runs with no transaction open.** `ExternalWorkInboxHandler`
splits the handler into the part that calls out and the part that writes, so a
courier that has stopped answering costs this consumer its thread and not one of
the ten pooled database connections every module shares.

Retry is the ADR 0005 inbox's, not the route's: a second retry policy here would
multiply against it. A query that stays unsettled to the last attempt is emitted
as `resolution: UNRESOLVED` rather than dead-lettered silently, because an
unanswerable case must be a fact somebody can act on.

## Runbook

**Circuit open for one provider.** Only that partner is affected; the other keeps
taking orders. Check the partner's status page, then `horecaos.delivery.route` with
`event=circuit_open` for the onset time. The circuit half-opens by itself after
30s. Do not restart the application to force it closed — that also discards the
failure window that proves whether the partner has recovered.

**`resolution: UNRESOLVED` on `fulfillment.events`.** The platform asked the
partner repeatedly and never got an answer it could trust. Treat it as "we do not
know", never as "it did not happen": call the partner's dispatcher with the
external reference before anybody re-books, because the one case this state
covers is the one where a courier is already on the way.

**Reconciliation commands piling up on `fulfillment.commands`.** Consumer lag on
this topic is work not being done rather than a projection being behind. Check
`horecaos.delivery.reconciliation` with `event=unsettled` for the provider, and the
partner's own status page: a partner whose status endpoint is down produces
exactly this shape.

**`event=binding_refused` on `horecaos.delivery.reconciliation`.** A command named a
binding its tenant may not use. It is refused and no partner is called. One of
these is a binding disabled between the operation and the reconciliation; a run
of them for one tenant is a security question, not an operational one.

**`RECONCILE_MANUAL` in the log.** A create timed out before returning a
reference. The two partners are searched by different values, and getting this
wrong means hunting for something that was never sent:

| Partner | Search by | Field |
|---|---|---|
| Noor | the HorecaOS **order reference** (`DeliveryOperation.request.horecaosReference`) | `vendor_order_id` |
| Yandex | the HorecaOS **command id** (`DeliveryOperation.commandId`) | `request_id` query parameter |

Noor is not sent the command id at all. If an order exists, record its reference
against the shipment. If none exists, the create may be re-sent. Do not re-send
before checking — Noor's `vendor_order_id` is not documented as an idempotency
key, so a blind re-send is a second courier.

**Repeated `PROVIDER_UNAUTHORIZED`.** The gateway already retried once past the
secret cache, so this is not a stale-cache problem. The credential in OpenBao is
wrong or revoked. Rotate it at the partner, update the secret, and only then
re-enable the binding.

**Unmapped stage warnings from `NoorStage`.** Noor returned a stage the table
does not know. Orders in that state read as `UNKNOWN` and will not progress. Add
the stage to `NoorStage` with its correct meaning; do not guess from the name.

## Rollout

Per ADR 0007: this route ships with its bindings disabled. Enable for one test
tenant and one location, run the connection and contract checks, then widen.
Rollback disables the binding — durable state stays in PostgreSQL for
reconciliation.
