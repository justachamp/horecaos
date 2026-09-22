package uz.horecaos.platform.ordering.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.fulfillment.api.ShipmentCancellationPort;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.ordering.application.AggregatorOrderIntakeService;
import uz.horecaos.platform.ordering.application.CartPaymentOptions;
import uz.horecaos.platform.ordering.application.CartService;
import uz.horecaos.platform.ordering.application.CheckoutService;
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
import uz.horecaos.platform.ordering.application.ReorderPlanService;
import uz.horecaos.platform.ordering.domain.OrderStateMachine;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * H8: cancelling an already-terminal order used to throw {@link
 * OrderStateMachine.IllegalTransitionException} straight out of both
 * cancel handlers -- neither caught it, so it fell through to a raw 500
 * instead of the 409 every sibling order-action endpoint (state-actions,
 * state-overrides, completion) already gives for this exact exception. No
 * database and no Spring context, the same style {@code
 * OperationsOrderControllerActionCapabilitiesTests} uses: only the collaborator
 * that throws is stubbed, everything else is an unused mock.
 */
class OrderCancellationConflictMappingTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final String SUBJECT = "actor-1";

    @Test
    @DisplayName("OperationsOrderController.cancel maps an already-terminal order to 409, not a raw 500")
    void operationsControllerMapsIllegalTransitionToConflict() {
        OrderQueryService orderQuery = mock(OrderQueryService.class);
        JdbcOrderStore.OrderRow row = mock(JdbcOrderStore.OrderRow.class);
        when(row.locationId()).thenReturn(LOCATION);
        OrderQueryService.OrderDetail detail = mock(OrderQueryService.OrderDetail.class);
        when(detail.order()).thenReturn(row);
        when(orderQuery.detail(TENANT, ORDER)).thenReturn(Optional.of(detail));

        OrderStateService orderState = mock(OrderStateService.class);
        when(orderState.cancel(eq(TENANT), eq(ORDER), anyInt(), any(), any(), any(), any()))
                .thenThrow(
                        new OrderStateMachine.IllegalTransitionException(OrderStatus.COMPLETED, OrderStatus.CANCELLED));

        CurrentActor currentActor = () -> new AuthenticatedActor(SUBJECT, Set.of(), Map.of());
        OperationsOrderController controller = new OperationsOrderController(
                orderQuery,
                orderState,
                mock(OrderOutcomeService.class),
                mock(OrderAmendmentService.class),
                mock(RejectReasonQueryService.class),
                mock(JdbcCartStore.class),
                currentActor,
                mock(AuthorizationService.class),
                mock(OrderCallProvenanceService.class),
                mock(OperatorOrderingService.class),
                mock(OperatorCustomerLookupService.class),
                mock(OrderBulkActionService.class),
                mock(LiveBoardQueryService.class),
                mock(AggregatorOrderIntakeService.class),
                mock(ShipmentCancellationPort.class),
                mock(MyWorkQueryService.class));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("If-Match", "\"3\"");

        Throwable thrown = catchThrowable(() -> controller.cancel(
                TENANT,
                BRAND,
                LOCATION,
                ORDER,
                new OperationsOrderController.CancelRequest("OPERATOR_ERROR", null, null),
                request));

        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
    }

    @Test
    @DisplayName("StorefrontOrderingController.cancel maps an already-terminal order to 409, not a raw 500")
    void storefrontControllerMapsIllegalTransitionToConflict() {
        UUID accountId = UUID.randomUUID();

        OrderQueryService orderQuery = mock(OrderQueryService.class);
        when(orderQuery.detailForCustomer(eq(TENANT), eq(ORDER), eq(accountId), any()))
                .thenReturn(Optional.of(mock(OrderQueryService.OrderDetail.class)));

        OrderStateService orderState = mock(OrderStateService.class);
        when(orderState.cancel(eq(TENANT), eq(ORDER), anyInt(), any(), any(), any(), any()))
                .thenThrow(
                        new OrderStateMachine.IllegalTransitionException(OrderStatus.CANCELLED, OrderStatus.CANCELLED));

        CurrentCustomer currentCustomer = mock(CurrentCustomer.class);
        when(currentCustomer.account(TENANT, BRAND)).thenReturn(Optional.of(new CustomerAccountRef(accountId, TENANT)));
        CurrentActor currentActor = () -> new AuthenticatedActor(SUBJECT, Set.of(), Map.of());

        StorefrontOrderingController controller = new StorefrontOrderingController(
                mock(CartService.class),
                mock(CheckoutService.class),
                mock(CartPaymentOptions.class),
                orderQuery,
                orderState,
                mock(ReorderPlanService.class),
                currentCustomer,
                currentActor);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("If-Match", "\"1\"");

        Throwable thrown = catchThrowable(() -> controller.cancel(
                TENANT,
                BRAND,
                ORDER,
                new StorefrontOrderingController.CancelRequest("CUSTOMER_CHANGED_MIND"),
                request));

        assertThat(thrown).isInstanceOf(ApiException.class);
        assertThat(((ApiException) thrown).errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
    }
}
