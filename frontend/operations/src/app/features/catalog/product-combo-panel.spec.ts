import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { CatalogApi } from './catalog-api';
import { ProductDetail, VariantDetail } from './catalog-domain';
import { ComboGroupView, CompositeApi } from './composite-api';
import { ProductComboPanel } from './product-combo-panel';
import { PricingApi } from './pricing-api';

const SCOPE = { tenantId: 't1', brandId: 'b1' };

const variant = (id: string, name: string, isDefault: boolean): VariantDetail => ({
  variantId: id,
  sku: id.toUpperCase(),
  unitCode: 'PIECE',
  isDefault,
  sortOrder: isDefault ? 0 : 1,
  status: 'ACTIVE',
  version: 1,
  translations: { en: { name } },
  fiscal: null,
});

function product(variants: readonly VariantDetail[] = [variant('lunch-v', 'Lunch box', true)]) {
  return {
    productId: 'p1',
    code: 'LUNCH',
    status: 'ACTIVE',
    version: 1,
    translations: { en: { name: 'Lunch' } },
    catalogIds: ['c1'],
    categoryIds: [],
    variants,
    modifierGroups: [],
    media: [],
  } satisfies ProductDetail;
}

const GROUP: ComboGroupView = {
  comboGroupId: 'g1',
  containerVariantId: 'lunch-v',
  code: 'MAIN',
  minimumSelections: 1,
  maximumSelections: 1,
  allowSameComponentMultipleTimes: false,
  sortOrder: 0,
  status: 'ACTIVE',
  version: 2,
  names: { en: 'Choose a main' },
  components: [
    {
      componentId: 'c-burger',
      comboGroupId: 'g1',
      componentVariantId: 'burger-v',
      defaultQuantity: 1,
      sortOrder: 0,
      status: 'ACTIVE',
      version: 1,
    },
  ],
};

function render(
  options: {
    detail?: ProductDetail;
    composite?: Record<string, unknown>;
    pricing?: Record<string, unknown>;
  } = {},
) {
  TestBed.resetTestingModule();
  const composite = {
    comboGroupsOf: vi.fn().mockReturnValue(of([GROUP])),
    createComboGroup: vi.fn(),
    ...options.composite,
  };
  const pricing = {
    resolvedComponentPrices: vi
      .fn()
      .mockReturnValue(
        of({ priceBookId: 'book-1', currency: 'UZS', amountsMinor: { 'c-burger': 25000 } }),
      ),
    ...options.pricing,
  };
  TestBed.configureTestingModule({
    providers: [
      { provide: CompositeApi, useValue: composite },
      { provide: PricingApi, useValue: pricing },
      {
        provide: CatalogApi,
        useValue: {
          variantsAtLocation: () =>
            of({
              items: [{ variantId: 'burger-v', productName: 'Burger', available: true }],
              nextCursor: null,
            }),
        },
      },
      { provide: CurrentBrand, useValue: { scope: signal(SCOPE) } },
      {
        provide: CurrentLocation,
        useValue: {
          scope: signal({ ...SCOPE, locationId: 'l1' }),
          ensureLoaded: () => Promise.resolve(),
        },
      },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(ProductComboPanel);
  fixture.componentRef.setInput('product', options.detail ?? product());
  fixture.componentRef.setInput('locale', 'en');
  let saved = 0;
  fixture.componentInstance.saved.subscribe(() => saved++);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const q = (testId: string) => host.querySelector<HTMLElement>(`[data-testid="${testId}"]`);
  const settle = async () => {
    // Zoneless: `whenStable` does not wait for the component's own promises, so let the
    // macrotask queue drain a few times with a render between.
    for (let pass = 0; pass < 3; pass++) {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
      fixture.detectChanges();
    }
  };
  return { fixture, host, q, settle, composite, pricing, saved: () => saved };
}

describe('ProductComboPanel', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('reads the groups of the default variant and shows each as a card with the component named', async () => {
    const { q, settle, composite } = render();
    await settle();

    expect(composite['comboGroupsOf']).toHaveBeenCalledWith(SCOPE, 'lunch-v');
    expect(q('combo-group-title')?.textContent).toContain('Choose a main');
    expect(q('combo-component-name')?.textContent).toContain('Burger');
  });

  it('resolves component prices at the operator’s location and shows them', async () => {
    const { q, settle, pricing } = render();
    await settle();

    expect(pricing['resolvedComponentPrices']).toHaveBeenCalledWith(SCOPE, 'l1', ['c-burger']);
    expect(q('combo-component-price')?.textContent).toContain('25');
    expect(q('combo-component-price')?.textContent).not.toContain('Not priced');
  });

  it('says a variant with no group is not a combo yet, and offers to make it one', async () => {
    const { q, settle } = render({ composite: { comboGroupsOf: vi.fn().mockReturnValue(of([])) } });
    await settle();

    expect(q('combo-empty')?.textContent).toContain('not a combo yet');
    expect(q('combo-create-group')).not.toBeNull();
  });

  it('lets the operator pick which variant is the container when the product has several', async () => {
    const detail = product([
      variant('lunch-v', 'Small', true),
      variant('family-v', 'Family', false),
    ]);
    const comboGroupsOf = vi.fn().mockReturnValue(of([]));
    const { q, settle } = render({ detail, composite: { comboGroupsOf } });
    await settle();

    const select = q('combo-container-select') as HTMLSelectElement;
    expect([...select.options].map((option) => option.textContent?.trim())).toEqual([
      'Small',
      'Family',
    ]);

    select.value = 'family-v';
    select.dispatchEvent(new Event('change'));
    await settle();

    expect(comboGroupsOf).toHaveBeenLastCalledWith(SCOPE, 'family-v');
  });

  it('creates a group on the container with the name in the editing locale, and shows it', async () => {
    const created: ComboGroupView = { ...GROUP, comboGroupId: 'g2', code: 'SIDE', components: [] };
    const createComboGroup = vi.fn().mockReturnValue(of(created));
    const { q, settle, saved } = render({
      composite: { comboGroupsOf: vi.fn().mockReturnValue(of([])), createComboGroup },
    });
    await settle();

    const set = (testId: string, value: string) => {
      (q(testId) as HTMLInputElement).value = value;
    };
    set('combo-new-name', 'Choose a side');
    set('combo-new-code', 'SIDE');
    set('combo-new-minimum', '0');
    set('combo-new-maximum', '2');
    (q('combo-new-repeat') as HTMLInputElement).checked = true;
    (q('combo-create-group') as HTMLButtonElement).click();
    await settle();

    expect(createComboGroup).toHaveBeenCalledWith(SCOPE, {
      containerVariantId: 'lunch-v',
      code: 'SIDE',
      name: 'Choose a side',
      locale: 'en',
      minimumSelections: 0,
      maximumSelections: 2,
      allowSameComponentMultipleTimes: true,
      sortOrder: 0,
    });
    expect(q('combo-group')).not.toBeNull();
    expect(saved()).toBe(1);
  });

  it('does not send a group with no name or code', async () => {
    const createComboGroup = vi.fn();
    const { q, settle } = render({
      composite: { comboGroupsOf: vi.fn().mockReturnValue(of([])), createComboGroup },
    });
    await settle();

    (q('combo-create-group') as HTMLButtonElement).click();
    await settle();

    expect(createComboGroup).not.toHaveBeenCalled();
  });

  it('names the refusal when the variant is already a component of another combo', async () => {
    const refusal = new ApiError(
      ApiErrorCode.INVALID_REQUEST,
      422,
      { status: 422, findingCode: 'COMBO_NESTING_FORBIDDEN', detail: 'nested' },
      null,
    );
    const { q, settle } = render({
      composite: {
        comboGroupsOf: vi.fn().mockReturnValue(of([])),
        createComboGroup: vi.fn().mockReturnValue(throwError(() => refusal)),
      },
    });
    await settle();

    (q('combo-new-name') as HTMLInputElement).value = 'Pick';
    (q('combo-new-code') as HTMLInputElement).value = 'PICK';
    (q('combo-create-group') as HTMLButtonElement).click();
    await settle();

    expect(q('combo-error')?.textContent).toContain('cannot contain another combo');
    expect(q('combo-group')).toBeNull();
  });

  it('says so when the groups cannot be read, rather than showing an empty combo', async () => {
    const { q, settle } = render({
      composite: { comboGroupsOf: vi.fn().mockReturnValue(throwError(() => new Error('down'))) },
    });
    await settle();

    expect(q('combo-load-failed')).not.toBeNull();
    expect(q('combo-empty')).toBeNull();
  });
});
