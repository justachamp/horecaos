package uz.horecaos.platform.ordering.domain;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Why a mark was released (ADR 0161). A code-owned list and no free text, because free text is where a
 * customer's name arrives. The first three are the operator's reason codes on a removal; the last two are
 * written by the platform ({@link #ORDER_CANCELLED} when an order that has not reached the handover is
 * cancelled, rejected or expired; {@link #LINE_REPLACED} is reserved for the day an amendment may carry
 * marks forward and is written by nothing in v1).
 */
public enum MarkReleaseReason {
    MISSCAN(true),
    UNIT_REPLACED(true),
    UNIT_DAMAGED(true),
    ORDER_CANCELLED(false),
    LINE_REPLACED(false);

    private final boolean operatorChoice;

    MarkReleaseReason(boolean operatorChoice) {
        this.operatorChoice = operatorChoice;
    }

    /** Whether an operator may name this reason on a removal. */
    public boolean operatorChoice() {
        return operatorChoice;
    }

    public static Optional<MarkReleaseReason> parseOperatorReason(@Nullable String value) {
        for (MarkReleaseReason reason : values()) {
            if (reason.operatorChoice && reason.name().equals(value)) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }
}
