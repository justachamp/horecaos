package uz.qoida.platform.loyalty.domain;

/**
 * The closed set of movements a points ledger admits (ADR 0046).
 *
 * <p>None of them names a payout destination, and that is the point of the set
 * being closed. There is no {@code TOPUP} and no {@code WITHDRAWAL} here, nor in
 * V0042's check constraint, so a balance cannot be funded with money or drained
 * into it by any code path, migration, or hotfix. The database repeats the list
 * rather than trusting this enum, because the property has to survive an
 * application nobody has written yet.
 */
public enum EntryType {

    /** Earned on an order, deferred past the refund window, granting a lot. */
    ACCRUAL(Direction.CREDIT),

    /** Spent against a tender. The debit happens when the tender reserves. */
    REDEMPTION(Direction.DEBIT),

    /**
     * Returns points whose tender never settled — the checkout failed, or the
     * hold aged out. Distinct from {@link #REVERSAL}, which returns points whose
     * tender did settle. Same arithmetic, different events: a report that
     * conflates them cannot tell a broken payment path from a refund policy.
     */
    RELEASE(Direction.CREDIT),

    /** A lot reaching its expiry with points left on it. */
    EXPIRY(Direction.DEBIT),

    /** Account closure and ADR 0029 erasure. Destroys value; never pays out. */
    FORFEITURE(Direction.DEBIT),

    /**
     * The one signed movement an operator can author. It takes one account and
     * one amount and has no paired form, so two offsetting adjustments are two
     * separate acts, each with a reason, an actor, and an approval above the
     * configured threshold — visible and countable rather than impossible.
     */
    ADJUSTMENT(Direction.EITHER),

    /** Returns points whose settled tender is being refunded. */
    REVERSAL(Direction.CREDIT),

    /**
     * Nothing writes this, and V0079 refuses it.
     *
     * <p>It was the shortfall of a clawback the balance could not absorb, and it
     * was not a movement: the shortfall is by definition the part the balance
     * could not cover, so there is nothing left for it to move and the balance
     * floor would refuse it if there were. Every row of this ledger is a balance
     * movement — V0042 requires {@code balance_minor} to equal
     * {@code SUM(amount_minor)} for the account at all times — so a WRITE_OFF of
     * 2 000 against a balance of zero simply made that false by 2 000, for ever.
     *
     * <p>The fact it was trying to record is real and now has a table shaped for
     * it: {@code loyalty.clawbacks} holds what a refunded order cost the brand
     * that absorbs it. This constant survives only so that a row written before
     * the migration can still be read back and repaired.
     */
    WRITE_OFF(Direction.DEBIT);

    /** Which signs an entry of this type may carry. */
    public enum Direction {
        CREDIT,
        DEBIT,
        EITHER
    }

    private final Direction direction;

    EntryType(Direction direction) {
        this.direction = direction;
    }

    public Direction direction() {
        return direction;
    }

    /** Mirrors V0042's {@code ck_loyalty_entry_direction}, so a bad sign fails early. */
    public boolean permits(long amountMinor) {
        if (amountMinor == 0) {
            return false;
        }
        return switch (direction) {
            case CREDIT -> amountMinor > 0;
            case DEBIT -> amountMinor < 0;
            case EITHER -> true;
        };
    }
}
