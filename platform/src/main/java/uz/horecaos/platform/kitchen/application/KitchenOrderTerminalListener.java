package uz.horecaos.platform.kitchen.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.ordering.api.OrderCancelled;
import uz.horecaos.platform.ordering.api.OrderExpired;

/**
 * Voids the buffered ticket of an order that ended (ADR 0168 Decision 10).
 *
 * <p>{@code KitchenStateMachine} has declared {@code HELD -> VOIDED} since ADR 0041 and nothing called it,
 * and {@code claimDueForRelease} has no predicate on the order, so the buffered ticket of a cancelled
 * pre-order <em>fired on schedule</em>, paid or not. This closes that in the cancellation's own
 * transaction, {@link TransactionPhase#BEFORE_COMMIT} like {@link KitchenAmendmentListener} and for the
 * same reason: the order's end and the buffer's view of it commit together, so there is no window in
 * which an order is cancelled and its ticket is still due. A ticket that has already fired is left exactly
 * as it is.
 *
 * <p>An order expires before its ticket exists today (the approval timeout runs before confirmation), so
 * {@link OrderExpired} finds nothing now. It is listed so the path stays correct if that ever changes.
 * {@code releaseDue}'s own guard covers an order that reaches a terminal status by a route this listener
 * did not see.
 */
@Component
public class KitchenOrderTerminalListener {

    private final KitchenTicketService tickets;

    public KitchenOrderTerminalListener(KitchenTicketService tickets) {
        this.tickets = tickets;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOrderCancelled(OrderCancelled event) {
        tickets.voidBufferedTicketOfOrder(event.tenantId().value(), event.orderId());
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOrderExpired(OrderExpired event) {
        tickets.voidBufferedTicketOfOrder(event.tenantId().value(), event.orderId());
    }
}
