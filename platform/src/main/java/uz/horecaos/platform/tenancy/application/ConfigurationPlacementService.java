package uz.horecaos.platform.tenancy.application;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationPlacementStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationPlacementStore.StoredPolicy;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationPlacementStore.StoredValue;

/**
 * Where each setting and each listed policy of a tenant is set (settings.md §10.0, gap map row {@code 10.0}).
 *
 * <p>Two reads for the whole tenant, whatever the number of keys, rather than one per key: «Find a setting»
 * resolves the index once when it opens. Filtering to the keys a tenant may see is the caller's, because the
 * visibility rule belongs to the operations surface (a platform-only default is not merely omitted from a
 * list, asking for it by code is a not-found).
 */
@Service
public class ConfigurationPlacementService {

    private final JdbcConfigurationPlacementStore store;

    public ConfigurationPlacementService(JdbcConfigurationPlacementStore store) {
        this.store = store;
    }

    /** Every value stored for the tenant at tenant, brand or branch scope, by key then by how general the scope is. */
    @Transactional(readOnly = true)
    public List<StoredValue> values(UUID tenantId) {
        return store.valuesOf(tenantId);
    }

    /** The newest version of each {@link TenantPolicyFamilies#LISTED} policy at every scope that holds one. */
    @Transactional(readOnly = true)
    public List<StoredPolicy> policies(UUID tenantId) {
        return store.policiesOf(tenantId, TenantPolicyFamilies.LISTED).stream()
                .sorted(Comparator.comparingInt(
                                (StoredPolicy policy) -> TenantPolicyFamilies.LISTED.indexOf(policy.keyCode()))
                        .thenComparing(policy -> policy.scopeType().ordinal()))
                .toList();
    }
}
