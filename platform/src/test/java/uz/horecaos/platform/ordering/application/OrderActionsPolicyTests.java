package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.ordering.domain.OrderStateMachine;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * {@code actions[]} against every status and fulfilment mode (orders.md §4.2).
 *
 * <p>Every assertion here is written against the exact call the mutating
 * endpoint makes, not against a hand-written expected table, which is what
 * makes this a drift test rather than a description of {@link
 * OrderActionsPolicy}'s own code read back at itself:
 *
 * <ul>
 *   <li>{@code decide()} accepts APPROVE/REJECT exactly when {@code status ==
 *       AWAITING_APPROVAL} — the same predicate this test applies.
 *   <li>{@code advance()} accepts a target exactly when {@link
 *       OrderStateMachine#permits(OrderStatus, OrderStatus, FulfillmentMode)}
 *       says so — the very method this test calls to build its expectation.
 *   <li>{@code cancel()}'s reasonless path accepts exactly when {@link
 *       OrderActionsPolicy#canCancelWithoutReason} says so <em>and</em> {@link
 *       OrderStateMachine#permits(OrderStatus, OrderStatus)} allows the
 *       CANCELLED edge — {@code OrderStateService.cancel} was refactored to
 *       call {@code canCancelWithoutReason} directly (rather than repeating its
 *       own copy of the CONFIRMED/PREPARING/READY/FULFILLING list), so this
 *       test and the production guard are reading the same method, not two
 *       that happen to agree today.
 * </ul>
 *
 * <p>A hand-maintained "expected actions per status" table would drift the
 * moment somebody added a transition to {@code OrderStateMachine} without
 * remembering to update it — which is exactly the failure mode orders.md §4.2
 * exists to rule out on the client. Driving both sides of every assertion from
 * {@code OrderStateMachine} and {@code OrderActionsPolicy}'s own extracted
 * guard is what keeps that failure mode out of this test too.
 */
class OrderActionsPolicyTests {

    @Test
    void theScanCoversEveryStatusAndMode() {
        // A test that silently iterated zero combinations would pass forever.
        int combinations = OrderStatus.values().length * FulfillmentMode.values().length;
        assertThat(combinations).isPositive();
    }

    @Test
    void approveAndRejectAppearExactlyOnAwaitingApproval() {
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                List<OrderAction> actions = OrderActionsPolicy.availableFor(status, mode);
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
    void everyAdvanceTargetIsExactlyWhatTheStateMachinePermits() {
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                List<OrderStatus> offered = OrderActionsPolicy.availableFor(status, mode).stream()
                        .filter(a -> a.code() == OrderActionCode.ADVANCE)
                        .map(OrderAction::targetStatus)
                        .toList();

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
    void cancelAppearsExactlyWhereTheCombinedGuardPermitsIt() {
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                boolean offered = OrderActionsPolicy.availableFor(status, mode).stream()
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
            boolean offered = OrderActionsPolicy.availableFor(status, FulfillmentMode.DELIVERY).stream()
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
                assertThat(OrderActionsPolicy.availableFor(status, mode))
                        .as("%s/%s is terminal", status, mode)
                        .isEmpty();
            }
        }
    }

    private static List<OrderStatus> targetsOf(OrderStatus status, FulfillmentMode mode) {
        return OrderActionsPolicy.availableFor(status, mode).stream()
                .filter(a -> a.code() == OrderActionCode.ADVANCE)
                .map(OrderAction::targetStatus)
                .toList();
    }
}
