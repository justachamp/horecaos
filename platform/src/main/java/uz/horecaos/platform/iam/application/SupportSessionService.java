package uz.horecaos.platform.iam.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.SupportSessionChanged;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Support sessions: how a HorecaOS support person enters one tenant (ADR 0081).
 *
 * <p>A session is a grant of a support-only role that ends by itself, opened
 * with a reason the tenant can read. The grant does the authorizing, on the
 * ordinary path, and {@code valid_until} ends it whether or not anything here
 * runs again; this service keeps the account of it and ends it early.
 */
@Service
public class SupportSessionService {

    /** The shortest window worth opening; anything less is a mistyped minute count. */
    public static final Duration SHORTEST = Duration.ofMinutes(15);

    /** The longest a person should be inside someone else's account without asking again. */
    public static final Duration LONGEST = Duration.ofHours(4);

    private final JdbcClient jdbc;
    private final GrantManagementService grants;
    private final AuthorizationService authorization;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public SupportSessionService(
            JdbcClient jdbc,
            GrantManagementService grants,
            AuthorizationService authorization,
            ApplicationEventPublisher events,
            Clock clock) {
        this.jdbc = jdbc;
        this.grants = grants;
        this.authorization = authorization;
        this.events = events;
        this.clock = clock;
    }

    /** How much a session lets its holder do. */
    public enum Access {
        /** Look, never change. */
        VIEW(PlatformRole.SUPPORT_SESSION_VIEW),
        /** Look, and do the order-floor acts a location manager could. */
        ASSIST(PlatformRole.SUPPORT_SESSION_ASSIST);

        private final PlatformRole role;

        Access(PlatformRole role) {
            this.role = role;
        }

        public PlatformRole role() {
            return role;
        }
    }

    @Transactional
    public SupportSession open(
            UUID tenantId,
            String staffSubject,
            Access access,
            String reason,
            @Nullable String ticketReference,
            Duration length) {

        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A support session states why it is opened");
        }
        if (length.compareTo(SHORTEST) < 0 || length.compareTo(LONGEST) > 0) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "A support session lasts between 15 minutes and 4 hours");
        }
        String status = jdbc.sql("SELECT status FROM tenant.tenants WHERE id = :id")
                .param("id", tenantId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such tenant"));
        if ("ARCHIVED".equals(status)) {
            // An archived tenant keeps no grants at all (ADR 0078), so a session
            // would open onto nothing; refusing says so instead of pretending.
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "An archived tenant cannot be entered");
        }

        Instant now = clock.instant();
        Instant until = now.plus(length);
        UUID grantId = grants.grantForSupportSession(
                new GrantManagementService.GrantCommand(
                        staffSubject, access.role().code(), ResourceScope.tenant(tenantId), reason.strip(), until),
                staffSubject);

        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO iam.support_sessions
                    (id, tenant_id, principal_subject, access, grant_id, reason, ticket_reference,
                     started_at, expires_at)
                VALUES (:id, :tenantId, :subject, :access, :grantId, :reason, :ticket, :startedAt, :expiresAt)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("subject", staffSubject)
                .param("access", access.name())
                .param("grantId", grantId)
                .param("reason", reason.strip())
                .param("ticket", blankToNull(ticketReference))
                .param("startedAt", utc(now))
                .param("expiresAt", utc(until))
                .update();

        Map<String, Object> details = new HashMap<>();
        details.put("access", access.name());
        details.put("expiresAt", until.toString());
        String ticket = blankToNull(ticketReference);
        if (ticket != null) {
            details.put("ticketReference", ticket);
        }
        events.publishEvent(new SupportSessionChanged(
                id,
                tenantId,
                SupportSessionChanged.Change.OPENED,
                staffSubject,
                staffSubject,
                reason.strip(),
                details,
                now));
        return find(tenantId, id).orElseThrow();
    }

    /**
     * Ends a session before its deadline by revoking the grant it conferred.
     *
     * <p>The person inside may end their own. A platform administrator may end
     * anyone's, and so may the tenant's own administrator — it is their account
     * the session is in. Ending one that already ended changes nothing.
     */
    @Transactional
    public SupportSession end(UUID tenantId, UUID sessionId, String actorSubject, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Ending a support session states why");
        }
        SupportSession session = find(tenantId, sessionId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such support session"));
        if (session.endedAt() != null) {
            return session;
        }
        boolean own = session.principalSubject().equals(actorSubject);
        if (!own
                && !authorization.has(actorSubject, Capability.PLATFORM_ADMIN, ResourceScope.platform())
                && !authorization.has(actorSubject, Capability.IAM_GRANT_MANAGE, ResourceScope.tenant(tenantId))) {
            throw new AuthorizationService.AccessDeniedException(
                    Capability.IAM_GRANT_MANAGE, ResourceScope.tenant(tenantId));
        }

        Instant now = clock.instant();
        grants.revoke(tenantId, session.grantId(), actorSubject, reason.strip());
        jdbc.sql("""
                UPDATE iam.support_sessions
                   SET ended_at = :now, ended_by = :by, end_reason = :reason
                 WHERE tenant_id = :tenantId AND id = :id AND ended_at IS NULL
                """)
                .param("now", utc(now))
                .param("by", actorSubject)
                .param("reason", reason.strip())
                .param("tenantId", tenantId)
                .param("id", sessionId)
                .update();

        events.publishEvent(new SupportSessionChanged(
                sessionId,
                tenantId,
                SupportSessionChanged.Change.ENDED,
                session.principalSubject(),
                actorSubject,
                reason.strip(),
                Map.of("access", session.access().name(), "early", now.isBefore(session.expiresAt())),
                now));
        return find(tenantId, sessionId).orElseThrow();
    }

    /** A tenant's sessions, newest first: the record of who from HorecaOS came in. */
    @Transactional(readOnly = true)
    public List<SupportSession> listForTenant(UUID tenantId, int limit) {
        return jdbc.sql(SELECT + " WHERE tenant_id = :tenantId ORDER BY started_at DESC, id DESC LIMIT :limit")
                .param("tenantId", tenantId)
                .param("limit", Math.clamp(limit, 1, 200))
                .query(SupportSessionService::map)
                .list();
    }

    /** A support person's sessions that are still open, soonest to end first. */
    @Transactional(readOnly = true)
    public List<SupportSession> openFor(String staffSubject) {
        return jdbc.sql(SELECT + """
                 WHERE principal_subject = :subject AND ended_at IS NULL AND expires_at > :now
                 ORDER BY expires_at
                """)
                .param("subject", staffSubject)
                .param("now", utc(clock.instant()))
                .query(SupportSessionService::map)
                .list();
    }

    /** The caller's own open session in this tenant, for the banner shown to them inside it. */
    @Transactional(readOnly = true)
    public Optional<SupportSession> currentFor(String staffSubject, UUID tenantId) {
        return openFor(staffSubject).stream()
                .filter(session -> session.tenantId().equals(tenantId))
                .findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<SupportSession> find(UUID tenantId, UUID sessionId) {
        return jdbc.sql(SELECT + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", sessionId)
                .query(SupportSessionService::map)
                .optional();
    }

    /** Whether the session is still in force at this instant. */
    public boolean isOpen(SupportSession session) {
        return session.endedAt() == null && session.expiresAt().isAfter(clock.instant());
    }

    private static final String SELECT = """
            SELECT id, tenant_id, principal_subject, access, grant_id, reason, ticket_reference,
                   started_at, expires_at, ended_at, ended_by, end_reason
              FROM iam.support_sessions
            """;

    private static SupportSession map(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        OffsetDateTime ended = row.getObject("ended_at", OffsetDateTime.class);
        return new SupportSession(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getString("principal_subject"),
                Access.valueOf(row.getString("access")),
                row.getObject("grant_id", UUID.class),
                row.getString("reason"),
                row.getString("ticket_reference"),
                row.getObject("started_at", OffsetDateTime.class).toInstant(),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                ended == null ? null : ended.toInstant(),
                row.getString("ended_by"),
                row.getString("end_reason"));
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** One support visit, as stored. */
    public record SupportSession(
            UUID id,
            UUID tenantId,
            String principalSubject,
            Access access,
            UUID grantId,
            String reason,
            @Nullable String ticketReference,
            Instant startedAt,
            Instant expiresAt,
            @Nullable Instant endedAt,
            @Nullable String endedBy,
            @Nullable String endReason) {}
}
