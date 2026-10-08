package uz.horecaos.platform.iam.application;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.GrantChanged;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.TenantRoleCatalog;
import uz.horecaos.platform.iam.api.grants.GrantAuthority;
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
 *
 * <p><strong>{@code grant} and {@code revoke} already work for {@code PLATFORM}
 * scope</strong> (Gap A of the 2026-08-30 proving run): {@link #resolveRole}
 * resolves a {@link PlatformRole} without needing a tenant at all, and {@link
 * #revoke} matches a {@code NULL} {@code tenant_id} correctly. Deliberately
 * this class still does not know that a {@code PLATFORM} grant is the
 * highest-authority action in the system and needs ADR 0027's maker-checker
 * before it takes effect — putting that here would give {@code iam} a
 * dependency on {@code audit}, and {@code audit} already depends on {@code
 * iam} for {@link ResourceScope} and {@link uz.horecaos.platform.iam.api.CurrentActor},
 * so the reverse edge would close a module cycle {@code ModularArchitectureTests}
 * exists to catch. {@code audit.application.PlatformGrantService} is where
 * that gate lives instead, calling {@link #grant} and {@link #revoke}
 * unchanged after the approval clears — the same shape {@code
 * ApprovalDecisionService} already uses to depend on {@code iam.api} without
 * {@code iam} ever depending back.
 */
@Service
public class GrantManagementService implements GrantAuthority {

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

    /**
     * {@link GrantAuthority}'s own seam for a caller outside {@code iam} —
     * translates into {@link #grant(GrantCommand, String)}, the same
     * enforcement, just without exposing this class's own nested command
     * type across the module boundary.
     */
    @Override
    @Transactional
    public UUID grant(
            String principalSubject,
            String roleCode,
            ResourceScope scope,
            String reason,
            @Nullable Instant validUntil,
            String granterSubject) {
        return grant(new GrantCommand(principalSubject, roleCode, scope, reason, validUntil), granterSubject);
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

        UUID grantId = insertGrantRow(command, role, granterSubject, clock.instant());
        evictAndPublish(new GrantChanged(
                grantId,
                GrantChanged.Change.GRANTED,
                command.principalSubject(),
                command.scope(),
                granterSubject,
                command.reason(),
                // Staff 9.3a: a freshly inserted grant has no prior state.
                Map.of(),
                Map.of(
                        "role",
                        role.code(),
                        "scope",
                        command.scope().type().name(),
                        "validUntil",
                        String.valueOf(command.validUntil())),
                correlationIdFor(grantId),
                clock.instant()));
        return grantId;
    }

    private UUID insertGrantRow(GrantCommand command, ResolvedRole role, String grantedBy, Instant now) {
        UUID grantId = UUID.randomUUID();
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
                .param("grantedBy", grantedBy)
                .param("reason", command.reason())
                .param("validFrom", at(now))
                .param("validUntil", command.validUntil() == null ? null : at(command.validUntil()))
                .update();
        return grantId;
    }

    /**
     * Grants a role as a system-initiated consequence of a platform process,
     * never as a person exercising {@code IAM_GRANT_MANAGE}.
     *
     * <p>{@link uz.horecaos.platform.iam.api.grants.TenantOwnerAuthorityGrantor}
     * (ADR 0009's missing half) is the caller this exists for: once the
     * onboarding workflow confirms who the tenant owner is, the platform
     * itself confers the role, the same way {@code StorefrontChannelSeeder}
     * gives every new tenant its channel as "a consequence ADR 0036 attaches"
     * rather than an action anyone asked for. Two things distinguish this
     * from {@link #grant}, both because there is no human granter to consult:
     *
     * <ul>
     *   <li>Skips {@code authorization.require} and {@link #requireGrantable}.
     *       Both exist to stop a caller minting authority it does not itself
     *       hold, which is a question about a *person's* existing grants. It
     *       does not apply to the workflow deciding, unconditionally, that
     *       {@code roleCode} is exactly what ADR 0009 always intended to
     *       confer — a background job has no session to hold a grant on, and
     *       building one (a synthetic {@code Authentication} carrying
     *       {@code platform-admin}) would be the more dangerous path: forging
     *       the exact credential {@link JdbcAuthorizationService}'s bootstrap
     *       bypass exists to gate.
     *   <li>Idempotent by lookup rather than by catching the unique-index
     *       violation {@link #grant} would throw on a repeat. ADR 0008 requires
     *       every step handler to be safe to run again; a retried
     *       {@code TENANT_OWNER_LINK_OR_INVITE} must find the grant already
     *       there and complete, not fail on a constraint it cannot see coming.
     * </ul>
     *
     * <p>{@code platform.admin} is still never grantable here — the one check
     * from {@link #requireGrantable} this method keeps, because a background
     * job minting the superuser bundle would be worse than a person doing it
     * by mistake.
     *
     * @param systemActor a stable, non-human identifier (never a Keycloak
     *                    subject) recorded as {@code granted_by}, so the audit
     *                    trail can tell a platform action from an
     *                    administrator's
     */
    @Transactional
    public UUID grantSystemInitiated(GrantCommand command, String systemActor) {
        ResolvedRole role = resolveRole(command.roleCode(), command.scope().tenantId());
        if (role.capabilities().contains(Capability.PLATFORM_ADMIN)) {
            throw new IllegalArgumentException(
                    "platform.admin is issued by Keycloak and is never granted through this API");
        }

        Optional<UUID> existing = existingActiveGrant(command.principalSubject(), role.id(), command.scope());
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID grantId = insertGrantRow(command, role, systemActor, clock.instant());

        evictAndPublish(new GrantChanged(
                grantId,
                GrantChanged.Change.GRANTED,
                command.principalSubject(),
                command.scope(),
                systemActor,
                command.reason(),
                // Staff 9.3a: a freshly inserted grant has no prior state.
                Map.of(),
                Map.of(
                        "role",
                        role.code(),
                        "scope",
                        command.scope().type().name(),
                        "validUntil",
                        String.valueOf(command.validUntil())),
                correlationIdFor(grantId),
                clock.instant()));
        return grantId;
    }

    /**
     * Confers a support-session role for the length of one session (ADR 0081).
     *
     * <p>Not {@link #grant}: the person opening a session holds a platform
     * capability to do so rather than {@code IAM_GRANT_MANAGE} inside the
     * tenant, and the role is one nobody grants by hand. Not {@link
     * #grantSystemInitiated} either, which hands back an existing active grant
     * as it is — and a session whose window has closed leaves exactly such a
     * row behind, still {@code ACTIVE} with a {@code valid_until} in the past.
     * Reusing it would open a new session on a grant that had already expired,
     * so a lapsed one is retired here first, with its own reason, and the
     * one-active-grant rule ({@code uq_grant_active}) keeps meaning one open
     * session per person per tenant per role.
     *
     * @throws ApiException {@code RESOURCE_CONFLICT} when the same person
     *                      already has an unexpired session of this kind here
     */
    @Transactional
    public UUID grantForSupportSession(GrantCommand command, String staffSubject) {
        if (command.validUntil() == null) {
            throw new IllegalArgumentException("A support session always ends");
        }
        PlatformRole platformRole = PlatformRole.find(command.roleCode())
                .filter(PlatformRole::supportSessionOnly)
                .orElseThrow(() -> new IllegalArgumentException(
                        "A support session confers a support-session role and nothing else"));
        ResolvedRole role = resolveRole(platformRole.code(), command.scope().tenantId());
        Instant now = clock.instant();

        jdbc.sql("""
                UPDATE iam.grants
                   SET status = 'REVOKED', version = version + 1, updated_at = :now,
                       revoked_at = :now, revoked_by = :revokedBy,
                       revoked_reason = 'The support session it was opened for had ended'
                 WHERE principal_subject = :subject AND role_id = :roleId
                   AND scope_type = :scopeType AND scope_id IS NOT DISTINCT FROM :scopeId
                   AND status = 'ACTIVE' AND valid_until IS NOT NULL AND valid_until <= :now
                """)
                .param("now", at(now))
                .param("revokedBy", staffSubject)
                .param("subject", command.principalSubject())
                .param("roleId", role.id())
                .param("scopeType", command.scope().type().name())
                .param("scopeId", command.scope().scopeId())
                .update();

        if (existingActiveGrant(command.principalSubject(), role.id(), command.scope())
                .isPresent()) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "A support session of this kind is already open for this tenant");
        }

        UUID grantId = insertGrantRow(command, role, staffSubject, now);
        evictAndPublish(new GrantChanged(
                grantId,
                GrantChanged.Change.GRANTED,
                command.principalSubject(),
                command.scope(),
                staffSubject,
                command.reason(),
                // Staff 9.3a: a freshly inserted grant has no prior state.
                Map.of(),
                Map.of(
                        "role",
                        role.code(),
                        "scope",
                        command.scope().type().name(),
                        "validUntil",
                        String.valueOf(command.validUntil())),
                correlationIdFor(grantId),
                now));
        return grantId;
    }

    /**
     * Matches {@code uq_grant_active} exactly: one active grant per
     * (subject, role, scope-type, scope-id-or-the-platform-sentinel).
     */
    private Optional<UUID> existingActiveGrant(String subject, UUID roleId, ResourceScope scope) {
        return jdbc.sql("""
                SELECT id FROM iam.grants
                 WHERE principal_subject = :subject AND role_id = :roleId
                   AND scope_type = :scopeType
                   AND coalesce(scope_id, '00000000-0000-0000-0000-000000000000'::uuid)
                     = coalesce(:scopeId, '00000000-0000-0000-0000-000000000000'::uuid)
                   AND status = 'ACTIVE'
                """)
                .param("subject", subject)
                .param("roleId", roleId)
                .param("scopeType", scope.type().name())
                .param("scopeId", scope.scopeId())
                .query(UUID.class)
                .optional();
    }

    /**
     * Revokes a grant belonging to {@code tenantId}, or — Gap A — a {@code
     * PLATFORM}-scope grant when {@code tenantId} is {@code null}.
     *
     * <p>The tenant is a parameter rather than being read from the grant row
     * because it is the tenant the caller was <em>authorised against</em>, and
     * those are only the same thing when nobody is attacking. A grant id is an
     * opaque UUID that travels through support tickets, exports and logs, so
     * treating it as proof of ownership would let any tenant's grant
     * administrator revoke another tenant's grants and lock their staff out.
     *
     * <p>{@code IS NOT DISTINCT FROM} rather than {@code =} on {@code
     * tenant_id}: SQL's three-valued logic means {@code tenant_id = NULL} is
     * never true, so a caller passing {@code tenantId = null} to reach a
     * {@code PLATFORM} grant (whose row genuinely has a {@code NULL} {@code
     * tenant_id} — {@code ck_grant_scope_id} makes the two facts identical)
     * used to match nothing at all. This is a strict widening: for every
     * non-null {@code tenantId} the two operators agree exactly, so no
     * existing tenant-scoped caller changes behaviour.
     */
    @Override
    @Transactional
    public boolean revoke(@Nullable UUID tenantId, UUID grantId, String revokerSubject, String reason) {
        var existing = jdbc.sql("""
                SELECT principal_subject, tenant_id, scope_type, scope_id
                  FROM iam.grants
                 WHERE id = :id AND tenant_id IS NOT DISTINCT FROM :tenantId AND status = 'ACTIVE'
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

        Instant now = clock.instant();
        // V0127: the revocation's own reason and actor, kept apart from the
        // grant's own `reason` (why it was created) so a suspended person's
        // row can show why access was taken away, not why it was given.
        int updated = jdbc.sql("""
                UPDATE iam.grants
                   SET status = 'REVOKED', version = version + 1, updated_at = :now,
                       revoked_at = :now, revoked_by = :revokedBy, revoked_reason = :revokedReason
                 WHERE id = :id AND tenant_id IS NOT DISTINCT FROM :tenantId AND status = 'ACTIVE'
                """)
                .param("id", grantId)
                .param("tenantId", tenantId)
                .param("now", at(now))
                .param("revokedBy", revokerSubject)
                .param("revokedReason", reason)
                .update();

        if (updated == 1) {
            RevokedGrant grant = existing.get();
            evictAndPublish(new GrantChanged(
                    grantId,
                    GrantChanged.Change.REVOKED,
                    grant.principalSubject(),
                    grant.tenantId() == null ? ResourceScope.platform() : ResourceScope.tenant(grant.tenantId()),
                    revokerSubject,
                    reason,
                    // Staff 9.3a: "status" genuinely moves from ACTIVE (the
                    // WHERE status = 'ACTIVE' guard above proves it) to
                    // REVOKED.
                    Map.of("status", "ACTIVE", "scope", grant.scopeType()),
                    Map.of("status", "REVOKED", "scope", grant.scopeType()),
                    correlationIdFor(grantId),
                    clock.instant()));
        }
        return updated == 1;
    }

    /**
     * The grants given at one branch, or inside one brand, for a granter whose
     * {@code iam.grant.manage} reaches no further (ADR 0103) — "the Chilonzor
     * manager sees Chilonzor's team".
     *
     * <p>Exactly the grants whose own scope lies at or beneath {@code boundary}:
     * for a location, that location's; for a brand, the brand's own and every one
     * of its locations'. Never a company-level or platform grant, and never a
     * grant of another brand, which is the point — the query is written against
     * the boundary rather than filtering {@link #listForTenant}'s answer after the
     * fact, so a bug in a filter cannot leak a sibling's team.
     *
     * @param boundary a {@code BRAND} or {@code LOCATION} scope; anything wider is
     *                 {@link #listForTenant}'s business
     */
    public List<GrantView> listWithin(ResourceScope boundary, boolean includeInactive) {
        requireBranchBoundary(boundary);
        String within = boundary.type() == ResourceScope.ScopeType.LOCATION
                ? "(g.scope_type = 'LOCATION' AND g.scope_id = :locationId)"
                : """
                          ((g.scope_type = 'BRAND' AND g.scope_id = :brandId)
                            OR (g.scope_type = 'LOCATION' AND g.scope_id IN (
                                  SELECT l.id FROM tenant.locations l
                                   WHERE l.tenant_id = :tenantId AND l.brand_id = :brandId)))
                          """;
        return jdbc.sql("""
                SELECT g.id, g.principal_subject, r.code AS role_code, g.scope_type, g.scope_id,
                       g.status, g.granted_by, g.reason, g.valid_from, g.valid_until,
                       g.revoked_at, g.revoked_by, g.revoked_reason
                  FROM iam.grants g
                  JOIN iam.roles r ON r.id = g.role_id
                 WHERE g.tenant_id = :tenantId AND (:includeInactive OR g.status = 'ACTIVE')
                   AND %s
                 ORDER BY g.created_at DESC
                """.formatted(within))
                .param("tenantId", boundary.tenantId())
                .param("brandId", boundary.brandId())
                .param("locationId", boundary.locationId())
                .param("includeInactive", includeInactive)
                .query(GrantManagementService::toGrantView)
                .list();
    }

    /**
     * Revokes a grant lying at or beneath {@code boundary}, for a branch-scoped
     * granter (ADR 0103). Answers {@code false} — the same answer a grant that
     * does not exist gets — for one lying anywhere else, so a manager cannot use
     * a grant id seen in a ticket to take away a company-level job or a sibling
     * branch's.
     *
     * <p>A second rule beside the boundary, and the mirror of {@link
     * #requireGrantable}: she may only take away a job she could herself have
     * given at that scope. Otherwise a manager could suspend a finance clerk the
     * owner placed at her branch — a job whose capabilities she does not hold, and
     * so one she has no standing to judge.
     */
    @Transactional
    public boolean revokeWithin(ResourceScope boundary, UUID grantId, String revokerSubject, String reason) {
        requireBranchBoundary(boundary);
        UUID tenantId = Objects.requireNonNull(boundary.tenantId());
        var row = jdbc.sql("""
                SELECT g.role_id, g.scope_type, g.scope_id
                  FROM iam.grants g
                 WHERE g.id = :id AND g.tenant_id = :tenantId AND g.status = 'ACTIVE'
                """)
                .param("id", grantId)
                .param("tenantId", tenantId)
                .query((rs, n) -> new GrantLocator(
                        rs.getObject("role_id", UUID.class),
                        rs.getString("scope_type"),
                        rs.getObject("scope_id", UUID.class)))
                .optional();
        if (row.isEmpty()) {
            return false;
        }
        ResourceScope grantScope = scopeOfGrant(tenantId, row.get());
        if (grantScope == null || !boundary.covers(grantScope)) {
            return false;
        }
        for (String code : jdbc.sql("SELECT capability_code FROM iam.role_capabilities WHERE role_id = :roleId")
                .param("roleId", row.get().roleId())
                .query(String.class)
                .list()) {
            Capability capability = Capability.require(code);
            if (!authorization.has(revokerSubject, capability, grantScope)) {
                throw new AuthorizationService.AccessDeniedException(capability, grantScope);
            }
        }
        return revoke(tenantId, grantId, revokerSubject, reason);
    }

    /**
     * The tenant-visible jobs, each with whether {@code granterSubject} may confer
     * it at {@code scope} right now (ADR 0103).
     *
     * <p>Answered by running {@link #requireGrantable} itself and reading its
     * refusal, not by a second copy of its rules: the picker a branch manager is
     * shown and the check that runs when she presses the button then cannot
     * disagree, which is the whole of staff-and-access.md §0's "never taunt her
     * with a job she cannot give".
     */
    public List<GrantableRole> grantableRoles(ResourceScope scope, String granterSubject) {
        return TenantRoleCatalog.tenantVisible().stream()
                .map(descriptor -> {
                    PlatformRole role = PlatformRole.find(descriptor.code()).orElseThrow();
                    ResolvedRole resolved = new ResolvedRole(
                            RoleRegistrySynchronizer.platformRoleId(role), role.code(), role.capabilities(), true);
                    boolean grantable = true;
                    try {
                        requireGrantable(resolved, scope, granterSubject);
                    } catch (AuthorizationService.AccessDeniedException refused) {
                        grantable = false;
                    } catch (ApiException refused) {
                        if (refused.errorCode() != ErrorCode.INSUFFICIENT_CAPABILITY) {
                            throw refused;
                        }
                        grantable = false;
                    }
                    return new GrantableRole(
                            descriptor.code(), descriptor.scopeType(), descriptor.capabilities(), grantable);
                })
                .toList();
    }

    /**
     * The brand and branches a {@link #listWithin} answer is grouped under, named.
     *
     * <p>The tenant's own brand and location lists need {@code brand.read} and
     * {@code location.read} one level up from where a branch manager holds them, so
     * the team view carries its own small directory rather than asking for a wider
     * grant for the sake of two display names. Only places at or beneath the
     * boundary, and the boundary's own brand.
     */
    public PlaceDirectory placesWithin(ResourceScope boundary) {
        requireBranchBoundary(boundary);
        List<PlaceDirectory.BrandPlace> brands = jdbc.sql("""
                SELECT id, display_name FROM tenant.brands WHERE tenant_id = :tenantId AND id = :brandId
                """)
                .param("tenantId", boundary.tenantId())
                .param("brandId", boundary.brandId())
                .query((rs, n) ->
                        new PlaceDirectory.BrandPlace(rs.getObject("id", UUID.class), rs.getString("display_name")))
                .list();
        List<PlaceDirectory.LocationPlace> locations = jdbc.sql("""
                SELECT id, brand_id, display_name FROM tenant.locations
                 WHERE tenant_id = :tenantId AND brand_id = :brandId
                   AND (CAST(:locationId AS uuid) IS NULL OR id = CAST(:locationId AS uuid))
                 ORDER BY display_name
                """)
                .param("tenantId", boundary.tenantId())
                .param("brandId", boundary.brandId())
                .param("locationId", boundary.locationId())
                .query((rs, n) -> new PlaceDirectory.LocationPlace(
                        rs.getObject("id", UUID.class),
                        rs.getObject("brand_id", UUID.class),
                        rs.getString("display_name")))
                .list();
        return new PlaceDirectory(brands, locations);
    }

    private static void requireBranchBoundary(ResourceScope boundary) {
        if (boundary.type() != ResourceScope.ScopeType.BRAND && boundary.type() != ResourceScope.ScopeType.LOCATION) {
            throw new IllegalArgumentException(
                    "A branch boundary is a BRAND or LOCATION scope, not " + boundary.type());
        }
    }

    /** The scope a grant row sits at, rehydrated with the ancestry its type needs; null for a platform grant. */
    private @Nullable ResourceScope scopeOfGrant(UUID tenantId, GrantLocator grant) {
        return switch (grant.scopeType()) {
            case "TENANT" -> ResourceScope.tenant(tenantId);
            case "BRAND" -> ResourceScope.brand(tenantId, Objects.requireNonNull(grant.scopeId()));
            case "LOCATION" -> {
                UUID locationId = Objects.requireNonNull(grant.scopeId());
                yield jdbc.sql("SELECT brand_id FROM tenant.locations WHERE tenant_id = :tenantId AND id = :id")
                        .param("tenantId", tenantId)
                        .param("id", locationId)
                        .query(UUID.class)
                        .optional()
                        .map(brandId -> ResourceScope.location(tenantId, brandId, locationId))
                        .orElse(null);
            }
            default -> null;
        };
    }

    /** Active grants only, the historical default — see {@link #listForTenant(UUID, boolean)}. */
    public List<GrantView> listForTenant(UUID tenantId) {
        return listForTenant(tenantId, false);
    }

    /**
     * A tenant's grants, active-only by default.
     *
     * @param includeInactive when true, also returns {@code REVOKED} grants —
     *                         staff-and-access.md §2's suspended-row state and
     *                         §11.2's "restore" action both need to see what
     *                         was taken away, not just what remains
     */
    public List<GrantView> listForTenant(UUID tenantId, boolean includeInactive) {
        return jdbc.sql("""
                SELECT g.id, g.principal_subject, r.code AS role_code, g.scope_type, g.scope_id,
                       g.status, g.granted_by, g.reason, g.valid_from, g.valid_until,
                       g.revoked_at, g.revoked_by, g.revoked_reason
                  FROM iam.grants g
                  JOIN iam.roles r ON r.id = g.role_id
                 WHERE g.tenant_id = :tenantId AND (:includeInactive OR g.status = 'ACTIVE')
                 ORDER BY g.created_at DESC
                """)
                .param("tenantId", tenantId)
                .param("includeInactive", includeInactive)
                .query(GrantManagementService::toGrantView)
                .list();
    }

    /**
     * One subject's active grants that carry a named capability, anywhere in
     * this tenant — the reason chain Staff 9.5's access check reads: not just
     * whether the covering scope has it, but every scope this subject holds it
     * at, so a negative answer can point at the grant that almost worked
     * ("she has this job, but only at Chilonzor branch") instead of a bare no.
     *
     * <p>Unlike {@link #listForTenant}, this is one subject's rows only, and it
     * joins through {@code iam.role_capabilities} rather than filtering a
     * tenant's whole grant list client-side — the same join {@code
     * JdbcAuthorizationService#SELECT_GRANTS} already uses to answer {@code
     * has()}, so the two never disagree about which grants carry a capability.
     */
    public List<GrantView> grantsCarrying(UUID tenantId, String subject, Capability capability) {
        return jdbc.sql("""
                SELECT g.id, g.principal_subject, r.code AS role_code, g.scope_type, g.scope_id,
                       g.status, g.granted_by, g.reason, g.valid_from, g.valid_until,
                       g.revoked_at, g.revoked_by, g.revoked_reason
                  FROM iam.grants g
                  JOIN iam.roles r ON r.id = g.role_id
                  JOIN iam.role_capabilities rc ON rc.role_id = r.id
                 WHERE g.principal_subject = :subject
                   AND rc.capability_code = :capabilityCode
                   AND g.status = 'ACTIVE'
                   AND r.status = 'ACTIVE'
                   AND (g.scope_type = 'PLATFORM' OR g.tenant_id = :tenantId)
                 ORDER BY g.created_at DESC
                """)
                .param("subject", subject)
                .param("capabilityCode", capability.code())
                .param("tenantId", tenantId)
                .query(GrantManagementService::toGrantView)
                .list();
    }

    /** The active {@code PLATFORM}-scope grants (Gap A), for the console that authors them. */
    public List<GrantView> listPlatformGrants() {
        return jdbc.sql("""
                SELECT g.id, g.principal_subject, r.code AS role_code, g.scope_type, g.scope_id,
                       g.status, g.granted_by, g.reason, g.valid_from, g.valid_until,
                       g.revoked_at, g.revoked_by, g.revoked_reason
                  FROM iam.grants g
                  JOIN iam.roles r ON r.id = g.role_id
                 WHERE g.tenant_id IS NULL AND g.scope_type = 'PLATFORM' AND g.status = 'ACTIVE'
                 ORDER BY g.created_at DESC
                """).query(GrantManagementService::toGrantView).list();
    }

    private static GrantView toGrantView(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
        return new GrantView(
                rs.getObject("id", UUID.class),
                rs.getString("principal_subject"),
                rs.getString("role_code"),
                rs.getString("scope_type"),
                rs.getObject("scope_id", UUID.class),
                rs.getString("status"),
                rs.getString("granted_by"),
                rs.getString("reason"),
                rs.getObject("valid_from", OffsetDateTime.class).toInstant(),
                optionalInstant(rs, "valid_until"),
                optionalInstant(rs, "revoked_at"),
                rs.getString("revoked_by"),
                rs.getString("revoked_reason"));
    }

    private static @Nullable Instant optionalInstant(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
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
        // ADR 0081: a support-session role comes with a session — a reason, a
        // deadline and a record the tenant can read — or not at all.
        if (PlatformRole.find(role.code()).map(PlatformRole::supportSessionOnly).orElse(false)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "This role is conferred only by opening a support session");
        }

        for (Capability capability : role.capabilities()) {
            if (!authorization.has(granterSubject, capability, scope)) {
                throw new AuthorizationService.AccessDeniedException(capability, scope);
            }
        }

        requireWithinABranchGranter(role, scope, granterSubject);
    }

    /**
     * ADR 0103: what a granter whose {@code iam.grant.manage} stops short of the
     * whole company may confer, beyond the subset rule above.
     *
     * <p>Two refusals, both only for such a granter. The scope must name a
     * hierarchy that exists, because {@link ResourceScope#covers} is a statement
     * about levels: a brand manager naming {@code location(her brand, somebody
     * else's location)} would pass every capability check above and confer a job
     * at a branch of another brand. And the job must be code-owned and no broader
     * than the scope it is given at: a company-level job such as
     * {@code tenant-finance} has no meaning "at one branch", and the subset rule
     * alone would let a manager whose bundle happens to hold all of a narrower
     * job's capabilities hand out a company-level one with a branch's reach
     * looking like a company's.
     *
     * <p>A tenant-wide granter is exempt on purpose: this changes nothing for the
     * owner and the administrator, who may already give any job at any level.
     */
    private void requireWithinABranchGranter(ResolvedRole role, ResourceScope scope, String granterSubject) {
        if (scope.type() == ResourceScope.ScopeType.PLATFORM || holdsGrantManagementTenantWide(granterSubject, scope)) {
            return;
        }
        requireRealHierarchy(scope);
        PlatformRole platformRole =
                role.platformDefined() ? PlatformRole.find(role.code()).orElse(null) : null;
        if (platformRole == null
                || platformRole.scopeType().ordinal() < scope.type().ordinal()) {
            throw ApiException.insufficientCapability(Capability.IAM_GRANT_MANAGE.code(), "TENANT");
        }
    }

    private boolean holdsGrantManagementTenantWide(String granterSubject, ResourceScope scope) {
        UUID tenantId = scope.tenantId();
        return tenantId != null
                && authorization.has(granterSubject, Capability.IAM_GRANT_MANAGE, ResourceScope.tenant(tenantId));
    }

    private void requireRealHierarchy(ResourceScope scope) {
        boolean real =
                switch (scope.type()) {
                    case PLATFORM -> true;
                    case TENANT -> true;
                    case BRAND ->
                        jdbc.sql("SELECT count(*) FROM tenant.brands WHERE tenant_id = :tenantId AND id = :brandId")
                                        .param("tenantId", scope.tenantId())
                                        .param("brandId", scope.brandId())
                                        .query(Long.class)
                                        .single()
                                > 0;
                    case LOCATION ->
                        jdbc.sql("""
                                        SELECT count(*) FROM tenant.locations
                                         WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :locationId
                                        """)
                                        .param("tenantId", scope.tenantId())
                                        .param("brandId", scope.brandId())
                                        .param("locationId", scope.locationId())
                                        .query(Long.class)
                                        .single()
                                > 0;
                };
        if (!real) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such place in this company");
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
    private ResolvedRole resolveRole(String roleCode, @Nullable UUID tenantId) {
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

    /**
     * Staff 9.3c: a bulk action is N grant changes that must audit as one
     * group, not N. {@code CorrelationIdFilter} already puts the request's own
     * {@code X-Correlation-Id} (client-supplied, or generated when absent)
     * into MDC before any controller runs, so reusing it here is what lets
     * {@code staff-page.ts}'s {@code suspend}/{@code restore} fan-out — one
     * minted id sent on every call in a {@code Promise.allSettled} batch —
     * turn into one shared {@code correlation_id} across every resulting
     * {@code GrantChanged}, instead of {@code grantId} grouping each row
     * alone. Falls back to {@code grantId} only when nothing put a
     * correlation id on this thread — a system-initiated grant with no
     * request behind it at all.
     */
    private static String correlationIdFor(UUID grantId) {
        String fromRequest = MDC.get("correlationId");
        return fromRequest == null || fromRequest.isBlank() ? grantId.toString() : fromRequest;
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
     * @param id              the {@code iam.roles} row this grant will reference
     * @param code            the role code, for the audit entry
     * @param capabilities    from {@link PlatformRole} for a platform role, from
     *                        {@code iam.role_capabilities} for a tenant-defined one
     * @param platformDefined whether this is a platform role rather than one the
     *                        tenant defined. Written onto the grant as
     *                        {@code role_is_platform}, which V0089's foreign key
     *                        then makes true: the derived owner is the platform
     *                        sentinel or this grant's own tenant, and a claim that
     *                        does not match the role is refused by the key rather
     *                        than trusted.
     */
    private record ResolvedRole(UUID id, String code, Set<Capability> capabilities, boolean platformDefined) {}

    /**
     * A request to grant one role to one principal at one scope.
     *
     * @param validUntil null for an open-ended grant; set it for support access
     */
    public record GrantCommand(
            String principalSubject,
            String roleCode,
            ResourceScope scope,
            String reason,
            @Nullable Instant validUntil) {}

    /**
     * @param reason        why this grant was created, whatever its current status
     * @param validFrom     when this grant took effect
     * @param validUntil    null for an open-ended grant
     * @param revokedAt     null unless {@code status} is {@code REVOKED} (V0127)
     * @param revokedBy     null unless {@code status} is {@code REVOKED}
     * @param revokedReason why this grant was taken away — distinct from
     *                      {@code reason}, which stays why it was given
     */
    public record GrantView(
            UUID id,
            String principalSubject,
            String roleCode,
            String scopeType,
            UUID scopeId,
            String status,
            String grantedBy,
            String reason,
            Instant validFrom,
            @Nullable Instant validUntil,
            @Nullable Instant revokedAt,
            @Nullable String revokedBy,
            @Nullable String revokedReason) {}

    /**
     * One tenant-visible job as a particular granter sees it at a particular scope.
     *
     * @param grantable whether the granter may confer it there; false is "do not offer", never an error
     */
    public record GrantableRole(
            String code, ResourceScope.ScopeType scopeType, Set<String> capabilities, boolean grantable) {}

    /** The names a branch manager's team view groups its people under. */
    public record PlaceDirectory(List<BrandPlace> brands, List<LocationPlace> locations) {

        public record BrandPlace(UUID id, String displayName) {}

        public record LocationPlace(UUID id, UUID brandId, String displayName) {}
    }

    private record RevokedGrant(String principalSubject, UUID tenantId, String scopeType, UUID scopeId) {}

    private record GrantLocator(
            UUID roleId, String scopeType, @Nullable UUID scopeId) {}
}
