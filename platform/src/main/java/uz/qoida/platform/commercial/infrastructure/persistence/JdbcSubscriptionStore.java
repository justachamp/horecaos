package uz.qoida.platform.commercial.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import uz.qoida.platform.commercial.api.EnforcementMode;
import uz.qoida.platform.commercial.domain.EntitlementOverride;
import uz.qoida.platform.commercial.domain.Subscription;
import uz.qoida.platform.commercial.domain.SubscriptionStatus;

/**
 * Subscription and override persistence (ADR 0021).
 *
 * <p>Every statement carries the tenant predicate. A subscription id is a UUID
 * that arrives from a console, and a lookup on it alone would serve another
 * restaurant's commercial terms to whoever asked.
 */
@Repository
public class JdbcSubscriptionStore {

    private final JdbcClient jdbc;

    public JdbcSubscriptionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Subscription subscription, Instant now) {
        jdbc.sql("""
                INSERT INTO commercial.subscriptions (
                    id, tenant_id, plan_version_id, status, start_at, trial_end_at,
                    current_period_start, current_period_end, external_billing_reference,
                    version, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :planVersionId, :status, :startAt, :trialEndAt,
                    :periodStart, :periodEnd, :externalReference,
                    1, :now, :now)
                """)
                .param("id", subscription.id())
                .param("tenantId", subscription.tenantId())
                .param("planVersionId", subscription.planVersionId())
                .param("status", subscription.status().name())
                .param("startAt", utc(subscription.startAt()))
                .param("trialEndAt", utc(subscription.trialEndAt()))
                .param("periodStart", utc(subscription.currentPeriodStart()))
                .param("periodEnd", utc(subscription.currentPeriodEnd()))
                .param("externalReference", subscription.externalBillingReference())
                .param("now", utc(now))
                .update();
    }

    public Optional<Subscription> findLive(UUID tenantId) {
        return jdbc.sql(SELECT + """
                 WHERE tenant_id = :tenantId AND status NOT IN ('TERMINATED', 'EXPIRED')
                """)
                .param("tenantId", tenantId)
                .query(JdbcSubscriptionStore::map)
                .optional();
    }

    public List<Subscription> history(UUID tenantId) {
        return jdbc.sql(SELECT + " WHERE tenant_id = :tenantId ORDER BY created_at DESC")
                .param("tenantId", tenantId)
                .query(JdbcSubscriptionStore::map)
                .list();
    }

    /**
     * Moves a subscription to a new status.
     *
     * <p>A conditional UPDATE naming both the status and the version it expects.
     * Nothing here reads, decides, then writes: that pattern is how a scheduled
     * trial expiry cancels a subscription an operator converted a millisecond
     * earlier.
     *
     * @return true when this call performed the transition
     */
    public boolean transition(UUID tenantId, UUID subscriptionId, SubscriptionStatus from,
            SubscriptionStatus to, long expectedVersion, Instant suspendedAt,
            String suspensionReason, Instant cancelAt, Instant endedAt, Instant now) {

        return jdbc.sql("""
                UPDATE commercial.subscriptions
                   SET status = :to,
                       suspended_at = :suspendedAt,
                       suspension_reason = :suspensionReason,
                       cancel_at = :cancelAt,
                       ended_at = :endedAt,
                       version = version + 1,
                       updated_at = :now
                 WHERE id = :id AND tenant_id = :tenantId
                   AND status = :from AND version = :expectedVersion
                """)
                .param("id", subscriptionId).param("tenantId", tenantId)
                .param("from", from.name()).param("to", to.name())
                .param("expectedVersion", expectedVersion)
                .param("suspendedAt", utc(suspendedAt))
                .param("suspensionReason", suspensionReason)
                .param("cancelAt", utc(cancelAt))
                .param("endedAt", utc(endedAt))
                .param("now", utc(now))
                .update() == 1;
    }

    /** Rolls the billing window forward without touching anything else. */
    public boolean advancePeriod(UUID tenantId, UUID subscriptionId, Instant periodStart,
            Instant periodEnd, long expectedVersion, Instant now) {

        return jdbc.sql("""
                UPDATE commercial.subscriptions
                   SET current_period_start = :periodStart, current_period_end = :periodEnd,
                       version = version + 1, updated_at = :now
                 WHERE id = :id AND tenant_id = :tenantId AND version = :expectedVersion
                """)
                .param("id", subscriptionId).param("tenantId", tenantId)
                .param("periodStart", utc(periodStart)).param("periodEnd", utc(periodEnd))
                .param("expectedVersion", expectedVersion).param("now", utc(now))
                .update() == 1;
    }

    // ------------------------------------------------------------- overrides

    public void insertOverride(UUID id, UUID tenantId, String key, Long integerValue,
            Boolean booleanValue, EnforcementMode mode, String reason, Instant validFrom,
            Instant validUntil, String requestedBy, String approvedBy, Instant now) {

        jdbc.sql("""
                INSERT INTO commercial.entitlement_overrides (
                    id, tenant_id, entitlement_key, value_type, boolean_value, integer_value,
                    enforcement_mode, reason, valid_from, valid_until,
                    requested_by, approved_by, version, created_at, updated_at)
                VALUES (
                    :id, :tenantId, :key, :valueType, :booleanValue, :integerValue,
                    :mode, :reason, :validFrom, :validUntil,
                    :requestedBy, :approvedBy, 1, :now, :now)
                """)
                .param("id", id).param("tenantId", tenantId).param("key", key)
                .param("valueType", integerValue != null ? "INTEGER" : "BOOLEAN")
                .param("booleanValue", booleanValue).param("integerValue", integerValue)
                .param("mode", mode == null ? null : mode.name())
                .param("reason", reason)
                .param("validFrom", utc(validFrom)).param("validUntil", utc(validUntil))
                .param("requestedBy", requestedBy).param("approvedBy", approvedBy)
                .param("now", utc(now))
                .update();
    }

    public boolean revokeOverride(UUID tenantId, String key, String revokedBy, Instant now) {
        return jdbc.sql("""
                UPDATE commercial.entitlement_overrides
                   SET revoked_at = :now, revoked_by = :revokedBy,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND entitlement_key = :key AND revoked_at IS NULL
                """)
                .param("tenantId", tenantId).param("key", key)
                .param("revokedBy", revokedBy).param("now", utc(now))
                .update() == 1;
    }

    /**
     * Unrevoked overrides for a tenant, whether or not their window is open.
     *
     * <p>An expired override is fetched deliberately. The resolver decides
     * liveness against its own clock so that resolution stays a pure function of
     * rows plus an instant, which is what makes the expiry boundary testable
     * without waiting for it.
     */
    public Map<String, EntitlementOverride> overrides(UUID tenantId) {
        List<EntitlementOverride> rows = jdbc.sql("""
                SELECT entitlement_key, integer_value, boolean_value, enforcement_mode,
                       valid_from, valid_until
                  FROM commercial.entitlement_overrides
                 WHERE tenant_id = :tenantId AND revoked_at IS NULL
                """)
                .param("tenantId", tenantId)
                .query((row, number) -> new EntitlementOverride(
                        row.getString("entitlement_key"),
                        row.getObject("integer_value", Long.class),
                        row.getObject("boolean_value", Boolean.class),
                        row.getString("enforcement_mode") == null
                                ? null : EnforcementMode.valueOf(row.getString("enforcement_mode")),
                        row.getObject("valid_from", OffsetDateTime.class).toInstant(),
                        row.getObject("valid_until", OffsetDateTime.class).toInstant()))
                .list();

        Map<String, EntitlementOverride> byKey = new LinkedHashMap<>();
        rows.forEach(row -> byKey.put(row.entitlementKey(), row));
        return Map.copyOf(byKey);
    }

    /**
     * The tenant's own timezone, which decides when a period rolls.
     *
     * <p>Read from {@code tenant.tenants} rather than assumed, because
     * Asia/Tashkent is five hours from UTC and a monthly allowance computed in
     * UTC resets on the evening of the last day of the previous month.
     */
    public ZoneId timezone(UUID tenantId) {
        return jdbc.sql("SELECT default_timezone FROM tenant.tenants WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .optional()
                .map(ZoneId::of)
                .orElse(ZoneOffset.UTC);
    }

    private static final String SELECT = """
            SELECT id, tenant_id, plan_version_id, status, start_at, trial_end_at,
                   current_period_start, current_period_end, cancel_at, suspended_at,
                   suspension_reason, ended_at, external_billing_reference, version
              FROM commercial.subscriptions
            """;

    private static Subscription map(ResultSet row, int number) throws SQLException {
        return new Subscription(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("plan_version_id", UUID.class),
                SubscriptionStatus.valueOf(row.getString("status")),
                instant(row, "start_at"),
                instant(row, "trial_end_at"),
                instant(row, "current_period_start"),
                instant(row, "current_period_end"),
                instant(row, "cancel_at"),
                instant(row, "suspended_at"),
                row.getString("suspension_reason"),
                instant(row, "ended_at"),
                row.getString("external_billing_reference"),
                row.getLong("version"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
