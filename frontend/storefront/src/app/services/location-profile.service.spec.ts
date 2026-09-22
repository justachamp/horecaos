import { TestBed } from '@angular/core/testing';

import { LocationProfileService, type LocationProfile } from './location-profile.service';
import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';
import { HorecaOSApiError } from '../core/api/problem-details';

const CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

function profileFixture(overrides: Partial<LocationProfile> = {}): LocationProfile {
  return {
    displayName: 'Chilonzor filiali',
    addressLine: 'Bunyodkor ko\'chasi 12',
    district: 'Chilonzor',
    city: 'Toshkent',
    ...overrides,
  };
}

class FakeApiClient {
  get = vi.fn();
}

function setUp() {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });
  return { service: TestBed.inject(LocationProfileService), api };
}

describe('LocationProfileService.profile', () => {
  it('reads the tenant/brand-scoped, unauthenticated profile endpoint for the given location', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(profileFixture());

    const result = await service.profile('loc-1');

    expect(api.get).toHaveBeenCalledWith(
      '/storefront/tenants/10000000-0000-0000-0000-000000000001' +
        '/brands/10000000-0000-0000-0000-000000000002' +
        '/locations/loc-1/profile',
      { anonymous: true },
    );
    expect(result).toEqual(profileFixture());
  });

  it('resolves to null for an unknown location (404 RESOURCE_NOT_FOUND) rather than throwing', async () => {
    const { service, api } = setUp();
    api.get.mockRejectedValue(
      new HorecaOSApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'x' }),
    );

    const result = await service.profile('unknown-loc');

    expect(result).toBeNull();
  });

  it('propagates a failure that is not "not found"', async () => {
    const { service, api } = setUp();
    api.get.mockRejectedValue(new HorecaOSApiError({ status: 500, code: 'INTERNAL_ERROR', detail: 'x' }));

    await expect(service.profile('loc-1')).rejects.toThrow();
  });

  it('caches a resolved read -- a second call for the same location does not ask again', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(profileFixture());

    await service.profile('loc-1');
    await service.profile('loc-1');

    expect(api.get).toHaveBeenCalledTimes(1);
  });

  it('does not cache a failure -- a later call retries instead of repeating a transient error forever', async () => {
    const { service, api } = setUp();
    api.get.mockRejectedValueOnce(new HorecaOSApiError({ status: 500, code: 'INTERNAL_ERROR', detail: 'x' }));
    api.get.mockResolvedValueOnce(profileFixture());

    await expect(service.profile('loc-1')).rejects.toThrow();
    const second = await service.profile('loc-1');

    expect(api.get).toHaveBeenCalledTimes(2);
    expect(second).toEqual(profileFixture());
  });
});
