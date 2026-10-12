package uz.horecaos.platform.workforce.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The roster lifecycle of ADR 0156 Decision 3, as the code states it. */
class RosterStatusTests {

    @Test
    @DisplayName("a missed entry can still be consumed: hours recorded after the sweeper ran are the fact of that plan")
    void aMissedEntryCanBeConsumed() {
        assertThat(RosterStatus.MISSED.canMoveTo(RosterStatus.CONSUMED)).isTrue();
        assertThat(RosterStatus.PUBLISHED.canMoveTo(RosterStatus.CONSUMED)).isTrue();
    }

    @Test
    @DisplayName("a missed entry goes nowhere else, and a consumed or cancelled one is final")
    void theOtherEndsAreFinal() {
        for (RosterStatus next : RosterStatus.values()) {
            if (next != RosterStatus.CONSUMED) {
                assertThat(RosterStatus.MISSED.canMoveTo(next))
                        .as("MISSED -> %s", next)
                        .isFalse();
            }
            assertThat(RosterStatus.CONSUMED.canMoveTo(next))
                    .as("CONSUMED -> %s", next)
                    .isFalse();
            assertThat(RosterStatus.CANCELLED.canMoveTo(next))
                    .as("CANCELLED -> %s", next)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a draft is published or cancelled, a published entry is consumed, missed or cancelled")
    void theWayForward() {
        assertThat(RosterStatus.DRAFT.canMoveTo(RosterStatus.PUBLISHED)).isTrue();
        assertThat(RosterStatus.DRAFT.canMoveTo(RosterStatus.CANCELLED)).isTrue();
        assertThat(RosterStatus.DRAFT.canMoveTo(RosterStatus.CONSUMED)).isFalse();
        assertThat(RosterStatus.PUBLISHED.canMoveTo(RosterStatus.MISSED)).isTrue();
        assertThat(RosterStatus.PUBLISHED.canMoveTo(RosterStatus.CANCELLED)).isTrue();
    }
}
