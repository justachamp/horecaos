package uz.horecaos.platform.marketing.infrastructure.persistence;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code marketing.automation_runs} (gap-map row 6.5, V0415) — the per-recipient
 * firing ledger.
 *
 * <p>{@link #claim} and one of {@link #markFired}/{@link #markRefused}/{@link
 * #markCancelled} run inside the same {@code AutomationFiringService}
 * transaction, in that order: the guard key is reserved first, before
 * eligibility is even checked, so two sweep passes racing the same customer
 * and the same guard key never both send. See V0415's own header for why
 * {@code PENDING} is a transient in-transaction state and never a committed
 * rest state.
 */
@Repository
public class JdbcAutomationRunStore {

    private final JdbcClient jdbc;

    public JdbcAutomationRunStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reserves the guard key. {@code ON CONFLICT DO NOTHING} on {@code
     * uq_automation_run_guard} — the database is the guard, not this method.
     *
     * @return false when this exact (rule, customer, guard key) already has a
     *         row, meaning this firing has already been decided and nothing
     *         more should happen
     */
    public boolean claim(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID automationRuleId,
            UUID customerAccountId,
            String triggerType,
            String guardKey,
            @Nullable UUID subjectId,
            Instant now) {

        return jdbc.sql("""
                INSERT INTO marketing.automation_runs (
                    id, tenant_id, brand_id, automation_rule_id, customer_account_id,
                    trigger_type, guard_key, subject_id, status, fired_at)
                VALUES (:id, :tenantId, :brandId, :ruleId, :accountId, :triggerType, :guardKey,
                    :subjectId, 'PENDING', :now)
                ON CONFLICT (tenant_id, automation_rule_id, customer_account_id, guard_key) DO NOTHING
                """)
                        .param("id", id)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("ruleId", automationRuleId)
                        .param("accountId", customerAccountId)
                        .param("triggerType", triggerType)
                        .param("guardKey", guardKey)
                        .param("subjectId", subjectId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public void markFired(UUID tenantId, UUID id, UUID notificationId, Instant firedAt) {
        jdbc.sql("""
                UPDATE marketing.automation_runs
                   SET status = 'FIRED', notification_id = :notificationId, fired_at = :firedAt
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", id)
                .param("notificationId", notificationId)
                .param("firedAt", utc(firedAt))
                .update();
    }

    public void markRefused(UUID tenantId, UUID id, String refusalReason) {
        jdbc.sql("""
                UPDATE marketing.automation_runs
                   SET status = 'REFUSED', refusal_reason = :reason
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", id)
                .param("reason", refusalReason)
                .update();
    }

    public void markCancelled(UUID tenantId, UUID id, String reason) {
        jdbc.sql("""
                UPDATE marketing.automation_runs
                   SET status = 'CANCELLED', cancelled_reason = :reason
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", id)
                .param("reason", reason)
                .update();
    }

    /** The rule's own recent firing history, newest first — the console's audit view. */
    public List<AutomationRunRow> recentByRule(UUID tenantId, UUID automationRuleId, int limit) {
        return jdbc.sql("""
                SELECT id, customer_account_id, trigger_type, status, refusal_reason,
                       cancelled_reason, notification_id, fired_at
                  FROM marketing.automation_runs
                 WHERE tenant_id = :tenantId AND automation_rule_id = :ruleId
                 ORDER BY fired_at DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("ruleId", automationRuleId)
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    private AutomationRunRow map(ResultSet row, int number) throws java.sql.SQLException {
        return new AutomationRunRow(
                row.getObject("id", UUID.class),
                row.getObject("customer_account_id", UUID.class),
                row.getString("trigger_type"),
                row.getString("status"),
                row.getString("refusal_reason"),
                row.getString("cancelled_reason"),
                row.getObject("notification_id", UUID.class),
                row.getObject("fired_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    public record AutomationRunRow(
            UUID id,
            UUID customerAccountId,
            String triggerType,
            String status,
            @Nullable String refusalReason,
            @Nullable String cancelledReason,
            @Nullable UUID notificationId,
            Instant firedAt) {}
}
