package uz.horecaos.platform.reviews.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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
import uz.horecaos.platform.reviews.application.ReviewSubmissionService;
import uz.horecaos.platform.reviews.application.ReviewSubmissionService.Submission;
import uz.horecaos.platform.reviews.application.ReviewSubmissionService.UnknownCursorException;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * A customer rating their own completed order (ADR 0071).
 *
 * <p>Authorised by account ownership ({@link CustomerOwned} over
 * {@link CurrentCustomer}), the same standing {@code StorefrontOrderingController}
 * and {@code ReferralStorefrontController} already establish: a customer
 * rating their own order is not delegated staff authority, so nothing here
 * declares an ADR 0025 capability.
 */
@RestController
@RequestMapping("/api/v1/storefront/tenants/{tenantId}/brands/{brandId}")
@Tag(name = "Reviews", description = "A customer's own rating of their own completed order")
public class StorefrontReviewController {

    private final ReviewSubmissionService reviews;
    private final CurrentCustomer currentCustomer;

    public StorefrontReviewController(ReviewSubmissionService reviews, CurrentCustomer currentCustomer) {
        this.reviews = reviews;
        this.currentCustomer = currentCustomer;
    }

    @PostMapping("/orders/{orderId}/review")
    @CustomerOwned
    @Idempotent
    @Operation(
            summary = "Rate a completed order, once",
            description = "Refused with RESOURCE_NOT_FOUND when the order is not the caller's own, "
                    + "UNPROCESSABLE_STATE when it has not reached COMPLETED yet, and "
                    + "RESOURCE_CONFLICT when it already has a review. There is no endpoint to edit "
                    + "or withdraw a review once submitted (ADR 0071's own open input).")
    public ResponseEntity<ReviewResponse> submit(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID orderId,
            @Valid @RequestBody SubmitReviewRequest body) {
        UUID accountId = accountId(tenantId, brandId);
        Submission submission = reviews.submit(tenantId, brandId, orderId, accountId, body.rating(), body.comment());
        return ResponseEntity.ok(ReviewResponse.of(submission));
    }

    @GetMapping("/reviews")
    @CustomerOwned
    @Operation(
            summary = "The caller's own reviews at this brand, newest first",
            description = "Cursor-paginated per ADR 0031: pass the previous page's nextCursor, and a "
                    + "null nextCursor is the end.")
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

    private UUID accountId(UUID tenantId, UUID brandId) {
        return currentCustomer
                .account(tenantId, brandId)
                .map(CustomerAccountRef::accountId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "This principal has no customer account for this brand"));
    }

    // ------------------------------------------------------------- wire shapes

    public record SubmitReviewRequest(
            @Min(1) @Max(5) int rating,
            @Nullable @Size(max = 2000) String comment) {}

    public record ReviewResponse(
            UUID id, UUID orderId, int rating, @Nullable String comment, Instant submittedAt) {

        static ReviewResponse of(Submission submission) {
            return new ReviewResponse(
                    submission.id(),
                    submission.orderId(),
                    submission.rating(),
                    submission.comment(),
                    submission.submittedAt());
        }
    }
}
