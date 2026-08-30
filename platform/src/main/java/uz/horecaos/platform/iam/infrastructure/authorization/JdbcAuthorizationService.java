package uz.horecaos.platform.iam.infrastructure.authorization;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CapabilityView;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;

/**
 * Resolves capability grants (ADR 0025).
 *
 * <p>The scope rule is the whole decision: a grant applies when its scope
 * <em>covers</em> the target, using the same {@link ResourceScope#chain()} that
 * ADR 0030 uses for configuration precedence. A grant at one brand can never
 * reach a sibling brand, and a location grant never reaches its tenant.
 */
@Service
public class JdbcAuthorizationService implements AuthorizationService {

    private static final String SELECT_GRANTS = """
            SELECT g.scope_type, g.scope_id, g.tenant_id, r.code AS role_code, rc.capability_code
              FROM iam.grants g
              JOIN iam.roles r ON r.id = g.role_id
              JOIN iam.role_capabilities rc ON rc.role_id = r.id
             WHERE g.principal_subject = :subject
               AND g.status = 'ACTIVE'
               AND r.status = 'ACTIVE'
               AND g.valid_from <= :now
               AND (g.valid_until IS NULL OR g.valid_until > :now)
               AND (g.scope_type = 'PLATFORM' OR g.tenant_id = :tenantId)
            """;

    /**
     * The Keycloak realm role that stands outside the grant table.
     *
     * <p>Spelled the same way {@code TenantAccessPolicy} spells it, and
     * deliberately not a {@link Capability}: {@code GrantManagementService}
     * refuses to confer it through the API precisely because ADR 0003 makes
     * Keycloak its issuer.
     */
    private static final String PLATFORM_ADMIN = "platform-admin";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final CurrentActor currentActor;

    public JdbcAuthorizationService(JdbcClient jdbc, Clock clock, CurrentActor currentActor) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.currentActor = currentActor;
    }

    /**
     * The only capability the bootstrap bypass confers.
     *
     * <p>Deliberately one capability rather than all of them. The problem being
     * solved is that a fresh deployment cannot create its first grant; the
     * solution is therefore the ability to create grants, and nothing else. A
     * platform admin bootstraps the estate by granting themselves — through the
     * ordinary, audited API — whatever else they need.
     *
     * <p>Widening this to every capability would make the realm role a standing
     * key to every control-plane endpoint of every tenant, which is the state
     * ADR 0025 was written to end: the role alone opened everything and the
     * capability declaration decided nothing.
     */
    private static final Capability BOOTSTRAP_CAPABILITY = Capability.IAM_GRANT_MANAGE;

    @Override
    public boolean has(String subject, Capability capability, ResourceScope scope) {
        return (capability == BOOTSTRAP_CAPABILITY && isPlatformAdmin(subject)) || hasGrant(subject, capability, scope);
    }

    /**
     * Whether this subject holds the platform-admin realm role.
     *
     * <p>This exists because of a bootstrap problem with no other honest answer.
     * {@code GrantManagementService} is the only writer of {@code iam.grants},
     * it requires {@code iam.grant.manage} at the scope it is writing, and it
     * refuses to confer platform-admin at all — so a fresh deployment had no
     * grants, no API path to its first one, and a control plane that stayed shut
     * until somebody inserted a row by hand. Every test fixture needing an
     * operator wrote that row directly, which was the symptom.
     *
     * <p>It is a bypass and it is narrow on purpose. It confers exactly one
     * capability — see {@link #BOOTSTRAP_CAPABILITY} — so a platform admin can
     * create the first grant and must then grant themselves everything else
     * through the ordinary audited API. And:
     *
     * <ul>
     *   <li>The role is issued by Keycloak, not by this platform, so revoking it
     *       is an identity-provider act and cannot be done by anyone who has it.
     *   <li>It is read from the <em>calling</em> actor and compared against the
     *       subject being asked about, so it cannot answer "yes" for somebody
     *       else. Asking whether another subject may act is a question about
     *       grants, and grants are what answer it.
     *   <li>{@link #viewFor} does not report it. A capability view is a
     *       projection of grants for a frontend to render; a platform admin
     *       holding no grants sees no capabilities, which is true of the grant
     *       table and is the answer that keeps the view honest.
     * </ul>
     */
    private boolean isPlatformAdmin(String subject) {
        var actor = currentActor.get();
        return actor != null && subject.equals(actor.subject()) && actor.hasGlobalRole(PLATFORM_ADMIN);
    }

    private boolean hasGrant(String subject, Capability capability, ResourceScope scope) {
        return grantsFor(subject, scope.tenantId()).stream()
                .filter(grant -> grant.capability() == capability)
                .anyMatch(grant -> grant.scope().covers(scope));
    }

    @Override
    public void require(String subject, Capability capability, ResourceScope scope) {
        if (!has(subject, capability, scope)) {
            throw new AccessDeniedException(capability, scope);
        }
    }

    @Override
    public CapabilityView viewFor(String subject, UUID tenantId) {
        List<GrantRow> grants = grantsFor(subject, tenantId);

        Set<Capability> all = EnumSet.noneOf(Capability.class);
        grants.forEach(grant -> all.add(grant.capability()));

        List<CapabilityView.ScopeGrant> scopes = new ArrayList<>();
        grants.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        grant -> java.util.Map.entry(grant.scope(), grant.roleCode()),
                        java.util.stream.Collectors.mapping(GrantRow::capability, java.util.stream.Collectors.toSet())))
                .forEach((key, capabilities) ->
                        scopes.add(new CapabilityView.ScopeGrant(key.getKey(), key.getValue(), capabilities)));

        return new CapabilityView(
                subject,
                tenantId == null ? null : tenantId.toString(),
                all,
                scopes,
                clock.instant().getEpochSecond());
    }

    /**
     * Grants sit on the hot path of every authorized request, so they are cached
     * (ADR 0033 {@code iam.grants}).
     *
     * <p>The TTL is deliberately the shortest in the registry: a revoked grant
     * must stop working quickly, and a stale allow is worse than a stale
     * configuration value. PostgreSQL stays the authority — a cache miss or a
     * cache outage degrades to a database read, never to an allow.
     *
     * <p>Revocation should evict rather than wait for the TTL; {@link #evictGrants}
     * is called by grant management and by the ADR 0032 grants-changed event.
     */
    @Cacheable(cacheNames = "iam.grants", key = "#subject + '|' + #tenantId", sync = true)
    public List<GrantRow> grantsFor(String subject, UUID tenantId) {
        return jdbc.sql(SELECT_GRANTS)
                .param("subject", subject)
                .param("tenantId", tenantId)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .query((resultSet, rowNumber) -> {
                    ScopeType scopeType = ScopeType.valueOf(resultSet.getString("scope_type"));
                    UUID scopeId = resultSet.getObject("scope_id", UUID.class);
                    UUID grantTenant = resultSet.getObject("tenant_id", UUID.class);
                    return new GrantRow(
                            toScope(scopeType, grantTenant, scopeId),
                            resultSet.getString("role_code"),
                            Capability.require(resultSet.getString("capability_code")));
                })
                .list();
    }

    /**
     * A grant stores only its own level's identifier, so a brand or location
     * grant is rehydrated with the ancestry the scope type requires. The
     * ancestry is trustworthy because the grant's composite constraints and the
     * tenant hierarchy already enforce it.
     */
    private ResourceScope toScope(ScopeType scopeType, UUID tenantId, UUID scopeId) {
        return switch (scopeType) {
            case PLATFORM -> ResourceScope.platform();
            case TENANT -> ResourceScope.tenant(tenantId);
            case BRAND -> ResourceScope.brand(tenantId, scopeId);
            case LOCATION -> locationScope(tenantId, scopeId);
        };
    }

    private ResourceScope locationScope(UUID tenantId, UUID locationId) {
        UUID brandId = jdbc.sql("SELECT brand_id FROM tenant.locations WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", locationId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Grant references location %s outside tenant %s".formatted(locationId, tenantId)));
        return ResourceScope.location(tenantId, brandId, locationId);
    }

    /** Drops a principal's cached grants immediately after a change. */
    @CacheEvict(cacheNames = "iam.grants", key = "#subject + '|' + #tenantId")
    public void evictGrants(String subject, UUID tenantId) {
        // The annotation performs the eviction; the method body is the seam.
    }

    /** Visible because Spring caching proxies cannot cache a private method. */
    public record GrantRow(ResourceScope scope, String roleCode, Capability capability) {}
}
