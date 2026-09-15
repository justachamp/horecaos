package uz.horecaos.platform.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The &sect;3.11 amendment interlock's own signal (ADR 0039, wave P42), and
 * that it names exactly the four states {@code
 * ordering.application.PosExportStatus#settledFor} already enforces —
 * duplicated by hand because {@code ordering} reads this module's table
 * through SQL rather than importing {@link ExportState} (see that method's
 * own doc for why).
 */
class ExportStatePermitsAmendmentTests {

    private static final Set<ExportState> PERMITS =
            EnumSet.of(ExportState.PENDING, ExportState.REJECTED, ExportState.RESOLVED_ABSENT, ExportState.ABANDONED);

    @Test
    @DisplayName("exactly PENDING, REJECTED, RESOLVED_ABSENT and ABANDONED permit an amendment")
    void namesExactlyTheStatesTheOrderingPortAlreadySettles() {
        for (ExportState state : ExportState.values()) {
            assertThat(state.permitsAmendment())
                    .as("%s should%s permit an amendment", state, PERMITS.contains(state) ? "" : " not")
                    .isEqualTo(PERMITS.contains(state));
        }
    }

    @Test
    @DisplayName("an export the till has confirmed printed still blocks an amendment")
    void anAcceptedExportBlocksAnAmendmentEvenThoughItSucceeded() {
        // The trap this test exists to catch: ACCEPTED reads like a success and
        // is tempting to treat as "safe now", but the kitchen may already hold
        // a ticket this edit would silently leave stale.
        assertThat(ExportState.ACCEPTED.permitsAmendment()).isFalse();
    }

    @Test
    @DisplayName("an export a recovery read could not settle blocks an amendment")
    void anUncertainOrAwaitingOperatorExportBlocksAnAmendment() {
        assertThat(ExportState.UNCERTAIN.permitsAmendment()).isFalse();
        assertThat(ExportState.AWAITING_OPERATOR.permitsAmendment()).isFalse();
    }
}
