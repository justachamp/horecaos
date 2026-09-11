package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.configuration.Ids;

/**
 * {@code tenant.owner_invitation_events} (ADR 0100, V0215): what has happened
 * to one owner's invitation, in order.
 *
 * <p>Append-only, and not by convention: the application role holds
 * {@code SELECT, INSERT} on this table and nothing else, so the class has no
 * update or delete to offer. A resend rewrites the invitation row and leaves
 * this one alone, which is the whole reason it exists.
 *
 * <p>No column here holds an address. {@code actorReference} is a Keycloak
 * subject or a job name.
 */
@Repository
public class JdbcOwnerInvitationEventStore {

    /** Onboarding, or an operator sending a tenant's first invitation, queued it. */
    public static final String QUEUED = "QUEUED";

    /** An operator requeued it with a new link and said why. */
    public static final String RESENT = "RESENT";

    /** The relay emailed it on this attempt. */
    public static final String SENT = "SENT";

    /** An attempt did not deliver and will be made again. */
    public static final String SEND_DEFERRED = "SEND_DEFERRED";

    /** Sending was given up on; a person decides what happens next. */
    public static final String SEND_FAILED = "SEND_FAILED";

    /** The owner opened the link. Only the first open is recorded. */
    public static final String OPENED = "OPENED";

    /** The owner set a name and a password. */
    public static final String ACCEPTED = "ACCEPTED";

    /** The account already had a password when the relay looked. */
    public static final String NOT_NEEDED = "NOT_NEEDED";

    private final JdbcClient jdbc;

    public JdbcOwnerInvitationEventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Appends one event. The identifier is minted here; nothing else ever writes this row. */
    public void append(Entry entry) {
        jdbc.sql("""
                        INSERT INTO tenant.owner_invitation_events (
                            id, tenant_id, invitation_id, event_type, attempt, locale, outcome_code,
                            actor_type, actor_reference, reason, occurred_at)
                        VALUES (:id, :tenantId, :invitationId, :type, :attempt, :locale, :outcomeCode,
                            :actorType, :actorReference, :reason, :occurredAt)
                        """)
                .param("id", Ids.newId())
                .param("tenantId", entry.tenantId())
                .param("invitationId", entry.invitationId())
                .param("type", entry.type())
                .param("attempt", entry.attempt())
                .param("locale", entry.locale())
                .param("outcomeCode", entry.outcomeCode())
                .param("actorType", entry.actorType())
                .param("actorReference", entry.actorReference())
                .param("reason", entry.reason())
                .param("occurredAt", OffsetDateTime.ofInstant(entry.occurredAt(), ZoneOffset.UTC))
                .update();
    }

    /**
     * One invitation's history, oldest first.
     *
     * <p>{@code recorded_at} breaks the tie between two events the platform
     * clock stamped with the same instant -- a queue and its first send attempt
     * within one millisecond, or anything at all under a fixed clock in a test.
     */
    public List<Row> timeline(UUID tenantId, UUID invitationId) {
        return jdbc.sql("""
                        SELECT id, event_type, attempt, locale, outcome_code, actor_type,
                               actor_reference, reason, occurred_at
                          FROM tenant.owner_invitation_events
                         WHERE tenant_id = :tenantId AND invitation_id = :invitationId
                         ORDER BY occurred_at, recorded_at, id
                        """)
                .param("tenantId", tenantId)
                .param("invitationId", invitationId)
                .query(JdbcOwnerInvitationEventStore::map)
                .list();
    }

    private static Row map(ResultSet row, int number) throws SQLException {
        OffsetDateTime occurredAt = Objects.requireNonNull(
                row.getObject("occurred_at", OffsetDateTime.class), "An event always has an instant");
        return new Row(
                Objects.requireNonNull(row.getObject("id", UUID.class)),
                row.getString("event_type"),
                row.getInt("attempt"),
                row.getString("locale"),
                row.getString("outcome_code"),
                row.getString("actor_type"),
                row.getString("actor_reference"),
                row.getString("reason"),
                occurredAt.toInstant());
    }

    /**
     * An event about to be appended.
     *
     * @param attempt the send attempt it belongs to, counted from one; zero on
     *        an event that is not a send attempt
     * @param actorType {@code SYSTEM_JOB}, {@code USER} or {@code OWNER}
     * @param reason an operator's own words; absent on a machine event
     */
    public record Entry(
            UUID tenantId,
            UUID invitationId,
            String type,
            int attempt,
            @Nullable String locale,
            @Nullable String outcomeCode,
            String actorType,
            @Nullable String actorReference,
            @Nullable String reason,
            Instant occurredAt) {}

    /** One event, as the timeline reads it. */
    public record Row(
            UUID id,
            String type,
            int attempt,
            @Nullable String locale,
            @Nullable String outcomeCode,
            String actorType,
            @Nullable String actorReference,
            @Nullable String reason,
            Instant occurredAt) {}
}
