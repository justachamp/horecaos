package uz.horecaos.platform.media.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
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
