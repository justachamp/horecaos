-- ADR 0043 / ADR 0029, wave P28: the export centre's own audited job queue.
--
-- ADR 0043 puts a report's Excel/CSV export last, deliberately, "behind
-- capabilities and an audited job queue" -- this table is that queue. ADR
-- 0029's own egress checklist names the control it is missing in as many
-- words: "no data-subject export ... operation exists anywhere". Both close
-- here.
--
-- Asynchronous, on the same idiom as V0232's customer.customer_import_runs:
-- POST /reporting/exports queues a row here, ReportExportWorker claims and
-- processes it (FOR UPDATE SKIP LOCKED -- JdbcCustomerImportStore
-- #claimNextQueuedRun's own idiom), and GET /reporting/reports/{id} polls
-- it. The artifact bytes themselves never live in this table -- only the
-- object-store bucket/key ReportExportWorker wrote them to through the
-- media module's own ObjectStorage port (ADR 0010), the same "business
-- tables reference media asset IDs or immutable object keys" AGENTS.md
-- states for media generally.
--
-- requested_columns and effective_columns are kept apart on purpose. A
-- principal holding report.export but not customer.pii.export gets a file
-- with the PII column group silently omitted rather than a 403 (the row's
-- own named test) -- requested_columns is the evidence of what was asked
-- for, effective_columns of what actually left the building, and
-- includes_pii_columns is the flag ReportExportWorker's own audit fact
-- reads rather than recomputing.
--
-- The search text a CUSTOMER_DIRECTORY export filters by is PII-shaped (it
-- may be a phone number), so it is never stored in the plain `filters`
-- column -- only encrypted_query, envelope-encrypted the same way V0232
-- encrypts a whole import file, decrypted once by the worker and cleared
-- immediately after (ck_report_export_query_retention mirrors V0232's own
-- ck_customer_import_run_content_retention). `filters` itself carries only
-- what CustomerListQueryService#exportFiltered's own audit fact already
-- considers safe to keep: a status enum, never free text.

CREATE TABLE reporting.report_exports (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- ReportExportRegistry's own key, e.g. CUSTOMER_DIRECTORY. Not a foreign
    -- key: the registry is code, not a table, the same reasoning ADR 0025
    -- gives for Capability -- an unknown key fails the request rather than
    -- silently denying it.
    report_key varchar(64) NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'QUEUED',

    -- The Keycloak subject who asked -- the same string-not-FK shape
    -- customer.customer_import_runs.imported_by_principal_id uses, for the
    -- same reason: the identity may since have left, and the historical
    -- fact must outlive it.
    requested_by_subject varchar(255) NOT NULL,

    -- ADR 0027's stated reason, exactly like every CustomerController reveal.
    purpose varchar(500) NOT NULL,

    -- Non-PII filter metadata only. A JSON object, e.g. {"status": "ACTIVE"}.
    filters jsonb NOT NULL DEFAULT '{}'::jsonb,

    -- The one filter value that can be PII-shaped -- see this migration's
    -- own header. ProtectedValue's serialized form, the same shape
    -- customer.customer_import_runs.encrypted_content stores.
    encrypted_query text,

    -- JSON arrays of column keys (ReportExportRegistry's own vocabulary).
    requested_columns jsonb NOT NULL,
    effective_columns jsonb NOT NULL,
    includes_pii_columns boolean NOT NULL DEFAULT false,

    row_quota integer NOT NULL,
    row_count integer,
    truncated boolean NOT NULL DEFAULT false,

    artifact_bucket varchar(255),
    artifact_object_key varchar(512),
    artifact_content_type varchar(100),
    artifact_size_bytes bigint,
    artifact_checksum_sha256 varchar(64),

    -- A short code only, never the failure's own message -- ADR 0029, the
    -- same discipline customer.customer_import_runs.failure_reason states.
    failure_reason varchar(64),

    created_at timestamptz NOT NULL,
    started_at timestamptz,
    completed_at timestamptz,

    CONSTRAINT fk_report_export_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT ck_report_export_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETE', 'FAILED')),
    CONSTRAINT ck_report_export_quota CHECK (row_quota > 0),
    CONSTRAINT ck_report_export_row_count CHECK (row_count IS NULL OR row_count >= 0),
    CONSTRAINT ck_report_export_failure_shape CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    CONSTRAINT ck_report_export_complete_shape CHECK (
        (status <> 'COMPLETE')
        OR (artifact_object_key IS NOT NULL AND row_count IS NOT NULL)
    ),
    CONSTRAINT ck_report_export_completed_at CHECK (
        (status IN ('QUEUED', 'RUNNING')) OR (completed_at IS NOT NULL)
    ),
    -- The source file is retained only while there is still something to do
    -- with it -- a settled export never needs the raw search text again.
    CONSTRAINT ck_report_export_query_retention CHECK (
        (status IN ('QUEUED', 'RUNNING')) OR (encrypted_query IS NULL)
    )
);

CREATE INDEX ix_report_exports_tenant ON reporting.report_exports (tenant_id, created_at DESC);

-- The worker's claim query scans across every tenant for work; a partial
-- index over just the QUEUED rows keeps that scan small regardless of how
-- many finished exports a tenant accumulates.
CREATE INDEX ix_report_exports_queued ON reporting.report_exports (created_at)
    WHERE status = 'QUEUED';

COMMENT ON TABLE reporting.report_exports IS
    'ADR 0043/ADR 0029, wave P28: one row per requested report export -- the audited job queue ADR 0043 puts behind capabilities and ADR 0029 relies on to catch a bulk PII egress.';

GRANT SELECT, INSERT, UPDATE ON reporting.report_exports TO horecaos_application;
