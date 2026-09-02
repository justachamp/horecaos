-- Operations Customers section (frontend-information-architecture.md §5.1-5.2):
-- two P-tier fields the existing customer schema (V0017) has no room for.
--
-- date_of_birth_encrypted closes "profile + DOB" (§5.2). It follows the same
-- envelope-encryption discipline as every other personal value on this schema
-- (ADR 0029) rather than a plain `date` column: a birth date is exactly the
-- kind of fact ADR 0029 exists to protect, and there is no query in this
-- release that needs to filter or sort on it — the birthday-window segment
-- (§5.3, tier 2) is out of scope here and would need its own decision about
-- whether a segment builder may query a decrypted date at all.
--
-- customer.blacklist_entries closes "blacklist/suppression with reason,
-- actor, expiry and a defined enforcement point" (§5.2). It is deliberately
-- its own table rather than a new customer_accounts.status value: the
-- existing SUSPENDED status has no reason, no actor, and no expiry, and
-- reusing it would still need a second place to record all three.

ALTER TABLE customer.customer_accounts
    ADD COLUMN date_of_birth_encrypted text;

COMMENT ON COLUMN customer.customer_accounts.date_of_birth_encrypted IS
    'ADR 0029. Envelope-encrypted like every other personal value on this schema. Nullable: most accounts (imported, operator-created, most self-registered) never carry one.';

CREATE TABLE customer.blacklist_entries (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,

    -- Encrypted like ordering.order_outcomes.note_encrypted (V0029), not like
    -- ordering.orders.kitchen_note (deliberately left in clear). The
    -- distinction that migration draws is exactly the one here: a kitchen
    -- note is a routine ticket instruction, but an operator's own account of
    -- why somebody was blacklisted routinely names a specific incident and
    -- can carry a third party's name, so it gets the customer-facing-words
    -- level of protection rather than the operational one.
    reason_encrypted text NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    actor_type varchar(16) NOT NULL,
    actor_id varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    -- Null means indefinite. Deliberately never swept into a stored status
    -- change: CLAUDE.md's own rule for this codebase is that an expiry is
    -- checked against the clock on every read rather than trusted from a
    -- column a background job might fail to advance, the same reasoning
    -- session.ts's accessToken() doc comment gives for the storefront.
    -- CustomerBlacklistService is the one place that evaluates it.
    expires_at timestamptz,

    lifted_at timestamptz,
    lifted_by_actor_type varchar(16),
    lifted_by_actor_id varchar(255),
    lift_reason_encrypted text,

    CONSTRAINT ck_blacklist_status CHECK (status IN ('ACTIVE', 'LIFTED')),
    CONSTRAINT ck_blacklist_actor_type CHECK (actor_type IN ('USER', 'SERVICE')),
    CONSTRAINT ck_blacklist_lifted_by_actor_type CHECK (
        lifted_by_actor_type IS NULL OR lifted_by_actor_type IN ('USER', 'SERVICE')
    ),
    -- A lifted entry always names who lifted it and when; an active one never
    -- does yet. The pair travels together the same way order_outcomes' own
    -- accepted_at/accepted_by pair does (V0029).
    CONSTRAINT ck_blacklist_lifted_fields CHECK (
        (status = 'ACTIVE' AND lifted_at IS NULL AND lifted_by_actor_type IS NULL
            AND lifted_by_actor_id IS NULL AND lift_reason_encrypted IS NULL)
        OR (status = 'LIFTED' AND lifted_at IS NOT NULL AND lifted_by_actor_type IS NOT NULL
            AND lifted_by_actor_id IS NOT NULL)
    ),
    CONSTRAINT ck_blacklist_expiry_after_creation CHECK (expires_at IS NULL OR expires_at > created_at),
    CONSTRAINT fk_blacklist_account FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id)
);

-- The blacklist tab's own history read: newest first, one account.
CREATE INDEX ix_blacklist_entries_account
    ON customer.blacklist_entries (tenant_id, customer_account_id, created_at DESC);

-- The enforcement point's own read (CustomerBlacklistService#isCurrentlyBlacklisted,
-- called from CustomerIdentityService#resolve): status is filtered here, expiry is
-- filtered by the query itself against the clock, per the comment on expires_at above.
CREATE INDEX ix_blacklist_entries_active
    ON customer.blacklist_entries (tenant_id, customer_account_id)
    WHERE status = 'ACTIVE';

GRANT SELECT, INSERT, UPDATE ON customer.blacklist_entries TO horecaos_application;
