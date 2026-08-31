package uz.horecaos.platform.ordering.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.fulfillment.api.OrderProgressPort;
import uz.horecaos.platform.ordering.domain.OrderStatus;

/**
 * Ordering's answer to the one thing the kitchen asks it (ADR 0041, ADR 0019).
 *
 * <p>The interface is declared in {@code fulfillment.api} and satisfied here,
 * for the same reason {@code JdbcDeliveryOrderPort} is: ADR 0019's command path
 * is {@code ordering.application} and module-internal, so the adapter has to be
 * on this side of the line. The kitchen holds a reference to the interface and
 * has no other way to reach an order — in particular it never writes
 * {@code ordering.orders}, which is the property the whole port exists to keep.
 *
 * <p>Thin on purpose. Everything that makes a proposal a proposal — the state
 * machine, the idempotency ledger, the consequences, the audit — is
 * {@link OrderStateService#proposeProgress}, next to the rest of the order
 * lifecycle. What is left here is the translation between the three transitions
 * a kitchen is entitled to name and the statuses ADR 0019 knows.
 *
 * <p>Nothing is caught. A refusal is a return value and never an exception, so
 * anything that does throw out of here is a database or an audit failing, and
 * this is called inside the kitchen's own transaction: swallowing it would let a
 * station advance commit against an order transition that did not, which is the
 * split ADR 0041 wrote the before-commit listener to avoid.
 */
@Component
public class OrderProgressAdapter implements OrderProgressPort {

    private final OrderStateService orders;

    public OrderProgressAdapter(OrderStateService orders) {
        this.orders = orders;
    }

    @Override
    public ProposalOutcome propose(
            UUID tenantId,
            UUID orderId,
            OrderProgress progress,
            String idempotencyKey,
            String reasonCode,
            String actorType,
            String actorId,
            @Nullable String correlationId) {

        // The fulfilment-mode split at READY is not repeated here. A COMPLETED
        // proposal on a delivery order is refused by OrderStateMachine, which is
        // the one place that rule is written, rather than by a second copy of it
        // that could drift from the first.
        OrderStatus target =
                switch (progress) {
                    case PREPARING -> OrderStatus.PREPARING;
                    case READY -> OrderStatus.READY;
                    case COMPLETED -> OrderStatus.COMPLETED;
                };

        return switch (orders.proposeProgress(
                tenantId, orderId, target, idempotencyKey, reasonCode, actorType, actorId, correlationId)) {
            case APPLIED -> ProposalOutcome.APPLIED;
            case ALREADY_THERE -> ProposalOutcome.ALREADY_THERE;
            case REFUSED -> ProposalOutcome.REFUSED;
        };
    }
}
