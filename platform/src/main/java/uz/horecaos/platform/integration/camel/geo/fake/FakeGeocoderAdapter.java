package uz.horecaos.platform.integration.camel.geo.fake;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.geo.GeoOperation;
import uz.horecaos.platform.integration.camel.geo.GeocoderAdapter;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.geo.AddressComponents;
import uz.horecaos.platform.tenancy.api.geo.GeoSuggestion;
import uz.horecaos.platform.tenancy.api.geo.GeocodeConfidence;
import uz.horecaos.platform.tenancy.api.geo.GeocodePrecision;
import uz.horecaos.platform.tenancy.api.geo.GeocodeResult;
import uz.horecaos.platform.tenancy.api.geo.MapClientConfig;

/**
 * A controlled stand-in for a map provider, in the ADR 0007 genre of
 * {@code ControlledFakeProvider} and {@code FakeClickHttpProvider} (ADR 0145: "ship the seams
 * with a fake adapter behind the real endpoints, so the console components and the storefront
 * change can be built and tested before a key exists").
 *
 * <p><strong>Fixture data, not a geocoder.</strong> Eleven places in Tashkent and one street
 * of the same name in another country, matched by substring. It invents no result for a query
 * it does not know: an unknown address answers an empty list, which is what a real geocoder
 * says about a nonsense query and what every screen has to handle. The coordinates are
 * approximate and are not to be read as survey data.
 *
 * <p><strong>Deliberately framework-free</strong>, like {@code FakeClickHttpProvider}: no
 * Spring annotation, so a test can {@code new} one and {@code FakeGeocoderConfiguration} is the
 * separate, {@code local}-profile-only glue that registers it. It cannot run in production for
 * the same layered reasons the Click fake cannot: the profile, and the property
 * {@code horecaos.geo.provider} that has to name it.
 *
 * <p><strong>Scenarios carry no switch in production code</strong>, per ADR 0007's rule for
 * {@code ControlledFakeProvider}: a scenario is selected by the text a caller sends, which is
 * ordinary input a test or a developer already controls. {@value #UNAVAILABLE_TEXT},
 * {@value #REFUSED_TEXT} and {@value #EMPTY_TEXT} produce the three non-answers a screen must
 * survive; nothing else in the platform knows a scenario concept exists.
 *
 * <p>The real box check is the gateway's, not this class's: this adapter returns the out-of-box
 * Bishkek street with the confidence a naive provider would give it, so a test through the
 * gateway proves the box was applied on the way back by something other than the fake.
 */
public final class FakeGeocoderAdapter implements GeocoderAdapter {

    public static final String PROVIDER = "FAKE";

    /** A query containing this is answered as a provider outage (retryable). */
    public static final String UNAVAILABLE_TEXT = "fake-scenario-unavailable";

    /** A query containing this is answered as a refused key. */
    public static final String REFUSED_TEXT = "fake-scenario-refused";

    /** A query containing this is answered as a successful search that found nothing. */
    public static final String EMPTY_TEXT = "fake-scenario-empty";

    /** Within this of a fixture a reverse lookup says "that is the place". */
    static final double REVERSE_RADIUS_METRES = 300;

    private record Place(
            String title,
            String subtitle,
            String street,
            @Nullable String house,
            String country,
            String locality,
            GeoPoint point,
            GeocodePrecision precision,
            List<String> aliases) {

        String formatted() {
            return country + ", " + locality + ", " + title;
        }
    }

    private static final List<Place> GAZETTEER = List.of(
            place(
                    "Amir Temur Square",
                    "Tashkent, Uzbekistan",
                    "Amir Temur Square",
                    null,
                    41.3111,
                    69.2797,
                    GeocodePrecision.LOCALITY,
                    "amir temur",
                    "amir temur square",
                    "амира темура",
                    "амир темур"),
            place(
                    "Amir Temur ko'chasi, 107",
                    "Tashkent, Uzbekistan",
                    "Amir Temur ko'chasi",
                    "107",
                    41.3123,
                    69.2845,
                    GeocodePrecision.HOUSE,
                    "amir temur",
                    "amir temur ko'chasi",
                    "107"),
            place(
                    "Amir Temur ko'chasi, 15",
                    "Tashkent, Uzbekistan",
                    "Amir Temur ko'chasi",
                    "15",
                    41.3105,
                    69.2791,
                    GeocodePrecision.HOUSE,
                    "amir temur",
                    "amir temur ko'chasi",
                    "15"),
            place(
                    "Navoi ko'chasi, 28",
                    "Tashkent, Uzbekistan",
                    "Navoi ko'chasi",
                    "28",
                    41.3078,
                    69.2702,
                    GeocodePrecision.HOUSE,
                    "navoi",
                    "навои",
                    "28"),
            place(
                    "Chorsu Bazaar",
                    "Tashkent, Uzbekistan",
                    "Eski Shahar",
                    null,
                    41.3267,
                    69.2349,
                    GeocodePrecision.LOCALITY,
                    "chorsu",
                    "чорсу",
                    "bazaar"),
            place(
                    "Tashkent TV Tower",
                    "Tashkent, Uzbekistan",
                    "Shahrisabz ko'chasi",
                    "1",
                    41.3399,
                    69.2858,
                    GeocodePrecision.HOUSE,
                    "tv tower",
                    "телебашня",
                    "tower",
                    "shahrisabz"),
            place(
                    "Tashkent railway station",
                    "Tashkent, Uzbekistan",
                    "Turkiston ko'chasi",
                    "7",
                    41.2926,
                    69.2875,
                    GeocodePrecision.HOUSE,
                    "railway",
                    "station",
                    "вокзал",
                    "turkiston"),
            place(
                    "Minor Mosque",
                    "Tashkent, Uzbekistan",
                    "Minor ko'chasi",
                    "1",
                    41.3317,
                    69.2724,
                    GeocodePrecision.HOUSE,
                    "minor",
                    "mosque",
                    "мечеть"),
            place(
                    "Mirzo Ulugbek ko'chasi, 3",
                    "Tashkent, Uzbekistan",
                    "Mirzo Ulugbek ko'chasi",
                    "3",
                    41.3382,
                    69.3346,
                    GeocodePrecision.HOUSE,
                    "ulugbek",
                    "улугбек",
                    "mirzo"),
            place(
                    "Yunusobod, 19-mavze",
                    "Tashkent, Uzbekistan",
                    "Yunusobod",
                    null,
                    41.3652,
                    69.2891,
                    GeocodePrecision.LOCALITY,
                    "yunusobod",
                    "юнусабад",
                    "19"),
            place(
                    "Chilonzor, 9-kvartal",
                    "Tashkent, Uzbekistan",
                    "Chilonzor",
                    null,
                    41.2751,
                    69.2043,
                    GeocodePrecision.LOCALITY,
                    "chilonzor",
                    "чиланзар",
                    "9"),
            // The trap ADR 0037 describes: a plausible street of the same name in another
            // country. Returned as a naive provider would, with high confidence, so that it is
            // the gateway's box check that marks it LOW_CONFIDENCE.
            new Place(
                    "Amir Temur ko'chasi, 1",
                    "Bishkek, Kyrgyzstan",
                    "Amir Temur ko'chasi",
                    "1",
                    "Kyrgyzstan",
                    "Bishkek",
                    new GeoPoint(42.8746, 74.6122),
                    GeocodePrecision.HOUSE,
                    List.of("amir temur", "amir temur ko'chasi", "1")));

    private static Place place(
            String title,
            String subtitle,
            String street,
            @Nullable String house,
            double latitude,
            double longitude,
            GeocodePrecision precision,
            String... aliases) {
        return new Place(
                title,
                subtitle,
                street,
                house,
                "Uzbekistan",
                "Tashkent",
                new GeoPoint(latitude, longitude),
                precision,
                List.of(aliases));
    }

    private final Clock clock;

    public FakeGeocoderAdapter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public @Nullable SecretReference credentialReference() {
        return null;
    }

    @Override
    public MapClientConfig clientConfig() {
        // No tiles: there is no key and no vendor script, and a screen has to be honest about it.
        return new MapClientConfig(
                PROVIDER, true, null, List.of("SUGGEST", "GEOCODE", "REVERSE"), "Fixture data, not a real provider");
    }

    @Override
    public ProviderOutcome execute(GeoOperation operation, @Nullable String credential, Duration timeout) {
        String text = operation.text() == null ? "" : operation.text().toLowerCase(Locale.ROOT);
        if (text.contains(UNAVAILABLE_TEXT)) {
            return ProviderOutcome.retryable("PROVIDER_UNAVAILABLE", "scenario", null);
        }
        if (text.contains(REFUSED_TEXT)) {
            return ProviderOutcome.rejected("PROVIDER_AUTHENTICATION", "scenario");
        }
        if (text.contains(EMPTY_TEXT)) {
            return ProviderOutcome.success(Map.of(PAYLOAD_KEY, List.of()), null);
        }
        return switch (operation.kind()) {
            case SUGGEST -> ProviderOutcome.success(Map.of(PAYLOAD_KEY, suggestions(text)), null);
            case GEOCODE ->
                ProviderOutcome.success(
                        Map.of(
                                PAYLOAD_KEY,
                                matches(text).stream().map(this::result).toList()),
                        null);
            case REVERSE -> ProviderOutcome.success(Map.of(PAYLOAD_KEY, reverse(operation.point())), null);
        };
    }

    private List<GeoSuggestion> suggestions(String text) {
        return matches(text).stream()
                .limit(7)
                .map(place -> new GeoSuggestion(
                        place.title(), place.subtitle(), place.formatted(), "fake:" + place.title(), null))
                .toList();
    }

    /** Places whose title or aliases contain the query, most specific (longest alias hit) first. */
    private static List<Place> matches(String text) {
        String needle = text.strip();
        if (needle.length() < 2) {
            return List.of();
        }
        return GAZETTEER.stream()
                .filter(place -> place.title().toLowerCase(Locale.ROOT).contains(needle)
                        || place.formatted().toLowerCase(Locale.ROOT).contains(needle)
                        || place.aliases().stream().anyMatch(alias -> needle.contains(alias) || alias.contains(needle)))
                .sorted(Comparator.comparingInt((Place place) -> -specificity(place, needle)))
                .toList();
    }

    private static int specificity(Place place, String needle) {
        int best = place.title().toLowerCase(Locale.ROOT).contains(needle) ? needle.length() + 100 : 0;
        for (String alias : place.aliases()) {
            if (needle.contains(alias)) {
                best = Math.max(best, alias.length());
            }
        }
        return best;
    }

    private List<GeocodeResult> reverse(@Nullable GeoPoint point) {
        if (point == null) {
            return List.of();
        }
        return GAZETTEER.stream()
                .filter(place -> metres(place.point(), point) <= REVERSE_RADIUS_METRES)
                .min(Comparator.comparingDouble(place -> metres(place.point(), point)))
                .map(place -> List.of(result(place)))
                .orElse(List.of());
    }

    private GeocodeResult result(Place place) {
        return new GeocodeResult(
                place.point(),
                new AddressComponents(
                        place.country().equals("Kyrgyzstan") ? "KG" : "UZ",
                        place.locality(),
                        null,
                        place.street(),
                        place.house(),
                        place.formatted()),
                "fake:" + place.title(),
                // A naive provider: a door is HIGH, anything else MEDIUM, regardless of where it is.
                place.precision() == GeocodePrecision.HOUSE ? GeocodeConfidence.HIGH : GeocodeConfidence.MEDIUM,
                place.precision(),
                clock.instant(),
                PROVIDER);
    }

    /** Equirectangular, which is plenty at city scale and for a fixture. */
    static double metres(GeoPoint a, GeoPoint b) {
        double metresPerDegree = 111_320;
        double dLat = (a.latitude() - b.latitude()) * metresPerDegree;
        double dLon = (a.longitude() - b.longitude())
                * metresPerDegree
                * Math.cos(Math.toRadians((a.latitude() + b.latitude()) / 2));
        return Math.sqrt(dLat * dLat + dLon * dLon);
    }

    /** Test support: the fixtures a caller may query for, so a test does not hard-code a copy. */
    public static List<String> knownTitles() {
        List<String> titles = new ArrayList<>();
        GAZETTEER.forEach(place -> titles.add(place.title()));
        return List.copyOf(titles);
    }

    /** Test support: where a fixture is, or empty. */
    public static Optional<GeoPoint> pointOf(String title) {
        return GAZETTEER.stream()
                .filter(place -> place.title().equals(title))
                .map(Place::point)
                .findFirst();
    }
}
