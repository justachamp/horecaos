# Object store migration: MinIO → RustFS

**Last executed: never.** MinIO's own images are withdrawn — `quay.io/minio/minio`
and `quay.io/minio/mc` answer 401 anonymously, Docker Hub's `minio/minio` is 404 —
so this migration is not optional maintenance; it is how the platform's object
store keeps existing. It still costs VM time and touches live media, audit-archive
and backup objects, so **nothing in this runbook runs until the owner says so.**
Read the whole thing before typing the first command.

Written against ADR 0135 (search `docs/adr/` for its current file — ADRs are
filed by implementation status and this one was written in the same batch as
this runbook, so its exact path was not yet settled when this was written),
which records why RustFS and not another S3-compatible product. The compose
and CI side of the swap (the object-store service definition, the `minio`
network alias, the pinned `rustfs/rustfs:1.0.0` image) is a separate change to
`deploy/compose.production.yml` and `platform/compose.production.yaml`; this
runbook assumes that change is already merged and treats it as a
precondition, not something it does.

## What "the object store" means here, precisely

Three buckets, three different migration shapes:

| Bucket | What's in it | Versioned? | Object Lock? | Migration shape |
|---|---|---|---|---|
| `horecaos-media` | Product photos and other customer-facing uploads | No | No | Plain copy — step 3 |
| `horecaos-audit-archive` | ADR 0027 append-only audit evidence | Yes | Yes, GOVERNANCE | Every version, retention preserved — step 4 |
| `horecaos-backups` | Nightly encrypted `pg_dump` output | Yes (bucket-level) | No | Current objects only — step 5, see its own note on why |

`platform/infra/production/migrate-object-store.sh` (referred to below as just
"the script") implements steps 3, 4 and 5, idempotently, against the pinned
`amazon/aws-cli` image. Run `./migrate-object-store.sh --help` for its full
option and environment-variable list before using it; this runbook only quotes
the parts specific to a production run.

## Preconditions — verify every one before step 1

1. **The deploy compose is already at the RustFS version, but `platform-app` is
   not yet switched.** `deploy/compose.production.yml`'s object-store service
   runs `rustfs/rustfs:1.0.0@sha256:8cc9801755448b71a786705ce76692c77e14936cccd87cf2fc31842e58f4d1ff`
   and is reachable at the network alias `minio` — the same DNS name every other
   service already uses, kept stable on purpose so this migration changes
   credentials, not URLs. Confirm what is actually running right now, because a
   compose *file* being updated is not the same claim as a *container* having
   been restarted from it:

   ```bash
   cd /opt/horecaos/horecaos-platform
   alias qc='docker compose -f deploy/compose.production.yml --env-file /etc/horecaos/production.env'
   qc ps minio   # (or whatever wave C's compose named the service — grep the
                 #  file for "rustfs" if "minio" no longer matches)
   ```

   If the `Image` column already shows `rustfs/rustfs`, the swap already
   happened and step 2's bucket seeding should already be done too — check
   before re-running it (the script and the seed commands below are
   idempotent, but there is no reason to redo work that is already done).

2. **The old MinIO image is still in the VM's Docker cache.** Step 1 starts it
   by hand; it is not pulled (it cannot be — see the top of this file), only
   whatever `docker image inspect` can already find:

   ```bash
   docker image inspect quay.io/minio/minio:RELEASE.2025-07-23T15-54-02Z >/dev/null \
     && echo "cached: good" \
     || echo "NOT CACHED — load the fallback tarball, next line"
   ```

   If that fails, the fallback is a tarball the operator saved to their own
   machine, not this repository (a MinIO image is exactly the kind of file
   this runbook cannot commit and stay honest about "never pull it again"):

   ```bash
   docker load -i minio-and-mc-2025-07.tar
   ```

3. **A sealed evidence copy is acceptable to make.** Step 7 leaves the old
   `minio-data` volume in place, untouched, for as long as the longest audit
   retention on it has not yet expired. Confirm there is disk headroom to keep
   an idle volume around for that long — `df -h` on the host, and see step 7
   for how to find the actual date.

4. **You can reach both endpoints from one place.** The script runs every AWS
   CLI call inside a throwaway container on a Docker network you name — on the
   VM this is the `core` network deploy/compose.production.yml defines
   (`internal: true`, no route out, which is exactly right for this — nothing
   here needs the internet). Docker Compose names it `<project>_core`; the
   project name is pinned in the compose file itself, so on this host it
   should be `horecaos-production_core` — confirm rather than assume:

   ```bash
   docker network ls --format '{{.Name}}' | grep _core
   ```

## Step 1 — start the old MinIO container by hand

A throwaway container, outside the compose project, on the same network, with
the old data volume mounted **read-only** and a network alias of
`minio-legacy` — never `minio`, which now means RustFS.

Find the volume's actual name first; it follows the same `<project>_<name>`
rule as the network:

```bash
docker volume ls --format '{{.Name}}' | grep minio-data
```

Standalone MinIO (`server /data`, no external identity provider) always takes
its root identity from the environment variables it is started with — it is
never read back from the data directory — so `minio-legacy` does **not** need
the original production root credential. Generate a fresh, throwaway one for
this session only; it is never written to a file, never typed into OpenBao,
and dies with the container:

```bash
export MINIO_LEGACY_USER="legacy-$(openssl rand -hex 4)"
export MINIO_LEGACY_PASSWORD="$(openssl rand -hex 24)"

docker run -d --name minio-legacy \
  --network horecaos-production_core --network-alias minio-legacy \
  -e MINIO_ROOT_USER="${MINIO_LEGACY_USER}" \
  -e MINIO_ROOT_PASSWORD="${MINIO_LEGACY_PASSWORD}" \
  -v horecaos-production_minio-data:/data:ro \
  quay.io/minio/minio:RELEASE.2025-07-23T15-54-02Z server /data
```

The object-store service holds the `minio` alias on **two** networks, not
one — `core` (what `platform-app`'s S3 calls use) and `media` (what Caddy's
`reverse_proxy minio:9000` for `media.horecaos.uz` uses; see the two
`aliases:` blocks on `deploy/compose.production.yml`'s `object-store`
service). `docker run` only joins one network at creation, so reserve the
`minio-legacy` alias on `media` too, with a second call, now — this is what
lets step 8 hand *both* aliases back to this container instead of only one:

```bash
docker network connect --alias minio-legacy \
  horecaos-production_media minio-legacy
```

**If it refuses to start read-only** — some MinIO builds want to write an
internal lock or temp file even when serving reads only — drop the `:ro` and
rely on the script's own guarantee instead: `migrate-object-store.sh` never
issues a write against `SOURCE_ENDPOINT` under any code path (steps 3-5 all
read from source and write to target only), so nothing will write to this
volume as long as nobody runs an unrelated `mc`/`aws s3` write against it by
hand while it is up. Prefer `:ro` when it works; this is the fallback, not the
default.

**Check:**

```bash
docker run --rm --network horecaos-production_core curlimages/curl:8 \
  -sf http://minio-legacy:9000/minio/health/live && echo OK
```

## Step 2 — bring up the object store and seed it

If precondition 1 showed RustFS is not running yet, bring it up now:

```bash
qc up -d minio   # or the object-store service's actual name
```

Create the three buckets with the same properties `compose.yaml`'s
`minio-seed` service and `docs/runbooks/production-setup.md` section 5 give
them today — `horecaos-audit-archive` needs Object Lock enabled **at creation
time**, because (per the orchestrator's verification against a running
RustFS 1.0.0) there is no later call that retrofits it onto an existing
bucket, exactly as with S3 itself:

```bash
export TARGET_ROOT_ACCESS_KEY="<the new RustFS root access key wave C's first
  boot generated — read it with bao-get.sh, never type it>"
export TARGET_ROOT_SECRET_KEY="<same, for the secret key>"

docker run --rm --network horecaos-production_core \
  -e AWS_ACCESS_KEY_ID="${TARGET_ROOT_ACCESS_KEY}" \
  -e AWS_SECRET_ACCESS_KEY="${TARGET_ROOT_SECRET_KEY}" \
  -e AWS_DEFAULT_REGION=us-east-1 -e AWS_ENDPOINT_URL=http://minio:9000 \
  -e AWS_EC2_METADATA_DISABLED=true \
  amazon/aws-cli@sha256:83f8ffe939569070c5b66d22231862ab78718766d9d8e4c44ca84dd0be5569a5 \
  s3api create-bucket --bucket horecaos-media

# repeat for horecaos-backups, then enable versioning on it:
#   s3api create-bucket --bucket horecaos-backups
#   s3api put-bucket-versioning --bucket horecaos-backups --versioning-configuration Status=Enabled
# and horecaos-audit-archive, WITH the lock flag:
#   s3api create-bucket --bucket horecaos-audit-archive --object-lock-enabled-for-bucket
```

**Known gap, not silently papered over:** production's current MinIO setup
gives the application *scoped* service accounts per purpose (`media-service`,
a backup account) rather than the root credential, created with
`mc admin user add` / `mc admin policy attach`
(`docs/runbooks/production-setup.md` section 4). RustFS's S3 API surface was
verified for this migration (bucket/object operations, Object Lock,
versioning, presigned URLs); its IAM surface — whether it has any equivalent
to MinIO's admin API for scoped users and policies — was **not** verified and
is not documented in what the orchestrator confirmed. Until that is checked
against RustFS's own documentation, provision the application's OpenBao
credentials (step 6) as the **root** credential for all three purposes. This
is a real reduction in defence-in-depth from today's setup — a compromised
media-upload path could now reach the backup bucket too — and should be
tracked and closed, not left as a permanent decision. It does not block this
migration; it is a decision to make explicitly rather than not notice.

**Check:**

```bash
docker run --rm --network horecaos-production_core \
  -e AWS_ACCESS_KEY_ID="${TARGET_ROOT_ACCESS_KEY}" -e AWS_SECRET_ACCESS_KEY="${TARGET_ROOT_SECRET_KEY}" \
  -e AWS_ENDPOINT_URL=http://minio:9000 -e AWS_EC2_METADATA_DISABLED=true \
  amazon/aws-cli@sha256:83f8ffe939569070c5b66d22231862ab78718766d9d8e4c44ca84dd0be5569a5 \
  s3api get-bucket-versioning --bucket horecaos-audit-archive
```
shows `"Status": "Enabled"` — object-lock buckets auto-enable versioning, which is
what the orchestrator's own verification against a running RustFS 1.0.0 confirmed.

## Steps 3–5 — copy media, audit archive, and backups

This is what `migrate-object-store.sh` does. Media and backups go through a
local staging directory, because the AWS CLI takes one `--endpoint-url` per
invocation — there is no single `aws s3 sync` that reads from one
S3-compatible store and writes to another. The audit archive is a real
download-then-upload per version for the same reason, one level down:
`CopyObject`/`copy-object` is a single-endpoint, single-request operation
(the source is named by bucket+key inside the same account the request is
signed against), so there is no server-side call that moves a locked object
from one store to a different one — every version has to actually cross this
machine, retention read on the way out and re-applied on the way in.

Read the script's `--help` in full before running it; the essentials:

```bash
export SOURCE_ENDPOINT=http://minio-legacy:9000
export TARGET_ENDPOINT=http://minio:9000
export MIGRATE_DOCKER_NETWORK=horecaos-production_core

export SOURCE_ACCESS_KEY="${MINIO_LEGACY_USER}"
export SOURCE_SECRET_KEY="${MINIO_LEGACY_PASSWORD}"
export TARGET_ACCESS_KEY="${TARGET_ROOT_ACCESS_KEY}"
export TARGET_SECRET_KEY="${TARGET_ROOT_SECRET_KEY}"

# A dry run first. It performs every read for real — nothing here is a
# simulation of what the data looks like — and writes nothing.
./infra/production/migrate-object-store.sh --dry-run

# Then for real.
./infra/production/migrate-object-store.sh
```

The script itself verifies object-count/byte parity for media and backups,
and full per-version retention equality for the audit archive (every version,
not a sample — see the script's own comment on why checking all of them costs
nothing extra). It exits non-zero and says exactly what did not match if
anything is wrong; do not proceed past a non-zero exit.

**Independently of the script**, spot-check three audit-archive retentions by
hand, because a check the script did not write is a second, different look at
the same claim:

```bash
for key_version in \
    "partitions/2026-01/orders.parquet <a version id from step 4's output>" \
    "partitions/2026-05/orders.parquet <another>" \
    "partitions/2026-09/orders.parquet <another>"
do
  set -- ${key_version}
  echo "--- ${1} @ ${2} ---"
  docker run --rm --network horecaos-production_core \
    -e AWS_ACCESS_KEY_ID="${MINIO_LEGACY_USER}" -e AWS_SECRET_ACCESS_KEY="${MINIO_LEGACY_PASSWORD}" \
    -e AWS_ENDPOINT_URL=http://minio-legacy:9000 -e AWS_EC2_METADATA_DISABLED=true \
    amazon/aws-cli@sha256:83f8ffe939569070c5b66d22231862ab78718766d9d8e4c44ca84dd0be5569a5 \
    s3api get-object-retention --bucket horecaos-audit-archive --key "${1}" --version-id "${2}"
  docker run --rm --network horecaos-production_core \
    -e AWS_ACCESS_KEY_ID="${TARGET_ROOT_ACCESS_KEY}" -e AWS_SECRET_ACCESS_KEY="${TARGET_ROOT_SECRET_KEY}" \
    -e AWS_ENDPOINT_URL=http://minio:9000 -e AWS_EC2_METADATA_DISABLED=true \
    amazon/aws-cli@sha256:83f8ffe939569070c5b66d22231862ab78718766d9d8e4c44ca84dd0be5569a5 \
    s3api list-object-versions --bucket horecaos-audit-archive --prefix "${1}"
  # then get-object-retention on the target version id it printed, and compare by eye.
done
```

**Why step 5 (backups) does not need version history:** every nightly run
(`infra/backup/backup.sh`) writes a uniquely timestamped object name and never
overwrites an existing one, so there has only ever been at most one version
per key. Reproducing the current object set — what `aws s3 sync` without
`--delete` already does — reproduces everything that has ever mattered for a
restore. The target bucket's own versioning stays on for anything written
*after* the cutover; nothing about this migration turns it off.

**If the script's verification fails:** stop. Do not proceed to step 6. Re-run
`migrate-object-store.sh` (it is idempotent — already-copied objects and
already-present audit-archive versions are skipped, not duplicated) and read
what it reports failed. A media or backups parity mismatch after a clean
re-run means something is still being written to the source concurrently —
check nothing else has `minio-legacy` mounted writable. An audit-archive
retention mismatch after a clean re-run is the one that needs a person before
anything else happens next: it means the evidence itself does not match, not
just its container.

## Step 6 — switch `platform-app`

The endpoints do not change — `HORECAOS_MEDIA_ENDPOINT`,
`HORECAOS_AUDIT_ARCHIVE_ENDPOINT` and `HORECAOS_BACKUP_S3_ENDPOINT` all stay
`http://minio:9000`, because the network alias made that true on purpose. Only
credentials change, and where they live falls into exactly two shapes:

**A. Values the application resolves by reference at call time (no restart
needed for these — this is the whole point of ADR 0028's design, spelled out
in `platform/infra/production/README.md`'s "How a secret reaches a running
container").** Rotate these directly in OpenBao; `platform-app` picks up the
new value on its next call, not its next restart:

```text
horecaos/production/object_storage/platform/media-access-key
horecaos/production/object_storage/platform/media-secret-key
horecaos/production/object_storage/platform/audit-archive-access-key
horecaos/production/object_storage/platform/audit-archive-secret-key
horecaos/production/object_storage/platform/backup-access-key
horecaos/production/object_storage/platform/backup-secret-key
```

(`backup-offsite-*` is untouched — the off-site destination is a different
provider entirely and this migration does not reach it.) Per the "known gap"
note in step 2, write the new RustFS root credential's values into all six of
these for now, and track scoping them down separately.

`docs/runbooks/production-setup.md` section 4's own list of secrets to
provision only names the media and backup pairs, not
`audit-archive-access-key` / `audit-archive-secret-key` — check whether
those two already exist in OpenBao (`qc exec -T openbao bao kv get
horecaos/production/object_storage/platform/audit-archive-access-key`)
before assuming this is a two-value edit to an existing pair rather than a
first-time write. Either way, `S3AuditArchiveStore` reads exactly this
reference pair (`application.yml`'s `HORECAOS_AUDIT_ARCHIVE_ACCESS_KEY_REF`
default), so it is the correct path regardless of whether it is new.

Values are written with `bao kv put`, run by a person, never pasted into this
file or a chat transcript — `platform/infra/production/README.md` says the
same about every value on this list.

**B. One value delivered as a file at container start, which does need a
restart to pick up.** The object-store's own root credential —
`object_storage/platform/root-password` — reaches the container as
`/run/horecaos/secrets/minio-root-password` (or whatever wave C's compose
named that secret; `grep -n 'root-password\|ROOT_USER' deploy/compose.production.yml`
if `minio-root-password` no longer matches), materialised at boot by
`deploy/session-start.sh`'s `write_secret` step, not read live. After rotating
the OpenBao value:

```bash
# Re-materialise just this one file (session-start.sh's own write_secret
# pattern, done by hand rather than re-running the whole boot sequence,
# which would also re-issue the AppRole secret-id unnecessarily):
value="$(qc exec -T openbao bao kv get -field=value horecaos/production/object_storage/platform/root-password)"
( umask 133; printf '%s' "${value}" > /run/horecaos/secrets/minio-root-password )
chmod 0444 /run/horecaos/secrets/minio-root-password
unset value

qc restart minio   # or whatever the object-store service is actually named
```

The **only** line in `/etc/horecaos/production.env` this step might touch is
the root *username* — `HORECAOS_MINIO_ROOT_USER=` (plaintext, not a secret;
`deploy/env.template`'s own header says every value in this file is public
information) — and only if the new RustFS root user should have a different
name than the old MinIO one. Leave it alone if not. If wave C renamed the
variable itself, `grep -n 'ROOT_USER' /etc/horecaos/production.env` finds
whatever it is actually called now.

```bash
$EDITOR /etc/horecaos/production.env   # only if the root username itself changes
qc restart platform-app                 # picks up the new production.env line, if any
```

**Smoke test**, using what already exists rather than inventing a new one:

```bash
curl -fsS https://api.horecaos.uz/actuator/health/readiness
```

then a real presigned-upload round trip and one storefront image load — the
same checks `deploy/local-smoke.sh` runs locally against a fresh stack; run
its media assertions by hand against production, or, if the runbook this
migration is part of grows a scripted production smoke check before this
runs for real, use that instead. **Check:** an image already migrated in
step 3 loads in a browser from `https://media.horecaos.uz/...`, proving the
presigned URL's signature, the media origin, and the new credential all agree.

## Step 7 — retire `minio-legacy`

```bash
docker stop minio-legacy
docker rm minio-legacy
```

**The volume, not the container, is the evidence.** Do not remove
`horecaos-production_minio-data`. Label it and leave it sealed until the
longest retention on the audit archive it once served has expired — after
that point nothing on it is still legally required to exist anywhere.

**Finding that date:** query the object store directly rather than the
application's configured retention-day settings — `AuditPartitionArchiver`'s
`SECURITY_RETENTION_DAYS_CODE` / `BUSINESS_RETENTION_DAYS_CODE` policy values
can themselves change over the archive's lifetime, so the number configured
today is not proof of what an object written two years ago was actually
locked for. The lock itself is:

```bash
docker run --rm --network horecaos-production_core \
  -e AWS_ACCESS_KEY_ID="${TARGET_ROOT_ACCESS_KEY}" -e AWS_SECRET_ACCESS_KEY="${TARGET_ROOT_SECRET_KEY}" \
  -e AWS_ENDPOINT_URL=http://minio:9000 -e AWS_EC2_METADATA_DISABLED=true \
  amazon/aws-cli@sha256:83f8ffe939569070c5b66d22231862ab78718766d9d8e4c44ca84dd0be5569a5 \
  s3api list-object-versions --bucket horecaos-audit-archive \
  --query 'Versions[].VersionId' --output text \
  | tr '\t' '\n' \
  | while read -r vid; do
      key="$(docker run --rm --network horecaos-production_core \
        -e AWS_ACCESS_KEY_ID="${TARGET_ROOT_ACCESS_KEY}" -e AWS_SECRET_ACCESS_KEY="${TARGET_ROOT_SECRET_KEY}" \
        -e AWS_ENDPOINT_URL=http://minio:9000 -e AWS_EC2_METADATA_DISABLED=true \
        amazon/aws-cli@sha256:83f8ffe939569070c5b66d22231862ab78718766d9d8e4c44ca84dd0be5569a5 \
        s3api list-object-versions --bucket horecaos-audit-archive \
        --query "Versions[?VersionId=='${vid}'].Key | [0]" --output text)"
      docker run --rm --network horecaos-production_core \
        -e AWS_ACCESS_KEY_ID="${TARGET_ROOT_ACCESS_KEY}" -e AWS_SECRET_ACCESS_KEY="${TARGET_ROOT_SECRET_KEY}" \
        -e AWS_ENDPOINT_URL=http://minio:9000 -e AWS_EC2_METADATA_DISABLED=true \
        amazon/aws-cli@sha256:83f8ffe939569070c5b66d22231862ab78718766d9d8e4c44ca84dd0be5569a5 \
        s3api get-object-retention --bucket horecaos-audit-archive --key "${key}" --version-id "${vid}" \
        --query 'Retention.RetainUntilDate' --output text
    done | sort | tail -1
```

That last line is the date. Put a note on the calendar, and record the volume
name and this date in `infra/backup/README.md` next to the other numbers that
only mean something written down (its own convention already, per
`docs/runbooks/restore.md`'s section 3.7).

## Step 8 — rollback

**Nothing was deleted at any point before step 7 runs.** `minio-legacy`'s
volume mount was read-only (or, if it had to be mounted writable, nothing in
this runbook or the script ever wrote to it); the RustFS target's copies are
additive; `platform-app`'s old credentials still authenticate against
whatever was serving `minio:9000` before step 6.

**If step 6 goes wrong** — the smoke test fails, images will not load, the
application cannot authenticate — two things made platform-app's requests
land on RustFS, and rollback means undoing both, in order. Getting this
wrong in a way that looks like it worked is worse than getting it slowly:
restoring only the credentials while `minio` still means RustFS just
re-creates the original failure; restoring only the alias while the
credentials are still RustFS's just moves the same authentication failure
onto `minio-legacy` instead.

*First*, hand the `minio` alias back to `minio-legacy` — this is what makes
`http://minio:9000` mean the old store again, with no endpoint edit
anywhere, because nothing about this rollback should require reasoning about
which of a dozen `HORECAOS_*_ENDPOINT` lines to change under pressure. Do
this on **both** networks the object-store service held the alias on —
`core` (`platform-app`'s S3 calls) and `media` (Caddy's
`reverse_proxy minio:9000` for `media.horecaos.uz`, per the object-store
service's own two-network `aliases:` block). Restoring only `core` leaves
`platform-app` authenticating fine while every storefront image 502s, with
nothing in this runbook to say why — that gap is exactly what step 1's
extra `docker network connect` for `media` exists to close:

```bash
qc stop minio    # or whatever the object-store service is actually named —
                  # frees the "minio" alias on BOTH the core and media
                  # networks at once (stopping the container drops all of
                  # its network endpoints)

# A container already connected to a network cannot have an alias added to
# that connection — Docker refuses with "endpoint already exists" — so this
# is a disconnect and reconnect, naming both the alias minio-legacy already
# had and the one it is taking over, in the same call. Repeat for media —
# skipping it is the one-network mistake this step exists to prevent:
docker network disconnect horecaos-production_core minio-legacy
docker network connect --alias minio-legacy --alias minio \
  horecaos-production_core minio-legacy

docker network disconnect horecaos-production_media minio-legacy
docker network connect --alias minio-legacy --alias minio \
  horecaos-production_media minio-legacy
```

**Check both origins, not just one** — the whole reason this needed two
`docker network connect` calls instead of one is that a check against only
`core` cannot see a `media` alias that never came back:

```bash
docker run --rm --network horecaos-production_core curlimages/curl:8 \
  -sf http://minio:9000/minio/health/live && echo "core: OK"
docker run --rm --network horecaos-production_media curlimages/curl:8 \
  -sf http://minio:9000/minio/health/live && echo "media: OK"
```

*Second*, restore the six OpenBao values from step 6.A to what they were
before this migration touched them. OpenBao's KV v2 engine versions every
write, which is exactly what makes this safe to do without ever having
copied a credential out of it by hand — `bao kv put` in step 6 created a new
version; it did not destroy the one before it:

```bash
for path in media-access-key media-secret-key \
            audit-archive-access-key audit-archive-secret-key \
            backup-access-key backup-secret-key
do
  qc exec -T openbao bao kv rollback -version=<the version step 6 overwrote — 1 less than current>\
    "horecaos/production/object_storage/platform/${path}"
done
```

No `platform-app` restart is needed for this half — the same reference
resolution that made step 6.A a live rotation makes this a live rotation
back. If `minio-legacy` was started with a fresh throwaway credential in
step 1 rather than the one these six values expect, generate a matching
scoped credential against `minio-legacy` the same way
`docs/runbooks/production-setup.md` section 4/5 describes, and roll the six
values forward (not back) to that instead — do not skip straight to
declaring this rollback done.

This whole path only works because `minio-legacy` is still running (or
trivially restartable from the untouched volume) until step 7 explicitly
retires it — which is exactly why step 7 is its own step, done deliberately
once the new store is proven, not folded into step 6.

**If step 3, 4 or 5 goes wrong partway through:** re-run
`migrate-object-store.sh`. Every phase is idempotent by design (see the
script's own header comment for exactly how each one achieves that); nothing
it does is destructive to either endpoint.

**There is no rollback for step 7 once the volume is actually removed** —
that is why step 7 says to keep it, not delete it, and names a specific date
rather than "eventually."
