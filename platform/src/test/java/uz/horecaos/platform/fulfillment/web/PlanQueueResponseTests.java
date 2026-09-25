package uz.horecaos.platform.fulfillment.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.PickupPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.PlanStatus;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingMode;
import uz.horecaos.platform.fulfillment.web.DispatchController.PlanQueueResponse;

/**
 * Row 3.1: the dispatch board's non-PII destination label reaches the queue
 * response unchanged from the plan it was snapshotted onto — no house
 * number, no flat, no phone, because {@code DeliveryPlan#destinationLabel}
 * carries none of them in the first place (see {@code
 * DeliveryDestinationTests}, which is where that guarantee is actually made).
 */
class PlanQueueResponseTests {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    @Test
    void theDestinationLabelReachesTheQueueRowUnchanged() {
        DeliveryPlan plan = plan("Yunusobod, Amir Temur ko'chasi");

        PlanQueueResponse response = PlanQueueResponse.of(plan, null);

        assertThat(response.destinationLabel()).isEqualTo("Yunusobod, Amir Temur ko'chasi");
        assertThat(response.destinationLabel()).doesNotContainPattern("\\d");
    }

    @Test
    void aPlanWithNoLabelAnswersNullRatherThanAnEmptyString() {
        DeliveryPlan plan = plan(null);

        assertThat(PlanQueueResponse.of(plan, null).destinationLabel()).isNull();
    }

    private static DeliveryPlan plan(@Nullable String destinationLabel) {
        PickupPlan pickup = new PickupPlan(
                NOW,
                Duration.ofMinutes(15),
                NOW.plus(Duration.ofMinutes(15)),
                NOW,
                NOW.plus(Duration.ofMinutes(30)),
                NOW,
                NOW.plus(Duration.ofMinutes(20)),
                ZoneOffset.UTC,
                1);
        return new DeliveryPlan(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                PlanStatus.PLANNED,
                SourcingMode.FLEET_FIRST,
                DeliveryPlan.STANDARD,
                12_000L,
                "UZS",
                null,
                pickup,
                null,
                null,
                350,
                "RADIUS",
                UUID.randomUUID(),
                1,
                1,
                destinationLabel);
    }
}
