package uz.horecaos.platform.kitchen.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
import uz.horecaos.platform.kitchen.application.KitchenDeviceService;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A branch's kitchen display devices: who is enrolled, approving a new one,
 * and revoking a lost or retired one (ADR 0079).
 *
 * <p>Every endpoint here sits behind {@code kitchen.station.manage} — the
 * same capability that already gates a branch's station layout, extended by
 * this record's own decision rather than a new capability minted beside it.
 * Nothing here is what a device itself calls: an unenrolled device calls
 * {@code iam.web.DeviceEnrolmentController}, and an enrolled one calls the
 * ordinary {@link KitchenBoardController} endpoints its {@code
 * kitchen.ticket.read}/{@code kitchen.ticket.advance} grant already reaches.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/kitchen/devices")
@Tag(name = "Kitchen devices", description = "ADR 0079: enrolling and revoking a branch's kitchen display devices")
public class KitchenDeviceController {

    private final KitchenDeviceService devices;
    private final CurrentActor currentActor;

    public KitchenDeviceController(KitchenDeviceService devices, CurrentActor currentActor) {
        this.devices = devices;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.KITCHEN_STATION_MANAGE, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The branch's kitchen devices",
            description = "Active and revoked alike, with who enrolled or revoked each one. Behind "
                    + "kitchen.station.manage rather than kitchen.ticket.read, because a revocation "
                    + "reason and an enrolling manager's name are administrative facts a line cook's "
                    + "board read does not need.")
    public ResponseEntity<List<DeviceResponse>> list(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {

        return ResponseEntity.ok(devices.list(tenantId, locationId).stream()
                .filter(device -> device.brandId().equals(brandId))
                .map(DeviceResponse::of)
                .toList());
    }

    @PostMapping("/enrolments/{userCode}/approve")
    @RequiresCapability(value = Capability.KITCHEN_STATION_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Approve a pending enrolment",
            description = "The userCode is read off the new device's own screen. Approval grants "
                    + "exactly kitchen.ticket.read and kitchen.ticket.advance at this branch — never "
                    + "more — and writes an ADR 0027 audit fact naming this manager, the device, and "
                    + "this branch.")
    public ResponseEntity<DeviceResponse> approve(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable String userCode,
            @Valid @RequestBody ApproveRequest body) {

        DevicePrincipalView device = devices.approve(
                tenantId,
                brandId,
                locationId,
                userCode,
                body.displayName(),
                currentActor.get().subject());
        return ResponseEntity.ok(DeviceResponse.of(device));
    }

    @PostMapping("/{deviceId}/revoke")
    @RequiresCapability(value = Capability.KITCHEN_STATION_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Revoke a device",
            description = "Not a courtesy call to the device: its grant is revoked first, which is "
                    + "cache-evicted immediately, so the device is refused on its very next request "
                    + "regardless of its access token's remaining validity. A second press on an "
                    + "already-revoked device is not an error.")
    public ResponseEntity<RevokeResponse> revoke(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody RevokeRequest body) {

        atThisBranch(tenantId, locationId, deviceId);
        boolean revoked = devices.revoke(
                tenantId,
                brandId,
                locationId,
                deviceId,
                body.reason(),
                currentActor.get().subject());
        return ResponseEntity.ok(new RevokeResponse(revoked));
    }

    /**
     * A device at a sibling branch answers "not found" rather than
     * "forbidden", the same isolation rule {@link KitchenBoardController}
     * already applies to a ticket: confirming that a device of that id exists
     * somewhere is information the caller was not entitled to.
     */
    private void atThisBranch(UUID tenantId, UUID locationId, UUID deviceId) {
        boolean present = devices.list(tenantId, locationId).stream()
                .anyMatch(device -> device.id().equals(deviceId));
        if (!present) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such device");
        }
    }

    record ApproveRequest(@NotBlank @Size(max = 120) String displayName) {}

    record RevokeRequest(@NotBlank @Size(max = 500) String reason) {}

    record RevokeResponse(boolean changed) {}

    record DeviceResponse(
            UUID deviceId,
            UUID locationId,
            String deviceClass,
            String displayName,
            String status,
            String enrolledBy,
            Instant enrolledAt,
            @Nullable String revokedBy,
            @Nullable Instant revokedAt,
            @Nullable String revokedReason) {

        static DeviceResponse of(DevicePrincipalView device) {
            return new DeviceResponse(
                    device.id(),
                    device.locationId(),
                    device.deviceClass().name(),
                    device.displayName(),
                    device.status(),
                    device.enrolledBy(),
                    device.enrolledAt(),
                    device.revokedBy(),
                    device.revokedAt(),
                    device.revokedReason());
        }
    }
}
