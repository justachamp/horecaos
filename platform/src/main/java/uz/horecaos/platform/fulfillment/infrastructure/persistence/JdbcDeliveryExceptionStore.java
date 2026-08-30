package uz.horecaos.platform.fulfillment.infrastructure.persistence;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import static uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore.instant;
import static uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore.utc;

/**
 * {@code fulfillment.delivery_exceptions} (ADR 0014, V0054).
 *
 * <p>{@code ux_exception_one_open} does the deduplication, not this class: a
 * sweeper running every minute against a plan nobody has rescued must produce one
 * row, not sixty an hour saying the same thing, and only the index can make that
 * true across two workers.
 */
@Repository
public class JdbcDeliveryExceptionStore {

    /**
     * Detail is a short operator-facing note and is length-capped by the column.
     * It carries reason codes and identifiers only — never an address, a name or a
     * phone number, and never a provider's own error text, which is the one place
     * a customer's details have been seen to arrive in a column nobody was
     * watching (ADR 0029).
     */
    private static final int MAX_DETAIL = 400;

    private final JdbcClient jdbc;

    public JdbcDeliveryExceptionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true when this call opened the exception. False means one was
     *         already open for this plan and reason, which is the normal answer on
     *         every tick after the first
     */
    public boolean raise(UUID tenantId, UUID brandId, UUID locationId, UUID planId,
            String reasonCode, String detail, String raisedBy, Instant now) {

        Map<String, Object> params = new HashMap<>();
        params.put("id", UUID.randomUUID());
        params.put("tenantId", tenantId);
        params.put("brandId", brandId);
        params.put("locationId", locationId);
        params.put("planId", planId);
        params.put("reasonCode", reasonCode);
        params.put("detail", detail == null ? null
                : detail.substring(0, Math.min(detail.length(), MAX_DETAIL)));
        params.put("raisedBy", raisedBy);
        params.put("raisedAt", utc(now));

        return jdbc.sql("""
                INSERT INTO fulfillment.delivery_exceptions (
                    id, tenant_id, brand_id, location_id, delivery_plan_id,
                    reason_code, severity, status, detail, raised_at, raised_by)
                VALUES (
                    :id, :tenantId, :brandId, :locationId, :planId,
                    :reasonCode, 'ACTION_REQUIRED', 'OPEN', :detail, :raisedAt, :raisedBy)
                ON CONFLICT (tenant_id, delivery_plan_id, reason_code) WHERE status <> 'RESOLVED'
                DO NOTHING
                """)
                .params(params)
                .update() == 1;
    }

    public List<OpenException> open(UUID tenantId, UUID planId) {
        return jdbc.sql("""
                SELECT id, reason_code, severity, status, detail, raised_at, raised_by
                FROM fulfillment.delivery_exceptions
                WHERE tenant_id = :tenantId AND delivery_plan_id = :planId AND status <> 'RESOLVED'
                ORDER BY raised_at, id
                """)
                .param("tenantId", tenantId).param("planId", planId)
                .query((row, number) -> new OpenException(
                        row.getObject("id", UUID.class),
                        row.getString("reason_code"),
                        row.getString("severity"),
                        row.getString("status"),
                        row.getString("detail"),
                        instant(row, "raised_at"),
                        row.getString("raised_by")))
                .list();
    }

    /** An operator picked the plan up and did something about it. */
    public boolean resolve(UUID tenantId, UUID exceptionId, String resolutionCode,
            String resolvedBy, Instant now) {
        return jdbc.sql("""
                UPDATE fulfillment.delivery_exceptions
                SET status = 'RESOLVED', resolved_at = :now, resolved_by = :resolvedBy,
                    resolution_code = :resolutionCode
                WHERE tenant_id = :tenantId AND id = :exceptionId AND status <> 'RESOLVED'
                """)
                .param("tenantId", tenantId).param("exceptionId", exceptionId)
                .param("resolutionCode", resolutionCode).param("resolvedBy", resolvedBy)
                .param("now", utc(now))
                .update() == 1;
    }

    public record OpenException(
            UUID id,
            String reasonCode,
            String severity,
            String status,
            String detail,
            Instant raisedAt,
            String raisedBy) { }
}
