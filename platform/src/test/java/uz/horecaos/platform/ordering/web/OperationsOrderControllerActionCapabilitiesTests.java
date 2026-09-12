package uz.horecaos.platform.ordering.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.application.LiveBoardQueryService;
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

/**
 * {@link OperationsOrderController#grantedOrderActionCapabilities}, the wiring
 * {@code list} and {@code detail} both lean on to turn a principal's real
 * grants into the input {@code OrderActionsPolicy.availableFor} reads.
 *
 * <p>Wave P05 (gap map 1.2a). {@code OrderActionsPolicyTests} proves the policy
 * function itself is correct for every role's real capability set; this class
 * proves the controller asks {@link AuthorizationService} the right question —
 * the right subject, the right four capabilities, and critically the right
 * <em>scope</em> (this order's {@code LOCATION}, never the brand or the
 * tenant, which would silently widen or narrow every grant check on the
 * board). No database and no Spring context: every collaborator but {@link
 * CurrentActor} and {@link AuthorizationService} is mocked and never invoked,
 * the same style {@code PlatformCustomerLookupControllerTests} uses for a
 * controller whose collaborators outnumber what one test exercises.
 */
class OperationsOrderControllerActionCapabilitiesTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final String SUBJECT = "operator-1";

    private static OperationsOrderController controllerFor(AuthorizationService authorization) {
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
                mock(OperatorOrderingService.class),
                mock(OperatorCustomerLookupService.class),
                mock(OrderBulkActionService.class),
                mock(LiveBoardQueryService.class));
    }

    @Test
    void grantingNothingYieldsAnEmptySet() {
        AuthorizationService authorization = mock(AuthorizationService.class);
        when(authorization.has(any(), any(), any())).thenReturn(false);

        Set<Capability> granted = controllerFor(authorization).grantedOrderActionCapabilities(TENANT, BRAND, LOCATION);

        assertThat(granted).isEmpty();
    }

    @Test
    void grantingOnlyOrderCancelYieldsExactlyThatOneCapability() {
        AuthorizationService authorization = mock(AuthorizationService.class);
        when(authorization.has(any(), any(), any())).thenReturn(false);
        when(authorization.has(SUBJECT, Capability.ORDER_CANCEL, ResourceScope.location(TENANT, BRAND, LOCATION)))
                .thenReturn(true);

        Set<Capability> granted = controllerFor(authorization).grantedOrderActionCapabilities(TENANT, BRAND, LOCATION);

        assertThat(granted).containsExactly(Capability.ORDER_CANCEL);
    }

    @Test
    void grantingAllFourYieldsAllFour() {
        AuthorizationService authorization = mock(AuthorizationService.class);
        when(authorization.has(any(), any(), any())).thenReturn(true);

        Set<Capability> granted = controllerFor(authorization).grantedOrderActionCapabilities(TENANT, BRAND, LOCATION);

        assertThat(granted)
                .containsExactlyInAnyOrder(
                        Capability.ORDER_APPROVE,
                        Capability.ORDER_ADVANCE,
                        Capability.ORDER_CANCEL,
                        Capability.ORDER_AMEND);
    }

    /**
     * The scope asked about is this order's own branch, never the brand or the
     * tenant — a widened scope here would leak a brand- or tenant-level grant
     * onto a branch the principal was never given, and a narrowed one would
     * refuse a grant actually held at exactly this location.
     */
    @Test
    void asksAtLocationScopeNotBrandOrTenantScope() {
        AuthorizationService authorization = mock(AuthorizationService.class);
        when(authorization.has(any(), any(), any())).thenReturn(false);

        controllerFor(authorization).grantedOrderActionCapabilities(TENANT, BRAND, LOCATION);

        verify(authorization)
                .has(eq(SUBJECT), eq(Capability.ORDER_APPROVE), eq(ResourceScope.location(TENANT, BRAND, LOCATION)));
        verify(authorization)
                .has(eq(SUBJECT), eq(Capability.ORDER_ADVANCE), eq(ResourceScope.location(TENANT, BRAND, LOCATION)));
        verify(authorization)
                .has(eq(SUBJECT), eq(Capability.ORDER_CANCEL), eq(ResourceScope.location(TENANT, BRAND, LOCATION)));
        verify(authorization)
                .has(eq(SUBJECT), eq(Capability.ORDER_AMEND), eq(ResourceScope.location(TENANT, BRAND, LOCATION)));
    }
}
