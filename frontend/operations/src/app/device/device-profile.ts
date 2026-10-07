/**
 * What an enrolled kitchen device learns about itself from `GET /api/v1/devices/me` (ADR 0151, answering
 * ADR 0119's "whoami" input for kitchen devices): who it is, where it is, in which zone, and for a wall
 * display which station it shows.
 *
 * Read from the server, not typed: a restarted TV comes back showing the right station and reading its
 * clock in its branch's own zone with nobody at a keyboard. Never a secret, a client id or a token: the
 * endpoint returns none, and neither does this type.
 */

/** The classes a kitchen device can be enrolled as (`DevicePrincipalClass`). */
export type DeviceClass = 'KITCHEN_KDS' | 'KITCHEN_VDU';

/** A station as a wall names it: the three display names it renders, and its stable code. */
export interface DeviceStation {
  readonly stationId: string;
  readonly code: string;
  readonly displayNameRu: string;
  readonly displayNameUz: string;
  readonly displayNameEn: string;
}

/** Mirrors `KitchenDeviceSelfController.DeviceSelfResponse`. */
export interface DeviceProfile {
  readonly deviceId: string;
  readonly deviceClass: DeviceClass;
  /** The device's own label, as Kitchen → Devices shows it. */
  readonly displayName: string;
  readonly tenantId: string;
  readonly brandId: string;
  readonly locationId: string;
  readonly locationName: string;
  /** The branch's IANA zone, so the wall reads its clock where the kitchen is. */
  readonly timezone: string;
  /** For a wall display, the station it shows; null for the whole branch and for a touch KDS. */
  readonly station: DeviceStation | null;
}

const CLASSES: readonly string[] = ['KITCHEN_KDS', 'KITCHEN_VDU'];

/** A body that is the record, or null: a malformed answer must not become a half-configured device. */
export function toDeviceProfile(value: unknown): DeviceProfile | null {
  if (typeof value !== 'object' || value === null) {
    return null;
  }
  const candidate = value as Partial<Record<keyof DeviceProfile, unknown>>;
  const text = (field: keyof DeviceProfile): string | null =>
    typeof candidate[field] === 'string' && (candidate[field] as string).length > 0
      ? (candidate[field] as string)
      : null;
  const deviceClass = text('deviceClass');
  const deviceId = text('deviceId');
  const tenantId = text('tenantId');
  const brandId = text('brandId');
  const locationId = text('locationId');
  const timezone = text('timezone');
  if (
    !deviceClass ||
    !CLASSES.includes(deviceClass) ||
    !deviceId ||
    !tenantId ||
    !brandId ||
    !locationId ||
    !timezone
  ) {
    return null;
  }
  return {
    deviceId,
    deviceClass: deviceClass as DeviceClass,
    displayName: text('displayName') ?? '',
    tenantId,
    brandId,
    locationId,
    locationName: text('locationName') ?? '',
    timezone,
    station: toStation(candidate.station),
  };
}

function toStation(value: unknown): DeviceStation | null {
  if (typeof value !== 'object' || value === null) {
    return null;
  }
  const station = value as Partial<DeviceStation>;
  if (typeof station.stationId !== 'string' || typeof station.code !== 'string') {
    return null;
  }
  return {
    stationId: station.stationId,
    code: station.code,
    displayNameRu: station.displayNameRu ?? station.code,
    displayNameUz: station.displayNameUz ?? station.code,
    displayNameEn: station.displayNameEn ?? station.code,
  };
}
