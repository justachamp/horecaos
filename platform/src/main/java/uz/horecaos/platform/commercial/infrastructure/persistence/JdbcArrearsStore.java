package uz.horecaos.platform.commercial.infrastructure.persistence;

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
import uz.horecaos.platform.commercial.api.ArrearsDirectory.Arrear;
import uz.horecaos.platform.commercial.domain.SubscriptionStatus;

/**
 * Subscriptions in arrears across every tenant (ADR 0089), read through the
 * partial index V0202 keeps on exactly those rows.
 */
@Repository
public class JdbcArrearsStore {

    private final JdbcClient jdbc;

    public JdbcArrearsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every past-due or suspended subscription with its tenant's name, longest in arrears first. */
    public List<ArrearRow> board() {
        return jdbc.sql("""
                        SELECT s.id, s.tenant_id, t.display_name, s.status, s.status_changed_at,
                               s.suspension_reason, s.version, p.code AS plan_code, v.version_number
                          FROM commercial.subscriptions s
                          JOIN tenant.tenants t ON t.id = s.tenant_id
                          JOIN commercial.plan_versions v ON v.id = s.plan_version_id
                          JOIN commercial.plans p ON p.id = v.plan_id
                         WHERE s.status IN ('PAST_DUE', 'SUSPENDED')
                         ORDER BY s.status_changed_at
                        """).query(JdbcArrearsStore::row).list();
    }

    public List<Arrear> pastDueSince(Instant before, int limit) {
        return jdbc.sql("""
                        SELECT tenant_id, id, status_changed_at
                          FROM commercial.subscriptions
                         WHERE status = 'PAST_DUE' AND status_changed_at < :before
                         ORDER BY status_changed_at
                         LIMIT :limit
                        """)
                .param("before", OffsetDateTime.ofInstant(before, ZoneOffset.UTC))
                .param("limit", limit)
                .query((row, number) -> new Arrear(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("id", UUID.class),
                        row.getObject("status_changed_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** One subscription on the arrears board. */
    public record ArrearRow(
            UUID subscriptionId,
            UUID tenantId,
            String tenantName,
            SubscriptionStatus status,
            Instant since,
            @Nullable String suspensionReason,
            long version,
            String planCode,
            int versionNumber) {}

    private static ArrearRow row(ResultSet row, int number) throws SQLException {
        return new ArrearRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getString("display_name"),
                SubscriptionStatus.valueOf(row.getString("status")),
                row.getObject("status_changed_at", OffsetDateTime.class).toInstant(),
                row.getString("suspension_reason"),
                row.getLong("version"),
                row.getString("plan_code"),
                row.getInt("version_number"));
    }
}
