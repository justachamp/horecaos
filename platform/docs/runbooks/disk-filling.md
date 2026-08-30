# The data volume is filling

**Morning alert** at 85%. **Last executed:** never — this is a draft.

Nothing frees space automatically. The 15% is there to buy you a working day,
and the order below matters because only the last step needs the facility.

## 1. What is using it?

```bash
df -h / && docker system df && du -sh /var/lib/docker/volumes/* 2>/dev/null | sort -h | tail
```

## 2. The order. Do these in this order.

### Expire Kafka segments

```bash
qc exec -T kafka /opt/kafka/bin/kafka-configs.sh --bootstrap-server localhost:29092 \
  --entity-type topics --entity-name ordering.events --describe
```

Retention is already bounded at seven days and 8 GiB, which is longer than any
ADR 0006 replay window. Shortening it temporarily is the cheapest space
available and costs only replay depth.

### Prune backups past retention

```bash
qc run --rm ops sh -c 'mc ls qoida/${QOIDA_BACKUP_BUCKET}' | head -40
```

Retention is `QOIDA_BACKUP_RETENTION_DAYS`, and `backup.sh` applies it to both
destinations. **Confirm the off-site copy of that exact object exists before
deleting anything local** — the nightly run refuses without an off-site
destination, but a bucket lifecycle rule on the far side can still have expired
an object the local store kept:

```bash
qc run --rm ops sh -c '
  mc alias set offsite "$QOIDA_BACKUP_OFFSITE_ENDPOINT" \
     "$QOIDA_BACKUP_OFFSITE_ACCESS_KEY" "$QOIDA_BACKUP_OFFSITE_SECRET_KEY" >/dev/null
  mc ls offsite/${QOIDA_BACKUP_OFFSITE_BUCKET:-$QOIDA_BACKUP_BUCKET}' | head -40
```

Deleting the only copy to make room is how a disk-space incident becomes a
data-loss incident.

### Drop leftover rehearsal databases

```bash
qc exec -T platform-db psql -U qoida_migrator -d postgres -c \
  "SELECT datname, pg_size_pretty(pg_database_size(datname)) FROM pg_database ORDER BY pg_database_size(datname) DESC"
```

A weekly restore rehearsal leaves a database behind if it failed partway. These
are safe to drop and are usually the largest single win.

### Docker images and build cache

```bash
docker image prune -af && docker builder prune -af
```

Keep the currently running tag and the previous one — the previous tag is the
rollback.

### Extend the volume

Only this step needs the facility, which is why it is last. Expect it to take
hours rather than minutes.

## 3. If it is already at 100%

PostgreSQL is refusing writes and the night alert has already fired. Free
something with `docker image prune -af` first: it needs no database and no
running application, and it is usually enough to get PostgreSQL writing again so
that the rest of this list becomes possible.

## 4. Why 85% and not 95%

PostgreSQL, Kafka segments, audit partitions and the trace store all grow
monotonically, and the slope is normally days rather than hours. 15% is the
margin that makes this a morning item you handle during a working day instead of
a page you handle at 3am with no room left to work in.
