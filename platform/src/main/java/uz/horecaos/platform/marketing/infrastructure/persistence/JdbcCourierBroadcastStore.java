package uz.horecaos.platform.marketing.infrastructure.persistence;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Dispatcher-authored operational SMS blasts to couriers (V0307, operations
 * §6.4b) — never a customer campaign; see the migration's own doc for why.
 */
@Repository
public class JdbcCourierBroadcastStore {

    private final JdbcClient jdbc;

    public JdbcCourierBroadcastStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String targetKind,
            @Nullable UUID targetGroupId,
            String message,
            UUID createdBy,
            Instant now) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("tenantId", tenantId);
        params.put("brandId", brandId);
        params.put("targetKind", targetKind);
        params.put("targetGroupId", targetGroupId);
        params.put("message", message);
        params.put("createdBy", createdBy);
        params.put("now", utc(now));

        jdbc.sql("""
                INSERT INTO marketing.courier_broadcasts (
                    id, tenant_id, brand_id, target_kind, target_group_id, message,
                    created_by, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :targetKind, :targetGroupId, :message,
                    :createdBy, :now, :now)
                """).params(params).update();
    }

    private static final String COLUMNS = """
            id, tenant_id, brand_id, channel, target_kind, target_group_id, message,
            status, recipient_count, refusal_reason, created_by, created_at, updated_at, sent_at, version
            """;

    public Optional<CourierBroadcastRow> find(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM marketing.courier_broadcasts WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query(JdbcCourierBroadcastStore::toRow)
                .optional();
    }

    public List<CourierBroadcastRow> listByBrand(UUID tenantId, UUID brandId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM marketing.courier_broadcasts WHERE tenant_id = :tenantId AND brand_id = :brandId"
                        + " ORDER BY created_at DESC")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(JdbcCourierBroadcastStore::toRow)
                .list();
    }

    /** How many ACTIVE couriers of this tenant the target resolves to right now. */
    public int countTarget(UUID tenantId, String targetKind, @Nullable UUID targetGroupId) {
        if ("GROUP".equals(targetKind)) {
            return jdbc.sql("""
                    SELECT COUNT(*) FROM fulfillment.couriers c
                     JOIN fulfillment.courier_group_members m
                       ON m.tenant_id = c.tenant_id AND m.courier_id = c.id
                     WHERE c.tenant_id = :tenantId AND c.status = 'ACTIVE' AND m.group_id = :groupId
                    """)
                    .param("tenantId", tenantId)
                    .param("groupId", targetGroupId)
                    .query(Integer.class)
                    .single();
        }
        return jdbc.sql("SELECT COUNT(*) FROM fulfillment.couriers WHERE tenant_id = :tenantId AND status = 'ACTIVE'")
                .param("tenantId", tenantId)
                .query(Integer.class)
                .single();
    }

    /** Records a successful send: status SENT, the resolved recipient count, and when. */
    public boolean recordSent(UUID tenantId, UUID id, int recipientCount, Instant now) {
        return jdbc.sql("""
                UPDATE marketing.courier_broadcasts
                   SET status = 'SENT', recipient_count = :recipientCount, sent_at = :now,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'DRAFT'
                """)
                        .param("tenantId", tenantId)
                        .param("id", id)
                        .param("recipientCount", recipientCount)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** Records a refused send: status FAILED and why, so a dispatcher who tried sees the reason. */
    public boolean recordFailed(UUID tenantId, UUID id, String refusalReason, Instant now) {
        return jdbc.sql("""
                UPDATE marketing.courier_broadcasts
                   SET status = 'FAILED', refusal_reason = :reason,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'DRAFT'
                """)
                        .param("tenantId", tenantId)
                        .param("id", id)
                        .param("reason", refusalReason)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    private static CourierBroadcastRow toRow(ResultSet row, int number) throws java.sql.SQLException {
        return new CourierBroadcastRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getString("channel"),
                row.getString("target_kind"),
                row.getObject("target_group_id", UUID.class),
                row.getString("message"),
                row.getString("status"),
                row.getInt("recipient_count"),
                row.getString("refusal_reason"),
                row.getObject("created_by", UUID.class),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant(),
                instant(row.getObject("sent_at", OffsetDateTime.class)),
                row.getInt("version"));
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record CourierBroadcastRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String channel,
            String targetKind,
            @Nullable UUID targetGroupId,
            String message,
            String status,
            int recipientCount,
            @Nullable String refusalReason,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            @Nullable Instant sentAt,
            int version) {}
}
