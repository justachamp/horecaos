-- ADR 0116: a staff member is invited with a job (staff-and-access.md §4, gap
-- map row 9.1a).
--
-- A sibling to tenant.owner_invitations, not a generalisation of it by a
-- `kind` column -- see ADR 0116's decision for why. The owner table's own
-- uq_owner_invitation enforces one live row per tenant, which is correct for
-- an owner and wrong for staff: a tenant may have several colleagues invited
-- at once, and each invitation is for an account and a grant that were
-- created *before* this row exists, not after acceptance the way an owner's
-- authority is. So this table is not "waiting for an account" the way the
-- owner table is; it is "here is the one-time link for an account and a
-- grant that already exist".
--
-- Holds no name, phone or email -- OwnerInvitationService's stance, repeated
-- here for the same reason: the profile lives in Keycloak, and the only key
-- back to it is subject_id. Only the SHA-256 of the one-time link's token is
-- kept; the token itself exists once, in the response that creates this row,
-- and is never logged, audited, or written anywhere else (ADR 0029).
--
--   QUEUED     the row exists; no email was given, or the send has not
--              completed yet -- the link is still handed back once in the
--              create response either way (staff-and-access.md §4)
--   SENT       emailed successfully; the link works until expires_at
--   OPENED     the invited person opened the link
--   ACCEPTED   a password was set; the hash is gone
--   CANCELLED  revoked before acceptance; the grant this row names is
--              revoked in the same action, under its own audit fact
--
-- Expired is not a stored state: any of the first three with expires_at in
-- the past, read at the moment it matters -- the same choice the owner table
-- makes and for the same reason: no sweeper has to keep it true.

CREATE TABLE tenant.staff_invitations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    subject_id varchar(128) NOT NULL,
    grant_id uuid NOT NULL,
    locale varchar(8) NOT NULL,
    status varchar(16) NOT NULL,
    token_hash varchar(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    email_given boolean NOT NULL,
    invited_by varchar(255) NOT NULL,
    invited_at timestamptz NOT NULL,
    sent_at timestamptz,
    opened_at timestamptz,
    accepted_at timestamptz,
    cancelled_at timestamptz,
    cancelled_by varchar(255),
    last_error_code varchar(64),
    version integer NOT NULL DEFAULT 1,

    CONSTRAINT fk_staff_invitation_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    -- The grant this invitation was created for. One invitation per grant,
    -- and it is looked up the same direction on revoke: cancel the
    -- invitation, revoke the grant it names, one audit fact each.
    CONSTRAINT fk_staff_invitation_grant FOREIGN KEY (grant_id) REFERENCES iam.grants (id),
    CONSTRAINT uq_staff_invitation_grant UNIQUE (grant_id),
    CONSTRAINT uq_staff_invitation_token UNIQUE (token_hash),
    CONSTRAINT ck_staff_invitation_status CHECK (
        status IN ('QUEUED', 'SENT', 'OPENED', 'ACCEPTED', 'CANCELLED')),
    CONSTRAINT ck_staff_invitation_locale CHECK (locale IN ('uz', 'ru', 'en')),
    CONSTRAINT ck_staff_invitation_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_staff_invitation_accepted CHECK (status <> 'ACCEPTED' OR accepted_at IS NOT NULL),
    CONSTRAINT ck_staff_invitation_cancelled CHECK (
        status <> 'CANCELLED' OR (cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL))
);

-- The People screen's outstanding-invitations read, and resend/revoke's own
-- lookup: both are "this tenant's invitations", newest first.
CREATE INDEX ix_staff_invitations_tenant ON tenant.staff_invitations (tenant_id, invited_at DESC);

-- Resend/revoke reach a row by (tenantId, subjectId) from the People screen,
-- which knows a principalSubject and not an invitation id.
CREATE INDEX ix_staff_invitations_subject ON tenant.staff_invitations (tenant_id, subject_id);

COMMENT ON TABLE tenant.staff_invitations IS
    'ADR 0116. A staff member invited with a job: the account and the grant already exist; this is only the one-time link to set a password.';

GRANT SELECT, INSERT, UPDATE ON tenant.staff_invitations TO horecaos_application;
