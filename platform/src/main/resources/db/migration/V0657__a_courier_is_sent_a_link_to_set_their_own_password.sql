-- ADR 0178, couriers.md §18: a courier's first sign-in and a reset of it.
--
-- CourierAccountProvisioningService creates the courier's account at the identity provider when the
-- courier is registered, and leaves it without a password: the courier mobile app's onboarding did
-- not exist. So a provisioned courier had no way to sign in, and the console had nothing to press.
-- Now a person with courier.engagement.manage sends «Send invite» (a first sign-in) or «Reset access»
-- and the courier is texted a one-time link; the console never shows, derives or transmits a
-- credential (couriers.md §18: the passport serial was the legacy password, deliberately removed).
--
-- A sibling table of iam.password_resets and tenancy's staff invitations (ADR 0116), not a column on
-- the courier: a courier has many invitations over a working life, and the history of who asked for
-- access, when it was sent and when it was spent is the evidence a dispute about a locked-out courier
-- needs. It differs from the staff reset in two ways that matter: the person asking is an authenticated
-- manager (so there is no anonymous request to defend), and the link goes by SMS to the courier's phone,
-- which this table does not hold -- the number lives at the identity provider and is read when the SMS is
-- sent. The token is never stored either: the relay makes it at send time, texts it, and keeps its
-- SHA-256, so nobody who reads this table can use a link.
--
--   QUEUED    waiting for the relay; next_attempt_at says when
--   SENT      texted; the link works until expires_at
--   ACCEPTED  the password was set and the hash is gone
--   VOIDED    replaced by a newer invitation, or the courier left, before it was spent
--   FAILED    the phone was missing, the gateway refused, or sending kept failing
--
-- Expired is not a state: it is SENT with expires_at in the past, read when it matters.
-- At most one invitation per courier is live (QUEUED or SENT): asking again voids the old one, so the
-- link already texted stops working and no number of requests accumulates live links.

CREATE TABLE fulfillment.courier_access_invitations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    courier_id uuid NOT NULL,
    kind varchar(8) NOT NULL,
    status varchar(16) NOT NULL,
    locale varchar(16) NOT NULL,
    token_hash varchar(64),
    expires_at timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_error_code varchar(64),
    requested_by varchar(128) NOT NULL,
    requested_at timestamptz NOT NULL,
    sent_at timestamptz,
    opened_at timestamptz,
    accepted_at timestamptz,
    voided_at timestamptz,
    version integer NOT NULL DEFAULT 1,

    CONSTRAINT fk_courier_access_courier FOREIGN KEY (courier_id, tenant_id)
        REFERENCES fulfillment.couriers (id, tenant_id),
    CONSTRAINT uq_courier_access_token UNIQUE (token_hash),
    CONSTRAINT ck_courier_access_kind CHECK (kind IN ('INVITE', 'RESET')),
    CONSTRAINT ck_courier_access_status CHECK (status IN ('QUEUED', 'SENT', 'ACCEPTED', 'VOIDED', 'FAILED')),
    CONSTRAINT ck_courier_access_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_courier_access_hash CHECK (token_hash IS NULL OR token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_courier_access_queued CHECK (status <> 'QUEUED' OR next_attempt_at IS NOT NULL),
    CONSTRAINT ck_courier_access_sent CHECK (
        status <> 'SENT' OR (token_hash IS NOT NULL AND expires_at IS NOT NULL AND sent_at IS NOT NULL)),
    -- Only a sent invitation carries a live link.
    CONSTRAINT ck_courier_access_link CHECK (status = 'SENT' OR token_hash IS NULL),
    CONSTRAINT ck_courier_access_accepted CHECK (status <> 'ACCEPTED' OR accepted_at IS NOT NULL),
    CONSTRAINT ck_courier_access_voided CHECK (status <> 'VOIDED' OR voided_at IS NOT NULL)
);

CREATE UNIQUE INDEX ux_courier_access_live
    ON fulfillment.courier_access_invitations (tenant_id, courier_id)
    WHERE status IN ('QUEUED', 'SENT');

CREATE INDEX ix_courier_access_due
    ON fulfillment.courier_access_invitations (next_attempt_at)
    WHERE status = 'QUEUED';

CREATE INDEX ix_courier_access_courier
    ON fulfillment.courier_access_invitations (tenant_id, courier_id, requested_at DESC);

COMMENT ON TABLE fulfillment.courier_access_invitations IS
    'ADR 0178. A courier''s invitation to set their own password, or a reset of it, sent by SMS. No phone number, and only the hash of the one-time token.';
COMMENT ON COLUMN fulfillment.courier_access_invitations.requested_by IS
    'The Keycloak subject of the manager who pressed Send invite or Reset access. Never the courier.';

-- UPDATE is not optional: every step after the insert is a conditional UPDATE, and those conditions are
-- the concurrency control (the accept spends the link with WHERE status = 'SENT').
GRANT SELECT, INSERT, UPDATE ON fulfillment.courier_access_invitations TO horecaos_application;
