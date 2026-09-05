package uz.horecaos.platform.voice.domain;

/**
 * Masks a decrypted caller number for display (ADR 0029): everything but the
 * last four digits becomes a bullet.
 *
 * <p>Its own small utility rather than a dependency on {@code
 * ordering.web.PhoneMasking} — that class is package-private and belongs to
 * the order module's own edge, and this module's decrypt-once-then-mask
 * discipline is the same shape but not the same call site. Never given a
 * ciphertext: the decryption happens once, in {@code ScreenPopQueryService},
 * which is the only place in this module holding {@code FieldProtection}.
 */
public final class CallerNumberDisplay {

    private CallerNumberDisplay() {}

    public static String mask(String decryptedNumber) {
        String digitsAndPlus = decryptedNumber.strip();
        if (digitsAndPlus.length() <= 4) {
            return "•".repeat(Math.max(digitsAndPlus.length(), 0));
        }
        String tail = digitsAndPlus.substring(digitsAndPlus.length() - 4);
        return "•".repeat(digitsAndPlus.length() - 4) + tail;
    }
}
