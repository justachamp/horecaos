import { TestBed } from '@angular/core/testing';

import type { PaymentSplit, PaymentTender } from '../../services/points.service';
import { TranslateService } from '../../services/translate.service';
import { PaymentSplitComponent } from './payment-split.component';

class FakeTranslateService {
  get(key: string): string {
    return key === 'common.currency' ? "so'm" : key;
  }
  getWithParams(key: string): string {
    return key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

const tender = (overrides: Partial<PaymentTender>): PaymentTender => ({
  kind: 'MONEY',
  methodCode: 'CASH',
  amount: { amountMinor: 10_000, currency: 'UZS' },
  status: 'PLANNED',
  ...overrides,
});

function render(split: PaymentSplit | null) {
  TestBed.configureTestingModule({
    providers: [{ provide: TranslateService, useClass: FakeTranslateService }],
  });
  const fixture = TestBed.createComponent(PaymentSplitComponent);
  fixture.componentRef.setInput('split', split);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return {
    host,
    rows: () =>
      [...host.querySelectorAll('[data-testid="payment-split-row"]')].map((row) =>
        (row.textContent ?? '').replace(/\s+/g, ' ').trim(),
      ),
  };
}

const split = (...tenders: PaymentTender[]): PaymentSplit => ({
  orderId: 'o1',
  total: { amountMinor: 20_000, currency: 'UZS' },
  tenders,
});

describe('PaymentSplitComponent', () => {
  it('lists the points and the money in the order given, each with its status', () => {
    const { rows } = render(
      split(
        tender({ kind: 'POINTS', methodCode: null, status: 'SETTLED' }),
        tender({ methodCode: 'PAYME', status: 'PLANNED' }),
      ),
    );

    expect(rows()).toHaveLength(2);
    expect(rows()[0]).toContain('split.points');
    expect(rows()[0]).toContain('split.status.SETTLED');
    expect(rows()[1]).toContain('cart.payme');
    expect(rows()[1]).toContain('split.status.PLANNED');
  });

  it('writes an amount with the grouping and the unit the customer reads elsewhere', () => {
    const { rows } = render(
      split(
        tender({
          kind: 'POINTS',
          methodCode: null,
          amount: { amountMinor: 84_000, currency: 'UZS' },
        }),
      ),
    );

    expect(rows()[0]).toContain("84 000 so'm");
  });

  it('shows the points even when they pay for all of what the page lists as one tender', () => {
    const { host } = render(split(tender({ kind: 'POINTS', methodCode: null })));

    expect(host.querySelector('[data-testid="payment-split"]')).not.toBeNull();
  });

  it('shows two money tenders: more than one way is a split', () => {
    const { rows } = render(split(tender({ methodCode: 'CASH' }), tender({ methodCode: 'CLICK' })));

    expect(rows()).toHaveLength(2);
  });

  it('says nothing for a single money tender: the total already says it', () => {
    const { host } = render(split(tender({ methodCode: 'CASH' })));

    expect(host.querySelector('[data-testid="payment-split"]')).toBeNull();
  });

  it('names a method this storefront has no wording for by its code rather than hiding it', () => {
    const { rows } = render(
      split(tender({ kind: 'POINTS', methodCode: null }), tender({ methodCode: 'ATMOS' })),
    );

    expect(rows()[1]).toContain('ATMOS');
  });

  it('draws nothing without a split', () => {
    const { host } = render(null);

    expect(host.querySelector('[data-testid="payment-split"]')).toBeNull();
  });
});
