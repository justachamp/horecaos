package uz.horecaos.platform.printing.render;

import java.util.Map;

/**
 * Writes an amount the way the console does, because the paper and the screen must agree to the som.
 *
 * <p>The exponents are declared here as data this platform owns and are never asked of {@code
 * java.util.Currency}: ISO 4217 gives UZS two minor units and the platform stores whole som, so a
 * formatter that asked the JDK would print 125 000 som as 1 250. The same trap {@code money.ts} in the
 * operations console documents; an unknown currency throws, because a wrong price is worse than a
 * missing one.
 */
public final class MoneyText {

    private static final Map<String, Integer> MINOR_UNIT_EXPONENT = Map.of("UZS", 0, "USD", 2, "EUR", 2, "RUB", 2);

    private MoneyText() {}

    public static boolean knows(String currency) {
        return MINOR_UNIT_EXPONENT.containsKey(currency);
    }

    /**
     * @param withUnit write the unit after the number (on a total; a row carries the bare number)
     */
    public static String format(
            long amountMinor, String currency, String locale, boolean withUnit, PrintLabels labels) {
        Integer exponent = MINOR_UNIT_EXPONENT.get(currency);
        if (exponent == null) {
            throw new IllegalArgumentException("No minor-unit exponent is declared for the currency " + currency);
        }
        boolean negative = amountMinor < 0;
        long absolute = Math.abs(amountMinor);
        long divisor = (long) Math.pow(10, exponent);
        long whole = absolute / divisor;
        long fraction = absolute % divisor;

        StringBuilder text = new StringBuilder();
        if (negative) {
            text.append('-');
        }
        text.append(group(Long.toString(whole)));
        if (exponent > 0) {
            text.append("en".equals(locale) ? '.' : ',');
            text.append(String.format("%0" + exponent + "d", fraction));
        }
        if (withUnit) {
            String key = "unit." + currency;
            text.append(' ').append(labels.has(key) ? labels.get(key) : currency);
        }
        return text.toString();
    }

    /** Groups thousands with a plain space: a cell is a cell, and a no-break space would be a second glyph to cover. */
    private static String group(String digits) {
        StringBuilder grouped = new StringBuilder();
        int first = digits.length() % 3;
        if (first > 0) {
            grouped.append(digits, 0, first);
        }
        for (int index = first; index < digits.length(); index += 3) {
            if (grouped.length() > 0) {
                grouped.append(' ');
            }
            grouped.append(digits, index, index + 3);
        }
        return grouped.toString();
    }
}
