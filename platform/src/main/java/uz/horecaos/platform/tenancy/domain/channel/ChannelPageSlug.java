package uz.horecaos.platform.tenancy.domain.channel;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Row 10.5's closed set of storefront static pages, mirrored by
 * {@code ck_channel_page_slug} in migration V0404 so a row written outside
 * this application cannot carry a slug the storefront's {@code /pages/{slug}}
 * route does not know how to render.
 *
 * <p>Closed rather than free text for the same reason {@code
 * SalesChannelSystemType} is: a slug a tenant typed would be a page nobody
 * implemented, and the storefront's {@code /pages/{slug}} route is fixed
 * routing, not a generic CMS — settings.md 10.5's WEB section is explicit
 * that a tenant-authored raw page is a declined shape (block-based/CMS is
 * out of scope; see the amend-the-IA note for row 6.7a).
 */
public enum ChannelPageSlug {
    ABOUT("about"),
    CONTACTS("contacts"),
    DELIVERY_TERMS("delivery-terms"),
    PRIVACY_OFFER("privacy-offer");

    private final String slug;

    ChannelPageSlug(String slug) {
        this.slug = slug;
    }

    public String slug() {
        return slug;
    }

    public static Optional<ChannelPageSlug> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(candidate -> candidate.slug.equals(normalized))
                .findFirst();
    }

    public static ChannelPageSlug require(String value) {
        return parse(value)
                .orElseThrow(() -> new IllegalArgumentException("\"%s\" is not one of the supported static pages %s"
                        .formatted(value, Arrays.toString(values()))));
    }
}
