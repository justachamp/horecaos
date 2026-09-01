package uz.horecaos.platform.conversations.domain;

import java.util.List;

/**
 * Sends a message with an inline keyboard and waits — there is no
 * unconditional {@code next}: a {@code URL} button never produces one, and a
 * {@code CALLBACK} button carries its own {@link FlowButton#next()}, since
 * different buttons legitimately go different places (the welcome series'
 * own shape: an order button that leaves the flow where it is, and a feedback
 * button that advances it).
 *
 * @param text the message body shown above the keyboard
 * @param buttons at least one button; validated non-empty and shape-checked by
 *                {@link FlowDocumentValidator}
 */
public record ButtonsBlock(String text, List<FlowButton> buttons) implements FlowBlock {

    public static final String TYPE = "buttons";

    @Override
    public String type() {
        return TYPE;
    }
}
