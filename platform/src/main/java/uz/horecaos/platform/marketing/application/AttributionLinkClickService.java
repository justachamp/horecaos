package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAttributionLinkStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * Counts a click on an attribution link (ADR 0167 Decision 4 tier 1 and Decision 5; ADR 0044).
 *
 * <p><strong>A click is a count, not an event.</strong> Nothing in this class receives an address, a
 * user agent or a cookie; the only thing that stands for the visitor is {@code clientKey}, an opaque
 * keyed hash the controller derived from the address, which exists in the ADR 0033 limiter's memory
 * for sixty seconds and is written to no table, log, metric or event. What the database learns is
 * that a link, on a day, was opened one more time.
 *
 * <p><strong>The answer does not depend on whether the token exists.</strong> A token that does not
 * match the shape of one is ignored before the limiter is touched, so a scan with garbage cannot grow
 * its buckets; a well-formed token is limited first and looked up second, so the rate-limited answer
 * is the same for a real token and an invented one; and an unknown, archived, out-of-window or
 * other-brand token returns without a trace. The endpoint cannot be used to probe which tokens exist.
 */
@Service
public class AttributionLinkClickService {

    /** The shape {@code ck_attribution_link_token_shape} enforces, checked before any state is touched. */
    private static final Pattern TOKEN_SHAPE = Pattern.compile("^[A-Za-z0-9_-]{4,64}$");

    static final String OPERATION = "marketing.attribution.click";

    /**
     * Counted clicks per token and visitor per minute. A person opening a link once is one click; three
     * is a person reloading, and more is a script. Strict (fails closed): a click lost to an unavailable
     * limiter is a smaller number, and a chart that is too small is the safer way to be wrong than one
     * that anyone can inflate.
     */
    static final RateLimiter.Policy LIMIT = RateLimiter.Policy.strictPerMinute(3);

    private final JdbcAttributionLinkStore store;
    private final RateLimiter rateLimiter;
    private final Clock clock;

    public AttributionLinkClickService(JdbcAttributionLinkStore store, RateLimiter rateLimiter, Clock clock) {
        this.store = store;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /**
     * @param clientKey an opaque, never-stored handle for the caller, for the limiter and nothing else
     * @return whether a click was counted; callers answer the same either way
     * @throws ApiException {@code RATE_LIMIT_EXCEEDED} when this visitor has used the minute's allowance
     */
    @Transactional
    public boolean recordClick(UUID tenantId, UUID brandId, String token, String clientKey) {
        if (token == null || !TOKEN_SHAPE.matcher(token).matches()) {
            return false;
        }
        RateLimiter.Decision decision =
                rateLimiter.check(new RateLimiter.Key(OPERATION, tenantId.toString(), token + "|" + clientKey), LIMIT);
        if (!decision.allowed()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED, "Too many requests for this link. Try again shortly.");
        }
        return store.recordPublicClick(tenantId, brandId, token, clock.instant());
    }
}
