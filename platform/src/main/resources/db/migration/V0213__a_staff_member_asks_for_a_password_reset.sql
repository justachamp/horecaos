-- ADR 0098: a staff member who forgot their password asks for a reset.
--
-- One row per Keycloak subject, and that uniqueness *is* the "one live reset
-- per account" rule: a second request reuses the row, clears the hash, and
-- puts it back in the queue, so the link already sent stops working and no
-- number of requests can accumulate live links for one account.
--
-- What that rule does *not* bound is how many emails a repeated request
-- produces: each requeue is another send. The request endpoint is
-- unauthenticated, so the upsert carries a cooldown in its own ON CONFLICT
-- ... WHERE -- a link sent within the last few minutes and still live is left
-- alone, and the request is a silent no-op. Without it, anybody who knows a
-- staff address can post it every few seconds and each post both kills the
-- link its owner is holding and queues another email to them.
--
-- It holds no address -- that lives in Keycloak and is read when the email is
-- sent -- and never the token itself: the relay makes the token at send time,
-- emails it, and keeps only its SHA-256 (ADR 0009, ADR 0029), so nobody who
-- reads this table can use a link.
--
-- No tenant_id, and deliberately. A staff account belongs to the identity
-- provider rather than to a tenant, and control-plane staff belong to no
-- Keycloak organization at all; a tenant column here would be null for half
-- the rows and would suggest a scoping rule that does not exist. What the
-- row does carry is the console it was asked from, because that decides
-- which origin the emailed link points at and nothing else.
--
--   QUEUED    waiting for the relay; next_attempt_at says when
--   SENT      emailed; the link works until expires_at
--   ACCEPTED  the password was set and the hash is gone
--   FAILED    the address was refused, the account vanished, or sending kept
--             failing; the staff member asks again
--
-- Expired is not a state: it is SENT with expires_at in the past, read at the
-- moment it matters, so no sweeper has to keep it true.

CREATE TABLE iam.password_resets (
    id uuid PRIMARY KEY,
    subject_id varchar(128) NOT NULL,
    console varchar(16) NOT NULL,
    locale varchar(8) NOT NULL,
    status varchar(16) NOT NULL,
    token_hash varchar(64),
    expires_at timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_error_code varchar(64),
    requested_at timestamptz NOT NULL,
    sent_at timestamptz,
    opened_at timestamptz,
    accepted_at timestamptz,
    version integer NOT NULL DEFAULT 1,

    CONSTRAINT uq_password_reset_subject UNIQUE (subject_id),
    CONSTRAINT uq_password_reset_token UNIQUE (token_hash),
    CONSTRAINT ck_password_reset_status CHECK (status IN ('QUEUED', 'SENT', 'ACCEPTED', 'FAILED')),
    CONSTRAINT ck_password_reset_console CHECK (console IN ('CONTROL_PLANE', 'OPERATIONS')),
    CONSTRAINT ck_password_reset_locale CHECK (locale IN ('uz', 'ru', 'en')),
    CONSTRAINT ck_password_reset_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_password_reset_hash CHECK (token_hash IS NULL OR token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_password_reset_queued CHECK (status <> 'QUEUED' OR next_attempt_at IS NOT NULL),
    CONSTRAINT ck_password_reset_sent CHECK (
        status <> 'SENT' OR (token_hash IS NOT NULL AND expires_at IS NOT NULL AND sent_at IS NOT NULL)),
    -- Only a sent reset carries a live link.
    CONSTRAINT ck_password_reset_link CHECK (status = 'SENT' OR token_hash IS NULL),
    CONSTRAINT ck_password_reset_accepted CHECK (status <> 'ACCEPTED' OR accepted_at IS NOT NULL)
);

CREATE INDEX ix_password_resets_due ON iam.password_resets (next_attempt_at) WHERE status = 'QUEUED';

COMMENT ON TABLE iam.password_resets IS
    'ADR 0098. A staff member''s password reset. No address, and only the hash of the one-time token.';

-- UPDATE is not optional here: every step after the insert is a conditional
-- UPDATE. Those conditions are the concurrency control -- the accept spends
-- the link with WHERE status = 'SENT', which only one of two concurrent
-- accepts can match, and it does so before either has called Keycloak. No
-- row lock is held across a remote call, deliberately: the pool is ten
-- connections wide and shared by every module.
GRANT SELECT, INSERT, UPDATE ON iam.password_resets TO horecaos_application;
