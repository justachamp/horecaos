-- A new ADR (referral program): trackable acquisition and a tenant-configurable
-- reward, decided 2026-09-05 — operations §6.6 Referrals.
--
-- ---------------------------------------------------------------------------
-- Why this rides on the loyalty ledger instead of inventing a second one
-- ---------------------------------------------------------------------------
--
-- ADR 0044 recorded referral reward mechanics as an open input and named the
-- two shapes it could resolve to without a schema change: a coded benefit
-- grant, or an ADR 0046 points accrual. The owner's 2026-09-05 decision takes
-- the second, and makes the shape itself a brand's own choice rather than a
-- platform-wide constant: a tenant authors whether both sides are rewarded, or
-- the referrer only, together with the amounts, a per-referrer cap, and how
-- long a redeemed-but-unqualified code stays open.
--
-- Nothing here is a second money-like primitive. `referral.programs` is a
-- policy — versioned, draft-then-activate-then-retire, exactly like
-- `loyalty.accrual_rules` and `loyalty.redemption_policies` beside it — and
-- the reward itself is minted through `loyalty.entries` by
-- `loyalty.api.ReferralGrantPort`, an ADJUSTMENT-shaped credit this schema
-- never writes directly. What lives here is what loyalty does not already
-- model: a code per customer, one redemption per new customer, and the
-- qualifying-event bookkeeping that decides whether and when a reward fires.
--
-- ---------------------------------------------------------------------------
-- The three abuse cases this schema closes structurally
-- ---------------------------------------------------------------------------
--
-- 1. **Self-referral.** `ck_referral_redemption_no_self_referral` refuses a
--    redemption whose referrer and referee are the same account, at the
--    database, not only in the service that creates one.
--
-- 2. **Stacking.** `uq_referral_redemption_referee` is one redemption per
--    referee per brand, ever. A customer who already redeemed a code cannot
--    redeem a second one, and the same row is what answers "did this
--    customer already claim a referral reward" without a second query.
--
-- 3. **Double-crediting a replayed qualifying event.** A redemption leaves
--    `PENDING` exactly once, by a conditional `UPDATE ... WHERE status =
--    'PENDING'` the application issues — the same discipline
--    `loyalty.accounts.balance_minor` uses against two checkouts. A second
--    delivery of the same order-completed fact matches zero rows and mints
--    nothing a second time. `ck_referral_redemption_state_shape` pins the
--    three reachable shapes of the row so `qualifying_order_id` and
--    `rewarded_at` can never disagree with `status` about whether the event
--    already happened.
--
-- ---------------------------------------------------------------------------
-- What is deliberately absent
-- ---------------------------------------------------------------------------
--
-- No `?ref=` link table and no Telegram deep-link/BotFather flow. Those are
-- `marketing.attribution_links` (ADR 0044) and remain that ADR's open
-- checklist item; this schema is the reward half of operations §6.6 only. No
-- scheduled sweep flips a stale `PENDING` row to `EXPIRED` on the clock alone
-- — a redemption is judged against `expires_at` at the moment a qualifying
-- event or a read asks, the same lazy-expiry choice ADR 0046 did not make for
-- lots (which does sweep) but is proportionate here, where an unswept row
-- costs nothing but a stale read.

CREATE SCHEMA referral;

COMMENT ON SCHEMA referral IS
    'A new ADR: referral codes, redemptions, and the reward program a tenant authors. The reward itself is minted through loyalty.entries, never here.';

-- --------------------------------------------------------------- programs

CREATE TABLE referral.programs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    -- BOTH_SIDES: the referrer and the referee are each credited on the
    -- referee's first completed order. REFERRER_ONLY: only the referrer is.
    reward_shape varchar(16) NOT NULL,

    referrer_reward_minor bigint NOT NULL,
    referee_reward_minor bigint NOT NULL DEFAULT 0,
    reward_currency char(3) NOT NULL,

    -- Null is uncapped, the same deliberate-not-absent-default ADR 0046 uses
    -- for max_accrual_minor. Counted over REWARDED redemptions naming this
    -- referrer, for the life of the program row rather than a rolling window.
    max_rewarded_referrals_per_referrer integer,

    -- How long a redeemed code stays open waiting for the referee's first
    -- completed order before it lapses unqualified.
    redemption_window_days integer NOT NULL,

    -- A referral reward's own lot lifetime. Deliberately not borrowed from
    -- loyalty.accrual_rules: a brand can run referrals with no active accrual
    -- rule at all, and coupling the two would leave the reward's expiry
    -- undefined exactly when there is nothing to fall back to.
    reward_lot_lifetime_days integer NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    version integer NOT NULL DEFAULT 1,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_referral_program_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_referral_program_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT uq_referral_program_identity UNIQUE (tenant_id, id),

    CONSTRAINT ck_referral_program_shape CHECK (
        reward_shape IN ('BOTH_SIDES', 'REFERRER_ONLY')),
    CONSTRAINT ck_referral_program_referrer_reward CHECK (referrer_reward_minor > 0),

    -- The shape and the referee amount cannot disagree: REFERRER_ONLY carries
    -- no referee reward at all, and BOTH_SIDES always carries a positive one.
    -- A zero-but-BOTH_SIDES row would silently be REFERRER_ONLY with an extra
    -- redemption row per referee that nobody could explain.
    CONSTRAINT ck_referral_program_referee_reward CHECK (
        (reward_shape = 'REFERRER_ONLY' AND referee_reward_minor = 0)
        OR (reward_shape = 'BOTH_SIDES' AND referee_reward_minor > 0)),

    CONSTRAINT ck_referral_program_currency CHECK (reward_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_referral_program_cap CHECK (
        max_rewarded_referrals_per_referrer IS NULL OR max_rewarded_referrals_per_referrer > 0),
    CONSTRAINT ck_referral_program_window CHECK (redemption_window_days > 0),
    CONSTRAINT ck_referral_program_lot_lifetime CHECK (reward_lot_lifetime_days > 0),
    CONSTRAINT ck_referral_program_status CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_referral_program_valid_window CHECK (
        valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_referral_program_version CHECK (version >= 1)
);

-- One live program per brand. The draft/activate/retire lifecycle below mints
-- this the same way LoyaltyPolicyAuthoringService promotes an accrual rule:
-- activation retires whichever program currently holds this brand, in one
-- transaction, so the resolver this index backs never sees two.
CREATE UNIQUE INDEX ux_referral_program_one_active_per_brand
    ON referral.programs (tenant_id, brand_id)
    WHERE status = 'ACTIVE';

COMMENT ON TABLE referral.programs IS
    'A brand''s authored referral reward: which shape, the amounts, the per-referrer cap, and how long an unqualified redemption stays open. A brand with no ACTIVE row runs no referral program, the same silence ADR 0046 chose for a brand with no active accrual rule.';

COMMENT ON COLUMN referral.programs.reward_shape IS
    'BOTH_SIDES credits the referrer and the referee; REFERRER_ONLY credits only the referrer. The owner''s 2026-09-05 decision is that this is the tenant''s choice, not a platform-wide constant.';

COMMENT ON COLUMN referral.programs.max_rewarded_referrals_per_referrer IS
    'Counted over this program''s own REWARDED redemptions naming the referrer. Null is uncapped, a deliberate choice rather than the absence of one.';

-- ------------------------------------------------------------------- codes

CREATE TABLE referral.codes (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,

    code varchar(16) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_referral_code_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_referral_code_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_referral_code_customer FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),

    -- One code per customer per brand, matching a loyalty account's own scope
    -- key: the same person is a different account at each of a tenant's
    -- brands under BRAND_ISOLATED, and their referral identity follows it.
    CONSTRAINT uq_referral_code_owner UNIQUE (tenant_id, brand_id, customer_account_id),
    CONSTRAINT uq_referral_code_value UNIQUE (tenant_id, code),
    CONSTRAINT uq_referral_code_identity UNIQUE (tenant_id, id),

    CONSTRAINT ck_referral_code_format CHECK (code ~ '^[0-9A-Z]{6,16}$'),
    CONSTRAINT ck_referral_code_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_referral_code_version CHECK (version >= 1)
);

COMMENT ON TABLE referral.codes IS
    'One code per customer per brand, minted the first time they ask for it. Crockford base32 from a CSPRNG (ADR 0044''s coded-grant generator) with I, L, O and U removed, so it cannot be confused with a digit or guessed in sequence.';

-- ------------------------------------------------------------ redemptions

CREATE TABLE referral.redemptions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    code_id uuid NOT NULL,
    program_id uuid NOT NULL,
    program_version integer NOT NULL,

    referrer_customer_account_id uuid NOT NULL,
    referee_customer_account_id uuid NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'PENDING',

    redeemed_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,

    qualifying_order_id uuid,
    rewarded_at timestamptz,

    -- Snapshotted from the program that was ACTIVE at redemption, not re-read
    -- from it at reward time: the customer redeemed under a stated promise,
    -- and a later change to the program's numbers — or its retirement —
    -- before the referee's first order completes must not move what this
    -- redemption pays. REFERRER_ONLY snapshots referee_reward_minor as 0,
    -- matching referral.programs' own shape check.
    referrer_reward_minor bigint NOT NULL,
    referee_reward_minor bigint NOT NULL,

    -- Null until the grant that entry names actually happens. Presence, not a
    -- separate flag, is what a report reads as "this side was paid".
    referrer_entry_id uuid,
    referee_entry_id uuid,

    -- Set when the referrer's own per-referrer cap was already reached at
    -- reward time: the referee side (if the shape pays one) still fires, and
    -- this column is why the referrer's absence is not read as a bug.
    referrer_skip_reason varchar(32),

    idempotency_key varchar(255) NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_referral_redemption_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_referral_redemption_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_referral_redemption_code FOREIGN KEY (tenant_id, code_id)
        REFERENCES referral.codes (tenant_id, id),
    CONSTRAINT fk_referral_redemption_program FOREIGN KEY (tenant_id, program_id)
        REFERENCES referral.programs (tenant_id, id),
    CONSTRAINT fk_referral_redemption_referrer FOREIGN KEY (referrer_customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT fk_referral_redemption_referee FOREIGN KEY (referee_customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),
    CONSTRAINT fk_referral_redemption_order FOREIGN KEY (qualifying_order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    -- loyalty.entries gained uq_loyalty_entry_identity UNIQUE (id, tenant_id) in
    -- V0084 for exactly this: a single-column reference into it lets one
    -- tenant's row point at another tenant's ledger entry.
    CONSTRAINT fk_referral_redemption_referrer_entry FOREIGN KEY (referrer_entry_id, tenant_id)
        REFERENCES loyalty.entries (id, tenant_id),
    CONSTRAINT fk_referral_redemption_referee_entry FOREIGN KEY (referee_entry_id, tenant_id)
        REFERENCES loyalty.entries (id, tenant_id),

    CONSTRAINT uq_referral_redemption_identity UNIQUE (tenant_id, id),

    -- Abuse case: stacking. One redemption per referee per brand, ever — a
    -- customer who already redeemed a code cannot claim a second one, and
    -- this is the row a second attempt is refused against.
    CONSTRAINT uq_referral_redemption_referee UNIQUE (tenant_id, brand_id, referee_customer_account_id),
    CONSTRAINT uq_referral_redemption_idempotency UNIQUE (tenant_id, idempotency_key),

    -- Abuse case: self-referral. Refused at the database, not only by the
    -- service that creates a redemption.
    CONSTRAINT ck_referral_redemption_no_self_referral CHECK (
        referrer_customer_account_id <> referee_customer_account_id),

    CONSTRAINT ck_referral_redemption_status CHECK (
        status IN ('PENDING', 'REWARDED', 'EXPIRED', 'VOIDED')),
    CONSTRAINT ck_referral_redemption_window CHECK (expires_at > redeemed_at),

    -- Abuse case: double-crediting a replayed qualifying event. The three
    -- reachable shapes of this row, stated so status can never disagree with
    -- whether the reward already fired.
    CONSTRAINT ck_referral_redemption_state_shape CHECK (
        (status = 'PENDING' AND qualifying_order_id IS NULL AND rewarded_at IS NULL)
        OR (status = 'REWARDED' AND qualifying_order_id IS NOT NULL AND rewarded_at IS NOT NULL)
        OR (status IN ('EXPIRED', 'VOIDED') AND qualifying_order_id IS NULL AND rewarded_at IS NULL)),

    CONSTRAINT ck_referral_redemption_reward_amounts CHECK (
        referrer_reward_minor > 0 AND referee_reward_minor >= 0),

    CONSTRAINT ck_referral_redemption_version CHECK (version >= 1)
);

CREATE INDEX ix_referral_redemption_referrer
    ON referral.redemptions (tenant_id, brand_id, referrer_customer_account_id);

CREATE INDEX ix_referral_redemption_pending_expiry
    ON referral.redemptions (expires_at)
    WHERE status = 'PENDING';

COMMENT ON TABLE referral.redemptions IS
    'One new customer''s use of one referral code. At most one per referee per brand ever, and the reward fires at most once, on the referee''s first completed order while this row is still PENDING and not past expires_at.';

COMMENT ON COLUMN referral.redemptions.expires_at IS
    'redeemed_at plus the program''s redemption_window_days, snapshotted at redemption so a later change to the program does not retroactively expire or extend an open redemption.';

COMMENT ON COLUMN referral.redemptions.referrer_skip_reason IS
    'Set, alongside a referrer_entry_id that stays null, when the referrer''s per-referrer cap was already reached at reward time. referrer_reward_minor still names the amount the cap refused. The referee''s own reward, if the program shape pays one, is unaffected: the referee did nothing to be denied for.';

-- -------------------------------------------------------------------- grants

GRANT USAGE ON SCHEMA referral TO horecaos_application;

GRANT SELECT, INSERT, UPDATE ON referral.programs TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON referral.codes TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON referral.redemptions TO horecaos_application;
