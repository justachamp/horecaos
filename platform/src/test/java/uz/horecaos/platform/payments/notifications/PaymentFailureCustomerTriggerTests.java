package uz.horecaos.platform.payments.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.payments.api.PaymentAttemptFailed;
import uz.horecaos.platform.support.RecordingCustomerAlertPort;

/** {@link PaymentFailureCustomerTrigger}. */
class PaymentFailureCustomerTriggerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID ATTEMPT = UUID.randomUUID();

    @Test
    void aFailedAttemptNotifiesTheOrdersOwnCustomer() {
        RecordingCustomerAlertPort port = new RecordingCustomerAlertPort();
        PaymentFailureCustomerTrigger trigger = new PaymentFailureCustomerTrigger(port, Duration.ofHours(2));

        trigger.onAttemptFailed(new PaymentAttemptFailed(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, ORDER, ATTEMPT, "DECLINED", Instant.now()));

        assertThat(port.calls()).hasSize(1);
        RecordingCustomerAlertPort.Call call = port.calls().get(0);
        assertThat(call.tenantId()).isEqualTo(TENANT);
        assertThat(call.orderId()).isEqualTo(ORDER);
        assertThat(call.templateKey()).isEqualTo(PaymentFailureCustomerTrigger.PAYMENT_FAILED);
        assertThat(call.subjectType()).isEqualTo("Order");
        assertThat(call.variables()).containsExactly(java.util.Map.entry("reasonCode", "DECLINED"));
        assertThat(call.idempotencyKey()).contains(ATTEMPT.toString());
    }

    @Test
    void aMissingReasonCodeRendersAsUnspecifiedRatherThanFailing() {
        RecordingCustomerAlertPort port = new RecordingCustomerAlertPort();
        PaymentFailureCustomerTrigger trigger = new PaymentFailureCustomerTrigger(port, Duration.ofHours(2));

        trigger.onAttemptFailed(new PaymentAttemptFailed(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, ORDER, ATTEMPT, null, Instant.now()));

        assertThat(port.calls().get(0).variables()).containsEntry("reasonCode", "UNSPECIFIED");
    }

    @Test
    void twoDeclinedAttemptsOnTheSameOrderProduceTwoDistinctKeys() {
        // The property that makes a customer told about a second, genuinely
        // different declined card rather than having it collapse into the
        // first one's idempotency key: keyed on the attempt, not the order.
        RecordingCustomerAlertPort port = new RecordingCustomerAlertPort();
        PaymentFailureCustomerTrigger trigger = new PaymentFailureCustomerTrigger(port, Duration.ofHours(2));
        UUID secondAttempt = UUID.randomUUID();

        trigger.onAttemptFailed(new PaymentAttemptFailed(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, ORDER, ATTEMPT, "DECLINED", Instant.now()));
        trigger.onAttemptFailed(new PaymentAttemptFailed(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, ORDER, secondAttempt, "INSUFFICIENT_FUNDS", Instant.now()));

        assertThat(port.calls()).hasSize(2);
        assertThat(port.calls().get(0).idempotencyKey())
                .isNotEqualTo(port.calls().get(1).idempotencyKey());
    }

    @Test
    void aReplayOfTheSameAttemptCarriesTheSameIdempotencyKey() {
        // A fresh event id every time, which is exactly what a replay looks
        // like — the same idiom NotificationDeliveryTests documents on its
        // own orderConfirmed() factory. The key this trigger derives must not
        // move with it, or a redelivered PaymentAttemptFailed becomes a second
        // message for the same declined attempt.
        RecordingCustomerAlertPort port = new RecordingCustomerAlertPort();
        PaymentFailureCustomerTrigger trigger = new PaymentFailureCustomerTrigger(port, Duration.ofHours(2));

        trigger.onAttemptFailed(new PaymentAttemptFailed(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, ORDER, ATTEMPT, "DECLINED", Instant.now()));
        trigger.onAttemptFailed(new PaymentAttemptFailed(
                UUID.randomUUID(), TENANT, BRAND, LOCATION, ORDER, ATTEMPT, "DECLINED", Instant.now()));

        assertThat(port.calls()).hasSize(2);
        assertThat(port.calls().get(0).idempotencyKey())
                .isEqualTo(port.calls().get(1).idempotencyKey());
    }
}
