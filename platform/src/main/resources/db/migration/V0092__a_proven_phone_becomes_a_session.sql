-- ADR 0051: the customer session, which is the step that was missing between
-- "the code was right" and "a principal exists".
--
-- One table, and the shape of it is the decision. A session is an opaque bearer
-- token stored only as a SHA-256 digest, and every binding it carries — tenant,
-- brand, account, identity partition — is a column here rather than a claim
-- inside the token. Nothing a client holds can be decoded, and therefore nothing
-- a client holds can be edited to reach another account: the token is 256 bits
-- of CSPRNG output that means nothing except as a key into this row.
--
-- That is why this is not a second JWT issuer. A JWT subject is unique only
-- within the realm that minted it, which is the argument PrincipalCustomer makes
-- for the resource server trusting exactly one issuer; an opaque token does not
-- participate in that namespace at all, because it resolves by digest to one row
-- that already names the account. There is no subject to collide, so there is no
-- second trust root to reason about. ADR 0047's guest token is the same
-- construction for the same reason.
--
-- ------------------------------------------------------------------ no PII here
--
-- Deliberately no phone number, no hash of one, and no reference to the
-- verification challenge the session came from. The challenge row carries an
-- ADR 0029 encrypted number and is purged on a retention schedule; a foreign key
-- to it would either block that purge or leave a dangling column, and a copy of
-- the destination hash here would put a per-customer correlation key in a table
-- that has no question to answer with it. What links a session to a person is
-- customer_account_id and nothing else.

CREATE TABLE customer.customer_sessions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- The brand the customer signed in at. Kept even under TENANT_SHARED, where
    -- it partitions nothing, because a session is still a sign-in that happened
    -- somewhere and the audit trail is worth more than the column costs.
    brand_id uuid NOT NULL,

    customer_account_id uuid NOT NULL,

    -- The partition the account lives in: null under TENANT_SHARED, the brand
    -- under BRAND_ISOLATED. Copied from the account rather than recomputed at
    -- resolution, and then compared against the partition the tenant's current
    -- policy implies for the brand being addressed. A governed mode change
    -- therefore ends existing sessions instead of silently resolving them into
    -- the other partitioning — which is the cross-brand exposure ADR 0015 is
    -- about, arriving through a token this time.
    identity_partition_brand_id uuid,

    -- SHA-256 of a 256-bit secret, lower-case hex. A bare digest rather than a
    -- password KDF for the reason VerificationGrantSecret records: 2^256 drawn
    -- uniformly has no dictionary, so a work factor would buy nothing and would
    -- be paid on every single request a signed-in customer makes.
    token_hash varchar(64) NOT NULL,

    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,

    -- Set by sign-out and by an operator ending a session. Separate from
    -- expires_at so that "this ended early" is distinguishable from "this ran
    -- its course", which is the difference between an incident and a Tuesday.
    revoked_at timestamptz,

    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_customer_session_window CHECK (expires_at > issued_at),
    -- A malformed digest would be stored happily and then never match, which
    -- presents as "sign-in works and every subsequent request is 401".
    CONSTRAINT ck_customer_session_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    -- Under BRAND_ISOLATED the partition is the brand the session was minted at.
    -- Any other value would be an account from one brand reachable through a
    -- session issued at another.
    CONSTRAINT ck_customer_session_partition CHECK (
        identity_partition_brand_id IS NULL OR identity_partition_brand_id = brand_id
    ),
    CONSTRAINT fk_customer_session_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id)
);

-- Resolution is a single probe on the digest alone, because a caller presents
-- the token and nothing else. The tenant comes back off the row and is then
-- checked against the path, so it cannot be edited into the request.
CREATE UNIQUE INDEX ux_customer_session_token
    ON customer.customer_sessions (token_hash);

-- "End every session this customer has", which is what a lost handset needs.
CREATE INDEX ix_customer_session_account
    ON customer.customer_sessions (tenant_id, customer_account_id)
    WHERE revoked_at IS NULL;

-- The sweeper's index. Rows are deleted rather than kept, because an expired
-- session answers no question worth a row per sign-in per customer forever.
CREATE INDEX ix_customer_session_expiry
    ON customer.customer_sessions (expires_at);

GRANT SELECT, INSERT, UPDATE, DELETE
    ON customer.customer_sessions TO qoida_application;

COMMENT ON TABLE customer.customer_sessions IS
    'ADR 0051. A platform-issued, opaque, database-backed customer session. Holds a SHA-256 of a 256-bit token, never the token, and no personal data of any kind.';
COMMENT ON COLUMN customer.customer_sessions.identity_partition_brand_id IS
    'ADR 0015/0051. The partition the account was resolved in. Compared at every request against the partition the tenant''s current policy implies, so a governed mode change ends sessions instead of re-resolving them.';
COMMENT ON COLUMN customer.customer_sessions.revoked_at IS
    'Sign-out, or an operator ending a session. Distinct from expires_at so an early end is legible as one.';
