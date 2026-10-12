package uz.horecaos.platform.fiscal.application;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.fiscal.domain.FiscalDocumentState;
import uz.horecaos.platform.fiscal.domain.FiscalReasonCode;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore.FiscalDocumentRow;
import uz.horecaos.platform.ordering.api.OrderMarksCompleted;
import uz.horecaos.platform.ordering.api.OrderMarksPort;
import uz.horecaos.platform.ordering.api.OrderMarksPort.LineMarkCount;

/**
 * Returns a document blocked {@code MARKS_INCOMPLETE} to the queue once its order's marks complete (ADR 0161).
 *
 * <p>{@code OrderMarksCompleted} is a nudge, not a ledger: this re-reads {@link OrderMarksPort#markCounts}
 * (counts only, nothing decrypted) and reopens only when every marked line really is complete, so a removal
 * that landed after the event was published cannot reopen a document that is still short. Reopening is the
 * same {@link FiscalDocumentService#reopen} an operator's "unblock" uses, taken by a system actor; the next
 * submission pass then builds the receipt with the codes.
 *
 * <p>After commit and in a transaction of its own: the nudge must not hold the scanner's request open, and a
 * failure here must never undo a scan that was accepted. A document nobody reopens stays visible on the
 * blocked worklist with its captured-of-required counts, where an operator can reopen it by hand.
 */
@Component
public class MarksCompletedReopener {

    private static final Logger log = LoggerFactory.getLogger(MarksCompletedReopener.class);

    private static final String REASON = "the order's marked lines are now complete";

    private final JdbcFiscalLifecycleStore documents;
    private final FiscalDocumentService service;
    private final OrderMarksPort marks;

    public MarksCompletedReopener(
            JdbcFiscalLifecycleStore documents, FiscalDocumentService service, OrderMarksPort marks) {
        this.documents = documents;
        this.service = service;
        this.marks = marks;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onMarksCompleted(OrderMarksCompleted event) {
        UUID tenantId = event.tenantId().value();
        List<FiscalDocumentRow> blocked = documents.forOrder(tenantId, event.orderId()).stream()
                .filter(document -> document.state() == FiscalDocumentState.BLOCKED
                        && FiscalReasonCode.MARKS_INCOMPLETE.equals(document.reasonCode()))
                .toList();
        if (blocked.isEmpty()) {
            return;
        }
        List<LineMarkCount> counts = marks.markCounts(tenantId, event.orderId());
        if (counts.isEmpty() || !counts.stream().allMatch(LineMarkCount::complete)) {
            return;
        }
        for (FiscalDocumentRow document : blocked) {
            try {
                service.reopen(
                        tenantId,
                        document.id(),
                        document.version(),
                        ActorRef.systemJob("fiscal-marks"),
                        REASON,
                        event.orderId().toString());
            } catch (RuntimeException failure) {
                log.warn(
                        "Fiscal document {} for order {} could not be reopened after its marks completed: {}",
                        document.id(),
                        event.orderId(),
                        failure.getClass().getSimpleName());
            }
        }
    }
}
