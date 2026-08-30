package uz.qoida.platform.integration.camel.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0023 pages on a payment circuit that has been open for ten minutes, and
 * this is where the ten minutes is measured.
 *
 * <p>The alert is not "the circuit is open" — that is the breaker working, and
 * half a dozen of them on a flaky afternoon would be a pager nobody reads. So
 * what the alert evaluator reads has to be a duration, and a duration is only
 * correct if it resets when the circuit closes. A gauge that kept counting
 * across a close-then-open would report ten minutes for a provider that had been
 * fine for nine of them.
 */
class ProviderCircuitMetricsTests {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MovableClock clock = new MovableClock(Instant.parse("2026-08-23T09:00:00Z"));

    @Test
    @DisplayName("a breaker created after binding is still measured")
    void breakersCreatedLaterAreBound() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults());
        ProviderCircuitMetrics.bind(registry, meters, "payment", clock);

        // Breakers are created lazily on the first call for a provider, so a
        // binding that only swept the registry at startup would publish nothing
        // at all until then and would never notice the second provider.
        registry.circuitBreaker("payment-payme");

        assertThat(meters.find("qoida.provider.circuit.state")
                .tag("provider", "payment-payme").gauge()).isNotNull();
    }

    @Test
    @DisplayName("the open duration counts from the transition and resets on close")
    void openDurationTracksTheTransition() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.ofDefaults());
        ProviderCircuitMetrics.bind(registry, meters, "payment", clock);
        CircuitBreaker breaker = registry.circuitBreaker("payment-click");

        assertThat(openDuration()).isZero();

        breaker.transitionToOpenState();
        clock.advance(Duration.ofMinutes(11));

        assertThat(openDuration())
                .as("past ten minutes is roughly twenty failed half-open probes")
                .isGreaterThan(600d);
        assertThat(state()).isEqualTo(2d);

        breaker.transitionToHalfOpenState();
        assertThat(openDuration())
                .as("a circuit that is probing its way back is not one that is stuck")
                .isZero();
        assertThat(state()).isEqualTo(1d);

        breaker.transitionToOpenState();
        clock.advance(Duration.ofMinutes(1));
        assertThat(openDuration())
                .as("the second opening starts its own clock, so a flapping provider never reads as stuck")
                .isLessThan(120d);
    }

    private double openDuration() {
        return meters.get("qoida.provider.circuit.open.duration")
                .tag("provider", "payment-click").gauge().value();
    }

    private double state() {
        return meters.get("qoida.provider.circuit.state")
                .tag("provider", "payment-click").gauge().value();
    }

    /** A clock the test moves, so that eleven minutes costs no wall time. */
    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
