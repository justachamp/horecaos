package uz.horecaos.platform.notifications.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.notifications.domain.MoneyText;
import uz.horecaos.platform.ordering.api.TillRevisionNoticeOpened;

/**
 * Ordering's operations Telegram trigger for a weighed total the till does not hold (ADR 0058, ADR 0137, row
 * {@code 4.2c}): an order whose weight was captured after the till already held it needs a person to change
 * the till, and the branch's chat hears it once.
 *
 * <p>Same placement and same phase as {@link InventoryOperationsAlertTrigger}: {@code ordering} must not
 * depend on {@code notifications} (the edge already runs the other way), so it publishes a signal and this
 * listener, in the same transaction, raises the alert. A rolled-back weighing raises nothing.
 *
 * <p>The subject is the order, so the platform adds {@code orderNumber}, {@code amount} (the order's
 * current, weighed total) and {@code currency} itself when the message renders; the one variable this adds
 * is the total the till holds. Money and a public order number: no customer, no line, no address.
 */
@Component
public class TillRevisionAlertTrigger {

    /** The event class a chat subscribes to, and the semantic template key a tenant authors wording against. */
    public static final String TILL_REVISION_NEEDED = "TILL_REVISION_NEEDED";

    static final String SUBJECT_TYPE = "Order";

    /** A till that nobody corrected for half a day is a till somebody already knows about. */
    private static final Duration EXPIRY = Duration.ofHours(12);

    private final OperationsAlertFanoutService alerts;

    public TillRevisionAlertTrigger(OperationsAlertFanoutService alerts) {
        this.alerts = alerts;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onNoticeOpened(TillRevisionNoticeOpened event) {
        alerts.fanOut(
                event.tenantId(),
                event.brandId(),
                event.locationId(),
                TILL_REVISION_NEEDED,
                TILL_REVISION_NEEDED,
                SUBJECT_TYPE,
                event.orderId(),
                null,
                // One alert per notice: a second weighing before anyone acted updates the notice and says
                // nothing more.
                "%s:%s:%s".formatted(TILL_REVISION_NEEDED, "OrderTillRevisionNotice", event.noticeId()),
                alertVariables(event.currency(), event.tillTotalMinor()),
                EXPIRY);
    }

    /**
     * The whole variable set the alert adds to the order's own: the total the till holds, formatted. Package-
     * visible so the classification test asserts against the call fixed here.
     */
    static Map<String, String> alertVariables(String currency, long tillTotalMinor) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("tillAmount", MoneyText.format(tillTotalMinor, currency));
        return variables;
    }
}
