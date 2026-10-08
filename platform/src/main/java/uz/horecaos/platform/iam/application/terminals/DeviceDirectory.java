package uz.horecaos.platform.iam.application.terminals;

import java.util.Optional;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.DevicePrincipalView;
import uz.horecaos.platform.web.cache.CacheRegistry;

/**
 * "Is this authenticated caller an enrolled device, and in what mode?" -- asked of every request that carries a bearer
 * (ADR 0155).
 *
 * <p>Answered from {@code iam.device_principals}, the row revocation and a change of attribution mode both change, so a
 * revoked device or a device set back to {@code DEVICE_ONLY} is treated as such on its very next request. The one thing
 * cached is the negative -- "this subject is not a device" -- because that is what almost every request is, and
 * it cannot go stale: a subject does not change kind (see {@link CacheRegistry#IAM_NOT_A_DEVICE}).
 */
@Component
public class DeviceDirectory {

    private final DeviceEnrolmentPort devices;
    private final Cache notADevice;

    public DeviceDirectory(DeviceEnrolmentPort devices, CacheManager caches) {
        this.devices = devices;
        Cache cache = caches.getCache(CacheRegistry.IAM_NOT_A_DEVICE.cacheName());
        this.notADevice = cache != null ? cache : new org.springframework.cache.concurrent.ConcurrentMapCache("unused");
    }

    /** The active device principal for this Keycloak subject; empty for a person, a partner and a revoked device. */
    public Optional<DevicePrincipalView> activeDeviceOf(String subject) {
        if (notADevice.get(subject) != null) {
            return Optional.empty();
        }
        Optional<DevicePrincipalView> device = devices.activeDeviceOf(subject);
        if (device.isEmpty()) {
            notADevice.put(subject, Boolean.TRUE);
        }
        return device;
    }
}
