package uz.horecaos.platform.tenancy.domain.channel;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The closed, code-owned set of subdomain slugs row 10.5's WEB channel may
 * not claim under the platform's own base domain.
 *
 * <p>Reserved for the same two reasons {@code tenancy.domain.Slug} exists at
 * all: a tenant that typed "api" or "admin" would either collide with a
 * platform path the moment one is served from the same base domain, or read
 * as an official HorecaOS surface to a customer who has no way to tell the
 * difference. The set is deliberately short and generic rather than an
 * attempt to anticipate every future platform subdomain — new names are
 * added here as they are claimed, never guessed in advance.
 */
public final class ReservedSubdomains {

    /** DNS label shape — one to 63 lowercase letters, digits, or internal hyphens. Matches {@code tenancy.domain.Slug}'s own pattern. */
    private static final Pattern SLUG_FORMAT = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    private static final Set<String> RESERVED = Set.of(
            "www",
            "api",
            "admin",
            "app",
            "console",
            "control-plane",
            "operations",
            "storefront",
            "mail",
            "smtp",
            "ftp",
            "cdn",
            "assets",
            "static",
            "media",
            "docs",
            "support",
            "help",
            "billing",
            "status",
            "kiosk",
            "localhost",
            "horecaos",
            "staging",
            "test",
            "dev",
            "sandbox");

    private ReservedSubdomains() {}

    /** Case-insensitive: a tenant typing "WWW" is asking for the same reserved word as "www". */
    public static boolean isReserved(String slug) {
        return RESERVED.contains(slug.strip().toLowerCase(Locale.ROOT));
    }

    /** Whether {@code slug} is a well-formed DNS label at all, independent of whether it is reserved. */
    public static boolean isWellFormed(String slug) {
        return slug != null && slug.length() <= 63 && SLUG_FORMAT.matcher(slug).matches();
    }
}
