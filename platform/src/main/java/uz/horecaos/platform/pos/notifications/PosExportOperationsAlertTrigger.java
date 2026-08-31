package uz.horecaos.platform.pos.notifications;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;
import uz.horecaos.platform.pos.api.PosExportAwaitingOperator;

/**
 * POS's operations Telegram trigger (ADR 0058): an export reaching {@code
 * AWAITING_OPERATOR}.
 *
 * <p>Lives in {@code pos}, not beside {@code OrderNotificationTrigger} in
 * {@code notifications.application} — see {@code
 * uz.horecaos.platform.integration.notifications.DeadLetterOperationsAlertTrigger}'s
 * Javadoc for the identical reasoning: {@code pos} already depends on
 * {@code integration}, which already depends on {@code notifications}, so a
 * listener in {@code notifications} importing {@link
 * PosExportAwaitingOperator} from {@code pos.api} would close a cycle
 * through {@code integration} — caught by {@code
 * ModularArchitectureTests.verifiesModuleBoundaries} during this build.
 *
 * <p>Plain {@link EventListener}, not {@code TransactionalEventListener}:
 * {@code PosOrderExportService.discoverOutcome} is deliberately not {@code
 * @Transactional} (a connection must not be held across the provider read
 * it makes), so there is no commit for a transactional listener to defer
 * to.
 */
@Component
public class PosExportOperationsAlertTrigger {

    /** The semantic template key a tenant authors this alert's wording against. */
    public static final String POS_EXPORT_AWAITING_OPERATOR = "POS_EXPORT_AWAITING_OPERATOR";

    static final String SUBJECT_TYPE = "PosExport";

    private final OperationsAlertPort operationsAlerts;
    private final Duration expiry;

    public PosExportOperationsAlertTrigger(
            OperationsAlertPort operationsAlerts,
            @Value("${horecaos.notifications.telegram.pos-export-alert-expiry:P1D}") Duration expiry) {
        this.operationsAlerts = operationsAlerts;
        this.expiry = expiry;
    }

    @EventListener
    public void onExportAwaitingOperator(PosExportAwaitingOperator event) {
        operationsAlerts.fanOut(
                event.tenantId(),
                event.brandId(),
                event.locationId(),
                POS_EXPORT_AWAITING_OPERATOR,
                POS_EXPORT_AWAITING_OPERATOR,
                SUBJECT_TYPE,
                event.exportId(),
                event.eventId(),
                // Keyed on the export: discoverOutcome's own conditional
                // UPDATE (UNCERTAIN -> AWAITING_OPERATOR) means this fires
                // at most once per export, but the key stands on its own
                // regardless — a repeat is exactly the redelivery this
                // discipline exists for.
                "%s:%s:%s".formatted(POS_EXPORT_AWAITING_OPERATOR, SUBJECT_TYPE, event.exportId()),
                variables(event.reasonCode()),
                expiry);
    }

    /**
     * The entire variable set this alert ever renders with — a reason code,
     * nothing about the order or the till. Package-visible so {@code
     * TelegramOperationsMessageClassificationTests} (in {@code
     * notifications}) asserts against a call fixed here directly, since
     * this class sits outside that module.
     */
    static Map<String, String> variables(String reasonCode) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("reasonCode", reasonCode);
        return variables;
    }
}
