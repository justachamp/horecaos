package uz.qoida.platform.integration.camel.common;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Publishes how long each provider's circuit has been continuously open (ADR
 * 0023).
 *
 * <p>ADR 0023 alerts on a breaker that stays open for ten minutes, not on one
 * that opens. Opening is the breaker doing its job, and half a dozen a day
 * during a flaky afternoon would be a pager nobody reads. Ten minutes is
 * roughly twenty automatic half-open probes, which is long past "flaky" and
 * into "this provider is down and the restaurant should be told to take cash".
 *
 * <p>That distinction is why this publishes a duration rather than a state. An
 * alert evaluator sampling a state gauge every minute would have to remember
 * ten previous samples and would lose that memory on every restart, and a
 * breaker that flapped closed and open between two samples would read as
 * continuously open. The breaker itself knows exactly when it last entered the
 * open state, so the duration is computed where the transition happens and read
 * as a plain threshold everywhere else.
 *
 * <p>A state gauge is published alongside for the dashboard, because "open" and
 * "half open" are different things to look at during an incident and the
 * duration alone cannot distinguish them.
 *
 * <p>ADR 0029: the only label is the provider type, which is a closed set of
 * names the platform itself chose. No tenant, no binding, no merchant account.
 */
public final class ProviderCircuitMetrics {

    private static final double CLOSED = 0;
    private static final double HALF_OPEN = 1;
    private static final double OPEN = 2;
    private static final double FORCED_OR_DISABLED = 3;

    private ProviderCircuitMetrics() {
    }

    /**
     * Binds every breaker the registry holds now and every one it creates later.
     *
     * <p>The listener matters as much as the loop: breakers are created lazily on
     * first call for a provider, so a registry bound only at startup would
     * publish nothing at all until the first provider call and would then never
     * notice the second provider.
     */
    public static void bind(CircuitBreakerRegistry registry, MeterRegistry meters, String family, Clock clock) {
        // The listener goes on before the sweep, and the sweep is idempotent.
        // The other order has a window: a breaker created between the sweep and
        // the listener being attached is bound by neither and never appears.
        Set<String> bound = ConcurrentHashMap.newKeySet();
        registry.getEventPublisher()
                .onEntryAdded(event -> bindBreaker(event.getAddedEntry(), meters, family, clock, bound));
        registry.getAllCircuitBreakers().forEach(breaker -> bindBreaker(breaker, meters, family, clock, bound));
    }

    private static void bindBreaker(
            CircuitBreaker breaker, MeterRegistry meters, String family, Clock clock, Set<String> bound) {
        if (!bound.add(breaker.getName())) {
            // Registering a second gauge with the same name and tags leaves
            // Micrometer reporting whichever it saw first while the other
            // silently observes nothing, which is worse than either.
            return;
        }
        OpenSince openSince = new OpenSince(clock);
        if (breaker.getState() == CircuitBreaker.State.OPEN) {
            openSince.opened();
        }
        breaker.getEventPublisher().onStateTransition(event -> {
            if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                openSince.opened();
            } else {
                openSince.closed();
            }
        });

        Gauge.builder("qoida.provider.circuit.open.duration", openSince, OpenSince::seconds)
                .description("How long this provider's circuit has been continuously open")
                .baseUnit("seconds")
                .tag("family", family)
                .tag("provider", breaker.getName())
                .register(meters);

        Gauge.builder("qoida.provider.circuit.state", breaker, ProviderCircuitMetrics::stateValue)
                .description("0 closed, 1 half open, 2 open, 3 forced or disabled")
                .tag("family", family)
                .tag("provider", breaker.getName())
                .register(meters);
    }

    private static double stateValue(CircuitBreaker breaker) {
        return switch (breaker.getState()) {
            case CLOSED, METRICS_ONLY -> CLOSED;
            case HALF_OPEN -> HALF_OPEN;
            case OPEN -> OPEN;
            case FORCED_OPEN, DISABLED -> FORCED_OR_DISABLED;
        };
    }

    /**
     * The instant a circuit last went open, or absent while it is not open.
     *
     * <p>Held as an epoch millisecond in an {@link AtomicLong} rather than an
     * {@code Instant} field because the transition listener and the metrics
     * scrape run on different threads and neither should take a lock.
     */
    private static final class OpenSince {

        private static final long NOT_OPEN = -1L;

        private final Clock clock;
        private final AtomicLong openedAtEpochMillis = new AtomicLong(NOT_OPEN);

        private OpenSince(Clock clock) {
            this.clock = clock;
        }

        private void opened() {
            openedAtEpochMillis.compareAndSet(NOT_OPEN, clock.millis());
        }

        private void closed() {
            openedAtEpochMillis.set(NOT_OPEN);
        }

        private double seconds() {
            long openedAt = openedAtEpochMillis.get();
            return openedAt == NOT_OPEN ? 0d : (clock.millis() - openedAt) / 1000d;
        }
    }
}
