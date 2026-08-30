package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.notifications.domain.NotificationStatus;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.AttemptRow;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NotificationRow;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.StatusEventRow;

/**
 * What Operations can see and do about a message (ADR 0020).
 *
 * <p>The reason this exists is the question support actually receives: "why did
 * the customer not get their confirmation?". Answering it means showing the
 * suppression reason, the template version that was chosen, every attempt, and
 * every status the provider gave — and showing none of it for another tenant, which
 * is why every read below carries the tenant.
 *
 * <p>What an operator cannot do here is override consent. ADR 0020 is explicit that
 * a manual retry must not send a message the customer's consent refused, and the
 * retry below deliberately re-runs eligibility rather than jumping past it.
 */
@Service
public class NotificationQueryService {

    private final JdbcNotificationStore notifications;
    private final Clock clock;

    public NotificationQueryService(JdbcNotificationStore notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<NotificationDetail> detail(UUID tenantId, UUID notificationId) {
        return notifications
                .find(tenantId, notificationId)
                .map(row -> new NotificationDetail(row, attemptDetails(tenantId, row.id())));
    }

    @Transactional(readOnly = true)
    public List<NotificationRow> forOrder(UUID tenantId, UUID orderId) {
        return notifications.forSubject(tenantId, "Order", orderId);
    }

    /**
     * Puts a settled message back in the queue.
     *
     * <p>Sends it back to {@code CREATED} rather than {@code READY}, so eligibility
     * runs again from the start. That is the whole point: consent may have been
     * withdrawn since, the template may have been retired, and an operator pressing
     * retry must not be able to send something the gate would now refuse.
     *
     * <p>A message that already reached {@code DELIVERED} is not retried. It is not
     * a failure, and resending it is how a customer gets two confirmations from a
     * well-meaning support action.
     *
     * @return false when the message was not in a state a retry applies to
     */
    @Transactional
    public boolean retry(UUID tenantId, UUID notificationId, String reason) {
        Optional<NotificationRow> found = notifications.find(tenantId, notificationId);
        if (found.isEmpty()) {
            return false;
        }
        NotificationRow row = found.get();
        NotificationStatus status = NotificationStatus.valueOf(row.status());
        if (status == NotificationStatus.DELIVERED || !status.isTerminal()) {
            return false;
        }
        Instant now = clock.instant();
        return notifications.reopenForRetry(tenantId, notificationId, row.version(), reason, now);
    }

    private List<AttemptDetail> attemptDetails(UUID tenantId, UUID notificationId) {
        return notifications.attempts(tenantId, notificationId).stream()
                .map(attempt -> new AttemptDetail(attempt, notifications.statusEvents(tenantId, attempt.id())))
                .toList();
    }

    /**
     * Everything about one message, and nothing about the person it was for.
     *
     * <p>The recipient appears as an endpoint id. Turning that into a phone number
     * needs {@code CUSTOMER_PII_REVEAL} and a stated purpose in the customers
     * module, which is where that decision belongs.
     */
    public record NotificationDetail(NotificationRow notification, List<AttemptDetail> attempts) {}

    public record AttemptDetail(AttemptRow attempt, List<StatusEventRow> statusEvents) {}
}
