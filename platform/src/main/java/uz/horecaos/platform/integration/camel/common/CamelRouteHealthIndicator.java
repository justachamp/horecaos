package uz.horecaos.platform.integration.camel.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.ServiceStatus;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Whether every provider route is actually running (ADR 0007).
 *
 * <p>A stopped route is invisible without this. Camel logs a failure to start
 * one and then carries on serving HTTP perfectly, so the application reports
 * healthy while every call to {@code direct:payment.merchant-api} fails with "no
 * consumers available" — an outage that looks, from outside, exactly like a
 * provider being down. The distinction matters because the two have opposite
 * responses: one is a phone call to a partner, the other is a deploy.
 *
 * <p><strong>An open circuit is deliberately not unhealthy.</strong> ADR 0023 is
 * explicit that a breaker opening is the breaker working, and half a dozen a day
 * during a flaky afternoon is normal. Reporting that as DOWN on a single-box
 * deployment would take the only container out of the reverse proxy because one
 * courier partner was having a bad hour. Circuit state is published as a gauge by
 * {@link ProviderCircuitMetrics} and alerted on by duration, which is the right
 * shape for it.
 *
 * <p>This contributes to {@code /actuator/health} only. The liveness, readiness,
 * and customer groups list their members explicitly in {@code application.yml},
 * and none of them includes this one — a route that failed to start is an
 * operator's problem to fix forward, not a reason for the watchdog to recreate
 * the container.
 */
@Component
public class CamelRouteHealthIndicator implements HealthIndicator {

    private final CamelContext camel;

    public CamelRouteHealthIndicator(CamelContext camel) {
        this.camel = camel;
    }

    @Override
    public Health health() {
        List<Route> routes = camel.getRoutes();
        Map<String, Object> details = new LinkedHashMap<>();
        List<String> notRunning = new java.util.ArrayList<>();

        for (Route route : routes) {
            ServiceStatus status = camel.getRouteController().getRouteStatus(route.getRouteId());
            details.put(route.getRouteId(), status == null ? "UNKNOWN" : status.name());
            if (status == null || !status.isStarted()) {
                notRunning.add(route.getRouteId());
            }
        }
        details.put("routes", routes.size());

        // No routes at all is not "healthy with nothing to report": every
        // deployment of this application has provider routes, so an empty
        // context means the builders never registered.
        if (routes.isEmpty()) {
            return Health.down().withDetail("reason", "No Camel routes are registered").build();
        }
        if (!notRunning.isEmpty()) {
            return Health.down().withDetails(details).withDetail("stopped", notRunning).build();
        }
        return Health.up().withDetails(details).build();
    }
}
