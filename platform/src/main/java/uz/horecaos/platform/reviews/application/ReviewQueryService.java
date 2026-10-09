package uz.horecaos.platform.reviews.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.reviews.application.FeedbackTagService.TagView;
import uz.horecaos.platform.reviews.domain.CommentState;
import uz.horecaos.platform.reviews.domain.RevealPurpose;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcPublicationStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcPublicationStore.PublicationRow;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore.CaseLink;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore.Row;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore.Summary;

/**
 * The operations side of ADR 0071 — §5.4 Reviews: a brand's own reviews,
 * filtered, read against the order and the customer each one is attached to.
 *
 * <p>Read-only, and not where a case is worked or a comment moderated -- those are
 * {@code RecoveryCaseService} and {@code ReviewModerationService} (ADR 0159). A review is
 * customer-authored and immutable once submitted; what this list adds to it is only what stands beside
 * it: the tags it carries, where its comment stands for the public page, and the case, if any, that
 * answers it.
 */
@Service
public class ReviewQueryService {

    private final JdbcReviewStore store;
    private final ReviewCommentVault vault;
    private final JdbcPublicationStore publications;
    private final JdbcRecoveryCaseStore cases;
    private final FeedbackTagService tags;

    public ReviewQueryService(
            JdbcReviewStore store,
            ReviewCommentVault vault,
            JdbcPublicationStore publications,
            JdbcRecoveryCaseStore cases,
            FeedbackTagService tags) {
        this.store = store;
        this.vault = vault;
        this.publications = publications;
        this.cases = cases;
        this.tags = tags;
    }

    /**
     * One review as the operations screen renders it — the comment already decrypted.
     *
     * @param tags          the tags it carries
     * @param publicDisplay where its comment stands for the public page, and why if it is hidden
     * @param caseLink      the service-recovery case that answers it, or null
     */
    public record ReviewView(
            UUID id,
            UUID orderId,
            UUID locationId,
            UUID customerAccountId,
            int rating,
            @Nullable String comment,
            Instant submittedAt,
            List<TagView> tags,
            CommentState publicState,
            @Nullable String publicReasonCode,
            @Nullable CaseLink caseLink) {}

    /**
     * A brand's reviews, newest first, filtered by whatever the operator
     * supplied — every parameter is optional.
     *
     * @param customerAccountId row 5.2h: narrows to one customer's own reviews
     *                          at this brand — the customer detail pane's own
     *                          tab, so an operator handling a complaint can
     *                          see this person's rating history without
     *                          scrolling the whole brand-wide list and
     *                          matching account ids by eye
     * @param cursorReviewId the last review of the previous page, or null for the first
     * @throws UnknownCursorException when the cursor names no review at this brand
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Transactional(readOnly = true)
    public List<ReviewView> list(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID customerAccountId,
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
        List<Row> rows = store.listForBrand(
                tenantId,
                brandId,
                customerAccountId,
                locationId,
                minRating,
                maxRating,
                submittedFrom,
                submittedTo,
                before,
                cursorReviewId,
                limit);
        List<UUID> reviewIds = rows.stream().map(Row::id).toList();
        Map<UUID, List<UUID>> tagIds = store.tagIdsFor(tenantId, reviewIds);
        Map<UUID, TagView> views = tags.viewsOf(
                tenantId, tagIds.values().stream().flatMap(Collection::stream).toList());
        Map<UUID, PublicationRow> publicationRows = publications.forReviews(tenantId, reviewIds);
        Map<UUID, CaseLink> links = cases.linksForReviews(tenantId, reviewIds);

        return rows.stream()
                .map(row -> {
                    PublicationRow publication = publicationRows.get(row.id());
                    return new ReviewView(
                            row.id(),
                            row.orderId(),
                            row.locationId(),
                            row.customerAccountId(),
                            row.rating(),
                            reveal(tenantId, row),
                            row.submittedAt(),
                            tagIds.getOrDefault(row.id(), List.of()).stream()
                                    .map(views::get)
                                    .filter(java.util.Objects::nonNull)
                                    .toList(),
                            publication == null ? CommentState.NOT_SHARED : publication.commentState(),
                            publication == null ? null : publication.stateReasonCode(),
                            links.get(row.id()));
                })
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
        return vault.revealComment(tenantId, row.id(), row.commentProtected(), RevealPurpose.OPERATIONS_REVIEWS_SCREEN);
    }

    /** The cursor names no review at this brand. */
    public static class UnknownCursorException extends RuntimeException {
        public UnknownCursorException() {
            super("This cursor does not name a review at this brand");
        }
    }
}
