package uz.horecaos.platform.ordering.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartRow;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Operator-assisted order creation (ADR 0039): "an operator-placed order is
 * the same order, taken by a different hand."
 *
 * <p>{@link #place} is an orchestration over {@link CartService} and {@link
 * CheckoutService} — the identical path {@code StorefrontOrderingController}
 * and {@code CustomerBotOrderingAdapter} already take an order through, never
 * a second pricing, inventory or payment rule set of its own. What differs
 * from a customer's own checkout is attribution alone: the cart is opened for
 * the resolved customer's own account (so every ownership check {@link
 * CartService} already enforces still applies), and the {@link
 * CheckoutService.CheckoutCommand} names the operator as {@code USER} rather
 * than the account as {@code CUSTOMER}, which is what lands in {@code
 * ordering.orders.created_by_actor_type/id} (V0029).
 *
 * <p><strong>Payment is whatever the operator channel's own matrix offers,
 * not a hard-coded list.</strong> Wave P13 refused anything but {@code CASH}
 * here before writing a row; that check is gone, because {@link
 * CheckoutEligibilityGuard} already asks the identical question of every
 * other checkout — {@code tenant.channel_payment_methods} intersected with
 * {@code PaymentIntentPort#canAcceptPayment} — and a second, narrower rule in
 * front of it would just be a worse copy of the one rule that has to be right.
 * Today's channel matrix for the tenant's operator channel may still enable
 * cash alone, which is a configuration fact and not a rule this class states;
 * enabling Click or Payme on that channel is a Settings change, not a release.
 *
 * <p><strong>A promo code applies exactly as a customer's own does</strong>
 * (ADR 0072): {@link #place} calls {@link CartService#applyPromoCode} between
 * filling the basket and pricing it, so the same eligibility check — active,
 * in its window, not exhausted — runs on a phone order that runs on a
 * self-service one.
 *
 * <p>The phone lookup ADR 0039 describes beside this is {@code
 * OperatorCustomerLookupService} — a separate class with separate
 * collaborators (a PII port and an audit recorder rather than a cart and a
 * checkout transaction), kept apart so that testing one never needs a stand-in
 * for the other.
 *
 * <p><strong>A pre-order time is a requested instant, not a second pricing
 * pipeline</strong> (row 1.3d). {@link PlaceOrderCommand#requestedFor} passes
 * straight through to {@link CheckoutService.CheckoutCommand#requestedFor()},
 * where {@code CheckoutEligibilityGuard} validates it against the branch's
 * own hours before checkout ever commits and {@code CheckoutOrderWriter}
 * writes it as the order's promise under {@code PromiseBasis.SCHEDULED_SLOT}.
 * This is deliberately the narrow slice of ADR 0019's still-open scheduled-order
 * input — an operator-entered time, checked against today's hours — and not the
 * long-lead reprice/reservation/payment-timing policy that ADR still leaves
 * for a later decision.
 */
@Service
public class OperatorOrderingService {

    private final CartService carts;
    private final CheckoutService checkout;

    public OperatorOrderingService(CartService carts, CheckoutService checkout) {
        this.carts = carts;
        this.checkout = checkout;
    }

    /** One line an operator entered into the basket. */
    public record OrderLine(
            UUID variantId,
            int quantity,
            List<UUID> modifierOptionIds,
            @Nullable String customerNote) {}

    /**
     * Where a delivery order is going — a saved address of the resolved
     * customer's, never one typed ad hoc: see {@link
     * CartService#setDestination}'s own doc for why. Null for a pickup or
     * dine-in order.
     */
    public record Destination(
            UUID customerAddressId,
            String recipientName,
            String recipientPhone,
            @Nullable String deliveryNote) {}

    /**
     * @param requestedFor       row 1.3d: the caller asked for this order for
     *                           later rather than now, or null for an ordinary
     *                           immediate order. Validated against the branch's
     *                           own hours inside {@code CheckoutService} and
     *                           written to {@code OrderPromise} as {@code
     *                           PromiseBasis.SCHEDULED_SLOT} — see that basis's
     *                           own doc
     * @param overrideOutOfHours whether the operator has already been warned the
     *                           branch is closed at {@code requestedFor} and
     *                           chose to place it anyway. Meaningless when {@code
     *                           requestedFor} is null
     */
    public record PlaceOrderCommand(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID customerAccountId,
            String channelCode,
            FulfillmentMode fulfillmentMode,
            List<OrderLine> lines,
            @Nullable Destination destination,
            String paymentMethodCode,
            @Nullable String promoCode,
            String idempotencyKey,
            String operatorSubject,
            @Nullable String correlationId,
            @Nullable Instant requestedFor,
            boolean overrideOutOfHours) {}

    /**
     * Opens a cart for the resolved customer, fills it exactly as entered,
     * applies a promo code when one was given, prices it and checks it out
     * through {@link CheckoutService} — the same transaction, the same rules,
     * and the same order that a customer's own checkout would produce,
     * attributed to the operator who took the call.
     */
    @Transactional
    public CheckoutService.CheckoutResult place(PlaceOrderCommand command) {
        if (command.lines().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "An order needs at least one line");
        }
        boolean delivery = command.fulfillmentMode() == FulfillmentMode.DELIVERY;
        if (delivery && command.destination() == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A delivery order needs a destination");
        }
        if (!delivery && command.destination() != null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "A " + command.fulfillmentMode() + " order has nowhere to deliver to");
        }

        CartRow cart = carts.create(
                command.tenantId(),
                command.brandId(),
                command.locationId(),
                command.channelCode(),
                command.fulfillmentMode(),
                command.customerAccountId(),
                null);

        int version = cart.version();
        int lineIndex = 0;
        for (OrderLine line : command.lines()) {
            var view = carts.putLine(
                    command.tenantId(),
                    command.brandId(),
                    command.customerAccountId(),
                    cart.cartId(),
                    version,
                    "op" + lineIndex++,
                    line.variantId(),
                    line.quantity(),
                    line.modifierOptionIds(),
                    line.customerNote());
            version = view.cart().version();
        }

        if (delivery) {
            // Required and refused above when absent; NullAway cannot see that
            // cross-statement guarantee, so it is restated here rather than
            // silently re-typed away.
            Destination destination =
                    Objects.requireNonNull(command.destination(), "A delivery order needs a destination");
            var view = carts.setDestination(
                    command.tenantId(),
                    command.brandId(),
                    command.customerAccountId(),
                    cart.cartId(),
                    version,
                    new CartService.DestinationCommand(
                            destination.customerAddressId(),
                            destination.recipientName(),
                            destination.recipientPhone(),
                            destination.deliveryNote()));
            version = view.cart().version();
        }

        if (command.promoCode() != null && !command.promoCode().isBlank()) {
            // ADR 0072, threaded exactly as a customer's own
            // POST /carts/{cartId}/promo-code does: checked read-only against
            // live coupon state, stored, and re-checked independently on every
            // price that follows — this call does not decide anything the
            // quote below will not decide again.
            var view = carts.applyPromoCode(
                    command.tenantId(),
                    command.brandId(),
                    command.customerAccountId(),
                    cart.cartId(),
                    version,
                    command.promoCode());
            version = view.cart().version();
        }

        var priced =
                carts.price(command.tenantId(), command.brandId(), command.customerAccountId(), cart.cartId(), version);

        return checkout.checkout(new CheckoutService.CheckoutCommand(
                command.tenantId(),
                command.brandId(),
                cart.cartId(),
                priced.cartVersion(),
                priced.quote().quoteId(),
                priced.quote().contextHash(),
                command.idempotencyKey(),
                command.paymentMethodCode(),
                0L,
                "USER",
                command.operatorSubject(),
                command.correlationId(),
                command.requestedFor(),
                command.overrideOutOfHours()));
    }
}
