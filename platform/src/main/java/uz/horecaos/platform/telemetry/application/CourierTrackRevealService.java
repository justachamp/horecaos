package uz.horecaos.platform.telemetry.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore;
import uz.horecaos.platform.telemetry.infrastructure.persistence.JdbcTelemetryStore.TrackWindowRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Opening one named courier's stored track (ADR 0045, ADR 0029, ADR 0027).
 *
 * <p>This is the class the whole privacy analysis converges on, and it is
 * deliberately small enough that a reviewer can read all of it.
 *
 * <p>Four things are true of every call. The caller holds
 * {@code courier.track.reveal}, which is in no default role bundle and not in
 * {@code platform.admin} either, so somebody granted it to a named person on
 * purpose. The caller states a purpose, and a blank one is refused rather than
 * defaulted. The window is bounded and capped, so "this courier's track" is never
 * a request anybody can make. And the ADR 0027 audit entry is written in the same
 * transaction as the decryption, naming the actor, the courier, the window, and
 * the reason — which means an action that succeeded without a record is
 * impossible rather than merely discouraged.
 *
 * <p>The live map does none of this and is not audited per refresh. The asymmetry
 * is the decision: a five-second map produces more audit rows than the tenant has
 * orders, and it would bury exactly the records this class writes.
 */
@Service
public class CourierTrackRevealService {

    /**
     * The widest window one reveal may ask for.
     *
     * <p>Equal to the retention window, so a reveal can legitimately cover
     * everything that still exists — and no more, because a request with no upper
     * bound is a request for a movement history rather than for evidence about an
     * incident.
     */
    public static final Duration MAXIMUM_REVEAL_WINDOW = Duration.ofDays(31);

    private static final int MINIMUM_PURPOSE_LENGTH = 12;

    private final JdbcTelemetryStore store;
    private final FieldProtection protection;
    private final AuditRecorder audit;
    private final ObjectMapper json;
    private final Clock clock;

    public CourierTrackRevealService(
            JdbcTelemetryStore store, FieldProtection protection, AuditRecorder audit, ObjectMapper json, Clock clock) {
        this.store = store;
        this.protection = protection;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public Reveal reveal(RevealCommand command) {
        String purpose = command.purpose() == null ? "" : command.purpose().strip();
        if (purpose.length() < MINIMUM_PURPOSE_LENGTH) {
            // "investigation" is not a purpose and neither is "check". The audit
            // entry is only worth writing if the sentence in it answers, months
            // later, why somebody looked.
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A track reveal states why, in a sentence somebody can be held to. "
                            + "At least %d characters (ADR 0029).".formatted(MINIMUM_PURPOSE_LENGTH),
                    Map.of("field", "purpose"));
        }
        if (!command.to().isAfter(command.from())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The window ends after it starts");
        }
        if (Duration.between(command.from(), command.to()).compareTo(MAXIMUM_REVEAL_WINDOW) > 0) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A reveal covers at most %d days".formatted(MAXIMUM_REVEAL_WINDOW.toDays()),
                    Map.of("field", "window"));
        }

        List<TrackWindowRow> windows =
                store.trackWindows(command.tenantId(), command.courierId(), command.from(), command.to());

        Instant now = clock.instant();

        // Written before the plaintext exists in this method, so a decryption
        // failure cannot leave a reveal that happened with no record of it. ADR
        // 0027 puts the audit write in the same transaction for this reason; the
        // ordering inside the transaction is what makes it true of a crash too.
        audit.record(AuditFact.of("telemetry.courier_track.revealed", AuditClass.SECURITY)
                .by(command.actor())
                .at(ResourceScope.location(command.tenantId(), command.brandId(), command.locationId()))
                .target("CourierTrack", command.courierId())
                .because(purpose)
                .usingCapability("courier.track.reveal")
                .changed(Map.of(
                        "courierId", command.courierId().toString(),
                        "windowFrom", command.from().toString(),
                        "windowTo", command.to().toString(),
                        "windowsRevealed", windows.size()))
                .correlatedBy(command.correlationId())
                .occurredAt(now)
                .build());

        List<RevealedWindow> revealed = windows.stream()
                .map(window -> new RevealedWindow(
                        window.windowStart(),
                        window.windowEnd(),
                        window.observationCount(),
                        window.distanceMeters(),
                        decrypt(command.tenantId(), window, purpose)))
                .toList();

        return new Reveal(command.courierId(), command.from(), command.to(), purpose, revealed);
    }

    private List<Map<String, Object>> decrypt(UUID tenantId, TrackWindowRow window, String purpose) {
        String plaintext = protection.reveal(
                tenantId,
                ProtectedValue.deserialize(window.protectedTrack()),
                new FieldProtection.RecordRef("fulfillment.courier_location_tracks", "protected_track", window.id()),
                purpose);

        return json.readValue(plaintext, new TypeReference<List<Map<String, Object>>>() {});
    }

    /**
     * @param brandId  and {@code locationId} name the scope the capability was
     *                 checked at, so the audit entry records where the authority
     *                 came from and not only who used it
     * @param purpose  free text, mandatory, and long enough to be a sentence
     */
    public record RevealCommand(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID courierId,
            Instant from,
            Instant to,
            String purpose,
            ActorRef actor,
            String correlationId) {}

    public record RevealedWindow(
            Instant windowStart,
            Instant windowEnd,
            int observationCount,
            int distanceMeters,
            List<Map<String, Object>> observations) {}

    public record Reveal(UUID courierId, Instant from, Instant to, String purpose, List<RevealedWindow> windows) {}
}
