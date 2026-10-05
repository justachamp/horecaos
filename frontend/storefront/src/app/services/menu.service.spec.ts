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
});

describe('MenuService.item: row 10.12 comment preset wording', () => {
  it('carries a preset’s per-locale wording and its resolved label through to the screens', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({
      ...emptyMenu(),
      products: [
        {
          productId: 'p1',
          code: 'PLOV',
          name: 'Osh',
          description: null,
          mediaAssetIds: [],
          imageUrls: [],
          variants: [],
          modifierGroupIds: [],
          commentPresets: [
            {
              code: 'NO_ONIONS',
              labelRu: 'Без лука',
              labelUz: 'Piyozsiz',
              labelEn: 'No onions',
              labels: { ru: 'Без лука', 'uz-Latn': 'Piyozsiz', en: 'No onions', kaa: 'Piyazsiz' },
              label: 'Piyozsiz',
            },
          ],
        },
      ],
    } satisfies PublishedMenu);

    const item = await service.item('p1', 'uz');

    expect(item?.commentPresets).toEqual([
      {
        code: 'NO_ONIONS',
        labelRu: 'Без лука',
        labelUz: 'Piyozsiz',
        labelEn: 'No onions',
        labels: { ru: 'Без лука', 'uz-Latn': 'Piyozsiz', en: 'No onions', kaa: 'Piyazsiz' },
        label: 'Piyozsiz',
      },
    ]);
  });

  it('still reads a preset from a platform that sends only the triple', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({
      ...emptyMenu(),
      products: [
        {
          productId: 'p1',
          code: 'PLOV',
          name: 'Osh',
          description: null,
          mediaAssetIds: [],
          imageUrls: [],
          variants: [],
          modifierGroupIds: [],
          commentPresets: [
            { code: 'NO_ONIONS', labelRu: 'Без лука', labelUz: 'Piyozsiz', labelEn: 'No onions' },
          ],
        },
      ],
    } satisfies PublishedMenu);

    const item = await service.item('p1', 'uz');

    expect(item?.commentPresets).toEqual([
      { code: 'NO_ONIONS', labelRu: 'Без лука', labelUz: 'Piyozsiz', labelEn: 'No onions' },
    ]);
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
          commentPresets: [],
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
          commentPresets: [],
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
          commentPresets: [],
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
          commentPresets: [],
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
});

describe("MenuService: a portion's own groups and the choices an option opens (ADR 0136)", () => {
  const variant = (id: string, extra: Record<string, unknown> = {}) => ({
    variantId: id,
    sku: null,
    unitCode: id,
    isDefault: false,
    orderable: true,
    onSaleNow: true,
    amountMinor: 10_000,
    remainingQuantity: null,
    ...extra,
  });

  const group = (id: string, extra: Record<string, unknown> = {}) => ({
    modifierGroupId: id,
    code: id.toUpperCase(),
    name: id,
    required: false,
    minimumSelections: 0,
    maximumSelections: 3,
    allowSameOptionMultipleTimes: false,
    options: [
      { optionId: `${id}-a`, code: 'A', maximumQuantity: 1, amountMinor: 0, name: 'A' },
      { optionId: `${id}-b`, code: 'B', maximumQuantity: 1, amountMinor: 500, name: 'B' },
    ],
    ...extra,
  });

  const product = (extra: Record<string, unknown>) => ({
    productId: 'p-fries',
    code: 'FRIES',
    name: 'Fries',
    description: null,
    mediaAssetIds: [],
    imageUrls: [],
    variants: [],
    modifierGroupIds: [],
    commentPresets: [],
    ...extra,
  });

  it("gives a portion that carries groups of its own the whole list it is offered: the product's, then its own", async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({
      ...emptyMenu(),
      products: [
        product({
          modifierGroupIds: ['g-salt'],
          variants: [
            variant('v-small'),
            variant('v-large', {
              modifierGroupIds: ['g-dip'],
              modifierGroupPolicies: [
                {
                  modifierGroupId: 'g-dip',
                  required: true,
                  minimumSelections: 1,
                  maximumSelections: 1,
                },
              ],
            }),
          ],
        }),
      ],
      modifierGroups: [group('g-salt'), group('g-dip')],
    } satisfies PublishedMenu);

    const item = await service.item('p-fries', 'uz');
    const small = item?.variants.find((v) => v.id === 'v-small');
    const large = item?.variants.find((v) => v.id === 'v-large');

    expect(small?.modifierGroups).toBeUndefined();
    expect(item?.modifierGroups.map((g) => g.id)).toEqual(['g-salt']);
    expect(
      large?.modifierGroups?.map((g) => [
        g.id,
        g.required,
        g.minimumSelections,
        g.maximumSelections,
      ]),
    ).toEqual([
      ['g-salt', false, 0, 3],
      ['g-dip', true, 1, 1],
    ]);
  });

  it("lays a portion's row for a group the product also attaches over the product's, whole", async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({
      ...emptyMenu(),
      products: [
        product({
          modifierGroupIds: ['g-salt'],
          modifierGroupPolicies: [
            {
              modifierGroupId: 'g-salt',
              required: true,
              minimumSelections: 1,
              maximumSelections: 1,
            },
          ],
          variants: [
            variant('v-small'),
            variant('v-large', {
              modifierGroupPolicies: [
                {
                  modifierGroupId: 'g-salt',
                  required: false,
                  minimumSelections: 0,
                  maximumSelections: 2,
                },
              ],
            }),
          ],
        }),
      ],
      modifierGroups: [group('g-salt')],
    } satisfies PublishedMenu);

    const item = await service.item('p-fries', 'uz');

    expect(item?.modifierGroups[0]).toMatchObject({ required: true, maximumSelections: 1 });
    expect(item?.variants.find((v) => v.id === 'v-large')?.modifierGroups?.[0]).toMatchObject({
      required: false,
      minimumSelections: 0,
      maximumSelections: 2,
    });
  });

  it('resolves the choices an option opens from the menu’s groups, with the rules published for them, one level deep', async () => {
    const { service, api } = setUp();
    api.get.mockResolvedValue({
      ...emptyMenu(),
      products: [product({ modifierGroupIds: ['g-sauce'], variants: [variant('v-1')] })],
      modifierGroups: [
        group('g-sauce', {
          options: [
            {
              optionId: 'o-chili',
              code: 'CHILI',
              maximumQuantity: 1,
              amountMinor: 0,
              name: 'Chili',
              nestedGroups: [
                {
                  modifierGroupId: 'g-heat',
                  required: true,
                  minimumSelections: 1,
                  maximumSelections: 1,
                },
                {
                  modifierGroupId: 'g-gone',
                  required: false,
                  minimumSelections: 0,
                  maximumSelections: 1,
                },
              ],
            },
            {
              optionId: 'o-garlic',
              code: 'GARLIC',
              maximumQuantity: 1,
              amountMinor: 0,
              name: 'Garlic',
            },
          ],
        }),
        group('g-heat', {
          options: [
            { optionId: 'o-hot', code: 'HOT', maximumQuantity: 1, amountMinor: 0, name: 'Hot' },
            {
              optionId: 'o-mild',
              code: 'MILD',
              maximumQuantity: 1,
              amountMinor: 0,
              name: 'Mild',
              nestedGroups: [
                {
                  modifierGroupId: 'g-sauce',
                  required: false,
                  minimumSelections: 0,
                  maximumSelections: 1,
                },
              ],
            },
          ],
        }),
      ],
    } satisfies PublishedMenu);

    const item = await service.item('p-fries', 'uz');
    const [chili, garlic] = item?.modifierGroups[0].options ?? [];

    expect(garlic.nestedGroups).toBeUndefined();
    expect(chili.nestedGroups?.map((g) => [g.id, g.required, g.minimumSelections])).toEqual([
      ['g-heat', true, 1],
    ]);
    expect(chili.nestedGroups?.[0].options.map((o) => o.label)).toEqual(['Hot', 'Mild']);
    expect(
      chili.nestedGroups?.[0].options.every((o) => o.nestedGroups === undefined),
      'a third level is never drawn',
    ).toBe(true);
  });
});
