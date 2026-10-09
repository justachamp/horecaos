package uz.horecaos.platform.tenancy.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Which KIOSK sales channel a paired kiosk sells through (ADR 0162), for the module that answers a
 * device "who am I" ({@code GET /api/v1/devices/me}).
 *
 * <p>The self-read lives beside the kitchen's because it was built for the kitchen displays (ADR 0151) and
 * answers every enrolled device with the same record; the kiosk's channel is tenancy's fact, reached through
 * this port so the answer never copies it.
 */
public interface KioskDeviceLookup {

    /**
     * The channel the paired kiosk {@code deviceId} of this tenant sells through.
     *
     * <p>Empty when the tenant's {@code feature.kiosk_devices} flag is off, and empty for a KIOSK principal with no
     * binding row (a defect, or an approval that half-committed): the device shell shows «not configured» and the
     * registry flags it, rather than the self-read failing for the device.
     */
    Optional<KioskBinding> bindingOf(UUID tenantId, UUID deviceId);

    /**
     * @param channelStatus {@code ACTIVE}, {@code INACTIVE} or {@code ARCHIVED}: a kiosk bound to a channel that
     *                      is later archived keeps working as a device and sells nothing, and says so
     */
    record KioskBinding(UUID channelId, String channelCode, String channelName, String channelStatus) {}
}
