package uz.qoida.platform.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The edges that exist, and the one that must not (ADR 0011).
 */
class ExportStateMachineTests {

    @Test
    @DisplayName("an uncertain export cannot be sent again")
    void thereIsNoPathFromUncertainBackToSent() {
        assertThat(ExportStateMachine.permits(ExportState.UNCERTAIN, ExportState.SENT))
                .as("on a provider with no idempotency key, this edge is a second kitchen ticket")
                .isFalse();
    }

    @Test
    @DisplayName("an export waiting on a person cannot be sent again either")
    void thereIsNoPathFromAwaitingOperatorToSent() {
        assertThat(ExportStateMachine.permits(ExportState.AWAITING_OPERATOR, ExportState.SENT))
                .isFalse();
    }

    @Test
    @DisplayName("only an established absence permits another attempt")
    void resolvedAbsentIsTheOnlyStateASecondSendLeavesFrom() {
        for (ExportState state : ExportState.values()) {
            boolean sendable = ExportStateMachine.permits(state, ExportState.SENT);
            assertThat(sendable)
                    .as("%s should%s permit a send", state,
                            state == ExportState.PENDING || state == ExportState.RESOLVED_ABSENT
                                    ? "" : " not")
                    .isEqualTo(state == ExportState.PENDING || state == ExportState.RESOLVED_ABSENT);
        }
    }

    @Test
    @DisplayName("terminal states go nowhere")
    void nothingLeavesATerminalState() {
        for (ExportState state : ExportState.values()) {
            if (!state.terminal()) {
                continue;
            }
            for (ExportState target : ExportState.values()) {
                assertThat(ExportStateMachine.permits(state, target))
                        .as("%s is terminal but permits %s", state, target)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("a refused transition names both states")
    void aRejectionIsDiagnosable() {
        assertThatThrownBy(() ->
                ExportStateMachine.require(ExportState.UNCERTAIN, ExportState.SENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNCERTAIN")
                .hasMessageContaining("SENT");
    }

    @Test
    @DisplayName("an uncertain export can reach a person, or an identity, and nothing else useful")
    void uncertainLeadsOnlyToResolution() {
        assertThat(ExportStateMachine.permits(ExportState.UNCERTAIN, ExportState.AWAITING_OPERATOR))
                .isTrue();
        assertThat(ExportStateMachine.permits(ExportState.UNCERTAIN, ExportState.RESOLVED_LANDED))
                .isTrue();
        assertThat(ExportStateMachine.permits(ExportState.UNCERTAIN, ExportState.RESOLVED_ABSENT))
                .as("nothing may declare an export absent without a person or an echoed reference")
                .isFalse();
    }
}
