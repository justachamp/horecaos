-- ADR 0015: one-time verification of a phone number, and the proof it becomes.
--
-- The row holds no code. It holds a keyed MAC over one (ADR 0029 lookupHash,
-- keyed from the ADR 0028 KEK), because six digits is a domain of a million and
-- an unkeyed digest of the code would be the code. It holds no grant either,
-- only a SHA-256 of one.
--
-- Three rules in this table are conditional UPDATEs rather than Java, and the
-- columns exist to carry those conditions: attempts_used < max_attempts is the
-- attempt limit, status = 'PENDING' is what makes a code single-use, and
-- grant_redeemed_at IS NULL is what makes a grant single-use.

CREATE TABLE customer.verification_challenges (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    purpose varchar(32) NOT NULL,
    contact_type varchar(16) NOT NULL,

    -- Keyed, per-tenant, deterministic: the same value customer.contact_points
    -- uses, so a number proved here matches the contact point stored for it.
    -- This is also the rate-limit key, and the only form of the number that is
    -- safe to put in a log line.
    destination_hash varchar(64) NOT NULL,

    -- Randomized AEAD, bound to this row. Needed because the number must survive
    -- from issuance to redemption; a design that asked the client to re-send it
    -- would let the client swap it.
    destination_encrypted text NOT NULL,

    -- A keyed MAC over (challenge id || ':' || code). The challenge id is in the
    -- value rather than the key domain so two live challenges holding the same
    -- six digits store different values, without giving every challenge its own
    -- derived key in the application's bounded key cache.
    code_hash varchar(64) NOT NULL,

    attempts_used smallint NOT NULL DEFAULT 0,
    max_attempts smallint NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'PENDING',

    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    settled_at timestamptz,

    grant_hash varchar(64),
    grant_expires_at timestamptz,
    grant_redeemed_at timestamptz,

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_verification_purpose CHECK (purpose IN ('SIGN_IN')),
    -- Only PHONE is issued today. Email OTP is a deliberate migration, not an
    -- accident of a wider constraint.
    CONSTRAINT ck_verification_contact_type CHECK (contact_type = 'PHONE'),
    CONSTRAINT ck_verification_status CHECK (
        status IN ('PENDING', 'VERIFIED', 'EXHAUSTED', 'SUPERSEDED', 'EXPIRED')
    ),
    CONSTRAINT ck_verification_max_attempts CHECK (max_attempts BETWEEN 1 AND 10),
    CONSTRAINT ck_verification_attempts CHECK (
        attempts_used >= 0 AND attempts_used <= max_attempts
    ),
    CONSTRAINT ck_verification_window CHECK (expires_at > issued_at),
    CONSTRAINT ck_verification_settled CHECK (
        (status = 'PENDING') = (settled_at IS NULL)
    ),
    -- A grant on a row that is not verified would be a challenge that is
    -- simultaneously guessable and redeemable.
    CONSTRAINT ck_verification_grant CHECK (
        (status = 'VERIFIED') = (grant_hash IS NOT NULL)
    ),
    CONSTRAINT ck_verification_grant_window CHECK (
        (grant_hash IS NULL) = (grant_expires_at IS NULL)
    ),
    CONSTRAINT ck_verification_grant_redeemed CHECK (
        grant_redeemed_at IS NULL OR grant_hash IS NOT NULL
    )
);

-- Redemption is a single probe on the digest alone, and two rows cannot hold
-- one grant.
CREATE UNIQUE INDEX ux_verification_grant
    ON customer.verification_challenges (grant_hash)
    WHERE grant_hash IS NOT NULL;

-- Serves both halves of the issuance check (how many in the window, and when
-- the last one went out) in one scan, and the supersede that follows it.
CREATE INDEX ix_verification_by_destination
    ON customer.verification_challenges (tenant_id, destination_hash, issued_at DESC);

CREATE INDEX ix_verification_pending_expiry
    ON customer.verification_challenges (expires_at)
    WHERE status = 'PENDING';

CREATE INDEX ix_verification_settled
    ON customer.verification_challenges (settled_at)
    WHERE status <> 'PENDING';

-- DELETE is not optional here, unlike everywhere else in this schema: the row
-- carries an encrypted phone number and ADR 0029 retention is enforced by the
-- application deleting it.
GRANT SELECT, INSERT, UPDATE, DELETE
    ON customer.verification_challenges TO qoida_application;

COMMENT ON TABLE customer.verification_challenges IS
    'ADR 0015. Holds a keyed MAC of a one-time code, never the code. Rows are deleted after their retention because they carry an ADR 0029 encrypted phone number.';
COMMENT ON COLUMN customer.verification_challenges.code_hash IS
    'ADR 0015/0028. Keyed MAC over the challenge id and the code. The key lives in the secrets manager, so a dump of this table yields nothing to attack.';
COMMENT ON COLUMN customer.verification_challenges.grant_hash IS
    'SHA-256 of a 256-bit single-use secret. Proof of a number, redeemed on the way to a Keycloak session. Never a session itself (ADR 0003).';
