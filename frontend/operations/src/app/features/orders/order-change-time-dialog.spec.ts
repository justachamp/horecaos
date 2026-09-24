import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderChangeTimeDialog } from './order-change-time-dialog';

function render(initialValueIso: string | null = null): {
  fixture: ReturnType<typeof TestBed.createComponent<OrderChangeTimeDialog>>;
} {
  const fixture = TestBed.createComponent(OrderChangeTimeDialog);
  fixture.componentRef.setInput('initialValueIso', initialValueIso);
  fixture.detectChanges();
  return { fixture };
}

describe('OrderChangeTimeDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('seeds the input from the order’s current promisedAt, in the operator’s own local time', () => {
    const iso = '2026-09-23T14:30:00Z';
    const { fixture } = render(iso);
    const input = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="order-change-time-dialog-input"]',
    ) as HTMLInputElement;

    // `datetime-local` is inherently local-time, exactly like
    // `new-order-page.ts`'s own `requestedFor` field — derive the expected
    // string the same way rather than hardcoding one timezone's offset.
    const local = new Date(iso);
    const pad = (n: number): string => String(n).padStart(2, '0');
    const expected =
      `${local.getFullYear()}-${pad(local.getMonth() + 1)}-${pad(local.getDate())}` +
      `T${pad(local.getHours())}:${pad(local.getMinutes())}`;
    expect(input.value).toBe(expected);
  });

  it('starts blank, and disables confirm, when the order has no promise yet', () => {
    const { fixture } = render(null);
    const host: HTMLElement = fixture.nativeElement;

    expect(
      (host.querySelector('[data-testid="order-change-time-dialog-input"]') as HTMLInputElement)
        .value,
    ).toBe('');
    expect(
      (host.querySelector('[data-testid="order-change-time-dialog-confirm"]') as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  });

  it('emits confirm with the chosen time as an ISO instant', () => {
    const { fixture } = render(null);
    const host: HTMLElement = fixture.nativeElement;
    let emitted: string | null = null;
    fixture.componentInstance.confirm.subscribe((iso) => (emitted = iso));

    const input = host.querySelector(
      '[data-testid="order-change-time-dialog-input"]',
    ) as HTMLInputElement;
    input.value = '2026-09-23T18:00';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (
      host.querySelector('[data-testid="order-change-time-dialog-confirm"]') as HTMLButtonElement
    ).click();

    expect(emitted).toBe(new Date('2026-09-23T18:00').toISOString());
  });

  it('emits dismiss', () => {
    const { fixture } = render(null);
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
