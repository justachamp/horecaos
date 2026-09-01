package uz.horecaos.platform.conversations.domain;

import org.jspecify.annotations.Nullable;

/**
 * Sends one rendered text message, then advances unconditionally.
 *
 * @param text the message body, possibly carrying {@code {{variable}}}
 *             placeholders {@link FlowTemplate} resolves at send time
 * @param next the state to advance to, or null to end the flow here — the
 *             same "no next means the flow completes" rule every
 *             unconditionally-advancing block follows
 */
public record MessageBlock(String text, @Nullable String next) implements FlowBlock {

    public static final String TYPE = "message";

    @Override
    public String type() {
        return TYPE;
    }
}
