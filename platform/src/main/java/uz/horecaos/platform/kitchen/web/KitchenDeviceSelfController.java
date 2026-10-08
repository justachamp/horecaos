package uz.horecaos.platform.kitchen.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.devices.DevicePrincipalClass;
import uz.horecaos.platform.kitchen.application.KitchenDeviceDisplayService;
import uz.horecaos.platform.kitchen.application.KitchenDeviceDisplayService.DeviceSelf;
import uz.horecaos.platform.kitchen.web.KitchenDeviceController.StationRef;
import uz.horecaos.platform.tenancy.api.KioskDeviceLookup;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * An enrolled kitchen device asks what it is (ADR 0151, answering ADR 0119's "whoami" input for
 * kitchen devices).
 *
 * <p>Before this, a device's tenant, brand and branch were typed once by whoever installed it, and a
 * wall display ran on a placeholder time zone. The device now reads its own record: it resolves the
 * caller through {@code iam.device_principals.principal_subject}, which is unique, so no Keycloak
 * protocol-mapper change is needed (the obstacle ADR 0119 recorded).
 *
 * <p><strong>No capability, on purpose, and no more than the caller's own record.</strong> This is a
 * self-read in the category of {@code GET /session/context}: a principal reading itself learns nothing
 * a refusal would not, so there is nothing for a capability to guard. It is refused to everyone who is
 * not an <em>active</em> kitchen device: a staff token has no device row, and a revoked device's row is
 * no longer active, so a token that outlives a revocation still stops here. It never returns a secret,
 * a Keycloak client id or a token.
 */
@RestController
@RequestMapping("/api/v1/devices/me")
@Tag(
        name = "Kitchen device self",
        description = "ADR 0151, ADR 0162: an enrolled device (a kitchen display or a kiosk) reads its own record")
public class KitchenDeviceSelfController {

    private final KitchenDeviceDisplayService displays;
    private final CurrentActor currentActor;
    private final KioskDeviceLookup kiosks;

    public KitchenDeviceSelfController(
            KitchenDeviceDisplayService displays, CurrentActor currentActor, KioskDeviceLookup kiosks) {
        this.displays = displays;
        this.currentActor = currentActor;
        this.kiosks = kiosks;
    }

    @GetMapping
    @Operation(
            summary = "The calling device's own record",
            description = "Its id, class, tenant, brand and branch, the branch's display name and IANA time "
                    + "zone, for a wall display the station it shows with its display names in the three "
                    + "languages, and for a kiosk the channel it sells through. A device principal only: a person's token and a revoked device's are 403. "
                    + "Never a secret, a client id or a token.")
    public ResponseEntity<DeviceSelfResponse> me() {
        DeviceSelf self = displays.selfOf(currentActor.get().subject())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INSUFFICIENT_CAPABILITY, "Only an enrolled kitchen device may read its own record"));
        return ResponseEntity.ok(new DeviceSelfResponse(
                self.device().id(),
                self.device().deviceClass().name(),
                self.device().displayName(),
                self.device().tenantId(),
                self.device().brandId(),
                self.device().locationId(),
                self.location().displayName(),
                self.location().timezone(),
                StationRef.of(self.station()),
                kioskOf(self)));
    }

    /** A kiosk's channel (ADR 0162); null for every other class and for a kiosk with no binding row. */
    private @Nullable KioskRef kioskOf(DeviceSelf self) {
        if (self.device().deviceClass() != DevicePrincipalClass.KIOSK) {
            return null;
        }
        return kiosks.bindingOf(self.device().tenantId(), self.device().id())
                .map(binding -> new KioskRef(
                        binding.channelId(), binding.channelCode(), binding.channelName(), binding.channelStatus()))
                .orElse(null);
    }

    /**
     * @param displayName  the device's own label (what Kitchen → Devices calls it)
     * @param locationName the branch's display name
     * @param timezone     the branch's IANA zone, so the wall reads its clock where the kitchen is
     * @param station      for a wall display, the station it shows; null for the whole branch and for a touch KDS
     * @param kiosk        for a paired kiosk, the channel it sells through (ADR 0162); null for every other class
     *                     and for a kiosk that is not configured, which its shell shows as «not configured»
     */
    record DeviceSelfResponse(
            UUID deviceId,
            String deviceClass,
            String displayName,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String locationName,
            String timezone,
            @Nullable StationRef station,
            @Nullable KioskRef kiosk) {}

    record KioskRef(UUID channelId, String channelCode, String channelName, String channelStatus) {}
}
