package uz.horecaos.platform.reviews.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.customers.api.ConsentRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.reviews.api.ReviewCommentModerated;
import uz.horecaos.platform.reviews.application.FeedbackTagService.TagView;
import uz.horecaos.platform.reviews.domain.CommentState;
import uz.horecaos.platform.reviews.domain.ModerationReason;
import uz.horecaos.platform.reviews.domain.RevealPurpose;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcPublicationStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcPublicationStore.PublicationRow;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcPublicationStore.QueueRow;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore.Row;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Moderation hides words and never numbers (ADR 0159, Decisions 6 and 7).
 *
 * <p>A decision changes only the publication row -- never {@code order_reviews}, never the rating, never
 * the average -- and writes an ADR 0027 fact whose {@code reason} is the code's label and whose change
 * document carries states and the code, never the comment. The form offers five reasons and no free text:
 * "negative", "unfair" and "I disagree" are not reasons.
 *
 * <p>The customer can <strong>withdraw</strong> public display at any time. That records a {@code
 * WITHDRAWN} consent decision for the same purpose through {@link ConsentRecorder}, stores its id, hides
 * the comment for {@code CUSTOMER_WITHDREW}, and cannot be reversed by a moderator. {@code public_consent}
 * is never flipped: it is the snapshot of what was ticked at submission. Withdrawal does not touch the
 * rating, the comment the tenant's staff see, or a case: ADR 0071's "no withdrawal of a review" stands
 * for everything except who the public may read it.
 *
 * <p>Every path that stops a comment being public evicts the brand's public list in the same request,
 * after the commit, so the next read cannot return it.
 */
@Service
public class ReviewModerationService {

    public static final String AUDIT_ACTION = "reviews.moderation.decide";

    private final JdbcReviewStore reviews;
    private final JdbcPublicationStore publications;
    private final ReviewCommentVault vault;
    private final FeedbackTagService tags;
    private final ConsentRecorder consent;
    private final PublicReviewCache publicCache;
    private final AuditRecorder audit;
    private final ApplicationEventPublisher events;
    private final ReviewsMetrics metrics;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReviewModerationService(
            JdbcReviewStore reviews,
            JdbcPublicationStore publications,
            ReviewCommentVault vault,
            FeedbackTagService tags,
            ConsentRecorder consent,
            PublicReviewCache publicCache,
            AuditRecorder audit,
            ApplicationEventPublisher events,
            ReviewsMetrics metrics,
            Clock clock) {
        this.reviews = reviews;
        this.publications = publications;
        this.vault = vault;
        this.tags = tags;
        this.consent = consent;
        this.publicCache = publicCache;
        this.audit = audit;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** A comment waiting for, or already given, a decision. */
    public record QueueItem(
            UUID reviewId,
            UUID locationId,
            int rating,
            @Nullable String comment,
            Instant submittedAt,
            CommentState state,
            @Nullable String reasonCode,
            int version,
            Instant updatedAt,
            List<TagView> tags) {}

    /** One page of the queue and the cursor to continue it. */
    public record Queue(List<QueueItem> items, @Nullable String nextCursor, Map<CommentState, Long> counts) {}

    /**
     * One brand's comments in a state, longest-waiting first.
     *
     * @throws ApiException {@code INVALID_REQUEST} for a cursor that names nothing in this brand
     */
    @Transactional(readOnly = true)
    public Queue queue(UUID tenantId, UUID brandId, CommentState state, @Nullable UUID cursor, int limit) {
        Instant after = null;
        if (cursor != null) {
            after = publications
                    .queueCursor(tenantId, brandId, cursor)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.INVALID_REQUEST, "This cursor does not name a review of this brand"));
        }
        List<QueueRow> rows = publications.queue(tenantId, brandId, state, after, cursor, limit);
        List<UUID> reviewIds = rows.stream().map(QueueRow::reviewId).toList();
        Map<UUID, List<UUID>> tagIds = reviews.tagIdsFor(tenantId, reviewIds);
        Map<UUID, TagView> views = tags.viewsOf(
                tenantId, tagIds.values().stream().flatMap(Collection::stream).toList());
        List<QueueItem> items = new ArrayList<>();
        for (QueueRow row : rows) {
            items.add(new QueueItem(
                    row.reviewId(),
                    row.locationId(),
                    row.rating(),
                    vault.revealComment(
                            tenantId, row.reviewId(), row.commentProtected(), RevealPurpose.OPERATIONS_REVIEWS_SCREEN),
                    row.submittedAt(),
                    row.commentState(),
                    row.stateReasonCode(),
                    row.version(),
                    row.updatedAt(),
                    tagIds.getOrDefault(row.reviewId(), List.of()).stream()
                            .map(views::get)
                            .filter(java.util.Objects::nonNull)
                            .toList()));
        }
        String next = rows.size() < limit
                ? null
                : rows.get(rows.size() - 1).reviewId().toString();
        return new Queue(items, next, publications.countsByState(tenantId, brandId));
    }

    /**
     * A moderator's decision.
     *
     * @param reason required to hide; null for an approval or a restore, which is not a judgement of the
     *               words and so gives no reason
     * @throws ApiException {@code RESOURCE_NOT_FOUND} for a review of another brand, {@code
     *                      STALE_VERSION} on a lost race, {@code UNPROCESSABLE_STATE} for an edge the
     *                      machine does not have or a restore after the customer took the words back,
     *                      {@code VALIDATION_FAILED} for a missing or system reason
     */
    @Transactional
    public PublicationRow decide(
            UUID tenantId,
            UUID brandId,
            UUID reviewId,
            CommentState to,
            @Nullable ModerationReason reason,
            int expectedVersion,
            String actorSubject) {
        PublicationRow current = publications
                .find(tenantId, reviewId)
                .filter(row -> row.brandId().equals(brandId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such review"));
        if (current.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, current.version());
        }
        CommentState from = current.commentState();
        if (!from.moderatorTargets().contains(to)) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "A comment cannot be moved from %s to %s by a moderator".formatted(from, to));
        }
        if (from == CommentState.HIDDEN) {
            ModerationReason hiddenFor = current.stateReasonCode() == null
                    ? null
                    : ModerationReason.parse(current.stateReasonCode()).orElse(null);
            if (hiddenFor != null && hiddenFor.blocksRestore()) {
                throw new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        "The customer took these words back, so a moderator cannot publish them again");
            }
        }
        String reasonCode;
        String auditReason;
        if (to == CommentState.HIDDEN) {
            if (reason == null || !reason.moderatorMayGive()) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Hiding a comment needs one of: ABUSIVE_LANGUAGE, PERSONAL_DATA_OF_ANOTHER, "
                                + "NAMES_AN_INDIVIDUAL, SPAM_OR_UNRELATED, LEGAL_REQUEST");
            }
            reasonCode = reason.name();
            auditReason = reason.label();
        } else {
            if (reason != null) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED, "Only hiding a comment takes a reason; publishing gives none");
            }
            reasonCode = null;
            auditReason = from == CommentState.PENDING ? "Approved for public display" : "Restored to public display";
        }

        Instant now = clock.instant();
        int newVersion = publications
                .transition(tenantId, reviewId, expectedVersion, from, to, reasonCode, "STAFF", actorSubject, null, now)
                .orElseThrow(() -> ApiException.staleVersion(
                        expectedVersion,
                        publications
                                .find(tenantId, reviewId)
                                .map(PublicationRow::version)
                                .orElse(-1)));

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("state", from.name());
        before.put("reasonCode", current.stateReasonCode());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("state", to.name());
        after.put("reasonCode", reasonCode);
        audit.record(AuditFact.of(AUDIT_ACTION, AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("ReviewPublication", reviewId)
                .targetVersion((long) newVersion)
                .because(auditReason)
                .usingCapability(Capability.REVIEW_MODERATE.code())
                .changed(ChangeDocuments.diff(before, after))
                .correlatedBy(reviewId.toString())
                .occurredAt(now)
                .build());

        Row review = reviews.findById(tenantId, reviewId).orElseThrow();
        events.publishEvent(new ReviewCommentModerated(
                Ids.newId(),
                tenantId,
                reviewId,
                review.orderId(),
                brandId,
                from.name(),
                to.name(),
                reasonCode == null ? "NONE" : reasonCode,
                "STAFF",
                now));
        metrics.commentDecided(to.name(), reasonCode == null ? "NONE" : reasonCode);
        publicCache.evictAfterCommit(tenantId, brandId);
        return publications.find(tenantId, reviewId).orElseThrow();
    }

    /**
     * The customer takes their words off the public page.
     *
     * @throws ApiException {@code RESOURCE_NOT_FOUND} when the review is not this customer's at this
     *                      brand, {@code UNPROCESSABLE_STATE} when it was never shared or has already
     *                      been withdrawn or erased
     */
    @Transactional
    public PublicationRow withdraw(UUID tenantId, UUID brandId, UUID customerAccountId, UUID reviewId) {
        Row review = reviews.findById(tenantId, reviewId)
                .filter(row ->
                        row.brandId().equals(brandId) && row.customerAccountId().equals(customerAccountId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such review"));
        PublicationRow current = publications
                .find(tenantId, reviewId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such review"));
        if (current.commentState() == CommentState.NOT_SHARED || !current.publicConsent()) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "This comment was never shared, so there is nothing to withdraw");
        }
        Optional<ModerationReason> hiddenFor = current.stateReasonCode() == null
                ? Optional.empty()
                : ModerationReason.parse(current.stateReasonCode());
        if (current.withdrawalDecisionId() != null
                || hiddenFor.filter(ModerationReason::blocksRestore).isPresent()) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, "This comment has already been taken off the page");
        }

        Instant now = clock.instant();
        UUID decisionId = consent.recordWithdrawal(
                tenantId,
                customerAccountId,
                brandId,
                ReviewSubmissionService.PUBLIC_DISPLAY_PURPOSE,
                null,
                ReviewSubmissionService.PUBLIC_DISPLAY_POLICY_VERSION,
                "STOREFRONT",
                reviewId.toString());
        boolean moved = publications
                .transition(
                        tenantId,
                        reviewId,
                        current.version(),
                        current.commentState(),
                        CommentState.HIDDEN,
                        ModerationReason.CUSTOMER_WITHDREW.name(),
                        "CUSTOMER",
                        customerAccountId.toString(),
                        decisionId,
                        now)
                .isPresent();
        if (!moved) {
            throw ApiException.staleVersion(
                    current.version(),
                    publications
                            .find(tenantId, reviewId)
                            .map(PublicationRow::version)
                            .orElse(-1));
        }

        events.publishEvent(new ReviewCommentModerated(
                Ids.newId(),
                tenantId,
                reviewId,
                review.orderId(),
                brandId,
                current.commentState().name(),
                CommentState.HIDDEN.name(),
                ModerationReason.CUSTOMER_WITHDREW.name(),
                "CUSTOMER",
                now));
        metrics.commentDecided(CommentState.HIDDEN.name(), ModerationReason.CUSTOMER_WITHDREW.name());
        publicCache.evictAfterCommit(tenantId, brandId);
        return publications.find(tenantId, reviewId).orElseThrow();
    }
}
