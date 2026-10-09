package uz.horecaos.platform.reviews.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.customers.api.CurrentCustomer;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerOwned;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.OrderDirectory.RecentOrder;
import uz.horecaos.platform.reviews.application.FeedbackPromptPolicyService;
import uz.horecaos.platform.reviews.application.ReviewModerationService;
import uz.horecaos.platform.reviews.application.ReviewSubmissionService;
import uz.horecaos.platform.reviews.application.ReviewSubmissionService.PublicDisplay;
import uz.horecaos.platform.reviews.application.ReviewSubmissionService.Submission;
import uz.horecaos.platform.reviews.application.ReviewSubmissionService.UnknownCursorException;
import uz.horecaos.platform.reviews.application.ReviewsSettings;
import uz.horecaos.platform.reviews.domain.CommentState;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcPublicationStore.PublicationRow;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * A customer rating their own completed order (ADR 0071, ADR 0159).
 *
 * <p>Authorised by account ownership ({@link CustomerOwned} over {@link CurrentCustomer}), the same
 * standing {@code StorefrontOrderingController} and {@code ReferralStorefrontController} already
 * establish: a customer rating their own order is not delegated staff authority, so nothing here declares
 * an ADR 0025 capability.
 *
 * <p>Since ADR 0159 a review also carries the tags the customer tapped and, if they ticked the box, their
 * agreement to show the words publicly; "my reviews" reports where each comment stands for the public
 * page; and the customer can take their words back off it at any time.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}")
@Tag(name = "Reviews", description = "A customer's own rating of their own completed order")
public class StorefrontReviewController {

    private final ReviewSubmissionService reviews;
    private final ReviewModerationService publication;
    private final ReviewsSettings settings;
    private final FeedbackPromptPolicyService promptPolicy;
    private final OrderDirectory orders;
    private final JdbcReviewStore store;
    private final CurrentCustomer currentCustomer;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public StorefrontReviewController(
            ReviewSubmissionService reviews,
            ReviewModerationService publication,
            ReviewsSettings settings,
            FeedbackPromptPolicyService promptPolicy,
            OrderDirectory orders,
            JdbcReviewStore store,
            CurrentCustomer currentCustomer,
            Clock clock) {
        this.reviews = reviews;
        this.publication = publication;
        this.settings = settings;
        this.promptPolicy = promptPolicy;
        this.orders = orders;
        this.store = store;
        this.currentCustomer = currentCustomer;
        this.clock = clock;
    }

    @PostMapping("/orders/{orderId}/review")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Rate a completed order, once",
            description = "Refused with RESOURCE_NOT_FOUND when the order is not the caller's own, "
                    + "UNPROCESSABLE_STATE when it has not reached COMPLETED yet, and "
                    + "RESOURCE_CONFLICT when it already has a review. tagIds are the tags the customer "
                    + "tapped: at most five, active, this tenant's, and matching the rating (positive for "
                    + "4-5, negative for 1-2, either for 3); anything else is VALIDATION_FAILED. "
                    + "publicConsent is whether the customer ticked the box allowing their words on the "
                    + "restaurant's public page; it counts only with a comment. Both are ignored while the "
                    + "tenant's feedback settings are off. There is no endpoint to edit or withdraw a "
                    + "review once submitted (ADR 0071); only the public display of its comment can be "
                    + "withdrawn.")
    public ResponseEntity<ReviewResponse> submit(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID orderId,
            @Valid @RequestBody SubmitReviewRequest body) {
        UUID accountId = accountId(tenantId, brandId);
        Submission submission = reviews.submit(
                tenantId,
                brandId,
                orderId,
                accountId,
                body.rating(),
                body.comment(),
                body.tagIds() == null ? List.of() : body.tagIds(),
                Boolean.TRUE.equals(body.publicConsent()));
        return ResponseEntity.ok(ReviewResponse.of(submission));
    }

    @GetMapping("/reviews")
    @CustomerOwned
    @Operation(
            summary = "The caller's own reviews at this brand, newest first",
            description = "Cursor-paginated per ADR 0031: pass the previous page's nextCursor, and a "
                    + "null nextCursor is the end. Each row carries its tags and where its comment "
                    + "stands for the public page (publicDisplay.state, and the reason code when it is "
                    + "hidden).")
    public Page<ReviewResponse> myReviews(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(required = false) Integer limit) {
        UUID accountId = accountId(tenantId, brandId);
        int pageSize = Page.limitOrDefault(limit);

        List<Submission> submissions;
        try {
            submissions = reviews.myReviews(tenantId, brandId, accountId, cursor, pageSize);
        } catch (UnknownCursorException unusable) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, unusable.getMessage());
        }

        String nextCursor = submissions.size() < pageSize
                ? null
                : submissions.get(submissions.size() - 1).id().toString();
        return new Page<>(submissions.stream().map(ReviewResponse::of).toList(), nextCursor);
    }

    @GetMapping("/reviews/pending")
    @CustomerOwned
    @Operation(
            summary = "Completed orders the caller has not yet rated, still inside the prompt window",
            description = "The storefront's own rating prompt (ADR 0159, Decision 4): the caller's "
                    + "COMPLETED orders at this brand, newest first, that carry no review and were "
                    + "placed within the brand's inlineWindowDays (seven unless the rating-prompt "
                    + "policy says otherwise). Empty while the tenant's feedback settings are off, so "
                    + "the storefront shows exactly what it showed before.")
    public List<PendingReviewResponse> pending(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        UUID accountId = accountId(tenantId, brandId);
        if (!settings.feedbackSettingsOn(tenantId)) {
            return List.of();
        }
        Instant earliest = clock.instant().minus(Duration.ofDays(promptPolicy.inlineWindowDays(tenantId, brandId)));
        List<PendingReviewResponse> pending = new ArrayList<>();
        for (RecentOrder order : orders.recentForCustomer(tenantId, brandId, accountId, 50)) {
            if ("COMPLETED".equals(order.status())
                    && !order.placedAt().isBefore(earliest)
                    && store.findByOrder(tenantId, order.orderId()).isEmpty()) {
                pending.add(new PendingReviewResponse(
                        order.orderId(), order.publicOrderNumber(), order.locationId(), order.placedAt()));
            }
        }
        return pending;
    }

    @PostMapping("/reviews/{reviewId}/public-display/withdrawal")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Take a comment off the restaurant's public page",
            description = "The customer's own withdrawal of the consent they gave (ADR 0159, Decision 6): "
                    + "records a WITHDRAWN decision, hides the comment for CUSTOMER_WITHDREW and cannot be "
                    + "reversed by a moderator. Refused with UNPROCESSABLE_STATE for a comment that was "
                    + "never shared or is already off the page. Touches nothing else: the rating, the "
                    + "comment the restaurant's staff see and any case stay as they were.")
    public ResponseEntity<PublicDisplayResponse> withdraw(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID reviewId) {
        UUID accountId = accountId(tenantId, brandId);
        PublicationRow row = publication.withdraw(tenantId, brandId, accountId, reviewId);
        return ResponseEntity.ok(PublicDisplayResponse.of(row));
    }

    private UUID accountId(UUID tenantId, UUID brandId) {
        return currentCustomer
                .account(tenantId, brandId)
                .map(CustomerAccountRef::accountId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "This principal has no customer account for this brand"));
    }

    // ------------------------------------------------------------- wire shapes

    /**
     * @param tagIds        boxed and optional: Jackson 3 refuses a missing primitive, and a client with no
     *                      tags sends none
     * @param publicConsent boxed for the same reason; absent means not ticked
     */
    public record SubmitReviewRequest(
            @Min(1) @Max(5) int rating,
            @Nullable @Size(max = 2000) String comment,
            @Nullable @Size(max = 5) List<UUID> tagIds,
            @Nullable Boolean publicConsent) {}

    /** A tag a review carries, with its wording in every locale the tenant wrote it in. */
    public record ReviewTag(
            UUID id,
            String code,
            String category,
            String sentiment,
            java.util.Map<String, String> labels,
            @Nullable UUID iconAssetId) {}

    /**
     * Where a comment stands for the public page.
     *
     * @param state      NOT_SHARED, PENDING, PUBLISHED or HIDDEN
     * @param reasonCode why it is hidden: a moderator's reason, CUSTOMER_WITHDREW or CUSTOMER_ERASED
     * @param withdrawable whether the customer can still take it off the page
     */
    public record PublicDisplayResponse(
            CommentState state, @Nullable String reasonCode, boolean withdrawable) {

        static PublicDisplayResponse of(PublicDisplay display) {
            boolean taken = display.reasonCode() != null
                    && ("CUSTOMER_WITHDREW".equals(display.reasonCode())
                            || "CUSTOMER_ERASED".equals(display.reasonCode()));
            return new PublicDisplayResponse(
                    display.state(), display.reasonCode(), display.state() != CommentState.NOT_SHARED && !taken);
        }

        static PublicDisplayResponse of(PublicationRow row) {
            return of(new PublicDisplay(row.commentState(), row.stateReasonCode(), row.version()));
        }
    }

    public record ReviewResponse(
            UUID id,
            UUID orderId,
            int rating,
            @Nullable String comment,
            Instant submittedAt,
            List<ReviewTag> tags,
            PublicDisplayResponse publicDisplay) {

        static ReviewResponse of(Submission submission) {
            return new ReviewResponse(
                    submission.id(),
                    submission.orderId(),
                    submission.rating(),
                    submission.comment(),
                    submission.submittedAt(),
                    submission.tags().stream()
                            .map(view -> new ReviewTag(
                                    view.row().id(),
                                    view.row().code(),
                                    view.row().category().name(),
                                    view.row().sentiment().name(),
                                    view.labels(),
                                    view.row().iconAssetId()))
                            .toList(),
                    PublicDisplayResponse.of(submission.publicDisplay()));
        }
    }

    /** A completed order still waiting for the customer's rating. */
    public record PendingReviewResponse(UUID orderId, String publicOrderNumber, UUID locationId, Instant placedAt) {}
}
