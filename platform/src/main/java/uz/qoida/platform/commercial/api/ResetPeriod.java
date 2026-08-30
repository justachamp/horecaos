package uz.qoida.platform.commercial.api;

/**
 * The window a limit is measured over (ADR 0021).
 *
 * <p>The distinction that matters is {@link #NONE} against the rest. A standing
 * limit counts what exists now — locations, users, published products — and its
 * ledger is a running sum of signed movements that never restarts. A periodic
 * allowance counts what happened inside a window and starts again at the
 * boundary. Storing both as "a number and a limit" without saying which is
 * which is how a monthly order allowance ends up cumulative and a location count
 * ends up resetting on the first of the month.
 */
public enum ResetPeriod {

    /** A standing limit. One period, {@code LIFETIME}, that never closes. */
    NONE,

    /** Resets at local midnight in the tenant's timezone. */
    DAILY,

    /** Resets at the first local instant of each calendar month. */
    MONTHLY,

    /**
     * Resets with the subscription's own billing period, which is not the
     * calendar month for a tenant that started on the fourteenth.
     */
    BILLING_PERIOD
}
