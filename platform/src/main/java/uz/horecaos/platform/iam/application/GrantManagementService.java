package uz.horecaos.platform.iam.application;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.GrantChanged;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Grants and revokes roles (ADR 0025).
 *
 * <p>Two rules keep this from becoming a privilege-escalation path. A granter
 * may only grant a role whose capabilities it already holds itself, and only at
 * a scope it already covers. Without the first, a tenant admin could mint a role
 * with capabilities it lacks; without the second, it could grant into a sibling
 * brand.
 *
 * <p>{@code platform.admin} is never grantable here at all. It is issued by
 * Keycloak per ADR 0003, and a tenant-facing API that could confer it would make
 * the whole tenant boundary decorative.
 *
 * <p>A third rule joins them: the role a grant names must be one this tenant may
 * see — the platform's, or one it defined itself. {@code iam.roles.tenant_id} is
 * nullable by design and {@code fk_grant_role} references the id alone, so
 * nothing in the schema ever related the two. See {@link #resolveRole}, and
 * V0086 for the trigger that holds the same rule when this service is not the
 * writer.
 */
@Service
public class GrantManagementService {

    private final JdbcClient jdbc;
    private final AuthorizationService authorization;
    private final JdbcAuthorizationService cacheOwner;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public GrantManagementService(
            JdbcClient jdbc,
            AuthorizationService authorization,
            JdbcAuthorizationService cacheOwner,
            ApplicationEventPublisher events,
            Clock clock) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.cacheOwner = cacheOwner;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public UUID grant(GrantCommand command, String granterSubject) {
        // Ordered the way CapabilityEnforcementInterceptor orders its own two
        // checks, and for the same reason. Resolving the role first would make
        // the pair of answers an oracle: 404 for a role code that names nothing
        // this tenant may see, an authorization failure for one that does — so a
        // principal who cannot manage grants at all could still enumerate a
        // tenant's private role codes by watching which answer arrives. Behind
        // the capability check, only a caller already entitled to administer
        // grants in this tenant reaches the resolution at all.
        authorization.require(granterSubject, Capability.IAM_GRANT_MANAGE, command.scope());

        ResolvedRole role = resolveRole(command.roleCode(), command.scope().tenantId());
        requireGrantable(role, command.scope(), granterSubject);

        UUID grantId = UUID.randomUUID();
        Instant now = clock.instant();

        jdbc.sql("""
                INSERT INTO iam.grants (
                    id, tenant_id, principal_subject, role_id, role_is_platform,
                    scope_type, scope_id,
                    status, granted_by, reason, valid_from, valid_until)
                VALUES (:id, :tenantId, :subject, :roleId, :roleIsPlatform,
                        :scopeType, :scopeId,
                        'ACTIVE', :grantedBy, :reason, :validFrom, :validUntil)
                """)
                .param("id", grantId)
                .param("roleIsPlatform", role.platformDefined())
                .param("tenantId", command.scope().tenantId())
                .param("subject", command.principalSubject())
                .param("roleId", role.id())
                .param("scopeType", command.scope().type().name())
                .param("scopeId", command.scope().scopeId())
                .param("grantedBy", granterSubject)
                .param("reason", command.reason())
                .param("validFrom", at(now))
                .param("validUntil", command.validUntil() == null ? null : at(command.validUntil()))
                .update();

        evictAndPublish(new GrantChanged(
                grantId,
                GrantChanged.Change.GRANTED,
                command.principalSubject(),
                command.scope(),
                granterSubject,
                command.reason(),
                Map.of(
                        "role",
                        role.code(),
                        "scope",
                        command.scope().type().name(),
                        "validUntil",
                        String.valueOf(command.validUntil())),
                now));
        return grantId;
    }

    /**
     * Revokes a grant belonging to {@code tenantId}.
     *
     * <p>The tenant is a parameter rather than being read from the grant row
     * because it is the tenant the caller was <em>authorised against</em>, and
     * those are only the same thing when nobody is attacking. A grant id is an
     * opaque UUID that travels through support tickets, exports and logs, so
     * treating it as proof of ownership would let any tenant's grant
     * administrator revoke another tenant's grants and lock their staff out.
     */
    @Transactional
    public boolean revoke(UUID tenantId, UUID grantId, String revokerSubject, String reason) {
        var existing = jdbc.sql("""
                SELECT principal_subject, tenant_id, scope_type, scope_id
                  FROM iam.grants
                 WHERE id = :id AND tenant_id = :tenantId AND status = 'ACTIVE'
                """)
                .param("id", grantId)
                .param("tenantId", tenantId)
                .query((rs, n) -> new RevokedGrant(
                        rs.getString("principal_subject"),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("scope_type"),
                        rs.getObject("scope_id", UUID.class)))
                .optional();

        if (existing.isEmpty()) {
            return false;
        }

        int updated = jdbc.sql("""
                UPDATE iam.grants
                   SET status = 'REVOKED', version = version + 1, updated_at = :now
                 WHERE id = :id AND tenant_id = :tenantId AND status = 'ACTIVE'
                """)
                .param("id", grantId)
                .param("tenantId", tenantId)
                .param("now", at(clock.instant()))
                .update();

        if (updated == 1) {
            RevokedGrant grant = existing.get();
            evictAndPublish(new GrantChanged(
                    grantId,
                    GrantChanged.Change.REVOKED,
                    grant.principalSubject(),
                    ResourceScope.tenant(grant.tenantId()),
                    revokerSubject,
                    reason,
                    Map.of("scope", grant.scopeType()),
                    clock.instant()));
        }
        return updated == 1;
    }

    public List<GrantView> listForTenant(UUID tenantId) {
        return jdbc.sql("""
                SELECT g.id, g.principal_subject, r.code AS role_code, g.scope_type, g.scope_id,
                       g.status, g.granted_by, g.valid_from, g.valid_until
                  FROM iam.grants g
                  JOIN iam.roles r ON r.id = g.role_id
                 WHERE g.tenant_id = :tenantId AND g.status = 'ACTIVE'
                 ORDER BY g.created_at DESC
                """)
                .param("tenantId", tenantId)
                .query((rs, n) -> new GrantView(
                        rs.getObject("id", UUID.class),
                        rs.getString("principal_subject"),
                        rs.getString("role_code"),
                        rs.getString("scope_type"),
                        rs.getObject("scope_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("granted_by")))
                .list();
    }

    /**
     * A granter may confer only what it already holds, at a scope it already
     * covers. Both halves matter: the first stops a role being used to acquire
     * capabilities, the second stops it reaching sideways.
     *
     * <p>The {@code platform.admin} refusal is stated over the resolved
     * capability set rather than over the {@link PlatformRole} enum, so it also
     * catches a tenant-defined role that lists the capability in
     * {@code iam.role_capabilities}. It composes with the resolution rule rather
     * than replacing it: a role must first be one this tenant may name, and then
     * must still not carry the superuser capability.
     */
    private void requireGrantable(ResolvedRole role, ResourceScope scope, String granterSubject) {
        if (role.capabilities().contains(Capability.PLATFORM_ADMIN)) {
            throw new IllegalArgumentException(
                    "platform.admin is issued by Keycloak and is never granted through this API");
        }

        for (Capability capability : role.capabilities()) {
            if (!authorization.has(granterSubject, capability, scope)) {
                throw new AuthorizationService.AccessDeniedException(capability, scope);
            }
        }
    }

    /**
     * The role a grant may name: the platform's, or one this tenant defined.
     *
     * <p>{@code iam.roles.tenant_id} is nullable by design — {@code
     * ck_role_ownership} splits a platform role (tenant_id NULL) from a
     * tenant-defined one (tenant_id NOT NULL) — and {@code fk_grant_role}
     * references {@code iam.roles (id)} on the id alone. The target is mixed, so
     * a composite foreign key cannot express the disjunction this method is: it
     * would have to choose between the platform's roles and the tenant's, and a
     * NULL in a MATCH SIMPLE key stops the check rather than widening it. V0086
     * holds the same rule in a trigger for every writer that is not this method;
     * this is the one that answers the caller.
     *
     * <p>A platform role is resolved from the code-owned {@link PlatformRole}
     * registry rather than from the table, so a tenant cannot define a role whose
     * code shadows {@code tenant-owner} and quietly change what that code means
     * for its own administrators. The table is consulted only for codes the
     * platform has not claimed, and only inside the grant's own tenant.
     *
     * <p>Every refusal is the same refusal. A code that names nothing, a code
     * that names a retired role, and a code that names a role another tenant
     * defined all raise {@link NoSuchRoleException} with one message: telling a
     * caller that a role belongs to somebody else confirms that it exists, and a
     * role code is a name a tenant chose — {@code night-audit-override} is worth
     * knowing about. This is {@code requireRealScope}'s trade, made again.
     *
     * @param tenantId the tenant the grant will belong to, which is null for a
     *                 platform-scope grant — and a platform-scope grant can
     *                 therefore only ever name a platform role
     */
    private ResolvedRole resolveRole(String roleCode, UUID tenantId) {
        Optional<PlatformRole> platformRole = PlatformRole.find(roleCode);
        if (platformRole.isPresent()) {
            PlatformRole role = platformRole.get();
            return new ResolvedRole(
                    RoleRegistrySynchronizer.platformRoleId(role), role.code(), role.capabilities(), true);
        }

        // A platform-scope grant belongs to no tenant, so there is no tenant whose
        // roles it could name. Stated here rather than left to `tenant_id = NULL`
        // matching nothing, because a rule that works by accident of SQL's
        // three-valued logic reads as an oversight the next time somebody edits it.
        if (tenantId == null) {
            throw new NoSuchRoleException();
        }

        UUID roleId = jdbc.sql("""
                SELECT id
                  FROM iam.roles
                 WHERE code = :code
                   AND tenant_id = :tenantId
                   AND status = 'ACTIVE'
                """)
                .param("code", roleCode)
                .param("tenantId", tenantId)
                .query(UUID.class)
                .optional()
                .orElseThrow(NoSuchRoleException::new);

        // A tenant-defined role's capabilities live in the table; a platform
        // role's live in code. Both are then subject to the same two rules
        // above, which is the point of resolving to one shape here.
        Set<Capability> capabilities = jdbc.sql("""
                SELECT capability_code FROM iam.role_capabilities WHERE role_id = :roleId
                """).param("roleId", roleId).query(String.class).list().stream()
                .map(Capability::require)
                .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(Capability.class)));

        return new ResolvedRole(roleId, roleCode, capabilities, false);
    }

    /**
     * Eviction happens with the change, not on a timer. ADR 0033 gives grants
     * the shortest TTL in the registry precisely because a stale allow is the
     * worst kind of stale, and waiting for it to expire after a deliberate
     * revocation would be careless.
     */
    private void evictAndPublish(GrantChanged event) {
        cacheOwner.evictGrants(event.principalSubject(), event.scope().tenantId());
        events.publishEvent(event);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * The role code named nothing this tenant may grant.
     *
     * <p>Deliberately carries no detail. It is raised identically for a code that
     * exists nowhere, a code whose role is retired, and a code that belongs to
     * another tenant, because the three answers are only distinguishable to an
     * attacker: the first two are useless and the third is an existence oracle
     * for another tenant's private role names. Rendered as ADR 0031
     * {@code RESOURCE_NOT_FOUND}, which is the answer
     * {@code CapabilityEnforcementInterceptor.requireRealScope} gives to the
     * same question about a scope.
     */
    public static final class NoSuchRoleException extends ApiException {
        public NoSuchRoleException() {
            super(ErrorCode.RESOURCE_NOT_FOUND, "No such role");
        }
    }

    /**
     * A role that may be granted, resolved from wherever it is defined.
     *
     * @param id           the {@code iam.roles} row this grant will reference
     * @param code         the role code, for the audit entry
     * @param capabilities from {@link PlatformRole} for a platform role, from
     *                     {@code iam.role_capabilities} for a tenant-defined one
     */
    /**
     * @param platformDefined whether this is a platform role rather than one the
     *                        tenant defined. Written onto the grant as
     *                        {@code role_is_platform}, which V0089's foreign key
     *                        then makes true: the derived owner is the platform
     *                        sentinel or this grant's own tenant, and a claim that
     *                        does not match the role is refused by the key rather
     *                        than trusted.
     */
    private record ResolvedRole(UUID id, String code, Set<Capability> capabilities, boolean platformDefined) {}

    /** @param validUntil null for an open-ended grant; set it for support access */
    public record GrantCommand(
            String principalSubject, String roleCode, ResourceScope scope, String reason, Instant validUntil) {}

    public record GrantView(
            UUID id,
            String principalSubject,
            String roleCode,
            String scopeType,
            UUID scopeId,
            String status,
            String grantedBy) {}

    private record RevokedGrant(String principalSubject, UUID tenantId, String scopeType, UUID scopeId) {}
}
