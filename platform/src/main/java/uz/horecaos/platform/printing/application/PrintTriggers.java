package uz.horecaos.platform.printing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.kitchen.api.KitchenTicketAmended;
import uz.horecaos.platform.kitchen.api.KitchenTicketReleased;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.payments.api.FiscalDocumentIssued;
import uz.horecaos.platform.printing.domain.PrintDocumentType;

/**
 * The facts that put paper in a queue (ADR 0154, Decision 8): an order confirmed, a ticket released or
 * amended, a fiscal document issued. In-process signals only -- nothing here is a Kafka event and nothing
 * leaves the module.
 *
 * <p>{@code AFTER_COMMIT}, never {@code BEFORE_COMMIT}: a defect in printing must not stop a restaurant
 * confirming orders, which is exactly the property {@code KitchenTicketOpener}'s atomic listener has and this
 * one must not. The listener is the fast path; {@link PrintReconciler} is the guarantee that a job a listener
 * missed (a crash between the commit and the callback) is found within a minute. A failure here is counted
 * and logged by identifier -- never by anything on the paper.
 *
 * <p>{@code fallbackExecution} so a fact published with no transaction open is not silently dropped.
 */
@Component
class PrintTriggers {

    private static final Logger log = LoggerFactory.getLogger(PrintTriggers.class);

    private final PrintJobPlanner planner;
    private final PrintingMetrics metrics;

    PrintTriggers(PrintJobPlanner planner, PrintingMetrics metrics) {
        this.planner = planner;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onOrderConfirmed(OrderConfirmed event) {
        try {
            planner.enqueueOrderSlip(
                    event.tenantId().value(),
                    event.brandId(),
                    event.locationId(),
                    event.orderId(),
                    event.orderVersion());
        } catch (RuntimeException failure) {
            failed(PrintDocumentType.ORDER_SLIP, event.orderId().toString(), failure);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onTicketReleased(KitchenTicketReleased event) {
        try {
            planner.enqueueKitchenTicket(event.tenantId(), event.brandId(), event.locationId(), event.ticketId(), null);
        } catch (RuntimeException failure) {
            failed(PrintDocumentType.KITCHEN_TICKET, event.ticketId().toString(), failure);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onTicketAmended(KitchenTicketAmended event) {
        try {
            planner.enqueueKitchenTicket(
                    event.tenantId(), event.brandId(), event.locationId(), event.ticketId(), event.revision());
        } catch (RuntimeException failure) {
            failed(PrintDocumentType.KITCHEN_TICKET, event.ticketId().toString(), failure);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onFiscalDocumentIssued(FiscalDocumentIssued event) {
        try {
            planner.enqueueFiscalReceipt(event.tenantId(), event.orderId(), event.documentId());
        } catch (RuntimeException failure) {
            failed(PrintDocumentType.FISCAL_RECEIPT, event.documentId().toString(), failure);
        }
    }

    private void failed(PrintDocumentType type, String subject, RuntimeException failure) {
        metrics.enqueueFailed(type);
        // The identifier and the exception's type: a message can carry what was being built.
        log.warn(
                "Could not queue a {} for {} ({}); the reconciler will find it",
                type,
                subject,
                failure.getClass().getSimpleName());
        log.debug("Queue failure detail", failure);
    }
}
