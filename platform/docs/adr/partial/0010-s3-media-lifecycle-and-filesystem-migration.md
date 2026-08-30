# ADR 0010: S3 media lifecycle and filesystem migration

- Decision status: Accepted
- Implementation status: Partial — the single-asset upload lifecycle works end
  to end and now renders its derivatives through a durable pipeline; a media
  reference is checked by the database; the legacy filesystem migration has not
  started. `V0015` creates `media.assets` with the
  `PENDING_UPLOAD`/`UPLOADED`/`AVAILABLE`/`REJECTED`/`DELETION_REQUESTED`/
  `DELETED` states, a platform-allocated immutable `object_key`, and declared
  versus verified content-type/size/checksum columns; `MediaAsset`,
  `MediaAssetService`, the `ObjectStorage` port and `S3ObjectStorage` implement
  the domain lifecycle and tenant-aware storage, and `MediaController` exposes
  presigned `upload-requests`, `finalize`, read and `download-url` behind
  `MEDIA_UPLOAD` / `MEDIA_READ` — so the third and fifth checklist boxes are in
  fact done, except that verification is synchronous inside `finalizeUpload`
  (read back from the store, then `AVAILABLE`) rather than a separate validation
  worker. MinIO is in `compose.yaml` and `MediaLifecycleTests` proves
  tenant isolation, signature-enforced size and content-type, disallowed types,
  finalize idempotency and that a pending asset is not servable. `V0058` adds
  `media.derivatives`, `media.migration_runs`, `media.legacy_path_mappings` and
  `media.migration_items`, plus `media.assets.legacy_path` and the tenant-scoped
  `uq_media_assets_tenant_scoped` key that lets any reference to an asset be
  checked by the database; `DerivativeVariant`, `MediaDerivative`,
  `ImageIoDerivativeRenderer`, `MediaDerivativeService` and
  `JdbcMediaDerivativeStore` render and record the fixed variant set, and
  `DerivativeRenderingTests` exercises them. `V0065` connects that renderer to
  the lifecycle and makes a media reference checkable — the Implementation status
  line above is advanced for both. Reaching `AVAILABLE` now runs one short
  transaction that marks the asset, writes a `media.derivative_jobs` row and
  publishes `MediaAssetAvailable`, which `MediaOutboxEventListener` appends to
  the outbox on `BEFORE_COMMIT`; the event is catalogued on `media.events` with a
  JSON schema and a row in `docs/domains/events.md`, and the existing relay
  publishes it, so no Kafka call happens inside the transaction.
  `MediaDerivativeWorker` claims jobs under a lease — `V0054`'s claim query,
  unchanged — and calls `MediaDerivativeService` outside any transaction, which
  is why the render is not an `InboxHandler`: `InboxExecutor` runs a handler
  inside the transaction that records it, and an object-store download plus three
  decodes there would hold one of ten pooled connections for the length of both.
  `MediaLifecycleTests` now drives the whole path — upload, finalize, one poll —
  against PostgreSQL and MinIO with real `media.derivatives` rows rather than the
  in-memory stand-in, and proves that a replayed trigger produces exactly one set
  at the same keys, that a malformed original finishes its own job while the next
  asset in the same batch still renders, and that an asset deleted before its
  render is abandoned rather than retried forever.

  The derivative pipeline's ceiling is now stated in the unit it was always
  about. `MAX_IMAGE_PIXELS` bounded a quantity whose decoded cost varies
  eightfold — a pixel is one byte or eight, decided by two bytes of `IHDR` the
  probe never read — so a 311KB PNG declaring 8000x5000 at 16-bit RGBA passed
  every gate and decoded to 305MB. `ImageProbe` now reads the sample depth and
  the channel count of every format it supports (PNG's bit depth and colour
  type, a JPEG frame's precision and component count, WebP's fixed 8-bit RGBA,
  AVIF's `av1C` bit-depth flags), `ProbedImage.decodedBytes()` is what both the
  upload gate and the renderer decide on, and `ImageCostLimits` holds the three
  limits — 128MiB of heap, forty megapixels of scaling work, twelve thousand
  pixels a side — in one place rather than two copies. `ImageDerivativeRenderer`
  no longer answers with an `Optional` that meant both "no decoder here will ever
  read this" and "this render failed": `Unsupported` is settled and completes the
  job, `Failed` carries a code, leaves `MediaDerivativeService` as a
  `DerivativeRenderFailedException`, and spends the job's retry budget. It also
  renders every missing variant from a single decode rather than one decode per
  variant. `MediaDerivativeWorker` catches `Error`, which it has to: the JDK's
  two readers disagree — `PNGImageReader` wraps a failed allocation in an
  `IIOException` while `JPEGImageReader` lets the `OutOfMemoryError` out — so the
  JPEG case passed through every catch, left the job `LEASED` and was re-claimed
  on every lease expiry without ever consulting `max-attempts`. `DecodeError`
  writes down which errors are a property of the input and may be recorded (a
  failed heap allocation, an exhausted stack) and which must still kill the
  process (Metaspace, native-thread and direct-buffer exhaustion, `LinkageError`,
  `InternalError`), and the worker settles a job's lease before rethrowing either
  way; a claim whose attempt count is already past the limit is abandoned before
  any work starts, so no job can be re-claimed indefinitely on any path.
  `MediaLifecycleTests` runs on a movable clock now, which reaches three
  documented paths a `Clock.fixed` made unreachable from any test — the
  dead-worker reclaim, the retry after a `reschedule` whose `due_at` is in the
  future, and the `attemptCount() >= maximumAttempts` abandonment — and a real
  300KB raster bomb is rejected at finalize, settled by the renderer when the row
  predates that gate, and abandoned with `RENDER_OUT_OF_MEMORY` recorded when the
  decode runs out of memory. `V0065` also gives
  `catalog.media_relations` the composite foreign key `(media_asset_id,
  tenant_id)` against `uq_media_assets_tenant_scoped`, moving any row that cannot
  satisfy it into `catalog.media_relation_orphans` first, so a cross-tenant or
  invented media reference is refused by PostgreSQL and
  `CatalogPublicationTests` proves it. Not built: the media-owned `brand_media` /
  `location_media` / `product_media` tables this record names — `catalog` owns
  the only relation anything writes and it is now a checked reference, so three
  empty media-owned tables were not added, and no relation is brand-scoped by the
  database because `media.assets` carries no key a brand-scoped reference could
  point at; the separate validation worker, since verification is still
  synchronous inside `finalizeUpload`; WebP and AVIF renditions, because the JDK
  ships no encoder for either, so every variant is JPEG and a WebP or AVIF
  original gets no derivative at all; any inventory, copy, checksum or
  reconciliation tooling for the legacy filesystem, so `media.migration_runs` and
  `media.migration_items` have never held a row and no legacy image has a
  migration path (`LegacyPath` and `LegacyPathMapping` are value objects with a
  unit test and no store); malware scanning; media audit facts, the worker's
  `horecaos.media.derivative.jobs` counters being the only observability the module
  has; and the production media origin, still listed as unproven in
  `infra/production/README.md`.
- Date proposed: 2026-08-19
- Date decided: 2026-08-20
- Deciders: Ayubkhon Abbosov (platform architecture)
- Depends on: ADR 0002, ADR 0004, ADR 0029, ADR 0034
- Supersedes / Superseded by: —
- Open inputs: Media classification, size/type limits, retention, and CDN provider (product, legal)

## Context

Legacy media is stored as filesystem paths. A scalable SaaS platform needs
tenant-aware object ownership, immutable keys, direct uploads, validation,
derivatives, private access controls, restartable copy jobs, and checksum-based
cutover. Persisting environment-specific public URLs would prevent safe CDN,
bucket, and domain changes.

## Decision

Create a `media` aggregate backed by PostgreSQL metadata and an S3-compatible
private object store. Business modules reference `MediaAssetId`; they never
own filesystem methods, bucket credentials, or mutable public URLs. New writes
go through a presigned upload lifecycle. Legacy files migrate with recorded
source/destination checksums and a dual-read rollback period.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Keep filesystem storage behind shared network storage | Blocks horizontal scaling of stateless API replicas, offers no signed access model, and makes backup and disaster recovery a volume-snapshot problem rather than an object-lifecycle one | Never |
| Store public URLs on business rows, as the legacy system does | Couples business data to an environment. Changing bucket, CDN, or domain would require rewriting business rows, and a rollback becomes a data migration | Never |
| Public bucket with predictable object keys | Enumerable across tenants, and no way to revoke access to a single asset. Private buckets with signed or CDN-fronted access cost little more | Never |
| Store binaries in PostgreSQL as `bytea` or large objects | Inflates WAL, backups, and restore times for data that never needs transactional semantics, and puts image bytes in the same failure domain as orders | Never |
| Generate derivatives on demand at the edge with an image proxy | Genuinely attractive and cheaper to build. Rejected for the first release because validation, type detection, and malware scanning must complete before an asset becomes available, and on-demand CPU cost is unpredictable under a menu-browsing load spike | Derivative generation becomes a measured bottleneck. The asset model already supports adding it without changing business references |
| Trust the client's declared content type, size, and checksum at finalize | A client can claim anything. Reading trusted object metadata server-side is the only proof that matters | Never |
| Delete legacy files at cutover | Removes the only rollback evidence for a migration whose reconciliation is not yet proven | A separately approved destructive runbook after the retention window, per ADR 0024 |

## Media asset lifecycle

```text
REQUESTED -> UPLOADING -> UPLOADED -> VALIDATING
          -> PROCESSING -> AVAILABLE
          -> REJECTED or FAILED
AVAILABLE -> DELETION_PENDING -> DELETED
```

State transitions are explicit methods and record actor, time, reason,
correlation, and optimistic version. An object existing in S3 does not by itself
make an asset available.

## Physical model

### `media.media_assets`

```text
id uuid primary key
tenant_id uuid not null
object_key varchar(1024) not null
original_filename varchar(255) null
content_type varchar(127) not null
byte_size bigint not null
checksum_sha256 char(64) not null
visibility varchar(24) not null
status varchar(24) not null
width integer null
height integer null
duration_ms bigint null
created_by varchar(255) not null
version bigint not null
uploaded_at, validated_at, available_at timestamptz null
deletion_requested_at, deleted_at timestamptz null
legacy_path varchar(2048) null
created_at, updated_at timestamptz not null
```

Constraints include unique `(tenant_id, object_key)`, positive size/dimensions,
lowercase SHA-256 format, lifecycle consistency, and immutable tenant/key after
allocation.

### Ownership relations

Use constrained relations rather than a polymorphic owner string:

```text
media.brand_media(tenant_id, brand_id, asset_id, role, sort_order)
media.location_media(tenant_id, brand_id, location_id, asset_id, role, sort_order)
media.product_media(tenant_id, brand_id, product_id, asset_id, role, sort_order)
```

Composite foreign keys prevent attaching another tenant or brand's asset.

### Derivatives

`media.media_derivatives` records derivative ID, parent asset, variant code,
immutable object key, content type, size, checksum, dimensions, status, and
processor version. Examples: `w400-webp`, `w800-avif`, and `thumbnail`.

## Object key policy

Generate keys server-side from trusted IDs:

```text
tenants/{tenantId}/{ownerType}/{ownerId}/{assetId}/original
tenants/{tenantId}/{ownerType}/{ownerId}/{assetId}/w400.webp
```

Do not use an original filename, tenant slug, brand slug, email, or phone as a
key. Keys are immutable; replacement creates a new asset.

## Upload API

```text
POST /api/v1/media/assets/upload-requests
POST /api/v1/media/assets/{assetId}/finalize
GET  /api/v1/media/assets/{assetId}
POST /api/v1/media/assets/{assetId}/deletion-requests
```

Allocation validates tenant ownership, expected content type, maximum length,
checksum, visibility, and intended relation. Return a short-lived presigned
PUT/POST constrained by key, size, checksum, and content type. Finalize reads
trusted object metadata and never accepts the client's claim as proof.

## Validation and processing

- Verify object exists at the allocated immutable key.
- Compare byte size and SHA-256 checksum.
- Detect actual file type; do not trust extension/content-type alone.
- Reject active content where the use case expects images.
- Decode dimensions safely with resource limits.
- Run malware/security scanning according to classification.
- Strip unsafe metadata when producing public derivatives.
- Generate derivatives asynchronously through outbox/Kafka/inbox.
- Mark available only after required validation and derivatives succeed.

## Access policy

- Buckets are private with public-access blocking.
- Public catalog media is served through a CDN/private origin, not public S3
  ACLs.
- Private assets use short-lived signed delivery URLs or authenticated proxy
  access.
- CORS permits only exact approved origins and upload methods.
- API and migration roles have separate least-privilege permissions.
- Encryption, access logs, metrics, lifecycle, and non-production separation
  are mandatory.

## What is built so far

The asset model, the `ObjectStorage` port, its S3 adapter, the presigned upload
lifecycle, and the four HTTP endpoints. `MediaLifecycleTests` runs against MinIO
in a container rather than a stub, because the property under test is whether a
signature actually constrains an upload, and a stub would simply agree that it
does.

**The signature is the enforcement point, not the application.** Content type and
content length are signed into the presigned PUT, so an upload of the wrong size
or the wrong type is refused by the store with a 403 and nothing is written. That
is what makes a presigned URL a bounded capability rather than a
write-anything-here token: a leaked URL cannot be used to upload a gigabyte or a
different file. The service's own checks at finalize remain as defence in depth
against a store that does not enforce signed headers.

**Finalize reads the store, never the request.** The finalize endpoint takes no
body. A client asserting "it's a 200KB JPEG" is precisely the claim an attacker
would make, so the trusted values come from `HeadObject` and are kept in separate
columns from the declared ones — keeping both is what lets an operator later see
that a client claimed one thing and uploaded another.

**SVG is excluded from the allowed types.** It is a document format that can
carry script, so serving user-supplied SVG from our own origin is stored
cross-site scripting.

Credentials are resolved from ADR 0028 rather than read from properties. They are
read once at startup because the SDK client holds them, so rotating object-store
credentials currently needs a restart — recorded here as a known limitation
rather than left to be discovered.

Not yet built: derivative generation, malware scanning, the CDN origin, the
`media.migration_runs` / `migration_items` / `legacy_path_mappings` tables, and
the dual-read fallback. Those belong to the legacy migration phase rather than
to the foundation, and the migration cannot start before the catalog that
references the assets exists.

## Storage abstraction

Define a media-owned `ObjectStorage` port for allocate/presign/head/read/copy/
delete. Provider SDK types remain in the S3 adapter. Support an S3-compatible
test service but avoid coding to provider-specific public URL conventions.

## Legacy migration model

Add:

```text
media.migration_runs
media.migration_items
media.legacy_path_mappings
```

Each item records tenant/owner mapping, source path, safe normalized path,
source size/checksum, target asset/key, copy status, destination size/checksum,
attempts, error, and timestamps. Unique source identity makes retries
idempotent.

Migration phases:

1. Inventory files and all database references read-only.
2. Classify missing, orphaned, duplicate, unsafe, and ambiguous ownership.
3. Approve legacy path to tenant/constrained owner mappings.
4. Copy without overwriting an existing different checksum.
5. Verify source and destination size/checksum.
6. Create asset/ownership references while retaining legacy evidence.
7. Enable S3 read with filesystem fallback.
8. Reconcile references, counts, bytes, checksums, access class, and errors.
9. Cut new writes, then reads, separately.
10. Preserve legacy storage through the rollback/retention window.

## Events

```text
MediaUploadRequested
MediaAssetUploaded
MediaAssetAvailable
MediaAssetRejected
MediaAssetDeletionRequested
MediaAssetDeleted
MediaMigrationItemCompleted
```

Partition by asset ID. Events carry object key only where the consumer is
authorized and never expose presigned URLs.

## Testing

- S3-compatible integration tests cover constrained presign, head, checksum,
  access denial, and deletion lifecycle.
- Tenant A cannot read/sign/attach tenant B's asset.
- Mismatched content type, size, checksum, dimensions, and unsafe filename are
  rejected.
- Duplicate finalize/copy is idempotent.
- Processor retry cannot create duplicate derivative relations.
- Migration resumes after interruption and detects destination corruption.
- Dual-read fallback and rollback are tested with missing objects.

## Rollout and rollback

Enable new uploads for internal tenants first. Copy legacy assets in batches,
then enable S3 reads with filesystem fallback. Rollback disables S3 reads/new
cohorts while retaining copied objects and mappings. Initial cutover never
deletes legacy files; deletion is a later separately approved action.

## Consequences

### Positive

- Media becomes tenant-owned, verifiable, and independent of environment URLs.
- Buckets, CDNs, and domains can change without touching business rows.
- The migration is restartable and evidence-preserving, so a failed batch is a
  retry rather than an incident.

### Negative

- The upload path is now multi-step, so every client must implement request,
  upload, and finalize instead of posting a file.
- Derivative generation is asynchronous, so an asset is briefly uploaded but not
  yet available, and product surfaces must handle that state.
- Object storage, CDN, and signing configuration add infrastructure to operate
  and secure in every environment.

### Accepted trade-offs

- Legacy files are retained through the rollback window, so storage is paid for
  twice during migration.
- Validation before availability costs latency on first upload in exchange for
  never serving an unscanned or misdeclared file.

## Implementation checklist

- [ ] Approve media classification per ADR 0029, size/type limits, retention, and CDN policy.
- [~] Add asset, derivative, relation, and migration tables. Assets are `V0015`; derivatives, `migration_runs`, `legacy_path_mappings` and `migration_items` are `V0058`, which also adds the tenant-scoped unique key on `media.assets` that a checked reference needs; `media.derivative_jobs` is `V0065`. The relation is now checked: `V0065` gives `catalog.media_relations` a composite foreign key `(media_asset_id, tenant_id)` against that key, having first moved any row it could not satisfy into `catalog.media_relation_orphans`. What is still not built is the media-owned split this record sketches — `brand_media`, `location_media`, `product_media` — because `catalog` owns the only relation anything writes; and no relation is brand-scoped by the database, since `media.assets` has no key a brand-scoped reference could point at.
- [x] Implement domain lifecycle and tenant-aware storage port.
- [ ] Provision S3-compatible local/test infrastructure and production IAM plan.
- [ ] Implement presigned allocation/finalize APIs and validation worker. The APIs are built — `MediaController` allocates a constrained presigned PUT and finalizes from `HeadObject`, never from the request body. There is no validation worker: verification runs synchronously inside `finalizeUpload`, which is adequate for a head and a header read and is not what this box asked for.
- [~] Implement derivative pipeline through outbox/inbox. Built and reached from `finalizeUpload`: one transaction marks the asset `AVAILABLE`, writes a `media.derivative_jobs` row (`V0065`) and publishes `MediaAssetAvailable`, which `MediaOutboxEventListener` appends to the outbox on `BEFORE_COMMIT` for the relay to publish on `media.events`. `MediaDerivativeWorker` claims jobs under a lease and renders outside any transaction. The inbox half is deliberately absent rather than pending: `InboxExecutor` runs a handler inside the transaction that records it, so a handler that downloaded an original and decoded it three times would hold a pooled connection for the length of both — the render is owed by a durable job row instead, and nothing consumes `media.events` yet.
- [ ] Implement inventory/copy/checksum/reconciliation tooling.
- [ ] Add metrics, audit, access-denial, corruption, resume, and rollback tests.

## Exit criteria

New assets reach `AVAILABLE` only after verified private-object upload and
processing, and a legacy migration batch can copy, checksum, reconcile, resume,
and roll back without deleting filesystem evidence.
