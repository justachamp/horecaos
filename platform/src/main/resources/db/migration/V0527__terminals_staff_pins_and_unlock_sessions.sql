-- ADR 0155: terminals and staff PINs.
--
-- A device authenticates as itself (ADR 0079); this adds the overlay that says which person is at it. A PIN is an
-- ATTRIBUTION factor, never authentication: it is per person per tenant, verified by the platform under a pepper
-- held in the ADR 0028 store, accepted only from an enrolled unlockable device that is already authenticated, and
-- it opens a terminal session -- a row that records who is acting and can only narrow what the device may do.
--
-- Four tables, the nullable via_device_id a ticket event needs to name both the person and the screen, and the
-- Telegram event classes the two operations alerts of this record raise. The columns on iam.device_principals
-- (attribution_mode, auto_lock_seconds, last_seen_at, last_seen_build) are V0525's, and the tenant-bearing key a
-- session references to pin its location to its device's, uq_device_principal_tenant_id_location, is V0501's.

-- ---------------------------------------------------------------------------
-- The credential
-- ---------------------------------------------------------------------------

CREATE TABLE iam.staff_pins (
    tenant_id uuid NOT NULL,
    staff_member_id uuid NOT NULL,
    -- NOT_SET: no PIN, or one a manager reset. ACTIVE: a digest exists. LOCKED: twelve wrong PINs in a rolling day
    -- lock the credential until an activation replaces it; a locked credential holds no digest.
    status varchar(10) NOT NULL DEFAULT 'NOT_SET',
    -- HMAC-SHA256(pepper[pepper_version], tenant | member | pin_version | pin), hex. Compared in constant time.
    -- A slow hash would add nothing to a million-entry space; the keyed digest is what keeps a database dump from
    -- being a list of working PINs.
    pin_digest varchar(64),
    pepper_version smallint,
    -- Mixed into the digest, so a digest cannot be replayed after the PIN it was made from is replaced or reset.
    pin_version integer NOT NULL DEFAULT 0,
    -- An existing PIN keeps the length it was set with; the policy's length applies to the next one set.
    pin_length smallint,
    set_at timestamptz,
    set_on_device_id uuid,
    -- SELF, or the issuing manager's subject: how the activation that set this PIN was issued.
    activated_by varchar(255),
    -- The rolling 24-hour count across every device. Success never clears it.
    window_started_at timestamptz,
    failures_in_window integer NOT NULL DEFAULT 0,
    locked_at timestamptz,
    locked_reason varchar(24),
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_staff_pins PRIMARY KEY (tenant_id, staff_member_id),
    CONSTRAINT fk_staff_pin_member FOREIGN KEY (staff_member_id, tenant_id)
        REFERENCES iam.staff_members (id, tenant_id),
    CONSTRAINT fk_staff_pin_device FOREIGN KEY (tenant_id, set_on_device_id)
        REFERENCES iam.device_principals (tenant_id, id),
    CONSTRAINT ck_staff_pin_status CHECK (status IN ('NOT_SET', 'ACTIVE', 'LOCKED')),
    -- A credential locked by the 24-hour rule drops its digest: only an activation can replace it.
    CONSTRAINT ck_staff_pin_digest CHECK ((status = 'ACTIVE') = (pin_digest IS NOT NULL)),
    CONSTRAINT ck_staff_pin_shape CHECK (
        (pin_digest IS NULL AND pepper_version IS NULL AND pin_length IS NULL)
        OR (pin_digest IS NOT NULL AND pepper_version IS NOT NULL AND pin_length IN (4, 6))),
    CONSTRAINT ck_staff_pin_lock CHECK ((status = 'LOCKED') = (locked_at IS NOT NULL AND locked_reason IS NOT NULL)),
    CONSTRAINT ck_staff_pin_counters CHECK (failures_in_window >= 0 AND pin_version >= 0 AND version >= 1)
);

COMMENT ON TABLE iam.staff_pins IS
    'ADR 0155. One PIN per staff member per tenant: an attribution factor that names the person at an already-authenticated device. It never signs anyone in, and a manager can neither choose nor read one.';

-- ---------------------------------------------------------------------------
-- Single-use activation codes
-- ---------------------------------------------------------------------------

CREATE TABLE iam.staff_pin_activations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    staff_member_id uuid NOT NULL,
    -- Eight digits, hashed like the PIN and never stored. Shown once, to whoever issued it.
    code_digest varchar(64) NOT NULL,
    issued_by varchar(255) NOT NULL,
    issued_via varchar(8) NOT NULL,
    status varchar(10) NOT NULL DEFAULT 'PENDING',
    -- Burned at the fifth wrong try.
    failed_attempts smallint NOT NULL DEFAULT 0,
    -- Fifteen minutes when the person issued it from their own profile, four hours when a manager did.
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    consumed_device_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_staff_pin_activation_member FOREIGN KEY (staff_member_id, tenant_id)
        REFERENCES iam.staff_members (id, tenant_id),
    CONSTRAINT fk_staff_pin_activation_device FOREIGN KEY (tenant_id, consumed_device_id)
        REFERENCES iam.device_principals (tenant_id, id),
    CONSTRAINT ck_staff_pin_activation_via CHECK (issued_via IN ('SELF', 'MANAGER')),
    CONSTRAINT ck_staff_pin_activation_status
        CHECK (status IN ('PENDING', 'CONSUMED', 'EXPIRED', 'SUPERSEDED', 'BURNED')),
    CONSTRAINT ck_staff_pin_activation_consumed CHECK ((status = 'CONSUMED') = (consumed_at IS NOT NULL)),
    CONSTRAINT ck_staff_pin_activation_attempts CHECK (failed_attempts BETWEEN 0 AND 5)
);

-- A second PENDING code supersedes the first, so at most one is ever live for a person.
CREATE UNIQUE INDEX uq_staff_pin_activation_pending
    ON iam.staff_pin_activations (tenant_id, staff_member_id) WHERE status = 'PENDING';
CREATE INDEX ix_staff_pin_activation_member ON iam.staff_pin_activations (tenant_id, staff_member_id, created_at DESC);

COMMENT ON TABLE iam.staff_pin_activations IS
    'ADR 0155. A single-use code that lets a person set a first PIN at a terminal without the manager choosing it. The digest is keyed; the code itself exists only on the screen of whoever issued it.';

-- ---------------------------------------------------------------------------
-- The per-device lock
-- ---------------------------------------------------------------------------

-- Five consecutive wrong PINs lock that person on that device for fifteen minutes. Durable and in the database,
-- serialized per person by the staff_pins row lock -- never in the ADR 0033 limiter, which is per replica and
-- forgets on restart: a restart must not forgive a guesser.
CREATE TABLE iam.terminal_pin_locks (
    tenant_id uuid NOT NULL,
    device_id uuid NOT NULL,
    staff_member_id uuid NOT NULL,
    consecutive_failures smallint NOT NULL DEFAULT 0,
    locked_until timestamptz,
    last_failure_at timestamptz,

    CONSTRAINT pk_terminal_pin_locks PRIMARY KEY (device_id, staff_member_id),
    CONSTRAINT fk_terminal_pin_lock_device FOREIGN KEY (tenant_id, device_id)
        REFERENCES iam.device_principals (tenant_id, id),
    CONSTRAINT fk_terminal_pin_lock_member FOREIGN KEY (staff_member_id, tenant_id)
        REFERENCES iam.staff_members (id, tenant_id),
    CONSTRAINT ck_terminal_pin_lock_failures CHECK (consecutive_failures >= 0)
);

COMMENT ON TABLE iam.terminal_pin_locks IS
    'ADR 0155. Consecutive wrong PINs per person per device and the fifteen-minute lock they earn. Durable: a process restart does not forgive a guesser.';

-- ---------------------------------------------------------------------------
-- The terminal session
-- ---------------------------------------------------------------------------

CREATE TABLE iam.terminal_sessions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    device_id uuid NOT NULL,
    staff_member_id uuid NOT NULL,
    location_id uuid NOT NULL,
    -- SHA-256 of a 256-bit token the device holds in memory only, so a reload locks the terminal. The token is
    -- accepted only together with the device's own Keycloak bearer and confers nothing by itself.
    token_digest varchar(64) NOT NULL,
    opened_at timestamptz NOT NULL,
    last_activity_at timestamptz NOT NULL,
    idle_seconds integer NOT NULL,
    hard_expires_at timestamptz NOT NULL,
    ended_at timestamptz,
    ended_reason varchar(16),

    CONSTRAINT uq_terminal_session_token UNIQUE (token_digest),
    -- The session's location is its device's location, enforced by the database and not by the service.
    CONSTRAINT fk_terminal_session_device FOREIGN KEY (tenant_id, device_id, location_id)
        REFERENCES iam.device_principals (tenant_id, id, location_id),
    CONSTRAINT fk_terminal_session_member FOREIGN KEY (staff_member_id, tenant_id)
        REFERENCES iam.staff_members (id, tenant_id),
    CONSTRAINT ck_terminal_session_reason CHECK (ended_reason IS NULL OR ended_reason IN (
        'LOCKED', 'SUPERSEDED', 'REVOKED', 'PIN_RESET', 'EMPLOYMENT_ENDED', 'IDLE', 'MAX_AGE')),
    CONSTRAINT ck_terminal_session_ended CHECK ((ended_at IS NULL) = (ended_reason IS NULL)),
    CONSTRAINT ck_terminal_session_limits CHECK (idle_seconds BETWEEN 30 AND 3600 AND hard_expires_at > opened_at)
);

-- At most one live session per device and one per person across the tenant: opening a second ends the first, so
-- the live session is where the person is.
CREATE UNIQUE INDEX uq_terminal_session_live_device ON iam.terminal_sessions (device_id) WHERE ended_at IS NULL;
CREATE UNIQUE INDEX uq_terminal_session_live_member
    ON iam.terminal_sessions (tenant_id, staff_member_id) WHERE ended_at IS NULL;
-- The person's own last-twenty list, and a device's history.
CREATE INDEX ix_terminal_session_member ON iam.terminal_sessions (tenant_id, staff_member_id, opened_at DESC);
CREATE INDEX ix_terminal_session_device ON iam.terminal_sessions (tenant_id, device_id, opened_at DESC);

COMMENT ON TABLE iam.terminal_sessions IS
    'ADR 0155. Who is unlocked at which device, and for how long. A row that attributes and narrows, never a bearer that authorizes: it is honoured only alongside the device''s own token, from the device it was issued to.';

-- Granted to the role the application connects as (V0035). Sessions and activations are never deleted by a request;
-- only the purge job issues DELETE, for rows older than 400 days. The pin-lock rows are deleted by a PIN reset or a
-- successful activation, which is the end of the failures they count.
GRANT SELECT, INSERT, UPDATE ON iam.staff_pins TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON iam.staff_pin_activations TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON iam.terminal_pin_locks TO horecaos_application;
GRANT SELECT, INSERT, UPDATE, DELETE ON iam.terminal_sessions TO horecaos_application;

-- ---------------------------------------------------------------------------
-- A ticket event names both the person and the screen
-- ---------------------------------------------------------------------------

-- Written whenever a terminal session was the attribution, so a board action names the person (actor_id) and the
-- device it was pressed on. Tenant-scoped; null for every action not taken through a session, so the match-simple
-- key is unenforced exactly there.
ALTER TABLE kitchen.ticket_events ADD COLUMN via_device_id uuid;
ALTER TABLE kitchen.ticket_events
    ADD CONSTRAINT fk_ticket_event_via_device FOREIGN KEY (tenant_id, via_device_id)
        REFERENCES iam.device_principals (tenant_id, id);

-- ---------------------------------------------------------------------------
-- The two operations alerts of this record
-- ---------------------------------------------------------------------------

-- TERMINAL_CREDENTIAL_LOCKED (a PIN lock on a device or on the credential) and TERMINAL_DEVICE_UNSEEN (a
-- PERSON_REQUIRED device unseen during service) join the enumerated event class list V0488 last restated. The
-- full current list is carried forward rather than a clause added: a bare DROP/ADD pair silently drops every
-- value the previous migration did not know about. ADR 0154's V0522 widened the same list for PRINTER_OFFLINE and
-- PRINT_JOB_DEAD and runs before this one, so both are carried forward here (the integrator found them missing
-- when the two waves met).
ALTER TABLE integration.telegram_binding_events DROP CONSTRAINT ck_telegram_binding_event_class;
ALTER TABLE integration.telegram_binding_events
    ADD CONSTRAINT ck_telegram_binding_event_class CHECK (
        event_class IN (
            'ORDER_CONFIRMED', 'ORDER_REJECTED', 'ORDER_APPROVAL_DEADLINE_WARNING',
            'ORDER_AWAITING_APPROVAL',
            'DIGEST_15M', 'DIGEST_HALF_DAY', 'DIGEST_DAY_CLOSE',
            'PLATFORM_DIGEST_HALF_DAY', 'PLATFORM_DIGEST_DAY_CLOSE',
            'PAYMENT_ATTEMPT_FAILED', 'PAYMENT_ATTEMPT_NEEDS_OPERATOR',
            'FISCAL_DOCUMENT_BLOCKED',
            'ITEM_86D',
            'DEAD_LETTER_RECORDED', 'POS_EXPORT_AWAITING_OPERATOR',
            'CAMPAIGN_BLOCK_RATE_PAUSED',
            'MARKETPLACE_CHANNEL_STALE',
            'PRINTER_OFFLINE', 'PRINT_JOB_DEAD',
            'TERMINAL_CREDENTIAL_LOCKED', 'TERMINAL_DEVICE_UNSEEN'));
