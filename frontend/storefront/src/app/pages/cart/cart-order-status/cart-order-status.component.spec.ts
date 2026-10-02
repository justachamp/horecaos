import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { NEVER, of } from 'rxjs';

import { AnalyticsInjector } from '../../../core/analytics/analytics-injector';
import { LangService } from '../../../services/lang.service';
import { MenuService } from '../../../services/menu.service';
import { OrdersService, type ApiOrderDetail } from '../../../services/orders.service';
import { TranslateService } from '../../../services/translate.service';
import { CartOrderStatusComponent } from './cart-order-status.component';

const NBSP = ' ';

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string, params?: Record<string, string | number>): string {
    return params ? `${key}|${Object.values(params).join('|')}` : key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

function open(detail: ApiOrderDetail) {
  TestBed.configureTestingModule({
    imports: [CartOrderStatusComponent],
    providers: [
      provideRouter([]),
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: { paramMap: { get: (key: string) => (key === 'id' ? 'o1' : null) } },
        },
      },
      {
        provide: OrdersService,
        useValue: { getOrderDetail: vi.fn(() => of(detail)), poll: vi.fn(() => NEVER) },
      },
      { provide: TranslateService, useValue: new FakeTranslateService() },
      { provide: LangService, useValue: { langId: () => 'ru' } },
      { provide: MenuService, useValue: { currency: () => 'UZS' } },
      { provide: AnalyticsInjector, useValue: { ensureLoaded: () => Promise.resolve() } },
    ],
  });
  const fixture = TestBed.createComponent(CartOrderStatusComponent);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  return { fixture, host, text: () => (host.textContent ?? '').replace(/\s+/g, ' ') };
}

function order(items: ApiOrderDetail['items']): ApiOrderDetail {
  return {
    id: 'o1',
    order_number: 1001,
    status: { id: 'CONFIRMED', name: 'CONFIRMED' },
    items,
    subtotal: { price: 20_000, discount: 0 },
    total: { price: 20_000, discount: 0 },
  };
}

const CAKE = {
  quantumGrams: 100,
  nominalGramsPerUnit: 1_200,
  pricePerQuantumMinor: 15_000,
  provisional: true,
  actualWeightGrams: null,
};

describe('CartOrderStatusComponent: portions and weighed lines (ADR 0137)', () => {
  it('writes a half portion with the language’s decimal mark, and its own amount', () => {
    const { text } = open(
      order([{ name: 'Plov', quantity: 0.5, price: 38_000, lineAmount: 19_000 }]),
    );

    expect(text()).toContain('0,5 physical.portionsUnit');
    expect(text()).toContain('19');
  });

  it('writes a whole quantity in pieces and prices it as unit × quantity, as before', () => {
    const { text } = open(order([{ name: 'Cola', quantity: 3, price: 9_000 }]));

    expect(text()).toContain('3 common.itemsUnit');
    expect(text()).toContain('27');
  });

  it('shows a weighed line’s estimated weight and amount, and says the final weight is set at handover', () => {
    const { host, text } = open(
      order([
        { name: 'Medovik', quantity: 1, price: 180_000, lineAmount: 180_000, catchweight: CAKE },
      ]),
    );

    expect(host.querySelector('[data-testid="status-line-weight"]')?.textContent).toContain(
      `1,2${NBSP}кг`,
    );
    expect(text()).toContain('≈ 180');
    expect(host.querySelector('[data-testid="status-final-weight-notice"]')).not.toBeNull();
  });

  it('shows the weighed amount, not unit × quantity, once the line is weighed — and drops the notice', () => {
    const { host, text } = open(
      order([
        {
          name: 'Medovik',
          quantity: 1,
          price: 180_000,
          lineAmount: 201_000,
          catchweight: { ...CAKE, provisional: false, actualWeightGrams: 1_340 },
        },
      ]),
    );

    expect(text()).toContain('201');
    expect(text()).not.toContain('≈');
    expect(host.querySelector('[data-testid="status-final-weight-notice"]')).toBeNull();
  });
});
