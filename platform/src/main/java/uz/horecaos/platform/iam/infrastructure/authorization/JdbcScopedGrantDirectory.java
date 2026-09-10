package uz.horecaos.platform.iam.infrastructure.authorization;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.grants.ScopedGrantDirectory;

@Component
public class JdbcScopedGrantDirectory implements ScopedGrantDirectory {

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcScopedGrantDirectory(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public int activeGrantsScopedTo(ResourceScope scope) {
        Objects.requireNonNull(scope, "A scope is required");
        if (scope.type() != ResourceScope.ScopeType.BRAND && scope.type() != ResourceScope.ScopeType.LOCATION) {
            throw new IllegalArgumentException(
                    "Only a brand or location scope can be asked about, not " + scope.type());
        }
        // tenant_id as well as scope_id, although a brand or location id is
        // unique on its own: every other read of iam.grants names the tenant,
        // and a query that did not would be the one place a grant from another
        // tenant could be counted against this one's brand.
        return jdbc.sql("""
                SELECT count(*)
                  FROM iam.grants
                 WHERE tenant_id = :tenantId
                   AND scope_type = :scopeType
                   AND scope_id = :scopeId
                   AND status = 'ACTIVE'
                   AND (valid_until IS NULL OR valid_until > :now)
                """)
                .param("tenantId", scope.tenantId())
                .param("scopeType", scope.type().name())
                .param("scopeId", scope.scopeId())
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .query(Integer.class)
                .single();
    }
}
