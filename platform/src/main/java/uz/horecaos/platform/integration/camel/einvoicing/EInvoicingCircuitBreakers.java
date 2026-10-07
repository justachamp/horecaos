package uz.horecaos.platform.integration.camel.einvoicing;

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
 * One circuit breaker per e-invoicing <em>installation</em> (ADR 0007, ADR 0096).
 *
 * <p>Per installation -- HorecaOS's own account with one operator -- so Didox being down
 * never stops Faktura.uz, and a revoked credential at one operator opens one breaker. Only a
 * plainly unhealthy operator counts as a failure: {@code RETRYABLE}. A business refusal is an
 * operator working correctly, and an uncertain outcome is expensive to recover from but is
 * not evidence the operator is down.
 */
@Component
public class EInvoicingCircuitBreakers {

    private final CircuitBreakerRegistry registry;

    public EInvoicingCircuitBreakers(MeterRegistry meters) {
        this.registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(
                        failure -> failure instanceof EInvoicingCallFailed call && countsAsFailure(call.outcome()))
                .build());
        // No labels at all: the count of breakers that are not closed is what an operator pages
        // on, and which operator it is they read from the account screen (ADR 0029 keeps an
        // installation id out of a metric).
        Gauge.builder("horecaos.einvoicing.circuit.not_closed", registry, EInvoicingCircuitBreakers::notClosed)
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
    public static final class EInvoicingCallFailed extends RuntimeException {

        private final transient ProviderOutcome outcome;

        public EInvoicingCallFailed(ProviderOutcome outcome) {
            super(outcome.errorCode(), null, false, false);
            this.outcome = outcome;
        }

        public ProviderOutcome outcome() {
            return outcome;
        }
    }
}
