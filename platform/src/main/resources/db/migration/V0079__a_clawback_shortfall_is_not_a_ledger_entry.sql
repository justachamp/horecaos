-- ADR 0046: what a clawback the balance cannot cover actually is.
--
-- ---------------------------------------------------------------------------
-- The defect
-- ---------------------------------------------------------------------------
--
-- `LoyaltyAdjustmentService.clawBack` absorbed a shortfall — a refunded order
-- whose accrual has already been spent — by writing a WRITE_OFF entry of
-- -2 000 on the customer's account and moving nothing. V0042 says of
-- `loyalty.accounts.balance_minor`:
--
--     A projection of the ledger, never the authority. Equals
--     SUM(loyalty.entries.amount_minor) for this account at all times.
--
-- A WRITE_OFF that moves no balance makes that sentence false by exactly the
-- amount written off, permanently, on a live customer account. Seeded 1 000 and
-- clawed back 3 000, the account committed with a balance of 0 against a ledger
-- summing to -2 000.
--
-- ---------------------------------------------------------------------------
-- Why the answer is not "make it move something"
-- ---------------------------------------------------------------------------
--
-- There is nothing left for it to move. The recoverable part has already come
-- off the balance with its own ADJUSTMENT entry; the shortfall is by definition
-- the part the balance could not cover, so the balance is at zero when the
-- write-off is reached. Moving it would mean a negative balance, which
-- `ck_loyalty_account_balance_not_negative` refuses, and which V0042's own
-- comment on that constraint names as the thing a write-off exists to avoid.
--
-- So the shortfall is not a movement of the customer's points. It is a fact
-- about the tenant: value the tenant accrued, the customer spent, and the
-- refunded order means nobody is going to pay for. That is a liability line with
-- a brand's name against it, and it belongs in a table shaped like one rather
-- than in a ledger whose every row is required to be a balance movement.
--
-- ADR 0046 says two things that cannot both be true, and this file picks one.
-- It requires `balance_minor` to be the sum of the entries, and it lists
-- WRITE_OFF among the eight movements the ledger admits and says a shortfall is
-- one. There is no WRITE_OFF that satisfies the first, so the entry type is the
-- half that goes. What the ADR decided — the shortfall is charged to the tenant
-- and visible on the liability report, never to the customer as a negative
-- balance — is unchanged and is what `loyalty.clawbacks` is for. The record's
-- own text still names WRITE_OFF as an entry type and needs an amendment or a
-- superseding record; nothing here edits it, because that is not a migration's
-- decision to make.
--
-- ---------------------------------------------------------------------------
-- One row per clawback, not one row per write-off
-- ---------------------------------------------------------------------------
--
-- The row records the whole outcome — requested, recovered, written off — and
-- not only the written-off part, for two reasons.
--
-- First, idempotency. The caller `clawBack`'s own javadoc describes is "a
-- machine following a refunded order", which is the shape that gets redelivered.
-- The unique key on (tenant_id, order_id) makes the insert the gate: the second
-- delivery inserts nothing, moves nothing, and reads its answer back from the
-- row the first one wrote. Recomputing the answer from the live balance would
-- report the whole 3 000 as written off on the second pass, having written off
-- 2 000 on the first, because the first pass took the balance to zero.
--
-- Second, the report. `recovered_minor + written_off_minor = requested_minor` is
-- a check constraint, so a row cannot claim to have absorbed more or less than
-- the clawback asked for, and finance can read what a refunded order actually
-- cost the brand without joining back to the ledger to work out the other half.
--
-- Per brand, and never pooled into a tenant figure, for the reason V0042 gives
-- for `loyalty.accounts`: a brand's points are the liability of the legal entity
-- that will honour them, and ADR 0038 establishes that one tenant routinely
-- contains several taxpayers.
--
-- SELECT and INSERT only, like `loyalty.entries`. A write-off that can be
-- amended is a write-off nobody can defend; a correction is another row.

CREATE TABLE loyalty.clawbacks (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    brand_id uuid NOT NULL,
    account_id uuid NOT NULL,

    -- The refunded order the accrual came from. Not nullable: a clawback with no
    -- order behind it is an operator's adjustment, which has its own path, its
    -- own reason code and its own approval threshold.
    order_id uuid NOT NULL,

    requested_minor bigint NOT NULL,
    recovered_minor bigint NOT NULL,
    written_off_minor bigint NOT NULL,

    reason_code varchar(48) NOT NULL,
    actor varchar(255) NOT NULL,

    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT fk_loyalty_clawback_tenant FOREIGN KEY (tenant_id)
        REFERENCES tenant.tenants (id),
    CONSTRAINT fk_loyalty_clawback_brand FOREIGN KEY (tenant_id, brand_id)
        REFERENCES tenant.brands (tenant_id, id),
    CONSTRAINT fk_loyalty_clawback_account FOREIGN KEY (tenant_id, account_id)
        REFERENCES loyalty.accounts (tenant_id, id),
    CONSTRAINT fk_loyalty_clawback_order FOREIGN KEY (order_id, tenant_id)
        REFERENCES ordering.orders (id, tenant_id),

    -- The gate. One refunded order claws its accrual back once, and a
    -- redelivery is refused by the index rather than by a boolean somebody
    -- remembered to read.
    CONSTRAINT uq_loyalty_clawback_order UNIQUE (tenant_id, order_id),

    CONSTRAINT ck_loyalty_clawback_requested CHECK (requested_minor > 0),
    CONSTRAINT ck_loyalty_clawback_recovered CHECK (recovered_minor >= 0),
    CONSTRAINT ck_loyalty_clawback_written_off CHECK (written_off_minor >= 0),

    -- The whole of the clawback is accounted for by exactly one of the two
    -- halves. A row that balanced to something other than what was asked for
    -- would be a liability figure nobody could tie to an order.
    CONSTRAINT ck_loyalty_clawback_halves_sum CHECK (
        recovered_minor + written_off_minor = requested_minor)
);

-- The liability report's own predicate. A clawback the balance covered costs the
-- brand nothing and is not a line on it.
CREATE INDEX ix_loyalty_clawback_write_offs
    ON loyalty.clawbacks (tenant_id, brand_id, occurred_at)
    WHERE written_off_minor > 0;

COMMENT ON TABLE loyalty.clawbacks IS
    'ADR 0046 one refunded order''s accrual clawback: what was asked for, what the balance covered, and what the brand absorbed. The written-off part is a tenant liability and deliberately not a loyalty.entries row, because it moves no balance and every entry must.';

COMMENT ON COLUMN loyalty.clawbacks.written_off_minor IS
    'Value the platform accrued, the customer spent, and the refunded order means nobody will pay for. Charged to the brand''s legal entity, never to the customer as a negative balance.';

COMMENT ON COLUMN loyalty.clawbacks.recovered_minor IS
    'The part taken back off the balance, which has its own ADJUSTMENT entry in loyalty.entries under key CLAWBACK:<order_id>. This column is the summary; that entry is the movement.';

COMMENT ON CONSTRAINT uq_loyalty_clawback_order ON loyalty.clawbacks IS
    'Idempotency by key rather than by luck. The insert is the gate a redelivered clawback fails at, before anything moves.';

GRANT SELECT, INSERT ON loyalty.clawbacks TO qoida_application;

-- ---------------------------------------------------------------------------
-- And the ledger stops admitting the shape
-- ---------------------------------------------------------------------------
--
-- `ck_loyalty_entry_type` still names WRITE_OFF, because V0042 is applied and
-- append-only. This refuses it on the way in instead, which is the same
-- statement one migration later: there is no WRITE_OFF a balance could move
-- for, so an application, a migration, or a psql session that writes one is
-- writing an entry that makes `balance_minor = SUM(amount_minor)` false.
--
-- NOT VALID deliberately. It refuses every new row without taking the table scan
-- and the ACCESS EXCLUSIVE lock that validation needs on a ledger that only
-- grows, and any legacy WRITE_OFF row is drift to be repaired with a compensating
-- movement rather than a reason to fail this migration. `clawBack` has no caller
-- in the application today, so there should be none.
ALTER TABLE loyalty.entries
    ADD CONSTRAINT ck_loyalty_entry_no_write_off CHECK (entry_type <> 'WRITE_OFF') NOT VALID;

COMMENT ON CONSTRAINT ck_loyalty_entry_no_write_off ON loyalty.entries IS
    'A clawback shortfall moves no balance and so is not a movement. It is recorded in loyalty.clawbacks, against the brand that absorbs it.';
