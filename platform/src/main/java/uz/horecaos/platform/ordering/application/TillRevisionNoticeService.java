package uz.horecaos.platform.ordering.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.ordering.api.TillRevisionNoticeOpened;
import uz.horecaos.platform.ordering.api.TillRevisionNotices;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcTillRevisionNoticeStore;

/**
 * The notices an order raises when its total moves after the till already holds it (ADR 0137, row
 * {@code 4.2c}).
 *
 * <p>A captured weight corrects an order's total at the pass. If the order was exported before that, the till
 * holds the nominal total and the platform now holds the weighed one, and nothing used to say so: the till
 * stayed silently wrong until somebody noticed at the cash desk. This class is the memory of that
 * difference. {@link #orderTotalMoved} is called from inside the weighing's own transaction, so a notice
 * exists exactly when the revision does, and a rolled-back weighing leaves none.
 *
 * <p>What it does not do is tell the till. Whether a till can be told is a fact about a vendor, which
 * {@code ordering} does not know and must not (ADR 0011); the {@code pos} module reads the open notice
 * through {@link TillRevisionNotices} and closes it, by telling the vendor or by a person's acknowledgement.
 */
@Service
public class TillRevisionNoticeService implements TillRevisionNotices {

    /** What moved the total. An amendment cannot: it is refused while the till holds the order. */
    public static final String SOURCE_CATCHWEIGHT = "CATCHWEIGHT";

    private final JdbcTillRevisionNoticeStore store;
    private final ApplicationEventPublisher events;

    public TillRevisionNoticeService(JdbcTillRevisionNoticeStore store, ApplicationEventPublisher events) {
        this.store = store;
        this.events = events;
    }

    /**
     * An order the till holds is now worth {@code totalAfter} where it was worth {@code totalBefore}.
     *
     * <ul>
     *   <li>No notice open: one is opened carrying what the till holds ({@code totalBefore}) and the alert
     *       signal is published, once.
     *   <li>A notice already open (a second line weighed before anyone acted): it keeps the total the till
     *       holds and takes the new revised figure, with no second alert.
     *   <li>A notice open and the new total is the one the till holds (a weight corrected back): there is
     *       nothing left to change, and the notice is closed by the platform.
     * </ul>
     *
     * Mandatory transaction: this is part of the weighing, never a write of its own.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void orderTotalMoved(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID orderId,
            int revision,
            String currency,
            long totalBefore,
            long totalAfter,
            Instant now) {
        if (totalBefore == totalAfter) {
            return;
        }
        if (store.reviseOpen(tenantId, orderId, revision, totalAfter, now).isPresent()) {
            return;
        }
        if (store.findOpen(tenantId, orderId).isPresent()) {
            // Open, and the revised total is the one the till holds: the difference is gone.
            store.closeAgreed(tenantId, orderId, revision, now);
            return;
        }
        UUID id = Ids.newId();
        store.insertOpen(
                id,
                tenantId,
                brandId,
                locationId,
                orderId,
                SOURCE_CATCHWEIGHT,
                revision,
                currency,
                totalBefore,
                totalAfter,
                now);
        events.publishEvent(new TillRevisionNoticeOpened(
                tenantId, brandId, locationId, orderId, id, currency, totalBefore, totalAfter));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Notice> openFor(UUID tenantId, UUID orderId) {
        return store.findOpen(tenantId, orderId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Notice> latestFor(UUID tenantId, UUID orderId) {
        return store.findLatest(tenantId, orderId);
    }

    @Override
    @Transactional
    public boolean markTold(UUID tenantId, UUID noticeId, String actor, Instant at) {
        return store.resolve(tenantId, noticeId, "TOLD", actor, at);
    }

    @Override
    @Transactional
    public boolean markAcknowledged(UUID tenantId, UUID noticeId, String actor, Instant at) {
        return store.resolve(tenantId, noticeId, "ACKNOWLEDGED", actor, at);
    }

    @Override
    @Transactional
    public void recordFailedAttempt(UUID tenantId, UUID noticeId, String failureCode, Instant at) {
        store.recordFailedAttempt(tenantId, noticeId, failureCode, at);
    }
}
