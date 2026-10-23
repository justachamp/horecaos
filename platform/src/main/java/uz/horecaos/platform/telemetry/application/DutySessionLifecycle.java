package uz.horecaos.platform.telemetry.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.telemetry.api.DutySessionLifecyclePort;
import uz.horecaos.platform.telemetry.domain.DutySessionStatus;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore.DutySessionRow;

/**
 * The shift's word to its duty session (ADR 0045, ADR 0179): the session ends with the shift and
 * pauses with a break.
 *
 * <p>It runs inside the shift's own transaction -- {@link DutySessionService}'s methods join it --
 * so a shift that closes and a session that fails to close cannot both be committed. None of the
 * three is allowed to fail the shift's own operation for want of a session: a courier with no open
 * session is the normal case, and a session in the "wrong" state for the call (already suspended
 * when a break starts, already closed when the shift ends) is left exactly as it is.
 */
@Component
public class DutySessionLifecycle implements DutySessionLifecyclePort {

    /** The reason code on a session closed by the end of its shift. */
    public static final String SHIFT_ENDED = "SHIFT_ENDED";

    private final DutySessionService sessions;

    public DutySessionLifecycle(DutySessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public void shiftEnded(
            UUID tenantId, UUID courierId, ActorRef actor, String capabilityUsed, String reason, String correlationId) {

        Optional<DutySessionRow> open = sessions.openSession(tenantId, courierId);
        if (open.isEmpty()) {
            return;
        }
        sessions.close(tenantId, open.get().id(), SHIFT_ENDED, actor, reason, capabilityUsed, correlationId);
    }

    @Override
    public void breakStarted(UUID tenantId, UUID courierId) {
        sessions.openSession(tenantId, courierId)
                .filter(session -> session.status() == DutySessionStatus.OPEN)
                .ifPresent(session -> sessions.suspend(tenantId, session.id()));
    }

    @Override
    public void breakEnded(UUID tenantId, UUID courierId) {
        sessions.openSession(tenantId, courierId)
                .filter(session -> session.status() == DutySessionStatus.SUSPENDED)
                .ifPresent(session -> sessions.resume(tenantId, session.id()));
    }
}
