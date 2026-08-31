package uz.horecaos.platform.ordering.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.ordering.api.OrderAwaitingApproval;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.ordering.application.CheckoutService.CheckoutCommand;
import uz.horecaos.platform.ordering.domain.OrderAcceptancePolicy;
import uz.horecaos.platform.ordering.domain.OrderStateMachine;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.domain.TransitionTrigger;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * Step 8 of {@link CheckoutService}'s order of operations: advance the new
 * order past {@code RECEIVED} — into {@code PAYMENT_AUTHORIZING} when ADR
 * 0013's capture timing says the money must arrive first, into {@code
 * AWAITING_APPROVAL} with a durable timer armed when the location requires
 * restaurant approval, or straight to {@code CONFIRMED} otherwise, enqueuing
 * the inventory commit and writing the outbox event either way.
 *
 * <p>Which of the three applies is {@link CheckoutService}'s own decision, made
 * from what {@link CheckoutOrderWriter} resolved; this class only knows how to
 * execute each path once chosen.
 */
@Component
class CheckoutProgressionStep {

    private final JdbcOrderStore orders;
    private final OrderInventoryProcess inventoryProcess;
    private final ApplicationEventPublisher events;

    CheckoutProgressionStep(JdbcOrderStore orders, OrderInventoryProcess inventoryProcess, ApplicationEventPublisher events) {
        this.orders = orders;
        this.inventoryProcess = inventoryProcess;
        this.events = events;
    }

    /**
     * Holds the order until the money arrives (ADR 0013's {@code
     * BEFORE_CONFIRMATION} capture timing).
     *
     * <p>No approval timer is armed here even for a restaurant-approval location.
     * The approval clock measures how long the restaurant took to answer, and a
     * restaurant that has not been asked yet is not late; the timer is armed on
     * the {@code PAYMENT_AUTHORIZING -> AWAITING_APPROVAL} transition the payment
     * produces, not on this one.
     *
     * <p>No event is published either. {@code OrderReceived} has already been
     * published by the caller and is what a consumer sees; the ordering events
     * this release has all describe a commercial decision — accepted, rejected,
     * confirmed — and "waiting on a card" is not one of them.
     */
    OrderStatus awaitPayment(CheckoutCommand command, UUID orderId, Instant now) {

        OrderStateMachine.require(OrderStatus.RECEIVED, OrderStatus.PAYMENT_AUTHORIZING);
        int version = orders.transition(
                        command.tenantId(), orderId, OrderStatus.RECEIVED, OrderStatus.PAYMENT_AUTHORIZING, now)
                .orElseThrow(() -> new IllegalStateException("Order changed during checkout"));

        orders.recordTransition(
                command.tenantId(),
                orderId,
                version,
                OrderStatus.RECEIVED,
                OrderStatus.PAYMENT_AUTHORIZING,
                TransitionTrigger.CHECKOUT,
                null,
                command.actorType(),
                command.actorId(),
                command.correlationId(),
                now);

        return OrderStatus.PAYMENT_AUTHORIZING;
    }

    OrderStatus awaitApproval(
            CheckoutCommand command, CartRow cart, UUID orderId, OrderAcceptancePolicy policy, Instant deadline, Instant now) {

        OrderStateMachine.require(OrderStatus.RECEIVED, OrderStatus.AWAITING_APPROVAL);
        int version = orders.transition(
                        command.tenantId(), orderId, OrderStatus.RECEIVED, OrderStatus.AWAITING_APPROVAL, now)
                .orElseThrow(() -> new IllegalStateException("Order changed during checkout"));

        orders.recordTransition(
                command.tenantId(),
                orderId,
                version,
                OrderStatus.RECEIVED,
                OrderStatus.AWAITING_APPROVAL,
                TransitionTrigger.CHECKOUT,
                null,
                command.actorType(),
                command.actorId(),
                command.correlationId(),
                now);

        // A durable timer, not an in-memory scheduler. A restart must not lose the
        // deadline and leave the order waiting for an approval nobody will ever be
        // asked for.
        orders.insertTimer(command.tenantId(), orderId, CheckoutService.APPROVAL_TIMER, deadline);

        events.publishEvent(new OrderAwaitingApproval(
                UUID.randomUUID(),
                new TenantId(command.tenantId()),
                orderId,
                now,
                command.brandId(),
                cart.locationId(),
                policy.approvalChannel().name(),
                deadline,
                policy.timeoutAction().name(),
                OrderStatus.AWAITING_APPROVAL.name(),
                version));

        return OrderStatus.AWAITING_APPROVAL;
    }

    OrderStatus confirmImmediately(
            CheckoutCommand command,
            CartRow cart,
            UUID orderId,
            OrderAcceptancePolicy policy,
            QuoteSnapshot quote,
            Instant now) {

        // ADR 0039: an auto-confirmed order was accepted by the platform's own
        // rule, and the attribution says so. Leaving it empty would make the
        // operations board show a blank "Принял" cell on every auto-confirm, which
        // is what trained legacy staff to ignore the field.
        OrderStateMachine.require(OrderStatus.RECEIVED, OrderStatus.CONFIRMED);
        int version = orders.transition(
                        command.tenantId(),
                        orderId,
                        OrderStatus.RECEIVED,
                        OrderStatus.CONFIRMED,
                        now,
                        "SYSTEM_JOB",
                        "order-acceptance-policy")
                .orElseThrow(() -> new IllegalStateException("Order changed during checkout"));

        orders.recordTransition(
                command.tenantId(),
                orderId,
                version,
                OrderStatus.RECEIVED,
                OrderStatus.CONFIRMED,
                TransitionTrigger.CHECKOUT,
                null,
                command.actorType(),
                command.actorId(),
                command.correlationId(),
                now);

        // The inventory process manager, not an inline commit. The commit is a
        // consequence of confirmation and is retried on its own if it fails,
        // rather than failing the checkout that had already succeeded.
        inventoryProcess.enqueueCommit(orderId, command.tenantId(), quote.quoteId(), now);

        events.publishEvent(new OrderConfirmed(
                UUID.randomUUID(),
                new TenantId(command.tenantId()),
                orderId,
                now,
                command.brandId(),
                cart.locationId(),
                policy.mode().name(),
                null,
                now,
                quote.currency(),
                quote.totalMinor(),
                OrderStatus.CONFIRMED.name(),
                version));

        return OrderStatus.CONFIRMED;
    }
}
