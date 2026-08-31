package uz.horecaos.platform.telemetry.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.telemetry.domain.LivePositionRules;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore.LivePositionRow;

/**
 * The dispatcher's map, and the only read of a live position in the platform
 * (ADR 0045).
 *
 * <p>Two things it does that a plain select would not.
 *
 * <p>It splits the fleet into pins and non-pins. An observation worse than 100 m
 * is stored, because a coarse fix is still evidence of roughly where somebody
 * was, and it is not drawn, because an accuracy circle rendered as a dot is a
 * confident lie that sends a courier to the wrong street. The coarse couriers are
 * still returned — by identity, with no coordinate — so the board shows that they
 * exist and are on duty rather than appearing to have vanished.
 *
 * <p>It is not audited. That is ADR 0045's decision and it is worth stating where
 * somebody reading this class will see it: a five-second map refreshed all shift
 * produces more audit rows than the tenant has orders, and burying the reveal
 * that matters under them is a worse outcome than not recording the refresh.
 * Opening a stored track is always audited, in {@link CourierTrackRevealService}.
 */
@Service
public class CourierPositionQueryService {

    private final JdbcTelemetryStore store;

    public CourierPositionQueryService(JdbcTelemetryStore store) {
        this.store = store;
    }

    /** Every on-duty courier of one branch, split by whether they can be drawn. */
    public FleetView fleetAt(UUID tenantId, UUID locationId, Instant now) {
        List<CourierPin> pins = new ArrayList<>();
        List<CoarseCourier> coarse = new ArrayList<>();

        for (LivePositionRow row : store.livePositionsAtLocation(tenantId, locationId)) {
            boolean fresh = LivePositionRules.freshEnoughForTheMap(row.capturedAt(), now);
            if (LivePositionRules.drawable(row.accuracyMeters()) && fresh) {
                pins.add(new CourierPin(
                        row.courierId(),
                        row.latitude(),
                        row.longitude(),
                        row.accuracyMeters(),
                        row.headingDegrees(),
                        row.speedMps(),
                        row.batteryPercent(),
                        row.deviceCharging(),
                        row.activeAssignmentCount(),
                        row.capturedAt()));
            } else {
                coarse.add(new CoarseCourier(
                        row.courierId(),
                        row.activeAssignmentCount(),
                        row.capturedAt(),
                        fresh ? "ACCURACY_BELOW_MAP_FLOOR" : "LAST_FIX_TOO_OLD"));
            }
        }
        return new FleetView(pins, coarse);
    }

    /**
     * One courier's own position.
     *
     * <p>Self-scoped: ADR 0045 gives a courier their own current position and
     * their own track, and this is the read behind the app's visible on-duty
     * indicator. A courier who signs off can see that it stopped.
     */
    public java.util.Optional<LivePositionRow> ownPosition(UUID tenantId, UUID courierId) {
        return store.livePosition(tenantId, courierId);
    }

    /**
     * One courier's rendered position, for the dispatcher's map.
     *
     * @param batteryPercent the one device value a dispatcher genuinely needs: a
     *                       phone that will die mid-delivery is an order that
     *                       arrives without anybody knowing where it is
     */
    public record CourierPin(
            UUID courierId,
            double latitude,
            double longitude,
            double accuracyMeters,
            @Nullable Double headingDegrees,
            @Nullable Double speedMps,
            @Nullable Integer batteryPercent,
            @Nullable Boolean deviceCharging,
            int activeAssignmentCount,
            Instant capturedAt) {}

    /** On duty, and not drawable. Present so the board is honest about the gap. */
    public record CoarseCourier(UUID courierId, int activeAssignmentCount, Instant lastFixAt, String reason) {}

    public record FleetView(List<CourierPin> pins, List<CoarseCourier> withoutPin) {}
}
