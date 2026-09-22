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

describe('MenuService.menu: concurrent callers for the same key share one request', () => {
  it('two callers before the first resolves get one GET, not two -- home and a re-projecting cart asking together', async () => {
    const { service, api } = setUp();
    let resolve!: (menu: PublishedMenu) => void;
    api.get.mockReturnValue(new Promise<PublishedMenu>((r) => (resolve = r)));

    const first = service.menu('uz');
    const second = service.menu('uz');

    expect(api.get).toHaveBeenCalledTimes(1);
    resolve(emptyMenu());
    const [a, b] = await Promise.all([first, second]);
    expect(a).toBe(await service.menu('uz')); // now cached
    expect(b).toEqual(emptyMenu());
  });

  it('a different locale (a different key) is its own request, not deduped against the first', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(emptyMenu());

    await Promise.all([service.menu('uz'), service.menu('ru')]);

    expect(api.get).toHaveBeenCalledTimes(2);
  });

  it('a request that fails does not poison the next call for the same key', async () => {
    const { service, api } = setUp();
    api.get.mockRejectedValueOnce(new Error('network exploded'));
    api.get.mockResolvedValueOnce(emptyMenu());

    await expect(service.menu('uz')).rejects.toThrow('network exploded');
    await expect(service.menu('uz')).resolves.toEqual(emptyMenu());
    expect(api.get).toHaveBeenCalledTimes(2);
  });

  it('once cached, a later call for the same key makes no further request', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(emptyMenu());

    await service.menu('uz');
    await service.menu('uz');

    expect(api.get).toHaveBeenCalledTimes(1);
  });

  it('forget() drops the cache, so the next call re-fetches', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(emptyMenu());

    await service.menu('uz');
    service.forget();
    await service.menu('uz');

    expect(api.get).toHaveBeenCalledTimes(2);
  });
});
