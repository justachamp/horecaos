package uz.horecaos.platform.customers.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * ADR 0111 §4's status machine, as a table rather than a diagram.
 *
 * <p>The record draws {@code NEW -> CONTACTED -> CALLBACK_SCHEDULED -> CONVERTED} and {@code NEW |
 * CONTACTED | CALLBACK_SCHEDULED -> DECLINED | LOST}. Every one of the thirty-six pairs is asserted
 * below, so adding a state or an edge fails here until somebody says which it is.
 */
class LeadStatusTests {

    private static final Set<LeadStatus> OPEN =
            EnumSet.of(LeadStatus.NEW, LeadStatus.CONTACTED, LeadStatus.CALLBACK_SCHEDULED);

    @ParameterizedTest
    @EnumSource(LeadStatus.class)
    void aTerminalStateHasNoWayOut(LeadStatus from) {
        if (OPEN.contains(from)) {
            return;
        }
        for (LeadStatus to : LeadStatus.values()) {
            assertThat(from.canMoveTo(to))
                    .as("%s is finished: the next contact with that guest is a new lead", from)
                    .isFalse();
        }
        assertThat(from.isOpen()).isFalse();
        assertThat(from.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(LeadStatus.class)
    void nothingMovesBackIntoNew(LeadStatus from) {
        assertThat(from.canMoveTo(LeadStatus.NEW)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(LeadStatus.class)
    void everyOpenStateCanEndInAnyOfTheThreeEnds(LeadStatus from) {
        if (!OPEN.contains(from)) {
            return;
        }
        assertThat(from.canMoveTo(LeadStatus.CONVERTED)).isTrue();
        assertThat(from.canMoveTo(LeadStatus.DECLINED)).isTrue();
        assertThat(from.canMoveTo(LeadStatus.LOST)).isTrue();
    }

    @Test
    void theRecordsOwnArrowsAreAllThere() {
        assertThat(LeadStatus.NEW.canMoveTo(LeadStatus.CONTACTED)).isTrue();
        assertThat(LeadStatus.CONTACTED.canMoveTo(LeadStatus.CALLBACK_SCHEDULED))
                .isTrue();
        assertThat(LeadStatus.CALLBACK_SCHEDULED.canMoveTo(LeadStatus.CONVERTED))
                .isTrue();
    }

    @Test
    void aCallbackCanBeScheduledFromNewAndRescheduledWhilePending() {
        assertThat(LeadStatus.NEW.canMoveTo(LeadStatus.CALLBACK_SCHEDULED))
                .as("a guest who asked to be rung at six")
                .isTrue();
        assertThat(LeadStatus.CALLBACK_SCHEDULED.canMoveTo(LeadStatus.CALLBACK_SCHEDULED))
                .as("a reschedule")
                .isTrue();
    }

    @Test
    void contactedIsReachedOnceAndFromAPendingCallbackOnly() {
        assertThat(LeadStatus.NEW.canMoveTo(LeadStatus.CONTACTED)).isTrue();
        assertThat(LeadStatus.CALLBACK_SCHEDULED.canMoveTo(LeadStatus.CONTACTED))
                .as("the callback was made and the guest has not yet decided")
                .isTrue();
        assertThat(LeadStatus.CONTACTED.canMoveTo(LeadStatus.CONTACTED))
                .as("CONTACTED is not a loop")
                .isFalse();
    }

    @Test
    void exactlyThreeStatesAreOpen() {
        assertThat(EnumSet.allOf(LeadStatus.class).stream().filter(LeadStatus::isOpen))
                .containsExactlyInAnyOrderElementsOf(OPEN);
    }
}
