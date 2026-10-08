package uz.horecaos.platform.content.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.content.domain.ContentDocuments.Validated;
import uz.horecaos.platform.content.domain.DocumentProblems.InvalidDocumentException;

/**
 * The server-side sanitiser for the editorial body (ADR 0157, Decision 6).
 *
 * <p>The ADR does not store sanitised HTML: markup is not representable, so there is nothing to
 * sanitise. What is tested here is that this really holds on the bytes that arrive -- a corpus of
 * the things an attacker would try, each refused or stored as inert text, and a walk over every
 * accepted document proving it contains no key outside the block grammar.
 */
class BlockDocumentsTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID ASSET = UUID.fromString("018f9b20-4000-7000-8000-0000000000a1");

    /** Every key a stored editorial document may contain, at any depth. */
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "schemaVersion",
            "texts",
            "title",
            "summary",
            "coverAssetId",
            "body",
            "blocks",
            "type",
            "inlines",
            "level",
            "ordered",
            "items",
            "assetId",
            "caption",
            "text",
            "bold",
            "italic",
            "href");

    private static String news(String blocks) {
        return "{\"texts\":{\"ru\":{\"title\":\"t\"}},\"body\":{\"ru\":{\"blocks\":" + blocks + "}}}";
    }

    private static Validated ok(String blocks) {
        return ContentDocuments.validate(ContentKind.NEWS, JSON.readTree(news(blocks)));
    }

    private static List<FieldProblem> refused(String blocks) {
        return org.junit.jupiter.api.Assertions.assertThrows(
                        InvalidDocumentException.class,
                        () -> ContentDocuments.validate(ContentKind.NEWS, JSON.readTree(news(blocks))))
                .problems();
    }

    private static String paragraph(String run) {
        return "[{\"type\":\"PARAGRAPH\",\"inlines\":[" + run + "]}]";
    }

    // ========================================================= what is stored

    @Test
    @DisplayName("every block type is accepted, normalised, and nothing outside the grammar survives")
    void everyBlockType() {
        Validated document = ok("[{\"type\":\"HEADING\",\"level\":2,\"inlines\":[{\"text\":\"Hello\"}]},"
                + "{\"type\":\"PARAGRAPH\",\"inlines\":[{\"text\":\"a \",\"bold\":true},{\"text\":\"b\",\"italic\":true,\"href\":\"https://shop.example.uz/x\"}]},"
                + "{\"type\":\"QUOTE\",\"inlines\":[{\"text\":\"q\"}]},"
                + "{\"type\":\"LIST\",\"ordered\":true,\"items\":[[{\"text\":\"one\"}],[{\"text\":\"two\"}]]},"
                + "{\"type\":\"IMAGE\",\"assetId\":\"" + ASSET + "\",\"caption\":[{\"text\":\"c\"}]},"
                + "{\"type\":\"DIVIDER\"}]");

        JsonNode blocks = document.document().get("body").get("ru").get("blocks");
        assertThat(blocks).hasSize(6);
        assertThat(blocks.get(1).get("inlines").get(0).get("bold").booleanValue())
                .isTrue();
        assertThat(blocks.get(1).get("inlines").get(0).has("italic")).isFalse();
        assertThat(blocks.get(3).get("ordered").booleanValue()).isTrue();
        assertThat(document.facts().assets())
                .singleElement()
                .satisfies(use -> assertThat(use.role()).isEqualTo(MediaRole.BODY_IMAGE));
        assertThat(document.facts().externalRefs())
                .singleElement()
                .satisfies(ref -> assertThat(ref.host()).isEqualTo("shop.example.uz"));
        assertOnlyAllowedKeys(document.document());
    }

    @Test
    @DisplayName("text that looks like markup is stored as the plain text it is: there is no way to say it is HTML")
    void markupIsJustText() {
        String hostile = "<script>alert(1)</script><img src=x onerror=alert(1)>&lt;b&gt;";
        Validated document = ok(paragraph("{\"text\":\"" + hostile.replace("\"", "\\\"") + "\"}"));

        JsonNode run = document.document()
                .get("body")
                .get("ru")
                .get("blocks")
                .get(0)
                .get("inlines")
                .get(0);
        assertThat(run.get("text").stringValue()).isEqualTo(hostile);
        assertThat(run.propertyNames()).containsExactly("text");
        assertOnlyAllowedKeys(document.document());
    }

    @Test
    @DisplayName("an optional flag omitted is the default, not an error: the console sends only the keys it fills in")
    void optionalFlagsMayBeOmitted() {
        Validated document = ok(
                "[{\"type\":\"LIST\",\"items\":[[{\"text\":\"one\"}]]},{\"type\":\"HEADING\",\"inlines\":[{\"text\":\"h\"}]}]");
        JsonNode blocks = document.document().get("body").get("ru").get("blocks");
        assertThat(blocks.get(0).get("ordered").booleanValue()).isFalse();
        assertThat(blocks.get(1).get("level").intValue()).isEqualTo(2);
    }

    // ============================================================== hrefs

    @Test
    @DisplayName(
            "an href is https, tel: or mailto: and nothing else: every other scheme and every relative form is refused")
    void hrefSchemes() {
        for (String bad : new String[] {
            "http://shop.example.uz/",
            "javascript:alert(1)",
            "JaVaScRiPt:alert(1)",
            "  javascript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD4=",
            "vbscript:msgbox(1)",
            "file:///etc/passwd",
            "ftp://shop.example.uz/",
            "//evil.example/phish",
            "/relative",
            "relative/path",
            "#fragment",
            "https://user:pass@shop.example.uz/",
            "https://shop.example.uz:8443/",
            "https://localhost/",
            "https://shop.example.uz/\\u0000",
            "mailto:a@b.uz?subject=x",
            "mailto:not-an-address",
            "tel:call-me",
            "tel:+998 90 123 45 67",
            "blob:https://shop.example.uz/1"
        }) {
            assertThat(refused(paragraph("{\"text\":\"x\",\"href\":\"" + bad + "\"}")))
                    .as("href %s", bad)
                    .anySatisfy(problem -> assertThat(problem.field()).endsWith("href"));
        }
    }

    @Test
    @DisplayName(
            "https, tel: and mailto: links are kept; an https host is handed to the allowlist, a phone and an address are not hosts")
    void goodHrefs() {
        Validated document = ok(
                paragraph(
                        "{\"text\":\"a\",\"href\":\"https://Shop.Example.UZ/menu\"},"
                                + "{\"text\":\"b\",\"href\":\"tel:+998901234567\"},{\"text\":\"c\",\"href\":\"mailto:hello@shop.example.uz\"}"));

        JsonNode inlines =
                document.document().get("body").get("ru").get("blocks").get(0).get("inlines");
        assertThat(inlines.get(0).get("href").stringValue()).isEqualTo("https://shop.example.uz/menu");
        assertThat(inlines.get(1).get("href").stringValue()).isEqualTo("tel:+998901234567");
        assertThat(inlines.get(2).get("href").stringValue()).isEqualTo("mailto:hello@shop.example.uz");
        assertThat(document.facts().externalRefs()).hasSize(1);
    }

    // ============================================================ the grammar

    @Test
    @DisplayName("an unknown block type, an unknown key anywhere, and a nested list are refused")
    void unknownThingsAreRefused() {
        assertThat(refused("[{\"type\":\"IFRAME\",\"src\":\"https://evil.example\"}]"))
                .anySatisfy(problem -> assertThat(problem.code()).isEqualTo("UNKNOWN_VALUE"));
        assertThat(refused("[{\"type\":\"PARAGRAPH\",\"inlines\":[{\"text\":\"x\"}],\"style\":\"color:red\"}]"))
                .anySatisfy(problem -> assertThat(problem.code()).isEqualTo("UNKNOWN_KEY"));
        assertThat(refused(paragraph("{\"text\":\"x\",\"html\":\"<b>x</b>\"}")))
                .anySatisfy(problem -> assertThat(problem.code()).isEqualTo("UNKNOWN_KEY"));
        assertThat(refused("[{\"type\":\"LIST\",\"items\":[{\"type\":\"LIST\",\"items\":[]}]}]"))
                .anySatisfy(problem -> assertThat(problem.code()).isEqualTo("NOT_AN_ARRAY"));
        assertThat(refused("[{\"type\":\"HEADING\",\"level\":1,\"inlines\":[{\"text\":\"h\"}]}]"))
                .anySatisfy(problem -> assertThat(problem.code()).isEqualTo("OUT_OF_RANGE"));
        assertThat(refused("[{\"type\":\"IMAGE\"}]"))
                .anySatisfy(problem -> assertThat(problem.code()).isEqualTo("REQUIRED"));
        assertThat(refused("{\"type\":\"PARAGRAPH\"}"))
                .anySatisfy(problem -> assertThat(problem.code()).isEqualTo("NOT_AN_ARRAY"));
    }

    @Test
    @DisplayName(
            "size and nesting limits: 200 blocks, 40 runs a block, 50 list items, 5,000 characters a run and 50,000 a language")
    void limits() {
        assertThat(ok("[" + repeat("{\"type\":\"DIVIDER\"}", 200) + "]")).isNotNull();
        assertThat(refused("[" + repeat("{\"type\":\"DIVIDER\"}", 201) + "]"))
                .anySatisfy(problem -> assertThat(problem.code()).isEqualTo("TOO_MANY"));

        assertThat(refused(paragraph(repeat("{\"text\":\"x\"}", 41))))
                .anySatisfy(problem -> assertThat(problem.code()).isEqualTo("TOO_MANY"));
        ok(paragraph(repeat("{\"text\":\"x\"}", 40)));

        assertThat(refused("[{\"type\":\"LIST\",\"items\":[" + repeat("[{\"text\":\"i\"}]", 51) + "]}]"))
                .anySatisfy(problem -> assertThat(problem.code()).isEqualTo("TOO_MANY"));

        assertThat(refused(paragraph("{\"text\":\"" + "a".repeat(5_001) + "\"}")))
                .anySatisfy(problem -> assertThat(problem.code()).isEqualTo("TOO_LONG"));

        // Ten runs of 5,000 is exactly 50,000 characters: accepted. One more character is not.
        ok(paragraph(repeat("{\"text\":\"" + "a".repeat(5_000) + "\"}", 10)));
        assertThat(refused(paragraph(repeat("{\"text\":\"" + "a".repeat(5_000) + "\"}", 10) + ",{\"text\":\"b\"}")))
                .anySatisfy(problem -> assertThat(problem.code()).isEqualTo("TEXT_TOO_LONG"));
    }

    @Test
    @DisplayName(
            "a body refuses control characters, line separators and the bidirectional overrides, but keeps a newline")
    void invisibleCharacters() {
        ok(paragraph("{\"text\":\"line one\\nline two\"}"));
        for (int codePoint : new int[] {
            0x0000, 0x0007, 0x001B, 0x007F, 0x0085, 0x2028, 0x2029, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066,
            0x2067, 0x2068, 0x2069
        }) {
            String text = "a" + new String(Character.toChars(codePoint)) + "b";
            assertThat(refused(paragraph("{\"text\":\"" + escape(text) + "\"}")))
                    .as("U+%04X", codePoint)
                    .anySatisfy(problem -> assertThat(problem.code()).isEqualTo("FORBIDDEN_CHARACTER"));
        }
        // A run keeps the spaces that separate it from its neighbours: it is not stripped.
        Validated document = ok(paragraph("{\"text\":\"a \"},{\"text\":\" b\"}"));
        JsonNode inlines =
                document.document().get("body").get("ru").get("blocks").get(0).get("inlines");
        assertThat(inlines.get(0).get("text").stringValue()).isEqualTo("a ");
        assertThat(inlines.get(1).get("text").stringValue()).isEqualTo(" b");
    }

    @Test
    @DisplayName(
            "a block document that is not an object, has no blocks array, or carries a version other than 1 is refused")
    void documentShape() {
        for (String body : new String[] {
            "[]", "\"text\"", "{\"blocks\":\"x\"}", "{\"schemaVersion\":2,\"blocks\":[]}", "{\"blocks\":[],\"extra\":1}"
        }) {
            String document = "{\"texts\":{\"ru\":{\"title\":\"t\"}},\"body\":{\"ru\":" + body + "}}";
            org.junit.jupiter.api.Assertions.assertThrows(
                    InvalidDocumentException.class,
                    () -> ContentDocuments.validate(ContentKind.NEWS, JSON.readTree(document)));
        }
        // An empty body is a body with no blocks, which is allowed to be a draft.
        ContentDocuments.validate(
                ContentKind.NEWS,
                JSON.readTree("{\"texts\":{\"ru\":{\"title\":\"t\"}},\"body\":{\"ru\":{\"blocks\":[]}}}"));
    }

    // ================================================================ helpers

    private static void assertOnlyAllowedKeys(JsonNode node) {
        List<String> stray = new ArrayList<>();
        walk(node, stray, 0);
        assertThat(stray)
                .as("keys outside the block grammar in a stored document")
                .isEmpty();
    }

    private static void walk(JsonNode node, List<String> stray, int depth) {
        if (node.isObject()) {
            for (var entry : node.properties()) {
                // Language keys sit directly under "texts" and "body".
                boolean languageKey = depth > 0 && entry.getValue().isObject() && (isLocale(entry.getKey()));
                if (!languageKey && !ALLOWED_KEYS.contains(entry.getKey())) {
                    stray.add(entry.getKey());
                }
                walk(entry.getValue(), stray, depth + 1);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                walk(element, stray, depth + 1);
            }
        }
    }

    private static boolean isLocale(String key) {
        return key.equals("ru") || key.equals("uz-Latn") || key.equals("en");
    }

    private static String repeat(String piece, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) {
            out.append(i == 0 ? "" : ",").append(piece);
        }
        return out.toString();
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            out.append(String.format("\\u%04x", (int) c));
        }
        return out.toString();
    }
}
