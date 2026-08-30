package uz.horecaos.platform.observability;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import uz.horecaos.platform.support.TestDatabase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The things ADR 0023's probe design would be worthless without: that the
 * watchdog's probe does not consult the database, that the external probe does,
 * and that the on-box probe can read the metrics it evaluates every threshold
 * from.
 *
 * <p>A real servlet container rather than MockMvc, and that is not incidental.
 * The scrape rule in {@link LocalMetricsScrapeMatcher} depends on a servlet
 * request listener that a mocked request never fires and on a forwarded-header
 * filter whose behaviour is the whole reason the rule is written the way it is.
 * Mocked, both halves would be asserted against a request that does not resemble
 * the one production handles — and the failure mode of getting this wrong is
 * silent: the probe reads 401, treats an absent value as "not firing", and the
 * platform stops paging without anything failing.
 *
 * <p>The last test stops PostgreSQL on purpose, because the two-audience
 * decision only has content in that state. Both probes answer 200 while
 * everything is up, and a suite that only ever ran them then would pass
 * identically against the mistake this configuration exists to prevent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Spring Boot disables metrics export in tests by default, which would leave
// /actuator/prometheus empty and let the series-name contract below pass by
// asserting nothing about an empty string.
@AutoConfigureMetrics
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HealthProbeAndMetricTests {

    /**
     * Label keys that would put a person, or an unbounded set, into the metrics
     * store. ADR 0029 forbids the personal ones outright; ADR 0023 and ADR 0033
     * forbid the tenant identifier for cardinality on a metrics store that shares
     * a disk with PostgreSQL.
     */
    private static final Set<String> FORBIDDEN_LABEL_KEYS = Set.of(
            "tenant", "tenantId", "tenant_id", "brand_id", "location_id",
            "customer", "customer_id", "phone", "email", "address",
            "correlation_id", "order_id", "aggregate_id", "user", "user_id");

    /**
     * The one suite in the tree that still starts a container of its own, and the
     * only one that should.
     *
     * <p>Every other suite now takes a cloned database off a single shared
     * PostgreSQL that no test stops, because a suite that stops it takes the
     * database away from every class that has not run yet. This suite's
     * {@code @Order(6)} test <em>is</em> stopping the database — that is the whole
     * assertion, that liveness holds at 200 while the customer group goes 503 and
     * names no back end. There is no version of it that leaves the database
     * standing, so it cannot be given the shared one.
     *
     * <p>One container, stopped part way through its own suite, is a cost this
     * design can carry. See {@code TestDatabase}.
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = TestDatabase.container();

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the observability probe tests");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("horecaos.messaging.inbox.listener.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // Without this the last test waits out Hikari's thirty-second default
        // before the customer probe can answer, which is also what an external
        // uptime check would experience. Five seconds is the shape of the real
        // configuration on the box rather than a test convenience.
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
    }

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int port;

    @Autowired
    private HealthEndpointGroups groups;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private PrometheusMeterRegistry prometheus;

    @Test
    @Order(1)
    @DisplayName("the watchdog's probe consults no dependency")
    void livenessHasNoDependencyIndicator() {
        // Asserted structurally rather than by reading the response, because the
        // group shows no details to anyone: a passing 200 proves nothing about
        // what was checked to produce it.
        assertThat(groups.get("liveness").isMember("db"))
                .as("a liveness probe that fails on a slow database restarts the only container there is")
                .isFalse();
        assertThat(groups.get("readiness").isMember("db"))
                .as("the proxy must not stop routing to its only upstream because the database is slow")
                .isFalse();
    }

    @Test
    @Order(2)
    @DisplayName("the external probe asks whether a customer could order")
    void customerGroupConsultsTheDatabase() {
        assertThat(groups.get("customer")).isNotNull();
        assertThat(groups.get("customer").isMember("db")).isTrue();
    }

    @Test
    @Order(3)
    @DisplayName("no metric carries a tenant or a person")
    void noMetricIsLabelledByPersonOrTenant() {
        List<String> offenders = meters.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream()
                        .map(Tag::getKey)
                        .filter(FORBIDDEN_LABEL_KEYS::contains)
                        .map(key -> meter.getId().getName() + " labelled by " + key))
                .distinct()
                .toList();

        assertThat(offenders)
                .as("ADR 0029: a metric labelled by customer is a privacy incident with a dashboard")
                .isEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("the scrape carries the exact series names the on-box probe greps for")
    void prometheusSeriesNamesMatchTheProbe() {
        // The alert thresholds live in infra/observability/horecaos-probe.sh and are
        // evaluated by matching these names in the scrape. Renaming a meter is
        // therefore a silent way to disable a night alert. This is the contract
        // between the two halves, and it belongs on the Java side because that is
        // the side that changes.
        String scrape = prometheus.scrape();

        assertThat(scrape)
                .contains("horecaos_outbox_oldest_pending_age_seconds")
                .contains("horecaos_inbox_oldest_pending_age_seconds")
                .contains("horecaos_outbox_pending")
                .contains("horecaos_inbox_pending")
                .contains("horecaos_orders_oldest_live_age_seconds")
                .contains("disk_free_bytes");
    }

    @Test
    @Order(5)
    @DisplayName("the probe reads the scrape from loopback without a token, and a claim changes nothing")
    void onlyALoopbackScrapeIsPermitted() {
        assertThat(get("/actuator/prometheus", Map.of()).statusCode())
                .as("the on-box probe holds no bearer token and must not need one")
                .isEqualTo(200);

        // The same request with a forwarding header is still permitted, and that
        // is the point rather than a hole: the rule reads the peer address the
        // container recorded before any filter ran, so what the caller claims
        // about itself is not an input. A caller arriving through the edge has a
        // bridge address there and is refused whatever it sets — see
        // LocalMetricsScrapeMatcherTests, which exercises that half directly
        // because a test connecting over loopback cannot produce it.
        assertThat(get("/actuator/prometheus", Map.of("X-Forwarded-For", "203.0.113.9")).statusCode())
                .as("the header is not consulted, so it cannot grant or remove access")
                .isEqualTo(200);
    }

    @Test
    @Order(6)
    @DisplayName("with PostgreSQL stopped, the watchdog holds and the external probe reports down")
    void databaseLossSeparatesTheTwoAudiences() {
        postgres.stop();

        assertThat(get("/actuator/health/liveness", Map.of()).statusCode())
                .as("restarting the container would not bring the database back")
                .isEqualTo(200);

        HttpResponse<String> customer = get("/actuator/health/customer", Map.of());
        assertThat(customer.statusCode())
                .as("no customer can place an order without the database")
                .isEqualTo(503);
        assertThat(customer.body())
                .as("the group is reachable from the internet and names no back-end service")
                .doesNotContain("PostgreSQL")
                .doesNotContain("jdbc");
    }

    /**
     * The JDK client rather than a Spring test client, because the request has to
     * reach the server over a real loopback socket for the servlet request
     * listener to fire and for the peer address to be what production sees.
     */
    private HttpResponse<String> get(String path, Map<String, String> headers) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + path));
            headers.forEach(request::header);
            return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
