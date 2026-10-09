package uz.horecaos.platform.content.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * JSON with object keys sorted and no insignificant whitespace -- RFC 8785's rules, for the
 * subset of JSON a content document can hold (ADR 0157, {@code content_hash}).
 *
 * <p>Two drafts that say the same thing must hash the same, and two that do not must not. The
 * documents hold only strings, integers, booleans, nulls, arrays and objects: a floating-point
 * number is refused here rather than serialised, because RFC 8785's number rules are the one part
 * of it that is not a few lines, and the document grammar has no field that needs one.
 *
 * <p>Keys are ordered by UTF-16 code unit, which is what {@link String#compareTo} does and what
 * RFC 8785 specifies. Strings escape only what JSON requires (the quote, the backslash and the C0
 * controls); everything else, including U+2028, is written as itself.
 */
public final class CanonicalJson {

    private CanonicalJson() {}

    public static String write(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    /**
     * How many bytes PostgreSQL's {@code jsonb::text} would give this document.
     *
     * <p>PostgreSQL prints {@code ", "} and {@code ": "} where the canonical form prints {@code ","}
     * and {@code ":"}, so the table's {@code octet_length(document::text)} check sees a document a
     * little larger than the canonical one. The grammar enforces the same limit on the same figure,
     * so a document is refused with a code and never with a constraint violation.
     */
    public static int jsonbTextBytes(JsonNode node) {
        return write(node).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + separators(node);
    }

    private static int separators(JsonNode node) {
        int count = 0;
        if (node.isObject()) {
            int properties = 0;
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                properties++;
                count += 1 + separators(entry.getValue());
            }
            count += Math.max(0, properties - 1);
        } else if (node.isArray()) {
            int elements = 0;
            for (JsonNode element : node) {
                elements++;
                count += separators(element);
            }
            count += Math.max(0, elements - 1);
        }
        return count;
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isBoolean()) {
            out.append(node.booleanValue() ? "true" : "false");
        } else if (node.isString()) {
            writeString(node.stringValue(), out);
        } else if (node.isIntegralNumber()) {
            out.append(node.bigIntegerValue().toString());
        } else if (node.isNumber()) {
            throw new IllegalArgumentException("A content document holds no floating-point number");
        } else if (node.isArray()) {
            out.append('[');
            boolean first = true;
            for (JsonNode element : node) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(element, out);
            }
            out.append(']');
        } else if (node.isObject()) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
            entries.sort(Map.Entry.comparingByKey());
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : entries) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(entry.getKey(), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else {
            throw new IllegalArgumentException("A content document holds no " + node.getNodeType() + " value");
        }
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
