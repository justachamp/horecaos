package uz.horecaos.platform.audit.domain;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

    /**
     * Merges two flat snapshots — "before this write" and "after it" — into
     * the per-field {@code {before, after}} shape {@link #change} already
     * produces for one field, over the union of both maps' keys.
     *
     * <p>Staff 9.3a: almost every {@code .changed(...)} call site in this
     * codebase writes a single flat after-only map, or — {@code
     * TenantControlPlaneService}'s {@code brand.revised} and {@code
     * location.revised} facts, until this wave — a hand-rolled root-level
     * {@code {before, after}} pair that is two whole-object snapshots rather
     * than a diff a reader can act on. Either shape means «who changed the
     * minimum order sum from 30 000 to 50 000» cannot be answered from the
     * screen. This is the write-side primitive for fixing that a call site at
     * a time: pass what an aggregate looked like before a write and what it
     * looks like after, keyed identically, and get back the per-field diff
     * the activity log's viewer already parses.
     *
     * <p>A key present in only one map still gets an entry — a field that
     * existed and was cleared, or one that did not exist and was set, is a
     * change either way, not an omission.
     */
    public static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Set<String> fields = new LinkedHashSet<>(before.keySet());
        fields.addAll(after.keySet());

        Map<String, Object> document = new LinkedHashMap<>();
        for (String field : fields) {
            document.putAll(change(field, before.get(field), after.get(field)));
        }
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
