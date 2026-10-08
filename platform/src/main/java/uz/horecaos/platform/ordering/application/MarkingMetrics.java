package uz.horecaos.platform.ordering.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@code horecaos.marking.capture} (ADR 0161): how many scans were accepted or refused, and at which stage.
 *
 * <p>Bounded labels only, and only these two: {@code outcome} is {@code accepted} or {@code refused} and
 * {@code stage} is {@code PICK}, {@code HANDOVER} or {@code none} (refused before the order was read). No
 * GTIN, no order, no tenant and no code is ever a label -- a per-code series is unbounded cardinality and a
 * place a code lives (ADR 0029).
 */
@Component
public class MarkingMetrics {

    public static final String CAPTURE = "horecaos.marking.capture";

    private final MeterRegistry registry;

    public MarkingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A registry nobody scrapes, for code wired by hand. */
    public static MarkingMetrics none() {
        return new MarkingMetrics(new SimpleMeterRegistry());
    }

    void accepted(String stage) {
        registry.counter(CAPTURE, "outcome", "accepted", "stage", stage).increment();
    }

    void refused(String stage) {
        registry.counter(CAPTURE, "outcome", "refused", "stage", stage).increment();
    }

    /** For tests: the registry these counters live in. */
    public MeterRegistry registry() {
        return registry;
    }
}
