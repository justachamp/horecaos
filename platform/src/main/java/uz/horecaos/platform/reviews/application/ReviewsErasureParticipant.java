package uz.horecaos.platform.reviews.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.ErasesCustomerData;
import uz.horecaos.platform.customers.spi.ErasureCounts;
import uz.horecaos.platform.reviews.api.ReviewCommentModerated;
import uz.horecaos.platform.reviews.domain.CommentState;
import uz.horecaos.platform.reviews.domain.ModerationReason;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcPublicationStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcPublicationStore.Hidden;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore.ErasedComment;

/**
 * Takes a data subject's words out of {@code reviews} (ADR 0159, Decision 13, ADR 0029).
 *
 * <p>An erasure nulls the comment of every review the customer left and every note on every case of
 * those reviews, hides any shared comment for {@code CUSTOMER_ERASED}, and evicts the public list of each
 * brand where a row changed -- after the commit, as a withdrawal does. The rating, the tags, the ids, the
 * states and the outcomes stay, so no average moves and no case loses its history.
 *
 * <p>The comment update is the one exception to "no UPDATE" on {@code order_reviews}: a column grant on
 * {@code comment_protected} and a trigger that refuses any change except setting it to null. It is
 * idempotent under retry, as {@link CustomerErasureParticipant} requires: a second call finds nothing
 * left to null and nothing left to hide.
 *
 * <p>Registered the way {@code dinein}'s participant is: {@code customers} declares the seam and discovers
 * implementations by type, and never imports {@code reviews}.
 */
@Component
@ErasesCustomerData(
        name = "reviews",
        tables = {
            "reviews.order_reviews",
            "reviews.review_publications",
            "reviews.recovery_cases",
            "reviews.recovery_case_events"
        })
public class ReviewsErasureParticipant implements CustomerErasureParticipant {

    private final JdbcReviewStore reviews;
    private final JdbcPublicationStore publications;
    private final JdbcRecoveryCaseStore cases;
    private final PublicReviewCache publicCache;
    private final ApplicationEventPublisher events;
    private final ReviewsMetrics metrics;
    private final Clock clock;

    public ReviewsErasureParticipant(
            JdbcReviewStore reviews,
            JdbcPublicationStore publications,
            JdbcRecoveryCaseStore cases,
            PublicReviewCache publicCache,
            ApplicationEventPublisher events,
            ReviewsMetrics metrics,
            Clock clock) {
        this.reviews = reviews;
        this.publications = publications;
        this.cases = cases;
        this.publicCache = publicCache;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ErasureCounts erase(UUID tenantId, UUID customerAccountId, ErasedContacts held) {
        Instant now = clock.instant();
        List<ErasedComment> erased = reviews.eraseCommentsOf(tenantId, customerAccountId);
        List<Hidden> hidden = publications.hideAllOf(tenantId, customerAccountId, now);
        int notes = cases.eraseNotesOf(tenantId, customerAccountId);

        Set<UUID> brandsToEvict = new LinkedHashSet<>();
        erased.forEach(comment -> brandsToEvict.add(comment.brandId()));
        for (Hidden row : hidden) {
            brandsToEvict.add(row.brandId());
            events.publishEvent(new ReviewCommentModerated(
                    Ids.newId(),
                    tenantId,
                    row.reviewId(),
                    row.orderId(),
                    row.brandId(),
                    row.fromState().name(),
                    CommentState.HIDDEN.name(),
                    ModerationReason.CUSTOMER_ERASED.name(),
                    "SYSTEM",
                    now));
            metrics.commentDecided(CommentState.HIDDEN.name(), ModerationReason.CUSTOMER_ERASED.name());
        }
        brandsToEvict.forEach(brandId -> publicCache.evictAfterCommit(tenantId, brandId));
        return ErasureCounts.builder()
                .add("reviews.order_reviews", erased.size())
                .add("reviews.review_publications", hidden.size())
                .add("reviews.recovery_case_events", notes)
                .build();
    }
}
