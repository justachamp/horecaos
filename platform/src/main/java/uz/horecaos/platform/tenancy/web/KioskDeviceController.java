package uz.horecaos.platform.tenancy.web;

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
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.PendingEnrolmentView;
import uz.horecaos.platform.tenancy.application.KioskDeviceService;
import uz.horecaos.platform.tenancy.application.KioskDeviceService.KioskConfiguration;
import uz.horecaos.platform.tenancy.application.KioskDeviceService.KioskDeviceView;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcKioskDeviceStore.ChannelChoice;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A branch's kiosks: pairing one from the code on its own screen, listing them, moving one to another KIOSK
 * channel, revoking one, and the one read a paired kiosk makes (ADR 0162).
 *
 * <p>Every manager's endpoint sits behind {@code kiosk.device.manage} at {@code LOCATION} scope -- not {@code
 * kitchen.station.manage} and not the tenant-wide {@code channel.manage}: the person who may attach a
 * customer-facing device to a branch is not the head chef. The kiosk itself holds {@code kiosk.device.read}
 * and nothing else, and reads only its own branch's configuration. An unenrolled kiosk calls the existing
 * {@code iam.web.DeviceEnrolmentController}; this controller starts at the manager's confirmation.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/kiosk")
@Tag(name = "Kiosk devices", description = "ADR 0162: pairing, listing and revoking a branch's kiosks")
public class KioskDeviceController {

    private final KioskDeviceService kiosks;
    private final CurrentActor currentActor;

    public KioskDeviceController(KioskDeviceService kiosks, CurrentActor currentActor) {
        this.kiosks = kiosks;
        this.currentActor = currentActor;
    }

    @GetMapping("/enrolments/{userCode}")
    @RequiresCapability(value = Capability.KIOSK_DEVICE_MANAGE, scope = ScopeType.LOCATION)
    @Operation(
            summary = "What a pending code claims",
            description = "The class the device asked for, its label and when the code dies, for the approval "
                    + "page. No secret. An unknown, spent or expired code is the same 404, and the lookup is "
                    + "rate limited per approver because a short code is an oracle.")
    public ResponseEntity<PendingResponse> pending(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable String userCode) {

        PendingEnrolmentView pending =
                kiosks.pending(userCode, currentActor.get().subject());
        return ResponseEntity.ok(
                new PendingResponse(pending.requestedClass().name(), pending.requestedLabel(), pending.expiresAt()));
    }

    @GetMapping("/channels")
    @RequiresCapability(value = Capability.KIOSK_DEVICE_MANAGE, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The KIOSK channels a kiosk may be paired to at this branch",
            description = "Active KIOSK channels with an active binding at this branch. Read here so the "
                    + "approval page does not need the tenant-wide channel.read a branch manager may not hold.")
    public ResponseEntity<List<ChannelChoiceResponse>> channels(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {

        return ResponseEntity.ok(kiosks.channelsAt(tenantId, locationId).stream()
                .map(ChannelChoiceResponse::of)
                .toList());
    }

    @PostMapping("/enrolments/{userCode}/approve")
    @RequiresCapability(value = Capability.KIOSK_DEVICE_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Pair a kiosk",
            description = "The userCode is the one on the kiosk's own screen. The manager has confirmed that the "
                    + "code in front of them is the one shown; arriving on the approval page approved nothing. "
                    + "Approves the device as a KIOSK and binds it to channelId at this branch, in one "
                    + "transaction. A code that asked to be another class is refused with "
                    + "DEVICE_CLASS_MISMATCH naming both classes; a channel that is not an active KIOSK channel "
                    + "selling here is KIOSK_CHANNEL_NOT_AVAILABLE. Writes an ADR 0027 SECURITY fact.")
    public ResponseEntity<KioskResponse> approve(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable String userCode,
            @Valid @RequestBody ApproveRequest body) {

        KioskDeviceView device = kiosks.approve(
                tenantId,
                brandId,
                locationId,
                userCode,
                body.displayName().strip(),
                body.channelId(),
                currentActor.get().subject());
        return ResponseEntity.ok(KioskResponse.of(device));
    }

    @GetMapping("/devices")
    @RequiresCapability(value = Capability.KIOSK_DEVICE_MANAGE, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The branch's kiosks",
            description = "Active and revoked alike, with the channel each sells through, when it was last seen "
                    + "and the build it reported, who approved or revoked it and why. needsAttention marks a "
                    + "kiosk that cannot sell: no binding row, or a channel since archived or withdrawn here.")
    public ResponseEntity<List<KioskResponse>> devices(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {

        return ResponseEntity.ok(kiosks.list(tenantId, brandId, locationId).stream()
                .map(KioskResponse::of)
                .toList());
    }

    @PutMapping("/devices/{deviceId}")
    @RequiresCapability(value = Capability.KIOSK_DEVICE_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Rename a kiosk or move it to another KIOSK channel at the same branch",
            description = "The version the form was opened at is If-Match (0 for a kiosk listed as not "
                    + "configured, which this binds); a second manager's save in between is STALE_VERSION. The "
                    + "channel is re-checked as at pairing, by the service and by the database's composite keys.")
    public ResponseEntity<KioskResponse> rebind(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody ApproveRequest body,
            HttpServletRequest request) {

        long expected = AggregateVersion.requireIfMatch(request);
        KioskDeviceView device = kiosks.rebind(
                tenantId,
                brandId,
                locationId,
                deviceId,
                body.displayName().strip(),
                body.channelId(),
                Math.toIntExact(expected),
                currentActor.get().subject());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(device.version()))
                .body(KioskResponse.of(device));
    }

    @PostMapping("/devices/{deviceId}/revoke")
    @RequiresCapability(value = Capability.KIOSK_DEVICE_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Revoke a kiosk",
            description = "Its grant is revoked first, cache-evicted at once, so the kiosk is refused on its very "
                    + "next request whatever its access token's remaining validity; its Keycloak client is then "
                    + "disabled so no new token is minted. A second press on a revoked kiosk is not an error. "
                    + "'Replace' is revoke, then pair the new unit: there is no console-issued code.")
    public ResponseEntity<RevokeResponse> revoke(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody RevokeRequest body) {

        boolean changed = kiosks.revoke(
                tenantId,
                brandId,
                locationId,
                deviceId,
                body.reason(),
                currentActor.get().subject());
        return ResponseEntity.ok(new RevokeResponse(changed));
    }

    @GetMapping("/configuration")
    @RequiresCapability(value = Capability.KIOSK_DEVICE_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The calling kiosk's configuration",
            description = "The channel it sells through (code, name, status), its branch (name, timezone, whether "
                    + "it is open and the next time it is), the fulfilment modes and payment methods the channel "
                    + "enables, the price plane's channel code and a version for caching. From the tables ADR 0036 "
                    + "owns: nothing personal, no secret. A kiosk cannot read the board, an order, a customer or "
                    + "a settings document, and it cannot place an order.")
    public ResponseEntity<KioskConfiguration> configuration(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {

        return ResponseEntity.ok(kiosks.configuration(
                tenantId, brandId, locationId, currentActor.get().subject()));
    }

    // -------------------------------------------------------------------------- bodies

    record ApproveRequest(
            @NotBlank @Size(max = 120) String displayName,
            @NotNull UUID channelId) {}

    record RevokeRequest(@NotBlank @Size(max = 500) String reason) {}

    record RevokeResponse(boolean changed) {}

    /**
     * @param requestedLabel what the device called itself: whatever it sent, never trusted, at most 120 characters,
     *                       shown as escaped text and never a credential
     */
    record PendingResponse(String requestedClass, @Nullable String requestedLabel, Instant expiresAt) {}

    record ChannelChoiceResponse(UUID channelId, String code, String displayName) {

        static ChannelChoiceResponse of(ChannelChoice choice) {
            return new ChannelChoiceResponse(choice.channelId(), choice.code(), choice.displayName());
        }
    }

    record ChannelResponse(UUID channelId, String code, String displayName, String status) {}

    record KioskResponse(
            UUID deviceId,
            UUID locationId,
            String displayName,
            String status,
            @Nullable ChannelResponse channel,
            int version,
            boolean needsAttention,
            @Nullable String enrolledBy,
            Instant enrolledAt,
            @Nullable String revokedBy,
            @Nullable Instant revokedAt,
            @Nullable String revokedReason,
            @Nullable Instant lastSeenAt,
            @Nullable String lastSeenBuild) {

        static KioskResponse of(KioskDeviceView view) {
            return new KioskResponse(
                    view.deviceId(),
                    view.locationId(),
                    view.displayName(),
                    view.status(),
                    view.channel() == null
                            ? null
                            : new ChannelResponse(
                                    view.channel().channelId(),
                                    view.channel().code(),
                                    view.channel().displayName(),
                                    view.channel().status()),
                    view.version(),
                    view.needsAttention(),
                    view.enrolledBy(),
                    view.enrolledAt(),
                    view.revokedBy(),
                    view.revokedAt(),
                    view.revokedReason(),
                    view.lastSeenAt(),
                    view.lastSeenBuild());
        }
    }
}
