package uz.horecaos.platform.customers.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The voice contact journal (ADR 0111 §8): insert and read, and no other statement exists.
 *
 * <p>There is no update and no delete here because there is no grant for either: the application
 * role holds {@code INSERT} and {@code SELECT} on {@code customer.contact_attempts} and nothing
 * else (V0507), so a call outcome cannot be rewritten after the fact by this class or by any bug
 * in it. The rows carry ids, codes and instants only.
 */
@Repository
public class JdbcContactAttemptStore {

    private static final String COLUMNS = """
            id, tenant_id, brand_id, lead_id, customer_account_id, direction, channel, attempt_id, outcome,
            blocking_reason, operator_actor_id, occurred_at, recorded_at, next_action, next_action_at
            """;

    private final JdbcClient jdbc;

    public JdbcContactAttemptStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return false when this attempt id was already recorded for the tenant: a retried submit is
     *     one attempt, not two
     */
    public boolean insert(NewAttempt attempt, Instant recordedAt) {
        return jdbc.sql("""
                INSERT INTO customer.contact_attempts (
                    id, tenant_id, brand_id, lead_id, customer_account_id, direction, channel, attempt_id,
                    outcome, blocking_reason, operator_actor_id, occurred_at, recorded_at, next_action,
                    next_action_at)
                VALUES (:id, :tenantId, :brandId, :leadId, :accountId, :direction, 'PHONE', :attemptId,
                        :outcome, :blockingReason, :operator, :occurredAt, :recordedAt, :nextAction,
                        :nextActionAt)
                ON CONFLICT (tenant_id, attempt_id) DO NOTHING
                """)
                        .param("id", attempt.id())
                        .param("tenantId", attempt.tenantId())
                        .param("brandId", attempt.brandId())
                        .param("leadId", attempt.leadId())
                        .param("accountId", attempt.customerAccountId())
                        .param("direction", attempt.direction())
                        .param("attemptId", attempt.attemptId())
                        .param("outcome", attempt.outcome())
                        .param("blockingReason", attempt.blockingReason())
                        .param("operator", attempt.operatorActorId())
                        .param("occurredAt", at(attempt.occurredAt()))
                        .param("recordedAt", at(recordedAt))
                        .param("nextAction", attempt.nextAction())
                        .param("nextActionAt", attempt.nextActionAt() == null ? null : at(attempt.nextActionAt()))
                        .update()
                == 1;
    }

    /** The attempt a retried submit refers to. */
    public java.util.Optional<AttemptRow> byAttemptId(UUID tenantId, UUID attemptId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM customer.contact_attempts WHERE tenant_id = :tenantId AND attempt_id = :attemptId
                """)
                .param("tenantId", tenantId)
                .param("attemptId", attemptId)
                .query(JdbcContactAttemptStore::toRow)
                .optional();
    }

    public List<AttemptRow> forLead(UUID tenantId, UUID leadId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM customer.contact_attempts
                 WHERE tenant_id = :tenantId AND lead_id = :leadId
                 ORDER BY occurred_at DESC, id DESC LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("leadId", leadId)
                .param("limit", limit)
                .query(JdbcContactAttemptStore::toRow)
                .list();
    }

    /** Every attempt about one guest -- against the account itself or against a lead linked to it. */
    public List<AttemptRow> forAccount(UUID tenantId, UUID accountId, @Nullable Instant before, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM customer.contact_attempts a
                 WHERE a.tenant_id = :tenantId
                   AND (a.customer_account_id = :accountId
                        OR a.lead_id IN (SELECT l.id FROM customer.leads l
                                          WHERE l.tenant_id = :tenantId AND l.customer_account_id = :accountId))
                   AND (CAST(:before AS timestamptz) IS NULL OR a.occurred_at < CAST(:before AS timestamptz))
                 ORDER BY a.occurred_at DESC, a.id DESC LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("before", before == null ? null : at(before))
                .param("limit", limit)
                .query(JdbcContactAttemptStore::toRow)
                .list();
    }

    private static AttemptRow toRow(ResultSet rs, int row) throws SQLException {
        OffsetDateTime nextActionAt = rs.getObject("next_action_at", OffsetDateTime.class);
        return new AttemptRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("brand_id", UUID.class),
                rs.getObject("lead_id", UUID.class),
                rs.getObject("customer_account_id", UUID.class),
                rs.getString("direction"),
                rs.getString("channel"),
                rs.getObject("attempt_id", UUID.class),
                rs.getString("outcome"),
                rs.getString("blocking_reason"),
                rs.getString("operator_actor_id"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                rs.getString("next_action"),
                nextActionAt == null ? null : nextActionAt.toInstant());
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** One attempt about to be recorded. */
    public record NewAttempt(
            UUID id,
            UUID tenantId,
            UUID brandId,
            @Nullable UUID leadId,
            @Nullable UUID customerAccountId,
            String direction,
            UUID attemptId,
            String outcome,
            @Nullable String blockingReason,
            String operatorActorId,
            Instant occurredAt,
            @Nullable String nextAction,
            @Nullable Instant nextActionAt) {}

    /** One stored attempt. */
    public record AttemptRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            @Nullable UUID leadId,
            @Nullable UUID customerAccountId,
            String direction,
            String channel,
            UUID attemptId,
            String outcome,
            @Nullable String blockingReason,
            String operatorActorId,
            Instant occurredAt,
            Instant recordedAt,
            @Nullable String nextAction,
            @Nullable Instant nextActionAt) {}
}
