# PostgreSQL is down while the host is up

**Night alert.** **Last executed:** never — this is a draft.

Everything assumes you are on the production host in `/opt/qoida/qoida-platform`
as root, with:

```bash
alias qc='docker compose -f compose.production.yaml --env-file /etc/qoida/production.env'
```

## 1. Is it actually down, or is it full?

```bash
qc ps platform-db && df -h / && qc exec -T platform-db pg_isready -U qoida_migrator -d qoida
```

**Check:** `df` first. A data volume at 100% presents as PostgreSQL refusing
writes, and that is the most likely cause of this alert. If the disk is full, go
to [disk-filling.md](disk-filling.md) and come back. Nothing below will work
until there is space.

## 2. What did it say on the way down?

```bash
qc logs --tail 200 platform-db
```

**Check:** you are looking for one of three things, and they have different
answers.

- `PANIC: could not write to file` or `No space left on device` — the disk.
  [disk-filling.md](disk-filling.md).
- `database system was not properly shut down; automatic recovery in progress` —
  it is recovering. **Wait.** Recovery after an unclean stop is normal and
  finishes on its own; restarting it in the middle starts it again from the
  beginning.
- Repeated `FATAL` on startup with no recovery line — the data directory is
  damaged. This is a restore: [restore.md](restore.md).

## 3. If the container is not running at all

```bash
qc up -d platform-db && sleep 20 && qc exec -T platform-db pg_isready -U qoida_migrator -d qoida
```

**Check:** `accepting connections`. If it comes up and stays up, the application
reconnects by itself through the Hikari pool — there is nothing to restart in
`platform-app`, and restarting it costs a JVM start for no benefit.

## 4. If it will not come up

Stop and read [restore.md](restore.md). Do not try repair tools on the data
directory. The restore path has been rehearsed and `pg_resetwal` has not.

## What you are protecting

Nothing degrades gracefully without this. Every order in flight is in
PostgreSQL, every outbox row that has not reached Kafka is in PostgreSQL, and
the platform is *behind* rather than *losing* only for as long as the disk is
intact. That is why this alert wakes you and the Kafka one does not.

## What recovers itself, so you do not have to

The application does not need restarting after the database returns. The
connection pool reconnects, outbox leases expire and are reclaimed, `SKIP
LOCKED` claims release, and unacknowledged Kafka records are redelivered. If you
find yourself restarting `platform-app` to "clear" something, stop: that is the
platform's design being distrusted rather than a step that helps.
