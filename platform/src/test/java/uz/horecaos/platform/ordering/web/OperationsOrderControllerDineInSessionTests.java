package uz.horecaos.platform.ordering.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.catalog.api.ItemDisplayLookup;
import uz.horecaos.platform.fulfillment.api.ShipmentCancellationPort;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.staff.StaffDirectory;
import uz.horecaos.platform.ordering.application.AggregatorOrderIntakeService;
import uz.horecaos.platform.ordering.application.LiveBoardQueryService;
import uz.horecaos.platform.ordering.application.MyWorkQueryService;
import uz.horecaos.platform.ordering.application.OperatorCustomerLookupService;
import uz.horecaos.platform.ordering.application.OperatorOrderingService;
import uz.horecaos.platform.ordering.application.OrderAmendmentService;
import uz.horecaos.platform.ordering.application.OrderBulkActionService;
import uz.horecaos.platform.ordering.application.OrderCallProvenanceService;
import uz.horecaos.platform.ordering.application.OrderOutcomeService;
import uz.horecaos.platform.ordering.application.OrderQueryService;
import uz.horecaos.platform.ordering.application.OrderStateService;
import uz.horecaos.platform.ordering.application.RejectReasonQueryService;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * The second capability an operator's DINE_IN placement needs when it names a party
 * (ADR 0047): {@code POST .../orders} declares {@code order.place}, and putting the
 * order on a table's bill is a write to that bill, so the request also has to hold
 * {@code dinein.session.manage} at the branch. One annotation declares one capability,
 * so the second is checked in the handler, the way {@code OperationsCourierController}
 * checks its policy scopes -- and before the placement is handed to the service, so a
 * refused operator creates nothing.
 *
 * <p>No database and no Spring context: every collaborator but {@link CurrentActor},
 * {@link AuthorizationService} and {@link OperatorOrderingService} is a mock that is
 * never called. The bill itself, and what a closed or foreign party answers, is
 * {@code CartCheckoutAndOrderTests} and {@code DineInTests}.
 */
class OperationsOrderControllerDineInSessionTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final String SUBJECT = "operator-1";

    private static OperationsOrderController controllerFor(
            AuthorizationService authorization, OperatorOrderingService ordering) {
        CurrentActor currentActor = () -> new AuthenticatedActor(SUBJECT, Set.of(), Map.of());
        return new OperationsOrderController(
                mock(OrderQueryService.class),
                mock(OrderStateService.class),
                mock(OrderOutcomeService.class),
                mock(OrderAmendmentService.class),
                mock(RejectReasonQueryService.class),
                mock(JdbcCartStore.class),
                currentActor,
                authorization,
                mock(OrderCallProvenanceService.class),
                ordering,
                mock(uz.horecaos.platform.ordering.application.OperatorOrderQuoteService.class),
                mock(OperatorCustomerLookupService.class),
                mock(OrderBulkActionService.class),
                mock(LiveBoardQueryService.class),
                mock(AggregatorOrderIntakeService.class),
                mock(ShipmentCancellationPort.class),
                mock(MyWorkQueryService.class),
                mock(StaffDirectory.class),
                mock(ItemDisplayLookup.class),
                mock(uz.horecaos.platform.ordering.application.BranchResolutionQueryService.class),
                mock(uz.horecaos.platform.ordering.application.BranchOverrideReasonQueryService.class));
    }

    private static OperationsOrderController.PlaceOrderRequest request(FulfillmentMode mode, @Nullable UUID sessionId) {
        return new OperationsOrderController.PlaceOrderRequest(
                UUID.randomUUID(),
                "STOREFRONT",
                mode,
                List.of(new OperationsOrderController.OrderLineRequest(
                        UUID.randomUUID(), java.math.BigDecimal.ONE, List.of(), List.of(), null)),
                null,
                "CASH",
                null,
                null,
                false,
                null,
                null,
                null,
                sessionId,
                null);
    }

    @Test
    void namingAPartyNeedsTheSessionManageCapabilityAtTheBranchAndCreatesNothingWithout() {
        AuthorizationService authorization = mock(AuthorizationService.class);
        ResourceScope branch = ResourceScope.location(TENANT, BRAND, LOCATION);
        doThrow(new AuthorizationService.AccessDeniedException(Capability.DINEIN_SESSION_MANAGE, branch))
                .when(authorization)
                .require(SUBJECT, Capability.DINEIN_SESSION_MANAGE, branch);
        OperatorOrderingService ordering = mock(OperatorOrderingService.class);

        assertThatThrownBy(() -> controllerFor(authorization, ordering)
                        .place(TENANT, BRAND, LOCATION, "key-1", request(FulfillmentMode.DINE_IN, UUID.randomUUID())))
                .isInstanceOf(AuthorizationService.AccessDeniedException.class)
                .satisfies(thrown -> assertThat(((AuthorizationService.AccessDeniedException) thrown).capability())
                        .isEqualTo(Capability.DINEIN_SESSION_MANAGE));

        verifyNoInteractions(ordering);
    }

    @Test
    void anOrderThatNamesNoPartyAsksForNoSecondCapability() {
        AuthorizationService authorization = mock(AuthorizationService.class);
        OperatorOrderingService ordering = mock(OperatorOrderingService.class);
        when(ordering.place(any())).thenThrow(new IllegalStateException("reached the service"));

        assertThatThrownBy(() -> controllerFor(authorization, ordering)
                        .place(TENANT, BRAND, LOCATION, "key-2", request(FulfillmentMode.PICKUP, null)))
                .isInstanceOf(IllegalStateException.class);

        verify(authorization, never()).require(any(), eq(Capability.DINEIN_SESSION_MANAGE), any());
    }
}
