package uz.horecaos.platform.courier.domain;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The words a settlement statement may not contain (ADR 0042).
 *
 * <p>Couriers here are registered self-employed persons. The statement carries
 * gross only: no withholding line, no net-of-tax line, no payslip. That decision
 * is easy to state and easy to erode — somebody adds a "net" field meaning "net
 * of cash held", an accountant reads it as net of tax, and the platform has
 * quietly published a payroll document. So the rule is enforced on the document
 * rather than only asserted in a test: a statement carrying one of these words
 * is refused before it is hashed.
 *
 * <p>The transfer figure is {@code amountPayableMinor} and the statement labels
 * it as an amount to transfer, with the explicit note that no tax has been
 * deducted and none will be.
 */
public final class StatementVocabulary {

    private static final Set<String> FORBIDDEN = Set.of(
            "net", "netof", "netpay", "netpayable", "netamount",
            "withhold", "withholding", "withheld",
            "tax", "taxable", "taxdeducted", "ndfl", "payslip", "payroll",
            "pensioncontribution", "socialcontribution");

    private StatementVocabulary() {
    }

    /** Thrown when a statement document would carry tax language. */
    public static final class TaxLanguageException extends IllegalStateException {
        public TaxLanguageException(String message) {
            super(message);
        }
    }

    /**
     * The one key whose value is exempt, and the only place the word "tax" may
     * appear on a statement at all.
     *
     * <p>ADR 0042 requires the transfer amount to be labelled with an explicit
     * note that no tax has been deducted and none will be. Saying so is the
     * opposite of implying a deduction, and a reader who is told plainly does
     * not go looking for a withholding line that is not there.
     */
    private static final String DECLARATION_KEY = "declaration";

    /**
     * Walks the whole document, keys and string values alike. A label is as
     * capable of misleading a reader as a field name, and the person who adds
     * one is usually adding it to a rendering map rather than to a record.
     */
    public static void assertCarriesNoTaxLanguage(Object document) {
        List<String> offences = new java.util.ArrayList<>();
        walk(document, "statement", offences);
        if (!offences.isEmpty()) {
            throw new TaxLanguageException(
                    "A courier settlement statement carries gross only (ADR 0042): "
                            + String.join(", ", offences));
        }
    }

    private static void walk(Object node, String path, List<String> offences) {
        switch (node) {
            case null -> { }
            case Map<?, ?> map -> map.forEach((key, value) -> {
                String childPath = path + "." + key;
                check(String.valueOf(key), childPath, offences);
                if (!DECLARATION_KEY.equals(String.valueOf(key))) {
                    walk(value, childPath, offences);
                }
            });
            case Iterable<?> items -> {
                int index = 0;
                for (Object item : items) {
                    walk(item, path + "[" + index++ + "]", offences);
                }
            }
            case String text -> check(text, path, offences);
            default -> { }
        }
    }

    private static void check(String candidate, String path, List<String> offences) {
        String normalized = candidate.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        for (String forbidden : FORBIDDEN) {
            // Whole-token containment on the normalized form. "netofcashheld"
            // matches, and so does "Итого net" once punctuation is stripped;
            // "network" does not, because the check runs on the tokens a
            // statement actually uses rather than on arbitrary prose.
            if (normalized.equals(forbidden)
                    || normalized.startsWith(forbidden + "of")
                    || normalized.startsWith(forbidden + "minor")
                    || normalized.endsWith(forbidden)) {
                offences.add("%s says \"%s\"".formatted(path, candidate));
                return;
            }
        }
    }
}
