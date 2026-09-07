package uz.horecaos.platform.loyalty.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.OrderFacts;
import uz.horecaos.platform.ordering.api.OrderCompleted;
import uz.horecaos.platform.ordering.api.OrderingEvent;

/**
 * Turning ADR 0019's {@code OrderCompleted} fact into an accrual attempt (ADR
 * 0046).
 *
 * <p>This is the caller {@code LoyaltyAccrualService.accrue(CompletedOrder)}
 * was missing. ADR 0046 recorded it in place: the method "has no caller
 * outside that test and no listener on order completion". {@link
 * uz.horecaos.platform.notifications.application.OrderNotificationTrigger} is
 * the worked example this follows — the same event, the same {@link
 * TransactionPhase#BEFORE_COMMIT}, so a completion and the points it earns
 * commit together rather than leaving a window where an order finished and
 * nobody will ever credit it.
 *
 * <p><strong>Only {@code OrderCompleted}.</strong> Every other terminal status
 * — cancelled, rejected, expired — earns nothing, and there is no case for any
 * of them below: {@code OrderStateService} never publishes a completion event
 * for them, so the gate is the event type itself rather than a status field
 * this class would have to remember to check. Adding a case here is adding a
 * way to earn points, which is a product decision and should look like one in
 * a diff — the same discipline {@code OrderNotificationTrigger}'s own {@code
 * default} branch states.
 *
 * <p><strong>What the event does not carry, and where the rest comes
 * from.</strong> {@link OrderCompleted} is deliberately thin — no customer, no
 * channel, no fee, no split between money and points (ADR 0029, ADR 0032) — so
 * this reads {@link JdbcLoyaltyStore#orderFacts} for the order's customer,
 * channel and delivery fee, the same read {@code PointsRedemptionService}
 * already makes rather than trusting a caller-supplied copy, and {@link
 * JdbcLoyaltyStore#settledRedemptionMinor} for how much of the total was
 * discharged from points and stayed discharged. Both run inside the same
 * transaction {@code OrderStateService.advance} is still holding open, after
 * {@code recordHandover} has already settled the order's tenders — so this
 * sees the finished split, not a stale one.
 *
 * <p><strong>Idempotent by inheritance, not by anything new here.</strong>
 * {@code LoyaltyAccrualService.accrue} already keys its ledger entry on
 * {@code "ACCRUAL:" + orderId} and returns empty on a second delivery; this
 * class adds no idempotency logic of its own because there is none to add — a
 * trigger that only ever translates one event into one call has nothing to
 * deduplicate that the call beneath it does not already refuse.
 */
@Component
public class OrderCompletionAccrualTrigger {

    private final LoyaltyAccrualService accrual;
    private final JdbcLoyaltyStore store;

    public OrderCompletionAccrualTrigger(LoyaltyAccrualService accrual, JdbcLoyaltyStore store) {
        this.accrual = accrual;
        this.store = store;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOrderingEvent(OrderingEvent event) {
        if (event instanceof OrderCompleted completed) {
            accrueFor(completed);
        }
    }

    private void accrueFor(OrderCompleted completed) {
        UUID tenantId = completed.tenantId().value();

        // Read-your-own-write: this is the same transaction OrderStateService
        // is still holding open, so the order this event just described is
        // always found here. Empty and a null customer are handled the same
        // way LoyaltyAccrualService.accrue itself handles a guest order --
        // there is no account to earn into -- rather than treated as a fault.
        Optional<OrderFacts> facts = store.orderFacts(tenantId, completed.orderId());
        if (facts.isEmpty() || facts.get().customerAccountId() == null) {
            return;
        }
        OrderFacts order = facts.get();

        long redeemedMinor = store.settledRedemptionMinor(tenantId, completed.orderId());
        // Never negative: a redemption can never exceed the order total (ADR
        // 0046's own redemption cap), but this guards the arithmetic rather
        // than trusting that invariant from inside a different module's
        // ledger read.
        long moneySettledMinor = Math.max(0L, completed.totalMinor() - redeemedMinor);

        accrual.accrue(new LoyaltyAccrualService.CompletedOrder(
                tenantId,
                completed.brandId(),
                completed.locationId(),
                order.channelId(),
                order.customerAccountId(),
                completed.orderId(),
                completed.currency(),
                moneySettledMinor,
                order.feeMinor(),
                completed.completedAt()));
    }
}
