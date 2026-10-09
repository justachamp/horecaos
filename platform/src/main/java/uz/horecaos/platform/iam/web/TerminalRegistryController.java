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
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.terminals.TerminalPolicy.AttributionMode;
import uz.horecaos.platform.iam.application.terminals.TerminalRegistryService;
import uz.horecaos.platform.iam.application.terminals.TerminalRegistryService.Terminal;
import uz.horecaos.platform.iam.application.terminals.TerminalRegistryService.TerminalSessionPage;
import uz.horecaos.platform.iam.application.terminals.TerminalRegistryService.UnlockedNow;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The class-neutral terminals registry (ADR 0155, Decision 6): every device principal at a branch, of any class.
 *
 * <p>Reading needs {@code terminal.read}, changing needs {@code terminal.manage}, both at {@code LOCATION} scope. Enrolment
 * approval is not here: it stays with the module that owns the class ({@code kitchen.station.manage}, {@code
 * kiosk.device.manage}). Two ways to revoke a kitchen tablet exist, by two capabilities, and both end its sessions.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/terminals")
@Tag(name = "Terminals", description = "ADR 0155: every device at a branch, who is at it, and how it attributes")
public class TerminalRegistryController {

    private final TerminalRegistryService registry;
    private final CurrentActor currentActor;

    public TerminalRegistryController(TerminalRegistryService registry, CurrentActor currentActor) {
        this.registry = registry;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.TERMINAL_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Every device at this branch",
            description =
                    "Of any class: name, class, state, attribution mode, the device's auto-lock and what applies now, "
                            + "who is unlocked at it, when it was last seen and the build it reported, who enrolled it and who "
                            + "revoked it. «Last seen» is the last authenticated request from the device, stamped at most once a "
                            + "minute: no heartbeat endpoint exists, so a healthy idle device and a stuck one are told apart by "
                            + "how long since.")
    public ResponseEntity<List<TerminalResponse>> list(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {

        return ResponseEntity.ok(registry.list(tenantId, brandId, locationId).stream()
                .map(TerminalResponse::of)
                .toList());
    }

    @PutMapping("/{deviceId}")
    @RequiresCapability(value = Capability.TERMINAL_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Rename a device, set its attribution mode and auto-lock",
            description = "The version the form was opened at is If-Match; a second manager's save in between is "
                    + "STALE_VERSION. A mode other than DEVICE_ONLY is refused for a class nobody unlocks (422), and setting "
                    + "a device back to DEVICE_ONLY ends its sessions at once -- rollback restores today's attribution. "
                    + "autoLockSeconds null follows the tenant's policy; otherwise 30 to 3,600.")
    public ResponseEntity<TerminalResponse> configure(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody TerminalConfigureRequest body,
            HttpServletRequest request) {

        long expected = AggregateVersion.requireIfMatch(request);
        Terminal terminal = registry.configure(
                tenantId,
                brandId,
                locationId,
                deviceId,
                body.displayName().strip(),
                body.attributionMode(),
                body.autoLockSeconds(),
                Math.toIntExact(expected),
                currentActor.get().subject());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(terminal.version()))
                .body(TerminalResponse.of(terminal));
    }

    @PostMapping("/{deviceId}/revoke")
    @RequiresCapability(value = Capability.TERMINAL_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Revoke a device",
            description = "Its grant is revoked first, so the next request is refused whatever its token's remaining "
                    + "validity; its Keycloak client is disabled and its terminal sessions end. A second press on a "
                    + "revoked device is not an error.")
    public ResponseEntity<TerminalRevokeResponse> revoke(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody TerminalRevokeRequest body) {

        boolean changed = registry.revoke(
                tenantId,
                brandId,
                locationId,
                deviceId,
                body.reason(),
                currentActor.get().subject());
        return ResponseEntity.ok(new TerminalRevokeResponse(changed));
    }

    @GetMapping("/{deviceId}/sessions")
    @RequiresCapability(value = Capability.TERMINAL_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Who was unlocked at this device, when, and how it ended",
            description =
                    "Newest first, twenty-five to a page, opaque cursor. The label is the one the tenant chose for a "
                            + "shared screen; the non-personal reference rides beside it.")
    public ResponseEntity<TerminalSessionPage> sessions(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID deviceId,
            @RequestParam(required = false) @Nullable String cursor) {

        return ResponseEntity.ok(registry.sessionsOf(tenantId, brandId, locationId, deviceId, cursor));
    }

    // ------------------------------------------------------------------------------------ bodies

    record TerminalConfigureRequest(
            @NotBlank @Size(max = 120) String displayName,
            @NotNull AttributionMode attributionMode,
            @Nullable Integer autoLockSeconds) {}

    record TerminalRevokeRequest(@NotBlank @Size(max = 500) String reason) {}

    record TerminalRevokeResponse(boolean changed) {}

    record TerminalResponse(
            UUID deviceId,
            String displayName,
            String deviceClass,
            String status,
            boolean unlockable,
            String attributionMode,
            @Nullable Integer autoLockSeconds,
            int effectiveAutoLockSeconds,
            @Nullable Instant lastSeenAt,
            @Nullable String lastSeenBuild,
            @Nullable UnlockedNow unlockedNow,
            @Nullable String enrolledBy,
            Instant enrolledAt,
            @Nullable String revokedBy,
            @Nullable Instant revokedAt,
            @Nullable String revokedReason,
            int version) {

        static TerminalResponse of(Terminal terminal) {
            return new TerminalResponse(
                    terminal.deviceId(),
                    terminal.displayName(),
                    terminal.deviceClass(),
                    terminal.status(),
                    terminal.unlockable(),
                    terminal.attributionMode(),
                    terminal.autoLockSeconds(),
                    terminal.effectiveAutoLockSeconds(),
                    terminal.lastSeenAt(),
                    terminal.lastSeenBuild(),
                    terminal.unlockedNow(),
                    terminal.enrolledBy(),
                    terminal.enrolledAt(),
                    terminal.revokedBy(),
                    terminal.revokedAt(),
                    terminal.revokedReason(),
                    terminal.version());
        }
    }
}
