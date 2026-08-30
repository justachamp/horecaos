package uz.horecaos.platform.web.api;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;

/**
 * Optimistic concurrency over HTTP (ADR 0031).
 *
 * <p>A version, not a timestamp: clock skew and same-millisecond updates make
 * timestamps unsafe, and every aggregate in the platform already carries a
 * version column.
 *
 * <p>The {@code ETag} is the version rendered as a weak validator. Weak, because
 * two responses at the same version are semantically equivalent without being
 * byte-identical — field ordering or a formatting change would otherwise
 * invalidate a caller's cache for no reason.
 */
public final class AggregateVersion {

    private AggregateVersion() {
    }

    public static String toETag(long version) {
        return "W/\"%d\"".formatted(version);
    }

    /**
     * Reads the expected version from {@code If-Match}.
     *
     * @return the version, or empty when the header is absent
     * @throws ApiException when the header is present but unparseable, since a
     *                      malformed precondition must never be treated as no
     *                      precondition at all
     */
    public static Optional<Long> fromIfMatch(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.IF_MATCH);
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(parse(header));
    }

    /**
     * Reads a required expected version.
     *
     * @throws ApiException when the header is absent, so a caller cannot skip
     *                      the check by omitting it
     */
    public static long requireIfMatch(HttpServletRequest request) {
        return fromIfMatch(request).orElseThrow(() -> new ApiException(
                ErrorCode.INVALID_REQUEST,
                "This operation requires an If-Match header carrying the expected version"));
    }

    /** Fails when the stored version has moved on, with both versions reported. */
    public static void requireMatch(long expected, long actual) {
        if (expected != actual) {
            throw ApiException.staleVersion(expected, actual);
        }
    }

    private static long parse(String header) {
        String value = header.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "").trim();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST, "If-Match must carry a numeric aggregate version");
        }
    }
}
