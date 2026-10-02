import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentBrand } from '../../core/auth/current-brand';
import { CurrentLocation } from '../../core/auth/current-location';
import { I18n } from '../../core/i18n/i18n';
import { Combobox } from '../../shared/ui/combobox';
import { MoneyInput } from '../../shared/ui/money-input';
import { CatalogApi } from './catalog-api';
import { ComboComponentView, ComboGroupView, CompositeApi } from './composite-api';
import { ComboPriceBook, ProductComboGroupCard } from './product-combo-group-card';
import { PricingApi } from './pricing-api';

const SCOPE = { tenantId: 't1', brandId: 'b1' };
const BOOK: ComboPriceBook = { priceBookId: 'book-1', currency: 'UZS' };

const component = (id: string, variantId: string, overrides: Partial<ComboComponentView> = {}) =>
  ({
    componentId: id,
    comboGroupId: 'group-1',
    componentVariantId: variantId,
    defaultQuantity: 1,
    sortOrder: 0,
    status: 'ACTIVE',
    version: 3,
    ...overrides,
  }) satisfies ComboComponentView;

function group(overrides: Partial<ComboGroupView> = {}): ComboGroupView {
  return {
    comboGroupId: 'group-1',
    containerVariantId: 'lunch-variant',
    code: 'MAIN',
    minimumSelections: 1,
    maximumSelections: 2,
    allowSameComponentMultipleTimes: false,
    sortOrder: 0,
    status: 'ACTIVE',
    version: 5,
    names: { ru: 'Выберите основное', en: 'Choose a main' },
    components: [
      component('c-burger', 'burger-v'),
      component('c-cola', 'cola-v', { sortOrder: 1 }),
    ],
    ...overrides,
  };
}

interface Fakes {
  composite: Record<string, ReturnType<typeof vi.fn>>;
  pricing: Record<string, ReturnType<typeof vi.fn>>;
  catalog: Record<string, ReturnType<typeof vi.fn>>;
}

function render(
  overrides: {
    group?: ComboGroupView;
    priceBook?: ComboPriceBook | null;
    prices?: Record<string, number>;
    composite?: Partial<Fakes['composite']>;
    pricing?: Partial<Fakes['pricing']>;
    catalog?: Partial<Fakes['catalog']>;
  } = {},
) {
  TestBed.resetTestingModule();
  const fakes: Fakes = {
    composite: {
      updateComboGroup: vi.fn(),
      updateComponent: vi.fn(),
      addComponent: vi.fn(),
      ...overrides.composite,
    },
    pricing: { setComboComponentPrice: vi.fn().mockReturnValue(of({})), ...overrides.pricing },
    catalog: {
      variantsAtLocation: vi.fn().mockReturnValue(of({ items: [], nextCursor: null })),
      ...overrides.catalog,
    },
  };
  TestBed.configureTestingModule({
    providers: [
      { provide: CompositeApi, useValue: fakes.composite },
      { provide: PricingApi, useValue: fakes.pricing },
      { provide: CatalogApi, useValue: fakes.catalog },
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
  const fixture = TestBed.createComponent(ProductComboGroupCard);
  fixture.componentRef.setInput('group', overrides.group ?? group());
  fixture.componentRef.setInput('locale', 'en');
  fixture.componentRef.setInput('variantNames', { 'burger-v': 'Burger', 'cola-v': 'Cola' });
  fixture.componentRef.setInput(
    'priceBook',
    overrides.priceBook === undefined ? BOOK : overrides.priceBook,
  );
  fixture.componentRef.setInput('prices', overrides.prices ?? { 'c-burger': 0, 'c-cola': 3000 });
  const changed: ComboGroupView[] = [];
  const priced: { componentId: string; amountMinor: number }[] = [];
  fixture.componentInstance.groupChanged.subscribe((next) => changed.push(next));
  fixture.componentInstance.priceChanged.subscribe((next) => priced.push(next));
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const q = (testId: string) => host.querySelector<HTMLElement>(`[data-testid="${testId}"]`);
  const all = (testId: string) => [
    ...host.querySelectorAll<HTMLElement>(`[data-testid="${testId}"]`),
  ];
  const settle = async () => {
    // Zoneless: `whenStable` does not wait for the component's own promises, so let the
    // macrotask queue drain a few times with a render between.
    for (let pass = 0; pass < 3; pass++) {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
      fixture.detectChanges();
    }
  };
  return { fixture, host, q, all, settle, fakes, changed, priced };
}

describe('ProductComboGroupCard', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('shows the heading in the editing locale, its range, and a row per component', () => {
    const { q, all } = render();

    expect(q('combo-group-title')?.textContent).toContain('Choose a main');
    expect(q('combo-group-range')?.textContent).toContain('Pick 1 to 2');
    expect(all('combo-component-name').map((cell) => cell.textContent?.trim())).toEqual([
      'Burger',
      'Cola',
    ]);
  });

  it('prices each component on its own — zero is a price, a missing one is flagged', () => {
    const { all } = render({ prices: { 'c-burger': 0 } });

    const prices = all('combo-component-price').map((cell) => cell.textContent?.trim());
    expect(prices[0]).toMatch(/^0/);
    expect(prices[1]).toBe('Not priced');
    expect(all('combo-component-price')[1].classList.contains('combo-group__price--missing')).toBe(
      true,
    );
  });

  it('warns while editing when the minimum is more than the active components can supply', () => {
    const { q, fixture } = render({
      group: group({ minimumSelections: 3, maximumSelections: 3 }),
    });

    expect(q('combo-group-unsatisfiable')?.textContent).toContain('at least 3');
    expect(q('combo-group-unsatisfiable')?.textContent).toContain('only 2');
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('counts the repeat rule as capacity: one component can fill a group that allows repeats', () => {
    const { q } = render({
      group: group({
        minimumSelections: 3,
        maximumSelections: 3,
        allowSameComponentMultipleTimes: true,
        components: [component('c-burger', 'burger-v')],
      }),
    });

    expect(q('combo-group-unsatisfiable')).toBeNull();
  });

  it('does not count an archived component towards capacity', () => {
    const { q } = render({
      group: group({
        minimumSelections: 2,
        components: [
          component('c-burger', 'burger-v'),
          component('c-cola', 'cola-v', { status: 'ARCHIVED' }),
        ],
      }),
    });

    expect(q('combo-group-unsatisfiable')).not.toBeNull();
  });

  it('saves the range with the version it read as If-Match, and hands the answer up', async () => {
    const answer = group({ minimumSelections: 2, maximumSelections: 2, version: 6 });
    const updateComboGroup = vi.fn().mockReturnValue(of(answer));
    const { q, settle, changed } = render({ composite: { updateComboGroup } });

    const minimum = q('combo-group-minimum') as HTMLInputElement;
    minimum.value = '2';
    const maximum = q('combo-group-maximum') as HTMLInputElement;
    maximum.value = '2';
    (q('combo-group-save') as HTMLButtonElement).click();
    await settle();

    expect(updateComboGroup).toHaveBeenCalledWith(SCOPE, 'group-1', 5, {
      minimumSelections: 2,
      maximumSelections: 2,
      allowSameComponentMultipleTimes: false,
      sortOrder: 0,
      status: 'ACTIVE',
    });
    expect(changed).toEqual([answer]);
  });

  it('saves a component’s quantity and status against the component’s own version', async () => {
    const answer = component('c-cola', 'cola-v', { defaultQuantity: 2, version: 4 });
    const updateComponent = vi.fn().mockReturnValue(of(answer));
    const { all, settle, changed } = render({ composite: { updateComponent } });

    const rows = all('combo-component');
    (rows[1].querySelector('[data-testid="combo-component-quantity"]') as HTMLInputElement).value =
      '2';
    (rows[1].querySelector('[data-testid="combo-component-save"]') as HTMLButtonElement).click();
    await settle();

    expect(updateComponent).toHaveBeenCalledWith(SCOPE, 'c-cola', 3, {
      defaultQuantity: 2,
      sortOrder: 1,
      status: 'ACTIVE',
    });
    expect(changed[0].components[1].defaultQuantity).toBe(2);
    expect(changed[0].components[0].componentId).toBe('c-burger');
  });

  it('writes a component’s price to the price book that resolved, per unit, and reports it', async () => {
    const { fixture, all, settle, fakes, priced } = render();

    const input = fixture.debugElement.queryAll(By.directive(MoneyInput))[1];
    (input.componentInstance as MoneyInput).valueMinorChange.emit(4500);
    await settle();
    (
      all('combo-component')[1].querySelector(
        '[data-testid="combo-component-set-price"]',
      ) as HTMLButtonElement
    ).click();
    await settle();

    expect(fakes.pricing['setComboComponentPrice']).toHaveBeenCalledWith(
      SCOPE,
      'book-1',
      'c-cola',
      4500,
    );
    expect(priced).toEqual([{ componentId: 'c-cola', amountMinor: 4500 }]);
  });

  it('offers no price edit and says why when no price book resolves', () => {
    const { host, q, fixture } = render({ priceBook: null, prices: {} });

    expect(host.querySelector('q-money-input')).toBeNull();
    expect(q('combo-no-price-book')?.textContent).toContain('price book');
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('searches the brand’s variants, leaves out the container and what is already offered, and adds the pick', async () => {
    const variantsAtLocation = vi.fn().mockReturnValue(
      of({
        items: [
          { variantId: 'lunch-variant', productName: 'Lunch box', available: true },
          { variantId: 'cola-v', productName: 'Cola', available: true },
          { variantId: 'fries-v', productName: 'Fries', category: 'Sides', available: true },
        ],
        nextCursor: null,
      }),
    );
    const added = component('c-fries', 'fries-v', { sortOrder: 2, version: 1 });
    const addComponent = vi.fn().mockReturnValue(of(added));
    const { fixture, q, settle, changed } = render({
      catalog: { variantsAtLocation },
      composite: { addComponent },
    });
    const named: { variantId: string; name: string }[] = [];
    fixture.componentInstance.variantNamed.subscribe((value) => named.push(value));

    const combobox = fixture.debugElement.query(By.directive(Combobox))
      .componentInstance as Combobox;
    combobox.search.emit('fri');
    await settle();

    expect(variantsAtLocation).toHaveBeenCalledWith(
      SCOPE,
      'l1',
      expect.objectContaining({ limit: 20 }),
      { search: 'fri' },
    );
    expect(q('combo-add-component')?.hasAttribute('disabled')).toBe(true);

    combobox.optionSelected.emit({ id: 'fries-v', label: 'Fries', sublabel: 'Sides' });
    await settle();
    (q('combo-add-component-quantity') as HTMLInputElement).value = '2';
    (q('combo-add-component') as HTMLButtonElement).click();
    await settle();

    expect(addComponent).toHaveBeenCalledWith(SCOPE, 'group-1', {
      componentVariantId: 'fries-v',
      defaultQuantity: 2,
      sortOrder: 2,
    });
    expect(named).toEqual([{ variantId: 'fries-v', name: 'Fries' }]);
    expect(changed[0].components.map((c) => c.componentId)).toEqual([
      'c-burger',
      'c-cola',
      'c-fries',
    ]);
  });

  it('names the refusal when a combo would contain a combo', async () => {
    const refusal = new ApiError(
      ApiErrorCode.INVALID_REQUEST,
      422,
      {
        status: 422,
        code: 'UNPROCESSABLE_STATE',
        findingCode: 'COMBO_NESTING_FORBIDDEN',
        detail: 'Variant is a combo container',
      },
      null,
    );
    const addComponent = vi.fn().mockReturnValue(throwError(() => refusal));
    const { fixture, q, settle, changed } = render({
      catalog: {
        variantsAtLocation: vi.fn().mockReturnValue(
          of({
            items: [{ variantId: 'x', productName: 'Another combo', available: true }],
            nextCursor: null,
          }),
        ),
      },
      composite: { addComponent },
    });

    const combobox = fixture.debugElement.query(By.directive(Combobox))
      .componentInstance as Combobox;
    combobox.search.emit('another');
    await settle();
    combobox.optionSelected.emit({ id: 'x', label: 'Another combo' });
    await settle();
    (q('combo-add-component') as HTMLButtonElement).click();
    await settle();

    expect(q('combo-group-error')?.textContent).toContain('cannot contain another combo');
    expect(changed).toEqual([]);
  });

  it('does not search for fewer than two characters', async () => {
    const { fixture, settle, fakes } = render();

    const combobox = fixture.debugElement.query(By.directive(Combobox))
      .componentInstance as Combobox;
    combobox.search.emit('f');
    await settle();

    expect(fakes.catalog['variantsAtLocation']).not.toHaveBeenCalled();
  });
});
