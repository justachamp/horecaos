package uz.horecaos.platform.tenancy.infrastructure.persistence;

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
 * {@code tenant.staff_invitations} (ADR 0116, V0313).
 *
 * <p>Unlike {@link JdbcOwnerInvitationStore}, a row here names an account and
 * a grant that already exist by the time it is written -- the invitation is
 * only the one-time link to set a password -- so there is no "queue, then
 * claim, then send" lifecycle to persist: {@link StaffInvitationService}
 * writes the row once, sends the email itself if one was asked for, and
 * records whether that worked.
 */
@Repository
public class JdbcStaffInvitationStore {

    private static final String COLUMNS = """
            id, tenant_id, subject_id, grant_id, locale, status, token_hash, expires_at,
            email_given, invited_by, invited_at, sent_at, opened_at, accepted_at,
            cancelled_at, cancelled_by, last_error_code
            """;

    private final JdbcClient jdbc;

    public JdbcStaffInvitationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            UUID id,
            UUID tenantId,
            String subjectId,
            UUID grantId,
            String locale,
            String tokenHash,
            Instant expiresAt,
            boolean emailGiven,
            String invitedBy,
            Instant now) {
        jdbc.sql("""
                        INSERT INTO tenant.staff_invitations (
                            id, tenant_id, subject_id, grant_id, locale, status, token_hash,
                            expires_at, email_given, invited_by, invited_at)
                        VALUES (:id, :tenantId, :subjectId, :grantId, :locale, 'QUEUED', :tokenHash,
                            :expiresAt, :emailGiven, :invitedBy, :now)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("subjectId", subjectId)
                .param("grantId", grantId)
                .param("locale", locale)
                .param("tokenHash", tokenHash)
                .param("expiresAt", utc(expiresAt))
                .param("emailGiven", emailGiven)
                .param("invitedBy", invitedBy)
                .param("now", utc(now))
                .update();
    }

    /** The email was sent successfully; called after {@link #insert}, outside any transaction. */
    public void markSent(UUID id, Instant now) {
        jdbc.sql("""
                        UPDATE tenant.staff_invitations
                           SET status = 'SENT', sent_at = :now, last_error_code = NULL, version = version + 1
                         WHERE id = :id AND status = 'QUEUED'
                        """).param("id", id).param("now", utc(now)).update();
    }

    /** The send was attempted and failed, or no mail server is configured; the link is still live. */
    public void markSendFailed(UUID id, String errorCode) {
        jdbc.sql("""
                        UPDATE tenant.staff_invitations
                           SET last_error_code = :errorCode, version = version + 1
                         WHERE id = :id AND status = 'QUEUED'
                        """).param("id", id).param("errorCode", errorCode).update();
    }

    public Optional<Row> byTokenHash(String tokenHash) {
        return jdbc.sql("SELECT " + COLUMNS + """
                          FROM tenant.staff_invitations
                         WHERE token_hash = :hash
                        """)
                .param("hash", tokenHash)
                .query(JdbcStaffInvitationStore::map)
                .optional();
    }

    public Optional<Row> byId(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + """
                          FROM tenant.staff_invitations
                         WHERE id = :id AND tenant_id = :tenantId
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .query(JdbcStaffInvitationStore::map)
                .optional();
    }

    /** The invitation this subject was created under, most recent first -- there is ordinarily exactly one. */
    public Optional<Row> latestForSubject(UUID tenantId, String subjectId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                          FROM tenant.staff_invitations
                         WHERE tenant_id = :tenantId AND subject_id = :subjectId
                         ORDER BY invited_at DESC, id
                         LIMIT 1
                        """)
                .param("tenantId", tenantId)
                .param("subjectId", subjectId)
                .query(JdbcStaffInvitationStore::map)
                .optional();
    }

    /** Every invitation this tenant has open (not accepted, not cancelled) -- the People screen's «Приглашён» pill. */
    public List<Row> outstandingForTenant(UUID tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                          FROM tenant.staff_invitations
                         WHERE tenant_id = :tenantId AND status NOT IN ('ACCEPTED', 'CANCELLED')
                         ORDER BY invited_at DESC
                        """)
                .param("tenantId", tenantId)
                .query(JdbcStaffInvitationStore::map)
                .list();
    }

    public boolean markOpened(UUID id, Instant now) {
        return jdbc.sql("""
                        UPDATE tenant.staff_invitations
                           SET status = CASE WHEN status = 'QUEUED' THEN 'SENT' ELSE status END,
                               opened_at = :now, version = version + 1
                         WHERE id = :id AND status NOT IN ('ACCEPTED', 'CANCELLED') AND opened_at IS NULL
                        """).param("id", id).param("now", utc(now)).update() > 0;
    }

    /**
     * Spends the link and marks the invitation accepted. Guarded on not being
     * already accepted or cancelled -- {@code QUEUED}, {@code SENT} and
     * {@code OPENED} may all still be live, unlike the owner table, which
     * only ever hands out a link once it is {@code SENT}.
     */
    public boolean markAccepted(UUID id, Instant now) {
        return jdbc.sql("""
                        UPDATE tenant.staff_invitations
                           SET status = 'ACCEPTED', token_hash = NULL, accepted_at = :now,
                               opened_at = COALESCE(opened_at, :now), version = version + 1
                         WHERE id = :id AND status NOT IN ('ACCEPTED', 'CANCELLED')
                        """).param("id", id).param("now", utc(now)).update() > 0;
    }

    /** Mirrors {@code OwnerInvitationService.restoreLink}: a refused password undoes nothing but the spend. */
    public boolean restoreLink(UUID id, String tokenHash, Instant acceptedAt) {
        return jdbc.sql("""
                        UPDATE tenant.staff_invitations
                           SET status = 'OPENED', token_hash = :hash, accepted_at = NULL, version = version + 1
                         WHERE id = :id AND status = 'ACCEPTED' AND token_hash IS NULL AND accepted_at = :acceptedAt
                        """)
                        .param("id", id)
                        .param("hash", tokenHash)
                        .param("acceptedAt", utc(acceptedAt))
                        .update()
                > 0;
    }

    /** A fresh link and expiry on the same row -- resend never creates a second invitation for one account. */
    public boolean requeue(UUID id, String tokenHash, Instant expiresAt, Instant now) {
        return jdbc.sql("""
                        UPDATE tenant.staff_invitations
                           SET status = 'QUEUED', token_hash = :hash, expires_at = :expiresAt,
                               sent_at = NULL, opened_at = NULL, last_error_code = NULL, version = version + 1
                         WHERE id = :id AND status <> 'ACCEPTED' AND status <> 'CANCELLED'
                        """)
                        .param("id", id)
                        .param("hash", tokenHash)
                        .param("expiresAt", utc(expiresAt))
                        .param("now", utc(now))
                        .update()
                > 0;
    }

    public boolean markCancelled(UUID id, String cancelledBy, Instant now) {
        return jdbc.sql("""
                        UPDATE tenant.staff_invitations
                           SET status = 'CANCELLED', token_hash = NULL, cancelled_at = :now,
                               cancelled_by = :cancelledBy, version = version + 1
                         WHERE id = :id AND status <> 'ACCEPTED' AND status <> 'CANCELLED'
                        """)
                        .param("id", id)
                        .param("cancelledBy", cancelledBy)
                        .param("now", utc(now))
                        .update()
                > 0;
    }

    public String tenantName(UUID tenantId) {
        return jdbc.sql("SELECT display_name FROM tenant.tenants WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .single();
    }

    public Optional<String> keycloakOrganizationId(UUID tenantId) {
        return jdbc.sql("SELECT keycloak_organization_id FROM tenant.tenants WHERE id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .optional();
    }

    /** The job name a grant carries, resolved through {@code iam.roles} -- platform and tenant-defined alike. */
    public Optional<String> roleCodeOfGrant(UUID grantId) {
        return jdbc.sql("""
                        SELECT r.code
                          FROM iam.grants g
                          JOIN iam.roles r ON r.id = g.role_id
                         WHERE g.id = :grantId
                        """).param("grantId", grantId).query(String.class).optional();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Row map(ResultSet row, int number) throws SQLException {
        return new Row(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getString("subject_id"),
                row.getObject("grant_id", UUID.class),
                row.getString("locale"),
                row.getString("status"),
                row.getString("token_hash"),
                instant(row, "expires_at"),
                row.getBoolean("email_given"),
                row.getString("invited_by"),
                java.util.Objects.requireNonNull(instant(row, "invited_at")),
                instant(row, "sent_at"),
                instant(row, "opened_at"),
                instant(row, "accepted_at"),
                instant(row, "cancelled_at"),
                row.getString("cancelled_by"));
    }

    private static @Nullable Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record Row(
            UUID id,
            UUID tenantId,
            String subjectId,
            UUID grantId,
            String locale,
            String status,
            @Nullable String tokenHash,
            @Nullable Instant expiresAt,
            boolean emailGiven,
            String invitedBy,
            Instant invitedAt,
            @Nullable Instant sentAt,
            @Nullable Instant openedAt,
            @Nullable Instant acceptedAt,
            @Nullable Instant cancelledAt,
            @Nullable String cancelledBy) {}
}
