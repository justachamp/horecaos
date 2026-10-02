package uz.horecaos.platform.integration.camel.marketplace;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * One circuit breaker per marketplace <em>binding</em> (ADR 0007, ADR 0141).
 *
 * <p>Per binding rather than per provider type, which is what the POS and delivery breakers
 * use, and the difference is deliberate. A binding is one venue's credentials at one
 * aggregator: a revoked token or a venue the aggregator suspended fails every call for that
 * venue and no other, and a breaker shared across the provider type would stop forty healthy
 * venues' stop lists because one restaurant's contract lapsed. An outage of the aggregator
 * itself opens each venue's breaker in turn after a few failed calls, which costs probes and
 * not a storm — the half-open state admits three calls at a time.
 *
 * <p>Only a plainly unhealthy partner counts as a failure: {@code RETRYABLE}. A business
 * refusal is a partner working correctly, and an uncertain outcome is expensive to recover
 * from (the reconciler resends the current truth) but is not evidence the partner is down.
 */
@Component
public class MarketplaceCircuitBreakers {

    private final CircuitBreakerRegistry registry;

    public MarketplaceCircuitBreakers(MeterRegistry meters) {
        this.registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(
                        failure -> failure instanceof MarketplaceCallFailed call && countsAsFailure(call.outcome()))
                .build());
        // A gauge with no labels at all: ProviderCircuitMetrics tags each breaker with its name,
        // which for these is a binding id, and ADR 0029 keeps a binding out of a metric (an
        // unbounded tag eventually takes the registry down). The count of breakers that are not
        // closed is what an operator pages on; which venue it is, they read from the
        // propagation view.
        Gauge.builder("horecaos.marketplace.circuit.not_closed", registry, MarketplaceCircuitBreakers::notClosed)
                .register(meters);
    }

    private static double notClosed(CircuitBreakerRegistry registry) {
        return registry.getAllCircuitBreakers().stream()
                .filter(breaker -> breaker.getState() != CircuitBreaker.State.CLOSED)
                .count();
    }

    public CircuitBreaker forBinding(UUID bindingId) {
        return registry.circuitBreaker(bindingId.toString());
    }

    private static boolean countsAsFailure(ProviderOutcome outcome) {
        return outcome.status() == ProviderOutcome.Status.RETRYABLE;
    }

    /** Carries a classified outcome through the breaker without losing it. */
    public static final class MarketplaceCallFailed extends RuntimeException {

        private final transient ProviderOutcome outcome;

        public MarketplaceCallFailed(ProviderOutcome outcome) {
            super(outcome.errorCode(), null, false, false);
            this.outcome = outcome;
        }

        public ProviderOutcome outcome() {
            return outcome;
        }
    }
}
