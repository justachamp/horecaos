package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.ordering.api.RedemptionOfferPort;
import uz.horecaos.platform.ordering.application.CartService.CartView;
import uz.horecaos.platform.ordering.domain.CartStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartRow;
import uz.horecaos.platform.pricing.api.QuoteAcceptancePort;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;

/**
 * What a customer may spend of their points on the basket in front of them (ADR 0046, ADR 0185): the offer is
 * made from the quote the cart is bound to and from nothing else, and every reason it is empty is a code.
 *
 * <p>The ledger is not under test here: that is {@code PointsRedemptionService.quote} behind the port, covered
 * where the ledger is. What is under test is what ordering contributes: which figures it offers against, and what it
 * refuses to offer on.
 */
class CartRedemptionOffersTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID CHANNEL = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID CART = UUID.randomUUID();
    private static final UUID QUOTE = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-14T09:00:00Z");

    private final CartService carts = mock(CartService.class);
    private final QuoteAcceptancePort quotes = mock(QuoteAcceptancePort.class);
    private final SalesChannelLookup channels = mock(SalesChannelLookup.class);
    private final RedemptionOfferPort offers = mock(RedemptionOfferPort.class);
    private final CartRedemptionOffers service =
            new CartRedemptionOffers(carts, quotes, channels, offers, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void aPricedCartOnTheStorefrontChannel() {
        SalesChannel storefront = mock(SalesChannel.class);
        when(storefront.code()).thenReturn("STOREFRONT");
        when(channels.byId(TENANT, CHANNEL)).thenReturn(Optional.of(storefront));
        when(carts.view(TENANT, BRAND, CUSTOMER, CART)).thenReturn(Optional.of(viewOf(cart(CUSTOMER, QUOTE))));
        when(quotes.quoteSnapshot(TENANT, QUOTE))
                .thenReturn(Optional.of(quote(94_000L, 6_000L, NOW.plusSeconds(600), true)));
        when(offers.offer(eq(TENANT), eq(BRAND), eq(CUSTOMER), anyLong(), anyLong(), anyString(), anyString()))
                .thenReturn(new RedemptionOfferPort.Offer(UUID.randomUUID(), 40_000L, 40_000L, "UZS", null));
    }

    @Test
    @DisplayName(
            "the offer is made against the priced quote's total, delivery fee and channel, not anything the caller said")
    void theOfferIsMadeFromTheQuote() {
        var offer = service.forCart(TENANT, BRAND, CUSTOMER, CART).orElseThrow();

        assertThat(offer.priced()).isTrue();
        assertThat(offer.orderTotalMinor()).isEqualTo(94_000L);
        assertThat(offer.deliveryFeeMinor()).isEqualTo(6_000L);
        assertThat(offer.offer().maximumMinor()).isEqualTo(40_000L);
        assertThat(offer.offer().redeemable()).isTrue();
        verify(offers).offer(TENANT, BRAND, CUSTOMER, 94_000L, 6_000L, "UZS", "STOREFRONT");
    }

    @Test
    @DisplayName("a cart that is not the caller's, or does not exist, is one answer: nothing")
    void aStrangersCartIsNotProbeable() {
        assertThat(service.forCart(TENANT, BRAND, UUID.randomUUID(), CART)).isEmpty();
        assertThat(service.forCart(TENANT, BRAND, CUSTOMER, UUID.randomUUID())).isEmpty();
        verify(offers, never()).offer(any(), any(), any(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("a cart that has not been priced offers nothing and says so, rather than offering against a guess")
    void anUnpricedCartOffersNothing() {
        when(carts.view(TENANT, BRAND, CUSTOMER, CART)).thenReturn(Optional.of(viewOf(cart(CUSTOMER, null))));

        var offer = service.forCart(TENANT, BRAND, CUSTOMER, CART).orElseThrow();

        assertThat(offer.priced()).isFalse();
        assertThat(offer.offer().redeemable()).isFalse();
        assertThat(offer.offer().refusal()).isEqualTo(CartRedemptionOffers.CART_NOT_PRICED);
        verify(offers, never()).offer(any(), any(), any(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("a quote that has expired is no figure to offer against: pricing again gives a fresh one")
    void anExpiredQuoteOffersNothing() {
        when(quotes.quoteSnapshot(TENANT, QUOTE)).thenReturn(Optional.of(quote(94_000L, 0L, NOW, true)));

        var offer = service.forCart(TENANT, BRAND, CUSTOMER, CART).orElseThrow();

        assertThat(offer.priced()).isFalse();
        assertThat(offer.offer().refusal()).isEqualTo(CartRedemptionOffers.QUOTE_EXPIRED);
    }

    @Test
    @DisplayName(
            "a promotion that blocks spending points on the order (ADR 0140) empties the offer but leaves the balance visible")
    void aBlockingPromotionEmptiesTheOffer() {
        when(quotes.quoteSnapshot(TENANT, QUOTE))
                .thenReturn(Optional.of(quote(94_000L, 6_000L, NOW.plusSeconds(600), false)));

        var offer = service.forCart(TENANT, BRAND, CUSTOMER, CART).orElseThrow();

        assertThat(offer.priced()).isTrue();
        assertThat(offer.offer().redeemable()).isFalse();
        assertThat(offer.offer().refusal()).isEqualTo(CartRedemptionOffers.REDEMPTION_BLOCKED_BY_PROMOTION);
        assertThat(offer.offer().availableMinor())
                .as("what the customer holds is still theirs to see")
                .isEqualTo(40_000L);
    }

    @Test
    @DisplayName("a guest cart has no account to spend from")
    void aGuestCartIsToldToSignIn() {
        when(carts.view(TENANT, BRAND, CUSTOMER, CART)).thenReturn(Optional.of(viewOf(cart(null, QUOTE))));

        var offer = service.forCart(TENANT, BRAND, CUSTOMER, CART).orElseThrow();

        assertThat(offer.offer().refusal()).isEqualTo(CartRedemptionOffers.SIGN_IN_REQUIRED);
        assertThat(offer.offer().redeemable()).isFalse();
    }

    @Test
    @DisplayName(
            "the cash still owed is the total less the redemption, and a redemption above the maximum is not an amount")
    void cashToCollectIsTheTotalLessTheRedemption() {
        var offer = new RedemptionOfferPort.Offer(UUID.randomUUID(), 40_000L, 40_000L, "UZS", null);

        assertThat(CartRedemptionOffers.cashToCollect(94_000L, offer, 12_000L)).isEqualTo(82_000L);
        assertThat(CartRedemptionOffers.cashToCollect(94_000L, offer, 0L)).isEqualTo(94_000L);
        assertThat(CartRedemptionOffers.cashToCollect(94_000L, offer, 40_000L)).isEqualTo(54_000L);
        assertThat(CartRedemptionOffers.cashToCollect(94_000L, offer, 40_001L)).isNull();
        assertThat(CartRedemptionOffers.cashToCollect(94_000L, offer, -1L)).isNull();
    }

    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }

    private static CartView viewOf(CartRow cart) {
        return new CartView(cart, List.of());
    }

    private static CartRow cart(@Nullable UUID account, @Nullable UUID quoteId) {
        return new CartRow(
                CART,
                TENANT,
                BRAND,
                LOCATION,
                CHANNEL,
                account,
                null,
                FulfillmentMode.DELIVERY,
                "UZS",
                CartStatus.ACTIVE,
                quoteId,
                quoteId == null ? null : "hash",
                null,
                1,
                Instant.parse("2026-10-15T00:00:00Z"),
                null,
                null,
                null);
    }

    private static QuoteSnapshot quote(long total, long fee, Instant expiresAt, boolean redemptionAllowed) {
        return new QuoteSnapshot(
                QUOTE,
                TENANT,
                BRAND,
                LOCATION,
                CUSTOMER,
                "UZS",
                QuoteSnapshot.Status.ACTIVE,
                UUID.randomUUID(),
                "hash",
                total - fee,
                0L,
                fee,
                0L,
                total,
                expiresAt,
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                true,
                redemptionAllowed);
    }
}
