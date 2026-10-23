package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.ordering.api.TillRevisionNotices.Notice;

/**
 * {@code ordering.order_till_revision_notices} (V0665): money and codes about an order whose total moved
 * after the till already held it. Every statement carries the tenant, as the rest of {@code ordering} does.
 */
@Repository
public class JdbcTillRevisionNoticeStore {

    private static final String COLUMNS = """
            id, tenant_id, brand_id, location_id, order_id, source, revision, currency, till_total_minor,
            revised_total_minor, status, opened_at, resolved_at, attempt_count, last_failure_code""";

    private final JdbcClient jdbc;

    public JdbcTillRevisionNoticeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Notice> findOpen(UUID tenantId, UUID orderId) {
        return jdbc.sql("SELECT " + COLUMNS + """

                FROM ordering.order_till_revision_notices
                WHERE tenant_id = :tenantId AND order_id = :orderId AND status = 'OPEN'
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(JdbcTillRevisionNoticeStore::notice)
                .optional();
    }

    public Optional<Notice> findLatest(UUID tenantId, UUID orderId) {
        return jdbc.sql("SELECT " + COLUMNS + """

                FROM ordering.order_till_revision_notices
                WHERE tenant_id = :tenantId AND order_id = :orderId
                ORDER BY opened_at DESC, id DESC
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(JdbcTillRevisionNoticeStore::notice)
                .optional();
    }

    public Optional<Notice> find(UUID tenantId, UUID noticeId) {
        return jdbc.sql("SELECT " + COLUMNS + """

                FROM ordering.order_till_revision_notices
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", noticeId)
                .query(JdbcTillRevisionNoticeStore::notice)
                .optional();
    }

    /** Opens a notice. The partial unique index makes a second open one for the same order a conflict. */
    public void insertOpen(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID orderId,
            String source,
            int revision,
            String currency,
            long tillTotalMinor,
            long revisedTotalMinor,
            Instant now) {
        jdbc.sql("""
                INSERT INTO ordering.order_till_revision_notices (
                    id, tenant_id, brand_id, location_id, order_id, source, revision, currency,
                    till_total_minor, revised_total_minor, status, opened_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :orderId, :source, :revision, :currency,
                        :tillTotal, :revisedTotal, 'OPEN', :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("orderId", orderId)
                .param("source", source)
                .param("revision", revision)
                .param("currency", currency)
                .param("tillTotal", tillTotalMinor)
                .param("revisedTotal", revisedTotalMinor)
                .param("now", at(now))
                .update();
    }

    /**
     * A second weighing before anyone acted: the till still holds what it held, so only the revised figure and
     * the revision move. If the revised total is back to what the till holds there is nothing left to correct,
     * and the notice is closed as acknowledged by the platform itself.
     *
     * @return the notice as it is now, or empty when none was open
     */
    public Optional<Notice> reviseOpen(UUID tenantId, UUID orderId, int revision, long revisedTotalMinor, Instant now) {
        return jdbc.sql("""
                UPDATE ordering.order_till_revision_notices
                   SET revision = :revision, revised_total_minor = :revisedTotal, updated_at = :now
                 WHERE tenant_id = :tenantId AND order_id = :orderId AND status = 'OPEN'
                   AND till_total_minor <> :revisedTotal
                """ + " RETURNING " + COLUMNS)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .param("revision", revision)
                .param("revisedTotal", revisedTotalMinor)
                .param("now", at(now))
                .query(JdbcTillRevisionNoticeStore::notice)
                .optional();
    }

    /** Closes the order's open notice because the totals agree again (a weight corrected back). */
    public boolean closeAgreed(UUID tenantId, UUID orderId, int revision, Instant now) {
        return jdbc.sql("""
                UPDATE ordering.order_till_revision_notices
                   SET status = 'ACKNOWLEDGED', revision = :revision, resolved_at = :now,
                       resolved_by = 'system:totals-agree', updated_at = :now
                 WHERE tenant_id = :tenantId AND order_id = :orderId AND status = 'OPEN'
                """)
                        .param("tenantId", tenantId)
                        .param("orderId", orderId)
                        .param("revision", revision)
                        .param("now", at(now))
                        .update()
                == 1;
    }

    public boolean resolve(UUID tenantId, UUID noticeId, String status, String actor, Instant now) {
        return jdbc.sql("""
                UPDATE ordering.order_till_revision_notices
                   SET status = :status, resolved_at = :now, resolved_by = :actor, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'OPEN'
                """)
                        .param("tenantId", tenantId)
                        .param("id", noticeId)
                        .param("status", status)
                        .param("actor", actor)
                        .param("now", at(now))
                        .update()
                == 1;
    }

    public void recordFailedAttempt(UUID tenantId, UUID noticeId, String failureCode, Instant now) {
        jdbc.sql("""
                UPDATE ordering.order_till_revision_notices
                   SET attempt_count = attempt_count + 1, last_attempt_at = :now,
                       last_failure_code = :code, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'OPEN'
                """)
                .param("tenantId", tenantId)
                .param("id", noticeId)
                .param("code", failureCode)
                .param("now", at(now))
                .update();
    }

    private static Notice notice(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        OffsetDateTime resolved = row.getObject("resolved_at", OffsetDateTime.class);
        return new Notice(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getObject("order_id", UUID.class),
                row.getString("source"),
                row.getInt("revision"),
                row.getString("currency").strip(),
                row.getLong("till_total_minor"),
                row.getLong("revised_total_minor"),
                row.getString("status"),
                row.getObject("opened_at", OffsetDateTime.class).toInstant(),
                resolved == null ? null : resolved.toInstant(),
                row.getInt("attempt_count"),
                row.getString("last_failure_code"));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
