package uz.horecaos.platform.integration.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.integration.api.DeadLetterRecorded;
import uz.horecaos.platform.ordering.api.OrderDirectory.OrderSummary;
import uz.horecaos.platform.support.RecordingOperationsAlertPort;

/** {@link DeadLetterOperationsAlertTrigger}. */
class DeadLetterOperationsAlertTriggerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();

    @Test
    void aDeadLetterOnAnOrderFansOutOnceTheOrderResolves() {
        RecordingOperationsAlertPort port = new RecordingOperationsAlertPort();
        OrderSummary summary =
                new OrderSummary(ORDER, TENANT, BRAND, LOCATION, "A-1", null, null, "CONFIRMED", "UZS", 1000, 1);
        DeadLetterOperationsAlertTrigger trigger = new DeadLetterOperationsAlertTrigger(
                port, (tenantId, orderId) -> Optional.of(summary), Duration.ofDays(3));

        trigger.onDeadLetterRecorded(new DeadLetterRecorded(
                EVENT_ID, TENANT, DeadLetterRecorded.SOURCE_OUTBOX, "Order", ORDER, "RETRY_EXHAUSTED", Instant.now()));

        assertThat(port.calls()).hasSize(1);
        RecordingOperationsAlertPort.Call call = port.calls().get(0);
        assertThat(call.brandId()).isEqualTo(BRAND);
        assertThat(call.locationId()).isEqualTo(LOCATION);
        assertThat(call.eventClass()).isEqualTo(DeadLetterOperationsAlertTrigger.DEAD_LETTER_RECORDED);
        assertThat(call.subjectId()).isEqualTo(ORDER);
        assertThat(call.triggerEventId()).isEqualTo(EVENT_ID);
        assertThat(call.idempotencyKeyBase()).contains(EVENT_ID.toString());
        assertThat(call.variables()).containsEntry("source", "OUTBOX").containsEntry("reasonCode", "RETRY_EXHAUSTED");
    }

    @Test
    void aDeadLetterOnAnUnresolvableOrderRaisesNoAlert() {
        RecordingOperationsAlertPort port = new RecordingOperationsAlertPort();
        DeadLetterOperationsAlertTrigger trigger =
                new DeadLetterOperationsAlertTrigger(port, (tenantId, orderId) -> Optional.empty(), Duration.ofDays(3));

        trigger.onDeadLetterRecorded(new DeadLetterRecorded(
                EVENT_ID,
                TENANT,
                DeadLetterRecorded.SOURCE_INBOX,
                "Order",
                ORDER,
                "TRANSIENT_INFRASTRUCTURE",
                Instant.now()));

        assertThat(port.calls()).isEmpty();
    }

    @Test
    void aDeadLetterOnANonOrderAggregateRaisesNoAlert() {
        RecordingOperationsAlertPort port = new RecordingOperationsAlertPort();
        DeadLetterOperationsAlertTrigger trigger = new DeadLetterOperationsAlertTrigger(
                port,
                (tenantId, orderId) -> {
                    throw new AssertionError("must not even attempt to resolve a non-Order aggregate");
                },
                Duration.ofDays(3));

        trigger.onDeadLetterRecorded(new DeadLetterRecorded(
                EVENT_ID,
                TENANT,
                DeadLetterRecorded.SOURCE_OUTBOX,
                "MediaAsset",
                UUID.randomUUID(),
                "CONTRACT_UNSUPPORTED",
                Instant.now()));

        assertThat(port.calls()).isEmpty();
    }
}
