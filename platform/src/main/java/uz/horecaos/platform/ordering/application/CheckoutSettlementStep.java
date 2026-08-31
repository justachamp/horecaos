package uz.horecaos.platform.ordering.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.ordering.api.OrderSettlementPort;
import uz.horecaos.platform.ordering.api.PaymentIntentPort;
import uz.horecaos.platform.ordering.application.CheckoutEligibilityGuard.Eligible;
import uz.horecaos.platform.ordering.application.CheckoutService.CheckoutCommand;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;

/**
 * Step 7 of {@link CheckoutService}'s order of operations: the settlement that
 * will discharge this order (ADR 0046), and the provider-neutral payment
 * intent (ADR 0013, ADR 0019) it is planned for. Both write local rows only —
 * no provider, and no other module's write, ever happens from inside this
 * transaction.
 */
@Component
class CheckoutSettlementStep {

    private final OrderSettlementPort settlements;
    private final PaymentIntentPort payments;

    CheckoutSettlementStep(OrderSettlementPort settlements, PaymentIntentPort payments) {
        this.settlements = settlements;
        this.payments = payments;
    }

    /**
     * @param paymentFirst whether ADR 0013's capture timing requires this order
     *     to be paid before it may be confirmed; when it does and no intent
     *     could be created, the checkout must fail rather than commit an order
     *     that can never be paid
     */
    void planAndCreateIntent(CheckoutCommand command, Eligible eligible, UUID orderId, boolean paymentFirst) {
        var cart = eligible.cart();
        QuoteSnapshot quote = eligible.quote();
        // CheckoutEligibilityGuard already refused any command with no payment
        // method before this collaborator ever runs.
        String paymentMethodCode = Objects.requireNonNull(command.paymentMethodCode());

        // 7a. The settlement that will discharge this order (ADR 0046), and with
        // it the only figure that says what is actually to be collected.
        //
        // Here rather than on the confirmation, for three reasons that all point
        // the same way. The tender composition is decided at checkout and nowhere
        // else — the method the customer chose and how much of it they asked to
        // take from their balance are on this command and are on no event. The
        // plan has to exist before a provider is called, and for a
        // BEFORE_CONFIRMATION method the provider is called between this
        // transaction and the confirmation, so a plan taken at confirmation would
        // be taken after the money. And the balance hold belongs to a transaction
        // that can still roll back: taken here it is released by the same rollback
        // that undoes the order, while a hold taken on a confirmation would have to
        // be compensated by hand.
        //
        // Before the intent rather than after it, which is the ordering this
        // change exists for. It used to run thirty lines later, and because it did,
        // the intent had nothing to be told and computed its own amount from the
        // quote total — so a customer who paid 12 000 of a 94 000 order from points
        // was asked by Click for 94 000 while the settlement recorded an 82 000
        // money leg. Both steps write local rows in this one transaction and
        // neither calls anything external, so their order is free to choose; the
        // points hold this takes is released by the same rollback that would undo
        // the intent, exactly as it was when it ran second.
        //
        // Ungated. The method is a precondition checked among the read-only
        // refusals above, so by here it is present and this cannot be the branch
        // that quietly did not run.
        Optional<OrderSettlementPort.PlannedSettlement> settlement =
                settlements.planSettlement(new OrderSettlementPort.SettlementRequest(
                        command.tenantId(),
                        command.brandId(),
                        orderId,
                        cart.customerAccountId(),
                        quote.currency(),
                        quote.totalMinor(),
                        paymentMethodCode,
                        command.redeemFromBalanceMinor(),
                        command.idempotencyKey(),
                        command.actorId()));

        // 7b. The provider-neutral payment intent (ADR 0019 step 7), for what the
        // provider is meant to collect. Local rows only — the order row it refers
        // to now exists, which is why this is here and not before the insert, and
        // no provider is called from inside this transaction.
        UUID intentId = payments.createIntent(
                command.tenantId(),
                orderId,
                amountDueMinor(orderId, command, quote, settlement),
                quote.currency(),
                paymentMethodCode,
                command.idempotencyKey());
        if (paymentFirst && intentId == null) {
            // An order that may not be confirmed until it is paid, and nothing to
            // pay against. Failing the transaction is the only answer that leaves
            // no trace: committing would create an order permanently in
            // PAYMENT_AUTHORIZING, and confirming it would put an unpaid order in
            // front of a kitchen.
            throw new IllegalStateException("Order " + orderId + " requires payment before "
                    + "confirmation but no payment intent was created");
        }
    }

    /**
     * What the payment intent is created for: the order's money leg, never its
     * total.
     *
     * <p>The settlement is the authority and this only reads it. Nothing here
     * subtracts the redemption from the total, and that is deliberate — the intent
     * and the money tender were two independent derivations of one number, so on
     * every split-tender order the provider was asked for the whole total while the
     * settlement recorded {@code total - redeemed}. The customer paid the full
     * price and spent their points for the same food, and the surplus sat on no
     * tender, which put it beyond the refund path as well: {@code refund} is
     * bounded by {@code refundableMinor()} per tender and no tender knew about it.
     *
     * <p>The remaining branch is the assembly with no payments module (ADR 0046's
     * {@code OrderSettlementPort} stand-in), which plans nothing. Such a build
     * cannot redeem at all — refused among the read-only validations above — so
     * nothing was taken from a balance and the order total <em>is</em> the money
     * leg. The check below is the guard that keeps that sentence true rather than
     * assumed: if it ever stops being true, the checkout fails and rolls back
     * instead of quietly overcharging.
     */
    private long amountDueMinor(
            UUID orderId,
            CheckoutCommand command,
            QuoteSnapshot quote,
            Optional<OrderSettlementPort.PlannedSettlement> settlement) {

        if (settlement.isPresent()) {
            return settlement.get().moneyDueMinor();
        }
        if (command.redeemFromBalanceMinor() > 0) {
            throw new IllegalStateException("Order " + orderId + " redeems from a balance but "
                    + "planned no settlement, so no tender records the redemption and the amount "
                    + "due cannot be established");
        }
        return quote.totalMinor();
    }
}
