package uz.horecaos.platform.kitchen.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.DevicePrincipalView;
import uz.horecaos.platform.iam.api.devices.DevicePrincipalClass;
import uz.horecaos.platform.kitchen.application.KitchenDeviceDisplayService;
import uz.horecaos.platform.kitchen.application.KitchenDeviceDisplayService.Display;
import uz.horecaos.platform.kitchen.application.KitchenDeviceService;
import uz.horecaos.platform.kitchen.infrastructure.persistence.JdbcKitchenStore.StationRow;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A branch's kitchen display devices: who is enrolled, approving a new one,
 * revoking a lost or retired one, and choosing which station a wall display
 * shows (ADR 0079, ADR 0151).
 *
 * <p>Every endpoint here sits behind {@code kitchen.station.manage} — the
 * same capability that already gates a branch's station layout, extended by
 * this record's own decision rather than a new capability minted beside it.
 * Nothing here is what a device itself calls: an unenrolled device calls
 * {@code iam.web.DeviceEnrolmentController}, an enrolled one asks who it is
 * through {@link KitchenDeviceSelfController}, and then reads what its own
 * class's capability opens ({@code kitchen.ticket.read}/{@code
 * kitchen.ticket.advance} for a touch KDS, {@code kitchen.display.read} for a
 * wall display).
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/kitchen/devices")
@Tag(
        name = "Kitchen devices",
        description = "ADR 0079/0151: enrolling and revoking a branch's kitchen displays, and configuring a wall")
public class KitchenDeviceController {

    private final KitchenDeviceService devices;
    private final KitchenDeviceDisplayService displays;
    private final CurrentActor currentActor;
    private final Clock clock;

    public KitchenDeviceController(
            KitchenDeviceService devices,
            KitchenDeviceDisplayService displays,
            CurrentActor currentActor,
            Clock clock) {
        this.devices = devices;
        this.displays = displays;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @GetMapping
    @RequiresCapability(value = Capability.KITCHEN_STATION_MANAGE, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The branch's kitchen devices",
            description = "Active and revoked alike, with who enrolled or revoked each one, the class each "
                    + "asked for and was approved as, and for a wall display the station it shows, when it "
                    + "last read and whether it counts as not seen. Behind kitchen.station.manage rather "
                    + "than kitchen.ticket.read, because a revocation reason and an enrolling manager's "
                    + "name are administrative facts a line cook's board read does not need.")
    public ResponseEntity<List<DeviceResponse>> list(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {

        Map<UUID, Display> displayOf = displays.displaysOf(tenantId, brandId, locationId);
        Instant now = clock.instant();
        return ResponseEntity.ok(devices.list(tenantId, locationId).stream()
                .filter(device -> device.brandId().equals(brandId))
                .map(device -> DeviceResponse.of(device, displayOf.get(device.id()), now))
                .toList());
    }

    @GetMapping("/enrolments/{userCode}")
    @RequiresCapability(value = Capability.KITCHEN_STATION_MANAGE, scope = ScopeType.LOCATION)
    @Operation(
            summary = "What a pending code claims",
            description = "The class the device asked for, shown to the approver before they choose the class "
                    + "to approve it as: an approval may narrow what was asked for and never widen it. An "
                    + "unknown, spent or expired code is the same 404.")
    public ResponseEntity<PendingEnrolmentResponse> pending(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable String userCode) {

        return ResponseEntity.ok(devices.pendingEnrolment(userCode)
                .map(pending -> new PendingEnrolmentResponse(
                        pending.requestedClass().name(), pending.requestedLabel(), pending.expiresAt()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No pending enrolment")));
    }

    @PostMapping("/enrolments/{userCode}/approve")
    @RequiresCapability(value = Capability.KITCHEN_STATION_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Approve a pending enrolment",
            description = "The userCode is read off the new device's own screen. deviceClass is the class "
                    + "it is approved as (absent: the class it asked for): a touch KDS is granted exactly kitchen.ticket.read, "
                    + "kitchen.display.read and kitchen.ticket.advance at this branch, a wall display "
                    + "exactly kitchen.display.read, never more. The class may narrow what the device "
                    + "asked for (a KITCHEN_KDS request may be approved as KITCHEN_VDU) and never widen "
                    + "it (a KITCHEN_VDU request cannot be approved as KITCHEN_KDS: 400). Writes an ADR 0027 "
                    + "audit fact naming this manager, the device, this branch and both classes.")
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
                body.deviceClass(),
                currentActor.get().subject());
        return ResponseEntity.ok(DeviceResponse.of(
                device, displays.displaysOf(tenantId, brandId, locationId).get(device.id()), clock.instant()));
    }

    @PutMapping("/{deviceId}/display")
    @RequiresCapability(value = Capability.KITCHEN_STATION_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Choose which station a wall display shows",
            description = "A wall display's station belongs to the device, not to the page: the server "
                    + "applies it and ignores the station a request names. stationId null means the whole "
                    + "branch. The version the form was opened at is If-Match; a second manager's save in "
                    + "between is STALE_VERSION. Only an active wall display has a configuration (422 "
                    + "otherwise), and a station must be an active station of this branch (400).")
    public ResponseEntity<DisplayResponse> configureDisplay(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody ConfigureDisplayRequest body,
            HttpServletRequest request) {

        long expected = AggregateVersion.requireIfMatch(request);
        atThisBranch(tenantId, locationId, deviceId);
        Display display = displays.configure(
                tenantId,
                brandId,
                locationId,
                deviceId,
                body.stationId(),
                expected,
                currentActor.get().subject());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(display.version()))
                .body(DisplayResponse.of(display, null, clock.instant()));
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

    /**
     * @param deviceClass the class the device is approved as; absent means the class it asked for. The
     *                    console always sends one (defaulting the choice to what was asked for), and a
     *                    client from before ADR 0151 sends none and keeps its meaning.
     */
    record ApproveRequest(
            @NotBlank @Size(max = 120) String displayName,
            @Nullable DevicePrincipalClass deviceClass) {}

    /** @param stationId the station to show, or null for the whole branch (an explicit null is a choice) */
    record ConfigureDisplayRequest(@Nullable UUID stationId) {}

    record RevokeRequest(@NotBlank @Size(max = 500) String reason) {}

    record RevokeResponse(boolean changed) {}

    record PendingEnrolmentResponse(
            String requestedClass, @Nullable String requestedLabel, Instant expiresAt) {}

    /** A station as a wall display names it: the three display names the wall renders, and its stable code. */
    record StationRef(UUID stationId, String code, String displayNameRu, String displayNameUz, String displayNameEn) {

        static @Nullable StationRef of(@Nullable StationRow station) {
            return station == null
                    ? null
                    : new StationRef(
                            station.id(),
                            station.code(),
                            station.displayNameRu(),
                            station.displayNameUz(),
                            station.displayNameEn());
        }
    }

    /**
     * @param station    the station the wall shows, null for the whole branch
     * @param lastReadAt when the wall last read, null before its first read
     * @param version    the {@code If-Match} the next configuration is sent with
     * @param notSeen    no read for {@code notSeenAfterMinutes}: powered off, off the network or erroring
     */
    record DisplayResponse(
            @Nullable StationRef station,
            @Nullable Instant lastReadAt,
            int version,
            boolean notSeen,
            long notSeenAfterMinutes) {

        /**
         * @param since when to start counting a wall that has never read: its enrolment. Null for a
         *              revoked wall, which is not "not seen" but gone.
         */
        static DisplayResponse of(Display display, @Nullable Instant since, Instant now) {
            return new DisplayResponse(
                    StationRef.of(display.station()),
                    display.lastReadAt(),
                    display.version(),
                    since != null && display.notSeen(since, now),
                    display.notSeenAfter().toMinutes());
        }
    }

    /**
     * @param deviceClass    the class the device was approved as
     * @param requestedClass the class it asked for; differs only when the approver narrowed it
     * @param display        a wall display's configuration; null for every other class
     */
    record DeviceResponse(
            UUID deviceId,
            UUID locationId,
            String deviceClass,
            String requestedClass,
            String displayName,
            String status,
            String enrolledBy,
            Instant enrolledAt,
            @Nullable String revokedBy,
            @Nullable Instant revokedAt,
            @Nullable String revokedReason,
            @Nullable DisplayResponse display) {

        static DeviceResponse of(DevicePrincipalView device, @Nullable Display display, Instant now) {
            return new DeviceResponse(
                    device.id(),
                    device.locationId(),
                    device.deviceClass().name(),
                    device.requestedClass().name(),
                    device.displayName(),
                    device.status(),
                    device.enrolledBy(),
                    device.enrolledAt(),
                    device.revokedBy(),
                    device.revokedAt(),
                    device.revokedReason(),
                    display == null
                            ? null
                            : DisplayResponse.of(
                                    display, "ACTIVE".equals(device.status()) ? device.enrolledAt() : null, now));
        }
    }
}
