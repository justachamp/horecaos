package uz.horecaos.platform.integration.provider.telegram;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A Bot API {@code inline_keyboard} (ADR 0060 §2, ADR 0059).
 *
 * <p>Every {@code callbackData} button's payload must already be the opaque
 * token the review insists on — this type carries whatever string it is
 * handed and enforces nothing about its shape, because the shape rule (a
 * {@link BotActionTokenStore}-minted token, or ADR 0059's own {@code cvb:}-
 * namespaced flow key, never anything else self-describing) belongs to the
 * caller that builds one, not to the wire format. A {@code url} button is the
 * one shape with nothing to keep opaque — Telegram opens it directly and
 * never reports the tap back at all.
 */
public record TelegramInlineKeyboard(List<List<Button>> rows) {

    /** Exactly one of {@code callbackData} or {@code url} is set. */
    public record Button(
            String text,
            @Nullable String callbackData,
            @Nullable String url) {

        public Button {
            if ((callbackData == null) == (url == null)) {
                throw new IllegalArgumentException("A button needs exactly one of callbackData or url");
            }
        }

        /** The everyday shape every existing caller (order decisions, the tenant picker) already used. */
        public Button(String text, String callbackData) {
            this(text, callbackData, null);
        }

        public static Button url(String text, String url) {
            return new Button(text, null, url);
        }
    }

    public static TelegramInlineKeyboard singleRow(Button... buttons) {
        return new TelegramInlineKeyboard(List.of(List.of(buttons)));
    }

    /** The Bot API {@code reply_markup} JSON shape. */
    Object toApiShape() {
        return Map.of(
                "inline_keyboard",
                rows.stream()
                        .map(row -> row.stream()
                                .map(TelegramInlineKeyboard::buttonShape)
                                .toList())
                        .toList());
    }

    private static Map<String, String> buttonShape(Button button) {
        Map<String, String> shape = new LinkedHashMap<>();
        shape.put("text", button.text());
        if (button.url() != null) {
            shape.put("url", button.url());
        } else {
            shape.put("callback_data", button.callbackData());
        }
        return shape;
    }
}
