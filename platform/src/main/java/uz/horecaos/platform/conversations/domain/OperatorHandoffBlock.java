package uz.horecaos.platform.conversations.domain;

import org.jspecify.annotations.Nullable;

/**
 * Parks the conversation for a human: sends {@code message} (if any), sets
 * the conversation's state to {@code HANDED_TO_OPERATOR}, and the engine
 * stops answering it — stage 2's operator inbox is what returns a
 * conversation from here, not built by this stage, but the state a handed-off
 * conversation sits in has to exist now so stage 2 has something to list.
 *
 * <p>Terminal for the engine: no {@code next}. Nothing this stage builds ever
 * auto-resumes a handoff.
 *
 * @param message what the customer sees before the handoff, or null to hand
 *                off silently
 */
public record OperatorHandoffBlock(@Nullable String message) implements FlowBlock {

    public static final String TYPE = "operator-handoff";

    @Override
    public String type() {
        return TYPE;
    }
}
