package uz.horecaos.platform.customers.application;

/**
 * The part of a phone number a list may show (ADR 0111, ADR 0029).
 *
 * <p>Written once, when a lead is created, so that no list ever has to decrypt to render a row. A
 * masked Uzbek mobile is four unknown digits over a known operator prefix, which is a convenience
 * for an operator choosing between two callers and not anonymity -- which is why nothing a customer
 * sees about themselves is ever masked, and why this is only ever shown to staff holding the read
 * capability.
 */
final class PhoneMasks {

    private PhoneMasks() {}

    /** {@code +998901234567} becomes {@code +998 ** *** 45 67}; anything else keeps only its last four digits. */
    static String mask(String normalized) {
        String digits = normalized.replaceAll("[^0-9]", "");
        String lastFour = digits.length() >= 4 ? digits.substring(digits.length() - 4) : digits;
        if (normalized.startsWith("+998") && digits.length() == 12) {
            return "+998 ** *** %s %s".formatted(lastFour.substring(0, 2), lastFour.substring(2));
        }
        return "*** " + lastFour;
    }
}
