package uz.horecaos.platform.reporting.domain;

/**
 * 7.8b: how a flagged {@code tenant.public_holidays} date affects the
 * same-weekday sample {@code demand-history} and the forecast model both draw
 * on.
 *
 * <p>This is deliberately not ADR 0043's own "holiday factor derived from
 * prior occurrences of the same named holiday, bounded by a floor and a
 * ceiling" — that needs its own calendar and {@code calendar_version} and
 * stays unbuilt. This is the smaller, honest alternative: a flagged date
 * either counts fully, is left out, or counts for less, and every sample date
 * still traces back to a real date a manager can look up, the same rule
 * {@code demand-history} already enforces for the sample as a whole.
 */
public enum HolidayMode {

    /** Every qualifying date counts fully. The default — matches every caller before 7.8b existed. */
    INCLUDE,

    /** A flagged date is dropped from the sample entirely; an older, non-holiday occurrence takes its place. */
    EXCLUDE,

    /** A flagged date stays in the sample but counts at {@code HolidayAwareness.HOLIDAY_WEIGHT}, never full weight and never zero. */
    WEIGHT
}
