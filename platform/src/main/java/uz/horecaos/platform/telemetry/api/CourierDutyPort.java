package uz.horecaos.platform.telemetry.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A courier's own duty session and observations, as {@code courier}'s self surface asks for them
 * (ADR 0045, ADR 0179).
 *
 * <p>ADR 0045 said the courier's own path would be a second controller with its own declaration, not
 * a widening of the staff one. That controller lives in {@code courier}, not here, for a reason of
 * dependency direction: the declaration it carries ({@code CourierSelfAuthorized}) is {@code courier}'s,
 * {@code courier} already depends on this module through {@link CourierShiftPort}, and a controller
 * in {@code telemetry} importing the annotation would close a cycle between the two modules. So the
 * controller is {@code courier}'s and this is what it calls.
 *
 * <p><strong>The caller resolves the courier.</strong> Every method takes the id of the courier the
 * <em>caller's own token</em> names, and trusts it: the only caller is a controller that found that
 * id from the signed subject (never from a path or a body), which is the whole of what makes "a
 * courier cannot send somebody else's position" true. Nothing here is reachable from a request that
 * names a courier.
 *
 * <p><strong>A reading crosses this boundary inward, once, and never comes back out.</strong> The
 * earlier statement of this package -- that neither direction carries a coordinate -- is why
 * {@link Reading} is here and not in a shared type: it is the courier's own handset's report of its
 * own position, handed to the module that owns every rule about it (the open session, the gate, the
 * cadence, the staleness, the encrypted track), and no method on this interface returns one. A
 * position is read back only through capability-gated HTTP at a location scope.
 */
public interface CourierDutyPort {

    /** The courier's open session if it is at this branch; a courier working elsewhere has none here. */
    Optional<Duty> current(UUID tenantId, UUID courierId, UUID locationId);

    /**
     * Goes on duty, or returns the session already open.
     *
     * <p>Refused with a stable {@code reason} (an {@code ApiException}) unless the courier has an open
     * shift at this branch ({@code NO_OPEN_SHIFT}) and a valid registration
     * ({@code REGISTRATION_LAPSED}). The collection gate is not the caller's to choose: it resolves at
     * the branch (ADR 0030).
     *
     * @param deviceId an opaque id for this installation; it names a handset, not a person
     * @param subject  the caller's token subject, recorded as who opened it
     */
    Duty open(UUID tenantId, UUID courierId, UUID locationId, String deviceId, String subject, String correlationId);

    /** Signs off the courier's own open session. Any other id is one that does not exist. */
    void close(UUID tenantId, UUID courierId, UUID sessionId, String subject, String correlationId);

    /**
     * Ingests one handset's batch.
     *
     * <p>Refused with {@code 422} and reason {@code NO_OPEN_DUTY_SESSION} when there is no open session
     * at this branch, and stored nowhere. Answers {@code suspended} while a break runs.
     */
    BatchOutcome ingest(
            UUID tenantId, UUID courierId, UUID locationId, int activeAssignmentCount, List<Reading> readings);

    /**
     * A session as its courier sees it. No courier id, no position, no registration number.
     *
     * @param batchCadenceSeconds how often the handset posts: the platform's lever, not the device's
     * @param maximumBatchSize    the most readings one post may carry
     */
    record Duty(
            UUID sessionId,
            UUID shiftId,
            UUID locationId,
            String status,
            String collectionGate,
            Instant startedAt,
            LocalDate registrationValidUntil,
            int batchCadenceSeconds,
            int maximumBatchSize) {}

    /**
     * One reading, as the handset reported it. Bounds are checked on the way in (an impossible
     * coordinate is a validation failure, not a stored value).
     */
    record Reading(
            Instant capturedAt,
            double latitude,
            double longitude,
            double accuracyMeters,
            @Nullable Double headingDegrees,
            @Nullable Double speedMps,
            @Nullable Integer batteryPercent,
            @Nullable Boolean deviceCharging) {

        @Override
        public String toString() {
            // A named courier's position prints as nothing (ADR 0029).
            return "Reading[REDACTED]";
        }
    }

    /** What became of one batch. */
    record BatchOutcome(
            int observationsAccepted,
            int windowsWritten,
            boolean livePositionMoved,
            String collectionGate,
            boolean suspended,
            int nextBatchAfterSeconds) {}
}
