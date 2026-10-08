package uz.horecaos.platform.reviews.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.reviews.application.FeedbackTagService;
import uz.horecaos.platform.reviews.application.FeedbackTagService.TagView;
import uz.horecaos.platform.reviews.application.PublicReviewService;
import uz.horecaos.platform.reviews.application.PublicReviewService.PublicList;
import uz.horecaos.platform.reviews.application.PublicReviewService.PublicSummary;
import uz.horecaos.platform.reviews.application.ReviewsSettings;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;

/**
 * What a restaurant's page and its review form may ask without signing in (ADR 0159, Decisions 3 and 5):
 * the tags a customer is offered, the brand's rating summary, and its published comments.
 *
 * <p>All three are anonymous, off until the tenant switches them on, cached, and rate limited per caller
 * (ADR 0033), because an anonymous endpoint that decrypts personal text is exactly where a loop would
 * hurt. None carries a name, a customer id, an order number, a review id or a time of day.
 *
 * <p>What each reveals is decided on every request from the brand's current settings, never from a cache
 * entry: switching the page off stops it serving at once, and the list cache is evicted by every hide,
 * withdrawal and erasure besides (see {@code PublicReviewCache}).
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}")
@Tag(name = "Public reviews", description = "A restaurant's review summary, published comments and feedback tags")
public class StorefrontPublicReviewController {

    /** ADR 0033: per caller and brand, reads fail open. */
    private static final RateLimiter.Policy READ_PER_CALLER = RateLimiter.Policy.perMinute(120);

    private static final String OPERATION = "reviews.public.read";

    /** The storefront's own translation key for the consent sentence beside the box. */
    public static final String CONSENT_TEXT_KEY = "reviews.publicDisplay.consent";

    private final PublicReviewService publicReviews;
    private final FeedbackTagService tags;
    private final ReviewsSettings settings;
    private final RateLimiter rateLimiter;

    public StorefrontPublicReviewController(
            PublicReviewService publicReviews,
            FeedbackTagService tags,
            ReviewsSettings settings,
            RateLimiter rateLimiter) {
        this.publicReviews = publicReviews;
        this.tags = tags;
        this.settings = settings;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/feedback/config")
    @Operation(
            summary = "The feedback tags a customer is offered, and whether to show the public-display box",
            description = "Anonymous and cached for a minute. enabled is false, with no tags, while the "
                    + "tenant's feedback settings are off. Otherwise the active tags, split by sentiment "
                    + "(at most eight each, in the tenant's order), each with its wording in every locale "
                    + "and its icon. The storefront shows the positive tags for 4-5 stars, the negative for "
                    + "1-2 and both for 3, and at most maxTagsPerReview may be sent. publicComments is "
                    + "true only when the brand shows comments publicly, which is when the consent box "
                    + "means anything; consentTextKey names the storefront's own translated sentence.")
    public ResponseEntity<FeedbackConfigResponse> config(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, HttpServletRequest request) {
        limit(request, tenantId, brandId);
        if (!settings.feedbackSettingsOn(tenantId)) {
            return cached(FeedbackConfigResponse.disabled());
        }
        FeedbackTagService.Offered offered = tags.offered(tenantId);
        boolean publicComments = settings.publicVisibility(tenantId, brandId).showsComments();
        return cached(new FeedbackConfigResponse(
                true,
                FeedbackTagService.MAXIMUM_TAGS_PER_REVIEW,
                publicComments,
                publicComments ? CONSENT_TEXT_KEY : null,
                offered.positive().stream()
                        .map(tag -> OfferedTag.of(tenantId, tag))
                        .toList(),
                offered.negative().stream()
                        .map(tag -> OfferedTag.of(tenantId, tag))
                        .toList()));
    }

    @GetMapping("/reviews/public/summary")
    @Operation(
            summary = "The brand's rating summary, or nothing but a flag",
            description = "Anonymous, cached for five minutes, rate limited. While the brand's public "
                    + "visibility is NONE, or it has fewer than its minimum count of reviews, ready is "
                    + "false and every figure is null. Otherwise the count, the average to one decimal "
                    + "and the five-bar distribution (index 0 is one star). The average counts every "
                    + "review of the brand, whatever became of its comment: a rating is not words.")
    public ResponseEntity<PublicSummary> summary(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, HttpServletRequest request) {
        limit(request, tenantId, brandId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(publicReviews.summary(tenantId, brandId));
    }

    @GetMapping("/reviews/public")
    @Operation(
            summary = "The brand's published comments, newest first",
            description = "Anonymous, cached for a minute and evicted by every hide, withdrawal and "
                    + "erasure, rate limited. Empty unless the brand's public visibility is "
                    + "RATING_AND_COMMENTS. Each entry is a comment its author agreed to show and a "
                    + "moderator (or the brand's own setting) approved, with its rating, tags, the day it "
                    + "was written and the branch name: no name, no customer, no order number, no review "
                    + "id, no time of day. The cursor is opaque. A tenant cannot filter this list by "
                    + "rating, pin, reorder or feature a comment, or reply to one.")
    public ResponseEntity<PublicList> comments(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit,
            HttpServletRequest request) {
        limit(request, tenantId, brandId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(publicReviews.list(tenantId, brandId, cursor, limit));
    }

    // ------------------------------------------------------------------ helpers

    private <T> ResponseEntity<T> cached(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(body);
    }

    private void limit(HttpServletRequest request, UUID tenantId, UUID brandId) {
        RateLimiter.Decision decision = rateLimiter.check(
                new RateLimiter.Key(OPERATION, tenantId.toString(), brandId + ":" + callerKey(request)),
                READ_PER_CALLER);
        if (!decision.allowed()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many review reads. Try again shortly.",
                    Map.of(
                            "retryAfterSeconds",
                            Math.max(1, decision.retryAfter().toSeconds())));
        }
    }

    /** An opaque, hashed, never-stored handle for the limiter's bucket; nothing about the address is kept. */
    private static String callerKey(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        if (address == null || address.isBlank()) {
            return "unattributed";
        }
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    // ------------------------------------------------------------- wire shapes

    /**
     * @param consentTextKey the storefront's translation key, or null when the box is not shown
     */
    public record FeedbackConfigResponse(
            boolean enabled,
            int maxTagsPerReview,
            boolean publicComments,
            @Nullable String consentTextKey,
            List<OfferedTag> positive,
            List<OfferedTag> negative) {

        static FeedbackConfigResponse disabled() {
            return new FeedbackConfigResponse(
                    false, FeedbackTagService.MAXIMUM_TAGS_PER_REVIEW, false, null, List.of(), List.of());
        }
    }

    /** One tag a customer may tap. */
    public record OfferedTag(
            UUID id,
            String code,
            String category,
            Map<String, String> labels,
            @Nullable String iconUrl) {

        static OfferedTag of(UUID tenantId, TagView view) {
            return new OfferedTag(
                    view.row().id(),
                    view.row().code(),
                    view.row().category().name(),
                    view.labels(),
                    view.row().iconAssetId() == null
                            ? null
                            : "/api/v1/storefront/tenants/%s/media/%s"
                                    .formatted(tenantId, view.row().iconAssetId()));
        }
    }
}
