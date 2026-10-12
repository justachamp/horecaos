package uz.horecaos.platform.printing.application;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The refusal every printing mutation shares when the tenant has not switched printing on (ADR 0154, "Ship
 * dark"): {@code feature.printing} (ADR 0082) is off by default and turned on by hand, tenant by tenant, so a
 * tenant that has not been given it cannot create a printer, a route or a job. Reads still answer -- an empty
 * queue and the rows that exist -- so a screen can say printing is off instead of failing.
 */
@Component
public class PrintingGuard {

    private final PrintingPolicy policy;

    public PrintingGuard(PrintingPolicy policy) {
        this.policy = policy;
    }

    public void requireEnabled(UUID tenantId, UUID brandId, UUID locationId) {
        if (!policy.enabled(tenantId, brandId, locationId)) {
            throw off();
        }
    }

    /** The same refusal for a brand-level setting, which has no branch in its path. */
    public void requireEnabledForBrand(UUID tenantId, UUID brandId) {
        if (!policy.enabledForBrand(tenantId, brandId)) {
            throw off();
        }
    }

    private static ApiException off() {
        return new ApiException(
                ErrorCode.UNPROCESSABLE_STATE,
                "Printing is not switched on for this tenant",
                Map.of("reason", "PRINTING_OFF"));
    }
}
