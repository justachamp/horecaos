package uz.horecaos.platform.iam.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
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
 * Explicit SQL for the terminals (ADR 0155): the PIN credential, its single-use activation codes, the per-device
 * lock and the terminal session.
 *
 * <p>Every statement names the tenant. The correctness of the lockout rests on one thing in here: {@link
 * #lockPin} takes the person's {@code iam.staff_pins} row {@code FOR UPDATE}, so one attempt at a time is counted
 * per person, in the database, whatever the number of replicas. Nothing here reads or writes a cache.
 */
@Repository
public class JdbcTerminalStore {

    private final JdbcClient jdbc;

    public JdbcTerminalStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------------------------ the credential

    /** {@code iam.staff_pins}: the credential of one person. */
    public record PinRow(
            UUID tenantId,
            UUID memberId,
            String status,
            @Nullable String digest,
            @Nullable Integer pepperVersion,
            int pinVersion,
            @Nullable Integer pinLength,
            @Nullable Instant setAt,
            @Nullable Instant windowStartedAt,
            int failuresInWindow,
            @Nullable Instant lockedAt,
            @Nullable String lockedReason,
            int version) {}

    /** Creates the member's credential row, {@code NOT_SET}, when there is none. Safe to call concurrently. */
    public void ensurePin(UUID tenantId, UUID memberId, Instant now) {
        jdbc.sql("""
                INSERT INTO iam.staff_pins (tenant_id, staff_member_id, created_at, updated_at)
                VALUES (:tenantId, :memberId, :now, :now)
                ON CONFLICT (tenant_id, staff_member_id) DO NOTHING
                """)
                .param("tenantId", tenantId)
                .param("memberId", memberId)
                .param("now", at(now))
                .update();
    }

    /** The credential, locked for this transaction: one attempt at a time per person. */
    public Optional<PinRow> lockPin(UUID tenantId, UUID memberId) {
        return jdbc.sql(PIN_SELECT + " WHERE tenant_id = :tenantId AND staff_member_id = :memberId FOR UPDATE")
                .param("tenantId", tenantId)
                .param("memberId", memberId)
                .query(JdbcTerminalStore::toPin)
                .optional();
    }

    public Optional<PinRow> findPin(UUID tenantId, UUID memberId) {
        return jdbc.sql(PIN_SELECT + " WHERE tenant_id = :tenantId AND staff_member_id = :memberId")
                .param("tenantId", tenantId)
                .param("memberId", memberId)
                .query(JdbcTerminalStore::toPin)
                .optional();
    }

    /** Every member's credential state, for the roster: {@code NOT_SET}, {@code ACTIVE} or {@code LOCKED}. */
    public List<MemberPinState> pinStates(UUID tenantId, List<UUID> memberIds) {
        if (memberIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT staff_member_id, status FROM iam.staff_pins
                 WHERE tenant_id = :tenantId AND staff_member_id = ANY(:memberIds)
                """)
                .param("tenantId", tenantId)
                .param("memberIds", memberIds.toArray(UUID[]::new))
                .query((rs, n) ->
                        new MemberPinState(rs.getObject("staff_member_id", UUID.class), rs.getString("status")))
                .list();
    }

    public record MemberPinState(UUID memberId, String status) {}

    /** A wrong PIN: the 24-hour window moves (or restarts) and the count goes up by one. */
    public void recordFailureInWindow(
            UUID tenantId, UUID memberId, Instant windowStartedAt, int failuresInWindow, Instant now) {
        jdbc.sql("""
                UPDATE iam.staff_pins
                   SET window_started_at = :windowStartedAt, failures_in_window = :failures,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND staff_member_id = :memberId
                """)
                .param("windowStartedAt", at(windowStartedAt))
                .param("failures", failuresInWindow)
                .param("now", at(now))
                .param("tenantId", tenantId)
                .param("memberId", memberId)
                .update();
    }

    /** Twelve in a rolling day: the credential locks and drops its digest, so only an activation can replace it. */
    public void lockCredential(UUID tenantId, UUID memberId, int failuresInWindow, String reason, Instant now) {
        jdbc.sql("""
                UPDATE iam.staff_pins
                   SET status = 'LOCKED', pin_digest = NULL, pepper_version = NULL, pin_length = NULL,
                       failures_in_window = :failures, locked_at = :now, locked_reason = :reason,
                       pin_version = pin_version + 1, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND staff_member_id = :memberId
                """)
                .param("failures", failuresInWindow)
                .param("reason", reason)
                .param("now", at(now))
                .param("tenantId", tenantId)
                .param("memberId", memberId)
                .update();
    }

    /** A PIN set or changed: the new digest, a fresh version, and a clean slate for the counters. */
    public void setPin(
            UUID tenantId,
            UUID memberId,
            String digest,
            int pepperVersion,
            int pinVersion,
            int pinLength,
            @Nullable UUID deviceId,
            String activatedBy,
            Instant now) {
        jdbc.sql("""
                UPDATE iam.staff_pins
                   SET status = 'ACTIVE', pin_digest = :digest, pepper_version = :pepperVersion,
                       pin_version = :pinVersion, pin_length = :pinLength, set_at = :now, set_on_device_id = :deviceId,
                       activated_by = :activatedBy, window_started_at = NULL, failures_in_window = 0,
                       locked_at = NULL, locked_reason = NULL, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND staff_member_id = :memberId
                """)
                .param("digest", digest)
                .param("pepperVersion", pepperVersion)
                .param("pinVersion", pinVersion)
                .param("pinLength", pinLength)
                .param("deviceId", deviceId)
                .param("activatedBy", activatedBy)
                .param("now", at(now))
                .param("tenantId", tenantId)
                .param("memberId", memberId)
                .update();
    }

    /** A successful unlock after the pepper moved on: the same PIN, keyed under the current pepper. */
    public void rekey(UUID tenantId, UUID memberId, String digest, int pepperVersion, Instant now) {
        jdbc.sql("""
                UPDATE iam.staff_pins
                   SET pin_digest = :digest, pepper_version = :pepperVersion, updated_at = :now
                 WHERE tenant_id = :tenantId AND staff_member_id = :memberId AND status = 'ACTIVE'
                """)
                .param("digest", digest)
                .param("pepperVersion", pepperVersion)
                .param("now", at(now))
                .param("tenantId", tenantId)
                .param("memberId", memberId)
                .update();
    }

    /** A manager's reset: the credential is cleared and the person activates again. */
    public void clearPin(UUID tenantId, UUID memberId, Instant now) {
        jdbc.sql("""
                UPDATE iam.staff_pins
                   SET status = 'NOT_SET', pin_digest = NULL, pepper_version = NULL, pin_length = NULL,
                       set_at = NULL, set_on_device_id = NULL, activated_by = NULL,
                       window_started_at = NULL, failures_in_window = 0, locked_at = NULL, locked_reason = NULL,
                       pin_version = pin_version + 1, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND staff_member_id = :memberId
                """)
                .param("now", at(now))
                .param("tenantId", tenantId)
                .param("memberId", memberId)
                .update();
    }

    // ----------------------------------------------------------------------------------- the device lock

    /** {@code iam.terminal_pin_locks}: consecutive wrong PINs of one person at one device. */
    public record DeviceLockRow(
            int consecutiveFailures, @Nullable Instant lockedUntil) {}

    public Optional<DeviceLockRow> findDeviceLock(UUID deviceId, UUID memberId) {
        return jdbc.sql("""
                SELECT consecutive_failures, locked_until FROM iam.terminal_pin_locks
                 WHERE device_id = :deviceId AND staff_member_id = :memberId
                """)
                .param("deviceId", deviceId)
                .param("memberId", memberId)
                .query((rs, n) -> new DeviceLockRow(rs.getInt("consecutive_failures"), instant(rs, "locked_until")))
                .optional();
    }

    public void saveDeviceLock(
            UUID tenantId,
            UUID deviceId,
            UUID memberId,
            int consecutiveFailures,
            @Nullable Instant lockedUntil,
            Instant now) {
        jdbc.sql("""
                INSERT INTO iam.terminal_pin_locks
                    (tenant_id, device_id, staff_member_id, consecutive_failures, locked_until, last_failure_at)
                VALUES (:tenantId, :deviceId, :memberId, :failures, :lockedUntil, :now)
                ON CONFLICT (device_id, staff_member_id) DO UPDATE
                   SET consecutive_failures = :failures, locked_until = :lockedUntil, last_failure_at = :now
                """)
                .param("tenantId", tenantId)
                .param("deviceId", deviceId)
                .param("memberId", memberId)
                .param("failures", consecutiveFailures)
                .param("lockedUntil", lockedUntil == null ? null : at(lockedUntil))
                .param("now", at(now))
                .update();
    }

    /** A success clears the consecutive count on this device, and never the 24-hour one. */
    public void clearDeviceLock(UUID deviceId, UUID memberId) {
        jdbc.sql("DELETE FROM iam.terminal_pin_locks WHERE device_id = :deviceId AND staff_member_id = :memberId")
                .param("deviceId", deviceId)
                .param("memberId", memberId)
                .update();
    }

    /** A reset or an activation ends the failures every device counted. */
    public void clearAllDeviceLocks(UUID tenantId, UUID memberId) {
        jdbc.sql("DELETE FROM iam.terminal_pin_locks WHERE tenant_id = :tenantId AND staff_member_id = :memberId")
                .param("tenantId", tenantId)
                .param("memberId", memberId)
                .update();
    }

    // ------------------------------------------------------------------------------------- activations

    /** {@code iam.staff_pin_activations}. */
    public record ActivationRow(
            UUID id,
            UUID memberId,
            String codeDigest,
            String issuedBy,
            String issuedVia,
            String status,
            int failedAttempts,
            Instant expiresAt,
            Instant createdAt) {}

    /** At most one live code per person: issuing a second supersedes the first. */
    public int supersedePendingActivations(UUID tenantId, UUID memberId) {
        return jdbc.sql("""
                UPDATE iam.staff_pin_activations SET status = 'SUPERSEDED'
                 WHERE tenant_id = :tenantId AND staff_member_id = :memberId AND status = 'PENDING'
                """)
                .param("tenantId", tenantId)
                .param("memberId", memberId)
                .update();
    }

    public void insertActivation(
            UUID id,
            UUID tenantId,
            UUID memberId,
            String codeDigest,
            String issuedBy,
            String issuedVia,
            Instant expiresAt,
            Instant now) {
        jdbc.sql("""
                INSERT INTO iam.staff_pin_activations
                    (id, tenant_id, staff_member_id, code_digest, issued_by, issued_via, expires_at, created_at)
                VALUES (:id, :tenantId, :memberId, :digest, :issuedBy, :issuedVia, :expiresAt, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("memberId", memberId)
                .param("digest", codeDigest)
                .param("issuedBy", issuedBy)
                .param("issuedVia", issuedVia)
                .param("expiresAt", at(expiresAt))
                .param("now", at(now))
                .update();
    }

    public Optional<ActivationRow> pendingActivation(UUID tenantId, UUID memberId) {
        return jdbc.sql(ACTIVATION_SELECT
                        + " WHERE tenant_id = :tenantId AND staff_member_id = :memberId AND status = 'PENDING'")
                .param("tenantId", tenantId)
                .param("memberId", memberId)
                .query(JdbcTerminalStore::toActivation)
                .optional();
    }

    /** Sets a pending code's status (EXPIRED, BURNED) or counts a wrong try. */
    public void setActivationStatus(UUID id, String status) {
        jdbc.sql("UPDATE iam.staff_pin_activations SET status = :status WHERE id = :id AND status = 'PENDING'")
                .param("status", status)
                .param("id", id)
                .update();
    }

    public void countWrongActivationTry(UUID id, int failedAttempts) {
        jdbc.sql("UPDATE iam.staff_pin_activations SET failed_attempts = :n WHERE id = :id AND status = 'PENDING'")
                .param("n", failedAttempts)
                .param("id", id)
                .update();
    }

    public void consumeActivation(UUID id, UUID deviceId, Instant now) {
        jdbc.sql("""
                UPDATE iam.staff_pin_activations
                   SET status = 'CONSUMED', consumed_at = :now, consumed_device_id = :deviceId
                 WHERE id = :id AND status = 'PENDING'
                """)
                .param("now", at(now))
                .param("deviceId", deviceId)
                .param("id", id)
                .update();
    }

    // --------------------------------------------------------------------------------------- sessions

    /** {@code iam.terminal_sessions}. */
    public record SessionRow(
            UUID id,
            UUID tenantId,
            UUID deviceId,
            UUID memberId,
            UUID locationId,
            Instant openedAt,
            Instant lastActivityAt,
            int idleSeconds,
            Instant hardExpiresAt,
            @Nullable Instant endedAt,
            @Nullable String endedReason) {}

    /** A session beside who and where: what the person's own list and a device's history show. */
    public record SessionHistoryRow(
            UUID id,
            UUID deviceId,
            String deviceName,
            UUID memberId,
            String memberReference,
            Instant openedAt,
            @Nullable Instant endedAt,
            @Nullable String endedReason,
            Instant lastActivityAt,
            int idleSeconds,
            Instant hardExpiresAt) {}

    /**
     * Serializes session changes per device for the rest of the transaction: two people tapping their names at one tablet at
     * once would otherwise both see no live session and both insert one, and the database's one-live-session-per-device
     * index would refuse the second as an error nobody can interpret.
     */
    public void lockDevice(UUID tenantId, UUID deviceId) {
        jdbc.sql("SELECT id FROM iam.device_principals WHERE tenant_id = :tenantId AND id = :id FOR UPDATE")
                .param("tenantId", tenantId)
                .param("id", deviceId)
                .query(UUID.class)
                .optional();
    }

    public void insertSession(
            UUID id,
            UUID tenantId,
            UUID deviceId,
            UUID memberId,
            UUID locationId,
            String tokenDigest,
            Instant now,
            int idleSeconds,
            Instant hardExpiresAt) {
        jdbc.sql("""
                INSERT INTO iam.terminal_sessions
                    (id, tenant_id, device_id, staff_member_id, location_id, token_digest, opened_at,
                     last_activity_at, idle_seconds, hard_expires_at)
                VALUES (:id, :tenantId, :deviceId, :memberId, :locationId, :digest, :now, :now, :idle, :hardExpires)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("deviceId", deviceId)
                .param("memberId", memberId)
                .param("locationId", locationId)
                .param("digest", tokenDigest)
                .param("now", at(now))
                .param("idle", idleSeconds)
                .param("hardExpires", at(hardExpiresAt))
                .update();
    }

    public Optional<SessionRow> findSessionByToken(String tokenDigest) {
        return jdbc.sql(SESSION_SELECT + " WHERE token_digest = :digest")
                .param("digest", tokenDigest)
                .query(JdbcTerminalStore::toSession)
                .optional();
    }

    /** The live session of a person, for the row lock a new unlock takes before it supersedes it. */
    public List<SessionRow> liveSessionsOf(UUID tenantId, UUID memberId) {
        return jdbc.sql(SESSION_SELECT
                        + " WHERE tenant_id = :tenantId AND staff_member_id = :memberId AND ended_at IS NULL")
                .param("tenantId", tenantId)
                .param("memberId", memberId)
                .query(JdbcTerminalStore::toSession)
                .list();
    }

    public List<SessionRow> liveSessionsOfDevice(UUID tenantId, UUID deviceId) {
        return jdbc.sql(SESSION_SELECT + " WHERE tenant_id = :tenantId AND device_id = :deviceId AND ended_at IS NULL")
                .param("tenantId", tenantId)
                .param("deviceId", deviceId)
                .query(JdbcTerminalStore::toSession)
                .list();
    }

    /** Ends one session if it is still live. Returns whether this call ended it. */
    public boolean endSession(UUID sessionId, String reason, Instant now) {
        return jdbc.sql("""
                        UPDATE iam.terminal_sessions SET ended_at = :now, ended_reason = :reason
                         WHERE id = :id AND ended_at IS NULL
                        """)
                        .param("now", at(now))
                        .param("reason", reason)
                        .param("id", sessionId)
                        .update()
                == 1;
    }

    /** Extends the idle deadline; a no-op once ended. */
    public boolean touchSession(UUID sessionId, Instant now) {
        return jdbc.sql("""
                        UPDATE iam.terminal_sessions SET last_activity_at = :now
                         WHERE id = :id AND ended_at IS NULL
                        """).param("now", at(now)).param("id", sessionId).update() == 1;
    }

    /** The person's own most recent unlocks, newest first: which device, when, how it ended. */
    public List<SessionHistoryRow> recentSessionsOf(UUID tenantId, UUID memberId, int limit) {
        return jdbc.sql(HISTORY_SELECT + """
                 WHERE s.tenant_id = :tenantId AND s.staff_member_id = :memberId
                 ORDER BY s.opened_at DESC, s.id DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("memberId", memberId)
                .param("limit", limit)
                .query(JdbcTerminalStore::toHistory)
                .list();
    }

    /** A device's history, newest first, strictly older than the cursor. */
    public List<SessionHistoryRow> sessionsOfDevice(
            UUID tenantId, UUID deviceId, @Nullable Instant before, @Nullable UUID beforeId, int limit) {
        return jdbc.sql(HISTORY_SELECT + """
                 WHERE s.tenant_id = :tenantId AND s.device_id = :deviceId
                   AND (CAST(:before AS timestamptz) IS NULL OR (s.opened_at, s.id) < (CAST(:before AS timestamptz), CAST(:beforeId AS uuid)))
                 ORDER BY s.opened_at DESC, s.id DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("deviceId", deviceId)
                .param("before", before == null ? null : at(before))
                .param("beforeId", beforeId)
                .param("limit", limit)
                .query(JdbcTerminalStore::toHistory)
                .list();
    }

    /** Who is unlocked now, per device of a location: the registry's «who is at it». */
    public List<LiveSessionRow> liveSessionsAt(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT s.device_id, s.id, s.staff_member_id, m.display_reference, s.opened_at, s.last_activity_at,
                       s.idle_seconds, s.hard_expires_at
                  FROM iam.terminal_sessions s
                  JOIN iam.staff_members m ON m.id = s.staff_member_id AND m.tenant_id = s.tenant_id
                 WHERE s.tenant_id = :tenantId AND s.location_id = :locationId AND s.ended_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query((rs, n) -> new LiveSessionRow(
                        rs.getObject("device_id", UUID.class),
                        rs.getObject("id", UUID.class),
                        rs.getObject("staff_member_id", UUID.class),
                        rs.getString("display_reference"),
                        instant(rs, "opened_at"),
                        instant(rs, "last_activity_at"),
                        rs.getInt("idle_seconds"),
                        instant(rs, "hard_expires_at")))
                .list();
    }

    public record LiveSessionRow(
            UUID deviceId,
            UUID sessionId,
            UUID memberId,
            String memberReference,
            @Nullable Instant openedAt,
            @Nullable Instant lastActivityAt,
            int idleSeconds,
            @Nullable Instant hardExpiresAt) {}

    /** The purge: rows older than the retention horizon. Returns how many were removed. */
    public int purgeSessionsOpenedBefore(Instant horizon) {
        return jdbc.sql("DELETE FROM iam.terminal_sessions WHERE ended_at IS NOT NULL AND opened_at < :horizon")
                .param("horizon", at(horizon))
                .update();
    }

    public int purgeActivationsCreatedBefore(Instant horizon) {
        return jdbc.sql("DELETE FROM iam.staff_pin_activations WHERE status <> 'PENDING' AND created_at < :horizon")
                .param("horizon", at(horizon))
                .update();
    }

    // ---------------------------------------------------------------------------------- the roster

    /** A member who might be eligible at a branch: active, holding an active job that covers it. */
    public record Candidate(UUID memberId, String subject, String reference) {}

    /**
     * The active members whose grants cover this branch -- a tenant, brand or location grant -- and nobody else. A
     * candidate only: eligibility is {@code AuthorizationService.has} for each capability the class names, asked of the
     * person's subject, so there is no second table that says who may do what.
     */
    public List<Candidate> candidatesAt(UUID tenantId, UUID brandId, UUID locationId, Instant now) {
        return jdbc.sql("""
                SELECT m.id, m.principal_subject, m.display_reference
                  FROM iam.staff_members m
                 WHERE m.tenant_id = :tenantId AND m.employment_status = 'ACTIVE'
                   AND EXISTS (
                       SELECT 1 FROM iam.grants g
                        WHERE g.tenant_id = m.tenant_id AND g.principal_subject = m.principal_subject
                          AND g.status = 'ACTIVE' AND g.valid_from <= :now
                          AND (g.valid_until IS NULL OR g.valid_until > :now)
                          AND ((g.scope_type = 'TENANT' AND g.scope_id = :tenantId)
                            OR (g.scope_type = 'BRAND' AND g.scope_id = :brandId)
                            OR (g.scope_type = 'LOCATION' AND g.scope_id = :locationId)))
                 ORDER BY m.display_reference
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("now", at(now))
                .query((rs, n) -> new Candidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("principal_subject"),
                        rs.getString("display_reference")))
                .list();
    }

    // ------------------------------------------------------------------------------------- mapping

    private static final String PIN_SELECT = """
            SELECT tenant_id, staff_member_id, status, pin_digest, pepper_version, pin_version, pin_length, set_at,
                   window_started_at, failures_in_window, locked_at, locked_reason, version
              FROM iam.staff_pins
            """;

    private static final String ACTIVATION_SELECT = """
            SELECT id, staff_member_id, code_digest, issued_by, issued_via, status, failed_attempts, expires_at, created_at
              FROM iam.staff_pin_activations
            """;

    private static final String SESSION_SELECT = """
            SELECT id, tenant_id, device_id, staff_member_id, location_id, opened_at, last_activity_at, idle_seconds,
                   hard_expires_at, ended_at, ended_reason
              FROM iam.terminal_sessions
            """;

    private static final String HISTORY_SELECT = """
            SELECT s.id, s.device_id, d.display_name AS device_name, s.staff_member_id, m.display_reference,
                   s.opened_at, s.ended_at, s.ended_reason, s.last_activity_at, s.idle_seconds, s.hard_expires_at
              FROM iam.terminal_sessions s
              JOIN iam.device_principals d ON d.id = s.device_id AND d.tenant_id = s.tenant_id
              JOIN iam.staff_members m ON m.id = s.staff_member_id AND m.tenant_id = s.tenant_id
            """;

    private static PinRow toPin(ResultSet rs, int rowNumber) throws SQLException {
        return new PinRow(
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("staff_member_id", UUID.class),
                rs.getString("status"),
                rs.getString("pin_digest"),
                (Integer) intOrNull(rs, "pepper_version"),
                rs.getInt("pin_version"),
                (Integer) intOrNull(rs, "pin_length"),
                instant(rs, "set_at"),
                instant(rs, "window_started_at"),
                rs.getInt("failures_in_window"),
                instant(rs, "locked_at"),
                rs.getString("locked_reason"),
                rs.getInt("version"));
    }

    private static ActivationRow toActivation(ResultSet rs, int rowNumber) throws SQLException {
        return new ActivationRow(
                rs.getObject("id", UUID.class),
                rs.getObject("staff_member_id", UUID.class),
                rs.getString("code_digest"),
                rs.getString("issued_by"),
                rs.getString("issued_via"),
                rs.getString("status"),
                rs.getInt("failed_attempts"),
                java.util.Objects.requireNonNull(instant(rs, "expires_at")),
                java.util.Objects.requireNonNull(instant(rs, "created_at")));
    }

    private static SessionRow toSession(ResultSet rs, int rowNumber) throws SQLException {
        return new SessionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("device_id", UUID.class),
                rs.getObject("staff_member_id", UUID.class),
                rs.getObject("location_id", UUID.class),
                java.util.Objects.requireNonNull(instant(rs, "opened_at")),
                java.util.Objects.requireNonNull(instant(rs, "last_activity_at")),
                rs.getInt("idle_seconds"),
                java.util.Objects.requireNonNull(instant(rs, "hard_expires_at")),
                instant(rs, "ended_at"),
                rs.getString("ended_reason"));
    }

    private static SessionHistoryRow toHistory(ResultSet rs, int rowNumber) throws SQLException {
        return new SessionHistoryRow(
                rs.getObject("id", UUID.class),
                rs.getObject("device_id", UUID.class),
                rs.getString("device_name"),
                rs.getObject("staff_member_id", UUID.class),
                rs.getString("display_reference"),
                java.util.Objects.requireNonNull(instant(rs, "opened_at")),
                instant(rs, "ended_at"),
                rs.getString("ended_reason"),
                java.util.Objects.requireNonNull(instant(rs, "last_activity_at")),
                rs.getInt("idle_seconds"),
                java.util.Objects.requireNonNull(instant(rs, "hard_expires_at")));
    }

    private static @Nullable Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
