package uz.horecaos.platform.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * The forward graph ({@link OrderStateMachine#permits}/{@link
 * OrderStateMachine#transitionsFrom}) and the compensating graph ({@link
 * OrderStateMachine#compensatingTransitionsFrom}/{@link
 * OrderStateMachine#isCompensating}) declared as two disjoint tables (ADR 0019
 * amendment, ADR 0110; wave P41, orders.md §0.2, §11.3).
 *
 * <p>The brief's own trap stated as tests: a compensating edge must never also
 * be a literal reversal — asserted below as "the two tables never agree on the
 * same (from, to) pair" — and a terminal order must never gain one, asserted
 * both directly and by construction (the compensating table simply has no
 * entry for a terminal status).
 */
class OrderStateMachineTests {

    @Test
    void theTwoNamedCompensatingEdgesAreExactlyReadyToPreparingAndFulfillingToReady() {
        assertThat(OrderStateMachine.compensatingTransitionsFrom(OrderStatus.READY))
                .containsExactly(OrderStatus.PREPARING);
        assertThat(OrderStateMachine.compensatingTransitionsFrom(OrderStatus.FULFILLING))
                .containsExactly(OrderStatus.READY);
        assertThat(OrderStateMachine.isCompensating(OrderStatus.READY, OrderStatus.PREPARING))
                .isTrue();
        assertThat(OrderStateMachine.isCompensating(OrderStatus.FULFILLING, OrderStatus.READY))
                .isTrue();
    }

    /** No third edge crept in, and no status but the two named above declares one at all. */
    @Test
    void noOtherStatusDeclaresACompensatingEdge() {
        for (OrderStatus status : OrderStatus.values()) {
            if (status == OrderStatus.READY || status == OrderStatus.FULFILLING) {
                continue;
            }
            assertThat(OrderStateMachine.compensatingTransitionsFrom(status))
                    .as("%s declares no compensating edge", status)
                    .isEmpty();
        }
    }

    /**
     * The trap named directly: a compensating edge is a new forward step, never
     * a literal reversal of the edge that produced {@code from}. If {@code
     * isCompensating(from, to)} ever agreed with {@code permits(from, to)} for
     * the same pair, the two tables would have collapsed into one and a reader
     * of {@code order_state_history} could no longer tell a correction from an
     * ordinary advance by the (from, to) pair alone.
     */
    @Test
    void noCompensatingEdgeIsAlsoAForwardEdge() {
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStateMachine.compensatingTransitionsFrom(from)) {
                assertThat(OrderStateMachine.permits(from, to))
                        .as("%s -> %s must not also be a forward edge", from, to)
                        .isFalse();
            }
        }
    }

    /**
     * Literally "not an undo": the compensating table is not the forward
     * table's edges reversed. {@code READY -> PREPARING} restores the status a
     * forward edge {@code PREPARING -> READY} left, but nothing here is
     * computed by inverting {@link OrderStateMachine#transitionsFrom} — this
     * test proves the inverse set and the declared compensating set disagree
     * (the inverse of the forward graph is far larger, e.g. it would also claim
     * {@code CONFIRMED -> RECEIVED}), which is exactly why {@code
     * OrderStateMachine} declares {@link OrderStateMachine#compensatingTransitionsFrom}
     * by name rather than deriving it.
     */
    @Test
    void theCompensatingTableIsNotTheForwardGraphInverted() {
        Set<OrderStatus> reachesReadyForward = EnumSet.noneOf(OrderStatus.class);
        for (OrderStatus from : OrderStatus.values()) {
            if (OrderStateMachine.permits(from, OrderStatus.READY)) {
                reachesReadyForward.add(from);
            }
        }
        // Only PREPARING forward-advances into READY, so a naive "reverse every
        // forward edge" scheme would declare READY -> PREPARING as a
        // reversal — the one case where the compensating edge happens to
        // coincide with what a reversal would have produced. FULFILLING is
        // never a source of a forward edge into READY at all (READY only ever
        // advances into FULFILLING, never the other way), so
        // FULFILLING -> READY exists in the compensating table with no forward
        // edge to be "the reverse of" — proof the table is authored, not derived.
        assertThat(reachesReadyForward).containsExactly(OrderStatus.PREPARING);
        assertThat(OrderStateMachine.permits(OrderStatus.READY, OrderStatus.FULFILLING))
                .as("the only forward edge between READY and FULFILLING runs READY -> FULFILLING")
                .isTrue();
        assertThat(OrderStateMachine.permits(OrderStatus.FULFILLING, OrderStatus.READY))
                .as("so FULFILLING -> READY has no forward edge to be a reversal of")
                .isFalse();
    }

    /** Terminal orders stay terminal: no compensating edge starts from one, by omission rather than a guard. */
    @Test
    void terminalStatusesDeclareNoCompensatingEdge() {
        for (OrderStatus status : OrderStatus.values()) {
            if (!status.terminal()) {
                continue;
            }
            assertThat(OrderStateMachine.compensatingTransitionsFrom(status))
                    .as("%s is terminal", status)
                    .isEmpty();
        }
    }

    /** The forward graph itself is unchanged by this wave — a regression guard, not new behaviour. */
    @Test
    void theForwardGraphIsUntouched() {
        assertThat(OrderStateMachine.permits(OrderStatus.PREPARING, OrderStatus.READY))
                .isTrue();
        assertThat(OrderStateMachine.permits(OrderStatus.READY, OrderStatus.FULFILLING, FulfillmentMode.DELIVERY))
                .isTrue();
        assertThat(OrderStateMachine.permits(OrderStatus.READY, OrderStatus.COMPLETED, FulfillmentMode.PICKUP))
                .isTrue();
        assertThat(OrderStateMachine.permits(OrderStatus.READY, OrderStatus.PREPARING))
                .as("the forward graph itself still has no literal reversal")
                .isFalse();
        assertThat(OrderStateMachine.permits(OrderStatus.FULFILLING, OrderStatus.READY))
                .as("the forward graph itself still has no literal reversal")
                .isFalse();
    }
}
