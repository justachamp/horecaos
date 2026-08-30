# Backup and restore

ADR 0034 puts Qoida on a colocated server first. Nobody else is checking that a
backup ran, produced something restorable, or left the building — so these
scripts verify each of those, and the rehearsal is the one that matters.

## The scripts

| Script | Purpose |
|---|---|
| `backup.sh` | Dump, verify, encrypt, upload locally **and off-site**, read the off-site copy back, expire old copies |
| `restore.sh` | Download, verify checksum, decrypt, restore into a **new** database, verify |
| `rehearse-restore.sh` | Runs the whole path into a scratch database and compares row counts |

`restore.sh` never targets the live database. A restore run against production
during an incident is how a bad day becomes a worse one.

## Running the rehearsal locally

```bash
docker compose up -d
./infra/backup/rehearse-restore.sh
```

It prints a baseline, runs the real path, and fails if the restored counts
differ. Last local run: 2 tenants, 25 audit events, 12 migrations, matched.

## Configuration

```text
QOIDA_BACKUP_DB_URL          source database
QOIDA_BACKUP_PASSPHRASE      encryption passphrase; see below
QOIDA_BACKUP_BUCKET          primary (on-box) bucket
QOIDA_BACKUP_S3_ENDPOINT     primary S3-compatible endpoint
QOIDA_BACKUP_ACCESS_KEY      primary credentials
QOIDA_BACKUP_SECRET_KEY
QOIDA_BACKUP_RETENTION_DAYS  default 30
QOIDA_RESTORE_TARGET_URL     restore target; never production
```

## The off-site copy

`backup.sh` writes the encrypted dump to **two** destinations, and **reads the
off-site one back** to verify it. Verifying the local copy would prove nothing
about the copy that survives losing the primary, so the read-back deliberately
ignores it. `rehearse-restore.sh` then restores from the off-site copy for the
same reason.

**`backup.sh` refuses to run at all when the off-site destination is not
configured.** It does not warn and continue. A cron entry records only an exit
status, the heartbeat only alerts on a backup that did not run, and a warning on
stdout at 02:17 is read by nobody — so "warn and keep the local copy" would have
reported healthy backups right up to the morning the building was gone. It also
refuses an off-site endpoint equal to the primary one: a second bucket on the
same store is a second name for the same failure domain.

Locally, "off-site" is a second MinIO instance (`minio-offsite`) rather than a
second bucket on the same server — a bucket beside the primary does not model
the risk being guarded against.

## Switching to a real destination

Four environment variables, nothing else. The first three have no default in
`backup.sh` — that is what makes an unconfigured deployment fail rather than
quietly keep a copy on the machine it is backing up.

```text
QOIDA_BACKUP_OFFSITE_ENDPOINT     https://s3.<region>.amazonaws.com  (or B2, or a
                                  regional provider's S3-compatible endpoint)
QOIDA_BACKUP_OFFSITE_ACCESS_KEY   a key restricted to this one bucket
QOIDA_BACKUP_OFFSITE_SECRET_KEY   resolved from OpenBao, never a literal
QOIDA_BACKUP_OFFSITE_BUCKET       optional; defaults to QOIDA_BACKUP_BUCKET,
                                  because the endpoint is what makes it off-site
```

Generate the key on the provider yourself. Nothing in this repository contains
one, and nothing in it should.

The bucket needs versioning enabled and object-lock or a lifecycle rule that
prevents deletion inside the retention window. Without one, a compromised backup
credential can delete every copy — and a backup an attacker can erase is not a
backup.

The credential should be able to write and read this bucket and nothing else.
The encryption passphrase must not live in the same account: if one compromise
yields both the ciphertext and the key, the encryption did nothing.

## What is proven and what is not

**Proven by the rehearsal:** dump, integrity check, AES-256 encryption before
upload, upload to two sites, checksum comparison against the *off-site* copy,
decrypt, restore into a scratch database, and row-count verification against the
source.

**Proven by `BackupScriptTests`:** that the nightly backup exits non-zero, before
it dumps anything, when the off-site destination is missing, half-configured, or
pointed at the primary store.

**Not proven:** durability of a real remote provider, real cross-network transfer
time, and restore time at production data volume. The first two arrive with a
real destination; the third should be measured and re-measured as the database
grows, because a recovery time nobody has measured is a guess.

## The passphrase

Encryption happens before upload, so the destination never holds readable data.
Losing the passphrase loses every backup. It belongs in OpenBao under
`data_encryption`, plus a sealed offline copy held somewhere other than the
server room. It must never be committed here.

## Before this is production-ready

- [ ] Point `QOIDA_BACKUP_OFFSITE_*` at a genuine remote bucket with versioning
      and object-lock, using a credential scoped to that bucket alone. Until this
      is done the nightly backup fails every night, which is the intended
      behaviour and is not something to work around.
- [ ] Resolve `QOIDA_BACKUP_OFFSITE_ACCESS_KEY` and `..._SECRET_KEY` from OpenBao
      in `infra/production/ops/backup-job.sh`, beside the passphrase.
- [ ] Schedule `backup.sh` and run `rehearse-restore.sh` at least weekly.
- [ ] Alert on a backup that did not run, not only on one that failed — silence
      is the failure mode that hides longest.
- [ ] Record the measured restore time, and re-measure as the database grows.
- [ ] Store the passphrase in OpenBao and seal an offline copy.
