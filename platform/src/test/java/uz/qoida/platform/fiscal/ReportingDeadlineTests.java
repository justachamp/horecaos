package uz.qoida.platform.fiscal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.qoida.platform.fiscal.domain.FiscalReportingPolicy;
import uz.qoida.platform.fiscal.domain.ReportingDeadline;

/**
 * The arithmetic that decides when silence becomes a missing receipt (ADR 0038).
 *
 * <p>Pure, and tested without a database or a provider, because every way this
 * can be wrong is a way an unreceipted order becomes invisible: too late and the
 * tenant finds out at an audit, too early and the worklist fills with documents
 * whose callback was ten minutes behind.
 */
class ReportingDeadlineTests {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    @Test
    @DisplayName("the interval applies while it is inside the business date")
    void theIntervalWinsWhenItExpiresFirst() {
        Instant submitted = Instant.parse("2026-08-22T09:00:00Z");

        ReportingDeadline deadline = ReportingDeadline.of(
                submitted, null, Duration.ofMinutes(60), TASHKENT);

        assertThat(deadline.effective()).isEqualTo(Instant.parse("2026-08-22T10:00:00Z"));
        assertThat(deadline.backstopped()).isFalse();
        assertThat(deadline.passedAt(Instant.parse("2026-08-22T09:59:59Z"))).isFalse();
        assertThat(deadline.passedAt(Instant.parse("2026-08-22T10:00:00Z"))).isTrue();
    }

    @Test
    @DisplayName("the business date is an absolute ceiling on any interval")
    void theBackstopWinsWhenTheIntervalWouldOutliveTheBusinessDate() {
        // 23:30 in Tashkent on the 22nd. A twelve-hour policy would carry this into
        // the middle of the 23rd, where nobody is reconciling the 22nd any more —
        // and a tax obligation belongs to a business date.
        Instant submitted = Instant.parse("2026-08-22T18:30:00Z");

        ReportingDeadline deadline = ReportingDeadline.of(
                submitted, null, Duration.ofHours(12), TASHKENT);

        // Midnight in Tashkent, which is 19:00 UTC.
        assertThat(deadline.effective()).isEqualTo(Instant.parse("2026-08-22T19:00:00Z"));
        assertThat(deadline.backstopped()).isTrue();
    }

    @Test
    @DisplayName("the business date is the branch's, not UTC's")
    void aServiceRunningPastMidnightUtcIsNotCutInHalf() {
        // 06:00 in Tashkent on the 23rd, which is already the 23rd in UTC too — but
        // the point is the other direction: at 02:00 UTC the Tashkent day is well
        // under way, and a UTC backstop would have expired five hours into it.
        Instant submitted = Instant.parse("2026-08-23T01:00:00Z");

        ReportingDeadline utcBacked = ReportingDeadline.of(
                submitted, null, Duration.ofHours(24), ZoneId.of("UTC"));
        ReportingDeadline branchBacked = ReportingDeadline.of(
                submitted, null, Duration.ofHours(24), TASHKENT);

        assertThat(branchBacked.effective())
                .as("the branch's day ends five hours before UTC's, and that is the point")
                .isBefore(utcBacked.effective())
                .isEqualTo(Instant.parse("2026-08-23T19:00:00Z"));
    }

    @Test
    @DisplayName("a deadline already recorded on the row is honoured over the policy")
    void aRecordedDeadlineIsNotRecomputed() {
        Instant submitted = Instant.parse("2026-08-22T09:00:00Z");
        Instant recorded = Instant.parse("2026-08-22T09:15:00Z");

        ReportingDeadline deadline = ReportingDeadline.of(
                submitted, recorded, Duration.ofMinutes(60), TASHKENT);

        assertThat(deadline.effective())
                .as("a deadline set when the document was submitted must survive a later "
                        + "policy change, or the policy silently rewrites what was already owed")
                .isEqualTo(recorded);
    }

    @Test
    @DisplayName("the two providers can be given different intervals")
    void theProviderOverrideApplies() {
        FiscalReportingPolicy policy = new FiscalReportingPolicy(60,
                java.util.Map.of("CLICK", 5));

        assertThat(policy.deadlineFor("CLICK")).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.deadlineFor("PAYME")).isEqualTo(Duration.ofMinutes(60));
        assertThat(policy.deadlineFor(null))
                .as("a document with no provider answers the default rather than throwing; "
                        + "it is a cash leg, and the sweep never reaches it")
                .isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    @DisplayName("the platform default is sixty minutes for both providers")
    void thePlatformDefaultIsTheOneTheAdrStates() {
        FiscalReportingPolicy policy = FiscalReportingPolicy.platformDefault();

        assertThat(policy.deadlineFor("PAYME")).isEqualTo(Duration.ofMinutes(60));
        assertThat(policy.deadlineFor("CLICK")).isEqualTo(Duration.ofMinutes(60));
    }
}
