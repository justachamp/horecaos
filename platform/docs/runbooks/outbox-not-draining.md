# Order flow stalled — the outbox is not draining

**Night alert.** **Last executed:** never — this is a draft.

```bash
cd /opt/qoida/qoida-platform
alias qc='docker compose -f compose.production.yaml --env-file /etc/qoida/production.env'
```

## Before anything: the rows are safe

They are in PostgreSQL, committed, with the order that produced them. The
platform is **behind, not losing**. Nothing below is urgent enough to justify
touching the database by hand, and the reason this paragraph is first is that at
3am it is the one you need in order to read the rest calmly.

## 1. How far behind, and which direction?

```bash
qc exec -T platform-db psql -U qoida_migrator -d qoida -c \
  "SELECT status, count(*), max(now() - created_at) AS oldest
     FROM integration.outbox_events GROUP BY status ORDER BY 1"
```

**Check:** which status holds the old rows.

- `PENDING` piling up — the relay is not running or cannot publish. Step 2.
- `PUBLISHING` piling up — a worker died holding leases. They expire after five
  minutes and are reclaimed automatically. **Wait five minutes and re-run this
  query before doing anything.**
- `DEAD_LETTER` — this is a different problem:
  [dead-letter-decision.md](dead-letter-decision.md).

This one query is a count across the whole table, which the failure API does not
answer and does not try to. The moment you have a **single event id**, stop using
`psql` and use the ADR 0006 read instead — it is audited, it works from anywhere
you can reach the API, and it does not need a database credential:

```bash
read -rsp 'access token: ' TOKEN; echo
curl -fsS -H "Authorization: Bearer ${TOKEN}" \
  "https://api.qoida.uz/api/v1/control-plane/integration/failures/outbox/<eventId>" | jq .
```

## 2. Is the relay running?

```bash
qc ps platform-app && qc logs --tail 100 platform-app | grep -i outbox
```

**Check:** the relay is a switch, `qoida.messaging.outbox.enabled`. If the
container is running and there is no outbox activity in the log at all, the
switch is off in that container's environment — which is a deploy mistake, not
an incident. Fix the environment and redeploy.

## 3. Will Kafka accept a write?

```bash
qc exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:29092 --list
```

**Check:** a list of topics. If this hangs or errors, Kafka is the problem, and
the relay is doing exactly the right thing by holding rows in PostgreSQL until
it returns.

```bash
qc ps kafka && qc logs --tail 100 kafka && df -h /
```

Kafka's own disk fills the same volume as everything else. If it is full, go to
[disk-filling.md](disk-filling.md).

## 4. Consumer lag, if the alert named lag rather than age

```bash
qc exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:29092 --describe --group qoida-platform
```

**Check:** the `LAG` column. Lag that is falling is a consumer catching up and
needs nothing. Lag that is flat with a live `CONSUMER-ID` is a consumer stuck on
one record — look for the same event id repeating in `platform-app` logs, then
read that consumer's copy of it directly, which says how many attempts it has
spent and what the last error was:

```bash
curl -fsS -H "Authorization: Bearer ${TOKEN}" \
  "https://api.qoida.uz/api/v1/control-plane/integration/failures/inbox/<consumer>/<eventId>" | jq .
```

The consumer is part of the key, not a filter: one event reaches several
consumers and each has its own attempt count. Then treat it as
[dead-letter-decision.md](dead-letter-decision.md). Lag that is flat
with no consumer at all means the listener is not running:
`qoida.messaging.inbox.listener.enabled`.

## 5. Never

Do not `UPDATE integration.outbox_events SET status = 'PENDING'`. Do not delete
rows. ADR 0006 exists to eliminate exactly that practice: the failure API
carries the audit trail and the reason, and hand-edited rows have neither. If a
row genuinely has to move, it moves through the failure API.

## Why fifteen minutes

The relay polls every second and backs off to at most five minutes across ten
attempts — about thirteen and a half minutes end to end. Nothing healthy is
fifteen minutes old, which is why fifteen is the threshold and why anything past
it is a real stall rather than a busy evening.
