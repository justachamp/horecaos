package uz.horecaos.platform.pricing.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Counters for the promotion engine (ADR 0140, observability).
 *
 * <p>What an operator asks of the engine on a bad day is not "which customer" but "is anything
 * firing, and if not, why not": did the rules stop being evaluated, did they all start losing to
 * a window or a limit, did redemptions stop being claimed. These counters answer that and
 * nothing finer.
 *
 * <p><b>Bounded labels only.</b> {@code reason} is one of the {@link
 * PromotionEvaluator.Verdict} names, {@code source} one of {@code automatic} and {@code coupon},
 * {@code outcome} one of three fixed words. No tenant, brand, promotion, code, order or account
 * is ever a label: a per-promotion series is unbounded cardinality, and a per-account one is a
 * place an account id lives (ADR 0029). Which promotion is the question the ledger and the 7.9
 * report answer.
 *
 * <p><b>What is counted.</b> A rule evaluation is one candidate promotion judged in one priced
 * quote, so a cart edited five times counts five evaluations of the same rule; these are engine
 * workload and not orders. Only real quotes count. The simulator prices through the same engine
 * and counts nothing, because an author trying a rule out is not the platform pricing a basket.
 */
@Component
public class PromotionMetrics {

    /** One candidate promotion judged in one real quote. */
    public static final String EVALUATED = "horecaos.promo.rules.evaluated";

    /** An evaluation that ended with the promotion applied. */
    public static final String FIRED = "horecaos.promo.rules.fired";

    /** An evaluation that ended with the promotion not applied, labelled with the reason. */
    public static final String REFUSED = "horecaos.promo.rules.refused";

    /** A redemption claimed, or refused, when a checkout accepted its quote. */
    public static final String REDEMPTIONS = "horecaos.promo.redemptions";

    private final MeterRegistry registry;

    public PromotionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A registry nobody scrapes, for code wired by hand. */
    public static PromotionMetrics none() {
        return new PromotionMetrics(new SimpleMeterRegistry());
    }

    /** Counts the decision trace of one real quote. */
    public void evaluated(List<PromotionEvaluator.TraceEntry> trace) {
        for (PromotionEvaluator.TraceEntry entry : trace) {
            registry.counter(EVALUATED).increment();
            if (entry.verdict() == PromotionEvaluator.Verdict.APPLIED) {
                registry.counter(FIRED).increment();
            } else {
                registry.counter(REFUSED, "reason", entry.verdict().name().toLowerCase(Locale.ROOT))
                        .increment();
            }
        }
    }

    /** A redemption recorded for an order, by where it came from. */
    public void redemptionClaimed(Source source) {
        registry.counter(REDEMPTIONS, "source", source.label, "outcome", "claimed")
                .increment();
    }

    /** A checkout turned away because a limit had run out. */
    public void redemptionRefused(Source source, boolean perCustomer) {
        registry.counter(REDEMPTIONS, "source", source.label, "outcome", perCustomer ? "per_customer_limit" : "limit")
                .increment();
    }

    /** For tests: the registry these counters live in. */
    public MeterRegistry registry() {
        return registry;
    }

    /** Where a redemption came from. */
    public enum Source {
        AUTOMATIC("automatic"),
        COUPON("coupon");

        private final String label;

        Source(String label) {
            this.label = label;
        }
    }
}
