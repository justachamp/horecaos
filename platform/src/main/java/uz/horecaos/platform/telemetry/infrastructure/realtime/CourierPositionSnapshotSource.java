package uz.horecaos.platform.telemetry.infrastructure.realtime;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.telemetry.api.ScopeKey;
import uz.horecaos.platform.telemetry.api.StreamChannel;
import uz.horecaos.platform.telemetry.application.CourierPositionQueryService;
import uz.horecaos.platform.telemetry.application.CourierPositionQueryService.FleetView;

/**
 * What the {@code COURIER_POSITIONS} snapshot frame carries (ADR 0045).
 *
 * <p>The one place in this module where a coordinate is written to a socket, and
 * it is worth being precise about why that is allowed. This frame is an
 * authorized HTTP response under ADR 0025 — the subscriber holds
 * {@code courier.position.read} at this location, checked at connect and again
 * whenever grants change — and it is not a Kafka payload. That distinction is
 * exactly why the frame may carry values ADR 0032 forbids on a topic: the record
 * that <em>caused</em> this frame carried a courier id and a scope key, and this
 * process read the live row it already had access to.
 *
 * <p>It is a snapshot rather than a signal for an arithmetic reason. A signal per
 * courier per tick would produce N fetches per tick for N couriers, against a
 * primary with no read replica that also serves ADR 0043's reporting — the exact
 * fetch amplification the coalescing budget exists to bound.
 *
 * <p>It reuses {@link CourierPositionQueryService} rather than querying itself,
 * so the accuracy floor and the staleness rule apply identically on the stream
 * and on the polling path. A stream that drew pins the polled map refuses would
 * be two maps disagreeing about where a courier is.
 */
@Component
public class CourierPositionSnapshotSource implements SnapshotSource {

    private final CourierPositionQueryService positions;
    private final Clock clock;

    public CourierPositionSnapshotSource(CourierPositionQueryService positions, Clock clock) {
        this.positions = positions;
        this.clock = clock;
    }

    @Override
    public StreamChannel channel() {
        return StreamChannel.COURIER_POSITIONS;
    }

    @Override
    public Optional<Object> snapshot(UUID tenantId, ScopeKey scopeKey) {
        if (scopeKey.type() != ScopeType.LOCATION) {
            return Optional.empty();
        }
        FleetView fleet = positions.fleetAt(tenantId, scopeKey.id(), clock.instant());
        if (fleet.pins().isEmpty() && fleet.withoutPin().isEmpty()) {
            // A branch with nobody on duty should not receive an empty list every
            // five seconds for the length of a shift.
            return Optional.empty();
        }
        return Optional.of(Map.of("pins", fleet.pins(), "withoutPin", fleet.withoutPin()));
    }
}
