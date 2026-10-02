import { TestBed } from '@angular/core/testing';

import { TranslateService } from '../../../services/translate.service';
import type { PromotionNote, PromotionRow } from '../../../services/ui-cart.service';
import { OrderSummaryComponent } from './order-summary.component';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string): string => key;
  current = (): Record<string, unknown> => ({});
}

interface Inputs {
  subtotal: string;
  deliveryFee: string;
  unresolvedMessage: string | null;
  discountRows: readonly PromotionRow[];
  notes: readonly PromotionNote[];
  total: string;
}

function setUp(overrides: Partial<Inputs> = {}) {
  TestBed.configureTestingModule({
    providers: [{ provide: TranslateService, useClass: FakeTranslateService }],
  });
  const fixture = TestBed.createComponent(OrderSummaryComponent);
  const inputs: Inputs = {
    subtotal: '25 000 so‘m',
    deliveryFee: '7 000 so‘m',
    unresolvedMessage: null,
    discountRows: [],
    notes: [],
    total: '32 000 so‘m',
    ...overrides,
  };
  for (const [name, value] of Object.entries(inputs)) {
    fixture.componentRef.setInput(name, value);
  }
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const rows = () =>
    [...host.querySelectorAll('.summary__row')].map((row) =>
      [...row.querySelectorAll('span')].map((cell) => cell.textContent?.trim()),
    );
  return { host, rows };
}

describe('OrderSummaryComponent', () => {
  it('lists the subtotal, the delivery fee and the total', () => {
    const { rows } = setUp();

    expect(rows()).toEqual([
      ['cart.subtotalLabel', '25 000 so‘m'],
      ['cart.delivery', '7 000 so‘m'],
      ['cart.total', '32 000 so‘m'],
    ]);
  });

  it("adds one line per discount, each with the platform's amount and the code only on the code line", () => {
    const { rows } = setUp({
      discountRows: [
        { labelKey: 'cart.offerDiscount', code: null, amount: '4 000 so‘m' },
        { labelKey: 'cart.promoCode', code: 'OSH2026', amount: '1 000 so‘m' },
      ],
    });

    expect(rows().map((row) => row[0])).toEqual([
      'cart.subtotalLabel',
      'cart.delivery',
      'cart.offerDiscount',
      'cart.promoCode OSH2026',
      'cart.total',
    ]);
    expect(rows()[2][1]).toBe('−4 000 so‘m');
    expect(rows()[3][1]).toBe('−1 000 so‘m');
  });

  it('adds no discount line when nothing was discounted', () => {
    const { host } = setUp();

    expect(host.querySelector('[data-testid="discount-row"]')).toBeNull();
  });

  it('explains a delivery offer or a surcharge as a caption, not as a line to add', () => {
    const { host, rows } = setUp({
      notes: [{ labelKey: 'cart.deliveryOfferNote', amount: '5 000 so‘m' }],
    });

    expect(host.querySelector('[data-testid="promotion-note"]')?.textContent?.trim()).toBe(
      'cart.deliveryOfferNote',
    );
    expect(rows()).toHaveLength(3);
  });

  it('says why the delivery fee is a dash when the platform could not resolve it', () => {
    const { host } = setUp({ deliveryFee: '—', unresolvedMessage: 'No courier zone here.' });

    expect(host.querySelector('[data-testid="delivery-unresolved"]')?.textContent?.trim()).toBe(
      'No courier zone here.',
    );
  });

  it('draws no note when the delivery fee resolved', () => {
    const { host } = setUp();

    expect(host.querySelector('[data-testid="delivery-unresolved"]')).toBeNull();
  });
});
