import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NEVER, Subject, of } from 'rxjs';

import { ActiveOrderComponent } from './active-order.component';
import { OrdersService, type ApiOrder, type OrdersLoadedEvent } from '../../../services/orders.service';
import { NotificationService } from '../../../services/notification.service';
import { TranslateService } from '../../../services/translate.service';
import { ORDER_STATUS_I18N_KEY } from '../orders.data';

/**
 * Echoes the requested key back, exactly like the other component specs'
 * fake translate services (see e.g. home.component.spec.ts). This is what
 * makes the regression provable: `getStatusLabel('CONFIRMED')` renders
 * whatever key `ActiveOrderComponent` actually asked for. Under the bug this
 * suite guards against, that key was the non-existent
 * `orders.statusCONFIRMED` -- which this fake would echo right back onto the
 * screen, exactly as the real `TranslateService` did in production.
 */
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
  poll = vi.fn(() => NEVER);
  cancelOrder = vi.fn(() => of({}));
}

function apiOrder(overrides: Partial<ApiOrder> = {}): ApiOrder {
  return {
    id: 1,
    status: { id: 'RECEIVED', name: 'RECEIVED' },
    total: 25000,
    order_number: 1001,
    ...overrides,
  };
}

function setUp() {
  const ordersService = new FakeOrdersService();
  TestBed.configureTestingModule({
    imports: [ActiveOrderComponent],
    providers: [
      provideRouter([]),
      { provide: OrdersService, useValue: ordersService },
      { provide: NotificationService, useValue: { show: vi.fn() } },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  const fixture = TestBed.createComponent(ActiveOrderComponent);
  return { fixture, comp: fixture.componentInstance, ordersService };
}

describe('ActiveOrderComponent: platform status vocabulary', () => {
  // One order per real ordering.domain.OrderStatus value a customer can see.
  const ALL_PLATFORM_STATUSES = [
    'RECEIVED',
    'PAYMENT_AUTHORIZING',
    'AWAITING_APPROVAL',
    'CONFIRMED',
    'PREPARING',
    'READY',
    'FULFILLING',
    'COMPLETED',
    'CANCELLED',
    'REJECTED',
    'EXPIRED',
    'PAYMENT_FAILED',
  ];

  it.each(ALL_PLATFORM_STATUSES)(
    'renders a real i18n key for platform status %s, never a legacy-shaped one',
    (status) => {
      const { comp } = setUp();

      const label = comp.getStatusLabel(status);

      // The regression this guards: the old mapping ran every unrecognised
      // status through `UI_TO_API_STATUS`/`API_STATUS_TO_I18N_KEY`, missed,
      // and fell back to `orders.status${Capitalized}` -- a key that does not
      // exist in en/ru/uz.json. `orders.statusCONFIRMED` is the exact string
      // that reached the screen for a CONFIRMED order in production.
      expect(label).not.toBe(`orders.status${status.charAt(0).toUpperCase() + status.slice(1)}`);
      expect(label).not.toContain('orders.status' + status);
      expect(label).toBe(ORDER_STATUS_I18N_KEY[status]);
      expect(label).toBe(`orders.platformStatus.${status}`);
    },
  );

  it('reproduces the exact proven defect: a CONFIRMED order never shows "orders.statusCONFIRMED" on screen', async () => {
    const { fixture, ordersService } = setUp();
    ordersService.ordersToReturn = [apiOrder({ status: { id: 'CONFIRMED', name: 'CONFIRMED' } })];

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('orders.statusCONFIRMED');
    expect(text).toContain('orders.platformStatus.CONFIRMED');
  });

  it('an order with no status at all renders blank, never a fabricated default', () => {
    const { comp } = setUp();

    expect(comp.getStatusLabel('')).toBe('');
  });

  it('falls back to the raw status rather than an untranslated key path, for a status this build does not recognise', () => {
    const { comp } = setUp();

    expect(comp.getStatusLabel('SOME_FUTURE_STATUS')).toBe('SOME_FUTURE_STATUS');
  });
});

describe('ActiveOrderComponent: order card item count and distance', () => {
  it('never shows a fabricated "0 ta" item count when the API reports none -- the proven "0 ta | km" defect', async () => {
    const { fixture, comp, ordersService } = setUp();
    ordersService.ordersToReturn = [
      apiOrder({
        items_count: undefined,
        delivery_distance: undefined,
        created_date: '2026-09-01T10:00:00Z',
      }),
    ];

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(comp.orders()[0].itemCount).toBe('');
    expect(comp.orders()[0].distanceKm).toBe('');
    // Under the old code, OrdersService.toApiOrder hardcoded items_count to 0
    // (not undefined), which this same FakeTranslateService would have
    // rendered as exactly "0 common.itemsUnit" here -- this is the DOM-level
    // proof the "0 ta" defect is gone, not just that the field is unset.
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('0 common.itemsUnit');
    expect(text).not.toContain('0 ta');
  });

  it('falls back to the order\'s placed-at date when there is no item count or distance to show', async () => {
    const { fixture, comp, ordersService } = setUp();
    ordersService.ordersToReturn = [apiOrder({ created_date: '2026-09-01T10:00:00Z' })];

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(comp.orders()[0].date).not.toBe('');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain(comp.orders()[0].date);
  });

  it('shows a real item count when the API actually reports one, rather than suppressing every count', async () => {
    const { fixture, comp, ordersService } = setUp();
    ordersService.ordersToReturn = [apiOrder({ items_count: 3 })];

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    // FakeTranslateService echoes the requested key rather than resolving a
    // real string, so the unit here is the key itself -- what matters is
    // that a real reported count is shown at all, unlike the fabricated
    // "0 ta" the old mapping produced for every order.
    expect(comp.orders()[0].itemCount).toBe('3 common.itemsUnit');
  });
});

describe('ActiveOrderComponent: cancel action reflects real cancellability', () => {
  it('offers cancel for an order the API marked cancellable', async () => {
    const { fixture, ordersService } = setUp();
    ordersService.ordersToReturn = [apiOrder({ actions: ['cancel'] })];

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const button = (fixture.nativeElement as HTMLElement).querySelector('button');
    expect(button).not.toBeNull();
  });

  it('offers no cancel button for an order the API did not mark cancellable', async () => {
    const { fixture, ordersService } = setUp();
    ordersService.ordersToReturn = [apiOrder({ actions: [] })];

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const button = (fixture.nativeElement as HTMLElement).querySelector('button');
    expect(button).toBeNull();
  });
});
