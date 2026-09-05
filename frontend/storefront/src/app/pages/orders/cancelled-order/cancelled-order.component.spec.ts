import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Subject, of } from 'rxjs';

import { CancelledOrderComponent } from './cancelled-order.component';
import { OrdersService, type ApiOrder, type OrdersLoadedEvent } from '../../../services/orders.service';
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

class FakeOrdersService {
  readonly onOrdersLoaded = new Subject<OrdersLoadedEvent>();
  ordersToReturn: ApiOrder[] = [];
  getOrders = vi.fn(() => of(this.ordersToReturn));
}

function apiOrder(overrides: Partial<ApiOrder> = {}): ApiOrder {
  return {
    id: 1,
    status: { id: 'CANCELLED', name: 'CANCELLED' },
    total: 25000,
    order_number: 1001,
    ...overrides,
  };
}

function setUp() {
  const ordersService = new FakeOrdersService();
  TestBed.configureTestingModule({
    imports: [CancelledOrderComponent],
    providers: [
      provideRouter([]),
      { provide: OrdersService, useValue: ordersService },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  const fixture = TestBed.createComponent(CancelledOrderComponent);
  return { fixture, comp: fixture.componentInstance, ordersService };
}

describe('CancelledOrderComponent: never fabricates an item count or distance', () => {
  it('leaves the subtitle honest -- no "0 ta", falls back to the placed-at date', async () => {
    const { fixture, comp, ordersService } = setUp();
    ordersService.ordersToReturn = [
      apiOrder({ items_count: undefined, delivery_distance: undefined, created_date: '2026-09-01T10:00:00Z' }),
    ];

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(comp.orders()[0].itemCount).toBe('');
    expect(comp.orders()[0].distanceKm).toBe('');
    expect(comp.orders()[0].date).not.toBe('');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('0 common.itemsUnit');
    expect(text).not.toContain('0 ta');
  });
});
