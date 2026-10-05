import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';

import { UiCartService } from './ui-cart.service';
import {
  CartService,
  type DeliveryCharge,
  type PlatformCart,
  type PricedCart,
} from './cart.service';
import { MenuService, type PublishedMenu } from './menu.service';
import { DeliverySelectionService } from './delivery-selection.service';
import { TranslateService } from './translate.service';
import { LangService } from './lang.service';
import { ApiClient } from '../core/api/api-client';
import { CustomerApi } from '../core/api/customer-api';
import { APP_CONFIG, type AppConfig } from '../core/config/app-config';
import { HorecaOSApiError } from '../core/api/problem-details';
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
  selectPaymentMethod = vi.fn();
  applyPromoCode = vi.fn();
  removePromoCode = vi.fn();
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
      expect.objectContaining({
        addressId: 'addr-1',
        recipientName: 'Aziz',
        recipientPhone: '+998901234567',
      }),
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
        {
          lineKey: 'v-known',
          variantId: 'v-known',
          quantity: 2,
          commentPresetCodes: [],
          hasCustomerNote: false,
        },
        {
          lineKey: 'v-gone',
          variantId: 'v-gone',
          quantity: 5,
          commentPresetCodes: [],
          hasCustomerNote: false,
        },
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
                onSaleNow: true,
                remainingQuantity: null,
              },
            ],
            modifierGroupIds: [],
            commentPresets: [],
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
      lines: [
        {
          lineKey: 'v-gone',
          variantId: 'v-gone',
          quantity: 1,
          commentPresetCodes: [],
          hasCustomerNote: false,
        },
      ],
    });
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart));
    menu.menu.mockResolvedValue(emptyMenu());

    await service.load();

    expect(service.cartData()?.items).toEqual([]);
  });

  it("row 2.1b: resolves a line's checked preset codes to the product's own offered presets for display", async () => {
    const { service, carts, menu } = setUp();
    const cart = baseCart({
      lines: [
        {
          lineKey: 'v-known',
          variantId: 'v-known',
          quantity: 1,
          commentPresetCodes: ['NO_ONIONS'],
          hasCustomerNote: false,
        },
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
                onSaleNow: true,
                remainingQuantity: null,
              },
            ],
            modifierGroupIds: [],
            commentPresets: [
              { code: 'NO_ONIONS', labelRu: 'Без лука', labelUz: 'Piyozsiz', labelEn: 'No onions' },
            ],
          },
        ],
      }),
    );

    await service.load();

    const items = service.cartData()?.items ?? [];
    expect(items[0].commentPresetCodes).toEqual(['NO_ONIONS']);
    expect(items[0].commentPresets).toEqual([
      { code: 'NO_ONIONS', labelRu: 'Без лука', labelUz: 'Piyozsiz', labelEn: 'No onions' },
    ]);
  });

  it('row 2.1b: a checked code the product no longer offers round-trips on the wire but is dropped from the resolved label list', async () => {
    const { service, carts, menu } = setUp();
    const cart = baseCart({
      lines: [
        {
          lineKey: 'v-known',
          variantId: 'v-known',
          quantity: 1,
          commentPresetCodes: ['WITHDRAWN_CODE'],
          hasCustomerNote: false,
        },
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
                onSaleNow: true,
                remainingQuantity: null,
              },
            ],
            modifierGroupIds: [],
            commentPresets: [],
          },
        ],
      }),
    );

    await service.load();

    const items = service.cartData()?.items ?? [];
    expect(items[0].commentPresetCodes).toEqual(['WITHDRAWN_CODE']);
    expect(items[0].commentPresets).toEqual([]);
  });
});

describe('UiCartService.switchFulfillmentMode', () => {
  it('row 2.1b: rebuilds every carried line with its own checked preset codes, not an empty list', async () => {
    const { service, carts, menu } = setUp();
    const existing = baseCart({
      fulfillmentMode: 'DELIVERY',
      lines: [
        {
          lineKey: 'v1',
          variantId: 'v1',
          quantity: 2,
          commentPresetCodes: ['NO_ONIONS'],
          hasCustomerNote: false,
        },
      ],
    });
    carts.cart.set(existing);
    carts.create.mockResolvedValue(baseCart({ fulfillmentMode: 'PICKUP' }));
    carts.putLine.mockResolvedValue(baseCart({ fulfillmentMode: 'PICKUP' }));
    menu.menu.mockResolvedValue(emptyMenu());

    await service.switchFulfillmentMode('PICKUP');

    expect(carts.discard).toHaveBeenCalled();
    expect(carts.putLine).toHaveBeenCalledWith({
      variantId: 'v1',
      quantity: 2,
      modifierOptionIds: [],
      commentPresetCodes: ['NO_ONIONS'],
    });
  });
});

describe('UiCartService.add', () => {
  it('row 2.1b: threads the checked preset codes through to CartService.putLine', async () => {
    const { service, carts, menu } = setUp();
    carts.ensure.mockResolvedValue(baseCart());
    carts.putLine.mockResolvedValue(baseCart());
    menu.menu.mockResolvedValue(emptyMenu());

    await service.add('v1', 1, undefined, [], ['NO_ONIONS', 'EXTRA_SPICY']);

    expect(carts.putLine).toHaveBeenCalledWith({
      variantId: 'v1',
      quantity: 1,
      customerNote: undefined,
      modifierOptionIds: [],
      commentPresetCodes: ['NO_ONIONS', 'EXTRA_SPICY'],
    });
  });

  it('omits commentPresetCodes rather than inventing an empty list, when the caller names none -- CartService.putLine itself defaults it', async () => {
    const { service, carts, menu } = setUp();
    carts.ensure.mockResolvedValue(baseCart());
    carts.putLine.mockResolvedValue(baseCart());
    menu.menu.mockResolvedValue(emptyMenu());

    await service.add('v1', 1);

    expect(carts.putLine).toHaveBeenCalledWith({
      variantId: 'v1',
      quantity: 1,
      customerNote: undefined,
      modifierOptionIds: undefined,
      commentPresetCodes: undefined,
    });
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
    commentPresetCodes: ['NO_ONIONS'],
    commentPresets: [
      { code: 'NO_ONIONS', labelRu: 'Без лука', labelUz: 'Piyozsiz', labelEn: 'No onions' },
    ],
  };

  it('resends the exact modifier selection and comment presets on a quantity change, not an empty list', async () => {
    const { service, carts } = setUp();
    carts.putLine.mockResolvedValue(baseCart());

    await service.setQuantity(item, 3);

    expect(carts.putLine).toHaveBeenCalledWith({
      variantId: 'v1',
      quantity: 3,
      modifierOptionIds: ['m1', 'm2'],
      commentPresetCodes: ['NO_ONIONS'],
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
      lines: [
        {
          lineKey: 'v-known',
          variantId: 'v-known',
          quantity: 1,
          commentPresetCodes: [],
          hasCustomerNote: false,
        },
      ],
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

  it("resolves the fee once the priced cart's delivery outcome is RESOLVED, and enables checkout", async () => {
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
      lines: [
        {
          lineKey: 'v-known',
          variantId: 'v-known',
          quantity: 1,
          commentPresetCodes: [],
          hasCustomerNote: false,
        },
      ],
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

describe('UiCartService.selectPaymentMethod (ADR 0140)', () => {
  function cartPayingBy(code: string | null): PlatformCart {
    return baseCart({
      lines: [
        {
          lineKey: 'v-known',
          variantId: 'v-known',
          quantity: 1,
          commentPresetCodes: [],
          hasCustomerNote: false,
        },
      ],
      paymentMethodCode: code,
    });
  }

  it("writes the method to the cart and re-prices, so the total is the platform's answer for that method", async () => {
    const { service, carts } = setUp();
    carts.cart.set(cartPayingBy(null));
    const selected = cartPayingBy('CLICK');
    carts.selectPaymentMethod.mockResolvedValue(selected);
    carts.price.mockResolvedValue(pricedFor(selected));

    await service.selectPaymentMethod('CLICK');

    expect(carts.selectPaymentMethod).toHaveBeenCalledWith('CLICK');
    expect(carts.price).toHaveBeenCalled();
  });

  it('writes nothing when the cart already carries the method, so it is free to repeat before every checkout', async () => {
    const { service, carts } = setUp();
    carts.cart.set(cartPayingBy('CLICK'));

    await service.selectPaymentMethod('CLICK');

    expect(carts.selectPaymentMethod).not.toHaveBeenCalled();
    expect(carts.price).not.toHaveBeenCalled();
  });

  it('writes nothing when there is no cart yet', async () => {
    const { service, carts } = setUp();

    await service.selectPaymentMethod('CLICK');

    expect(carts.selectPaymentMethod).not.toHaveBeenCalled();
  });

  it("throws the platform's refusal to the caller, which owns the screen's error", async () => {
    const { service, carts } = setUp();
    carts.cart.set(cartPayingBy(null));
    carts.selectPaymentMethod.mockRejectedValue(
      new HorecaOSApiError({
        status: 409,
        code: 'RESOURCE_CONFLICT',
        detail: 'refused',
        problem: { status: 409, code: 'RESOURCE_CONFLICT', reason: 'PAYMENT_METHOD_UNAVAILABLE' },
      }),
    );

    await expect(service.selectPaymentMethod('CLICK')).rejects.toBeInstanceOf(HorecaOSApiError);
  });
});

describe('UiCartService promotions behind the price (ADR 0140)', () => {
  async function priceWith(overrides: Partial<PricedCart>): Promise<UiCartService> {
    const { service, carts, menu } = setUp();
    const cart = baseCart({
      lines: [
        {
          lineKey: 'v-known',
          variantId: 'v-known',
          quantity: 1,
          commentPresetCodes: [],
          hasCustomerNote: false,
        },
      ],
    });
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart, overrides));
    menu.menu.mockResolvedValue(emptyMenu());
    await service.load();
    return service;
  }

  it("prints each discount as its own line with the platform's amount, an offer apart from a typed code", async () => {
    const service = await priceWith({
      discountMinor: 6_000,
      appliedPromotions: [
        { source: 'AUTOMATIC', effect: 'DISCOUNT', amountMinor: 4_000 },
        { source: 'PROMO_CODE', effect: 'DISCOUNT', amountMinor: 2_000 },
      ],
    });

    expect(service.discountRows()).toEqual([
      { labelKey: 'cart.offerDiscount', amount: fmt(4_000) },
      { labelKey: 'cart.promoCode', amount: fmt(2_000) },
    ]);
  });

  it('keeps a discount the platform did not break down as one generic line rather than a gap in the total', async () => {
    const service = await priceWith({ discountMinor: 5_000 });

    expect(service.discountRows()).toEqual([{ labelKey: 'cart.discount', amount: fmt(5_000) }]);
  });

  it('has no discount line when nothing was discounted', async () => {
    const service = await priceWith({ discountMinor: 0, appliedPromotions: [] });

    expect(service.discountRows()).toEqual([]);
  });

  it('explains a delivery offer and a surcharge as captions and never as lines', async () => {
    const service = await priceWith({
      appliedPromotions: [
        { source: 'AUTOMATIC', effect: 'DELIVERY_DISCOUNT', amountMinor: 5_000 },
        { source: 'AUTOMATIC', effect: 'SURCHARGE', amountMinor: 1_500 },
      ],
    });

    expect(service.discountRows()).toEqual([]);
    expect(service.promotionNotes()).toEqual([
      { labelKey: 'cart.deliveryOfferNote', amount: fmt(5_000) },
      { labelKey: 'cart.surchargeNote', amount: fmt(1_500) },
    ]);
  });
});

/** A cart line for `variantId`, as the platform reports it. */
function lineOf(variantId: string, quantity = 1, extra: Record<string, unknown> = {}) {
  return {
    lineKey: variantId,
    variantId,
    quantity,
    commentPresetCodes: [],
    hasCustomerNote: false,
    ...extra,
  };
}

/** A published product with one orderable variant. */
function productOf(variantId: string, name: string, amountMinor = 10_000) {
  return {
    productId: `p-${variantId}`,
    code: null,
    name,
    description: null,
    mediaAssetIds: [],
    imageUrls: [`/${variantId}.png`],
    variants: [
      {
        variantId,
        sku: null,
        unitCode: null,
        isDefault: true,
        orderable: true,
        amountMinor,
        onSaleNow: true,
        remainingQuantity: null,
      },
    ],
    modifierGroupIds: [],
    commentPresets: [],
  };
}

describe('UiCartService.applyPromoCode / removePromoCode (ADR 0072)', () => {
  function cartWith(promoCode: string | null): PlatformCart {
    return baseCart({ lines: [lineOf('v-known')], appliedPromoCode: promoCode });
  }

  it("trims the code, applies it, and re-prices so the discount is the platform's own answer", async () => {
    const { service, carts, menu } = setUp();
    const applied = cartWith('OSH2026');
    carts.applyPromoCode.mockResolvedValue(applied);
    carts.price.mockResolvedValue(pricedFor(applied, { discountMinor: 4_800 }));
    menu.menu.mockResolvedValue(emptyMenu({ products: [productOf('v-known', 'Osh')] }));

    const ok = await service.applyPromoCode('  OSH2026  ');

    expect(ok).toBe(true);
    expect(carts.applyPromoCode).toHaveBeenCalledWith('OSH2026');
    expect(carts.price).toHaveBeenCalled();
    expect(service.appliedPromoCode()).toBe('OSH2026');
    expect(service.discountFormatted()).toBe(fmt(4_800));
    expect(service.promoError()).toBeNull();
    expect(service.promoBusy()).toBe(false);
  });

  it('never calls the platform for a blank code', async () => {
    const { service, carts } = setUp();

    const ok = await service.applyPromoCode('   ');

    expect(ok).toBe(false);
    expect(carts.applyPromoCode).not.toHaveBeenCalled();
  });

  it('shows a refusal as the customer-facing sentence for its reason, never the raw code', async () => {
    const { service, carts } = setUp();
    carts.applyPromoCode.mockRejectedValue(
      new HorecaOSApiError({
        status: 409,
        code: 'RESOURCE_CONFLICT',
        detail: 'expired',
        problem: { status: 409, code: 'RESOURCE_CONFLICT', reason: 'CODE_EXPIRED' },
      }),
    );

    const ok = await service.applyPromoCode('OLDCODE');

    expect(ok).toBe(false);
    // The fake TranslateService echoes the key, so this is the *key* the message resolves to.
    expect(service.promoError()).toBe('errors.reason.codeExpired');
    expect(service.promoBusy()).toBe(false);
  });

  it('names an unknown code, which the platform answers 404', async () => {
    const { service, carts } = setUp();
    carts.applyPromoCode.mockRejectedValue(
      new HorecaOSApiError({
        status: 404,
        code: 'RESOURCE_NOT_FOUND',
        detail: 'no such code',
        problem: { status: 404, code: 'RESOURCE_NOT_FOUND', reason: 'CODE_NOT_FOUND' },
      }),
    );

    await service.applyPromoCode('NOPE');

    expect(service.promoError()).toBe('errors.reason.codeNotFound');
  });

  it('falls back to the generic sentence when it is not a platform answer', async () => {
    const { service, carts } = setUp();
    carts.applyPromoCode.mockRejectedValue(new Error('boom'));

    await service.applyPromoCode('X');

    expect(service.promoError()).toBe('errors.generic');
  });

  it('keeps what was priced before when the code is refused', async () => {
    const { service, carts, menu } = setUp();
    const before = cartWith(null);
    carts.ensure.mockResolvedValue(before);
    carts.price.mockResolvedValue(pricedFor(before, { totalMinor: 7_000 }));
    menu.menu.mockResolvedValue(emptyMenu({ products: [productOf('v-known', 'Osh')] }));
    await service.load();
    carts.applyPromoCode.mockRejectedValue(new Error('boom'));

    await service.applyPromoCode('X');

    expect(service.totalAmount()).toBe(fmt(7_000));
    expect(service.appliedPromoCode()).toBeNull();
  });

  it('removePromoCode re-prices without the code', async () => {
    const { service, carts, menu } = setUp();
    const cleared = cartWith(null);
    carts.removePromoCode.mockResolvedValue(cleared);
    carts.price.mockResolvedValue(pricedFor(cleared, { discountMinor: 0 }));
    menu.menu.mockResolvedValue(emptyMenu({ products: [productOf('v-known', 'Osh')] }));

    await service.removePromoCode();

    expect(carts.removePromoCode).toHaveBeenCalled();
    expect(service.appliedPromoCode()).toBeNull();
    expect(service.discountFormatted()).toBeNull();
  });

  it('removePromoCode: a refusal is named, not flattened to the generic sentence', async () => {
    const { service, carts } = setUp();
    carts.removePromoCode.mockRejectedValue(
      new HorecaOSApiError({
        status: 409,
        code: 'RESOURCE_CONFLICT',
        detail: 'frozen',
        problem: { status: 409, code: 'RESOURCE_CONFLICT', reason: 'CART_NOT_EDITABLE' },
      }),
    );

    await service.removePromoCode();

    expect(service.promoError()).toBe('errors.reason.cartNotEditable');
  });
});

describe('UiCartService: what became of the code on the cart (ADR 0140)', () => {
  async function priceWith(
    overrides: Partial<PricedCart>,
    promoCode: string | null,
  ): Promise<UiCartService> {
    const { service, carts, menu } = setUp();
    const cart = baseCart({ lines: [lineOf('v-known')], appliedPromoCode: promoCode });
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart, overrides));
    menu.menu.mockResolvedValue(emptyMenu({ products: [productOf('v-known', 'Osh')] }));
    await service.load();
    return service;
  }

  it("says the offers are already better, from the platform's verdict, when a code did not move the price", async () => {
    const service = await priceWith(
      {
        discountMinor: 18_000,
        appliedPromotions: [{ source: 'AUTOMATIC', effect: 'DISCOUNT', amountMinor: 18_000 }],
        promoCodeOutcome: 'OFFERS_ARE_BETTER',
      },
      'SMALL5',
    );

    expect(service.appliedPromoCode()).toBe('SMALL5');
    expect(service.promoOutcomeKey()).toBe('cart.promoOffersBetter');
  });

  it('says nothing about a code that applied', async () => {
    const service = await priceWith({ promoCodeOutcome: 'APPLIED' }, 'BIG30');

    expect(service.promoOutcomeKey()).toBeNull();
  });

  it('reports what the code itself took off, apart from an offer that applied without it', async () => {
    const service = await priceWith(
      {
        discountMinor: 6_000,
        appliedPromotions: [
          { source: 'AUTOMATIC', effect: 'DISCOUNT', amountMinor: 4_000 },
          { source: 'PROMO_CODE', effect: 'DISCOUNT', amountMinor: 2_000 },
        ],
        promoCodeOutcome: 'APPLIED',
      },
      'SAVE10',
    );

    expect(service.promoCodeDiscountFormatted()).toBe(fmt(2_000));
  });

  it('claims no saving for a code the offers beat', async () => {
    const service = await priceWith(
      {
        discountMinor: 18_000,
        appliedPromotions: [{ source: 'AUTOMATIC', effect: 'DISCOUNT', amountMinor: 18_000 }],
        promoCodeOutcome: 'OFFERS_ARE_BETTER',
      },
      'SMALL5',
    );

    expect(service.promoCodeDiscountFormatted()).toBeNull();
  });

  it('says nothing when the cart carries no code, whatever a stale verdict says', async () => {
    const service = await priceWith({ promoCodeOutcome: 'OFFERS_ARE_BETTER' }, null);

    expect(service.promoOutcomeKey()).toBeNull();
  });
});

describe('UiCartService gift offers (ADR 0140: an offer, never a line)', () => {
  const GIFT = {
    ruleId: 'rule-1',
    variantId: 'v-cola',
    quantity: 1,
    inCart: false,
    toAdd: 1,
  };

  async function offered(
    giftOffers: PricedCart['giftOffers'],
    lines = [lineOf('v-known')],
    products = [productOf('v-known', 'Osh'), productOf('v-cola', 'Cola')],
  ) {
    const fakes = setUp();
    const cart = baseCart({ lines });
    fakes.carts.ensure.mockResolvedValue(cart);
    fakes.carts.price.mockResolvedValue(pricedFor(cart, { giftOffers }));
    fakes.menu.menu.mockResolvedValue(emptyMenu({ products }));
    await fakes.service.load();
    return { ...fakes, cart };
  }

  it('offers the free gift by the name the menu gives it, and puts nothing in the basket', async () => {
    const { service, carts } = await offered([GIFT]);

    expect(service.giftOffers()).toEqual([
      {
        ruleId: 'rule-1',
        toAdd: 1,
        choices: [{ variantId: 'v-cola', name: 'Cola', image: '/v-cola.png', inCart: false }],
      },
    ]);
    expect(service.items().map((item) => item.variant_id)).toEqual(['v-known']);
    expect(carts.putLine).not.toHaveBeenCalled();
  });

  it('offers nothing when the priced cart carries no offer', async () => {
    const { service } = await offered(undefined);

    expect(service.giftOffers()).toEqual([]);
  });

  it('does not offer a gift the menu no longer carries', async () => {
    const { service } = await offered([{ ...GIFT, variantId: 'v-gone' }]);

    expect(service.giftOffers()).toEqual([]);
  });

  it('adds the gift through the normal cart call, then prices the cart again', async () => {
    const { service, carts, cart } = await offered([GIFT]);
    const withGift = baseCart({ lines: [lineOf('v-known'), lineOf('v-cola')], version: 2 });
    carts.ensure.mockResolvedValue(cart);
    carts.putLine.mockResolvedValue(withGift);
    carts.price.mockClear();
    carts.price.mockResolvedValue(
      pricedFor(withGift, {
        discountMinor: 10_000,
        appliedPromotions: [{ source: 'AUTOMATIC', effect: 'DISCOUNT', amountMinor: 10_000 }],
        giftOffers: [{ ...GIFT, inCart: true, toAdd: 0 }],
      }),
    );

    await service.addGift('v-cola');

    expect(carts.putLine).toHaveBeenCalledTimes(1);
    expect(carts.putLine.mock.calls[0][0]).toMatchObject({ variantId: 'v-cola', quantity: 1 });
    expect(carts.price).toHaveBeenCalled();
    // The platform priced it free; the offer is gone and the discount is its own figure.
    expect(service.giftOffers()).toEqual([]);
    expect(service.discountFormatted()).toBe(fmt(10_000));
  });

  it('adds only what is still missing when the cart already holds part of the allowance', async () => {
    const { service, carts } = await offered(
      [{ ...GIFT, quantity: 3, inCart: true, toAdd: 2 }],
      [lineOf('v-known'), lineOf('v-cola', 1)],
    );
    carts.putLine.mockResolvedValue(baseCart({ lines: [lineOf('v-cola', 3)] }));

    await service.addGift('v-cola');

    // One already there plus the two missing: the line is replaced by its new quantity.
    expect(carts.putLine.mock.calls[0][0]).toMatchObject({ variantId: 'v-cola', quantity: 3 });
  });

  it('keeps the comment presets of the gift line it tops up, or the write would strip them', async () => {
    const { service, carts } = await offered(
      [{ ...GIFT, quantity: 2, inCart: true, toAdd: 1 }],
      [lineOf('v-cola', 1, { commentPresetCodes: ['NO_ICE'] })],
    );
    carts.putLine.mockResolvedValue(baseCart({ lines: [lineOf('v-cola', 2)] }));

    await service.addGift('v-cola');

    expect(carts.putLine.mock.calls[0][0]).toMatchObject({
      variantId: 'v-cola',
      quantity: 2,
      commentPresetCodes: ['NO_ICE'],
    });
  });

  it('adds nothing for a variant that is not on offer', async () => {
    const { service, carts } = await offered([GIFT]);

    await service.addGift('v-known');

    expect(carts.putLine).not.toHaveBeenCalled();
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
      lines: [
        {
          lineKey: 'v-known',
          variantId: 'v-known',
          quantity: 1,
          commentPresetCodes: [],
          hasCustomerNote: false,
        },
      ],
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

describe('UiCartService: combos and what the server added (ADR 0136)', () => {
  const picks = [
    { componentId: 'c-burger', quantity: 1 },
    { componentId: 'c-cola', quantity: 2 },
  ];

  function comboMenu(): PublishedMenu {
    const component = (
      componentId: string,
      name: string,
      amountMinor: number,
      defaultQuantity = 1,
    ) => ({
      componentId,
      variantId: `${componentId}-v`,
      productId: null,
      name,
      variantName: null,
      defaultQuantity,
      sortOrder: 0,
      orderable: true,
      amountMinor,
    });
    return emptyMenu({
      products: [
        {
          productId: 'p-lunch',
          code: null,
          name: 'Lunch box',
          description: null,
          mediaAssetIds: [],
          imageUrls: [],
          variants: [
            {
              variantId: 'v-lunch',
              sku: null,
              unitCode: null,
              isDefault: true,
              orderable: true,
              amountMinor: null,
              onSaleNow: true,
              remainingQuantity: null,
            },
          ],
          modifierGroupIds: [],
          commentPresets: [],
          comboGroupIds: ['g-main'],
        },
      ],
      comboGroups: [
        {
          comboGroupId: 'g-main',
          containerVariantId: 'v-lunch',
          code: 'MAIN',
          name: 'Main',
          minimumSelections: 1,
          maximumSelections: 3,
          allowSameComponentMultipleTimes: true,
          sortOrder: 0,
          components: [
            component('c-burger', 'Burger', 25_000),
            component('c-cola', 'Cola', 3_000, 2),
          ],
        },
      ],
    });
  }

  it('shows a combo line as the container with its components, priced as the sum of what its picks cost', async () => {
    const { service, carts, menu } = setUp();
    const cart = baseCart({
      lines: [
        {
          lineKey: 'v-lunchcabc',
          variantId: 'v-lunch',
          quantity: 2,
          commentPresetCodes: [],
          hasCustomerNote: false,
          comboPicks: picks,
        },
      ],
    });
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart));
    menu.menu.mockResolvedValue(comboMenu());

    await service.load();

    const [item] = service.cartData()?.items ?? [];
    expect(item.name).toBe('Lunch box');
    // 25 000 + 2 picks of a cola at 3 000 x 2 units each = 25 000 + 12 000, per combo.
    expect(item.price).toBe(37_000);
    expect(item.comboPicks).toEqual(picks);
    expect(item.comboComponents?.map((c) => [c.name, c.quantity])).toEqual([
      ['Burger', 1],
      ['Cola', 4],
    ]);
  });

  it('puts a combo into the cart with its picks, and a plain line without any', async () => {
    const { service, carts, menu } = setUp();
    carts.ensure.mockResolvedValue(baseCart());
    carts.putLine.mockResolvedValue(baseCart());
    menu.menu.mockResolvedValue(emptyMenu());

    await service.add('v-lunch', 1, undefined, [], [], picks);

    expect(carts.putLine).toHaveBeenCalledWith(
      expect.objectContaining({ variantId: 'v-lunch', comboPicks: picks }),
    );
  });

  it('resends a combo’s picks on a quantity change, or its quantity would strip them', async () => {
    const { service, carts } = setUp();
    carts.putLine.mockResolvedValue(baseCart());
    const item: CartResponseItem = {
      variant_id: 'v-lunch',
      price: 37_000,
      item_id: 'v-lunchcabc',
      name: 'Lunch box',
      active: true,
      image: null,
      quantity: 1,
      note: null,
      modifierOptionIds: [],
      modifiers: [],
      commentPresetCodes: [],
      commentPresets: [],
      comboPicks: picks,
    };

    await service.setQuantity(item, 2);

    expect(carts.putLine).toHaveBeenCalledWith(
      expect.objectContaining({ variantId: 'v-lunch', quantity: 2, comboPicks: picks }),
    );
  });

  it('carries a combo’s picks across a change of fulfilment mode', async () => {
    const { service, carts, menu } = setUp();
    carts.cart.set(
      baseCart({
        lines: [
          {
            lineKey: 'v-lunchcabc',
            variantId: 'v-lunch',
            quantity: 1,
            commentPresetCodes: [],
            hasCustomerNote: false,
            comboPicks: picks,
          },
        ],
      }),
    );
    carts.create.mockResolvedValue(baseCart({ fulfillmentMode: 'PICKUP' }));
    carts.putLine.mockResolvedValue(baseCart({ fulfillmentMode: 'PICKUP' }));
    menu.menu.mockResolvedValue(emptyMenu());

    await service.switchFulfillmentMode('PICKUP');

    expect(carts.putLine).toHaveBeenCalledWith(
      expect.objectContaining({ variantId: 'v-lunch', comboPicks: picks }),
    );
  });

  it('itemises what the server added by itself, named from the menu, one row per option with the lines summed', async () => {
    const { service, carts, menu } = setUp();
    const cart = baseCart({
      lines: [
        {
          lineKey: 'v-1',
          variantId: 'v-1',
          quantity: 1,
          commentPresetCodes: [],
          hasCustomerNote: false,
        },
      ],
    });
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(
      pricedFor(cart, {
        hiddenCharges: [
          { lineKey: 'v-1', optionId: 'o-box', amountMinor: 2_000 },
          { lineKey: 'v-2', optionId: 'o-box', amountMinor: 2_000 },
        ],
      }),
    );
    menu.menu.mockResolvedValue(
      emptyMenu({
        products: [
          {
            productId: 'p-1',
            code: null,
            name: 'Salad',
            description: null,
            mediaAssetIds: [],
            imageUrls: [],
            variants: [
              {
                variantId: 'v-1',
                sku: null,
                unitCode: null,
                isDefault: true,
                orderable: true,
                amountMinor: 20_000,
                onSaleNow: true,
                remainingQuantity: null,
              },
            ],
            modifierGroupIds: [],
            commentPresets: [],
          },
        ],
        modifierGroups: [
          {
            modifierGroupId: 'g-box',
            code: 'BOX',
            name: 'Box',
            required: true,
            minimumSelections: 1,
            maximumSelections: 1,
            allowSameOptionMultipleTimes: false,
            options: [
              {
                optionId: 'o-box',
                code: 'BOX',
                maximumQuantity: 1,
                amountMinor: 2_000,
                name: 'Delivery box',
              },
            ],
          },
        ],
      }),
    );

    await service.load();

    expect(service.hiddenCharges()).toEqual([
      { optionId: 'o-box', label: 'Delivery box', amountMinor: 4_000, amount: fmt(4_000) },
    ]);
  });

  it('shows nothing of the kind for a cart the server added nothing to', async () => {
    const { service, carts } = setUp();
    const cart = baseCart({
      lines: [
        {
          lineKey: 'v-1',
          variantId: 'v-1',
          quantity: 1,
          commentPresetCodes: [],
          hasCustomerNote: false,
        },
      ],
    });
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(pricedFor(cart, { hiddenCharges: [] }));

    await service.load();

    expect(service.hiddenCharges()).toEqual([]);
  });

  it('names a charge whose option the menu does not carry with a neutral label rather than an id', async () => {
    const { service, carts } = setUp();
    const cart = baseCart({
      lines: [
        {
          lineKey: 'v-1',
          variantId: 'v-1',
          quantity: 1,
          commentPresetCodes: [],
          hasCustomerNote: false,
        },
      ],
    });
    carts.ensure.mockResolvedValue(cart);
    carts.price.mockResolvedValue(
      pricedFor(cart, {
        hiddenCharges: [{ lineKey: 'v-1', optionId: 'o-unknown', amountMinor: 0 }],
      }),
    );

    await service.load();

    expect(service.hiddenCharges()[0].label).toBe('cart.hiddenCharge.fallbackLabel');
    expect(service.hiddenCharges()[0].amountMinor).toBe(0);
  });
});

describe('UiCartService with portions and weighed items (ADR 0137)', () => {
  function variant(
    variantId: string,
    amountMinor: number,
    physical: PublishedMenu['products'][number]['variants'][number]['physical'],
  ): PublishedMenu['products'][number]['variants'][number] {
    return {
      variantId,
      sku: null,
      unitCode: null,
      isDefault: true,
      orderable: true,
      amountMinor,
      onSaleNow: true,
      remainingQuantity: null,
      physical,
    };
  }

  function product(
    productId: string,
    name: string,
    variants: PublishedMenu['products'][number]['variants'],
  ): PublishedMenu['products'][number] {
    return {
      productId,
      code: null,
      name,
      description: null,
      mediaAssetIds: [],
      imageUrls: [],
      variants,
      modifierGroupIds: [],
      commentPresets: [],
    };
  }

  const PLOV = variant('v-plov', 38_000, {
    catchweight: false,
    splittable: true,
    portionSize: 0.5,
  });
  const CAKE = variant('v-cake', 15_000, {
    catchweight: true,
    catchweightQuantumGrams: 100,
    catchweightNominalGrams: 1_200,
    splittable: false,
  });
  const COLA = variant('v-cola', 9_000, null);

  async function loaded(quantities: Record<string, number>) {
    const fakes = setUp();
    const cart = baseCart({
      lines: Object.entries(quantities).map(([variantId, quantity]) => ({
        lineKey: variantId,
        variantId,
        quantity,
        commentPresetCodes: [],
        hasCustomerNote: false,
      })),
    });
    fakes.carts.cart.set(cart);
    fakes.carts.ensure.mockResolvedValue(cart);
    fakes.carts.price.mockResolvedValue(pricedFor(cart));
    fakes.carts.putLine.mockResolvedValue(cart);
    fakes.carts.removeLine.mockResolvedValue(cart);
    fakes.menu.menu.mockResolvedValue(
      emptyMenu({
        products: [
          product('p-plov', 'Plov', [PLOV]),
          product('p-cake', 'Medovik', [CAKE]),
          product('p-cola', 'Cola', [COLA]),
        ],
      }),
    );
    await fakes.service.load();
    return fakes;
  }

  function itemOf(service: UiCartService, variantId: string): CartResponseItem {
    return service.items().find((item) => item.variant_id === variantId) as CartResponseItem;
  }

  it('carries each variant’s physical facts onto its cart line', async () => {
    const { service } = await loaded({ 'v-plov': 0.5, 'v-cola': 2 });

    expect(itemOf(service, 'v-plov').physical?.portionSize).toBe(0.5);
    expect(itemOf(service, 'v-cola').physical ?? null).toBeNull();
  });

  it('moves a splittable line by its portion size, and a plain one by one', async () => {
    const { service, carts } = await loaded({ 'v-plov': 1, 'v-cola': 2 });

    await service.setQuantity(itemOf(service, 'v-plov'), 1.5);
    service.increaseQuantity(itemOf(service, 'v-plov'));
    service.decreaseQuantity(itemOf(service, 'v-cola'));
    await Promise.resolve();

    const quantities = carts.putLine.mock.calls.map((call) => [
      call[0].variantId,
      call[0].quantity,
    ]);
    expect(quantities).toContainEqual(['v-plov', 1.5]);
    expect(quantities).toContainEqual(['v-plov', 1.5]);
    expect(quantities).toContainEqual(['v-cola', 1]);
  });

  it('a half portion is stepped down to nothing, which removes the line rather than writing zero', async () => {
    const { service, carts } = await loaded({ 'v-plov': 0.5 });

    service.decreaseQuantity(itemOf(service, 'v-plov'));
    await Promise.resolve();

    expect(carts.removeLine).toHaveBeenCalledWith('v-plov');
    expect(carts.putLine).not.toHaveBeenCalled();
  });

  it('does not let floating-point noise reach the platform: 0.2 + 0.1 is written 0.3', async () => {
    const { service, carts } = await loaded({ 'v-plov': 0.2 });
    const item = {
      ...itemOf(service, 'v-plov'),
      physical: { catchweight: false, splittable: true, portionSize: 0.1 },
    };

    service.increaseQuantity(item);
    await Promise.resolve();

    expect(carts.putLine.mock.calls[0][0].quantity).toBe(0.3);
  });

  it('prices a portion at its share of the price, and a weighed line at its estimated weight', async () => {
    const { service } = await loaded({ 'v-plov': 0.5, 'v-cake': 2, 'v-cola': 3 });

    expect(service.lineAmount(itemOf(service, 'v-plov'))).toBe(19_000);
    expect(service.lineAmount(itemOf(service, 'v-cake'))).toBe(360_000);
    expect(service.lineAmount(itemOf(service, 'v-cola'))).toBe(27_000);
  });

  it('counts a half portion as one plate in the basket badge, not as half of one', async () => {
    const { service } = await loaded({ 'v-plov': 0.5, 'v-cola': 2 });

    expect(service.totalItemsCount()).toBe(3);
  });

  it('knows when the basket holds something sold by weight, so its total is an estimate', async () => {
    expect((await loaded({ 'v-cola': 2, 'v-cake': 1 })).service.hasProvisionalLines()).toBe(true);
  });

  it('does not call a basket of fixed units an estimate', async () => {
    expect((await loaded({ 'v-cola': 2 })).service.hasProvisionalLines()).toBe(false);
  });
});
