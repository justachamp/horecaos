package uz.horecaos.platform.kitchen.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.ordering.api.OrderAmendmentApplied;

/**
 * Carries an applied order amendment onto the kitchen ticket that is already open for it (ADR 0039,
 * ADR 0041, ADR 0136).
 *
 * <p>{@link TransactionPhase#BEFORE_COMMIT}, as {@link KitchenTicketOpener} is and for the same
 * reason: the amendment and the stations' view of it commit together, so there is no window in
 * which the customer has been charged for a drink the bar never heard about. An amendment that
 * adds a line, adds a combo or makes a line bigger changes what has to be cooked, and the ticket
 * was built from the order as it stood at confirmation.
 *
 * <p>Every amendment is passed on, not only the ones that touch lines: the question "what does the
 * ticket not know about" is answered by comparing the order's live lines with the ticket's items,
 * and a kitchen-note or payment-method amendment finds nothing to do. That keeps this class free
 * of the list of commands that happen to change lines, a list that would be wrong the day a
 * command is added.
 */
@Component
public class KitchenAmendmentListener {

    private final KitchenTicketService tickets;

    public KitchenAmendmentListener(KitchenTicketService tickets) {
        this.tickets = tickets;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOrderAmendmentApplied(OrderAmendmentApplied event) {
        tickets.syncAmendedLines(event.tenantId().value(), event.orderId());
    }
}
