package uz.qoida.platform.fulfillment.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import uz.qoida.platform.fulfillment.domain.sourcing.DeliverySourcingPolicy;
import uz.qoida.platform.fulfillment.domain.sourcing.PickupPlan;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR 0014's time model against a fixed clock.
 *
 * <p>The ADR's own worked example is the two-hour preparation order, and it is
 * the first test here because it is the case the whole ADR exists for: sourcing
 * near readiness rather than at confirmation.
 */
class PickupPlanTests {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final DeliverySourcingPolicy POLICY = DeliverySourcingPolicy.DEFAULTS;

    @Test
    @DisplayName("a two-hour preparation order sources near readiness, not at confirmation")
    void twoHourPreparationSourcesNearReadiness() {
        Instant confirmed = ZonedDateTime.of(2026, 8, 24, 17, 0, 0, 0, TASHKENT).toInstant();

        PickupPlan plan = PickupPlan.forOrder(confirmed, Duration.ofHours(2), TASHKENT, POLICY);

        assertThat(plan.estimatedReadyAt())
                .isEqualTo(ZonedDateTime.of(2026, 8, 24, 19, 0, 0, 0, TASHKENT).toInstant());
        // ready - 10 minutes of in-house lead - 5 minutes of buffer.
        assertThat(plan.sourceAt())
                .as("sourcing waits until fifteen minutes before the food is ready")
                .isEqualTo(ZonedDateTime.of(2026, 8, 24, 18, 45, 0, 0, TASHKENT).toInstant());
        assertThat(plan.pickupWindowEnd())
                .isEqualTo(ZonedDateTime.of(2026, 8, 24, 19, 15, 0, 0, TASHKENT).toInstant());
        assertThat(plan.isDue(confirmed))
                .as("an order two hours from ready is not due for sourcing at confirmation")
                .isFalse();
        assertThat(plan.isDue(plan.sourceAt())).isTrue();
    }

    @Test
    @DisplayName("a short-preparation order is due immediately rather than overdue")
    void shortPreparationIsDueRatherThanOverdue() {
        Instant confirmed = Instant.parse("2026-08-24T12:00:00Z");

        PickupPlan plan = PickupPlan.forOrder(confirmed, Duration.ofMinutes(8), TASHKENT, POLICY);

        // Eight minutes of preparation is less than the fifteen minutes of lead
        // and buffer, so the naive formula puts source_at in the past — which
        // would show every quick order on an overdue queue.
        assertThat(plan.sourceAt()).isEqualTo(confirmed);
        assertThat(plan.isDue(confirmed)).isTrue();
    }

    @Test
    @DisplayName("a revised preparation estimate is measured from confirmation, not from now")
    void revisionIsMeasuredFromConfirmation() {
        Instant confirmed = Instant.parse("2026-08-24T12:00:00Z");
        PickupPlan original = PickupPlan.forOrder(confirmed, Duration.ofHours(2), TASHKENT, POLICY);

        PickupPlan revised = original.withPreparation(Duration.ofHours(3), POLICY);

        // Three hours from confirmation, not three hours from the moment the
        // kitchen got round to telling us. Otherwise a revision that arrives
        // twenty minutes late pushes the promise out by twenty minutes nobody
        // agreed to.
        assertThat(revised.estimatedReadyAt()).isEqualTo(confirmed.plus(Duration.ofHours(3)));
        assertThat(revised.confirmedAt()).isEqualTo(original.confirmedAt());
    }

    @Test
    @DisplayName("an overnight order keeps its branch timezone so the day it belongs to is answerable")
    void overnightOrderKeepsItsBranchZone() {
        // 23:40 Tashkent is 18:40 UTC on the same day; the pickup window crosses
        // local midnight. Uzbekistan has no DST, so nothing shifts under the
        // plan, but the zone is what lets an operator find it on the right day.
        Instant confirmed = ZonedDateTime.of(2026, 8, 24, 23, 40, 0, 0, TASHKENT).toInstant();

        PickupPlan plan = PickupPlan.forOrder(confirmed, Duration.ofMinutes(40), TASHKENT, POLICY);

        assertThat(plan.branchZone()).isEqualTo(TASHKENT);
        assertThat(plan.estimatedReadyAt().atZone(TASHKENT).getDayOfMonth()).isEqualTo(25);
        assertThat(plan.estimatedReadyAt().atZone(ZoneId.of("UTC")).getDayOfMonth())
                .as("the same instant is still the 24th in UTC, which is why the zone is carried")
                .isEqualTo(24);
    }

    @Test
    @DisplayName("latest assignment is past the pickup window, not at it")
    void latestAssignmentIsPastTheWindow() {
        PickupPlan plan = PickupPlan.forOrder(Instant.parse("2026-08-24T12:00:00Z"),
                Duration.ofHours(1), TASHKENT, POLICY);

        // A courier assigned one minute after the window closed still delivers
        // the order; one assigned fifteen minutes later does not, and that is an
        // operations exception rather than another sourcing retry.
        assertThat(plan.latestAssignmentAt())
                .isEqualTo(plan.pickupWindowEnd().plusSeconds(POLICY.latestAssignmentSlackSeconds()));
    }
}
