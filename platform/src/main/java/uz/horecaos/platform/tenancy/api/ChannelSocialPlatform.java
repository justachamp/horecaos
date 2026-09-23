package uz.horecaos.platform.tenancy.api;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The closed, code-owned set of social platforms a sales channel may link to
 * (ADR 0036, row {@code 10.4a}).
 *
 * <p>The same discipline {@link SalesChannelSystemType} already applies to a
 * channel's own type: a tenant may register any link it wants, but the
 * <em>platform</em> is code, because a console icon is chosen from this list
 * rather than parsed out of the URL. An operator typing a platform outside it
 * would produce a link nothing renders an icon for.
 *
 * <p>Mirrored by {@code ck_channel_social_platform} in migration V0385, so a
 * row inserted outside this application cannot carry a platform the code
 * cannot interpret. Deliberately narrower than {@code support.social_links}'
 * platform set (V0094): a channel's social presence is always a web
 * destination, so {@code PHONE} and {@code EMAIL} are not here.
 */
public enum ChannelSocialPlatform {
    TELEGRAM,
    INSTAGRAM,
    FACEBOOK,
    YOUTUBE,
    TIKTOK,
    WEBSITE;

    public static Optional<ChannelSocialPlatform> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalised = name.strip().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(platform -> platform.name().equals(normalised))
                .findFirst();
    }

    public static ChannelSocialPlatform require(String name) {
        return find(name)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown social platform \"%s\". The set is closed and owned by ADR 0036: %s"
                                .formatted(name, Arrays.toString(values()))));
    }
}
