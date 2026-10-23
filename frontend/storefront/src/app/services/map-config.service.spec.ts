import { TestBed } from '@angular/core/testing';
import type { YaConfig } from 'angular8-yandex-maps';

import { ApiClient } from '../core/api/api-client';
import { HorecaOSApiError } from '../core/api/problem-details';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';
import {
  MapConfigService,
  NO_MAP,
  YA_MAPS_CONFIG,
  canLookUp,
  hasTiles,
  type MapConfig,
} from './map-config.service';

const CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  channel: 'STOREFRONT',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

const WITH_TILES: MapConfig = {
  provider: 'YANDEX',
  configured: true,
  browserKey: 'a-browser-key',
  features: ['TILES', 'SUGGEST', 'GEOCODE', 'REVERSE'],
  attribution: '© Яндекс',
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
  return { service: TestBed.inject(MapConfigService), api };
}

describe('MapConfigService (ADR 0145 decision 4: the browser key is delivered, never committed)', () => {
  /** What the map library has been told since this test began, in order. */
  let heard: YaConfig[];
  let subscription: { unsubscribe(): void };

  beforeEach(() => {
    heard = [];
    subscription = YA_MAPS_CONFIG.subscribe((value) => heard.push(value));
    // The subject replays its last value to a late subscriber; a test cares about what is pushed from here.
    heard.length = 0;
  });

  afterEach(() => subscription.unsubscribe());

  it('asks the platform for the brand’s map-config, as the signed-in customer, and says nothing loudly if it fails', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(WITH_TILES);

    const config = await service.ensureLoaded();

    expect(api.get).toHaveBeenCalledWith(
      '/storefront/tenants/10000000-0000-0000-0000-000000000001' +
        '/brands/10000000-0000-0000-0000-000000000002/map-config',
      { quiet: true },
    );
    expect(config).toEqual(WITH_TILES);
    expect(service.current()).toEqual(WITH_TILES);
  });

  it('hands the map library its key and language the moment the platform says there are tiles', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(WITH_TILES);

    await service.ensureLoaded();

    expect(heard).toEqual([{ apikey: 'a-browser-key', lang: 'ru_RU' }]);
  });

  it('gives the library nothing when there are no tiles, so no keyless script is ever asked for', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({ ...WITH_TILES, browserKey: null, features: ['SUGGEST'] });

    const config = await service.ensureLoaded();

    expect(hasTiles(config)).toBe(false);
    expect(canLookUp(config)).toBe(true);
    expect(heard).toEqual([]);
  });

  it('asks once for however many screens ask', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(WITH_TILES);

    await Promise.all([service.ensureLoaded(), service.ensureLoaded()]);
    await service.ensureLoaded();

    expect(api.get).toHaveBeenCalledTimes(1);
  });

  it('is NO_MAP when the platform cannot be asked, and asks again next time instead of remembering the failure', async () => {
    const { service, api } = setUp();
    api.get
      .mockRejectedValueOnce(
        new HorecaOSApiError({ status: 500, code: 'INTERNAL_ERROR', detail: 'x' }),
      )
      .mockResolvedValue(WITH_TILES);

    expect(await service.ensureLoaded()).toEqual(NO_MAP);
    expect(service.current()).toEqual(NO_MAP);
    expect(await service.ensureLoaded()).toEqual(WITH_TILES);
    expect(api.get).toHaveBeenCalledTimes(2);
  });

  it('reads an unconfigured platform as no map and no lookups', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({
      provider: 'NONE',
      configured: false,
      browserKey: null,
      features: [],
      attribution: null,
    });

    const config = await service.ensureLoaded();

    expect(hasTiles(config)).toBe(false);
    expect(canLookUp(config)).toBe(false);
  });

  it('does not trust a malformed answer to carry a key, features or a configured flag', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({ provider: 'YANDEX', browserKey: 'k' });

    const config = await service.ensureLoaded();

    expect(config.configured).toBe(false);
    expect(config.features).toEqual([]);
    expect(hasTiles(config)).toBe(false);
  });
});
