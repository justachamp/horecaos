import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { OrderDetailComponent } from './order-detail.component';
import { OrdersService, type ApiOrderDetail } from '../../services/orders.service';
import { NotificationService } from '../../services/notification.service';
import { NavigationHistoryService } from '../../services/navigation-history.service';
import { TranslateService } from '../../services/translate.service';

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

function apiOrderDetail(overrides: Partial<ApiOrderDetail> = {}): ApiOrderDetail {
  return {
    id: 'o1',
    order_number: 1001,
    items: [],
    subtotal: { price: 20000, discount: 0 },
    delivery: { price: 0, discount: 0 },
    packaging: { price: 0, discount: 0 },
    total: { price: 20000, discount: 0 },
    actions: [],
    ...overrides,
  };
}

function setUp(orderId: string | null, detail: ApiOrderDetail) {
  const ordersService = { getOrderDetail: vi.fn(() => of(detail)) };
  TestBed.configureTestingModule({
    imports: [OrderDetailComponent],
    providers: [
      provideRouter([]),
      {
        provide: ActivatedRoute,
        useValue: { snapshot: { paramMap: { get: (key: string) => (key === 'id' ? orderId : null) } } },
      },
      { provide: OrdersService, useValue: ordersService },
      { provide: NotificationService, useValue: { show: vi.fn() } },
      { provide: NavigationHistoryService, useValue: { back: vi.fn() } },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  const fixture = TestBed.createComponent(OrderDetailComponent);
  return { fixture, comp: fixture.componentInstance, ordersService };
}

describe('OrderDetailComponent: delivery fee is never shown as a fabricated zero', () => {
  it('omits the delivery row entirely -- the platform sends no delivery-fee breakdown', async () => {
    // api.delivery is always {price: 0, discount: 0} coming out of
    // OrdersService.toApiOrderDetail, because OrderResponse has no such
    // field. Showing "0 so'm" would tell the customer delivery was free.
    const { fixture, comp } = setUp('o1', apiOrderDetail());

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(comp.order()?.deliveryFee).toBeUndefined();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('cart.delivery');
  });
});

describe('OrderDetailComponent: cancel button reflects the real actions the API sent', () => {
  it('shows cancel when the API marked this order cancellable', async () => {
    const { fixture } = setUp('o1', apiOrderDetail({ actions: ['cancel'] }));

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('orders.cancel');
  });

  it('shows no cancel affordance when the API did not mark this order cancellable', async () => {
    const { fixture } = setUp('o1', apiOrderDetail({ actions: [] }));

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('orders.cancel');
  });
});
