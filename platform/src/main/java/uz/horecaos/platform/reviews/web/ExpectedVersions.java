package uz.horecaos.platform.reviews.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Where an aggregate mutation's expected version comes from (ADR 0031).
 *
 * <p>The convention is the {@code If-Match} header carrying the version the last read returned (its
 * {@code ETag}). ADR 0159 also puts {@code expectedVersion} in the body of a transition and a moderation
 * decision, so either is accepted; if both are sent they must agree, and if neither is sent the call is
 * refused rather than treated as unconditional -- a caller cannot skip the check by omitting it.
 */
final class ExpectedVersions {

    private ExpectedVersions() {}

    static int resolve(HttpServletRequest request, @Nullable Integer fromBody) {
        Optional<Long> header = AggregateVersion.fromIfMatch(request);
        if (header.isPresent()) {
            long version = header.get();
            if (version < 0 || version > Integer.MAX_VALUE) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "If-Match must carry an aggregate version");
            }
            if (fromBody != null && fromBody.longValue() != version) {
                throw new ApiException(
                        ErrorCode.INVALID_REQUEST, "If-Match and expectedVersion name different versions");
            }
            return (int) version;
        }
        if (fromBody != null) {
            return fromBody;
        }
        throw new ApiException(
                ErrorCode.INVALID_REQUEST,
                "This operation requires an If-Match header (or expectedVersion) carrying the expected version");
    }
}
