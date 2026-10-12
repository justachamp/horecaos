package uz.horecaos.platform.catalog.domain;

import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * The one definition of "valid" for a row of the ИКПУ/MXIK reference (ADR 0173).
 *
 * <p>A code is valid on a date {@code d} when {@code valid_from <= d} and ({@code valid_until} is null or
 * {@code d < valid_until}). Withdrawal is a date and not a deletion, so a receipt issued last year under a
 * code that has since been retired is judged on last year's list. Before this existed the picker honoured the
 * window and the validator's lookup ignored it, so a withdrawn code was offered nowhere and reported as known;
 * every reader now asks this.
 *
 * <p>The SQL form exists because the readers filter in the database; a test pins the two forms to each other.
 */
public final class MxikValidity {

    private MxikValidity() {}

    /** The predicate, over the table's own columns and a bound date named {@code on}. */
    public static final String SQL_VALID_ON = "(valid_from <= :on AND (valid_until IS NULL OR :on < valid_until))";

    /** The same predicate against the database's own current date, for a reader with no date of its own. */
    public static final String SQL_VALID_TODAY =
            "(valid_from <= CURRENT_DATE AND (valid_until IS NULL OR CURRENT_DATE < valid_until))";

    public static boolean isValidOn(LocalDate validFrom, @Nullable LocalDate validUntil, LocalDate on) {
        return !validFrom.isAfter(on) && (validUntil == null || on.isBefore(validUntil));
    }
}
