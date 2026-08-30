package uz.qoida.platform.ordering.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.ordering.api.PaymentIntentPort;
import uz.qoida.platform.ordering.infrastructure.persistence.JdbcCartStore.CartRow;
import uz.qoida.platform.tenancy.api.FulfillmentMode;
import uz.qoida.platform.tenancy.api.SalesChannelLookup;

/**
 * What a customer may actually pay with, for the basket in front of them
 * (ADR 0036, ADR 0013, ADR 0019).
 *
 * <p>Nothing told the customer this before. {@code SalesChannelController} lets an
 * operator write the channel's payment matrix and {@code StorefrontPaymentController}
 * opens an attempt once a method has been chosen, but between them there was no
 * read — so a storefront either hard-coded a list or guessed, and either way a
 * method the tenant does not sell, or that no merchant account can take, was a
 * checkout that failed at its last step.
 *
 * <p><strong>Answered for a cart rather than for a location.</strong> The three
 * inputs — which channel, which branch, which fulfilment mode — are already on the
 * cart, already validated when it was opened, and already scoped to the caller's
 * own account. Taking them as query parameters instead would mean re-validating
 * each one and would hand an unauthenticated shape of this question to anybody
 * with a location id.
 *
 * <p><strong>Only offerable methods are returned.</strong> The alternative — every
 * configured method with an {@code available} flag beside it — was rejected: a
 * list a client has to filter is a list a client will one day forget to filter,
 * and the place that failure surfaces is a customer at the payment step of an
 * order they have already assembled. A method the tenant switched off, one this
 * build does not implement, one no customer may choose, and one whose provider
 * binding is suspended are all simply absent.
 *
 * <p>A new module edge was not needed for any of it.
 * {@link PaymentIntentPort#canAcceptPayment} is a port ordering already owns and
 * checkout already calls, so the payment question is asked exactly where ADR 0019
 * asks it and ordering still imports nothing from payments.
 */
@Service
public class CartPaymentOptions {

    private final CartService carts;
    private final SalesChannelLookup channels;
    private final PaymentIntentPort payments;

    public CartPaymentOptions(CartService carts, SalesChannelLookup channels,
            PaymentIntentPort payments) {
        this.carts = carts;
        this.channels = channels;
        this.payments = payments;
    }

    /**
     * The methods this cart may be paid with.
     *
     * <p>Four filters, and each removes a checkout that would otherwise fail
     * later:
     *
     * <ol>
     * <li>the channel's matrix, where an absent or disabled row is the operator's
     *     "we do not take that here" — V0020 makes absence a no, so a channel with
     *     no matrix offers nothing rather than everything;</li>
     * <li>codes this build implements, because a code the enum does not know
     *     creates no payment intent and leaves an order waiting for a callback
     *     that cannot arrive;</li>
     * <li>codes a customer may choose — {@code MARKETPLACE} is the tender of an
     *     order an aggregator already collected for and is never chosen at a Qoida
     *     checkout;</li>
     * <li>a merchant account that resolves for this branch's seller today, so a
     *     provider whose binding is suspended stops being offered rather than
     *     failing at the payment page.</li>
     * </ol>
     *
     * <p>The last three are one question to {@code canAcceptPayment}, which
     * answers no to an unknown code, no to a method with no provider behind it,
     * and no when no binding resolves. Cash needs no merchant account and is the
     * one method that passes on the channel matrix alone, which is right: it is
     * the majority tender here and the one no provider can refuse.
     *
     * @return empty when the cart is not this account's, is not at this brand, or
     *         does not exist — one answer, because telling them apart is how a
     *         cart id becomes probeable
     */
    @Transactional(readOnly = true)
    public Optional<PaymentOptions> forCart(UUID tenantId, UUID brandId, UUID callerAccountId,
            UUID cartId) {

        return carts.view(tenantId, brandId, callerAccountId, cartId).map(view -> {
            CartRow cart = view.cart();
            List<String> offerable = channels.enabledPaymentMethodCodes(tenantId, cart.channelId())
                    .stream()
                    .filter(code -> payments.canAcceptPayment(tenantId, cart.locationId(), code))
                    .sorted()
                    .toList();

            return new PaymentOptions(cart.cartId(), cart.locationId(), cart.channelId(),
                    cart.fulfillmentMode(), cart.currency(), offerable, warnings());
        });
    }

    /**
     * The gaps that apply to this answer, in the same shape every other ordering
     * read carries them.
     *
     * <p>It matters here more than elsewhere. An assembly with no payments module
     * leaves {@code canAcceptPayment} at its default of "no reason to refuse", so
     * the list degrades to the channel matrix unfiltered — every configured method
     * offered, none of them checked against a merchant account. That is the right
     * default for a build that requires payment for nothing, and it must not be
     * silent on a surface a customer is about to choose from.
     */
    private List<String> warnings() {
        return payments.isWired() ? List.of() : List.of(PaymentIntentPort.NOT_WIRED_WARNING);
    }

    /**
     * @param methodCodes the offerable codes, sorted. Codes rather than labels:
     *                    ADR 0038's registry owns per-tenant naming and the
     *                    storefront maps a code to customer wording the way it maps
     *                    a {@code ServiceabilityReason}
     * @param currency    the cart's currency, so a client renders one price in one
     *                    unit — whole som for UZS (ADR 0018)
     */
    public record PaymentOptions(UUID cartId, UUID locationId, UUID channelId,
            FulfillmentMode fulfillmentMode, String currency, List<String> methodCodes,
            List<String> warnings) { }
}
