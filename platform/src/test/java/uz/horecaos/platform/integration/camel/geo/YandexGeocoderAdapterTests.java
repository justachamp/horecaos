package uz.horecaos.platform.integration.camel.geo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.integration.camel.geo.yandex.YandexGeocoderAdapter;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.geo.GeoBoundingBox;
import uz.horecaos.platform.tenancy.api.geo.GeoRegion;
import uz.horecaos.platform.tenancy.api.geo.GeoSuggestion;
import uz.horecaos.platform.tenancy.api.geo.GeocodeConfidence;
import uz.horecaos.platform.tenancy.api.geo.GeocodePrecision;
import uz.horecaos.platform.tenancy.api.geo.GeocodeResult;
import uz.horecaos.platform.tenancy.api.geo.MapClientConfig;

/**
 * What the Yandex adapter puts on the wire and what it concludes from what comes back
 * (ADR 0145), against the recorded-shape fixtures in {@code src/test/resources/geo/yandex}
 * and the documentation transcribed in {@code docs/providers/yandex-maps.md}.
 *
 * <p>The fixtures are transcribed from the documented response shape, not captured from the
 * live service: no key exists. That limits what this proves to "the adapter reads the shape the
 * documentation describes", which is stated here so a green run is not read as "Yandex works".
 */
class YandexGeocoderAdapterTests {

    private static final String KEY = "server-key-must-never-be-logged";
    private static final String REFERENCE = "horecaos:test:provider_geocoding:platform:yandex";
    private static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");
    private static final GeoRegion TASHKENT = new GeoRegion(
            UUID.fromString("018f9b10-4000-7000-8000-000000000001"),
            "TASHKENT",
            new GeoPoint(41.31, 69.24),
            new GeoBoundingBox(new GeoPoint(41.15, 69.04), new GeoPoint(41.47, 69.46)));

    private final ProviderHttpClient http =
            new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier());

    private YandexGeocoderAdapter adapter(RecordingGeoProvider provider, String secretReference, String browserKey) {
        return new YandexGeocoderAdapter(
                http, provider.endpoints(), Clock.fixed(NOW, ZoneOffset.UTC), secretReference, browserKey, "© Яндекс");
    }

    private YandexGeocoderAdapter adapter(RecordingGeoProvider provider) {
        return adapter(provider, REFERENCE, "browser-key-is-public");
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> payload(ProviderOutcome outcome) {
        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.SUCCESS);
        return (List<T>) Objects.requireNonNull(outcome.normalized().get(GeocoderAdapter.PAYLOAD_KEY));
    }

    // ------------------------------------------------------------------ geocode

    @Test
    @DisplayName("a geocode sends the box as a bias, the longitude first, and the key as the provider's own parameter")
    void aGeocodeRequestCarriesTheDocumentedParameters() throws Exception {
        try (RecordingGeoProvider provider = RecordingGeoProvider.start()) {
            provider.replyFixture(RecordingGeoProvider.GEOCODER_PATH, "geocode-house.json");

            adapter(provider)
                    .execute(GeoOperation.geocode("Fixture ko'chasi 12", TASHKENT, "ru"), KEY, Duration.ofSeconds(2));

            RecordingGeoProvider.Call call = provider.lastCallTo(RecordingGeoProvider.GEOCODER_PATH);
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.param("apikey")).isEqualTo(KEY);
            assertThat(call.param("geocode")).isEqualTo("Fixture ko'chasi 12");
            assertThat(call.param("format")).isEqualTo("json");
            assertThat(call.param("lang")).isEqualTo("ru_RU");
            // South-west first, and longitude before latitude: Yandex's order, and the one a
            // hand-written "lat,lon" would silently get wrong.
            assertThat(call.param("bbox")).isEqualTo("69.04,41.15~69.46,41.47");
            // A bias, not a restriction: a restriction would make a wrong-city result vanish
            // instead of arriving flagged LOW_CONFIDENCE.
            assertThat(call.param("rspn")).isEqualTo("0");
        }
    }

    @Test
    @DisplayName("a door is HIGH, a street is LOW_CONFIDENCE, and the components are normalized")
    void aGeocodeAnswerIsNormalized() throws Exception {
        try (RecordingGeoProvider provider = RecordingGeoProvider.start()) {
            provider.replyFixture(RecordingGeoProvider.GEOCODER_PATH, "geocode-house.json");

            ProviderOutcome outcome = adapter(provider)
                    .execute(GeoOperation.geocode("Fixture ko'chasi 12", TASHKENT, "ru"), KEY, Duration.ofSeconds(2));

            List<GeocodeResult> results = payload(outcome);
            assertThat(results).hasSize(2);

            GeocodeResult house = results.get(0);
            // "pos" is "lon lat": the point must come out the right way round.
            assertThat(house.point()).isEqualTo(new GeoPoint(41.3111, 69.2797));
            assertThat(house.precision()).isEqualTo(GeocodePrecision.HOUSE);
            assertThat(house.confidence()).isEqualTo(GeocodeConfidence.HIGH);
            assertThat(house.components().country()).isEqualTo("UZ");
            assertThat(house.components().locality()).isEqualTo("Ташкент");
            assertThat(house.components().district()).isEqualTo("Fixture tumani");
            assertThat(house.components().street()).isEqualTo("Fixture ko'chasi");
            assertThat(house.components().house()).isEqualTo("12");
            assertThat(house.components().formatted()).isEqualTo("Узбекистан, Ташкент, Fixture ko'chasi, 12");
            assertThat(house.resolvedAt()).isEqualTo(NOW);
            assertThat(house.provider()).isEqualTo("YANDEX");

            // A street names no door; a delivery to it is a delivery to the wrong place.
            GeocodeResult street = results.get(1);
            assertThat(street.precision()).isEqualTo(GeocodePrecision.STREET);
            assertThat(street.confidence()).isEqualTo(GeocodeConfidence.LOW_CONFIDENCE);
            assertThat(street.components().house()).isNull();
        }
    }

    @Test
    @DisplayName("nothing found is a successful, empty answer and not a failure")
    void anEmptyAnswerIsSuccess() throws Exception {
        try (RecordingGeoProvider provider = RecordingGeoProvider.start()) {
            provider.replyFixture(RecordingGeoProvider.GEOCODER_PATH, "geocode-empty.json");

            ProviderOutcome outcome = adapter(provider)
                    .execute(GeoOperation.geocode("nothing here", TASHKENT, "ru"), KEY, Duration.ofSeconds(2));

            assertThat(payload(outcome)).isEmpty();
        }
    }

    @Test
    @DisplayName("a candidate with no point is dropped, never placed at zero")
    void anUnplaceableCandidateIsDropped() throws Exception {
        try (RecordingGeoProvider provider = RecordingGeoProvider.start()) {
            provider.replyFixture(RecordingGeoProvider.GEOCODER_PATH, "geocode-unplaceable.json");

            ProviderOutcome outcome = adapter(provider)
                    .execute(GeoOperation.geocode("Fixture ko'chasi 14", TASHKENT, "ru"), KEY, Duration.ofSeconds(2));

            List<GeocodeResult> results = payload(outcome);
            assertThat(results).hasSize(1);
            assertThat(results.get(0).precision()).isEqualTo(GeocodePrecision.NEAR_HOUSE);
            assertThat(results.get(0).confidence()).isEqualTo(GeocodeConfidence.MEDIUM);
        }
    }

    @Test
    @DisplayName("an answer in a shape the documentation does not describe is uncertain, never an empty list")
    void anUnexpectedShapeIsNotBelievedAsNoMatches() throws Exception {
        try (RecordingGeoProvider provider = RecordingGeoProvider.start()) {
            provider.replyFixture(RecordingGeoProvider.GEOCODER_PATH, "geocode-unexpected-shape.json");

            ProviderOutcome outcome = adapter(provider)
                    .execute(GeoOperation.geocode("anything", TASHKENT, "ru"), KEY, Duration.ofSeconds(2));

            // Reading it as "no matches" would tell the operator the address does not exist.
            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
            assertThat(outcome.errorCode()).isEqualTo("GEO_RESPONSE_UNREADABLE");
        }
    }

    @Test
    @DisplayName("a body that is not JSON is uncertain")
    void aMalformedBodyIsUncertain() throws Exception {
        try (RecordingGeoProvider provider = RecordingGeoProvider.start()) {
            provider.reply(RecordingGeoProvider.GEOCODER_PATH, 200, "<html>maintenance</html>");

            ProviderOutcome outcome = adapter(provider)
                    .execute(GeoOperation.geocode("anything", TASHKENT, "ru"), KEY, Duration.ofSeconds(2));

            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
        }
    }

    // ------------------------------------------------------------------ reverse

    @Test
    @DisplayName("a reverse lookup sends longitude first and asks for the house")
    void aReverseRequestCarriesTheDocumentedParameters() throws Exception {
        try (RecordingGeoProvider provider = RecordingGeoProvider.start()) {
            provider.replyFixture(RecordingGeoProvider.GEOCODER_PATH, "reverse-house.json");

            ProviderOutcome outcome = adapter(provider)
                    .execute(
                            GeoOperation.reverse(new GeoPoint(41.3111, 69.2797), TASHKENT, "en"),
                            KEY,
                            Duration.ofSeconds(2));

            RecordingGeoProvider.Call call = provider.lastCallTo(RecordingGeoProvider.GEOCODER_PATH);
            assertThat(call.param("geocode")).isEqualTo("69.2797,41.3111");
            assertThat(call.param("sco")).isEqualTo("longlat");
            assertThat(call.param("kind")).isEqualTo("house");
            assertThat(call.param("lang")).isEqualTo("en_US");
            assertThat(payload(outcome)).hasSize(1);
        }
    }

    // ------------------------------------------------------------------ suggest

    @Test
    @DisplayName("a suggest carries the box as a bias and ranks toward the person's own point")
    void aSuggestRequestCarriesTheDocumentedParameters() throws Exception {
        try (RecordingGeoProvider provider = RecordingGeoProvider.start()) {
            provider.replyFixture(RecordingGeoProvider.SUGGEST_PATH, "suggest.json");

            adapter(provider)
                    .execute(
                            GeoOperation.suggest("fixt", TASHKENT, new GeoPoint(41.33, 69.25), "uz-Latn"),
                            KEY,
                            Duration.ofSeconds(2));

            RecordingGeoProvider.Call call = provider.lastCallTo(RecordingGeoProvider.SUGGEST_PATH);
            assertThat(call.param("apikey")).isEqualTo(KEY);
            assertThat(call.param("text")).isEqualTo("fixt");
            assertThat(call.param("ll")).isEqualTo("69.25,41.33");
            assertThat(call.param("bbox")).isEqualTo("69.04,41.15~69.46,41.47");
            assertThat(call.param("strict_bounds")).isEqualTo("0");
            // No Uzbek locale is documented; uz-Latn asks for Russian, and the doc says so.
            assertThat(call.param("lang")).isEqualTo("ru");
        }
    }

    @Test
    @DisplayName("a suggest with no point of the person's own ranks toward the region's centre")
    void aSuggestWithoutNearUsesTheRegionCentre() throws Exception {
        try (RecordingGeoProvider provider = RecordingGeoProvider.start()) {
            provider.replyFixture(RecordingGeoProvider.SUGGEST_PATH, "suggest.json");

            adapter(provider).execute(GeoOperation.suggest("fixt", TASHKENT, null, "ru"), KEY, Duration.ofSeconds(2));

            assertThat(provider.lastCallTo(RecordingGeoProvider.SUGGEST_PATH).param("ll"))
                    .isEqualTo("69.24,41.31");
        }
    }

    @Test
    @DisplayName("suggestions carry the full address to resolve, no point, and drop a line with no title")
    void aSuggestAnswerIsNormalized() throws Exception {
        try (RecordingGeoProvider provider = RecordingGeoProvider.start()) {
            provider.replyFixture(RecordingGeoProvider.SUGGEST_PATH, "suggest.json");

            ProviderOutcome outcome = adapter(provider)
                    .execute(GeoOperation.suggest("fixt", TASHKENT, null, "ru"), KEY, Duration.ofSeconds(2));

            List<GeoSuggestion> suggestions = payload(outcome);
            assertThat(suggestions).hasSize(2);
            assertThat(suggestions.get(0).title()).isEqualTo("Fixture ko'chasi");
            assertThat(suggestions.get(0).subtitle()).isEqualTo("Ташкент, Узбекистан");
            assertThat(suggestions.get(0).fullText()).isEqualTo("Узбекистан, Ташкент, Fixture ko'chasi");
            assertThat(suggestions.get(0).providerReference()).isEqualTo("ymapsbm1://geo?data=FixtureOne");
            assertThat(suggestions.get(0).point())
                    .as("the provider's suggest answer carries no coordinates, and none is invented")
                    .isNull();
            // No formatted address on this line: the full text is built from title and subtitle.
            assertThat(suggestions.get(1).fullText()).isEqualTo("Fixture ko'chasi, 12, Ташкент, Узбекистан");
        }
    }

    @Test
    @DisplayName("an empty suggest list is an answer; an absent one is not")
    void anEmptySuggestListIsSuccessAndAMissingOneIsNot() throws Exception {
        try (RecordingGeoProvider provider = RecordingGeoProvider.start()) {
            provider.replyFixture(RecordingGeoProvider.SUGGEST_PATH, "suggest-empty.json");
            provider.replyFixture(RecordingGeoProvider.SUGGEST_PATH, "suggest-unexpected-shape.json");
            YandexGeocoderAdapter adapter = adapter(provider);
            GeoOperation operation = GeoOperation.suggest("fixt", TASHKENT, null, "ru");

            assertThat((List<?>) payload(adapter.execute(operation, KEY, Duration.ofSeconds(2))))
                    .isEmpty();
            ProviderOutcome missing = adapter.execute(operation, KEY, Duration.ofSeconds(2));
            assertThat(missing.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
        }
    }

    // ------------------------------------------------------------------ failures

    @Test
    @DisplayName("a rejected key is the platform's authentication code, so the gateway refreshes it once")
    void aRejectedKeyIsAnAuthenticationFailure() throws Exception {
        try (RecordingGeoProvider provider = RecordingGeoProvider.start()) {
            provider.reply(
                    RecordingGeoProvider.GEOCODER_PATH,
                    403,
                    "{\"statusCode\":403,\"error\":\"Forbidden\",\"message\":\"Invalid key\"}");

            ProviderOutcome outcome = adapter(provider)
                    .execute(GeoOperation.geocode("anything", TASHKENT, "ru"), KEY, Duration.ofSeconds(2));

            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
            assertThat(outcome.errorCode()).isEqualTo("PROVIDER_AUTHENTICATION");
        }
    }

    @Test
    @DisplayName("the provider's rate limit and a server fault are retryable, never refusals")
    void rateLimitAndServerFaultAreRetryable() throws Exception {
        try (RecordingGeoProvider provider = RecordingGeoProvider.start()) {
            provider.reply(RecordingGeoProvider.GEOCODER_PATH, 429, "{\"message\":\"too many\"}");
            provider.reply(RecordingGeoProvider.GEOCODER_PATH, 503, "{\"message\":\"down\"}");
            YandexGeocoderAdapter adapter = adapter(provider);
            GeoOperation operation = GeoOperation.geocode("anything", TASHKENT, "ru");

            assertThat(adapter.execute(operation, KEY, Duration.ofSeconds(2)).status())
                    .isEqualTo(ProviderOutcome.Status.RETRYABLE);
            assertThat(adapter.execute(operation, KEY, Duration.ofSeconds(2)).status())
                    .isEqualTo(ProviderOutcome.Status.RETRYABLE);
        }
    }

    @Test
    @DisplayName("an accepted-then-lost reply is uncertain, and the adapter does not repeat the call")
    void aLostReplyIsUncertainAndNotRepeated() throws Exception {
        try (RecordingGeoProvider provider = RecordingGeoProvider.start()) {
            provider.replyFixture(RecordingGeoProvider.GEOCODER_PATH, "geocode-house.json");
            provider.stallAfterReceiving(RecordingGeoProvider.GEOCODER_PATH, 600);

            ProviderOutcome outcome = adapter(provider)
                    .execute(GeoOperation.geocode("anything", TASHKENT, "ru"), KEY, Duration.ofMillis(150));

            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.UNCERTAIN);
            assertThat(provider.callsTo(RecordingGeoProvider.GEOCODER_PATH))
                    .as("a lost answer is never re-asked by the adapter; the person asking again is the retry")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("no key value, no call")
    void noKeyMeansNoRequest() throws Exception {
        try (RecordingGeoProvider provider = RecordingGeoProvider.start()) {
            ProviderOutcome outcome = adapter(provider)
                    .execute(GeoOperation.geocode("anything", TASHKENT, "ru"), null, Duration.ofSeconds(2));

            assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
            assertThat(outcome.errorCode()).isEqualTo("GEO_NOT_CONFIGURED");
            assertThat(provider.calls()).isEmpty();
        }
    }

    @Test
    @DisplayName("an environment with no approved row is unreachable, not guessed")
    void anUnapprovedEndpointIsNotCalled() throws Exception {
        try (RecordingGeoProvider provider = RecordingGeoProvider.start()) {
            YandexGeocoderAdapter adapter = new YandexGeocoderAdapter(
                    http,
                    code -> java.util.Optional.empty(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    REFERENCE,
                    "",
                    "© Яндекс");

            ProviderOutcome outcome =
                    adapter.execute(GeoOperation.geocode("anything", TASHKENT, "ru"), KEY, Duration.ofSeconds(2));

            assertThat(outcome.errorCode()).isEqualTo("GEO_ENDPOINT_NOT_APPROVED");
            assertThat(provider.calls()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ configuration

    @Test
    @DisplayName("without a key reference the adapter is not configured, and says so")
    void configuredFollowsTheKeyReference() throws Exception {
        try (RecordingGeoProvider provider = RecordingGeoProvider.start()) {
            assertThat(adapter(provider, "", "").configured()).isFalse();
            assertThat(adapter(provider, "  ", "").configured()).isFalse();
            assertThat(adapter(provider).configured()).isTrue();
            assertThat(String.valueOf(adapter(provider).credentialReference())).isEqualTo(REFERENCE);
        }
    }

    @Test
    @DisplayName("tiles are offered only when a browser key is, and the server key is nowhere in the client config")
    void clientConfigOffersTilesOnlyWithABrowserKey() throws Exception {
        try (RecordingGeoProvider provider = RecordingGeoProvider.start()) {
            MapClientConfig withTiles = adapter(provider).clientConfig();
            assertThat(withTiles.provider()).isEqualTo("YANDEX");
            assertThat(withTiles.configured()).isTrue();
            assertThat(withTiles.browserKey()).isEqualTo("browser-key-is-public");
            assertThat(withTiles.features()).containsExactly("TILES", "SUGGEST", "GEOCODE", "REVERSE");
            assertThat(withTiles.attribution()).isEqualTo("© Яндекс");
            assertThat(withTiles.toString()).doesNotContain(KEY).doesNotContain(REFERENCE);

            MapClientConfig noTiles = adapter(provider, REFERENCE, "").clientConfig();
            assertThat(noTiles.browserKey()).isNull();
            assertThat(noTiles.features()).containsExactly("SUGGEST", "GEOCODE", "REVERSE");
        }
    }

    @Test
    @DisplayName(
            "precision and confidence follow the documented mapping, including for values the provider did not list")
    void precisionAndConfidenceMapping() {
        assertThat(YandexGeocoderAdapter.precision("exact", "house")).isEqualTo(GeocodePrecision.HOUSE);
        assertThat(YandexGeocoderAdapter.precision("number", "house")).isEqualTo(GeocodePrecision.NEAR_HOUSE);
        assertThat(YandexGeocoderAdapter.precision("near", "house")).isEqualTo(GeocodePrecision.NEAR_HOUSE);
        assertThat(YandexGeocoderAdapter.precision("range", "house")).isEqualTo(GeocodePrecision.NEAR_HOUSE);
        assertThat(YandexGeocoderAdapter.precision("street", "street")).isEqualTo(GeocodePrecision.STREET);
        assertThat(YandexGeocoderAdapter.precision("other", "locality")).isEqualTo(GeocodePrecision.LOCALITY);
        assertThat(YandexGeocoderAdapter.precision("other", "somethingnew")).isEqualTo(GeocodePrecision.UNKNOWN);
        assertThat(YandexGeocoderAdapter.precision(null, null)).isEqualTo(GeocodePrecision.UNKNOWN);

        assertThat(YandexGeocoderAdapter.confidence(GeocodePrecision.HOUSE)).isEqualTo(GeocodeConfidence.HIGH);
        assertThat(YandexGeocoderAdapter.confidence(GeocodePrecision.NEAR_HOUSE))
                .isEqualTo(GeocodeConfidence.MEDIUM);
        assertThat(YandexGeocoderAdapter.confidence(GeocodePrecision.STREET))
                .isEqualTo(GeocodeConfidence.LOW_CONFIDENCE);
        assertThat(YandexGeocoderAdapter.confidence(GeocodePrecision.LOCALITY))
                .isEqualTo(GeocodeConfidence.LOW_CONFIDENCE);
        assertThat(YandexGeocoderAdapter.confidence(GeocodePrecision.UNKNOWN))
                .isEqualTo(GeocodeConfidence.LOW_CONFIDENCE);
    }

    @Test
    @DisplayName("a position that is not two numbers in range is unplaceable, not zero")
    void aBadPositionIsUnplaceable() {
        assertThat(YandexGeocoderAdapter.readPoint("69.2797 41.3111")).isEqualTo(new GeoPoint(41.3111, 69.2797));
        assertThat(YandexGeocoderAdapter.readPoint("69.2797")).isNull();
        assertThat(YandexGeocoderAdapter.readPoint("a b")).isNull();
        assertThat(YandexGeocoderAdapter.readPoint("69.2797 941.3111")).isNull();
        assertThat(YandexGeocoderAdapter.readPoint("NaN NaN")).isNull();
        assertThat(YandexGeocoderAdapter.readPoint(null)).isNull();
    }
}
