package uz.qoida.platform.integration.camel.delivery;

import java.time.Clock;
import java.time.Duration;

import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;

import uz.qoida.platform.integration.api.provider.ProviderOutcome;
import uz.qoida.platform.integration.camel.common.ProviderCircuitMetrics;

/**
 * One circuit breaker per courier partner (ADR 0007).
 *
 * <p>Per partner, not one for the route. A single breaker shared across every
 * adapter means a Noor outage opens the circuit and Yandex deliveries stop too —
 * turning one partner's bad afternoon into a total delivery outage, and sending
 * the operator to the wrong status page. Camel's route-level
 * {@code circuitBreaker()} is a single instance per route, which is why the
 * breaker lives here instead.
 *
 * <p>What counts as a failure is equally load-bearing. Provider faults and
 * uncertain outcomes count; business rejections do not. A partner declining
 * twenty out-of-zone addresses is a partner working correctly, and counting that
 * would take a healthy courier offline because customers kept asking for the
 * wrong thing.
 */
@Component
public class DeliveryCircuitBreakers {

    private final CircuitBreakerRegistry registry;

    public DeliveryCircuitBreakers(MeterRegistry meters, Clock clock) {
        this.registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                // Below this, a couple of early failures would open the circuit
                // on a partner that has barely been called.
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // Only outcomes that indicate the partner is unhealthy. See the
                // class note: a business "no" is not a fault.
                .recordException(failure -> failure instanceof ProviderCallFailed call
                        && countsAsFailure(call.outcome()))
                .build());
        // Published for the dashboard rather than for a page. ADR 0023's
        // stuck-circuit alert covers payment and POS breakers only: a courier
        // partner being down is a commercial conversation the operator can have
        // from the "is it working" page, and it does not stop an order being
        // taken.
        ProviderCircuitMetrics.bind(registry, meters, "delivery", clock);
    }

    /** The breaker for one provider type, created on first use. */
    public CircuitBreaker forProvider(String providerType) {
        return registry.circuitBreaker(providerType);
    }

    private static boolean countsAsFailure(ProviderOutcome outcome) {
        return outcome.status() == ProviderOutcome.Status.RETRYABLE
                || outcome.status() == ProviderOutcome.Status.UNCERTAIN;
    }

    /** Carries a classified outcome through the breaker without losing it. */
    public static final class ProviderCallFailed extends RuntimeException {

        private final transient ProviderOutcome outcome;

        public ProviderCallFailed(ProviderOutcome outcome) {
            super(outcome.errorCode(), null, false, false);
            this.outcome = outcome;
        }

        public ProviderOutcome outcome() {
            return outcome;
        }
    }
}
