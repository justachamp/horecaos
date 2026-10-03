import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { I18n } from '../../../core/i18n/i18n';
import { CatalogApi } from '../../catalog/catalog-api';
import { SegmentsApi } from '../../customers/segments/segments-api';
import { DeliveryZonesApi } from '../../delivery/delivery-zones-api';
import { LocationsApi } from '../../settings/locations/locations-api';
import { PaymentMethodsApi } from '../../settings/payment-methods/payment-methods-api';
import { SalesChannelsApi } from '../../settings/sales-channels/sales-channels-api';
import { PromotionReferences } from './promotion-references';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

function build(overrides: { readonly [key: string]: unknown } = {}) {
  const catalog = {
    listCatalogs: vi.fn(() =>
      of([{ catalogId: 'c1', code: 'main', name: 'Main', status: 'ACTIVE' }]),
    ),
    listCategories: vi.fn(() =>
      of([
        { categoryId: 'cat-b', name: 'Burgers', status: 'ACTIVE' },
        { categoryId: 'cat-a', name: 'Appetisers', status: 'ACTIVE' },
        { categoryId: 'cat-x', name: 'Retired', status: 'ARCHIVED' },
      ]),
    ),
    listProducts: vi.fn(() =>
      of({
        items: [
          { productId: 'p-pizza', name: 'Pizza', status: 'ACTIVE' },
          { productId: 'p-cola', name: 'Cola', status: 'ACTIVE' },
        ],
        nextCursor: null,
      }),
    ),
    variantsAtLocation: vi.fn((_scope: BrandScope, locationId: string) =>
      of({
        items:
          locationId === 'loc-1'
            ? [
                { variantId: 'aaaa-0001', productName: 'Pizza', category: 'Mains' },
                { variantId: 'aaaa-0002', productName: 'Pizza', category: 'Mains' },
                { variantId: 'bbbb-0003', productName: 'Cola', category: null },
              ]
            : [{ variantId: 'cccc-0004', productName: 'Lagman', category: null }],
        nextCursor: null,
      }),
    ),
    ...overrides,
  };
  const apis = {
    locations: {
      list: vi.fn().mockResolvedValue([
        { id: 'loc-0', displayName: 'Old branch', status: 'ARCHIVED', timezone: 'Asia/Tashkent' },
        { id: 'loc-1', displayName: 'Chilonzor', status: 'ACTIVE', timezone: 'Asia/Tashkent' },
        { id: 'loc-2', displayName: 'Yunusobod', status: 'ACTIVE', timezone: 'Asia/Tashkent' },
      ]),
    },
    channels: {
      list: vi.fn().mockResolvedValue([{ id: 'ch-1', code: 'WEB', displayName: 'Website' }]),
    },
    payments: {
      list: vi.fn().mockResolvedValue([
        { code: 'CLICK', displayName: 'Click', status: 'ACTIVE' },
        { code: 'OLDPAY', displayName: 'Old pay', status: 'DISABLED' },
      ]),
    },
    zones: {
      list: vi.fn().mockResolvedValue([
        {
          zoneId: 'z1',
          code: 'CENTRE',
          displayNameRu: 'Центр',
          displayNameUz: 'Markaz',
          displayNameEn: 'Centre',
        },
      ]),
    },
    segments: {
      list: vi.fn().mockResolvedValue([{ audienceId: 'aud-1', name: 'Regulars' }]),
    },
  };
  TestBed.configureTestingModule({
    providers: [
      PromotionReferences,
      { provide: CatalogApi, useValue: catalog },
      { provide: LocationsApi, useValue: apis.locations },
      { provide: SalesChannelsApi, useValue: apis.channels },
      { provide: PaymentMethodsApi, useValue: apis.payments },
      { provide: DeliveryZonesApi, useValue: apis.zones },
      { provide: SegmentsApi, useValue: apis.segments },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  return { refs: TestBed.inject(PromotionReferences), catalog, apis };
}

describe('PromotionReferences', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('turns every lookup into chips: codes and ids as the value, a readable label for the operator', async () => {
    const { refs } = build();
    await refs.load(SCOPE);
    const lookups = refs.lookups();
    expect(lookups.channels).toEqual([{ value: 'WEB', label: 'Website (WEB)' }]);
    expect(lookups.locations.map((l) => l.value)).toEqual(['loc-1', 'loc-2']);
    expect(lookups.paymentMethods).toEqual([{ value: 'CLICK', label: 'Click (CLICK)' }]);
    expect(lookups.zones).toEqual([{ value: 'z1', label: 'Centre' }]);
    expect(lookups.segments).toEqual([{ value: 'aud-1', label: 'Regulars' }]);
    // Sorted by label, and a retired category is not offered.
    expect(lookups.categories.map((c) => c.label)).toEqual(['Appetisers', 'Burgers']);
    expect(lookups.products.map((p) => p.label)).toEqual(['Cola', 'Pizza']);
    expect(lookups.channelTypes.map((c) => c.value)).toContain('QR_TABLE');
    expect(lookups.channelTypes.map((c) => c.value)).not.toContain('AGGREGATOR');
    expect(lookups.fulfillmentModes.map((c) => c.value)).toEqual(['DELIVERY', 'PICKUP', 'DINE_IN']);
  });

  it('labels a zone in the operator’s language', async () => {
    const { refs } = build();
    TestBed.inject(I18n).setLocale('ru');
    await refs.load(SCOPE);
    expect(refs.lookups().zones[0].label).toBe('Центр');
  });

  it('defaults the dish lists to the first live branch and reads that branch’s menu', async () => {
    const { refs, catalog } = build();
    await refs.load(SCOPE);
    expect(refs.menuLocationId()).toBe('loc-1');
    expect(catalog.variantsAtLocation).toHaveBeenCalledTimes(1);
    expect((catalog.variantsAtLocation.mock.calls[0] as unknown[])[1]).toBe('loc-1');
  });

  it('tells two variants of one dish apart, and leaves a one-variant dish plain', async () => {
    const { refs } = build();
    await refs.load(SCOPE);
    const labels = refs.lookups().variants.map((v) => v.label);
    expect(labels).toContain('Cola');
    expect(labels).toContain('Pizza (Mains) · #0001');
    expect(labels).toContain('Pizza (Mains) · #0002');
    expect(refs.variantLabel('bbbb-0003')).toBe('Cola');
    expect(refs.variantLabel('unknown-id')).toBe('unknown-id');
  });

  it('re-reads the dishes when another branch is chosen', async () => {
    const { refs } = build();
    await refs.load(SCOPE);
    await refs.loadVariants(SCOPE, 'loc-2');
    expect(refs.menuLocationId()).toBe('loc-2');
    expect(refs.lookups().variants.map((v) => v.label)).toEqual(['Lagman']);
    expect(refs.variantsLoading()).toBe(false);
  });

  it('survives a lookup the principal may not read: that condition has no choices and nothing else is lost', async () => {
    const { refs, apis } = build();
    apis.zones.list.mockRejectedValue(new Error('403'));
    apis.segments.list.mockRejectedValue(new Error('403'));
    await refs.load(SCOPE);
    expect(refs.lookups().zones).toEqual([]);
    expect(refs.lookups().segments).toEqual([]);
    expect(refs.lookups().channels).toHaveLength(1);
    expect(refs.lookups().products).toHaveLength(2);
  });

  it('survives a catalogue it cannot read, and a menu it cannot read', async () => {
    const { refs } = build({
      listCatalogs: vi.fn(() => throwError(() => new Error('403'))),
      variantsAtLocation: vi.fn(() => throwError(() => new Error('403'))),
    });
    await refs.load(SCOPE);
    expect(refs.lookups().products).toEqual([]);
    expect(refs.lookups().variants).toEqual([]);
    expect(refs.lookups().locations).toHaveLength(2);
  });
});
