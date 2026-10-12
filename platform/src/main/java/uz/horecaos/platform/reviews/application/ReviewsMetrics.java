package uz.horecaos.platform.reviews.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The reviews module's counters (ADR 0159, PII, audit and observability): counters only, and no tenant
 * text in any label or log line. A tag, a comment, a note and a staff name never reach a meter -- the
 * labels are a rating, a state, a closed reason code and a clock kind.
 */
@Component
public class ReviewsMetrics {

    private final MeterRegistry registry;

    public ReviewsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void reviewSubmitted(int rating) {
        registry.counter("horecaos.reviews.submitted", "rating", Integer.toString(rating))
                .increment();
    }

    public void commentDecided(String to, String reason) {
        registry.counter("horecaos.reviews.comment.decisions", "to", to, "reason", reason)
                .increment();
    }

    public void caseEnteredState(String state) {
        registry.counter("horecaos.reviews.recovery.cases", "state", state).increment();
    }

    public void slaBreached(String kind) {
        registry.counter("horecaos.reviews.recovery.sla.breaches", "kind", kind).increment();
    }
}
