package uz.horecaos.platform.reviews.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.reviews.application.ReviewQueryService;
import uz.horecaos.platform.reviews.application.ReviewQueryService.ReviewView;
import uz.horecaos.platform.reviews.application.ReviewQueryService.UnknownCursorException;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore.Summary;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * §5.4 Reviews: a brand's own order reviews, filtered, read against the order
 * and the customer each one is attached to (ADR 0071).
 *
 * <p>Deliberately read-only — there is no write endpoint here because there
 * is nothing an operator writes: a review is customer-authored and immutable.
 * No moderation action exists either; the ADR's own "no moderation, no
 * kanban" decision means acting on a bad review goes through the order's own
 * remedy tools ({@code OperationsRemedyController}, ADR 0048), not a
 * review-specific one.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/reviews")
@Tag(name = "Reviews", description = "A brand's own order reviews, against their orders and customers")
public class OperationsReviewController {

    private final ReviewQueryService reviews;

    public OperationsReviewController(ReviewQueryService reviews) {
        this.reviews = reviews;
    }

    @GetMapping
    @RequiresCapability(value = Capability.REVIEW_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "This brand's reviews, newest first, filtered",
            description = "Every filter is optional. Cursor-paginated per ADR 0031: pass the "
                    + "previous page's nextCursor, and a null nextCursor is the end.")
    @SuppressWarnings("checkstyle:ParameterNumber")
    public Page<ReviewResponse> list(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) Integer minRating,
            @RequestParam(required = false) Integer maxRating,
            @RequestParam(required = false) Instant submittedFrom,
            @RequestParam(required = false) Instant submittedTo,
            @RequestParam(required = false) @Schema(description = "The nextCursor of the previous page") UUID cursor,
            @RequestParam(required = false) Integer limit) {

        int pageSize = Page.limitOrDefault(limit);

        List<ReviewView> rows;
        try {
            rows = reviews.list(
                    tenantId, brandId, locationId, minRating, maxRating, submittedFrom, submittedTo, cursor, pageSize);
        } catch (UnknownCursorException unusable) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, unusable.getMessage());
        }

        String nextCursor =
                rows.size() < pageSize ? null : rows.get(rows.size() - 1).id().toString();
        return new Page<>(rows.stream().map(ReviewResponse::of).toList(), nextCursor);
    }

    @GetMapping("/summary")
    @RequiresCapability(value = Capability.REVIEW_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Count and average rating over the same filters the list accepts",
            description = "Computed directly from this brand's own rows, uncached and unregistered "
                    + "— ADR 0071 deliberately does not register this as an ADR 0043 metric while "
                    + "this is its only caller.")
    public ResponseEntity<SummaryResponse> summary(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) Instant submittedFrom,
            @RequestParam(required = false) Instant submittedTo) {
        Summary summary = reviews.summary(tenantId, brandId, locationId, submittedFrom, submittedTo);
        return ResponseEntity.ok(new SummaryResponse(summary.reviewCount(), summary.averageRating()));
    }

    // ------------------------------------------------------------- wire shapes

    public record ReviewResponse(
            UUID id,
            UUID orderId,
            UUID locationId,
            UUID customerAccountId,
            int rating,
            @Nullable String comment,
            Instant submittedAt) {

        static ReviewResponse of(ReviewView view) {
            return new ReviewResponse(
                    view.id(),
                    view.orderId(),
                    view.locationId(),
                    view.customerAccountId(),
                    view.rating(),
                    view.comment(),
                    view.submittedAt());
        }
    }

    public record SummaryResponse(long reviewCount, double averageRating) {}
}
