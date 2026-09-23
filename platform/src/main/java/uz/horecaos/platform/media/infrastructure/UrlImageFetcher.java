package uz.horecaos.platform.media.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A bounded {@code GET} for {@code MediaAssetIngestionService}'s server-side
 * image-by-URL fetch (row 4.5b, ADR 0010).
 *
 * <p>Two independent caps, because a lying server can defeat either one alone.
 * A declared {@code Content-Length} over the limit is rejected before a single
 * body byte is read — cheap and usually enough — but a server that omits the
 * header or sends fewer bytes than it declares is still stopped mid-stream: the
 * body is read in chunks and the connection is abandoned the moment the running
 * total would exceed the cap, so an attacker cannot use an unbounded or
 * mis-declared response to grow the heap of whatever holds the returned array.
 *
 * <p>The URL is not an operator's own trusted word: {@code
 * CatalogImportRowService} reaches this from a raw {@code image_url} CSV
 * field a brand's own catalog-editing staff supplies, so this class is a
 * working SSRF primitive unless it refuses to fetch a private, loopback, or
 * link-local address (the cloud metadata endpoint is link-local) -- including
 * one a redirect hands it after the first hop already passed. {@link
 * #isPubliclyRoutable} resolves the host and rejects the fetch outright if
 * any resolved address falls in one of those ranges, and every redirect hop
 * is resolved and checked again before it is followed, because a request
 * that started at an allowed host is not guaranteed to stay at one.
 * Redirects are therefore followed manually with a small hop cap rather than
 * through the JDK's own unrestricted {@code Redirect.NORMAL}, which offers no
 * point to hook a check into. This does not close a DNS-rebinding race
 * between the check and the connection the JDK client makes right after it
 * (the JDK {@code HttpClient} exposes no connect-time hook to pin the
 * resolved address) -- a residual gap worth closing with a custom resolver or
 * connector if this class ever needs to defend against an adversary who
 * controls DNS for the domains it is allowed to fetch from.
 */
@Component
public class UrlImageFetcher {

    private static final Logger log = LoggerFactory.getLogger(UrlImageFetcher.class);

    private static final int CHUNK_SIZE = 8 * 1024;

    private static final int MAX_REDIRECTS = 5;

    private final HttpClient client;
    private final boolean allowLoopbackForTests;

    public UrlImageFetcher() {
        this(false);
    }

    /**
     * Visible for tests that exercise the fetch itself against an in-process
     * server bound to loopback, standing in for a remote host. Spring never
     * wires this overload -- every production instance goes through {@link
     * #UrlImageFetcher()}. The allowance is loopback only: site-local,
     * link-local, and multicast addresses are refused either way, so a test
     * using this constructor can still exercise the refusal itself (a
     * redirect to 169.254.169.254 is still refused, for instance) without
     * needing real network access to a genuinely public host.
     */
    public UrlImageFetcher(boolean allowLoopbackForTests) {
        this.allowLoopbackForTests = allowLoopbackForTests;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                // Followed by hand below, one hop at a time, so each hop's
                // resolved address can be checked before it is connected to.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * @param maxBytes the hard cap; a response declaring or streaming more than
     *                 this is abandoned rather than truncated, because a
     *                 silently truncated image is not the image the URL named
     * @return the body, or empty when the fetch failed, resolved to a
     *         disallowed address, answered with a non-200 status, or
     *         exceeded {@code maxBytes} either by its own declared length or
     *         while streaming
     */
    public Optional<byte[]> fetch(URI url, long maxBytes) {
        URI current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!isPubliclyRoutable(current)) {
                log.info("Image-by-URL fetch refused: {} does not resolve to a public address", current.getHost());
                return Optional.empty();
            }

            HttpRequest request;
            try {
                request = HttpRequest.newBuilder(current)
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
            } catch (IllegalArgumentException malformed) {
                return Optional.empty();
            }

            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException | InterruptedException failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.info("Image-by-URL fetch failed: {}", failure.toString());
                return Optional.empty();
            }

            int status = response.statusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                Optional<URI> next = nextHop(current, response);
                closeQuietly(response);
                if (next.isEmpty()) {
                    return Optional.empty();
                }
                current = next.get();
                continue;
            }

            if (status != 200) {
                closeQuietly(response);
                return Optional.empty();
            }

            return readCapped(response, maxBytes);
        }
        log.info("Image-by-URL fetch refused: {} exceeded {} redirects", url, MAX_REDIRECTS);
        return Optional.empty();
    }

    /** The redirect target, or empty when it is missing, malformed, or not http(s). Never followed blindly -- the caller re-checks it against {@link #isPubliclyRoutable} on the next loop iteration. */
    private static Optional<URI> nextHop(URI current, HttpResponse<InputStream> response) {
        Optional<String> location = response.headers().firstValue("Location");
        if (location.isEmpty()) {
            return Optional.empty();
        }
        URI next;
        try {
            next = current.resolve(new URI(location.get()));
        } catch (URISyntaxException | IllegalArgumentException malformed) {
            return Optional.empty();
        }
        String scheme = next.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return Optional.empty();
        }
        return Optional.of(next);
    }

    private Optional<byte[]> readCapped(HttpResponse<InputStream> response, long maxBytes) {
        // Cheap early rejection when the server states its length honestly.
        // Never trusted as the enforcement point on its own -- the streamed
        // cap below is what actually holds the line against a server that
        // omits or understates this header.
        Optional<String> declaredLength = response.headers().firstValue("Content-Length");
        if (declaredLength.isPresent()) {
            try {
                if (Long.parseLong(declaredLength.get().strip()) > maxBytes) {
                    closeQuietly(response);
                    return Optional.empty();
                }
            } catch (NumberFormatException notNumeric) {
                // Fall through to the streamed cap, which does not depend on this header.
            }
        }

        try (InputStream body = response.body()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[CHUNK_SIZE];
            long total = 0;
            int read;
            while ((read = body.read(chunk)) != -1) {
                total += read;
                if (total > maxBytes) {
                    // Abandoned, not truncated: a cropped image silently
                    // saved as if it were the whole thing is worse than no
                    // image at all.
                    return Optional.empty();
                }
                buffer.write(chunk, 0, read);
            }
            return Optional.of(buffer.toByteArray());
        } catch (IOException failure) {
            log.info("Image-by-URL fetch failed while reading the body: {}", failure.toString());
            return Optional.empty();
        }
    }

    private static void closeQuietly(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException ignored) {
            // Draining a redirect/error response that is about to be discarded; nothing to act on.
        }
    }

    /**
     * Resolves {@code uri}'s host and rejects it unless every resolved
     * address is ordinarily routable on the public internet -- not loopback,
     * link-local (this range covers the cloud metadata endpoint,
     * 169.254.169.254), site-local/private, wildcard, multicast, or an IPv6
     * unique-local address (RFC 4193, {@code fc00::/7} -- see {@link
     * #isAllowed}). A host that resolves to no address, or does not resolve
     * at all, is rejected rather than treated as passing. Resolve-then-check:
     * every address {@code getAllByName} hands back for this hostname is
     * checked, and the fetch is refused if even one of them is disallowed --
     * a hostname is not required to resolve to only one address, and a
     * multi-answer response naming both a public and a private address must
     * not let the private one through.
     */
    private boolean isPubliclyRoutable(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException unresolvable) {
            return false;
        }
        if (addresses.length == 0) {
            return false;
        }
        for (InetAddress address : addresses) {
            if (!isAllowed(address)) {
                return false;
            }
        }
        return true;
    }

    /**
     * True only for an address ordinarily routable on the public internet.
     *
     * <p>Split out from {@link #isPubliclyRoutable} so each resolved address
     * -- including one recovered from an IPv4-mapped IPv6 wrapper below --
     * goes through the identical set of checks, and so the classification
     * itself is unit-testable against hand-built addresses without a live
     * DNS resolution or socket connection standing between the test and the
     * range it means to exercise.
     */
    boolean isAllowed(InetAddress address) {
        if (address.isLoopbackAddress()) {
            return allowLoopbackForTests;
        }
        if (isBlockedRange(address)) {
            return false;
        }
        if (address instanceof Inet6Address v6) {
            byte[] bytes = v6.getAddress();
            if (isUniqueLocal(bytes)) {
                // RFC 4193 fc00::/7 -- IPv6's own private-address space,
                // which includes AWS's IPv6 IMDSv2 endpoint (fd00:ec2::254).
                // InetAddress#isSiteLocalAddress() only recognizes the
                // deprecated, narrower fec0::/10 range and returns false for
                // this one, so it needs its own check.
                return false;
            }
            Optional<InetAddress> embeddedIpv4 = embeddedIpv4(bytes);
            if (embeddedIpv4.isPresent()) {
                // Belt-and-suspenders: the JDK documents that an IPv4-mapped
                // IPv6 literal (::ffff:a.b.c.d) is normalized to a plain
                // Inet4Address by getByName/getAllByName, so this path is
                // not known to be reachable today -- but that normalization
                // is a documented convenience, not a contract this class
                // should depend on for a security check, so a raw
                // Inet6Address carrying an IPv4-mapped payload is unwrapped
                // and re-checked against every IPv4 rule above (including
                // loopback) rather than trusted as "not IPv4, so not
                // private."
                return isAllowed(embeddedIpv4.get());
            }
        }
        return true;
    }

    private static boolean isBlockedRange(InetAddress address) {
        return address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress();
    }

    /** RFC 4193: the two top bits of the first byte are {@code 1}, i.e. the byte is {@code 0xFC} or {@code 0xFD}. */
    private static boolean isUniqueLocal(byte[] v6) {
        return (v6[0] & 0xFE) == 0xFC;
    }

    /** The embedded IPv4 address of an {@code ::ffff:0:0/96} IPv4-mapped IPv6 address, or empty when {@code v6} is not one. */
    private static Optional<InetAddress> embeddedIpv4(byte[] v6) {
        for (int i = 0; i < 10; i++) {
            if (v6[i] != 0) {
                return Optional.empty();
            }
        }
        if ((v6[10] & 0xFF) != 0xFF || (v6[11] & 0xFF) != 0xFF) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByAddress(java.util.Arrays.copyOfRange(v6, 12, 16)));
        } catch (UnknownHostException impossible) {
            // getByAddress only validates array length (4 or 16); a 4-byte
            // array is always accepted.
            throw new IllegalStateException(impossible);
        }
    }
}
