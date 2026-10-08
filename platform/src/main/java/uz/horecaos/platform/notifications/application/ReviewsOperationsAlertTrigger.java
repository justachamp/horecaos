package uz.horecaos.platform.notifications.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;
import uz.horecaos.platform.reviews.api.RecoveryAlertSettings;
import uz.horecaos.platform.reviews.api.RecoveryCaseOpened;
import uz.horecaos.platform.reviews.api.RecoveryCaseSlaBreached;

/**
 * Service recovery's operations Telegram trigger (ADR 0159, Decision 10, ADR 0058): a case opened, or a
 * clock passed, announced to the chats subscribed to it.
 *
 * <p>Same placement as {@link FiscalOperationsAlertTrigger}: {@code reviews} does not depend on {@code
 * notifications}, so a listener here importing the events from {@code reviews.api} is a clean one-way
 * edge ({@code notifications -> reviews}). Off unless the brand's {@code reviews.recovery} policy sets
 * {@code alertOnOpen} or {@code alertOnBreach}: a chat can subscribe, and nothing arrives until the
 * policy says it should, because an alert nobody asked for is the noise that gets a chat muted.
 *
 * <p>The message carries ids, a clock kind and a due time and nothing else -- no customer, no comment, no
 * note, no staff name (ADR 0029). {@link TransactionPhase#BEFORE_COMMIT}: the case and the alert it
 * causes commit together.
 */
@Component
public class ReviewsOperationsAlertTrigger {

    /** The semantic template key and event class a tenant authors this alert's wording against. */
    public static final String RECOVERY_CASE_OPENED = "RECOVERY_CASE_OPENED";

    public static final String RECOVERY_CASE_SLA_BREACHED = "RECOVERY_CASE_SLA_BREACHED";

    static final String SUBJECT_TYPE = "RecoveryCase";

    private final OperationsAlertPort operationsAlerts;
    private final RecoveryAlertSettings settings;
    private final Duration expiry;

    public ReviewsOperationsAlertTrigger(
            OperationsAlertPort operationsAlerts,
            RecoveryAlertSettings settings,
            // A case is a worklist item that lives for days, so its alert is not a warning about to
            // expire the way an approval deadline is.
            @Value("${horecaos.notifications.telegram.recovery-alert-expiry:P3D}") Duration expiry) {
        this.operationsAlerts = operationsAlerts;
        this.settings = settings;
        this.expiry = expiry;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onCaseOpened(RecoveryCaseOpened event) {
        if (!settings.alertOnOpen(event.tenantId(), event.brandId())) {
            return;
        }
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("caseId", event.caseId().toString());
        variables.put("dueAt", event.firstResponseDueAt().toString());
        operationsAlerts.fanOut(
                event.tenantId(),
                event.brandId(),
                event.locationId(),
                RECOVERY_CASE_OPENED,
                RECOVERY_CASE_OPENED,
                SUBJECT_TYPE,
                event.caseId(),
                event.eventId(),
                "%s:%s:%s".formatted(RECOVERY_CASE_OPENED, SUBJECT_TYPE, event.caseId()),
                variables,
                expiry);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onSlaBreached(RecoveryCaseSlaBreached event) {
        if (!settings.alertOnBreach(event.tenantId(), event.brandId())) {
            return;
        }
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("caseId", event.caseId().toString());
        variables.put("clock", event.kind());
        variables.put("dueAt", event.dueAt().toString());
        operationsAlerts.fanOut(
                event.tenantId(),
                event.brandId(),
                event.locationId(),
                RECOVERY_CASE_SLA_BREACHED,
                RECOVERY_CASE_SLA_BREACHED,
                SUBJECT_TYPE,
                event.caseId(),
                event.eventId(),
                // One alert per case per clock: the worker marks a breach once, and a replay of the
                // same fact must not fan out a second time.
                "%s:%s:%s:%s".formatted(RECOVERY_CASE_SLA_BREACHED, SUBJECT_TYPE, event.caseId(), event.kind()),
                variables,
                expiry);
    }
}
