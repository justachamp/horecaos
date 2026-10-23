package uz.horecaos.platform.courier.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * A courier's invitations to set their own password (ADR 0178, V0657).
 *
 * <p>Every step after the insert is a conditional UPDATE naming the status it expects, and the row count
 * decides: the accept spends a link with {@code WHERE status = 'SENT' AND token_hash = :hash}, which only
 * one of two concurrent accepts can match, and it does so before either has asked the identity provider for
 * anything. No row lock is held across a remote call: the pool is shared by every module.
 */
@Repository
public class JdbcCourierAccessStore {

    private static final String COLUMNS = """
            id, tenant_id, courier_id, kind, status, locale, token_hash, expires_at, attempts, next_attempt_at,
            last_error_code, requested_by, requested_at, sent_at, opened_at, accepted_at, voided_at
            """;

    private final JdbcClient jdbc;

    public JdbcCourierAccessStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Voids the courier's live invitation, if any, so the link already texted stops working. */
    public int voidLive(UUID tenantId, UUID courierId, Instant now) {
        return jdbc.sql("""
                        UPDATE fulfillment.courier_access_invitations
                           SET status = 'VOIDED', token_hash = NULL, expires_at = NULL, next_attempt_at = NULL,
                               voided_at = :now, version = version + 1
                         WHERE tenant_id = :tenantId AND courier_id = :courierId AND status IN ('QUEUED', 'SENT')
                        """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .param("now", utc(now))
                .update();
    }

    public void queue(
            UUID id, UUID tenantId, UUID courierId, String kind, String locale, String requestedBy, Instant now) {
        jdbc.sql("""
                        INSERT INTO fulfillment.courier_access_invitations (
                            id, tenant_id, courier_id, kind, status, locale, next_attempt_at, requested_by, requested_at)
                        VALUES (:id, :tenantId, :courierId, :kind, 'QUEUED', :locale, :now, :requestedBy, :now)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .param("kind", kind)
                .param("locale", locale)
                .param("requestedBy", requestedBy)
                .param("now", utc(now))
                .update();
    }

    /** The courier's newest invitation of any status, for the detail pane. */
    public Optional<Row> latest(UUID tenantId, UUID courierId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                          FROM fulfillment.courier_access_invitations
                         WHERE tenant_id = :tenantId AND courier_id = :courierId
                         ORDER BY requested_at DESC, id DESC
                         LIMIT 1
                        """)
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .query(JdbcCourierAccessStore::map)
                .optional();
    }

    public Optional<Row> byTokenHash(String tokenHash) {
        return jdbc.sql("SELECT " + COLUMNS + """
                          FROM fulfillment.courier_access_invitations
                         WHERE token_hash = :hash
                        """)
                .param("hash", tokenHash)
                .query(JdbcCourierAccessStore::map)
                .optional();
    }

    /**
     * Claims due invitations for one send each, pushing the next attempt a lease into the future and
     * counting the attempt. One statement, so the claim is atomic without a surrounding transaction;
     * {@code SKIP LOCKED} lets two replicas split the queue instead of texting twice.
     */
    public List<Row> claimDue(Instant now, Duration lease, int batchSize) {
        return jdbc.sql("UPDATE fulfillment.courier_access_invitations r"
                        + " SET next_attempt_at = :leaseEnd, attempts = attempts + 1"
                        + " FROM (SELECT id FROM fulfillment.courier_access_invitations"
                        + "        WHERE status = 'QUEUED' AND next_attempt_at <= :now"
                        + "        ORDER BY next_attempt_at LIMIT :batchSize FOR UPDATE SKIP LOCKED) due"
                        + " WHERE r.id = due.id RETURNING r.*")
                .param("now", utc(now))
                .param("leaseEnd", utc(now.plus(lease)))
                .param("batchSize", batchSize)
                .query(JdbcCourierAccessStore::map)
                .list();
    }

    public boolean markSent(UUID id, int attempt, String tokenHash, Instant expiresAt, Instant now) {
        return jdbc.sql("""
                        UPDATE fulfillment.courier_access_invitations
                           SET status = 'SENT', token_hash = :hash, expires_at = :expiresAt, sent_at = :now,
                               next_attempt_at = NULL, last_error_code = NULL, version = version + 1
                         WHERE id = :id AND status = 'QUEUED' AND attempts = :attempt
                        """)
                        .param("id", id)
                        .param("attempt", attempt)
                        .param("hash", tokenHash)
                        .param("expiresAt", utc(expiresAt))
                        .param("now", utc(now))
                        .update()
                > 0;
    }

    /**
     * Leaves it queued for another attempt.
     *
     * @param countsAsAttempt false when nothing was attempted -- no gateway connected -- so a deployment
     *        waiting for its gateway does not use up the attempts a real outage would need
     */
    public void markRetry(UUID id, int attempt, Instant nextAttemptAt, String errorCode, boolean countsAsAttempt) {
        jdbc.sql("""
                UPDATE fulfillment.courier_access_invitations
                   SET next_attempt_at = :next, last_error_code = :code,
                       attempts = CASE WHEN :counts THEN attempts ELSE attempts - 1 END,
                       version = version + 1
                 WHERE id = :id AND status = 'QUEUED' AND attempts = :attempt
                """)
                .param("id", id)
                .param("attempt", attempt)
                .param("next", utc(nextAttemptAt))
                .param("code", errorCode)
                .param("counts", countsAsAttempt)
                .update();
    }

    public void markFailed(UUID id, int attempt, String errorCode) {
        jdbc.sql("""
                UPDATE fulfillment.courier_access_invitations
                   SET status = 'FAILED', next_attempt_at = NULL, last_error_code = :code, version = version + 1
                 WHERE id = :id AND status = 'QUEUED' AND attempts = :attempt
                """)
                .param("id", id)
                .param("attempt", attempt)
                .param("code", errorCode)
                .update();
    }

    /** The first time the link was opened; later opens change nothing. */
    public void markOpened(UUID id, String tokenHash, Instant now) {
        jdbc.sql("""
                UPDATE fulfillment.courier_access_invitations
                   SET opened_at = :now, version = version + 1
                 WHERE id = :id AND status = 'SENT' AND token_hash = :hash AND opened_at IS NULL
                """)
                .param("id", id)
                .param("hash", tokenHash)
                .param("now", utc(now))
                .update();
    }

    /** Spends the link that was presented: its hash goes, so that token can never be used again. */
    public boolean markAccepted(UUID id, String tokenHash, Instant now) {
        return jdbc.sql("""
                        UPDATE fulfillment.courier_access_invitations
                           SET status = 'ACCEPTED', token_hash = NULL, expires_at = NULL, accepted_at = :now,
                               opened_at = COALESCE(opened_at, :now), version = version + 1
                         WHERE id = :id AND status = 'SENT' AND token_hash = :hash
                        """)
                        .param("id", id)
                        .param("hash", tokenHash)
                        .param("now", utc(now))
                        .update()
                > 0;
    }

    /** Puts back a link that was spent for a password the realm then refused. */
    public boolean restoreSent(UUID id, String tokenHash, Instant expiresAt, Instant acceptedAt) {
        return jdbc.sql("""
                        UPDATE fulfillment.courier_access_invitations
                           SET status = 'SENT', token_hash = :hash, expires_at = :expiresAt,
                               accepted_at = NULL, version = version + 1
                         WHERE id = :id AND status = 'ACCEPTED' AND accepted_at = :acceptedAt
                        """)
                        .param("id", id)
                        .param("hash", tokenHash)
                        .param("expiresAt", utc(expiresAt))
                        .param("acceptedAt", utc(acceptedAt))
                        .update()
                > 0;
    }

    private static Row map(ResultSet row, int number) throws SQLException {
        return new Row(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("courier_id", UUID.class),
                row.getString("kind"),
                row.getString("status"),
                row.getString("locale"),
                row.getString("token_hash"),
                instant(row, "expires_at"),
                row.getInt("attempts"),
                instant(row, "next_attempt_at"),
                row.getString("last_error_code"),
                row.getString("requested_by"),
                Objects.requireNonNull(instant(row, "requested_at")),
                instant(row, "sent_at"),
                instant(row, "opened_at"),
                instant(row, "accepted_at"),
                instant(row, "voided_at"));
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** One invitation. {@code tokenHash} is a SHA-256, never a token, and there is no phone number here at all. */
    public record Row(
            UUID id,
            UUID tenantId,
            UUID courierId,
            String kind,
            String status,
            String locale,
            @Nullable String tokenHash,
            @Nullable Instant expiresAt,
            int attempts,
            @Nullable Instant nextAttemptAt,
            @Nullable String lastErrorCode,
            String requestedBy,
            Instant requestedAt,
            @Nullable Instant sentAt,
            @Nullable Instant openedAt,
            @Nullable Instant acceptedAt,
            @Nullable Instant voidedAt) {}
}
