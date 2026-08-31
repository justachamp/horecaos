package uz.horecaos.platform.integration.provider.telegram;

import java.util.List;
import java.util.Map;

/**
 * A Bot API {@code inline_keyboard} (ADR 0060 §2).
 *
 * <p>Every button's {@code callbackData} must already be the opaque token the
 * review insists on — this type carries whatever string it is handed and
 * enforces nothing about its shape, because the shape rule (a
 * {@link BotActionTokenStore}-minted token, never anything self-describing)
 * belongs to the caller that builds one, not to the wire format.
 */
public record TelegramInlineKeyboard(List<List<Button>> rows) {

    public record Button(String text, String callbackData) {}

    public static TelegramInlineKeyboard singleRow(Button... buttons) {
        return new TelegramInlineKeyboard(List.of(List.of(buttons)));
    }

    /** The Bot API {@code reply_markup} JSON shape. */
    Object toApiShape() {
        return Map.of(
                "inline_keyboard",
                rows.stream()
                        .map(row -> row.stream()
                                .map(button -> Map.of(
                                        "text", button.text(),
                                        "callback_data", button.callbackData()))
                                .toList())
                        .toList());
    }
}
