-- ADR 0059 stage 3: the SendPulse contact-export import.
--
-- The import turns a SendPulse bot-audience export into real customers plus
-- real ADR 0058 CUSTOMER-audience Telegram bindings, exactly the shapes the
-- wave-7 handshake (V0107) creates, so an imported chat is a real binding —
-- not a parallel contact store (ADR 0059's own "Contacts are customers"
-- decision bullet). This migration adds only what an import needs that
-- neither the customer schema (V0017) nor the Telegram binding schema (V0099,
-- V0107) already carries: a durable, queryable record of each import attempt
-- and what it decided about each row, so a dry-run report is exact and a real
-- run's outcome is reviewable afterward without reading application logs.
--
-- Deliberately not a new contact table. There is no `sendpulse_contacts`
-- table here, on purpose: a row's fate is either an existing or newly created
-- `customer.customer_accounts` row plus an `integration.telegram_bindings`
-- row, both already governed by their own schemas, constraints and grants.
-- What this migration owns is the import run itself — the audit/report
-- trail ADR 0027 needs for "who imported, counts, source" — not a second
-- copy of the customer.

CREATE TABLE integration.sendpulse_import_runs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- The bot (and therefore the brand, ADR 0058's bot-per-brand topology)
    -- this export belongs to. brand_id is read from installations.brand_id
    -- at request time and stored on the run rather than re-resolved later,
    -- so a later brand reassignment cannot reinterpret which brand a past
    -- run imported contacts for.
    installation_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    -- REQUIRED dry-run mode (the SendPulse cutover runbook's own step):
    -- true is the safe default a caller must deliberately turn off. Recorded
    -- rather than inferred from status, so a report pulled up later says
    -- outright whether anything was actually written.
    dry_run boolean NOT NULL,
    status varchar(24) NOT NULL,
    source_format varchar(8) NOT NULL,
    source_file_name varchar(255) NOT NULL,

    -- The Keycloak subject who ran the import, for the same reason
    -- integration.telegram_pending_links.requested_by_principal_id is a
    -- string rather than a foreign key: ActorRef.subject is a string
    -- throughout this platform, never assumed to parse as anything else.
    imported_by_principal_id varchar(255) NOT NULL,

    -- Counts, frozen once the run completes. A dry run's counts describe
    -- what the run WOULD have done; a real run's describe what it did. The
    -- distinction is dry_run above, not a second set of columns.
    rows_total integer NOT NULL DEFAULT 0,
    rows_created_customer integer NOT NULL DEFAULT 0,
    rows_matched_customer integer NOT NULL DEFAULT 0,
    rows_skipped_already_linked integer NOT NULL DEFAULT 0,
    rows_rejected integer NOT NULL DEFAULT 0,
    rows_subscribed integer NOT NULL DEFAULT 0,
    rows_unsubscribed integer NOT NULL DEFAULT 0,

    started_at timestamptz NOT NULL,
    completed_at timestamptz,

    CONSTRAINT fk_sendpulse_import_run_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT fk_sendpulse_import_run_installation FOREIGN KEY (tenant_id, installation_id)
        REFERENCES integration.installations (tenant_id, id),
    CONSTRAINT fk_sendpulse_import_run_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT ck_sendpulse_import_run_status CHECK (
        status IN ('DRY_RUN_COMPLETE', 'COMPLETE', 'FAILED')
    ),
    CONSTRAINT ck_sendpulse_import_run_format CHECK (source_format IN ('CSV', 'JSON')),
    CONSTRAINT ck_sendpulse_import_run_completed CHECK (
        (status = 'FAILED') OR (completed_at IS NOT NULL)
    ),
    -- What the row-report table's own tenant-scoped foreign key references
    -- (repo-hygiene's own rule: "a foreign key must reference a unique
    -- constraint on exactly its own columns" — a bare PK on id alone would
    -- let one tenant's report row point at another tenant's run).
    CONSTRAINT uq_sendpulse_import_run_identity UNIQUE (id, tenant_id)
);

CREATE INDEX ix_sendpulse_import_runs_tenant ON integration.sendpulse_import_runs (tenant_id, started_at DESC);

COMMENT ON TABLE integration.sendpulse_import_runs IS
    'ADR 0059 stage 3: one row per SendPulse contact-export import attempt (dry-run or real), the ADR 0027 evidence for who imported, when, and with what counts.';

-- ------------------------------------------------------------- per-row report

-- One row per parsed input line. Written on a dry run exactly as on a real
-- one — this is the import's own bookkeeping, not customer data, and it is
-- what makes a dry-run report reproducible and a real run's outcome
-- reviewable afterward rather than only visible in the one HTTP response
-- that started it.
CREATE TABLE integration.sendpulse_import_run_rows (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    run_id uuid NOT NULL,
    row_number integer NOT NULL,

    -- Null only when the row's chat id could not even be parsed — the one
    -- case where there is nothing to key idempotency on at all, and the row
    -- can only ever be REJECTED.
    chat_id bigint,

    -- What the row's subscription column said, interpreted. Null exactly
    -- when the row was rejected before a subscription status was read.
    subscribed boolean,

    outcome varchar(32) NOT NULL,

    -- Set for CREATED_CUSTOMER, MATCHED_CUSTOMER and SKIPPED_ALREADY_LINKED;
    -- null for REJECTED and, on a dry run, for CREATED_CUSTOMER (nothing was
    -- actually created to name).
    customer_account_id uuid,

    -- Set for REJECTED only. A short code, never customer data: see
    -- SendPulseImportRowOutcome$RejectReason for the fixed vocabulary.
    reject_reason varchar(64),

    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_sendpulse_import_run_row_run FOREIGN KEY (run_id, tenant_id)
        REFERENCES integration.sendpulse_import_runs (id, tenant_id),
    -- Composite against customer_accounts' own (id, tenant_id) unique pair
    -- (uq_customer_identity, V0017) — the same discipline V0107 applies to
    -- telegram_pending_links.customer_account_id, so one tenant's import
    -- report can never point at another tenant's customer.
    CONSTRAINT fk_sendpulse_import_run_row_customer FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT ck_sendpulse_import_run_row_outcome CHECK (
        outcome IN ('CREATED_CUSTOMER', 'MATCHED_CUSTOMER', 'SKIPPED_ALREADY_LINKED', 'REJECTED')
    ),
    CONSTRAINT ck_sendpulse_import_run_row_reject_shape CHECK (
        (outcome = 'REJECTED') = (reject_reason IS NOT NULL)
    ),
    CONSTRAINT uq_sendpulse_import_run_row UNIQUE (run_id, row_number)
);

CREATE INDEX ix_sendpulse_import_run_rows_run ON integration.sendpulse_import_run_rows (tenant_id, run_id, row_number);

COMMENT ON TABLE integration.sendpulse_import_run_rows IS
    'ADR 0059 stage 3: one row per parsed input line of a SendPulse import run — the exact, re-readable dry-run report and the real run''s own outcome per contact.';

GRANT SELECT, INSERT, UPDATE ON integration.sendpulse_import_runs TO horecaos_application;
GRANT SELECT, INSERT ON integration.sendpulse_import_run_rows TO horecaos_application;
