package uz.horecaos.platform.payments.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.payments.api.PaymentAttemptFailed;
import uz.horecaos.platform.payments.api.PaymentAttemptNeedsOperator;
import uz.horecaos.platform.support.RecordingOperationsAlertPort;

/** {@link PaymentOperationsAlertTrigger}. */
class PaymentOperationsAlertTriggerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();

    @Test
    void aFailedAttemptFansOutKeyedOnTheAttempt() {
        RecordingOperationsAlertPort port = new RecordingOperationsAlertPort();
        PaymentOperationsAlertTrigger trigger = new PaymentOperationsAlertTrigger(port, Duration.ofHours(2));

        trigger.onAttemptFailed(new PaymentAttemptFailed(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, ORDER, ATTEMPT, "DECLINED", Instant.now()));

        assertThat(port.calls()).hasSize(1);
        RecordingOperationsAlertPort.Call call = port.calls().get(0);
        assertThat(call.eventClass()).isEqualTo(PaymentOperationsAlertTrigger.PAYMENT_ATTEMPT_FAILED);
        assertThat(call.subjectType()).isEqualTo("PaymentAttempt");
        assertThat(call.subjectId()).isEqualTo(ATTEMPT);
        assertThat(call.variables()).containsEntry("reasonCode", "DECLINED");
        assertThat(call.idempotencyKeyBase()).contains(ATTEMPT.toString());
    }

    @Test
    void aNeedsOperatorEventFansOutWithItsOwnEventClass() {
        RecordingOperationsAlertPort port = new RecordingOperationsAlertPort();
        PaymentOperationsAlertTrigger trigger = new PaymentOperationsAlertTrigger(port, Duration.ofHours(2));

        trigger.onAttemptNeedsOperator(new PaymentAttemptNeedsOperator(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                LOCATION,
                ORDER,
                ATTEMPT,
                PaymentAttemptNeedsOperator.REASON_DEADLINE_EXCEEDED,
                Instant.now()));

        assertThat(port.calls()).hasSize(1);
        RecordingOperationsAlertPort.Call call = port.calls().get(0);
        assertThat(call.eventClass()).isEqualTo(PaymentOperationsAlertTrigger.PAYMENT_ATTEMPT_NEEDS_OPERATOR);
        assertThat(call.variables()).containsEntry("reasonCode", "DEADLINE_EXCEEDED");
    }
}
