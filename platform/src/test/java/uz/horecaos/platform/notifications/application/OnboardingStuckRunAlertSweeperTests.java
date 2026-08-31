package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.notifications.api.ControlPlaneAlert;
import uz.horecaos.platform.notifications.api.ControlPlaneAlertPort;
import uz.horecaos.platform.tenancy.api.onboarding.OnboardingStuckRunDirectory.StuckRun;

/** {@link OnboardingStuckRunAlertSweeper}. */
class OnboardingStuckRunAlertSweeperTests {

    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void everyStuckRunRaisesOneControlPlaneAlert() {
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        List<ControlPlaneAlert> raised = new ArrayList<>();

        OnboardingStuckRunAlertSweeper sweeper = new OnboardingStuckRunAlertSweeper(
                (now, minimumAge, limit) -> List.of(
                        new StuckRun(runA, tenant, NOW.minus(Duration.ofHours(1))),
                        new StuckRun(runB, tenant, NOW.minus(Duration.ofMinutes(45)))),
                raised::add,
                CLOCK,
                Duration.ofMinutes(30),
                50);

        int alerted = sweeper.runOnce();

        assertThat(alerted).isEqualTo(2);
        assertThat(raised).hasSize(2);
        assertThat(raised)
                .extracting(ControlPlaneAlert::eventClass)
                .containsExactly(
                        OnboardingStuckRunAlertSweeper.ONBOARDING_RUN_STUCK,
                        OnboardingStuckRunAlertSweeper.ONBOARDING_RUN_STUCK);
        assertThat(raised)
                .extracting(ControlPlaneAlert::subjectId)
                .containsExactlyInAnyOrder(runA.toString(), runB.toString());
        assertThat(raised.get(0).variables()).containsEntry("stuckSeconds", "3600");
    }

    @Test
    void oneFailingAlertDoesNotStopTheRestOfTheBatch() {
        UUID runA = UUID.randomUUID();
        UUID runB = UUID.randomUUID();
        List<ControlPlaneAlert> raised = new ArrayList<>();
        ControlPlaneAlertPort port = alert -> {
            if (alert.subjectId().equals(runA.toString())) {
                throw new IllegalStateException("simulated failure raising the alert for runA");
            }
            raised.add(alert);
        };

        OnboardingStuckRunAlertSweeper sweeper = new OnboardingStuckRunAlertSweeper(
                (now, minimumAge, limit) -> List.of(
                        new StuckRun(runA, UUID.randomUUID(), NOW.minus(Duration.ofHours(1))),
                        new StuckRun(runB, UUID.randomUUID(), NOW.minus(Duration.ofHours(2)))),
                port,
                CLOCK,
                Duration.ofMinutes(30),
                50);

        int alerted = sweeper.runOnce();

        assertThat(alerted)
                .as("both stuck runs were processed, even though the first failed to alert")
                .isEqualTo(2);
        assertThat(raised).extracting(ControlPlaneAlert::subjectId).containsExactly(runB.toString());
    }
}
