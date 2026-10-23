package uz.horecaos.platform.iam.infrastructure.persistence;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.iam.application.mfa.StaffAccountFacts;

/** {@link StaffAccountFacts} over {@code iam.grants} and {@code iam.roles}. */
@Component
class JdbcStaffAccountFacts implements StaffAccountFacts {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final TenantRlsSession rls;

    /** For a fixture on the table owner's connection, which row-level security exempts anyway. */
    JdbcStaffAccountFacts(JdbcClient jdbc, Clock clock) {
        this(jdbc, clock, TenantRlsSession.notEnforced());
    }

    @Autowired
    JdbcStaffAccountFacts(JdbcClient jdbc, Clock clock, TenantRlsSession rls) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.rls = rls;
    }

    @Override
    public boolean holdsPlatformGrant(String subject) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                              FROM iam.grants
                             WHERE principal_subject = :subject
                               AND scope_type = 'PLATFORM'
                               AND status = 'ACTIVE'
                               AND valid_from <= :now
                               AND (valid_until IS NULL OR valid_until > :now))
                        """)
                .param("subject", subject)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .query(Boolean.class)
                .single();
    }

    /**
     * Across every tenant the subject works in, so it reads under the exempt role: the roles
     * named may be a tenant's own jobs (V0693, ADR 0186), and no single tenant is bound.
     */
    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Set<String>> rolesByTenant(String subject) {
        rls.bindPlatform();
        Map<UUID, Set<String>> byTenant = new HashMap<>();
        jdbc.sql("""
                        SELECT g.tenant_id, r.code
                          FROM iam.grants g
                          JOIN iam.roles r ON r.id = g.role_id
                         WHERE g.principal_subject = :subject
                           AND g.tenant_id IS NOT NULL
                           AND g.status = 'ACTIVE'
                           AND g.valid_from <= :now
                           AND (g.valid_until IS NULL OR g.valid_until > :now)
                        """)
                .param("subject", subject)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .query((resultSet, row) ->
                        new TenantRole(resultSet.getObject("tenant_id", UUID.class), resultSet.getString("code")))
                .list()
                .forEach(grant -> byTenant.computeIfAbsent(grant.tenantId(), id -> new HashSet<>())
                        .add(grant.roleCode()));
        return byTenant;
    }

    private record TenantRole(UUID tenantId, String roleCode) {}

    /**
     * Reads {@code iam.roles}, which has row-level security (V0693): bound to the tenant, the
     * session sees the tenant's own jobs and the platform's, and the predicate below keeps only the
     * former. Unbound it would see no job at all, so the caller gets an empty answer, which
     * {@code MfaPolicy} reads as "unknown" and asks for the factor.
     */
    @Override
    @Transactional(readOnly = true)
    public Map<String, Set<String>> tenantJobCapabilities(UUID tenantId, Set<String> roleCodes) {
        if (roleCodes.isEmpty()) {
            return Map.of();
        }
        rls.bindTenant(tenantId);
        Map<String, Set<String>> byJob = new HashMap<>();
        jdbc.sql("""
                        SELECT r.code, rc.capability_code
                          FROM iam.roles r
                          LEFT JOIN iam.role_capabilities rc ON rc.role_id = r.id
                         WHERE r.tenant_id = :tenantId
                           AND NOT r.is_platform_defined
                           AND r.code = ANY (CAST(:codes AS varchar[]))
                        """)
                .param("tenantId", tenantId)
                .param("codes", roleCodes.toArray(String[]::new))
                .query((resultSet, row) ->
                        new JobCapability(resultSet.getString("code"), resultSet.getString("capability_code")))
                .list()
                .forEach(job -> {
                    Set<String> carried = byJob.computeIfAbsent(job.roleCode(), code -> new HashSet<>());
                    if (job.capabilityCode() != null) {
                        carried.add(job.capabilityCode());
                    }
                });
        return byJob;
    }

    private record JobCapability(String roleCode, @Nullable String capabilityCode) {}

    @Override
    public Optional<String> uiLocale(String subject) {
        return jdbc.sql("""
                        SELECT ui_locale
                          FROM iam.staff_members
                         WHERE principal_subject = :subject
                           AND ui_locale IS NOT NULL
                         ORDER BY updated_at DESC
                         LIMIT 1
                        """).param("subject", subject).query(String.class).optional();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean holdsRoleInTenant(String subject, UUID tenantId, String roleCode) {
        rls.bindTenant(tenantId);
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1
                              FROM iam.grants g
                              JOIN iam.roles r ON r.id = g.role_id
                             WHERE g.principal_subject = :subject
                               AND g.tenant_id = :tenantId
                               AND r.code = :roleCode
                               AND g.status = 'ACTIVE'
                               AND g.valid_from <= :now
                               AND (g.valid_until IS NULL OR g.valid_until > :now))
                        """)
                .param("subject", subject)
                .param("tenantId", tenantId)
                .param("roleCode", roleCode)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .query(Boolean.class)
                .single();
    }
}
