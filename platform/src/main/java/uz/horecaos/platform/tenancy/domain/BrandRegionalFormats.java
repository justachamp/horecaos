package uz.horecaos.platform.tenancy.domain;

import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * How a brand's operators read money and phone numbers in the console (Settings
 * 10.12): where the currency unit sits on a total, how thousands are grouped, and
 * how a phone number is written.
 *
 * <p>Display only. The platform still stores an amount as integer minor units plus
 * a currency and a phone as E.164; nothing here changes what is stored, exported or
 * sent to a customer. The timezone is not part of this record because it is not a
 * brand fact: it is the tenant's ({@code tenant.tenants.default_timezone}) and each
 * branch's own.
 *
 * @param moneySymbolPlacement where the unit is written on a total; a row carries the
 *                             bare number either way
 * @param moneyGrouping        how the thousands of an amount are separated. The decimal
 *                             separator follows the choice so the two never collide
 * @param phoneDisplayPattern  one {@code #} per digit, with {@code + ( ) - .} and spaces
 *                             kept as written, for example {@code +### ## ### ## ##};
 *                             {@code null} shows the number as it arrives
 */
public record BrandRegionalFormats(
        MoneySymbolPlacement moneySymbolPlacement,
        MoneyGrouping moneyGrouping,
        @Nullable String phoneDisplayPattern) {

    /** Only what a phone pattern is made of; the digit-slot count is checked separately. */
    private static final Pattern PHONE_PATTERN_CHARACTERS = Pattern.compile("^[+#() .-]{1,32}$");

    /** E.164's own range: a number is at least seven and at most fifteen digits. */
    private static final int MIN_DIGIT_SLOTS = 7;

    private static final int MAX_DIGIT_SLOTS = 15;

    public BrandRegionalFormats {
        Objects.requireNonNull(moneySymbolPlacement, "Money symbol placement is required");
        Objects.requireNonNull(moneyGrouping, "Money grouping is required");
        phoneDisplayPattern = normalizedPhonePattern(phoneDisplayPattern);
    }

    /** What the console did before this was configurable, and what a brand that has chosen nothing gets. */
    public static BrandRegionalFormats defaults() {
        return new BrandRegionalFormats(MoneySymbolPlacement.AFTER, MoneyGrouping.SPACE, null);
    }

    private static @Nullable String normalizedPhonePattern(@Nullable String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        // Strip only the ends: the spaces inside a pattern are part of it.
        String stripped = pattern.strip();
        if (!PHONE_PATTERN_CHARACTERS.matcher(stripped).matches()) {
            throw new IllegalArgumentException(
                    "A phone display pattern is made of # (one per digit), + ( ) - . and spaces, at most 32 characters");
        }
        long slots = stripped.chars().filter(character -> character == '#').count();
        if (slots < MIN_DIGIT_SLOTS || slots > MAX_DIGIT_SLOTS) {
            throw new IllegalArgumentException("A phone display pattern needs between " + MIN_DIGIT_SLOTS + " and "
                    + MAX_DIGIT_SLOTS + " # slots, one per digit");
        }
        return stripped;
    }

    /** Where the currency unit is written on a total. */
    public enum MoneySymbolPlacement {
        BEFORE,
        AFTER
    }

    /** How the thousands of an amount are separated. */
    public enum MoneyGrouping {
        /** A no-break space, so a browser never splits {@code 146 000} across two lines. */
        SPACE,
        COMMA,
        DOT,
        NONE
    }
}
