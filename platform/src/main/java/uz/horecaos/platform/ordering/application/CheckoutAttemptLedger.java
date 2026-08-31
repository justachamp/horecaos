package uz.horecaos.platform.ordering.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.inventory.api.AvailabilityDecision;
import uz.horecaos.platform.ordering.api.OrderSettlementPort;
import uz.horecaos.platform.ordering.api.PaymentIntentPort;
import uz.horecaos.platform.ordering.application.CheckoutService.CheckoutCommand;
import uz.horecaos.platform.ordering.application.CheckoutService.CheckoutResult;
import uz.horecaos.platform.ordering.domain.CartStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCheckoutAttemptStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;

/**
 * The checkout attempt's own bookkeeping: claiming the idempotency record,
 * settling it against a refusal, replaying an earlier result, and — the twin
 * action ADR 0019 pairs with it — converting the cart once an order exists
 * (steps 1 and 9 of {@link CheckoutService}'s order of operations).
 *
 * <p>Every rejection {@link CheckoutService} produces passes through here, which
 * is why this is also where the platform-gap warnings are assembled: a result a
 * caller has to render is a result this class is already building.
 */
@Component
class CheckoutAttemptLedger {

    private final JdbcCheckoutAttemptStore attempts;
    private final JdbcOrderStore orders;
    private final JdbcCartStore carts;
    private final PaymentIntentPort payments;
    private final OrderSettlementPort settlements;

    CheckoutAttemptLedger(
            JdbcCheckoutAttemptStore attempts,
            JdbcOrderStore orders,
            JdbcCartStore carts,
            PaymentIntentPort payments,
            OrderSettlementPort settlements) {
        this.attempts = attempts;
        this.orders = orders;
        this.carts = carts;
        this.payments = payments;
        this.settlements = settlements;
    }

    /**
     * Claims the idempotency record, or resolves what an earlier attempt under
     * the same key already settled.
     *
     * <p>The insert is attempted first so two concurrent checkouts with one key
     * contend on the unique index; the loser blocks here until the winner
     * commits and then reads its result, rather than running the whole sequence
     * in parallel.
     *
     * @return empty when this call claimed the attempt and the caller should
     *     proceed; present with the result the caller must return immediately
     *     otherwise
     */
    Optional<CheckoutResult> claim(UUID attemptId, CheckoutCommand command, String fingerprint, Instant now) {
        if (attempts.claim(
                attemptId,
                command.tenantId(),
                command.idempotencyKey(),
                command.cartId(),
                command.quoteId(),
                fingerprint,
                now)) {
            return Optional.empty();
        }

        var existing = attempts.findForUpdate(command.tenantId(), command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Attempt vanished mid-transaction"));

        if (!existing.requestFingerprint().equals(fingerprint)) {
            // ADR 0019 rejected deriving the key from a request hash, because
            // two legitimately different carts can normalise to the same hash.
            // This is the opposite check: one client reusing a key for a
            // different request, which is a client bug and never a retry.
            return Optional.of(CheckoutResult.rejected(
                    "IDEMPOTENCY_KEY_REUSED",
                    "This idempotency key was already used for a different checkout",
                    warnings()));
        }
        if ("COMPLETED".equals(existing.status())) {
            return Optional.of(
                    existing.orderId() == null
                            ? CheckoutResult.rejected(existing.outcomeCode(), existing.outcomeDetail(), warnings())
                            : replayOf(command.tenantId(), existing.orderId()));
        }
        // IN_PROGRESS with the row lock held elsewhere is impossible to
        // observe: the FOR UPDATE above would have blocked. Seeing it means
        // the same transaction is re-entering, which is a programming error.
        throw new IllegalStateException("Checkout attempt " + existing.attemptId() + " is already in progress");
    }

    /** Settles the attempt against a plain business refusal and builds the result a caller renders. */
    CheckoutResult settle(UUID attemptId, UUID orderId, String code, String detail, Instant now) {
        attempts.complete(attemptId, orderId, code, detail, now);
        return CheckoutResult.rejected(code, detail, warnings());
    }

    /** Settles the attempt against an inventory refusal, naming every unavailable item. */
    CheckoutResult settleUnavailable(UUID attemptId, AvailabilityDecision decision, Instant now) {
        String detail = decision.unavailableItems().stream()
                .map(item -> item.variantId() + ":" + item.reason())
                .collect(Collectors.joining(","));
        attempts.complete(attemptId, null, "ITEMS_UNAVAILABLE", detail, now);
        return new CheckoutResult(
                CheckoutResult.Outcome.REJECTED,
                null,
                null,
                null,
                0,
                "ITEMS_UNAVAILABLE",
                "Some items are no longer available",
                decision.unavailableItems(),
                warnings());
    }

    private CheckoutResult replayOf(UUID tenantId, UUID orderId) {
        var order = orders.find(tenantId, orderId)
                .orElseThrow(() -> new IllegalStateException("Settled attempt names a missing order"));
        return new CheckoutResult(
                CheckoutResult.Outcome.REPLAYED,
                order.orderId(),
                order.publicOrderNumber(),
                order.status(),
                order.version(),
                null,
                null,
                List.of(),
                warnings());
    }

    /** The order's current version, for the result a successful checkout returns. */
    int orderVersion(UUID tenantId, UUID orderId) {
        return orders.find(tenantId, orderId).map(JdbcOrderStore.OrderRow::version).orElse(1);
    }

    /**
     * 9. Convert the cart and settle the idempotency record — the two mutations
     * that mark a checkout as finished, once nothing further can refuse it.
     */
    void completeAttempt(CheckoutCommand command, CartRow cart, UUID attemptId, UUID orderId, Instant now) {
        if (!carts.transition(command.tenantId(), cart.cartId(), CartStatus.ACTIVE, CartStatus.CONVERTED, orderId, now)) {
            // Unreachable while the cart row lock is held, and worth failing on
            // rather than committing an order whose cart is still orderable.
            throw new IllegalStateException("Cart " + cart.cartId() + " changed during checkout");
        }
        attempts.complete(attemptId, orderId, "CREATED", null, now);
    }

    /**
     * Platform gaps that apply to every order, carried on every result so an
     * unwired port is visible on a report rather than only in a startup log.
     */
    List<String> warnings() {
        List<String> gaps = new ArrayList<>(2);
        if (!payments.isWired()) {
            gaps.add(PaymentIntentPort.NOT_WIRED_WARNING);
        }
        if (!settlements.isWired()) {
            // An assembly that can take an order and refund none of them. Said on
            // the order rather than only at startup, because the person who finds
            // out otherwise is an operator with a customer on the phone.
            gaps.add(OrderSettlementPort.NOT_WIRED_WARNING);
        }
        return List.copyOf(gaps);
    }
}
