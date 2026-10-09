package uz.horecaos.platform.reviews.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which reviews open a case and how long staff have (ADR 0159, Decisions 8 and 10). */
class RecoveryPolicyDocumentTests {

    @Test
    @DisplayName("the built-in policy: 2 stars or less, 240 minutes to first touch, 72 hours to resolve, no alerts")
    void theBuiltInPolicy() {
        RecoveryPolicyDocument policy = RecoveryPolicyDocument.builtInDefault();

        assertThat(policy.autoOpenMaxRating()).isEqualTo(2);
        assertThat(policy.firstResponseMinutes()).isEqualTo(240);
        assertThat(policy.resolutionMinutes()).isEqualTo(4320);
        assertThat(policy.alertOnOpen()).isFalse();
        assertThat(policy.alertOnBreach()).isFalse();
        assertThat(policy.violations()).isEmpty();
    }

    @Test
    @DisplayName("a rating at the threshold opens a case, one above it does not, and zero means never")
    void theThresholdIsInclusive() {
        RecoveryPolicyDocument two = RecoveryPolicyDocument.builtInDefault();
        assertThat(two.opensFor(1)).isTrue();
        assertThat(two.opensFor(2)).as("at the threshold").isTrue();
        assertThat(two.opensFor(3)).as("above the threshold").isFalse();

        RecoveryPolicyDocument never = new RecoveryPolicyDocument(0, 240, 4320, false, false);
        for (int rating = 1; rating <= 5; rating++) {
            assertThat(never.opensFor(rating))
                    .as("autoOpenMaxRating 0, rating %d", rating)
                    .isFalse();
        }

        RecoveryPolicyDocument everything = new RecoveryPolicyDocument(5, 240, 4320, false, false);
        assertThat(everything.opensFor(5)).isTrue();
    }

    @Test
    @DisplayName("the clocks run on wall time from the opening instant")
    void theClocksAreWallClock() {
        RecoveryPolicyDocument policy = RecoveryPolicyDocument.builtInDefault();
        Instant opened = Instant.parse("2026-10-08T22:00:00Z");

        assertThat(policy.firstResponseDueAt(opened)).isEqualTo(Instant.parse("2026-10-09T02:00:00Z"));
        assertThat(policy.resolutionDueAt(opened))
                .as("72 hours later, overnight and a weekend included: no business-hours calendar")
                .isEqualTo(Instant.parse("2026-10-11T22:00:00Z"));
    }

    @Test
    @DisplayName("a policy that breaks a bound is refused, and resolution may not be shorter than first response")
    void violations() {
        assertThat(new RecoveryPolicyDocument(6, 240, 4320, false, false).violations())
                .anySatisfy(v -> assertThat(v).contains("autoOpenMaxRating"));
        assertThat(new RecoveryPolicyDocument(2, 14, 4320, false, false).violations())
                .anySatisfy(v -> assertThat(v).contains("firstResponseMinutes must be between 15 and 10080"));
        assertThat(new RecoveryPolicyDocument(2, 10081, 43200, false, false).violations())
                .anySatisfy(v -> assertThat(v).contains("firstResponseMinutes"));
        assertThat(new RecoveryPolicyDocument(2, 240, 59, false, false).violations())
                .anySatisfy(v -> assertThat(v).contains("resolutionMinutes must be between 60 and 43200"));
        assertThat(new RecoveryPolicyDocument(2, 600, 300, false, false).violations())
                .anySatisfy(v -> assertThat(v).contains("must not be shorter"));
        assertThat(new RecoveryPolicyDocument(0, 15, 60, true, true).violations())
                .as("the smallest legal policy")
                .isEmpty();
    }
}
