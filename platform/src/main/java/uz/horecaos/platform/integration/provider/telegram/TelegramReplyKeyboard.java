package uz.horecaos.platform.integration.provider.telegram;

import java.util.List;
import java.util.Map;

/**
 * A Bot API custom reply keyboard (ADR 0063) — Telegram's other keyboard shape,
 * distinct from {@link TelegramInlineKeyboard}'s {@code inline_keyboard}. A
 * reply keyboard replaces the chat's own text-entry keyboard rather than
 * attaching to one message, which is why it cannot be edited away the way
 * {@code editMessageReplyMarkup} strips an inline one: {@link #remove()} is
 * sent as the {@code reply_markup} of the <em>next</em> message instead.
 *
 * <p>The one button this type exists for is {@code request_contact}: Telegram
 * itself resolves the tap to the account's own verified phone number and
 * sends it back as a {@code contact} message, with no server-side round trip
 * of any kind — the platform never asks for the number, Telegram supplies it.
 */
public final class TelegramReplyKeyboard {

    private final Map<String, Object> apiShape;

    private TelegramReplyKeyboard(Map<String, Object> apiShape) {
        this.apiShape = apiShape;
    }

    /**
     * The one-button "share my phone number" keyboard (ADR 0063's share-contact
     * sign-in). {@code resize_keyboard} so it does not tower over a phone's
     * screen, and {@code one_time_keyboard} so it disappears the instant the
     * customer taps it or types over it rather than persisting after use.
     */
    public static TelegramReplyKeyboard requestContact(String buttonText) {
        return new TelegramReplyKeyboard(Map.of(
                "keyboard", List.of(List.of(Map.of("text", buttonText, "request_contact", true))),
                "resize_keyboard", true,
                "one_time_keyboard", true));
    }

    /** Clears whatever custom keyboard this chat is currently showing. */
    public static TelegramReplyKeyboard remove() {
        return new TelegramReplyKeyboard(Map.of("remove_keyboard", true));
    }

    Object toApiShape() {
        return apiShape;
    }
}
