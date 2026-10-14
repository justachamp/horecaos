package uz.horecaos.platform.integration.provider.telegram;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.marketing.api.TelegramLinkTargets;
import uz.horecaos.platform.tenancy.api.TelegramChannelSettings;
import uz.horecaos.platform.tenancy.api.TelegramChannelSettingsDirectory;

/**
 * What {@code marketing} needs from Telegram to build a link (ADR 0183): the bot's username, from the brand's own bot or
 * the tenant's, and the Mini App's BotFather short name, from the Telegram channel the bot backs.
 *
 * <p>Marketing declares {@link TelegramLinkTargets} and this class implements it, because {@code integration} already
 * depends on {@code marketing} (the outbox listener) and the reverse would close a cycle.
 *
 * <p>A Bot API that will not answer is {@link TelegramLinkTargets.Target#UNREACHABLE}, never an exception: a list of
 * links must render, saying which ones it could not build, and the username is cached on the installation after the first
 * successful read so the call is rare.
 */
@Component
public class TelegramLinkTargetsAdapter implements TelegramLinkTargets {

    private static final Logger log = LoggerFactory.getLogger(TelegramLinkTargetsAdapter.class);

    private final TelegramBotIdentityResolver bots;
    private final TelegramChannelSettingsDirectory settings;

    public TelegramLinkTargetsAdapter(TelegramBotIdentityResolver bots, TelegramChannelSettingsDirectory settings) {
        this.bots = bots;
        this.settings = settings;
    }

    @Override
    public Target forBrand(UUID tenantId, UUID brandId) {
        Optional<TelegramBotIdentityResolver.Installation> installation = bots.activeInstallation(tenantId, brandId);
        if (installation.isEmpty()) {
            return Target.notConnected();
        }
        Optional<String> username;
        try {
            username = bots.resolveUsername(tenantId, installation.get());
        } catch (RuntimeException failure) {
            // A secret that does not resolve, a database hiccup: the same answer as a Bot API that will not speak.
            log.warn(
                    "Could not resolve a Telegram bot's username for a link: {}",
                    failure.getClass().getSimpleName());
            username = Optional.empty();
        }
        if (username.isEmpty()) {
            return new Target(null, null, Target.UNREACHABLE);
        }
        // The Mini App opens one brand's storefront; a link for any other brand must not borrow its short name.
        @Nullable
        String shortName = settings.forInstallation(tenantId, installation.get().id())
                .filter(TelegramChannelSettings::miniAppEnabled)
                .filter(channel -> brandId.equals(channel.brandId()))
                .map(TelegramChannelSettings::miniAppShortName)
                .orElse(null);
        return new Target(username.get(), shortName, null);
    }
}
