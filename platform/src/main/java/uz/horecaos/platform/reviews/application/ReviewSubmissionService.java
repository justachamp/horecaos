package uz.horecaos.platform.reviews.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore.Row;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The storefront side of ADR 0071: a customer rating their own completed
 * order, once, and reading their own submissions back.
 *
 * <p>Ownership and eligibility are both checked here rather than left to the
 * database alone. {@link OrderDirectory#summary} answers "whose order is
 * this, and what state is it in" without this module importing anything
 * beneath {@code ordering.api} — the same restraint every other consumer of
 * that port already observes. The one thing the database alone enforces is
 * "at most once": {@code uq_order_review_one_per_order} is what actually
 * stops two concurrent submissions from both succeeding, and the pre-check
 * below exists only to answer the common case with a clean message rather
 * than a raw constraint violation.
 */
@Service
public class ReviewSubmissionService {

    private static final String TABLE = "reviews.order_reviews";
    private static final String COMMENT_COLUMN = "comment_protected";
    private static final String REVEAL_PURPOSE = "reviews.storefront.my-reviews";

    private final JdbcReviewStore store;
    private final OrderDirectory orders;
    private final FieldProtection protection;
    private final Clock clock;

    public ReviewSubmissionService(
            JdbcReviewStore store, OrderDirectory orders, FieldProtection protection, Clock clock) {
        this.store = store;
        this.orders = orders;
        this.protection = protection;
        this.clock = clock;
    }

    /** One of the caller's own reviews, comment already decrypted. */
    public record Submission(
            UUID id, UUID orderId, int rating, @Nullable String comment, Instant submittedAt) {

        static Submission of(Row row, @Nullable String comment) {
            return new Submission(row.id(), row.orderId(), row.rating(), comment, row.submittedAt());
        }
    }

    /**
     * Submits a review for {@code orderId} as {@code customerAccountId}.
     *
     * @throws ApiException RESOURCE_NOT_FOUND when the order does not exist or
     *                       belongs to a different account — the same answer for
     *                       both, deliberately (ADR 0071, {@code OrderDirectory}'s
     *                       own convention); UNPROCESSABLE_STATE when the order has
     *                       not reached COMPLETED; RESOURCE_CONFLICT when this
     *                       order already has a review
     */
    @Transactional
    public Submission submit(
            UUID tenantId, UUID brandId, UUID orderId, UUID customerAccountId, int rating, @Nullable String comment) {
        if (rating < 1 || rating > 5) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "rating must be between 1 and 5");
        }

        OrderDirectory.OrderSummary order = orders.summary(tenantId, orderId)
                .filter(candidate -> candidate.brandId().equals(brandId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));

        if (!customerAccountId.equals(order.customerAccountId())) {
            // Deliberately the same code and message as "no such order": a
            // customer probing somebody else's order id learns nothing more than
            // a customer who mistyped one entirely.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order");
        }
        if (!"COMPLETED".equals(order.status())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "This order has not been completed yet and cannot be reviewed");
        }
        if (store.findByOrder(tenantId, orderId).isPresent()) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This order already has a review");
        }

        UUID id = UUID.randomUUID();
        String trimmedComment = comment == null || comment.isBlank() ? null : comment.trim();
        String commentProtected = trimmedComment == null
                ? null
                : protection
                        .protect(tenantId, DataClass.PERSONAL, new RecordRef(TABLE, COMMENT_COLUMN, id), trimmedComment)
                        .serialize();

        Row row;
        try {
            row = store.insert(
                    id,
                    tenantId,
                    brandId,
                    order.locationId(),
                    orderId,
                    customerAccountId,
                    rating,
                    commentProtected,
                    clock.instant());
        } catch (DataIntegrityViolationException raced) {
            // uq_order_review_one_per_order: a concurrent request for the same
            // order won the race between the check above and this insert.
            // Reported identically to the pre-check, not as a second, differently
            // worded error.
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This order already has a review");
        }

        return Submission.of(row, trimmedComment);
    }

    /**
     * The caller's own reviews at this brand, newest first.
     *
     * @param cursorReviewId the last review of the previous page, or null for the first
     * @throws UnknownCursorException when the cursor names no review of this
     *                                caller's — including one that is somebody
     *                                else's, which answers identically
     */
    @Transactional(readOnly = true)
    public List<Submission> myReviews(
            UUID tenantId, UUID brandId, UUID customerAccountId, @Nullable UUID cursorReviewId, int limit) {
        Instant before = null;
        if (cursorReviewId != null) {
            before = store.customerCursor(tenantId, brandId, customerAccountId, cursorReviewId)
                    .orElseThrow(UnknownCursorException::new);
        }
        return store.forCustomer(tenantId, brandId, customerAccountId, before, cursorReviewId, limit).stream()
                .map(row -> Submission.of(row, reveal(tenantId, row)))
                .toList();
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

    /** The cursor names nothing this caller may continue from. */
    public static class UnknownCursorException extends RuntimeException {
        public UnknownCursorException() {
            super("This cursor does not name a review of yours");
        }
    }
}
