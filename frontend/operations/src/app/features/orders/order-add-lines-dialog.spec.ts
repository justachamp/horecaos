import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ComboboxOption } from '../../shared/ui/combobox';
import { MenuComboComponent, MenuComboGroup } from './new-order/new-order-api';
import { AddLinesSelection, OrderAddLinesDialog } from './order-add-lines-dialog';

const RESULTS: readonly ComboboxOption[] = [
  { id: 'variant-1', label: 'Лагман', sublabel: 'Горячие блюда' },
  { id: 'variant-2', label: 'Плов', sublabel: 'Горячие блюда' },
];

function render(
  options: readonly ComboboxOption[] = [],
  comboGroups: readonly MenuComboGroup[] = [],
): {
  fixture: ReturnType<typeof TestBed.createComponent<OrderAddLinesDialog>>;
} {
  const fixture = TestBed.createComponent(OrderAddLinesDialog);
  fixture.componentRef.setInput('options', options);
  fixture.componentRef.setInput('comboGroups', comboGroups);
  fixture.componentRef.setInput('currency', 'UZS');
  fixture.detectChanges();
  return { fixture };
}

const comboComponent = (id: string, name: string, amountMinor: number): MenuComboComponent => ({
  componentId: id,
  variantId: `${id}-v`,
  productId: null,
  name,
  variantName: null,
  defaultQuantity: 1,
  sortOrder: 0,
  orderable: true,
  amountMinor,
});

/** A combo container `lunch-v`: one main (exactly one) and an optional drink (up to two, repeatable). */
const LUNCH_GROUPS: readonly MenuComboGroup[] = [
  {
    comboGroupId: 'g-main',
    containerVariantId: 'lunch-v',
    code: 'MAIN',
    name: 'Main',
    minimumSelections: 1,
    maximumSelections: 1,
    allowSameComponentMultipleTimes: false,
    sortOrder: 0,
    components: [
      comboComponent('burger', 'Burger', 25_000),
      comboComponent('wrap', 'Wrap', 22_000),
    ],
  },
  {
    comboGroupId: 'g-drink',
    containerVariantId: 'lunch-v',
    code: 'DRINK',
    name: 'Drink',
    minimumSelections: 0,
    maximumSelections: 2,
    allowSameComponentMultipleTimes: true,
    sortOrder: 1,
    components: [comboComponent('cola', 'Cola', 3_000)],
  },
];

const WITH_COMBO: readonly ComboboxOption[] = [
  ...RESULTS,
  { id: 'lunch-v', label: 'Lunch box', sublabel: 'Combos' },
];

function searchInput(host: HTMLElement): HTMLInputElement {
  return host.querySelector('[data-testid="q-combobox-input"]') as HTMLInputElement;
}

describe('OrderAddLinesDialog', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  afterEach(() => vi.useRealTimers());

  it('emits search after the operator stops typing, the same debounce q-combobox already provides', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;
    let searched: string | undefined;
    fixture.componentInstance.search.subscribe((query) => (searched = query));

    const field = searchInput(host);
    field.value = 'Лаг';
    field.dispatchEvent(new Event('input'));
    vi.advanceTimersByTime(250);

    expect(searched).toBe('Лаг');
  });

  it('adds a picked result to the selected list with a default quantity of one', () => {
    const { fixture } = render(RESULTS);
    const host: HTMLElement = fixture.nativeElement;

    const field = searchInput(host);
    field.dispatchEvent(new Event('focus'));
    fixture.detectChanges();
    const firstOption = host.querySelector('[data-testid="q-combobox-option"]') as HTMLLIElement;
    firstOption.click();
    fixture.detectChanges();

    const row = host.querySelector('[data-testid="order-add-lines-dialog-row-variant-1"]');
    expect(row).not.toBeNull();
    expect(
      (
        host.querySelector(
          '[data-testid="order-add-lines-dialog-quantity-variant-1"]',
        ) as HTMLInputElement
      ).value,
    ).toBe('1');
  });

  it('picking the same result again increases its quantity instead of duplicating the row', () => {
    const { fixture } = render(RESULTS);
    const host: HTMLElement = fixture.nativeElement;

    const pickFirst = (): void => {
      searchInput(host).dispatchEvent(new Event('focus'));
      fixture.detectChanges();
      (host.querySelector('[data-testid="q-combobox-option"]') as HTMLLIElement).click();
      fixture.detectChanges();
    };
    pickFirst();
    pickFirst();

    expect(host.querySelectorAll('[data-testid^="order-add-lines-dialog-row-"]')).toHaveLength(1);
    expect(
      (
        host.querySelector(
          '[data-testid="order-add-lines-dialog-quantity-variant-1"]',
        ) as HTMLInputElement
      ).value,
    ).toBe('2');
  });

  it('disables confirm while no line is selected, and emits variantId+quantity pairs (no modifiers)', () => {
    const { fixture } = render(RESULTS);
    const host: HTMLElement = fixture.nativeElement;
    expect(
      (host.querySelector('[data-testid="order-add-lines-dialog-confirm"]') as HTMLButtonElement)
        .disabled,
    ).toBe(true);

    searchInput(host).dispatchEvent(new Event('focus'));
    fixture.detectChanges();
    (host.querySelector('[data-testid="q-combobox-option"]') as HTMLLIElement).click();
    fixture.detectChanges();

    const quantityInput = host.querySelector(
      '[data-testid="order-add-lines-dialog-quantity-variant-1"]',
    ) as HTMLInputElement;
    quantityInput.value = '3';
    quantityInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    let emitted: unknown = null;
    fixture.componentInstance.confirm.subscribe((selection) => (emitted = selection));
    (
      host.querySelector('[data-testid="order-add-lines-dialog-confirm"]') as HTMLButtonElement
    ).click();

    expect(emitted).toEqual([{ variantId: 'variant-1', quantity: 3 }]);
  });

  it('removes a selected line', () => {
    const { fixture } = render(RESULTS);
    const host: HTMLElement = fixture.nativeElement;
    searchInput(host).dispatchEvent(new Event('focus'));
    fixture.detectChanges();
    (host.querySelector('[data-testid="q-combobox-option"]') as HTMLLIElement).click();
    fixture.detectChanges();
    expect(
      host.querySelector('[data-testid="order-add-lines-dialog-row-variant-1"]'),
    ).not.toBeNull();

    (
      host.querySelector(
        '[data-testid="order-add-lines-dialog-remove-variant-1"]',
      ) as HTMLButtonElement
    ).click();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="order-add-lines-dialog-row-variant-1"]')).toBeNull();
  });

  it('emits dismiss', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;
    let dismissed = false;
    fixture.componentInstance.dismiss.subscribe(() => (dismissed = true));

    const dismissButton = [...host.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === 'Dismiss',
    ) as HTMLButtonElement;
    dismissButton.click();

    expect(dismissed).toBe(true);
  });

  describe('combos (ADR 0136)', () => {
    function pick(fixture: ReturnType<typeof render>['fixture'], optionIndex: number): void {
      const host: HTMLElement = fixture.nativeElement;
      searchInput(host).dispatchEvent(new Event('focus'));
      fixture.detectChanges();
      (
        host.querySelectorAll('[data-testid="q-combobox-option"]')[optionIndex] as HTMLLIElement
      ).click();
      fixture.detectChanges();
    }

    function chooseBurger(fixture: ReturnType<typeof render>['fixture']): void {
      const host: HTMLElement = fixture.nativeElement;
      (host.querySelectorAll('[data-testid="combo-dialog-radio"]')[0] as HTMLInputElement).click();
      fixture.detectChanges();
    }

    function confirmCombo(fixture: ReturnType<typeof render>['fixture']): void {
      const host: HTMLElement = fixture.nativeElement;
      (host.querySelector('[data-testid="combo-dialog-confirm"]') as HTMLButtonElement).click();
      fixture.detectChanges();
    }

    it('opens the combo picker for a combo container instead of adding it as a plain line', () => {
      const { fixture } = render(WITH_COMBO, LUNCH_GROUPS);
      const host: HTMLElement = fixture.nativeElement;

      pick(fixture, 2);

      expect(host.querySelector('[data-testid="combo-picker-dialog"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="order-add-lines-dialog-row-lunch-v"]')).toBeNull();
    });

    it('adds the combo with its picks once the minimum is met, and sends the picks with the line', () => {
      const { fixture } = render(WITH_COMBO, LUNCH_GROUPS);
      const host: HTMLElement = fixture.nativeElement;
      let emitted: readonly AddLinesSelection[] | null = null;
      fixture.componentInstance.confirm.subscribe((selection) => (emitted = selection));

      pick(fixture, 2);
      chooseBurger(fixture);
      confirmCombo(fixture);

      expect(host.querySelector('[data-testid="combo-picker-dialog"]')).toBeNull();
      const row = host.querySelector('[data-testid^="order-add-lines-dialog-row-lunch-v"]');
      expect(row?.textContent).toContain('Lunch box');
      expect(row?.textContent).toContain('Burger');

      (
        host.querySelector('[data-testid="order-add-lines-dialog-confirm"]') as HTMLButtonElement
      ).click();
      expect(emitted).toEqual([
        { variantId: 'lunch-v', quantity: 1, comboPicks: [{ componentId: 'burger', quantity: 1 }] },
      ]);
    });

    it('adds nothing when the picker is dismissed or a group is still short', () => {
      const { fixture } = render(WITH_COMBO, LUNCH_GROUPS);
      const host: HTMLElement = fixture.nativeElement;

      pick(fixture, 2);
      confirmCombo(fixture);
      expect(host.querySelector('[data-testid="combo-picker-dialog"]')).not.toBeNull();
      expect(host.querySelector('[data-testid^="order-add-lines-dialog-row-lunch-v"]')).toBeNull();

      (host.querySelector('.combo-dialog__dismiss') as HTMLButtonElement).click();
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="combo-picker-dialog"]')).toBeNull();
      expect(host.querySelector('[data-testid^="order-add-lines-dialog-row-lunch-v"]')).toBeNull();
    });

    it('keeps two different combos as two lines, and counts the same combo twice as two combos', () => {
      const { fixture } = render(WITH_COMBO, LUNCH_GROUPS);
      const host: HTMLElement = fixture.nativeElement;
      let emitted: readonly AddLinesSelection[] | null = null;
      fixture.componentInstance.confirm.subscribe((selection) => (emitted = selection));

      pick(fixture, 2);
      chooseBurger(fixture);
      confirmCombo(fixture);
      pick(fixture, 2);
      chooseBurger(fixture);
      confirmCombo(fixture);
      pick(fixture, 2);
      (host.querySelectorAll('[data-testid="combo-dialog-radio"]')[1] as HTMLInputElement).click();
      fixture.detectChanges();
      confirmCombo(fixture);

      expect(
        host.querySelectorAll('[data-testid^="order-add-lines-dialog-row-lunch-v"]'),
      ).toHaveLength(2);
      (
        host.querySelector('[data-testid="order-add-lines-dialog-confirm"]') as HTMLButtonElement
      ).click();
      expect(emitted).toEqual([
        { variantId: 'lunch-v', quantity: 2, comboPicks: [{ componentId: 'burger', quantity: 1 }] },
        { variantId: 'lunch-v', quantity: 1, comboPicks: [{ componentId: 'wrap', quantity: 1 }] },
      ]);
    });

    it('adds a combo container as a plain line only when the menu has no groups for it', () => {
      const { fixture } = render(WITH_COMBO, []);
      const host: HTMLElement = fixture.nativeElement;

      pick(fixture, 2);

      expect(host.querySelector('[data-testid="combo-picker-dialog"]')).toBeNull();
      expect(
        host.querySelector('[data-testid="order-add-lines-dialog-row-lunch-v"]'),
      ).not.toBeNull();
    });

    it('says combos cannot be offered when the menu could not be read', () => {
      const { fixture } = render(WITH_COMBO, []);
      fixture.componentRef.setInput('combosUnavailable', true);
      fixture.detectChanges();

      expect(
        (fixture.nativeElement as HTMLElement).querySelector(
          '[data-testid="order-add-lines-dialog-combos-unavailable"]',
        ),
      ).not.toBeNull();
    });
  });
});
