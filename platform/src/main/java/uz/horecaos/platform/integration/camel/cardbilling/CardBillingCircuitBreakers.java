package uz.horecaos.platform.integration.camel.cardbilling;

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
 * One circuit breaker per card-billing <em>installation</em> (ADR 0007, ADR 0095): HorecaOS's own
 * account with one provider, so replacing a provider never inherits the old one's open breaker.
 *
 * <p>Only a plainly unhealthy provider counts as a failure: {@code RETRYABLE}. A decline is a
 * provider working correctly, and an uncertain outcome is expensive to recover from but is not
 * evidence the provider is down.
 */
@Component
public class CardBillingCircuitBreakers {

    private final CircuitBreakerRegistry registry;

    public CardBillingCircuitBreakers(MeterRegistry meters) {
        this.registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(
                        failure -> failure instanceof CardBillingCallFailed call && countsAsFailure(call.outcome()))
                .build());
        // No labels: the count of breakers that are not closed is what an operator pages on (ADR 0029
        // keeps an installation id out of a metric).
        Gauge.builder("horecaos.cardbilling.circuit.not_closed", registry, CardBillingCircuitBreakers::notClosed)
                .register(meters);
    }

    private static double notClosed(CircuitBreakerRegistry registry) {
        return registry.getAllCircuitBreakers().stream()
                .filter(breaker -> breaker.getState() != CircuitBreaker.State.CLOSED)
                .count();
    }

    public CircuitBreaker forInstallation(UUID installationId) {
        return registry.circuitBreaker(installationId.toString());
    }

    private static boolean countsAsFailure(ProviderOutcome outcome) {
        return outcome.status() == ProviderOutcome.Status.RETRYABLE;
    }

    /** Carries a classified outcome through the breaker without losing it. */
    public static final class CardBillingCallFailed extends RuntimeException {

        private final transient ProviderOutcome outcome;

        public CardBillingCallFailed(ProviderOutcome outcome) {
            super(outcome.errorCode(), null, false, false);
            this.outcome = outcome;
        }

        public ProviderOutcome outcome() {
            return outcome;
        }
    }
}
