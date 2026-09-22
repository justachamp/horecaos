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
  it('omits the delivery row entirely when the order genuinely had no fee', async () => {
    // OrderResponse.feeMinor is 0 for a PICKUP/DINE_IN order (nothing to
    // deliver) or a DELIVERY order with a waived/free fee. Showing "0 so'm"
    // in either case would claim delivery was priced at zero rather than not
    // charged at all, so the row stays hidden exactly like `packaging` below.
    const { fixture, comp } = setUp('o1', apiOrderDetail());

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(comp.order()?.deliveryFee).toBeUndefined();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('cart.delivery');
  });
});

describe('OrderDetailComponent: the placed order reconciles total against subtotal + tax + delivery', () => {
  // OrderResponse (StorefrontOrderingController) now carries feeMinor
  // alongside subtotalMinor/taxMinor/totalMinor -- see JdbcOrderStore's doc
  // comment on the field. Before this, a DELIVERY order's own detail screen
  // showed "To'lov summasi <total>" directly above "Buyurtma <subtotal>"
  // with no line bridging the gap, reproducing on this screen the exact
  // "lines never sum" defect the cart/confirmation pages were fixed for.
  it('shows the delivery-fee and tax lines the backend now sends, so the total is explained', async () => {
    const { fixture, comp } = setUp(
      'o1',
      apiOrderDetail({
        subtotal: { price: 40_179, discount: 0 },
        tax: { price: 4_821, discount: 0 },
        delivery: { price: 25_000, discount: 0 },
        total: { price: 70_000, discount: 0 },
      }),
    );

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(comp.order()?.deliveryFee).toBeDefined();
    expect(comp.order()?.tax).toBeDefined();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('cart.delivery');
    expect(text).toContain('cart.tax');
  });

  it('still omits the tax row when the order reports none', async () => {
    const { fixture, comp } = setUp('o1', apiOrderDetail({ tax: { price: 0, discount: 0 } }));

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(comp.order()?.tax).toBeUndefined();
  });
});

describe('OrderDetailComponent: the header shows the real order number, not "Order N: NaN"', () => {
  it('carries the platform\'s own public order number through as-is, and never coerces it to NaN', async () => {
    // `order_number` is `OrderResponse.publicOrderNumber` -- a string like
    // "0922-001" -- force-cast to `number` by OrdersService.toApiOrderDetail
    // (see ApiOrderDetail's own field note, and OrdersComponent's list,
    // which already reads this the same way). `Number("0922-001")` is NaN;
    // this fixture uses the real string shape rather than a fixture number
    // that would hide exactly that.
    const { fixture, comp } = setUp(
      'o1',
      apiOrderDetail({ order_number: '0922-001' as unknown as number }),
    );

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(comp.order()?.orderNumber).toBe('0922-001');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('NaN');
    // The same translated label the orders list already uses -- never the
    // hardcoded, untranslated "Order N:".
    expect(text).not.toContain('Order N:');
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
    const { fixture, comp } = setUp('o1', apiOrderDetail(), throwError(() => new Error('network')));

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.repeat-btn')).toBeNull();
    // A failed plan is not a failed screen: the order detail still renders.
    expect(comp.order()?.orderNumber).toBe(1001);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('orders.orderNumberLabel');
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
