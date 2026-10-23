import { describe, expect, it } from 'vitest';

import { toDeviceProfile, toKioskConfiguration } from './device-profile';

const WALL = {
  deviceId: 'dev-1',
  deviceClass: 'KITCHEN_VDU',
  displayName: 'Grill TV',
  tenantId: 't1',
  brandId: 'b1',
  locationId: 'l1',
  locationName: 'Chilanzar',
  timezone: 'Asia/Tashkent',
  station: {
    stationId: 's1',
    code: 'GRILL',
    displayNameRu: 'Гриль',
    displayNameUz: 'Gril',
    displayNameEn: 'Grill',
  },
};

describe('toDeviceProfile', () => {
  it('reads the record the server answers a wall with, station and all', () => {
    const profile = toDeviceProfile(WALL);

    expect(profile?.deviceClass).toBe('KITCHEN_VDU');
    expect(profile?.timezone).toBe('Asia/Tashkent');
    expect(profile?.station?.displayNameRu).toBe('Гриль');
    expect(profile?.locationId).toBe('l1');
  });

  it('reads a touch display, whose station is absent, as having none', () => {
    const profile = toDeviceProfile({ ...WALL, deviceClass: 'KITCHEN_KDS', station: null });

    expect(profile?.deviceClass).toBe('KITCHEN_KDS');
    expect(profile?.station).toBeNull();
  });

  it('refuses a body that is not the record rather than building a half-configured device', () => {
    expect(toDeviceProfile(null)).toBeNull();
    expect(toDeviceProfile('x')).toBeNull();
    expect(toDeviceProfile({ ...WALL, deviceClass: 'KITCHEN_EXPO' })).toBeNull();
    expect(toDeviceProfile({ ...WALL, timezone: undefined })).toBeNull();
    expect(toDeviceProfile({ ...WALL, locationId: '' })).toBeNull();
    expect(toDeviceProfile({ ...WALL, tenantId: 7 })).toBeNull();
  });

  it('names a station by its code when a display name is missing', () => {
    const profile = toDeviceProfile({
      ...WALL,
      station: { stationId: 's1', code: 'GRILL' },
    });

    expect(profile?.station?.displayNameEn).toBe('GRILL');
  });

  it('reads a kiosk with the channel it sells through (ADR 0162)', () => {
    const profile = toDeviceProfile({
      ...WALL,
      deviceClass: 'KIOSK',
      station: null,
      kiosk: {
        channelId: 'c1',
        channelCode: 'KIOSK_A',
        channelName: 'Lobby channel',
        channelStatus: 'ACTIVE',
      },
    });

    expect(profile?.deviceClass).toBe('KIOSK');
    expect(profile?.kiosk?.channelName).toBe('Lobby channel');
    expect(profile?.kiosk?.channelStatus).toBe('ACTIVE');
  });

  it('reads a kiosk the platform has not yet bound as one with no channel, not as a broken record', () => {
    const profile = toDeviceProfile({ ...WALL, deviceClass: 'KIOSK', station: null, kiosk: null });

    expect(profile?.deviceClass).toBe('KIOSK');
    expect(profile?.kiosk).toBeNull();
  });

  it('names a channel by its code when the server sends no display name', () => {
    const profile = toDeviceProfile({
      ...WALL,
      deviceClass: 'KIOSK',
      kiosk: { channelId: 'c1', channelCode: 'KIOSK_A' },
    });

    expect(profile?.kiosk?.channelName).toBe('KIOSK_A');
  });
});

describe('toKioskConfiguration (ADR 0187)', () => {
  it('keeps the pictures in position order with the version', () => {
    const configuration = toKioskConfiguration({
      idleMedia: [
        { id: 'b', assetId: 'ab', position: 1, url: '/api/v1/storefront/tenants/t/media/ab' },
        { id: 'a', assetId: 'aa', position: 0, url: '/api/v1/storefront/tenants/t/media/aa' },
      ],
      idleMediaVersion: 7,
    });

    expect(configuration?.idleMedia.map((picture) => picture.id)).toEqual(['a', 'b']);
    expect(configuration?.idleMediaVersion).toBe(7);
  });

  it('drops a picture whose address is not a path on the API’s own origin, rather than load a stranger’s URL', () => {
    const configuration = toKioskConfiguration({
      idleMedia: [
        { id: 'x', assetId: 'x', position: 0, url: 'https://evil.example/a.jpg' },
        { id: 'y', assetId: 'y', position: 1, url: '//evil.example/a.jpg' },
        { id: 'z', assetId: 'z', position: 2, url: 'javascript:alert(1)' },
        { id: 'ok', assetId: 'ok', position: 3, url: '/api/v1/storefront/tenants/t/media/ok' },
      ],
      idleMediaVersion: 1,
    });

    expect(configuration?.idleMedia.map((picture) => picture.id)).toEqual(['ok']);
  });

  it('is null for a body that is not a configuration with a list, and ignores entries that are not pictures', () => {
    expect(toKioskConfiguration(null)).toBeNull();
    expect(toKioskConfiguration({ idleMediaVersion: 1 })).toBeNull();
    expect(
      toKioskConfiguration({ idleMedia: [null, 'x', 4], idleMediaVersion: 1 })?.idleMedia,
    ).toEqual([]);
  });
});
