import { TestBed } from '@angular/core/testing';
import { Component } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { OrdersComponent } from './orders.component';
import {
  OrdersService,
  type ApiOrder,
  type ApiOrderDetail,
  type ReorderLineResponse,
  type ReorderPlanResponse,
  type ReorderVerdict,
} from '../../services/orders.service';
import { LangService } from '../../services/lang.service';
import { UiCartService } from '../../services/ui-cart.service';
import { TranslateService } from '../../services/translate.service';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string, params?: Record<string, string | number>): string =>
    params ? `${key}(${JSON.stringify(params)})` : key;
  current = (): Record<string, unknown> => ({});
}

@Component({ selector: 'app-test-target', template: '' })
class TestTargetComponent {}

interface OrderOverrides {
  readonly id?: string | number;
  readonly status?: { id: string; name: string };
  readonly total_price?: number;
}

/** `ApiOrder.id` is typed `number` but the real runtime value is a UUID string
 * -- see `OrdersService.toApiOrder`'s own comment -- so this helper (and the
 * component) treat it opaquely, cast past the narrower TS type. */
function order(overrides: OrderOverrides = {}): ApiOrder {
  return {
    id: (overrides.id ?? 'order-1') as unknown as number,
    status: overrides.status ?? { id: 'PREPARING', name: 'PREPARING' },
    total: 25_000,
    total_price: overrides.total_price ?? 25_000,
    order_number: 'PN-1' as unknown as number,
    number: 1,
    created_date: '2026-08-20T10:00:00Z',
    created_time: '2026-08-20T10:00:00Z',
    actions: [],
  };
}

function planLine(overrides: Partial<ReorderLineResponse> = {}): ReorderLineResponse {
  return {
    lineNumber: 1,
    productName: 'Osh',
    variantName: null,
    productId: 'p1',
    variantId: 'v1',
    quantity: 3,
    modifierOptionIds: [],
    status: 'AVAILABLE',
    unitAmountMinor: 25_000,
    originalUnitAmountMinor: 25_000,
    ...overrides,
  };
}

function plan(
  verdict: ReorderVerdict,
  lines: readonly ReorderLineResponse[] = [planLine()],
  orderId = 'r1',
): ReorderPlanResponse {
  return {
    orderId,
    publicOrderNumber: 'PN-1',
    locationId: 'loc-1',
    channelCode: 'STOREFRONT',
    verdict,
    currency: 'UZS',
    lines,
  };
}

class FakeOrdersService {
  getOrders = vi.fn(() => of<ApiOrder[]>([]));
  getOrderDetail = vi.fn(() =>
    of<ApiOrderDetail>({ id: 'order-1', items: [] } as unknown as ApiOrderDetail),
  );
  getReorderPlan = vi.fn(() => of(plan('READY')));
}

class FakeLangService {
  langId = () => 'uz';
}

class FakeUiCartService {
  add = vi.fn(async () => {});
  formatPrice = (value: number) => `${value} so'm`;
}

async function setUp(configure: (orders: FakeOrdersService) => void = () => {}) {
  const orders = new FakeOrdersService();
  configure(orders);

  TestBed.configureTestingModule({
    imports: [OrdersComponent],
    providers: [
      provideRouter([
        { path: 'home', component: TestTargetComponent },
        { path: 'cart', component: TestTargetComponent },
      ]),
      { provide: OrdersService, useValue: orders },
      { provide: LangService, useClass: FakeLangService },
      { provide: UiCartService, useClass: FakeUiCartService },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });

  const fixture = TestBed.createComponent(OrdersComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  return {
    fixture,
    orders,
    cart: TestBed.inject(UiCartService) as unknown as FakeUiCartService,
    router: TestBed.inject(Router),
  };
}

describe('OrdersComponent -- denied/empty/error states', () => {
  it('shows the empty state with a way to place a first order', async () => {
    const { fixture, router } = await setUp();
    const navigateSpy = vi.spyOn(router, 'navigate');

    expect(fixture.nativeElement.textContent).toContain('orders.noOrdersTitle');
    (fixture.nativeElement.querySelector('.btn') as HTMLButtonElement).click();
    expect(navigateSpy).toHaveBeenCalledWith(['/home']);
  });

  it('surfaces a load failure as a translated message, never the raw failure', async () => {
    const { fixture } = await setUp((orders) => {
      orders.getOrders.mockReturnValue(throwError(() => new Error('network')));
    });

    expect(fixture.nativeElement.textContent).toContain('orders.loadError');
  });
});

describe('OrdersComponent -- active order and history', () => {
  it('shows one order as the active card by its real platform status, and never duplicates it into history', async () => {
    const active = order({ id: 'a1', status: { id: 'PREPARING', name: 'PREPARING' } });
    const done = order({ id: 'a2', status: { id: 'COMPLETED', name: 'COMPLETED' } });
    const { fixture } = await setUp((orders) => {
      orders.getOrders.mockReturnValue(of([active, done]));
    });

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('orders.platformStatus.PREPARING');
    expect(fixture.nativeElement.querySelectorAll('.active-card')).toHaveLength(1);
    expect(fixture.nativeElement.querySelectorAll('.order-card')).toHaveLength(1);
  });

  it('never shows the legacy status vocabulary', async () => {
    const { fixture } = await setUp((orders) => {
      orders.getOrders.mockReturnValue(
        of([order({ status: { id: 'CANCELLED', name: 'CANCELLED' } })]),
      );
    });

    const text = fixture.nativeElement.textContent as string;
    expect(text).not.toContain('statusNew');
    expect(text).not.toContain('YANGI');
  });
});

describe("OrdersComponent.repeat -- driven by the platform's plan (ADR 0074)", () => {
  const completed = () => order({ id: 'r1', status: { id: 'COMPLETED', name: 'COMPLETED' } });

  it("rebuilds the order's exact lines -- variant ids and modifiers, not a name match", async () => {
    const { fixture, cart, router } = await setUp((orders) => {
      orders.getOrders.mockReturnValue(of([completed()]));
      orders.getReorderPlan.mockReturnValue(
        of(
          plan('READY', [
            planLine({ lineNumber: 1, variantId: 'v1', quantity: 3, modifierOptionIds: ['o1', 'o2'] }),
            planLine({ lineNumber: 2, variantId: 'v2', quantity: 1 }),
          ]),
        ),
      );
    });
    const navigateSpy = vi.spyOn(router, 'navigate');

    (fixture.nativeElement.querySelector('.repeat-btn') as HTMLButtonElement).click();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));

    // The modifiers travel. The name-matching version could not send them at
    // all, so "osh with extra meat" came back as plain osh.
    expect(cart.add).toHaveBeenCalledWith('v1', 3, undefined, ['o1', 'o2']);
    expect(cart.add).toHaveBeenCalledWith('v2', 1, undefined, []);
    expect(navigateSpy).toHaveBeenCalledWith(['/cart']);
  });

  it('shows no button at all when the plan is PARTIAL', async () => {
    const { fixture, cart } = await setUp((orders) => {
      orders.getOrders.mockReturnValue(of([completed()]));
      orders.getReorderPlan.mockReturnValue(
        of(
          plan('PARTIAL', [
            planLine({ lineNumber: 1, variantId: 'v1' }),
            planLine({ lineNumber: 2, variantId: 'v2', status: 'SOLD_OUT', unitAmountMinor: null }),
          ]),
        ),
      );
    });

    // The owner's rule: a repeat that quietly drops a dish is not a repeat, so
    // PARTIAL hides the button rather than offering a smaller basket.
    expect(fixture.nativeElement.querySelector('.repeat-btn')).toBeNull();
    expect(cart.add).not.toHaveBeenCalled();
  });

  it('shows no button when every line is gone', async () => {
    const { fixture } = await setUp((orders) => {
      orders.getOrders.mockReturnValue(of([completed()]));
      orders.getReorderPlan.mockReturnValue(
        of(plan('UNAVAILABLE', [planLine({ status: 'WITHDRAWN', productId: null, unitAmountMinor: null })])),
      );
    });

    expect(fixture.nativeElement.querySelector('.repeat-btn')).toBeNull();
  });

  it('shows no button when the plan request fails, rather than one that would fail too', async () => {
    const { fixture } = await setUp((orders) => {
      orders.getOrders.mockReturnValue(of([completed()]));
      orders.getReorderPlan.mockReturnValue(throwError(() => new Error('network')));
    });

    expect(fixture.nativeElement.querySelector('.repeat-btn')).toBeNull();
    // A failed plan is not a failed screen: the history still renders.
    expect(fixture.nativeElement.querySelectorAll('.order-card')).toHaveLength(1);
  });

  it('asks for a plan only for the newest past order, never one per history row', async () => {
    const { orders } = await setUp((service) => {
      service.getOrders.mockReturnValue(
        of([
          order({ id: 'r1', status: { id: 'COMPLETED', name: 'COMPLETED' } }),
          order({ id: 'r2', status: { id: 'COMPLETED', name: 'COMPLETED' } }),
          order({ id: 'r3', status: { id: 'COMPLETED', name: 'COMPLETED' } }),
        ]),
      );
    });

    // Fifty history rows must not be fifty requests before the screen settles.
    expect(orders.getReorderPlan).toHaveBeenCalledTimes(1);
    expect(orders.getReorderPlan).toHaveBeenCalledWith('r1');
  });

  it('never offers the button on a row the plan is not about', async () => {
    const { fixture } = await setUp((orders) => {
      orders.getOrders.mockReturnValue(
        of([
          order({ id: 'r1', status: { id: 'COMPLETED', name: 'COMPLETED' } }),
          order({ id: 'r2', status: { id: 'COMPLETED', name: 'COMPLETED' } }),
        ]),
      );
      // A READY plan whose orderId names neither row: the guard is the id, not
      // the position, so a stale plan cannot arm somebody else's button.
      orders.getReorderPlan.mockReturnValue(of(plan('READY', [planLine()], 'someone-else')));
    });

    expect(fixture.nativeElement.querySelectorAll('.repeat-btn')).toHaveLength(0);
  });

  it('never reads the order detail to repeat -- the plan carries everything', async () => {
    const { fixture, orders } = await setUp((service) => {
      service.getOrders.mockReturnValue(of([completed()]));
    });

    (fixture.nativeElement.querySelector('.repeat-btn') as HTMLButtonElement).click();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(orders.getOrderDetail).not.toHaveBeenCalled();
  });
});
