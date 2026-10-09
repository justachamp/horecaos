package uz.horecaos.platform.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code content_hash} (ADR 0157, Physical model): what makes "nothing to publish" mean nothing.
 *
 * <p>It covers the document <em>and</em> the channel set, the window, the promotion and the
 * location -- extending a banner's end date must be an ordinary publish -- and nothing that differs
 * on every publish by construction.
 */
class ContentHashTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode DOCUMENT = JSON.readTree("{\"b\":1,\"a\":{\"y\":[1,2],\"x\":\"é\"},\"c\":null}");
    private static final Instant START = Instant.parse("2026-10-10T08:00:00Z");
    private static final Instant END = Instant.parse("2026-10-17T08:00:00Z");
    private static final UUID PROMOTION = UUID.fromString("018f9b20-4000-7000-8000-0000000000b3");
    private static final UUID LOCATION = UUID.fromString("018f9b20-4000-7000-8000-0000000000c3");

    private static String hash(
            JsonNode document,
            List<String> channels,
            @Nullable Instant start,
            @Nullable Instant end,
            @Nullable UUID promotion,
            @Nullable UUID location) {
        return ContentHash.of(document, channels, start, end, promotion, location);
    }

    private static String base() {
        return hash(DOCUMENT, List.of("WEB", "IOS"), START, END, PROMOTION, LOCATION);
    }

    @Test
    @DisplayName("canonical JSON has sorted keys and no whitespace, however the document was written")
    void canonicalisation() {
        assertThat(CanonicalJson.write(DOCUMENT)).isEqualTo("{\"a\":{\"x\":\"é\",\"y\":[1,2]},\"b\":1,\"c\":null}");
        JsonNode reordered = JSON.readTree("{ \"c\": null,\n \"a\": {\"x\":\"é\", \"y\":[1, 2]}, \"b\": 1 }");
        assertThat(CanonicalJson.write(reordered)).isEqualTo(CanonicalJson.write(DOCUMENT));
        assertThat(hash(reordered, List.of("WEB", "IOS"), START, END, PROMOTION, LOCATION))
                .isEqualTo(base());
    }

    @Test
    @DisplayName("a string escapes only what JSON requires, and floating point is not representable")
    void escapes() {
        assertThat(CanonicalJson.write(JSON.readTree("{\"s\":\"a\\\"b\\\\c\\n\\u0001\\u2028\"}")))
                .isEqualTo("{\"s\":\"a\\\"b\\\\c\\n\\u0001" + new String(Character.toChars(0x2028)) + "\"}");
        assertThatThrownBy(() -> CanonicalJson.write(JSON.readTree("{\"n\":1.5}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("PostgreSQL's jsonb text puts a space after every colon and comma, and the size check counts them")
    void jsonbTextSize() {
        // {"a":{"x":"é","y":[1,2]},"b":1,"c":null}  ->  {"a": {"x": "é", "y": [1, 2]}, "b": 1, "c": null}
        String postgres = "{\"a\": {\"x\": \"é\", \"y\": [1, 2]}, \"b\": 1, \"c\": null}";
        assertThat(CanonicalJson.jsonbTextBytes(DOCUMENT))
                .isEqualTo(postgres.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("each of the six values moves the hash, on its own")
    void everyValueMatters() {
        String base = base();
        assertThat(hash(JSON.readTree("{\"b\":2}"), List.of("WEB", "IOS"), START, END, PROMOTION, LOCATION))
                .isNotEqualTo(base);
        assertThat(hash(DOCUMENT, List.of("WEB"), START, END, PROMOTION, LOCATION))
                .isNotEqualTo(base);
        assertThat(hash(DOCUMENT, List.of("WEB", "IOS"), START.plusSeconds(1), END, PROMOTION, LOCATION))
                .isNotEqualTo(base);
        assertThat(hash(DOCUMENT, List.of("WEB", "IOS"), START, END.plusSeconds(7 * 24 * 3600), PROMOTION, LOCATION))
                .isNotEqualTo(base);
        assertThat(hash(DOCUMENT, List.of("WEB", "IOS"), START, END, UUID.randomUUID(), LOCATION))
                .isNotEqualTo(base);
        assertThat(hash(DOCUMENT, List.of("WEB", "IOS"), START, END, PROMOTION, UUID.randomUUID()))
                .isNotEqualTo(base);
        assertThat(hash(DOCUMENT, List.of("WEB", "IOS"), null, END, PROMOTION, LOCATION))
                .isNotEqualTo(base);
        assertThat(hash(DOCUMENT, List.of("WEB", "IOS"), START, null, PROMOTION, LOCATION))
                .isNotEqualTo(base);
        assertThat(hash(DOCUMENT, List.of("WEB", "IOS"), START, END, null, LOCATION))
                .isNotEqualTo(base);
        assertThat(hash(DOCUMENT, List.of("WEB", "IOS"), START, END, PROMOTION, null))
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("the channel set is sorted and de-duplicated, but an empty set is not a list of every channel")
    void channelSets() {
        assertThat(hash(DOCUMENT, List.of("IOS", "WEB", "WEB"), START, END, PROMOTION, LOCATION))
                .isEqualTo(base());
        String everyChannel = hash(DOCUMENT, List.of(), START, END, PROMOTION, LOCATION);
        assertThat(everyChannel)
                .isNotEqualTo(
                        hash(DOCUMENT, List.of("WEB", "IOS", "ANDROID", "TELEGRAM"), START, END, PROMOTION, LOCATION));
    }

    @Test
    @DisplayName("instants are UTC ISO-8601 with microseconds, the precision the database keeps")
    void instants() {
        String text = ContentHash.canonicalText(
                DOCUMENT, List.of(), Instant.parse("2026-10-10T08:00:00.123456789Z"), null, null, null);
        assertThat(text)
                .contains("\"startsAt\":\"2026-10-10T08:00:00.123456Z\"")
                .contains("\"endsAt\":null");
        // Nanoseconds the database cannot hold do not make two equal values differ.
        assertThat(hash(DOCUMENT, List.of(), Instant.parse("2026-10-10T08:00:00.123456789Z"), null, null, null))
                .isEqualTo(hash(DOCUMENT, List.of(), Instant.parse("2026-10-10T08:00:00.123456Z"), null, null, null));
    }

    @Test
    @DisplayName("the hash is 64 lower-case hex characters and is stable across runs")
    void shape() {
        assertThat(base()).matches("^[0-9a-f]{64}$");
        assertThat(base()).isEqualTo(base());
    }
}
