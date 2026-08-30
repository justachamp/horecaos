# Restore

Read this paragraph before typing anything.

**`infra/backup/restore.sh` never targets the live database, and you should not
change that.** It restores into a *new* database and verifies it. Bringing the
platform onto that restored database is a separate, deliberate step at the
bottom of this file. A `pg_restore --clean` fired at production during an
incident is how a recoverable afternoon becomes an unrecoverable one.

That is now enforced rather than requested. Before it downloads anything, the
script asks the target server for `current_database()` and refuses to continue if
the answer is `qoida`:

```text
!! Refusing to restore into "qoida".
```

It asks the server rather than reading the URL because a connection string can
reach production through a hostname alias, an SSH tunnel, a pooler, or by
omitting the database name entirely, and none of those look like `qoida` in the
text you pasted. `QOIDA_RESTORE_FORBIDDEN_DATABASES` is the list, and widening it
is the only way past — which is a decision somebody makes, not one they make by
accident at 3am.

Setup, as in every runbook here:

```bash
cd /opt/qoida/qoida-platform
alias qc='docker compose -f compose.production.yaml --env-file /etc/qoida/production.env'
```

Everything below runs inside the `ops` container, which has `pg_restore`, `psql`,
`mc` and `openssl` at the right versions. Nothing needs to be installed on the
host.

---

## 1. What has actually gone wrong?

Answer this before restoring anything. The three cases need different things and
two of them do not need a restore at all.

| Symptom | This is | Go to |
|---|---|---|
| The database will not start; disk or data-directory error | Hardware or corruption | Section 3 |
| The database is fine but a table holds wrong data — a bad backfill, a mistaken bulk update | Data damage | Section 4 |
| A migration went wrong | Schema damage | `deploy.md` section 3 first; come back only if that says so |

Restoring to fix a bad `UPDATE` loses every order placed since the backup. Almost
always the right answer is to restore into a scratch database, pull the correct
rows out of it, and repair the live one — section 4.

---

## 2. Is there a backup, and is it any good?

```bash
qc run --rm ops bash -c '
  mc alias set b "$QOIDA_BACKUP_S3_ENDPOINT" \
    "$(bao-get.sh production/object_storage/platform/backup-access-key)" \
    "$(bao-get.sh production/object_storage/platform/backup-secret-key)" >/dev/null
  mc ls b/qoida-backups/ | tail -10'
```

**Check:** the newest `.dump.enc` is from last night, and there is a matching
`.sha256` beside it. If the newest is days old, the backup has been silently
failing — see section 6 — and the recovery point is that date, not last night.

If this machine is gone — fire, flood, seizure — the same objects are in the
off-site bucket, which is the case that bucket exists for. Point the alias there
instead and everything downstream is identical:

```bash
mc alias set b "$QOIDA_BACKUP_OFFSITE_ENDPOINT" \
  "$(bao-get.sh production/object_storage/platform/backup-offsite-access-key)" \
  "$(bao-get.sh production/object_storage/platform/backup-offsite-secret-key)"
```

That command needs OpenBao, which was also in the building. Rebuilding it from
the sealed unseal shares comes first; `openbao-sealed.md` is the procedure, and
the passphrase in the sealed envelope is the other thing you cannot restore
without.

---

## 3. The database is gone

This is the full restore. It takes as long as it takes; there is nothing to fail
over to while it runs, and the platform is down for the whole of it.

### 3.1 Stop writing

```bash
qc stop platform-app
```

**Check:** `qc ps platform-app` shows the container stopped. Leaving it running
means it will reconnect mid-restore and write into a half-restored database.

Leave `edge` running. Customers get a 502, which is a clearer signal than a
timeout, and the external uptime monitor will already have alerted.

### 3.2 Restore into a new database, never over the old one

```bash
qc exec -T platform-db psql -U qoida_migrator -d postgres \
  -c "CREATE DATABASE qoida_restore OWNER qoida_migrator;"
```

```bash
qc run --rm ops bash -c '
  export QOIDA_BACKUP_ACCESS_KEY="$(bao-get.sh production/object_storage/platform/backup-access-key)"
  export QOIDA_BACKUP_SECRET_KEY="$(bao-get.sh production/object_storage/platform/backup-secret-key)"
  export QOIDA_BACKUP_PASSPHRASE="$(bao-get.sh production/data_encryption/platform/backup-passphrase)"
  export PGPASSWORD="$(bao-get.sh production/database/platform/migrator-password)"
  export QOIDA_RESTORE_TARGET_URL="postgresql://qoida_migrator@platform-db:5432/qoida_restore"
  /opt/qoida/backup/restore.sh'
```

To restore a specific backup rather than the newest, pass its object name as the
last argument to `restore.sh`.

**Check:** the script ends with `==> Restore verified` and prints non-zero counts
for the Flyway history, the schemas, the tenants and the audit events. It fails
loudly on a checksum mismatch, so if it printed that line the ciphertext was
intact and the decryption was correct.

If the checksum did not match, **do not decrypt further** — try the previous
backup instead. A corrupt object is far more likely than a corrupt disk on both
sides.

### 3.3 Look at it before you trust it

```bash
qc exec -T platform-db psql -U qoida_migrator -d qoida_restore -c "
  SELECT max(placed_at) AS newest_order, count(*) AS orders FROM ordering.orders;"
qc exec -T platform-db psql -U qoida_migrator -d qoida_restore -c "
  SELECT count(*) FROM flyway_schema_history WHERE success;"
```

**Check:** `newest_order` is roughly when the backup ran. Everything between that
timestamp and now is lost, and this is the moment you learn exactly how much.
Write that timestamp down; the business needs it before customers do.

**Check:** the Flyway count matches the number of files in
`src/main/resources/db/migration` for the release that was running. If the backup
is older than the deployed code, you must migrate the restored database before
the application will start against it — section 3.5.

### 3.4 Swap it in

Rename rather than drop. The damaged database is the only evidence of what
happened, and it costs nothing but disk to keep it until the incident is
understood.

```bash
qc exec -T platform-db psql -U qoida_migrator -d postgres -c "
  ALTER DATABASE qoida RENAME TO qoida_damaged_$(date -u +%Y%m%d);"
qc exec -T platform-db psql -U qoida_migrator -d postgres -c "
  ALTER DATABASE qoida_restore RENAME TO qoida;"
```

If the rename fails with "database is being accessed by other users", something
is still connected:

```bash
qc exec -T platform-db psql -U qoida_migrator -d postgres -c "
  SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='qoida';"
```

Then recreate the application login, because `pg_restore --no-owner
--no-privileges` restores objects without the role grants that went with them:

```bash
qc exec -T platform-db psql -U qoida_migrator -d qoida
```

```sql
-- The NOLOGIN group roles are cluster-wide and survived; qoida_app's membership
-- and the per-table grants did not travel with the dump.
GRANT CONNECT ON DATABASE qoida TO qoida_app;
GRANT qoida_application TO qoida_app;
```

**Check:** connect as the application and read one row.

```bash
qc exec -T platform-db psql -U qoida_app -d qoida -c "SELECT count(*) FROM tenant.tenants;"
```

If that returns `permission denied`, the grants did not come back. Re-run the
migrations (section 3.5) — every migration re-issues its own `GRANT` block, which
is precisely why they are written that way.

### 3.5 Bring the schema up to the deployed release

Only if section 3.3 showed the backup is behind the running code.

```bash
export FLYWAY_PASSWORD="$(qc run --rm --no-TTY ops bao-get.sh production/database/platform/migrator-password)"
qc run --rm platform-migrate info
qc run --rm platform-migrate migrate
unset FLYWAY_PASSWORD
```

**Check:** `info` shows every migration `Success` and none `Pending`.

### 3.6 Start

```bash
qc up -d platform-app
qc logs -f platform-app
```

**Check:** `qc ps` shows `platform-app (healthy)`, and from off the machine:

```bash
curl -fsS https://api.qoida.uz/actuator/health/readiness
```

### 3.7 Afterwards, the same evening

- Record how long the restore took, wall clock, and put it in
  `infra/backup/README.md`. That number is the recovery time; until it is
  written down it is a guess.
- Tell the business the exact data-loss window from section 3.3.
- Keep `qoida_damaged_*` until the cause is understood, then drop it.
- Take a fresh backup immediately. The one you just used is now the only copy of
  a database that has since diverged.

---

## 4. Only some data is wrong

Do not restore over the live database. Restore beside it and copy back.

```bash
qc exec -T platform-db psql -U qoida_migrator -d postgres \
  -c "CREATE DATABASE qoida_scratch OWNER qoida_migrator;"
```

Then run the restore command from section 3.2 with
`QOIDA_RESTORE_TARGET_URL` pointing at `qoida_scratch`.

Now both are on the same server and one query can see both — `dblink` and
`postgres_fdw` are the ways, but for anything under a few hundred thousand rows
the simplest is a dump of the one table:

```bash
qc run --rm ops bash -c '
  export PGPASSWORD="$(bao-get.sh production/database/platform/migrator-password)"
  pg_dump --data-only --table=<schema>.<table> \
    "postgresql://qoida_migrator@platform-db:5432/qoida_scratch" \
    > /tmp/table.sql
  wc -l /tmp/table.sql'
```

**Check the row count before you load it.** Then load it into a staging table in
the live database — never straight over the damaged one — and reconcile with SQL
you have read twice.

Anything you change this way is a decision that matters, and ADR 0027 says
decisions that matter are audited. Record what you ran and why.

---

## 5. Rehearsing, so that none of the above is the first time

`infra/backup/rehearse-restore.sh` runs the whole path into a scratch database
and compares row counts against the source. It is the only thing that turns a
belief into a capability.

Locally, against the development stack:

```bash
docker compose up -d
./infra/backup/rehearse-restore.sh
```

**Do this at least monthly, and always before a migration that rewrites data.**
Put a reminder somewhere that nags — the failure mode of a solo operator is not
laziness, it is that nothing complains.

ADR 0034 reversed its earlier deferral: the off-site bucket ships **before the
pilot**, not after the data migration, because the migration is the phase most
likely to need a restore and would otherwise be the phase where every copy still
lived in the primary's failure domain.

`backup.sh` enforces that. It refuses to run when `QOIDA_BACKUP_OFFSITE_ENDPOINT`,
`QOIDA_BACKUP_OFFSITE_ACCESS_KEY` or `QOIDA_BACKUP_OFFSITE_SECRET_KEY` is unset,
and refuses an off-site endpoint equal to the primary one. So a nightly backup
failing with "No off-site destination is configured" is not a bug to route
around — it is the gate, and the fix is the bucket.

Still take a manual copy onto separate physical media before every irreversible
migration step. A destination configured this afternoon has not yet had a
restore succeed from it.

---

## 6. The backup did not run

The heartbeat alerts when `/var/lib/qoida/last-backup` is more than 26 hours old.
That fires for "did not run" as well as "ran and failed", which is the point:
silence is the failure mode that hides longest.

```bash
grep run-backup /var/log/syslog | tail -20
/opt/qoida/qoida-platform/infra/production/run-backup.sh
```

The usual causes, in the order they actually happen:

1. **OpenBao is sealed.** The backup borrows the agent's token and cannot get it.
   `qc exec -T openbao bao status`. Unseal it; see `deploy.md` section 5.
2. **The disk is full.** The dump is staged in the container's filesystem before
   it is encrypted. `df -h`, then `docker system prune`.
3. **The off-site endpoint is unreachable, or is not configured.** `mc` will have
   said so, or the script will have refused before dumping anything. Either way
   the run is a failure and **tonight has no off-site copy** — the local upload
   may have succeeded, so check what is in the primary bucket, but do not read a
   local object as the backup having worked.
4. **The passphrase was rotated and the new one was not stored.** Every backup
   taken since is unreadable. This is the worst one on the list and the only
   defence is that rotating it is a documented procedure that ends with a
   rehearsal.

After fixing, run the backup by hand and confirm the stamp file updated:

```bash
/opt/qoida/qoida-platform/infra/production/run-backup.sh && touch /var/lib/qoida/last-backup
ls -l /var/lib/qoida/last-backup
```

---

## 7. What is not covered here, honestly

- **Point-in-time recovery.** Not configured. There is no WAL archive, so the
  recovery point is the last nightly dump and nothing finer. Setting up
  continuous archiving is the single largest improvement available to the
  recovery story and it is not done.
- **Restoring OpenBao itself.** If the OpenBao volume is lost, every secret is
  lost with it, and the platform cannot start. Take a raft snapshot alongside the
  database backup — `bao operator raft snapshot save` — and treat it with the
  same care as the database dump. This is not yet in `run-backup.sh` and it
  should be.
- **Restoring MinIO.** Media objects are not in the database dump. Losing them
  loses product photographs, which is recoverable by re-upload and is therefore
  ranked below everything above — but it is a real gap and nobody has ever
  tested it.
- **Restoring Keycloak.** Its database is separate and is not in the nightly
  backup. Losing it loses every user account and every client secret. Its dump
  belongs in the same job; it is not there yet.

The first and last of those four are the ones that will hurt.
