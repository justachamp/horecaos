package uz.horecaos.platform.customers.application;

/**
 * Why one row of a generic customer CSV import was not imported (row {@code
 * X.13}/{@code 5.1b}).
 *
 * <p>A fixed, short vocabulary rather than a free-text message — the same
 * reason {@code SendPulseImportRejectReason} gives for its own: an operator
 * reads this in the ResultSummary and decides whether to fix the file and
 * re-run, and a code is worth more than a sentence that differs on every row.
 * Never customer data — every value here describes what the row's shape or
 * the platform's own state prevented, never what the row said about a person.
 */
public enum CustomerCsvImportRejectReason {

    /** No recognised phone column had a value at all. Phone is this import's only required field. */
    MISSING_PHONE,

    /** A phone column was present but did not parse as a phone number. */
    MALFORMED_PHONE,

    /**
     * The row's phone number is already held by more than one customer
     * account. ADR 0015 never auto-merges on a shared contact, and an import
     * is not the place to start.
     */
    AMBIGUOUS_PHONE_MATCH
}
