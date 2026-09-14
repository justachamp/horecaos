import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderCashTenderedDialog } from './order-cash-tendered-dialog';

/** `setInput`, not a host template — see `order-reason-dialog.spec.ts`'s identical note (zoneless, ADR 0035). */
function render(initialValueMinor = 0): {
  fixture: ReturnType<typeof TestBed.createComponent<OrderCashTenderedDialog>>;
} {
  const fixture = TestBed.createComponent(OrderCashTenderedDialog);
  fixture.componentRef.setInput('initialValueMinor', initialValueMinor);
  fixture.componentRef.setInput('currency', 'UZS');
  fixture.detectChanges();
  return { fixture };
}

describe('OrderCashTenderedDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('starts at the initial amount and emits it unchanged on confirm', () => {
    const { fixture } = render(200_000);
    const host: HTMLElement = fixture.nativeElement;
    const submissions: number[] = [];
    fixture.componentInstance.confirm.subscribe((amountMinor) => submissions.push(amountMinor));

    (
      host.querySelector('[data-testid="order-cash-tendered-dialog-confirm"]') as HTMLButtonElement
    ).click();

    expect(submissions).toEqual([200_000]);
  });

  it('emits the edited amount as a whole-som integer, never a formatted string', () => {
    const { fixture } = render();
    const host: HTMLElement = fixture.nativeElement;
    const submissions: number[] = [];
    fixture.componentInstance.confirm.subscribe((amountMinor) => submissions.push(amountMinor));

    const input = host.querySelector(
      '[data-testid="order-cash-tendered-dialog-amount"] input',
    ) as HTMLInputElement;
    input.value = '150 000';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (
      host.querySelector('[data-testid="order-cash-tendered-dialog-confirm"]') as HTMLButtonElement
    ).click();

    expect(submissions).toEqual([150_000]);
  });

  it('disables both buttons while busy', () => {
    const { fixture } = render();
    fixture.componentRef.setInput('busy', true);
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;

    expect(
      (
        host.querySelector(
          '[data-testid="order-cash-tendered-dialog-confirm"]',
        ) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
  });

  it('emits dismiss when the operator dismisses the dialog', () => {
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
