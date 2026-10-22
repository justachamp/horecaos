-- ADR 0029, batch 21 (w10-privacy, step 2): the two privacy operations the record lists beside erasure that had no table.
--
--   export   "assemble a customer's data across modules, decrypted under audit"
--   correct  "amend with evidence, preserving prior values under retention"
--
-- 1. customer.export_requests: the life of a data-subject access request. Raised by the customer on the storefront
--    or by staff on her behalf, produced by a person who holds customer.pii.export (under an ADR 0027 approval when
--    the tenant has authored a policy for it), and kept as one JSON document in object storage until it expires.
--    The row holds the object's key, its SHA-256 and a manifest of counts per module and table. It never holds a
--    value: a downloadable bundle is the only place a customer's data is gathered, and the row says where that is
--    and when it goes. The declared purpose of the reveal is typed by a person, so it lives on the audit fact and
--    not here, the way a legal hold's justification lives on its approval request (V0682).
--
-- 2. customer.profile_corrections: one row per amendment made against evidence. The prior value is kept, protected
--    under the envelope and bound to this row, so that "preserving prior values under retention" is something a
--    data-subject export can show and an erasure can remove. evidence_reference is a reference to the evidence (a
--    ticket, a document number), never the evidence and never prose: the pattern refuses spaces so that a name
--    typed in the box is a validation error instead of a second place the person is spelled out.

CREATE TABLE customer.export_requests (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'PENDING',

    requested_via varchar(16) NOT NULL,
    requested_by_actor_type varchar(16) NOT NULL,
    requested_by_actor_id varchar(255) NOT NULL,
    requested_at timestamptz NOT NULL DEFAULT now(),
    -- The day the tenant owes the data subject an answer, fixed when the request is raised from the deadline the
    -- tenant had then (customers.dsar_response_deadline_days). A later change of the setting does not move it.
    response_due_at timestamptz NOT NULL,

    -- Production of the bundle: a separate, capability-gated act from the request, like an erasure's execution.
    produced_at timestamptz,
    produced_by_actor_type varchar(16),
    produced_by_actor_id varchar(255),
    approval_id uuid,
    bundle_bucket varchar(128),
    bundle_key varchar(255),
    bundle_sha256 varchar(64),
    bundle_bytes bigint,
    -- {"sections":[{"name":..., "tables":[{"table":..., "rows":n, "truncated":bool}]}], "withheld":[...]}
    manifest jsonb,
    bundle_expires_at timestamptz,
    bundle_removed_at timestamptz,

    cancelled_at timestamptz,
    cancelled_by_actor_type varchar(16),
    cancelled_by_actor_id varchar(255),

    version integer NOT NULL DEFAULT 1,

    CONSTRAINT ck_export_request_status CHECK (status IN ('PENDING', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_export_request_via CHECK (requested_via IN ('STOREFRONT', 'OPERATIONS')),
    CONSTRAINT ck_export_request_actor_type CHECK (
        requested_by_actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'MIGRATION')),
    CONSTRAINT ck_export_request_produced_actor_type CHECK (
        produced_by_actor_type IS NULL
        OR produced_by_actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'MIGRATION')),
    CONSTRAINT ck_export_request_cancelled_actor_type CHECK (
        cancelled_by_actor_type IS NULL
        OR cancelled_by_actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'MIGRATION')),
    -- A request is COMPLETED exactly when it records who produced the bundle, when, and where it is: the equivalence,
    -- not one direction, is what catches a service that flips the status without writing the person or the object.
    CONSTRAINT ck_export_request_produced_fields CHECK (
        (status = 'COMPLETED' AND produced_at IS NOT NULL
            AND produced_by_actor_type IS NOT NULL AND produced_by_actor_id IS NOT NULL
            AND bundle_bucket IS NOT NULL AND bundle_key IS NOT NULL
            AND bundle_sha256 IS NOT NULL AND bundle_bytes IS NOT NULL
            AND bundle_expires_at IS NOT NULL AND manifest IS NOT NULL)
        OR (status <> 'COMPLETED' AND produced_at IS NULL AND produced_by_actor_type IS NULL
            AND produced_by_actor_id IS NULL AND bundle_key IS NULL AND bundle_removed_at IS NULL)),
    CONSTRAINT ck_export_request_cancelled_fields CHECK (
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL
            AND cancelled_by_actor_type IS NOT NULL AND cancelled_by_actor_id IS NOT NULL)
        OR (status <> 'CANCELLED' AND cancelled_at IS NULL
            AND cancelled_by_actor_type IS NULL AND cancelled_by_actor_id IS NULL)),
    CONSTRAINT ck_export_request_version CHECK (version >= 1),
    CONSTRAINT fk_export_request_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT uq_export_request_identity UNIQUE (id, tenant_id)
);

-- At most one PENDING request per account, so a second ask while the first waits is the same request (as V0178 does
-- for an erasure). COMPLETED and CANCELLED may repeat.
CREATE UNIQUE INDEX ux_export_request_pending
    ON customer.export_requests (tenant_id, customer_account_id)
    WHERE status = 'PENDING';

CREATE INDEX ix_export_requests_account
    ON customer.export_requests (tenant_id, customer_account_id, requested_at DESC);

CREATE INDEX ix_export_requests_tenant
    ON customer.export_requests (tenant_id, requested_at DESC);

-- The sweeper that takes expired bundles out of object storage reads only the ones still there.
CREATE INDEX ix_export_requests_bundle_expiry
    ON customer.export_requests (bundle_expires_at)
    WHERE status = 'COMPLETED' AND bundle_removed_at IS NULL;

COMMENT ON TABLE customer.export_requests IS
    'ADR 0029. A data-subject access request: raised, produced as one JSON bundle in object storage, expired. Holds the object key, its hash and counts per module and table; never a value.';
COMMENT ON COLUMN customer.export_requests.manifest IS
    'What the bundle contains: sections and tables with row counts, and what was withheld. Counts and names only.';

GRANT SELECT, INSERT, UPDATE ON customer.export_requests TO horecaos_application;

CREATE TABLE customer.profile_corrections (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,

    subject varchar(16) NOT NULL,
    -- The contact point or the address that was amended; null for the profile and the date of birth.
    target_id uuid,
    evidence_reference varchar(80) NOT NULL,
    -- The value as it was, as JSON, protected under the envelope and bound to this row (ADR 0029).
    prior_value_protected text NOT NULL,

    corrected_by_actor_type varchar(16) NOT NULL,
    corrected_by_actor_id varchar(255) NOT NULL,
    corrected_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_profile_correction_subject CHECK (
        subject IN ('DISPLAY_NAME', 'DATE_OF_BIRTH', 'CONTACT_POINT', 'ADDRESS')),
    CONSTRAINT ck_profile_correction_target CHECK (
        (subject IN ('CONTACT_POINT', 'ADDRESS') AND target_id IS NOT NULL)
        OR (subject IN ('DISPLAY_NAME', 'DATE_OF_BIRTH') AND target_id IS NULL)),
    CONSTRAINT ck_profile_correction_evidence CHECK (evidence_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/#-]{2,79}$'),
    CONSTRAINT ck_profile_correction_actor_type CHECK (
        corrected_by_actor_type IN ('USER', 'SERVICE', 'SYSTEM_JOB', 'MIGRATION')),
    CONSTRAINT fk_profile_correction_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id)
);

CREATE INDEX ix_profile_corrections_account
    ON customer.profile_corrections (tenant_id, customer_account_id, corrected_at DESC);

COMMENT ON TABLE customer.profile_corrections IS
    'ADR 0029. One amendment made against evidence, with the value it replaced kept protected. Removed by the erasure of the account: the prior value is the old personal data.';

-- DELETE is the erasure's: a prior value is the old personal data, and "preserving it under retention" ends with the
-- person's erasure. There is no UPDATE: a correction is a fact, not a record anyone edits.
GRANT SELECT, INSERT, DELETE ON customer.profile_corrections TO horecaos_application;
