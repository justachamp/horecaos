-- ADR 0046: the loyalty points ledger, and split tender in the payment
-- aggregate.
--
-- ---------------------------------------------------------------------------
-- What is deliberately absent
-- ---------------------------------------------------------------------------
--
-- There is no customer-funded balance here, and none is deferred. No DEPOSIT
-- account type, no CUSTOMER_DEPOSIT payment method, no TOPUP entry type, no
-- WITHDRAWAL entry type, and no kill switch behind which any of them waits. A
-- kill-switched feature still costs its tables, its enum values, its registry
-- row and its test surface, and its first defect is found by whoever enables it
-- two years later. Stored value returns as a new ADR, under a Central Bank
-- authorisation or with an acquirer holding the float, and not as an edit to
-- this file.
--
-- ---------------------------------------------------------------------------
-- Points are not money, and the enforcement is here rather than in prose
-- ---------------------------------------------------------------------------
--
-- Three properties, each with the back door it closes.
--
-- 1. **Not withdrawable.** `loyalty.entries.entry_type` is a closed check
--    constraint naming eight movements, none of which is a payout, and there is
--    no column on the table that could carry a payout destination: a debit
--    references a lot, an order, and a tender on that order, and nothing else.
--    Below, `payments.tenders` refuses a `payment_intent_id` on a
--    balance-settled tender by check constraint, so a redemption cannot reach a
--    provider even by a mistaken insert. The refund cap that stops a
--    points-settled order refunding as cash is application code — a refund reads
--    the settled amount of the tender it is refunding — but the shape of these
--    two tables is what makes that code the only path there is.
--
-- 2. **Not transferable between people.** `loyalty.entries` has one
--    `account_id` and no counterparty column, so a transfer cannot be written as
--    a pair even from psql. A `REDEMPTION` names a tender, whose settlement
--    names an order, whose `customer_account_id` and `brand_id` must equal the
--    account's — checked inside the checkout transaction, over columns that are
--    present here for the check to read. Two offsetting `ADJUSTMENT` rows remain
--    a transfer with extra steps; the adjustment path takes one account and one
--    signed amount, has no paired form, and every row carries a reason, an actor
--    and an approval reference, which makes the manoeuvre visible and countable
--    rather than impossible. That is the correct treatment for something an
--    operator legitimately does during a support call.
--
-- 3. **No cash value outside the platform.** Redemption's only sink is a tender
--    on an order in the same tenant and brand. `EXPIRY` and `FORFEITURE` destroy
--    value with no compensating movement, which is only meaningful because there
--    is no cash-out to prefer over expiring.
--
-- ---------------------------------------------------------------------------
-- Movements, never a mutable balance
-- ---------------------------------------------------------------------------
--
-- `loyalty.entries` is append-only, and append-only is a grant rather than a
-- convention: the application role holds SELECT and INSERT and nothing else, and
-- a trigger says the same thing to a migration, a psql session, and a well-meant
-- hotfix that the grant does not reach. This is the discipline ADR 0021 applies
-- to usage metering, for the same three reasons — a balance updated in place
-- cannot be audited, cannot be recomputed after a bug, and cannot be defended to
-- a customer who disputes it.
--
-- `loyalty.accounts.balance_minor` is therefore a projection and never the
-- authority. It is exactly `SUM(entries.amount_minor)` for the account, and
-- every entry carries `balance_after_minor`, so any past balance is a stored row
-- rather than a replay and the two are reconcilable by a single query.
--
-- ---------------------------------------------------------------------------
-- The hold is a debit
-- ---------------------------------------------------------------------------
--
-- Two carts in two tabs must not both spend the same 40 000, so a redemption
-- reserves. This schema makes the reservation an actual debit — `REDEMPTION`
-- entries written when the tender reaches RESERVED — rather than a counter of
-- held points sitting beside an untouched balance. Three things follow, and all
-- three are why it is done this way:
--
-- * concurrency is decided by PostgreSQL, in one conditional UPDATE that
--   refuses to take a balance below zero, rather than by a read-then-write that
--   two tabs both pass;
-- * `balance_minor` stays the sum of the ledger with no second number to keep
--   in step with it; and
-- * the two ways a hold can die get distinct, auditable entry types. `RELEASE`
--   returns points whose tender never settled — the checkout failed, or the
--   reservation aged out. `REVERSAL` returns points whose tender did settle and
--   is now being refunded. They are the same arithmetic and completely different
--   events, and a report that cannot tell them apart cannot tell a broken
--   payment path from a generous refund policy.
--
-- ---------------------------------------------------------------------------
-- Money
-- ---------------------------------------------------------------------------
--
-- Every `*_minor` column here is whole som, matching ADR 0018 and
-- `payments.domain.SomAmount`. Points are som one to one, because the
-- redemption has to land in a provider's integer discount field on a fiscal
-- receipt: any other unit needs a conversion at the fiscal boundary plus a
-- versioned rate joined to every historical receipt to explain one. `currency`
-- on an account names the denomination of the discount it produces. It is not a
-- claim on funds.

CREATE SCHEMA loyalty;

COMMENT ON SCHEMA loyalty IS
    'ADR 0046 loyalty points: an append-only movement ledger, expiring lots, and redemption holds. Points are not money and nothing here holds customer funds.';

-- ------------------------------------------------------------------ accounts

CREATE TABLE loyalty.accounts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    customer_account_id uuid NOT NULL,

    currency char(3) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',

    balance_minor bigint NOT NULL DEFAULT 0,
    reserved_minor bigint NOT NULL DEFAULT 0,

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_loyalty_account_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_loyalty_account_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_loyalty_account_customer FOREIGN KEY (customer_account_id, tenant_id)
        REFERENCES customer.customer_accounts (id, tenant_id),

    -- The cross-brand rule, as a key rather than as a sentence. One account per
    -- brand per customer means there is no row a tenant-wide pool could live in,
    -- so redeeming at brand B points earned at brand A is not something an
    -- application bug can do by accident. A brand's outstanding points are the
    -- liability of the legal entity that will honour them, and ADR 0038
    -- establishes that one tenant routinely contains several taxpayers.
    CONSTRAINT uq_loyalty_account_scope UNIQUE (tenant_id, brand_id, customer_account_id),
    CONSTRAINT uq_loyalty_account_identity UNIQUE (tenant_id, id),

    CONSTRAINT ck_loyalty_account_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_loyalty_account_status CHECK (
        status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),

    -- The rule the whole liability report depends on. A shortfall — a refunded
    -- order whose accrual has already been spent — is a WRITE_OFF against the
    -- tenant, visible on that report, and never a negative balance the customer
    -- discovers on their next order.
    CONSTRAINT ck_loyalty_account_balance_not_negative CHECK (balance_minor >= 0),
    CONSTRAINT ck_loyalty_account_reserved_not_negative CHECK (reserved_minor >= 0),
    CONSTRAINT ck_loyalty_account_version CHECK (version >= 1)
);

COMMENT ON TABLE loyalty.accounts IS
    'ADR 0046 one points account per customer per brand. There is no account_type column: there is one kind of account, and a column holding one value is an invitation to add a second one without an ADR.';

COMMENT ON COLUMN loyalty.accounts.balance_minor IS
    'A projection of the ledger, never the authority. Equals SUM(loyalty.entries.amount_minor) for this account at all times, and is recomputable from it after any bug.';

COMMENT ON COLUMN loyalty.accounts.reserved_minor IS
    'Points already debited by a tender that has reserved but not settled. Not part of the balance — the debit has happened — so this is what a RELEASE would return, and it is the in-flight figure a liability report separates out.';

COMMENT ON COLUMN loyalty.accounts.currency IS
    'The denomination of the discount a redemption produces, one point to one som. Not a currency the platform holds on the customer''s behalf, because it holds none.';

COMMENT ON COLUMN loyalty.accounts.status IS
    'CLOSED is terminal and is reached with a zero balance: closure forfeits what is left with a FORFEITURE entry and never pays it out, which is the moment the not-money constraints exist for.';

-- ------------------------------------------------------------ payment methods
--
-- ADR 0038 owns this registry; ADR 0046 needs it to exist because a tender names
-- a method row rather than carrying a tender-type enum of its own. Two registries
-- of settlement mechanisms disagree, and the damaging disagreement is over
-- `responsibility`, which decides who issues the fiscal receipt.
--
-- ADR 0038 records that ADR 0046 contributes two rows to it. That is stale: the
-- second was CUSTOMER_DEPOSIT and it is withdrawn. One row is contributed,
-- LOYALTY_POINTS, and it is seeded per tenant by the application rather than
-- here, because the registry is tenant-scoped.

CREATE TABLE payments.payment_methods (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    code varchar(32) NOT NULL,
    display_name varchar(120) NOT NULL,

    responsibility varchar(16) NOT NULL,

    -- The flag, not the name, is what every rule keys on: reservation ordering,
    -- accrual net of the redeemed portion, the money-tender invariant, and
    -- cash_due_minor all test this column. A second balance-backed method added
    -- later inherits all four without a code change.
    settles_from_balance boolean NOT NULL DEFAULT false,

    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_payment_method_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT uq_payment_method_code UNIQUE (tenant_id, code),
    CONSTRAINT uq_payment_method_identity UNIQUE (tenant_id, id),

    -- Referenced by payments.tenders so that a tender's snapshot of the flag
    -- cannot disagree with the registry row it names.
    CONSTRAINT uq_payment_method_balance_flag UNIQUE (tenant_id, id, settles_from_balance),

    CONSTRAINT ck_payment_method_code CHECK (code ~ '^[A-Z][A-Z0-9_]{0,31}$'),
    CONSTRAINT ck_payment_method_responsibility CHECK (
        responsibility IN ('PARTNER', 'TERMINAL', 'MARKETPLACE', 'OPERATOR')),
    CONSTRAINT ck_payment_method_status CHECK (status IN ('ACTIVE', 'DISABLED')),

    -- A balance-settled method is not a fiscal path. It settles nothing
    -- externally, so it can never be the party that issues a receipt, and
    -- PARTNER or MARKETPLACE on such a row would send a redemption looking for a
    -- provider payment that does not exist.
    CONSTRAINT ck_payment_method_balance_is_not_a_fiscal_path CHECK (
        NOT settles_from_balance OR responsibility = 'OPERATOR'),
    CONSTRAINT ck_payment_method_version CHECK (version >= 1)
);

COMMENT ON TABLE payments.payment_methods IS
    'ADR 0038 tenant payment-method registry: the settlement mechanism, not the customer-visible label. Localised labels and icons are ADR 0036 presentation resolved per channel over these rows.';

COMMENT ON COLUMN payments.payment_methods.settles_from_balance IS
    'True for a method that discharges an amount from a HorecaOS-held balance rather than moving external money. Today only LOYALTY_POINTS. It is not a claim that the platform holds funds: a points balance is a promise, not a deposit.';

COMMENT ON COLUMN payments.payment_methods.responsibility IS
    'ADR 0038: who issues the fiscal receipt. Validated when a method is activated, and the reason a tender names a registry row instead of a second enum.';

-- --------------------------------------------------------- order settlements
--
-- ADR 0013 proposes one payment intent per order with a single requested amount.
-- That is right for a card payment and wrong for an order paid 12 000 from
-- points and 82 000 by card. A settlement sits between the order and the
-- intents; each tender names a method, carries its own amount and lifecycle, and
-- the tender amounts sum exactly to the order total.

CREATE TABLE payments.order_settlements (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    order_id uuid NOT NULL,

    currency char(3) NOT NULL,
    total_due_minor bigint NOT NULL,
    settled_minor bigint NOT NULL DEFAULT 0,

    status varchar(24) NOT NULL DEFAULT 'DRAFT',

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_order_settlement_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_order_settlement_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT uq_order_settlement_order UNIQUE (tenant_id, order_id),
    CONSTRAINT uq_order_settlement_identity UNIQUE (tenant_id, id),

    CONSTRAINT ck_order_settlement_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_order_settlement_total CHECK (total_due_minor > 0),
    CONSTRAINT ck_order_settlement_settled CHECK (
        settled_minor >= 0 AND settled_minor <= total_due_minor),
    CONSTRAINT ck_order_settlement_status CHECK (status IN (
        'DRAFT', 'PLANNED', 'PARTIALLY_SETTLED', 'SETTLED',
        'PARTIALLY_REVERSED', 'REVERSED', 'FAILED')),
    CONSTRAINT ck_order_settlement_version CHECK (version >= 1)
);

COMMENT ON TABLE payments.order_settlements IS
    'ADR 0046 the ordered set of tenders that discharges one order. Corrects ADR 0013''s one-intent-per-order cardinality, which cannot express points plus cash.';

COMMENT ON COLUMN payments.order_settlements.status IS
    'PARTIALLY_SETTLED never rests across a checkout boundary: if any tender fails, every reservation is released and ADR 0019 takes the PAYMENT_FAILED path. Half-paid is not a state this platform has.';

CREATE TABLE payments.tenders (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    settlement_id uuid NOT NULL,

    -- The order the tenders settle in. The balance tender reserves first and
    -- external tenders settle last, because releasing a points reservation is a
    -- local write while reversing a captured card payment is a provider refund
    -- with an uncertainty window. The other order produces a failed points debit
    -- after a successful capture, which is the case where the customer has paid
    -- and the order has not.
    sequence integer NOT NULL,

    payment_method_id uuid NOT NULL,

    -- Snapshotted from the registry, and tied back to it by composite foreign
    -- key so the snapshot cannot drift from the row it names. It is on the
    -- tender because the invariants below are check constraints and a check
    -- constraint cannot join.
    settles_from_balance boolean NOT NULL,

    amount_minor bigint NOT NULL,
    currency char(3) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PLANNED',

    payment_intent_id uuid,
    loyalty_reservation_id uuid,

    settled_at timestamptz,

    idempotency_key varchar(255) NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_tender_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_tender_settlement FOREIGN KEY (tenant_id, settlement_id)
        REFERENCES payments.order_settlements (tenant_id, id),
    CONSTRAINT fk_tender_method FOREIGN KEY (tenant_id, payment_method_id, settles_from_balance)
        REFERENCES payments.payment_methods (tenant_id, id, settles_from_balance),
    CONSTRAINT fk_tender_intent FOREIGN KEY (tenant_id, payment_intent_id)
        REFERENCES payments.payment_intents (tenant_id, id),

    CONSTRAINT uq_tender_identity UNIQUE (tenant_id, id),
    CONSTRAINT uq_tender_sequence UNIQUE (settlement_id, sequence),
    CONSTRAINT uq_tender_idempotency UNIQUE (tenant_id, idempotency_key),

    CONSTRAINT ck_tender_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_tender_sequence CHECK (sequence >= 1),

    -- A zero tender is a tender nobody meant to plan, and it would let a
    -- settlement satisfy the money-tender invariant with a money tender that
    -- moves nothing.
    CONSTRAINT ck_tender_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_tender_status CHECK (status IN (
        'PLANNED', 'RESERVED', 'SETTLED', 'RELEASED', 'REVERSED', 'FAILED')),

    -- Not withdrawable, stated where an insert has to pass it. A balance tender
    -- has no payment intent, in any status, so platform-held value can never
    -- become an outbound provider call; and a money tender has no loyalty
    -- reservation, so a card capture can never be booked against a points hold.
    CONSTRAINT ck_tender_balance_has_no_intent CHECK (
        NOT settles_from_balance OR payment_intent_id IS NULL),
    CONSTRAINT ck_tender_money_has_no_reservation CHECK (
        settles_from_balance OR loyalty_reservation_id IS NULL),

    CONSTRAINT ck_tender_settled_at_pairs_with_status CHECK (
        (settled_at IS NULL) = (status <> 'SETTLED' AND status <> 'REVERSED')),
    CONSTRAINT ck_tender_version CHECK (version >= 1)
);

CREATE INDEX ix_tender_settlement
    ON payments.tenders (tenant_id, settlement_id, sequence);

-- At most one balance tender per settlement. A partial unique index rather than
-- a check, because the constraint is over the set of a settlement's tenders and
-- no row can see its siblings.
CREATE UNIQUE INDEX ux_tender_one_balance_per_settlement
    ON payments.tenders (settlement_id)
    WHERE settles_from_balance;

COMMENT ON TABLE payments.tenders IS
    'ADR 0046 one tender of a split settlement. The sum of a settlement''s tender amounts equals the order total, and at least one of them has settles_from_balance false, both enforced inside the ADR 0019 checkout transaction.';

COMMENT ON COLUMN payments.tenders.settles_from_balance IS
    'Snapshotted from payments.payment_methods and tied to it by composite foreign key. Rules key on this rather than on the method code, so a balance method added later inherits them.';

COMMENT ON COLUMN payments.tenders.loyalty_reservation_id IS
    'The loyalty.reservations row this tender holds. Set only on a balance tender; a money tender naming one is refused by check constraint.';

-- ------------------------------------------------------------------- entries

CREATE TABLE loyalty.entries (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,

    -- One account and no counterparty. This is the shape of
    -- non-transferability: there is no column into which the other side of a
    -- transfer could be written, so a transfer cannot be recorded as a pair even
    -- by someone holding a psql session.
    account_id uuid NOT NULL,

    entry_type varchar(16) NOT NULL,

    -- Signed. A movement, never a balance.
    amount_minor bigint NOT NULL,
    balance_after_minor bigint NOT NULL,

    lot_id uuid,
    order_id uuid,
    tender_id uuid,

    rule_id uuid,
    rule_version integer,

    reason_code varchar(48) NOT NULL,
    actor varchar(255) NOT NULL,
    approval_id uuid,

    idempotency_key varchar(255) NOT NULL,

    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_loyalty_entry_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_loyalty_entry_account FOREIGN KEY (tenant_id, account_id)
        REFERENCES loyalty.accounts (tenant_id, id),
    CONSTRAINT fk_loyalty_entry_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT fk_loyalty_entry_tender FOREIGN KEY (tenant_id, tender_id)
        REFERENCES payments.tenders (tenant_id, id),

    CONSTRAINT uq_loyalty_entry_idempotency UNIQUE (tenant_id, account_id, idempotency_key),

    -- The closed set, at the database. TOPUP and WITHDRAWAL are rejected here
    -- and not merely absent from an application enum, because the whole force of
    -- "points are not money" is that no code path, no migration and no hotfix
    -- can introduce a movement that funds or drains an account.
    CONSTRAINT ck_loyalty_entry_type CHECK (entry_type IN (
        'ACCRUAL', 'REDEMPTION', 'RELEASE', 'EXPIRY',
        'FORFEITURE', 'ADJUSTMENT', 'REVERSAL', 'WRITE_OFF')),

    CONSTRAINT ck_loyalty_entry_amount_not_zero CHECK (amount_minor <> 0),

    -- Direction is a property of the type, so a REDEMPTION that credits is
    -- refused rather than becoming a balance nobody can explain. ADJUSTMENT is
    -- the only type free to go either way, which is exactly why it carries a
    -- reason, an actor and an approval reference.
    CONSTRAINT ck_loyalty_entry_direction CHECK (
        CASE entry_type
            WHEN 'ACCRUAL' THEN amount_minor > 0
            WHEN 'RELEASE' THEN amount_minor > 0
            WHEN 'REVERSAL' THEN amount_minor > 0
            WHEN 'REDEMPTION' THEN amount_minor < 0
            WHEN 'EXPIRY' THEN amount_minor < 0
            WHEN 'FORFEITURE' THEN amount_minor < 0
            WHEN 'WRITE_OFF' THEN amount_minor < 0
            ELSE true
        END),

    CONSTRAINT ck_loyalty_entry_balance_not_negative CHECK (balance_after_minor >= 0),

    -- A redemption and everything that unwinds one name the order and the tender
    -- they moved against. That pair is what a dispute is answered from, and it is
    -- what the cross-brand and cross-customer checks read.
    CONSTRAINT ck_loyalty_entry_tender_movement_names_an_order CHECK (
        entry_type NOT IN ('REDEMPTION', 'RELEASE', 'REVERSAL')
            OR (order_id IS NOT NULL AND tender_id IS NOT NULL AND lot_id IS NOT NULL)),

    -- Stated as pair completeness rather than as two nullable columns that
    -- happen to agree. (a IS NULL AND b IS NULL) OR (...) leaves the mixed case
    -- reachable through three-valued logic; this does not.
    CONSTRAINT ck_loyalty_entry_rule_snapshot_pair CHECK (
        (rule_id IS NULL) = (rule_version IS NULL)),

    -- Accrual snapshots the rule that produced it, so changing tomorrow's rate
    -- never restates yesterday's balance and a customer asking why they earned
    -- 3 000 gets the rule they earned it under.
    CONSTRAINT ck_loyalty_entry_accrual_snapshots_its_rule CHECK (
        entry_type <> 'ACCRUAL' OR rule_id IS NOT NULL)
);

CREATE INDEX ix_loyalty_entry_account
    ON loyalty.entries (tenant_id, account_id, occurred_at DESC);

CREATE INDEX ix_loyalty_entry_tender
    ON loyalty.entries (tenant_id, tender_id)
    WHERE tender_id IS NOT NULL;

COMMENT ON TABLE loyalty.entries IS
    'ADR 0046 append-only points movements. The evidence a disputed balance is defended with: never updated, never deleted, and sufficient on its own to reconstruct any balance at any past instant.';

COMMENT ON COLUMN loyalty.entries.amount_minor IS
    'A signed movement in whole som, never a balance. Positive credits the customer, negative debits them, and the sign permitted is fixed per entry type.';

COMMENT ON COLUMN loyalty.entries.balance_after_minor IS
    'The account balance immediately after this movement. Stored so that a past balance is a row rather than a replay, and so a reconciliation can find the first entry where the running total and the stored total part company.';

COMMENT ON COLUMN loyalty.entries.entry_type IS
    'RELEASE returns points whose tender never settled; REVERSAL returns points whose tender settled and is being refunded. Same arithmetic, different events, and a report that conflates them cannot tell a broken payment path from a refund policy.';

COMMENT ON COLUMN loyalty.entries.occurred_at IS
    'When the movement happened, as against recorded_at. A deferred accrual occurs when its earn delay elapses and is recorded when the sweep runs, and the liability report counts it in the period it occurred in.';

COMMENT ON COLUMN loyalty.entries.actor IS
    'Who or what caused the movement. An opaque staff subject or a system component name under ADR 0029 — never a customer contact point, and never a name.';

-- Append-only, said to callers the GRANT does not reach: a migration, a psql
-- session, and the hotfix that needs to "just correct one row". A convention
-- survives until the first such hotfix; a missing UPDATE grant does not.
CREATE OR REPLACE FUNCTION loyalty.reject_ledger_rewrite() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION
        'The loyalty ledger is append-only; correct it with a new entry (ADR 0046)';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_loyalty_entries_append_only
    BEFORE UPDATE OR DELETE ON loyalty.entries
    FOR EACH ROW EXECUTE FUNCTION loyalty.reject_ledger_rewrite();

-- ---------------------------------------------------------------------- lots

CREATE TABLE loyalty.lots (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    account_id uuid NOT NULL,

    source_entry_id uuid NOT NULL,

    granted_minor bigint NOT NULL,
    remaining_minor bigint NOT NULL,

    earns_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'PENDING',

    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_loyalty_lot_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_loyalty_lot_account FOREIGN KEY (tenant_id, account_id)
        REFERENCES loyalty.accounts (tenant_id, id),
    CONSTRAINT fk_loyalty_lot_source FOREIGN KEY (source_entry_id)
        REFERENCES loyalty.entries (id),
    CONSTRAINT uq_loyalty_lot_identity UNIQUE (tenant_id, id),
    CONSTRAINT uq_loyalty_lot_source UNIQUE (source_entry_id),

    CONSTRAINT ck_loyalty_lot_granted CHECK (granted_minor > 0),
    CONSTRAINT ck_loyalty_lot_remaining CHECK (
        remaining_minor >= 0 AND remaining_minor <= granted_minor),
    CONSTRAINT ck_loyalty_lot_window CHECK (expires_at > earns_at),
    CONSTRAINT ck_loyalty_lot_status CHECK (status IN (
        'PENDING', 'ACTIVE', 'CONSUMED', 'EXPIRED', 'FORFEITED')),
    CONSTRAINT ck_loyalty_lot_version CHECK (version >= 1)
);

CREATE INDEX ix_loyalty_lot_consumption_order
    ON loyalty.lots (tenant_id, account_id, expires_at, earns_at)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_loyalty_lot_expiry_sweep
    ON loyalty.lots (expires_at)
    WHERE status IN ('PENDING', 'ACTIVE');

COMMENT ON TABLE loyalty.lots IS
    'ADR 0046 the expiry unit. A balance that expires as one block on one date is rejected: the customer who earned steadily would lose everything at once, and would be right to complain.';

COMMENT ON COLUMN loyalty.lots.earns_at IS
    'When the lot becomes spendable — order completion plus the configured earn delay. Crediting at checkout instead would mean a cancelled order requires clawing back points already spent.';

COMMENT ON COLUMN loyalty.lots.expires_at IS
    'Fixed at grant and never moved. Reversing a redemption restores the consumed lots at this original value: points three days from expiry when spent are three days from expiry when returned, and resetting the clock is a quiet giveaway that compounds on every refund.';

COMMENT ON COLUMN loyalty.lots.status IS
    'PENDING until earns_at, then ACTIVE. Consumption is oldest-expiry-first and then oldest-granted-first, over ACTIVE lots only.';

-- Added after the lots table because the two reference each other: a lot names
-- the ACCRUAL entry that granted it, and every later movement names the lot it
-- moved. An ACCRUAL's own lot_id is therefore null — it is the entry the lot is
-- created from and cannot name one — and every other lot-bearing entry points at
-- a row that already exists.
ALTER TABLE loyalty.entries
    ADD CONSTRAINT fk_loyalty_entry_lot FOREIGN KEY (tenant_id, lot_id)
        REFERENCES loyalty.lots (tenant_id, id);

-- -------------------------------------------------------------- reservations

CREATE TABLE loyalty.reservations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    account_id uuid NOT NULL,

    order_id uuid NOT NULL,
    tender_id uuid NOT NULL,

    amount_minor bigint NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'HELD',
    expires_at timestamptz NOT NULL,

    idempotency_key varchar(255) NOT NULL,
    version integer NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_loyalty_reservation_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_loyalty_reservation_account FOREIGN KEY (tenant_id, account_id)
        REFERENCES loyalty.accounts (tenant_id, id),
    CONSTRAINT fk_loyalty_reservation_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),
    CONSTRAINT fk_loyalty_reservation_tender FOREIGN KEY (tenant_id, tender_id)
        REFERENCES payments.tenders (tenant_id, id),

    CONSTRAINT uq_loyalty_reservation_identity UNIQUE (tenant_id, id),
    CONSTRAINT uq_loyalty_reservation_tender UNIQUE (tenant_id, tender_id),
    CONSTRAINT uq_loyalty_reservation_idempotency UNIQUE (tenant_id, idempotency_key),

    CONSTRAINT ck_loyalty_reservation_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_loyalty_reservation_status CHECK (status IN (
        'HELD', 'SETTLED', 'RELEASED', 'REVERSED')),
    CONSTRAINT ck_loyalty_reservation_version CHECK (version >= 1)
);

CREATE INDEX ix_loyalty_reservation_expiry
    ON loyalty.reservations (expires_at)
    WHERE status = 'HELD';

COMMENT ON TABLE loyalty.reservations IS
    'ADR 0046 one redemption hold, one per tender. Redemption reserves rather than counts, for the reason ADR 0018 reserves coupon usage: two carts in two tabs must not both spend the same 40 000.';

COMMENT ON COLUMN loyalty.reservations.expires_at IS
    'When an abandoned hold is swept back with RELEASE entries. A hold that outlives its checkout is points the customer cannot see and cannot spend.';

CREATE TABLE loyalty.reservation_lots (
    reservation_id uuid NOT NULL,
    lot_id uuid NOT NULL,
    tenant_id uuid NOT NULL,
    amount_minor bigint NOT NULL,

    CONSTRAINT pk_loyalty_reservation_lot PRIMARY KEY (reservation_id, lot_id),
    CONSTRAINT fk_loyalty_reservation_lot_reservation FOREIGN KEY (tenant_id, reservation_id)
        REFERENCES loyalty.reservations (tenant_id, id),
    CONSTRAINT fk_loyalty_reservation_lot_lot FOREIGN KEY (tenant_id, lot_id)
        REFERENCES loyalty.lots (tenant_id, id),
    CONSTRAINT ck_loyalty_reservation_lot_amount CHECK (amount_minor > 0)
);

COMMENT ON TABLE loyalty.reservation_lots IS
    'Which lots a hold took and how much from each, decided once at reservation. Recomputing the split at release or refund time would return points to whichever lots looked oldest later, which is how an expiry date quietly moves.';

-- --------------------------------------------------------- rules and policies

CREATE TABLE loyalty.accrual_rules (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    scope_type varchar(16) NOT NULL DEFAULT 'BRAND',
    scope_id uuid,

    rate_basis_points integer NOT NULL,
    max_accrual_minor bigint,

    earn_delay_hours integer NOT NULL,
    lot_lifetime_days integer NOT NULL,
    expiry_warning_days integer NOT NULL,

    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    version integer NOT NULL DEFAULT 1,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_loyalty_accrual_rule_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_loyalty_accrual_rule_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT uq_loyalty_accrual_rule_identity UNIQUE (tenant_id, id),

    CONSTRAINT ck_loyalty_accrual_rule_scope CHECK (
        scope_type IN ('BRAND', 'LOCATION', 'CHANNEL')),
    -- A BRAND rule has no narrower target; a LOCATION or CHANNEL rule names one.
    CONSTRAINT ck_loyalty_accrual_rule_scope_pair CHECK (
        (scope_type = 'BRAND') = (scope_id IS NULL)),
    CONSTRAINT ck_loyalty_accrual_rule_rate CHECK (
        rate_basis_points >= 0 AND rate_basis_points <= 10000),
    CONSTRAINT ck_loyalty_accrual_rule_max CHECK (
        max_accrual_minor IS NULL OR max_accrual_minor > 0),
    CONSTRAINT ck_loyalty_accrual_rule_delay CHECK (earn_delay_hours >= 0),
    CONSTRAINT ck_loyalty_accrual_rule_lifetime CHECK (lot_lifetime_days > 0),
    CONSTRAINT ck_loyalty_accrual_rule_warning CHECK (
        expiry_warning_days >= 0 AND expiry_warning_days < lot_lifetime_days),
    CONSTRAINT ck_loyalty_accrual_rule_status CHECK (
        status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_loyalty_accrual_rule_window CHECK (
        valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_loyalty_accrual_rule_version CHECK (version >= 1)
);

COMMENT ON TABLE loyalty.accrual_rules IS
    'ADR 0046 versioned accrual rules. Snapshotted onto the entry that used them, so raising tomorrow''s rate never restates yesterday''s balance.';

COMMENT ON COLUMN loyalty.accrual_rules.rate_basis_points IS
    'Basis points of the money-settled, fee-excluded order value. The proposed default is 300 (3%), pending product and finance confirmation as an ADR 0030 policy value.';

COMMENT ON COLUMN loyalty.accrual_rules.max_accrual_minor IS
    'Caps the liability one unusually large corporate order can create, which is the case a percentage rate handles badly. Null means uncapped, which is a deliberate tenant choice rather than the absence of one.';

COMMENT ON COLUMN loyalty.accrual_rules.earn_delay_hours IS
    'Hours after the order reaches COMPLETED before the lot becomes spendable. The window in which a delivery complaint is actually raised is hours; beyond that a clawback is rare enough to be a WRITE_OFF.';

CREATE TABLE loyalty.redemption_policies (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,

    max_share_basis_points integer NOT NULL,
    min_order_minor bigint NOT NULL DEFAULT 0,
    excludes_delivery_fee boolean NOT NULL DEFAULT true,

    -- Channel codes, not identifiers: an order snapshots its channel code, and a
    -- policy that named channel rows would have to be rewritten when a tenant
    -- registers its third aggregator.
    allowed_channels varchar(32)[] NOT NULL DEFAULT '{}',

    status varchar(16) NOT NULL DEFAULT 'DRAFT',
    version integer NOT NULL DEFAULT 1,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_loyalty_redemption_policy_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_loyalty_redemption_policy_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT uq_loyalty_redemption_policy_identity UNIQUE (tenant_id, id),

    -- Points may never cover the whole order. The cap is a product number that
    -- can be raised to 90% without argument; 100% is refused here because an
    -- order with no money tender has no fiscal path at all — no Click payment to
    -- hang submit_items on, no Payme receipt, and on a cash order a courier who
    -- collects nothing while handing over food.
    CONSTRAINT ck_loyalty_redemption_policy_share CHECK (
        max_share_basis_points > 0 AND max_share_basis_points <= 9000),
    CONSTRAINT ck_loyalty_redemption_policy_min_order CHECK (min_order_minor >= 0),
    CONSTRAINT ck_loyalty_redemption_policy_status CHECK (
        status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    CONSTRAINT ck_loyalty_redemption_policy_window CHECK (
        valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_loyalty_redemption_policy_version CHECK (version >= 1)
);

COMMENT ON TABLE loyalty.redemption_policies IS
    'ADR 0046 what a redemption may cover. The proposed default is 5 000 bp of the money-eligible value with the delivery fee excluded and a 50 000 som minimum order, pending product and finance confirmation.';

COMMENT ON COLUMN loyalty.redemption_policies.max_share_basis_points IS
    'Capped at 9 000 by check constraint, not by convention. The share is a product number; the requirement that some money changes hands is not.';

COMMENT ON COLUMN loyalty.redemption_policies.excludes_delivery_fee IS
    'When true the fee line carries no share of the allocated discount, so its own fiscal classification and VAT are untouched by a redemption.';

-- -------------------------------------------------------------------- grants

GRANT USAGE ON SCHEMA loyalty TO horecaos_application;

GRANT SELECT, INSERT, UPDATE ON loyalty.accounts TO horecaos_application;
GRANT SELECT, INSERT ON loyalty.entries TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON loyalty.lots TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON loyalty.reservations TO horecaos_application;
GRANT SELECT, INSERT ON loyalty.reservation_lots TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON loyalty.accrual_rules TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON loyalty.redemption_policies TO horecaos_application;

GRANT SELECT, INSERT, UPDATE ON payments.payment_methods TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON payments.order_settlements TO horecaos_application;
GRANT SELECT, INSERT, UPDATE ON payments.tenders TO horecaos_application;

-- loyalty.entries receives SELECT and INSERT and nothing else, and that is the
-- decision this whole schema rests on. An UPDATE grant is all it takes for a
-- disputed figure to become an unrecorded one, and the ledger's entire value is
-- that nothing can rewrite it — not the application, not a support fix, and not
-- a future migration that thinks it knows better.
--
-- Nothing in this schema receives DELETE. An expired lot, a released hold, a
-- retired rule and a closed account are all answers to questions somebody will
-- ask later, and a forfeited balance is the one a customer is most likely to ask
-- about.
