-- Row 4.5b: catalog CSV/Excel import, run as a durable, pollable job over the
-- brand's own catalog -- the same shape customer.customer_import_runs
-- (V0232/V0233) already gives the generic customer CSV import, narrowed here
-- to a brand's products/variants/prices/categories/availability instead of a
-- phone-keyed address book.
--
-- Two tables, matching V0232/V0233's own split: a header row with running
-- counts a JobProgress bar polls, and one child row per parsed input line --
-- the dry-run diff and the real result summary, written identically either
-- way. A run is a job over a single catalog within a brand (ADR 0016: a
-- brand may have several catalogs, and a category the import creates or
-- places a product into is scoped to exactly one of them).

CREATE TABLE catalog.import_runs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    catalog_id uuid NOT NULL,

    -- REQUIRED dry-run mode, the same "true is the safe default" rule V0111
    -- and V0232 both already state for their own imports.
    dry_run boolean NOT NULL,
    status varchar(24) NOT NULL,

    source_file_name varchar(255) NOT NULL,

    -- The uploaded CSV. Unlike customer.customer_import_runs.encrypted_content
    -- this is plain text, not envelope-encrypted: a catalog is product names,
    -- prices and categories, not personal data (ADR 0029's classification
    -- does not reach it). Cleared to NULL once the run leaves
    -- QUEUED/RUNNING for the same storage-hygiene reason V0232 gives, not a
    -- PII one.
    content text,

    imported_by_principal_id varchar(255) NOT NULL,

    rows_total integer NOT NULL DEFAULT 0,
    rows_processed integer NOT NULL DEFAULT 0,
    rows_created integer NOT NULL DEFAULT 0,
    rows_updated integer NOT NULL DEFAULT 0,
    rows_skipped integer NOT NULL DEFAULT 0,
    rows_error integer NOT NULL DEFAULT 0,

    -- Set only for status = FAILED, and only ever a short code -- the parser's
    -- own message never lands here, matching V0232's reasoning even though
    -- this content is not personal data: an operator-authored row could still
    -- carry something (a supplier's private cost note in a description
    -- column) nobody meant to put in a system error message.
    failure_reason varchar(64),

    created_at timestamptz NOT NULL,
    started_at timestamptz,
    completed_at timestamptz,

    CONSTRAINT fk_catalog_import_run_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT fk_catalog_import_run_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_catalog_import_run_catalog FOREIGN KEY (catalog_id, tenant_id, brand_id)
        REFERENCES catalog.catalogs (id, tenant_id, brand_id),
    CONSTRAINT ck_catalog_import_run_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'DRY_RUN_COMPLETE', 'COMPLETE', 'FAILED')
    ),
    CONSTRAINT ck_catalog_import_run_progress CHECK (rows_processed <= rows_total),
    CONSTRAINT ck_catalog_import_run_completed CHECK (
        (status IN ('QUEUED', 'RUNNING')) OR (completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_catalog_import_run_failure_shape CHECK (
        (status = 'FAILED') = (failure_reason IS NOT NULL)
    ),
    CONSTRAINT ck_catalog_import_run_content_retention CHECK (
        (status IN ('QUEUED', 'RUNNING')) OR (content IS NULL)
    ),
    -- What catalog.import_run_rows' own tenant-scoped foreign key references.
    CONSTRAINT uq_catalog_import_run_identity UNIQUE (id, tenant_id)
);

CREATE INDEX ix_catalog_import_runs_brand ON catalog.import_runs (tenant_id, brand_id, created_at DESC);

-- The sweeper's claim query scans across every tenant for work; a partial
-- index over just the QUEUED rows keeps that scan small regardless of how
-- many finished runs accumulate, matching V0232's own ix_customer_import_runs_queued.
CREATE INDEX ix_catalog_import_runs_queued ON catalog.import_runs (created_at)
    WHERE status = 'QUEUED';

COMMENT ON TABLE catalog.import_runs IS
    'Row 4.5b: one row per catalog CSV/Excel import job, dry-run or real, processed asynchronously by CatalogImportRunWorker.';

-- Row 4.5b's per-row report -- the dry-run diff and the real result summary,
-- in the same shape either way, matching customer.customer_import_run_rows
-- (V0233). No product name, description or price here (a milder version of
-- V0233's own reasoning): a row names the product/variant it created or
-- matched, once that decision is made, and the run's own source content
-- already carries the row's full detail while the run is still open.
CREATE TABLE catalog.import_run_rows (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,

    -- 1-based, matching the row's position in the source file.
    row_number integer NOT NULL,

    outcome varchar(16) NOT NULL,

    -- Set for CREATED/UPDATED; null for SKIPPED/ERROR, and, on a dry run,
    -- for CREATED (nothing was actually created to name -- the identical
    -- rule customer.customer_import_run_rows.customer_account_id already
    -- states for its own CREATED_CUSTOMER row). Deliberately no foreign key:
    -- catalog.products' own identity key is (id, tenant_id, brand_id) and
    -- this table does not carry brand_id separately from its run, so the
    -- reference is checked in application code against the run's own
    -- brand_id rather than doubling every row's width for a brand_id column
    -- that would only ever equal its run's.
    product_id uuid,
    variant_id uuid,

    -- Set for ERROR only. A short code from CatalogImportRowRejectReason's
    -- fixed vocabulary, never row content.
    error_reason varchar(64),

    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_catalog_import_run_row_run FOREIGN KEY (run_id, tenant_id)
        REFERENCES catalog.import_runs (id, tenant_id),
    CONSTRAINT ck_catalog_import_run_row_outcome CHECK (
        outcome IN ('CREATED', 'UPDATED', 'SKIPPED', 'ERROR')
    ),
    CONSTRAINT ck_catalog_import_run_row_error_shape CHECK (
        (outcome = 'ERROR') = (error_reason IS NOT NULL)
    ),
    CONSTRAINT uq_catalog_import_run_row UNIQUE (run_id, row_number)
);

CREATE INDEX ix_catalog_import_run_rows_run ON catalog.import_run_rows (tenant_id, run_id, row_number);

COMMENT ON TABLE catalog.import_run_rows IS
    'Row 4.5b: one row per parsed input line of a catalog import run -- the dry-run diff and the real result summary, in the same shape.';

GRANT SELECT, INSERT, UPDATE ON catalog.import_runs TO horecaos_application;
GRANT SELECT, INSERT ON catalog.import_run_rows TO horecaos_application;
