package uz.horecaos.platform.integration.provider.routing;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.configuration.Ids;

/**
 * The routing installations of ADR 0147, read and created with explicit SQL.
 *
 * <p>Deliberately not {@code ProviderInstallationLookup}: that port is tenant-keyed
 * because every other caller starts from a tenant's request. This one starts from a
 * tariff version's own {@code routing_provider_installation_id}, a reference whose
 * foreign key is composite with the tenant, so the id alone already names exactly one
 * tenant's row and the adapter has no tenant to supply.
 */
@Repository
class JdbcRoutingInstallations {

    /** The tenant's own account reference for the platform engine, which makes "ensure" idempotent in the database. */
    static final String PLATFORM_ACCOUNT_REFERENCE = "platform-routing";

    static final String PROVIDER_TYPE = "OSRM";
    static final String ENVIRONMENT_CODE = "osrm_internal";

    private final JdbcClient jdbc;

    JdbcRoutingInstallations(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The installation behind a tariff's routing reference, with the approved endpoint
     * its environment names.
     *
     * <p>Read on every call and never cached: a status change on the row has to take
     * effect on the next quote, and a cache holding an installation's standing for a TTL
     * would make that slower than the incident. No console or API door changes a platform
     * routing installation's status today, so the per-tenant rollback an operator uses is
     * the tariff version's distance mode (runbook step 7), not this row.
     */
    Optional<RoutingInstallation> find(UUID installationId) {
        return jdbc.sql("""
                SELECT i.id, i.tenant_id, i.provider_category, i.provider_type, i.status,
                       e.base_url
                  FROM integration.installations i
                  JOIN integration.provider_environments e ON e.code = i.environment_code
                 WHERE i.id = :id
                """)
                .param("id", installationId)
                .query((row, number) -> new RoutingInstallation(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getString("provider_category"),
                        row.getString("provider_type"),
                        row.getString("status"),
                        row.getString("base_url")))
                .optional();
    }

    /** The tenant's platform-routing installation, if it has one yet. */
    Optional<UUID> findPlatformRouting(UUID tenantId) {
        return jdbc.sql("""
                SELECT id FROM integration.installations
                 WHERE tenant_id = :tenantId AND provider_type = :type
                   AND environment_code = :environment
                   AND external_account_reference = :account
                """)
                .param("tenantId", tenantId)
                .param("type", PROVIDER_TYPE)
                .param("environment", ENVIRONMENT_CODE)
                .param("account", PLATFORM_ACCOUNT_REFERENCE)
                .query(UUID.class)
                .optional();
    }

    /**
     * Inserts the tenant's platform-routing installation unless it exists.
     *
     * <p>{@code ACTIVE} from the start, where a credentialed installation begins as
     * {@code DRAFT} and waits for a connection check: there is no credential to check
     * and no vendor account to confuse, and an installation that needed a second click
     * before it could be used would be the credential screen this decision removes.
     *
     * @return the new id, or empty when the unique account reference already held one
     */
    Optional<UUID> insertPlatformRouting(UUID tenantId) {
        UUID id = Ids.newId();
        int inserted = jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code,
                     display_name, status, secret_reference, external_account_reference)
                VALUES (:id, :tenantId, 'ROUTING', :type, :environment,
                        'Platform routing', 'ACTIVE', NULL, :account)
                ON CONFLICT (tenant_id, provider_type, environment_code, external_account_reference)
                    WHERE external_account_reference IS NOT NULL
                DO NOTHING
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("type", PROVIDER_TYPE)
                .param("environment", ENVIRONMENT_CODE)
                .param("account", PLATFORM_ACCOUNT_REFERENCE)
                .update();
        return inserted == 1 ? Optional.of(id) : Optional.empty();
    }

    record RoutingInstallation(
            UUID id, UUID tenantId, String category, String providerType, String status, String baseUrl) {

        boolean isActiveOsrm() {
            return "ROUTING".equals(category) && PROVIDER_TYPE.equals(providerType) && "ACTIVE".equals(status);
        }
    }
}
