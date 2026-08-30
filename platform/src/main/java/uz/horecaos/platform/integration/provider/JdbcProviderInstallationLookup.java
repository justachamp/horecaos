package uz.horecaos.platform.integration.provider;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderEntityMappingLookup;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;

/** SQL adapter for ADR 0026 binding and mapping resolution. */
@Repository
public class JdbcProviderInstallationLookup implements ProviderInstallationLookup, ProviderEntityMappingLookup {

    private static final String SELECT_BINDINGS = """
            SELECT b.id, b.installation_id, b.tenant_id, b.brand_id, b.location_id,
                   i.provider_category, i.provider_type, bc.is_primary, b.priority
              FROM integration.bindings b
              JOIN integration.installations i
                ON i.id = b.installation_id AND i.tenant_id = b.tenant_id
              JOIN integration.binding_capabilities bc
                ON bc.binding_id = b.id AND bc.tenant_id = b.tenant_id
             WHERE b.tenant_id = :tenantId
               AND b.status = 'ACTIVE'
               AND i.status = 'ACTIVE'
               AND bc.capability_code = :capability
               AND bc.enabled
               AND b.effective_from <= :now
               AND (b.effective_until IS NULL OR b.effective_until > :now)
               AND (
                    (b.location_id = :locationId)
                 OR (b.location_id IS NULL AND b.brand_id = :brandId)
               )
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcProviderInstallationLookup(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<BindingRef> primaryBinding(UUID tenantId, UUID brandId, UUID locationId, String capabilityCode) {

        List<Candidate> candidates = candidates(tenantId, brandId, locationId, capabilityCode);

        // Narrowest scope first: a location binding overrides its brand's, the
        // same direction ADR 0030 resolves configuration.
        return candidates.stream()
                .filter(Candidate::primary)
                .min((left, right) -> {
                    int byScope = Integer.compare(specificity(right), specificity(left));
                    return byScope != 0 ? byScope : Integer.compare(left.priority(), right.priority());
                })
                .map(Candidate::ref);
    }

    @Override
    public List<BindingRef> candidateBindings(UUID tenantId, UUID brandId, UUID locationId, String capabilityCode) {

        return candidates(tenantId, brandId, locationId, capabilityCode).stream()
                .sorted((left, right) -> {
                    int byScope = Integer.compare(specificity(right), specificity(left));
                    return byScope != 0 ? byScope : Integer.compare(left.priority(), right.priority());
                })
                .map(Candidate::ref)
                .toList();
    }

    @Override
    public Optional<InstallationSnapshot> installation(UUID tenantId, UUID installationId) {
        return jdbc.sql("""
                SELECT i.id, i.provider_category, i.provider_type, i.environment_code,
                       e.base_url, i.status, i.secret_reference, i.adapter_version
                  FROM integration.installations i
                  JOIN integration.provider_environments e ON e.code = i.environment_code
                 WHERE i.tenant_id = :tenantId AND i.id = :installationId
                """)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .query((rs, n) -> new InstallationSnapshot(
                        rs.getObject("id", UUID.class),
                        ProviderCategory.valueOf(rs.getString("provider_category")),
                        rs.getString("provider_type"),
                        rs.getString("environment_code"),
                        rs.getString("base_url"),
                        rs.getString("status"),
                        rs.getString("secret_reference"),
                        rs.getString("adapter_version")))
                .optional();
    }

    @Override
    public Optional<String> externalIdFor(UUID bindingId, String entityType, UUID horecaosEntityId) {
        return jdbc.sql("""
                SELECT external_entity_id FROM integration.provider_entity_mappings
                 WHERE binding_id = :bindingId AND entity_type = :entityType
                   AND horecaos_entity_id = :horecaosEntityId AND status = 'ACTIVE'
                """)
                .param("bindingId", bindingId)
                .param("entityType", entityType)
                .param("horecaosEntityId", horecaosEntityId)
                .query(String.class)
                .optional();
    }

    @Override
    public Optional<UUID> horecaosIdFor(UUID bindingId, String entityType, String externalId) {
        return jdbc.sql("""
                SELECT horecaos_entity_id FROM integration.provider_entity_mappings
                 WHERE binding_id = :bindingId AND entity_type = :entityType
                   AND external_entity_id = :externalId AND status = 'ACTIVE'
                """)
                .param("bindingId", bindingId)
                .param("entityType", entityType)
                .param("externalId", externalId)
                .query(UUID.class)
                .optional();
    }

    private List<Candidate> candidates(UUID tenantId, UUID brandId, UUID locationId, String capabilityCode) {

        return jdbc.sql(SELECT_BINDINGS)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("capability", capabilityCode)
                .param("now", clock.instant().atOffset(ZoneOffset.UTC))
                .query((rs, n) -> new Candidate(
                        new BindingRef(
                                rs.getObject("id", UUID.class),
                                rs.getObject("installation_id", UUID.class),
                                rs.getObject("tenant_id", UUID.class),
                                ProviderCategory.valueOf(rs.getString("provider_category")),
                                rs.getString("provider_type"),
                                rs.getObject("brand_id", UUID.class),
                                rs.getObject("location_id", UUID.class)),
                        rs.getBoolean("is_primary"),
                        rs.getInt("priority")))
                .list();
    }

    private static int specificity(Candidate candidate) {
        return candidate.ref().locationId() != null ? 2 : 1;
    }

    private record Candidate(BindingRef ref, boolean primary, int priority) {}
}
