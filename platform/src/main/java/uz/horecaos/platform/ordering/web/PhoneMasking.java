package uz.horecaos.platform.ordering.web;

import org.jspecify.annotations.Nullable;

/**
 * The board and detail phone mask (orders.md §1.5): {@code +998 90 ••• •• 42}.
 *
 * <p>Presentation only — the decryption that produces the plaintext this masks
 * happens once, in {@code OrderQueryService}, which is the only place holding
 * {@code FieldProtection}. This class never sees a ciphertext and never calls
 * out to anything; it exists so the masking rule is written once rather than
 * once per response record that needs it.
 */
final class PhoneMasking {

    private static final String GLYPH = "•"; // •

    private PhoneMasking() {}

    /**
     * Masks a phone number for display, keeping only enough to recognise it.
     *
     * @return the masked form, or null when there was nothing to mask — a
     *         customer with no phone on file renders {@code —}, not an empty
     *         mask that looks like a data-entry error
     */
    static @Nullable String mask(@Nullable String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }

        String digits = phone.replaceAll("[^0-9]", "");

        // The market this platform serves: +998, a two-digit operator code, and
        // seven subscriber digits. Matched exactly, because a near-miss masked
        // by the generic fallback below is safer than one formatted as if it
        // were a real Uzbek number when it is not.
        if (digits.length() == 12 && digits.startsWith("998")) {
            String operatorCode = digits.substring(3, 5);
            String lastTwo = digits.substring(digits.length() - 2);
            return "+998 %s %s%s%s %s%s %s".formatted(operatorCode, GLYPH, GLYPH, GLYPH, GLYPH, GLYPH, lastTwo);
        }

        // Any other shape still never reaches the client whole: keep enough of
        // the head to recognise a country code and the tail to disambiguate two
        // customers on a call, mask everything between.
        if (phone.length() <= 6) {
            return GLYPH.repeat(phone.length());
        }
        String head = phone.substring(0, 4);
        String tail = phone.substring(phone.length() - 2);
        return head + GLYPH.repeat(Math.max(3, phone.length() - 6)) + tail;
    }
}
