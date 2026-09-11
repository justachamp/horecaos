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

    /**
     * The invitation a link names.
     *
     * <p>No {@code FOR UPDATE}. It used to take one so an accept and a resend
     * could not cross, which meant the lock was held for the whole of the two
     * Keycloak calls the accept then made. The guards do that work instead:
     * {@link #markAccepted} insists the row is still {@code SENT} and {@link
     * #requeue} that it is not yet {@code ACCEPTED}, so whichever of the two
     * arrives second is refused rather than made to wait behind a round trip to
     * another server.
     */
    public Optional<Row> byTokenHash(String tokenHash) {
        return jdbc.sql("SELECT " + COLUMNS + """
                          FROM tenant.owner_invitations
                         WHERE token_hash = :hash
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
     * @return true when this attempt was still the current one, so the retry
     *         applied; false when a newer attempt or an operator's resend has
     *         moved the row on
     */
    public boolean markRetry(UUID id, int attempt, Instant nextAttemptAt, String errorCode, boolean countsAsAttempt) {
        return jdbc.sql("""
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
                        .update()
                > 0;
    }

    /** @return true when this attempt was still the current one, so the failure applied */
    public boolean markFailed(UUID id, int attempt, String errorCode) {
        return jdbc.sql("""
                        UPDATE tenant.owner_invitations
                           SET status = 'FAILED', next_attempt_at = NULL, last_error_code = :code,
                               version = version + 1
                         WHERE id = :id AND status = 'QUEUED' AND attempts = :attempt
                        """)
                        .param("id", id)
                        .param("attempt", attempt)
                        .param("code", errorCode)
                        .update()
                > 0;
    }

    /** @return true when this attempt was still the current one, so the row was settled */
    public boolean markNotNeeded(UUID id, int attempt) {
        return jdbc.sql("""
                                UPDATE tenant.owner_invitations
                                   SET status = 'NOT_NEEDED', next_attempt_at = NULL, last_error_code = NULL,
                                       version = version + 1
                                 WHERE id = :id AND status = 'QUEUED' AND attempts = :attempt
                                """).param("id", id).param("attempt", attempt).update() > 0;
    }

    /**
     * The first time the link was opened; later opens change nothing.
     *
     * @return true when this was the first open, so the history records one
     *         open rather than one per mail scanner that followed the link
     */
    public boolean markOpened(UUID id, Instant now) {
        return jdbc.sql("""
                        UPDATE tenant.owner_invitations
                           SET opened_at = :now, version = version + 1
                         WHERE id = :id AND status = 'SENT' AND opened_at IS NULL
                        """).param("id", id).param("now", utc(now)).update() > 0;
    }

    /**
     * Spends the link: the hash goes, so the same token can never be used again.
     *
     * <p>Called before the password is set, not after, so that two requests
     * holding one link cannot both reach the identity provider. The {@code
     * status = 'SENT'} guard is what decides between them, and what refuses an
     * accept that an operator's resend got to first.
     *
     * @return true when this call spent it
     */
    public boolean markAccepted(UUID id, Instant now) {
        return jdbc.sql("""
                        UPDATE tenant.owner_invitations
                           SET status = 'ACCEPTED', token_hash = NULL, accepted_at = :now,
                               opened_at = COALESCE(opened_at, :now), version = version + 1
                         WHERE id = :id AND status = 'SENT'
                        """).param("id", id).param("now", utc(now)).update() > 0;
    }

    /**
     * Gives a spent link back, because the password it was spent for was refused
     * by the identity provider's policy and nothing happened.
     *
     * <p>Guarded on this caller's own spend -- the instant it wrote into {@code
     * accepted_at}, with the hash still cleared -- so a restore can only ever
     * undo the acceptance it belongs to, and never one that succeeded a moment
     * later. {@code expires_at} and {@code sent_at} were untouched by the spend,
     * which is why the row can go back to SENT at all; {@code opened_at} stays,
     * because the owner really did open it.
     *
     * @return true when the link was given back; false when something else has
     *         moved the row on, which leaves it moved on
     */
    public boolean restoreLink(UUID id, String tokenHash, Instant acceptedAt) {
        return jdbc.sql("""
                        UPDATE tenant.owner_invitations
                           SET status = 'SENT', token_hash = :hash, accepted_at = NULL,
                               version = version + 1
                         WHERE id = :id AND status = 'ACCEPTED' AND token_hash IS NULL
                           AND accepted_at = :acceptedAt AND expires_at IS NOT NULL AND sent_at IS NOT NULL
                        """)
                        .param("id", id)
                        .param("hash", tokenHash)
                        .param("acceptedAt", utc(acceptedAt))
                        .update()
                > 0;
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

    /**
     * Every tenant whose owner matters, with its latest invitation if it has
     * one (ADR 0100).
     *
     * <p>The left join is the point. A tenant onboarded before invitations
     * existed has a completed owner step and no invitation row at all, and a
     * list that started from {@code owner_invitations} would leave out exactly
     * the tenants nobody has told yet. A tenant with neither an invitation nor
     * a linked owner has no owner to chase and is not listed; an archived
     * tenant is nobody's work and is not either.
     *
     * @param limit a hard cap, because each row costs one identity-provider
     *        read to resolve its recipient. Tenants whose owner is already set
     *        up sort last, so the cap is reached on those first and a caller
     *        asking for the outstanding ones gets all of them until there are
     *        more than {@code limit} outstanding
     */
    public List<OverviewRow> overview(int limit) {
        return jdbc.sql("""
                        SELECT t.id AS tenant_id, t.slug, t.display_name, t.status AS tenant_status,
                               i.id AS invitation_id, COALESCE(i.subject_id, o.external_reference) AS subject_id,
                               i.status, i.locale, i.attempts, i.last_error_code,
                               i.queued_at, i.sent_at, i.opened_at, i.accepted_at, i.expires_at
                          FROM tenant.tenants t
                          LEFT JOIN LATERAL (
                              SELECT oi.id, oi.subject_id, oi.status, oi.locale, oi.attempts,
                                     oi.last_error_code, oi.queued_at, oi.sent_at, oi.opened_at,
                                     oi.accepted_at, oi.expires_at
                                FROM tenant.owner_invitations oi
                               WHERE oi.tenant_id = t.id
                               ORDER BY oi.queued_at DESC, oi.id
                               LIMIT 1) i ON true
                          LEFT JOIN LATERAL (
                              SELECT s.external_reference
                                FROM tenant.onboarding_steps s
                                JOIN tenant.onboarding_runs r ON r.id = s.run_id
                               WHERE r.tenant_id = t.id
                                 AND s.step_key = 'TENANT_OWNER_LINK_OR_INVITE'
                                 AND s.status = 'COMPLETED'
                                 AND s.external_reference IS NOT NULL
                               ORDER BY r.started_at DESC
                               LIMIT 1) o ON true
                         WHERE t.status <> 'ARCHIVED'
                           AND (i.id IS NOT NULL OR o.external_reference IS NOT NULL)
                         -- Settled last. The cap is applied by the database and the
                         -- state filter by the caller, so whatever this ORDER BY puts
                         -- beyond :limit is what the screen will not have. Sorting
                         -- alphabetically would make that an arbitrary letter; sorting
                         -- settled last makes it "we ran out of room for tenants whose
                         -- owner is already set up", which is the truncation to want.
                         -- COALESCE, not a bare IN: a tenant with no invitation row has
                         -- a NULL status, NULL IN (...) is NULL, and NULL sorts last --
                         -- which would drop the never-invited tenants first of all.
                         ORDER BY (COALESCE(i.status, 'NONE') IN ('ACCEPTED', 'NOT_NEEDED')),
                                  t.display_name, t.id
                         LIMIT :limit
                        """)
                .param("limit", limit)
                .query((row, number) -> new OverviewRow(
                        java.util.Objects.requireNonNull(row.getObject("tenant_id", UUID.class)),
                        row.getString("slug"),
                        row.getString("display_name"),
                        row.getString("tenant_status"),
                        row.getObject("invitation_id", UUID.class),
                        row.getString("subject_id"),
                        row.getString("status"),
                        row.getString("locale"),
                        row.getObject("attempts", Integer.class),
                        row.getString("last_error_code"),
                        instant(row, "queued_at"),
                        instant(row, "sent_at"),
                        instant(row, "opened_at"),
                        instant(row, "accepted_at"),
                        instant(row, "expires_at")))
                .list();
    }

    /**
     * Where every unarchived tenant's owner stands, and nothing else (ADR
     * 0100): an identifier and a status, with no {@code subject_id} to resolve
     * and therefore no identity-provider read and no address anywhere on the
     * path.
     *
     * <p>Two differences from {@link #overview(int)} carry the whole point of
     * having a second query. It selects no subject, so a caller cannot resolve
     * an address from what it returns. And it keeps the tenant that has neither
     * an invitation nor a linked owner -- which {@code overview} drops as
     * nobody's work -- because a column that says nothing about a tenant it was
     * never told about is the column that claimed an owner existed.
     *
     * @param limit a cap for the same reason any unbounded list has one, not
     *        because a row is expensive: a tenant beyond it is simply absent,
     *        and a caller that has to say something about an absent tenant must
     *        say it does not know
     */
    public List<OwnerStateRow> ownerStates(int limit) {
        return jdbc.sql("""
                        SELECT t.id AS tenant_id, i.status, i.expires_at,
                               (i.id IS NOT NULL OR o.external_reference IS NOT NULL) AS owner_known
                          FROM tenant.tenants t
                          LEFT JOIN LATERAL (
                              SELECT oi.id, oi.status, oi.expires_at
                                FROM tenant.owner_invitations oi
                               WHERE oi.tenant_id = t.id
                               ORDER BY oi.queued_at DESC, oi.id
                               LIMIT 1) i ON true
                          LEFT JOIN LATERAL (
                              SELECT s.external_reference
                                FROM tenant.onboarding_steps s
                                JOIN tenant.onboarding_runs r ON r.id = s.run_id
                               WHERE r.tenant_id = t.id
                                 AND s.step_key = 'TENANT_OWNER_LINK_OR_INVITE'
                                 AND s.status = 'COMPLETED'
                                 AND s.external_reference IS NOT NULL
                               ORDER BY r.started_at DESC
                               LIMIT 1) o ON true
                         WHERE t.status <> 'ARCHIVED'
                         ORDER BY t.display_name, t.id
                         LIMIT :limit
                        """)
                .param("limit", limit)
                .query((row, number) -> new OwnerStateRow(
                        java.util.Objects.requireNonNull(row.getObject("tenant_id", UUID.class)),
                        row.getString("status"),
                        instant(row, "expires_at"),
                        row.getBoolean("owner_known")))
                .list();
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

    /**
     * One tenant on the cross-tenant overview. Every invitation field is absent
     * for a tenant whose owner was linked but never invited; {@code subjectId}
     * is then the owner that onboarding linked.
     */
    public record OverviewRow(
            UUID tenantId,
            String tenantSlug,
            String tenantName,
            String tenantStatus,
            @Nullable UUID invitationId,
            @Nullable String subjectId,
            @Nullable String status,
            @Nullable String locale,
            @Nullable Integer attempts,
            @Nullable String lastErrorCode,
            @Nullable Instant queuedAt,
            @Nullable Instant sentAt,
            @Nullable Instant openedAt,
            @Nullable Instant acceptedAt,
            @Nullable Instant expiresAt) {}

    /**
     * One tenant on the address-free projection: where its owner stands and
     * nothing more.
     *
     * @param status the latest invitation's stored status, absent when the
     *        tenant has no invitation row
     * @param ownerKnown whether an owner exists to chase at all -- an
     *        invitation, or an onboarding run that linked one
     */
    public record OwnerStateRow(
            UUID tenantId,
            @Nullable String status,
            @Nullable Instant expiresAt,
            boolean ownerKnown) {}

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
