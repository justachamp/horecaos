package uz.horecaos.platform.conversations.api;

import java.util.List;

/**
 * One rendered outbound turn: text, and an optional set of buttons rendered
 * as one inline keyboard — the shape a Telegram {@code sendMessage} call
 * needs, described without naming Telegram.
 */
public record OutboundMessage(String text, List<OutboundButton> buttons) {

    public OutboundMessage {
        buttons = List.copyOf(buttons);
    }

    public static OutboundMessage textOnly(String text) {
        return new OutboundMessage(text, List.of());
    }
}
