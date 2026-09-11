-- ADR 0097: HorecaOS invites a tenant's owner itself.
--
-- One row per tenant and owner account. It holds no address -- that lives in
-- Keycloak and is read when the email is sent -- and never the token itself:
-- the relay makes the token at send time, emails it, and keeps only its
-- SHA-256, so nobody who reads this table can use a link (ADR 0009). A resend
-- reuses the row, clearing the hash, so the earlier link stops working.
--
--   QUEUED      waiting for the relay; next_attempt_at says when
--   SENT        emailed; the link works until expires_at
--   ACCEPTED    the owner set a password; the hash is gone
--   NOT_NEEDED  the account already had a password when the relay looked
--   FAILED      the address was refused, the account vanished, or sending
--               kept failing; a person resends it
--
-- Expired is not a state: it is SENT with expires_at in the past, read at the
-- moment it matters, so no sweeper has to keep it true.

CREATE TABLE tenant.owner_invitations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    subject_id varchar(128) NOT NULL,
    locale varchar(8) NOT NULL,
    status varchar(16) NOT NULL,
    token_hash varchar(64),
    expires_at timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_error_code varchar(64),
    queued_at timestamptz NOT NULL,
    queued_by varchar(255) NOT NULL,
    sent_at timestamptz,
    opened_at timestamptz,
    accepted_at timestamptz,
    version integer NOT NULL DEFAULT 1,

    CONSTRAINT fk_owner_invitation_tenant FOREIGN KEY (tenant_id) REFERENCES tenant.tenants (id),
    CONSTRAINT uq_owner_invitation UNIQUE (tenant_id, subject_id),
    CONSTRAINT uq_owner_invitation_token UNIQUE (token_hash),
    CONSTRAINT ck_owner_invitation_status CHECK (
        status IN ('QUEUED', 'SENT', 'ACCEPTED', 'NOT_NEEDED', 'FAILED')),
    CONSTRAINT ck_owner_invitation_locale CHECK (locale IN ('uz', 'ru', 'en')),
    CONSTRAINT ck_owner_invitation_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_owner_invitation_hash CHECK (token_hash IS NULL OR token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_owner_invitation_queued CHECK (status <> 'QUEUED' OR next_attempt_at IS NOT NULL),
    CONSTRAINT ck_owner_invitation_sent CHECK (
        status <> 'SENT' OR (token_hash IS NOT NULL AND expires_at IS NOT NULL AND sent_at IS NOT NULL)),
    -- Only a sent invitation carries a live link.
    CONSTRAINT ck_owner_invitation_link CHECK (status = 'SENT' OR token_hash IS NULL),
    CONSTRAINT ck_owner_invitation_accepted CHECK (status <> 'ACCEPTED' OR accepted_at IS NOT NULL)
);

CREATE INDEX ix_owner_invitations_due ON tenant.owner_invitations (next_attempt_at) WHERE status = 'QUEUED';

COMMENT ON TABLE tenant.owner_invitations IS
    'ADR 0097. A tenant owner''s invitation to set up their account. No address, and only the hash of the one-time token.';

GRANT SELECT, INSERT, UPDATE ON tenant.owner_invitations TO horecaos_application;
