package uz.horecaos.platform.integration.camel.geo;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * One breaker for the platform's one geocoder (ADR 0007, ADR 0145).
 *
 * <p>Unlike the SMS route, which has no breaker because a breaker there would stop sign-in, a
 * geocoder outage is the one failure this record asks to be survivable: ADR 0145 decision 6
 * makes it "a metric and an alert, not a checkout failure". Without a breaker every address
 * typed during an outage waits out the whole provider timeout before the field degrades to
 * plain text, which is the worst of both. With one, the first few failures open it and the
 * screens degrade at once.
 *
 * <p>One instance, not one per tenant: the credential is the platform's, so the provider's
 * health is the same fact for everybody.
 *
 * <p>Only a plainly unhealthy provider counts: {@code RETRYABLE} (a 5xx, a refused connection,
 * a 429) and {@code UNCERTAIN} (a read timeout, an unreadable answer; for a read there is
 * nothing to reconcile, so both mean the same thing). A refusal such as a wrong key is a
 * provider working correctly and says nothing about its health.
 */
@Component
public class GeoCircuitBreaker {

    private final CircuitBreaker breaker;

    public GeoCircuitBreaker(MeterRegistry meters) {
        this.breaker = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .automaticTransitionFromOpenToHalfOpenEnabled(true)
                        .recordException(failure -> failure instanceof GeoCallFailed)
                        .build())
                .circuitBreaker("geocoder");
        // 1 when not closed. No tags: there is one breaker, and a tag here would only be the
        // chance for someone to add a tenant to it.
        Gauge.builder(
                        "horecaos.geo.circuit.not_closed",
                        breaker,
                        b -> b.getState() == CircuitBreaker.State.CLOSED ? 0 : 1)
                .register(meters);
    }

    /** Whether a call may go to the provider now. A refusal here costs no network round trip. */
    public boolean tryAcquire() {
        return breaker.tryAcquirePermission();
    }

    public void onHealthy(Duration elapsed) {
        breaker.onSuccess(elapsed.toNanos(), TimeUnit.NANOSECONDS);
    }

    public void onUnhealthy(Duration elapsed, String reason) {
        breaker.onError(elapsed.toNanos(), TimeUnit.NANOSECONDS, new GeoCallFailed(reason));
    }

    /** For a permit that was taken and then not used, so a half-open probe is not leaked. */
    public void release() {
        breaker.releasePermission();
    }

    public CircuitBreaker.State state() {
        return breaker.getState();
    }

    /** Carries a failure reason through the breaker; never a message that could hold an address. */
    static final class GeoCallFailed extends RuntimeException {
        GeoCallFailed(String reason) {
            super(reason, null, false, false);
        }
    }
}
