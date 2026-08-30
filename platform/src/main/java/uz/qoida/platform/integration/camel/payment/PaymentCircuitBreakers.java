package uz.qoida.platform.integration.camel.payment;

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
 * One circuit breaker per payment provider (ADR 0007).
 *
 * <p>Per provider for the reason the delivery breakers are per partner: a shared
 * breaker would stop Payme checkouts because Click was having a bad afternoon.
 *
 * <p>What counts as a failure is narrower here than on delivery, and deliberately
 * so. Only {@code RETRYABLE} counts. An {@code UNCERTAIN} payment outcome does
 * <em>not</em> open the circuit, because uncertainty on this route is often the
 * gateway's own conservative upgrade of a single lost response rather than
 * evidence that the provider is unhealthy — and opening the circuit on it would
 * stop the very {@code status_by_mti} queries that resolve the uncertainty. A
 * provider genuinely down produces connect failures, which are retryable and do
 * count.
 *
 * <p>Slow counts too, and it has to. A breaker with only a failure dimension
 * never opens on a provider that answers every call eventually: each one is a
 * success, the failure rate stays at zero, and the queue behind it grows until
 * the request threads are gone. Slow is measured rather than merely tolerated
 * because ProviderHttpClient now bounds every exchange — until it did, a hung
 * call recorded neither a failure nor a duration, because it never returned at
 * all, and no breaker configuration could have saved it.
 */
@Component
public class PaymentCircuitBreakers {

    /**
     * Above this, a call is unhealthy however it ends.
     *
     * <p>Set against what a payment presentation actually costs a customer, not
     * against a provider's SLA: Click and Payme publish none. Ten seconds is
     * already twice what a checkout should take, and a third of the transport's
     * own 30-second default deadline, so a provider degrading towards its
     * deadline trips the breaker well before every caller is sitting on one.
     */
    private static final Duration SLOW_CALL = Duration.ofSeconds(10);

    private final CircuitBreakerRegistry registry;

    public PaymentCircuitBreakers(MeterRegistry meters, Clock clock) {
        this.registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                // Higher than the failure threshold on purpose. A slow provider is
                // still a working one, and half the window being slow is ordinary
                // congestion; four in five is a provider that has stopped coping.
                .slowCallRateThreshold(80)
                .slowCallDurationThreshold(SLOW_CALL)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(failure -> failure instanceof PaymentCallFailed call
                        && countsAsFailure(call.outcome()))
                .build());
        // ADR 0023 alerts on a payment circuit that stays open for ten minutes.
        // The breaker is the only thing that knows when it went open, so the
        // duration is published from here rather than reconstructed by an alert
        // evaluator sampling a state gauge.
        ProviderCircuitMetrics.bind(registry, meters, "payment", clock);
    }

    public CircuitBreaker forProvider(String providerType) {
        return registry.circuitBreaker("payment-" + providerType);
    }

    private static boolean countsAsFailure(ProviderOutcome outcome) {
        return outcome.status() == ProviderOutcome.Status.RETRYABLE;
    }

    /** Carries a classified outcome through the breaker without losing it. */
    public static final class PaymentCallFailed extends RuntimeException {

        private final transient ProviderOutcome outcome;

        public PaymentCallFailed(ProviderOutcome outcome) {
            super(outcome.errorCode(), null, false, false);
            this.outcome = outcome;
        }

        public ProviderOutcome outcome() {
            return outcome;
        }
    }
}
