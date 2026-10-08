package uz.horecaos.platform.iam.api.devices;

import java.util.UUID;

/**
 * A device principal was revoked (ADR 0079, ADR 0155), published synchronously inside the revoking transaction so whatever
 * hangs off a device -- today, its terminal sessions -- ends in the same commit. Carries ids and a reason, nothing about
 * the device's credential.
 */
public record DeviceRevoked(UUID tenantId, UUID deviceId, String revokerSubject, String reason) {}
