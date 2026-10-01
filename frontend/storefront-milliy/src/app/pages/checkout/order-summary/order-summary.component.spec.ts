import { TestBed } from '@angular/core/testing';

import { TranslateService } from '../../../services/translate.service';
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
  hasDiscount: boolean;
  discount: string;
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
    hasDiscount: false,
    discount: '',
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

  it('adds a discount line only when the cart has a discount', () => {
    const { rows } = setUp({ hasDiscount: true, discount: '5 000 so‘m' });

    expect(rows().map((row) => row[0])).toEqual([
      'cart.subtotalLabel',
      'cart.delivery',
      'cart.promoCode',
      'cart.total',
    ]);
    expect(rows()[2][1]).toBe('−5 000 so‘m');
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
