package uz.horecaos.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The inbox backlog aggregate has no index to serve it, so on a large
 * {@code integration.inbox_messages} it is a sequential scan. A gauge that runs
 * one every fifteen seconds costs the database more than the number is worth,
 * and the failure it announces — order flow stalled — is one the outbox gauge
 * announces first anyway.
 *
 * <p>Backing off is only safe if the retreat is visible, because an unrefreshed
 * gauge holds its last value and a stale zero looks exactly like a healthy zero.
 * Both halves are asserted here.
 */
class MessagingBacklogDegradationTests {

    private static final Instant START = Instant.parse("2026-08-20T22:00:00Z");
    private static final Duration SLOW = Duration.ofSeconds(6);
    private static final Duration QUICK = Duration.ofMillis(40);

    @Test
    void aSlowScanSkipsPollsRatherThanRunningAgainImmediately() {
        MutableClock clock = new MutableClock(START);
        MessagingBacklogMetrics metrics = metrics(clock, new SimpleMeterRegistry());

        metrics.recordInboxScan(SLOW, true);

        assertThat(metrics.inboxScanIsDue(clock.instant()))
                .as("a scan that just cost six seconds must not be re-run on the next minute")
                .isFalse();
        clock.advance(Duration.ofMinutes(2));
        assertThat(metrics.inboxScanIsDue(clock.instant())).isTrue();
    }

    @Test
    void theBackoffClimbsButNeverPastTheAlertThreshold() {
        MutableClock clock = new MutableClock(START);
        MessagingBacklogMetrics metrics = metrics(clock, new SimpleMeterRegistry());

        Duration widest = Duration.ZERO;
        for (int round = 0; round < 10; round++) {
            metrics.recordInboxScan(SLOW, true);
            Duration waited = Duration.ZERO;
            while (!metrics.inboxScanIsDue(clock.instant())) {
                clock.advance(Duration.ofSeconds(10));
                waited = waited.plusSeconds(10);
            }
            widest = waited.compareTo(widest) > 0 ? waited : widest;
        }

        assertThat(widest)
                .as("ADR 0023 alerts on an age of fifteen minutes, so a gap approaching that "
                        + "would let a stall start and clear unobserved")
                .isLessThanOrEqualTo(Duration.ofMinutes(5));
        assertThat(widest)
                .as("the ladder has to climb, or the back-off is decoration")
                .isGreaterThan(Duration.ofMinutes(2));
    }

    @Test
    void aScanBackWithinBudgetResumesTheNormalCadence() {
        MutableClock clock = new MutableClock(START);
        MessagingBacklogMetrics metrics = metrics(clock, new SimpleMeterRegistry());

        metrics.recordInboxScan(SLOW, true);
        clock.advance(Duration.ofMinutes(3));
        metrics.recordInboxScan(QUICK, true);

        assertThat(metrics.inboxScanIsDue(clock.instant()))
                .as("an index added, or a table truncated, must take effect without a restart")
                .isTrue();
    }

    @Test
    void aFastFailureIsNotMistakenForAnExpensiveScan() {
        MutableClock clock = new MutableClock(START);
        MessagingBacklogMetrics metrics = metrics(clock, new SimpleMeterRegistry());

        // A database refusing connections answers in milliseconds. Backing off
        // from that would delay recovery for no saving at all.
        metrics.recordInboxScan(QUICK, false);

        assertThat(metrics.inboxScanIsDue(clock.instant())).isTrue();
    }

    @Test
    void figuresThatWereNotRefreshedAreReportedAsStale() {
        MutableClock clock = new MutableClock(START);
        MeterRegistry meters = new SimpleMeterRegistry();
        MessagingBacklogMetrics metrics = metrics(clock, meters);

        metrics.recordInboxScan(QUICK, true);
        clock.advance(Duration.ofMinutes(4));
        metrics.recordInboxScan(SLOW, false);

        assertThat(metrics.inboxStaleness())
                .as("a scan that produced no figures must not make the old ones look fresh")
                .isEqualTo(Duration.ofMinutes(4));
        assertThat(meters.get("horecaos.inbox.backlog.staleness").gauge().value())
                .as("without this, a stale zero is indistinguishable from a healthy zero")
                .isEqualTo(240.0);
    }

    @Test
    void theScanCostIsPublishedSoTheMissingIndexIsVisibleBeforeItHurts() {
        MutableClock clock = new MutableClock(START);
        MeterRegistry meters = new SimpleMeterRegistry();
        MessagingBacklogMetrics metrics = metrics(clock, meters);

        metrics.recordInboxScan(Duration.ofMillis(1500), true);

        assertThat(meters.get("horecaos.inbox.backlog.scan.duration").gauge().value())
                .isEqualTo(1.5);
    }

    /**
     * No data source: every method under test answers from the clock and the
     * back-off state, and a table large enough to be genuinely slow is the one
     * thing a test cannot arrange honestly.
     */
    private static MessagingBacklogMetrics metrics(Clock clock, MeterRegistry meters) {
        return new MessagingBacklogMetrics(JdbcClient.create(new JdbcTemplate()), clock, meters);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
