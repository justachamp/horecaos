import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { OrderAmendmentConfirmDialog } from './order-amendment-confirm-dialog';

function render(inputs: {
  deltaMinor: number;
  currency?: string;
  requiresApproval?: boolean;
  busy?: boolean;
}): { fixture: ReturnType<typeof TestBed.createComponent<OrderAmendmentConfirmDialog>> } {
  const fixture = TestBed.createComponent(OrderAmendmentConfirmDialog);
  fixture.componentRef.setInput('deltaMinor', inputs.deltaMinor);
  fixture.componentRef.setInput('currency', inputs.currency ?? 'UZS');
  fixture.componentRef.setInput('requiresApproval', inputs.requiresApproval ?? false);
  fixture.componentRef.setInput('busy', inputs.busy ?? false);
  fixture.componentRef.setInput('locale', 'en');
  fixture.detectChanges();
  return { fixture };
}

describe('OrderAmendmentConfirmDialog', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({});
    TestBed.inject(I18n).setLocale('en');
  });

  it('renders the increase copy with the formatted delta for a positive delta', () => {
    const { fixture } = render({ deltaMinor: 15_000 });
    const host: HTMLElement = fixture.nativeElement;
    const text = host.querySelector('[data-testid="order-amendment-confirm-delta"]')?.textContent;
    expect(text).toContain('Adds');
    // U+00A0 NO-BREAK SPACE groups thousands (`money.ts`'s own doc) — never a comma.
    expect(text).toContain('15 000');
  });

  it('renders the decrease copy for a negative delta, the absolute amount', () => {
    const { fixture } = render({ deltaMinor: -5_000 });
    const host: HTMLElement = fixture.nativeElement;
    const text = host.querySelector('[data-testid="order-amendment-confirm-delta"]')?.textContent;
    expect(text).toContain('Lowers');
    expect(text).toContain('5 000');
    expect(text).not.toContain('−5 000');
  });

  it('renders the no-change copy for a zero delta', () => {
    const { fixture } = render({ deltaMinor: 0 });
    const host: HTMLElement = fixture.nativeElement;
    expect(
      host.querySelector('[data-testid="order-amendment-confirm-delta"]')?.textContent,
    ).toContain('No change');
  });

  it('shows the approval notice only when requiresApproval is true', () => {
    const { fixture: withApproval } = render({ deltaMinor: 1000, requiresApproval: true });
    expect(
      (withApproval.nativeElement as HTMLElement).querySelector(
        '[data-testid="order-amendment-confirm-approval"]',
      ),
    ).not.toBeNull();

    const { fixture: withoutApproval } = render({ deltaMinor: 1000, requiresApproval: false });
    expect(
      (withoutApproval.nativeElement as HTMLElement).querySelector(
        '[data-testid="order-amendment-confirm-approval"]',
      ),
    ).toBeNull();
  });

  it(
    'shows the confirmation channel picker only for an increase -- ADR 0039 ' +
      "ties the customer's recorded agreement to a raised total, never a decrease",
    () => {
      const { fixture: increase } = render({ deltaMinor: 1000 });
      expect(
        (increase.nativeElement as HTMLElement).querySelector(
          '[data-testid="order-amendment-confirm-channel"]',
        ),
      ).not.toBeNull();

      const { fixture: decrease } = render({ deltaMinor: -1000 });
      expect(
        (decrease.nativeElement as HTMLElement).querySelector(
          '[data-testid="order-amendment-confirm-channel"]',
        ),
      ).toBeNull();

      const { fixture: noChange } = render({ deltaMinor: 0 });
      expect(
        (noChange.nativeElement as HTMLElement).querySelector(
          '[data-testid="order-amendment-confirm-channel"]',
        ),
      ).toBeNull();
    },
  );

  it('emits confirm with the chosen channel', () => {
    const { fixture } = render({ deltaMinor: 1000 });
    const host: HTMLElement = fixture.nativeElement;
    let emitted: string | null = null;
    fixture.componentInstance.confirm.subscribe((channel) => (emitted = channel));

    const select = host.querySelector(
      '[data-testid="order-amendment-confirm-channel"]',
    ) as HTMLSelectElement;
    select.value = 'IN_PERSON';
    select.dispatchEvent(new Event('change'));
    (
      host.querySelector('[data-testid="order-amendment-confirm-submit"]') as HTMLButtonElement
    ).click();

    expect(emitted).toBe('IN_PERSON');
  });

  it('defaults the channel to PHONE', () => {
    const { fixture } = render({ deltaMinor: 1000 });
    const host: HTMLElement = fixture.nativeElement;
    let emitted: string | null = null;
    fixture.componentInstance.confirm.subscribe((channel) => (emitted = channel));

    (
      host.querySelector('[data-testid="order-amendment-confirm-submit"]') as HTMLButtonElement
    ).click();

    expect(emitted).toBe('PHONE');
  });

  it('emits dismiss', () => {
    const { fixture } = render({ deltaMinor: 1000 });
    const host: HTMLElement = fixture.nativeElement;
    let dismissed = false;
    fixture.componentInstance.dismiss.subscribe(() => (dismissed = true));

    const dismissButton = [...host.querySelectorAll('button')].find(
      (b) => b.textContent?.trim() === 'Dismiss',
    ) as HTMLButtonElement;
    dismissButton.click();

    expect(dismissed).toBe(true);
  });

  // ------------------------------------------------------------ ADR 0169: an amendment that removes

  const writtenOff = {
    orderLineId: 'line-1',
    variantId: 'variant-1',
    productName: 'Burger',
    quantityBefore: 2,
    quantityAfter: 1,
    stockUnits: 1,
    kitchenState: 'STARTED',
    declaredDisposition: 'RETURN_TO_STOCK',
    effectiveDisposition: 'WRITE_OFF',
    releasedUnits: 0,
    returnedUnits: 0,
    writtenOffUnits: 1,
    noEffectUnits: 0,
    reasonId: 'reason-1',
    reasonCategory: 'CUSTOMER_CHANGED_MIND',
  } as const;

  it('asks how the customer agreed to a removal even though the total only goes down', () => {
    const { fixture } = render({ deltaMinor: -50_000 });
    fixture.componentRef.setInput('stockConsequence', [writtenOff]);
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelector('[data-testid="order-amendment-confirm-channel"]')).not.toBeNull();
  });

  it('still does not ask about a decrease that removes nothing', () => {
    const { fixture } = render({ deltaMinor: -50_000 });
    const host: HTMLElement = fixture.nativeElement;

    expect(host.querySelector('[data-testid="order-amendment-confirm-channel"]')).toBeNull();
    expect(host.querySelector('[data-testid="order-amendment-confirm-stock"]')).toBeNull();
  });

  it('says what the stock will do before anything is applied, including the kitchen override', () => {
    const { fixture } = render({ deltaMinor: -50_000 });
    fixture.componentRef.setInput('stockConsequence', [writtenOff]);
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;

    const line = host.querySelector('[data-testid="order-amendment-confirm-stock-line-1"]');
    expect(line?.textContent).toContain('Burger');
    expect(line?.textContent).toContain('Written off: 1');
    expect(host.querySelector('[data-testid="order-amendment-confirm-kitchen"]')).not.toBeNull();
  });

  it('reports a returned unit as back on the shelf, with no kitchen note for an untouched line', () => {
    const { fixture } = render({ deltaMinor: -50_000 });
    fixture.componentRef.setInput('stockConsequence', [
      {
        ...writtenOff,
        kitchenState: 'UNKNOWN',
        effectiveDisposition: 'RETURN_TO_STOCK',
        writtenOffUnits: 0,
        returnedUnits: 1,
      },
    ]);
    fixture.detectChanges();
    const host: HTMLElement = fixture.nativeElement;

    const line = host.querySelector('[data-testid="order-amendment-confirm-stock-line-1"]');
    expect(line?.textContent).toContain('Back on the shelf: 1');
    expect(line?.textContent).not.toContain('Written off');
    expect(host.querySelector('[data-testid="order-amendment-confirm-kitchen"]')).toBeNull();
  });
});
