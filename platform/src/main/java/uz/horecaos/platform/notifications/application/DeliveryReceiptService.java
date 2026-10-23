package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.marketing.api.DeliverabilityFeedbackPort;
import uz.horecaos.platform.notifications.api.DeliveryReceiptPort;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore.ReceiptAttempt;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.StatusEventRow;

/**
 * Applying a gateway's delivery receipt to the attempt it is about (ADR 0146
 * Decisions 4 to 6).
 *
 * <p>A receipt is evidence about something this platform already sent, and the
 * rules below keep it that way.
 *
 * <ul>
 *   <li><strong>It must name one of our attempts.</strong> Matched on the
 *       provider's message id under the bindings of the installation the callback
 *       arrived on. An id nobody here sent creates nothing and is only counted: a
 *       callback is not a way to write data.
 *   <li><strong>It may only advance.</strong> A state has a rank, and a receipt
 *       whose rank does not exceed the attempt's current one is a no-op, so
 *       {@code Delivered} followed by a late {@code Sent} leaves the attempt
 *       delivered. {@code Delivered} and {@code Failed} are final.
 *   <li><strong>It never causes a resend.</strong> Nothing here touches the
 *       notification's own status or schedules anything: a failed receipt is often
 *       terminal (a blacklist, a bad number), and a retry is a second message and a
 *       second charge.
 *   <li><strong>Only a hard bounce suppresses.</strong> And only through the
 *       receipt that matched, so a forged id cannot silence a customer.
 * </ul>
 *
 * <p>Nothing here holds a number or a body: a receipt carries neither.
 */
@Service
public class DeliveryReceiptService implements DeliveryReceiptPort {

    private static final Logger log = LoggerFactory.getLogger(DeliveryReceiptService.class);

    /** Bounded reason codes written to {@code failure_code}; never the provider's text. */
    static final String FAILURE_REPORTED = "PROVIDER_REPORTED_FAILURE";

    static final String FAILURE_UNREACHABLE = "RECEIVER_UNREACHABLE";

    private final JdbcDeliveryReceiptStore receipts;
    private final JdbcNotificationStore notifications;
    private final DeliverabilityFeedbackPort feedback;
    private final Clock clock;

    public DeliveryReceiptService(
            JdbcDeliveryReceiptStore receipts,
            JdbcNotificationStore notifications,
            DeliverabilityFeedbackPort feedback,
            Clock clock) {
        this.receipts = receipts;
        this.notifications = notifications;
        this.feedback = feedback;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ReceiptDisposition apply(ReceiptCommand receipt) {
        return applyTo(receipt);
    }

    /**
     * Applies what a pull learned (ADR 0146 Decision 5's sweeper), through the same
     * rules as a callback: the answer is evidence about an attempt of ours, it may
     * only advance it, and it is recorded as a status event like any other. The
     * installation is not part of the identity of a pull, so the binding that made
     * the attempt stands in for it.
     */
    @Transactional
    public ReceiptDisposition applyPulled(
            UUID tenantId,
            UUID bindingId,
            String providerMessageId,
            String normalizedStatus,
            @Nullable String providerStatus,
            boolean hardBounce) {
        return applyTo(new ReceiptCommand(
                tenantId,
                new UUID(0L, 0L),
                Set.of(bindingId),
                providerMessageId,
                normalizedStatus,
                providerStatus,
                null,
                hardBounce));
    }

    /**
     * A customer tapped a push notification (ADR 0182, D14): the one read signal Web Push offers.
     *
     * <p>Applied to the notification's latest attempt through the same ladder a gateway's receipt is,
     * because a click is evidence about an attempt of ours and obeys the same rule: it can only advance
     * it. A tap proves the message reached the browser, so an attempt still {@code ACCEPTED} becomes
     * {@code DELIVERED}, and the status event is {@code READ}. A second tap, or a tap on a message the
     * platform marked failed, changes nothing. The platform's own push has no ADR 0026 binding, which is
     * why this does not go through {@link #apply}, whose lookup is by provider message id under a binding.
     */
    @Transactional
    public ReceiptDisposition applyRead(UUID tenantId, UUID notificationId) {
        Optional<ReceiptAttempt> found = receipts.lockLatestAttempt(tenantId, notificationId);
        if (found.isEmpty()) {
            return ReceiptDisposition.UNKNOWN_MESSAGE;
        }
        return applyToAttempt(tenantId, found.get(), found.get().id().toString(), "READ", "PUSH_CLICKED", null, false);
    }

    private ReceiptDisposition applyTo(ReceiptCommand receipt) {
        Optional<ReceiptAttempt> found =
                receipts.lockAttempt(receipt.tenantId(), receipt.bindingIds(), receipt.providerMessageId());
        if (found.isEmpty()) {
            return ReceiptDisposition.UNKNOWN_MESSAGE;
        }
        return applyToAttempt(
                receipt.tenantId(),
                found.get(),
                receipt.providerMessageId(),
                receipt.normalizedStatus(),
                receipt.providerStatus(),
                receipt.occurredAt(),
                receipt.hardBounce());
    }

    private ReceiptDisposition applyToAttempt(
            UUID tenantId,
            ReceiptAttempt attempt,
            String eventMessageId,
            String normalizedStatus,
            @Nullable String providerStatus,
            @Nullable Instant occurredAt,
            boolean hardBounce) {
        Instant now = clock.instant();

        String eventId = eventMessageId + ":" + normalizedStatus;
        List<StatusEventRow> events = notifications.statusEvents(tenantId, attempt.id());
        if (events.stream().anyMatch(event -> event.providerEventId().equals(eventId))) {
            // The same fact twice. The unique key would absorb the insert; saying so
            // here is what lets the caller count it as a duplicate rather than as an
            // advance that changed nothing.
            return ReceiptDisposition.DUPLICATE;
        }

        int incoming = rank(normalizedStatus);
        if (incoming <= currentRank(attempt, events)) {
            return ReceiptDisposition.REGRESSED;
        }

        notifications.recordStatusEvent(
                tenantId,
                attempt.id(),
                eventId,
                normalizedStatus,
                providerStatus,
                occurredAt == null ? now : occurredAt,
                now);

        switch (normalizedStatus) {
            case "DELIVERED" ->
                receipts.advance(tenantId, attempt.id(), "DELIVERED", null, occurredAt == null ? now : occurredAt, now);
            case "READ" -> {
                // A read is a delivery that was also seen. The attempt's own status stays DELIVERED
                // (V0026 has no READ attempt status, and the ladder's evidence is the status event just
                // recorded above), and a read that follows a delivery must not move the time it was
                // delivered at. A read with no delivery before it is a delivery as well.
                if (!"DELIVERED".equals(attempt.status())) {
                    receipts.advance(
                            tenantId, attempt.id(), "DELIVERED", null, occurredAt == null ? now : occurredAt, now);
                }
            }
            case "FAILED" ->
                receipts.advance(
                        tenantId,
                        attempt.id(),
                        "FAILED",
                        hardBounce ? FAILURE_UNREACHABLE : FAILURE_REPORTED,
                        null,
                        now);
            default ->
                // Accepted, handed to the operator, or unresolved: still the same
                // attempt in the same state, but somebody is evidently listening, so
                // a "no receipt" mark that raced this one no longer holds.
                receipts.clearNoReceipt(tenantId, attempt.id(), now);
        }

        if (hardBounce && attempt.recipientAccountId() != null && attempt.brandId() != null) {
            boolean written = feedback.recordHardBounce(
                    tenantId,
                    attempt.brandId(),
                    attempt.recipientAccountId(),
                    "SMS",
                    attempt.id().toString());
            log.info("Hard-bounce receipt for attempt {}: suppression {}", attempt.id(), written ? "written" : "held");
        }
        return ReceiptDisposition.APPLIED;
    }

    /**
     * Where an attempt stands on the ladder a receipt can only climb.
     *
     * <p>A terminal attempt is at the top whatever its events say; otherwise the
     * highest of "the provider accepted it" and every status event on record.
     */
    static int currentRank(ReceiptAttempt attempt, List<StatusEventRow> events) {
        if ("FAILED".equals(attempt.status())) {
            return FINAL;
        }
        int rank = "ACCEPTED".equals(attempt.status()) ? 1 : 0;
        if ("DELIVERED".equals(attempt.status())) {
            rank = rank("DELIVERED");
        }
        for (StatusEventRow event : events) {
            rank = Math.max(rank, rank(event.normalizedStatus()));
        }
        return rank;
    }

    /** Past a failure nothing advances, not even a read. */
    private static final int FINAL = 6;

    /**
     * The ladder. {@code UNKNOWN} is the provider's terminal-and-unresolved word, so
     * it outranks "handed over" and yields to a later definite answer: a message the
     * operator could not report on at first may still turn out delivered.
     *
     * <p>{@code DELIVERED} and {@code FAILED} share a rung: whichever came first is final, and
     * the other is a regression. {@code READ} is above them, because a channel that reports both
     * reports a read <em>after</em> a delivery (ADR 0182), and an attempt that was delivered must
     * still be able to become one that was read. A failed attempt is final to everything.
     */
    static int rank(String normalizedStatus) {
        return switch (normalizedStatus) {
            case "ACCEPTED" -> 1;
            case "DISPATCHED" -> 2;
            case "UNKNOWN" -> 3;
            case "DELIVERED", "FAILED" -> 4;
            case "READ" -> 5;
            default -> 0;
        };
    }
}
