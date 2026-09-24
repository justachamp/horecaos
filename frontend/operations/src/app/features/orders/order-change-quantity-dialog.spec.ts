import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderLine } from './order-detail';
import { OrderChangeQuantityDialog } from './order-change-quantity-dialog';

function line(overrides: Partial<OrderLine> = {}): OrderLine {
  return {
    lineNumber: 1,
    productName: 'Лагман',
    quantity: 2,
    finalAmountMinor: 40_000,
    modifiers: [],
    lineId: 'line-1',
    hasNote: false,
    ...overrides,
  };
}

function render(lines: readonly OrderLine[]): {
  fixture: ReturnType<typeof TestBed.createComponent<OrderChangeQuantityDialog>>;
} {
  const fixture = TestBed.createComponent(OrderChangeQuantityDialog);
  fixture.componentRef.setInput('lines', lines);
  fixture.detectChanges();
  return { fixture };
}

describe('OrderChangeQuantityDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('preselects the first line and defaults the quantity to one above its current one', () => {
    const { fixture } = render([line({ lineId: 'line-1', quantity: 2 })]);
    const host: HTMLElement = fixture.nativeElement;

    expect(
      (
        host.querySelector(
          '[data-testid="order-change-quantity-dialog-quantity"]',
        ) as HTMLInputElement
      ).value,
    ).toBe('3');
  });

  it('re-seeds the minimum and default quantity when the operator picks a different line', () => {
    const { fixture } = render([
      line({ lineId: 'line-1', quantity: 2 }),
      line({ lineId: 'line-2', productName: 'Плов', quantity: 5 }),
    ]);
    const host: HTMLElement = fixture.nativeElement;

    const select = host.querySelector(
      '[data-testid="order-change-quantity-dialog-line"]',
    ) as HTMLSelectElement;
    select.value = 'line-2';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(
      (
        host.querySelector(
          '[data-testid="order-change-quantity-dialog-quantity"]',
        ) as HTMLInputElement
      ).value,
    ).toBe('6');
  });

  it('disables confirm for a quantity at or below the current one', () => {
    const { fixture } = render([line({ lineId: 'line-1', quantity: 2 })]);
    const host: HTMLElement = fixture.nativeElement;
    const quantityInput = host.querySelector(
      '[data-testid="order-change-quantity-dialog-quantity"]',
    ) as HTMLInputElement;
    const confirmButton = host.querySelector(
      '[data-testid="order-change-quantity-dialog-confirm"]',
    ) as HTMLButtonElement;

    quantityInput.value = '2';
    quantityInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(confirmButton.disabled).toBe(true);

    quantityInput.value = '4';
    quantityInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(confirmButton.disabled).toBe(false);
  });

  it('emits confirm with the selected line and quantity', () => {
    const { fixture } = render([line({ lineId: 'line-1', quantity: 2 })]);
    const host: HTMLElement = fixture.nativeElement;
    let emitted: { orderLineId: string; quantity: number } | null = null;
    fixture.componentInstance.confirm.subscribe((submission) => (emitted = submission));

    const quantityInput = host.querySelector(
      '[data-testid="order-change-quantity-dialog-quantity"]',
    ) as HTMLInputElement;
    quantityInput.value = '5';
    quantityInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (
      host.querySelector(
        '[data-testid="order-change-quantity-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();

    expect(emitted).toEqual({ orderLineId: 'line-1', quantity: 5 });
  });

  it('emits dismiss', () => {
    const { fixture } = render([line()]);
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
