package uz.horecaos.platform.marketing;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.api.EntitlementSnapshot;
import uz.horecaos.platform.commercial.api.LimitCheck;

/**
 * The {@link EntitlementService} every marketing test that does not care about
 * entitlements wires in place of {@code EntitlementQueryService} — no test
 * tenant here has a plan or an override, so a real resolution would answer
 * exactly this anyway (ADR 0021's safe-default rule). Mirrors {@code
 * notifications.AlwaysEntitledService}, duplicated per module rather than
 * shared, the same way that class is its own copy rather than a shared test
 * fixture.
 */
final class AlwaysEntitledService implements EntitlementService {

    @Override
    public EntitlementSnapshot snapshot(UUID tenantId) {
        return new EntitlementSnapshot(tenantId, null, Map.of(), Instant.now());
    }

    @Override
    public LimitCheck check(UUID tenantId, EntitlementKey<Long> key, long requested) {
        throw new UnsupportedOperationException("Not exercised by a marketing campaign test");
    }

    @Override
    public LimitCheck require(UUID tenantId, EntitlementKey<Long> key, long requested) {
        throw new UnsupportedOperationException("Not exercised by a marketing campaign test");
    }

    @Override
    public boolean featureEnabled(UUID tenantId, EntitlementKey<Boolean> key) {
        return true;
    }

    @Override
    public void requireFeature(UUID tenantId, EntitlementKey<Boolean> key) {
        // Every feature is entitled; nothing to refuse.
    }
}
