-- ADR 0010: the derivative pipeline's durable work queue, and a media reference
-- the database checks rather than trusts.
--
-- Two halves of the same record. The first gives derivative rendering somewhere
-- to be owed from, so an asset that reached AVAILABLE has a row saying its
-- renditions are still due. The second turns `catalog.media_relations` from a
-- pair of uuids that happen to be next to each other into a reference the
-- database will not let point outside its own tenant.

-- ---------------------------------------------------------------------------
-- 1. Derivative rendering work
-- ---------------------------------------------------------------------------

-- Why a job row and not the outbox itself: the outbox is for facts leaving the
-- platform, and its relay's only verb is "publish". Rendering is CPU-bound work
-- with an object-store round trip on either side of it, and it needs a lease, an
-- attempt count and a due time — the three things V0054's sourcing jobs needed
-- for the same reason. The shape is deliberately V0054's, because a second
-- pattern for the same problem is a second chance to get FOR UPDATE SKIP LOCKED
-- subtly wrong.
--
-- The row is written in the same transaction that marks the asset AVAILABLE, so
-- there is no window in which an asset is displayable and nothing will ever
-- render it. The `MediaAssetAvailable` outbox fact is appended in that same
-- transaction and is a separate thing: the fact tells the rest of the platform,
-- the job tells this module what it still owes.
CREATE TABLE media.derivative_jobs (
    job_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    asset_id uuid NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'PENDING',
    due_at timestamptz NOT NULL DEFAULT now(),
    attempt_count integer NOT NULL DEFAULT 0,

    -- The lease. A worker claims by writing a token, a holder and a deadline in
    -- one conditional update; a worker that dies leaves a lease that expires
    -- rather than a job nobody may touch.
    lease_token uuid,
    leased_until timestamptz,
    leased_by varchar(128),

    -- A code, never a message. A render failure's most tempting log line is the
    -- filename the customer uploaded, and ADR 0029 does not allow it here.
    last_error_code varchar(48),
    last_error_at timestamptz,

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    -- Composite, against the tenant-scoped key V0058 added, so a job cannot be
    -- hung on another tenant's asset.
    CONSTRAINT fk_derivative_job_asset FOREIGN KEY (asset_id, tenant_id)
        REFERENCES media.assets (asset_id, tenant_id),
    CONSTRAINT ck_derivative_job_status CHECK (status IN (
        'PENDING', 'LEASED', 'COMPLETED', 'ABANDONED')),
    CONSTRAINT ck_derivative_job_attempts CHECK (attempt_count >= 0),
    -- A lease is a token, a holder and a deadline together. Any one of them
    -- alone is a job that either nobody can claim or everybody can.
    CONSTRAINT ck_derivative_job_lease_triple CHECK (
        (lease_token IS NULL) = (leased_until IS NULL)
        AND (lease_token IS NULL) = (leased_by IS NULL)),
    CONSTRAINT ck_derivative_job_leased_has_lease CHECK (
        status <> 'LEASED' OR lease_token IS NOT NULL),
    CONSTRAINT ck_derivative_job_error_pair CHECK (
        (last_error_code IS NULL) = (last_error_at IS NULL))
);

-- One outstanding job per asset. Two would be two workers decoding the same
-- photograph; the derivative rows would still collapse to one set, but the CPU
-- and the object writes would be paid twice.
--
-- Deliberately partial rather than total: a finished job must not stop a later
-- re-render being asked for — a processor-version sweep is exactly that — and
-- the render itself is idempotent, so a second pass over an asset that already
-- has every variant costs three lookups.
CREATE UNIQUE INDEX ux_derivative_job_one_active
    ON media.derivative_jobs (tenant_id, asset_id)
    WHERE status IN ('PENDING', 'LEASED');

-- The claim query: due, and either unleased or holding an expired lease.
CREATE INDEX ix_derivative_job_claimable
    ON media.derivative_jobs (due_at)
    WHERE status IN ('PENDING', 'LEASED');

COMMENT ON TABLE media.derivative_jobs IS
    'ADR 0010 derivative rendering owed for an asset. Written with the AVAILABLE transition; drained by a leased worker outside any transaction.';

GRANT SELECT, INSERT, UPDATE ON media.derivative_jobs TO horecaos_application;

-- ---------------------------------------------------------------------------
-- 2. A media reference the database checks
-- ---------------------------------------------------------------------------

-- `catalog.media_relations` (V0016) has carried `tenant_id` and `media_asset_id`
-- since it was created and has never related them to anything. ADR 0010's
-- physical model says composite foreign keys prevent attaching another tenant's
-- asset, and V0058 added `uq_media_assets_tenant_scoped (asset_id, tenant_id)`
-- for exactly this reference — the two columns, in that order, and nothing else.
-- Until now nothing pointed at it, so a row naming a deleted asset, a fabricated
-- uuid, or another tenant's photograph inserted cleanly.

-- Evidence before enforcement. A relation that cannot satisfy the constraint is
-- moved here rather than deleted, because the row is the only record that
-- somebody once attached that asset to that product, and an operator asking why
-- a menu lost an image needs to see it. ADR 0024's rule for the legacy
-- migration — never destroy the evidence a cutover was wrong — is the same rule.
CREATE TABLE catalog.media_relation_orphans (
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    entity_type varchar(32) NOT NULL,
    entity_id uuid NOT NULL,
    media_asset_id uuid NOT NULL,
    role varchar(32) NOT NULL,
    sort_order integer NOT NULL,
    quarantine_reason varchar(64) NOT NULL,
    quarantined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (entity_type, entity_id, media_asset_id, role)
);

COMMENT ON TABLE catalog.media_relation_orphans IS
    'Rows removed from catalog.media_relations by V0065 because no media asset of that tenant matched. Retained as evidence; never read by the storefront.';

GRANT SELECT, INSERT ON catalog.media_relation_orphans TO horecaos_application;

-- Move first, constrain second. An ALTER that fails on real data is not a
-- migration, and "there is probably nothing in that table" is not a plan.
WITH orphaned AS (
    DELETE FROM catalog.media_relations AS relation
    WHERE NOT EXISTS (
        SELECT 1
        FROM media.assets AS asset
        WHERE asset.asset_id = relation.media_asset_id
          AND asset.tenant_id = relation.tenant_id)
    RETURNING relation.tenant_id, relation.brand_id, relation.entity_type,
              relation.entity_id, relation.media_asset_id, relation.role,
              relation.sort_order
)
INSERT INTO catalog.media_relation_orphans (
    tenant_id, brand_id, entity_type, entity_id, media_asset_id, role,
    sort_order, quarantine_reason)
SELECT tenant_id, brand_id, entity_type, entity_id, media_asset_id, role,
       sort_order, 'NO_MEDIA_ASSET_IN_TENANT'
FROM orphaned;

-- The constraint ADR 0010 specified and V0058 prepared the target for. A
-- cross-tenant attach is now refused by PostgreSQL rather than by whoever
-- remembered to write the predicate.
--
-- No ON DELETE clause, so the default NO ACTION stands and it is the point: a
-- media asset that a live menu references cannot be deleted out from under it.
-- Media deletion is a status transition on the asset (ADR 0010's
-- DELETION_REQUESTED -> DELETED), which this constraint leaves alone.
ALTER TABLE catalog.media_relations
    ADD CONSTRAINT fk_media_relation_asset FOREIGN KEY (media_asset_id, tenant_id)
        REFERENCES media.assets (asset_id, tenant_id);
