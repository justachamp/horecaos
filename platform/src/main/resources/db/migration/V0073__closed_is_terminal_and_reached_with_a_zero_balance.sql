-- ADR 0046: make the sentence on loyalty.accounts.status true.
--
-- The column comment written by V0042 says:
--
--     CLOSED is terminal and is reached with a zero balance.
--
-- Nothing enforced either half of it, and that is how a defect survived a
-- schema, a service and a test suite. `restoreLot` had no status predicate and a
-- CASE that could only emit EXPIRED, PENDING or ACTIVE, so a FORFEITED lot came
-- back ACTIVE; `creditBalance` asked nothing about the account, so the balance
-- of an account closed by ADR 0029 erasure went back up. The reproduction is the
-- ordinary cancellation path: forfeit the account while a tender holds part of
-- it, then let OrderStateService fail the settlement, and the closed account is
-- carrying 12 000 spendable points belonging to a customer who no longer exists.
--
-- The application half of that is fixed in PointsRedemptionService — the points
-- come back with their RELEASE entry and go straight out again with a
-- FORFEITURE naming the lot. This file is the half that outlives the fix. A
-- comment is a claim; a constraint is the claim being true for every code path,
-- every migration, and every psql session that has not been written yet.
--
-- ---------------------------------------------------------------------------
-- Why two triggers and no CHECK constraint
-- ---------------------------------------------------------------------------
--
-- A CHECK is evaluated per statement, and the correct repair is necessarily two
-- statements: the points are credited back with their entry, and then destroyed
-- again with a second one. The balance is legitimately non-zero between those,
-- inside one transaction, and a CHECK would refuse the first of the pair and so
-- forbid the very movement that keeps the ledger honest. The requirement is
-- about the state a transaction *commits*, which is a deferred constraint
-- trigger and nothing else.
--
-- The trigger re-reads the row rather than trusting NEW. A deferred AFTER ROW
-- trigger carries the tuple as of the statement that queued it, not as of
-- commit, so a function that read NEW.balance_minor would fire on the credit and
-- never see the forfeiture that followed it.
--
-- Terminality is the other half and is not deferrable: CLOSED -> anything is
-- wrong the moment it is written, and there is no legitimate transaction that
-- passes through it. Nothing in the application reopens an account —
-- `openAccount` is ON CONFLICT DO NOTHING and every other writer moves towards
-- CLOSED — so this refuses a path that does not exist yet, which is when it is
-- cheapest to refuse.
--
-- Neither trigger destroys or rewrites anything. They refuse, which leaves the
-- caller's transaction to roll back with its own reason attached.

CREATE OR REPLACE FUNCTION loyalty.assert_closed_account_holds_nothing() RETURNS trigger AS $$
DECLARE
    settled_status  varchar(16);
    settled_balance bigint;
BEGIN
    -- As of commit, not as of the statement that queued this. See the note
    -- above: the credit and the forfeiture that answers it are two statements.
    SELECT status, balance_minor
      INTO settled_status, settled_balance
      FROM loyalty.accounts
     WHERE id = NEW.id;

    IF NOT FOUND THEN
        RETURN NULL;
    END IF;

    IF settled_status = 'CLOSED' AND settled_balance <> 0 THEN
        RAISE EXCEPTION
            'loyalty.accounts %: CLOSED is terminal and is reached with a zero balance, but this transaction leaves % on it (ADR 0046)',
            NEW.id, settled_balance
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_loyalty_account_closed_holds_nothing
    AFTER INSERT OR UPDATE ON loyalty.accounts
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION loyalty.assert_closed_account_holds_nothing();

CREATE OR REPLACE FUNCTION loyalty.assert_closed_account_stays_closed() RETURNS trigger AS $$
BEGIN
    IF OLD.status = 'CLOSED' AND NEW.status <> 'CLOSED' THEN
        RAISE EXCEPTION
            'loyalty.accounts %: CLOSED is terminal; a closed account is not reopened, it is a new account (ADR 0046)',
            OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_loyalty_account_closed_is_terminal
    BEFORE UPDATE ON loyalty.accounts
    FOR EACH ROW EXECUTE FUNCTION loyalty.assert_closed_account_stays_closed();

COMMENT ON COLUMN loyalty.accounts.status IS
    'CLOSED is terminal and is reached with a zero balance: closure forfeits what is left with a FORFEITURE entry and never pays it out, which is the moment the not-money constraints exist for. Both halves are enforced by trigger (V0073) rather than asserted by this comment.';

-- No new table and therefore no new GRANT. Trigger functions run with the
-- privileges of whoever ran the statement, and both of these only read and
-- raise, so horecaos_application needs nothing it does not already hold. EXECUTE is
-- granted to PUBLIC by default on a new function; it is restated here so that a
-- later REVOKE ... FROM PUBLIC on this schema cannot silently disarm the two
-- statements this file exists to make.
GRANT EXECUTE ON FUNCTION loyalty.assert_closed_account_holds_nothing() TO horecaos_application;
GRANT EXECUTE ON FUNCTION loyalty.assert_closed_account_stays_closed() TO horecaos_application;
