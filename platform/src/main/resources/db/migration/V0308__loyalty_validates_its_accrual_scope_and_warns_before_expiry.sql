-- T18, operations §6.3 Loyalty: two gaps ADR 0046 left open.
--
-- First, `accrual_rules.scope_id` has never had a foreign key, because it is
-- polymorphic -- a LOCATION rule's id is a row in tenant.locations, a CHANNEL
-- rule's is a row in tenant.sales_channels, and a plain FOREIGN KEY cannot
-- point at one column conditionally between two tables. The gap is real: a
-- typo'd or stale scope_id persists silently, and
-- `JdbcLoyaltyStore.accrualRule`'s own narrowest-first resolver simply never
-- matches it, so every order at that location or channel falls back to the
-- brand's rule with no error anywhere. A BEFORE INSERT OR UPDATE trigger is
-- the standard way to enforce a conditional reference PostgreSQL's own
-- constraint syntax cannot express -- it runs the same EXISTS check a real
-- FK's index probe would run, and it raises the same class of error
-- (foreign_key_violation) an ORM already knows how to surface. The
-- application-level check in LoyaltyPolicyAuthoringService is the one an
-- operator actually sees as a clean 422; this trigger is the backstop for
-- every other writer, present and future.
--
-- Second, `loyalty.lots` has never carried the accrual rule's own
-- expiry_warning_days, so no sweep could tell "this lot is inside its
-- warning window" from "this lot is not" without re-resolving a rule that
-- may since have changed. Every other policy value this module snapshots
-- onto a lot or an entry at grant time (rate, cap, earn delay, lifetime) for
-- exactly this reason -- LoyaltyAccrualService's own doc: raising tomorrow's
-- rate must never restate yesterday's balance. expiry_warning_days is the
-- one the accrual rule table has always carried and the lot never received.

-- ------------------------------------------------------- accrual rule scope

CREATE OR REPLACE FUNCTION loyalty.validate_accrual_rule_scope() RETURNS trigger AS $$
BEGIN
    IF NEW.scope_type = 'LOCATION' THEN
        IF NOT EXISTS (
            SELECT 1 FROM tenant.locations
             WHERE tenant_id = NEW.tenant_id AND id = NEW.scope_id
        ) THEN
            RAISE EXCEPTION
                'loyalty.accrual_rules: no location % for tenant %; a LOCATION rule must scope to a real location',
                NEW.scope_id, NEW.tenant_id
                USING ERRCODE = 'foreign_key_violation';
        END IF;
    ELSIF NEW.scope_type = 'CHANNEL' THEN
        IF NOT EXISTS (
            SELECT 1 FROM tenant.sales_channels
             WHERE tenant_id = NEW.tenant_id AND id = NEW.scope_id
        ) THEN
            RAISE EXCEPTION
                'loyalty.accrual_rules: no sales channel % for tenant %; a CHANNEL rule must scope to a real channel',
                NEW.scope_id, NEW.tenant_id
                USING ERRCODE = 'foreign_key_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION loyalty.validate_accrual_rule_scope() IS
    'The foreign key scope_id cannot declare directly, because it names a row in one of two different tables depending on scope_type. Raises foreign_key_violation, the same SQLSTATE a real FK would.';

CREATE TRIGGER trg_accrual_rule_scope_exists
    BEFORE INSERT OR UPDATE OF scope_type, scope_id ON loyalty.accrual_rules
    FOR EACH ROW
    EXECUTE FUNCTION loyalty.validate_accrual_rule_scope();

-- --------------------------------------------------------- lot expiry warning

ALTER TABLE loyalty.lots
    ADD COLUMN expiry_warning_days integer NOT NULL DEFAULT 0,
    ADD COLUMN expiry_warning_sent_at timestamptz;

ALTER TABLE loyalty.lots
    ADD CONSTRAINT ck_loyalty_lot_expiry_warning_days CHECK (expiry_warning_days >= 0);

COMMENT ON COLUMN loyalty.lots.expiry_warning_days IS
    'Snapshotted from the accrual rule''s own expiryWarningDays at grant time (LoyaltyAccrualService), the same reasoning rate_basis_points and lot_lifetime_days are already snapshotted under. Zero for a lot granted before this column existed, or under a rule authored with no warning -- LoyaltyMaintenanceService.warnExpiringLots skips a zero rather than treating it as "warn immediately".';
COMMENT ON COLUMN loyalty.lots.expiry_warning_sent_at IS
    'Set once the warning sweep has told LoyaltyExpiryWarningPort about this lot. The sweep''s own idempotency key -- a repeat scan before the next tick finds nothing to do, the same shape ApprovalDeadlineWarningSweeper''s own doc describes for its intent-level key, done here as a status column because a lot has no second table a unique index could dedupe against.';

CREATE INDEX ix_loyalty_lot_expiry_warning_due
    ON loyalty.lots (expires_at)
    WHERE status = 'ACTIVE' AND expiry_warning_sent_at IS NULL;

COMMENT ON INDEX loyalty.ix_loyalty_lot_expiry_warning_due IS
    'LoyaltyMaintenanceService.warnExpiringLots'' own worklist -- same partial-index shape as ix_loyalty_lot_expiry_sweep (V0042), scoped to lots the warning sweep has not yet reached.';
