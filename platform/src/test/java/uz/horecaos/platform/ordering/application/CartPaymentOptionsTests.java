package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.ordering.api.PaymentIntentPort;
import uz.horecaos.platform.ordering.application.CartService.CartView;
import uz.horecaos.platform.ordering.domain.CartStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartRow;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;

/**
 * What a customer may pay with for the basket in front of them, when the basket holds a line
 * sold by weight (ADR 0137).
 *
 * <p>A weighed line's price is final only when the scale has spoken, and a total that a provider
 * has already taken cannot follow it (the weighing refuses {@code PAYMENT_ALREADY_TAKEN}), so a
 * method whose money is taken before the order reaches the pass is not offered for such a basket.
 * Each test would pass if the list ignored the weighed line, except the ones that read it.
 */
class CartPaymentOptionsTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID CHANNEL = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID CART = UUID.randomUUID();

    private final CartService carts = mock(CartService.class);
    private final SalesChannelLookup channels = mock(SalesChannelLookup.class);
    private final PaymentIntentPort payments = mock(PaymentIntentPort.class);
    private final CartPaymentOptions options = new CartPaymentOptions(carts, channels, payments);

    private CartRow cart;
    private CartView view;

    @BeforeEach
    void channelSellingCashAndClick() {
        cart = new CartRow(
                CART,
                TENANT,
                BRAND,
                LOCATION,
                CHANNEL,
                CUSTOMER,
                null,
                FulfillmentMode.PICKUP,
                "UZS",
                CartStatus.ACTIVE,
                null,
                null,
                null,
                1,
                Instant.parse("2026-10-02T00:00:00Z"),
                null,
                null,
                null);
        view = new CartView(cart, List.of());
        when(carts.view(TENANT, BRAND, CUSTOMER, CART)).thenReturn(Optional.of(view));
        when(channels.enabledPaymentMethodCodes(TENANT, CHANNEL)).thenReturn(Set.of("CASH", "CLICK"));
        when(payments.isWired()).thenReturn(true);
        when(payments.canAcceptPayment(any(), any(), anyString())).thenReturn(true);
        when(payments.takesMoneyBeforeHandover(TENANT, "CLICK")).thenReturn(true);
        when(payments.takesMoneyBeforeHandover(TENANT, "CASH")).thenReturn(false);
    }

    @Test
    @DisplayName("a basket with a weighed line is offered only the methods that settle at handover, and is told why")
    void aWeighedBasketIsOfferedOnlyWhatSettlesAtHandover() {
        when(carts.holdsWeighedLine(TENANT, BRAND, cart, view.lines())).thenReturn(true);

        var offered = options.forCart(TENANT, BRAND, CUSTOMER, CART).orElseThrow();

        assertThat(offered.methodCodes()).containsExactly("CASH");
        assertThat(offered.warnings()).contains(CartPaymentOptions.WEIGHED_LINES_PAY_AT_HANDOVER);
    }

    @Test
    @DisplayName("a basket with no weighed line keeps every method the channel sells and says nothing")
    void anOrdinaryBasketIsOfferedEverything() {
        when(carts.holdsWeighedLine(TENANT, BRAND, cart, view.lines())).thenReturn(false);

        var offered = options.forCart(TENANT, BRAND, CUSTOMER, CART).orElseThrow();

        assertThat(offered.methodCodes()).containsExactly("CASH", "CLICK");
        assertThat(offered.warnings()).doesNotContain(CartPaymentOptions.WEIGHED_LINES_PAY_AT_HANDOVER);
    }

    @Test
    @DisplayName("the weighed-line note is not raised when the channel sold nothing it would have removed")
    void noNoteWhenNothingWasRemoved() {
        when(channels.enabledPaymentMethodCodes(TENANT, CHANNEL)).thenReturn(Set.of("CASH"));
        when(carts.holdsWeighedLine(TENANT, BRAND, cart, view.lines())).thenReturn(true);

        var offered = options.forCart(TENANT, BRAND, CUSTOMER, CART).orElseThrow();

        assertThat(offered.methodCodes()).containsExactly("CASH");
        assertThat(offered.warnings()).isEmpty();
    }
}
