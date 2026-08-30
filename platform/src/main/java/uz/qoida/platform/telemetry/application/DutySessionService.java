package uz.qoida.platform.telemetry.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.audit.api.ActorRef;
import uz.qoida.platform.audit.api.AuditClass;
import uz.qoida.platform.audit.api.AuditFact;
import uz.qoida.platform.audit.api.AuditRecorder;
import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.telemetry.api.CourierShiftPort;
import uz.qoida.platform.telemetry.domain.CollectionGate;
import uz.qoida.platform.telemetry.domain.DutySessionStatus;
import uz.qoida.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore;
import uz.qoida.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore.DutySessionRow;
import uz.qoida.platform.web.api.ApiException;
import uz.qoida.platform.web.api.ErrorCode;

/**
 * Opening, suspending, and closing the window in which a courier is tracked
 * (ADR 0045).
 *
 * <p>This is the only thing that turns collection on, and it refuses three ways.
 * There is no open ADR 0042 shift, so the courier is not working. The
 * self-employment registration is absent or expired, so the arrangement has
 * stopped being a declared one and the platform is the only party positioned to
 * notice. Or ADR 0042 has not shipped at all, in which case the port is unwired
 * and every open is refused — which is the correct direction to fail, because a
 * stand-in that said yes would collect a named person's location with nothing
 * checked.
 *
 * <p>Opening and closing are audited; the position reads they enable are not. The
 * asymmetry is deliberate and ADR 0045 states it: auditing a five-second map
 * produces more audit rows than the tenant has orders and buries the reveal that
 * matters, while the decision that a named person is now being tracked at all is
 * exactly the kind of act ADR 0027 exists to record.
 */
@Service
public class DutySessionService {

    private final JdbcTelemetryStore store;
    private final CourierShiftPort shifts;
    private final AuditRecorder audit;
    private final Clock clock;

    public DutySessionService(JdbcTelemetryStore store, CourierShiftPort shifts,
            AuditRecorder audit, Clock clock) {
        this.store = store;
        this.shifts = shifts;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Opens a session, or returns the one that is already open.
     *
     * <p>Re-opening is not an error. A courier's handset reconnects, a device is
     * swapped mid-shift, and an app is force-closed and reopened; every one of
     * those posts an open, and answering the second one with a conflict would
     * leave a working courier unable to be tracked while the dispatcher watches a
     * stale pin.
     */
    @Transactional
    public DutySessionRow open(OpenCommand command) {
        Optional<DutySessionRow> existing = store.findOpenSession(command.tenantId(), command.courierId());
        if (existing.isPresent()) {
            return existing.get();
        }

        if (!shifts.isWired()) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "A duty session cannot open until ADR 0042 supplies the shift and registration "
                            + "check. Collection without it is not something this platform does.",
                    Map.of("reason", CourierShiftPort.NOT_WIRED_REASON));
        }

        CourierShiftPort.OpenShift shift = shifts
                .openShift(command.tenantId(), command.courierId(), command.locationId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_CONFLICT,
                        "This courier has no open shift at this branch, so there is nothing to "
                                + "collect for. A shift is the courier's own declaration that they "
                                + "are working (ADR 0042).",
                        Map.of("reason", "NO_OPEN_SHIFT")));

        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (shift.registrationValidUntil().isBefore(today)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "This courier's self-employment registration expired on %s. New work and new "
                            + "collection are refused until it is renewed; work already accepted is "
                            + "unaffected (ADR 0042)."
                            .formatted(shift.registrationValidUntil()),
                    Map.of("reason", "REGISTRATION_LAPSED",
                            "registrationValidUntil", shift.registrationValidUntil().toString()));
        }

        DutySessionRow session = new DutySessionRow(
                UUID.randomUUID(), command.tenantId(), shift.brandId(), shift.locationId(),
                command.courierId(), shift.shiftId(), command.deviceId(),
                DutySessionStatus.OPEN, command.collectionGate(),
                now, shift.registrationValidUntil(), command.actor().subject(),
                now, null, null, null, 1);

        store.insertDutySession(session);

        audit.record(AuditFact.of("telemetry.duty_session.opened", AuditClass.BUSINESS)
                .by(command.actor())
                .at(ResourceScope.location(command.tenantId(), shift.brandId(), shift.locationId()))
                .target("CourierDutySession", session.id())
                .targetVersion(1L)
                .because(command.reason())
                .usingCapability(command.capabilityUsed())
                .changed(Map.of(
                        "courierId", command.courierId().toString(),
                        "shiftId", shift.shiftId().toString(),
                        "collectionGate", command.collectionGate().name(),
                        "registrationValidUntil", shift.registrationValidUntil().toString()))
                .correlatedBy(command.correlationId())
                .occurredAt(now)
                .build());

        return session;
    }

    /** A break began. Collection stops; the pin goes stale and then goes. */
    @Transactional
    public void suspend(UUID tenantId, UUID sessionId) {
        if (!store.transitionSession(tenantId, sessionId,
                DutySessionStatus.OPEN, DutySessionStatus.SUSPENDED, clock.instant())) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "This duty session is not open, so there is no collection to suspend");
        }
    }

    /** The break ended. ADR 0042 owns that decision; this only follows it. */
    @Transactional
    public void resume(UUID tenantId, UUID sessionId) {
        if (!store.transitionSession(tenantId, sessionId,
                DutySessionStatus.SUSPENDED, DutySessionStatus.OPEN, clock.instant())) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT,
                    "This duty session is not suspended, so there is no break to end");
        }
    }

    /**
     * Signs off. The live row survives one more hour so a dispatcher finishing a
     * call can still see where the courier was; the retention sweeper removes it.
     */
    @Transactional
    public void close(UUID tenantId, UUID sessionId, String endReason, ActorRef actor,
            String reason, String capabilityUsed, String correlationId) {

        DutySessionRow session = store.findSession(tenantId, sessionId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such duty session"));

        Instant now = clock.instant();
        if (!store.closeSession(tenantId, sessionId, endReason, now)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This duty session is already closed");
        }

        audit.record(AuditFact.of("telemetry.duty_session.closed", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.location(tenantId, session.brandId(), session.locationId()))
                .target("CourierDutySession", sessionId)
                .targetVersion((long) session.version() + 1)
                .because(reason)
                .usingCapability(capabilityUsed)
                .changed(Map.of("courierId", session.courierId().toString(), "endReason", endReason))
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
    }

    public Optional<DutySessionRow> openSession(UUID tenantId, UUID courierId) {
        return store.findOpenSession(tenantId, courierId);
    }

    /**
     * @param collectionGate resolved through ADR 0030 before it reaches here, so
     *                       the narrower gate is a configuration change rather
     *                       than a code change
     * @param reason         required by ADR 0027 for a user-initiated action:
     *                       "opened by the courier" and "opened by a manager
     *                       because the courier's phone died" are different facts
     */
    public record OpenCommand(
            UUID tenantId, UUID courierId, UUID locationId, String deviceId,
            CollectionGate collectionGate, ActorRef actor, String reason,
            String capabilityUsed, String correlationId) {
    }
}
