package uz.horecaos.platform.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.DevicePrincipalView;
import uz.horecaos.platform.iam.api.terminals.TerminalPolicy;
import uz.horecaos.platform.iam.api.terminals.TerminalPolicy.AttributionMode;
import uz.horecaos.platform.iam.api.terminals.TerminalPolicySource;
import uz.horecaos.platform.iam.api.terminals.TerminalSessionGate;
import uz.horecaos.platform.iam.application.terminals.DeviceDirectory;
import uz.horecaos.platform.iam.application.terminals.PinService;
import uz.horecaos.platform.iam.application.terminals.PinService.Activated;
import uz.horecaos.platform.iam.application.terminals.PinService.Changed;
import uz.horecaos.platform.iam.application.terminals.PinService.Unlocked;
import uz.horecaos.platform.iam.application.terminals.TerminalEligibility;
import uz.horecaos.platform.iam.application.terminals.TerminalSessionService;
import uz.horecaos.platform.iam.application.terminals.TerminalSessionService.OpenedSession;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcTerminalStore.SessionRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * What a terminal does to unlock itself for a person (ADR 0155): read its roster, open a session with a PIN or an activation
 * code, keep it alive with a touch, change the PIN, and lock.
 *
 * <p><strong>The PIN attributes; it never authenticates.</strong> Every endpoint here is called by an enrolled device with
 * the device's own Keycloak bearer, authorised by {@code terminal.unlock} (held by the touch kitchen display and by no
 * person's bundle) and then checked again against the device row: the caller must be an active device of a class a person
 * unlocks, at this very location. The unlock and activation responses carry a session token, so they are not {@code
 * @Idempotent} -- a stored response would put a bearer into {@code platform.idempotency_records} for the retention day --
 * and are listed by exact path in {@code EndpointCapabilityDeclarationTests} with that reason. A PIN is never accepted by any
 * endpoint outside this family, and a session token is never accepted as a bearer.
 *
 * <p>The request records print their secrets as {@code <redacted>}: an exception message or a log line that formats one
 * must not be a way to read a PIN.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/terminal-session")
@Tag(
        name = "Terminal session",
        description = "ADR 0155: a staff PIN names the person at an already-authenticated device")
public class TerminalSessionController {

    private final DeviceDirectory devices;
    private final PinService pins;
    private final TerminalSessionService sessions;
    private final TerminalEligibility eligibility;
    private final TerminalPolicySource policies;
    private final CurrentActor currentActor;

    public TerminalSessionController(
            DeviceDirectory devices,
            PinService pins,
            TerminalSessionService sessions,
            TerminalEligibility eligibility,
            TerminalPolicySource policies,
            CurrentActor currentActor) {
        this.devices = devices;
        this.pins = pins;
        this.sessions = sessions;
        this.eligibility = eligibility;
        this.policies = policies;
        this.currentActor = currentActor;
    }

    @GetMapping("/roster")
    @RequiresCapability(value = Capability.TERMINAL_UNLOCK, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Who may unlock this terminal",
            description = "The people eligible at this terminal's location -- an active staff member holding every "
                    + "capability the class names for attribution -- as the labels the tenant chose (first name and "
                    + "the initial of the last, or the non-personal reference), each with whether they have a PIN. "
                    + "Never a phone, photo, employee number or subject. Empty while the device is DEVICE_ONLY. "
                    + "Cache-Control: no-store.")
    public ResponseEntity<RosterResponse> roster(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {

        DevicePrincipalView device = requireUnlockableDevice(tenantId, brandId, locationId);
        TerminalPolicy policy = policies.of(tenantId);
        AttributionMode mode = AttributionMode.valueOf(device.attributionMode());
        List<RosterMember> members = List.of();
        if (mode != AttributionMode.DEVICE_ONLY) {
            List<TerminalEligibility.Entry> entries = eligibility.roster(device, policy.rosterDisplay());
            Map<UUID, String> states = pins.pinStates(
                    tenantId,
                    entries.stream().map(TerminalEligibility.Entry::memberId).toList());
            members = entries.stream()
                    .map(entry -> new RosterMember(entry.memberId(), entry.label(), states.get(entry.memberId())))
                    .toList();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new RosterResponse(
                        mode.name(),
                        device.autoLockSeconds() != null ? device.autoLockSeconds() : policy.autoLockSeconds(),
                        policy.sessionMaxHours(),
                        policy.pinLength(),
                        members));
    }

    @PostMapping("/unlocks")
    @RequiresCapability(value = Capability.TERMINAL_UNLOCK, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Unlock the terminal as a person",
            description =
                    "Opens the one live session of this device for the person who tapped their name and typed a PIN, "
                            + "ending the person's session elsewhere and the device's previous one. The token is returned once and "
                            + "is accepted only with this device's own bearer. A wrong PIN, an unknown member, an ineligible person "
                            + "and a person with no PIN all answer TERMINAL_UNLOCK_REFUSED alike; only a lock is distinguished "
                            + "(TERMINAL_PIN_LOCKED, with retryAfterSeconds when it will lift). No Idempotency-Key: the response "
                            + "carries a bearer.")
    public ResponseEntity<TerminalSessionResponse> unlock(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody UnlockRequest body) {

        DevicePrincipalView device = requireUnlockableDevice(tenantId, brandId, locationId);
        Unlocked outcome = pins.unlock(device, body.memberId(), body.pin());
        return switch (outcome.outcome()) {
            case SUCCEEDED -> opened(device, java.util.Objects.requireNonNull(outcome.opened()));
            case LOCKED -> throw pinLocked(outcome.retryAfterSeconds());
            case WRONG_PIN, NOT_ELIGIBLE -> throw unlockRefused();
        };
    }

    @PostMapping("/pin-activations")
    @RequiresCapability(value = Capability.TERMINAL_UNLOCK, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Set a PIN with a single-use activation code",
            description =
                    "A person with no PIN, or a locked one, types the code they were given and a new PIN. The code is "
                            + "single-use, expires, and is burned at the fifth wrong try; the PIN is refused with the reason stated "
                            + "when it is a run, a repeated digit, a common value or the last digits of the person's own phone or "
                            + "employee number (the code stays good). Success opens a session, as an unlock does.")
    public ResponseEntity<TerminalSessionResponse> activate(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody ActivationRequest body) {

        DevicePrincipalView device = requireUnlockableDevice(tenantId, brandId, locationId);
        Activated outcome = pins.activate(device, body.memberId(), body.activationCode(), body.newPin());
        if (outcome.opened() != null) {
            return opened(device, outcome.opened());
        }
        if (outcome.refusal() == PinService.ActivationRefusal.PIN_RULE) {
            throw new ApiException(
                    ErrorCode.TERMINAL_ACTIVATION_REFUSED,
                    "That PIN is not allowed",
                    Map.of(
                            "reason",
                            java.util.Objects.requireNonNull(outcome.rule()).name()));
        }
        throw new ApiException(ErrorCode.TERMINAL_ACTIVATION_REFUSED, "That activation code was not accepted");
    }

    @PostMapping("/pin-changes")
    @RequiresCapability(value = Capability.TERMINAL_UNLOCK, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Change the PIN of the person unlocked here",
            description =
                    "Needs this device's live session (X-Terminal-Session) and the current PIN, which is checked under "
                            + "the same lockout as an unlock. The new PIN is refused with the reason stated under the same rules as "
                            + "an activation.")
    public ResponseEntity<Void> changePin(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody PinChangeRequest body,
            HttpServletRequest request) {

        DevicePrincipalView device = requireUnlockableDevice(tenantId, brandId, locationId);
        SessionRow session = sessions.requireLive(sessionToken(request), device);
        Changed outcome = pins.change(device, session, body.currentPin(), body.newPin());
        if (outcome.rule() != null) {
            throw new ApiException(
                    ErrorCode.TERMINAL_ACTIVATION_REFUSED,
                    "That PIN is not allowed",
                    Map.of("reason", outcome.rule().name()));
        }
        if (outcome.refusal() != null) {
            if (outcome.refusal() == PinService.Outcome.LOCKED) {
                throw pinLocked(outcome.retryAfterSeconds());
            }
            throw unlockRefused();
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/touch")
    @RequiresCapability(value = Capability.TERMINAL_UNLOCK, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Keep this terminal unlocked",
            description =
                    "The keypad's tap that is not itself a mutating call (opening a ticket, scrolling the board) moves "
                            + "the idle deadline. 204, no body. No Idempotency-Key: its only effect is a timestamp moved forward, so "
                            + "a repeat is identical. The board's timed refresh never calls this.")
    public ResponseEntity<Void> touch(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            HttpServletRequest request) {

        DevicePrincipalView device = requireUnlockableDevice(tenantId, brandId, locationId);
        sessions.touch(sessionToken(request), device);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/locks")
    @RequiresCapability(value = Capability.TERMINAL_UNLOCK, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Lock this terminal",
            description = "Ends this device's session. Idempotent: locking a terminal that is already locked is 204.")
    public ResponseEntity<Void> lock(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            HttpServletRequest request) {

        DevicePrincipalView device = requireUnlockableDevice(tenantId, brandId, locationId);
        sessions.lock(
                request.getHeader(TerminalSessionGate.SESSION_HEADER),
                device,
                UUID.randomUUID().toString());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------------------------ helpers

    /**
     * The caller must be an active device of a class a person unlocks, and the very device whose location the path names.
     * {@code terminal.unlock} is held by no person's bundle, but the superuser holds every capability, and a staff token must
     * not be able to open a session on a device it is not.
     */
    private DevicePrincipalView requireUnlockableDevice(UUID tenantId, UUID brandId, UUID locationId) {
        return devices.activeDeviceOf(currentActor.get().subject())
                .filter(device -> device.deviceClass().unlockable()
                        && device.tenantId().equals(tenantId)
                        && device.brandId().equals(brandId)
                        && device.locationId().equals(locationId))
                .orElseThrow(() -> ApiException.insufficientCapability(Capability.TERMINAL_UNLOCK.code(), "LOCATION"));
    }

    private static String sessionToken(HttpServletRequest request) {
        String token = request.getHeader(TerminalSessionGate.SESSION_HEADER);
        if (token == null || token.isBlank()) {
            throw new ApiException(ErrorCode.TERMINAL_SESSION_ENDED, "This terminal is locked");
        }
        return token;
    }

    private ResponseEntity<TerminalSessionResponse> opened(DevicePrincipalView device, OpenedSession opened) {
        TerminalPolicy policy = policies.of(device.tenantId());
        String label = eligibility.roster(device, policy.rosterDisplay()).stream()
                .filter(entry -> entry.memberId().equals(opened.memberId()))
                .map(TerminalEligibility.Entry::label)
                .findFirst()
                .orElse("");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new TerminalSessionResponse(
                        opened.token(),
                        new RosterMember(opened.memberId(), label, "ACTIVE"),
                        opened.idleSeconds(),
                        opened.hardExpiresAt()));
    }

    private static ApiException unlockRefused() {
        return new ApiException(ErrorCode.TERMINAL_UNLOCK_REFUSED, "That PIN was not accepted");
    }

    private static ApiException pinLocked(@Nullable Long retryAfterSeconds) {
        return retryAfterSeconds == null
                ? new ApiException(ErrorCode.TERMINAL_PIN_LOCKED, "This PIN is locked until a manager resets it")
                : new ApiException(
                        ErrorCode.TERMINAL_PIN_LOCKED,
                        "Too many wrong PINs on this terminal",
                        Map.of("retryAfterSeconds", retryAfterSeconds));
    }

    // ------------------------------------------------------------------------------------ bodies

    record UnlockRequest(
            @NotNull UUID memberId,
            @NotBlank @Size(max = 12) String pin) {

        @Override
        public String toString() {
            return "UnlockRequest[memberId=" + memberId + ", pin=<redacted>]";
        }
    }

    record ActivationRequest(
            @NotNull UUID memberId,
            @NotBlank @Size(max = 16) String activationCode,
            @NotBlank @Size(max = 12) String newPin) {

        @Override
        public String toString() {
            return "ActivationRequest[memberId=" + memberId + ", activationCode=<redacted>, newPin=<redacted>]";
        }
    }

    record PinChangeRequest(
            @NotBlank @Size(max = 12) String currentPin,
            @NotBlank @Size(max = 12) String newPin) {

        @Override
        public String toString() {
            return "PinChangeRequest[currentPin=<redacted>, newPin=<redacted>]";
        }
    }

    /** @param pinState {@code SET}-equivalent states of the credential: {@code ACTIVE}, {@code NOT_SET} or {@code LOCKED} */
    record RosterMember(
            UUID memberId, String label, @Nullable String pinState) {}

    record RosterResponse(
            String attributionMode, int idleSeconds, int sessionMaxHours, int pinLength, List<RosterMember> members) {}

    record TerminalSessionResponse(String sessionToken, RosterMember member, int idleSeconds, Instant hardExpiresAt) {

        @Override
        public String toString() {
            return "TerminalSessionResponse[sessionToken=<redacted>, member=" + member + "]";
        }
    }
}
