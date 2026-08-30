package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * SQL adapter for ADR 0030 policy resolution.
 *
 * <p>{@link #resolve} answers "what applies here now"; {@link #pinned} answers
 * "what applied when this decision was made". The second is the reason policies
 * are versioned at all: without it, editing a policy would silently rewrite the
 * meaning of every historical order, refund, and approval that referenced it.
 */
@Repository
public class JdbcPolicyResolver implements PolicyResolver {

    private static final String SELECT_ACTIVE_IN_CHAIN = """
            SELECT p.id, p.version, p.scope_type, p.document_hash, p.document::text AS document
              FROM tenant.policy_current c
              JOIN tenant.policies p ON p.id = c.policy_id
             WHERE c.key_code = :keyCode
               AND (
                    (c.scope_type = 'PLATFORM')
                 OR (c.scope_type = 'TENANT' AND c.tenant_id = :tenantId)
                 OR (c.scope_type = 'BRAND' AND c.tenant_id = :tenantId AND c.brand_id = :brandId)
                 OR (c.scope_type = 'LOCATION' AND c.tenant_id = :tenantId
                     AND c.brand_id = :brandId AND c.location_id = :locationId)
               )
               AND p.status = 'ACTIVE'
            """;

    private static final String SELECT_PINNED = """
            SELECT id, version, scope_type, document_hash, document::text AS document
              FROM tenant.policies
             WHERE id = :policyId AND version = :version
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcPolicyResolver(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
        List<Row> candidates = jdbc.sql(SELECT_ACTIVE_IN_CHAIN)
                .param("keyCode", key.code())
                .param("tenantId", scope.tenantId())
                .param("brandId", scope.brandId())
                .param("locationId", scope.locationId())
                .query((resultSet, rowNumber) -> new Row(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getInt("version"),
                        ScopeType.valueOf(resultSet.getString("scope_type")),
                        resultSet.getString("document_hash"),
                        resultSet.getString("document")))
                .list();

        // Most specific wins, using the same chain order as configuration values
        // rather than a second precedence rule.
        for (ResourceScope level : scope.chain()) {
            for (Row candidate : candidates) {
                if (candidate.scopeType() == level.type()) {
                    return Optional.of(toResolved(key, candidate));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
        return jdbc.sql(SELECT_PINNED)
                .param("policyId", policyId)
                .param("version", policyVersion)
                .query((resultSet, rowNumber) -> new Row(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getInt("version"),
                        ScopeType.valueOf(resultSet.getString("scope_type")),
                        resultSet.getString("document_hash"),
                        resultSet.getString("document")))
                .optional()
                .map(row -> toResolved(key, row));
    }

    private <P> ResolvedPolicy<P> toResolved(PolicyKey<P> key, Row row) {
        return new ResolvedPolicy<>(
                key.code(),
                row.id(),
                row.version(),
                row.scopeType(),
                row.documentHash(),
                deserialize(key, row.document()));
    }

    private <P> P deserialize(PolicyKey<P> key, String document) {
        try {
            return objectMapper.readValue(document, key.documentType());
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Stored policy document for %s does not match %s"
                            .formatted(key.code(), key.documentType().getSimpleName()),
                    exception);
        }
    }

    private record Row(UUID id, int version, ScopeType scopeType, String documentHash, String document) {}
}
