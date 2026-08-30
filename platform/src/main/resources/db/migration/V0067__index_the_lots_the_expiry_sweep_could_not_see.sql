-- ADR 0046: let the expiry sweep reach a lot that already says it is expired.
--
-- ---------------------------------------------------------------------------
-- What went wrong
-- ---------------------------------------------------------------------------
--
-- Returning points to a lot whose expiry had already passed left the lot
-- EXPIRED with a positive remaining_minor, and credited the same value to
-- loyalty.accounts.balance_minor. The customer could see the points and could
-- never spend them: JdbcLoyaltyStore.availableLots requires
-- `status = 'ACTIVE' AND expires_at > now`, so every redemption planned against
-- them was refused for INSUFFICIENT_BALANCE. Nor could they ever be destroyed,
-- because the expiry sweep's own read selected `status IN ('PENDING','ACTIVE')`
-- and therefore could not see the row it needed to write an EXPIRY entry for.
-- The tenant's reported liability carried the value permanently.
--
-- `balance_minor = SUM(lots.remaining_minor)` held throughout, which is why
-- every existing reconciliation passed. The invariant that broke is
-- `balance_minor = spendable + what will eventually expire with an entry`.
--
-- The return path now closes such a lot on the spot, in the same transaction,
-- with the EXPIRY entry that explains it. This index is for the rows written
-- before it did: JdbcLoyaltyStore.expiredLots grew a second arm that selects
-- past-expiry lots holding value whatever their status, so the hourly sweep
-- repairs them.
--
-- ---------------------------------------------------------------------------
-- Why an index and not a constraint
-- ---------------------------------------------------------------------------
--
-- A CHECK saying `remaining_minor = 0 OR status IN ('PENDING','ACTIVE')` is the
-- honest statement of the invariant, and it cannot be added here: any row the
-- defect already produced would fail validation at migration time and take the
-- deployment with it, and NOT VALID would leave a constraint nobody ever
-- validates. The sweep repairs the rows first; the constraint is worth a later
-- ADR once the estate is known clean.
--
-- ---------------------------------------------------------------------------
-- Why the predicate is spelled out to the letter
-- ---------------------------------------------------------------------------
--
-- ix_loyalty_lot_expiry_sweep is partial on `status IN ('PENDING','ACTIVE')`, so
-- widening the sweep's predicate with an OR would have dropped it to a
-- sequential scan of every lot in the estate, once an hour, for ever. The sweep
-- instead reads two arms and this index serves the second. PostgreSQL will only
-- use a partial index when it can prove the index predicate follows from the
-- query's, and its prover does not reason about one NOT IN list implying
-- another -- so the predicate below is character-for-character the one
-- JdbcLoyaltyStore.expiredLots issues, and must stay that way.
--
-- It indexes a set that should be empty. That is the point: the repair arm
-- costs an index probe against nothing on an estate with no damaged rows.
--
-- FORFEITED is excluded from both. Closure and ADR 0029 erasure destroy a lot
-- with a FORFEITURE entry of their own, and an expiry sweep that re-labelled one
-- EXPIRED would rewrite that fact. Value left on a forfeited lot is what
-- JdbcLoyaltyStore.unbackedValueMinor refuses to let a return commit onto.
--
-- No new table, and therefore no new GRANT: loyalty.lots already carries the
-- application role's SELECT, INSERT and UPDATE from V0042.

CREATE INDEX ix_loyalty_lot_expiry_repair
    ON loyalty.lots (expires_at)
    WHERE remaining_minor > 0
      AND status NOT IN ('PENDING', 'ACTIVE', 'FORFEITED');

COMMENT ON INDEX loyalty.ix_loyalty_lot_expiry_repair IS
    'ADR 0046 the repair arm of the expiry sweep. A lot in a terminal status still holding value is points a customer can see and can never spend; this index is what lets the hourly sweep find one and write the EXPIRY entry that closes it. The set it indexes should be empty.';
