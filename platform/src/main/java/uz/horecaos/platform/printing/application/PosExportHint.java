package uz.horecaos.platform.printing.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.rls.TenantRlsSession;
import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.pos.api.PosCapability;

/**
 * Whether a branch's point of sale may already print what HorecaOS could print (ADR 0154, Decision 6).
 *
 * <p>An order sent to a till prints at the till because it arrives there, and the platform cannot read whether
 * it did (Decision 11 adds no print capability to ADR 0011). So the only honest fact is the one the platform
 * does hold: this branch has an active binding that provides {@code ORDER_EXPORT}. The routes screen says "your
 * point of sale may already print this" beside the kitchen-ticket route when it is true. It is a statement, not
 * a gate: it creates no route, removes none and refuses nothing.
 */
@Service
public class PosExportHint {

    private final TenantRlsSession rls;
    private final ProviderInstallationLookup installations;

    public PosExportHint(TenantRlsSession rls, ProviderInstallationLookup installations) {
        this.rls = rls;
        this.installations = installations;
    }

    @Transactional(readOnly = true)
    public boolean orderExportBound(UUID tenantId, UUID brandId, UUID locationId) {
        rls.bindTenant(tenantId);
        return installations
                .primaryBinding(tenantId, brandId, locationId, PosCapability.ORDER_EXPORT.code())
                .isPresent();
    }
}
