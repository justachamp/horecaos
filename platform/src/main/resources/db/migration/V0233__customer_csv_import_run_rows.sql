-- Row 5.1b/X.13's per-row report -- the ResultSummary q-import-wizard renders,
-- and the dry-run diff before it (written identically either way, exactly
-- as integration.sendpulse_import_run_rows, V0111, already does for the
-- Telegram import).
--
-- No phone number and no display name here, by design (ADR 0029): a row
-- names the customer account it created or matched, once that decision is
-- made, and never the source data that produced it. A reviewer reading this
-- table after the fact sees what happened to row 47, not what row 47's
-- phone number was.

CREATE TABLE customer.customer_import_run_rows (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,

    -- 1-based, matching the row's position in the source file.
    row_number integer NOT NULL,

    outcome varchar(32) NOT NULL,

    -- Set for CREATED_CUSTOMER and MATCHED_CUSTOMER; null for REJECTED, and,
    -- on a dry run, for CREATED_CUSTOMER (nothing was actually created to
    -- name -- see SendPulseImportRowOutcome's own doc for the identical rule).
    customer_account_id uuid,

    -- Set for REJECTED only. A short code from CustomerCsvImportRejectReason's
    -- fixed vocabulary, never customer data.
    reject_reason varchar(64),

    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_customer_import_run_row_run FOREIGN KEY (run_id, tenant_id)
        REFERENCES customer.customer_import_runs (id, tenant_id),
    CONSTRAINT fk_customer_import_run_row_customer FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT ck_customer_import_run_row_outcome CHECK (
        outcome IN ('CREATED_CUSTOMER', 'MATCHED_CUSTOMER', 'REJECTED')
    ),
    CONSTRAINT ck_customer_import_run_row_reject_shape CHECK (
        (outcome = 'REJECTED') = (reject_reason IS NOT NULL)
    ),
    CONSTRAINT uq_customer_import_run_row UNIQUE (run_id, row_number)
);

CREATE INDEX ix_customer_import_run_rows_run ON customer.customer_import_run_rows (tenant_id, run_id, row_number);

COMMENT ON TABLE customer.customer_import_run_rows IS
    'Row 5.1b/X.13: one row per parsed input line of a customer CSV import run -- the dry-run diff and the real result summary, in the same shape.';

GRANT SELECT, INSERT ON customer.customer_import_run_rows TO horecaos_application;
