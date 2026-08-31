package uz.horecaos.platform.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.api.EntitlementSnapshot;
import uz.horecaos.platform.commercial.api.LimitCheck;

/**
 * {@link TelegramOperationsEntitlementGate} — the local seam this build
 * uses in place of wave6-digests' canonical {@code telegram.*} key, per
 * that class's own Javadoc.
 */
class TelegramOperationsEntitlementGateTests {

    @Test
    void asksTheEntitlementServiceForTheGatesOwnKey() {
        UUID tenantId = UUID.randomUUID();
        java.util.List<EntitlementKey<Boolean>> asked = new java.util.ArrayList<>();
        TelegramOperationsEntitlementGate gate = new TelegramOperationsEntitlementGate(new EntitlementService() {
            @Override
            public EntitlementSnapshot snapshot(UUID tenant) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LimitCheck check(UUID tenant, EntitlementKey<Long> key, long requested) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LimitCheck require(UUID tenant, EntitlementKey<Long> key, long requested) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean featureEnabled(UUID tenant, EntitlementKey<Boolean> key) {
                asked.add(key);
                return tenant.equals(tenantId);
            }

            @Override
            public void requireFeature(UUID tenant, EntitlementKey<Boolean> key) {
                throw new UnsupportedOperationException();
            }
        });

        boolean enabled = gate.enabledFor(tenantId);

        assertThat(enabled).isTrue();
        assertThat(asked).hasSize(1);
        assertThat(asked.get(0).code()).isEqualTo("telegram.operations_alerts.enabled");
        assertThat(gate.enabledFor(UUID.randomUUID())).isFalse();
    }
}
