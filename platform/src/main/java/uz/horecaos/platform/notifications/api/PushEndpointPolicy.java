package uz.horecaos.platform.notifications.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which push service URLs the platform will send to (ADR 0182, D14).
 *
 * <p><strong>A subscription's URL is typed by a customer's browser, and the platform POSTs to it.</strong>
 * Left open, that is a way to make the platform call any address it can reach, from a customer's
 * session. A browser's push service is one of a handful of operators, so the answer is an allowlist
 * of their hosts: https, the standard port, no credentials in the URL, and a host that is one of the
 * configured suffixes on a label boundary ({@code evil-fcm.googleapis.com} is not
 * {@code fcm.googleapis.com}). It is checked when a subscription arrives, so a bad one is refused
 * to the customer's face, and again immediately before every send, so a stored one cannot outlive a
 * narrowing of the list.
 *
 * <p>Both settings exist for the test and local environments, whose fake push service is plain http
 * on a loopback address, and have production defaults that are the strict ones.
 */
@Component
public class PushEndpointPolicy {

    /** The operators of the push services the mainstream browsers use. Configurable, never widened by code. */
    static final String DEFAULT_ALLOWED_HOSTS = "fcm.googleapis.com,updates.push.services.mozilla.com,"
            + "push.services.mozilla.com,web.push.apple.com,notify.windows.com";

    private static final int MAX_LENGTH = 2048;

    private final List<String> allowedSuffixes;
    private final boolean requireHttps;

    public PushEndpointPolicy(
            @Value("${horecaos.push.allowed-hosts:" + DEFAULT_ALLOWED_HOSTS + "}") String allowedHosts,
            @Value("${horecaos.push.require-https:true}") boolean requireHttps) {
        this.allowedSuffixes = Arrays.stream(allowedHosts.split(","))
                .map(host -> host.strip().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty())
                .toList();
        this.requireHttps = requireHttps;
    }

    /**
     * Why this URL may not be sent to, as a bounded code, or empty when it may. The URL is never part of
     * the answer: it names a device.
     */
    public Optional<String> refusal(String endpoint) {
        if (endpoint == null || endpoint.isBlank() || endpoint.length() > MAX_LENGTH) {
            return Optional.of("PUSH_ENDPOINT_INVALID");
        }
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException malformed) {
            return Optional.of("PUSH_ENDPOINT_INVALID");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (requireHttps ? !"https".equals(scheme) : !("https".equals(scheme) || "http".equals(scheme))) {
            return Optional.of("PUSH_ENDPOINT_NOT_HTTPS");
        }
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            return Optional.of("PUSH_ENDPOINT_INVALID");
        }
        if (requireHttps && uri.getPort() != -1 && uri.getPort() != 443) {
            return Optional.of("PUSH_ENDPOINT_INVALID");
        }
        String lower = host.toLowerCase(Locale.ROOT);
        boolean allowed =
                allowedSuffixes.stream().anyMatch(suffix -> lower.equals(suffix) || lower.endsWith("." + suffix));
        return allowed ? Optional.empty() : Optional.of("PUSH_ENDPOINT_NOT_ALLOWED");
    }
}
