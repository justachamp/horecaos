import { TestBed } from '@angular/core/testing';

import { MenuService, type PublishedMenu } from './menu.service';
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

function emptyMenu(): PublishedMenu {
  return {
    publicationId: 'pub-1',
    locale: 'uz',
    currency: 'UZS',
    categories: [],
    products: [],
    modifierGroups: [],
  };
}

function setUp() {
  const api = new FakeApiClient();
  TestBed.configureTestingModule({
    providers: [
      { provide: ApiClient, useValue: api },
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });
  return { service: TestBed.inject(MenuService), api };
}

describe('MenuService.menu: every read goes back to the origin', () => {
  it('a later call for the same key still re-fetches -- the origin, not a stale local copy, decides freshness', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(emptyMenu());

    await service.menu('uz');
    await service.menu('uz');

    expect(api.get).toHaveBeenCalledTimes(2);
  });

  it('a stop taken between two reads shows up on the very next call, not just after some future cache expiry', async () => {
    const { service, api } = setUp();
    const stillAvailable = emptyMenu();
    const now86d: PublishedMenu = { ...emptyMenu(), publicationId: 'pub-1' };
    api.get.mockResolvedValueOnce(stillAvailable).mockResolvedValueOnce(now86d);

    const first = await service.menu('uz');
    const second = await service.menu('uz');

    expect(api.get).toHaveBeenCalledTimes(2);
    expect(second).toBe(now86d);
    expect(second).not.toBe(first);
  });

  it('a different locale (a different key) is its own request', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(emptyMenu());

    await Promise.all([service.menu('uz'), service.menu('ru')]);

    expect(api.get).toHaveBeenCalledTimes(2);
  });
});
