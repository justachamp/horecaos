package uz.horecaos.platform.marketing.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a Telegram link needs to name its target (ADR 0183): the bot's {@code @username} and the Mini App's BotFather short
 * name.
 *
 * <p>Declared by {@code marketing}, implemented by {@code integration}: the bot is an installation and its username a
 * fact of the Bot API, and {@code integration} already depends on {@code marketing}, so the reverse dependency would
 * close a cycle. {@code marketing} asks for the two strings it needs and takes nothing else about the installation.
 */
public interface TelegramLinkTargets {

    Target forBrand(UUID tenantId, UUID brandId);

    /**
     * @param botUsername the bot's username without the {@code @}; null when {@code botMissing} says why not
     * @param miniAppShortName set only when the Telegram channel has switched the Mini App on and recorded the short name
     * @param botMissing {@link #NOT_CONNECTED} or {@link #UNREACHABLE} when there is no username, else null
     */
    record Target(
            @Nullable String botUsername,
            @Nullable String miniAppShortName,
            @Nullable String botMissing) {

        /** No active Telegram bot installation serves the brand. */
        public static final String NOT_CONNECTED = "NOT_CONNECTED";

        /** An installation exists, and Telegram would not tell us its username just now. */
        public static final String UNREACHABLE = "UNREACHABLE";

        public static Target notConnected() {
            return new Target(null, null, NOT_CONNECTED);
        }
    }
}
