package uz.horecaos.platform.ordering.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.ordering.application.CartPaymentOptions;
import uz.horecaos.platform.ordering.application.CartService;
import uz.horecaos.platform.ordering.application.CheckoutService;
import uz.horecaos.platform.ordering.application.OrderQueryService;
import uz.horecaos.platform.ordering.application.OrderStateService;
import uz.horecaos.platform.ordering.application.ReorderPlanService;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The door for ADR 0047's table proof at checkout: {@code POST .../checkouts} reads
 * the guest's {@code X-Dine-In-Token} and hands it to the checkout, and the three
 * refusals that come back for a bound cart are conflicts.
 *
 * <p>What the guard does with the token -- refuse none, an ended one, one for another
 * table -- is {@code CartCheckoutAndOrderTests}, over the real checkout. This proves
 * the two facts a checkout test cannot see because it never goes through the
 * controller: that the header reaches the command at all (a controller that dropped it
 * would leave every bound cart refused as {@code TABLE_TOKEN_REQUIRED}, or, if the
 * guard were ever loosened, unchecked), and that no refusal is ever a 401, which the
 * storefront reads as a lapsed customer sign-in.
 */
class StorefrontOrderingControllerCheckoutTokenTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();

    private final CheckoutService checkout = mock(CheckoutService.class);

    private StorefrontOrderingController controller() {
        CartService carts = mock(CartService.class);
        when(carts.view(any(), any(), any(), any())).thenReturn(Optional.of(mock(CartService.CartView.class)));
        CurrentCustomer customer = mock(CurrentCustomer.class);
        when(customer.account(TENANT, BRAND)).thenReturn(Optional.of(new CustomerAccountRef(ACCOUNT, TENANT)));
        CurrentActor actor = () -> new AuthenticatedActor("customer-1", Set.of(), Map.of());
        return new StorefrontOrderingController(
                carts,
                checkout,
                mock(CartPaymentOptions.class),
                mock(OrderQueryService.class),
                mock(OrderStateService.class),
                mock(ReorderPlanService.class),
                customer,
                actor,
                mock(uz.horecaos.platform.pricing.api.AppliedPromotionPort.class));
    }

    private static StorefrontOrderingController.CheckoutRequest body() {
        return new StorefrontOrderingController.CheckoutRequest(
                UUID.randomUUID(), 3, UUID.randomUUID(), "hash", "CASH", 0L);
    }

    private CheckoutService.CheckoutCommand checkOutWith(@Nullable String token, String rejectionCode) {
        when(checkout.checkout(any()))
                .thenReturn(new CheckoutService.CheckoutResult(
                        CheckoutService.CheckoutResult.Outcome.REJECTED,
                        null,
                        null,
                        null,
                        0,
                        rejectionCode,
                        "refused",
                        List.of(),
                        List.of()));
        assertThatThrownBy(() -> controller().checkout(TENANT, BRAND, "key-1", token, body()))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException refusal = (ApiException) thrown;
                    assertThat(refusal.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
                    assertThat(refusal.properties()).containsEntry("reason", rejectionCode);
                });
        ArgumentCaptor<CheckoutService.CheckoutCommand> command =
                ArgumentCaptor.forClass(CheckoutService.CheckoutCommand.class);
        verify(checkout).checkout(command.capture());
        return command.getValue();
    }

    @Test
    void theGuestsTokenReachesTheCheckoutCommand() {
        assertThat(checkOutWith("guest-token-7", "TABLE_TOKEN_ENDED").dineInGuestToken())
                .isEqualTo("guest-token-7");
    }

    @Test
    void aCheckoutWithoutTheHeaderCarriesNoToken() {
        assertThat(checkOutWith(null, "TABLE_TOKEN_REQUIRED").dineInGuestToken())
                .isNull();
    }

    @Test
    void theTokenNeverAppearsWhereACommandIsPrinted() {
        CheckoutService.CheckoutCommand command = checkOutWith("guest-token-secret", "TABLE_BINDING_STALE");

        assertThat(command.toString())
                .as("a bearer credential (ADR 0028) must not reach a log line or an assertion message")
                .doesNotContain("guest-token-secret");
    }

    @Test
    void everyTableProofRefusalIsAConflictAndNeverASignOut() {
        for (String code : List.of("TABLE_TOKEN_REQUIRED", "TABLE_TOKEN_ENDED", "TABLE_BINDING_STALE")) {
            assertThat(StorefrontOrderingController.errorCodeFor(code)).as(code).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        }
    }
}
