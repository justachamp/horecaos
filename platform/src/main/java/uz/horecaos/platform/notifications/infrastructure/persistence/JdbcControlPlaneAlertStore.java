package uz.horecaos.platform.notifications.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.notifications.api.ControlPlaneAlert;

/**
 * ADR 0085: control-plane alerts as incidents.
 *
 * <p>Raising is an upsert against the one live row per event class and
 * subject, so a watcher that fires every minute about the same metric makes
 * one incident with a rising count, not a page of duplicates.
 */
@Repository
public class JdbcControlPlaneAlertStore {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcControlPlaneAlertStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void raise(ControlPlaneAlert alert) {
        jdbc.sql("""
                INSERT INTO notifications.control_plane_alerts
                    (id, event_class, subject_type, subject_id, variables, first_raised_at, last_raised_at)
                VALUES (:id, :eventClass, :subjectType, :subjectId, CAST(:variables AS jsonb), :at, :at)
                ON CONFLICT (event_class, subject_type, subject_id) WHERE status <> 'RESOLVED'
                DO UPDATE SET last_raised_at = EXCLUDED.last_raised_at,
                              occurrences = control_plane_alerts.occurrences + 1,
                              variables = EXCLUDED.variables
                """)
                .param("id", Ids.newId())
                .param("eventClass", alert.eventClass())
                .param("subjectType", alert.subjectType())
                .param("subjectId", alert.subjectId())
                .param("variables", json.writeValueAsString(alert.variables()))
                .param("at", utc(alert.occurredAt()))
                .update();
    }

    /** Live incidents first, then the most recently raised, at most {@code limit}. */
    public List<StoredAlert> list(boolean includeResolved, int limit) {
        return jdbc.sql(SELECT + """
                 WHERE (:includeResolved OR status <> 'RESOLVED')
                 ORDER BY (status = 'RESOLVED'), last_raised_at DESC
                 LIMIT :limit
                """)
                .param("includeResolved", includeResolved)
                .param("limit", limit)
                .query(this::map)
                .list();
    }

    public Optional<StoredAlert> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /** @return false when the alert is not open (already acknowledged or resolved) */
    public boolean acknowledge(UUID id, String by, Instant at) {
        return jdbc.sql("""
                UPDATE notifications.control_plane_alerts
                   SET status = 'ACKNOWLEDGED', acknowledged_by = :by, acknowledged_at = :at
                 WHERE id = :id AND status = 'OPEN'
                """)
                        .param("id", id)
                        .param("by", by)
                        .param("at", utc(at))
                        .update()
                == 1;
    }

    /** @return false when the alert was already resolved */
    public boolean resolve(UUID id, String by, String note, Instant at) {
        return jdbc.sql("""
                UPDATE notifications.control_plane_alerts
                   SET status = 'RESOLVED', resolved_by = :by, resolved_at = :at, resolution_note = :note
                 WHERE id = :id AND status <> 'RESOLVED'
                """)
                        .param("id", id)
                        .param("by", by)
                        .param("note", note)
                        .param("at", utc(at))
                        .update()
                == 1;
    }

    private static final String SELECT = """
            SELECT id, event_class, subject_type, subject_id, variables, first_raised_at, last_raised_at,
                   occurrences, status, acknowledged_by, acknowledged_at, resolved_by, resolved_at, resolution_note
              FROM notifications.control_plane_alerts
            """;

    private StoredAlert map(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new StoredAlert(
                row.getObject("id", UUID.class),
                row.getString("event_class"),
                row.getString("subject_type"),
                row.getString("subject_id"),
                json.readValue(row.getString("variables"), new TypeReference<Map<String, String>>() {}),
                instant(row, "first_raised_at"),
                instant(row, "last_raised_at"),
                row.getInt("occurrences"),
                row.getString("status"),
                row.getString("acknowledged_by"),
                nullableInstant(row, "acknowledged_at"),
                row.getString("resolved_by"),
                nullableInstant(row, "resolved_at"),
                row.getString("resolution_note"));
    }

    private static Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static @Nullable Instant nullableInstant(java.sql.ResultSet row, String column)
            throws java.sql.SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** One incident as stored. */
    public record StoredAlert(
            UUID id,
            String eventClass,
            String subjectType,
            String subjectId,
            Map<String, String> variables,
            Instant firstRaisedAt,
            Instant lastRaisedAt,
            int occurrences,
            String status,
            @Nullable String acknowledgedBy,
            @Nullable Instant acknowledgedAt,
            @Nullable String resolvedBy,
            @Nullable Instant resolvedAt,
            @Nullable String resolutionNote) {}
}
