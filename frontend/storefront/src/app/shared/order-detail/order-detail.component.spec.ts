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
import {
  LocationProfileService,
  type LocationProfile,
} from '../../services/location-profile.service';

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

function branch(overrides: Partial<LocationProfile> = {}): LocationProfile {
  return {
    displayName: 'Central kitchen',
    addressLine: '1 Demo Street',
    district: 'Shaykhontohur',
    city: 'Tashkent',
    ...overrides,
  };
}

function setUp(
  orderId: string | null,
  detail: ApiOrderDetail,
  reorderPlan$: Observable<ReorderPlanResponse> = of(plan('READY')),
  locationProfile: LocationProfile | null = null,
) {
  const cartAdd = vi.fn(async () => {});
  const ordersService = {
    getOrderDetail: vi.fn(() => of(detail)),
    getReorderPlan: vi.fn(() => reorderPlan$),
  };
  const locationProfileService = {
    profile: vi.fn(() => Promise.resolve(locationProfile)),
  };
  TestBed.configureTestingModule({
    imports: [OrderDetailComponent],
    providers: [
      provideRouter([]),
      {
        provide: ActivatedRoute,
        useValue: {
          snapshot: { paramMap: { get: (key: string) => (key === 'id' ? orderId : null) } },
        },
      },
      { provide: OrdersService, useValue: ordersService },
      { provide: NotificationService, useValue: { show: vi.fn() } },
      { provide: NavigationHistoryService, useValue: { back: vi.fn() } },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: UiCartService, useValue: { add: cartAdd } },
      { provide: LocationProfileService, useValue: locationProfileService },
    ],
  });
  const fixture = TestBed.createComponent(OrderDetailComponent);
  return {
    fixture,
    comp: fixture.componentInstance,
    ordersService,
    cartAdd,
    locationProfileService,
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

describe('OrderDetailComponent: the promotions behind the price (ADR 0140)', () => {
  async function render(overrides: Partial<ApiOrderDetail>) {
    const { fixture, comp } = setUp('o1', apiOrderDetail(overrides));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { host: fixture.nativeElement as HTMLElement, comp };
  }

  const rowsOf = (host: HTMLElement) =>
    [...host.querySelectorAll('[data-testid="discount-row"]')].map((row) =>
      [...row.querySelectorAll('span')].map((cell) => cell.textContent?.trim()),
    );

  it('shows each kind of discount as its own line, so subtotal + tax + delivery - discount is the total', async () => {
    const { host } = await render({
      subtotal: { price: 45_000, discount: 0 },
      discount: { price: 9_000, discount: 0 },
      total: { price: 36_000, discount: 0 },
      promotions: [{ source: 'AUTOMATIC', effect: 'DISCOUNT', amountMinor: 9_000 }],
    });

    expect(rowsOf(host)).toEqual([['cart.offerDiscount', expect.stringMatching(/^-9.000 /)]]);
  });

  it("labels the customer's typed code as a code, apart from an offer", async () => {
    const { host } = await render({
      discount: { price: 6_000, discount: 0 },
      promotions: [
        { source: 'AUTOMATIC', effect: 'DISCOUNT', amountMinor: 4_000 },
        { source: 'PROMO_CODE', effect: 'DISCOUNT', amountMinor: 2_000 },
      ],
    });

    expect(rowsOf(host).map((row) => row[0])).toEqual(['cart.offerDiscount', 'cart.promoCode']);
  });

  it('keeps a discount the platform did not break down as one generic line', async () => {
    const { host } = await render({ discount: { price: 5_000, discount: 0 } });

    expect(rowsOf(host).map((row) => row[0])).toEqual(['cart.discount']);
  });

  it('draws nothing for an order that was not discounted', async () => {
    const { host } = await render({});

    expect(host.querySelector('[data-testid="discount-row"]')).toBeNull();
    expect(host.querySelector('[data-testid="promotion-note"]')).toBeNull();
  });

  it('explains a delivery offer as a caption beside the delivery fee, not as a line to subtract', async () => {
    const { host } = await render({
      delivery: { price: 7_000, discount: 0 },
      promotions: [{ source: 'AUTOMATIC', effect: 'DELIVERY_DISCOUNT', amountMinor: 3_000 }],
    });

    expect(host.querySelector('[data-testid="promotion-note"]')?.textContent?.trim()).toBe(
      'cart.deliveryOfferNote',
    );
    expect(host.querySelector('[data-testid="discount-row"]')).toBeNull();
  });
});

describe('OrderDetailComponent: names the pickup branch (2026-09-21 audit follow-up (d))', () => {
  it("asks for and shows the branch's name and address on a PICKUP order", async () => {
    const { fixture, comp, locationProfileService } = setUp(
      'o1',
      apiOrderDetail({ fulfillmentMode: 'PICKUP', locationId: 'loc-1' }),
      of(plan('READY')),
      branch({
        displayName: 'Central kitchen',
        addressLine: '1 Demo Street',
        district: 'Shaykhontohur',
        city: 'Tashkent',
      }),
    );

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(locationProfileService.profile).toHaveBeenCalledWith('loc-1');
    expect(comp.pickupBranch()?.displayName).toBe('Central kitchen');
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Central kitchen');
    expect(text).toContain('1 Demo Street');
  });

  it('never asks for a branch on a DELIVERY order', async () => {
    const { fixture, locationProfileService } = setUp(
      'o1',
      apiOrderDetail({ fulfillmentMode: 'DELIVERY', locationId: 'loc-1' }),
    );

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(locationProfileService.profile).not.toHaveBeenCalled();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('orders.pickupBranch');
  });

  it('shows nothing extra when the branch cannot be resolved, rather than a placeholder', async () => {
    const { fixture, comp } = setUp(
      'o1',
      apiOrderDetail({ fulfillmentMode: 'PICKUP', locationId: 'loc-1' }),
      of(plan('READY')),
      null,
    );

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(comp.pickupBranch()).toBeNull();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('orders.pickupBranch');
  });
});

describe('OrderDetailComponent: the header shows the real order number, not "Order N: NaN"', () => {
  it("carries the platform's own public order number through as-is, and never coerces it to NaN", async () => {
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
          planLine({
            lineNumber: 1,
            variantId: 'v1',
            quantity: 3,
            modifierOptionIds: ['m1', 'm2'],
          }),
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
      of(
        plan('UNAVAILABLE', [
          planLine({ status: 'WITHDRAWN', productId: null, unitAmountMinor: null }),
        ]),
      ),
    );

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.repeat-btn')).toBeNull();
  });

  it('shows no repeat button when the plan request fails, rather than one that would fail too', async () => {
    const { fixture, comp } = setUp(
      'o1',
      apiOrderDetail(),
      throwError(() => new Error('network')),
    );

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
    const { fixture } = setUp(
      'o1',
      apiOrderDetail(),
      of(plan('READY', [planLine()], 'someone-else')),
    );

    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.repeat-btn')).toBeNull();
  });
});

describe('OrderDetailComponent: combos and what the server added (ADR 0136)', () => {
  const COMBO = 'sel-1';

  function comboOrder(): ApiOrderDetail {
    return apiOrderDetail({
      items: [
        { name: 'Salad', quantity: 1, price: 20_000, image: null },
        {
          name: 'Burger',
          quantity: 2,
          price: 25_000,
          image: null,
          comboSelectionId: COMBO,
          comboName: 'Lunch box',
        },
        {
          name: 'Cola 0.5 L',
          quantity: 2,
          price: 3_000,
          image: null,
          comboSelectionId: COMBO,
          comboName: 'Lunch box',
        },
      ],
    });
  }

  async function render(detail: ApiOrderDetail, reorder = of(plan('READY'))) {
    const harness = setUp('o1', detail, reorder);
    harness.fixture.detectChanges();
    await harness.fixture.whenStable();
    harness.fixture.detectChanges();
    return harness;
  }

  it('shows a combo as one header with its components beneath, and an ordinary line as it always was', async () => {
    const { fixture } = await render(comboOrder());
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelectorAll('[data-testid="order-combo"]')).toHaveLength(1);
    expect(host.querySelector('[data-testid="order-combo-name"]')?.textContent?.trim()).toBe(
      'Lunch box',
    );
    const names = [...host.querySelectorAll('[data-testid="order-line"] h2')].map((h) =>
      h.textContent?.trim(),
    );
    expect(names).toEqual(['Salad', 'Burger', 'Cola 0.5 L']);
  });

  it('draws no header for an order with no combo', async () => {
    const { fixture } = await render(
      apiOrderDetail({ items: [{ name: 'Salad', quantity: 1, price: 20_000, image: null }] }),
    );

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="order-combo"]'),
    ).toBeNull();
  });

  it('itemises what the server added by itself, one row per option, each already in the total', async () => {
    const { fixture, comp } = await render(
      apiOrderDetail({
        items: [
          {
            name: 'Salad',
            quantity: 1,
            price: 22_000,
            image: null,
            autoSelectedCharges: [{ name: 'Delivery box', amountMinor: 2_000 }],
          },
          {
            name: 'Soup',
            quantity: 1,
            price: 12_000,
            image: null,
            autoSelectedCharges: [{ name: 'Delivery box', amountMinor: 2_000 }],
          },
        ],
      }),
    );
    const host = fixture.nativeElement as HTMLElement;

    expect(comp.order()?.hiddenCharges).toHaveLength(1);
    const rows = host.querySelectorAll('[data-testid="order-hidden-charge"]');
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain('Delivery box');
    expect(rows[0].textContent).toMatch(/4.000/);
    expect(host.querySelector('[data-testid="order-hidden-charges"]')?.textContent).toContain(
      'cart.hiddenCharge.title',
    );
  });

  it('shows nothing of the kind for an order the server added nothing to', async () => {
    const { fixture, comp } = await render(apiOrderDetail());

    expect(comp.order()?.hiddenCharges).toBeUndefined();
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="order-hidden-charges"]'),
    ).toBeNull();
  });

  it('repeats a combo as a combo: its container with the picks the order named', async () => {
    const picks = [
      { componentId: 'c-1', quantity: 1 },
      { componentId: 'c-2', quantity: 2 },
    ];
    const { fixture, cartAdd } = await render(
      apiOrderDetail(),
      of(
        plan('READY', [
          planLine({ variantId: 'v-lunch', quantity: 2, unitAmountMinor: null, comboPicks: picks }),
        ]),
      ),
    );

    (fixture.nativeElement.querySelector('.repeat-btn') as HTMLButtonElement).click();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(cartAdd).toHaveBeenCalledWith('v-lunch', 2, undefined, [], undefined, picks);
  });
});

describe('OrderDetailComponent: portions and weighed lines (ADR 0137)', () => {
  const NBSP = ' ';

  async function open(items: ApiOrderDetail['items']) {
    const { fixture, comp } = setUp('o1', apiOrderDetail({ items }));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const text = () =>
      ((fixture.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');
    return { fixture, comp, text, host: fixture.nativeElement as HTMLElement };
  }

  const CAKE = {
    quantumGrams: 100,
    nominalGramsPerUnit: 1_200,
    pricePerQuantumMinor: 15_000,
    provisional: true,
    actualWeightGrams: null,
  };

  it('writes a half portion with the language’s decimal mark, and a whole quantity as before', async () => {
    const { text } = await open([
      { name: 'Plov', quantity: 0.5, price: 38_000, lineAmount: 19_000 },
      { name: 'Cola', quantity: 3, price: 9_000, lineAmount: 27_000 },
    ]);

    expect(text()).toContain('0,5 physical.portionsUnit');
    expect(text()).toContain('3 common.itemsUnit');
  });

  it('shows a weighed line as an estimate, with its estimated weight, until it is weighed', async () => {
    const { host, text } = await open([
      { name: 'Medovik', quantity: 1, price: 180_000, lineAmount: 180_000, catchweight: CAKE },
    ]);

    const weight = host.querySelector('[data-testid="order-line-weight"]');
    expect(weight?.textContent).toContain('physical.estimateLine');
    expect(weight?.textContent).toContain(`1,2${NBSP}kg`);
    expect(text()).toContain('≈ 180');
  });

  it('shows what a line weighed and its own corrected amount once it is weighed', async () => {
    const { host, text } = await open([
      {
        name: 'Medovik',
        quantity: 1,
        price: 180_000,
        lineAmount: 201_000,
        catchweight: { ...CAKE, provisional: false, actualWeightGrams: 1_340 },
      },
    ]);

    const weight = host.querySelector('[data-testid="order-line-weight"]');
    expect(weight?.textContent).toContain('physical.weighedLine');
    expect(weight?.textContent).toContain(`1,34${NBSP}kg`);
    expect(text()).toContain('201');
    expect(text()).not.toContain('≈');
  });

  it('says the final weight and price are set at handover while any line is unweighed', async () => {
    const { host } = await open([
      { name: 'Medovik', quantity: 1, price: 180_000, lineAmount: 180_000, catchweight: CAKE },
    ]);

    expect(host.querySelector('[data-testid="order-final-weight-notice"]')).not.toBeNull();
  });

  it('says nothing of the kind once every weighed line is weighed, or when there is none', async () => {
    const weighed = await open([
      {
        name: 'Medovik',
        quantity: 1,
        price: 180_000,
        lineAmount: 201_000,
        catchweight: { ...CAKE, provisional: false, actualWeightGrams: 1_340 },
      },
    ]);

    expect(weighed.host.querySelector('[data-testid="order-final-weight-notice"]')).toBeNull();
  });

  it('says nothing about weight on a fixed unit', async () => {
    const { host } = await open([{ name: 'Cola', quantity: 3, price: 9_000, lineAmount: 27_000 }]);

    expect(host.querySelector('[data-testid="order-line-weight"]')).toBeNull();
    expect(host.querySelector('[data-testid="order-final-weight-notice"]')).toBeNull();
  });
});
