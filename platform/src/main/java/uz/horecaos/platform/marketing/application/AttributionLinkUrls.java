package uz.horecaos.platform.marketing.application;

import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.marketing.api.TelegramLinkTargets;
import uz.horecaos.platform.tenancy.api.StorefrontHostnames;

/**
 * The URL a marketer copies for an attribution link (ADR 0183 decision 5; ADR 0044 "Attribution and referrals"; row 6.6a),
 * computed here on the server, once per read, and never stored: a tenant's hostname can change without invalidating an issued
 * token, and the bot's username is Telegram's fact and not ours.
 *
 * <ul>
 *   <li>{@code WEB}: {@code https://<verified hostname>/?ref=<token>}
 *   <li>{@code TELEGRAM_BOT}: {@code https://t.me/<bot>?start=<token>}
 *   <li>{@code TELEGRAM_MINI_APP}: {@code https://t.me/<bot>/<short name>?startapp=<token>}, Telegram's direct link to a Mini App
 *   <li>{@code MOBILE_APP}: none. The Flutter app is on hold (ADR 0055), so there is nowhere for such a link to go
 * </ul>
 *
 * <p>When a link cannot be built the answer says what is missing, as a code the console turns into a sentence in the
 * operator's language, because «{tenant-domain}» printed where a hostname should be is a link nobody can use and nobody can
 * tell why.
 */
@Service
public class AttributionLinkUrls {

    /** What stops a link from being built, as the stable code the console branches on. */
    public enum Unavailable {
        WEB_HOSTNAME_NOT_CLAIMED,
        WEB_HOSTNAME_NOT_VERIFIED,
        WEB_HOSTNAME_AMBIGUOUS,
        TELEGRAM_BOT_NOT_CONNECTED,
        TELEGRAM_BOT_UNREACHABLE,
        TELEGRAM_MINI_APP_NOT_SET_UP,
        MOBILE_APP_ON_HOLD
    }

    /** Exactly one of {@code url} and {@code unavailable} is set. */
    public record Rendered(@Nullable String url, @Nullable Unavailable unavailable) {

        static Rendered of(String url) {
            return new Rendered(url, null);
        }

        static Rendered missing(Unavailable reason) {
            return new Rendered(null, reason);
        }
    }

    /** What Telegram and BotFather allow in a username, a short name and a start parameter. */
    private static final Pattern TELEGRAM_NAME = Pattern.compile("^[A-Za-z0-9_]{3,64}$");

    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{4,64}$");

    private final StorefrontHostnames hostnames;
    private final TelegramLinkTargets telegram;

    public AttributionLinkUrls(StorefrontHostnames hostnames, TelegramLinkTargets telegram) {
        this.hostnames = hostnames;
        this.telegram = telegram;
    }

    /**
     * A renderer for one brand's links. The hostname and the bot are each looked up at most once however many links are
     * rendered, because a list of forty links must not make forty Bot API calls.
     */
    public Renderer forBrand(UUID tenantId, UUID brandId) {
        return new Renderer(tenantId, brandId);
    }

    public Rendered render(UUID tenantId, UUID brandId, String channel, String token) {
        return forBrand(tenantId, brandId).render(channel, token);
    }

    public final class Renderer {

        private final UUID tenantId;
        private final UUID brandId;
        private StorefrontHostnames.@Nullable Resolution storefront;
        private TelegramLinkTargets.@Nullable Target bot;

        private Renderer(UUID tenantId, UUID brandId) {
            this.tenantId = tenantId;
            this.brandId = brandId;
        }

        public Rendered render(String channel, String token) {
            if (!TOKEN.matcher(token).matches()) {
                // Cannot happen for a token this platform minted; refused rather than put into a URL unescaped.
                return Rendered.missing(Unavailable.TELEGRAM_BOT_UNREACHABLE);
            }
            return switch (channel) {
                case "WEB" -> web(token);
                case "TELEGRAM_BOT" -> telegramBot(token);
                case "TELEGRAM_MINI_APP" -> miniApp(token);
                default -> Rendered.missing(Unavailable.MOBILE_APP_ON_HOLD);
            };
        }

        private Rendered web(String token) {
            StorefrontHostnames.Resolution resolution = storefront();
            StorefrontHostnames.Reason reason = resolution.reason();
            if (reason == null) {
                return Rendered.of(resolution.origin() + "/?ref=" + token);
            }
            return Rendered.missing(
                    switch (reason) {
                        case NOT_CLAIMED -> Unavailable.WEB_HOSTNAME_NOT_CLAIMED;
                        case NOT_VERIFIED -> Unavailable.WEB_HOSTNAME_NOT_VERIFIED;
                        case AMBIGUOUS -> Unavailable.WEB_HOSTNAME_AMBIGUOUS;
                    });
        }

        private Rendered telegramBot(String token) {
            String username = botUsername();
            return username != null
                    ? Rendered.of("https://t.me/" + username + "?start=" + token)
                    : Rendered.missing(botMissing());
        }

        private Rendered miniApp(String token) {
            String username = botUsername();
            if (username == null) {
                return Rendered.missing(botMissing());
            }
            String shortName = target().miniAppShortName();
            return shortName != null && TELEGRAM_NAME.matcher(shortName).matches()
                    ? Rendered.of("https://t.me/" + username + "/" + shortName + "?startapp=" + token)
                    : Rendered.missing(Unavailable.TELEGRAM_MINI_APP_NOT_SET_UP);
        }

        private @Nullable String botUsername() {
            String username = target().botUsername();
            return username != null && TELEGRAM_NAME.matcher(username).matches() ? username : null;
        }

        private Unavailable botMissing() {
            return TelegramLinkTargets.Target.NOT_CONNECTED.equals(target().botMissing())
                    ? Unavailable.TELEGRAM_BOT_NOT_CONNECTED
                    : Unavailable.TELEGRAM_BOT_UNREACHABLE;
        }

        private StorefrontHostnames.Resolution storefront() {
            if (storefront == null) {
                storefront = hostnames.forBrand(tenantId, brandId);
            }
            return storefront;
        }

        private TelegramLinkTargets.Target target() {
            if (bot == null) {
                bot = telegram.forBrand(tenantId, brandId);
            }
            return bot;
        }
    }
}
