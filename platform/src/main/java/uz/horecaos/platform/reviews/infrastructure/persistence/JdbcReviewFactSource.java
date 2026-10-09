package uz.horecaos.platform.reviews.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.reviews.api.RecoveryFactSource;
import uz.horecaos.platform.reviews.api.ReviewFactSource;

/**
 * The reviews and cases the day close turns into facts (ADR 0159, Decision 12, ADR 0043): the single
 * place {@code reporting} learns of them, through {@code reviews.api}, never by reading these tables.
 *
 * <p>A review counts whatever its comment's publication state -- no query here looks at {@code
 * review_publications} -- because the average counts every review of the brand. A case's remedy money is
 * the amount and currency copied onto its compensation link at attach, so nothing here reads {@code
 * payments}.
 */
@Repository
public class JdbcReviewFactSource implements ReviewFactSource, RecoveryFactSource {

    private final JdbcClient jdbc;

    public JdbcReviewFactSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewFact> submittedBetween(UUID tenantId, Instant fromInclusive, Instant toExclusive) {
        Map<UUID, List<TagFact>> tagsByReview = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT rt.review_id, ft.id AS tag_id, ft.category, ft.sentiment
                  FROM reviews.order_review_tags rt
                  JOIN reviews.feedback_tags ft ON ft.tenant_id = rt.tenant_id AND ft.id = rt.tag_id
                  JOIN reviews.order_reviews r ON r.tenant_id = rt.tenant_id AND r.id = rt.review_id
                 WHERE rt.tenant_id = :tenantId AND r.submitted_at >= :from AND r.submitted_at < :to
                 ORDER BY rt.review_id, ft.sort_order, ft.code
                """)
                .param("tenantId", tenantId)
                .param("from", utc(fromInclusive))
                .param("to", utc(toExclusive))
                .query((RowCallbackHandler) rs -> tagsByReview
                        .computeIfAbsent(rs.getObject("review_id", UUID.class), id -> new ArrayList<>())
                        .add(new TagFact(
                                rs.getObject("tag_id", UUID.class),
                                rs.getString("category"),
                                rs.getString("sentiment"))));

        return jdbc.sql("""
                SELECT id, brand_id, location_id, order_id, customer_account_id, rating,
                       comment_protected IS NOT NULL AS has_comment, submitted_at
                  FROM reviews.order_reviews
                 WHERE tenant_id = :tenantId AND submitted_at >= :from AND submitted_at < :to
                 ORDER BY submitted_at, id
                """)
                .param("tenantId", tenantId)
                .param("from", utc(fromInclusive))
                .param("to", utc(toExclusive))
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new ReviewFact(
                            id,
                            rs.getObject("brand_id", UUID.class),
                            rs.getObject("location_id", UUID.class),
                            rs.getObject("order_id", UUID.class),
                            rs.getObject("customer_account_id", UUID.class),
                            rs.getInt("rating"),
                            rs.getBoolean("has_comment"),
                            rs.getObject("submitted_at", OffsetDateTime.class).toInstant(),
                            tagsByReview.getOrDefault(id, List.of()));
                })
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CaseFact> touchedBetween(UUID tenantId, Instant fromInclusive, Instant toExclusive) {
        return jdbc.sql("""
                SELECT c.id, c.brand_id, c.location_id, c.order_id, c.review_id, r.rating, c.state, c.outcome_code,
                       c.created_at, c.first_response_at, c.closed_at,
                       c.first_response_breached_at IS NOT NULL AS first_response_breached,
                       c.resolution_breached_at IS NOT NULL AS resolution_breached,
                       (SELECT count(*) FROM reviews.recovery_case_compensations k
                         WHERE k.tenant_id = c.tenant_id AND k.case_id = c.id) AS compensation_count,
                       (SELECT COALESCE(sum(k.amount_minor), 0) FROM reviews.recovery_case_compensations k
                         WHERE k.tenant_id = c.tenant_id AND k.case_id = c.id AND k.kind = 'ORDER_REMEDY') AS remedy_money,
                       (SELECT max(k.currency) FROM reviews.recovery_case_compensations k
                         WHERE k.tenant_id = c.tenant_id AND k.case_id = c.id AND k.kind = 'ORDER_REMEDY') AS remedy_currency
                  FROM reviews.recovery_cases c
                  JOIN reviews.order_reviews r ON r.tenant_id = c.tenant_id AND r.id = c.review_id
                 WHERE c.tenant_id = :tenantId
                   AND ((c.created_at >= :from AND c.created_at < :to)
                        OR (c.closed_at >= :from AND c.closed_at < :to))
                 ORDER BY c.created_at, c.id
                """)
                .param("tenantId", tenantId)
                .param("from", utc(fromInclusive))
                .param("to", utc(toExclusive))
                .query((rs, n) -> new CaseFact(
                        rs.getObject("id", UUID.class),
                        rs.getObject("brand_id", UUID.class),
                        rs.getObject("location_id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getObject("review_id", UUID.class),
                        rs.getInt("rating"),
                        rs.getString("state"),
                        rs.getString("outcome_code"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        instant(rs.getObject("first_response_at", OffsetDateTime.class)),
                        instant(rs.getObject("closed_at", OffsetDateTime.class)),
                        rs.getBoolean("first_response_breached"),
                        rs.getBoolean("resolution_breached"),
                        rs.getInt("compensation_count"),
                        rs.getLong("remedy_money"),
                        rs.getString("remedy_currency")))
                .list();
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
