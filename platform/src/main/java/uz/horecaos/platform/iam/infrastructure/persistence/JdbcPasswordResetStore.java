package uz.horecaos.platform.iam.infrastructure.persistence;

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
 * {@code iam.password_resets} (ADR 0098, V0213).
 *
 * <p>Shaped after {@code JdbcOwnerInvitationStore}, and for the same reason:
 * every write after the claim names the attempt it belongs to, so a relay that
 * lost its lease -- a slow mail server, a second replica -- cannot record an
 * outcome over a newer attempt's.
 *
 * <p>The one structural difference is {@link #request}. An invitation is
 * queued once and resent by a person; a reset is requested by whoever is at
 * the screen, so the insert is an upsert that puts an existing row back in the
 * queue and clears its hash. That single statement is the whole
 * "one live reset per account" rule, and its {@code WHERE} is the whole
 * cooldown: both are decided by the database rather than by a read the caller
 * makes first, because the caller is unauthenticated and two of them can be in
 * flight at once.
 */
@Repository
public class JdbcPasswordResetStore {

    private static final String COLUMNS = """
            id, subject_id, console, locale, status, token_hash, expires_at, attempts,
            next_attempt_at, last_error_code, requested_at, sent_at, opened_at, accepted_at
            """;

    private final JdbcClient jdbc;

    public JdbcPasswordResetStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Queues a reset for this account, replacing whatever was outstanding --
     * unless a link that was delivered moments ago is still live.
     *
     * <p>The replacement is what makes "one live reset per account" true: the
     * hash the older link would be matched against is cleared in the same
     * statement. The exception is the cooldown, and it exists because the
     * endpoint in front of this is unauthenticated: without it, anybody who
     * knows a staff address can post it every few seconds, and each post both
     * kills the link the person is holding and queues another email to them.
     * A row that is already queued, failed, accepted or holding an expired
     * link is requeued as before; only a link sent inside {@code
     * cooldownCutoff} and still live survives a second ask.
     *
     * <p>The test is in the statement's own {@code WHERE} rather than in a
     * read the caller makes first, because two unauthenticated requests can be
     * in flight at once: at READ COMMITTED both would see "no cooldown", and
     * the second would still overwrite the first's link.
     *
     * @return the row's id, or empty when the cooldown left a live link alone
     */
    public Optional<UUID> request(
            UUID id, String subjectId, String console, String locale, Instant now, Instant cooldownCutoff) {
        return jdbc.sql("""
                        INSERT INTO iam.password_resets (
                            id, subject_id, console, locale, status, next_attempt_at, requested_at)
                        VALUES (:id, :subjectId, :console, :locale, 'QUEUED', :now, :now)
                        ON CONFLICT (subject_id) DO UPDATE
                           SET status = 'QUEUED', console = :console, locale = :locale, token_hash = NULL,
                               expires_at = NULL, attempts = 0, next_attempt_at = :now, last_error_code = NULL,
                               requested_at = :now, sent_at = NULL, opened_at = NULL, accepted_at = NULL,
                               version = iam.password_resets.version + 1
                         WHERE iam.password_resets.status <> 'SENT'
                            OR iam.password_resets.expires_at <= :now
                            OR iam.password_resets.sent_at <= :cooldownCutoff
                        RETURNING id
                        """)
                .param("id", id)
                .param("subjectId", subjectId)
                .param("console", console)
                .param("locale", locale)
                .param("now", utc(now))
                .param("cooldownCutoff", utc(cooldownCutoff))
                .query(UUID.class)
                .optional();
    }

    /**
     * The reset a link names.
     *
     * <p>No {@code FOR UPDATE}: nothing that follows this read stays in the
     * same transaction, because what follows it is Keycloak. The single-use
     * rule is enforced instead by {@link #markAccepted}, whose {@code WHERE
     * status = 'SENT'} only one of two concurrent accepts can match.
     */
    public Optional<Row> byTokenHash(String tokenHash) {
        return jdbc.sql("SELECT " + COLUMNS + """
                          FROM iam.password_resets
                         WHERE token_hash = :hash
                        """)
                .param("hash", tokenHash)
                .query(JdbcPasswordResetStore::map)
                .optional();
    }

    /** Where this account's reset stands, for a test and for a support question. */
    public Optional<Row> forSubject(String subjectId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                          FROM iam.password_resets
                         WHERE subject_id = :subjectId
                        """)
                .param("subjectId", subjectId)
                .query(JdbcPasswordResetStore::map)
                .optional();
    }

    /**
     * Claims due resets for one send each, pushing their next attempt a lease
     * into the future and counting the attempt. One statement, so the claim is
     * atomic without a surrounding transaction; {@code SKIP LOCKED} lets two
     * replicas split the queue instead of sending twice.
     */
    public List<Row> claimDue(Instant now, Duration lease, int batchSize) {
        return jdbc.sql("UPDATE iam.password_resets r SET next_attempt_at = :leaseEnd, attempts = attempts + 1"
                        + " FROM (SELECT id FROM iam.password_resets"
                        + "        WHERE status = 'QUEUED' AND next_attempt_at <= :now"
                        + "        ORDER BY next_attempt_at LIMIT :batchSize FOR UPDATE SKIP LOCKED) due"
                        + " WHERE r.id = due.id RETURNING r.*")
                .param("now", utc(now))
                .param("leaseEnd", utc(now.plus(lease)))
                .param("batchSize", batchSize)
                .query(JdbcPasswordResetStore::map)
                .list();
    }

    public boolean markSent(UUID id, int attempt, String tokenHash, Instant expiresAt, Instant now) {
        return jdbc.sql("""
                        UPDATE iam.password_resets
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
                UPDATE iam.password_resets
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
                UPDATE iam.password_resets
                   SET status = 'FAILED', next_attempt_at = NULL, last_error_code = :code, version = version + 1
                 WHERE id = :id AND status = 'QUEUED' AND attempts = :attempt
                """)
                .param("id", id)
                .param("attempt", attempt)
                .param("code", errorCode)
                .update();
    }

    /** The first time the link was opened; later opens change nothing. */
    public void markOpened(UUID id, Instant now) {
        jdbc.sql("""
                UPDATE iam.password_resets
                   SET opened_at = :now, version = version + 1
                 WHERE id = :id AND status = 'SENT' AND opened_at IS NULL
                """).param("id", id).param("now", utc(now)).update();
    }

    /**
     * Spends the link: the hash goes, so the same token can never be used again.
     *
     * <p>{@code WHERE status = 'SENT'} is the accept path's whole concurrency
     * control. Two accepts of one token race here and exactly one wins, and it
     * wins <em>before</em> either has asked Keycloak for anything, which is why
     * the row no longer has to be held under {@code FOR UPDATE} across the
     * calls that follow.
     */
    public boolean markAccepted(UUID id, Instant now) {
        return jdbc.sql("""
                        UPDATE iam.password_resets
                           SET status = 'ACCEPTED', token_hash = NULL, expires_at = NULL, accepted_at = :now,
                               opened_at = COALESCE(opened_at, :now), version = version + 1
                         WHERE id = :id AND status = 'SENT'
                        """).param("id", id).param("now", utc(now)).update() > 0;
    }

    /**
     * Puts back a link that was spent for a password the realm then refused.
     *
     * <p>A policy refusal is the one failure after the spend that provably
     * changed nothing at Keycloak, and somebody who typed a password the realm
     * dislikes has to be able to type another one rather than go and ask for a
     * new link. Guarded by the {@code accepted_at} this spend wrote, so a fresh
     * request that requeued the row in between is not stomped; when the guard
     * matches nothing the link simply stays spent, which is the safe direction.
     *
     * @param acceptedAt the instant {@link #markAccepted} stamped, naming that spend
     */
    public boolean restoreSent(UUID id, String tokenHash, Instant expiresAt, Instant acceptedAt) {
        return jdbc.sql("""
                        UPDATE iam.password_resets
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
                row.getString("subject_id"),
                row.getString("console"),
                row.getString("locale"),
                row.getString("status"),
                row.getString("token_hash"),
                instant(row, "expires_at"),
                row.getInt("attempts"),
                instant(row, "next_attempt_at"),
                row.getString("last_error_code"),
                java.util.Objects.requireNonNull(instant(row, "requested_at")),
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

    /** One reset. {@code tokenHash} is a SHA-256, never a token, and there is no address here at all. */
    public record Row(
            UUID id,
            String subjectId,
            String console,
            String locale,
            String status,
            @Nullable String tokenHash,
            @Nullable Instant expiresAt,
            int attempts,
            @Nullable Instant nextAttemptAt,
            @Nullable String lastErrorCode,
            Instant requestedAt,
            @Nullable Instant sentAt,
            @Nullable Instant openedAt,
            @Nullable Instant acceptedAt) {}
}
