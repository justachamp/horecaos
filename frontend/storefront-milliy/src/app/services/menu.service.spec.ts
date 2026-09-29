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

describe('MenuService.menu: a channel override for the table-QR flow', () => {
  it('asks for the given channel instead of this build\'s own, because a table\'s QR_TABLE channel is resolved per scan', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(emptyMenu());

    await service.menu('uz', 'loc-9', 'QR_TABLE');

    expect(api.get).toHaveBeenCalledWith(
      expect.stringContaining('/locations/loc-9/menu'),
      expect.objectContaining({ query: { locale: 'uz', channel: 'QR_TABLE' } }),
    );
  });

  it('keeps this deployment\'s own channel when no override is given', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(emptyMenu());

    await service.menu('uz');

    expect(api.get).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({ query: { locale: 'uz', channel: 'STOREFRONT' } }),
    );
  });

  it('home() carries the channel override through to the one menu read', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(emptyMenu());

    await service.home('uz', 'loc-9', 'QR_TABLE');

    expect(api.get).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({ query: { locale: 'uz', channel: 'QR_TABLE' } }),
    );
  });
});

describe('MenuService: a variant\'s sale window reaches the screens (row 4.2g)', () => {
  function menuWith(variant: Record<string, unknown>): PublishedMenu {
    return {
      ...emptyMenu(),
      categories: [
        {
          categoryId: 'c1',
          code: null,
          name: 'Osh',
          parentCategoryId: null,
          sortOrder: 0,
          productIds: ['p1'],
        },
      ],
      products: [
        {
          productId: 'p1',
          code: null,
          name: 'Osh',
          description: null,
          mediaAssetIds: [],
          imageUrls: [],
          variants: [
            {
              variantId: 'v1',
              sku: null,
              unitCode: null,
              isDefault: true,
              orderable: true,
              amountMinor: 1000,
              remainingQuantity: null,
              ...variant,
            } as never,
          ],
          modifierGroupIds: [],
        },
      ],
    };
  }

  it('keeps an out-of-window variant orderable-but-not-on-sale, distinct from sold out', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(menuWith({ onSaleNow: false }));

    const item = await service.item('p1', 'uz');

    expect(item?.variants[0].onSaleNow).toBe(false);
    // Not 86'd: the product page must be able to tell "sold out today" from
    // "not on the menu at this hour".
    expect(item?.variants[0].active).toBe(true);
    expect(item?.active).toBe(true);
  });

  it('carries onSaleNow: true straight through', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(menuWith({ onSaleNow: true }));

    const item = await service.item('p1', 'uz');

    expect(item?.variants[0].onSaleNow).toBe(true);
  });

  it('treats a variant the platform sent without the field as on sale, never as off-window', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(menuWith({}));

    const item = await service.item('p1', 'uz');

    expect(item?.variants[0].onSaleNow).toBe(true);
  });
});

describe('MenuService: the price on the dish card is the price of the portion a customer can actually get', () => {
  function portion(id: string, amountMinor: number, overrides: Record<string, unknown> = {}) {
    return {
      variantId: id,
      sku: null,
      unitCode: null,
      isDefault: false,
      orderable: true,
      onSaleNow: true,
      amountMinor,
      remainingQuantity: null,
      ...overrides,
    };
  }

  async function cardPrice(variants: readonly Record<string, unknown>[]): Promise<number | undefined> {
    const { service, api } = setUp();
    api.get.mockResolvedValue({
      ...emptyMenu(),
      products: [
        {
          productId: 'p1',
          code: null,
          name: 'Osh',
          description: null,
          mediaAssetIds: [],
          imageUrls: [],
          variants,
          modifierGroupIds: [],
        },
      ],
    } as never);
    const item = await service.item('p1', 'uz');
    return item?.price;
  }

  it('skips an authored default that is off its sale window when another portion is sellable', async () => {
    // 15:00: the breakfast-only default (10,000) is closed, the all-day portion (18,000) is not.
    const price = await cardPrice([
      portion('breakfast', 10_000, { isDefault: true, onSaleNow: false }),
      portion('all-day', 18_000),
    ]);

    expect(price).toBe(18_000);
  });

  it('skips a sold-out default when another portion is sellable', async () => {
    const price = await cardPrice([
      portion('small', 10_000, { isDefault: true, orderable: false }),
      portion('large', 18_000),
    ]);

    expect(price).toBe(18_000);
  });

  it('keeps the authored default when it can be bought, even if it is not listed first', async () => {
    const price = await cardPrice([
      portion('small', 10_000),
      portion('large', 18_000, { isDefault: true }),
    ]);

    expect(price).toBe(18_000);
  });

  it('with nothing sellable, still prices the authored default rather than inventing a number', async () => {
    const price = await cardPrice([
      portion('breakfast', 10_000, { isDefault: true, onSaleNow: false }),
      portion('lunch', 18_000, { onSaleNow: false }),
    ]);

    expect(price).toBe(10_000);
  });

  it('with nothing sellable and one portion merely sold out, prefers the one still orderable', async () => {
    const price = await cardPrice([
      portion('gone', 10_000, { isDefault: true, orderable: false }),
      portion('later', 18_000, { onSaleNow: false }),
    ]);

    expect(price).toBe(18_000);
  });
});
