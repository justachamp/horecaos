package uz.horecaos.platform.support;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.telemetry.api.DutySessionLifecyclePort;

/**
 * Stands in for the duty sessions a shift tells about its end and its breaks (ADR 0045, ADR 0179)
 * in a suite that builds {@code CourierShiftService} by hand and has no telemetry module behind it.
 *
 * <p>It records instead of ignoring, so a suite that cares what the shift said can read it, and one
 * that does not simply never looks.
 */
public final class RecordingDutySessions implements DutySessionLifecyclePort {

    public record Ended(UUID tenantId, UUID courierId, String capabilityUsed, String reason) {}

    public final List<Ended> ended = new CopyOnWriteArrayList<>();
    public final List<UUID> breaksStarted = new CopyOnWriteArrayList<>();
    public final List<UUID> breaksEnded = new CopyOnWriteArrayList<>();

    @Override
    public void shiftEnded(
            UUID tenantId, UUID courierId, ActorRef actor, String capabilityUsed, String reason, String correlationId) {
        ended.add(new Ended(tenantId, courierId, capabilityUsed, reason));
    }

    @Override
    public void breakStarted(UUID tenantId, UUID courierId) {
        breaksStarted.add(courierId);
    }

    @Override
    public void breakEnded(UUID tenantId, UUID courierId) {
        breaksEnded.add(courierId);
    }
}
