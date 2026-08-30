# Route descriptor: `notification.send.v1`

Required by ADR 0007. A production route may not ship without one of these; see
`docs/routes/README.md` for the format.

| Field | Value |
|---|---|
| Route IDs | `notification.send.v1`, `notification.status.v1`, `notification.send.dead-letter` |
| Version | 1 |
| Owning module | `integration` (route and gateway), commanded by `notifications` (ADR 0020) |
| Owner | Ayubkhon Abbosov (platform architecture) |
| Input contract | `NotificationSendOperation` v1 (in-process command record) |
| Output contract | `ProviderOutcome` v1 |
| Source | `direct:notification.send`; status queries enter at `direct:notification.status` |
| Destination | Messaging gateway over HTTPS, selected by the ADR 0026 binding that holds the `SEND_SMS` capability |
| Service identity | Per-installation gateway credential, ADR 0026 |
| Secret reference type | `qoida:{env}:provider_notification:{owner}:{id}` (ADR 0028) |
| Connect timeout | 5s |
| Total timeout | 15s per call, the gateway default |
| Retry classification | **None on the send.** ADR 0020 already gives a notification a durable attempt counter and backoff; a second policy in-route would multiply against it. Bounded redelivery (3 attempts, 2s, doubling) exists on `notification.status.v1` only, because a status query has no side effect |
| Idempotency key | `NotificationSendOperation.providerIdempotencyKey`, stable across attempts and also the value the status query asks about |
| Circuit breaker | **None.** The send is already bounded by ADR 0020's per-notification attempt counter, and a shared breaker would silence every brand's messages because one gateway account was throttled. Revisit when a second gateway exists to fail over to |
| Dead-letter destination | `notification.send.dead-letter` → an `UNCERTAIN` outcome for the `notifications` module to reconcile, then ADR 0006 failure operations |
| PII classification | The recipient's phone number and the rendered message body — personal data under ADR 0029. `NotificationDispatch` and `NotificationSendOperation` both override `toString` to omit them, because Camel prints exchange bodies into route logs and error messages by default |
| Expected volume | Pilot: under 5,000 messages/day/tenant |
| SLO | p95 under 4s for a send; under 2s for a status query |
| Runbook | `docs/routes/notification-send.md#runbook` |
| Dashboard | Metric `qoida.notifications.provider.calls`, tagged `channel`, `kind`, `outcome` |

## Why the send never retries and the query does

Twenty-four messages to one customer is what a retry policy in two places
produces: ADR 0020's eight database attempts multiplied by three route
redeliveries. The route therefore calls once and reports, and the module decides
what happens next from its own durable counter.

The status query is the exception, and safely so: it has no side effect, so
repeating it cannot text anybody.

## Outcome policy

| Outcome | What the route does | Why |
|---|---|---|
| `SUCCESS` | Returns the normalized result | — |
| `REJECTED` | Returns as-is | A gateway refusing an unreachable number is the gateway working; retrying produces the same refusal |
| `RETRYABLE` | Returns for ADR 0020's counter to schedule | One backoff, in one place |
| `UNCERTAIN` | Goes to `direct:notification.status` to discover the true state. **Never repeats the send** | A lost response after a send is not proof of nothing sent, and a blind re-send is a second message to a customer who already had one |

## Runbook

**A tenant's messages stop while others continue.** This route has no circuit
breaker by design, so an outage here is per binding. Check the binding's
installation status and the gateway's own account state — a throttled or
suspended gateway account fails every call for that tenant and none for anyone
else.

**Rising `UNCERTAIN` outcomes.** The gateway is accepting sends and losing
replies. Do not re-send. The status query resolves them by
`providerIdempotencyKey`; if the query is also failing, stop dispatching for that
binding rather than accumulating unresolved sends, because every one of them may
already have reached a customer.

**Repeated `PROVIDER_AUTHENTICATION`.** The gateway already retried once past the
secret cache. The credential is wrong or revoked. Rotate it at the gateway,
update the secret, then re-enable the binding.

## Rollout

Per ADR 0007: ships with its bindings disabled, enabled for one test tenant and
one location after the connection and contract checks, then widened. Rollback
disables the binding; queued notifications stay in PostgreSQL under ADR 0020.
