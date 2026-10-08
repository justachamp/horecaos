package uz.horecaos.platform.notifications.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.iam.api.terminals.TerminalAlert;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;

/**
 * The terminals' operations Telegram trigger (ADR 0058, ADR 0155): a PIN lock, and a {@code PERSON_REQUIRED} device unseen
 * during service.
 *
 * <p>Same placement as {@link InventoryOperationsAlertTrigger}: {@code iam} does not depend on {@code notifications}, so a
 * listener here importing {@link TerminalAlert} from {@code iam.api} is a clean one-way edge. Both event classes are in the
 * closed {@code TelegramEventClass} set and in {@code ck_telegram_binding_event_class}; an alert raised under a class that was
 * in neither would reach no one, which is why this record adds them.
 *
 * <p>The variables name a non-personal member reference ({@code S-0142}) and a device label the tenant chose -- never a name, a
 * phone or a PIN. The idempotency key base names the subject and the episode (the lock's instant, the device's last-seen
 * instant), so a replayed trigger reaches a chat once and a later lock alerts again.
 */
@Component
public class TerminalOperationsAlertTrigger {

    static final String SUBJECT_STAFF = "StaffMember";
    static final String SUBJECT_DEVICE = "Device";

    private final OperationsAlertPort operationsAlerts;
    private final Duration expiry;

    public TerminalOperationsAlertTrigger(
            OperationsAlertPort operationsAlerts,
            @Value("${horecaos.notifications.telegram.terminal-alert-expiry:PT12H}") Duration expiry) {
        this.operationsAlerts = operationsAlerts;
        this.expiry = expiry;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onTerminalAlert(TerminalAlert alert) {
        operationsAlerts.fanOut(
                alert.tenantId(),
                alert.brandId(),
                alert.locationId(),
                alert.eventClass(),
                alert.eventClass(),
                alert.subjectType(),
                alert.subjectId(),
                alert.eventId(),
                "%s:%s:%s:%s".formatted(alert.eventClass(), alert.subjectType(), alert.subjectId(), alert.episode()),
                variablesFor(alert),
                expiry);
    }

    /**
     * The entire variable set these alerts ever render with. Package-visible so {@code
     * TelegramOperationsMessageClassificationTests} asserts it directly.
     */
    static Map<String, String> variablesFor(TerminalAlert alert) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("deviceLabel", alert.deviceName());
        if (TerminalAlert.CREDENTIAL_LOCKED.equals(alert.eventClass())) {
            variables.put("staffReference", alert.staffReference());
            variables.put("lockRule", alert.rule());
        } else {
            variables.put("lastSeenAt", alert.episode().toString());
        }
        return variables;
    }
}
