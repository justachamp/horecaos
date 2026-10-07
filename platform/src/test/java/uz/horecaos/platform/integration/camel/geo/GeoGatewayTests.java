package uz.horecaos.platform.integration.camel.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.geo.fake.FakeGeocoderAdapter;
import uz.horecaos.platform.integration.camel.geo.fake.FakeGeocoderConfiguration;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.geo.GeoBoundingBox;
import uz.horecaos.platform.tenancy.api.geo.GeoRegion;
import uz.horecaos.platform.tenancy.api.geo.GeocodeConfidence;
import uz.horecaos.platform.tenancy.api.geo.GeocodeResult;
import uz.horecaos.platform.tenancy.api.geo.MapClientConfig;

/** Which adapter a gateway runs, what it does when there is none, and what the fake will and will not say. */
class GeoGatewayTests {

    private static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");
    private static final GeoRegion TASHKENT = new GeoRegion(
            UUID.fromString("018f9b10-4000-7000-8000-000000000001"),
            "TASHKENT",
            new GeoPoint(41.31, 69.24),
            new GeoBoundingBox(new GeoPoint(41.15, 69.04), new GeoPoint(41.47, 69.46)));

    private static final SecretResolver NO_SECRETS = new SecretResolver() {
        @Override
        public SecretValue resolve(SecretReference reference) {
            throw new AssertionError("A gateway with nothing to call must not read a secret");
        }

        @Override
        public SecretValue resolveFresh(SecretReference reference) {
            throw new AssertionError("A gateway with nothing to call must not read a secret");
        }
    };

    private static GeoGateway gateway(String provider, GeocoderAdapter... adapters) {
        return new GeoGateway(
                List.of(adapters), provider, NO_SECRETS, new GeoCircuitBreaker(new SimpleMeterRegistry()));
    }

    private static FakeGeocoderAdapter fake() {
        return new FakeGeocoderAdapter(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** An adapter that exists but has no key, as Yandex is before the owner has one. */
    private static GeocoderAdapter unkeyed() {
        return new GeocoderAdapter() {
            @Override
            public String provider() {
                return "YANDEX";
            }

            @Override
            public boolean configured() {
                return false;
            }

            @Override
            public @Nullable SecretReference credentialReference() {
                return null;
            }

            @Override
            public MapClientConfig clientConfig() {
                return new MapClientConfig("YANDEX", false, "must-not-be-offered", List.of("TILES"), "x");
            }

            @Override
            public ProviderOutcome execute(GeoOperation operation, @Nullable String credential, Duration timeout) {
                throw new AssertionError("An unconfigured adapter must never be called");
            }
        };
    }

    @Test
    @DisplayName(
            "naming a provider that is not registered is 'not configured', which is what production says of `fake`")
    void aNamedProviderWithNoAdapterIsNotConfigured() {
        // `fake` is registered only under the local profile; in production nothing answers to it.
        GeoGateway gateway = gateway("fake");

        ProviderOutcome outcome = gateway.call(GeoOperation.geocode("navoi", TASHKENT, "ru"));

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
        assertThat(outcome.errorCode()).isEqualTo("GEO_NOT_CONFIGURED");
        assertThat(gateway.clientConfig().configured()).isFalse();
        assertThat(gateway.providerLabel()).isEqualTo("none");
    }

    @Test
    @DisplayName("the adapter is picked by name, whatever the case, and the others are never asked")
    void selectionIsByNameAndCaseInsensitive() {
        FakeGeocoderAdapter fake = fake();

        assertThat(gateway("FAKE", unkeyed(), fake).active()).containsSame(fake);
        assertThat(gateway("  Fake ", unkeyed(), fake).active()).containsSame(fake);
        assertThat(gateway("yandex", fake).active()).isEmpty();
        assertThat(gateway("none", fake).active()).isEmpty();
    }

    @Test
    @DisplayName("an adapter with no key is not called and is not offered to the browser, even though it is named")
    void anUnkeyedAdapterIsNotConfigured() {
        GeoGateway gateway = gateway("yandex", unkeyed());

        assertThat(gateway.call(GeoOperation.suggest("navoi", TASHKENT, null, "ru"))
                        .errorCode())
                .isEqualTo("GEO_NOT_CONFIGURED");
        MapClientConfig config = gateway.clientConfig();
        assertThat(config.configured()).isFalse();
        assertThat(config.browserKey())
                .as("a browser key is never offered for a provider that cannot answer")
                .isNull();
        assertThat(config.features()).isEmpty();
    }

    @Test
    @DisplayName("the fake exists only under the local profile")
    void theFakeIsRegisteredOnlyLocally() {
        Profile profile = FakeGeocoderConfiguration.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("local");
    }

    // ----------------------------------------------------------------------- the fake

    @Test
    @DisplayName("the fake invents nothing: an unknown address is an empty answer, and a far point is nothing")
    void theFakeAnswersOnlyWhatItKnows() {
        FakeGeocoderAdapter fake = fake();

        ProviderOutcome unknown =
                fake.execute(GeoOperation.geocode("zzz qqq nowhere", TASHKENT, "ru"), null, Duration.ofSeconds(1));
        assertThat(unknown.status()).isEqualTo(ProviderOutcome.Status.SUCCESS);
        assertThat((List<?>) unknown.normalized().get(GeocoderAdapter.PAYLOAD_KEY))
                .isEmpty();

        ProviderOutcome faraway = fake.execute(
                GeoOperation.reverse(new GeoPoint(41.20, 69.10), TASHKENT, "ru"), null, Duration.ofSeconds(1));
        assertThat((List<?>) faraway.normalized().get(GeocoderAdapter.PAYLOAD_KEY))
                .isEmpty();

        assertThat(fake.configured()).isTrue();
        assertThat(fake.credentialReference()).isNull();
        assertThat(fake.clientConfig().features())
                .as("no tiles: the fake has no key and no map")
                .doesNotContain("TILES");
        assertThat(fake.clientConfig().browserKey()).isNull();
    }

    @Test
    @DisplayName("the fake's own fixtures lie where they say, so a test using them is not testing the fixtures")
    void theFakesFixturesAreInTheRegionExceptTheTrap() {
        FakeGeocoderAdapter fake = fake();

        ProviderOutcome all =
                fake.execute(GeoOperation.geocode("amir temur ko'chasi", TASHKENT, "ru"), null, Duration.ofSeconds(1));
        @SuppressWarnings("unchecked")
        List<GeocodeResult> results = (List<GeocodeResult>)
                java.util.Objects.requireNonNull(all.normalized().get(GeocoderAdapter.PAYLOAD_KEY));

        assertThat(results).hasSizeGreaterThanOrEqualTo(3);
        long outside = results.stream()
                .filter(r -> !TASHKENT.box().contains(r.point()))
                .count();
        assertThat(outside)
                .as("exactly the one trap street, in another country")
                .isEqualTo(1);
        assertThat(results.stream().filter(r -> !TASHKENT.box().contains(r.point())))
                .allSatisfy(r -> assertThat(r.confidence())
                        .as("the fake is naive on purpose: the gateway, not the fake, flags it")
                        .isEqualTo(GeocodeConfidence.HIGH));
        assertThat(FakeGeocoderAdapter.knownTitles()).contains("Navoi ko'chasi, 28");
        assertThat(FakeGeocoderAdapter.pointOf("Navoi ko'chasi, 28")).isPresent();
    }

    // ----------------------------------------------------------------- the operation

    @Test
    @DisplayName("an operation cannot be built without what it needs, and prints nothing of what it carries")
    void anOperationIsValidAndRedacted() {
        assertThatThrownBy(() -> GeoOperation.geocode("  ", TASHKENT, "ru"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeoOperation(GeoOperation.Kind.REVERSE, null, null, TASHKENT, "ru"))
                .isInstanceOf(IllegalArgumentException.class);

        GeoOperation operation = GeoOperation.geocode("Zaglushka ko'chasi 7", TASHKENT, "ru");
        assertThat(operation.toString()).doesNotContain("Zaglushka").contains("GEOCODE");
        assertThat(GeoOperation.reverse(new GeoPoint(41.31234, 69.24567), TASHKENT, "ru")
                        .toString())
                .doesNotContain("41.31234")
                .doesNotContain("69.24567");
        assertThat(GeoOperation.Kind.SUGGEST.label()).isEqualTo("suggest");
    }
}
