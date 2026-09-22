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

class FakeApiClient {
  get = vi.fn();
}

function setUp(): { service: LocationProfileService; api: FakeApiClient } {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });
  return { service: TestBed.inject(LocationProfileService), api };
}

function profile(overrides: Partial<LocationProfile> = {}): LocationProfile {
  return {
    locationId: 'loc-1',
    brandName: 'Test Brand',
    locationName: 'Central kitchen',
    addressLine: '1 Demo Street',
    district: 'Shaykhontohur',
    city: 'Tashkent',
    ...overrides,
  };
}

function notFound(): HorecaOSApiError {
  return new HorecaOSApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'No such location' });
}

describe('LocationProfileService.profile', () => {
  it('requests the storefront profile endpoint, anonymously, for the configured tenant and brand', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(profile());

    await service.profile('loc-1');

    expect(api.get).toHaveBeenCalledWith(
      '/storefront/tenants/10000000-0000-0000-0000-000000000001/brands/' +
        '10000000-0000-0000-0000-000000000002/locations/loc-1/profile',
      { anonymous: true },
    );
  });

  it('resolves the branch on a successful read', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(profile({ locationName: 'Central kitchen', addressLine: '1 Demo Street' }));

    const result = await service.profile('loc-1');

    expect(result).toEqual(profile({ locationName: 'Central kitchen', addressLine: '1 Demo Street' }));
  });

  it('resolves null, not a thrown error, on a 404 -- an inactive or unknown branch is "nothing to show"', async () => {
    const { service, api } = setUp();
    api.get.mockRejectedValue(notFound());

    await expect(service.profile('missing')).resolves.toBeNull();
  });

  it('caches a resolved read per location -- a second call for the same branch does not hit the network again', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(profile());

    await service.profile('loc-1');
    await service.profile('loc-1');

    expect(api.get).toHaveBeenCalledTimes(1);
  });

  it('caches a 404 too -- the branch does not appear mid-session', async () => {
    const { service, api } = setUp();
    api.get.mockRejectedValue(notFound());

    await service.profile('missing');
    await service.profile('missing');

    expect(api.get).toHaveBeenCalledTimes(1);
  });

  it('asks two different branches independently', async () => {
    const { service, api } = setUp();
    api.get.mockImplementation((path: string) =>
      Promise.resolve(profile({ locationId: path.includes('loc-1') ? 'loc-1' : 'loc-2' })),
    );

    const [first, second] = await Promise.all([service.profile('loc-1'), service.profile('loc-2')]);

    expect(first?.locationId).toBe('loc-1');
    expect(second?.locationId).toBe('loc-2');
    expect(api.get).toHaveBeenCalledTimes(2);
  });

  it('does not cache a genuine failure -- a network blip can be retried rather than replayed forever', async () => {
    const { service, api } = setUp();
    api.get.mockRejectedValueOnce(new Error('network down'));
    api.get.mockResolvedValueOnce(profile());

    await expect(service.profile('loc-1')).rejects.toThrow('network down');
    const result = await service.profile('loc-1');

    expect(result).toEqual(profile());
    expect(api.get).toHaveBeenCalledTimes(2);
  });
});
