package uz.horecaos.platform.integration.provider.routing;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.fulfillment.api.RoadRoute;
import uz.horecaos.platform.integration.camel.common.ProviderExceptionClassifier;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.support.FakeOsrmEngine;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.web.cache.CacheRegistry;

/**
 * The OSRM adapter against an engine that answers what the real one answers, over real
 * HTTP, through the real {@link ProviderHttpClient} and a real installations table
 * (ADR 0147, "Testing").
 *
 * <p>What this cannot prove is the engine's own routing: that needs {@code osrm-routed}
 * and a preprocessed extract, which is a deployment concern recorded in
 * {@code docs/runbooks/load-uzbekistan-routing-dataset.md}. What it does prove is every
 * decision the adapter makes around the engine &mdash; the request it sends, what it
 * does with each kind of answer, and, as important, each way it refuses to answer.
 */
class OsrmRoadDistanceAdapterTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final GeoPoint BRANCH = new GeoPoint(41.311081, 69.240562);
    private static final GeoPoint DOORSTEP = new GeoPoint(41.3309, 69.2641);
    private static final String DATASET = "2026-10-01";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T06:00:00Z"), ZoneOffset.UTC);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcRoutingInstallations installations;
    private FakeOsrmEngine engine;
    private CacheManager caches;
    private SimpleMeterRegistry meters;
    private UUID installation;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the routing adapter tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE integration.installations CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        seedTenant(TENANT, "routing-tenant");
        seedTenant(OTHER_TENANT, "other-routing-tenant");

        engine = FakeOsrmEngine.start();
        jdbc.sql("UPDATE integration.provider_environments SET base_url = :url WHERE code = 'osrm_internal'")
                .param("url", engine.baseUrl())
                .update();

        installations = new JdbcRoutingInstallations(jdbc);
        caches = new ConcurrentMapCacheManager(CacheRegistry.ROUTING_ROAD_ROUTES.cacheName());
        meters = new SimpleMeterRegistry();
        installation = installations.insertPlatformRouting(TENANT).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    // ----------------------------------------------------------------- the answer

    @Test
    @DisplayName("a known pair returns the known metres, seconds, provider and dataset")
    void aKnownPairReturnsTheKnownRoute() {
        engine.routeOf(4_321.7, 468.2);

        Optional<RoadRoute> route =
                adapter(OsrmProperties.enabledWith(DATASET)).measure(BRANCH, DOORSTEP, installation);

        // Rounded to the nearest metre and second, and attributed to the map that
        // measured it: a road figure with no dataset cannot be reproduced after a refresh.
        assertThat(route).contains(new RoadRoute(4_322, 468, "osrm", DATASET));
        assertThat(count("ok")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the request is the engine's route service, longitude first, with no geometry")
    void theRequestIsWhatTheEngineExpects() {
        adapter(OsrmProperties.enabledWith(DATASET)).measure(BRANCH, DOORSTEP, installation);

        // OSRM takes longitude before latitude. A swapped pair is a valid request for a
        // point in the Indian Ocean, and the engine answers it without complaint.
        assertThat(engine.lastRequest())
                .startsWith("/route/v1/driving/69.240562,41.311081;69.264100,41.330900?")
                .contains("overview=false")
                .contains("steps=false")
                .contains("alternatives=false")
                .contains("radiuses=1000;1000");
    }

    @Test
    @DisplayName("a half metre rounds up, as the fee evidence will read it")
    void metresAreRoundedToTheNearest() {
        engine.routeOf(1_234.5, 99.5);

        Optional<RoadRoute> route =
                adapter(OsrmProperties.enabledWith(DATASET)).measure(BRANCH, DOORSTEP, installation);

        assertThat(route).map(RoadRoute::meters).contains(1_235);
        assertThat(route).map(RoadRoute::seconds).contains(100);
    }

    @Test
    @DisplayName("an unreachable pair is no answer, never a number, and does not trip the breaker")
    void anUnreachablePairIsEmpty() {
        engine.noRoute();
        OsrmRoadDistanceAdapter adapter = adapter(OsrmProperties.enabledWith(DATASET));

        for (int i = 0; i < 40; i++) {
            GeoPoint elsewhere = new GeoPoint(41.33 + i * 0.001, 69.26);
            assertThat(adapter.measure(BRANCH, elsewhere, installation)).isEmpty();
        }

        // "No route" is the engine working. Counting it as a fault would take a healthy
        // engine offline because customers kept pinning the far side of a canal.
        assertThat(count("no_route")).isEqualTo(40.0);
        assertThat(count("breaker_open")).isZero();
        engine.routeOf(2_000, 200);
        assertThat(adapter.measure(BRANCH, DOORSTEP, installation)).isPresent();
    }

    @Test
    @DisplayName("a pin too far from any road is no answer either")
    void aCoordinateOffTheNetworkIsEmpty() {
        engine.noSegment();

        assertThat(adapter(OsrmProperties.enabledWith(DATASET)).measure(BRANCH, DOORSTEP, installation))
                .isEmpty();
        assertThat(count("no_route")).isEqualTo(1.0);
    }

    // ------------------------------------------------------------ bounded, breakable

    @Test
    @DisplayName("an engine that does not answer in time is empty within the deadline")
    void aSlowEngineIsEmptyWithinTheDeadline() {
        engine.slow(Duration.ofSeconds(5));
        OsrmRoadDistanceAdapter adapter = adapter(withTimeout(Duration.ofMillis(200)));

        long started = System.nanoTime();
        Optional<RoadRoute> route = adapter.measure(BRANCH, DOORSTEP, installation);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(route).isEmpty();
        // The whole exchange is bounded, so the checkout thread is back well inside a
        // second even though the engine would have taken five.
        assertThat(took).isLessThan(Duration.ofSeconds(2));
        assertThat(count("timeout")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("after repeated timeouts the next call returns empty without waiting or calling")
    void theBreakerOpensAndTheNextCallDoesNotWait() {
        engine.slow(Duration.ofSeconds(5));
        OsrmProperties properties =
                new OsrmProperties(true, DATASET, Duration.ofMillis(150), 1_000, 5, Duration.ofMinutes(5));
        OsrmRoadDistanceAdapter adapter = adapter(properties);

        for (int i = 0; i < 5; i++) {
            assertThat(adapter.measure(BRANCH, new GeoPoint(41.33 + i * 0.001, 69.26), installation))
                    .isEmpty();
        }
        int hitsWhenItOpened = engine.hits();

        long started = System.nanoTime();
        Optional<RoadRoute> afterwards = adapter.measure(BRANCH, new GeoPoint(41.40, 69.30), installation);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(afterwards).isEmpty();
        assertThat(took).as("an open breaker answers without a round trip").isLessThan(Duration.ofMillis(100));
        assertThat(engine.hits()).as("and without asking the engine").isEqualTo(hitsWhenItOpened);
        assertThat(count("breaker_open")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an engine fault and an answer that is not a route both count against the breaker")
    void faultsOpenTheBreaker() {
        OsrmProperties properties =
                new OsrmProperties(true, DATASET, Duration.ofMillis(500), 1_000, 3, Duration.ofMinutes(5));
        OsrmRoadDistanceAdapter adapter = adapter(properties);

        engine.serverError();
        assertThat(adapter.measure(BRANCH, new GeoPoint(41.331, 69.26), installation))
                .isEmpty();
        assertThat(adapter.measure(BRANCH, new GeoPoint(41.332, 69.26), installation))
                .isEmpty();
        engine.garbage();
        assertThat(adapter.measure(BRANCH, new GeoPoint(41.333, 69.26), installation))
                .isEmpty();

        int hits = engine.hits();
        assertThat(adapter.measure(BRANCH, new GeoPoint(41.334, 69.26), installation))
                .isEmpty();
        assertThat(engine.hits()).isEqualTo(hits);
        assertThat(count("error")).isEqualTo(3.0);
        assertThat(count("breaker_open")).isEqualTo(1.0);
    }

    // ---------------------------------------------------------------------- cache

    @Test
    @DisplayName("the same pair twice is one engine call, and a pin on the same doorstep is the same question")
    void theSamePairTwiceIsOneEngineCall() {
        OsrmRoadDistanceAdapter adapter = adapter(OsrmProperties.enabledWith(DATASET));

        Optional<RoadRoute> first = adapter.measure(BRANCH, DOORSTEP, installation);
        Optional<RoadRoute> second = adapter.measure(BRANCH, DOORSTEP, installation);
        // Four decimals is about eleven metres: 41.330904 and 41.3309 are one doorstep.
        Optional<RoadRoute> nextToIt = adapter.measure(BRANCH, new GeoPoint(41.330904, 69.264098), installation);

        assertThat(engine.hits()).isEqualTo(1);
        assertThat(second).isEqualTo(first);
        assertThat(nextToIt).isEqualTo(first);
        assertThat(meters.get("horecaos.routing.cache")
                        .tag("result", "hit")
                        .counter()
                        .count())
                .isEqualTo(2.0);

        engine.routeOf(9_000, 900);
        Optional<RoadRoute> furtherAway = adapter.measure(BRANCH, new GeoPoint(41.3312, 69.2641), installation);
        assertThat(engine.hits())
                .as("a different doorstep is a different question")
                .isEqualTo(2);
        assertThat(furtherAway).map(RoadRoute::meters).contains(9_000);
    }

    @Test
    @DisplayName("a new dataset version is a miss: the cache never serves a figure from another map")
    void aNewDatasetVersionIsAMiss() {
        OsrmRoadDistanceAdapter october = adapter(OsrmProperties.enabledWith("2026-10-01"));
        OsrmRoadDistanceAdapter november = adapter(OsrmProperties.enabledWith("2026-11-01"));

        Optional<RoadRoute> before = october.measure(BRANCH, DOORSTEP, installation);
        engine.routeOf(4_410, 470);
        Optional<RoadRoute> after = november.measure(BRANCH, DOORSTEP, installation);

        assertThat(engine.hits()).isEqualTo(2);
        assertThat(before).map(RoadRoute::datasetVersion).contains("2026-10-01");
        assertThat(after).map(RoadRoute::datasetVersion).contains("2026-11-01");
        assertThat(after).map(RoadRoute::meters).contains(4_410);
    }

    @Test
    @DisplayName("one tenant's cached route is never served to another tenant")
    void theCacheIsTenantScoped() {
        UUID otherInstallation =
                installations.insertPlatformRouting(OTHER_TENANT).orElseThrow();
        OsrmRoadDistanceAdapter adapter = adapter(OsrmProperties.enabledWith(DATASET));

        adapter.measure(BRANCH, DOORSTEP, installation);
        adapter.measure(BRANCH, DOORSTEP, otherInstallation);

        assertThat(engine.hits()).isEqualTo(2);
    }

    // ------------------------------------------------------- standing and switches

    @Test
    @DisplayName("suspending the installation is the rollback: it stops answering at once, cached or not")
    void suspendingTheInstallationStopsTheAnswer() {
        OsrmRoadDistanceAdapter adapter = adapter(OsrmProperties.enabledWith(DATASET));
        assertThat(adapter.measure(BRANCH, DOORSTEP, installation)).isPresent();

        jdbc.sql("UPDATE integration.installations SET status = 'SUSPENDED' WHERE id = :id")
                .param("id", installation)
                .update();

        // The route is in the cache, and the installation is read before the cache is:
        // a cache that outlived the rollback would make the rollback slower than the incident.
        assertThat(adapter.measure(BRANCH, DOORSTEP, installation)).isEmpty();
        assertThat(count("unavailable")).isEqualTo(1.0);
        assertThat(engine.hits()).isEqualTo(1);

        jdbc.sql("UPDATE integration.installations SET status = 'ACTIVE' WHERE id = :id")
                .param("id", installation)
                .update();
        assertThat(adapter.measure(BRANCH, DOORSTEP, installation)).isPresent();
    }

    @Test
    @DisplayName("an engine that is switched off, or has no named dataset, is never asked")
    void aSwitchedOffEngineIsNeverAsked() {
        OsrmProperties off =
                new OsrmProperties(false, DATASET, Duration.ofMillis(500), 1_000, 10, Duration.ofSeconds(30));
        OsrmProperties unnamed =
                new OsrmProperties(true, null, Duration.ofMillis(500), 1_000, 10, Duration.ofSeconds(30));

        assertThat(adapter(off).measure(BRANCH, DOORSTEP, installation)).isEmpty();
        assertThat(adapter(unnamed).measure(BRANCH, DOORSTEP, installation)).isEmpty();

        // A fee must name the map that measured it, so an engine with no dataset tag
        // answers nothing rather than answering anonymously.
        assertThat(engine.hits()).isZero();
        assertThat(count("unavailable")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("a dataset tag the fee row cannot hold is not used: a failed insert would be a failed quote")
    void aDatasetTagTooLongForTheFeeRowIsNotUsed() {
        String tooLong = "2026-10-01-" + "x".repeat(OsrmProperties.MAX_DATASET_VERSION_LENGTH);

        assertThat(adapter(OsrmProperties.enabledWith(tooLong)).measure(BRANCH, DOORSTEP, installation))
                .isEmpty();
        assertThat(engine.hits()).isZero();
        // At the limit it is used: the column is varchar(32), and 32 characters fit.
        String atTheLimit = "x".repeat(OsrmProperties.MAX_DATASET_VERSION_LENGTH);
        assertThat(adapter(OsrmProperties.enabledWith(atTheLimit)).measure(BRANCH, DOORSTEP, installation))
                .map(RoadRoute::datasetVersion)
                .contains(atTheLimit);
    }

    @Test
    @DisplayName("a tariff with no routing installation, or one that is not the routing engine, is never answered")
    void onlyARoutingInstallationIsAnswered() {
        jdbc.sql("""
                INSERT INTO integration.provider_environments (
                    code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('OTHER-ROUTING', 'OTHER', 'routing', :url, false, 'x')
                """).param("url", engine.baseUrl()).update();
        UUID imposter = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations (
                    id, tenant_id, provider_category, provider_type, environment_code, display_name, status)
                VALUES (:id, :tenantId, 'OTHER', 'routing', 'OTHER-ROUTING', 'Not routing', 'ACTIVE')
                """).param("id", imposter).param("tenantId", TENANT).update();
        OsrmRoadDistanceAdapter adapter = adapter(OsrmProperties.enabledWith(DATASET));

        assertThat(adapter.measure(BRANCH, DOORSTEP, null)).isEmpty();
        assertThat(adapter.measure(BRANCH, DOORSTEP, imposter)).isEmpty();
        assertThat(adapter.measure(BRANCH, DOORSTEP, UUID.randomUUID())).isEmpty();
        assertThat(engine.hits()).isZero();
    }

    // ------------------------------------------------------------------ privacy

    @Test
    @DisplayName("a connection failure logs the operation and the outcome, never the customer's coordinates")
    void noCoordinateReachesTheLog() {
        int deadPort = engine.port();
        engine.close();
        GeoPoint customer = new GeoPoint(41.330912, 69.264177);

        List<ILoggingEvent> logged = captureLogs(() -> {
            assertThat(adapter(OsrmProperties.enabledWith(DATASET)).measure(BRANCH, customer, installation))
                    .isEmpty();
        });

        assertThat(deadPort).isPositive();
        assertThat(logged).as("the failure was logged at all").isNotEmpty();
        // ADR 0029: a precise location is as identifying as the address beside it. The
        // path carries both coordinates, so the HTTP client must log a label instead.
        assertThat(logged)
                .allSatisfy(event -> assertThat(event.getFormattedMessage())
                        .doesNotContain("41.330912")
                        .doesNotContain("69.264177")
                        .doesNotContain("41.3309")
                        .doesNotContain("69.2641")
                        .doesNotContain("/route/v1"));
        assertThat(logged)
                .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("osrm.route"));
    }

    // -------------------------------------------------------------------- metrics

    @Test
    @DisplayName("the dataset's age is published in days, from the tag's date")
    void theDatasetAgeIsPublished() {
        adapter(OsrmProperties.enabledWith("2026-10-01"));

        // The fixed clock is 2026-10-08.
        assertThat(meters.get("horecaos.routing.dataset.age_days").gauge().value())
                .isEqualTo(7.0);
    }

    // -------------------------------------------------------------------- helpers

    private OsrmRoadDistanceAdapter adapter(OsrmProperties properties) {
        return new OsrmRoadDistanceAdapter(
                new ProviderHttpClient(JsonMapper.builder().build(), new ProviderExceptionClassifier()),
                properties,
                installations,
                caches,
                meters,
                CLOCK);
    }

    private static OsrmProperties withTimeout(Duration timeout) {
        return new OsrmProperties(true, DATASET, timeout, 1_000, 10, Duration.ofSeconds(30));
    }

    private double count(String outcome) {
        Counter counter =
                meters.find("horecaos.routing.calls").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private void seedTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    /** Runs the action with an appender on the two loggers that speak about a routing call. */
    private static List<ILoggingEvent> captureLogs(Runnable action) {
        List<Logger> loggers = List.of((Logger) LoggerFactory.getLogger(ProviderHttpClient.class), (Logger)
                LoggerFactory.getLogger(OsrmRoadDistanceAdapter.class));
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        loggers.forEach(logger -> logger.addAppender(appender));
        try {
            action.run();
        } finally {
            loggers.forEach(logger -> logger.detachAppender(appender));
            appender.stop();
        }
        synchronized (appender) {
            return new ArrayList<>(appender.list);
        }
    }
}
