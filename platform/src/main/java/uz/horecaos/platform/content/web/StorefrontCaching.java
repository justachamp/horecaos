package uz.horecaos.platform.content.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * How the storefront content reads are cached (ADR 0157, Decision 9): a body-digest {@code ETag} and
 * {@code Cache-Control: public, max-age=60}.
 *
 * <p>There is no timestamp in any of these bodies, so the digest moves only when content does; and no
 * server-side cache is registered, because the reads are a handful of indexed queries and a cache here
 * would be one more thing to invalidate when a window opens or a host leaves the allowlist.
 */
final class StorefrontCaching {

    /** How long a browser or an edge may reuse a document without asking. */
    static final Duration MAX_AGE = Duration.ofSeconds(60);

    /** Serialises with map entries in key order, so two equal documents always digest alike. */
    private static final JsonMapper DIGEST_MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private StorefrontCaching() {}

    static <T> ResponseEntity<T> cacheable(T body) {
        return ResponseEntity.ok()
                .eTag("\"" + digest(body) + "\"")
                .cacheControl(CacheControl.maxAge(MAX_AGE).cachePublic())
                .body(body);
    }

    private static String digest(Object body) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(DIGEST_MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }
}
