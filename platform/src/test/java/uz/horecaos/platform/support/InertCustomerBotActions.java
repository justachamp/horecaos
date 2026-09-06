package uz.horecaos.platform.support;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.integration.provider.telegram.BotActionTokenStore;
import uz.horecaos.platform.integration.provider.telegram.CustomerBotActionAuthorizer;
import uz.horecaos.platform.integration.provider.telegram.TelegramBindingStore;
import uz.horecaos.platform.ordering.api.CustomerBotOrderingPort;
import uz.horecaos.platform.reviews.api.CustomerReviewPort;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * A {@link CustomerBotActionAuthorizer} for the suites that construct
 * {@code TelegramUpdateHandler} by hand and have nothing to say about ADR 0075.
 *
 * <p>Its ports answer nothing, so a customer token would resolve and then find
 * no order and no cart. That is deliberate rather than lazy: these suites drive
 * staff decisions, conversation flows and the sign-in handshake, and a stub that
 * <em>did</em> return orders would let one of them pass while silently
 * exercising a code path it never meant to.
 */
public final class InertCustomerBotActions {

    private InertCustomerBotActions() {}

    public static CustomerBotActionAuthorizer forTests(
            BotActionTokenStore tokens,
            TelegramBindingStore bindings,
            EntitlementService entitlements,
            RateLimiter rateLimiter,
            AuditRecorder audit,
            Clock clock) {
        return new CustomerBotActionAuthorizer(
                tokens, bindings, entitlements, new NoOrders(), new NoReviews(), rateLimiter, audit, clock);
    }

    /**
     * ADR 0075's customer-button gate, off.
     *
     * <p>For the notification suites that assert on a message body or a staff
     * keyboard: a customer status button appearing on an ORDER_CONFIRMED message
     * would change what they see for a reason none of them is about.
     */
    public static EntitlementService customerActionsOff() {
        return new EntitlementService() {

            @Override
            public uz.horecaos.platform.commercial.api.EntitlementSnapshot snapshot(UUID tenantId) {
                throw new UnsupportedOperationException("not needed by these suites");
            }

            @Override
            public uz.horecaos.platform.commercial.api.LimitCheck check(
                    UUID tenantId, uz.horecaos.platform.commercial.api.EntitlementKey<Long> key, long requested) {
                throw new UnsupportedOperationException("not needed by these suites");
            }

            @Override
            public uz.horecaos.platform.commercial.api.LimitCheck require(
                    UUID tenantId, uz.horecaos.platform.commercial.api.EntitlementKey<Long> key, long requested) {
                throw new UnsupportedOperationException("not needed by these suites");
            }

            @Override
            public boolean featureEnabled(
                    UUID tenantId, uz.horecaos.platform.commercial.api.EntitlementKey<Boolean> key) {
                return false;
            }

            @Override
            public void requireFeature(
                    UUID tenantId, uz.horecaos.platform.commercial.api.EntitlementKey<Boolean> key) {}
        };
    }

    private static final class NoOrders implements CustomerBotOrderingPort {

        @Override
        public Optional<OrderCard> latestOrder(UUID tenantId, UUID brandId, UUID customerAccountId) {
            return Optional.empty();
        }

        @Override
        public Repeat repeat(UUID tenantId, UUID brandId, UUID customerAccountId, UUID orderId) {
            return new Repeat(Repeat.Result.NO_SUCH_ORDER, null, 0, java.util.List.of());
        }

        @Override
        public Optional<CartCard> currentCart(UUID tenantId, UUID brandId, UUID customerAccountId) {
            return Optional.empty();
        }

        @Override
        public Checkout checkoutForCash(
                UUID tenantId, UUID brandId, UUID customerAccountId, UUID cartId, String idempotencyKey) {
            return new Checkout(Checkout.Result.EMPTY, null, null);
        }
    }

    private static final class NoReviews implements CustomerReviewPort {

        @Override
        public Outcome rate(UUID tenantId, UUID brandId, UUID orderId, UUID customerAccountId, int rating) {
            return Outcome.NOT_ELIGIBLE;
        }

        @Override
        public boolean hasReview(UUID tenantId, UUID orderId) {
            return false;
        }
    }
}
