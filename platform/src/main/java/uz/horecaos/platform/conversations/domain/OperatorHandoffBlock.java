package uz.horecaos.platform.conversations.domain;

import org.jspecify.annotations.Nullable;

/**
 * Parks the conversation for a human: sends {@code message} (if any), sets
 * the conversation's state to {@code HANDED_TO_OPERATOR}, and the engine
 * stops answering it.
 *
 * <p>{@code next}, added in ADR 0059 stage 2, is what the inbox's
 * return-to-flow action resumes at: when an operator returns a parked
 * conversation, the engine advances past this block to {@code next} rather
 * than re-executing it (re-executing it would immediately re-park the same
 * conversation). A handoff with no {@code next} — the only shape stage 1 ever
 * authored, and still the default — stays terminal for the engine exactly as
 * before: return-to-flow sends the conversation to {@code IDLE} instead of
 * replaying the handoff.
 *
 * @param message what the customer sees before the handoff, or null to hand
 *                off silently
 * @param next where return-to-flow resumes, or null to leave the
 *             conversation idle once returned rather than replaying this
 *             block
 */
public record OperatorHandoffBlock(
        @Nullable String message, @Nullable String next) implements FlowBlock {

    public static final String TYPE = "operator-handoff";

    @Override
    public String type() {
        return TYPE;
    }
}
