package uz.horecaos.platform.ordering.domain;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The shape of a marking code (ADR 0161): normalisation and the GS1 structure check, as pure functions.
 *
 * <p>The same normalisation runs in the browser (instant feedback at the scanner) and here (the
 * authority), and both are held to one vector file -- {@code src/test/resources/marking/
 * normalisation-vectors.json} and its byte-identical copy in the console, which {@code
 * tools/checks/repo_hygiene.py} refuses to let drift. In order:
 *
 * <ol>
 *   <li>strip a leading AIM symbology identifier ({@code ]d2} or {@code ]d1});
 *   <li>strip trailing CR, LF, TAB and spaces, and leading spaces;
 *   <li>refuse unless every character is U+0020 to U+007E or the GS control character (U+001D) and the
 *       length is 19 to 300.
 * </ol>
 *
 * <p>The result is the <em>canonical</em> string: the one that is hashed, stored and sent. Nothing
 * cryptographic is verified and nothing here keeps or logs what it is given; a refusal says only that the
 * code was malformed, never what it said.
 *
 * <p>The structure check encodes an assumption the repository cannot verify (it holds no specification of
 * the Asl Belgisi code): the GS1 element-string shape {@code 01 + GTIN-14 + 21 + serial}, optionally
 * followed by a group separator and further elements carried verbatim. That is why it sits behind the
 * policy key {@code fiscal.marking.structure_check} -- a wrong assumption is a configuration change.
 */
public final class MarkingCodes {

    /** The GS1 group separator (FNC1 as a scanner sends it): part of a code, never a terminator. */
    public static final char GROUP_SEPARATOR = '\u001d';

    public static final int MIN_LENGTH = 19;
    public static final int MAX_LENGTH = 300;

    /**
     * Stored when the structure check is {@code NONE} and the code carries no recognisable GTIN prefix:
     * the table's GTIN column is a mandatory fourteen digits, and an unreadable GTIN is recorded as
     * unchecked ({@code gtin_checked = false}) rather than guessed.
     */
    public static final String UNKNOWN_GTIN = "00000000000000";

    private static final int MAX_SERIAL_LENGTH = 20;

    private MarkingCodes() {}

    /** The canonical string, or empty when the input is not a marking code at all. */
    public static Optional<String> normalise(@Nullable String input) {
        if (input == null) {
            return Optional.empty();
        }
        String value = input;
        if (value.startsWith("]d2") || value.startsWith("]d1")) {
            value = value.substring(3);
        }
        int end = value.length();
        while (end > 0 && isTerminator(value.charAt(end - 1))) {
            end--;
        }
        int start = 0;
        while (start < end && value.charAt(start) == ' ') {
            start++;
        }
        value = value.substring(start, end);
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean printable = character >= 0x20 && character <= 0x7e;
            if (!printable && character != GROUP_SEPARATOR) {
                return Optional.empty();
            }
        }
        return Optional.of(value);
    }

    private static boolean isTerminator(char character) {
        return character == '\r' || character == '\n' || character == '\t' || character == ' ';
    }

    /**
     * What the structure check found.
     *
     * @param gtin the fourteen digits after {@code 01}, when the shape is valid; null otherwise
     */
    public record Structure(boolean valid, @Nullable String gtin) {

        static Structure invalid() {
            return new Structure(false, null);
        }
    }

    /**
     * {@code 01} + a GTIN-14 with a valid GS1 mod-10 check digit + {@code 21} + a serial of one to twenty
     * characters (up to the first GS or the end), then optionally GS and further elements, carried
     * verbatim and never interpreted.
     */
    public static Structure gs1Structure(String canonical) {
        if (!canonical.startsWith("01") || canonical.length() < 2 + 14 + 2 + 1) {
            return Structure.invalid();
        }
        String gtin = canonical.substring(2, 16);
        if (!isDigits(gtin) || !hasValidCheckDigit(gtin)) {
            return Structure.invalid();
        }
        if (!canonical.startsWith("21", 16)) {
            return Structure.invalid();
        }
        int serialStart = 18;
        int separator = canonical.indexOf(GROUP_SEPARATOR, serialStart);
        int serialLength = (separator == -1 ? canonical.length() : separator) - serialStart;
        if (serialLength < 1 || serialLength > MAX_SERIAL_LENGTH) {
            return Structure.invalid();
        }
        return new Structure(true, gtin);
    }

    /**
     * The GTIN a code claims, under the given policy.
     *
     * <p>{@code GS1} insists on the full structure and returns empty for a code that fails it. {@code NONE}
     * checks only what {@link #normalise} already did, and reads a GTIN when the code starts {@code 01}
     * followed by fourteen digits (the check digit is not verified), else returns {@link #UNKNOWN_GTIN} as an
     * unchecked value.
     */
    public static Optional<ClaimedGtin> claimedGtin(String canonical, MarkingStructureCheck policy) {
        if (policy == MarkingStructureCheck.GS1) {
            Structure structure = gs1Structure(canonical);
            return structure.valid() && structure.gtin() != null
                    ? Optional.of(new ClaimedGtin(structure.gtin(), true))
                    : Optional.empty();
        }
        if (canonical.startsWith("01") && canonical.length() >= 16 && isDigits(canonical.substring(2, 16))) {
            return Optional.of(new ClaimedGtin(canonical.substring(2, 16), true));
        }
        return Optional.of(new ClaimedGtin(UNKNOWN_GTIN, false));
    }

    /**
     * @param readable false when the GTIN is the {@link #UNKNOWN_GTIN} placeholder, so no product match can
     *     be made from it
     */
    public record ClaimedGtin(String gtin, boolean readable) {}

    /**
     * The catalog classification's {@code barcode} (an EAN-8, UPC-A or EAN-13 as stored, up to thirteen
     * digits) left-padded with zeros to fourteen digits, so it can be compared with a code's GTIN; empty
     * for a barcode that is not all digits or is longer than fourteen.
     */
    public static Optional<String> barcodeAsGtin14(@Nullable String barcode) {
        if (barcode == null) {
            return Optional.empty();
        }
        String digits = barcode.trim();
        if (digits.isEmpty() || digits.length() > 14 || !isDigits(digits)) {
            return Optional.empty();
        }
        return Optional.of("0".repeat(14 - digits.length()) + digits);
    }

    private static boolean isDigits(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return !value.isEmpty();
    }

    /** GS1 mod-10: weights 3,1,3,1... from the digit left of the check digit. */
    private static boolean hasValidCheckDigit(String gtin14) {
        int total = 0;
        for (int index = 12; index >= 0; index--) {
            int weight = (12 - index) % 2 == 0 ? 3 : 1;
            total += (gtin14.charAt(index) - '0') * weight;
        }
        return (10 - total % 10) % 10 == gtin14.charAt(13) - '0';
    }
}
