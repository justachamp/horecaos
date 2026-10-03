package uz.horecaos.platform.dinein.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counters for the walk-in claim lifecycle (ADR 0143, Audit, events, observability).
 *
 * <p>Bounded labels only: {@code outcome} is one of {@code opened}, {@code
 * confirmed}, {@code lapsed}, {@code refused}, and {@code reason} on a refusal is
 * one of the classes {@link WalkInSeatingService.RefusalClass} names. No tenant,
 * branch, table or account is ever a label -- a per-account series would be an
 * unbounded cardinality and a place an account id lives (ADR 0029). The class of a
 * refusal is internal; the response the guest gets stays one generic answer.
 *
 * <p>A branch whose lapse rate is high is the signal to look at abuse.
 */
@Component
public class ClaimMetrics {

    public static final String CLAIMS = "horecaos.dinein.claims";

    private final MeterRegistry registry;

    public ClaimMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A registry nobody scrapes, for code wired by hand. */
    public static ClaimMetrics none() {
        return new ClaimMetrics(new SimpleMeterRegistry());
    }

    void opened() {
        registry.counter(CLAIMS, "outcome", "opened").increment();
    }

    void confirmed() {
        registry.counter(CLAIMS, "outcome", "confirmed").increment();
    }

    void lapsed() {
        registry.counter(CLAIMS, "outcome", "lapsed").increment();
    }

    void refused(String reasonClass) {
        registry.counter(CLAIMS, "outcome", "refused", "reason", reasonClass).increment();
    }

    /** For tests: the registry these counters live in. */
    public MeterRegistry registry() {
        return registry;
    }
}
