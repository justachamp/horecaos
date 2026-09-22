import { TestBed } from '@angular/core/testing';

import { FulfillmentModeService } from './fulfillment-mode.service';
import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';

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

function setUp() {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });
  return { service: TestBed.inject(FulfillmentModeService), api };
}

describe('FulfillmentModeService.modes', () => {
  it('reads the default location, the configured channel, and never sends a bearer token', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({
      modes: [
        { mode: 'DELIVERY', sold: true, serviceable: true, reason: null },
        { mode: 'PICKUP', sold: false, serviceable: false, reason: 'CHANNEL_NOT_ENABLED' },
      ],
    });

    const modes = await service.modes();

    expect(api.get).toHaveBeenCalledWith(
      '/storefront/tenants/10000000-0000-0000-0000-000000000001' +
        '/brands/10000000-0000-0000-0000-000000000002' +
        '/locations/10000000-0000-0000-0000-000000000003/fulfillment-modes',
      { query: { channel: 'STOREFRONT' }, anonymous: true },
    );
    expect(modes).toHaveLength(2);
    expect(modes[0]).toEqual({ mode: 'DELIVERY', sold: true, serviceable: true, reason: null });
  });

  it('reads an explicit location instead of the configured default when given one', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({ modes: [] });

    await service.modes('other-location');

    expect(api.get).toHaveBeenCalledWith(expect.stringContaining('/locations/other-location/'), expect.anything());
  });

  it('returns an empty list rather than throwing when the response carries no modes field', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({});

    expect(await service.modes()).toEqual([]);
  });
});
