package uz.horecaos.platform.media.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
 * <p>Deliberately not a general outbound HTTP client. It has no allowlist of
 * approved hosts (row 4.5b names that as accepted, "allowlist-free") and no
 * redirect ceiling beyond the JDK default, because the caller is an operator
 * naming their own product photo host rather than an untrusted third party
 * supplying the URL on someone else's behalf. It has exactly one behaviour:
 * fetch, cap, and hand back bytes or nothing — the content-type decision is
 * left entirely to {@code ImageProbe} reading the bytes it returns, never to
 * this class or to any header the remote server sent.
 */
@Component
public class UrlImageFetcher {

    private static final Logger log = LoggerFactory.getLogger(UrlImageFetcher.class);

    private static final int CHUNK_SIZE = 8 * 1024;

    private final HttpClient client;

    public UrlImageFetcher() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * @param maxBytes the hard cap; a response declaring or streaming more than
     *                 this is abandoned rather than truncated, because a
     *                 silently truncated image is not the image the URL named
     * @return the body, or empty when the fetch failed, answered with a
     *         non-200 status, or exceeded {@code maxBytes} either by its own
     *         declared length or while streaming
     */
    public Optional<byte[]> fetch(URI url, long maxBytes) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(url)
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

        if (response.statusCode() != 200) {
            return Optional.empty();
        }

        // Cheap early rejection when the server states its length honestly.
        // Never trusted as the enforcement point on its own -- see the class
        // doc -- the streamed cap below is what actually holds the line
        // against a server that omits or understates this header.
        Optional<String> declaredLength = response.headers().firstValue("Content-Length");
        if (declaredLength.isPresent()) {
            try {
                if (Long.parseLong(declaredLength.get().strip()) > maxBytes) {
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
}
