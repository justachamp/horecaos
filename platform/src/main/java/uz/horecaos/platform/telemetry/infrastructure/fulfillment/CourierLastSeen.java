package uz.horecaos.platform.telemetry.infrastructure.fulfillment;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.telemetry.api.CourierLastSeenPort;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore;

/** The one query behind {@link CourierLastSeenPort} — a plain pass-through, unlike its sibling
 * {@link LivePositionProximity}: there is no freshness or accuracy filter to apply here, because
 * the caller's own "online within N minutes" policy is the freshness rule, not a map's. */
@Component
public class CourierLastSeen implements CourierLastSeenPort {

    private final JdbcTelemetryStore store;

    public CourierLastSeen(JdbcTelemetryStore store) {
        this.store = store;
    }

    @Override
    public Map<UUID, Instant> lastFixByCourier(UUID tenantId, Collection<UUID> courierIds) {
        return store.lastFixByCourier(tenantId, courierIds);
    }
}
