-- Row 5.1b / X.13: a generic (non-Telegram) customer CSV import, run as a
-- durable, pollable job rather than the synchronous POST the existing
-- SendPulse contact import (V0111) uses.
--
-- Deliberately a second table rather than widening
-- integration.sendpulse_import_runs: that table's own shape is Telegram-
-- specific (installation_id, brand-per-bot, chat_id-keyed rows) and this
-- import has no installation, no chat, and no subscription state at all --
-- only a phone-keyed customer list. What is shared between the two is the
-- *pattern* (a header row with counts, one child row per parsed input line,
-- written identically on a dry run and a real one, ADR 0059's own "never a
-- silent default" for consent), not the schema.
--
-- Lives in the customer schema, not integration: this import is not a
-- provider adapter, it is the customers module's own bulk entry point over
-- the CustomerImportDirectory port SendPulse's adapter also uses.
--
-- Asynchronous by design (the row's own "Missing" list): both existing
-- imports are synchronous POSTs with nothing for a progress bar to poll.
-- QUEUED -> RUNNING is the CustomerImportRunWorker sweeper's own claim
-- (FOR UPDATE SKIP LOCKED, see JdbcCustomerImportStore#claimNextQueuedRun);
-- rows_processed advances as the sweeper works through the file so a GET
-- mid-run answers with real progress rather than a wait with nothing to show.

CREATE TABLE customer.customer_import_runs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- The brand every created account is attached to -- there is no
    -- installation to read this from the way SendPulse's import reads
    -- installations.brand_id, so the caller states it directly.
    brand_id uuid NOT NULL,

    -- REQUIRED dry-run mode, the same "true is the safe default" rule
    -- V0111's own comment states for SendPulse's import.
    dry_run boolean NOT NULL,
    status varchar(24) NOT NULL,

    source_file_name varchar(255) NOT NULL,

    -- The uploaded CSV, envelope-encrypted (ADR 0029: a customer list is as
    -- sensitive as the customer rows it becomes) -- FieldProtection.protect's
    -- serialized form, the same shape customer.contact_points.encrypted_value
    -- stores. Cleared to NULL once the run leaves QUEUED/RUNNING: nothing
    -- after that point ever needs to re-read the source file, and ADR 0029
    -- favours not retaining a PII blob longer than its purpose requires.
    encrypted_content text,

    -- The Keycloak subject who submitted the import, the same string-not-FK
    -- shape integration.sendpulse_import_runs.imported_by_principal_id uses.
    imported_by_principal_id varchar(255) NOT NULL,

    -- Known immediately at submission (parsing costs no external call), so a
    -- progress bar has a denominator from the first GET onward.
    rows_total integer NOT NULL DEFAULT 0,
    rows_processed integer NOT NULL DEFAULT 0,
    rows_created_customer integer NOT NULL DEFAULT 0,
    rows_matched_customer integer NOT NULL DEFAULT 0,
    rows_rejected integer NOT NULL DEFAULT 0,

    -- Set only for status = FAILED, and only ever a short code -- never the
    -- parser's raw message, which could echo a row's own content back
    -- (ADR 0029: no PII in an error message).
    failure_reason varchar(64),

    created_at timestamptz NOT NULL,
    started_at timestamptz,
    completed_at timestamptz,

    CONSTRAINT fk_customer_import_run_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT fk_customer_import_run_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT ck_customer_import_run_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'DRY_RUN_COMPLETE', 'COMPLETE', 'FAILED')
    ),
    CONSTRAINT ck_customer_import_run_progress CHECK (rows_processed <= rows_total),
    CONSTRAINT ck_customer_import_run_completed CHECK (
        (status IN ('QUEUED', 'RUNNING')) OR (completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_customer_import_run_failure_shape CHECK (
        (status = 'FAILED') = (failure_reason IS NOT NULL)
    ),
    -- The source file is retained only while there is still something to do
    -- with it; a finished run (dry-run or real) never needs it again.
    CONSTRAINT ck_customer_import_run_content_retention CHECK (
        (status IN ('QUEUED', 'RUNNING')) OR (encrypted_content IS NULL)
    ),
    -- What the per-row report table's own tenant-scoped foreign key
    -- references (repo hygiene's own rule: a foreign key must reference a
    -- unique constraint on exactly its own columns).
    CONSTRAINT uq_customer_import_run_identity UNIQUE (id, tenant_id)
);

CREATE INDEX ix_customer_import_runs_tenant ON customer.customer_import_runs (tenant_id, created_at DESC);

-- The sweeper's claim query scans across every tenant for work; a partial
-- index over just the QUEUED rows keeps that scan small regardless of how
-- many finished runs a tenant accumulates.
CREATE INDEX ix_customer_import_runs_queued ON customer.customer_import_runs (created_at)
    WHERE status = 'QUEUED';

COMMENT ON TABLE customer.customer_import_runs IS
    'Row 5.1b/X.13: one row per customer CSV import job, dry-run or real, processed asynchronously by CustomerImportRunWorker.';

GRANT SELECT, INSERT, UPDATE ON customer.customer_import_runs TO horecaos_application;
