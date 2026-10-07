package uz.horecaos.platform.storefrontapps.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One web origin as a public client's allowlist holds it and as a request's
 * {@code Origin} header names it (ADR 0070).
 *
 * <p>Exactly {@code scheme://host[:port]}, lowercase, with a default port dropped:
 * that is how a browser writes the header, so the two sides compare as plain
 * strings and nothing in between can disagree about whether
 * {@code https://shop.example.uz:443} is {@code https://shop.example.uz}. No
 * wildcard exists on purpose. A pattern like {@code *.example.uz} lets any
 * subdomain a tenant of that domain can create speak as the app, and the only
 * thing a public client has to be held to is this list.
 *
 * <p>Plain {@code http} is refused except for a loopback host, so a development
 * storefront can register {@code http://localhost:4200} and nothing else can
 * register a cleartext origin.
 */
public final class AppOrigin {

    private AppOrigin() {}

    /**
     * Normalises an origin an operator typed into the allowlist.
     *
     * @throws IllegalArgumentException naming what is wrong, for a 400 an operator can act on
     */
    public static String parseAllowlistEntry(String raw) {
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("An origin must not be blank");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException malformed) {
            throw new IllegalArgumentException("Not a web origin: " + trimmed);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IllegalArgumentException(
                    "An origin must start with https:// (or http:// for localhost): " + trimmed);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("An origin must name a host: " + trimmed);
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("An origin carries no credentials: " + trimmed);
        }
        String path = uri.getRawPath();
        if ((path != null && !path.isEmpty() && !path.equals("/"))
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("An origin is scheme, host and port only, with no path: " + trimmed);
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (lowerHost.contains("*")) {
            throw new IllegalArgumentException("An origin is exact; wildcards are not supported: " + trimmed);
        }
        if (scheme.equals("http") && !isLoopback(lowerHost)) {
            throw new IllegalArgumentException("Only localhost may use plain http: " + trimmed);
        }
        return render(scheme, lowerHost, uri.getPort());
    }

    /**
     * Reads the origin a request names, or empty when the header is absent, the
     * literal {@code null} a sandboxed frame sends, or anything unparsable. Never
     * throws: a request's header is not trusted input to fail on.
     */
    public static Optional<String> fromRequestValue(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(parseAllowlistEntry(raw));
        } catch (IllegalArgumentException refused) {
            return Optional.empty();
        }
    }

    /**
     * The origin of a {@code Referer} URL, for a same-origin read that carries no
     * {@code Origin} header. A browser omits {@code Origin} on a same-origin GET,
     * and the first-party storefronts are served behind the platform's own proxy,
     * so without this the app's own page-load reads would refuse themselves.
     */
    public static Optional<String> fromReferer(@Nullable String referer) {
        if (referer == null || referer.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(referer.strip());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return Optional.empty();
            }
            return fromRequestValue(uri.getScheme().toLowerCase(Locale.ROOT) + "://" + hostWithPort(uri));
        } catch (URISyntaxException malformed) {
            return Optional.empty();
        }
    }

    private static String hostWithPort(URI uri) {
        return uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
    }

    private static boolean isLoopback(String host) {
        return host.equals("localhost")
                || host.endsWith(".localhost")
                || host.equals("127.0.0.1")
                || host.equals("[::1]");
    }

    private static String render(String scheme, String host, int port) {
        boolean defaultPort =
                port < 0 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
        return defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
    }
}
