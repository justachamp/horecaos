package uz.qoida.platform.integration.camel.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.camel.CamelContext;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

/**
 * ADR 0007 asks for route health. The failure it has to catch is the quiet one:
 * a route that did not start while the application reports healthy and every
 * caller gets "no consumers available", which reads from outside exactly like a
 * provider outage and has the opposite fix.
 */
class CamelRouteHealthIndicatorTests {

    private CamelContext camel;
    private CamelRouteHealthIndicator health;

    @BeforeEach
    void startContext() throws Exception {
        camel = new DefaultCamelContext();
        camel.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:health.first").routeId("health.first.v1").process(exchange -> { });
                from("direct:health.second").routeId("health.second.v1").process(exchange -> { });
            }
        });
        camel.start();
        health = new CamelRouteHealthIndicator(camel);
    }

    @AfterEach
    void stopContext() {
        camel.stop();
    }

    @Test
    void everyRouteRunningReportsUpAndNamesThem() {
        var report = health.health();

        assertThat(report.getStatus()).isEqualTo(Status.UP);
        assertThat(report.getDetails())
                .containsEntry("health.first.v1", "Started")
                .containsEntry("routes", 2);
    }

    @Test
    void aStoppedRouteIsReportedDownAndNamed() throws Exception {
        camel.getRouteController().stopRoute("health.second.v1");

        var report = health.health();

        assertThat(report.getStatus())
                .as("a stopped route serves nothing while the application still answers HTTP")
                .isEqualTo(Status.DOWN);
        assertThat(report.getDetails()).containsEntry("stopped", java.util.List.of("health.second.v1"));
    }

    @Test
    void anEmptyContextIsDownRatherThanQuietlyHealthy() {
        CamelContext empty = new DefaultCamelContext();
        empty.start();
        try {
            assertThat(new CamelRouteHealthIndicator(empty).health().getStatus())
                    .as("no routes at all means the builders never registered, not that "
                            + "there was nothing to check")
                    .isEqualTo(Status.DOWN);
        } finally {
            empty.stop();
        }
    }
}
