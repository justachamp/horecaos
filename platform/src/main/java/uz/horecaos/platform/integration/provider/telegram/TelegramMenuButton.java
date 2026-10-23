package uz.horecaos.platform.integration.provider.telegram;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The button beside a chat's message field (Bot API {@code MenuButton}, ADR 0183 D27).
 *
 * <p>Only the three shapes the Bot API defines. {@link WebApp} is the one the tenant's Mini App uses: a label and the
 * address it opens, which the platform derives from the brand's verified storefront hostname and never takes from a
 * tenant.
 */
public sealed interface TelegramMenuButton {

    /** The Bot API {@code MenuButton} object. */
    Map<String, Object> toApiShape();

    /** The bot's own command list. */
    record Commands() implements TelegramMenuButton {
        @Override
        public Map<String, Object> toApiShape() {
            return Map.of("type", "commands");
        }
    }

    /** Telegram's default: the chat's own behaviour, which for a bot with a Mini App is the "Open" button. */
    record Default() implements TelegramMenuButton {
        @Override
        public Map<String, Object> toApiShape() {
            return Map.of("type", "default");
        }
    }

    /**
     * Opens a Mini App.
     *
     * @param text the label; Telegram allows at most 64 characters
     * @param url an https address; Telegram refuses anything else
     */
    record WebApp(String text, String url) implements TelegramMenuButton {

        public WebApp {
            if (text == null || text.isBlank() || text.length() > 64) {
                throw new IllegalArgumentException("A menu button label is 1 to 64 characters");
            }
            if (url == null || !url.startsWith("https://")) {
                throw new IllegalArgumentException("A Mini App address must be https");
            }
        }

        @Override
        public Map<String, Object> toApiShape() {
            Map<String, Object> shape = new LinkedHashMap<>();
            shape.put("type", "web_app");
            shape.put("text", text);
            shape.put("web_app", Map.of("url", url));
            return shape;
        }
    }

    /**
     * Reads a {@code getChatMenuButton} result back. An unrecognised or malformed shape reads as {@link Default}: the safest
     * thing to believe about a button the platform cannot read is that it is not the one it set.
     */
    static TelegramMenuButton fromApiShape(@Nullable Map<String, Object> result) {
        if (result == null) {
            return new Default();
        }
        Object type = result.get("type");
        if ("web_app".equals(type)
                && result.get("text") instanceof String text
                && result.get("web_app") instanceof Map<?, ?> webApp
                && webApp.get("url") instanceof String url) {
            try {
                return new WebApp(text, url);
            } catch (IllegalArgumentException refused) {
                return new Default();
            }
        }
        return "commands".equals(type) ? new Commands() : new Default();
    }

    /** The address this button opens, if it is a Mini App button. */
    default Optional<String> webAppUrl() {
        return this instanceof WebApp webApp ? Optional.of(webApp.url()) : Optional.empty();
    }
}
