package uz.horecaos.platform.tenancy.api.geo;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a browser needs to draw a map (ADR 0145 decision 4's {@code map-config}): the provider
 * code, its public browser key, the features this environment offers and the attribution the
 * licence requires.
 *
 * <p>The browser key is public by construction and is delivered by an API read so it can be
 * rotated without a build. It is the one value here a person could call sensitive, and it
 * still is not a secret: the server key never leaves the platform and is not representable
 * in this type.
 *
 * @param provider    {@code YANDEX}, {@code FAKE} or {@code NONE}
 * @param configured  whether anything answers. {@code false} means every lookup is refused and
 *                    the screens must say so rather than show an empty list
 * @param browserKey  the referrer-restricted key for the vendor's map script, absent when the
 *                    provider has no tiles or none is configured
 * @param features    what is available: any of {@code TILES}, {@code SUGGEST}, {@code GEOCODE},
 *                    {@code REVERSE}
 * @param attribution the provider's own attribution text, unmodified
 */
public record MapClientConfig(
        String provider,
        boolean configured,
        @Nullable String browserKey,
        List<String> features,
        @Nullable String attribution) {

    public static final String PROVIDER_NONE = "NONE";

    public MapClientConfig {
        Objects.requireNonNull(provider, "A provider is required");
        features = List.copyOf(features);
    }

    /** Nothing is set up here. */
    public static MapClientConfig notConfigured() {
        return new MapClientConfig(PROVIDER_NONE, false, null, List.of(), null);
    }
}
