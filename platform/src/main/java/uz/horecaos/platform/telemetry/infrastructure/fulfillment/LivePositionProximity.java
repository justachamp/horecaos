package uz.horecaos.platform.telemetry.infrastructure.fulfillment;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import uz.horecaos.platform.telemetry.api.CourierProximityPort;
import uz.horecaos.platform.telemetry.domain.LivePositionRules;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore.ProximityRow;

/**
 * The distance half of the dispatcher's map, answered to ADR 0042's dispatch
 * ranking without handing it a position (ADR 0045).
 *
 * <p>The two filters below are the map's own, and they are here rather than in
 * the SQL so that the rule a dispatcher sees drawn and the rule dispatch ranks
 * on cannot drift apart.
 *
 * <p><strong>Stale is dropped, not ranked.</strong> A fix older than ten
 * minutes describes where a courier was before he went into a basement kitchen
 * or a lift. Ranking on it sends the next order to whoever the telemetry
 * happened to lose, which is the opposite of the intent — so it becomes an
 * absent entry, and ADR 0014's comparator sorts an absent distance last.
 *
 * <p><strong>Coarse is dropped for the same reason the map will not draw it.</strong>
 * A 900 m accuracy circle offered as a 200 m distance is a confident lie in the
 * direction that costs money: the "nearest" courier is a kilometre from where
 * the number says. The observation is still stored — a coarse fix is evidence
 * of roughly where somebody was — it simply does not decide who gets the order.
 *
 * <p>The clock is injected because both rules are durations, and a duration
 * asserted against a fixed instant is not asserted at all.
 */
@Component
public class LivePositionProximity implements CourierProximityPort {

    private final JdbcTelemetryStore store;
    private final Clock clock;

    public LivePositionProximity(JdbcTelemetryStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public Map<UUID, Integer> metresFromBranch(UUID tenantId, UUID locationId,
            Collection<UUID> courierIds) {

        Instant now = clock.instant();
        Map<UUID, Integer> metres = new HashMap<>();

        for (ProximityRow row : store.metresFromBranch(tenantId, locationId, courierIds)) {
            if (!LivePositionRules.freshEnoughForTheMap(row.capturedAt(), now)) {
                continue;
            }
            if (!LivePositionRules.drawable(row.accuracyMeters())) {
                continue;
            }
            // Rounded to a whole metre. Sub-metre precision on a straight-line
            // distance derived from a fix that is itself accurate to tens of
            // metres is precision the number does not have.
            metres.put(row.courierId(), (int) Math.round(row.metres()));
        }
        return Map.copyOf(metres);
    }
}
