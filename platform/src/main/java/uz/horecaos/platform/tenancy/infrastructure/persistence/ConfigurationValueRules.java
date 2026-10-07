package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Value rules a typed {@link ConfigurationKey} cannot express on its own (ADR
 * 0030).
 *
 * <p>A key's Java type refuses a string for an integer and nothing else; a
 * setting whose meaning is narrower than its type -- a colour that ends up in a
 * style binding, a minute count that feeds a threshold a constructor refuses to
 * take negative -- would otherwise be accepted by the write and fail somewhere
 * far from it, at read time, for every user of the board. Enforced in one place,
 * the value author, so the tenant surface, the platform-admin surface and any
 * future writer meet the same rule.
 *
 * <p>Only keys that need a rule appear here. A key with none is unchanged.
 */
public final class ConfigurationValueRules {

    /** {@code #rrggbb}: the shape {@code q-color-input} ever emits, and the only shape served to a stylesheet. */
    private static final Pattern HEX_COLOUR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** A day: past it a threshold is a typo, not a policy. */
    private static final int MAXIMUM_MINUTES = 1_440;

    /**
     * The longest disclosure sentence a tenant may author (ADR 0069). Declared here and not
     * read from {@code AssistantConfigurationKeys}: that class lives in the assistant module,
     * which this one may not import, and {@code AssistantConfigurationKeysTests} holds the two
     * numbers together.
     */
    static final int DISCLOSURE_TEXT_MAXIMUM_CHARACTERS = 500;

    private static final Map<String, Consumer<Object>> RULES = Map.of(
            "ordering.late_colour", ConfigurationValueRules::requireBlankOrHexColour,
            "ordering.at_risk_before_minutes", ConfigurationValueRules::requireMinutesWithinADay,
            "assistant.disclosure_text_en", value -> requirePlainShortText("assistant.disclosure_text_en", value),
            "assistant.disclosure_text_ru", value -> requirePlainShortText("assistant.disclosure_text_ru", value),
            "assistant.disclosure_text_uz", value -> requirePlainShortText("assistant.disclosure_text_uz", value));

    private ConfigurationValueRules() {}

    /**
     * @throws ApiException {@link ErrorCode#VALIDATION_FAILED} when the value breaks the key's rule
     */
    public static void validate(ConfigurationKey<?> key, @Nullable Object value) {
        if (value == null) {
            return;
        }
        Consumer<Object> rule = RULES.get(key.code());
        if (rule != null) {
            rule.accept(value);
        }
    }

    /** Whether {@code candidate} is a colour that may be served to a stylesheet. */
    public static boolean isHexColour(@Nullable String candidate) {
        return candidate != null && HEX_COLOUR.matcher(candidate).matches();
    }

    private static void requireBlankOrHexColour(Object value) {
        String text = String.valueOf(value);
        if (!text.isEmpty() && !isHexColour(text)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "ordering.late_colour must be blank (keep the design-system colour) or #rrggbb");
        }
    }

    /**
     * Text a customer will read in a chat message: bounded, and plain. A control character
     * (other than a line break) has no business in a sentence and can corrupt how a channel
     * renders it, so it is refused where it is written rather than discovered in a customer's
     * chat. Blank is allowed -- it means "keep the platform's wording".
     */
    private static void requirePlainShortText(String code, Object value) {
        String text = String.valueOf(value);
        if (text.length() > DISCLOSURE_TEXT_MAXIMUM_CHARACTERS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "%s must be at most %d characters".formatted(code, DISCLOSURE_TEXT_MAXIMUM_CHARACTERS));
        }
        boolean hasControlCharacter =
                text.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint) && codePoint != '\n');
        if (hasControlCharacter) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    code + " must be plain text: no control characters other than a line break");
        }
    }

    private static void requireMinutesWithinADay(Object value) {
        int minutes = ((Number) value).intValue();
        if (minutes < 0 || minutes > MAXIMUM_MINUTES) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "ordering.at_risk_before_minutes must be between 0 and " + MAXIMUM_MINUTES);
        }
    }
}
