package uz.horecaos.platform.reviews.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A service-recovery case's two clocks (ADR 0159, Decision 10): at risk in the last 20% of a window,
 * breached past it, and -- the property that is easy to get wrong -- a closed case judged against when it
 * was answered or closed and never against now.
 *
 * <p>Every test below states what would still be true if the clock were wrong. A test that only reads a
 * case at its opening instant proves nothing about a duration, so each advances the instant it asks at.
 */
class CaseClocksTests {

    private static final Instant OPENED = Instant.parse("2026-10-08T10:00:00Z");

    /** First response due in 240 minutes, resolution in 72 hours: the built-in policy. */
    private static CaseClocks open() {
        return new CaseClocks(
                OPENED, OPENED.plus(Duration.ofMinutes(240)), OPENED.plus(Duration.ofMinutes(4320)), null, null);
    }

    @Test
    @DisplayName("an open, unanswered case is on track, then at risk in the last fifth of the window, then breached")
    void anOpenCaseMovesThroughItsWindow() {
        CaseClocks clocks = open();

        assertThat(clocks.firstResponse(OPENED)).isEqualTo(SlaState.ON_TRACK);
        // 80% of 240 minutes is 192: from minute 192 the first response is at risk.
        assertThat(clocks.firstResponse(OPENED.plus(Duration.ofMinutes(191)))).isEqualTo(SlaState.ON_TRACK);
        assertThat(clocks.firstResponse(OPENED.plus(Duration.ofMinutes(192)))).isEqualTo(SlaState.AT_RISK);
        assertThat(clocks.firstResponse(OPENED.plus(Duration.ofMinutes(240))))
                .as("exactly at the due time is not yet late")
                .isEqualTo(SlaState.AT_RISK);
        assertThat(clocks.firstResponse(OPENED.plus(Duration.ofMinutes(241)))).isEqualTo(SlaState.BREACHED);
    }

    @Test
    @DisplayName("starting work stops the first-response clock for good, however long the case then stays open")
    void startingWorkStopsTheFirstResponseClock() {
        CaseClocks answeredInTime = new CaseClocks(
                OPENED,
                OPENED.plus(Duration.ofMinutes(240)),
                OPENED.plus(Duration.ofMinutes(4320)),
                OPENED.plus(Duration.ofMinutes(10)),
                null);
        Instant aMonthLater = OPENED.plus(Duration.ofDays(30));

        assertThat(answeredInTime.firstResponse(aMonthLater))
                .as("answered on time stays MET; reading it a month later must not turn it late")
                .isEqualTo(SlaState.MET);
        assertThat(answeredInTime.resolution(aMonthLater))
                .as("the resolution clock is a different clock and has run out")
                .isEqualTo(SlaState.BREACHED);
        assertThat(answeredInTime.overall(OPENED.plus(Duration.ofMinutes(30))))
                .as("answered, with the resolution clock barely started: on track")
                .isEqualTo(SlaState.ON_TRACK);

        CaseClocks answeredLate = new CaseClocks(
                OPENED,
                OPENED.plus(Duration.ofMinutes(240)),
                OPENED.plus(Duration.ofMinutes(4320)),
                OPENED.plus(Duration.ofMinutes(300)),
                null);
        assertThat(answeredLate.firstResponse(OPENED.plus(Duration.ofMinutes(301))))
                .as("answered after the due time is breached, and stays so")
                .isEqualTo(SlaState.BREACHED);
    }

    @Test
    @DisplayName("a closed case is judged when it closed, not now: on time stays MET, late stays BREACHED")
    void aClosedCaseIsJudgedAtItsClose() {
        Instant firstResponseDue = OPENED.plus(Duration.ofMinutes(240));
        Instant resolutionDue = OPENED.plus(Duration.ofMinutes(4320));
        CaseClocks resolvedInTime = new CaseClocks(
                OPENED,
                firstResponseDue,
                resolutionDue,
                OPENED.plus(Duration.ofMinutes(5)),
                OPENED.plus(Duration.ofHours(2)));
        CaseClocks resolvedLate = new CaseClocks(
                OPENED,
                firstResponseDue,
                resolutionDue,
                OPENED.plus(Duration.ofMinutes(5)),
                resolutionDue.plusSeconds(1));
        Instant muchLater = OPENED.plus(Duration.ofDays(365));

        assertThat(resolvedInTime.overall(muchLater)).isEqualTo(SlaState.MET);
        assertThat(resolvedInTime.resolution(OPENED))
                .as("nor does reading it early change it")
                .isEqualTo(SlaState.MET);
        assertThat(resolvedLate.resolution(OPENED)).isEqualTo(SlaState.BREACHED);
        assertThat(resolvedLate.overall(muchLater)).isEqualTo(SlaState.BREACHED);
    }

    @Test
    @DisplayName("a case dismissed unanswered is judged on the first-response clock at the moment it was dismissed")
    void aDismissedCaseIsJudgedWhereItStopped() {
        Instant firstResponseDue = OPENED.plus(Duration.ofMinutes(240));
        CaseClocks dismissedQuickly = new CaseClocks(
                OPENED,
                firstResponseDue,
                OPENED.plus(Duration.ofMinutes(4320)),
                null,
                OPENED.plus(Duration.ofMinutes(20)));
        CaseClocks dismissedLate = new CaseClocks(
                OPENED,
                firstResponseDue,
                OPENED.plus(Duration.ofMinutes(4320)),
                null,
                OPENED.plus(Duration.ofMinutes(500)));

        assertThat(dismissedQuickly.firstResponse(OPENED.plus(Duration.ofDays(9))))
                .isEqualTo(SlaState.MET);
        assertThat(dismissedLate.firstResponse(OPENED.plus(Duration.ofDays(9)))).isEqualTo(SlaState.BREACHED);
    }

    @Test
    @DisplayName("overall is the worse of the two clocks")
    void overallIsTheWorseOfTheTwo() {
        CaseClocks clocks = open();

        // 200 minutes in: the first response is at risk, the resolution is nowhere near.
        assertThat(clocks.overall(OPENED.plus(Duration.ofMinutes(200)))).isEqualTo(SlaState.AT_RISK);
        // 300 minutes in: the first response is breached; the resolution is still on track.
        assertThat(clocks.overall(OPENED.plus(Duration.ofMinutes(300)))).isEqualTo(SlaState.BREACHED);
    }

    @Test
    @DisplayName(
            "the sweeper's predicates select only an open case that has passed a clock it has not been answered on")
    void theOverduePredicatesMatchTheSweepers() {
        CaseClocks clocks = open();
        Instant pastBoth = OPENED.plus(Duration.ofDays(10));

        assertThat(clocks.firstResponseOverdue(OPENED.plus(Duration.ofMinutes(239))))
                .isFalse();
        assertThat(clocks.firstResponseOverdue(OPENED.plus(Duration.ofMinutes(241))))
                .isTrue();
        assertThat(clocks.resolutionOverdue(pastBoth)).isTrue();

        CaseClocks answered = new CaseClocks(
                OPENED,
                OPENED.plus(Duration.ofMinutes(240)),
                OPENED.plus(Duration.ofMinutes(4320)),
                OPENED.plus(Duration.ofMinutes(1)),
                null);
        assertThat(answered.firstResponseOverdue(pastBoth))
                .as("answered: nothing to mark")
                .isFalse();

        CaseClocks closed = new CaseClocks(
                OPENED,
                OPENED.plus(Duration.ofMinutes(240)),
                OPENED.plus(Duration.ofMinutes(4320)),
                null,
                OPENED.plusSeconds(60));
        assertThat(closed.firstResponseOverdue(pastBoth))
                .as("closed before the worker came: never marked")
                .isFalse();
        assertThat(closed.resolutionOverdue(pastBoth)).isFalse();
    }
}
