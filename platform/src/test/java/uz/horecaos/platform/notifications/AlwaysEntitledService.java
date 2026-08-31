package uz.horecaos.platform.notifications;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.api.EntitlementSnapshot;
import uz.horecaos.platform.commercial.api.LimitCheck;

/**
 * The {@link EntitlementService} every operations-trigger test wires in place
 * of {@code EntitlementQueryService} — these tests are plain JUnit against a
 * real PostgreSQL, not a Spring context, and no test tenant here has a plan
 * or an override, so a real resolution would answer exactly this anyway (ADR
 * 0021's safe-default rule). Named for what it is: every feature reads
 * enabled, which is what {@link uz.horecaos.platform.notifications.application.TelegramOperationsEntitlementGate}
 * needs and nothing more, so the counted methods are never expected to be
 * called by anything these tests exercise.
 */
final class AlwaysEntitledService implements EntitlementService {

    @Override
    public EntitlementSnapshot snapshot(UUID tenantId) {
        return new EntitlementSnapshot(tenantId, null, Map.of(), Instant.now());
    }

    @Override
    public LimitCheck check(UUID tenantId, EntitlementKey<Long> key, long requested) {
        throw new UnsupportedOperationException("Not exercised by an operations-alert trigger test");
    }

    @Override
    public LimitCheck require(UUID tenantId, EntitlementKey<Long> key, long requested) {
        throw new UnsupportedOperationException("Not exercised by an operations-alert trigger test");
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
