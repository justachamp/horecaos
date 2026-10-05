package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.ordering.application.BranchResolutionQueryService.BranchResolution;
import uz.horecaos.platform.ordering.domain.CartStatus;
import uz.horecaos.platform.ordering.domain.DeliveryDestination;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcBranchOverrideReasonStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartLineRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Gap map row 1.3's own audit half: {@code OperatorOrderingService#place}
 * validates a branch override before touching {@link CartService} and, once
 * {@link CheckoutService} actually creates the order, records it through
 * {@link uz.horecaos.platform.audit.api.ChangeDocuments#created} (ADR 0027).
 *
 * <p>Collaborators are mocked rather than run against a real database — this
 * class's own new branching (validate-then-audit) is orchestration, not a
 * persistence concern, and {@code CartCheckoutAndOrderTests} already proves
 * the underlying cart/checkout pipeline itself end to end against Postgres;
 * duplicating that fixture here would prove the pipeline twice and this
 * class's own new logic not at all more precisely. {@code
 * OperationsOrderControllerActionCapabilitiesTests} sets the same precedent
 * for this package: mocked collaborators for a controller/service's own
 * wiring, a real database for the pipeline underneath it. {@code
 * NewOrderBranchResolutionHttpTests} covers this same row's HTTP surface —
 * the capability gate and the pre-{@code CartService} validation refusals —
 * over the real stack.
 */
class OperatorOrderingServiceBranchOverrideTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID PROPOSED_LOCATION = UUID.randomUUID();
    private static final UUID CHOSEN_LOCATION = UUID.randomUUID();
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID CART_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID VARIANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private final CartService carts = mock(CartService.class);
    private final CheckoutService checkout = mock(CheckoutService.class);
    private final BranchOverrideReasonQueryService overrideReasons = mock(BranchOverrideReasonQueryService.class);
    private final BranchResolutionQueryService branchResolution = mock(BranchResolutionQueryService.class);
    private final CustomerAddressBook addresses = mock(CustomerAddressBook.class);
    private final JdbcOrderStore orders = mock(JdbcOrderStore.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final OperatorOrderingService service = new OperatorOrderingService(
            carts, checkout, overrideReasons, branchResolution, addresses, orders, audit, clock);

    @Test
    void anOverrideThatCreatesTheOrderIsAuditedWithBeforeNullAfterTheChoice() {
        stubHappyCartPath(CHOSEN_LOCATION);
        stubResolvedProposal(PROPOSED_LOCATION);
        JdbcBranchOverrideReasonStore.ReasonRow reason = reasonRow("PROPOSED_BRANCH_TOO_BUSY", false);
        when(overrideReasons.validateForDecision("PROPOSED_BRANCH_TOO_BUSY", null))
                .thenReturn(reason);
        when(checkout.checkout(any())).thenReturn(created(ORDER_ID));

        service.place(commandFor(CHOSEN_LOCATION, PROPOSED_LOCATION, "PROPOSED_BRANCH_TOO_BUSY", null));

        ArgumentCaptor<AuditFact> captured = ArgumentCaptor.forClass(AuditFact.class);
        verify(audit).record(captured.capture());
        AuditFact fact = captured.getValue();
        assertThat(fact.actionCode()).isEqualTo("ordering.order.branch_overridden");
        assertThat(fact.targetType()).isEqualTo("Order");
        assertThat(fact.targetId()).isEqualTo(ORDER_ID);
        assertThat(fact.reason()).isNotBlank();

        @SuppressWarnings("unchecked")
        Map<String, Object> proposedField =
                (Map<String, Object>) fact.changeDocument().get("proposedLocationId");
        @SuppressWarnings("unchecked")
        Map<String, Object> chosenField =
                (Map<String, Object>) fact.changeDocument().get("chosenLocationId");
        @SuppressWarnings("unchecked")
        Map<String, Object> reasonField =
                (Map<String, Object>) fact.changeDocument().get("reasonCode");
        assertThat(proposedField).containsEntry("before", null).containsEntry("after", PROPOSED_LOCATION.toString());
        assertThat(chosenField).containsEntry("before", null).containsEntry("after", CHOSEN_LOCATION.toString());
        assertThat(reasonField).containsEntry("before", null).containsEntry("after", "PROPOSED_BRANCH_TOO_BUSY");
    }

    @Test
    void placingAtTheProposedBranchIsNotAnOverrideAndIsNeverAudited() {
        stubHappyCartPath(PROPOSED_LOCATION);
        stubResolvedProposal(PROPOSED_LOCATION);
        when(checkout.checkout(any())).thenReturn(created(ORDER_ID));

        service.place(commandFor(PROPOSED_LOCATION, PROPOSED_LOCATION, null, null));

        verify(audit, never()).record(any());
        verifyNoInteractions(overrideReasons);
    }

    @Test
    void theOperatorsPaymentMethodIsOnTheCartBeforeTheCartIsPriced() {
        stubHappyCartPath(PROPOSED_LOCATION);
        stubResolvedProposal(PROPOSED_LOCATION);
        when(checkout.checkout(any())).thenReturn(created(ORDER_ID));

        service.place(commandFor(PROPOSED_LOCATION, PROPOSED_LOCATION, null, null));

        var inOrder = org.mockito.Mockito.inOrder(carts);
        inOrder.verify(carts).setPaymentMethod(eq(TENANT), eq(BRAND), eq(CUSTOMER), eq(CART_ID), anyInt(), eq("CASH"));
        inOrder.verify(carts).price(eq(TENANT), eq(BRAND), eq(CUSTOMER), eq(CART_ID), anyInt());
    }

    @Test
    void aMethodTheChannelDoesNotOfferIsLeftForCheckoutToRefuse() {
        stubHappyCartPath(PROPOSED_LOCATION);
        stubResolvedProposal(PROPOSED_LOCATION);
        when(carts.setPaymentMethod(eq(TENANT), eq(BRAND), eq(CUSTOMER), eq(CART_ID), anyInt(), eq("CASH")))
                .thenThrow(new CartService.CartRefusedException("PAYMENT_METHOD_UNAVAILABLE", "not offered"));
        when(checkout.checkout(any())).thenReturn(created(ORDER_ID));

        service.place(commandFor(PROPOSED_LOCATION, PROPOSED_LOCATION, null, null));

        verify(carts).price(eq(TENANT), eq(BRAND), eq(CUSTOMER), eq(CART_ID), anyInt());
        verify(checkout).checkout(any());
    }

    @Test
    void theResolverProposingNothingAtAllIsNeverAnOverride() {
        stubHappyCartPath(CHOSEN_LOCATION);
        stubResolvedProposal(null);
        when(checkout.checkout(any())).thenReturn(created(ORDER_ID));

        service.place(commandFor(CHOSEN_LOCATION, null, null, null));

        verify(audit, never()).record(any());
        verifyNoInteractions(overrideReasons);
    }

    @Test
    void anOverrideThatIsRejectedByCheckoutIsNeverAudited() {
        stubHappyCartPath(CHOSEN_LOCATION);
        stubResolvedProposal(PROPOSED_LOCATION);
        when(overrideReasons.validateForDecision("PROPOSED_BRANCH_TOO_BUSY", null))
                .thenReturn(reasonRow("PROPOSED_BRANCH_TOO_BUSY", false));
        when(checkout.checkout(any())).thenReturn(rejected());

        service.place(commandFor(CHOSEN_LOCATION, PROPOSED_LOCATION, "PROPOSED_BRANCH_TOO_BUSY", null));

        verify(audit, never()).record(any());
    }

    @Test
    void anOverrideWithNoReasonCodeIsRefusedBeforeTheCartIsEverTouched() {
        stubResolvedProposal(PROPOSED_LOCATION);

        assertThatThrownBy(() -> service.place(commandFor(CHOSEN_LOCATION, PROPOSED_LOCATION, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown ->
                        assertThat(((ApiException) thrown).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(carts);
        verifyNoInteractions(checkout);
        verifyNoInteractions(audit);
    }

    @Test
    void anOverrideWithAnUnknownReasonIsRefusedBeforeTheCartIsEverTouched() {
        stubResolvedProposal(PROPOSED_LOCATION);
        when(overrideReasons.validateForDecision(eq("NOT_A_REAL_REASON"), any()))
                .thenThrow(
                        new BranchOverrideReasonQueryService.UnknownBranchOverrideReasonException("NOT_A_REAL_REASON"));

        assertThatThrownBy(
                        () -> service.place(commandFor(CHOSEN_LOCATION, PROPOSED_LOCATION, "NOT_A_REAL_REASON", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown ->
                        assertThat(((ApiException) thrown).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(carts);
        verifyNoInteractions(checkout);
    }

    @Test
    void anOtherOverrideWithNoNoteIsRefusedBeforeTheCartIsEverTouched() {
        stubResolvedProposal(PROPOSED_LOCATION);
        when(overrideReasons.validateForDecision("OTHER", null))
                .thenThrow(new IllegalArgumentException(
                        "\"OTHER\" needs a short note — it carries no wording of its own"));

        assertThatThrownBy(() -> service.place(commandFor(CHOSEN_LOCATION, PROPOSED_LOCATION, "OTHER", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown ->
                        assertThat(((ApiException) thrown).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(carts);
    }

    // --------------------------------------------------- security: the server, not the client, decides

    /**
     * The finding this class now also proves closed: the override gate used
     * to trust {@code PlaceOrderCommand#proposedLocationId} — a field taken
     * straight from the request body — so a caller could place an order at a
     * branch the resolver would never have proposed just by omitting that
     * field (or setting it equal to {@code locationId}), skipping the
     * curated-reason requirement and leaving no {@code
     * ordering.order.branch_overridden} audit fact at all.
     */
    @Test
    void aClientOmittingProposedLocationIdCannotSkipTheGateWhenTheResolverWouldProposeElsewhere() {
        // The request itself says nothing about an override (proposedLocationId
        // is null, exactly like a caller who never resolved branches, or one
        // who stripped the field on purpose) — but the resolver, asked fresh,
        // would have sent this PICKUP order to PROPOSED_LOCATION instead of
        // the branch this request actually names.
        stubResolvedProposal(PROPOSED_LOCATION);

        assertThatThrownBy(() -> service.place(commandFor(CHOSEN_LOCATION, null, null, null)))
                .as("the server's own resolution disagrees with the chosen branch, so this is an override "
                        + "whatever the request body did or did not say")
                .isInstanceOf(ApiException.class)
                .satisfies(thrown ->
                        assertThat(((ApiException) thrown).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(carts);
        verifyNoInteractions(checkout);
        verifyNoInteractions(audit);
    }

    @Test
    void aClientClaimingTheChosenBranchIsTheProposalIsStillCaughtWhenTheResolverDisagrees() {
        // The other half of the same bypass: rather than omitting the field,
        // the caller sets proposedLocationId equal to locationId so
        // PlaceOrderCommand#isBranchOverride() (the old, client-trusted
        // check) would have read false even though this is a real override.
        stubResolvedProposal(PROPOSED_LOCATION);

        assertThatThrownBy(() -> service.place(commandFor(CHOSEN_LOCATION, CHOSEN_LOCATION, null, null)))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown ->
                        assertThat(((ApiException) thrown).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(carts);
        verifyNoInteractions(audit);
    }

    @Test
    void theAuditRecordsTheServerResolvedProposalNeverTheClientSuppliedOne() {
        UUID clientClaimedProposal = UUID.randomUUID();
        stubHappyCartPath(CHOSEN_LOCATION);
        stubResolvedProposal(PROPOSED_LOCATION);
        when(overrideReasons.validateForDecision("PROPOSED_BRANCH_TOO_BUSY", null))
                .thenReturn(reasonRow("PROPOSED_BRANCH_TOO_BUSY", false));
        when(checkout.checkout(any())).thenReturn(created(ORDER_ID));

        // The request body names a different (bogus) proposal than the one
        // the resolver actually returns.
        service.place(commandFor(CHOSEN_LOCATION, clientClaimedProposal, "PROPOSED_BRANCH_TOO_BUSY", null));

        ArgumentCaptor<AuditFact> captured = ArgumentCaptor.forClass(AuditFact.class);
        verify(audit).record(captured.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> proposedField =
                (Map<String, Object>) captured.getValue().changeDocument().get("proposedLocationId");
        assertThat(proposedField)
                .as("audited as the server's own resolution, never the client-supplied value")
                .containsEntry("after", PROPOSED_LOCATION.toString());
    }

    @Test
    void dineInIsNeverTreatedAsAnOverrideAndNeverAsksTheResolver() {
        stubHappyCartPath(CHOSEN_LOCATION, FulfillmentMode.DINE_IN);
        when(checkout.checkout(any())).thenReturn(created(ORDER_ID));

        service.place(new OperatorOrderingService.PlaceOrderCommand(
                TENANT,
                BRAND,
                CHOSEN_LOCATION,
                CUSTOMER,
                "call-centre",
                FulfillmentMode.DINE_IN,
                List.of(new OperatorOrderingService.OrderLine(VARIANT, 1, List.of(), List.of(), null)),
                null,
                "CASH",
                null,
                "idem-key-dine-in",
                "operator-1",
                null,
                null,
                false,
                null,
                null,
                null));

        verify(audit, never()).record(any());
        verifyNoInteractions(overrideReasons);
        verifyNoInteractions(branchResolution);
    }

    @Test
    void aDeliveryOverrideIsDetectedFromTheResolvedDestinationEvenWithoutTheClientSayingSo() {
        UUID addressId = UUID.randomUUID();
        GeoPoint point = new GeoPoint(41.311, 69.240);
        when(addresses.destination(eq(TENANT), eq(CUSTOMER), eq(addressId), any()))
                .thenReturn(Optional.of(new CustomerAddressBook.SavedDestination(
                        addressId,
                        "Home",
                        new DeliveryDestination("Street 1", "", "Tashkent", "", "", "", "", "", "", 41.311, 69.240),
                        null)));
        when(branchResolution.resolve(TENANT, BRAND, FulfillmentMode.DELIVERY, point, "call-centre"))
                .thenReturn(new BranchResolution(List.of(), PROPOSED_LOCATION));

        OperatorOrderingService.Destination destination =
                new OperatorOrderingService.Destination(addressId, "Recipient", "+998901234567", null);
        var command = new OperatorOrderingService.PlaceOrderCommand(
                TENANT,
                BRAND,
                CHOSEN_LOCATION,
                CUSTOMER,
                "call-centre",
                FulfillmentMode.DELIVERY,
                List.of(new OperatorOrderingService.OrderLine(VARIANT, 1, List.of(), List.of(), null)),
                destination,
                "CASH",
                null,
                "idem-key-delivery",
                "operator-1",
                null,
                null,
                false,
                null,
                null,
                null);

        assertThatThrownBy(() -> service.place(command))
                .as("PROPOSED_LOCATION, resolved from the destination's own coordinate, differs from "
                        + "CHOSEN_LOCATION — an override the client never mentioned")
                .isInstanceOf(ApiException.class)
                .satisfies(thrown ->
                        assertThat(((ApiException) thrown).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(carts);
    }

    /** Every existing test here places a PICKUP order over the "call-centre" channel with no point to resolve. */
    private void stubResolvedProposal(@Nullable UUID proposedLocationId) {
        when(branchResolution.resolve(TENANT, BRAND, FulfillmentMode.PICKUP, null, "call-centre"))
                .thenReturn(new BranchResolution(List.of(), proposedLocationId));
    }

    // ------------------------------------------------------------------ fixtures

    private void stubHappyCartPath(UUID locationId) {
        stubHappyCartPath(locationId, FulfillmentMode.PICKUP);
    }

    private void stubHappyCartPath(UUID locationId, FulfillmentMode mode) {
        CartRow cart = new CartRow(
                CART_ID,
                TENANT,
                BRAND,
                locationId,
                UUID.randomUUID(),
                CUSTOMER,
                null,
                mode,
                "UZS",
                CartStatus.ACTIVE,
                null,
                null,
                null,
                0,
                NOW.plusSeconds(3600),
                null,
                null);
        when(carts.create(eq(TENANT), eq(BRAND), eq(locationId), any(), eq(mode), eq(CUSTOMER), any()))
                .thenReturn(cart);
        CartRow lined = new CartRow(
                CART_ID,
                TENANT,
                BRAND,
                locationId,
                cart.channelId(),
                CUSTOMER,
                null,
                mode,
                "UZS",
                CartStatus.ACTIVE,
                null,
                null,
                null,
                1,
                NOW.plusSeconds(3600),
                null,
                null);
        when(carts.putLine(
                        eq(TENANT),
                        eq(BRAND),
                        eq(CUSTOMER),
                        eq(CART_ID),
                        anyInt(),
                        any(),
                        eq(VARIANT),
                        // The quantity is a decimal since ADR 0137; a whole one compares equal in value.
                        argThat((BigDecimal quantity) -> quantity.compareTo(BigDecimal.ONE) == 0),
                        any(),
                        any(),
                        // ADR 0136: the combo picks and the second-level selections an operator
                        // line carries, empty for the plain dish this suite orders.
                        any(),
                        any(),
                        any()))
                .thenReturn(new CartService.CartView(lined, List.<CartLineRow>of()));
        // ADR 0140: the operator's payment method goes on the cart before it is priced.
        when(carts.setPaymentMethod(eq(TENANT), eq(BRAND), eq(CUSTOMER), eq(CART_ID), anyInt(), eq("CASH")))
                .thenReturn(new CartService.CartView(lined, List.<CartLineRow>of()));
        when(carts.price(eq(TENANT), eq(BRAND), eq(CUSTOMER), eq(CART_ID), anyInt()))
                .thenReturn(new CartService.PricedCart(CART_ID, 1, quote(locationId)));
    }

    private QuoteSnapshot quote(UUID locationId) {
        return new QuoteSnapshot(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                locationId,
                CUSTOMER,
                "UZS",
                QuoteSnapshot.Status.ACTIVE,
                UUID.randomUUID(),
                "context-hash",
                10_000L,
                0L,
                0L,
                0L,
                10_000L,
                NOW.plusSeconds(600),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null);
    }

    private static CheckoutService.CheckoutResult created(UUID orderId) {
        return new CheckoutService.CheckoutResult(
                CheckoutService.CheckoutResult.Outcome.CREATED,
                orderId,
                "ORD-1",
                null,
                1,
                null,
                null,
                List.of(),
                List.of());
    }

    private static CheckoutService.CheckoutResult rejected() {
        return new CheckoutService.CheckoutResult(
                CheckoutService.CheckoutResult.Outcome.REJECTED,
                null,
                null,
                null,
                0,
                "OUT_OF_STOCK",
                "unavailable",
                List.of(),
                List.of());
    }

    private static JdbcBranchOverrideReasonStore.ReasonRow reasonRow(String code, boolean requiresNote) {
        return new JdbcBranchOverrideReasonStore.ReasonRow(code, 1, requiresNote, true, Map.of("en", code), NOW, NOW);
    }

    private static OperatorOrderingService.PlaceOrderCommand commandFor(
            UUID locationId,
            @Nullable UUID proposedLocationId,
            @Nullable String overrideReasonCode,
            @Nullable String overrideNote) {
        return new OperatorOrderingService.PlaceOrderCommand(
                TENANT,
                BRAND,
                locationId,
                CUSTOMER,
                "call-centre",
                FulfillmentMode.PICKUP,
                List.of(new OperatorOrderingService.OrderLine(VARIANT, 1, List.of(), List.of(), null)),
                null,
                "CASH",
                null,
                "idem-key-1",
                "operator-1",
                null,
                null,
                false,
                proposedLocationId,
                overrideReasonCode,
                overrideNote);
    }
}
