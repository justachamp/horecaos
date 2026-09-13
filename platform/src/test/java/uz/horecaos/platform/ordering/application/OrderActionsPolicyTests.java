package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.ordering.domain.OrderStateMachine;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * {@code actions[]} against every status, fulfilment mode and grant (orders.md
 * §4.2).
 *
 * <p>Every assertion here is written against the exact call the mutating
 * endpoint makes, not against a hand-written expected table, which is what
 * makes this a drift test rather than a description of {@link
 * OrderActionsPolicy}'s own code read back at itself:
 *
 * <ul>
 *   <li>{@code decide()} accepts APPROVE/REJECT exactly when {@code status ==
 *       AWAITING_APPROVAL} and the caller holds {@code ORDER_APPROVE} — the
 *       same predicate this test applies.
 *   <li>{@code advance()} accepts a target exactly when {@link
 *       OrderStateMachine#permits(OrderStatus, OrderStatus, FulfillmentMode)}
 *       says so and the caller holds {@code ORDER_ADVANCE} — the very method
 *       this test calls to build its expectation.
 *   <li>{@code cancel()}'s reasonless path accepts exactly when {@link
 *       OrderActionsPolicy#canCancelWithoutReason} says so <em>and</em> {@link
 *       OrderStateMachine#permits(OrderStatus, OrderStatus)} allows the
 *       CANCELLED edge <em>and</em> the caller holds {@code ORDER_CANCEL} —
 *       {@code OrderStateService.cancel} was refactored to call {@code
 *       canCancelWithoutReason} directly (rather than repeating its own copy of
 *       the CONFIRMED/PREPARING/READY/FULFILLING list), so this test and the
 *       production guard are reading the same method, not two that happen to
 *       agree today.
 * </ul>
 *
 * <p>A hand-maintained "expected actions per status" table would drift the
 * moment somebody added a transition to {@code OrderStateMachine} without
 * remembering to update it — which is exactly the failure mode orders.md §4.2
 * exists to rule out on the client. Driving both sides of every assertion from
 * {@code OrderStateMachine} and {@code OrderActionsPolicy}'s own extracted
 * guard is what keeps that failure mode out of this test too.
 *
 * <p><b>Wave P05 (gap map 1.2a).</b> Before this wave {@code availableFor} took
 * no principal, so {@code LOCATION_STAFF} — which holds {@code
 * ORDER_APPROVE}/{@code ORDER_ADVANCE} and not {@code ORDER_CANCEL} — was
 * offered «Отменить» on every open order and refused with a 403. The tests
 * below in the "principal-aware" section assert the fix directly, reading
 * each role's real grant set from {@link PlatformRole} rather than a
 * hand-typed capability list, for the same drift-proofing reason as above.
 */
class OrderActionsPolicyTests {

    /** The only capabilities {@link OrderActionsPolicy#availableFor} reads. */
    private static final Set<Capability> ALL_ACTION_CAPS = EnumSet.of(
            Capability.ORDER_APPROVE, Capability.ORDER_ADVANCE, Capability.ORDER_CANCEL, Capability.ORDER_AMEND);

    @Test
    void theScanCoversEveryStatusAndMode() {
        // A test that silently iterated zero combinations would pass forever.
        int combinations = OrderStatus.values().length * FulfillmentMode.values().length;
        assertThat(combinations).isPositive();
    }

    @Test
    void approveAndRejectAppearExactlyOnAwaitingApprovalWhenApproveIsGranted() {
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                List<OrderAction> actions = OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS);
                boolean hasApprove = actions.stream().anyMatch(a -> a.code() == OrderActionCode.APPROVE);
                boolean hasReject = actions.stream().anyMatch(a -> a.code() == OrderActionCode.REJECT);

                if (status == OrderStatus.AWAITING_APPROVAL) {
                    assertThat(hasApprove)
                            .as("%s/%s offers APPROVE", status, mode)
                            .isTrue();
                    assertThat(hasReject)
                            .as("%s/%s offers REJECT", status, mode)
                            .isTrue();
                } else {
                    assertThat(hasApprove)
                            .as("%s/%s must not offer APPROVE", status, mode)
                            .isFalse();
                    assertThat(hasReject)
                            .as("%s/%s must not offer REJECT", status, mode)
                            .isFalse();
                }
            }
        }
    }

    @Test
    void everyAdvanceTargetIsExactlyWhatTheStateMachinePermitsWhenAdvanceIsGranted() {
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                List<OrderStatus> offered = targetsOf(status, mode);

                // The exact call OrderStateService.advance makes: permits(from, to,
                // mode). CANCELLED is carved out because it is offered under its own
                // action code, not ADVANCE.
                List<OrderStatus> expected = OrderStateMachine.transitionsFrom(status).stream()
                        .filter(target -> target != OrderStatus.CANCELLED)
                        .filter(target -> OrderStateMachine.permits(status, target, mode))
                        .toList();

                assertThat(offered)
                        .as("advance targets for %s/%s", status, mode)
                        .containsExactlyInAnyOrderElementsOf(expected);
            }
        }
    }

    /**
     * The one status where fulfilment mode actually changes the answer: a
     * pickup or dine-in order must never be offered {@code READY -> FULFILLING}
     * and a delivery order must never be offered {@code READY -> COMPLETED} —
     * exactly the split {@code OrderStateMachine.permits(from, to, mode)}
     * enforces in the mutating endpoint.
     */
    @Test
    void readyOffersTheModeAppropriateAdvanceOnly() {
        assertThat(targetsOf(OrderStatus.READY, FulfillmentMode.DELIVERY)).containsExactly(OrderStatus.FULFILLING);
        assertThat(targetsOf(OrderStatus.READY, FulfillmentMode.PICKUP)).containsExactly(OrderStatus.COMPLETED);
        assertThat(targetsOf(OrderStatus.READY, FulfillmentMode.DINE_IN)).containsExactly(OrderStatus.COMPLETED);
    }

    @Test
    void cancelAppearsExactlyWhereTheCombinedGuardPermitsItWhenCancelIsGranted() {
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                boolean offered = OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS).stream()
                        .anyMatch(a -> a.code() == OrderActionCode.CANCEL);

                // OrderStateService.cancel's reasonless path, read directly: the
                // extracted policy predicate AND the state machine's CANCELLED edge.
                boolean expected = OrderActionsPolicy.canCancelWithoutReason(status)
                        && OrderStateMachine.permits(status, OrderStatus.CANCELLED);

                assertThat(offered).as("cancel for %s/%s", status, mode).isEqualTo(expected);
            }
        }
    }

    /**
     * Names the concrete rule orders.md §0.3/§1.1 documents, so a refactor that
     * kept the predicates individually correct but broke this specific,
     * customer-visible boundary still fails a test that says what broke.
     */
    @Test
    void cancelIsOfferedBeforeConfirmationAndNowhereFromConfirmedOnward() {
        EnumSet<OrderStatus> expectedCancellable =
                EnumSet.of(OrderStatus.RECEIVED, OrderStatus.PAYMENT_AUTHORIZING, OrderStatus.AWAITING_APPROVAL);

        for (OrderStatus status : OrderStatus.values()) {
            boolean offered =
                    OrderActionsPolicy.availableFor(status, FulfillmentMode.DELIVERY, ALL_ACTION_CAPS).stream()
                            .anyMatch(a -> a.code() == OrderActionCode.CANCEL);
            assertThat(offered).as("%s cancellable today", status).isEqualTo(expectedCancellable.contains(status));
        }
    }

    /** A terminal order offers nothing at all — not even a read-only advance. */
    @Test
    void terminalStatusesOfferNoActions() {
        for (OrderStatus status : OrderStatus.values()) {
            if (!status.terminal()) {
                continue;
            }
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                assertThat(OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS))
                        .as("%s/%s is terminal", status, mode)
                        .isEmpty();
            }
        }
    }

    /**
     * {@code AMEND}'s gate ({@code canAmend}: legal on any order that has not
     * ended, regardless of fulfilment mode or advance/cancel state, mirroring
     * {@code OrderAmendmentService.propose}'s own status guard) is built and
     * correct — but wave P05's adversarial review found the console has no
     * translated label or click handler for a code {@code ORDER_AMEND}
     * already reaches five real roles with, so emission is held behind
     * {@code OrderActionsPolicy.AMEND_EMISSION_ENABLED} until wave P10 ships
     * the amendment client (ADR 0105). This test proves the hold, not the
     * gate: {@code AMEND} must never appear in {@code actions[]} today, on
     * any status or mode, even for a principal holding every other action
     * capability there is. {@link #amendsGateIsBuiltButDisabled} is the
     * companion assertion that the gate itself still exists and is not simply
     * deleted code.
     */
    @Test
    void amendIsNeverEmittedTodayRegardlessOfStatusModeOrGrant() {
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                boolean withFullGrant = OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS).stream()
                        .anyMatch(a -> a.code() == OrderActionCode.AMEND);
                assertThat(withFullGrant)
                        .as("%s/%s must never offer AMEND while emission is held back", status, mode)
                        .isFalse();

                boolean withoutGrant =
                        OrderActionsPolicy.availableFor(status, mode, EnumSet.noneOf(Capability.class)).stream()
                                .anyMatch(a -> a.code() == OrderActionCode.AMEND);
                assertThat(withoutGrant)
                        .as("%s/%s amend, ungranted", status, mode)
                        .isFalse();
            }
        }
    }

    /**
     * The gate {@code AMEND_EMISSION_ENABLED} holds back is exactly the one
     * described in ADR 0105 and the class doc — proved here by reading {@code
     * canAmend} directly, the same predicate the (currently inert) {@code
     * AMEND} branch in {@code availableFor} uses. If this ever disagreed with
     * {@code availableFor}'s own guard, flipping the constant on for wave P10
     * would emit something other than what was designed and tested here.
     */
    @Test
    void amendsGateIsBuiltButDisabled() {
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(OrderActionsPolicy.canAmend(status))
                    .as("%s canAmend", status)
                    .isEqualTo(!status.terminal());
        }
    }

    // -------------------------------------------------- principal-aware (P05)

    /**
     * The exact bug 1.2a named: {@code LOCATION_STAFF} holds {@code
     * ORDER_APPROVE}/{@code ORDER_ADVANCE} and neither {@code ORDER_CANCEL} nor
     * {@code ORDER_AMEND}, so it must never see «Отменить» or the amendment
     * action, on any order, at any status where a fuller grant would have
     * offered one.
     */
    @Test
    void locationStaffNeverOffersCancelOrAmend() {
        Set<Capability> granted = PlatformRole.LOCATION_STAFF.capabilities();
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                List<OrderActionCode> codes = codesOf(status, mode, granted);
                assertThat(codes)
                        .as("location-staff at %s/%s", status, mode)
                        .doesNotContain(OrderActionCode.CANCEL, OrderActionCode.AMEND);
            }
        }
        // And on a status where CANCEL and AMEND would otherwise be legal (RECEIVED
        // is cancellable and amendable), staff still gets only what its own grant
        // justifies: three ADVANCE targets (docs/domains/state-machines.md), never
        // a CANCEL or AMEND alongside them.
        List<OrderActionCode> onReceived = codesOf(OrderStatus.RECEIVED, FulfillmentMode.DELIVERY, granted);
        assertThat(onReceived).isNotEmpty().containsOnly(OrderActionCode.ADVANCE);
    }

    /**
     * {@code SUPPORT_AGENT} holds exactly the inverse of {@code
     * LOCATION_STAFF} among these four: {@code ORDER_CANCEL} and {@code
     * ORDER_AMEND}, never {@code ORDER_APPROVE} or {@code ORDER_ADVANCE}.
     * {@code ORDER_AMEND} is held, but {@code AMEND} itself is never emitted
     * today (see {@link #amendIsNeverEmittedTodayRegardlessOfStatusModeOrGrant}),
     * so this role's only visible action right now is {@code CANCEL}.
     */
    @Test
    void supportAgentOnlyEverOffersCancelToday() {
        Set<Capability> granted = PlatformRole.SUPPORT_AGENT.capabilities();
        assertThat(granted)
                .as("support-agent must hold ORDER_AMEND for this test to prove AMEND stays hidden despite the grant")
                .contains(Capability.ORDER_AMEND);
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                List<OrderActionCode> codes = codesOf(status, mode, granted);
                assertThat(codes)
                        .as("support-agent at %s/%s", status, mode)
                        .doesNotContain(
                                OrderActionCode.APPROVE,
                                OrderActionCode.REJECT,
                                OrderActionCode.ADVANCE,
                                OrderActionCode.AMEND);
            }
        }
        // RECEIVED is cancellable and amendable; support-agent's grant covers
        // both, but only CANCEL is visible while AMEND emission is held back.
        assertThat(codesOf(OrderStatus.RECEIVED, FulfillmentMode.DELIVERY, granted))
                .containsExactly(OrderActionCode.CANCEL);
    }

    /**
     * {@code TENANT_FINANCE}, {@code BRAND_MANAGER} and {@code
     * COURIER_DISPATCHER} each hold {@code ORDER_READ} and none of {@code
     * ORDER_APPROVE}/{@code ORDER_ADVANCE}/{@code ORDER_CANCEL}/{@code
     * ORDER_AMEND} — the gap map named all three as getting the false
     * «Отменить» offer alongside {@code LOCATION_STAFF} and {@code
     * SUPPORT_AGENT}. Each must see an empty {@code actions[]} everywhere.
     */
    @Test
    void readOnlyRolesNeverOfferAnyAction() {
        for (PlatformRole role :
                List.of(PlatformRole.TENANT_FINANCE, PlatformRole.BRAND_MANAGER, PlatformRole.COURIER_DISPATCHER)) {
            Set<Capability> granted = role.capabilities();
            assertThat(granted)
                    .as("%s must hold none of the four gating capabilities for this test to prove anything", role)
                    .doesNotContain(
                            Capability.ORDER_APPROVE,
                            Capability.ORDER_ADVANCE,
                            Capability.ORDER_CANCEL,
                            Capability.ORDER_AMEND);

            for (OrderStatus status : OrderStatus.values()) {
                for (FulfillmentMode mode : FulfillmentMode.values()) {
                    assertThat(OrderActionsPolicy.availableFor(status, mode, granted))
                            .as("%s at %s/%s", role, status, mode)
                            .isEmpty();
                }
            }
        }
    }

    /** {@code LOCATION_MANAGER} holds all four and gets everything its status permits. */
    @Test
    void locationManagerGetsExactlyWhatTheFullGrantJustifies() {
        Set<Capability> granted = PlatformRole.LOCATION_MANAGER.capabilities();
        assertThat(granted).containsAll(ALL_ACTION_CAPS);

        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                assertThat(OrderActionsPolicy.availableFor(status, mode, granted))
                        .as("%s/%s", status, mode)
                        .isEqualTo(OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS));
            }
        }
    }

    // ---------------------------------------------- every code has a route (P05)

    /**
     * {@link OrderActionCode}'s widened set names four routes that do not yet
     * exist from an order — {@code COMPLETE} needs the fulfilment-mode-aware
     * completion reason (gap map {@code P09}), {@code RESOLVE} needs
     * per-amendment state {@code availableFor} does not carry, and {@code
     * ASSIGN_COURIER}/{@code ISSUE_INVOICE} have no endpoint at all yet
     * (gap map {@code P11}/{@code P12}). This switch is exhaustive on purpose:
     * adding a ninth {@link OrderActionCode} constant without adding a branch
     * here fails to <em>compile</em>, so a future change cannot silently start
     * emitting a code from {@link OrderActionsPolicy#availableFor} without
     * this test being forced to take a position on whether that code has a
     * real route.
     *
     * <p>{@code AMEND} answers {@code true} here — its route ({@code POST
     * .../amendments}) genuinely exists and its gate is built — even though
     * {@link #amendIsNeverEmittedTodayRegardlessOfStatusModeOrGrant} proves it
     * is not emitted today. "Has a route" and "is emitted" are different
     * questions for exactly this code: the hold is a frontend-readiness
     * decision (ADR 0105), not a missing endpoint.
     */
    private static boolean hasRealRouteToday(OrderActionCode code) {
        return switch (code) {
            case APPROVE, REJECT -> true; // POST .../approval-decisions
            case ADVANCE -> true; // POST .../state-actions
            case CANCEL -> true; // POST .../cancellations
            case AMEND -> true; // POST .../amendments
            case COMPLETE, RESOLVE, ASSIGN_COURIER, ISSUE_INVOICE -> false;
        };
    }

    @Test
    void everyCodeWithARealRouteIsAccountedForByName() {
        assertThat(EnumSet.allOf(OrderActionCode.class).stream().filter(OrderActionsPolicyTests::hasRealRouteToday))
                .containsExactlyInAnyOrder(
                        OrderActionCode.APPROVE,
                        OrderActionCode.REJECT,
                        OrderActionCode.ADVANCE,
                        OrderActionCode.CANCEL,
                        OrderActionCode.AMEND);
    }

    /**
     * The property orders.md §4.2 exists for: {@code availableFor} never
     * offers a code with no endpoint behind it, at any status, mode or grant —
     * the array cannot lead an operator to a dead end.
     */
    @Test
    void theArrayNeverOffersAnActionWithNoRoute() {
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                // The full grant is the maximal case: availableFor only ever adds
                // actions for a capability present, so anything offered with less
                // than the full grant is offered with the full grant too. Sweeping
                // the maximum is exhaustive for "is this code ever emitted at all".
                for (OrderAction action : OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS)) {
                    assertThat(hasRealRouteToday(action.code()))
                            .as("%s offered at %s/%s must have a real route", action.code(), status, mode)
                            .isTrue();
                }
            }
        }
    }

    private static List<OrderStatus> targetsOf(OrderStatus status, FulfillmentMode mode) {
        return OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS).stream()
                .filter(a -> a.code() == OrderActionCode.ADVANCE)
                .map(OrderAction::targetStatus)
                .toList();
    }

    private static List<OrderActionCode> codesOf(OrderStatus status, FulfillmentMode mode, Set<Capability> granted) {
        return OrderActionsPolicy.availableFor(status, mode, granted).stream()
                .map(OrderAction::code)
                .toList();
    }
}
