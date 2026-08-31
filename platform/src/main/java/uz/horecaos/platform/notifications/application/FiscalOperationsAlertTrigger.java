package uz.horecaos.platform.notifications.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.fiscal.api.FiscalDocumentBlocked;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;

/**
 * Fiscal's operations Telegram trigger (ADR 0058): a document entering
 * {@code BLOCKED} — ADR 0038's own worklist alert, one message per block
 * with the reason code.
 *
 * <p>Same placement as {@link OrderNotificationTrigger} — {@code fiscal}
 * does not depend on {@code notifications}, so a listener here importing
 * {@link FiscalDocumentBlocked} from {@code fiscal.api} is a clean one-way
 * edge (contrast {@code payments.notifications.PaymentOperationsAlertTrigger},
 * which a cycle forced out of this package). Depends on {@link
 * OperationsAlertPort} rather than {@link OperationsAlertFanoutService}
 * directly so a unit test can fake the fan-out call.
 * {@link TransactionPhase#BEFORE_COMMIT}: the block and the alert it causes
 * commit together, matching {@code FiscalDocumentService.sweepOverdueReports}'s
 * own one-transaction-per-batch shape — a batch that rolls back loses the
 * alerts it would have raised along with the blocks it would have written,
 * which is consistent rather than a new risk.
 */
@Component
public class FiscalOperationsAlertTrigger {

    /** The semantic template key a tenant authors this alert's wording against. */
    public static final String FISCAL_DOCUMENT_BLOCKED = "FISCAL_DOCUMENT_BLOCKED";

    static final String SUBJECT_TYPE = "FiscalDocument";

    private final OperationsAlertPort operationsAlerts;
    private final Duration expiry;

    public FiscalOperationsAlertTrigger(
            OperationsAlertPort operationsAlerts,
            // A blocked document stays a worklist item until an operator
            // resolves it or a late report arrives, which can be days — this
            // alert is not a warning about to expire the way an approval
            // deadline is, so its own expiry is generous rather than short.
            @Value("${horecaos.notifications.telegram.fiscal-alert-expiry:P3D}") Duration expiry) {
        this.operationsAlerts = operationsAlerts;
        this.expiry = expiry;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onDocumentBlocked(FiscalDocumentBlocked event) {
        operationsAlerts.fanOut(
                event.tenantId(),
                event.brandId(),
                event.locationId(),
                FISCAL_DOCUMENT_BLOCKED,
                FISCAL_DOCUMENT_BLOCKED,
                SUBJECT_TYPE,
                event.documentId(),
                event.eventId(),
                // Keyed on the document, not the event id: a sweep that
                // somehow reconsiders the same already-blocked document
                // (the conditional UPDATE in JdbcFiscalLifecycleStore.block
                // means it cannot, but the dedup is free insurance the same
                // way OrderNotificationTrigger takes it) must still resolve
                // to one alert.
                "%s:%s:%s".formatted(FISCAL_DOCUMENT_BLOCKED, SUBJECT_TYPE, event.documentId()),
                reasonVariables(event.reasonCode()),
                expiry);
    }

    /**
     * The entire variable set this alert ever renders with — a reason code,
     * nothing about the order's amount or the seller. Package-visible so
     * {@code TelegramOperationsMessageClassificationTests} asserts that
     * directly.
     */
    static Map<String, String> reasonVariables(String reasonCode) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("reasonCode", reasonCode);
        return variables;
    }
}
