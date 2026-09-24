import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderChangePaymentMethodDialog } from './order-change-payment-method-dialog';

function render(methods: readonly string[]): {
  fixture: ReturnType<typeof TestBed.createComponent<OrderChangePaymentMethodDialog>>;
} {
  const fixture = TestBed.createComponent(OrderChangePaymentMethodDialog);
  fixture.componentRef.setInput('methods', methods);
  fixture.detectChanges();
  return { fixture };
}

describe('OrderChangePaymentMethodDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('renders every method the caller resolved from the channel matrix, as its own raw code', () => {
    const { fixture } = render(['CASH', 'CLICK', 'PAYME']);
    const host: HTMLElement = fixture.nativeElement;

    for (const method of ['CASH', 'CLICK', 'PAYME']) {
      expect(
        host.querySelector(`[data-testid="order-change-payment-method-dialog-option-${method}"]`),
      ).not.toBeNull();
    }
  });

  it('preselects the first method and can confirm without any further click', () => {
    const { fixture } = render(['CASH', 'CLICK']);
    const host: HTMLElement = fixture.nativeElement;
    let emitted: string | null = null;
    fixture.componentInstance.confirm.subscribe((code) => (emitted = code));

    (
      host.querySelector(
        '[data-testid="order-change-payment-method-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();

    expect(emitted).toBe('CASH');
  });

  it('emits confirm with the operator’s chosen method', () => {
    const { fixture } = render(['CASH', 'CLICK', 'PAYME']);
    const host: HTMLElement = fixture.nativeElement;
    let emitted: string | null = null;
    fixture.componentInstance.confirm.subscribe((code) => (emitted = code));

    (
      host.querySelector(
        '[data-testid="order-change-payment-method-dialog-option-PAYME"]',
      ) as HTMLInputElement
    ).click();
    (
      host.querySelector(
        '[data-testid="order-change-payment-method-dialog-confirm"]',
      ) as HTMLButtonElement
    ).click();

    expect(emitted).toBe('PAYME');
  });

  it('shows the empty state and disables confirm when the channel has no enabled method', () => {
    const { fixture } = render([]);
    const host: HTMLElement = fixture.nativeElement;

    expect(
      host.querySelector('[data-testid="order-change-payment-method-dialog-empty"]'),
    ).not.toBeNull();
    expect(
      (
        host.querySelector(
          '[data-testid="order-change-payment-method-dialog-confirm"]',
        ) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
  });

  it('emits dismiss', () => {
    const { fixture } = render(['CASH']);
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
