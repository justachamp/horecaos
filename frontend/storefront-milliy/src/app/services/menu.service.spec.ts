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
  it("asks for the given channel instead of this build's own, because a table's QR_TABLE channel is resolved per scan", async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(emptyMenu());

    await service.menu('uz', 'loc-9', 'QR_TABLE');

    expect(api.get).toHaveBeenCalledWith(
      expect.stringContaining('/locations/loc-9/menu'),
      expect.objectContaining({ query: { locale: 'uz', channel: 'QR_TABLE' } }),
    );
  });

  it("keeps this deployment's own channel when no override is given", async () => {
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

describe("MenuService: a variant's sale window reaches the screens (row 4.2g)", () => {
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

  async function cardPrice(
    variants: readonly Record<string, unknown>[],
  ): Promise<number | undefined> {
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

describe('MenuService: composite products (ADR 0136)', () => {
  const component = (componentId: string, name: string, amountMinor: number | null) => ({
    componentId,
    variantId: `${componentId}-variant`,
    productId: null,
    name,
    variantName: null,
    defaultQuantity: 1,
    sortOrder: 0,
    orderable: true,
    amountMinor,
  });

  function comboMenu(): PublishedMenu {
    return {
      ...emptyMenu(),
      products: [
        {
          productId: 'p-lunch',
          code: 'LUNCH',
          name: 'Lunch box',
          description: null,
          mediaAssetIds: [],
          imageUrls: [],
          variants: [
            {
              variantId: 'v-lunch',
              sku: null,
              unitCode: null,
              isDefault: true,
              orderable: true,
              amountMinor: null,
              onSaleNow: true,
              remainingQuantity: null,
            },
          ],
          modifierGroupIds: [],
          comboGroupIds: ['g-main', 'g-drink'],
        },
      ],
      comboGroups: [
        {
          comboGroupId: 'g-main',
          containerVariantId: 'v-lunch',
          code: 'MAIN',
          name: 'Main',
          minimumSelections: 1,
          maximumSelections: 1,
          allowSameComponentMultipleTimes: false,
          sortOrder: 0,
          components: [
            component('c-burger', 'Burger', 25_000),
            component('c-wrap', 'Wrap', 22_000),
          ],
        },
        {
          comboGroupId: 'g-drink',
          containerVariantId: 'v-lunch',
          code: 'DRINK',
          name: 'Drink',
          minimumSelections: 0,
          maximumSelections: 1,
          allowSameComponentMultipleTimes: false,
          sortOrder: 1,
          components: [component('c-cola', 'Cola', 3_000)],
        },
      ],
    };
  }

  it('projects a combo product with its choices, each component with its own price inside the combo', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(comboMenu());

    const item = await service.item('p-lunch', 'uz');

    expect(item?.comboGroups?.map((group) => group.name)).toEqual(['Main', 'Drink']);
    expect(item?.comboGroups?.[0].components).toEqual([
      {
        id: 'c-burger',
        name: 'Burger',
        variantName: null,
        defaultQuantity: 1,
        active: true,
        amountMinor: 25_000,
      },
      {
        id: 'c-wrap',
        name: 'Wrap',
        variantName: null,
        defaultQuantity: 1,
        active: true,
        amountMinor: 22_000,
      },
    ]);
  });

  it('prices a combo at the least it can cost, since its own variant has none', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue(comboMenu());

    const item = await service.item('p-lunch', 'uz');

    expect(item?.price).toBe(22_000);
    expect(item?.variants[0].price).toBe(22_000);
  });

  it('shows a combo with an unpriced needed component as having no price, never as the sum of the rest', async () => {
    const { service, api } = setUp();
    const menu = comboMenu();
    api.get.mockResolvedValue({
      ...menu,
      comboGroups: [
        {
          ...menu.comboGroups![0],
          components: [component('c-burger', 'Burger', null), component('c-wrap', 'Wrap', 22_000)],
        },
      ],
    });

    const item = await service.item('p-lunch', 'uz');

    expect(item?.price).toBe(0);
  });

  it('a product that is no combo has no choices and keeps its own price', async () => {
    const { service, api } = setUp();
    const menu = comboMenu();
    api.get.mockResolvedValue({
      ...menu,
      products: [
        {
          ...menu.products[0],
          comboGroupIds: undefined,
          variants: [{ ...menu.products[0].variants[0], amountMinor: 30_000 }],
        },
      ],
    });

    const item = await service.item('p-lunch', 'uz');

    expect(item?.comboGroups).toEqual([]);
    expect(item?.price).toBe(30_000);
  });

  it('replaces a shared group’s required, minimum and maximum with this product’s own published rule', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({
      ...emptyMenu(),
      products: [
        {
          productId: 'p-1',
          code: 'BURGER',
          name: 'Burger',
          description: null,
          mediaAssetIds: [],
          imageUrls: [],
          variants: [],
          modifierGroupIds: ['g-sauce'],
          modifierGroupPolicies: [
            {
              modifierGroupId: 'g-sauce',
              required: true,
              minimumSelections: 1,
              maximumSelections: 2,
            },
          ],
        },
        {
          productId: 'p-2',
          code: 'WRAP',
          name: 'Wrap',
          description: null,
          mediaAssetIds: [],
          imageUrls: [],
          variants: [],
          modifierGroupIds: ['g-sauce'],
        },
      ],
      modifierGroups: [
        {
          modifierGroupId: 'g-sauce',
          code: 'SAUCE',
          name: 'Sauce',
          required: false,
          minimumSelections: 0,
          maximumSelections: 3,
          allowSameOptionMultipleTimes: false,
          options: [],
        },
      ],
    } satisfies PublishedMenu);

    const burger = await service.item('p-1', 'uz');
    const wrap = await service.item('p-2', 'uz');

    expect(burger?.modifierGroups[0]).toMatchObject({
      required: true,
      minimumSelections: 1,
      maximumSelections: 2,
    });
    expect(wrap?.modifierGroups[0]).toMatchObject({
      required: false,
      minimumSelections: 0,
      maximumSelections: 3,
    });
  });

  it('labels an option by its name where the menu carries one, and by its code where it does not', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({
      ...emptyMenu(),
      products: [
        {
          productId: 'p-1',
          code: 'BURGER',
          name: 'Burger',
          description: null,
          mediaAssetIds: [],
          imageUrls: [],
          variants: [],
          modifierGroupIds: ['g-sauce'],
        },
      ],
      modifierGroups: [
        {
          modifierGroupId: 'g-sauce',
          code: 'SAUCE',
          name: 'Sauce',
          required: false,
          minimumSelections: 0,
          maximumSelections: 3,
          allowSameOptionMultipleTimes: false,
          options: [
            {
              optionId: 'o-1',
              code: 'MAYO',
              maximumQuantity: 1,
              amountMinor: 0,
              name: 'Mayonnaise',
            },
            { optionId: 'o-2', code: 'BBQ', maximumQuantity: 1, amountMinor: 0 },
          ],
        },
      ],
    } satisfies PublishedMenu);

    const item = await service.item('p-1', 'uz');

    expect(item?.modifierGroups[0].options.map((option) => option.label)).toEqual([
      'Mayonnaise',
      'BBQ',
    ]);
  });

  it('keeps every option’s name by id, including those of groups no product offers (a delivery box the server applies)', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({
      ...emptyMenu(),
      modifierGroups: [
        {
          modifierGroupId: 'g-box',
          code: 'BOX',
          name: 'Box',
          required: false,
          minimumSelections: 0,
          maximumSelections: 1,
          allowSameOptionMultipleTimes: false,
          options: [
            {
              optionId: 'o-box',
              code: 'BOX',
              maximumQuantity: 1,
              amountMinor: 3_000,
              name: 'Delivery box',
            },
            { optionId: 'o-bag', code: 'BAG', maximumQuantity: 1, amountMinor: 0 },
          ],
        },
      ],
    } satisfies PublishedMenu);

    await service.menu('uz');

    expect(service.optionLabels().get('o-box')).toBe('Delivery box');
    expect(service.optionLabels().get('o-bag')).toBe('BAG');
  });
});
