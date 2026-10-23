package uz.horecaos.platform.integration.provider.telegram;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.provider.telegram.TelegramWebhookInstallationLookup.WebhookInstallation;
import uz.horecaos.platform.marketing.api.AnonymousVisitorHandle;
import uz.horecaos.platform.marketing.api.AttributionClickPort;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.api.PlatformLocale.Tier;
import uz.horecaos.platform.tenancy.api.PlatformLocales;
import uz.horecaos.platform.tenancy.api.StorefrontHostnames;
import uz.horecaos.platform.tenancy.api.TelegramChannelSettings;
import uz.horecaos.platform.tenancy.api.TelegramChannelSettingsDirectory;

/**
 * What the bot does when a customer opens it (ADR 0183): counts a click for an attribution link the customer arrived by,
 * and answers with the channel's own welcome.
 *
 * <p><strong>A click is a count, not an event</strong> (ADR 0167 Decision 5). The only thing that stands for the customer is
 * {@link AnonymousVisitorHandle}, a keyed hash of the Telegram user id that exists in the ADR 0033 limiter's memory for sixty
 * seconds. No chat id, user id, name or language reaches the click counter, a log line or a metric.
 *
 * <p><strong>The welcome belongs to a configured channel.</strong> A bot whose Telegram channel nobody has configured stays
 * exactly as silent as it was; one with a welcome text, the Mini App on or the offer link on greets in the customer's own
 * language (Telegram's {@code language_code}, read through the registry), falling back to the brand's default language, then
 * to the platform's wording. It also sets the menu button for that one chat in the customer's language, because Telegram keeps
 * a single label per bot and the per-chat call is how the other languages are ever seen.
 *
 * <p><strong>A per-chat button outlives the Mini App that set it.</strong> Telegram keeps it until something replaces it and
 * has no call that lists or clears them all. So when the Mini App is off, or the storefront has no verified address, a
 * customer who starts the bot again and still holds a Mini App button in that chat is put back to Telegram's default: the
 * button never keeps opening an address the platform no longer vouches for. A channel with neither the Mini App switch nor a
 * short name is taken for one that never had a Mini App: it is not asked, so a button another tool set is not touched. A chat
 * that holds no Mini App button is left exactly as it is.
 */
@Component
public class TelegramCustomerLanding {

    private static final Logger log = LoggerFactory.getLogger(TelegramCustomerLanding.class);

    private final AttributionClickPort clicks;
    private final TelegramChannelSettingsDirectory settings;
    private final StorefrontHostnames hostnames;
    private final BrandLocaleLookup brandLocales;
    private final TelegramInstallationBrandLookup installationBrands;
    private final TelegramBotApiClient bots;

    public TelegramCustomerLanding(
            AttributionClickPort clicks,
            TelegramChannelSettingsDirectory settings,
            StorefrontHostnames hostnames,
            BrandLocaleLookup brandLocales,
            TelegramInstallationBrandLookup installationBrands,
            TelegramBotApiClient bots) {
        this.clicks = clicks;
        this.settings = settings;
        this.hostnames = hostnames;
        this.brandLocales = brandLocales;
        this.installationBrands = installationBrands;
        this.bots = bots;
    }

    /**
     * Counts a {@code /start <payload>} that is not a customer link code or a sign-in code, if the payload is an attribution
     * token of a live link of this bot's brand.
     *
     * @return whether the payload was a link at all (counted, or rate limited and so not looked up): the caller then answers
     *         as for a bare {@code /start}. False means it was not, and the caller refuses it as it always did
     */
    public boolean countAttributionStart(WebhookInstallation installation, long fromUserId, String payload) {
        UUID brandId =
                installationBrands.brandFor(installation.installationId()).orElse(null);
        AttributionClickPort.Outcome outcome = clicks.click(
                installation.tenantId(), brandId, payload, AnonymousVisitorHandle.of("tg", String.valueOf(fromUserId)));
        return outcome != AttributionClickPort.Outcome.NOT_A_LINK;
    }

    /**
     * Greets a customer who started the bot, with the channel's welcome, and puts the Mini App on their menu button in
     * their language.
     *
     * @return whether anything was sent; false for a bot whose Telegram channel nobody has configured
     */
    public boolean greet(
            WebhookInstallation installation, ProviderCall call, long chatId, @Nullable String languageCode) {
        Optional<TelegramChannelSettings> found =
                settings.forInstallation(installation.tenantId(), installation.installationId());
        if (found.isEmpty() || !configured(found.get())) {
            return false;
        }
        TelegramChannelSettings channel = found.get();
        String locale = PlatformLocales.resolve(languageCode, Tier.MESSAGES);
        UUID brandId = channel.brandId() != null
                ? channel.brandId()
                : installationBrands.brandFor(installation.installationId()).orElse(null);
        String brandDefault = brandId == null
                ? PlatformLocales.fallback().tag()
                : brandLocales
                        .brandDefaultLocale(installation.tenantId(), brandId)
                        .orElse(PlatformLocales.fallback().tag());

        String origin = channel.brandId() == null ? null : storefrontOrigin(installation.tenantId(), channel.brandId());
        boolean miniApp = channel.miniAppEnabled() && origin != null;

        StringBuilder message = new StringBuilder(channel.welcomeText(
                        locale, brandDefault, PlatformLocales.fallback().tag())
                .orElseGet(() -> TelegramBotMessages.welcomeDefault(locale, miniApp)));
        if (channel.publicOfferLink() && origin != null) {
            message.append("\n\n")
                    .append(TelegramBotMessages.publicOfferLabel(locale))
                    .append(": ")
                    .append(origin)
                    .append("/terms");
        }
        bots.sendMessage(call, chatId, null, message.toString());

        if (miniApp) {
            localiseMenuButton(call, chatId, channel, locale, brandDefault, origin + "/");
        } else if (channel.miniAppEnabled() || channel.miniAppShortName() != null) {
            takeStaleMenuButtonOff(call, chatId);
        }
        return true;
    }

    private @Nullable String storefrontOrigin(UUID tenantId, UUID brandId) {
        StorefrontHostnames.Resolution storefront = hostnames.forBrand(tenantId, brandId);
        return storefront.resolved() ? storefront.origin() : null;
    }

    private void localiseMenuButton(
            ProviderCall call,
            long chatId,
            TelegramChannelSettings channel,
            String locale,
            String brandDefault,
            String url) {
        Optional<String> label = channel.menuButtonLabel(
                locale, brandDefault, PlatformLocales.fallback().tag());
        if (label.isEmpty()) {
            return;
        }
        try {
            bots.setChatMenuButton(call, chatId, new TelegramMenuButton.WebApp(label.get(), url));
        } catch (IllegalArgumentException refused) {
            // A stored label or address Telegram would refuse: the welcome has been sent, the button is a nicety.
            log.debug("Skipped a per-chat menu button: {}", refused.getClass().getSimpleName());
        }
    }

    /** Puts this chat's button back to Telegram's default when it still opens a Mini App; reads before it writes. */
    private void takeStaleMenuButtonOff(ProviderCall call, long chatId) {
        try {
            if (bots.getChatMenuButton(call, chatId) instanceof TelegramCallResult.Success held
                    && TelegramMenuButton.fromApiShape(held.result())
                            .webAppUrl()
                            .isPresent()) {
                bots.setChatMenuButton(call, chatId, new TelegramMenuButton.Default());
            }
        } catch (RuntimeException failure) {
            // The greeting has been sent; the button is a nicety and the next start tries again.
            log.debug(
                    "Skipped taking a per-chat menu button off: {}",
                    failure.getClass().getSimpleName());
        }
    }

    private static boolean configured(TelegramChannelSettings channel) {
        return channel.miniAppEnabled()
                || channel.publicOfferLink()
                || !channel.welcomeTexts().isEmpty();
    }
}
