package uz.horecaos.platform.payments.settlement;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import uz.horecaos.platform.ordering.api.OrderConfirmed;

/**
 * Settles a provider-tendered order's settlement when the order is confirmed
 * (ADR 0046, ADR 0013).
 *
 * <p>A listener on ordering's own event rather than a call inside
 * {@code CheckoutService}, for the reason {@code DeliveryPlanTrigger} gives:
 * confirmation happens in two places — at checkout when the branch auto-accepts,
 * and in {@code OrderStateService} when an operator, the POS or an approval
 * timeout accepts later — and one listener covers both without either having to
 * remember to.
 *
 * <p>{@link TransactionPhase#BEFORE_COMMIT}, alongside the kitchen opener, the
 * delivery plan and the POS export: the tenders and the confirmation that
 * evidences them commit together, so there is no window in which an order is
 * confirmed and its money is recorded as still outstanding. Nothing external is
 * called — settling a tender is local rows and, for a balance tender, a local
 * loyalty write — so this puts no network hop inside the checkout transaction.
 *
 * <p>Whether anything settles is not decided here. A cash order is confirmed with
 * nothing collected, and {@link CheckoutSettlementPlanner} refuses to settle it
 * on that basis by reading the capture timing of the tender's own registry row.
 * This class knows only that a confirmation happened.
 */
@Component
public class OrderConfirmedSettlementTrigger {

    /** The actor a settlement records when the platform's own rule settled it. */
    private static final String ACTOR = "order-confirmation";

    private final CheckoutSettlementPlanner settlements;

    public OrderConfirmedSettlementTrigger(CheckoutSettlementPlanner settlements) {
        this.settlements = settlements;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOrderConfirmed(OrderConfirmed event) {
        settlements.recordConfirmation(event.tenantId().value(), event.orderId(), ACTOR);
    }
}
