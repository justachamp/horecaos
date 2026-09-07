-- ADR 0010: the separate async validation worker the record always asked for.
--
-- Verification was synchronous inside finalizeUpload: a HeadObject, a ranged
-- read of up to 128KB, and header arithmetic, all on the request thread. That
-- was defensible while every check was that cheap, but it is still a blocking
-- round trip to the object store on a path a client is waiting on, and the ADR
-- named a worker rather than a request-thread check from the start.
--
-- The lifecycle already had the shape for this. PENDING_UPLOAD / UPLOADED /
-- AVAILABLE / REJECTED has been in `media.assets`'s own status check constraint
-- since V0015; nothing ever wrote UPLOADED, because finalizeUpload jumped
-- straight from PENDING_UPLOAD to a verdict. This migration does not touch that
-- constraint — it does not need to — and adds only what a worker needs to drain
-- a queue of claims still to be checked: a job row, in V0065's own shape,
-- because a third hand-written FOR UPDATE SKIP LOCKED claim query is a third
-- chance to get it subtly wrong, and none of the differences between rendering
-- a thumbnail and verifying an upload reach as far as the claim.

-- When a client's finalize call flips PENDING_UPLOAD -> UPLOADED. Separate from
-- `finalized_at`, which continues to mean what it always has: the instant a
-- verdict (AVAILABLE or REJECTED) was reached. Before this migration the two
-- were the same instant, because verification was synchronous; now there is a
-- queueing delay between them worth being able to see.
ALTER TABLE media.assets ADD COLUMN uploaded_at timestamptz;

CREATE TABLE media.verification_jobs (
    job_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    asset_id uuid NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'PENDING',
    due_at timestamptz NOT NULL DEFAULT now(),
    attempt_count integer NOT NULL DEFAULT 0,

    -- The lease. A worker claims by writing a token, a holder and a deadline in
    -- one conditional update; a worker that dies mid-verification leaves a
    -- lease that expires rather than an upload nobody may ever touch again.
    lease_token uuid,
    leased_until timestamptz,
    leased_by varchar(128),

    -- A code, never a message. What finalize used to reject with directly
    -- (OBJECT_MISSING, SIZE_MISMATCH, TYPE_MISMATCH, CONTENT_NOT_AN_IMAGE,
    -- DIMENSIONS_EXCEEDED, ...) is now this worker's own outcome, and a
    -- decoder's or a client's wording is not a place ADR 0029 allows a
    -- filename to end up.
    last_error_code varchar(48),
    last_error_at timestamptz,

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    -- Composite, against the tenant-scoped key V0058 added, so a verification
    -- job cannot be hung on another tenant's asset.
    CONSTRAINT fk_verification_job_asset FOREIGN KEY (asset_id, tenant_id)
        REFERENCES media.assets (asset_id, tenant_id),
    CONSTRAINT ck_verification_job_status CHECK (status IN (
        'PENDING', 'LEASED', 'COMPLETED', 'ABANDONED')),
    CONSTRAINT ck_verification_job_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_verification_job_lease_triple CHECK (
        (lease_token IS NULL) = (leased_until IS NULL)
        AND (lease_token IS NULL) = (leased_by IS NULL)),
    CONSTRAINT ck_verification_job_leased_has_lease CHECK (
        status <> 'LEASED' OR lease_token IS NOT NULL),
    CONSTRAINT ck_verification_job_error_pair CHECK (
        (last_error_code IS NULL) = (last_error_at IS NULL))
);

-- One outstanding job per asset. Two workers verifying the same upload would
-- both read the same object and reach the same verdict, so this is about not
-- paying the round trip twice rather than about correctness of the verdict.
CREATE UNIQUE INDEX ux_verification_job_one_active
    ON media.verification_jobs (tenant_id, asset_id)
    WHERE status IN ('PENDING', 'LEASED');

-- The claim query: due, and either unleased or holding an expired lease.
CREATE INDEX ix_verification_job_claimable
    ON media.verification_jobs (due_at)
    WHERE status IN ('PENDING', 'LEASED');

COMMENT ON TABLE media.verification_jobs IS
    'ADR 0010 upload verification owed for an asset. Written with the UPLOADED transition; drained by a leased worker outside any transaction, never on the request that called finalize.';

GRANT SELECT, INSERT, UPDATE ON media.verification_jobs TO horecaos_application;
