package uz.horecaos.platform.courier.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.courier.api.CourierSelfAuthorized;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.telemetry.api.CourierDutyPort;
import uz.horecaos.platform.telemetry.api.CourierDutyPort.BatchOutcome;
import uz.horecaos.platform.telemetry.api.CourierDutyPort.Duty;
import uz.horecaos.platform.telemetry.api.CourierDutyPort.Reading;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * The courier's own duty session and observation batch (ADR 0045, ADR 0179).
 *
 * <p>The staff controller in {@code telemetry} ({@code CourierDutyController}) declares
 * {@code courier.duty.manage} at a location scope; a courier holds no grants, so a courier could
 * neither open a session nor post a position through it. Its own class comment said the courier's path
 * would be a second controller with its own declaration, not a widening of that one, and this is it:
 * {@link CourierSelfAuthorized}, the {@code /api/v1/courier/...} prefix and the {@code courier}
 * OpenAPI group. The courier is the caller's own token subject and never a parameter, so nothing here
 * can name somebody else ({@link CallingCourier}).
 *
 * <p>There are no break endpoints here, deliberately. A break is the shift's
 * ({@code POST .../shifts/{id}/breaks}), and the shift tells its session: starting a break suspends
 * collection and ending it resumes, and closing the shift (by the courier, a manager or the
 * sweeper) closes the session. A second place to say "I am on break" would be a second way for the
 * session and the shift to disagree.
 *
 * <p>The batch endpoint is the one narrow exemption from ADR 0031's mandatory
 * {@code Idempotency-Key} that ADR 0045 names, for the reason it gives: six posts a minute per courier
 * would add six rows a minute per courier to the idempotency table for no benefit, because the track is
 * idempotent on its own natural key. Open and close carry the key.
 *
 * <p>No response here contains a position, and no request type prints one: the observation records
 * redact their {@code toString}, so a validation failure or a stray log line cannot carry a
 * coordinate (ADR 0029).
 */
@RestController
@RequestMapping("/api/v1/courier/tenants/{tenantId}/brands/{brandId}/locations/{locationId}")
@Tag(
        name = "Courier duty and telemetry (self)",
        description = "A courier's own duty session and observations, from their own handset")
public class CourierSelfDutyController {

    private final CourierDutyPort duty;
    private final CallingCourier caller;

    public CourierSelfDutyController(CourierDutyPort duty, CallingCourier caller) {
        this.duty = duty;
        this.caller = caller;
    }

    @GetMapping("/duty-session")
    @CourierSelfAuthorized(Capability.COURIER_TELEMETRY)
    @Operation(
            operationId = "courierGetDutySession",
            summary = "My duty session at this branch, if one is open",
            description = "Answers 200 with a null session when the courier is not on duty here, so a "
                    + "handset that has just started, or just reconnected, learns whether collection "
                    + "is running without reading an error. Carries the cadence the handset must obey: "
                    + "the platform owns the lever over battery, data cost and write volume.")
    public ResponseEntity<CourierCurrentDutyResponse> current(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {

        Duty session = duty.current(tenantId, caller.idIn(tenantId), locationId).orElse(null);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(new CourierCurrentDutyResponse(session == null ? null : CourierDutySessionView.of(session)));
    }

    @PostMapping("/duty-sessions")
    @CourierSelfAuthorized(Capability.COURIER_TELEMETRY)
    @Idempotent
    @Operation(
            operationId = "courierOpenDutySession",
            summary = "Go on duty: open the window in which my position is collected",
            description = "Refused (409) unless the courier has an open shift at this branch and a valid "
                    + "self-employment registration, with a stable reason: NO_OPEN_SHIFT, "
                    + "REGISTRATION_LAPSED. Opening twice returns the session already open. The "
                    + "collection gate is not the courier's to choose; it resolves at the branch.")
    public ResponseEntity<CourierDutySessionView> open(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody CourierOpenDutyRequest body) {

        Duty session = duty.open(
                tenantId, caller.idIn(tenantId), locationId, body.deviceId(), caller.subject(), correlationId());
        return ResponseEntity.ok(CourierDutySessionView.of(session));
    }

    @PostMapping("/duty-sessions/{sessionId}/closures")
    @CourierSelfAuthorized(Capability.COURIER_TELEMETRY)
    @Idempotent
    @Operation(
            operationId = "courierCloseDutySession",
            summary = "Sign off: collection stops",
            description = "Only my own open session can be closed; any other id answers 404. Closing the "
                    + "shift closes the session too, so this is for a courier who wants collection "
                    + "to stop while the shift stays open.")
    public ResponseEntity<Void> close(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID sessionId) {

        duty.close(tenantId, caller.idIn(tenantId), sessionId, caller.subject(), correlationId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/telemetry/observations")
    @CourierSelfAuthorized(Capability.COURIER_TELEMETRY)
    @Operation(
            operationId = "courierPostObservations",
            summary = "A batch of buffered observations from my handset",
            description = "Posted every cadence seconds while a duty session is open, carrying the readings "
                    + "buffered since the last batch. A batch with no open session at this branch is "
                    + "refused with 422 (reason NO_OPEN_DUTY_SESSION) and stored nowhere. While a break is "
                    + "running the answer is 202 with suspended = true and nothing is stored. Idempotent "
                    + "on a natural key rather than on an Idempotency-Key record, the narrow exemption "
                    + "ADR 0045 names. The courier is the caller; no courier id is accepted.")
    public ResponseEntity<CourierObservationsResponse> observations(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody CourierObservationBatchRequest body) {

        BatchOutcome outcome = duty.ingest(
                tenantId,
                caller.idIn(tenantId),
                locationId,
                body.activeAssignmentCount() == null ? 0 : body.activeAssignmentCount(),
                body.observations().stream()
                        .map(CourierObservationBody::toReading)
                        .toList());

        return ResponseEntity.accepted()
                .body(new CourierObservationsResponse(
                        outcome.observationsAccepted(),
                        outcome.windowsWritten(),
                        outcome.livePositionMoved(),
                        outcome.collectionGate(),
                        outcome.suspended(),
                        outcome.nextBatchAfterSeconds()));
    }

    private static String correlationId() {
        String correlationId = MDC.get("correlationId");
        return correlationId == null ? UUID.randomUUID().toString() : correlationId;
    }

    /**
     * @param deviceId an opaque id the app keeps for this installation. It names a handset, not a
     *                 person, and is not a hardware identifier
     */
    record CourierOpenDutyRequest(@NotBlank @Size(max = 128) String deviceId) {}

    /**
     * One handset's batch. No courier id: the caller is the courier.
     *
     * <p>{@code activeAssignmentCount} is the handset's own count of deliveries it carries, bounded
     * like the staff endpoint's; it feeds the {@code ON_ASSIGNMENT} gate and the badge on the
     * dispatcher's pin and decides nothing about pay. Boxed: Jackson 3 refuses a missing primitive.
     */
    record CourierObservationBatchRequest(
            @Nullable @Min(0) @Max(50) Integer activeAssignmentCount,
            @NotEmpty @Size(max = 60) List<@Valid CourierObservationBody> observations) {

        @Override
        public String toString() {
            return "CourierObservationBatchRequest[REDACTED]";
        }
    }

    /**
     * One reading. Every number is boxed because Jackson 3 refuses a missing primitive, and the
     * bounds are asserted here and again by the platform's own record, so a broken handset becomes
     * a validation problem and never a 500.
     */
    record CourierObservationBody(
            @NotNull Instant capturedAt,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @NotNull @DecimalMin("0") Double accuracyMeters,
            @Nullable Double headingDegrees,
            @Nullable Double speedMps,
            @Nullable @Min(0) @Max(100) Integer batteryPercent,
            @Nullable Boolean deviceCharging) {

        Reading toReading() {
            return new Reading(
                    capturedAt,
                    latitude,
                    longitude,
                    accuracyMeters,
                    headingDegrees,
                    speedMps,
                    batteryPercent,
                    deviceCharging);
        }

        @Override
        public String toString() {
            // A named courier's position prints as nothing (ADR 0029).
            return "CourierObservationBody[REDACTED]";
        }
    }

    record CourierCurrentDutyResponse(@Nullable CourierDutySessionView session) {}

    /**
     * A session as its courier sees it. No courier id, no position, no registration number.
     *
     * @param batchCadenceSeconds how often the handset posts; the platform's, not the device's
     * @param maximumBatchSize    the most observations one post may carry
     */
    record CourierDutySessionView(
            UUID sessionId,
            UUID shiftId,
            UUID locationId,
            String status,
            String collectionGate,
            Instant startedAt,
            String registrationValidUntil,
            int batchCadenceSeconds,
            int maximumBatchSize) {

        static CourierDutySessionView of(Duty session) {
            return new CourierDutySessionView(
                    session.sessionId(),
                    session.shiftId(),
                    session.locationId(),
                    session.status(),
                    session.collectionGate(),
                    session.startedAt(),
                    session.registrationValidUntil().toString(),
                    session.batchCadenceSeconds(),
                    session.maximumBatchSize());
        }
    }

    /**
     * What became of one batch.
     *
     * @param suspended true when a break was running, so the app's on-duty indicator can say truthfully
     *                  that collection has stopped
     */
    record CourierObservationsResponse(
            int observationsAccepted,
            int windowsWritten,
            boolean livePositionMoved,
            String collectionGate,
            boolean suspended,
            int nextBatchAfterSeconds) {}
}
