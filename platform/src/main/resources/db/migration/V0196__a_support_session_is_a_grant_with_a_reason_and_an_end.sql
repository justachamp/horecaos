-- ADR 0081: a HorecaOS support person enters a tenant only through a support
-- session -- a time-boxed grant of a support-only role, opened with a reason,
-- visible to the tenant, and ended by revoking that grant.
--
-- The grant is the authority; this row is the account of it. Authorization
-- never reads this table: iam.grants.valid_until already stops the grant at
-- the deadline on the ordinary ADR 0025 path, so a session cannot outlive its
-- window even if nothing here is ever updated again.
CREATE TABLE iam.support_sessions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant.tenants (id),
    -- The support person's own Keycloak subject: the exact value their grant
    -- and every audit fact they cause carry. There is no second identity.
    principal_subject varchar(255) NOT NULL,
    access varchar(16) NOT NULL,
    -- The iam.grants row this session conferred, tenant-scoped against
    -- uq_grant_tenant_id so one tenant's session can never name another's grant.
    grant_id uuid NOT NULL,
    reason varchar(1000) NOT NULL,
    ticket_reference varchar(200),
    started_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    ended_at timestamptz,
    ended_by varchar(255),
    end_reason varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_support_session_access CHECK (access IN ('VIEW', 'ASSIST')),
    CONSTRAINT ck_support_session_reason CHECK (length(btrim(reason)) > 0),
    -- Four hours is the longest a person should be inside someone else's
    -- account without stopping to ask again; a longer job is a second session.
    CONSTRAINT ck_support_session_window CHECK (
        expires_at > started_at AND expires_at <= started_at + interval '4 hours'),
    CONSTRAINT ck_support_session_end CHECK (
        (ended_at IS NULL AND ended_by IS NULL AND end_reason IS NULL)
        OR (ended_at IS NOT NULL AND ended_by IS NOT NULL AND end_reason IS NOT NULL)),
    CONSTRAINT fk_support_session_grant FOREIGN KEY (tenant_id, grant_id) REFERENCES iam.grants (tenant_id, id)
);

CREATE INDEX ix_support_session_tenant ON iam.support_sessions (tenant_id, started_at DESC);
CREATE INDEX ix_support_session_open ON iam.support_sessions (principal_subject, expires_at) WHERE ended_at IS NULL;

COMMENT ON TABLE iam.support_sessions IS
    'ADR 0081. The account of one support visit: who, which tenant, how much access, why, and when it ended. The authority itself is the iam.grants row it names.';

GRANT SELECT, INSERT, UPDATE ON iam.support_sessions TO horecaos_application;
