package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.RedemptionOfferPort;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartRow;
import uz.horecaos.platform.pricing.api.QuoteAcceptancePort;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;

/**
 * What a customer may spend of their points on the basket in front of them, and what they would still owe in
 * money (ADR 0046, ADR 0185): the read that makes a redemption something a screen can offer.
 *
 * <p>ADR 0185 declines a redemption recorded after checkout, so the offer is the only place a customer learns
 * what they may redeem and the only place an operator on the phone can. It is answered for a <em>cart</em>, as
 * {@link CartPaymentOptions} is, for the same reason: the channel, the branch, the account and the priced total
 * are already on the cart, already validated and already scoped to the caller. A request that named an amount
 * would be a question the caller could lie about.
 *
 * <p><strong>The figures are the priced quote's, not recomputed.</strong> The total, the delivery fee and the
 * promotion flag come from the quote the cart is bound to, which is the quote checkout accepts. An offer made
 * from any other figure promises a maximum the checkout then refuses. A cart that has not been priced, or whose
 * quote has expired, has no figure to offer against and says so by name instead of guessing.
 *
 * <p>Read-only: nothing is held. The points are debited by the settlement inside the checkout transaction.
 */
@Service
public class CartRedemptionOffers {

    /** The cart has not been priced, so there is no total to offer against. */
    public static final String CART_NOT_PRICED = "CART_NOT_PRICED";

    /** The quote the cart was priced under has expired; pricing again gives a fresh figure. */
    public static final String QUOTE_EXPIRED = "QUOTE_EXPIRED";

    /** A guest has no account to spend from. */
    public static final String SIGN_IN_REQUIRED = "SIGN_IN_REQUIRED";

    /** An applied promotion blocks spending points on the order (ADR 0140). */
    public static final String REDEMPTION_BLOCKED_BY_PROMOTION = "REDEMPTION_BLOCKED_BY_PROMOTION";

    private final CartService carts;
    private final QuoteAcceptancePort quotes;
    private final SalesChannelLookup channels;
    private final RedemptionOfferPort offers;
    private final Clock clock;

    public CartRedemptionOffers(
            CartService carts,
            QuoteAcceptancePort quotes,
            SalesChannelLookup channels,
            RedemptionOfferPort offers,
            Clock clock) {
        this.carts = carts;
        this.quotes = quotes;
        this.channels = channels;
        this.offers = offers;
        this.clock = clock;
    }

    /**
     * What this cart's customer could redeem against it.
     *
     * @return empty when the cart is not this account's, is not at this brand, or does not exist: one answer,
     *         because telling them apart is how a cart id becomes probeable
     */
    @Transactional(readOnly = true)
    public Optional<CartOffer> forCart(UUID tenantId, UUID brandId, UUID callerAccountId, UUID cartId) {
        return carts.view(tenantId, brandId, callerAccountId, cartId)
                .map(view -> offerFor(tenantId, brandId, view.cart()));
    }

    private CartOffer offerFor(UUID tenantId, UUID brandId, CartRow cart) {
        UUID accountId = cart.customerAccountId();
        if (accountId == null) {
            return CartOffer.unavailable(cart, SIGN_IN_REQUIRED);
        }
        UUID quoteId = cart.pricingQuoteId();
        if (quoteId == null) {
            return CartOffer.unavailable(cart, CART_NOT_PRICED);
        }
        Optional<QuoteSnapshot> priced = quotes.quoteSnapshot(tenantId, quoteId);
        if (priced.isEmpty()) {
            return CartOffer.unavailable(cart, CART_NOT_PRICED);
        }
        QuoteSnapshot quote = priced.get();
        if (!quote.expiresAt().isAfter(clock.instant())) {
            return CartOffer.unavailable(cart, QUOTE_EXPIRED);
        }
        String channelCode = channels.byId(tenantId, cart.channelId())
                .map(SalesChannel::code)
                .orElse("");
        RedemptionOfferPort.Offer offer = offers.offer(
                tenantId, brandId, accountId, quote.totalMinor(), quote.feeMinor(), quote.currency(), channelCode);
        if (!quote.loyaltyRedemptionAllowed()) {
            // ADR 0140: a promotion on the basket blocks spending points on it. Decided on the quote, which is
            // ordering's to read; the balance and the rest of the offer are still the customer's to see.
            offer = offer.refusedFor(REDEMPTION_BLOCKED_BY_PROMOTION);
        }
        return new CartOffer(cart.cartId(), quote.currency(), true, quote.totalMinor(), quote.feeMinor(), offer);
    }

    /**
     * What the customer would still owe in money if they spent {@code redeemMinor} of points, or null when that
     * is not an amount the offer permits.
     *
     * <p>The same sum the settlement writes: the money tender is exactly the total less the redemption, and a
     * redemption above the maximum is refused at checkout. Offered here so a screen never does the arithmetic
     * itself and drifts from it.
     */
    public static @Nullable Long cashToCollect(long totalMinor, RedemptionOfferPort.Offer offer, long redeemMinor) {
        if (redeemMinor < 0 || redeemMinor > offer.maximumMinor()) {
            return null;
        }
        return Math.subtractExact(totalMinor, redeemMinor);
    }

    /**
     * One cart's offer.
     *
     * @param priced whether the cart has a live quote to offer against; when false the offer is empty and its
     *               refusal says why
     */
    public record CartOffer(
            UUID cartId,
            String currency,
            boolean priced,
            long orderTotalMinor,
            long deliveryFeeMinor,
            RedemptionOfferPort.Offer offer) {

        static CartOffer unavailable(CartRow cart, String refusal) {
            return new CartOffer(
                    cart.cartId(),
                    cart.currency(),
                    false,
                    0L,
                    0L,
                    new RedemptionOfferPort.Offer(null, 0L, 0L, cart.currency(), refusal));
        }
    }
}
