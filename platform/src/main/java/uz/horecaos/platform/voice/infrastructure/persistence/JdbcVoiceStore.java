package uz.horecaos.platform.voice.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * JDBC persistence for {@code voice.*} (ADR 0064). Explicit SQL per this
 * repository's convention — no ORM.
 */
@Repository
public class JdbcVoiceStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcVoiceStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------- presence

    /**
     * Sets one operator's presence at one location, creating the row on first
     * use. An upsert rather than an insert-then-update: the ADR 0064 model has
     * no separate "open" step the way a courier duty session does, so the very
     * first {@code ONLINE} a new hire ever sends is exactly as ordinary as
     * their hundredth.
     */
    public void upsertPresence(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String operatorPrincipalId,
            String state,
            @Nullable String reason,
            Instant changedAt) {
        jdbc.sql("""
                INSERT INTO voice.operator_presence
                    (id, tenant_id, brand_id, location_id, operator_principal_id, state, reason, changed_at, version)
                VALUES (:id, :tenantId, :brandId, :locationId, :operatorId, :state, :reason, :changedAt, 1)
                ON CONFLICT (tenant_id, location_id, operator_principal_id) DO UPDATE SET
                    brand_id = excluded.brand_id,
                    state = excluded.state,
                    reason = excluded.reason,
                    changed_at = excluded.changed_at,
                    version = voice.operator_presence.version + 1
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("operatorId", operatorPrincipalId)
                .param("state", state)
                .param("reason", reason)
                .param("changedAt", utc(changedAt))
                .update();
    }

    public Optional<PresenceRow> presence(UUID tenantId, UUID locationId, String operatorPrincipalId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, location_id, operator_principal_id, state, reason, changed_at, version
                FROM voice.operator_presence
                WHERE tenant_id = :tenantId AND location_id = :locationId AND operator_principal_id = :operatorId
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("operatorId", operatorPrincipalId)
                .query(JdbcVoiceStore::toPresenceRow)
                .optional();
    }

    /** The whole roster at one location, any state — a supervisor's view, and the ADR 0064 roster snapshot's source. */
    public List<PresenceRow> presenceForLocation(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, location_id, operator_principal_id, state, reason, changed_at, version
                FROM voice.operator_presence
                WHERE tenant_id = :tenantId AND location_id = :locationId
                ORDER BY operator_principal_id
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(JdbcVoiceStore::toPresenceRow)
                .list();
    }

    private static PresenceRow toPresenceRow(java.sql.ResultSet row, int num) throws java.sql.SQLException {
        return new PresenceRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                row.getString("operator_principal_id"),
                row.getString("state"),
                row.getString("reason"),
                row.getObject("changed_at", OffsetDateTime.class).toInstant(),
                row.getInt("version"));
    }

    public record PresenceRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String operatorPrincipalId,
            String state,
            @Nullable String reason,
            Instant changedAt,
            int version) {}

    // ---------------------------------------------------------- call events

    public void insertCallEvent(NewCallEvent event) {
        jdbc.sql("""
                INSERT INTO voice.call_events
                    (id, tenant_id, brand_id, location_id, installation_id, binding_id, provider_call_id,
                     event_type, direction, line_did, caller_number_encrypted, caller_number_masked,
                     resolved_customer_account_id, operator_principal_id, duration_seconds,
                     transfer_target_line, online_operator_roster, occurred_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :installationId, :bindingId, :providerCallId,
                        :eventType, :direction, :lineDid, :callerNumberEncrypted, :callerNumberMasked,
                        :resolvedCustomerAccountId, :operatorPrincipalId, :durationSeconds,
                        :transferTargetLine, cast(:roster AS jsonb), :occurredAt)
                """)
                .param("id", event.id())
                .param("tenantId", event.tenantId())
                .param("brandId", event.brandId())
                .param("locationId", event.locationId())
                .param("installationId", event.installationId())
                .param("bindingId", event.bindingId())
                .param("providerCallId", event.providerCallId())
                .param("eventType", event.eventType())
                .param("direction", event.direction())
                .param("lineDid", event.lineDid())
                .param("callerNumberEncrypted", event.callerNumberEncrypted())
                .param("callerNumberMasked", event.callerNumberMasked())
                .param("resolvedCustomerAccountId", event.resolvedCustomerAccountId())
                .param("operatorPrincipalId", event.operatorPrincipalId())
                .param("durationSeconds", event.durationSeconds())
                .param("transferTargetLine", event.transferTargetLine())
                .param("roster", toJson(event.onlineOperatorRoster()))
                .param("occurredAt", utc(event.occurredAt()))
                .update();
    }

    /**
     * The earliest OFFERED/ANSWERED timestamp already recorded for this call,
     * so an ENDED event can compute {@code duration_seconds} without the
     * adapter having to remember call state itself across a reconnect.
     */
    public Optional<Instant> earliestEventAt(UUID tenantId, UUID installationId, String providerCallId) {
        // ORDER BY ... LIMIT 1 rather than MIN(occurred_at): an aggregate
        // always returns exactly one row, NULL when nothing matches, which
        // would make this row mapper handle a null occurred_at that the
        // column's own NOT NULL constraint says can never occur on an actual
        // row. LIMIT 1 instead returns zero rows for "nothing matches",
        // which optional() turns into Optional.empty() without the mapper
        // ever seeing a null.
        return jdbc.sql("""
                SELECT occurred_at
                FROM voice.call_events
                WHERE tenant_id = :tenantId AND installation_id = :installationId
                  AND provider_call_id = :providerCallId AND event_type IN ('OFFERED', 'ANSWERED')
                ORDER BY occurred_at ASC
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .param("providerCallId", providerCallId)
                .query((row, num) ->
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant())
                .optional();
    }

    /**
     * The operator who claimed this call's screen-pop card, when the provider
     * itself did not report who answered. Neither built adapter has an
     * extension-to-operator directory to draw on, so this is the one place
     * this build can genuinely attribute a call to an operator: the same
     * authenticated person who called {@code ScreenPopController.acknowledge}.
     */
    public Optional<String> acknowledgedOperator(UUID tenantId, UUID installationId, String providerCallId) {
        return jdbc.sql("""
                SELECT s.acknowledged_by_principal_id
                FROM voice.screen_pop_state s
                JOIN voice.call_events c ON c.id = s.offered_call_event_id
                WHERE c.tenant_id = :tenantId AND c.installation_id = :installationId
                  AND c.provider_call_id = :providerCallId AND c.event_type = 'OFFERED'
                  AND s.acknowledged_by_principal_id IS NOT NULL
                ORDER BY c.occurred_at DESC
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .param("providerCallId", providerCallId)
                .query(String.class)
                .optional();
    }

    /**
     * The one path back to a caller's actual number, for the ADR 0064
     * create-customer prefill on an unknown caller. Never used for a known
     * caller — {@code ScreenPopQueryService} refuses that before this is
     * even called, since a known customer's number is revealed through their
     * own ADR 0029 reveal-gated record instead.
     */
    public Optional<CallerNumberRow> callerNumberForReveal(UUID tenantId, UUID offeredCallEventId) {
        return jdbc.sql("""
                SELECT caller_number_encrypted, resolved_customer_account_id
                FROM voice.call_events
                WHERE tenant_id = :tenantId AND id = :callEventId AND event_type = 'OFFERED'
                """)
                .param("tenantId", tenantId)
                .param("callEventId", offeredCallEventId)
                .query((row, num) -> new CallerNumberRow(
                        row.getString("caller_number_encrypted"),
                        row.getObject("resolved_customer_account_id", UUID.class)))
                .optional();
    }

    public record CallerNumberRow(
            @Nullable String callerNumberEncrypted,
            @Nullable UUID resolvedCustomerAccountId) {}

    public List<CallLogRow> recentCalls(UUID tenantId, UUID locationId, int limit) {
        return jdbc.sql("""
                SELECT id, provider_call_id, event_type, direction, line_did, operator_principal_id,
                       duration_seconds, occurred_at
                FROM voice.call_events
                WHERE tenant_id = :tenantId AND location_id = :locationId
                ORDER BY occurred_at DESC
                LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("limit", limit)
                .query((row, num) -> new CallLogRow(
                        row.getObject("id", UUID.class),
                        row.getString("provider_call_id"),
                        row.getString("event_type"),
                        row.getString("direction"),
                        row.getString("line_did"),
                        row.getString("operator_principal_id"),
                        row.getObject("duration_seconds", Integer.class),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    public record NewCallEvent(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID installationId,
            @Nullable UUID bindingId,
            String providerCallId,
            String eventType,
            String direction,
            @Nullable String lineDid,
            @Nullable String callerNumberEncrypted,
            @Nullable String callerNumberMasked,
            @Nullable UUID resolvedCustomerAccountId,
            @Nullable String operatorPrincipalId,
            @Nullable Integer durationSeconds,
            @Nullable String transferTargetLine,
            List<RosterEntry> onlineOperatorRoster,
            Instant occurredAt) {}

    /** The shape frozen into {@code online_operator_roster}. Its own type rather than the api port's, so this store never imports voice.api. */
    public record RosterEntry(String operatorPrincipalId, String state) {}

    public record CallLogRow(
            UUID id,
            String providerCallId,
            String eventType,
            String direction,
            @Nullable String lineDid,
            @Nullable String operatorPrincipalId,
            @Nullable Integer durationSeconds,
            Instant occurredAt) {}

    // -------------------------------------------------------------- screen-pop

    public void openScreenPop(UUID tenantId, UUID locationId, UUID offeredCallEventId) {
        jdbc.sql("""
                INSERT INTO voice.screen_pop_state (offered_call_event_id, tenant_id, location_id)
                VALUES (:callEventId, :tenantId, :locationId)
                """)
                .param("callEventId", offeredCallEventId)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .update();
    }

    /** Marks every still-open card for this provider call cleared, on ANSWERED-elsewhere, ENDED, or MISSED. */
    public void clearScreenPopForCall(UUID tenantId, UUID installationId, String providerCallId, Instant clearedAt) {
        jdbc.sql("""
                UPDATE voice.screen_pop_state s
                SET cleared_at = :clearedAt
                WHERE s.tenant_id = :tenantId AND s.cleared_at IS NULL
                  AND s.offered_call_event_id IN (
                      SELECT id FROM voice.call_events
                      WHERE tenant_id = :tenantId AND installation_id = :installationId
                        AND provider_call_id = :providerCallId AND event_type = 'OFFERED')
                """)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .param("providerCallId", providerCallId)
                .param("clearedAt", utc(clearedAt))
                .update();
    }

    public boolean acknowledge(UUID tenantId, UUID offeredCallEventId, String operatorPrincipalId, Instant now) {
        return jdbc.sql("""
                UPDATE voice.screen_pop_state
                SET acknowledged_by_principal_id = :operatorId, acknowledged_at = :now
                WHERE tenant_id = :tenantId AND offered_call_event_id = :callEventId
                  AND cleared_at IS NULL AND acknowledged_by_principal_id IS NULL
                """)
                        .param("operatorId", operatorPrincipalId)
                        .param("now", utc(now))
                        .param("tenantId", tenantId)
                        .param("callEventId", offeredCallEventId)
                        .update()
                == 1;
    }

    /** The one open, unacknowledged-or-just-acknowledged card for a location — what the poll returns. */
    public Optional<ScreenPopRow> currentScreenPop(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT c.id AS call_event_id, c.provider_call_id, c.line_did, c.caller_number_masked,
                       c.resolved_customer_account_id, c.occurred_at,
                       s.acknowledged_by_principal_id, s.acknowledged_at
                FROM voice.screen_pop_state s
                JOIN voice.call_events c ON c.id = s.offered_call_event_id
                WHERE s.tenant_id = :tenantId AND s.location_id = :locationId AND s.cleared_at IS NULL
                ORDER BY c.occurred_at DESC
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query((row, num) -> new ScreenPopRow(
                        row.getObject("call_event_id", UUID.class),
                        row.getString("provider_call_id"),
                        row.getString("line_did"),
                        row.getString("caller_number_masked"),
                        row.getObject("resolved_customer_account_id", UUID.class),
                        row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        row.getString("acknowledged_by_principal_id"),
                        Optional.ofNullable(row.getObject("acknowledged_at", OffsetDateTime.class))
                                .map(OffsetDateTime::toInstant)
                                .orElse(null)))
                .optional();
    }

    public record ScreenPopRow(
            UUID callEventId,
            String providerCallId,
            @Nullable String lineDid,
            @Nullable String callerNumberMasked,
            @Nullable UUID resolvedCustomerAccountId,
            Instant occurredAt,
            @Nullable String acknowledgedByPrincipalId,
            @Nullable Instant acknowledgedAt) {}

    private String toJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
