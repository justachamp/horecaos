package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.commercial.api.ArrearsDirectory.Arrear;
import uz.horecaos.platform.notifications.api.ControlPlaneAlert;

/** ADR 0089: a tenant past due too long is put in front of a person, once per review interval. */
class CommercialArrearsReviewSweeperTests {

    private static final Instant NOW = Instant.parse("2026-09-11T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void aTenantPastDueLongerThanTheIntervalIsRaisedOncePerReview() {
        UUID tenant = UUID.randomUUID();
        UUID subscription = UUID.randomUUID();
        List<Instant> askedBefore = new ArrayList<>();
        List<ControlPlaneAlert> raised = new ArrayList<>();
        CommercialArrearsReviewSweeper sweeper = new CommercialArrearsReviewSweeper(
                (before, limit) -> {
                    askedBefore.add(before);
                    return List.of(new Arrear(tenant, subscription, NOW.minus(Duration.ofDays(30))));
                },
                raised::add,
                CLOCK,
                Duration.ofDays(14),
                50);

        assertThat(sweeper.runOnce()).isEqualTo(1);

        assertThat(askedBefore).containsExactly(NOW.minus(Duration.ofDays(14)));
        assertThat(raised).singleElement().satisfies(alert -> {
            assertThat(alert.eventClass()).isEqualTo(CommercialArrearsReviewSweeper.COMMERCIAL_ARREARS_REVIEW);
            // Thirty days is the second fourteen-day review: a new incident, not the first one again.
            assertThat(alert.subjectId()).isEqualTo(subscription + "/review-2");
            assertThat(alert.variables())
                    .containsOnlyKeys("tenantId", "subscriptionId", "pastDueDays")
                    .containsEntry("pastDueDays", "30");
        });
    }

    @Test
    void aZeroIntervalIsRefusedAtStartup() {
        assertThatThrownBy(() -> new CommercialArrearsReviewSweeper(
                        (before, limit) -> List.of(), alert -> {}, CLOCK, Duration.ZERO, 50))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
