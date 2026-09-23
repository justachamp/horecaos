package uz.horecaos.platform.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link UrlImageFetcher}'s SSRF refusal (row 4.5b) -- the property that
 * matters is not "a genuine image comes back" (covered end to end by {@code
 * MediaAssetIngestionServiceTests} and {@code CatalogImportRowServiceTests})
 * but that a caller-supplied URL naming a private, loopback, or link-local
 * address (the cloud metadata endpoint is link-local: 169.254.169.254) never
 * reaches a live connection attempt -- neither as the URL itself nor as
 * where a redirect leads.
 *
 * <p>Every "refused" assertion here also checks the call returned quickly
 * (well under the client's own 5s connect timeout): the fetch failing is not
 * enough on its own, because a fetch that actually dialled a private address
 * and then failed or timed out would refuse just as surely, and that is
 * exactly the working port-scan primitive the fix exists to close.
 */
class UrlImageFetcherTests {

    private static final Duration NO_CONNECTION_ATTEMPTED = Duration.ofSeconds(2);

    private @Nullable HttpServer httpServer;

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void refusesLoopbackByDefaultWithoutConnecting() {
        UrlImageFetcher fetcher = new UrlImageFetcher();

        assertRefusedFast(fetcher, URI.create("http://127.0.0.1:1/x.jpg"));
    }

    @Test
    void refusesLoopbackEvenWhenARealServerIsListening() throws IOException {
        // The port-1 case above proves refusal when nothing is even
        // listening; this proves it is the address policy doing the
        // refusing, not a lucky connection failure -- a real listener
        // answering real bytes must still never be reached.
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        httpServer.createContext("/ok", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        httpServer.start();
        int port = httpServer.getAddress().getPort();

        UrlImageFetcher fetcher = new UrlImageFetcher();
        Optional<byte[]> fetched = fetcher.fetch(URI.create("http://127.0.0.1:" + port + "/ok"), 1024);

        assertThat(fetched)
                .as("a loopback listener must not be reachable through this fetcher")
                .isEmpty();
    }

    @Test
    void refusesASiteLocalAddressWithoutConnecting() {
        UrlImageFetcher fetcher = new UrlImageFetcher();

        assertRefusedFast(fetcher, URI.create("http://10.0.0.5/x.jpg"));
        assertRefusedFast(fetcher, URI.create("http://192.168.1.1/x.jpg"));
        assertRefusedFast(fetcher, URI.create("http://172.16.0.1/x.jpg"));
    }

    @Test
    void refusesTheCloudMetadataAddressWithoutConnecting() {
        UrlImageFetcher fetcher = new UrlImageFetcher();

        assertRefusedFast(fetcher, URI.create("http://169.254.169.254/computeMetadata/v1/"));
    }

    @Test
    void refusesAnIPv6UniqueLocalAddress() throws UnknownHostException {
        // RFC 4193 fc00::/7 -- IPv6's own analogue of RFC1918 private space,
        // and what AWS's IPv6 IMDSv2 endpoint (fd00:ec2::254) uses.
        // InetAddress#isSiteLocalAddress() only recognizes the deprecated,
        // narrower fec0::/10 range and returns false for both halves of
        // fc00::/7 -- verified directly below against the live JDK, not
        // merely asserted -- so it is not covered by the check that already
        // stops 10.0.0.0/8. Built from raw bytes rather than a URI/fetch
        // round trip so the assertion is not at the mercy of whether this
        // sandbox happens to have (or lack) a route to an IPv6 address --
        // only the address-classification logic itself is under test.
        UrlImageFetcher fetcher = new UrlImageFetcher();

        InetAddress uniqueLocal =
                InetAddress.getByAddress(new byte[] {(byte) 0xfc, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
        InetAddress awsIpv6Imds = InetAddress.getByAddress(
                new byte[] {(byte) 0xfd, 0, (byte) 0xec, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0x54});

        assertThat(uniqueLocal.isSiteLocalAddress())
                .as("the JDK's own site-local check does not recognize fc00::/7")
                .isFalse();
        assertThat(fetcher.isAllowed(uniqueLocal)).as("fc00::1 must be refused").isFalse();
        assertThat(fetcher.isAllowed(awsIpv6Imds))
                .as("AWS's IPv6 IMDS address fd00:ec2::254 must be refused")
                .isFalse();
    }

    @Test
    void refusesAnIPv4MappedIPv6MetadataAddress() throws UnknownHostException {
        // ::ffff:169.254.169.254 -- the IPv4-mapped IPv6 form of the cloud
        // metadata address, built byte-for-byte rather than parsed from text
        // so the assertion holds regardless of whether InetAddress.getByName
        // itself already normalizes that literal to a plain Inet4Address on
        // this JDK.
        UrlImageFetcher fetcher = new UrlImageFetcher();

        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = (byte) 169;
        mapped[13] = (byte) 254;
        mapped[14] = (byte) 169;
        mapped[15] = (byte) 254;
        InetAddress ipv4MappedMetadata = InetAddress.getByAddress(mapped);

        assertThat(fetcher.isAllowed(ipv4MappedMetadata))
                .as("the IPv4-mapped IPv6 form of 169.254.169.254 must be refused, same as the IPv4 address itself")
                .isFalse();
    }

    @Test
    void refusesAnIPv4MappedIPv6PrivateAddressEvenWhenNotRecognizedAsLinkLocalOrSiteLocal()
            throws UnknownHostException {
        // A guard against a narrower fix that only special-cases the
        // metadata address: any embedded private/loopback IPv4 address must
        // be refused once unwrapped, not just 169.254.169.254.
        UrlImageFetcher loopbackAllowingFetcher = new UrlImageFetcher(true);
        UrlImageFetcher fetcher = new UrlImageFetcher();

        InetAddress mappedLoopback = ipv4Mapped(127, 0, 0, 1);
        InetAddress mappedPrivate = ipv4Mapped(10, 0, 0, 5);

        assertThat(fetcher.isAllowed(mappedLoopback))
                .as("mapped loopback refused by default")
                .isFalse();
        assertThat(loopbackAllowingFetcher.isAllowed(mappedLoopback))
                .as("mapped loopback allowed only under the test constructor, exactly like a plain IPv4 loopback")
                .isTrue();
        assertThat(fetcher.isAllowed(mappedPrivate))
                .as("mapped private address refused")
                .isFalse();
    }

    @Test
    void allowsAnOrdinaryPublicIPv4AndIPv6Address() throws UnknownHostException {
        UrlImageFetcher fetcher = new UrlImageFetcher();

        assertThat(fetcher.isAllowed(InetAddress.getByAddress(new byte[] {8, 8, 8, 8})))
                .isTrue();
        assertThat(fetcher.isAllowed(InetAddress.getByAddress(
                        new byte[] {0x26, 0x06, 0x47, 0x00, 0x47, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x11})))
                .isTrue();
    }

    private static InetAddress ipv4Mapped(int a, int b, int c, int d) throws UnknownHostException {
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = (byte) a;
        mapped[13] = (byte) b;
        mapped[14] = (byte) c;
        mapped[15] = (byte) d;
        return InetAddress.getByAddress(mapped);
    }

    @Test
    void theTestConstructorsLoopbackAllowanceDoesNotExtendToPrivateOrLinkLocalAddresses() {
        // allowLoopbackForTests exists only so tests can hit their own
        // in-process loopback server; it must not become a blanket bypass.
        UrlImageFetcher fetcher = new UrlImageFetcher(true);

        assertRefusedFast(fetcher, URI.create("http://169.254.169.254/computeMetadata/v1/"));
        assertRefusedFast(fetcher, URI.create("http://10.0.0.5/x.jpg"));
    }

    @Test
    void fetchesSuccessfullyFromLoopbackWhenTheTestConstructorAllowsIt() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        httpServer.createContext("/ok", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        httpServer.start();
        int port = httpServer.getAddress().getPort();

        UrlImageFetcher fetcher = new UrlImageFetcher(true);
        Optional<byte[]> fetched = fetcher.fetch(URI.create("http://127.0.0.1:" + port + "/ok"), 1024);

        assertThat(fetched).isPresent();
        assertThat(fetched.get()).isEqualTo(body);
    }

    @Test
    void refusesARedirectToALinkLocalAddressWithoutEverConnectingToIt() throws IOException {
        // The first hop is loopback (allowed under the test constructor) so
        // the fetch actually starts; the point of this test is entirely
        // about what happens next, when that first, allowed hop redirects
        // to an address the fix must still refuse.
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/computeMetadata/v1/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        httpServer.start();
        int port = httpServer.getAddress().getPort();

        UrlImageFetcher fetcher = new UrlImageFetcher(true);

        assertRefusedFast(fetcher, URI.create("http://127.0.0.1:" + port + "/redirect"));
    }

    @Test
    void refusesAfterTooManyRedirects() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/loop", exchange -> {
            exchange.getResponseHeaders().add("Location", "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        httpServer.start();
        int port = httpServer.getAddress().getPort();

        UrlImageFetcher fetcher = new UrlImageFetcher(true);
        Optional<byte[]> fetched = fetcher.fetch(URI.create("http://127.0.0.1:" + port + "/loop"), 1024);

        assertThat(fetched).isEmpty();
    }

    private static void assertRefusedFast(UrlImageFetcher fetcher, URI url) {
        Instant start = Instant.now();
        Optional<byte[]> fetched = fetcher.fetch(url, 1024);
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(fetched).as("fetch of a disallowed address must be refused").isEmpty();
        assertThat(elapsed)
                .as("a disallowed address must be refused before any connection is attempted, not time out")
                .isLessThan(NO_CONNECTION_ATTEMPTED);
    }
}
