-- ADR 0173 (Accepted 2026-10-07): the ИКПУ/MXIK reference dataset, gap-map rows 4.2e and 10.7c.
--
-- catalog.mxik_reference (V0028) was created empty and nothing wrote it. This is the way in: an audited,
-- two-step, idempotent import that a platform administrator runs from a file they downloaded from the tax
-- authority, a state row that says whether and when the list was loaded, and the columns a withdrawal and a
-- successor need. The dataset itself is an external fact: the official file, its exact headers and its
-- licence are the owner's and finance's to supply (ADR 0173, open inputs), so the mapping profile that reads
-- the file is code-owned and written against a fixture until the first real file exists.
--
-- Three decisions are carried here and nowhere else:
--
--   Never delete. The application role loses DELETE on the national list, so a bug in any tenant-facing path
--   cannot remove it; a code the authority withdraws is dated (valid_until), and a code that returns is
--   reinstated by clearing the date. A receipt issued last year under a code that has since been retired
--   was still correct.
--
--   A run is a record. Every upload is a row in mxik_import_runs, the file it came from is kept under its
--   content hash, and the rows it would write wait in mxik_import_run_rows until the administrator applies
--   the run. Nothing in the national list changes before that second, explicit act.
--
--   No foreign key from a tenant's classification to the list. V0028 declined it ("an empty reference behind
--   a foreign key would refuse every code") and this record does not reopen that.

-- --------------------------------------------------------------------- the list itself

ALTER TABLE catalog.mxik_reference
    -- The run that last wrote this row's content, and the run that first created it.
    ADD COLUMN dataset_version integer,
    ADD COLUMN first_run_id uuid,
    ADD COLUMN last_run_id uuid,
    -- The run that dated this code's withdrawal; cleared when a later run reinstates it.
    ADD COLUMN withdrawn_run_id uuid,
    -- A successor the file names for a withdrawn code. A suggestion for a person to click, never a rewrite.
    ADD COLUMN replaced_by_code varchar(32),
    -- The file's own level in the tree, if it has one.
    ADD COLUMN level smallint;

-- The prefix path an operator types into the picker.
CREATE INDEX ix_mxik_reference_code_prefix ON catalog.mxik_reference (code text_pattern_ops);

-- The national list is never deleted from; a withdrawal is a date.
REVOKE DELETE ON catalog.mxik_reference FROM horecaos_application;

COMMENT ON COLUMN catalog.mxik_reference.replaced_by_code IS
    'ADR 0173. A successor the official file names for a withdrawn code. Offered as a bulk action a person clicks in the fiscalization workbench; nothing is replaced automatically, and an issued receipt keeps the code it was sent under.';

-- ------------------------------------------------------------------- the list's state

-- One row, written by the apply that completes a run and by nothing else. "Loaded" means this row exists.
CREATE TABLE catalog.mxik_reference_state (
    id smallint PRIMARY KEY,
    dataset_version integer NOT NULL,
    imported_at timestamptz NOT NULL,
    source_sha256 char(64) NOT NULL,
    -- The date the authority published the file, if the operator entered it.
    source_published_on date,
    valid_rows integer NOT NULL,
    withdrawn_rows integer NOT NULL,
    last_run_id uuid,
    CONSTRAINT ck_mxik_reference_state_singleton CHECK (id = 1),
    CONSTRAINT ck_mxik_reference_state_counts CHECK (valid_rows >= 0 AND withdrawn_rows >= 0)
);

-- ------------------------------------------------------------------------ the import

-- Platform-scoped: the dataset is a national fact, not a tenant's, so neither table carries a tenant.
CREATE TABLE catalog.mxik_import_runs (
    id uuid PRIMARY KEY,
    status varchar(20) NOT NULL,
    mode varchar(16) NOT NULL,
    mapping_profile varchar(32) NOT NULL,
    source_file_name varchar(255) NOT NULL,
    source_sha256 char(64) NOT NULL,
    -- Where the raw file was kept (ADR 0010, ADR 0135), so a bad load is undone by importing the previous file.
    source_object_key varchar(255),
    source_published_on date,
    rows_total integer NOT NULL DEFAULT 0,
    rows_new integer NOT NULL DEFAULT 0,
    rows_changed integer NOT NULL DEFAULT 0,
    rows_unchanged integer NOT NULL DEFAULT 0,
    rows_withdrawn integer NOT NULL DEFAULT 0,
    rows_reinstated integer NOT NULL DEFAULT 0,
    rows_error integer NOT NULL DEFAULT 0,
    -- Rows that had one of the two labels only and took that text in both columns rather than being dropped.
    labels_borrowed integer NOT NULL DEFAULT 0,
    -- The threshold in force when the run was validated, and the acknowledgement that released a held run.
    withdrawal_threshold_percent integer NOT NULL,
    acknowledged_withdrawals integer,
    -- The row-length distribution of the codes in the file, shown to the human instead of asserting a format.
    code_length_histogram jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_by varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    applied_by varchar(255),
    applied_at timestamptz,
    dataset_version integer,
    failure_reason varchar(64),
    version integer NOT NULL DEFAULT 1,
    CONSTRAINT ck_mxik_import_run_status CHECK (
        status IN ('VALIDATED', 'HELD_FOR_REVIEW', 'APPLYING', 'APPLIED', 'FAILED', 'REJECTED')),
    CONSTRAINT ck_mxik_import_run_mode CHECK (mode IN ('FULL_SNAPSHOT', 'PARTIAL')),
    CONSTRAINT ck_mxik_import_run_counts CHECK (
        rows_total >= 0 AND rows_new >= 0 AND rows_changed >= 0 AND rows_unchanged >= 0
        AND rows_withdrawn >= 0 AND rows_reinstated >= 0 AND rows_error >= 0 AND labels_borrowed >= 0),
    -- A partial run withdraws nothing: only a full snapshot can say a code is gone.
    CONSTRAINT ck_mxik_import_run_partial_withdraws_nothing CHECK (mode = 'FULL_SNAPSHOT' OR rows_withdrawn = 0),
    CONSTRAINT ck_mxik_import_run_sha CHECK (source_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_mxik_import_runs_recent ON catalog.mxik_import_runs (created_at DESC);

-- What a run found, capped at 5,000 rows per run by the importer.
CREATE TABLE catalog.mxik_import_run_findings (
    run_id uuid NOT NULL,
    ordinal integer NOT NULL,
    kind varchar(32) NOT NULL,
    code varchar(32),
    detail_code varchar(48) NOT NULL,
    PRIMARY KEY (run_id, ordinal),
    CONSTRAINT ck_mxik_import_finding_kind CHECK (
        kind IN ('ROW_ERROR', 'WITHDRAWN', 'REINSTATED', 'PACKAGE_DEFAULTS_CHANGED')),
    CONSTRAINT fk_mxik_import_finding_run FOREIGN KEY (run_id) REFERENCES catalog.mxik_import_runs (id)
);

-- The normalised rows a validated run would write, kept until the run is applied or rejected. The object
-- store holds the raw file but offers no full read, and a second pass over a file that has to be parsed
-- again is a second chance for the two passes to disagree; the apply reads exactly what the dry run showed.
CREATE TABLE catalog.mxik_import_run_rows (
    run_id uuid NOT NULL,
    ordinal integer NOT NULL,
    code varchar(32) NOT NULL,
    parent_code varchar(32),
    label_ru varchar(400) NOT NULL,
    label_uz varchar(400) NOT NULL,
    label_en varchar(400),
    default_package_codes text[] NOT NULL DEFAULT '{}',
    default_unit_codes integer[] NOT NULL DEFAULT '{}',
    replaced_by_code varchar(32),
    level smallint,
    valid_from date,
    -- Depth in the file's own tree, so a parent is always written before its children.
    depth integer NOT NULL DEFAULT 0,
    PRIMARY KEY (run_id, code),
    CONSTRAINT uq_mxik_import_run_row_ordinal UNIQUE (run_id, ordinal),
    CONSTRAINT fk_mxik_import_run_row_run FOREIGN KEY (run_id) REFERENCES catalog.mxik_import_runs (id)
);

COMMENT ON TABLE catalog.mxik_import_runs IS
    'ADR 0173. One upload of the official ИКПУ/MXIK file. VALIDATED is a dry run that changed nothing; apply is a separate, explicit act. A full snapshot that would withdraw more than catalog.mxik_import_withdrawal_threshold_percent of the valid rows is held for review.';

-- ------------------------------------------------------------------------------ grants
--
-- The runs and the state are updated by the importer; findings are written once. The staging rows are
-- written once and cleared when the run is applied or rejected, which is the only delete here: they are a
-- working copy, not the list.
GRANT SELECT, INSERT, UPDATE ON catalog.mxik_import_runs TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON catalog.mxik_reference_state TO horecaos_application;
GRANT SELECT, INSERT ON catalog.mxik_import_run_findings TO horecaos_application;
GRANT SELECT, INSERT, DELETE ON catalog.mxik_import_run_rows TO horecaos_application;
