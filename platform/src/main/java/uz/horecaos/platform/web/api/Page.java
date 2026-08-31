package uz.horecaos.platform.web.api;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A cursor-paginated response body (ADR 0031).
 *
 * <p>There is deliberately no total count and no page number: computing a total
 * over a mutable collection costs a second scan and is stale the moment it is
 * returned. A null {@code nextCursor} means the end of the collection.
 */
public record Page<T>(List<T> items, @Nullable String nextCursor) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAXIMUM_LIMIT = 200;

    public Page {
        items = List.copyOf(Objects.requireNonNull(items, "Items are required"));
    }

    public static <T> Page<T> last(List<T> items) {
        return new Page<>(items, null);
    }

    /** Clamps a client-supplied limit into the documented range. */
    public static int limitOrDefault(@Nullable Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "limit must be at least 1");
        }
        return Math.min(requested, MAXIMUM_LIMIT);
    }
}
