package uz.horecaos.platform.conversations.domain;

import org.jspecify.annotations.Nullable;

/**
 * Branches on a previously captured field, evaluated the moment the engine
 * reaches it (redemption time) — never waits on its own.
 *
 * @param field the captured-field key to inspect
 * @param operator how to compare it
 * @param value the comparison value for {@link ConditionOperator#EQUALS};
 *              null for {@code PRESENT}/{@code ABSENT}
 * @param whenTrue the state to advance to when the comparison holds
 * @param whenFalse the state to advance to when it does not
 */
public record ConditionBlock(
        String field, ConditionOperator operator, @Nullable String value, String whenTrue, String whenFalse)
        implements FlowBlock {

    public static final String TYPE = "condition";

    @Override
    public String type() {
        return TYPE;
    }
}
