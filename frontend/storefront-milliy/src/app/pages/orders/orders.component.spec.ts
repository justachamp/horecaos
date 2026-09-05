import { TestBed } from '@angular/core/testing';
import { Component } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';

import { OrdersComponent } from './orders.component';
import { OrdersService, type ApiOrder, type ApiOrderDetail } from '../../services/orders.service';
import { MenuService, type PublishedMenu } from '../../services/menu.service';
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

function emptyMenu(overrides: Partial<PublishedMenu> = {}): PublishedMenu {
  return {
    publicationId: 'pub-1',
    locale: 'uz',
    currency: 'UZS',
    categories: [],
    products: [],
    modifierGroups: [],
    ...overrides,
  };
}

class FakeOrdersService {
  getOrders = vi.fn(() => of<ApiOrder[]>([]));
  getOrderDetail = vi.fn(() =>
    of<ApiOrderDetail>({ id: 'order-1', items: [] } as unknown as ApiOrderDetail),
  );
}

class FakeMenuService {
  menu = vi.fn(async () => emptyMenu());
}

class FakeLangService {
  langId = () => 'uz';
}

class FakeUiCartService {
  add = vi.fn(async () => {});
  formatPrice = (value: number) => `${value} so'm`;
}

async function setUp(
  configure: (orders: FakeOrdersService, menu: FakeMenuService) => void = () => {},
) {
  const orders = new FakeOrdersService();
  const menu = new FakeMenuService();
  configure(orders, menu);

  TestBed.configureTestingModule({
    imports: [OrdersComponent],
    providers: [
      provideRouter([
        { path: 'home', component: TestTargetComponent },
        { path: 'cart', component: TestTargetComponent },
      ]),
      { provide: OrdersService, useValue: orders },
      { provide: MenuService, useValue: menu },
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
    menu,
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

describe('OrdersComponent.repeat -- best-effort, name-matched re-add', () => {
  it('adds the matched product\'s orderable variant with the original quantity, and lands on /cart', async () => {
    const { fixture, orders, menu, cart, router } = await setUp((orders) => {
      orders.getOrders.mockReturnValue(of([order({ id: 'r1', status: { id: 'COMPLETED', name: 'COMPLETED' } })]));
    });
    orders.getOrderDetail.mockReturnValue(
      of({ id: 'r1', items: [{ name: 'Osh', quantity: 3, price: 25_000 }] } as unknown as ApiOrderDetail),
    );
    menu.menu.mockResolvedValue(
      emptyMenu({
        products: [
          {
            productId: 'p1',
            code: null,
            name: 'Osh',
            description: null,
            mediaAssetIds: [],
            imageUrls: [],
            variants: [
              { variantId: 'v1', sku: null, unitCode: null, isDefault: true, orderable: true, amountMinor: 25_000 },
            ],
            modifierGroupIds: [],
          },
        ],
      }),
    );
    const navigateSpy = vi.spyOn(router, 'navigate');

    (fixture.nativeElement.querySelector('.repeat-btn') as HTMLButtonElement).click();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(cart.add).toHaveBeenCalledWith('v1', 3);
    expect(navigateSpy).toHaveBeenCalledWith(['/cart']);
  });

  it('reports failure and never touches the cart when nothing on the order matches the current menu', async () => {
    const { fixture, orders, menu, cart, router } = await setUp((orders) => {
      orders.getOrders.mockReturnValue(of([order({ id: 'r1', status: { id: 'COMPLETED', name: 'COMPLETED' } })]));
    });
    orders.getOrderDetail.mockReturnValue(
      of({ id: 'r1', items: [{ name: 'Discontinued dish', quantity: 1, price: 1_000 }] } as unknown as ApiOrderDetail),
    );
    menu.menu.mockResolvedValue(emptyMenu());
    const navigateSpy = vi.spyOn(router, 'navigate');

    (fixture.nativeElement.querySelector('.repeat-btn') as HTMLButtonElement).click();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();

    expect(cart.add).not.toHaveBeenCalled();
    expect(navigateSpy).not.toHaveBeenCalledWith(['/cart']);
    expect(fixture.nativeElement.textContent).toContain('orders.repeatFailed');
  });
});
