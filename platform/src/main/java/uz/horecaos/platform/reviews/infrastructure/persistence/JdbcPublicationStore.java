package uz.horecaos.platform.reviews.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.reviews.domain.CommentState;

/**
 * {@code reviews.review_publications} (V0543, ADR 0159): whether a review's comment may be shown on the
 * brand's public page. The overlay of {@code order_reviews}, which this class never writes.
 *
 * <p>The row is mutable by design, but {@code public_consent} is deliberately not in the column grant:
 * it is the immutable snapshot of what the customer ticked at submission, and the present state of a
 * comment is {@code comment_state} together with {@code withdrawal_decision_id}.
 */
@Repository
public class JdbcPublicationStore {

    private final JdbcClient jdbc;

    public JdbcPublicationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One publication row as stored. */
    public record PublicationRow(
            UUID tenantId,
            UUID reviewId,
            UUID brandId,
            UUID locationId,
            boolean publicConsent,
            @Nullable UUID consentDecisionId,
            @Nullable UUID withdrawalDecisionId,
            CommentState commentState,
            @Nullable String stateReasonCode,
            @Nullable String decidedByActorType,
            @Nullable String decidedBySubject,
            @Nullable Instant decidedAt,
            int version,
            Instant createdAt,
            Instant updatedAt) {}

    private static final String COLUMNS = """
            tenant_id, review_id, brand_id, location_id, public_consent, consent_decision_id,
            withdrawal_decision_id, comment_state, state_reason_code, decided_by_actor_type,
            decided_by_subject, decided_at, version, created_at, updated_at
            """;

    public void insert(PublicationRow row) {
        jdbc.sql("""
                INSERT INTO reviews.review_publications
                    (tenant_id, review_id, brand_id, location_id, public_consent, consent_decision_id,
                     comment_state, version, created_at, updated_at)
                VALUES (:tenantId, :reviewId, :brandId, :locationId, :consent, :decision,
                        :state, 1, :now, :now)
                """)
                .param("tenantId", row.tenantId())
                .param("reviewId", row.reviewId())
                .param("brandId", row.brandId())
                .param("locationId", row.locationId())
                .param("consent", row.publicConsent())
                .param("decision", row.consentDecisionId())
                .param("state", row.commentState().name())
                .param("now", utc(row.createdAt()))
                .update();
    }

    public Optional<PublicationRow> find(UUID tenantId, UUID reviewId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                  FROM reviews.review_publications
                 WHERE tenant_id = :tenantId AND review_id = :reviewId
                """)
                .param("tenantId", tenantId)
                .param("reviewId", reviewId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public Map<UUID, PublicationRow> forReviews(UUID tenantId, Collection<UUID> reviewIds) {
        Map<UUID, PublicationRow> byReview = new LinkedHashMap<>();
        if (reviewIds.isEmpty()) {
            return byReview;
        }
        jdbc.sql("SELECT " + COLUMNS + """
                  FROM reviews.review_publications
                 WHERE tenant_id = :tenantId AND review_id IN (:reviewIds)
                """)
                .param("tenantId", tenantId)
                .param("reviewIds", reviewIds)
                .query((org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    PublicationRow row = map(rs);
                    byReview.put(row.reviewId(), row);
                });
        return byReview;
    }

    /**
     * Moves a comment between states under the version the caller read, in one conditional statement
     * (ADR 0159, Decision 7). The previous state is part of the predicate, so a decision made against a
     * state that has since changed is refused rather than applied to the wrong thing.
     *
     * @param withdrawalDecisionId set only by the customer's own withdrawal, never by a moderator
     * @return the new version, or empty when the row is no longer at {@code expectedVersion} in {@code from}
     */
    public Optional<Integer> transition(
            UUID tenantId,
            UUID reviewId,
            int expectedVersion,
            CommentState from,
            CommentState to,
            @Nullable String reasonCode,
            String actorType,
            String actorSubject,
            @Nullable UUID withdrawalDecisionId,
            Instant now) {
        return jdbc.sql("""
                UPDATE reviews.review_publications
                   SET comment_state = :to, state_reason_code = :reason, decided_by_actor_type = :actorType,
                       decided_by_subject = :subject, decided_at = :now,
                       withdrawal_decision_id = COALESCE(:withdrawal, withdrawal_decision_id),
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND review_id = :reviewId
                   AND version = :expected AND comment_state = :from
                RETURNING version
                """)
                .param("to", to.name())
                .param("reason", reasonCode)
                .param("actorType", actorType)
                .param("subject", truncate(actorSubject, 64))
                .param("now", utc(now))
                .param("withdrawal", withdrawalDecisionId)
                .param("tenantId", tenantId)
                .param("reviewId", reviewId)
                .param("expected", expectedVersion)
                .param("from", from.name())
                .query(Integer.class)
                .optional();
    }

    /** A comment an erasure just hid: where it was, so the event and the cache eviction can say so. */
    public record Hidden(UUID reviewId, UUID brandId, UUID orderId, CommentState fromState) {}

    /**
     * An erasure (ADR 0159, Decision 13): every shared comment of this customer is hidden for
     * {@code CUSTOMER_ERASED}, whatever hid it before, so a moderator cannot restore words that no longer
     * exist. A row already hidden for {@code CUSTOMER_ERASED} is left alone, which is what makes a retried
     * erasure change nothing.
     *
     * @return the rows that changed, for the events and for the public list's cache eviction
     */
    public List<Hidden> hideAllOf(UUID tenantId, UUID customerAccountId, Instant now) {
        return jdbc.sql("""
                WITH target AS (
                    SELECT p.review_id, p.brand_id, r.order_id, p.comment_state AS from_state
                      FROM reviews.review_publications p
                      JOIN reviews.order_reviews r ON r.tenant_id = p.tenant_id AND r.id = p.review_id
                     WHERE p.tenant_id = :tenantId AND r.customer_account_id = :accountId
                       AND p.comment_state <> 'NOT_SHARED'
                       AND NOT (p.comment_state = 'HIDDEN' AND p.state_reason_code = 'CUSTOMER_ERASED')
                       FOR UPDATE OF p)
                UPDATE reviews.review_publications p
                   SET comment_state = 'HIDDEN', state_reason_code = 'CUSTOMER_ERASED',
                       decided_by_actor_type = 'SYSTEM', decided_by_subject = 'reviews.customer-erasure',
                       decided_at = :now, version = p.version + 1, updated_at = :now
                  FROM target
                 WHERE p.tenant_id = :tenantId AND p.review_id = target.review_id
                RETURNING target.review_id, target.brand_id, target.order_id, target.from_state
                """)
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .query((rs, n) -> new Hidden(
                        rs.getObject("review_id", UUID.class),
                        rs.getObject("brand_id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        CommentState.valueOf(rs.getString("from_state"))))
                .list();
    }

    // ------------------------------------------------------------- moderation queue

    /** A queue entry: the publication state beside what a moderator needs to decide it. */
    public record QueueRow(
            UUID reviewId,
            UUID locationId,
            int rating,
            @Nullable String commentProtected,
            Instant submittedAt,
            CommentState commentState,
            @Nullable String stateReasonCode,
            int version,
            Instant updatedAt) {}

    /** The updated-at of one publication row of this brand, to continue a queue page from. */
    public Optional<Instant> queueCursor(UUID tenantId, UUID brandId, UUID reviewId) {
        return jdbc.sql("""
                SELECT updated_at FROM reviews.review_publications
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND review_id = :reviewId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("reviewId", reviewId)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    /**
     * One brand's comments in a state, longest-waiting first: the order a moderator should work them.
     * Only a comment that exists is queued -- a row whose comment was erased has nothing to decide.
     */
    public List<QueueRow> queue(
            UUID tenantId,
            UUID brandId,
            CommentState state,
            @Nullable Instant after,
            @Nullable UUID afterId,
            int limit) {
        boolean unbounded = after == null;
        return jdbc.sql("""
                SELECT p.review_id, p.location_id, r.rating, r.comment_protected, r.submitted_at,
                       p.comment_state, p.state_reason_code, p.version, p.updated_at
                  FROM reviews.review_publications p
                  JOIN reviews.order_reviews r ON r.tenant_id = p.tenant_id AND r.id = p.review_id
                 WHERE p.tenant_id = :tenantId AND p.brand_id = :brandId AND p.comment_state = :state
                   AND r.comment_protected IS NOT NULL
                   AND (:unbounded
                        OR (p.updated_at, p.review_id) > (CAST(:after AS timestamptz), CAST(:afterId AS uuid)))
                 ORDER BY p.updated_at, p.review_id
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("state", state.name())
                .param("unbounded", unbounded)
                .param("after", after == null ? null : utc(after))
                .param("afterId", afterId == null ? null : afterId.toString())
                .param("limit", limit)
                .query((rs, n) -> new QueueRow(
                        rs.getObject("review_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getInt("rating"),
                        rs.getString("comment_protected"),
                        rs.getObject("submitted_at", OffsetDateTime.class).toInstant(),
                        CommentState.valueOf(rs.getString("comment_state")),
                        rs.getString("state_reason_code"),
                        rs.getInt("version"),
                        rs.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** How many comments of the brand sit in each state, for the moderation tab's counts. */
    public Map<CommentState, Long> countsByState(UUID tenantId, UUID brandId) {
        Map<CommentState, Long> counts = new LinkedHashMap<>();
        for (CommentState state : CommentState.values()) {
            counts.put(state, 0L);
        }
        jdbc.sql("""
                SELECT p.comment_state, count(*) AS n
                  FROM reviews.review_publications p
                  JOIN reviews.order_reviews r ON r.tenant_id = p.tenant_id AND r.id = p.review_id
                 WHERE p.tenant_id = :tenantId AND p.brand_id = :brandId AND r.comment_protected IS NOT NULL
                 GROUP BY p.comment_state
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query((org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> counts.put(CommentState.valueOf(rs.getString("comment_state")), rs.getLong("n")));
        return counts;
    }

    // ----------------------------------------------------------------- public reads

    /** A published comment's row for the anonymous list: nothing that identifies a person or an order. */
    public record PublicRow(
            UUID reviewId,
            UUID locationId,
            int rating,
            String commentProtected,
            Instant submittedAt,
            CommentState commentState,
            boolean publicConsent) {}

    /**
     * The brand's comments the public may read, newest first, a window at a time. A comment appears only
     * when it is {@code PUBLISHED}, its author's consent snapshot is true, and the words still exist --
     * the three facts the anonymous reveal depends on, checked in the statement and not left to the
     * caller.
     *
     * <p>An offset and not a keyset on purpose: a review id is a version-7 id, which carries the
     * millisecond it was made, and the public page shows the day and never the time of day. A cursor
     * built from an id or a timestamp would hand it back.
     */
    public List<PublicRow> publishedComments(UUID tenantId, UUID brandId, int offset, int limit) {
        return jdbc.sql("""
                SELECT r.id AS review_id, r.location_id, r.rating, r.comment_protected, r.submitted_at,
                       p.comment_state, p.public_consent
                  FROM reviews.review_publications p
                  JOIN reviews.order_reviews r ON r.tenant_id = p.tenant_id AND r.id = p.review_id
                 WHERE p.tenant_id = :tenantId AND p.brand_id = :brandId
                   AND p.comment_state = 'PUBLISHED' AND p.public_consent
                   AND r.comment_protected IS NOT NULL
                 ORDER BY r.submitted_at DESC, r.id DESC
                 LIMIT :limit OFFSET :offset
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> new PublicRow(
                        rs.getObject("review_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getInt("rating"),
                        rs.getString("comment_protected"),
                        rs.getObject("submitted_at", OffsetDateTime.class).toInstant(),
                        CommentState.valueOf(rs.getString("comment_state")),
                        rs.getBoolean("public_consent")))
                .list();
    }

    private static PublicationRow map(ResultSet rs) throws SQLException {
        OffsetDateTime decidedAt = rs.getObject("decided_at", OffsetDateTime.class);
        return new PublicationRow(
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("review_id", UUID.class),
                rs.getObject("brand_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getBoolean("public_consent"),
                rs.getObject("consent_decision_id", UUID.class),
                rs.getObject("withdrawal_decision_id", UUID.class),
                CommentState.valueOf(rs.getString("comment_state")),
                rs.getString("state_reason_code"),
                rs.getString("decided_by_actor_type"),
                rs.getString("decided_by_subject"),
                decidedAt == null ? null : decidedAt.toInstant(),
                rs.getInt("version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
