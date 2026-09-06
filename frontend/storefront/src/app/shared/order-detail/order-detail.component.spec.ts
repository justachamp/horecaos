import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { of, throwError, type Observable } from 'rxjs';

import { OrderDetailComponent } from './order-detail.component';
import {
  OrdersService,
  type ApiOrderDetail,
  type ReorderLineResponse,
  type ReorderPlanResponse,
  type ReorderVerdict,
} from '../../services/orders.service';
import { NotificationService } from '../../services/notification.service';
import { NavigationHistoryService } from '../../services/navigation-history.service';
import { TranslateService } from '../../services/translate.service';
import { UiCartService } from '../../services/ui-cart.service';

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

function planLine(overrides: Partial<ReorderLineResponse> = {}): ReorderLineResponse {
  return {
    lineNumber: 1,
    productName: 'Osh',
    variantName: null,
    productId: 'p1',
    variantId: 'v1',
    quantity: 2,
    modifierOptionIds: [],
    status: 'AVAILABLE',
    unitAmountMinor: 25_000,
    originalUnitAmountMinor: 25_000,
    ...overrides,
  };
}

/** `orderId` defaults to 'o1' -- the same id {@link apiOrderDetail} defaults to. */
function plan(
  verdict: ReorderVerdict,
  lines: readonly ReorderLineResponse[] = [planLine()],
  orderId = 'o1',
): ReorderPlanResponse {
  return {
    orderId,
    publicOrderNumber: 'PN-o1',
    locationId: 'loc-1',
    channelCode: 'STOREFRONT',
    verdict,
    currency: 'UZS',
    lines,
  };
}

function setUp(
  orderId: string | null,
  detail: ApiOrderDetail,
  reorderPlan$: Observable<ReorderPlanResponse> = of(plan('READY')),
) {
  const cartAdd = vi.fn(async () => {});
  const ordersService = {
    getOrderDetail: vi.fn(() => of(detail)),
    getReorderPlan: vi.fn(() => reorderPlan$),
  };
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
      { provide: UiCartService, useValue: { add: cartAdd } },
    ],
  });
  const fixture = TestBed.createComponent(OrderDetailComponent);
  return {
    fixture,
    comp: fixture.componentInstance,
    ordersService,
    cartAdd,
    router: TestBed.inject(Router),
  };
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

describe("OrderDetailComponent.repeat -- driven by the platform's plan (ADR 0074)", () => {
  it("shows the repeat button on a READY plan, and rebuilds the order's exact lines -- variant, quantity and every modifier, not a name match", async () => {
    const { fixture, cartAdd, router } = setUp(
      'o1',
      apiOrderDetail(),
      of(
        plan('READY', [
          planLine({ lineNumber: 1, variantId: 'v1', quantity: 3, modifierOptionIds: ['m1', 'm2'] }),
          planLine({ lineNumber: 2, variantId: 'v2', quantity: 1 }),
        ]),
      ),
    );
    const navigateSpy = vi.spyOn(router, 'navigate');

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const button = fixture.nativeElement.querySelector('.repeat-btn') as HTMLButtonElement | null;
    expect(button).not.toBeNull();
    button!.click();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));

    // The modifiers travel. Matching by product name -- what this screen used
    // to have to do before ADR 0074 -- could not send them at all.
    expect(cartAdd).toHaveBeenCalledWith('v1', 3, undefined, ['m1', 'm2']);
    expect(cartAdd).toHaveBeenCalledWith('v2', 1, undefined, []);
    expect(navigateSpy).toHaveBeenCalledWith(['/cart']);
  });

  it('shows no repeat button when the plan is PARTIAL -- a repeat that quietly drops a dish is not a repeat', async () => {
    const { fixture, cartAdd } = setUp(
      'o1',
      apiOrderDetail(),
      of(
        plan('PARTIAL', [
          planLine({ lineNumber: 1, variantId: 'v1' }),
          planLine({ lineNumber: 2, variantId: 'v2', status: 'SOLD_OUT', unitAmountMinor: null }),
        ]),
      ),
    );

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.repeat-btn')).toBeNull();
    expect(cartAdd).not.toHaveBeenCalled();
  });

  it('shows no repeat button when the plan is UNAVAILABLE -- every line is gone', async () => {
    const { fixture } = setUp(
      'o1',
      apiOrderDetail(),
      of(plan('UNAVAILABLE', [planLine({ status: 'WITHDRAWN', productId: null, unitAmountMinor: null })])),
    );

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.repeat-btn')).toBeNull();
  });

  it('shows no repeat button when the plan request fails, rather than one that would fail too', async () => {
    const { fixture } = setUp('o1', apiOrderDetail(), throwError(() => new Error('network')));

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.repeat-btn')).toBeNull();
    // A failed plan is not a failed screen: the order detail still renders.
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('1001');
  });

  it('never offers the button from a plan that answers about a different order', async () => {
    // The guard is the id, not "a plan arrived" -- a stale or mismatched
    // response must not arm a button on an order it is not about.
    const { fixture } = setUp('o1', apiOrderDetail(), of(plan('READY', [planLine()], 'someone-else')));

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.repeat-btn')).toBeNull();
  });
});
