package uz.horecaos.platform.payments.notifications;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;
import uz.horecaos.platform.payments.api.PaymentAttemptFailed;
import uz.horecaos.platform.payments.api.PaymentAttemptNeedsOperator;

/**
 * Payments' operations Telegram trigger (ADR 0058): an attempt reaching
 * {@code FAILED}, and an uncertain attempt whose resolver reached {@code
 * OPERATIONS_EXCEPTION} — "a human, not a retry" in {@code
 * PaymentAttemptService}'s own words.
 *
 * <p>Lives in {@code payments}, not beside {@code OrderNotificationTrigger}
 * in {@code notifications.application} — the placement {@code
 * fiscal}/{@code inventory}'s triggers use. {@code payments} already
 * depends on {@code integration} (the provider adapters, e.g. {@code
 * ClickMerchantApi}), and {@code integration} already depends on {@code
 * notifications} (the Telegram/Camel adapter layer), so a listener in
 * {@code notifications} importing {@link PaymentAttemptFailed} from
 * {@code payments.api} would close a cycle through {@code integration} —
 * caught by {@code ModularArchitectureTests.verifiesModuleBoundaries}
 * during this build. Calling {@link OperationsAlertPort} from here instead
 * is the one-way edge that already exists, transitively, just used
 * directly and for a new reason.
 *
 * <p>{@link TransactionPhase#BEFORE_COMMIT}, unaffected by the relocation:
 * both events publish from inside a {@code @Transactional} method in
 * {@code PaymentAttemptService}, and Spring's transaction synchronization
 * does not care which module a listener lives in.
 *
 * <p>Deliberately two narrow listeners rather than one switch over a common
 * type — {@code payments.api} carries no sealed event interface, the same
 * reasoning {@code PaymentFailed}/{@code PaymentCaptured} give on their own
 * Javadoc — so Spring dispatches each of these to its own listener method
 * by runtime type.
 */
@Component
public class PaymentOperationsAlertTrigger {

    /** The semantic template key a tenant authors this alert's wording against. */
    public static final String PAYMENT_ATTEMPT_FAILED = "PAYMENT_ATTEMPT_FAILED";

    public static final String PAYMENT_ATTEMPT_NEEDS_OPERATOR = "PAYMENT_ATTEMPT_NEEDS_OPERATOR";

    static final String SUBJECT_TYPE = "PaymentAttempt";

    private final OperationsAlertPort operationsAlerts;
    private final Duration expiry;

    public PaymentOperationsAlertTrigger(
            OperationsAlertPort operationsAlerts,
            // Worth sending only briefly: an operator paging through a stale
            // payment-failure alert an hour after the order was long ago
            // retried or abandoned is exactly the noise ApprovalDeadlineWarningSweeper's
            // own reasoning warns against.
            @Value("${horecaos.notifications.telegram.payment-alert-expiry:PT2H}") Duration expiry) {
        this.operationsAlerts = operationsAlerts;
        this.expiry = expiry;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onAttemptFailed(PaymentAttemptFailed event) {
        operationsAlerts.fanOut(
                event.tenantId(),
                event.brandId(),
                event.locationId(),
                PAYMENT_ATTEMPT_FAILED,
                PAYMENT_ATTEMPT_FAILED,
                SUBJECT_TYPE,
                event.attemptId(),
                event.eventId(),
                // Keyed on the attempt, not the event id: a replayed publish
                // (a redelivered provider callback re-running the same
                // decline) carries a fresh event id and must still land on
                // exactly one alert per attempt, the same reasoning
                // OrderNotificationTrigger gives for keying on orderId.
                "%s:%s:%s".formatted(PAYMENT_ATTEMPT_FAILED, SUBJECT_TYPE, event.attemptId()),
                reasonVariables(event.reasonCode()),
                expiry);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onAttemptNeedsOperator(PaymentAttemptNeedsOperator event) {
        operationsAlerts.fanOut(
                event.tenantId(),
                event.brandId(),
                event.locationId(),
                PAYMENT_ATTEMPT_NEEDS_OPERATOR,
                PAYMENT_ATTEMPT_NEEDS_OPERATOR,
                SUBJECT_TYPE,
                event.attemptId(),
                event.eventId(),
                // Keyed on the attempt alone, deliberately not on the event
                // id or the resolver pass that produced it: PaymentAttemptService
                // republishes this on every sweep that still finds the same
                // attempt past its deadline, on purpose, and this key is what
                // collapses that stream onto exactly one alert per attempt.
                "%s:%s:%s".formatted(PAYMENT_ATTEMPT_NEEDS_OPERATOR, SUBJECT_TYPE, event.attemptId()),
                reasonVariables(event.reasonCode()),
                expiry);
    }

    /**
     * The entire variable set either payments alert ever renders with — a
     * reason code, nothing about the customer or the card. Package-visible
     * so {@code TelegramOperationsMessageClassificationTests} (in {@code
     * notifications}) asserts against a call fixed here directly, since
     * this class sits outside that module.
     */
    static Map<String, String> reasonVariables(@Nullable String reasonCode) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("reasonCode", reasonCode == null ? "UNSPECIFIED" : reasonCode);
        return variables;
    }
}
