import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { NEVER, of } from 'rxjs';

import { CartOrderStatusComponent } from './cart-order-status.component';
import { AnalyticsInjector } from '../../../core/analytics/analytics-injector';
import { MenuService } from '../../../services/menu.service';
import { OrdersService, type ApiOrderDetail } from '../../../services/orders.service';
import { TranslateService } from '../../../services/translate.service';

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string): string {
    return key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

function orderDetail(overrides: Partial<ApiOrderDetail> = {}): ApiOrderDetail {
  return {
    id: 'o1',
    order_number: 1001,
    status: { id: 'CONFIRMED', name: 'CONFIRMED' },
    items: [],
    subtotal: { price: 45_000, discount: 0 },
    total: { price: 45_000, discount: 0 },
    ...overrides,
  };
}

async function render(order: ApiOrderDetail): Promise<HTMLElement> {
  TestBed.configureTestingModule({
    imports: [CartOrderStatusComponent],
    providers: [
      provideRouter([]),
      { provide: ActivatedRoute, useValue: { snapshot: { paramMap: { get: () => 'o1' } } } },
      {
        provide: OrdersService,
        useValue: { getOrderDetail: () => of(order), poll: () => NEVER },
      },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: MenuService, useValue: { currency: () => 'UZS' } },
      { provide: AnalyticsInjector, useValue: { ensureLoaded: () => Promise.resolve() } },
    ],
  });
  const fixture = TestBed.createComponent(CartOrderStatusComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

const rowsOf = (host: HTMLElement) =>
  [...host.querySelectorAll('[data-testid="discount-row"]')].map((row) =>
    [...row.querySelectorAll('span')].map((cell) => cell.textContent?.trim()),
  );

describe('CartOrderStatusComponent: the promotions behind the price (ADR 0140)', () => {
  beforeEach(() => sessionStorage.clear());

  it("shows each kind of discount as its own line with the platform's amount", async () => {
    const host = await render(
      orderDetail({
        discount: { price: 6_000, discount: 0 },
        promotions: [
          { source: 'AUTOMATIC', effect: 'DISCOUNT', amountMinor: 4_000 },
          { source: 'PROMO_CODE', effect: 'DISCOUNT', amountMinor: 2_000 },
        ],
      }),
    );

    expect(rowsOf(host).map((row) => row[0])).toEqual(['cart.offerDiscount', 'cart.promoCode']);
    expect(rowsOf(host)[0][1]).toMatch(/^-4.000 /);
    expect(rowsOf(host)[1][1]).toMatch(/^-2.000 /);
  });

  it('keeps a discount the platform did not break down as one generic line', async () => {
    const host = await render(orderDetail({ discount: { price: 5_000, discount: 0 } }));

    expect(rowsOf(host).map((row) => row[0])).toEqual(['cart.discount']);
  });

  it('draws no discount line and no caption for an order that was not discounted', async () => {
    const host = await render(orderDetail());

    expect(host.querySelector('[data-testid="discount-row"]')).toBeNull();
    expect(host.querySelector('[data-testid="promotion-note"]')).toBeNull();
  });

  it('explains a delivery offer as a caption, not as a line to subtract', async () => {
    const host = await render(
      orderDetail({
        promotions: [{ source: 'AUTOMATIC', effect: 'DELIVERY_DISCOUNT', amountMinor: 3_000 }],
      }),
    );

    expect(host.querySelector('[data-testid="promotion-note"]')?.textContent?.trim()).toBe(
      'cart.deliveryOfferNote',
    );
    expect(host.querySelector('[data-testid="discount-row"]')).toBeNull();
  });
});
