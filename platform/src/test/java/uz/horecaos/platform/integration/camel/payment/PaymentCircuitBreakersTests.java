package uz.horecaos.platform.integration.camel.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;

/**
 * What has to be true of a payment breaker beyond "it counts failures" (ADR 0007).
 *
 * <p>The case that matters is the provider that is up and useless. It answers
 * every call, so nothing is a failure and the failure rate never moves, while
 * each answer takes long enough that the callers waiting on it are the whole
 * request pool. A breaker without a latency dimension watches that happen and
 * stays closed.
 */
class PaymentCircuitBreakersTests {

    private final PaymentCircuitBreakers breakers = new PaymentCircuitBreakers(
            new SimpleMeterRegistry(), Clock.fixed(Instant.parse("2026-08-24T09:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("a provider that answers every call but slowly opens the circuit")
    void slowSuccessesOpenTheCircuit() {
        CircuitBreaker breaker = breakers.forProvider("CLICK");

        // Successes, every one of them. Recorded through the breaker's own
        // duration API rather than by sleeping, because the threshold is ten
        // seconds and the assertion is about the configuration, not about how
        // long a test is willing to wait.
        for (int call = 0; call < 10; call++) {
            breaker.onSuccess(Duration.ofSeconds(11).toNanos(), TimeUnit.NANOSECONDS);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.getMetrics().getSlowCallRate()).isEqualTo(100.0f);
    }

    @Test
    @DisplayName("a provider answering promptly keeps the circuit closed")
    void promptSuccessesDoNotOpenTheCircuit() {
        CircuitBreaker breaker = breakers.forProvider("PAYME");

        for (int call = 0; call < 20; call++) {
            breaker.onSuccess(Duration.ofMillis(400).toNanos(), TimeUnit.NANOSECONDS);
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("an uncertain outcome still does not count as a failure")
    void uncertaintyIsNotAFailure() {
        CircuitBreaker breaker = breakers.forProvider("CLICK");

        // The rule the class exists to protect: opening on uncertainty would stop
        // the status queries that resolve it. The slow dimension must not have
        // smuggled a second way to trip on one, so these are recorded fast.
        for (int call = 0; call < 20; call++) {
            breaker.onError(
                    1,
                    TimeUnit.MILLISECONDS,
                    new PaymentCircuitBreakers.PaymentCallFailed(
                            ProviderOutcome.uncertain("READ_TIMEOUT", "no answer")));
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
