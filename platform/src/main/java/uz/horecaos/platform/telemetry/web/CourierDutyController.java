package uz.horecaos.platform.telemetry.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.telemetry.application.DutySessionService;
import uz.horecaos.platform.telemetry.application.DutySessionService.OpenCommand;
import uz.horecaos.platform.telemetry.application.TelemetryIngestService;
import uz.horecaos.platform.telemetry.application.TelemetryIngestService.IngestOutcome;
import uz.horecaos.platform.telemetry.domain.CollectionGate;
import uz.horecaos.platform.telemetry.domain.LivePositionRules;
import uz.horecaos.platform.telemetry.domain.TrackObservation;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore.DutySessionRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Duty sessions and the observation batch (ADR 0045).
 *
 * <p><strong>These are staff-facing today, and that is a rollout fact rather
 * than a design decision.</strong> ADR 0045 ships telemetry ingest last, behind
 * the collection gate, and only once the courier transparency notice exists and
 * ADR 0042's registration check is live. ADR 0042 owns the courier principal and
 * the {@code courier.shift.open} capability a courier holds over their own
 * record; until that lands, every endpoint here declares
 * {@code courier.duty.manage} at a location scope — the capability ADR 0045
 * defines as opening or closing a session on a courier's behalf — and a dispatch
 * or branch principal is the only caller there is. When the courier principal
 * arrives, the courier's own path is a second controller with its own
 * declaration, not a widening of this one.
 *
 * <p>The batch endpoint is the busiest write in the platform: six posts a minute
 * per courier, of the order of 360,000 observations a day per tenant. It is
 * exempt from ADR 0031's mandatory {@code Idempotency-Key} record and idempotent
 * on a natural key instead, which ADR 0045 names as a narrow exemption and
 * nowhere else — an idempotency row per beacon would add six rows a minute per
 * courier to that table for no benefit at all.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/courier")
@Tag(name = "Courier duty and telemetry",
        description = "Opening and closing the window in which a courier's position is collected")
public class CourierDutyController {

    private final DutySessionService sessions;
    private final TelemetryIngestService ingest;
    private final CurrentActor currentActor;

    public CourierDutyController(DutySessionService sessions, TelemetryIngestService ingest,
            CurrentActor currentActor) {
        this.sessions = sessions;
        this.ingest = ingest;
        this.currentActor = currentActor;
    }

    @PostMapping("/duty-sessions")
    @RequiresCapability(value = Capability.COURIER_DUTY_MANAGE, scope = ScopeType.LOCATION,
            mutating = true)
    @Operation(summary = "Open the window in which this courier is tracked",
            description = "Refused unless ADR 0042 reports an open shift for this courier at this "
                    + "branch with a valid self-employment registration. Both refusals are "
                    + "deliberate: a courier with no shift is not working, and an expired "
                    + "registration turns a compliant arrangement into an undeclared one. Opening "
                    + "twice returns the session already open, because a reconnecting handset, a "
                    + "swapped device, and a force-closed app all post this.")
    public ResponseEntity<DutySessionResponse> open(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId,
            @Valid @RequestBody OpenRequest body) {

        DutySessionRow session = sessions.open(new OpenCommand(
                tenantId, body.courierId(), locationId, body.deviceId(),
                CollectionGate.find(body.collectionGate()).orElse(CollectionGate.ON_DUTY),
                ActorRef.user(currentActor.get().subject(), null),
                body.reason(), Capability.COURIER_DUTY_MANAGE.code(), correlationId()));

        return ResponseEntity.ok(DutySessionResponse.of(session));
    }

    @PostMapping("/duty-sessions/{sessionId}/breaks")
    @RequiresCapability(value = Capability.COURIER_DUTY_MANAGE, scope = ScopeType.LOCATION,
            mutating = true)
    @Operation(summary = "A break began; collection stops",
            description = "ADR 0042's break, applied here. A courier on break is not assignable, "
                    + "so the pin had no operational use and the honest thing is to stop "
                    + "collecting rather than keep a dot nobody may act on.")
    public ResponseEntity<Void> suspend(
            @PathVariable UUID tenantId, @PathVariable UUID brandId,
            @PathVariable UUID locationId, @PathVariable UUID sessionId) {

        sessions.suspend(tenantId, sessionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/duty-sessions/{sessionId}/break-endings")
    @RequiresCapability(value = Capability.COURIER_DUTY_MANAGE, scope = ScopeType.LOCATION,
            mutating = true)
    @Operation(summary = "The break ended; collection resumes")
    public ResponseEntity<Void> resume(
            @PathVariable UUID tenantId, @PathVariable UUID brandId,
            @PathVariable UUID locationId, @PathVariable UUID sessionId) {

        sessions.resume(tenantId, sessionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/duty-sessions/{sessionId}/closures")
    @RequiresCapability(value = Capability.COURIER_DUTY_MANAGE, scope = ScopeType.LOCATION,
            mutating = true)
    @Operation(summary = "Sign off; collection stops and the pin expires within the hour",
            description = "The live row survives one more hour so a dispatcher finishing a call "
                    + "can still see where the courier was, and is then deleted by the retention "
                    + "sweeper. The track stays until its daily partition is dropped.")
    public ResponseEntity<Void> close(
            @PathVariable UUID tenantId, @PathVariable UUID brandId,
            @PathVariable UUID locationId, @PathVariable UUID sessionId,
            @Valid @RequestBody CloseRequest body) {

        sessions.close(tenantId, sessionId, body.endReason(),
                ActorRef.user(currentActor.get().subject(), null),
                body.reason(), Capability.COURIER_DUTY_MANAGE.code(), correlationId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/telemetry/observations")
    @RequiresCapability(value = Capability.COURIER_DUTY_MANAGE, scope = ScopeType.LOCATION,
            mutating = true)
    @Operation(summary = "A batch of buffered observations from one handset",
            description = "Posted every ten seconds while a duty session is open, carrying the "
                    + "readings buffered since the last batch so a lift, a basement kitchen, or a "
                    + "tunnel produces a late batch rather than a gap. A batch arriving with no "
                    + "open duty session is refused with 422 and stored nowhere — collection "
                    + "continuing after sign-off is the failure this whole feature is built to "
                    + "prevent, and it must fail loudly rather than accumulate quietly. "
                    + "Idempotent on a natural key rather than on an Idempotency-Key record, "
                    + "which is a narrow exemption ADR 0045 names here and nowhere else.")
    public ResponseEntity<IngestResponse> observations(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId,
            @Valid @RequestBody ObservationBatchRequest body) {

        IngestOutcome outcome = ingest.ingest(tenantId, body.courierId(),
                body.activeAssignmentCount() == null ? 0 : body.activeAssignmentCount(),
                body.observations().stream().map(ObservationRequest::toDomain).toList());

        return ResponseEntity.accepted().body(new IngestResponse(
                outcome.observationsAccepted(), outcome.windowsWritten(),
                outcome.livePositionMoved(), outcome.gate().name(), outcome.suspended(),
                // The device's next cadence, sent on every response so the platform
                // keeps the one lever it has over battery, data cost, and write
                // volume rather than leaving it to the handset.
                (int) LivePositionRules.BATCH_CADENCE.toSeconds()));
    }

    private static String correlationId() {
        String correlationId = MDC.get("correlationId");
        return correlationId == null ? UUID.randomUUID().toString() : correlationId;
    }

    public record OpenRequest(
            @NotNull UUID courierId,
            @NotBlank @Size(max = 128) String deviceId,
            @Size(max = 16) String collectionGate,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record CloseRequest(
            @NotBlank @Size(max = 32) String endReason,
            @NotBlank @Size(max = 500) String reason) {
    }

    public record ObservationBatchRequest(
            @NotNull UUID courierId,
            @Min(0) @Max(50) Integer activeAssignmentCount,
            @NotEmpty @Size(max = LivePositionRules.MAXIMUM_BATCH_SIZE)
            List<@Valid ObservationRequest> observations) {
    }

    /**
     * One reading.
     *
     * <p>Bounds are asserted here and again in {@link TrackObservation}, and the
     * duplication is deliberate: the record's constructor is what protects the
     * store from a caller that is not this controller, and the annotations are
     * what turn a broken handset into an ADR 0031 validation problem instead of a
     * 500.
     */
    public record ObservationRequest(
            @NotNull Instant capturedAt,
            @NotNull Double latitude,
            @NotNull Double longitude,
            @NotNull @Min(0) Double accuracyMeters,
            Double headingDegrees,
            Double speedMps,
            @Min(0) @Max(100) Integer batteryPercent,
            Boolean deviceCharging) {

        TrackObservation toDomain() {
            try {
                return new TrackObservation(capturedAt, latitude, longitude, accuracyMeters,
                        headingDegrees, speedMps, batteryPercent, deviceCharging);
            } catch (IllegalArgumentException invalid) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
            }
        }
    }

    public record DutySessionResponse(
            UUID id, UUID courierId, UUID shiftId, String status, String collectionGate,
            Instant startedAt, Instant registrationCheckedAt, String registrationValidUntil) {

        static DutySessionResponse of(DutySessionRow session) {
            return new DutySessionResponse(session.id(), session.courierId(), session.shiftId(),
                    session.status().name(), session.collectionGate().name(), session.startedAt(),
                    session.registrationCheckedAt(), session.registrationValidUntil().toString());
        }
    }

    /**
     * @param suspended true when a break was running, so the courier app's visible
     *                  on-duty indicator can say truthfully that collection has
     *                  stopped. Collection never runs invisibly, and a courier who
     *                  signs off can see that it stopped.
     */
    public record IngestResponse(
            int observationsAccepted, int windowsWritten, boolean livePositionMoved,
            String collectionGate, boolean suspended, int nextBatchAfterSeconds) {
    }
}
