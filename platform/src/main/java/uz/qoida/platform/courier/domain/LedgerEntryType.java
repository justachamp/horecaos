package uz.qoida.platform.courier.domain;

/**
 * What one ledger entry records (ADR 0042).
 *
 * <p>The sign is fixed per type and enforced by a database constraint, because
 * one ledger holds both what the tenant owes the courier and what cash of the
 * tenant's the courier is holding. There is no {@code COMMISSION}: the prepaid
 * float was rejected, so no arrangement exists in which a courier owes the
 * tenant for order flow, and an unused negative entry type in an append-only
 * ledger is how such an arrangement appears without a decision.
 */
public enum LedgerEntryType {

    DELIVERY_EARNING(Sign.POSITIVE),
    SHIFT_EARNING(Sign.POSITIVE),
    BONUS(Sign.POSITIVE),
    PENALTY(Sign.NEGATIVE),

    /** Cash the courier took from a customer: the tenant's money, in his bag. */
    CASH_COLLECTED(Sign.NEGATIVE),

    CASH_HANDED_OVER(Sign.POSITIVE),

    /** Never absorbed into another figure. Always its own entry, with a reason. */
    CASH_VARIANCE(Sign.EITHER),

    PAYOUT(Sign.NEGATIVE),
    PRIOR_PERIOD_ADJUSTMENT(Sign.EITHER),
    CORRECTION(Sign.EITHER);

    public enum Sign { POSITIVE, NEGATIVE, EITHER }

    private final Sign sign;

    LedgerEntryType(Sign sign) {
        this.sign = sign;
    }

    public Sign sign() {
        return sign;
    }

    /** Whether the amount agrees with what this entry type can mean. */
    public boolean accepts(long amountMinor) {
        return switch (sign) {
            case POSITIVE -> amountMinor > 0;
            case NEGATIVE -> amountMinor < 0;
            case EITHER -> amountMinor != 0;
        };
    }

    /** The components a statement's gross earnings figure is summed from. */
    public boolean isGrossEarning() {
        return this == DELIVERY_EARNING || this == SHIFT_EARNING;
    }

    public boolean isAdjustment() {
        return this == BONUS || this == PENALTY
                || this == PRIOR_PERIOD_ADJUSTMENT || this == CORRECTION;
    }

    public boolean isCash() {
        return this == CASH_COLLECTED || this == CASH_HANDED_OVER || this == CASH_VARIANCE;
    }
}
