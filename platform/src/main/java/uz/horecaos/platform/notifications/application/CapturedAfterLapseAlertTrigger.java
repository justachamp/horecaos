package uz.horecaos.platform.notifications.application;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;
import uz.horecaos.platform.ordering.api.OrderCapturedAfterLapse;

/**
 * The finance worklist item for a payment that arrived after its order had ended as {@code PAYMENT_FAILED}
 * (ADR 0170 Decision 17, ADR 0058).
 *
 * <p>The order is never revived; the money is recorded on the settlement as it always was and is
 * refundable through ADR 0048's remedy. What was missing was anyone being told a refund is due, and an
 * operations alert is the platform's mechanism for exactly that. No automatic refund: Payme's refund is a
 * provider-console act the platform back-records, and a refund is a money movement a person confirms.
 *
 * <p>Keyed on the order, so a re-delivered capture fan-out resolves to one alert per chat. The variables are
 * an empty set: no amount, no name, no provider reference (ADR 0029), only the fact that this order needs
 * looking at.
 */
@Component
public class CapturedAfterLapseAlertTrigger {

    /** The semantic template key a tenant authors this alert's wording against, and its event class. */
    public static final String ORDER_CAPTURED_AFTER_LAPSE = "ORDER_CAPTURED_AFTER_LAPSE";

    static final String SUBJECT_TYPE = "Order";

    private final OperationsAlertPort operationsAlerts;
    private final Duration expiry;

    public CapturedAfterLapseAlertTrigger(
            OperationsAlertPort operationsAlerts,
            // A refund owed stays a worklist item until a person gives the money back, which can be days.
            @Value("${horecaos.notifications.telegram.captured-after-lapse-alert-expiry:P3D}") Duration expiry) {
        this.operationsAlerts = operationsAlerts;
        this.expiry = expiry;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onCapturedAfterLapse(OrderCapturedAfterLapse event) {
        operationsAlerts.fanOut(
                event.tenantId().value(),
                event.brandId(),
                event.locationId(),
                ORDER_CAPTURED_AFTER_LAPSE,
                ORDER_CAPTURED_AFTER_LAPSE,
                SUBJECT_TYPE,
                event.orderId(),
                event.eventId(),
                "%s:%s:%s".formatted(ORDER_CAPTURED_AFTER_LAPSE, SUBJECT_TYPE, event.orderId()),
                Map.of(),
                expiry);
    }
}
