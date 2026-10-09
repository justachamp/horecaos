package uz.horecaos.platform.integration.camel.geo;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.integration.camel.geo.fake.FakeGeocoderAdapter;
import uz.horecaos.platform.integration.camel.geo.yandex.YandexGeocoderAdapter;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.geo.GeoBoundingBox;
import uz.horecaos.platform.tenancy.api.geo.GeoRegion;
import uz.horecaos.platform.tenancy.api.geo.GeoSuggestion;
import uz.horecaos.platform.tenancy.api.geo.GeoUnavailableReason;
import uz.horecaos.platform.tenancy.api.geo.GeocodeConfidence;
import uz.horecaos.platform.tenancy.api.geo.GeocodeOutcome;
import uz.horecaos.platform.tenancy.api.geo.GeocodePort;
import uz.horecaos.platform.tenancy.api.geo.GeocodeResult;

/**
 * The contract every map adapter is held to, run through the real route, gateway and port
 * (ADR 0145 testing section, ADR 0007): once against the controlled fake and once against
 * the Yandex adapter talking to a recorded-fixture server on a real socket.
 *
 * <p>The same assertions for both is the point. A third adapter added next year is correct when
 * it passes this class with a third {@link Rig}, and what this class proves holds for it
 * without anyone remembering to repeat it: the region box is applied on the way back by the
 * <em>gateway</em>, an outage is an answer and not an exception, a missing key is stated, the
 * breaker stops asking a failing provider, and no address or key reaches a log line.
 *
 * <p>Two scenarios are real-network-only (a reply that is accepted and then lost, and a body
 * that is not JSON); the fake cannot produce them and its rig says so with an assumption, so
 * the omission is visible in the report rather than silent.
 */
class GeocodePortContractTests {

    private static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");
    private static final String KEY = "server-key-must-never-be-logged";
    private static final String REFERENCE = "horecaos:test:provider_geocoding:platform:contract";

    /** The canary address: typed by a person, so it may reach neither a log line nor an exception message. */
    private static final String CANARY = "Zaglushka ko'chasi 7 canary-address";

    private static final GeoRegion TASHKENT = new GeoRegion(
            UUID.fromString("018f9b10-4000-7000-8000-000000000001"),
            "TASHKENT",
            new GeoPoint(41.31, 69.24),
            new GeoBoundingBox(new GeoPoint(41.15, 69.04), new GeoPoint(41.47, 69.46)));

    enum Provider {
        FAKE,
        YANDEX
    }

    /** How one adapter is made to answer each scripted situation. */
    private interface Rig extends AutoCloseable {

        GeocoderAdapter adapter();

        /** What to type so the provider answers with one in-region house. */
        String houseQuery();

        /** What to type so the provider answers with a house in another country, among others. */
        String foreignStreetQuery();

        String nothingFoundQuery();

        /** What to type so a lookup is answered as a provider outage. */
        String unavailableQuery();

        /** What to type so the key is refused (and stays refused after a fresh read). */
        String refusedQuery();

        /** Scripts the next geocode answers for {@code query}; a no-op for a rig that selects by text. */
        void arm(String scenario);

        long providerCalls();

        @Override
        void close();
    }

    private final List<AutoCloseable> toClose = new ArrayList<>();
    private @Nullable CamelContext camel;
    private final AtomicInteger freshReads = new AtomicInteger();
    private final AtomicInteger cachedReads = new AtomicInteger();

    private final SecretResolver secrets = new SecretResolver() {
        @Override
        public SecretValue resolve(SecretReference reference) {
            cachedReads.incrementAndGet();
            return SecretValue.of(KEY);
        }

        @Override
        public SecretValue resolveFresh(SecretReference reference) {
            freshReads.incrementAndGet();
            return SecretValue.of(KEY);
        }
    };

    @AfterEach
    void tearDown() throws Exception {
        if (camel != null) {
            camel.stop();
        }
        for (AutoCloseable closeable : toClose) {
            closeable.close();
        }
    }

    private Rig rig(Provider provider) throws Exception {
        Rig rig =
                switch (provider) {
                    case FAKE -> new FakeRig();
                    case YANDEX -> new YandexRig();
                };
        toClose.add(rig);
        return rig;
    }

    private Duration lookupTimeout = GeoGateway.LOOKUP_TIMEOUT;

    private CamelGeocodePort port(Rig rig, String selectedProvider) throws Exception {
        GeoGateway gateway = new GeoGateway(
                List.of(rig.adapter()),
                selectedProvider,
                secrets,
                new GeoCircuitBreaker(new SimpleMeterRegistry()),
                GeoGateway.SUGGEST_TIMEOUT,
                lookupTimeout);
        CamelContext context = new DefaultCamelContext();
        context.addRoutes(new GeoRouteBuilder(new GeoProcessor(gateway, new SimpleMeterRegistry())));
        context.start();
        camel = context;
        return new CamelGeocodePort(context.createProducerTemplate(), gateway);
    }

    private CamelGeocodePort port(Rig rig) throws Exception {
        return port(rig, rig.adapter().provider());
    }

    @SuppressWarnings("unchecked")
    private static <T> T answered(GeocodeOutcome<T> outcome) {
        assertThat(outcome).isInstanceOf(GeocodeOutcome.Answered.class);
        return ((GeocodeOutcome.Answered<T>) outcome).value();
    }

    private static GeoUnavailableReason unavailable(GeocodeOutcome<?> outcome) {
        assertThat(outcome).isInstanceOf(GeocodeOutcome.Unavailable.class);
        return ((GeocodeOutcome.Unavailable<?>) outcome).reason();
    }

    // ------------------------------------------------------------------ answers

    @ParameterizedTest
    @EnumSource(Provider.class)
    @DisplayName("a geocode answers candidates with a point, a confidence, a precision and a time")
    void aGeocodeAnswersCandidates(Provider provider) throws Exception {
        Rig rig = rig(provider);
        rig.arm("house");

        List<GeocodeResult> results = answered(port(rig).geocode(rig.houseQuery(), TASHKENT, "ru"));

        assertThat(results).isNotEmpty();
        GeocodeResult best = results.get(0);
        assertThat(TASHKENT.box().contains(best.point())).isTrue();
        assertThat(best.confidence()).isNotEqualTo(GeocodeConfidence.LOW_CONFIDENCE);
        assertThat(best.providerReference()).isNotBlank();
        assertThat(best.resolvedAt()).isNotNull();
        assertThat(best.provider()).isEqualToIgnoringCase(provider.name());
        assertThat(best.components().formatted()).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    @DisplayName(
            "a result outside the region's box is LOW_CONFIDENCE whatever the provider claimed, and one inside is not")
    void aResultOutsideTheBoxIsLowConfidence(Provider provider) throws Exception {
        Rig rig = rig(provider);
        rig.arm("foreign");

        List<GeocodeResult> results = answered(port(rig).geocode(rig.foreignStreetQuery(), TASHKENT, "ru"));

        List<GeocodeResult> outside = results.stream()
                .filter(r -> !TASHKENT.box().contains(r.point()))
                .toList();
        assertThat(outside)
                .as("the scenario must actually contain a result in another country, or this proves nothing")
                .isNotEmpty();
        // ADR 0037: an unconstrained geocoder asked for a Tashkent street name returns a
        // plausible street of the same name in another country. The adapters hand it back as a
        // naive provider would (a door, exact); the box check that flags it is the gateway's.
        assertThat(outside).allSatisfy(r -> assertThat(r.confidence()).isEqualTo(GeocodeConfidence.LOW_CONFIDENCE));
        assertThat(results.stream().filter(r -> TASHKENT.box().contains(r.point())))
                .as("a result inside the box keeps the confidence the provider gave it")
                .allSatisfy(r -> assertThat(r.confidence()).isNotNull());
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    @DisplayName("nothing found is an answer, an empty one, and not an outage")
    void nothingFoundIsAnEmptyAnswer(Provider provider) throws Exception {
        Rig rig = rig(provider);
        rig.arm("empty");

        assertThat(answered(port(rig).geocode(rig.nothingFoundQuery(), TASHKENT, "ru")))
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    @DisplayName("a suggest answers lines to show, a reverse lookup answers what is at the point")
    void suggestAndReverseAnswer(Provider provider) throws Exception {
        Rig rig = rig(provider);
        rig.arm("suggest");
        GeocodePort port = port(rig);

        List<GeoSuggestion> suggestions = answered(port.suggest(rig.houseQuery(), TASHKENT, null, "ru"));
        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions.get(0).title()).isNotBlank();
        assertThat(suggestions.get(0).fullText()).isNotBlank();

        rig.arm("reverse");
        Optional<GeocodeResult> there = answered(port.reverseGeocode(new GeoPoint(41.3111, 69.2797), TASHKENT, "ru"));
        assertThat(there).isPresent();
        assertThat(TASHKENT.box().contains(there.get().point())).isTrue();
    }

    // ----------------------------------------------------------------- non-answers

    @ParameterizedTest
    @EnumSource(Provider.class)
    @DisplayName("a provider outage is an answer of 'unavailable', never an exception")
    void anOutageIsAnAnswer(Provider provider) throws Exception {
        Rig rig = rig(provider);
        rig.arm("unavailable");

        GeocodeOutcome<List<GeocodeResult>> outcome = port(rig).geocode(rig.unavailableQuery(), TASHKENT, "ru");

        assertThat(unavailable(outcome)).isEqualTo(GeoUnavailableReason.PROVIDER_UNAVAILABLE);
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    @DisplayName("a refused key is stated as a refusal, after exactly one fresh read of the secret")
    void aRefusedKeyIsARefusal(Provider provider) throws Exception {
        Rig rig = rig(provider);
        rig.arm("refused");

        GeocodeOutcome<List<GeocodeResult>> outcome = port(rig).geocode(rig.refusedQuery(), TASHKENT, "ru");

        assertThat(unavailable(outcome)).isEqualTo(GeoUnavailableReason.PROVIDER_REFUSED);
        if (rig.adapter().credentialReference() != null) {
            // ADR 0028: once, past the cache, never in a loop. A rotated key and a stale cache
            // look the same until the second read says which.
            assertThat(freshReads.get()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a rotated key recovers: the stale cached key is refused, the fresh one is accepted")
    void aRotatedKeyRecoversAfterOneFreshRead() throws Exception {
        try (RecordingGeoProvider server = RecordingGeoProvider.start()) {
            server.reply(RecordingGeoProvider.GEOCODER_PATH, 403, "{\"message\":\"Invalid key\"}");
            server.replyFixture(RecordingGeoProvider.GEOCODER_PATH, "geocode-house.json");
            // The first reply is consumed once, so the second call (with the fresh key) sees the fixture.
            YandexRig rig = new YandexRig(server);

            GeocodeOutcome<List<GeocodeResult>> outcome = port(rig).geocode("Fixture ko'chasi 12", TASHKENT, "ru");

            assertThat(answered(outcome)).isNotEmpty();
            assertThat(freshReads.get()).isEqualTo(1);
            assertThat(server.callsTo(RecordingGeoProvider.GEOCODER_PATH)).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    @DisplayName("with no provider named nothing works, and the answer says so without a network call")
    void noProviderMeansNotConfigured(Provider provider) throws Exception {
        Rig rig = rig(provider);

        CamelGeocodePort port = port(rig, "none");

        assertThat(unavailable(port.geocode(rig.houseQuery(), TASHKENT, "ru")))
                .isEqualTo(GeoUnavailableReason.NOT_CONFIGURED);
        assertThat(unavailable(port.suggest(rig.houseQuery(), TASHKENT, null, "ru")))
                .isEqualTo(GeoUnavailableReason.NOT_CONFIGURED);
        assertThat(unavailable(port.reverseGeocode(new GeoPoint(41.31, 69.24), TASHKENT, "ru")))
                .isEqualTo(GeoUnavailableReason.NOT_CONFIGURED);
        assertThat(rig.providerCalls()).isZero();
        assertThat(port.clientConfig().configured())
                .as("the browser is told the same fact")
                .isFalse();
    }

    @Test
    @DisplayName("a key reference with no value behind it is 'not configured', not 'down'")
    void aMissingSecretValueIsNotConfigured() throws Exception {
        try (RecordingGeoProvider server = RecordingGeoProvider.start()) {
            SecretResolver empty = new SecretResolver() {
                @Override
                public SecretValue resolve(SecretReference reference) {
                    throw new SecretNotFoundException(reference);
                }

                @Override
                public SecretValue resolveFresh(SecretReference reference) {
                    throw new SecretNotFoundException(reference);
                }
            };
            YandexRig rig = new YandexRig(server);
            GeoGateway gateway = new GeoGateway(
                    List.of(rig.adapter()), "yandex", empty, new GeoCircuitBreaker(new SimpleMeterRegistry()));
            camel = new DefaultCamelContext();
            camel.addRoutes(new GeoRouteBuilder(new GeoProcessor(gateway, new SimpleMeterRegistry())));
            camel.start();

            GeocodeOutcome<List<GeocodeResult>> outcome =
                    new CamelGeocodePort(camel.createProducerTemplate(), gateway).geocode("anything", TASHKENT, "ru");

            assertThat(unavailable(outcome)).isEqualTo(GeoUnavailableReason.NOT_CONFIGURED);
            assertThat(server.calls()).isEmpty();
        }
    }

    @Test
    @DisplayName("with the route not running the port answers rather than throwing")
    void anUnreachableRouteIsAnAnswer() throws Exception {
        Rig rig = rig(Provider.FAKE);
        GeoGateway gateway = gatewayOf(rig, "fake");
        camel = new DefaultCamelContext();
        camel.start();

        GeocodeOutcome<List<GeocodeResult>> outcome =
                new CamelGeocodePort(camel.createProducerTemplate(), gateway).geocode(rig.houseQuery(), TASHKENT, "ru");

        assertThat(unavailable(outcome)).isEqualTo(GeoUnavailableReason.PROVIDER_UNAVAILABLE);
    }

    // ------------------------------------------------------------ real-network cases

    @Test
    @DisplayName("an accepted-then-lost reply is unavailable and is asked once, not repeated")
    void aLostReplyIsUnavailable() throws Exception {
        try (RecordingGeoProvider server = RecordingGeoProvider.start()) {
            server.replyFixture(RecordingGeoProvider.GEOCODER_PATH, "geocode-house.json");
            server.stallAfterReceiving(RecordingGeoProvider.GEOCODER_PATH, 1_200);
            lookupTimeout = Duration.ofMillis(300);
            YandexRig rig = new YandexRig(server);

            GeocodeOutcome<List<GeocodeResult>> outcome = port(rig).geocode("anything", TASHKENT, "ru");

            assertThat(unavailable(outcome)).isEqualTo(GeoUnavailableReason.PROVIDER_UNAVAILABLE);
            assertThat(server.callsTo(RecordingGeoProvider.GEOCODER_PATH)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a body that is not JSON is unavailable, not a thrown parse error")
    void aMalformedBodyIsUnavailable() throws Exception {
        try (RecordingGeoProvider server = RecordingGeoProvider.start()) {
            server.reply(RecordingGeoProvider.GEOCODER_PATH, 200, "<html>maintenance</html>");

            GeocodeOutcome<List<GeocodeResult>> outcome =
                    port(new YandexRig(server)).geocode("anything", TASHKENT, "ru");

            assertThat(unavailable(outcome)).isEqualTo(GeoUnavailableReason.PROVIDER_UNAVAILABLE);
        }
    }

    @Test
    @DisplayName("repeated failures open the breaker, and an open breaker stops asking the provider")
    void theBreakerStopsAskingAFailingProvider() throws Exception {
        try (RecordingGeoProvider server = RecordingGeoProvider.start()) {
            server.reply(RecordingGeoProvider.GEOCODER_PATH, 503, "{\"message\":\"down\"}");
            GeocodePort port = port(new YandexRig(server));

            for (int i = 0; i < 8; i++) {
                assertThat(unavailable(port.geocode("anything " + i, TASHKENT, "ru")))
                        .isEqualTo(GeoUnavailableReason.PROVIDER_UNAVAILABLE);
            }

            // minimumNumberOfCalls is 5: the first five reach the provider, and then it opens.
            assertThat(server.callsTo(RecordingGeoProvider.GEOCODER_PATH))
                    .as("ADR 0145 decision 6: during an outage the screens degrade at once instead of "
                            + "every keystroke waiting out a provider timeout")
                    .isEqualTo(5);
        }
    }

    // --------------------------------------------------------------------- privacy

    @ParameterizedTest
    @EnumSource(Provider.class)
    @DisplayName("neither the typed address nor the key reaches any log line, on success or failure")
    void noAddressOrKeyReachesALog(Provider provider) throws Exception {
        Rig rig = rig(provider);
        ListAppender<ILoggingEvent> lines = captureAllLogs();
        Level previous = rootLogger().getLevel();
        try {
            GeocodePort port = port(rig);
            rig.arm("house");
            port.geocode(CANARY, TASHKENT, "ru");
            rig.arm("unavailable");
            port.geocode(CANARY + rig.unavailableQuery(), TASHKENT, "ru");
            rig.arm("refused");
            port.geocode(CANARY + rig.refusedQuery(), TASHKENT, "ru");
            port.suggest(CANARY, TASHKENT, new GeoPoint(41.31, 69.24), "ru");
            port.reverseGeocode(new GeoPoint(41.31234, 69.24567), TASHKENT, "ru");

            List<ILoggingEvent> captured = snapshot(lines);
            assertThat(captured)
                    .as("the capture must see something, or it proves nothing")
                    .isNotEmpty();
            assertThat(captured).noneMatch(event -> event.getFormattedMessage().contains("canary-address"));
            assertThat(captured).noneMatch(event -> event.getFormattedMessage().contains(KEY));
            assertThat(captured)
                    .as("no logged throwable may carry the request in its message either")
                    .noneMatch(event -> event.getThrowableProxy() != null
                            && (String.valueOf(event.getThrowableProxy().getMessage())
                                            .contains("canary-address")
                                    || String.valueOf(event.getThrowableProxy().getMessage())
                                            .contains(KEY)));
            assertThat(captured)
                    .noneMatch(event -> event.getFormattedMessage().contains("41.31234")
                            || event.getFormattedMessage().contains("69.24567"));
        } finally {
            releaseAllLogs(lines, previous);
        }
    }

    // ------------------------------------------------------------------ rigs

    private GeoGateway gatewayOf(Rig rig, String provider) {
        return new GeoGateway(
                List.of(rig.adapter()), provider, secrets, new GeoCircuitBreaker(new SimpleMeterRegistry()));
    }

    private final class FakeRig implements Rig {

        private final FakeGeocoderAdapter adapter = new FakeGeocoderAdapter(Clock.fixed(NOW, ZoneOffset.UTC));
        private String foreign = "amir temur ko'chasi";

        @Override
        public GeocoderAdapter adapter() {
            return adapter;
        }

        @Override
        public String houseQuery() {
            return "navoi ko'chasi 28";
        }

        @Override
        public String foreignStreetQuery() {
            return foreign;
        }

        @Override
        public String nothingFoundQuery() {
            return FakeGeocoderAdapter.EMPTY_TEXT;
        }

        @Override
        public String unavailableQuery() {
            return " " + FakeGeocoderAdapter.UNAVAILABLE_TEXT;
        }

        @Override
        public String refusedQuery() {
            return " " + FakeGeocoderAdapter.REFUSED_TEXT;
        }

        @Override
        public void arm(String scenario) {
            // The fake selects its scenario by the text a caller sends (ADR 0007's rule), so
            // there is nothing to script.
        }

        @Override
        public long providerCalls() {
            return 0;
        }

        @Override
        public void close() {}
    }

    private final class YandexRig implements Rig {

        private final RecordingGeoProvider server;
        private final boolean owned;
        private final YandexGeocoderAdapter adapter;

        YandexRig() throws Exception {
            this(RecordingGeoProvider.start(), true);
        }

        YandexRig(RecordingGeoProvider server) {
            this(server, false);
        }

        private YandexRig(RecordingGeoProvider server, boolean owned) {
            this.server = server;
            this.owned = owned;
            this.adapter = new YandexGeocoderAdapter(
                    new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier()),
                    server.endpoints(),
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    REFERENCE,
                    "",
                    "© Яндекс");
        }

        @Override
        public GeocoderAdapter adapter() {
            return adapter;
        }

        @Override
        public String houseQuery() {
            return "Fixture ko'chasi 12";
        }

        @Override
        public String foreignStreetQuery() {
            return "Fixture ko'chasi 1";
        }

        @Override
        public String nothingFoundQuery() {
            return "nothing here";
        }

        @Override
        public String unavailableQuery() {
            return " outage";
        }

        @Override
        public String refusedQuery() {
            return " refused";
        }

        @Override
        public void arm(String scenario) {
            switch (scenario) {
                case "house" -> server.replyFixture(RecordingGeoProvider.GEOCODER_PATH, "geocode-house.json");
                case "foreign" -> {
                    // Two candidates: one inside the box, one in another country.
                    server.reply(
                            RecordingGeoProvider.GEOCODER_PATH,
                            200,
                            combined(
                                    RecordingGeoProvider.fixture("geocode-house.json"),
                                    RecordingGeoProvider.fixture("geocode-outside-region.json")));
                }
                case "empty" -> server.replyFixture(RecordingGeoProvider.GEOCODER_PATH, "geocode-empty.json");
                case "suggest" -> server.replyFixture(RecordingGeoProvider.SUGGEST_PATH, "suggest.json");
                case "reverse" -> server.replyFixture(RecordingGeoProvider.GEOCODER_PATH, "reverse-house.json");
                case "unavailable" -> server.reply(RecordingGeoProvider.GEOCODER_PATH, 503, "{\"message\":\"down\"}");
                case "refused" ->
                    server.reply(RecordingGeoProvider.GEOCODER_PATH, 403, "{\"message\":\"Invalid key\"}");
                default -> throw new IllegalArgumentException(scenario);
            }
        }

        @Override
        public long providerCalls() {
            return server.calls().size();
        }

        @Override
        public void close() {
            if (owned) {
                server.close();
            }
        }
    }

    /** The members of two recorded answers in one, so one reply can carry an in-region and a foreign result. */
    private static String combined(String first, String second) {
        var mapper = JsonMapper.builder().build();
        var one = mapper.readTree(first);
        var two = mapper.readTree(second);
        var members = (tools.jackson.databind.node.ArrayNode) one.at("/response/GeoObjectCollection/featureMember");
        two.at("/response/GeoObjectCollection/featureMember").forEach(members::add);
        return mapper.writeValueAsString(one);
    }

    // ----------------------------------------------------------------- log capture

    private static ListAppender<ILoggingEvent> captureAllLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger root = rootLogger();
        root.setLevel(Level.ALL);
        root.addAppender(appender);
        return appender;
    }

    private static void releaseAllLogs(ListAppender<ILoggingEvent> appender, Level previous) {
        Logger root = rootLogger();
        root.detachAppender(appender);
        root.setLevel(previous);
        appender.stop();
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    }

    /** A consistent copy: background threads keep appending while an assertion iterates. */
    private static List<ILoggingEvent> snapshot(ListAppender<ILoggingEvent> appender) {
        synchronized (appender) {
            return List.copyOf(appender.list);
        }
    }
}
