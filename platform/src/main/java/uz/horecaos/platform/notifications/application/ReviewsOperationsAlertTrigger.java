package uz.horecaos.platform.notifications.application;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;
import uz.horecaos.platform.reviews.api.RecoveryAlertSettings;
import uz.horecaos.platform.reviews.api.RecoveryCaseOpened;
import uz.horecaos.platform.reviews.api.RecoveryCaseSlaBreached;
import uz.horecaos.platform.reviews.api.ReviewSubmitted;

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

    private static final Logger log = LoggerFactory.getLogger(ReviewsOperationsAlertTrigger.class);

    /** The semantic template key and event class a tenant authors this alert's wording against. */
    public static final String RECOVERY_CASE_OPENED = "RECOVERY_CASE_OPENED";

    public static final String RECOVERY_CASE_SLA_BREACHED = "RECOVERY_CASE_SLA_BREACHED";

    /** ADR 0187: a review of 4 or 5 stars, and one of 1 to 3. The same names as the Telegram event classes. */
    public static final String REVIEW_POSITIVE = "REVIEW_POSITIVE";

    public static final String REVIEW_NEGATIVE = "REVIEW_NEGATIVE";

    /** The lowest rating that counts as positive; below it is negative. Closed, not a brand policy (ADR 0187). */
    static final int POSITIVE_FROM_RATING = 4;

    static final String SUBJECT_TYPE = "RecoveryCase";

    static final String REVIEW_SUBJECT_TYPE = "Review";

    private final OperationsAlertPort operationsAlerts;
    private final RecoveryAlertSettings settings;
    private final Duration expiry;
    private final Duration reviewExpiry;

    public ReviewsOperationsAlertTrigger(
            OperationsAlertPort operationsAlerts,
            RecoveryAlertSettings settings,
            // A case is a worklist item that lives for days, so its alert is not a warning about to
            // expire the way an approval deadline is.
            @Value("${horecaos.notifications.telegram.recovery-alert-expiry:P3D}") Duration expiry,
            // A review alert is news: a day later it is not worth a chat's attention.
            @Value("${horecaos.notifications.telegram.review-alert-expiry:P1D}") Duration reviewExpiry) {
        this.operationsAlerts = operationsAlerts;
        this.settings = settings;
        this.expiry = expiry;
        this.reviewExpiry = reviewExpiry;
    }

    /**
     * A review was submitted (ADR 0187, ADR 0071): told to the chats subscribed to {@code REVIEW_POSITIVE} (4 or 5 stars)
     * or {@code REVIEW_NEGATIVE} (1 to 3). There is no policy switch -- a chat that subscribed asked to hear it -- and the
     * message is the review's id and its rating and nothing else: no customer, no comment, no tag (ADR 0029). One alert
     * per review however many times the fact is replayed.
     *
     * <p>{@code AFTER_COMMIT}, unlike the recovery alerts above, which start clocks and so commit with the case: this one is
     * news. A fault in telling a chat must never fail the customer's submission, so it runs once the review is committed and
     * a failure is logged by identifier and goes no further; a review whose alert was lost is still on the reviews screen.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onReviewSubmitted(ReviewSubmitted event) {
        String eventClass = event.rating() >= POSITIVE_FROM_RATING ? REVIEW_POSITIVE : REVIEW_NEGATIVE;
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("reviewId", event.reviewId().toString());
        variables.put("rating", Integer.toString(event.rating()));
        try {
            operationsAlerts.fanOut(
                    event.tenantId(),
                    event.brandId(),
                    event.locationId(),
                    eventClass,
                    eventClass,
                    REVIEW_SUBJECT_TYPE,
                    event.reviewId(),
                    event.eventId(),
                    "%s:%s:%s".formatted(eventClass, REVIEW_SUBJECT_TYPE, event.reviewId()),
                    variables,
                    reviewExpiry);
        } catch (RuntimeException failure) {
            log.warn(
                    "A review alert could not be raised for review {}: {}",
                    event.reviewId(),
                    failure.getClass().getSimpleName());
        }
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
