package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code tenant.owner_invitations} (ADR 0097, V0210).
 *
 * <p>Every write after the claim names the attempt it belongs to, so a relay
 * that lost its lease -- a slow mail server, a second replica -- cannot record
 * an outcome over a newer attempt's.
 */
@Repository
public class JdbcOwnerInvitationStore {

    private static final String COLUMNS = """
            id, tenant_id, subject_id, locale, status, token_hash, expires_at, attempts,
            next_attempt_at, last_error_code, queued_at, queued_by, sent_at, opened_at, accepted_at
            """;

    private final JdbcClient jdbc;

    public JdbcOwnerInvitationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Queues an invitation for this owner unless one already exists.
     *
     * @return true when this call created it
     */
    public boolean queueIfAbsent(
            UUID id, UUID tenantId, String subjectId, String locale, String queuedBy, Instant now) {
        return jdbc.sql("""
                        INSERT INTO tenant.owner_invitations (
                            id, tenant_id, subject_id, locale, status, next_attempt_at, queued_at, queued_by)
                        VALUES (:id, :tenantId, :subjectId, :locale, 'QUEUED', :now, :now, :queuedBy)
                        ON CONFLICT (tenant_id, subject_id) DO NOTHING
                        """)
                        .param("id", id)
                        .param("tenantId", tenantId)
                        .param("subjectId", subjectId)
                        .param("locale", locale)
                        .param("now", utc(now))
                        .param("queuedBy", queuedBy)
                        .update()
                > 0;
    }

    /**
     * Puts an invitation back in the queue with no live link: the earlier
     * token's hash is cleared, so the link already sent stops working.
     * Refused once the owner has accepted.
     */
    public boolean requeue(UUID id, String locale, String queuedBy, Instant now) {
        return jdbc.sql("""
                        UPDATE tenant.owner_invitations
                           SET status = 'QUEUED', token_hash = NULL, expires_at = NULL, attempts = 0,
                               next_attempt_at = :now, last_error_code = NULL, queued_at = :now,
                               queued_by = :queuedBy, sent_at = NULL, opened_at = NULL, locale = :locale,
                               version = version + 1
                         WHERE id = :id AND status <> 'ACCEPTED'
                        """)
                        .param("id", id)
                        .param("locale", locale)
                        .param("queuedBy", queuedBy)
                        .param("now", utc(now))
                        .update()
                > 0;
    }

    /** The tenant's most recently queued owner invitation. */
    public Optional<Row> latestFor(UUID tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                          FROM tenant.owner_invitations
                         WHERE tenant_id = :tenantId
                         ORDER BY queued_at DESC, id
                         LIMIT 1
                        """)
                .param("tenantId", tenantId)
                .query(JdbcOwnerInvitationStore::map)
                .optional();
    }

    /** The invitation a link names, locked so an accept and a resend cannot cross. */
    public Optional<Row> byTokenHashForUpdate(String tokenHash) {
        return jdbc.sql("SELECT " + COLUMNS + """
                          FROM tenant.owner_invitations
                         WHERE token_hash = :hash
                         FOR UPDATE
                        """)
                .param("hash", tokenHash)
                .query(JdbcOwnerInvitationStore::map)
                .optional();
    }

    /**
     * Claims due invitations for one send each, pushing their next attempt a
     * lease into the future and counting the attempt. One statement, so the
     * claim is atomic without a surrounding transaction; {@code SKIP LOCKED}
     * lets two replicas split the queue instead of sending twice.
     */
    public List<Row> claimDue(Instant now, Duration lease, int batchSize) {
        return jdbc.sql("UPDATE tenant.owner_invitations i SET next_attempt_at = :leaseEnd, attempts = attempts + 1"
                        + " FROM (SELECT id FROM tenant.owner_invitations"
                        + "        WHERE status = 'QUEUED' AND next_attempt_at <= :now"
                        + "        ORDER BY next_attempt_at LIMIT :batchSize FOR UPDATE SKIP LOCKED) due"
                        + " WHERE i.id = due.id RETURNING i.*")
                .param("now", utc(now))
                .param("leaseEnd", utc(now.plus(lease)))
                .param("batchSize", batchSize)
                .query(JdbcOwnerInvitationStore::map)
                .list();
    }

    public boolean markSent(UUID id, int attempt, String tokenHash, Instant expiresAt, Instant now) {
        return jdbc.sql("""
                        UPDATE tenant.owner_invitations
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
     * Leaves it queued for another attempt at {@code nextAttemptAt}.
     *
     * @param countsAsAttempt false when nothing was attempted -- no mail server
     *        configured -- so a deployment waiting for its mail settings does not
     *        use up the attempts a real outage would need
     */
    public void markRetry(UUID id, int attempt, Instant nextAttemptAt, String errorCode, boolean countsAsAttempt) {
        jdbc.sql("""
                UPDATE tenant.owner_invitations
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
                UPDATE tenant.owner_invitations
                   SET status = 'FAILED', next_attempt_at = NULL, last_error_code = :code, version = version + 1
                 WHERE id = :id AND status = 'QUEUED' AND attempts = :attempt
                """)
                .param("id", id)
                .param("attempt", attempt)
                .param("code", errorCode)
                .update();
    }

    public void markNotNeeded(UUID id, int attempt) {
        jdbc.sql("""
                UPDATE tenant.owner_invitations
                   SET status = 'NOT_NEEDED', next_attempt_at = NULL, last_error_code = NULL, version = version + 1
                 WHERE id = :id AND status = 'QUEUED' AND attempts = :attempt
                """).param("id", id).param("attempt", attempt).update();
    }

    /** The first time the link was opened; later opens change nothing. */
    public void markOpened(UUID id, Instant now) {
        jdbc.sql("""
                UPDATE tenant.owner_invitations
                   SET opened_at = :now, version = version + 1
                 WHERE id = :id AND status = 'SENT' AND opened_at IS NULL
                """).param("id", id).param("now", utc(now)).update();
    }

    /** Spends the link: the hash goes, so the same token can never be used again. */
    public boolean markAccepted(UUID id, Instant now) {
        return jdbc.sql("""
                        UPDATE tenant.owner_invitations
                           SET status = 'ACCEPTED', token_hash = NULL, accepted_at = :now,
                               opened_at = COALESCE(opened_at, :now), version = version + 1
                         WHERE id = :id AND status = 'SENT'
                        """).param("id", id).param("now", utc(now)).update() > 0;
    }

    /**
     * The owner the tenant's most recent onboarding linked, from the owner
     * step's external reference -- the Keycloak subject, never a token (ADR
     * 0009). For a tenant onboarded before invitations existed, this is how
     * the platform finds whom to invite.
     */
    public Optional<String> ownerFromOnboarding(UUID tenantId) {
        return jdbc.sql("""
                        SELECT s.external_reference
                          FROM tenant.onboarding_steps s
                          JOIN tenant.onboarding_runs r ON r.id = s.run_id
                         WHERE r.tenant_id = :tenantId
                           AND s.step_key = 'TENANT_OWNER_LINK_OR_INVITE'
                           AND s.status = 'COMPLETED'
                           AND s.external_reference IS NOT NULL
                         ORDER BY r.started_at DESC
                         LIMIT 1
                        """).param("tenantId", tenantId).query(String.class).optional();
    }

    /** The name an invitation is written in; the tenant's own display name. */
    public String tenantName(UUID tenantId) {
        return jdbc.sql("SELECT display_name FROM tenant.tenants WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .single();
    }

    private static Row map(ResultSet row, int number) throws SQLException {
        return new Row(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getString("subject_id"),
                row.getString("locale"),
                row.getString("status"),
                row.getString("token_hash"),
                instant(row, "expires_at"),
                row.getInt("attempts"),
                instant(row, "next_attempt_at"),
                row.getString("last_error_code"),
                java.util.Objects.requireNonNull(instant(row, "queued_at")),
                row.getString("queued_by"),
                instant(row, "sent_at"),
                instant(row, "opened_at"),
                instant(row, "accepted_at"));
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** One invitation. {@code tokenHash} is a SHA-256, never a token. */
    public record Row(
            UUID id,
            UUID tenantId,
            String subjectId,
            String locale,
            String status,
            @Nullable String tokenHash,
            @Nullable Instant expiresAt,
            int attempts,
            @Nullable Instant nextAttemptAt,
            @Nullable String lastErrorCode,
            Instant queuedAt,
            String queuedBy,
            @Nullable Instant sentAt,
            @Nullable Instant openedAt,
            @Nullable Instant acceptedAt) {}
}
