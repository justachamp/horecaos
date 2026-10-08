package uz.horecaos.platform.integration.camel.geo.yandex;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.integration.camel.geo.GeoEndpoints;
import uz.horecaos.platform.integration.camel.geo.GeoOperation;
import uz.horecaos.platform.integration.camel.geo.GeocoderAdapter;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.geo.AddressComponents;
import uz.horecaos.platform.tenancy.api.geo.GeoBoundingBox;
import uz.horecaos.platform.tenancy.api.geo.GeoSuggestion;
import uz.horecaos.platform.tenancy.api.geo.GeocodeConfidence;
import uz.horecaos.platform.tenancy.api.geo.GeocodePrecision;
import uz.horecaos.platform.tenancy.api.geo.GeocodeResult;
import uz.horecaos.platform.tenancy.api.geo.MapClientConfig;

/**
 * Yandex Maps, the first map adapter ADR 0145 builds (decision 3): the HTTP Geocoder for
 * {@code geocode} and {@code reverseGeocode}, and the HTTP Suggest service for {@code suggest}.
 * Transcribed in {@code docs/providers/yandex-maps.md}; where this file and that one
 * disagree, that one wins.
 *
 * <p><strong>Nothing works without the key, and says so.</strong> {@link #configured()} is
 * false until {@code horecaos.geo.yandex.secret-reference} names an ADR 0028 reference, and
 * the gateway then answers {@code NOT_CONFIGURED} without a network round trip. The key is the
 * platform's one (ADR 0145 decision 4: one licence, not one per tenant), so this is platform
 * configuration with a secret reference, not an ADR 0026 tenant installation; the approved
 * endpoints are the ADR 0026 environment rows V0490 adds, which is where the base URLs come
 * from.
 *
 * <p><strong>The query string is the provider's and is never logged.</strong> The Geocoder
 * reads the address from {@code ?geocode=} and offers no other way, and the key is in
 * {@code ?apikey=}; {@link ProviderHttpClient#get(ProviderCall, String, Map, Map, java.util.function.Function)}
 * keeps both out of every log line and every outcome. Nothing in this class logs at all, and
 * nothing here retains a request or a response beyond the call.
 *
 * <p><strong>The region box is sent as a bias, not a restriction</strong> ({@code rspn=0},
 * {@code strict_bounds=0}). Restricting would make a result in the wrong city vanish; the
 * contract is that it comes back flagged {@code LOW_CONFIDENCE} so a person sees it was
 * elsewhere, and the gateway checks the box on the way back whatever this class did.
 *
 * <p>Provider answers are read defensively: a missing or oddly-typed field is an unreadable
 * answer, which is {@code UNCERTAIN} and therefore, to a read, "unavailable" — never an
 * exception and never a half-believed result.
 */
@Component
public class YandexGeocoderAdapter implements GeocoderAdapter {

    public static final String PROVIDER = "YANDEX";

    /** ADR 0026 approved environment codes (V0490). */
    public static final String GEOCODER_ENVIRONMENT = "yandex_geocoder_production";

    public static final String SUGGEST_ENVIRONMENT = "yandex_suggest_production";

    static final String GEOCODER_PATH = "/";
    static final String SUGGEST_PATH = "/suggest";
    static final int GEOCODE_RESULTS = 5;
    static final int SUGGEST_RESULTS = 7;

    static final String UNREADABLE = "GEO_RESPONSE_UNREADABLE";

    private final ProviderHttpClient http;
    private final GeoEndpoints endpoints;
    private final Clock clock;
    private final @Nullable SecretReference keyReference;
    private final @Nullable String browserKey;
    private final String attribution;

    @Autowired
    public YandexGeocoderAdapter(
            ProviderHttpClient http,
            GeoEndpoints endpoints,
            Clock clock,
            @Value("${horecaos.geo.yandex.secret-reference:}") String secretReference,
            @Value("${horecaos.geo.yandex.browser-key:}") String browserKey,
            @Value("${horecaos.geo.yandex.attribution:© Яндекс}") String attribution) {
        this.http = http;
        this.endpoints = endpoints;
        this.clock = clock;
        this.keyReference = secretReference == null || secretReference.isBlank()
                ? null
                : SecretReference.parse(secretReference.strip());
        this.browserKey = browserKey == null || browserKey.isBlank() ? null : browserKey.strip();
        this.attribution = attribution;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public boolean configured() {
        return keyReference != null;
    }

    @Override
    public @Nullable SecretReference credentialReference() {
        return keyReference;
    }

    @Override
    public MapClientConfig clientConfig() {
        List<String> features = new ArrayList<>(List.of("SUGGEST", "GEOCODE", "REVERSE"));
        if (browserKey != null) {
            features.add(0, "TILES");
        }
        return new MapClientConfig(PROVIDER, configured(), browserKey, features, attribution);
    }

    @Override
    public ProviderOutcome execute(GeoOperation operation, @Nullable String credential, Duration timeout) {
        if (credential == null || credential.isBlank()) {
            return ProviderOutcome.rejected("GEO_NOT_CONFIGURED", "The Yandex key is not available");
        }
        return switch (operation.kind()) {
            case SUGGEST -> suggest(operation, credential, timeout);
            case GEOCODE -> geocode(operation, credential, timeout);
            case REVERSE -> reverse(operation, credential, timeout);
        };
    }

    // ------------------------------------------------------------------ suggest

    private ProviderOutcome suggest(GeoOperation operation, String key, Duration timeout) {
        Optional<String> base = endpoints.baseUrlOf(SUGGEST_ENVIRONMENT);
        if (base.isEmpty()) {
            return notApproved(SUGGEST_ENVIRONMENT);
        }
        GeoPoint around = operation.point() != null
                ? operation.point()
                : operation.region().centre();

        Map<String, String> query = new LinkedHashMap<>();
        query.put("apikey", key);
        query.put("text", Objects.requireNonNull(operation.text(), "A suggest lookup needs text"));
        query.put("lang", suggestLanguage(operation.locale()));
        query.put("results", String.valueOf(SUGGEST_RESULTS));
        query.put("print_address", "1");
        query.put("types", "geo");
        query.put("attrs", "uri");
        query.put("ll", lonLat(around));
        query.put("bbox", bbox(operation.region().box()));
        query.put("strict_bounds", "0");

        return http.get(
                new ProviderCall(base.get(), key, null, timeout), SUGGEST_PATH, query, Map.of(), this::readSuggestions);
    }

    private ProviderOutcome readSuggestions(Map<String, Object> parsed) {
        if (!(parsed.get("results") instanceof List<?> results)) {
            // Yandex answers an empty search with an empty list; an absent one is a shape we
            // do not know, and believing it would read as "no matches".
            return ProviderOutcome.uncertain(UNREADABLE, "The suggest answer has no results list");
        }
        List<GeoSuggestion> suggestions = new ArrayList<>();
        for (Object entry : results) {
            if (!(entry instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> item = typed(raw);
            String title = text(child(item, "title"), "text");
            if (title == null) {
                continue;
            }
            String subtitle = text(child(item, "subtitle"), "text");
            String formatted = text(child(item, "address"), "formatted_address");
            String fullText = formatted != null ? formatted : subtitle == null ? title : title + ", " + subtitle;
            Object uri = item.get("uri");
            String reference = uri instanceof String value && !value.isBlank()
                    ? value
                    : "yandex-suggest:" + Integer.toHexString(fullText.hashCode());
            suggestions.add(new GeoSuggestion(title, subtitle, fullText, reference, null));
        }
        return ProviderOutcome.success(Map.of(PAYLOAD_KEY, List.copyOf(suggestions)), null);
    }

    // ------------------------------------------------------------------ geocode

    private ProviderOutcome geocode(GeoOperation operation, String key, Duration timeout) {
        Optional<String> base = endpoints.baseUrlOf(GEOCODER_ENVIRONMENT);
        if (base.isEmpty()) {
            return notApproved(GEOCODER_ENVIRONMENT);
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("apikey", key);
        query.put("geocode", Objects.requireNonNull(operation.text(), "A geocode lookup needs text"));
        query.put("format", "json");
        query.put("lang", geocoderLanguage(operation.locale()));
        query.put("results", String.valueOf(GEOCODE_RESULTS));
        query.put("bbox", bbox(operation.region().box()));
        query.put("rspn", "0");
        return get(base.get(), key, timeout, query);
    }

    private ProviderOutcome reverse(GeoOperation operation, String key, Duration timeout) {
        Optional<String> base = endpoints.baseUrlOf(GEOCODER_ENVIRONMENT);
        if (base.isEmpty()) {
            return notApproved(GEOCODER_ENVIRONMENT);
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("apikey", key);
        query.put("geocode", lonLat(Objects.requireNonNull(operation.point(), "A reverse lookup needs a point")));
        query.put("sco", "longlat");
        query.put("kind", "house");
        query.put("format", "json");
        query.put("lang", geocoderLanguage(operation.locale()));
        query.put("results", "1");
        return get(base.get(), key, timeout, query);
    }

    private ProviderOutcome get(String base, String key, Duration timeout, Map<String, String> query) {
        return http.get(new ProviderCall(base, key, null, timeout), GEOCODER_PATH, query, Map.of(), this::readResults);
    }

    private ProviderOutcome readResults(Map<String, Object> parsed) {
        Map<String, Object> collection = child(child(parsed, "response"), "GeoObjectCollection");
        if (collection == null || !(collection.get("featureMember") instanceof List<?> members)) {
            return ProviderOutcome.uncertain(UNREADABLE, "The geocoder answer has no feature list");
        }
        List<GeocodeResult> results = new ArrayList<>();
        for (Object member : members) {
            if (!(member instanceof Map<?, ?> raw)) {
                continue;
            }
            GeocodeResult result = readObject(child(typed(raw), "GeoObject"));
            if (result != null) {
                results.add(result);
            }
        }
        return ProviderOutcome.success(Map.of(PAYLOAD_KEY, List.copyOf(results)), null);
    }

    private @Nullable GeocodeResult readObject(@Nullable Map<String, Object> object) {
        if (object == null) {
            return null;
        }
        GeoPoint point = readPoint(text(child(object, "Point"), "pos"));
        Map<String, Object> meta = child(child(object, "metaDataProperty"), "GeocoderMetaData");
        if (point == null || meta == null) {
            // A candidate we cannot place is dropped rather than placed at zero: a result
            // without a point is not a worse answer, it is no answer.
            return null;
        }
        Map<String, Object> address = child(meta, "Address");
        String formatted =
                firstNonBlank(address == null ? null : stringOf(address.get("formatted")), stringOf(meta.get("text")));
        if (formatted == null) {
            return null;
        }

        GeocodePrecision precision = precision(stringOf(meta.get("precision")), stringOf(meta.get("kind")));
        Object uri = object.get("uri");
        String reference = uri instanceof String value && !value.isBlank()
                ? value
                : "yandex:" + point.longitude() + "," + point.latitude();

        return new GeocodeResult(
                point,
                components(address, formatted),
                reference,
                confidence(precision),
                precision,
                clock.instant(),
                PROVIDER);
    }

    // ---------------------------------------------------------------- mappings

    /**
     * Yandex's {@code precision}: {@code exact} is the building, {@code number} and
     * {@code near} are a neighbouring or interpolated number, {@code range} an interpolated
     * range, {@code street} the street and {@code other} anything else.
     */
    public static GeocodePrecision precision(@Nullable String precision, @Nullable String kind) {
        if (precision != null) {
            switch (precision.toLowerCase(Locale.ROOT)) {
                case "exact":
                    return GeocodePrecision.HOUSE;
                case "number":
                case "near":
                case "range":
                    return GeocodePrecision.NEAR_HOUSE;
                case "street":
                    return GeocodePrecision.STREET;
                default:
                    break;
            }
        }
        if (kind == null) {
            return GeocodePrecision.UNKNOWN;
        }
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "house" -> GeocodePrecision.NEAR_HOUSE;
            case "street" -> GeocodePrecision.STREET;
            case "locality", "district", "province", "area", "country", "metro", "airport" -> GeocodePrecision.LOCALITY;
            default -> GeocodePrecision.UNKNOWN;
        };
    }

    /**
     * Yandex publishes no confidence, so this one is ours and is stated in
     * {@code docs/providers/yandex-maps.md}: only a door is {@code HIGH}; a neighbouring number
     * is {@code MEDIUM}; a street or anything less names no door, and a delivery to it is a
     * delivery to the wrong place, so it is {@code LOW_CONFIDENCE} and a person must confirm it
     * on a map. The region box can only lower it further.
     */
    public static GeocodeConfidence confidence(GeocodePrecision precision) {
        return switch (precision) {
            case HOUSE -> GeocodeConfidence.HIGH;
            case NEAR_HOUSE -> GeocodeConfidence.MEDIUM;
            case STREET, LOCALITY, UNKNOWN -> GeocodeConfidence.LOW_CONFIDENCE;
        };
    }

    private static AddressComponents components(@Nullable Map<String, Object> address, String formatted) {
        String country = null;
        String locality = null;
        String district = null;
        String street = null;
        String house = null;
        if (address != null) {
            country = address.get("country_code") instanceof String code && !code.isBlank() ? code : null;
            if (address.get("Components") instanceof List<?> parts) {
                for (Object part : parts) {
                    if (!(part instanceof Map<?, ?> raw)) {
                        continue;
                    }
                    Map<String, Object> component = typed(raw);
                    String kind = stringOf(component.get("kind"));
                    String name = stringOf(component.get("name"));
                    if (kind == null || name == null) {
                        continue;
                    }
                    switch (kind.toLowerCase(Locale.ROOT)) {
                        case "country" -> country = country != null && country.length() == 2 ? country : name;
                        case "locality" -> locality = locality == null ? name : locality;
                        case "district" -> district = district == null ? name : district;
                        case "street" -> street = name;
                        case "house" -> house = name;
                        default -> {}
                    }
                }
            }
        }
        return new AddressComponents(country, locality, district, street, house, formatted);
    }

    /** {@code "lon lat"} — Yandex writes the longitude first. */
    public static @Nullable GeoPoint readPoint(@Nullable String position) {
        if (position == null) {
            return null;
        }
        String[] parts = position.strip().split("\\s+");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new GeoPoint(Double.parseDouble(parts[1]), Double.parseDouble(parts[0]));
        } catch (IllegalArgumentException outOfRange) {
            // NumberFormatException is one: either way the candidate is unplaceable.
            return null;
        }
    }

    static String lonLat(GeoPoint point) {
        return point.longitude() + "," + point.latitude();
    }

    static String bbox(GeoBoundingBox box) {
        return lonLat(box.southWest()) + "~" + lonLat(box.northEast());
    }

    /**
     * The Geocoder's documented locales are Russian, Ukrainian, Belarusian, English and
     * Turkish; <strong>no Uzbek locale is documented</strong>, so {@code uz-Latn} asks for
     * Russian, whose street names Tashkent addresses are most often written in. The bake-off
     * (ADR 0145 decision 3) is where this is measured.
     */
    static String geocoderLanguage(String locale) {
        return "en".equalsIgnoreCase(locale) ? "en_US" : "ru_RU";
    }

    static String suggestLanguage(String locale) {
        return "en".equalsIgnoreCase(locale) ? "en" : "ru";
    }

    private static ProviderOutcome notApproved(String environment) {
        // No row, no call: the egress catalogue is the allow-list (ADR 0026).
        return ProviderOutcome.rejected("GEO_ENDPOINT_NOT_APPROVED", "No approved endpoint for " + environment);
    }

    // ------------------------------------------------------------------ json

    @SuppressWarnings("unchecked")
    private static Map<String, Object> typed(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    private static @Nullable Map<String, Object> child(@Nullable Map<String, Object> parent, String key) {
        return parent != null && parent.get(key) instanceof Map<?, ?> value ? typed(value) : null;
    }

    private static @Nullable String text(@Nullable Map<String, Object> parent, String key) {
        return parent == null ? null : stringOf(parent.get(key));
    }

    private static @Nullable String stringOf(@Nullable Object value) {
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    private static @Nullable String firstNonBlank(@Nullable String first, @Nullable String second) {
        return first != null ? first : second;
    }
}
