package uz.horecaos.platform.ordering.infrastructure.realtime;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.ordering.api.OrderAwaitingApproval;
import uz.horecaos.platform.ordering.api.OrderCancelled;
import uz.horecaos.platform.ordering.api.OrderCompleted;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.ordering.api.OrderExpired;
import uz.horecaos.platform.ordering.api.OrderReceived;
import uz.horecaos.platform.ordering.api.OrderRejected;
import uz.horecaos.platform.ordering.api.OrderingEvent;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.RealtimeSignalPublisher;
import uz.horecaos.platform.telemetry.api.ScopeKey;
import uz.horecaos.platform.telemetry.api.StreamChannel;

/**
 * Turning an order state transition into the ADR 0045 push it never produced
 * (wave P08 row {@code 0.1f}/{@code 1.1b}).
 *
 * <p>{@code OperationsStreamController:104} has served {@code
 * text/event-stream} since ADR 0045, and {@code ORDER_QUEUE}/{@code
 * ORDER_DETAIL} have been declared channels since the same wave — but nothing
 * in {@code ordering} ever called {@link RealtimeSignalPublisher}, so a
 * correctly subscribed client received keep-alives forever and every board
 * paid the full 10-second poll. This is the missing caller, built the same
 * shape {@link uz.horecaos.platform.referral.application.ReferralOrderCompletionTrigger}
 * and {@code OrderConfirmedSettlementTrigger} already established: one small,
 * independent {@code @TransactionalEventListener} per concern, rather than
 * {@code OrderStateService} growing a direct dependency on {@code
 * telemetry.api} at every one of its own transition call sites.
 *
 * <p><strong>{@code AFTER_COMMIT}, not {@code BEFORE_COMMIT}.</strong> A signal
 * is an invitation to re-read, and re-reading a transition that then rolled
 * back would show a client a state the database never held. Firing after
 * commit costs nothing here — {@link RealtimeSignalPublisher#publish} is
 * fire-and-forget and never blocks on the network call it starts.
 *
 * <p><strong>{@code ORDER_QUEUE} and {@code ORDER_DETAIL}, both, every time.</strong>
 * {@code ORDER_DETAIL} carries no resource-level scope of its own — {@link
 * StreamChannel#ORDER_DETAIL} is subscribable at {@code LOCATION} exactly like
 * {@code ORDER_QUEUE}, per that channel's own doc, because a stream's
 * subscription set is fixed for the connection's life and there is no {@code
 * ORDER} {@link uz.horecaos.platform.iam.api.ResourceScope.ScopeType}. Both
 * frames are cheap (a signal is an identifier and a version, never the order
 * itself) and the detail pane discards what it is not currently showing by
 * {@code resourceId}, exactly the way a client filters any other broadcast
 * channel.
 *
 * <p><strong>Only the six status transitions and the order's own arrival.</strong>
 * An amendment, a revision, or a callback request/resolution also changes what
 * the detail pane would show, but none of them changes {@code status} or a
 * queue row's tab membership, and wiring them is left to whichever wave builds
 * their console surface (P10, P09) rather than claimed here speculatively.
 */
@Component
public class OrderRealtimeSignalTrigger {

    private final RealtimeSignalPublisher realtime;

    public OrderRealtimeSignalTrigger(RealtimeSignalPublisher realtime) {
        this.realtime = realtime;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderingEvent(OrderingEvent event) {
        Transition transition = transitionOf(event);
        if (transition == null) {
            return;
        }
        ScopeKey scope = ScopeKey.location(transition.locationId());
        UUID tenantId = event.tenantId().value();
        realtime.publish(RealtimeSignal.of(
                tenantId,
                StreamChannel.ORDER_QUEUE,
                scope,
                "Order",
                event.orderId(),
                transition.version(),
                event.occurredAt()));
        realtime.publish(RealtimeSignal.of(
                tenantId,
                StreamChannel.ORDER_DETAIL,
                scope,
                "Order",
                event.orderId(),
                transition.version(),
                event.occurredAt()));
    }

    /**
     * Pulls {@code locationId}/{@code orderVersion} out of the one {@link
     * OrderingEvent} subtype it actually is. Neither field is on the sealed
     * interface itself — every permitted record declares its own, identically
     * named — so this is the one place that has to know the shape of each.
     */
    private static @Nullable Transition transitionOf(OrderingEvent event) {
        return switch (event) {
            case OrderReceived e -> new Transition(e.locationId(), (long) e.orderVersion());
            case OrderAwaitingApproval e -> new Transition(e.locationId(), (long) e.orderVersion());
            case OrderConfirmed e -> new Transition(e.locationId(), (long) e.orderVersion());
            case OrderRejected e -> new Transition(e.locationId(), (long) e.orderVersion());
            case OrderExpired e -> new Transition(e.locationId(), (long) e.orderVersion());
            case OrderCancelled e -> new Transition(e.locationId(), (long) e.orderVersion());
            case OrderCompleted e -> new Transition(e.locationId(), (long) e.orderVersion());
            default -> null;
        };
    }

    private record Transition(UUID locationId, long version) {}
}
