package uz.horecaos.platform.reviews.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore.Row;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore.Summary;

/**
 * The operations side of ADR 0071 — §5.4 Reviews: a brand's own reviews,
 * filtered, read against the order and the customer each one is attached to.
 *
 * <p>Deliberately read-only, and deliberately not a case-management surface:
 * see the ADR's own "no moderation, no kanban" decision. There is no write
 * method here because there is nothing for an operator to write — a review is
 * customer-authored and immutable once submitted.
 */
@Service
public class ReviewQueryService {

    private static final String TABLE = "reviews.order_reviews";
    private static final String COMMENT_COLUMN = "comment_protected";
    private static final String REVEAL_PURPOSE = "reviews.operations.reviews-screen";

    private final JdbcReviewStore store;
    private final FieldProtection protection;

    public ReviewQueryService(JdbcReviewStore store, FieldProtection protection) {
        this.store = store;
        this.protection = protection;
    }

    /** One review as the operations screen renders it — the comment already decrypted. */
    public record ReviewView(
            UUID id,
            UUID orderId,
            UUID locationId,
            UUID customerAccountId,
            int rating,
            @Nullable String comment,
            Instant submittedAt) {

        static ReviewView of(Row row, @Nullable String comment) {
            return new ReviewView(
                    row.id(),
                    row.orderId(),
                    row.locationId(),
                    row.customerAccountId(),
                    row.rating(),
                    comment,
                    row.submittedAt());
        }
    }

    /**
     * A brand's reviews, newest first, filtered by whatever the operator
     * supplied — every parameter is optional.
     *
     * @param cursorReviewId the last review of the previous page, or null for the first
     * @throws UnknownCursorException when the cursor names no review at this brand
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Transactional(readOnly = true)
    public List<ReviewView> list(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            @Nullable Integer minRating,
            @Nullable Integer maxRating,
            @Nullable Instant submittedFrom,
            @Nullable Instant submittedTo,
            @Nullable UUID cursorReviewId,
            int limit) {
        Instant before = null;
        if (cursorReviewId != null) {
            before = store.brandCursor(tenantId, brandId, cursorReviewId).orElseThrow(UnknownCursorException::new);
        }
        return store
                .listForBrand(
                        tenantId,
                        brandId,
                        locationId,
                        minRating,
                        maxRating,
                        submittedFrom,
                        submittedTo,
                        before,
                        cursorReviewId,
                        limit)
                .stream()
                .map(row -> ReviewView.of(row, reveal(tenantId, row)))
                .toList();
    }

    /** The brand's own count and average rating over the same filters {@link #list} accepts, minus paging. */
    @Transactional(readOnly = true)
    public Summary summary(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            @Nullable Instant submittedFrom,
            @Nullable Instant submittedTo) {
        return store.summaryForBrand(tenantId, brandId, locationId, submittedFrom, submittedTo);
    }

    private @Nullable String reveal(UUID tenantId, Row row) {
        if (row.commentProtected() == null) {
            return null;
        }
        return protection.reveal(
                tenantId,
                ProtectedValue.deserialize(row.commentProtected()),
                new RecordRef(TABLE, COMMENT_COLUMN, row.id()),
                REVEAL_PURPOSE);
    }

    /** The cursor names no review at this brand. */
    public static class UnknownCursorException extends RuntimeException {
        public UnknownCursorException() {
            super("This cursor does not name a review at this brand");
        }
    }
}
