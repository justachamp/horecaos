package uz.horecaos.platform.content.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Strict reading of one JSON object (ADR 0157: "unknown keys rejected").
 *
 * <p>Every accessor marks its key consumed; {@link #finish} then refuses what is left. A missing
 * optional value takes a stated default and is never an error -- the console sends only the keys it
 * fills in, and a document must not be refused because an optional flag was omitted (the Jackson 3
 * defect this platform has met twice is a missing primitive in a request body, which this reader
 * never has because it reads a tree and not a record).
 */
final class ObjectReader {

    private final JsonNode node;
    private final String path;
    private final DocumentProblems problems;
    private final Set<String> consumed = new HashSet<>();
    private final boolean object;

    ObjectReader(@Nullable JsonNode node, String path, DocumentProblems problems) {
        this.path = path;
        this.problems = problems;
        this.object = node != null && node.isObject();
        this.node = object && node != null ? node : tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if (!object) {
            problems.add(path, "NOT_AN_OBJECT", "Expected an object");
        }
    }

    boolean valid() {
        return object;
    }

    String at(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    /** The raw child, or null when absent or JSON null. Marks the key consumed. */
    @Nullable
    JsonNode child(String key) {
        consumed.add(key);
        JsonNode child = node.get(key);
        return child == null || child.isNull() || child.isMissingNode() ? null : child;
    }

    boolean present(String key) {
        return child(key) != null;
    }

    /**
     * A nested object. An absent or null one reads as an empty object (every key of it is then
     * optional or required in its own right) unless {@code required}.
     */
    ObjectReader object(String key, boolean required) {
        JsonNode child = child(key);
        if (child == null) {
            if (required) {
                problems.add(at(key), "REQUIRED", "Required");
            }
            return new ObjectReader(
                    tools.jackson.databind.node.JsonNodeFactory.instance.objectNode(), at(key), problems);
        }
        return new ObjectReader(child, at(key), problems);
    }

    @Nullable
    String string(String key, int maxLength, boolean required, boolean allowNewline) {
        JsonNode child = child(key);
        if (child == null) {
            if (required) {
                problems.add(at(key), "REQUIRED", "Required");
            }
            return null;
        }
        if (!child.isString()) {
            problems.add(at(key), "NOT_A_STRING", "Expected a string");
            return null;
        }
        String value = TextRules.normalize(child.stringValue());
        if (value.isEmpty()) {
            if (required) {
                problems.add(at(key), "EMPTY", "Must not be blank");
            }
            return null;
        }
        if (TextRules.length(value) > maxLength) {
            problems.add(at(key), "TOO_LONG", "At most " + maxLength + " characters");
            return null;
        }
        if (TextRules.hasForbiddenCharacter(value, allowNewline)) {
            problems.add(at(key), "FORBIDDEN_CHARACTER", "Contains a control or direction-override character");
            return null;
        }
        return value;
    }

    @Nullable
    UUID uuid(String key, boolean required) {
        JsonNode child = child(key);
        if (child == null) {
            if (required) {
                problems.add(at(key), "REQUIRED", "Required");
            }
            return null;
        }
        return parseUuid(child, at(key));
    }

    @Nullable
    UUID parseUuid(JsonNode value, String field) {
        if (!value.isString()) {
            problems.add(field, "NOT_A_UUID", "Expected an identifier");
            return null;
        }
        try {
            return UUID.fromString(value.stringValue().strip());
        } catch (IllegalArgumentException malformed) {
            problems.add(field, "NOT_A_UUID", "Expected an identifier");
            return null;
        }
    }

    /** An integer in a range; a missing or null value takes {@code defaultValue} (and is required when that is null). */
    @Nullable
    Integer integer(String key, int min, int max, @Nullable Integer defaultValue) {
        JsonNode child = child(key);
        if (child == null) {
            if (defaultValue == null) {
                problems.add(at(key), "REQUIRED", "Required");
            }
            return defaultValue;
        }
        if (!child.isIntegralNumber() || !child.canConvertToInt()) {
            problems.add(at(key), "NOT_AN_INTEGER", "Expected a whole number");
            return defaultValue;
        }
        int value = child.intValue();
        if (value < min || value > max) {
            problems.add(at(key), "OUT_OF_RANGE", "Between " + min + " and " + max);
            return defaultValue;
        }
        return value;
    }

    /** An integer in a range, or null when absent or JSON null: for a value whose absence means something. */
    @Nullable
    Integer optionalInteger(String key, int min, int max) {
        JsonNode child = child(key);
        if (child == null) {
            return null;
        }
        if (!child.isIntegralNumber() || !child.canConvertToInt()) {
            problems.add(at(key), "NOT_AN_INTEGER", "Expected a whole number");
            return null;
        }
        int value = child.intValue();
        if (value < min || value > max) {
            problems.add(at(key), "OUT_OF_RANGE", "Between " + min + " and " + max);
            return null;
        }
        return value;
    }

    /** A boolean with a stated default; a missing value is the default, a non-boolean is refused. */
    boolean bool(String key, boolean defaultValue) {
        JsonNode child = child(key);
        if (child == null) {
            return defaultValue;
        }
        if (!child.isBoolean()) {
            problems.add(at(key), "NOT_A_BOOLEAN", "Expected true or false");
            return defaultValue;
        }
        return child.booleanValue();
    }

    <E extends Enum<E>> @Nullable E enumeration(String key, Class<E> type, boolean required, @Nullable E defaultValue) {
        JsonNode child = child(key);
        if (child == null) {
            if (required && defaultValue == null) {
                problems.add(at(key), "REQUIRED", "Required");
            }
            return defaultValue;
        }
        if (!child.isString()) {
            problems.add(at(key), "NOT_A_STRING", "Expected one of " + names(type));
            return defaultValue;
        }
        try {
            return Enum.valueOf(type, child.stringValue().strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            problems.add(at(key), "UNKNOWN_VALUE", "Expected one of " + names(type));
            return defaultValue;
        }
    }

    /** An array, or empty when absent; refused when it is too long or not an array. */
    List<JsonNode> array(String key, int max) {
        JsonNode child = child(key);
        if (child == null) {
            return List.of();
        }
        if (!child.isArray()) {
            problems.add(at(key), "NOT_AN_ARRAY", "Expected a list");
            return List.of();
        }
        if (child.size() > max) {
            problems.add(at(key), "TOO_MANY", "At most " + max + " entries");
            return List.of();
        }
        List<JsonNode> elements = new ArrayList<>(child.size());
        for (JsonNode element : child) {
            elements.add(element);
        }
        return elements;
    }

    /** A map keyed by language: canonical tag to its raw value. Unknown languages and duplicates are refused. */
    Map<String, JsonNode> localeMap(String key) {
        JsonNode child = child(key);
        Map<String, JsonNode> result = new LinkedHashMap<>();
        if (child == null) {
            return result;
        }
        if (!child.isObject()) {
            problems.add(at(key), "NOT_AN_OBJECT", "Expected an object keyed by language");
            return result;
        }
        for (Map.Entry<String, JsonNode> entry : child.properties()) {
            java.util.Optional<String> tag = TextRules.contentLocale(entry.getKey());
            if (tag.isEmpty()) {
                problems.add(at(key) + "." + entry.getKey(), "UNKNOWN_LOCALE", "Not a language this platform speaks");
                continue;
            }
            if (result.put(tag.get(), entry.getValue()) != null) {
                problems.add(at(key) + "." + entry.getKey(), "DUPLICATE_LOCALE", "The language is given twice");
            }
        }
        return result;
    }

    /** A problem of this object's own making, at one of its keys. */
    void problem(String key, String code, String message) {
        problems.add(at(key), code, message);
    }

    /** Refuses every key nobody asked for. */
    void finish() {
        if (!object) {
            return;
        }
        for (String name : node.propertyNames()) {
            if (!consumed.contains(name)) {
                problems.add(at(name), "UNKNOWN_KEY", "Not a field of this document");
            }
        }
    }

    private static String names(Class<? extends Enum<?>> type) {
        StringBuilder names = new StringBuilder();
        for (Enum<?> constant : type.getEnumConstants()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(constant.name());
        }
        return names.toString();
    }
}
