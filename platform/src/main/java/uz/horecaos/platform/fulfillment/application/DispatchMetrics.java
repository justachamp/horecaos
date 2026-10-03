package uz.horecaos.platform.fulfillment.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchDecision;

/**
 * Counters for what the dispatch rules decided (ADR 0142 "Audit, events, observability").
 *
 * <p>Bounded labels only: whether a rule or the default matched, and the sourcing mode. A rule's id
 * is operator-typed free text and a tenant can write a hundred of them, so it is never a label --
 * per-rule volumes come from the {@code usage} read, which counts plans, not from the metrics store.
 */
@Component
public class DispatchMetrics {

    private final MeterRegistry registry;

    public DispatchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A recorder that counts nothing, for a caller that has no registry. */
    public static DispatchMetrics none() {
        return new DispatchMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    public void recorded(DispatchDecision decision) {
        registry.counter(
                        "horecaos.delivery.dispatch.decisions",
                        "match",
                        decision.matchedARule() ? "rule" : "default",
                        "mode",
                        decision.mode().name())
                .increment();
    }
}
