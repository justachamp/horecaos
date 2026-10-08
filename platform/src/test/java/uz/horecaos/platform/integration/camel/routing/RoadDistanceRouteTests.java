package uz.horecaos.platform.integration.camel.routing;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import uz.horecaos.platform.fulfillment.api.RoadRoute;
import uz.horecaos.platform.integration.provider.routing.RoadRouteMeasurer;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * The road-distance route and the port in front of it (ADR 0007, ADR 0147): what happens
 * when the thing behind them does not behave.
 *
 * <p>The adapter is built never to throw, and these exist for the day that is not true: a
 * defect, an out-of-memory in a body buffer, a route that did not start. In every one the
 * quote is still priced, and nothing about a customer's location reaches a log.
 */
class RoadDistanceRouteTests {

    private static final GeoPoint BRANCH = new GeoPoint(41.311081, 69.240562);
    private static final GeoPoint CUSTOMER = new GeoPoint(41.330912, 69.264177);

    private @Nullable CamelContext camel;

    @AfterEach
    void tearDown() throws Exception {
        if (camel != null) {
            camel.stop();
        }
    }

    @Test
    @DisplayName("what the measurer answers is what the port answers, and the installation travels with the question")
    void theRouteCarriesTheAnswerAndTheQuestion() throws Exception {
        UUID installation = UUID.randomUUID();
        AtomicReference<UUID> asked = new AtomicReference<>();
        RoadRoute measured = new RoadRoute(4_322, 468, "osrm", "2026-10-01");
        CamelRoadDistancePort port = portOver((origin, destination, installationId) -> {
            asked.set(installationId);
            return Optional.of(measured);
        });

        assertThat(port.route(BRANCH, CUSTOMER, installation)).contains(measured);
        assertThat(asked.get()).isEqualTo(installation);
    }

    @Test
    @DisplayName("a measurer that does not answer is no answer, and a missing installation is carried as missing")
    void anEmptyAnswerStaysEmpty() throws Exception {
        AtomicReference<@Nullable UUID> asked = new AtomicReference<>(UUID.randomUUID());
        CamelRoadDistancePort port = portOver((origin, destination, installationId) -> {
            asked.set(installationId);
            return Optional.empty();
        });

        assertThat(port.route(BRANCH, CUSTOMER, null)).isEmpty();
        assertThat(asked.get()).isNull();
    }

    @Test
    @DisplayName("a measurer that throws is a fallback, not a failed quote, and is counted")
    void aThrowingMeasurerIsAnEmptyAnswer() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CamelRoadDistancePort port = portOver(
                (origin, destination, installationId) -> {
                    throw new IllegalStateException("the engine's client blew up");
                },
                meters);

        assertThat(port.route(BRANCH, CUSTOMER, UUID.randomUUID())).isEmpty();
        assertThat(meters.get("horecaos.routing.calls")
                        .tag("outcome", "error")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a failure never writes the customer's coordinates to a log, however Camel reports it")
    void aFailureLogsNoLocation() throws Exception {
        List<ILoggingEvent> logged = new ArrayList<>();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            CamelRoadDistancePort port = portOver((origin, destination, installationId) -> {
                throw new IllegalStateException("measurement failed");
            });
            port.route(BRANCH, CUSTOMER, UUID.randomUUID());
        } finally {
            root.detachAppender(appender);
            appender.stop();
            synchronized (appender) {
                logged.addAll(appender.list);
            }
        }

        assertThat(logged).as("the failure was logged at all").isNotEmpty();
        // The command that Camel prints into an exhausted-delivery message holds both
        // points. Its toString prints neither: the generated one would put a customer's
        // location into the log aggregator on the first routing failure (ADR 0029).
        assertThat(logged).allSatisfy(event -> {
            String text = event.getFormattedMessage() + " "
                    + (event.getThrowableProxy() == null
                            ? ""
                            : event.getThrowableProxy().getMessage());
            assertThat(text)
                    .doesNotContain("41.330912")
                    .doesNotContain("69.264177")
                    .doesNotContain("41.311081");
        });
        assertThat(new RoadDistanceCommand(BRANCH, CUSTOMER, null).toString())
                .doesNotContain("41.33")
                .doesNotContain("69.26");
    }

    @Test
    @DisplayName("a route that never started is no answer and no exception")
    void aRouteThatNeverStartedIsAnEmptyAnswer() throws Exception {
        camel = new DefaultCamelContext();
        camel.start();
        CamelRoadDistancePort port = new CamelRoadDistancePort(camel.createProducerTemplate());

        // "No consumers available", which ProducerTemplate attaches to the exchange rather
        // than throwing. Read explicitly, because "the route ran and found nothing" is a
        // different and much better answer than "there is no route".
        assertThat(port.route(BRANCH, CUSTOMER, UUID.randomUUID())).isEmpty();
    }

    // -------------------------------------------------------------------- helpers

    private CamelRoadDistancePort portOver(RoadRouteMeasurer measurer) throws Exception {
        return portOver(measurer, new SimpleMeterRegistry());
    }

    private CamelRoadDistancePort portOver(RoadRouteMeasurer measurer, SimpleMeterRegistry meters) throws Exception {
        camel = new DefaultCamelContext();
        camel.addRoutes(new RoadDistanceRouteBuilder(measurer, meters));
        camel.start();
        return new CamelRoadDistancePort(camel.createProducerTemplate());
    }
}
