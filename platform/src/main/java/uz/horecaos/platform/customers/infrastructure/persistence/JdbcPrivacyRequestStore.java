package uz.horecaos.platform.customers.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The tenant's data-subject worklist (Settings 10.11): erasure requests, export requests and corrections in one list,
 * newest first. One statement over the three tables rather than three reads merged in memory, so the page and the limit
 * mean the same thing for every kind.
 */
@Repository
public class JdbcPrivacyRequestStore {

    private final JdbcClient jdbc;

    public JdbcPrivacyRequestStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param kind   {@code ERASURE}, {@code EXPORT}, {@code CORRECTION}, or null for all three
     * @param status {@code PENDING}, {@code COMPLETED}, {@code CANCELLED}, or null for every status (a correction is
     *               always {@code COMPLETED})
     */
    public List<PrivacyRequestRow> worklist(UUID tenantId, @Nullable String kind, @Nullable String status, int limit) {
        return jdbc.sql("""
                SELECT kind, id, customer_account_id, status, via, requested_at, settled_at, due_at, subject FROM (
                    SELECT 'ERASURE' AS kind, id, customer_account_id, status, requested_via AS via, requested_at,
                           COALESCE(completed_at, cancelled_at) AS settled_at, NULL::timestamptz AS due_at,
                           NULL::varchar AS subject
                      FROM customer.erasure_requests WHERE tenant_id = :tenantId
                    UNION ALL
                    SELECT 'EXPORT', id, customer_account_id, status, requested_via, requested_at,
                           COALESCE(produced_at, cancelled_at), response_due_at, NULL
                      FROM customer.export_requests WHERE tenant_id = :tenantId
                    UNION ALL
                    SELECT 'CORRECTION', id, customer_account_id, 'COMPLETED', 'OPERATIONS', corrected_at,
                           corrected_at, NULL, subject
                      FROM customer.profile_corrections WHERE tenant_id = :tenantId
                ) requests
                 WHERE (CAST(:kind AS varchar) IS NULL OR kind = CAST(:kind AS varchar))
                   AND (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar))
                 ORDER BY requested_at DESC, id DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("kind", kind)
                .param("status", status)
                .param("limit", limit)
                .query((row, number) -> new PrivacyRequestRow(
                        row.getString("kind"),
                        row.getObject("id", UUID.class),
                        row.getObject("customer_account_id", UUID.class),
                        row.getString("status"),
                        row.getString("via"),
                        row.getObject("requested_at", OffsetDateTime.class).toInstant(),
                        instant(row.getObject("settled_at", OffsetDateTime.class)),
                        instant(row.getObject("due_at", OffsetDateTime.class)),
                        row.getString("subject")))
                .list();
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** One line of the worklist: which kind, whose, where it stands. No value of any kind. */
    public record PrivacyRequestRow(
            String kind,
            UUID id,
            UUID customerAccountId,
            String status,
            String requestedVia,
            Instant requestedAt,
            @Nullable Instant settledAt,
            @Nullable Instant dueAt,
            @Nullable String subject) {}
}
