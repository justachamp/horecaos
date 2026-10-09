package uz.horecaos.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The hostile lines this class exists for: what an HTTP server, proxy or client writes when its
 * logger is raised to DEBUG, with the provider key and the customer's address in it (ADR 0029,
 * ADR 0028). The first two are verbatim from the CI failure of 2026-10-09.
 *
 * <p>Each case states the whole expected line, not a "does not contain": a mask that ate the
 * entire line would pass the second kind of assertion, and a mask that left the name off would
 * pass the first kind of reading. The canaries are asserted absent as well, separately, so a
 * wrong expectation in this table cannot be the thing that lets a secret through.
 */
class SensitiveQueryRedactorTests {

    private static final String KEY = "server-key-must-never-be-logged";
    private static final String CANARY = "canary-address";

    static Stream<Arguments> hostileLines() {
        return Stream.of(
                Arguments.of(
                        "the request line the JDK's HTTP server wrote in CI",
                        "Exchange request line: GET /1.x/?apikey=" + KEY
                                + "&geocode=Zaglushka%20ko%27chasi%207%20" + CANARY
                                + "&format=json&lang=ru_RU&results=5&bbox=69.04%2C41.15~69.46%2C41.47&rspn=0 HTTP/1.1",
                        "Exchange request line: GET /1.x/?apikey=[redacted]&geocode=[redacted]"
                                + "&format=json&lang=ru_RU&results=5&bbox=69.04%2C41.15~69.46%2C41.47&rspn=0 HTTP/1.1"),
                Arguments.of(
                        "the reply line, with a reverse lookup's coordinates in geocode=",
                        "GET /1.x/?apikey=" + KEY
                                + "&geocode=69.24567%2C41.31234&sco=longlat&kind=house&format=json [403 Forbidden] ()",
                        "GET /1.x/?apikey=[redacted]&geocode=[redacted]&sco=longlat&kind=house&format=json"
                                + " [403 Forbidden] ()"),
                Arguments.of(
                        "a suggest call: text and the user's position",
                        "GET /v1/suggest?apikey=" + KEY + "&text=Zaglushka%20" + CANARY
                                + "&lang=ru&ll=69.24567%2C41.31234&strict_bounds=0 HTTP/1.1",
                        "GET /v1/suggest?apikey=[redacted]&text=[redacted]&lang=ru&ll=[redacted]&strict_bounds=0"
                                + " HTTP/1.1"),
                Arguments.of(
                        "names in any case",
                        "GET /x?APIKEY=" + KEY + "&GeoCode=" + CANARY + "&Text=" + CANARY,
                        "GET /x?APIKEY=[redacted]&GeoCode=[redacted]&Text=[redacted]"),
                Arguments.of(
                        "a form body that starts with the key",
                        "body: apikey=" + KEY + "&text=" + CANARY,
                        "body: apikey=[redacted]&text=[redacted]"),
                Arguments.of(
                        "a URL inside a JSON string, which ends at the quote",
                        "{\"url\":\"http://h/1.x/?apikey=" + KEY + "&geocode=" + CANARY + "%20x\",\"status\":200}",
                        "{\"url\":\"http://h/1.x/?apikey=[redacted]&geocode=[redacted]\",\"status\":200}"),
                Arguments.of(
                        "a value that ends at a fragment",
                        "http://h/?apikey=" + KEY + "#top",
                        "http://h/?apikey=[redacted]#top"),
                Arguments.of(
                        "an encoded ampersand is part of the address and does not end it",
                        "GET /1.x/?geocode=A%20%26%20" + CANARY + "&format=json HTTP/1.1",
                        "GET /1.x/?geocode=[redacted]&format=json HTTP/1.1"),
                Arguments.of(
                        "a value with characters a regex replacement would treat as special",
                        "GET /1.x/?geocode=a$1b\\c" + CANARY + "&x=1",
                        "GET /1.x/?geocode=[redacted]&x=1"),
                Arguments.of(
                        "the same URL percent-encoded into another one, where %26 separates",
                        "redirect=http%3A%2F%2Fh%2F1.x%2F%3Fapikey%3D" + KEY + "%26geocode%3D" + CANARY
                                + "%26format%3Djson",
                        "redirect=http%3A%2F%2Fh%2F1.x%2F%3Fapikey%3D[redacted]%26geocode%3D[redacted]"
                                + "%26format%3Djson"),
                Arguments.of(
                        "an encoded query parameter that only counts as one after ? or &",
                        "next=http%3A%2F%2Fh%2Fs%3Ftext%3D" + CANARY + "%26ll%3D69.2%2C41.3",
                        "next=http%3A%2F%2Fh%2Fs%3Ftext%3D[redacted]%26ll%3D[redacted]"),
                Arguments.of(
                        "every occurrence in one line, not the first",
                        "retry ?apikey=" + KEY + " after ?apikey=" + KEY + "-2",
                        "retry ?apikey=[redacted] after ?apikey=[redacted]"),
                Arguments.of(
                        "a bare secret in a config dump, with and without a header-style prefix",
                        "horecaos.geo.yandex.apikey=" + KEY + " x-api-key=" + KEY + " access_token=" + KEY,
                        "horecaos.geo.yandex.apikey=[redacted] x-api-key=[redacted] access_token=[redacted]"),
                Arguments.of(
                        "key as a query parameter",
                        "GET /maps?key=" + KEY + "&q=v HTTP/1.1",
                        "GET /maps?key=[redacted]&q=v HTTP/1.1"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hostileLines")
    @DisplayName("a hostile line keeps its shape and loses the secret and the person")
    void aHostileLineIsMasked(String description, String line, String expected) {
        String masked = SensitiveQueryRedactor.redact(line);

        assertThat(masked).isEqualTo(expected);
        assertThat(masked).doesNotContain(KEY).doesNotContain(CANARY);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ordinaryLines")
    @DisplayName("an ordinary line is returned as the very same object")
    void anOrdinaryLineIsLeftAlone(String line) {
        assertThat(SensitiveQueryRedactor.redact(line)).isSameAs(line);
    }

    static Stream<String> ordinaryLines() {
        return Stream.of(
                "Order paid",
                "",
                "order id=7 status=PAID",
                "monkey=banana",
                "Customer text=Hello",
                "key=value at the start of a line is not a query parameter",
                "GET /api/orders?status=PAID&page=2 HTTP/1.1",
                "bbox=69.04%2C41.15~69.46%2C41.47",
                "100%30 percent");
    }

    @Test
    @DisplayName("null stays null")
    void nullIsNull() {
        assertThat(SensitiveQueryRedactor.redact(null)).isNull();
    }
}
