import { describe, expect, it } from 'vitest';

import { toDeviceProfile } from './device-profile';

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
});
