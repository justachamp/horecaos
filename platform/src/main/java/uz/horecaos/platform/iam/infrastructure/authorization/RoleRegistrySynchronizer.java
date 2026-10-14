package uz.horecaos.platform.iam.infrastructure.authorization;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;

/**
 * Projects the code-owned role and capability registries into the database
 * (ADR 0025).
 *
 * <p>Grants need a role row to reference, but code remains the authority for
 * what a role means. Synchronising at startup keeps the two from drifting
 * without hard-coding identifiers in a migration, which would make every
 * bundle change a schema change.
 *
 * <p>Writes the platform's rows and nothing else (ADR 0186): a tenant's own jobs are never
 * listed, replaced or retired here, because every statement below names a role id derived
 * from a code-owned {@link PlatformRole}. What it replaces wholesale is a platform role's
 * capability list, so a capability removed from a bundle in code disappears from the table.
 */
@Component
public class RoleRegistrySynchronizer implements ApplicationRunner {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(RoleRegistrySynchronizer.class);

    private final JdbcClient jdbc;
    private final TenantRlsSession rls;

    /** For a fixture on the table owner's connection, which row-level security exempts anyway. */
    public RoleRegistrySynchronizer(JdbcClient jdbc) {
        this(jdbc, TenantRlsSession.notEnforced());
    }

    @Autowired
    public RoleRegistrySynchronizer(JdbcClient jdbc, TenantRlsSession rls) {
        this.jdbc = jdbc;
        this.rls = rls;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        synchronize();
    }

    @Transactional
    public void synchronize() {
        // ADR 0186 / V0693: the platform's jobs belong to no tenant, and no tenant session may
        // write them. They are written under the exempt role, for this transaction only.
        rls.bindPlatform();

        for (PlatformRole role : PlatformRole.values()) {
            UUID roleId = platformRoleId(role);

            jdbc.sql("""
                    INSERT INTO iam.roles (id, tenant_id, code, name, scope_type, status, is_platform_defined)
                    VALUES (:id, NULL, :code, :name, :scopeType, 'ACTIVE', true)
                    ON CONFLICT (id) DO UPDATE
                       SET name = excluded.name,
                           scope_type = excluded.scope_type,
                           status = 'ACTIVE',
                           updated_at = now()
                    """)
                    .param("id", roleId)
                    .param("code", role.code())
                    .param("name", role.name())
                    .param("scopeType", role.scopeType().name())
                    .update();

            // Replace rather than merge: a capability removed from a bundle in
            // code must disappear here, or a revoked permission would survive.
            jdbc.sql("DELETE FROM iam.role_capabilities WHERE role_id = :roleId")
                    .param("roleId", roleId)
                    .update();

            for (Capability capability : role.capabilities()) {
                jdbc.sql("""
                        INSERT INTO iam.role_capabilities (role_id, capability_code)
                        VALUES (:roleId, :capability)
                        """)
                        .param("roleId", roleId)
                        .param("capability", capability.code())
                        .update();
            }
        }

        pruneRemovedCapabilitiesFromTenantJobs();

        jdbc.sql("DELETE FROM iam.capability_registry_snapshot").update();
        for (Capability capability : Capability.values()) {
            jdbc.sql("""
                    INSERT INTO iam.capability_registry_snapshot (capability_code, resource_type, action)
                    VALUES (:code, :resourceType, :action)
                    """)
                    .param("code", capability.code())
                    .param("resourceType", capability.resourceType())
                    .param("action", capability.action())
                    .update();
        }
    }

    /**
     * A tenant job names capability codes the way a platform job does, and a release can
     * remove a code from {@link Capability}. A platform job is replaced wholesale above, so the
     * removed code leaves with it; a tenant job is not ours to replace, so the codes that no
     * longer exist are removed from it here (ADR 0186, fail closed). The authorization read
     * ignores an unknown code in the meantime, so a job is never <em>more</em> than its
     * remaining capabilities at any instant.
     *
     * <p>Logs a count, never a code list or a tenant.
     */
    private void pruneRemovedCapabilitiesFromTenantJobs() {
        String[] known = java.util.Arrays.stream(Capability.values())
                .map(Capability::code)
                .toArray(String[]::new);
        int removed = jdbc.sql("""
                DELETE FROM iam.role_capabilities rc
                 USING iam.roles r
                 WHERE r.id = rc.role_id
                   AND r.tenant_id IS NOT NULL
                   AND rc.capability_code <> ALL(:known)
                """).param("known", known).update();
        if (removed > 0) {
            LOGGER.warn("Removed {} capability code(s) the code no longer declares from tenant-defined jobs", removed);
        }
    }

    /**
     * Derives a stable identifier from the role code, so platform role IDs are
     * identical in every environment without being hard-coded anywhere.
     */
    public static UUID platformRoleId(PlatformRole role) {
        return UUID.nameUUIDFromBytes(("horecaos.platform-role:" + role.code()).getBytes(StandardCharsets.UTF_8));
    }
}
