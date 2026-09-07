package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.ordering.api.PaymentRefunded;
import uz.horecaos.platform.support.RecordingCustomerAlertPort;
import uz.horecaos.platform.tenancy.api.TenantId;

/** {@link PaymentRefundNotificationTrigger}, against a recording {@link uz.horecaos.platform.notifications.api.CustomerAlertPort} fake. */
class PaymentRefundNotificationTriggerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();

    @Test
    void aRefundNotifiesTheOrdersOwnCustomer() {
        RecordingCustomerAlertPort port = new RecordingCustomerAlertPort();
        PaymentRefundNotificationTrigger trigger = new PaymentRefundNotificationTrigger(port, Duration.ofDays(3));

        trigger.onPaymentRefunded(new PaymentRefunded(UUID.randomUUID(), new TenantId(TENANT), ORDER, Instant.now()));

        assertThat(port.calls()).hasSize(1);
        RecordingCustomerAlertPort.Call call = port.calls().get(0);
        assertThat(call.tenantId()).isEqualTo(TENANT);
        assertThat(call.orderId()).isEqualTo(ORDER);
        assertThat(call.templateKey()).isEqualTo(PaymentRefundNotificationTrigger.PAYMENT_REFUNDED);
        assertThat(call.subjectType()).isEqualTo("Order");
        assertThat(call.idempotencyKey()).contains(ORDER.toString());
    }

    @Test
    void aReplayOfTheSameOrderCarriesTheSameIdempotencyKey() {
        // A fresh event id every time, exactly what a replay looks like — see
        // NotificationDeliveryTests's own orderConfirmed() factory for the
        // same idiom. Also what a second, genuinely different remedy against
        // the same order looks like on this event, which this class's own
        // Javadoc explains it deliberately still collapses to one message.
        RecordingCustomerAlertPort port = new RecordingCustomerAlertPort();
        PaymentRefundNotificationTrigger trigger = new PaymentRefundNotificationTrigger(port, Duration.ofDays(3));

        trigger.onPaymentRefunded(new PaymentRefunded(UUID.randomUUID(), new TenantId(TENANT), ORDER, Instant.now()));
        trigger.onPaymentRefunded(new PaymentRefunded(UUID.randomUUID(), new TenantId(TENANT), ORDER, Instant.now()));

        assertThat(port.calls()).hasSize(2);
        assertThat(port.calls().get(0).idempotencyKey())
                .isEqualTo(port.calls().get(1).idempotencyKey());
    }

    @Test
    void refundsOnDifferentOrdersProduceDistinctKeys() {
        RecordingCustomerAlertPort port = new RecordingCustomerAlertPort();
        PaymentRefundNotificationTrigger trigger = new PaymentRefundNotificationTrigger(port, Duration.ofDays(3));
        UUID otherOrder = UUID.randomUUID();

        trigger.onPaymentRefunded(new PaymentRefunded(UUID.randomUUID(), new TenantId(TENANT), ORDER, Instant.now()));
        trigger.onPaymentRefunded(
                new PaymentRefunded(UUID.randomUUID(), new TenantId(TENANT), otherOrder, Instant.now()));

        assertThat(port.calls().get(0).idempotencyKey())
                .isNotEqualTo(port.calls().get(1).idempotencyKey());
    }
}
