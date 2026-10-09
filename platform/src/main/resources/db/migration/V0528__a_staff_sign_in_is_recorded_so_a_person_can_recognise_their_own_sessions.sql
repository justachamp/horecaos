-- ADR 0176: the platform's own record of a staff sign-in, so a person can tell which of
-- their sessions is their phone.
--
-- Keycloak stays the authority for whether a session is alive; it has no browser name
-- and no tenant. The platform is the party the browser talks to at sign-in (ADR 0062),
-- so it writes one row per tenant the token names, with a device label out of three
-- small closed sets and the times, and nothing else about the device. There is no
-- column for an address, a header, a position or a device identifier, on purpose:
-- ADR 0176 Decision 3, and a canary test asserts it.
--
-- Operational history, not evidence (Decision 7): rows are kept 180 days and swept.
-- Nothing audits a sign-in; what is audited is the act that ends sessions. That is why
-- the grant at the foot has DELETE, which the staff member table's grant does not --
-- only the retention sweeper uses it.

CREATE TABLE iam.staff_sign_ins (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    -- The same opaque string iam.grants and the audit trail key an actor by.
    principal_subject varchar(255) NOT NULL,
    -- The `sid` of the access token: Keycloak's id for the offline session the direct
    -- grant created (probed live, infra/keycloak/spikes/staff-sessions-probe.py).
    keycloak_session_id varchar(64) NOT NULL,
    browser_family varchar(24) NOT NULL,
    os_family varchar(16) NOT NULL,
    device_form varchar(12) NOT NULL,
    signed_in_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    ended_at timestamptz,
    end_reason varchar(24),

    CONSTRAINT fk_staff_sign_in_member FOREIGN KEY (tenant_id, principal_subject)
        REFERENCES iam.staff_members (tenant_id, principal_subject),
    CONSTRAINT uq_staff_sign_in_session UNIQUE (tenant_id, keycloak_session_id),
    CONSTRAINT ck_staff_sign_in_ended_pair CHECK ((ended_at IS NULL) = (end_reason IS NULL)),
    CONSTRAINT ck_staff_sign_in_browser CHECK (
        browser_family IN ('CHROME', 'SAFARI', 'FIREFOX', 'EDGE', 'YANDEX', 'SAMSUNG', 'OTHER')),
    CONSTRAINT ck_staff_sign_in_os CHECK (
        os_family IN ('WINDOWS', 'MACOS', 'LINUX', 'ANDROID', 'IOS', 'CHROMEOS', 'OTHER')),
    CONSTRAINT ck_staff_sign_in_form CHECK (device_form IN ('DESKTOP', 'PHONE', 'TABLET')),
    CONSTRAINT ck_staff_sign_in_reason CHECK (
        end_reason IS NULL OR end_reason IN (
            'SIGNED_OUT', 'SIGNED_OUT_EVERYWHERE', 'PASSWORD_RESET', 'ENDED_ONE', 'EXPIRED')),
    CONSTRAINT ck_staff_sign_in_times CHECK (last_seen_at >= signed_in_at)
);

CREATE INDEX ix_staff_sign_in_person
    ON iam.staff_sign_ins (tenant_id, principal_subject, signed_in_at DESC);

-- The retention sweep and the idle-expiry sweep read by age, across tenants.
CREATE INDEX ix_staff_sign_in_age ON iam.staff_sign_ins (signed_in_at);
CREATE INDEX ix_staff_sign_in_open_seen ON iam.staff_sign_ins (last_seen_at) WHERE ended_at IS NULL;

COMMENT ON TABLE iam.staff_sign_ins IS
    'ADR 0176. One row per tenant a staff sign-in named: the Keycloak session id, a closed-vocabulary device label and the times. Keycloak decides whether a session is alive; this row says what it was. No address, header, position or device identifier, ever. Kept iam.staff_sign_in_retention_days (default 180) and swept.';
COMMENT ON COLUMN iam.staff_sign_ins.keycloak_session_id IS
    'The sid claim of the access token the direct grant returned: Keycloak''s id for the offline session.';
COMMENT ON COLUMN iam.staff_sign_ins.end_reason IS
    'Why the platform believes the session ended: SIGNED_OUT (this device), SIGNED_OUT_EVERYWHERE (the person pressed the act), PASSWORD_RESET (ADR 0098, only after logoutEverywhere succeeded), ENDED_ONE (one session ended from the list), EXPIRED (the idle sweep: Keycloak no longer lists it).';

GRANT SELECT, INSERT, UPDATE, DELETE ON iam.staff_sign_ins TO horecaos_application;
