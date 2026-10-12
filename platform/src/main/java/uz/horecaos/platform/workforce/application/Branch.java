package uz.horecaos.platform.workforce.application;

import java.util.UUID;
import uz.horecaos.platform.iam.api.ResourceScope;

/** One branch of one tenant: the scope every workforce statement names (ADR 0156). */
public record Branch(UUID tenantId, UUID brandId, UUID locationId) {

    public ResourceScope scope() {
        return ResourceScope.location(tenantId, brandId, locationId);
    }
}
