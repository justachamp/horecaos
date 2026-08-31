package uz.horecaos.platform.integration.camel.notification.telegram;

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
 * The platform-wide Telegram breaker (ADR 0058).
 *
 * <p>One breaker for the whole Bot API, not per binding: ADR 0058 is explicit
 * that this distinguishes "the Bot API itself is unreachable" from "one chat is
 * broken" (403, a deleted topic), and the second is not a provider fault at all
 * — it is handled entirely by {@link TelegramBindingStore#retire} and must never
 * open this circuit, exactly as {@code DeliveryCircuitBreakers} already
 * excludes a business rejection from counting against a courier partner.
 *
 * <p>Its alert is {@link ProviderCircuitMetrics}'s open-duration gauge — the same
 * mechanism ADR 0023 already uses for the payment and POS breakers, extended
 * here to Telegram. That satisfies ADR 0058's "raises its alert over a
 * non-Telegram channel" by construction: a Prometheus gauge cannot ride the
 * transport it is reporting on being down. {@code ops/bands.yaml} carries the
 * control-band entry that turns a sustained open circuit into an escalation.
 */
@Component
public class TelegramCircuitBreakers {

    /** The one provider type this breaker family ever sees. */
    static final String PROVIDER_TYPE = "TELEGRAM_BOT_API";

    private final CircuitBreakerRegistry registry;

    public TelegramCircuitBreakers(MeterRegistry meters, Clock clock) {
        this.registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordException(
                        failure -> failure instanceof ProviderCallFailed call && countsAsFailure(call.outcome()))
                .build());
        ProviderCircuitMetrics.bind(registry, meters, "telegram-notification", clock);
    }

    public CircuitBreaker forBotApi() {
        return registry.circuitBreaker(PROVIDER_TYPE);
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
