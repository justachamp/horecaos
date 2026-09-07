package uz.horecaos.platform.ordering.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.catalog.api.ItemDisplayLookup;
import uz.horecaos.platform.ordering.api.CustomerBotOrderingPort;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * {@link CustomerBotOrderingPort} over the same application services the
 * storefront controller calls (ADR 0075).
 *
 * <p>Holds no rule of its own beyond the arrangement. Every refusal below is
 * {@code CartService}'s, {@code CheckoutService}'s or {@link ReorderPlanService}'s,
 * turned from an exception a web caller would render as a problem document into
 * an outcome a bot can put in a sentence — the same shape
 * {@code OrderDecisionPortAdapter} takes for the staff half.
 *
 * <p>The cash-only restriction in {@link #checkoutForCash} is not a rule this
 * class invents either; it is ADR 0075 declining to let money into a chat, and
 * the method is named for it so no future caller can pass a provider code by
 * accident.
 */
@Service
public class CustomerBotOrderingAdapter implements CustomerBotOrderingPort {

    /** ADR 0075: the bot repeats an order into a fresh cart on the storefront channel. */
    private static final String CHANNEL = "STOREFRONT";

    private static final String CASH = "CASH";

    /** ADR 0027 purpose for reading a past cart's destination to carry it forward. */
    private static final String REPEAT_DESTINATION_PURPOSE = "BOT_REPEAT_DESTINATION_CARRY_FORWARD";

    private final OrderQueryService orders;
    private final JdbcOrderStore orderStore;
    private final JdbcCartStore cartStore;
    private final ReorderPlanService plans;
    private final CartService carts;
    private final CartPaymentOptions paymentOptions;
    private final CheckoutService checkout;
    private final ItemDisplayLookup itemNames;
    private final java.time.Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public CustomerBotOrderingAdapter(
            OrderQueryService orders,
            JdbcOrderStore orderStore,
            JdbcCartStore cartStore,
            ReorderPlanService plans,
            CartService carts,
            CartPaymentOptions paymentOptions,
            CheckoutService checkout,
            ItemDisplayLookup itemNames,
            java.time.Clock clock) {
        this.orders = orders;
        this.orderStore = orderStore;
        this.cartStore = cartStore;
        this.plans = plans;
        this.carts = carts;
        this.paymentOptions = paymentOptions;
        this.checkout = checkout;
        this.itemNames = itemNames;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderCard> latestOrder(UUID tenantId, UUID brandId, UUID customerAccountId) {
        List<JdbcOrderStore.CustomerOrderRow> newest =
                orders.forCustomer(tenantId, brandId, customerAccountId, null, 1);
        if (newest.isEmpty()) {
            return Optional.empty();
        }
        JdbcOrderStore.CustomerOrderRow row = newest.get(0);

        boolean completed = row.status() == OrderStatus.COMPLETED;
        // Advisory. The button this feeds lives longer than the plan behind it,
        // which is why repeat() re-reads rather than trusting this.
        boolean repeatable = plans.planFor(tenantId, row.orderId(), customerAccountId)
                .map(plan -> plan.verdict() == ReorderPlanService.Verdict.READY)
                .orElse(false);

        return Optional.of(new OrderCard(
                row.orderId(),
                row.publicOrderNumber(),
                row.status().name(),
                row.currency(),
                row.totalMinor(),
                row.promisedAt(),
                !row.status().terminal(),
                repeatable,
                completed));
    }

    @Override
    @Transactional
    public Repeat repeat(UUID tenantId, UUID brandId, UUID customerAccountId, UUID orderId) {
        Optional<ReorderPlanService.ReorderPlan> found = plans.planFor(tenantId, orderId, customerAccountId);
        if (found.isEmpty()) {
            return new Repeat(Repeat.Result.NO_SUCH_ORDER, null, 0, List.of());
        }
        ReorderPlanService.ReorderPlan plan = found.get();

        if (plan.verdict() != ReorderPlanService.Verdict.READY) {
            // Named, not counted. A bot has no control to grey out, so the only
            // way to be useful about a blocked repeat is to say which dish.
            List<String> blocked = plan.lines().stream()
                    .filter(line -> line.status() != ReorderPlanService.LineStatus.AVAILABLE)
                    .map(ReorderPlanService.PlannedLine::productName)
                    .toList();
            return new Repeat(Repeat.Result.NOT_READY, null, 0, blocked);
        }

        var source = orderStore.find(tenantId, orderId).orElseThrow();

        try {
            CartRow cart = carts.create(
                    tenantId, brandId, plan.locationId(), CHANNEL, source.fulfillmentMode(), customerAccountId, null);

            int version = cart.version();
            for (ReorderPlanService.PlannedLine line : plan.lines()) {
                var view = carts.putLine(
                        tenantId,
                        brandId,
                        customerAccountId,
                        cart.cartId(),
                        version,
                        // The line number makes the key stable across a retry of the
                        // same repeat; CartService keys a line by variant and exact
                        // modifier selection anyway, so two identical lines of one
                        // order collapse the way they did when it was placed.
                        "r" + line.lineNumber(),
                        line.variantId(),
                        line.quantity(),
                        line.modifierOptionIds(),
                        null);
                version = view.cart().version();
            }

            if (source.fulfillmentMode() == FulfillmentMode.DELIVERY) {
                version = carryDestinationForward(
                        tenantId, brandId, customerAccountId, source.cartId(), cart.cartId(), version);
            }
            return new Repeat(Repeat.Result.BUILT, cart.cartId(), plan.lines().size(), List.of());
        } catch (CartService.CartRefusedException | CartService.StaleCartException refused) {
            // One transaction, so the half-built cart rolls back with this. A
            // customer told "something went wrong" and left holding six lines of
            // eight would have no way to know which two are missing.
            return new Repeat(Repeat.Result.REFUSED, null, 0, List.of());
        }
    }

    /**
     * Carries the previous order's destination onto the repeat.
     *
     * <p>The address the customer chose last time, not one inferred for them:
     * the order's own cart still holds {@code customer_address_id} and the
     * recipient it was placed for, because carts are expired and never deleted.
     * That is the only honest way to check a delivery repeat out from a chat —
     * {@code DestinationCommand}'s own doc is explicit that the recipient is
     * asked for rather than resolved from the account, and a bot cannot ask.
     *
     * <p>Best effort. An archived address, a cart from before destinations were
     * captured, or a since-relocated pin all leave the repeat without a
     * destination, and the customer finishes it in the app — which is a worse
     * experience than a tap and a far better one than a delivery to an address
     * they did not choose.
     */
    private int carryDestinationForward(
            UUID tenantId, UUID brandId, UUID customerAccountId, UUID sourceCartId, UUID targetCartId, int version) {
        Optional<CartService.CapturedDestination> previous =
                carts.destination(tenantId, sourceCartId, REPEAT_DESTINATION_PURPOSE);
        if (previous.isEmpty()) {
            return version;
        }
        CartService.CapturedDestination destination = previous.get();
        try {
            var view = carts.setDestination(
                    tenantId,
                    brandId,
                    customerAccountId,
                    targetCartId,
                    version,
                    new CartService.DestinationCommand(
                            destination.customerAddressId(),
                            destination.recipientName(),
                            destination.recipientPhone(),
                            destination.deliveryNote()));
            return view.cart().version();
        } catch (CartService.CartRefusedException unusable) {
            // ADDRESS_NOT_FOUND for an archived address, DESTINATION_NOT_LOCATED
            // for one whose pin was removed. Neither is a failed repeat: the cart
            // is real and correct, it simply has nowhere to go yet, and
            // currentCart reports that as NEEDS_DESTINATION.
            return version;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CartCard> currentCart(UUID tenantId, UUID brandId, UUID customerAccountId) {
        return cartStore
                .findOpenForCustomer(tenantId, brandId, customerAccountId, clock.instant())
                .map(cart -> describe(tenantId, brandId, customerAccountId, cart));
    }

    private CartCard describe(UUID tenantId, UUID brandId, UUID customerAccountId, CartRow cart) {
        var view =
                carts.view(tenantId, brandId, customerAccountId, cart.cartId()).orElseThrow();
        // A cart line stores a variant id and no name — the snapshot only exists
        // once the cart becomes an order. So the names are looked up, in one
        // query for the whole basket rather than one per line: the singular
        // lookup in this stream was an N+1 on a path a customer is waiting on,
        // ten round trips for a ten-line cart. A variant catalog can no longer
        // name reads as its own id rather than as a blank row.
        Map<UUID, String> names = itemNames.displayNames(
                tenantId,
                view.lines().stream()
                        .map(JdbcCartStore.CartLineRow::variantId)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        List<CartCard.Item> items = view.lines().stream()
                .map(line -> new CartCard.Item(
                        names.getOrDefault(line.variantId(), line.variantId().toString()), line.quantity()))
                .toList();

        String blocked = blockedReason(tenantId, brandId, customerAccountId, cart);
        Long total = null;
        if (blocked == null) {
            try {
                total = carts.price(tenantId, brandId, customerAccountId, cart.cartId(), cart.version())
                        .quote()
                        .totalMinor();
            } catch (CartService.CartRefusedException | CartService.StaleCartException unpriceable) {
                // Shown without a total rather than with a wrong one. ADR 0018's
                // whole point is that the number a customer is shown is the number
                // they are charged, so a basket that will not price has no number.
                blocked = "NOT_PRICEABLE";
            }
        }
        return new CartCard(cart.cartId(), items, cart.currency(), total, blocked == null && !items.isEmpty(), blocked);
    }

    private @Nullable String blockedReason(UUID tenantId, UUID brandId, UUID customerAccountId, CartRow cart) {
        if (cart.fulfillmentMode() == FulfillmentMode.DELIVERY
                && cartStore.findFulfillment(tenantId, cart.cartId()).isEmpty()) {
            return "NEEDS_DESTINATION";
        }
        boolean cash = paymentOptions
                .forCart(tenantId, brandId, customerAccountId, cart.cartId())
                .map(options -> options.methodCodes().contains(CASH))
                .orElse(false);
        return cash ? null : "NO_CASH_METHOD";
    }

    @Override
    @Transactional
    public Checkout checkoutForCash(
            UUID tenantId, UUID brandId, UUID customerAccountId, UUID cartId, String idempotencyKey) {

        Optional<CartRow> found = cartStore.find(tenantId, brandId, cartId);
        if (found.isEmpty()) {
            return new Checkout(Checkout.Result.EMPTY, null, null);
        }
        CartRow cart = found.get();
        CartCard card = describe(tenantId, brandId, customerAccountId, cart);
        if (card.items().isEmpty()) {
            return new Checkout(Checkout.Result.EMPTY, null, null);
        }
        if ("NEEDS_DESTINATION".equals(card.blockedReason())) {
            return new Checkout(Checkout.Result.NEEDS_DESTINATION, null, null);
        }
        if ("NO_CASH_METHOD".equals(card.blockedReason())) {
            return new Checkout(Checkout.Result.NO_CASH_METHOD, null, null);
        }

        CartService.PricedCart priced;
        try {
            // Priced again here, in the same transaction as the checkout, rather
            // than trusting the total the card just showed: ADR 0018 binds a quote
            // to a cart version, and the seconds between rendering a confirm
            // button and tapping it are seconds in which a promotion can end.
            priced = carts.price(tenantId, brandId, customerAccountId, cartId, cart.version());
        } catch (CartService.CartRefusedException | CartService.StaleCartException refused) {
            return new Checkout(Checkout.Result.REFUSED, null, codeOf(refused));
        }

        var result = checkout.checkout(new CheckoutService.CheckoutCommand(
                tenantId,
                brandId,
                cartId,
                priced.cartVersion(),
                priced.quote().quoteId(),
                priced.quote().contextHash(),
                idempotencyKey,
                CASH,
                0L,
                "CUSTOMER",
                customerAccountId.toString(),
                null));

        if (!result.created()) {
            return new Checkout(Checkout.Result.REFUSED, null, result.rejectionCode());
        }
        return new Checkout(Checkout.Result.PLACED, result.publicOrderNumber(), null);
    }

    private static String codeOf(RuntimeException refused) {
        return refused instanceof CartService.CartRefusedException named ? named.code() : "STALE_CART";
    }
}
