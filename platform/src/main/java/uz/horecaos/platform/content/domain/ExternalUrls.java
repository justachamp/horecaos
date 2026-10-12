package uz.horecaos.platform.content.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The shape of a URL a customer may be sent to, and nothing about whether this brand may send them
 * there (ADR 0157, Decision 3).
 *
 * <p>The allowlist is the open-redirect control and is consulted at publish and again on every
 * read; this class is the grammar half: {@code https}, no credentials, a lowercase ASCII DNS host
 * (an internationalised name must be written as punycode, so what is compared against the
 * allowlist is what the browser connects to), no port but the default, and no whitespace or control
 * character that could change what a log line or a header says.
 */
public final class ExternalUrls {

    /** What a customer is sent to: the normalised URL and its host, which the allowlist is checked against. */
    public record Parsed(String url, String host) {}

    public static final int MAX_LENGTH = 2048;

    private static final Pattern HOST =
            Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$");

    private static final Pattern SAFE_CHARACTERS = Pattern.compile("^[\\x21-\\x7E]+$");

    private ExternalUrls() {}

    /** An {@code https} URL, normalised, or empty when it is not one a link may carry. */
    public static Optional<Parsed> parseHttps(String raw) {
        if (raw == null
                || raw.length() > MAX_LENGTH
                || !SAFE_CHARACTERS.matcher(raw).matches()) {
            return Optional.empty();
        }
        if (raw.indexOf('\\') >= 0) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException malformed) {
            return Optional.empty();
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("https")) {
            return Optional.empty();
        }
        if (uri.getRawUserInfo() != null || uri.getHost() == null) {
            return Optional.empty();
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return Optional.empty();
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!HOST.matcher(host).matches()) {
            return Optional.empty();
        }
        // Rebuilt from the raw pieces after the authority, so a path and a query are exactly what
        // the author typed and only the scheme and host are case-folded.
        String rest = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            rest += "?" + uri.getRawQuery();
        }
        if (uri.getRawFragment() != null) {
            rest += "#" + uri.getRawFragment();
        }
        return Optional.of(new Parsed("https://" + host + rest, host));
    }
}
