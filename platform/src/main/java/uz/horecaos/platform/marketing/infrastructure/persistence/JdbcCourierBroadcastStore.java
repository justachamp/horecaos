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
            String templateKey,
            String variablesJson,
            UUID createdBy,
            Instant now) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("tenantId", tenantId);
        params.put("brandId", brandId);
        params.put("targetKind", targetKind);
        params.put("targetGroupId", targetGroupId);
        params.put("templateKey", templateKey);
        params.put("variables", variablesJson);
        params.put("createdBy", createdBy);
        params.put("now", utc(now));

        jdbc.sql("""
                INSERT INTO marketing.courier_broadcasts (
                    id, tenant_id, brand_id, target_kind, target_group_id, template_key, variables,
                    created_by, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :targetKind, :targetGroupId, :templateKey,
                    CAST(:variables AS jsonb), :createdBy, :now, :now)
                """).params(params).update();
    }

    private static final String COLUMNS = """
            id, tenant_id, brand_id, channel, target_kind, target_group_id, message, template_key,
            variables::text AS variables, status, recipient_count, refusal_reason, created_by, created_at,
            updated_at, sent_at, version
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

    /** The couriers a target resolves to right now, active ones only, with their non-personal reference. */
    public List<CourierTarget> targetCouriers(UUID tenantId, String targetKind, @Nullable UUID targetGroupId) {
        if ("GROUP".equals(targetKind)) {
            return jdbc.sql("""
                    SELECT c.id, c.display_reference FROM fulfillment.couriers c
                     JOIN fulfillment.courier_group_members m
                       ON m.tenant_id = c.tenant_id AND m.courier_id = c.id
                     WHERE c.tenant_id = :tenantId AND c.status = 'ACTIVE' AND m.group_id = :groupId
                     ORDER BY c.display_reference
                    """)
                    .param("tenantId", tenantId)
                    .param("groupId", targetGroupId)
                    .query((row, number) ->
                            new CourierTarget(row.getObject("id", UUID.class), row.getString("display_reference")))
                    .list();
        }
        return jdbc.sql("""
                SELECT id, display_reference FROM fulfillment.couriers
                 WHERE tenant_id = :tenantId AND status = 'ACTIVE'
                 ORDER BY display_reference
                """)
                .param("tenantId", tenantId)
                .query((row, number) ->
                        new CourierTarget(row.getObject("id", UUID.class), row.getString("display_reference")))
                .list();
    }

    /** The identity-provider subject each of these couriers signs in as (V0040), found by id within the tenant. */
    public Map<UUID, String> principalSubjects(UUID tenantId, java.util.Collection<UUID> courierIds) {
        if (courierIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> subjects = new HashMap<>();
        jdbc.sql("""
                SELECT id, principal_subject FROM fulfillment.couriers
                 WHERE tenant_id = :tenantId AND id IN (:ids)
                """)
                .param("tenantId", tenantId)
                .param("ids", courierIds)
                .query((row, number) -> Map.entry(row.getObject("id", UUID.class), row.getString("principal_subject")))
                .list()
                .forEach(entry -> subjects.put(entry.getKey(), entry.getValue()));
        return subjects;
    }

    /**
     * One ledger row. A second send of the same broadcast finds the first's row and adds nothing, so a
     * replay cannot show a courier twice.
     */
    public void insertRecipient(
            UUID tenantId,
            UUID broadcastId,
            UUID courierId,
            String courierReference,
            String state,
            @Nullable UUID notificationId,
            Instant now) {
        jdbc.sql("""
                INSERT INTO marketing.courier_broadcast_recipients (
                    id, tenant_id, broadcast_id, courier_id, courier_reference, state, notification_id, created_at)
                VALUES (:id, :tenantId, :broadcastId, :courierId, :reference, :state, :notificationId, :now)
                ON CONFLICT (tenant_id, broadcast_id, courier_id) DO NOTHING
                """)
                .param("id", uz.horecaos.platform.configuration.Ids.newId())
                .param("tenantId", tenantId)
                .param("broadcastId", broadcastId)
                .param("courierId", courierId)
                .param("reference", courierReference)
                .param("state", state)
                .param("notificationId", notificationId)
                .param("now", utc(now))
                .update();
    }

    /** Who a broadcast addressed and who had nothing to be addressed on, by the couriers' reference. */
    public List<RecipientRow> recipients(UUID tenantId, UUID broadcastId) {
        return jdbc.sql("""
                SELECT courier_id, courier_reference, state, notification_id
                  FROM marketing.courier_broadcast_recipients
                 WHERE tenant_id = :tenantId AND broadcast_id = :broadcastId
                 ORDER BY courier_reference
                """)
                .param("tenantId", tenantId)
                .param("broadcastId", broadcastId)
                .query((row, number) -> new RecipientRow(
                        row.getObject("courier_id", UUID.class),
                        row.getString("courier_reference"),
                        row.getString("state"),
                        row.getObject("notification_id", UUID.class)))
                .list();
    }

    private static final tools.jackson.databind.ObjectMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private static Map<String, String> variables(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return JSON.readValue(json, new tools.jackson.core.type.TypeReference<Map<String, String>>() {});
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
                row.getString("template_key"),
                variables(row.getString("variables")),
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
            @Nullable String message,
            @Nullable String templateKey,
            Map<String, String> variables,
            String status,
            int recipientCount,
            @Nullable String refusalReason,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            @Nullable Instant sentAt,
            int version) {}

    /** A courier a target resolves to: the id and the reference a screen may show. */
    public record CourierTarget(UUID courierId, String reference) {}

    /** One courier a broadcast addressed, or could not: the id, the reference, QUEUED or NO_CONTACT, and the intent. */
    public record RecipientRow(
            UUID courierId,
            String courierReference,
            String state,
            @Nullable UUID notificationId) {}
}
