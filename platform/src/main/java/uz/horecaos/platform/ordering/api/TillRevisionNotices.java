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
     * Closes an OPEN notice because the platform told the till what <em>this revision</em> of it said. False
     * when the notice was no longer open (another request closed it first) or had moved on to a later revision
     * (the order was weighed again while the till was being asked): either is an ordinary answer and not a
     * fault, and in both the notice is left as it is. A notice is closed only as the state the caller acted on.
     */
    boolean markTold(UUID tenantId, UUID noticeId, int revision, String actor, Instant at);

    /**
     * Closes an OPEN notice because a person says the till now agrees with <em>this revision</em> of it. False
     * when the notice was no longer open or had moved on to a later revision, and then it is left as it is.
     */
    boolean markAcknowledged(UUID tenantId, UUID noticeId, int revision, String actor, Instant at);

    /**
     * The till accepted {@code toldTotalMinor}, which was the revised total of revision {@code toldRevision} of
     * this notice, but the notice has moved on since (a second weighing during the call). The till holds that
     * amount now, so the notice carries it as what the till holds, and stays open for the newer total; if the
     * newer total is the very amount the till took, the difference is gone and the notice is closed as told.
     *
     * @return whether an open, newer notice was found and updated
     */
    boolean recordTold(UUID tenantId, UUID noticeId, int toldRevision, long toldTotalMinor, String actor, Instant at);

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
