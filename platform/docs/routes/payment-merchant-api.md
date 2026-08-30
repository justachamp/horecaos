# Route descriptor: `payment.merchant-api.v1`

Required by ADR 0007. A production route may not ship without one of these; see
`docs/routes/README.md` for the format.

| Field | Value |
|---|---|
| Route IDs | `payment.merchant-api.v1`, `payment.merchant-api.dead-letter` |
| Version | 1 |
| Owning module | `integration` (route and gateway), commanded by `payments` |
| Owner | Ayubkhon Abbosov (platform architecture) |
| Input contract | `MerchantApiCall` v1 (in-process command record) |
| Output contract | `ProviderOutcome` v1 |
| Source | `direct:payment.merchant-api` |
| Destination | Payment provider merchant API over HTTPS, selected by ADR 0026 binding |
| Service identity | Per-installation merchant credential, ADR 0026 |
| Secret reference type | `qoida:{env}:provider_payment:{owner}:{id}` (ADR 0028) |
| Connect timeout | 5s |
| Total timeout | `MerchantApiCall.timeout`, or 20s when the adapter names none |
| Retry classification | **None in-route.** The route classifies and returns; the caller decides whether trying again is safe |
| Idempotency key | **None.** Neither implemented provider offers one on a call that moves money — this is why there is no retry |
| Circuit breaker | Sliding window 20, minimum 10 calls, 50% failure rate, slow call 10s at an 80% rate, 30s open, 3 half-open probes, one breaker per provider type |
| Dead-letter destination | `payment.merchant-api.dead-letter` → an `UNCERTAIN` outcome for the adapter, then ADR 0006 failure operations |
| PII classification | Amount, merchant transaction id, and on some providers a payer phone number — personal data under ADR 0029. `MerchantApiCall.toString` omits the body and the authorization function so neither reaches a log line or an exception message |
| Expected volume | Pilot: under 2,000 calls/day/tenant |
| SLO | p95 under 4s for an invoice create; under 2s for a status query |
| Runbook | `docs/routes/payment-merchant-api.md#runbook` |
| Dashboard | Metric `qoida.payment.route`, tagged `event`, `provider`, `operation`, `status` |

## Why this route is shorter than `delivery.operation.v1`

By one branch, and the missing branch is the point. Delivery retries a retryable
outcome and reconciles an uncertain one inside the route. This route does
neither: it classifies, records, and hands the outcome straight back to the
`payments` module.

**There is no redelivery anywhere on this route.** ADR 0007's rule 7 permits
Camel redelivery only for an operation proven safe under one idempotency key, and
no payment provider in this build offers an idempotency key on any call. A
bounded redelivery on `invoice/create` or `payment/reversal` would therefore be a
bounded number of extra charges on a customer's card. A caller that wants to try
again re-sends the call itself, having first decided from the classification that
trying again is safe.

## Outcome policy

| Outcome | What the route does | Why |
|---|---|---|
| `SUCCESS` | Returns the normalized result | — |
| `REJECTED` | Returns as-is, no retry, circuit untouched | A declined card is the provider working. Counting it as a fault would open the circuit on a healthy provider and stop every other merchant |
| `RETRYABLE` | Returns with the classification. The caller re-sends | Only the caller knows whether its own call was mutating |
| `UNCERTAIN` | Returns for the adapter to resolve **by query**. Never repeats the call | `MerchantApiCall.mutating` is the field that matters here: a mutating call whose response was lost may already have moved money |

## Runbook

**Circuit open for one provider.** Only that provider is affected; the other keeps
taking payments. Check `qoida.payment.route` with `event=circuit_open` for the
onset time and the provider's status channel. The breaker half-opens by itself
after 30s. Do not restart the application to force it closed — a restart also
discards the failure window that proves whether the provider recovered.

**`UNCERTAIN` on a mutating call.** Do not re-send. Query the provider for the
merchant transaction id in the original call, and record what you find against
the payment before anyone decides anything. Re-sending is the specific mistake
this route's shape exists to prevent, and the customer's statement is where it
shows up.

**Repeated `PROVIDER_AUTHENTICATION`.** The gateway already retried once past the
secret cache, so this is not a stale-cache problem: the credential in OpenBao is
wrong or revoked. Rotate it at the provider, update the secret reference's value,
and only then re-enable the binding.

**Slow calls opening the circuit without any errors.** The breaker opens when
four calls in five take longer than 10s, whether or not they eventually succeed.
If `qoida.payment.route` shows successes alongside `circuit_open`, the provider is
up but degraded; treat it as an outage for capacity purposes and tell the
restaurants, because a 10s checkout is an abandoned basket either way. Four in
five, not one in five, because a couple of slow calls is ordinary congestion.

## Rollout

Per ADR 0007: ships with its bindings disabled, enabled for one test tenant and
one location after the connection and contract checks, then widened. Rollback
disables the binding; durable state stays in PostgreSQL for reconciliation.
