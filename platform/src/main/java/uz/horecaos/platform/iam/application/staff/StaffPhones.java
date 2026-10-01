package uz.horecaos.platform.iam.application.staff;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A contact phone as the staff member record keeps it (ADR 0139): what is
 * stored, and the digits the keyed lookup hash is computed over.
 *
 * <p>A contact phone is a way to reach someone, not an identity, so the only
 * rule is that it is a plausible number -- seven to fifteen digits, an optional
 * leading plus, formatting characters ignored. It is never checked against a
 * colleague's number, because two people may legitimately share a kitchen's
 * mobile. The digits (not the formatting) feed the hash, so {@code +998 90 123
 * 45 67} and {@code 998901234567} find each other.
 */
final class StaffPhones {

    private static final int MIN_DIGITS = 7;
    private static final int MAX_DIGITS = 15;
    private static final String MASK_GLYPH = "•";

    private StaffPhones() {}

    /**
     * @param stored the number with formatting removed and a leading plus kept
     * @param digits the digits alone, for the lookup hash
     */
    record Phone(String stored, String digits) {

        @Override
        public String toString() {
            return "Phone[<redacted>]";
        }
    }

    /**
     * A usable phone, or empty for a blank value.
     *
     * @throws IllegalArgumentException for a value that is not blank and not a plausible number
     */
    static Optional<Phone> parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String stripped = raw.strip().replaceAll("[\\s().\\-]", "");
        boolean plus = stripped.startsWith("+");
        String digits = plus ? stripped.substring(1) : stripped;
        if (!digits.matches("[0-9]+") || digits.length() < MIN_DIGITS || digits.length() > MAX_DIGITS) {
            throw new IllegalArgumentException("A phone number has seven to fifteen digits");
        }
        return Optional.of(new Phone((plus ? "+" : "") + digits, digits));
    }

    /** As {@link #parse}, for a value that came from somewhere we do not control and must not fail over. */
    static Optional<Phone> parseLeniently(@Nullable String raw) {
        try {
            return parse(raw);
        } catch (IllegalArgumentException unusable) {
            return Optional.empty();
        }
    }

    /**
     * The list's phone: enough to recognise the number and nothing a scraper
     * could dial -- {@code +998 90 ••• •• 42} for the market this serves, the
     * head and tail of anything else.
     */
    static @Nullable String mask(@Nullable String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() == 12 && digits.startsWith("998")) {
            return "+998 %s %s%s%s %s%s %s"
                    .formatted(
                            digits.substring(3, 5),
                            MASK_GLYPH,
                            MASK_GLYPH,
                            MASK_GLYPH,
                            MASK_GLYPH,
                            MASK_GLYPH,
                            digits.substring(10));
        }
        if (phone.length() <= 6) {
            return MASK_GLYPH.repeat(phone.length());
        }
        return phone.substring(0, 4)
                + MASK_GLYPH.repeat(Math.max(3, phone.length() - 6))
                + phone.substring(phone.length() - 2);
    }
}
