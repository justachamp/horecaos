package uz.horecaos.platform.iam.infrastructure.persistence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.application.sessions.DeviceLabel;
import uz.horecaos.platform.iam.application.sessions.EndReason;

/**
 * {@code iam.staff_sign_ins} (ADR 0176, V0528): the platform's record of what a staff session
 * was. Explicit SQL, every statement scoped by tenant where a tenant is known.
 *
 * <p>Holds a device label out of three closed sets and times. There is nowhere to put an address
 * or a header, and this class never receives one.
 */
@Repository
public class JdbcStaffSignInStore {

    private final JdbcClient jdbc;

    public JdbcStaffSignInStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One row of the history. */
    public record Row(
            UUID tenantId,
            String sessionId,
            DeviceLabel label,
            Instant signedInAt,
            Instant lastSeenAt,
            @Nullable Instant endedAt,
            @Nullable EndReason endReason) {}

    /**
     * Writes one row per tenant the sign-in named in which the account has a staff member record.
     * A tenant with no record for the subject is skipped, not an error: the foreign key would
     * refuse the row, and a person with no record in a tenant has no screen there to read it on.
     *
     * @return how many rows were written
     */
    public int recordSignIn(
            String subjectId, Collection<String> organizationIds, String sessionId, DeviceLabel label, Instant now) {
        if (organizationIds.isEmpty()) {
            return 0;
        }
        List<UUID> tenants = jdbc.sql("""
                        SELECT m.tenant_id
                          FROM iam.staff_members m
                          JOIN tenant.tenants t ON t.id = m.tenant_id
                         WHERE m.principal_subject = :subject
                           AND t.keycloak_organization_id IN (:organizations)
                        """)
                .param("subject", subjectId)
                .param("organizations", organizationIds)
                .query(UUID.class)
                .list();
        int written = 0;
        for (UUID tenantId : tenants) {
            written += jdbc.sql("""
                            INSERT INTO iam.staff_sign_ins (
                                id, tenant_id, principal_subject, keycloak_session_id,
                                browser_family, os_family, device_form, signed_in_at, last_seen_at)
                            VALUES (:id, :tenantId, :subject, :sessionId, :browser, :os, :form, :now, :now)
                            ON CONFLICT (tenant_id, keycloak_session_id) DO NOTHING
                            """)
                    .param("id", Ids.newId())
                    .param("tenantId", tenantId)
                    .param("subject", subjectId)
                    .param("sessionId", sessionId)
                    .param("browser", label.browser().name())
                    .param("os", label.os().name())
                    .param("form", label.form().name())
                    .param("now", now.atOffset(ZoneOffset.UTC))
                    .update();
        }
        return written;
    }

    /**
     * Moves {@code last_seen_at} forward for a session that is still open, and only when the
     * stored value is at least {@code notAfter} old: a refresh happens every few minutes and the
     * history needs a coarse "last seen", not a write per refresh (ADR 0176 Decision 2).
     */
    public int touch(String sessionId, Instant now, Instant notAfter) {
        return jdbc.sql("""
                        UPDATE iam.staff_sign_ins
                           SET last_seen_at = :now
                         WHERE keycloak_session_id = :sessionId
                           AND ended_at IS NULL
                           AND last_seen_at <= :notAfter
                           AND last_seen_at < :now
                        """)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("sessionId", sessionId)
                .param("notAfter", notAfter.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** The person's most recent rows in this tenant, newest sign-in first. */
    public List<Row> recent(UUID tenantId, String subjectId, int limit) {
        return jdbc.sql("""
                        SELECT tenant_id, keycloak_session_id, browser_family, os_family, device_form,
                               signed_in_at, last_seen_at, ended_at, end_reason
                          FROM iam.staff_sign_ins
                         WHERE tenant_id = :tenantId AND principal_subject = :subject
                         ORDER BY signed_in_at DESC, id DESC
                         LIMIT :limit
                        """)
                .param("tenantId", tenantId)
                .param("subject", subjectId)
                .param("limit", limit)
                .query((rs, n) -> new Row(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("keycloak_session_id"),
                        new DeviceLabel(
                                DeviceLabel.Browser.valueOf(rs.getString("browser_family")),
                                DeviceLabel.Os.valueOf(rs.getString("os_family")),
                                DeviceLabel.Form.valueOf(rs.getString("device_form"))),
                        rs.getObject("signed_in_at", java.time.OffsetDateTime.class)
                                .toInstant(),
                        rs.getObject("last_seen_at", java.time.OffsetDateTime.class)
                                .toInstant(),
                        rs.getObject("ended_at", java.time.OffsetDateTime.class) == null
                                ? null
                                : rs.getObject("ended_at", java.time.OffsetDateTime.class)
                                        .toInstant(),
                        rs.getString("end_reason") == null ? null : EndReason.valueOf(rs.getString("end_reason"))))
                .list();
    }

    public Optional<Row> bySession(UUID tenantId, String subjectId, String sessionId) {
        return jdbc.sql("""
                        SELECT tenant_id, keycloak_session_id, browser_family, os_family, device_form,
                               signed_in_at, last_seen_at, ended_at, end_reason
                          FROM iam.staff_sign_ins
                         WHERE tenant_id = :tenantId AND principal_subject = :subject
                           AND keycloak_session_id = :sessionId
                        """)
                .param("tenantId", tenantId)
                .param("subject", subjectId)
                .param("sessionId", sessionId)
                .query((rs, n) -> new Row(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("keycloak_session_id"),
                        new DeviceLabel(
                                DeviceLabel.Browser.valueOf(rs.getString("browser_family")),
                                DeviceLabel.Os.valueOf(rs.getString("os_family")),
                                DeviceLabel.Form.valueOf(rs.getString("device_form"))),
                        rs.getObject("signed_in_at", java.time.OffsetDateTime.class)
                                .toInstant(),
                        rs.getObject("last_seen_at", java.time.OffsetDateTime.class)
                                .toInstant(),
                        rs.getObject("ended_at", java.time.OffsetDateTime.class) == null
                                ? null
                                : rs.getObject("ended_at", java.time.OffsetDateTime.class)
                                        .toInstant(),
                        rs.getString("end_reason") == null ? null : EndReason.valueOf(rs.getString("end_reason"))))
                .optional();
    }

    public int openCount(UUID tenantId, String subjectId) {
        return jdbc.sql("""
                        SELECT count(*) FROM iam.staff_sign_ins
                         WHERE tenant_id = :tenantId AND principal_subject = :subject AND ended_at IS NULL
                        """)
                .param("tenantId", tenantId)
                .param("subject", subjectId)
                .query(Integer.class)
                .single();
    }

    /** The person's record id in this tenant: what an audit fact about their account targets. */
    public Optional<UUID> memberId(UUID tenantId, String subjectId) {
        return jdbc.sql("""
                        SELECT id FROM iam.staff_members WHERE tenant_id = :tenantId AND principal_subject = :subject
                        """)
                .param("tenantId", tenantId)
                .param("subject", subjectId)
                .query(UUID.class)
                .optional();
    }

    // ----------------------------------------------------------------- the one place a row is ended

    /** Ends every open row of the person in this tenant. @return how many rows were ended */
    public int endAllInTenant(UUID tenantId, String subjectId, EndReason reason, Instant now) {
        return jdbc.sql("""
                        UPDATE iam.staff_sign_ins
                           SET ended_at = :now, end_reason = :reason
                         WHERE tenant_id = :tenantId AND principal_subject = :subject AND ended_at IS NULL
                        """)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("reason", reason.name())
                .param("tenantId", tenantId)
                .param("subject", subjectId)
                .update();
    }

    /** Ends every open row of the person in every tenant: the unauthenticated reset has no tenant. */
    public int endAllEverywhere(String subjectId, EndReason reason, Instant now) {
        return jdbc.sql("""
                        UPDATE iam.staff_sign_ins
                           SET ended_at = :now, end_reason = :reason
                         WHERE principal_subject = :subject AND ended_at IS NULL
                        """)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("reason", reason.name())
                .param("subject", subjectId)
                .update();
    }

    /** Ends the open rows of one Keycloak session, in whichever tenants recorded it. */
    public int endSession(String sessionId, EndReason reason, Instant now) {
        return jdbc.sql("""
                        UPDATE iam.staff_sign_ins
                           SET ended_at = :now, end_reason = :reason
                         WHERE keycloak_session_id = :sessionId AND ended_at IS NULL
                        """)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("reason", reason.name())
                .param("sessionId", sessionId)
                .update();
    }

    /** Ends one session of one person in every tenant that recorded it. */
    public int endSessionOf(String subjectId, String sessionId, EndReason reason, Instant now) {
        return jdbc.sql("""
                        UPDATE iam.staff_sign_ins
                           SET ended_at = :now, end_reason = :reason
                         WHERE principal_subject = :subject AND keycloak_session_id = :sessionId
                           AND ended_at IS NULL
                        """)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("reason", reason.name())
                .param("subject", subjectId)
                .param("sessionId", sessionId)
                .update();
    }

    // ----------------------------------------------------------------- sweeps

    /** Closes rows whose session Keycloak has not seen since {@code cutoff} (the idle sweep). */
    public int expireIdleBefore(Instant cutoff, Instant now, int batchSize) {
        return jdbc.sql("""
                        UPDATE iam.staff_sign_ins
                           SET ended_at = :now, end_reason = 'EXPIRED'
                         WHERE id IN (
                               SELECT id FROM iam.staff_sign_ins
                                WHERE ended_at IS NULL AND last_seen_at < :cutoff
                                ORDER BY last_seen_at
                                LIMIT :batch)
                        """)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .param("batch", batchSize)
                .update();
    }

    /**
     * The longest retention any tenant stored for the key, or null when none did. A global pass
     * runs on the longer of the platform default and this, so a stored value can lengthen the
     * window and never shorten another tenant's (the rule the cart and applicant sweepers use).
     */
    public @Nullable Long longestTenantRetentionDays(String keyCode) {
        return jdbc.sql("""
                        SELECT max(integer_value) FROM tenant.configuration_values
                         WHERE key_code = :keyCode AND is_explicit_null = false
                        """)
                .param("keyCode", keyCode)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    public int countSignedInBefore(Instant cutoff) {
        return jdbc.sql("SELECT count(*) FROM iam.staff_sign_ins WHERE signed_in_at < :cutoff")
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .query(Integer.class)
                .single();
    }

    public int deleteSignedInBefore(Instant cutoff, int batchSize) {
        return jdbc.sql("""
                        DELETE FROM iam.staff_sign_ins
                         WHERE id IN (
                               SELECT id FROM iam.staff_sign_ins
                                WHERE signed_in_at < :cutoff
                                ORDER BY signed_in_at
                                LIMIT :batch)
                        """)
                .param("cutoff", cutoff.atOffset(ZoneOffset.UTC))
                .param("batch", batchSize)
                .update();
    }
}
