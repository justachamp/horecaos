package uz.horecaos.platform.ordering.application;

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
 * <p><strong>Cash only, this release.</strong> A card link sent to the
 * customer or any other online method is a bigger piece of work this wave does
 * not build, so {@link #place} refuses anything but {@code CASH} before it
 * writes a single row — the same "decide deliberately, do not half-build a
 * payment path" discipline {@code CustomerBotOrderingAdapter#checkoutForCash}
 * already applies for ADR 0075's chat channel.
 *
 * <p>The phone lookup ADR 0039 describes beside this is {@code
 * OperatorCustomerLookupService} — a separate class with separate
 * collaborators (a PII port and an audit recorder rather than a cart and a
 * checkout transaction), kept apart so that testing one never needs a stand-in
 * for the other.
 */
@Service
public class OperatorOrderingService {

    /** ADR 0039's payment decision for this wave: cash only, tested end to end. */
    static final String CASH = "CASH";

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
            String idempotencyKey,
            String operatorSubject,
            @Nullable String correlationId) {}

    /**
     * Opens a cart for the resolved customer, fills it exactly as entered,
     * prices it and checks it out through {@link CheckoutService} — the same
     * transaction, the same rules, and the same order that a customer's own
     * checkout would produce, attributed to the operator who took the call.
     */
    @Transactional
    public CheckoutService.CheckoutResult place(PlaceOrderCommand command) {
        if (!CASH.equals(command.paymentMethodCode())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Only CASH is supported for an operator-placed order in this release (ADR 0039)");
        }
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
                CASH,
                0L,
                "USER",
                command.operatorSubject(),
                command.correlationId()));
    }
}
