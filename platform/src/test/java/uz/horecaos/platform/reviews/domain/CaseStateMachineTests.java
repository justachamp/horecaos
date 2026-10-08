package uz.horecaos.platform.reviews.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The four-state machine and the closed list of outcomes (ADR 0159, Decision 8), written out as the
 * table the record states and compared against the code, so that adding an edge to either fails here
 * rather than being found by an operator.
 */
class CaseStateMachineTests {

    @Test
    @DisplayName("the edges are exactly the record's: start, release, resolve, close, and dismiss from NEW")
    void theEdgesAreExactlyTheRecords() {
        Map<CaseState, Set<CaseState>> expected = Map.of(
                CaseState.NEW, EnumSet.of(CaseState.IN_PROGRESS, CaseState.CLOSED_UNRESOLVED),
                CaseState.IN_PROGRESS, EnumSet.of(CaseState.NEW, CaseState.RESOLVED, CaseState.CLOSED_UNRESOLVED),
                CaseState.RESOLVED, EnumSet.noneOf(CaseState.class),
                CaseState.CLOSED_UNRESOLVED, EnumSet.noneOf(CaseState.class));

        for (CaseState from : CaseState.values()) {
            assertThat(from.targets()).as("from %s", from).isEqualTo(expected.get(from));
        }
    }

    @Test
    @DisplayName(
            "a terminal state is final: nothing leaves RESOLVED or CLOSED_UNRESOLVED, and they are the only terminals")
    void aTerminalStateIsFinal() {
        assertThat(CaseState.RESOLVED.terminal()).isTrue();
        assertThat(CaseState.CLOSED_UNRESOLVED.terminal()).isTrue();
        assertThat(CaseState.NEW.terminal()).isFalse();
        assertThat(CaseState.IN_PROGRESS.terminal()).isFalse();
        assertThat(CaseState.RESOLVED.targets()).isEmpty();
        assertThat(CaseState.CLOSED_UNRESOLVED.targets()).isEmpty();
    }

    @Test
    @DisplayName("every outcome belongs to exactly one terminal state, four and four")
    void everyOutcomeBelongsToOneTerminalState() {
        assertThat(CaseOutcome.values()).hasSize(8);
        assertThat(java.util.Arrays.stream(CaseOutcome.values()).filter(o -> o.endsIn() == CaseState.RESOLVED))
                .containsExactlyInAnyOrder(
                        CaseOutcome.REMEDY_GRANTED,
                        CaseOutcome.OFFLINE_GESTURE,
                        CaseOutcome.EXPLAINED_NO_REMEDY,
                        CaseOutcome.NO_REMEDY_NEEDED);
        assertThat(java.util.Arrays.stream(CaseOutcome.values()).filter(o -> o.endsIn() == CaseState.CLOSED_UNRESOLVED))
                .containsExactlyInAnyOrder(
                        CaseOutcome.CUSTOMER_UNREACHABLE,
                        CaseOutcome.CUSTOMER_DECLINED,
                        CaseOutcome.COMPLAINT_NOT_UPHELD,
                        CaseOutcome.NOT_ACTIONABLE);
    }

    @Test
    @DisplayName("only REMEDY_GRANTED needs a compensation behind it")
    void onlyRemedyGrantedNeedsACompensation() {
        for (CaseOutcome outcome : CaseOutcome.values()) {
            assertThat(outcome.needsCompensation()).as(outcome.name()).isEqualTo(outcome == CaseOutcome.REMEDY_GRANTED);
        }
    }

    @Test
    @DisplayName(
            "closing: from IN_PROGRESS any outcome of the target state; from NEW only NOT_ACTIONABLE, and never a resolve")
    void closingAllowsOnlyTheRightOutcomes() {
        for (CaseOutcome outcome : CaseOutcome.values()) {
            CaseState terminal = outcome.endsIn();
            assertThat(CaseState.IN_PROGRESS.mayCloseWith(terminal, outcome))
                    .as("IN_PROGRESS + %s", outcome)
                    .isTrue();
            CaseState other = terminal == CaseState.RESOLVED ? CaseState.CLOSED_UNRESOLVED : CaseState.RESOLVED;
            assertThat(CaseState.IN_PROGRESS.mayCloseWith(other, outcome))
                    .as("an outcome of %s does not close into %s", terminal, other)
                    .isFalse();

            boolean dismissal = outcome == CaseOutcome.NOT_ACTIONABLE;
            assertThat(CaseState.NEW.mayCloseWith(CaseState.CLOSED_UNRESOLVED, outcome))
                    .as("NEW + %s", outcome)
                    .isEqualTo(dismissal);
            assertThat(CaseState.NEW.mayCloseWith(CaseState.RESOLVED, outcome))
                    .as("NEW cannot be resolved without being started")
                    .isFalse();
        }
        assertThat(CaseState.RESOLVED.mayCloseWith(CaseState.CLOSED_UNRESOLVED, CaseOutcome.NOT_ACTIONABLE))
                .isFalse();
    }

    @Test
    @DisplayName(
            "a moderator's edges: PENDING either way, PUBLISHED can be hidden, HIDDEN can be restored, NOT_SHARED is untouchable")
    void theModeratorsEdges() {
        assertThat(CommentState.PENDING.moderatorTargets())
                .containsExactlyInAnyOrder(CommentState.PUBLISHED, CommentState.HIDDEN);
        assertThat(CommentState.PUBLISHED.moderatorTargets()).containsExactly(CommentState.HIDDEN);
        assertThat(CommentState.HIDDEN.moderatorTargets()).containsExactly(CommentState.PUBLISHED);
        assertThat(CommentState.NOT_SHARED.moderatorTargets())
                .as("a moderator cannot publish words their author did not offer")
                .isEmpty();
    }

    @Test
    @DisplayName("five reasons may hide a comment; the two system reasons may not be given, and both block a restore")
    void theReasonList() {
        assertThat(java.util.Arrays.stream(ModerationReason.values()).filter(ModerationReason::moderatorMayGive))
                .containsExactly(
                        ModerationReason.ABUSIVE_LANGUAGE,
                        ModerationReason.PERSONAL_DATA_OF_ANOTHER,
                        ModerationReason.NAMES_AN_INDIVIDUAL,
                        ModerationReason.SPAM_OR_UNRELATED,
                        ModerationReason.LEGAL_REQUEST);
        assertThat(ModerationReason.CUSTOMER_WITHDREW.blocksRestore()).isTrue();
        assertThat(ModerationReason.CUSTOMER_ERASED.blocksRestore()).isTrue();
        assertThat(ModerationReason.ABUSIVE_LANGUAGE.blocksRestore()).isFalse();
        assertThat(ModerationReason.parse("NEGATIVE"))
                .as("\"negative\" is not a reason")
                .isEmpty();
        assertThat(ModerationReason.parse("I_DISAGREE")).isEmpty();
    }

    @Test
    @DisplayName("tags offered follow the rating: 4-5 positive, 1-2 negative, 3 both")
    void tagsFollowTheRating() {
        assertThat(TagSentiment.offeredFor(5)).containsExactly(TagSentiment.POSITIVE);
        assertThat(TagSentiment.offeredFor(4)).containsExactly(TagSentiment.POSITIVE);
        assertThat(TagSentiment.offeredFor(3)).containsExactlyInAnyOrder(TagSentiment.POSITIVE, TagSentiment.NEGATIVE);
        assertThat(TagSentiment.offeredFor(2)).containsExactly(TagSentiment.NEGATIVE);
        assertThat(TagSentiment.offeredFor(1)).containsExactly(TagSentiment.NEGATIVE);
    }
}
