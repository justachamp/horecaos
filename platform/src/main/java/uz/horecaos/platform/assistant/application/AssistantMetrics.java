package uz.horecaos.platform.assistant.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.assistant.api.TokenUsage;
import uz.horecaos.platform.assistant.domain.RefusalReason;
import uz.horecaos.platform.assistant.domain.TurnOutcome;

/**
 * The assistant's operational metrics (ADR 0069: "tokens, latency, refusal rate,
 * escalation rate and spend").
 *
 * <p>Every tag is a code from a closed set -- an outcome, a refusal reason, a
 * direction. None is a tenant, a conversation, a customer or a word of anything
 * anyone wrote: ADR 0029 keeps personal data out of metrics, and a tenant id as a
 * tag would be unbounded cardinality in the Prometheus the platform runs. The
 * per-tenant figures the ADR wants -- tokens and spend by tenant -- are in the
 * ledger, {@code assistant.turns}, where a tenant's id is a column and not a
 * label.
 */
@Component
public class AssistantMetrics {

    static final String TURNS = "horecaos.assistant.turns";
    static final String MODEL_LATENCY = "horecaos.assistant.model.latency";
    static final String MODEL_TOKENS = "horecaos.assistant.model.tokens";
    static final String MODEL_FAILURES = "horecaos.assistant.model.failures";
    static final String SPEND = "horecaos.assistant.spend.usd_micros";

    private final MeterRegistry registry;

    AssistantMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void turn(TurnOutcome outcome, @Nullable RefusalReason reason, boolean servedFromCache) {
        registry.counter(
                        TURNS,
                        "outcome",
                        outcome.name(),
                        "reason",
                        reason == null ? "NONE" : reason.name(),
                        "cache",
                        servedFromCache ? "hit" : "miss")
                .increment();
    }

    void modelCall(Duration latency, TokenUsage usage, long costUsdMicros) {
        Timer.builder(MODEL_LATENCY).register(registry).record(latency);
        registry.counter(MODEL_TOKENS, "direction", "input").increment(usage.inputTokens());
        registry.counter(MODEL_TOKENS, "direction", "output").increment(usage.outputTokens());
        registry.counter(SPEND).increment(costUsdMicros);
    }

    void modelFailure(String code, boolean retryable) {
        registry.counter(MODEL_FAILURES, "code", code, "retryable", Boolean.toString(retryable))
                .increment();
    }
}
