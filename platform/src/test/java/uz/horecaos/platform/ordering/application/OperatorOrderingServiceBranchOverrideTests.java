package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.ordering.domain.CartStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcBranchOverrideReasonStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartLineRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore.CartRow;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
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
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final OperatorOrderingService service =
            new OperatorOrderingService(carts, checkout, overrideReasons, audit, clock);

    @Test
    void anOverrideThatCreatesTheOrderIsAuditedWithBeforeNullAfterTheChoice() {
        stubHappyCartPath(CHOSEN_LOCATION);
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
        when(checkout.checkout(any())).thenReturn(created(ORDER_ID));

        service.place(commandFor(PROPOSED_LOCATION, PROPOSED_LOCATION, null, null));

        verify(audit, never()).record(any());
        verifyNoInteractions(overrideReasons);
    }

    @Test
    void noProposedLocationAtAllIsNeverAnOverride() {
        stubHappyCartPath(CHOSEN_LOCATION);
        when(checkout.checkout(any())).thenReturn(created(ORDER_ID));

        service.place(commandFor(CHOSEN_LOCATION, null, null, null));

        verify(audit, never()).record(any());
        verifyNoInteractions(overrideReasons);
    }

    @Test
    void anOverrideThatIsRejectedByCheckoutIsNeverAudited() {
        stubHappyCartPath(CHOSEN_LOCATION);
        when(overrideReasons.validateForDecision("PROPOSED_BRANCH_TOO_BUSY", null))
                .thenReturn(reasonRow("PROPOSED_BRANCH_TOO_BUSY", false));
        when(checkout.checkout(any())).thenReturn(rejected());

        service.place(commandFor(CHOSEN_LOCATION, PROPOSED_LOCATION, "PROPOSED_BRANCH_TOO_BUSY", null));

        verify(audit, never()).record(any());
    }

    @Test
    void anOverrideWithNoReasonCodeIsRefusedBeforeTheCartIsEverTouched() {
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
        when(overrideReasons.validateForDecision("OTHER", null))
                .thenThrow(new IllegalArgumentException(
                        "\"OTHER\" needs a short note — it carries no wording of its own"));

        assertThatThrownBy(() -> service.place(commandFor(CHOSEN_LOCATION, PROPOSED_LOCATION, "OTHER", null)))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown ->
                        assertThat(((ApiException) thrown).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(carts);
    }

    // ------------------------------------------------------------------ fixtures

    private void stubHappyCartPath(UUID locationId) {
        CartRow cart = new CartRow(
                CART_ID,
                TENANT,
                BRAND,
                locationId,
                UUID.randomUUID(),
                CUSTOMER,
                null,
                FulfillmentMode.PICKUP,
                "UZS",
                CartStatus.ACTIVE,
                null,
                null,
                null,
                0,
                NOW.plusSeconds(3600),
                null,
                null);
        when(carts.create(
                        eq(TENANT), eq(BRAND), eq(locationId), any(), eq(FulfillmentMode.PICKUP), eq(CUSTOMER), any()))
                .thenReturn(cart);
        CartRow lined = new CartRow(
                CART_ID,
                TENANT,
                BRAND,
                locationId,
                cart.channelId(),
                CUSTOMER,
                null,
                FulfillmentMode.PICKUP,
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
                        eq(1),
                        any(),
                        any(),
                        any()))
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
