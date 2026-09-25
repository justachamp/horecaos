# ADR 0135: Object storage runtime: RustFS replaces MinIO

- Decision status: Accepted
- Implementation status: Partial — **2026-09-25 (re-audited against code, not
  copied from a wave report, per `docs/adr/README.md`'s 2026-08-24
  reconciliation):** every code-side target this record's original status
  line named has since changed. `compose.yaml`, `compose.production.yaml` and
  `deploy/compose.production.yml` all pin
  `rustfs/rustfs:1.0.0@sha256:8cc9801755448b71a786705ce76692c77e14936cccd87cf2fc31842e58f4d1ff`;
  `infra/production/ops/Dockerfile` installs the Alpine `aws-cli` package in
  place of the withdrawn `mc` binary; `infra/backup/backup.sh`, `restore.sh`
  and `rehearse-restore.sh` drive the AWS CLI against RustFS instead of `mc`;
  and all six MinIO-backed `GenericContainer` test classes
  (`CatalogImportRowServiceTests`, `S3AuditArchiveStoreTests`,
  `AuditPartitionArchiverTests`, `MediaLifecycleTests`,
  `MediaAssetIngestionServiceTests`, `BackupScriptTests`) now start the shared
  `ObjectStoreContainer` (RustFS) fixture instead. `application.yml` and
  `AuditArchiveStorageConfiguration` did **not** need to change — they only
  ever named the `HORECAOS_*_ENDPOINT` values and the generic `S3Client`/
  `S3Presigner` port this ADR's own Context section says the domain code
  stays coded to, never a MinIO-specific type, so the runtime swap under a
  stable `minio` network alias needed no application-code edit; that is the
  interface working as designed, not an unchecked box. Still not done, and
  the reason this stays `Partial` rather than `Built`: the scoped-credential
  provisioning path (checklist item 3 — production runs on the RustFS root
  credential for now, a tracked, explicit reduction in defence-in-depth, see
  `docs/runbooks/object-store-migration.md` step 2's "known gap" note), the
  actual pre-prod VM data migration and its restore rehearsal (checklist
  items 4-5 — the migration runbook's own header still reads "Last executed:
  never"), sealing the old MinIO volume (checklist item 6, which cannot
  happen before item 4), and the two open inputs (an upgrade/patch-cadence
  owner, and TLS termination in front of the S3 API). Advance this line again
  — to `Built`, or drop this addendum for a rewritten one — once the
  production migration in the runbook actually runs, not when its supporting
  code merges. (This line does not use `In progress` for the still-open items
  above: that token was retired for exactly the failure this re-audit exists
  to catch — status copied from a wave's own report rather than checked
  against code — and ADR 0000 lists it as a rejected alternative.)
- Date proposed: 2026-09-25
- Date decided: 2026-09-25
- Deciders: Ayubkhon Abbosov (platform owner — the withdrawal and the RustFS
  replacement are both his decision), Claude (architecture — verified the
  claims below against a running RustFS 1.0.0 container before this record
  was written)
- Depends on: ADR 0010 (media lifecycle — presigned PUT/GET, path-style),
  ADR 0027 (audit evidence — GOVERNANCE object lock for the archive),
  ADR 0028 (secrets are references, never values), ADR 0034 (S3-compatible-only
  portability rule, colocated topology), ADR 0073 (Proposed — a future rented-VM
  topology with a provider's own object storage; unaffected either way, see
  Alternatives)
- Supersedes / Superseded by: —
- Open inputs: RustFS's upgrade and patch cadence, and who watches its release
  feed and CVEs (owner); TLS termination in front of the object-store S3 API on
  the pilot box — today it is reached only over the internal `media`/`core`
  networks, never published, but the eventual rented-VM topology in ADR 0073
  will need this answered (owner/ops); distributed multi-node RustFS when a
  second host arrives — deferred to ADR 0034's scaling phase, not decided here
  (deferred; no owner needed until that trigger fires)

## Context

**MinIO withdrew its public images.** `quay.io/minio/minio` and
`quay.io/minio/mc` answer HTTP 401 to an anonymous pull, and Docker Hub's
`minio/minio` answers 404. Verified 2026-09-25 against the live registries.
Only local caches still hold either image — this machine's Docker cache and the
pre-prod VM's — so every fresh checkout, every CI runner, and every new
developer machine is already unable to bring up the `minio` service in
`compose.yaml` or the `GenericContainer("minio/minio")` instances six test
classes start. This is not a future risk to plan around; it is today's build
failure on anything that does not already have the image cached.

**What the platform actually needs from an object store**, independent of
which one runs it:

- Presigned `PUT`/`GET` URLs the AWS SDK v2 signs client-side and RustFS
  verifies server-side — ADR 0010's whole upload lifecycle depends on the
  signature being the enforcement point, not the application.
- `PutObject` with `x-amz-object-lock-mode: GOVERNANCE` and a retention date,
  readable back through `GetObjectRetention` — ADR 0027's audit-partition
  archive writes each closed year under Object Lock and reads the retention
  back before trusting it; a store that cannot do this cannot host that
  archive at all, and `HORECAOS_AUDIT_ARCHIVE_ENABLED` exists specifically so a
  store without lock support is told to stay off rather than archive into a
  bucket that can quietly be emptied.
- Bucket versioning, independent of object lock, for the backup buckets
  `infra/backup/backup.sh` writes to.
- Multipart upload for objects larger than a single `PUT`.
- Path-style addressing, because every S3-compatible target this platform has
  ever pointed at (MinIO, and now RustFS) serves buckets that way rather than
  as a subdomain, and `S3Client`/`S3Presigner` are already configured with
  `pathStyleAccessEnabled(true)` and region `us-east-1` for it.

None of that is provider-specific by requirement — ADR 0034's portability rule
already states "Object storage is S3-compatible only. MinIO here, S3 later,"
and that rule is what makes this ADR possible to write as a runtime swap
rather than a redesign.

**What was verified on RustFS 1.0.0, 2026-09-25**, against a running
container, image `docker.io/rustfs/rustfs:1.0.0`
(`sha256:8cc9801755448b71a786705ce76692c77e14936cccd87cf2fc31842e58f4d1ff`), GA
2026-09-16, Apache-2.0:

- `CreateBucket --object-lock-enabled-for-bucket` succeeds and auto-enables
  versioning on that bucket.
- `PutObject` with `x-amz-object-lock-mode: GOVERNANCE` and a
  retain-until-date succeeds, and `GetObjectRetention` reads the same value
  back.
- `DeleteObject` without a `versionId` on a locked object creates a delete
  marker rather than removing the object; `DeleteObject` with the locked
  object's own `versionId` is refused with `AccessDenied` ("Object is under
  GOVERNANCE retention").
- `PutBucketVersioning` / `GetBucketVersioning` round-trip correctly.
- A SigV4 presigned `GET` returns 200 when the URL's signed host matches the
  host the request is actually sent to — the same constraint ADR 0073 already
  recorded against a different provider, and the reason `HORECAOS_MEDIA_ORIGIN`
  has to be the public HTTPS name rather than an internal one.
- A 9 MB multipart upload completes.
- The image runs as a non-root user (`rustfs`, uid 10001), reads
  `RUSTFS_VOLUMES=/data` for its data path, serves the S3 API on 9000 and an
  optional console on 9001 gated by `RUSTFS_CONSOLE_ENABLE`, and answers
  `GET /health` with `200 {"status":"ok",...}`. It ships `curl`, `wget`, `nc`
  and `/bin/sh` — no `bash` — so a container healthcheck has to be
  `["CMD", "curl", "-sf", "http://localhost:9000/health"]`, not a `bash -c`
  form.
- Credentials come from `RUSTFS_ACCESS_KEY`/`RUSTFS_SECRET_KEY`, or
  `RUSTFS_ACCESS_KEY_FILE`/`RUSTFS_SECRET_KEY_FILE` for compose secrets. It
  also accepts `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD`, which this decision
  does not rely on — accepting an undocumented compatibility surface as the
  real interface is how a later RustFS release breaks the platform without
  warning. **Left unset, it falls back to a built-in default credential and
  warns loudly rather than refusing to start; that fallback must never be
  reachable in any environment this platform runs in.**
- RustFS's on-disk layout is not MinIO's. An existing `minio-data` volume
  cannot be mounted into a RustFS container and read; the only path between
  the two stores is the S3 API itself, object by object.

**What was not verified, because it does not exist yet to test**: RustFS
distributed/multi-node mode, its behavior under the platform's actual
production load, and its upgrade-to-upgrade compatibility — there has been
exactly one release.

## Decision

**RustFS 1.0.0, pinned by digest, is the S3-compatible object-storage runtime
in every environment this platform runs in** — local `compose.yaml`, CI
Testcontainers, staging, and production — replacing MinIO everywhere it
appears today. The pin is
`rustfs/rustfs:1.0.0@sha256:8cc9801755448b71a786705ce76692c77e14936cccd87cf2fc31842e58f4d1ff`,
not a floating `:latest` or `:1.0` tag: a store this platform's audit archive
and backups depend on does not get to change underneath a routine `docker
compose pull`.

**The AWS CLI replaces `mc`** for every seed job, backup script, restore
script, and rehearsal script that shelled out to it — `mc`'s own image is
withdrawn the same way MinIO's is, and the AWS CLI is a Python package
published and maintained independent of any S3-compatible vendor, which is
the property that matters here. `amazon/aws-cli` (public, pinned by tag or
digest) covers container contexts; Alpine's `aws-cli` apk package covers the
`ops` image built from `postgres:18-alpine`. Every `mc` invocation this
platform has (`mb`, `version enable`, `cp`, `ls`, `rm --older-than`, `ready`)
has a direct `aws s3`/`aws s3api` equivalent; none of them depend on a MinIO
admin API this decision has not verified RustFS provides.

**The existing MinIO volumes are not reused.** Because the on-disk layout is
incompatible, the pre-prod VM's live media, audit-archive, and backup objects
migrate by copying them over the S3 API into RustFS buckets — list, get,
put, verify checksum — the same shape as ADR 0010's own legacy-filesystem
migration model, applied to a second source. The old MinIO volume is not
deleted at cutover. It is sealed and kept, read-only, as evidence and a
rollback path until the longest applicable audit retention window has
elapsed — the same "do not delete the only rollback evidence" reasoning
ADR 0010 already applied to the legacy filesystem, now applied to the store
being replaced.

**The console stays disabled in every non-development environment.**
`RUSTFS_CONSOLE_ENABLE` is unset (or false) in staging and production
configuration, matching `MINIO_BROWSER: off` today — nothing about this
migration relaxes that.

**ADR 0034's portability rule is unchanged and this decision depends on it
holding.** The platform still codes to `ObjectStorage`/`S3Client` and
S3-compatible semantics, never to a RustFS-specific endpoint shape or
response header. This ADR is evidence the rule works: the runtime under it
changed without the domain code, the port, or ADR 0010's specification
changing at all.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Mirror the cached MinIO image to a private registry (GHCR) | Buys time, not a fix — the vendor withdrew the image rather than merely moving it, and a private mirror means this platform now owns redistributing a binary whose license terms and future availability it does not control, on top of still running software with no path to a security patch | RustFS proves unstable in the pilot window, and a mirrored MinIO is needed as a bridge while a different store is evaluated |
| Build MinIO from source | Trades a withdrawn public image for owning MinIO's entire release engineering — compiling, patching, and publishing an image ourselves, for a single-operator platform (ADR 0034) that cannot absorb that load, which is a larger version of the exact operational burden the withdrawal is forcing a decision about | A second infrastructure engineer exists whose job includes owning that pipeline |
| Garage | Real S3-compatible object storage, written in Rust, genuinely worth another look | Object lock, versioning, and multipart are verified equivalent by the same kind of probe run against RustFS here, and a second engineer exists to run the comparison |
| SeaweedFS | Mature and widely deployed, but its master/volume/filer topology is more moving parts to operate than a single-binary store for a one-node pilot (ADR 0034's "operated by one person" constraint), and its Object Lock support has not been probed the way RustFS's has here | The topology grows past one node and a distributed store's benefits are worth the extra operational surface |
| S3Mock, for tests only | Would unblock Testcontainers without touching compose or production, but leaves the platform testing against a different store than the one it runs on — precisely the dev/prod gap ADR 0034 exists to avoid, and the compose services still could not start on a fresh checkout either way | Never, while ADR 0034's "the technology in production is the one exercised on a developer machine every day" holds |
| A hyperscaler bucket now, per ADR 0073 | ADR 0073 proposes a rented VM with a cloud provider's own object storage, but that ADR is still `Proposed` — its SigV4 confirmation and provider contract are open — and it is a hosting-topology decision, larger and slower than swapping the storage runtime underneath the topology that exists today | ADR 0073's open inputs close and it moves to `Accepted`; at that point this ADR's "which self-hosted store" question is superseded by "which provider," not reopened |

## Consequences

### Positive

- Unblocks every environment that does not already have a cached MinIO image
  — a fresh checkout, a new CI runner, and a new developer machine can bring
  the stack up again.
- The runtime is pinned by digest rather than a tag, closing the exact gap
  that let a routine pull start failing with no warning.
- The specific behaviors ADR 0010 and ADR 0027 depend on — presigned PUT/GET
  with host-matching, GOVERNANCE object lock read back correctly, versioning,
  multipart — are independently verified against RustFS rather than assumed
  from its own claim of S3 compatibility.
- The AWS CLI is maintained by a party independent of any single S3-compatible
  vendor, so this migration does not trade one single-vendor client
  dependency (`mc`) for another.
- ADR 0034's portability rule is proven under real pressure: the runtime
  changed and the domain code did not.

### Negative

- RustFS 1.0.0 is nine days past its GA date as of this record, with a
  materially smaller operational track record than MinIO had after years of
  production use — this platform is an early production user of it, not a
  follower of an established pattern.
- Its console and admin surface differ from MinIO's, and this record does not
  verify a RustFS equivalent to `mc admin user add`/`policy create`/`policy
  attach` — the per-purpose scoped service-account credentials ADR 0034's
  deploy runbook provisions (a media-only credential, a backup-only
  credential) may need a different mechanism than the one documented today,
  and that mechanism is not yet confirmed to exist.
- There is no `mc` at all, in any image, so every script, runbook, and
  operator habit built around it — not only the ones this record's own wave
  touches — has to be rewritten, and a step missed in that rewrite fails
  silently until someone runs it.
- Nobody owns RustFS's upgrade and patch cadence yet (see Open inputs); a
  single-operator platform (ADR 0034) is the worst-positioned to absorb a
  missed security release.
- The pre-prod VM's live media, audit-archive, and backup objects need an
  actual migration window — copy, verify, cut over — not a volume remount,
  and that window carries real risk to data already in production use, small
  as the pilot is today.

### Accepted trade-offs

- **Running a nine-day-old GA release as the production object-storage
  runtime, deliberately**, because the alternative — every environment unable
  to start `minio` at all — is the worse and more immediate failure. This
  trade is revisited the moment RustFS's own track record, not this
  platform's patience, says otherwise.
- **The sealed MinIO volume is kept, unread, for the full audit-retention
  window**, which costs disk space during that window in exchange for not
  destroying the one rollback path if the RustFS migration turns out to have
  silently corrupted or dropped an object.

## Specification

This record does not restate ADR 0010's media model or ADR 0027's audit
archive specification — neither changes. What is specific to the runtime
swap:

**Image and pin.**
`rustfs/rustfs:1.0.0@sha256:8cc9801755448b71a786705ce76692c77e14936cccd87cf2fc31842e58f4d1ff`,
everywhere. Alpine-based, runs as `rustfs` (uid 10001), non-root.

**Configuration.** `RUSTFS_VOLUMES=/data` for the data directory;
`RUSTFS_ACCESS_KEY`/`RUSTFS_SECRET_KEY` (or the `_FILE` forms for compose
secrets) for credentials — resolved as ADR 0028 references in every
environment above local development, never a literal in an env file;
`RUSTFS_CONSOLE_ENABLE` unset/false outside local development;
`RUSTFS_CONSOLE_ADDRESS` only when a console is deliberately turned on for
local debugging.

**Health.** `GET http://<host>:9000/health` → `200 {"status":"ok",...}`. A
compose healthcheck must use `curl` (or `wget`), never a `bash -c` form — the
image has no `bash`.

**Client tooling.** The AWS CLI, with `--endpoint-url` pointed at the RustFS
S3 API and `AWS_EC2_METADATA_DISABLED=true` set so the SDK does not try to
reach an instance-metadata service that does not exist here. Region
`us-east-1`, path-style addressing — both already the platform's own defaults.

**Migration mechanics for an existing MinIO volume.** List objects in the
source bucket, `GetObject` each one, verify its checksum, `PutObject` into the
matching RustFS bucket (re-applying Object Lock mode and retention where the
source object carried it), verify the destination checksum, record the
mapping. This is the same shape as ADR 0010's legacy-filesystem migration —
inventory, copy without overwriting a different checksum, verify, reconcile —
applied to a second store instead of a filesystem. The source volume is not
deleted; it is sealed and mounted read-only, if mounted at all, until the
retention window this decision's Open inputs still need to close on TLS and
cadence does not block.

## Rollout and rollback

Bring RustFS up alongside the sealed MinIO volume rather than in place of it.
Migrate objects by the S3-copy mechanics above, verify counts and checksums
against the source, then cut the application's `HORECAOS_MEDIA_ENDPOINT` /
`HORECAOS_AUDIT_ARCHIVE_ENDPOINT` / `HORECAOS_BACKUP_S3_ENDPOINT` over to
RustFS. Rollback is pointing those same variables back at the still-sealed
MinIO instance — which is exactly why it is sealed rather than deleted, and
why cutover does not happen until a restore from the RustFS-hosted copy has
been rehearsed, the same gate ADR 0034 already set for the off-site backup
destination.

## Implementation checklist

- [x] Pin `rustfs/rustfs:1.0.0@sha256:...` in every compose file that names an
      object-store image today. Done 2026-09-25: `compose.yaml`,
      `compose.production.yaml` and `deploy/compose.production.yml` all pin
      the full digest.
- [x] Replace every `mc` invocation (seed jobs, `backup.sh`, `restore.sh`,
      `rehearse-restore.sh`, the `ops` image) with the AWS CLI equivalent.
      Done 2026-09-25: verified no `mc` binary use remains outside comments
      explaining the replacement (`grep` for `mc ` / `minio/mc` in
      `infra/backup/*.sh` and `infra/production/ops/Dockerfile`).
- [ ] Confirm — or build — a scoped-credential provisioning path for RustFS
      equivalent to the MinIO service accounts the deploy runbook creates
      today; do not run production on the root credential if a scoped path
      exists.
- [ ] Migrate the pre-prod VM's media, audit-archive, and backup objects by
      S3 copy; verify checksums; do not delete the source volume.
- [ ] Rehearse a restore against the RustFS-hosted copy before cutting the
      application over.
- [ ] Seal the old MinIO volume read-only and record the date the audit
      retention window it must survive actually elapses.
- [ ] Name an owner for RustFS's upgrade/patch cadence (Open inputs).
- [ ] Resolve TLS termination in front of the object-store S3 API before the
      ADR 0073 rented-VM topology needs it (Open inputs).

## Exit criteria

Every environment — local, CI, staging, production — starts its object store
from the pinned RustFS digest with no MinIO image referenced anywhere; the
pre-prod VM's media, audit-archive, and backup objects exist in RustFS with
verified checksums matching the sealed MinIO source; a restore has been
rehearsed against the RustFS-hosted backup copy; no script or runbook still
names `mc`; and the sealed MinIO volume's retention date is recorded rather
than assumed.

## References

- [ADR 0010: S3 media lifecycle and filesystem migration](../partial/0010-s3-media-lifecycle-and-filesystem-migration.md)
- [ADR 0027: Audit evidence and the approval model](../built/0027-audit-evidence-and-approval-model.md)
- [ADR 0028: Secrets management and credential lifecycle](../partial/0028-secrets-management-and-credential-lifecycle.md)
- [ADR 0034: Hosting environments, topology, and data residency](../partial/0034-hosting-environments-topology-and-data-residency.md)
- [ADR 0073: Production runs on a rented machine in Uzbekistan, not on hardware we own](../not-started/0073-production-runs-on-a-rented-vm-in-country.md)
- [`infra/backup/README.md`](../../../infra/backup/README.md) — the backup path this migration's client-tooling replacement runs inside
- RustFS 1.0.0 release, GA 2026-09-16, Apache-2.0 — `docker.io/rustfs/rustfs:1.0.0`
