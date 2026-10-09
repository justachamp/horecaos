package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.mfa.MfaRequirementMode;
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

    /** The late-order threshold the order-policy card has always allowed: a minute to ten hours (ADR 0150). */
    private static final int MAXIMUM_LATE_ORDER_THRESHOLD_MINUTES = 600;

    /**
     * The longest disclosure sentence a tenant may author (ADR 0069). Declared here and not
     * read from {@code AssistantConfigurationKeys}: that class lives in the assistant module,
     * which this one may not import, and {@code AssistantConfigurationKeysTests} holds the two
     * numbers together.
     */
    static final int DISCLOSURE_TEXT_MAXIMUM_CHARACTERS = 500;

    /**
     * The languages a printed document can be written in (ADR 0154). Restated from {@code
     * printing.api.PrintingConfigurationKeys#DOCUMENT_LOCALES}, which this module may not import;
     * {@code PrintingConfigurationKeyTests} holds the two lists together.
     */
    static final java.util.List<String> PRINT_DOCUMENT_LOCALES = java.util.List.of("ru", "uz-Latn", "en");

    private static final Map<String, Consumer<Object>> RULES = Map.ofEntries(
            Map.entry("ordering.late_colour", ConfigurationValueRules::requireBlankOrHexColour),
            Map.entry("ordering.at_risk_before_minutes", ConfigurationValueRules::requireMinutesWithinADay),
            Map.entry("iam.staff_mfa_requirement", ConfigurationValueRules::requireMfaRequirementMode),
            Map.entry("ordering.late_order_threshold_minutes", ConfigurationValueRules::requireLateOrderThreshold),
            Map.entry("kitchen.display.not_seen_after_minutes", ConfigurationValueRules::requireNotSeenAfterMinutes),
            Map.entry(
                    "assistant.disclosure_text_en",
                    value -> requirePlainShortText("assistant.disclosure_text_en", value)),
            Map.entry(
                    "assistant.disclosure_text_ru",
                    value -> requirePlainShortText("assistant.disclosure_text_ru", value)),
            Map.entry(
                    "assistant.disclosure_text_uz",
                    value -> requirePlainShortText("assistant.disclosure_text_uz", value)),
            Map.entry("printing.document_locale", ConfigurationValueRules::requirePrintDocumentLocale),
            Map.entry(
                    "printing.kitchen_ticket.expire_minutes",
                    value -> requireWholeMinutes("printing.kitchen_ticket.expire_minutes", value, 5, 240)),
            Map.entry(
                    "printing.late_marker_minutes",
                    value -> requireWholeMinutes("printing.late_marker_minutes", value, 1, MAXIMUM_MINUTES)),
            // ADR 0155: the five terminal policy values. Refused here, at the one place every writer passes through, so
            // a PIN length of 5 or a session of 40 hours is never stored for a reader to trip over.
            Map.entry("terminal.pin.length", value -> requireOneOf("terminal.pin.length", value, 4, 6)),
            Map.entry(
                    "terminal.auto_lock_seconds",
                    value -> requireBetween("terminal.auto_lock_seconds", value, 30, 3_600)),
            Map.entry(
                    "terminal.session.max_hours", value -> requireBetween("terminal.session.max_hours", value, 1, 16)),
            Map.entry(
                    "terminal.roster.display",
                    value -> requireText("terminal.roster.display", value, "NAME_INITIAL", "REFERENCE")),
            Map.entry(
                    "terminal.attribution.default",
                    value -> requireText(
                            "terminal.attribution.default",
                            value,
                            "DEVICE_ONLY",
                            "PERSON_OPTIONAL",
                            "PERSON_REQUIRED")),
            // ADR 0176: a sign-in row is kept thirty days to two years; past either end it is a typo.
            Map.entry("iam.staff_sign_in_retention_days", integerBetween("iam.staff_sign_in_retention_days", 30, 730)),
            // ADR 0156: the working-time keys. Each range is the record's own.
            Map.entry(
                    "workforce.shift.variance_threshold_minutes",
                    integerBetween("workforce.shift.variance_threshold_minutes", 5, 240)),
            Map.entry(
                    "workforce.shift.retroactive_edit_days",
                    integerBetween("workforce.shift.retroactive_edit_days", 0, 60)),
            Map.entry("workforce.shift.max_open_hours", integerBetween("workforce.shift.max_open_hours", 4, 16)),
            Map.entry(
                    "workforce.roster.late_after_minutes",
                    integerBetween("workforce.roster.late_after_minutes", 1, 120)),
            Map.entry(
                    "workforce.roster.match_window_minutes",
                    integerBetween("workforce.roster.match_window_minutes", 30, 480)));

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

    /** ADR 0148: a mistyped mode must be refused, never quietly read as "OFF". */
    private static void requireMfaRequirementMode(Object value) {
        if (MfaRequirementMode.parse(String.valueOf(value)).isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "iam.staff_mfa_requirement must be OFF, SENSITIVE_ROLES or ALL_STAFF");
        }
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

    private static void requireOneOf(String code, Object value, int... allowed) {
        int number = ((Number) value).intValue();
        for (int candidate : allowed) {
            if (candidate == number) {
                return;
            }
        }
        throw new ApiException(
                ErrorCode.VALIDATION_FAILED,
                code + " must be one of " + java.util.Arrays.toString(allowed),
                Map.of("field", code));
    }

    private static void requireBetween(String code, Object value, int minimum, int maximum) {
        int number = ((Number) value).intValue();
        if (number < minimum || number > maximum) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "%s must be between %d and %d".formatted(code, minimum, maximum),
                    Map.of("field", code));
        }
    }

    private static void requireText(String code, Object value, String... allowed) {
        String text = String.valueOf(value);
        for (String candidate : allowed) {
            if (candidate.equals(text)) {
                return;
            }
        }
        throw new ApiException(
                ErrorCode.VALIDATION_FAILED,
                code + " must be one of " + String.join(", ", allowed),
                Map.of("field", code));
    }

    private static void requireMinutesWithinADay(Object value) {
        int minutes = ((Number) value).intValue();
        if (minutes < 0 || minutes > MAXIMUM_MINUTES) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "ordering.at_risk_before_minutes must be between 0 and " + MAXIMUM_MINUTES);
        }
    }

    /**
     * Not zero and not negative: the threshold is the no-promise fallback of the lateness policy
     * (ADR 0150), a minimum of one minute everywhere a document may set it, and a value the reader
     * would have to ignore is a control that changes nothing.
     */
    private static void requireLateOrderThreshold(Object value) {
        int minutes = ((Number) value).intValue();
        if (minutes < 1 || minutes > MAXIMUM_LATE_ORDER_THRESHOLD_MINUTES) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "ordering.late_order_threshold_minutes must be between 1 and "
                            + MAXIMUM_LATE_ORDER_THRESHOLD_MINUTES);
        }
    }

    /** ADR 0154: a mistyped language is refused, never printed as a half-translated slip. */
    private static void requirePrintDocumentLocale(Object value) {
        if (!PRINT_DOCUMENT_LOCALES.contains(String.valueOf(value))) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "printing.document_locale must be one of " + PRINT_DOCUMENT_LOCALES);
        }
    }

    /** ADR 0154: a window that is a typo (zero, or a week) is a control that does nothing or everything. */
    private static void requireWholeMinutes(String code, Object value, int minimum, int maximum) {
        int minutes = ((Number) value).intValue();
        if (minutes < minimum || minutes > maximum) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "%s must be between %d and %d".formatted(code, minimum, maximum));
        }
    }

    /** At least a minute (a wall stamps its read at most once a minute) and at most a day. */
    private static void requireNotSeenAfterMinutes(Object value) {
        int minutes = ((Number) value).intValue();
        if (minutes < 1 || minutes > MAXIMUM_MINUTES) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "kitchen.display.not_seen_after_minutes must be between 1 and " + MAXIMUM_MINUTES);
        }
    }

    /** A whole number inside the record's stated range; the key's own type already refuses a string. */
    private static Consumer<Object> integerBetween(String code, int minimum, int maximum) {
        return value -> {
            long number = ((Number) value).longValue();
            if (number < minimum || number > maximum) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED, "%s must be between %d and %d".formatted(code, minimum, maximum));
            }
        };
    }
}
