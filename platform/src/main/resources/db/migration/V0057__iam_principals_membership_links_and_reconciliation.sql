-- ADR 0009: the evidence that lets a drift found on Tuesday be explained on Friday.
--
-- `tenant.tenants.keycloak_organization_id` stays the authoritative link and is
-- not moved. What is missing is everything around it: which people the platform
-- believes belong to a tenant, which Keycloak objects carry that belief, and
-- what a reconciliation actually saw the last time it looked. Today the linked
-- subject survives only in an onboarding step's `result_snapshot`, which is
-- deleted with the run and cannot be queried across tenants.
--
-- Nothing here holds a name, an email address, a phone number, an invitation
-- link, or a token. A principal is a realm and a subject id; the profile behind
-- it stays in Keycloak, which is the ADR 0029 answer to why this schema needs no
-- envelope encryption at all.

-- The prerequisite for the two-column reference below. A tenant is already
-- unique by id, so this adds no new rule about tenants; what it adds is a target
-- a foreign key can point at, which PostgreSQL requires to cover exactly the
-- referencing columns. Rows whose organization id is still null do not conflict.
ALTER TABLE tenant.tenants
    ADD CONSTRAINT uq_tenant_id_keycloak_organization UNIQUE (id, keycloak_organization_id);

-- One row per Keycloak subject the platform has ever linked.
--
-- Deliberately NOT tenant-scoped, and the one table in this schema that is not.
-- A person can hold membership of several tenants — that is exactly the case
-- ADR 0009 exists to keep honest — so a principal keyed by tenant would either
-- duplicate the subject or force a choice about which tenant "owns" a human
-- being. The tenant relationship lives in `tenant_membership_links`, one row per
-- (tenant, principal), and every read of this table reaches a tenant through
-- that join.
CREATE TABLE iam.principals (
    id uuid PRIMARY KEY,
    keycloak_realm varchar(64) NOT NULL,
    keycloak_subject_id varchar(64) NOT NULL,

    -- What Keycloak last said about the account, not what Qoida wants it to be.
    -- UNKNOWN is the honest state after a reconciliation could not reach
    -- Keycloak, and is distinct from DISABLED: one is an observation, the other
    -- is an absence of one.
    status varchar(16) NOT NULL DEFAULT 'UNKNOWN',

    first_linked_at timestamptz NOT NULL,
    last_reconciled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_principal_status CHECK (status IN ('ACTIVE', 'DISABLED', 'UNKNOWN')),
    -- The realm is part of the key because a subject id is unique within a realm
    -- and nowhere else. A staging realm and a production realm can issue the
    -- same UUID, and a platform that assumed otherwise would merge two people.
    CONSTRAINT uq_principal_realm_subject UNIQUE (keycloak_realm, keycloak_subject_id)
);

COMMENT ON TABLE iam.principals IS
    'ADR 0009. A Keycloak subject the platform has linked. Identifiers only: no name, email, or phone, so ADR 0029 encryption is not needed and no dump of this table identifies anybody.';

-- The tenant boundary made checkable.
--
-- `tenant.tenants.keycloak_organization_id` says which organization is a
-- tenant's; this says which people are inside it and which Keycloak objects
-- carry that membership. It is what a drift report compares against, and it is
-- the reason a report can name a specific membership rather than saying that a
-- tenant and a realm disagree.
CREATE TABLE iam.tenant_membership_links (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    principal_id uuid NOT NULL,

    -- Repeated from the tenant row rather than joined, because it records the
    -- organization the membership was actually created against. If those two
    -- ever differ, that difference is the finding.
    keycloak_organization_id varchar(64) NOT NULL,

    -- Null until Keycloak has confirmed the member object exists. An invited
    -- owner who has not accepted has a link and no membership id, and that is a
    -- distinct state from a membership that was deleted underneath us.
    keycloak_membership_id varchar(64),

    -- What the platform intends this person to hold inside the organization
    -- scope, so role reconciliation has something to reconcile against.
    -- Constrained to the three ADR 0003 organization roles: onboarding never
    -- grants `platform-admin`, and this makes that a fact the database enforces
    -- rather than an intention in a handler.
    expected_roles varchar(64)[] NOT NULL DEFAULT '{}',

    status varchar(24) NOT NULL,
    last_reconciled_at timestamptz,
    last_drift_code varchar(48),

    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_membership_link_principal FOREIGN KEY (principal_id)
        REFERENCES iam.principals (id),
    -- Two columns, so the link cannot name an organization that is not this
    -- tenant's. A one-column reference to the tenant would leave "which
    -- organization" to the application, and this is the platform's primary
    -- security boundary: the organization claim in a token is how a request is
    -- decided to belong to a tenant.
    CONSTRAINT fk_membership_link_tenant_organization
        FOREIGN KEY (tenant_id, keycloak_organization_id)
        REFERENCES tenant.tenants (id, keycloak_organization_id),
    CONSTRAINT ck_membership_link_status CHECK (
        status IN ('PENDING_INVITE', 'LINKED', 'DRIFTED', 'REVOKED')
    ),
    CONSTRAINT ck_membership_link_expected_roles CHECK (
        expected_roles <@ ARRAY['tenant-owner', 'tenant-admin', 'tenant-viewer']::varchar(64)[]
    ),
    -- A drift code without DRIFTED, or DRIFTED without a code, is a row that
    -- cannot be explained on Friday.
    CONSTRAINT ck_membership_link_drift CHECK (
        (status = 'DRIFTED') = (last_drift_code IS NOT NULL)
    ),
    CONSTRAINT ck_membership_link_version CHECK (version >= 0),
    CONSTRAINT uq_membership_link_tenant_principal UNIQUE (tenant_id, principal_id)
);

-- The drift report's own scan: everything the platform believes about one
-- organization, without touching the tenant table first.
CREATE INDEX ix_membership_link_organization
    ON iam.tenant_membership_links (keycloak_organization_id);

-- Least-recently-reconciled first, which is the order a bounded scheduled sweep
-- has to walk so that no link is starved by a large estate.
CREATE INDEX ix_membership_link_reconciliation_age
    ON iam.tenant_membership_links (last_reconciled_at NULLS FIRST)
    WHERE status <> 'REVOKED';

CREATE INDEX ix_membership_link_drifted
    ON iam.tenant_membership_links (tenant_id)
    WHERE status = 'DRIFTED';

COMMENT ON TABLE iam.tenant_membership_links IS
    'ADR 0009. What the platform believes about who belongs to a tenant in Keycloak. Compared against the realm by the drift report; never corrected automatically.';

-- Why a reconciliation reached the answer it did.
--
-- One row per comparison, kept whether or not anything was wrong, because the
-- question asked on Friday is usually "when did this last look right?" and a
-- table that only records failures cannot answer it. `expected` and `observed`
-- are the two sides of the comparison as the code saw them, so a finding can be
-- re-read without re-running it against a realm that has since changed.
CREATE TABLE iam.identity_reconciliation_runs (
    id uuid PRIMARY KEY,

    -- Null for a realm-wide sweep that found an organization belonging to no
    -- tenant — which is itself a finding, and one that has no tenant to scope.
    tenant_id uuid,
    membership_link_id uuid,

    operation varchar(32) NOT NULL,
    trigger_source varchar(24) NOT NULL,

    -- Which onboarding step asked, when one did. ADR 0008 steps are the normal
    -- caller, and this is what joins a drift back to the run that hit it.
    onboarding_step_id uuid,

    -- SHA-256 of the desired shape. Two runs with the same hash asked Keycloak
    -- for the same thing, which is how a repeated attempt after an uncertain
    -- outcome is recognised as a retry rather than a second intention.
    desired_state_hash varchar(64) NOT NULL,

    keycloak_organization_id varchar(64),
    keycloak_subject_id varchar(64),
    keycloak_membership_id varchar(64),

    attempt_count integer NOT NULL DEFAULT 1,
    outcome varchar(24) NOT NULL,
    drift_code varchar(48),

    -- Identifiers, flags, and role names. Never a profile, a token, or an
    -- invitation link: ADR 0009 is explicit, and the jsonb checks below stop a
    -- future caller stuffing a whole Keycloak response in here.
    expected jsonb NOT NULL DEFAULT '{}'::jsonb,
    observed jsonb NOT NULL DEFAULT '{}'::jsonb,

    -- Safe to log. A drift message names identifiers and states, and a handler
    -- that would otherwise put a provider's sentence here must reduce it to a
    -- code first.
    detail varchar(1000),

    correlation_id varchar(128) NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_reconciliation_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_reconciliation_membership_link FOREIGN KEY (membership_link_id)
        REFERENCES iam.tenant_membership_links (id),
    CONSTRAINT fk_reconciliation_onboarding_step FOREIGN KEY (onboarding_step_id)
        REFERENCES tenant.onboarding_steps (id) ON DELETE SET NULL,
    CONSTRAINT ck_reconciliation_operation CHECK (
        operation IN (
            'ORGANIZATION_ENSURE', 'ORGANIZATION_VERIFY',
            'MEMBERSHIP_ENSURE', 'MEMBERSHIP_VERIFY',
            'ROLE_ENSURE', 'ORGANIZATION_ENABLE', 'ORGANIZATION_DISABLE'
        )
    ),
    CONSTRAINT ck_reconciliation_trigger CHECK (
        trigger_source IN ('ONBOARDING_STEP', 'SCHEDULED_DRIFT_REPORT', 'OPERATOR')
    ),
    CONSTRAINT ck_reconciliation_outcome CHECK (
        outcome IN ('IN_PROGRESS', 'MATCHED', 'RECONCILED', 'DRIFT_DETECTED', 'UNCERTAIN', 'FAILED')
    ),
    -- Drift that does not say which kind is a row nobody can triage.
    CONSTRAINT ck_reconciliation_drift_code CHECK (
        (outcome = 'DRIFT_DETECTED') = (drift_code IS NOT NULL)
    ),
    CONSTRAINT ck_reconciliation_finished CHECK (
        (outcome = 'IN_PROGRESS') = (finished_at IS NULL)
    ),
    CONSTRAINT ck_reconciliation_attempts CHECK (attempt_count >= 1),
    CONSTRAINT ck_reconciliation_expected CHECK (jsonb_typeof(expected) = 'object'),
    CONSTRAINT ck_reconciliation_observed CHECK (jsonb_typeof(observed) = 'object')
);

-- "What happened to this tenant, most recent first" — the support question.
CREATE INDEX ix_reconciliation_tenant_time
    ON iam.identity_reconciliation_runs (tenant_id, started_at DESC);

-- "What is currently wrong across the estate" — the triage question.
CREATE INDEX ix_reconciliation_open_drift
    ON iam.identity_reconciliation_runs (started_at DESC)
    WHERE outcome IN ('DRIFT_DETECTED', 'UNCERTAIN', 'FAILED');

-- Recognising a retry of an uncertain create by what it asked for.
CREATE INDEX ix_reconciliation_desired_state
    ON iam.identity_reconciliation_runs (desired_state_hash, started_at DESC);

COMMENT ON TABLE iam.identity_reconciliation_runs IS
    'ADR 0009. One row per comparison of Qoida against Keycloak, kept whether or not anything was wrong, so "when did this last look right?" has an answer. Never holds a token, an invitation link, or a user profile.';
COMMENT ON COLUMN iam.identity_reconciliation_runs.desired_state_hash IS
    'ADR 0009. SHA-256 of the desired shape, so a repeated attempt after an uncertain outcome is recognisable as a retry rather than a second intention.';

-- V0035's lesson: in production the application inherits `qoida_application` and
-- reaches exactly what a migration names. No DELETE anywhere here — a
-- reconciliation run is evidence, a membership link is revoked rather than
-- removed, and a principal outlives every tenant it was ever linked to.
GRANT SELECT, INSERT, UPDATE ON iam.principals TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON iam.tenant_membership_links TO qoida_application;
GRANT SELECT, INSERT, UPDATE ON iam.identity_reconciliation_runs TO qoida_application;
