package uz.horecaos.platform.reviews.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.customers.api.ConsentRecorder;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.reviews.api.ReviewSubmitted;
import uz.horecaos.platform.reviews.application.FeedbackTagService.TagView;
import uz.horecaos.platform.reviews.domain.CommentState;
import uz.horecaos.platform.reviews.domain.RevealPurpose;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcFeedbackTagStore.TagRow;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcPublicationStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcPublicationStore.PublicationRow;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore.Row;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The storefront side of ADR 0071 and ADR 0159: a customer rating their own completed order, once, with
 * the tags that say which part went wrong and, if they choose, a yes to showing their words publicly;
 * and reading their own submissions back.
 *
 * <p>Ownership and eligibility are both checked here rather than left to the database alone. {@link
 * OrderDirectory#summary} answers "whose order is this, and what state is it in" without this module
 * importing anything beneath {@code ordering.api}. The one thing the database alone enforces is "at most
 * once": {@code uq_order_review_one_per_order} is what stops two concurrent submissions from both
 * succeeding, and the pre-check below exists only to answer the common case with a clean message.
 *
 * <p>In the review's own transaction, in this order: the review; its tags; the customer's consent
 * decision and the publication row (the overlay -- {@code order_reviews} itself is never touched again);
 * a service-recovery case when the policy says the rating warrants one; and the {@code ReviewSubmitted}
 * fact. If any of it fails none of it is written, so a one-star review that cannot open its case is
 * refused rather than saved unanswered.
 *
 * <p>{@code feature.feedback_settings} gates tags and the consent box. With it off, tags and a consent
 * tick that a client still sends are ignored -- not refused, so a rollback mid-session does not turn a
 * customer's submit into an error -- and the review behaves exactly as ADR 0071 built it.
 */
@Service
public class ReviewSubmissionService {

    /** The ADR 0015 purpose a customer's yes to public display is recorded under. */
    public static final String PUBLIC_DISPLAY_PURPOSE = "review.public_display";

    /** The wording version of {@link #PUBLIC_DISPLAY_PURPOSE} (ADR 0159, open input 2). */
    public static final String PUBLIC_DISPLAY_POLICY_VERSION = "1";

    private final JdbcReviewStore store;
    private final JdbcPublicationStore publications;
    private final OrderDirectory orders;
    private final ReviewCommentVault vault;
    private final FeedbackTagService tags;
    private final ReviewsSettings settings;
    private final ConsentRecorder consent;
    private final RecoveryCaseService recovery;
    private final PublicReviewCache publicCache;
    private final ApplicationEventPublisher events;
    private final ReviewsMetrics metrics;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReviewSubmissionService(
            JdbcReviewStore store,
            JdbcPublicationStore publications,
            OrderDirectory orders,
            ReviewCommentVault vault,
            FeedbackTagService tags,
            ReviewsSettings settings,
            ConsentRecorder consent,
            RecoveryCaseService recovery,
            PublicReviewCache publicCache,
            ApplicationEventPublisher events,
            ReviewsMetrics metrics,
            Clock clock) {
        this.store = store;
        this.publications = publications;
        this.orders = orders;
        this.vault = vault;
        this.tags = tags;
        this.settings = settings;
        this.consent = consent;
        this.recovery = recovery;
        this.publicCache = publicCache;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * One of the caller's own reviews, comment already decrypted.
     *
     * @param tags          the tags it carries, with their wording in every locale
     * @param publicDisplay where the comment stands for the public page
     */
    public record Submission(
            UUID id,
            UUID orderId,
            int rating,
            @Nullable String comment,
            Instant submittedAt,
            List<TagView> tags,
            PublicDisplay publicDisplay) {}

    /**
     * The state of a review's comment for the public page and the code that explains it, shown to the
     * customer who wrote it.
     *
     * @param reasonCode why it is hidden: a moderator's reason, {@code CUSTOMER_WITHDREW} or {@code CUSTOMER_ERASED}
     * @param version    the publication row's version, for a withdrawal that is checked against it
     */
    public record PublicDisplay(
            CommentState state, @Nullable String reasonCode, int version) {

        /** What a review placed before ADR 0159 reports: nothing was ever shared. */
        public static PublicDisplay notShared() {
            return new PublicDisplay(CommentState.NOT_SHARED, null, 1);
        }

        static PublicDisplay of(@Nullable PublicationRow row) {
            return row == null
                    ? notShared()
                    : new PublicDisplay(row.commentState(), row.stateReasonCode(), row.version());
        }
    }

    /**
     * Submits a review with no tags and no consent: the shape a Telegram star tap and every pre-ADR 0159
     * caller has.
     */
    @Transactional
    public Submission submit(
            UUID tenantId, UUID brandId, UUID orderId, UUID customerAccountId, int rating, @Nullable String comment) {
        return submit(tenantId, brandId, orderId, customerAccountId, rating, comment, List.of(), false);
    }

    /**
     * Submits a review for {@code orderId} as {@code customerAccountId}.
     *
     * @param tagIds        the tags the customer tapped, at most five, matching the rating's sentiment
     * @param publicConsent whether the customer ticked the box allowing their words on the brand's page
     * @throws ApiException RESOURCE_NOT_FOUND when the order does not exist or belongs to a different
     *                       account -- the same answer for both, deliberately (ADR 0071, {@code
     *                       OrderDirectory}'s own convention); UNPROCESSABLE_STATE when the order has not
     *                       reached COMPLETED; RESOURCE_CONFLICT when this order already has a review;
     *                       VALIDATION_FAILED for a rating out of range or a tag that does not fit
     */
    @Transactional
    @SuppressWarnings("checkstyle:ParameterNumber")
    public Submission submit(
            UUID tenantId,
            UUID brandId,
            UUID orderId,
            UUID customerAccountId,
            int rating,
            @Nullable String comment,
            Collection<UUID> tagIds,
            boolean publicConsent) {
        if (rating < 1 || rating > 5) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "rating must be between 1 and 5");
        }

        OrderDirectory.OrderSummary order = orders.summary(tenantId, orderId)
                .filter(candidate -> candidate.brandId().equals(brandId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));

        if (!customerAccountId.equals(order.customerAccountId())) {
            // Deliberately the same code and message as "no such order": a customer probing somebody
            // else's order id learns nothing more than a customer who mistyped one entirely.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order");
        }
        if (!"COMPLETED".equals(order.status())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "This order has not been completed yet and cannot be reviewed");
        }
        if (store.findByOrder(tenantId, orderId).isPresent()) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This order already has a review");
        }

        boolean feedbackOn = settings.feedbackSettingsOn(tenantId);
        List<TagRow> accepted = feedbackOn ? tags.validateForReview(tenantId, rating, tagIds) : List.of();

        UUID id = Ids.newId();
        String trimmedComment = comment == null || comment.isBlank() ? null : comment.trim();
        String commentProtected = trimmedComment == null ? null : vault.protectComment(tenantId, id, trimmedComment);
        Instant now = clock.instant();

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
                    now);
        } catch (DataIntegrityViolationException raced) {
            // uq_order_review_one_per_order: a concurrent request for the same order won the race between
            // the check above and this insert. Reported identically to the pre-check.
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This order already has a review");
        }
        store.insertTags(tenantId, id, accepted.stream().map(TagRow::id).toList());

        PublicationRow publication = publish(row, feedbackOn, publicConsent, trimmedComment != null, now);

        recovery.openAutomatically(row);

        events.publishEvent(new ReviewSubmitted(
                Ids.newId(),
                tenantId,
                id,
                orderId,
                brandId,
                order.locationId(),
                rating,
                trimmedComment != null,
                accepted.size(),
                now));
        metrics.reviewSubmitted(rating);

        Map<UUID, TagView> views =
                tags.viewsOf(tenantId, accepted.stream().map(TagRow::id).toList());
        return new Submission(
                id,
                orderId,
                rating,
                trimmedComment,
                now,
                accepted.stream().map(tag -> views.get(tag.id())).toList(),
                PublicDisplay.of(publication));
    }

    /**
     * Writes the publication row: the customer's tick as a snapshot, the consent decision it was recorded
     * as, and the state the comment starts in. The tick counts only when there are words to share and the
     * feature is on; otherwise the row is {@code NOT_SHARED} and records nothing was consented to.
     */
    private PublicationRow publish(Row review, boolean feedbackOn, boolean ticked, boolean hasComment, Instant now) {
        boolean shares = feedbackOn && ticked && hasComment;
        UUID decisionId = null;
        CommentState state = CommentState.NOT_SHARED;
        if (shares) {
            decisionId = consent.recordGrant(
                    review.tenantId(),
                    review.customerAccountId(),
                    review.brandId(),
                    PUBLIC_DISPLAY_PURPOSE,
                    null,
                    PUBLIC_DISPLAY_POLICY_VERSION,
                    "STOREFRONT",
                    review.id().toString());
            state = settings.commentsRequireApproval(review.tenantId(), review.brandId())
                    ? CommentState.PENDING
                    : CommentState.PUBLISHED;
        }
        PublicationRow row = new PublicationRow(
                review.tenantId(),
                review.id(),
                review.brandId(),
                review.locationId(),
                shares,
                decisionId,
                null,
                state,
                null,
                null,
                null,
                null,
                1,
                now,
                now);
        publications.insert(row);
        if (state == CommentState.PUBLISHED) {
            // A comment published without waiting for a moderator is public from the next read.
            publicCache.evictAfterCommit(review.tenantId(), review.brandId());
        }
        return row;
    }

    /**
     * The caller's own reviews at this brand, newest first.
     *
     * @param cursorReviewId the last review of the previous page, or null for the first
     * @throws UnknownCursorException when the cursor names no review of this caller's -- including one
     *                                that is somebody else's, which answers identically
     */
    @Transactional(readOnly = true)
    public List<Submission> myReviews(
            UUID tenantId, UUID brandId, UUID customerAccountId, @Nullable UUID cursorReviewId, int limit) {
        Instant before = null;
        if (cursorReviewId != null) {
            before = store.customerCursor(tenantId, brandId, customerAccountId, cursorReviewId)
                    .orElseThrow(UnknownCursorException::new);
        }
        List<Row> rows = store.forCustomer(tenantId, brandId, customerAccountId, before, cursorReviewId, limit);
        List<UUID> reviewIds = rows.stream().map(Row::id).toList();
        Map<UUID, List<UUID>> tagIds = store.tagIdsFor(tenantId, reviewIds);
        Map<UUID, PublicationRow> publicationRows = publications.forReviews(tenantId, reviewIds);
        Map<UUID, TagView> views = tags.viewsOf(
                tenantId, tagIds.values().stream().flatMap(Collection::stream).toList());

        List<Submission> submissions = new ArrayList<>();
        for (Row row : rows) {
            List<TagView> reviewTags = new LinkedHashSet<>(tagIds.getOrDefault(row.id(), List.of()))
                    .stream().map(views::get).filter(java.util.Objects::nonNull).toList();
            submissions.add(new Submission(
                    row.id(),
                    row.orderId(),
                    row.rating(),
                    vault.revealComment(
                            tenantId, row.id(), row.commentProtected(), RevealPurpose.STOREFRONT_MY_REVIEWS),
                    row.submittedAt(),
                    reviewTags,
                    PublicDisplay.of(publicationRows.get(row.id()))));
        }
        return submissions;
    }

    /** The cursor names nothing this caller may continue from. */
    public static class UnknownCursorException extends RuntimeException {
        public UnknownCursorException() {
            super("This cursor does not name a review of yours");
        }
    }
}
