package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The notices an order raises when its total moves after the till already holds it (ADR 0137, row
 * {@code 4.2c}).
 *
 * <p>A port in {@code ordering}, which owns the fact, for the module that can tell the till (the {@code pos}
 * module) to read and close it: {@code pos} does not reach into an {@code ordering} table, and {@code ordering}
 * does not know what a till can do. Opening a notice is not here: it is a consequence of a weight being
 * captured, inside that transaction, and nothing outside {@code ordering} causes it.
 */
public interface TillRevisionNotices {

    /** The order's one OPEN notice, or empty when the till and the platform agree. */
    Optional<Notice> openFor(UUID tenantId, UUID orderId);

    /** The order's most recent notice in any state: what a screen shows after the till was told. */
    Optional<Notice> latestFor(UUID tenantId, UUID orderId);

    /**
     * Closes an OPEN notice because the platform told the till. False when it was no longer open (another
     * request closed it first), which is an ordinary answer and not a fault.
     */
    boolean markTold(UUID tenantId, UUID noticeId, String actor, Instant at);

    /** Closes an OPEN notice because a person says the till now agrees. False when it was no longer open. */
    boolean markAcknowledged(UUID tenantId, UUID noticeId, String actor, Instant at);

    /** Counts a try to tell the till that did not succeed, with a stable code and never the vendor's words. */
    void recordFailedAttempt(UUID tenantId, UUID noticeId, String failureCode, Instant at);

    /**
     * @param tillTotalMinor the order's total when the notice opened: what the till holds
     * @param revisedTotalMinor what the order is worth now
     * @param status {@code OPEN}, {@code TOLD} or {@code ACKNOWLEDGED}
     * @param lastFailureCode a stable code for the last unsuccessful try to tell the till, or null
     */
    record Notice(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID orderId,
            String source,
            int revision,
            String currency,
            long tillTotalMinor,
            long revisedTotalMinor,
            String status,
            Instant openedAt,
            @Nullable Instant resolvedAt,
            int attemptCount,
            @Nullable String lastFailureCode) {

        public boolean open() {
            return "OPEN".equals(status);
        }

        public long deltaMinor() {
            return revisedTotalMinor - tillTotalMinor;
        }
    }
}
