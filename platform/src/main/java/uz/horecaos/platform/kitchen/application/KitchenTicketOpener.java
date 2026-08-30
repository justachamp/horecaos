package uz.horecaos.platform.kitchen.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.kitchen.domain.ReleaseMode;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore;
import uz.horecaos.platform.ordering.api.OrderConfirmed;

/**
 * Turns an ADR 0019 confirmation into a production ticket (ADR 0041).
 *
 * <p>{@link TransactionPhase#BEFORE_COMMIT}, like ADR 0020's notification
 * trigger and for the same reason: the ticket and the confirmation that caused it
 * commit together, so there is no window in which a restaurant has committed to
 * an order and the kitchen will never hear about it. That window is the one a
 * customer reports as "you confirmed my order an hour ago and nobody started it".
 *
 * <p>A branch with no stations configured is skipped rather than failed. Rollout
 * step 1 of ADR 0041 is one branch running the screen beside paper while every
 * other branch carries on as it did, and throwing here would stop confirmations
 * at the branches that are not part of the pilot.
 *
 * <p>Release mode is not chosen here. {@link KitchenTicketService} derives it from
 * the order's own promise, because whether a ticket waits is a question about the
 * time the customer was given rather than about the event that woke this listener.
 */
@Component
public class KitchenTicketOpener {

    private static final Logger log = LoggerFactory.getLogger(KitchenTicketOpener.class);

    private final KitchenTicketService tickets;
    private final JdbcKitchenStore kitchen;

    public KitchenTicketOpener(KitchenTicketService tickets, JdbcKitchenStore kitchen) {
        this.tickets = tickets;
        this.kitchen = kitchen;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOrderConfirmed(OrderConfirmed event) {
        if (kitchen.findFallbackStation(event.tenantId().value(), event.locationId())
                .isEmpty()) {
            log.debug("Location {} has no kitchen stations; no production ticket was opened", event.locationId());
            return;
        }
        // AUTO_ON_CONFIRM is the caller's request, not the outcome: the service
        // downgrades it to SCHEDULED when the promise leaves room to wait, which
        // is what stops a preorder for 20:00 printing on the line at 11:00.
        var ticket = tickets.open(event.tenantId().value(), event.orderId(), ReleaseMode.AUTO_ON_CONFIRM);
        log.debug(
                "Opened production ticket {} for order {} in status {}", ticket.id(), event.orderId(), ticket.status());
    }
}
