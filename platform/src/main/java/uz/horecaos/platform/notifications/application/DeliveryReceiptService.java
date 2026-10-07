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

    private ReceiptDisposition applyTo(ReceiptCommand receipt) {
        Optional<ReceiptAttempt> found =
                receipts.lockAttempt(receipt.tenantId(), receipt.bindingIds(), receipt.providerMessageId());
        if (found.isEmpty()) {
            return ReceiptDisposition.UNKNOWN_MESSAGE;
        }
        ReceiptAttempt attempt = found.get();
        Instant now = clock.instant();

        String eventId = receipt.providerMessageId() + ":" + receipt.normalizedStatus();
        List<StatusEventRow> events = notifications.statusEvents(receipt.tenantId(), attempt.id());
        if (events.stream().anyMatch(event -> event.providerEventId().equals(eventId))) {
            // The same fact twice. The unique key would absorb the insert; saying so
            // here is what lets the caller count it as a duplicate rather than as an
            // advance that changed nothing.
            return ReceiptDisposition.DUPLICATE;
        }

        int incoming = rank(receipt.normalizedStatus());
        if (incoming <= currentRank(attempt, events)) {
            return ReceiptDisposition.REGRESSED;
        }

        notifications.recordStatusEvent(
                receipt.tenantId(),
                attempt.id(),
                eventId,
                receipt.normalizedStatus(),
                receipt.providerStatus(),
                receipt.occurredAt() == null ? now : receipt.occurredAt(),
                now);

        switch (receipt.normalizedStatus()) {
            case "DELIVERED", "READ" ->
                receipts.advance(
                        receipt.tenantId(),
                        attempt.id(),
                        "DELIVERED",
                        null,
                        receipt.occurredAt() == null ? now : receipt.occurredAt(),
                        now);
            case "FAILED" ->
                receipts.advance(
                        receipt.tenantId(),
                        attempt.id(),
                        "FAILED",
                        receipt.hardBounce() ? FAILURE_UNREACHABLE : FAILURE_REPORTED,
                        null,
                        now);
            default ->
                // Accepted, handed to the operator, or unresolved: still the same
                // attempt in the same state, but somebody is evidently listening, so
                // a "no receipt" mark that raced this one no longer holds.
                receipts.clearNoReceipt(receipt.tenantId(), attempt.id(), now);
        }

        if (receipt.hardBounce() && attempt.recipientAccountId() != null && attempt.brandId() != null) {
            boolean written = feedback.recordHardBounce(
                    receipt.tenantId(),
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
        if (Set.of("DELIVERED", "FAILED").contains(attempt.status())) {
            return FINAL;
        }
        int rank = "ACCEPTED".equals(attempt.status()) ? 1 : 0;
        for (StatusEventRow event : events) {
            rank = Math.max(rank, rank(event.normalizedStatus()));
        }
        return rank;
    }

    private static final int FINAL = 4;

    /**
     * The ladder. {@code UNKNOWN} is the provider's terminal-and-unresolved word, so
     * it outranks "handed over" and yields to a later definite answer: a message the
     * operator could not report on at first may still turn out delivered.
     */
    static int rank(String normalizedStatus) {
        return switch (normalizedStatus) {
            case "ACCEPTED" -> 1;
            case "DISPATCHED" -> 2;
            case "UNKNOWN" -> 3;
            case "DELIVERED", "READ", "FAILED" -> FINAL;
            default -> 0;
        };
    }
}
