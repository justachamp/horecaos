package uz.horecaos.platform.commercial.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.iam.api.EntitlementGate;

/**
 * Implements {@code iam.api.EntitlementGate} so Staff 9.5's access check
 * (ADR 0109) can fold the ADR 0021 entitlement branch into its answer without
 * {@code iam} depending on {@code commercial} — see that interface's own doc
 * for why the port lives there and the adapter here.
 */
@Component
public class EntitlementGateAdapter implements EntitlementGate {

    private final EntitlementService entitlements;

    public EntitlementGateAdapter(EntitlementService entitlements) {
        this.entitlements = entitlements;
    }

    /**
     * The grant itself, read from the resolved snapshot and not from {@code featureEnabled}: a
     * feature nobody granted resolves to the catalogue's safe default (false) under the default
     * meter-only mode, and {@code featureEnabled} answers true for it because meter-only never
     * refuses. {@code DigestScheduler} documents the same trap for the same reason.
     */
    @Override
    public Optional<Answer> checkGranted(UUID tenantId, String entitlementKeyCode) {
        return EntitlementKeys.find(entitlementKeyCode)
                .filter(key -> key.valueType() == Boolean.class)
                .map(key -> new Answer(
                        Boolean.TRUE.equals(
                                entitlements.snapshot(tenantId).require(key).featureEnabled()),
                        key.description(),
                        "/api/v1/control-plane/plans"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Answer> checkFeature(UUID tenantId, String entitlementKeyCode) {
        return EntitlementKeys.find(entitlementKeyCode)
                .filter(key -> key.valueType() == Boolean.class)
                .map(key -> (EntitlementKey<Boolean>) key)
                .map(key -> new Answer(
                        entitlements.featureEnabled(tenantId, key), key.description(), "/api/v1/control-plane/plans"));
    }
}
