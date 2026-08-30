# A dead letter needs a decision

**Trading-hours alert** for monetary topics; everything else is a morning item.
**Last executed:** never — this is a draft.

Reaching dead letter already means roughly half an hour of automatic retry
failed. Nothing here is going to be fixed by waiting longer, and nothing here is
going to be fixed by a restart.

## 1. What is it?

Everything in this section is the ADR 0006 failure API. Your operator account
needs `INTEGRATION_FAILURE_READ` (ADR 0025); `platform-support` carries it.

```bash
read -rsp 'access token: ' TOKEN; echo
API=https://api.horecaos.uz/api/v1/control-plane/integration/failures
```

The queue, in both directions:

```bash
curl -fsS -H "Authorization: Bearer ${TOKEN}" \
  "${API}/outbox?status=DEAD_LETTER" | jq -r \
  '.items[] | [.id, .eventType, .errorCode, .attemptCount] | @tsv'

curl -fsS -H "Authorization: Bearer ${TOKEN}" \
  "${API}/inbox/<consumer>?status=DEAD_LETTER" | jq -r \
  '.items[] | [.id, .eventType, .errorCode, .attemptCount] | @tsv'
```

The inbox list is **per consumer** — the consumer name is part of the path, not
a filter — because one event reaches several consumers and each holds its own
decision. The probe alert does not name the consumer; it sums a gauge. Take the
name from the `platform-app` log line, or ask each consumer in turn.

Then the one item, in full:

```bash
curl -fsS -H "Authorization: Bearer ${TOKEN}" "${API}/outbox/<eventId>" | jq .

curl -fsS -H "Authorization: Bearer ${TOKEN}" "${API}/inbox/<consumer>/<eventId>" | jq .
```

**Check:** `errorCode`. It is an ADR 0006 `FailureCategory`, and it decides
everything that follows. Also read `topic` and `partitionKey` on an outbox item:
the outbox blocks per topic and partition key, so those two say whether one
order or a whole stream is waiting behind this — the question section 5 answers.

**The payload is not in the response, and that is deliberate.** Working the
failure queue is authority over the message, not over the customer record behind
it (ADR 0029). What you get instead is `aggregateType` and `aggregateId`, and
every business fact you legitimately need is reachable from those through the
API that owns the object, where your own authorization is checked and the read
is audited. Section 3 shows the one case where that matters.

<details>
<summary>If the API itself is down</summary>

Read-only, and only then. Nothing in this runbook is ever fixed with an
`UPDATE`; see section 4.

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -c \
  "SELECT event_id, topic, event_type, error_code, attempt_count, dead_lettered_at
     FROM integration.outbox_events WHERE status = 'DEAD_LETTER'
     ORDER BY dead_lettered_at DESC LIMIT 20"

qc exec -T platform-db psql -U horecaos_migrator -d horecaos -c \
  "SELECT consumer_name, event_id, topic, event_type, last_error_code,
          attempt_count, dead_lettered_at
     FROM integration.inbox_messages WHERE status = 'DEAD_LETTER'
     ORDER BY dead_lettered_at DESC LIMIT 20"
```

This is also the only way to see a `payload`, and reading one is a deliberate
act with a reason, not a step in a runbook.

</details>

## 2. The decision, by category

| Category | What happened | What you do |
|---|---|---|
| `PAYLOAD_INVALID` | The message cannot be parsed or is missing a required fact. It was dead-lettered immediately and correctly | Resolve it with a reason. There is nothing to retry: retrying is what the classification exists to prevent |
| `CONTRACT_UNSUPPORTED` | An event type or version this build does not know | If a newer build understands it, deploy that build and replay. If nothing will ever understand it, resolve with a reason |
| `TRANSIENT_INFRASTRUCTURE` | Ten attempts of backoff all failed | Fix the infrastructure first — usually Kafka or the database — then replay. Replaying into the same outage just spends the attempts again |
| `DOMAIN_REJECTED` | The aggregate refused the transition, normally because it is stale | Resolve with a reason. Replaying a stale transition does not make it current |
| `AUTHORIZATION_REJECTED` | An invalid tenant, scope, or service identity | **Treat as a security event first.** Find out where it came from before resolving anything |
| `UNCERTAIN_EXTERNAL_OUTCOME` | The provider may have acted. This is the expensive one | **Reconcile before you touch it.** Section 3 |

## 3. `UNCERTAIN_EXTERNAL_OUTCOME`: reconcile, never retry

A blind retry here is how a customer gets charged twice.

Start from `aggregateId` on the item from section 1 — for a payment-bearing
event that is the order — and walk to the attempts underneath it:

```bash
qc exec -T platform-db psql -U horecaos_migrator -d horecaos -c \
  "SELECT a.id, a.provider_type, a.external_payment_id, a.status,
          a.requested_amount_minor, a.currency
     FROM payments.payment_attempts a
     JOIN payments.payment_intents i ON i.id = a.intent_id
    WHERE i.order_id = '<aggregateId from section 1>'
    ORDER BY a.created_at"
```

This is a read of the payment record by its own identifier, which is the point:
you arrived at it through an authorized path rather than by reading a name and a
phone number out of a queue entry.

Then ask the provider what it thinks happened, through the provider's own status
query, and record the answer as the reconciliation evidence when you resolve.
ADR 0027 requires that evidence and ADR 0013 requires the compensation path if
the provider did act.

## 4. Retrying or resolving

Same API, same token, plus `INTEGRATION_FAILURE_RETRY` or
`INTEGRATION_FAILURE_RESOLVE`. Both are mutations, so both need a **fresh**
`Idempotency-Key` per attempt (ADR 0031); reusing one replays the first response
and changes nothing, which looks exactly like an action that did nothing.

```bash
curl -fsS -X POST -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  --data '{"reason":"broker recovered at 03:40"}' \
  "${API}/outbox/<eventId>/retry"

curl -fsS -X POST -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: $(uuidgen)" -H 'Content-Type: application/json' \
  --data '{"category":"UNCERTAIN_EXTERNAL_OUTCOME",
           "reason":"provider confirms no charge",
           "evidenceReference":"recon-2026-08-20-17"}' \
  "${API}/outbox/<eventId>/resolve"

unset TOKEN
```

`"outcome":"no_change"` is not an error. It means the item was no longer in a
terminal state — during an incident that usually means a colleague acted first.
Read the item again with section 1's single-item call to see what they did: it
answers whatever the status now is, and after a resolve it carries
`resolvedBy`, `resolutionReason` and `resolutionEvidence`.

Resolving requires a reason, and — for `UNCERTAIN_EXTERNAL_OUTCOME` — the
reconciliation evidence and a second approver (ADR 0027).

When a policy makes that second approver mandatory, the resolve answers **422
`unprocessable-state`** with the approval request in the problem document —
`approvalRequestId` and `approvalStatus` — and the detail names the same
identifier. Nothing was resolved; the item still reads `DEAD_LETTER`. Take that
identifier to a checker: it is a real `audit.approval_requests` row in `PENDING`,
and repeating the resolve while you wait returns the **same** identifier rather
than queueing a second signature.

**The signature is bound to the row the checker read.** An approval given for
one consumer's copy of an event resolves that consumer's row and nothing else —
not another consumer's copy of the same event, and not the outbound event that
shares the id. Each is its own decision and raises its own request.

**Never with SQL.** A hand-edited row has no audit trail, no reason, and no
second approver where ADR 0027 requires one. The API is not bureaucracy here; it
is the only record that the decision was made by a person who knew what they
were deciding.

## 5. Why the aggregate behind it is also stuck

ADR 0006 keeps per-aggregate ordering, so a poisoned message blocks every later
event for the same aggregate. That is deliberate — the alternative is events
applied out of order — and it is why a single non-monetary dead letter can leave
one order frozen for eight hours until the morning digest is read. That cost is
in ADR 0023's negative consequences, stated rather than discovered.
