package uz.horecaos.platform.telemetry.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.telemetry.api.CourierDutyPort;
import uz.horecaos.platform.telemetry.api.CourierShiftPort;
import uz.horecaos.platform.telemetry.api.TelemetryConfigurationKeys;
import uz.horecaos.platform.telemetry.application.DutySessionService.OpenCommand;
import uz.horecaos.platform.telemetry.application.TelemetryIngestService.IngestOutcome;
import uz.horecaos.platform.telemetry.domain.CollectionGate;
import uz.horecaos.platform.telemetry.domain.LivePositionRules;
import uz.horecaos.platform.telemetry.domain.TrackObservation;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore.DutySessionRow;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * {@link CourierDutyPort}: a courier's own duty session and observations (ADR 0045, ADR 0179).
 *
 * <p>Everything that matters about collection is still {@link DutySessionService} and
 * {@link TelemetryIngestService}, unchanged: a session still opens only from an open shift at this
 * branch with a valid registration, and a batch is still ordered through the checks ADR 0045 lists.
 * What this adds is what a staff caller supplies by hand -- the collection gate, which the courier
 * does not choose (it resolves through ADR 0030 at the branch), and the reason and capability an
 * audit fact names -- plus the branch check on a batch.
 */
@Service
public class CourierDutyFacade implements CourierDutyPort {

    /** The end reason on a session the courier signed off themself. */
    public static final String SIGNED_OFF = "SIGNED_OFF";

    private final DutySessionService sessions;
    private final TelemetryIngestService ingest;
    private final CourierShiftPort shifts;
    private final ConfigurationResolver configuration;

    public CourierDutyFacade(
            DutySessionService sessions,
            TelemetryIngestService ingest,
            CourierShiftPort shifts,
            ConfigurationResolver configuration) {
        this.sessions = sessions;
        this.ingest = ingest;
        this.shifts = shifts;
        this.configuration = configuration;
    }

    @Override
    public Optional<Duty> current(UUID tenantId, UUID courierId, UUID locationId) {
        return sessions.openSession(tenantId, courierId)
                .filter(session -> session.locationId().equals(locationId))
                .map(CourierDutyFacade::view);
    }

    @Override
    public Duty open(
            UUID tenantId, UUID courierId, UUID locationId, String deviceId, String subject, String correlationId) {

        // The brand a shift belongs to is the authority on which scope the gate resolves at; the
        // brand in a URL is not trusted for it. With no shift the open is refused below anyway.
        CollectionGate gate = shifts.openShift(tenantId, courierId, locationId)
                .map(shift -> resolveGate(tenantId, shift.brandId(), locationId))
                .orElse(CollectionGate.ON_DUTY);

        return view(sessions.open(new OpenCommand(
                tenantId,
                courierId,
                locationId,
                deviceId,
                gate,
                ActorRef.user(subject, null),
                "The courier went on duty from their own handset",
                Capability.COURIER_TELEMETRY.code(),
                correlationId)));
    }

    @Override
    public void close(UUID tenantId, UUID courierId, UUID sessionId, String subject, String correlationId) {
        DutySessionRow session = sessions.openSession(tenantId, courierId)
                .filter(open -> open.id().equals(sessionId))
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such open duty session of yours"));

        sessions.close(
                tenantId,
                session.id(),
                SIGNED_OFF,
                ActorRef.user(subject, null),
                "The courier signed off from their own handset",
                Capability.COURIER_TELEMETRY.code(),
                correlationId);
    }

    /**
     * The branch in the path must be the branch the session is open at: a handset that posts to
     * another branch's path has lost track of where it is working, and storing the batch anyway
     * would put a position on one branch's board under another branch's URL.
     */
    @Override
    public BatchOutcome ingest(
            UUID tenantId, UUID courierId, UUID locationId, int activeAssignmentCount, List<Reading> readings) {

        boolean atThisBranch = sessions.openSession(tenantId, courierId)
                .map(session -> session.locationId().equals(locationId))
                .orElse(false);
        if (!atThisBranch) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "This courier has no open duty session at this branch, so nothing about their "
                            + "location is collected. Go on duty first (ADR 0045).",
                    Map.of("reason", "NO_OPEN_DUTY_SESSION"));
        }

        IngestOutcome outcome = ingest.ingest(
                tenantId,
                courierId,
                activeAssignmentCount,
                readings.stream().map(CourierDutyFacade::toObservation).toList());

        return new BatchOutcome(
                outcome.observationsAccepted(),
                outcome.windowsWritten(),
                outcome.livePositionMoved(),
                outcome.gate().name(),
                outcome.suspended(),
                // The device's next cadence, sent on every response so the platform keeps the one
                // lever it has over battery, data cost and write volume.
                (int) LivePositionRules.BATCH_CADENCE.toSeconds());
    }

    private CollectionGate resolveGate(UUID tenantId, UUID brandId, UUID locationId) {
        String configured = configuration.value(
                TelemetryConfigurationKeys.COLLECTION_GATE, ResourceScope.location(tenantId, brandId, locationId));
        // A value nobody can parse falls back to the key's own default. The ingest service
        // re-resolves the live value on every batch and is the one that decides what is collected;
        // what is resolved here is only what the session row records at open.
        return CollectionGate.find(configured).orElse(CollectionGate.ON_DUTY);
    }

    private static TrackObservation toObservation(Reading reading) {
        try {
            return new TrackObservation(
                    reading.capturedAt(),
                    reading.latitude(),
                    reading.longitude(),
                    reading.accuracyMeters(),
                    reading.headingDegrees(),
                    reading.speedMps(),
                    reading.batteryPercent(),
                    reading.deviceCharging());
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }
    }

    private static Duty view(DutySessionRow session) {
        return new Duty(
                session.id(),
                session.shiftId(),
                session.locationId(),
                session.status().name(),
                session.collectionGate().name(),
                session.startedAt(),
                session.registrationValidUntil(),
                (int) LivePositionRules.BATCH_CADENCE.toSeconds(),
                LivePositionRules.MAXIMUM_BATCH_SIZE);
    }
}
