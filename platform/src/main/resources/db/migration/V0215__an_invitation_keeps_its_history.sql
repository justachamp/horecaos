-- ADR 0100: an owner invitation keeps a history, not just a position.
--
-- V0210's tenant.owner_invitations holds one mutable row per tenant and owner.
-- A resend rewrites it: the token hash, the expiry, sent_at, opened_at and the
-- last error go, and attempts returns to zero. That row answers "where does
-- this stand now" and destroys the answer to "what has happened to it", which
-- is the question an operator asks when an owner says nothing arrived.
--
-- This table is the history. One row per thing that happened, appended, never
-- rewritten -- the grant below is SELECT and INSERT and that is the guarantee,
-- not a convention somebody has to remember.
--
--   QUEUED         onboarding, or an operator, put it in the queue
--   RESENT         an operator requeued it with a new link, and said why
--   SENT           the relay emailed it on this attempt
--   SEND_DEFERRED  an attempt did not deliver and will be tried again
--   SEND_FAILED    sending was given up on; a person decides what next
--   OPENED         the owner opened the link (the first open only)
--   ACCEPTED       the owner set a name and a password
--   NOT_NEEDED     the account already had a password when the relay looked
--
-- It holds no address, for the same reason the parent does not (ADR 0097): the
-- address lives in Keycloak and is read when it is needed. actor_reference is
-- a subject id or a job name, never a display name. reason is the operators
-- own words for a resend -- the same text the ADR 0027 fact carries -- and is
-- absent on every machine event.

ALTER TABLE tenant.owner_invitations
    ADD CONSTRAINT uq_owner_invitation_tenant_id UNIQUE (tenant_id, id);

CREATE TABLE tenant.owner_invitation_events (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    invitation_id uuid NOT NULL,
    event_type varchar(24) NOT NULL,
    -- The send attempt this event belongs to, counted from one; zero on an
    -- event that is not a send attempt (a queue, a resend).
    attempt integer NOT NULL DEFAULT 0,
    locale varchar(8),
    -- What this event came to: a mail or identity failure code on a send
    -- attempt, and on a resend the state it replaced. Never a message, and
    -- never anything a provider wrote -- a code the platform chose.
    outcome_code varchar(64),
    actor_type varchar(16) NOT NULL,
    actor_reference varchar(255),
    reason varchar(1000),
    occurred_at timestamptz NOT NULL,
    -- Two events can share an instant: the platform clock is a fixed Clock in
    -- tests and a millisecond is long enough in production. clock_timestamp()
    -- is the real wall clock rather than the transaction's, so it separates
    -- them even inside one transaction, and the timeline reads in order.
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT fk_owner_invitation_event_invitation
        FOREIGN KEY (tenant_id, invitation_id)
        REFERENCES tenant.owner_invitations (tenant_id, id),
    CONSTRAINT ck_owner_invitation_event_type CHECK (event_type IN (
        'QUEUED', 'RESENT', 'SENT', 'SEND_DEFERRED', 'SEND_FAILED',
        'OPENED', 'ACCEPTED', 'NOT_NEEDED')),
    CONSTRAINT ck_owner_invitation_event_actor CHECK (actor_type IN ('SYSTEM_JOB', 'USER', 'OWNER')),
    CONSTRAINT ck_owner_invitation_event_attempt CHECK (attempt >= 0),
    CONSTRAINT ck_owner_invitation_event_locale CHECK (locale IS NULL OR locale IN ('uz', 'ru', 'en'))
);

CREATE INDEX ix_owner_invitation_events_timeline
    ON tenant.owner_invitation_events (tenant_id, invitation_id, occurred_at, recorded_at);

COMMENT ON TABLE tenant.owner_invitation_events IS
    'ADR 0100. Append-only history of one owner invitation: queues, send attempts, opens, accepts and resends. No address.';

GRANT SELECT, INSERT ON tenant.owner_invitation_events TO horecaos_application;
