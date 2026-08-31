package uz.horecaos.platform.audit.domain;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Builds classification-aware change documents (ADR 0027).
 *
 * <p>An audit trail is worthless if it becomes a second copy of the data it is
 * meant to protect. Fields carrying personal, sensitive, or credential data are
 * recorded as a marker showing that the field changed, never as a value.
 *
 * <p><strong>Interim mechanism.</strong> ADR 0029 will supply a classification
 * annotation this should consume. Until then the check is name-based, matching
 * the interim mechanism used for event payloads, and deliberately errs toward
 * redacting too much.
 */
public final class ChangeDocuments {

    /** Marker written in place of a protected value. */
    public static final String REDACTED = "[redacted]";

    private static final Set<String> PROTECTED_TERMS = Set.of(
            "phone",
            "email",
            "passport",
            "birth",
            "dateofbirth",
            "firstname",
            "lastname",
            "middlename",
            "fullname",
            "personname",
            "address",
            "latitude",
            "longitude",
            "coordinate",
            "geolocation",
            "password",
            "secret",
            "token",
            "credential",
            "apikey",
            "cardnumber",
            "pan",
            "cvv",
            "iban",
            "ssn",
            "jshir",
            "tin",
            "note",
            "comment",
            "instructions",
            "devicefingerprint");

    private ChangeDocuments() {}

    /**
     * Records a single field changing from one value to another.
     *
     * <p>Uses maps that permit nulls, because "the field was previously unset"
     * is itself evidence and must stay distinguishable from a redacted value.
     */
    public static Map<String, Object> change(String field, @Nullable Object before, @Nullable Object after) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("before", redact(field, before));
        change.put("after", redact(field, after));

        Map<String, Object> document = new LinkedHashMap<>();
        document.put(field, change);
        return document;
    }

    /** Redacts every protected field in a prepared document, including nested maps. */
    public static Map<String, Object> sanitize(Map<String, Object> document) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        document.forEach((field, value) -> sanitized.put(field, sanitizeValue(field, value)));
        return sanitized;
    }

    public static boolean isProtected(String field) {
        String normalized = field.toLowerCase(Locale.ROOT);
        return PROTECTED_TERMS.stream().anyMatch(normalized::contains);
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Object sanitizeValue(String field, @Nullable Object value) {
        if (value instanceof Map<?, ?> nested) {
            // A protected field name redacts its whole subtree: a nested "before"
            // and "after" under "customerPhone" are both the phone number.
            if (isProtected(field)) {
                Map<String, Object> marked = new LinkedHashMap<>();
                // A null stays null: "the field was unset" is evidence, and it must
                // stay distinguishable from "a value existed and is hidden".
                nested.forEach(
                        (key, nestedValue) -> marked.put(String.valueOf(key), nestedValue == null ? null : REDACTED));
                return marked;
            }
            return sanitize((Map<String, Object>) nested);
        }
        return redact(field, value);
    }

    private static @Nullable Object redact(String field, @Nullable Object value) {
        if (value == null) {
            return null;
        }
        return isProtected(field) ? REDACTED : value;
    }
}
