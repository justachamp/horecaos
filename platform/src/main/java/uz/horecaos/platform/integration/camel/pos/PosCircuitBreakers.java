package uz.horecaos.platform.integration.camel.pos;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderCircuitMetrics;

/**
 * One circuit breaker per POS vendor (ADR 0007).
 *
 * <p>Per vendor rather than one for the route, for the reason the delivery
 * breakers give: Camel's route-level {@code circuitBreaker()} is a single
 * instance, so one vendor's outage would stop every restaurant on every other
 * vendor too.
 *
 * <p>What counts as a failure is where this differs from its siblings, and the
 * difference is deliberate. Uncertain outcomes are <em>not</em> recorded as
 * failures here. On a POS with no idempotency key an uncertain outcome is
 * expensive but it is not evidence that the vendor is unhealthy — a customer
 * whose export timed out at eight seconds has produced one uncertain export and
 * possibly one perfectly good order. Opening a circuit on that would stop
 * exporting for every other branch on the same vendor, converting one slow
 * response into an estate-wide loss of kitchen tickets, and every one of those
 * blocked exports would then have to be reconciled by hand as well.
 *
 * <p>Business rejections do not count either. A vendor refusing twenty malformed
 * exports is a vendor working correctly and an adapter that is wrong.
 */
@Component
public class PosCircuitBreakers {

    private final CircuitBreakerRegistry registry;

    public PosCircuitBreakers(MeterRegistry meters, Clock clock) {
        this.registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(failure -> failure instanceof PosCallFailed call && countsAsFailure(call.outcome()))
                .build());
        // ADR 0023 pages on a payment or POS breaker that stays open for ten
        // minutes, because the action — tell the restaurant its tickets are not
        // reaching the kitchen — is worth taking at noon and pointless at 3am.
        ProviderCircuitMetrics.bind(registry, meters, "pos", clock);
    }

    public CircuitBreaker forProvider(String providerType) {
        return registry.circuitBreaker(providerType);
    }

    /**
     * Only a plainly unhealthy provider. See the class note for why an uncertain
     * outcome is excluded — it is the expensive case, not the unhealthy one.
     */
    private static boolean countsAsFailure(ProviderOutcome outcome) {
        return outcome.status() == ProviderOutcome.Status.RETRYABLE;
    }

    /** Carries a classified outcome through the breaker without losing it. */
    public static final class PosCallFailed extends RuntimeException {

        private final transient ProviderOutcome outcome;

        public PosCallFailed(ProviderOutcome outcome) {
            super(outcome.errorCode(), null, false, false);
            this.outcome = outcome;
        }

        public ProviderOutcome outcome() {
            return outcome;
        }
    }
}
