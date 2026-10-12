package uz.horecaos.platform.kitchen.web;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/** The scope an operations policy endpoint names with {@code ?brandId=&locationId=}: tenant by default. */
final class KitchenPolicyScopes {

    private KitchenPolicyScopes() {}

    static ResourceScope of(UUID tenantId, @Nullable UUID brandId, @Nullable UUID locationId) {
        if (locationId != null) {
            if (brandId == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "locationId needs the brandId it belongs to");
            }
            return ResourceScope.location(tenantId, brandId, locationId);
        }
        return brandId != null ? ResourceScope.brand(tenantId, brandId) : ResourceScope.tenant(tenantId);
    }
}
