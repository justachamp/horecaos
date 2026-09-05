package uz.horecaos.platform.reviews.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code reviews.order_reviews} (V0168, ADR 0071).
 *
 * <p>Every read here is scoped by the same predicate its caller already
 * authorised against — a customer's own {@code customer_account_id}, or a
 * brand an operator holds {@code review.read} at — so a cursor resolved
 * through one of the scoped lookups below can never be used to learn the
 * timing of a row outside that scope.
 */
@Repository
public class JdbcReviewStore {

    private static final String TABLE = "reviews.order_reviews";

    private final JdbcClient jdbc;

    public JdbcReviewStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One review, exactly as stored — the comment still protected, decrypted by the caller. */
    public record Row(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID orderId,
            UUID customerAccountId,
            int rating,
            @Nullable String commentProtected,
            Instant submittedAt) {}

    /** Whether this order already has a review — the friendly pre-check before the insert that actually enforces it. */
    public Optional<Row> findByOrder(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, location_id, order_id, customer_account_id,
                       rating, comment_protected, submitted_at
                  FROM reviews.order_reviews
                 WHERE tenant_id = :tenantId AND order_id = :orderId
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /**
     * Records a review. The caller has already checked {@link #findByOrder}; this
     * throws {@link org.springframework.dao.DataIntegrityViolationException} on
     * {@code uq_order_review_one_per_order} when a concurrent request won the
     * race between that check and this insert, which the caller reports the
     * same way as the pre-check.
     */
    public Row insert(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID orderId,
            UUID customerAccountId,
            int rating,
            @Nullable String commentProtected,
            Instant submittedAt) {
        jdbc.sql("""
                INSERT INTO reviews.order_reviews (
                    id, tenant_id, brand_id, location_id, order_id, customer_account_id,
                    rating, comment_protected, submitted_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :orderId, :customerAccountId,
                    :rating, :comment, :submittedAt)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("orderId", orderId)
                .param("customerAccountId", customerAccountId)
                .param("rating", rating)
                .param("comment", commentProtected)
                .param("submittedAt", OffsetDateTime.ofInstant(submittedAt, ZoneOffset.UTC))
                .update();
        return new Row(
                id, tenantId, brandId, locationId, orderId, customerAccountId, rating, commentProtected, submittedAt);
    }

    // ------------------------------------------------------- storefront: "my reviews"

    /**
     * The submitted-at instant of one of the caller's own reviews, or empty when
     * {@code reviewId} names no review of theirs — including one that belongs to
     * somebody else, which answers identically per {@code CustomerOrderHistoryController}'s
     * own cursor convention.
     */
    public Optional<Instant> customerCursor(UUID tenantId, UUID brandId, UUID customerAccountId, UUID reviewId) {
        return jdbc.sql("""
                SELECT submitted_at FROM reviews.order_reviews
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                   AND customer_account_id = :customerAccountId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("customerAccountId", customerAccountId)
                .param("id", reviewId)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    /**
     * The caller's own reviews at this brand, newest first — keyset-paginated on
     * {@code (submitted_at, id)} per ADR 0031, scoped by brand the same way
     * {@code StorefrontOrderingController}'s "my orders" is.
     */
    public List<Row> forCustomer(
            UUID tenantId,
            UUID brandId,
            UUID customerAccountId,
            @Nullable Instant before,
            @Nullable UUID beforeId,
            int limit) {
        // The composite (submitted_at, id) < (...) comparison needs both sides of
        // the row constructor explicitly typed for Postgres to plan it at all, and
        // an explicit :unbounded flag rather than an inline "IS NULL" check on the
        // cast timestamp — the exact shape JdbcOrderStore#listForCustomer already
        // uses for the identical keyset-pagination problem.
        boolean unbounded = before == null;
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, location_id, order_id, customer_account_id,
                       rating, comment_protected, submitted_at
                  FROM reviews.order_reviews
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                   AND customer_account_id = :customerAccountId
                   AND (:unbounded
                        OR (submitted_at, id) < (CAST(:before AS timestamptz), CAST(:beforeId AS uuid)))
                 ORDER BY submitted_at DESC, id DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("customerAccountId", customerAccountId)
                .param("unbounded", unbounded)
                .param("before", before == null ? null : OffsetDateTime.ofInstant(before, ZoneOffset.UTC))
                .param("beforeId", beforeId == null ? null : beforeId.toString())
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    // ------------------------------------------------------- operations: brand list

    /** The submitted-at instant of one review at this brand, scoped the same way {@link #listForBrand} is. */
    public Optional<Instant> brandCursor(UUID tenantId, UUID brandId, UUID reviewId) {
        return jdbc.sql("""
                SELECT submitted_at FROM reviews.order_reviews
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", reviewId)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant);
    }

    /**
     * A brand's reviews, newest first, filtered by whatever the operator supplied.
     * Every filter is optional and a null one is not applied — the empty-filter
     * call is "every review at this brand".
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public List<Row> listForBrand(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            @Nullable Integer minRating,
            @Nullable Integer maxRating,
            @Nullable Instant submittedFrom,
            @Nullable Instant submittedTo,
            @Nullable Instant before,
            @Nullable UUID beforeId,
            int limit) {
        // See forCustomer's own comment on :unbounded and the explicit CAST pair —
        // the same keyset-pagination shape, applied here too.
        boolean unbounded = before == null;
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, location_id, order_id, customer_account_id,
                       rating, comment_protected, submitted_at
                  FROM reviews.order_reviews
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                   AND (:locationId::uuid IS NULL OR location_id = :locationId)
                   AND (:minRating::smallint IS NULL OR rating >= :minRating)
                   AND (:maxRating::smallint IS NULL OR rating <= :maxRating)
                   AND (:submittedFrom::timestamptz IS NULL OR submitted_at >= :submittedFrom)
                   AND (:submittedTo::timestamptz IS NULL OR submitted_at <= :submittedTo)
                   AND (:unbounded
                        OR (submitted_at, id) < (CAST(:before AS timestamptz), CAST(:beforeId AS uuid)))
                 ORDER BY submitted_at DESC, id DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("minRating", minRating)
                .param("maxRating", maxRating)
                .param(
                        "submittedFrom",
                        submittedFrom == null ? null : OffsetDateTime.ofInstant(submittedFrom, ZoneOffset.UTC))
                .param(
                        "submittedTo",
                        submittedTo == null ? null : OffsetDateTime.ofInstant(submittedTo, ZoneOffset.UTC))
                .param("unbounded", unbounded)
                .param("before", before == null ? null : OffsetDateTime.ofInstant(before, ZoneOffset.UTC))
                .param("beforeId", beforeId == null ? null : beforeId.toString())
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** The brand's own count and average rating over the same filters {@link #listForBrand} accepts, minus paging. */
    public Summary summaryForBrand(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID locationId,
            @Nullable Instant submittedFrom,
            @Nullable Instant submittedTo) {
        return jdbc.sql("""
                SELECT count(*) AS review_count,
                       coalesce(avg(rating), 0) AS average_rating
                  FROM reviews.order_reviews
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                   AND (:locationId::uuid IS NULL OR location_id = :locationId)
                   AND (:submittedFrom::timestamptz IS NULL OR submitted_at >= :submittedFrom)
                   AND (:submittedTo::timestamptz IS NULL OR submitted_at <= :submittedTo)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param(
                        "submittedFrom",
                        submittedFrom == null ? null : OffsetDateTime.ofInstant(submittedFrom, ZoneOffset.UTC))
                .param(
                        "submittedTo",
                        submittedTo == null ? null : OffsetDateTime.ofInstant(submittedTo, ZoneOffset.UTC))
                .query((rs, n) -> new Summary(rs.getLong("review_count"), rs.getDouble("average_rating")))
                .single();
    }

    /** @param averageRating {@code 0} when {@code reviewCount} is also {@code 0} — never a divide-by-zero NaN */
    public record Summary(long reviewCount, double averageRating) {}

    private static Row map(ResultSet rs) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("brand_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                rs.getObject("order_id", UUID.class),
                rs.getObject("customer_account_id", UUID.class),
                rs.getInt("rating"),
                rs.getString("comment_protected"),
                rs.getTimestamp("submitted_at").toInstant());
    }
}
