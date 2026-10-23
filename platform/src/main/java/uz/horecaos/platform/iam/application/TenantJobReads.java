package uz.horecaos.platform.iam.application;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;

/**
 * The one SELECT that reads a tenant's own jobs (ADR 0186), shared by {@code TenantRoleService}
 * and {@code GrantManagementService} so the picker, the library and the editor cannot read
 * different things.
 *
 * <p>Every method here reads {@code iam.roles}, which has row-level security (V0693): the
 * caller must already have bound the tenant on the connection it passes in, inside a
 * transaction, or the answer is empty. That is not checked here because it cannot be: an
 * unbound session and a tenant with no jobs look the same. Both callers bind first, and
 * {@code IamRolesRowLevelSecurityTests} runs them as the application role.
 */
final class TenantJobReads {

    private static final String SELECT = """
            SELECT r.id, r.code, r.name, r.scope_type, r.status, r.version, r.copied_from,
                   r.created_by, r.updated_by, r.created_at, r.updated_at,
                   r.retired_at, r.retired_by, r.retired_reason,
                   coalesce(
                       (SELECT array_agg(rc.capability_code ORDER BY rc.capability_code)
                          FROM iam.role_capabilities rc WHERE rc.role_id = r.id),
                       ARRAY[]::varchar[]) AS capabilities,
                   (SELECT count(DISTINCT g.principal_subject)
                      FROM iam.grants g
                     WHERE g.role_id = r.id AND g.tenant_id = r.tenant_id
                       AND g.status = 'ACTIVE'
                       AND g.valid_from <= :now
                       AND (g.valid_until IS NULL OR g.valid_until > :now)) AS holders
              FROM iam.roles r
             WHERE r.tenant_id = :tenantId AND NOT r.is_platform_defined
            """;

    private TenantJobReads() {}

    /** A tenant's own jobs, oldest first; retired ones only when asked for. */
    static List<TenantJob> list(JdbcClient jdbc, UUID tenantId, boolean includeRetired, Instant now) {
        return jdbc.sql(SELECT + (includeRetired ? "" : " AND r.status = 'ACTIVE'") + " ORDER BY r.created_at, r.code")
                .param("tenantId", tenantId)
                .param("now", at(now))
                .query(TenantJobReads::map)
                .list();
    }

    static Optional<TenantJob> find(JdbcClient jdbc, UUID tenantId, String code, Instant now) {
        return jdbc.sql(SELECT + " AND r.code = :code")
                .param("tenantId", tenantId)
                .param("code", code)
                .param("now", at(now))
                .query(TenantJobReads::map)
                .optional();
    }

    private static TenantJob map(java.sql.ResultSet row, int number) throws SQLException {
        String[] capabilities = (String[]) row.getArray("capabilities").getArray();
        return new TenantJob(
                row.getObject("id", UUID.class),
                row.getString("code"),
                row.getString("name"),
                ScopeType.valueOf(row.getString("scope_type")),
                List.of(capabilities),
                row.getLong("version"),
                row.getString("status"),
                row.getString("copied_from"),
                row.getString("created_by"),
                row.getString("updated_by"),
                instant(row, "created_at"),
                instant(row, "updated_at"),
                instant(row, "retired_at"),
                row.getString("retired_reason"),
                row.getInt("holders"));
    }

    private static @Nullable Instant instant(java.sql.ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
