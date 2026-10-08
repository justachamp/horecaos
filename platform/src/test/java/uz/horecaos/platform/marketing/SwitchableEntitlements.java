package uz.horecaos.platform.marketing;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.api.EntitlementSnapshot;
import uz.horecaos.platform.commercial.api.LimitCheck;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * An {@link EntitlementService} that includes every feature until a test takes one away, and then
 * answers the way a plan that lacks it does: {@code featureEnabled} says no and {@code
 * requireFeature} refuses with {@code ENTITLEMENT_REQUIRED}.
 *
 * <p>{@link AlwaysEntitledService} cannot say no, so it cannot show that a check was made at all.
 * A plan can lapse after a launch, which is why the point of this class is that a test changes its
 * answer between two calls.
 */
final class SwitchableEntitlements implements EntitlementService {

    private final Set<String> denied = ConcurrentHashMap.newKeySet();

    /** From now on the tenant's plan does not include this feature. */
    void deny(EntitlementKey<Boolean> key) {
        denied.add(key.code());
    }

    /** From now on it does again. */
    void allow(EntitlementKey<Boolean> key) {
        denied.remove(key.code());
    }

    @Override
    public EntitlementSnapshot snapshot(UUID tenantId) {
        return new EntitlementSnapshot(tenantId, null, Map.of(), Instant.now());
    }

    @Override
    public LimitCheck check(UUID tenantId, EntitlementKey<Long> key, long requested) {
        throw new UnsupportedOperationException("Not exercised by a marketing scenario test");
    }

    @Override
    public LimitCheck require(UUID tenantId, EntitlementKey<Long> key, long requested) {
        throw new UnsupportedOperationException("Not exercised by a marketing scenario test");
    }

    @Override
    public boolean featureEnabled(UUID tenantId, EntitlementKey<Boolean> key) {
        return !denied.contains(key.code());
    }

    @Override
    public void requireFeature(UUID tenantId, EntitlementKey<Boolean> key) {
        if (denied.contains(key.code())) {
            throw new ApiException(
                    ErrorCode.ENTITLEMENT_REQUIRED, "The current plan does not include %s".formatted(key.code()));
        }
    }
}
