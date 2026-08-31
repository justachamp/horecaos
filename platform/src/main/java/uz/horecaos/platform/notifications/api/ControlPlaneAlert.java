package uz.horecaos.platform.notifications.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One fact for the ADR 0058 control-plane audience — platform staff, never
 * a tenant's own group.
 *
 * <p>Deliberately carries no tenant/brand/location: control-plane data is
 * platform-owned, not tenant-owned, the same distinction {@code
 * ProviderCircuitMetrics}'s own Javadoc draws for the breaker ("the only
 * label is the provider type... No tenant, no binding, no merchant
 * account"). {@code subjectId} is a plain string rather than a {@code UUID}
 * for the same reason: a control-band metric id is a slug
 * ({@code ops/bands.yaml}'s own {@code id} field), not a database key.
 */
public record ControlPlaneAlert(
        String eventClass, String subjectType, String subjectId, Map<String, String> variables, Instant occurredAt) {

    public ControlPlaneAlert {
        Objects.requireNonNull(eventClass, "An event class is required");
        Objects.requireNonNull(subjectType, "A subject type is required");
        Objects.requireNonNull(subjectId, "A subject id is required");
        variables = Map.copyOf(Objects.requireNonNull(variables, "Variables are required"));
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }
}
