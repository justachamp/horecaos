import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';

import { UiCartService } from './ui-cart.service';
import { CartService, type DeliveryCharge, type PlatformCart, type PricedCart } from './cart.service';
import { MenuService, type PublishedMenu } from './menu.service';
import { DeliverySelectionService } from './delivery-selection.service';
import { TranslateService } from './translate.service';
import { LangService } from './lang.service';
import { ApiClient } from '../core/api/api-client';
import { CustomerApi } from '../core/api/customer-api';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';
import type { CartResponseItem } from '../types/cart.types';

const CONFIG: AppConfig = {
  apiBaseUrl: '/api/v1',
  tenantId: '10000000-0000-0000-0000-000000000001',
  brandId: '10000000-0000-0000-0000-000000000002',
  defaultLocationId: '10000000-0000-0000-0000-000000000003',
  channel: 'STOREFRONT',
  yandexMapsApiKey: '',
  brand: { displayName: 'Test Brand', theme: { accent: '#000000', accentDeep: '#000000' } },
};

class FakeCartService {
  readonly cart = signal<PlatformCart | null>(null);
  ensure = vi.fn();
  create = vi.fn();
  putLine = vi.fn();
  removeLine = vi.fn();
  clear = vi.fn();
  price = vi.fn();
  setDestination = vi.fn();
  paymentMethods = vi.fn();
  checkout = vi.fn();
  discard = vi.fn();
}

class FakeMenuService {
  menu = vi.fn();
}

class FakeDeliverySelectionService {
  addressId = vi.fn<() => string | null>(() => null);
  isComplete = vi.fn<() => boolean>(() => false);
  recipientName = vi.fn<() => string>(() => '');
  recipientPhone = vi.fn<() => string>(() => '');
}

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string, params?: Record<string, string | number>): string {
    return params ? `${key}:${JSON.stringify(params)}` : key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

class FakeLangService {
  langId = () => 'uz';
}

class FakeCustomerApi {
  address = vi.fn();
}

class FakeApiClient {
  get = vi.fn();
  mutate = vi.fn();
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

function baseCart(overrides: Partial<PlatformCart> = {}): PlatformCart {
  return {
    cartId: 'cart-1',
    locationId: 'loc-1',
    status: 'OPEN',
    currency: 'UZS',
    fulfillmentMode: 'DELIVERY',
    version: 1,
    quoteId: null,
    contextHash: null,
    expiresAt: null,
    lines: [],
    ...overrides,
  };
}

function pricedFor(cart: PlatformCart, overrides: Partial<PricedCart> = {}): PricedCart {
  return {
    cartId: cart.cartId,
    cartVersion: cart.version,
    quoteId: 'quote-1',
    contextHash: 'hash-1',
    currency: cart.currency,
    subtotalMinor: 1000,
    taxMinor: 0,
    discountMinor: 0,
    feeMinor: 0,
    totalMinor: 1000,
    expiresAt: new Date().toISOString(),
    delivery: null,
    ...overrides,
  };
}

/** Mirrors `UiCartService.formatPrice` under `FakeTranslateService`, whose
 * `get('common.currency')` returns the key itself rather than "so'm". */
function fmt(minor: number): string {
  return `${minor.toLocaleString('uz-UZ')} common.currency`;
}

function deliveryCharge(overrides: Partial<DeliveryCharge> = {}): DeliveryCharge {
  return {
    feeMinor: 12_000,
    outcome: 'RESOLVED',
    reasonCode: 'RESOLVED',
    minBasketMinor: null,
    freeDeliveryFromMinor: null,
    ...overrides,
  };
}

interface Fakes {
  service: UiCartService;
  carts: FakeCartService;
  menu: FakeMenuService;
  delivery: FakeDeliverySelectionService;
  customerApi: FakeCustomerApi;
  api: FakeApiClient;
}

function setUp(): Fakes {
  const carts = new FakeCartService();
  const menu = new FakeMenuService();
  const delivery = new FakeDeliverySelectionService();
  const customerApi = new FakeCustomerApi();
  const api = new FakeApiClient();

  menu.menu.mockResolvedValue(emptyMenu());
  carts.price.mockResolvedValue(undefined);

  TestBed.configureTestingModule({
    providers: [
      { provide: CartService, useValue: carts },
      { provide: MenuService, useValue: menu },
      { provide: DeliverySelectionService, useValue: delivery },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: LangService, useClass: FakeLangService },
      { provide: CustomerApi, useValue: customerApi },
      { provide: ApiClient, useValue: api },
      { provide: APP_CONFIG, useValue: CONFIG },
    ],
  });

  return { service: TestBed.inject(UiCartService), carts, menu, delivery, customerApi, api };
}

describe('UiCartService.applyDestination', () => {
  it('short-circuits true for PICKUP without reading the delivery selection or writing a destination', async () => {
    const { service, carts, delivery } = setUp();
    carts.cart.set(baseCart({ fulfillmentMode: 'PICKUP' }));

    const result = await service.applyDestination();

    expect(result).toBe(true);
    expect(delivery.addressId).not.toHaveBeenCalled();
    expect(carts.setDestination).not.toHaveBeenCalled();
  });

  it('for DELIVERY, returns false and does not write when no address has been chosen', async () => {
    const { service, carts, delivery } = setUp();
    // No cart yet -- the DELIVERY default applies.
    delivery.addressId.mockReturnValue(null);

    const result = await service.applyDestination();

    expect(result).toBe(false);
    expect(carts.setDestination).not.toHaveBeenCalled();
  });

  it('for DELIVERY with a complete selection, writes the destination and returns true', async () => {
    const { service, carts, delivery } = setUp();
    // No cart yet -- the DELIVERY default applies.
    delivery.addressId.mockReturnValue('addr-1');
    delivery.isComplete.mockReturnValue(true);
    delivery.recipientName.mockReturnValue('Aziz');
    delivery.recipientPhone.mockReturnValue('+998901234567');
    carts.setDestination.mockResolvedValue(baseCart());

    const result = await service.applyDestination();

    expect(result).toBe(true);
    expect(carts.setDestination).toHaveBeenCalledWith(
      expect.objectContaining({ addressId: 'addr-1', recipientName: 'Aziz', recipientPhone: '+998901234567' }),
    );
  });

  it('does not re-PUT the destination on a retry when nothing about it changed, keeping the cart version and quote intact', async () => {
    // Setting a destination always bumps the cart's version and clears its
    // quote (ADR 0037), so a checkout retry that put the identical thing
    // back would spend that bump for nothing and force a fresh price on a
    // cart whose destination never actually moved -- see CartConfirmationComponent's
    // idempotency-key doc for why that divergence matters.
    const { service, carts, delivery } = setUp();
    const cart = baseCart();
    carts.cart.set(cart);
    delivery.addressId.mockReturnValue('addr-1');
    delivery.isComplete.mockReturnValue(true);
    delivery.recipientName.mockReturnValue('Aziz');
    delivery.recipientPhone.mockReturnValue('+998901234567');
    carts.setDestination.mockResolvedValue(cart);

    const first = await service.applyDestination();
    const second = await service.applyDestination();

    expect(first).toBe(true);
    expect(second).toBe(true);
    expect(carts.setDestination).toHaveBeenCalledTimes(1);
  });

  it('re-PUTs the destination when the recipient changes, even against the same cart and address', async () => {
    const { service, carts, delivery } = setUp();
    const cart = baseCart();
    carts.cart.set(cart);
    delivery.addressId.mockReturnValue('addr-1');
    delivery.isComplete.mockReturnValue(true);
    delivery.recipientName.mockReturnValue('Aziz');
    delivery.recipientPhone.mockReturnValue('+998901234567');
    carts.setDestination.mockResolvedValue(cart);

    await service.applyDestination();
    delivery.recipientName.mockReturnValue('Dilnoza');
    await service.applyDestination();

    expect(carts.setDestination).toHaveBeenCalledTimes(2);
  });
});

describe('UiCartService project() (via load())', () => {
  it('drops a line whose variant has left the menu, and excludes it from the item count', async () => {
    const { service, carts, menu } = setUp();
    const cart = baseCart({
      lines: [
        { lineKey: 'v-known', variantId: 'v-known', quantity: 2, hasCustomerNote: false },
        { lineKey: 'v-gone', variantId: 'v-gone', quantity: 5, hasCustomerNote: false },
      ],
    });
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart));
    menu.menu.mockResolvedValue(
      emptyMenu({
        products: [
          {
            productId: 'p-known',
            code: null,
            name: 'Osh',
            description: null,
            mediaAssetIds: [],
            imageUrls: [],
            variants: [
              {
                variantId: 'v-known',
                sku: null,
                unitCode: null,
                isDefault: true,
                orderable: true,
                amountMinor: 25_000,
              },
            ],
            modifierGroupIds: [],
          },
        ],
      }),
    );

    await service.load();

    const items = service.cartData()?.items ?? [];
    expect(items).toHaveLength(1);
    expect(items[0].item_id).toBe('v-known');
    expect(items[0].name).toBe('Osh');
    // Only the surviving line's quantity counts -- the dropped line
    // contributes nothing, it is not just hidden from the list.
    expect(service.totalItemsCount()).toBe(2);
  });

  it('shows an empty basket, not an error, when every line has left the menu', async () => {
    const { service, carts, menu } = setUp();
    const cart = baseCart({
      lines: [{ lineKey: 'v-gone', variantId: 'v-gone', quantity: 1, hasCustomerNote: false }],
    });
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart));
    menu.menu.mockResolvedValue(emptyMenu());

    await service.load();

    expect(service.cartData()?.items).toEqual([]);
  });
});

describe('UiCartService.setQuantity', () => {
  const item: CartResponseItem = {
    variant_id: 'v1',
    price: 5000,
    item_id: 'v1+m1.m2',
    name: 'Osh',
    active: true,
    image: null,
    quantity: 2,
    note: null,
    modifierOptionIds: ['m1', 'm2'],
    modifiers: [],
  };

  it('resends the exact modifier selection on a quantity change, not an empty list', async () => {
    const { service, carts } = setUp();
    carts.putLine.mockResolvedValue(baseCart());

    await service.setQuantity(item, 3);

    expect(carts.putLine).toHaveBeenCalledWith({
      variantId: 'v1',
      quantity: 3,
      modifierOptionIds: ['m1', 'm2'],
    });
    expect(carts.removeLine).not.toHaveBeenCalled();
  });

  it('removes the line instead of writing a zero or negative quantity', async () => {
    const { service, carts } = setUp();
    carts.removeLine.mockResolvedValue(baseCart());

    await service.setQuantity(item, 0);

    expect(carts.removeLine).toHaveBeenCalledWith('v1+m1.m2');
    expect(carts.putLine).not.toHaveBeenCalled();
  });
});

describe('UiCartService delivery charge (from the priced cart, never a coordinate call)', () => {
  function deliveryCart(): PlatformCart {
    return baseCart({
      lines: [{ lineKey: 'v-known', variantId: 'v-known', quantity: 1, hasCustomerNote: false }],
    });
  }

  it('never calls a coordinate-bearing delivery-fee endpoint -- the fee comes from POST /pricing alone', async () => {
    const { service, carts, menu, api } = setUp();
    const cart = deliveryCart();
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(
      pricedFor(cart, { delivery: deliveryCharge({ feeMinor: 12_000, outcome: 'RESOLVED' }) }),
    );
    menu.menu.mockResolvedValue(emptyMenu());

    await service.load();

    // No `.../delivery-fee` read at all -- the customer's coordinates never
    // leave this client for a preview, and the fee below came entirely from
    // the priced cart `carts.price()` already returned.
    expect(api.get).not.toHaveBeenCalled();
    expect(service.deliveryFee()).toBe(fmt(12_000));
  });

  it('resolves the fee once the priced cart\'s delivery outcome is RESOLVED, and enables checkout', async () => {
    const { service, carts, menu } = setUp();
    const cart = deliveryCart();
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(
      pricedFor(cart, { delivery: deliveryCharge({ feeMinor: 8_000, outcome: 'RESOLVED' }) }),
    );
    menu.menu.mockResolvedValue(emptyMenu());

    await service.load();

    expect(service.deliveryFee()).toBe(fmt(8_000));
    expect(service.deliveryFeeResolved()).toBe(true);
    expect(service.deliveryUnresolvedMessage()).toBeNull();
    expect(service.canPlaceOrder()).toBe(true);
  });

  it('treats EXTERNALLY_PRICED the same as RESOLVED -- both are fees checkout will accept', async () => {
    const { service, carts, menu } = setUp();
    const cart = deliveryCart();
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(
      pricedFor(cart, { delivery: deliveryCharge({ feeMinor: 0, outcome: 'EXTERNALLY_PRICED' }) }),
    );
    menu.menu.mockResolvedValue(emptyMenu());

    await service.load();

    expect(service.deliveryFeeResolved()).toBe(true);
    expect(service.canPlaceOrder()).toBe(true);
  });

  it('shows a dash and the specific reason, and blocks checkout, when the address is out of zone', async () => {
    const { service, carts, menu } = setUp();
    const cart = deliveryCart();
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(
      pricedFor(cart, {
        delivery: deliveryCharge({ feeMinor: 0, outcome: 'UNRESOLVED', reasonCode: 'OUT_OF_ZONE' }),
      }),
    );
    menu.menu.mockResolvedValue(emptyMenu());

    await service.load();

    // Never a zero fee, which would read as free delivery.
    expect(service.deliveryFee()).toBe('—');
    expect(service.deliveryFeeResolved()).toBe(false);
    expect(service.deliveryUnresolvedMessage()).toBe('errors.reason.outOfZone');
    expect(service.canPlaceOrder()).toBe(false);
  });

  it('interpolates the minimum basket amount when the shortfall is the reason', async () => {
    const { service, carts, menu } = setUp();
    const cart = deliveryCart();
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(
      pricedFor(cart, {
        delivery: deliveryCharge({
          feeMinor: 0,
          outcome: 'UNRESOLVED',
          reasonCode: 'BELOW_MINIMUM_BASKET',
          minBasketMinor: 100_000,
        }),
      }),
    );
    menu.menu.mockResolvedValue(emptyMenu());

    await service.load();

    expect(service.deliveryUnresolvedMessage()).toBe(
      `errors.reason.minimumBasketAmount:{"amount":"${fmt(100_000)}"}`,
    );
    expect(service.canPlaceOrder()).toBe(false);
  });

  it('asks for an address, and blocks checkout, when no destination has been chosen yet', async () => {
    const { service, carts, menu } = setUp();
    const cart = deliveryCart();
    carts.ensure.mockResolvedValue(cart);
    // No destination set yet: the priced cart's own `delivery` block is
    // absent (`DeliveryChargeResponse.of` returns null when
    // `deliveryOutcome` was never attempted), not an error.
    carts.price.mockResolvedValue(pricedFor(cart, { delivery: null }));
    menu.menu.mockResolvedValue(emptyMenu());

    await service.load();

    expect(service.deliveryFee()).toBe('—');
    expect(service.deliveryUnresolvedMessage()).toBe('cart.deliveryChooseAddress');
    expect(service.canPlaceOrder()).toBe(false);
  });

  it('is always resolvable for a non-delivery cart -- there is no fee to block checkout on', async () => {
    const { service, carts, menu } = setUp();
    const cart = baseCart({
      fulfillmentMode: 'PICKUP',
      lines: [{ lineKey: 'v-known', variantId: 'v-known', quantity: 1, hasCustomerNote: false }],
    });
    // Mirrors the real CartService.ensure, which sets its own `cart` signal
    // as a side effect -- fulfillmentMode now reads the cart's own fact
    // rather than a value a test pokes in directly (see the describe block
    // below this one).
    carts.cart.set(cart);
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart, { delivery: null }));
    menu.menu.mockResolvedValue(emptyMenu());

    await service.load();

    expect(service.deliveryFee()).toBe('—');
    expect(service.deliveryUnresolvedMessage()).toBeNull();
    expect(service.canPlaceOrder()).toBe(true);
  });
});

describe('UiCartService.fulfillmentMode reflects the loaded cart, not a stale default', () => {
  // A reload-style construction -- a fresh injection with nothing else having
  // called `switchFulfillmentMode` first, exactly what a full page load of
  // /cart or /cart/confirmation does -- has no other way to learn the mode a
  // PICKUP cart is actually in. Before this, `fulfillmentMode` was a plain
  // signal defaulting to 'DELIVERY' and set only by `switchFulfillmentMode`,
  // so `load()` never told it what the cart it just fetched actually was.
  it('a PICKUP cart loaded fresh reports PICKUP, not the DELIVERY default -- no delivery line, order button enabled', async () => {
    const { service, carts, menu } = setUp();
    const cart = baseCart({
      fulfillmentMode: 'PICKUP',
      lines: [{ lineKey: 'v-known', variantId: 'v-known', quantity: 1, hasCustomerNote: false }],
    });
    // Mirrors the real CartService.ensure, which sets its own `cart` signal
    // as a side effect of resolving -- the fake normally leaves it null
    // unless a test does this itself.
    carts.ensure.mockImplementation(async () => {
      carts.cart.set(cart);
      return cart;
    });
    carts.price.mockResolvedValue(pricedFor(cart, { delivery: null }));
    menu.menu.mockResolvedValue(emptyMenu());

    await service.load();

    expect(service.fulfillmentMode()).toBe('PICKUP');
    expect(service.deliveryFee()).toBe('—');
    expect(service.canPlaceOrder()).toBe(true);
  });
});
