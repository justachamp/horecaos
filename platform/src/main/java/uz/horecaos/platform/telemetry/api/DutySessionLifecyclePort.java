package uz.horecaos.platform.telemetry.api;

import java.util.UUID;
import uz.horecaos.platform.audit.api.ActorRef;

/**
 * What a shift tells the duty session it was opened from (ADR 0045, ADR 0179).
 *
 * <p>ADR 0045: "a duty session opens from a shift and never independently", and collection that
 * continues after sign-off is the failure the whole feature exists to prevent. Opening was always
 * tied to the shift ({@link CourierShiftPort} refuses without one); this is the other half, so that
 * the window closes when the shift does and pauses when the courier takes a break, whoever caused
 * it. A courier who closes the shift and whose app never closes the session, a manager who ends
 * service, and the sweeper that closes a shift left open overnight all arrive here -- before this
 * existed none of them touched the session, and the live pin of a courier who had gone home stayed
 * on the dispatcher's map.
 *
 * <p>Called by {@code courier}'s shift service inside the shift's own transaction, so the shift and
 * its session cannot disagree about whether the courier is working. A courier with no open session
 * is the ordinary case (the app never went on duty, or the gate is off) and is a no-op, not an error.
 * Nothing here carries a position.
 */
public interface DutySessionLifecyclePort {

    /** The shift ended: any open or suspended session of this courier ends with it. */
    void shiftEnded(
            UUID tenantId, UUID courierId, ActorRef actor, String capabilityUsed, String reason, String correlationId);

    /** A break began: collection stops. A no-op when the courier has no open session. */
    void breakStarted(UUID tenantId, UUID courierId);

    /** The break ended: collection resumes. A no-op when the session is not suspended. */
    void breakEnded(UUID tenantId, UUID courierId);
}
