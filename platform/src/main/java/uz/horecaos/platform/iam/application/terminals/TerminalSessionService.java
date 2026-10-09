package uz.horecaos.platform.iam.application.terminals;

import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.DevicePrincipalView;
import uz.horecaos.platform.iam.api.devices.DeviceRevoked;
import uz.horecaos.platform.iam.api.staff.StaffMemberChanged;
import uz.horecaos.platform.iam.api.terminals.TerminalAttribution;
import uz.horecaos.platform.iam.api.terminals.TerminalFact;
import uz.horecaos.platform.iam.api.terminals.TerminalPolicy.AttributionMode;
import uz.horecaos.platform.iam.api.terminals.TerminalSessionGate;
import uz.horecaos.platform.iam.application.staff.StaffTerminalFacts;
import uz.horecaos.platform.iam.application.staff.StaffTerminalFacts.Person;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcTerminalStore;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcTerminalStore.SessionRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The terminal session: a row that records who is unlocked at which device, and the gate that turns it into an attribution
 * (ADR 0155, Decision 5).
 *
 * <p>A session is not a token that authorizes. It is accepted only together with the device's own Keycloak bearer, only
 * from the device it was issued to, only at that device's location, and it grants nothing the device and the person do not
 * both hold already. The gate runs after ADR 0025's checks for the device and asks one more question of the same {@code
 * AuthorizationService}: may the <em>person</em> do this, here? The answer is an intersection, so a revoked cook's PIN is
 * not a way back in and a dishwasher cannot press «ready» under a cook's name.
 *
 * <p>Sessions are read from the table, never from a cache: a lock, a revocation and an employment end must take effect on the
 * next request. The idle and maximum limits are checked lazily at that same read, and the row records which one ended it.
 */
@Service
public class TerminalSessionService implements TerminalSessionGate {

    /** A mutating request extends a session only when its last activity is older than this (ADR 0155). */
    static final Duration ACTIVITY_GRANULARITY = Duration.ofSeconds(15);

    /** How many unlocks «Мой профиль» lists. */
    public static final int OWN_LIST_SIZE = 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTerminalStore store;
    private final DeviceDirectory devices;
    private final DeviceSeenRecorder seen;
    private final StaffTerminalFacts staff;
    private final AuthorizationService authorization;
    private final ApplicationEventPublisher events;
    private final MeterRegistry meters;
    private final Clock clock;

    public TerminalSessionService(
            JdbcTerminalStore store,
            DeviceDirectory devices,
            DeviceSeenRecorder seen,
            StaffTerminalFacts staff,
            AuthorizationService authorization,
            ApplicationEventPublisher events,
            MeterRegistry meters,
            Clock clock) {
        this.store = store;
        this.devices = devices;
        this.seen = seen;
        this.staff = staff;
        this.authorization = authorization;
        this.events = events;
        this.meters = meters;
        this.clock = clock;
    }

    // ----------------------------------------------------------------------------------------- the gate

    @Override
    public void observe(String principalSubject, @Nullable String build) {
        devices.activeDeviceOf(principalSubject).ifPresent(device -> seen.stamp(device.id(), build));
    }

    @Override
    public Optional<TerminalAttribution> admit(Admission request) {
        Optional<DevicePrincipalView> found = devices.activeDeviceOf(request.principalSubject());
        if (found.isEmpty()) {
            if (request.sessionToken() != null) {
                // A session belongs to the device it was issued to. A request carrying one without that device's own
                // bearer -- a staff token, a partner, a customer -- is refused before anything else runs.
                throw new ApiException(
                        ErrorCode.TERMINAL_SESSION_ENDED,
                        "A terminal session is accepted only from the device it was issued to");
            }
            return Optional.empty();
        }
        DevicePrincipalView device = found.get();
        seen.stamp(device.id(), request.build());

        if (request.sessionEndpoint() || !device.deviceClass().unlockable()) {
            return Optional.empty();
        }
        AttributionMode mode = AttributionMode.valueOf(device.attributionMode());
        if (mode == AttributionMode.DEVICE_ONLY) {
            return Optional.empty();
        }
        String token = request.sessionToken();
        if (token == null || token.isBlank()) {
            if (mode == AttributionMode.PERSON_REQUIRED && request.mutating()) {
                throw unlockRequired();
            }
            return Optional.empty();
        }

        Instant now = clock.instant();
        SessionRow session = liveSession(token, device, request, now, mode);
        Person person = staff.person(session.tenantId(), session.memberId())
                .filter(Person::active)
                .orElseThrow(() -> {
                    store.endSession(session.id(), "EMPLOYMENT_ENDED", now);
                    return deadSession(request, mode);
                });

        if (!authorization.has(person.subject(), request.capability(), request.scope())) {
            throw new ApiException(
                    ErrorCode.TERMINAL_PERSON_NOT_AUTHORIZED,
                    "The person unlocked at this terminal does not hold %s here"
                            .formatted(request.capability().code()),
                    Map.of("capability", request.capability().code()));
        }
        if (request.mutating()
                && Duration.between(session.lastActivityAt(), now).compareTo(ACTIVITY_GRANULARITY) > 0) {
            store.touchSession(session.id(), now);
        }
        return Optional.of(new TerminalAttribution(session.id(), device.id(), person.memberId(), person.subject()));
    }

    /**
     * The session the token names, if it is live for this device now: not ended, not past its maximum, not idle-expired,
     * issued to this device and at this location. An idle or over-age session is ended here, by the lazy check, and the row
     * says which.
     */
    private SessionRow liveSession(
            String token, DevicePrincipalView device, Admission request, Instant now, AttributionMode mode) {
        SessionRow row = store.findSessionByToken(PinDigests.tokenDigest(token)).orElse(null);
        if (row == null || !row.deviceId().equals(device.id()) || row.endedAt() != null) {
            throw deadSession(request, mode);
        }
        UUID scopeLocation = request.scope().locationId();
        if (scopeLocation != null && !scopeLocation.equals(row.locationId())) {
            throw deadSession(request, mode);
        }
        if (!now.isBefore(row.hardExpiresAt())) {
            store.endSession(row.id(), "MAX_AGE", now);
            throw deadSession(request, mode);
        }
        if (!now.isBefore(row.lastActivityAt().plusSeconds(row.idleSeconds()))) {
            store.endSession(row.id(), "IDLE", now);
            throw deadSession(request, mode);
        }
        return row;
    }

    private static ApiException deadSession(Admission request, AttributionMode mode) {
        if (mode == AttributionMode.PERSON_REQUIRED && request.mutating()) {
            return unlockRequired();
        }
        return new ApiException(ErrorCode.TERMINAL_SESSION_ENDED, "This terminal is locked");
    }

    private static ApiException unlockRequired() {
        return new ApiException(ErrorCode.TERMINAL_UNLOCK_REQUIRED, "Someone must unlock this terminal first");
    }

    // ---------------------------------------------------------------------------------- the keypad's calls

    /** What the device is handed once, on unlock. */
    public record OpenedSession(
            UUID sessionId, String token, UUID memberId, Instant openedAt, int idleSeconds, Instant hardExpiresAt) {

        @Override
        public String toString() {
            return "OpenedSession[" + sessionId + ", token=<redacted>]";
        }
    }

    /**
     * Opens a session for a person at a device, in the caller's transaction: ends the person's live session elsewhere and
     * the device's live session with reason {@code SUPERSEDED}, so the live session is where the person is. The token is
     * returned once and kept nowhere; only its SHA-256 is stored.
     */
    @Transactional
    public OpenedSession open(
            DevicePrincipalView device, Person person, int idleSeconds, int maxHours, String correlationId) {
        Instant now = clock.instant();
        store.lockDevice(device.tenantId(), device.id());
        for (SessionRow previous : store.liveSessionsOf(device.tenantId(), person.memberId())) {
            endAndRecord(previous, "SUPERSEDED", now, correlationId, person.subject());
        }
        for (SessionRow previous : store.liveSessionsOfDevice(device.tenantId(), device.id())) {
            endAndRecord(previous, "SUPERSEDED", now, correlationId, person.subject());
        }
        byte[] material = new byte[32];
        RANDOM.nextBytes(material);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        UUID id = Ids.newId();
        Instant hardExpires = now.plus(Duration.ofHours(maxHours));
        store.insertSession(
                id,
                device.tenantId(),
                device.id(),
                person.memberId(),
                device.locationId(),
                PinDigests.tokenDigest(token),
                now,
                idleSeconds,
                hardExpires);
        meters.counter(
                        "horecaos.terminal.sessions.opened",
                        "class",
                        device.deviceClass().name())
                .increment();
        events.publishEvent(new TerminalFact(
                "terminal.session.opened",
                device.tenantId(),
                device.locationId(),
                device.brandId(),
                person.subject(),
                "iam.terminal_session",
                id,
                device.id(),
                "Unlocked " + device.displayName(),
                null,
                "terminal:" + id,
                Map.of(),
                facts("deviceClass", device.deviceClass().name(), "idleSeconds", idleSeconds, "maxHours", maxHours),
                correlationId,
                now));
        return new OpenedSession(id, token, person.memberId(), now, idleSeconds, hardExpires);
    }

    /** The live session this token names for this device, or the refusal the shell turns into the lock screen. */
    public SessionRow requireLive(String token, DevicePrincipalView device) {
        Instant now = clock.instant();
        SessionRow row = store.findSessionByToken(PinDigests.tokenDigest(token)).orElse(null);
        if (row == null
                || !row.deviceId().equals(device.id())
                || row.endedAt() != null
                || !now.isBefore(row.hardExpiresAt())
                || !now.isBefore(row.lastActivityAt().plusSeconds(row.idleSeconds()))) {
            if (row != null && row.endedAt() == null && row.deviceId().equals(device.id())) {
                boolean overAge = !now.isBefore(row.hardExpiresAt());
                store.endSession(row.id(), overAge ? "MAX_AGE" : "IDLE", now);
            }
            throw new ApiException(ErrorCode.TERMINAL_SESSION_ENDED, "This terminal is locked");
        }
        return row;
    }

    /** The keypad's «touch»: a tap that is not itself a mutating call keeps the terminal unlocked. */
    public void touch(String token, DevicePrincipalView device) {
        SessionRow row = requireLive(token, device);
        store.touchSession(row.id(), clock.instant());
    }

    /** The shell's own lock, or the person walking away: ends this device's session. Idempotent. */
    @Transactional
    public boolean lock(@Nullable String token, DevicePrincipalView device, String correlationId) {
        if (token == null || token.isBlank()) {
            return false;
        }
        SessionRow row = store.findSessionByToken(PinDigests.tokenDigest(token)).orElse(null);
        if (row == null || !row.deviceId().equals(device.id()) || row.endedAt() != null) {
            return false;
        }
        endAndRecord(row, "LOCKED", clock.instant(), correlationId, null);
        return true;
    }

    // ------------------------------------------------------------------------------- ending, by cause

    /** A device was revoked (or set back to DEVICE_ONLY): every session at it ends. */
    @Transactional
    public int endDeviceSessions(UUID tenantId, UUID deviceId, String reason, String correlationId) {
        int ended = 0;
        for (SessionRow row : store.liveSessionsOfDevice(tenantId, deviceId)) {
            if (endAndRecord(row, reason, clock.instant(), correlationId, null)) {
                ended++;
            }
        }
        return ended;
    }

    /** A person's PIN was reset, their employment ended or their credential locked: every session of theirs ends. */
    @Transactional
    public int endMemberSessions(UUID tenantId, UUID memberId, String reason, String correlationId) {
        int ended = 0;
        for (SessionRow row : store.liveSessionsOf(tenantId, memberId)) {
            if (endAndRecord(row, reason, clock.instant(), correlationId, null)) {
                ended++;
            }
        }
        return ended;
    }

    private boolean endAndRecord(
            SessionRow row, String reason, Instant now, String correlationId, @Nullable String actorSubject) {
        if (!store.endSession(row.id(), reason, now)) {
            return false;
        }
        events.publishEvent(new TerminalFact(
                "terminal.session.ended",
                row.tenantId(),
                row.locationId(),
                null,
                actorSubject,
                "iam.terminal_session",
                row.id(),
                row.deviceId(),
                "Terminal session ended: " + reason,
                null,
                "terminal:" + row.id(),
                Map.of("ended", false),
                facts("ended", true, "reason", reason),
                correlationId,
                now));
        return true;
    }

    /**
     * A device was revoked: its sessions end in the same commit, so the next request is refused twice over -- by the grant that
     * is gone and by the session that is. A plain listener, called synchronously inside the revoking transaction: a
     * transactional one would run too late to record the fact it raises.
     */
    @EventListener
    void onDeviceRevoked(DeviceRevoked revoked) {
        endDeviceSessions(
                revoked.tenantId(), revoked.deviceId(), "REVOKED", Ids.newId().toString());
    }

    /** The action code {@code StaffMemberService} publishes when it ends a person's employment. */
    static final String EMPLOYMENT_ENDED = "staff.member.employment_ended";

    /**
     * Ending a person's employment ends their terminal sessions in the same transaction (ADR 0155). A PIN reset or a lock
     * does the same through {@link PinService}; a password reset (ADR 0098) deliberately does not -- a PIN is not the password,
     * and a forgotten password is not a compromised PIN.
     */
    @EventListener
    void onStaffMemberChanged(StaffMemberChanged change) {
        if (EMPLOYMENT_ENDED.equals(change.actionCode())) {
            endMemberSessions(change.tenantId(), change.memberId(), "EMPLOYMENT_ENDED", change.correlationId());
        }
    }

    // ----------------------------------------------------------------------------------------- reads

    public List<JdbcTerminalStore.SessionHistoryRow> recentSessionsOf(UUID tenantId, UUID memberId) {
        return store.recentSessionsOf(tenantId, memberId, OWN_LIST_SIZE);
    }

    private static Map<String, Object> facts(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
