package uz.qoida.platform.iam.infrastructure.authorization;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.iam.api.Capability;
import uz.qoida.platform.iam.api.PlatformRole;

/**
 * Projects the code-owned role and capability registries into the database
 * (ADR 0025).
 *
 * <p>Grants need a role row to reference, but code remains the authority for
 * what a role means. Synchronising at startup keeps the two from drifting
 * without hard-coding identifiers in a migration, which would make every
 * bundle change a schema change.
 */
@Component
public class RoleRegistrySynchronizer implements ApplicationRunner {

    private final JdbcClient jdbc;

    public RoleRegistrySynchronizer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        synchronize();
    }

    @Transactional
    public void synchronize() {
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
     * Derives a stable identifier from the role code, so platform role IDs are
     * identical in every environment without being hard-coded anywhere.
     */
    public static UUID platformRoleId(PlatformRole role) {
        return UUID.nameUUIDFromBytes(("qoida.platform-role:" + role.code()).getBytes(StandardCharsets.UTF_8));
    }
}
