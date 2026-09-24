import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { ComboboxOption } from '../../shared/ui/combobox';
import { OrderAddLinesDialog } from './order-add-lines-dialog';

const RESULTS: readonly ComboboxOption[] = [
  { id: 'variant-1', label: 'Лагман', sublabel: 'Горячие блюда' },
  { id: 'variant-2', label: 'Плов', sublabel: 'Горячие блюда' },
];

function render(options: readonly ComboboxOption[] = []): {
  fixture: ReturnType<typeof TestBed.createComponent<OrderAddLinesDialog>>;
} {
  const fixture = TestBed.createComponent(OrderAddLinesDialog);
  fixture.componentRef.setInput('options', options);
  fixture.detectChanges();
  return { fixture };
}

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
});
