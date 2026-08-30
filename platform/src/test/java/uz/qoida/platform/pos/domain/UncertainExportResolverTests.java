package uz.qoida.platform.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.qoida.platform.pos.api.CapabilitySnapshot.IdempotencyBehaviour;
import uz.qoida.platform.pos.domain.UncertainExportResolver.Outcome;

/**
 * The rule that decides whether a restaurant cooks a dinner twice (ADR 0011).
 *
 * <p>Every test here is an assertion that the resolver refuses to know something
 * it cannot know. The tempting automatic rules — one match means it landed, no
 * matches means it did not — are each represented by a test that requires them
 * <em>not</em> to fire.
 */
class UncertainExportResolverTests {

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

    @Test
    @DisplayName("one echoed correlation reference resolves without a person")
    void anEchoedReferenceIsIdentityRatherThanResemblance() {
        var decision = UncertainExportResolver.decide(
                List.of(candidate("501", true, true, true)), IdempotencyBehaviour.NONE);

        assertThat(decision.outcome()).isEqualTo(Outcome.LANDED);
        assertThat(decision.externalOrderId()).isEqualTo("501");
    }

    @Test
    @DisplayName("one heuristic match does not resolve, because a customer can order twice")
    void oneResemblingOrderIsNotProof() {
        var decision = UncertainExportResolver.decide(
                List.of(candidate("501", false, true, true)), IdempotencyBehaviour.NONE);

        assertThat(decision.outcome())
                .as("phone, time and composition all agreeing is also what a second genuine "
                        + "order from the same customer looks like")
                .isEqualTo(Outcome.OPERATOR);
        assertThat(decision.externalOrderId()).isNull();
    }

    @Test
    @DisplayName("no candidates does not mean the order is absent")
    void anEmptyReadIsNotEvidenceOfAbsence() {
        var decision = UncertainExportResolver.decide(List.of(), IdempotencyBehaviour.NONE);

        assertThat(decision.outcome())
                .as("a paged read taken seconds after a timeout can miss an order that exists; "
                        + "auto-resending on that is the same duplicate, arrived at more slowly")
                .isEqualTo(Outcome.OPERATOR);
        assertThat(decision.reason()).contains("not evidence");
    }

    @Test
    @DisplayName("two candidates carrying our reference is a person's problem, not a tie-break")
    void twoEchoedReferencesAreNotResolvedByPickingOne() {
        var decision = UncertainExportResolver.decide(
                List.of(candidate("501", true, true, true), candidate("502", true, true, true)),
                IdempotencyBehaviour.NONE);

        assertThat(decision.outcome()).isEqualTo(Outcome.OPERATOR);
        assertThat(decision.externalOrderId()).isNull();
    }

    @Test
    @DisplayName("several resembling orders name how many, so the operator sees the shape")
    void theReasonSaysWhatWasFound() {
        var decision = UncertainExportResolver.decide(
                List.of(candidate("501", false, true, true), candidate("502", false, true, false)),
                IdempotencyBehaviour.NONE);

        assertThat(decision.outcome()).isEqualTo(Outcome.OPERATOR);
        assertThat(decision.reason()).contains("2 provider order");
    }

    @Test
    @DisplayName("a provider that deduplicates needs none of this")
    void aKeyedProviderIsToldToResend() {
        var decision = UncertainExportResolver.decide(List.of(), IdempotencyBehaviour.KEYED);

        assertThat(decision.outcome())
                .as("the operator queue must empty by itself the day a provider gains a key")
                .isEqualTo(Outcome.RETRY_UNDER_KEY);
    }

    @Test
    @DisplayName("a keyed provider is answered before the candidates are even read")
    void keyedBeatsEveryHeuristic() {
        var decision = UncertainExportResolver.decide(
                List.of(candidate("501", false, true, true)), IdempotencyBehaviour.KEYED);

        assertThat(decision.outcome()).isEqualTo(Outcome.RETRY_UNDER_KEY);
    }

    private static ExportCandidate candidate(String id, boolean echoed, boolean phone,
            boolean fingerprint) {
        return new ExportCandidate(id, "PENDING", NOW, echoed, phone, fingerprint, 12);
    }
}
