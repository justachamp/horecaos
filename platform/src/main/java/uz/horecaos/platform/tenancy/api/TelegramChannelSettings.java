package uz.horecaos.platform.tenancy.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A TELEGRAM sales channel's own settings (ADR 0183 D27, {@code settings.md} 10.5): whether the bot's menu button opens
 * the Mini App, the short name BotFather gave the Mini App, the menu button label and the welcome text per language, and
 * whether the tenant's terms page is offered as the public offer.
 *
 * <p>No URL is stored. The Mini App's address is the brand's verified storefront hostname ({@link StorefrontHostnames}),
 * so the platform vouches for a hostname it has already proved the tenant controls and for nothing the tenant typed.
 *
 * @param brandId the brand whose storefront the Mini App opens; null until one is chosen
 * @param miniAppShortName the {@code /newapp} short name, the last segment of {@code https://t.me/<bot>/<short name>}
 * @param menuButtonLabels language tag to label, at most 64 characters each (Telegram's limit)
 * @param welcomeTexts language tag to welcome text
 * @param channelVersion the channel's version, the optimistic-lock token a write must present
 */
public record TelegramChannelSettings(
        UUID tenantId,
        UUID channelId,
        @Nullable UUID brandId,
        boolean miniAppEnabled,
        @Nullable String miniAppShortName,
        boolean publicOfferLink,
        Map<String, String> menuButtonLabels,
        Map<String, String> welcomeTexts,
        int channelVersion) {

    public TelegramChannelSettings {
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(channelId, "A channel id is required");
        menuButtonLabels = Map.copyOf(menuButtonLabels);
        welcomeTexts = Map.copyOf(welcomeTexts);
    }

    /** A channel nobody has configured: everything off, nothing written. */
    public static TelegramChannelSettings unconfigured(UUID tenantId, UUID channelId, int channelVersion) {
        return new TelegramChannelSettings(
                tenantId, channelId, null, false, null, false, Map.of(), Map.of(), channelVersion);
    }

    /** The label for {@code locale}, else the first of {@code fallbacks} that has one. */
    public Optional<String> menuButtonLabel(String locale, String... fallbacks) {
        return pick(menuButtonLabels, locale, fallbacks);
    }

    /** The welcome text for {@code locale}, else the first of {@code fallbacks} that has one. */
    public Optional<String> welcomeText(String locale, String... fallbacks) {
        return pick(welcomeTexts, locale, fallbacks);
    }

    private static Optional<String> pick(Map<String, String> byLocale, String locale, String... fallbacks) {
        String exact = byLocale.get(locale);
        if (exact != null) {
            return Optional.of(exact);
        }
        for (String fallback : fallbacks) {
            String text = byLocale.get(fallback);
            if (text != null) {
                return Optional.of(text);
            }
        }
        return Optional.empty();
    }
}
